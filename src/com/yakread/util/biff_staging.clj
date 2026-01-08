(ns com.yakread.util.biff-staging
  (:require
   [aero.core :as aero]
   [buddy.core.mac :as mac]
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.find :as ns-find]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.wsscode.pathom3.connect.runner :as-alias pcr]
   [com.yakread.lib.core :as lib.core]
   [malli.core :as malli]
   [malli.registry :as malr] ;;[xtdb.api :as xt]
))

(defn doc-asts [{:keys [registry] :as malli-opts}]
  (for [schema-k (keys (malr/schemas (:registry malli-opts)))
        :let [schema (try (malli/deref-recursive schema-k malli-opts) (catch Exception _))]
        :when schema
        :let [schemas (volatile! [])
              _ (malli/walk schema (fn [schema _ _ _]
                                     (vswap! schemas conj schema)))]
        schema @schemas
        :let [ast (malli/ast schema)]
        :when (and ast
                   (= (:type ast) :map)
                   (contains? (:keys ast) :xt/id))]
    (assoc-in ast [:properties :schema] schema-k)))

(defn- attr-union [m1 m2]
  (let [shared-keys (into [] (filter #(contains? m2 %)) (keys m1))]
    (when-some [conflicting-attr (first (filter #(not= (m1 %) (m2 %)) shared-keys))]
      (throw (ex-info "An attribute has a conflicting definition"
                      {:attr conflicting-attr
                       :definition-1 (m1 conflicting-attr)
                       :definition-2 (m2 conflicting-attr)})))
    (merge m1 m2)))

(defn table-ast? [ast]
  (and (= :map (:type ast))
       (contains? (:keys ast) :xt/id)))

(defn deref-ast [schema malli-opts]
  (some-> (biff/catchall (malli/deref-recursive schema malli-opts))
          malli/ast))

(defn table-asts [schema malli-opts]
  (->> (deref-ast schema malli-opts)
       (tree-seq (constantly true) :children)
       (filterv table-ast?)))

;; rename to column info, or refactor to be more general
(defn schema-info [malli-opts]
  (into {}
        (keep (fn [schema-k]
                (let [attrs (->> (table-asts schema-k malli-opts)
                                 (mapv :keys)
                                 (reduce attr-union {}))]
                  (when (not-empty attrs)
                    [schema-k attrs]))))
        (keys (malr/schemas (:registry malli-opts)))))

(def table-properties
  (memoize
   (fn [table malli-opts]
     (->> (table-asts table malli-opts)
          ;; TODO maybe check that certain properties are same for all asts
          first
          :properties))))

(defn field-asts [malli-opts]
  (apply merge (vals (schema-info malli-opts))))

(defn- expects [env]
  (-> env
      ::pcp/node
      ::pcp/expects
      keys
      vec))

;; TODO see if we can infer this more intelligently
(def schema-whitelist
  #{:feed
    :ad-credit
    :bulk-send
    :mv-sub
    :user-item
    :sub
    :digest-item
    :item
    :mv-user
    :digest
    :reclist
    :ad-click
    :deleted-user
    :redirect
    :ad
    :user
    :skip})

(defn xtdb2-resolvers [malli-opts]
  ;; TODO maybe add reverse resolvers too
  (for [[schema attrs] (schema-info malli-opts)
        :when (contains? schema-whitelist schema)
        :let [ref? (fn [attr]
                     (boolean (get-in attrs [attr :properties :biff/ref])))
              joinify (fn [[k v]]
                        (if (ref? k)
                          [k {:xt/id v}]
                          [k v]))
              joinify-map (fn [m]
                            (into {} (map joinify) m))]
        :when (not (qualified-keyword? schema))
        :let [op-name (symbol "com.yakread.util.biff-staging"
                              (str (name schema) "-xtdb2-resolver"))]]
    (pco/resolver op-name
                  {::pco/input [:xt/id]
                   ::pco/output (vec (for [k (keys attrs)
                                           :when (not= k :xt/id)]
                                       (if (ref? k)
                                         {k [:xt/id]}
                                         k)))
                   ::pco/batch? true
                   ::pco/cache-key (fn [env input]
                                     [op-name input (expects env)])}
                  (fn [{:keys [biff/conn ::pcr/resolver-cache*] :as env} inputs]
                    ;; TODO
                    ;; - use a fixed db snapshot
                    (let [resolver-cache* (when (or (volatile? resolver-cache*)
                                                    (= clojure.lang.Atom (type resolver-cache*)))
                                            resolver-cache*)
                          cache-value (some-> resolver-cache* deref)
                          columns (filterv attrs (expects env))
                          results (mapv (fn [{:keys [xt/id] :as input}]
                                          (merge input
                                                 (get-in cache-value [::cache schema id])))
                                        inputs)
                          missing-columns (into #{}
                                                (mapcat (fn [entity]
                                                          (into []
                                                                (remove #(contains? entity %))
                                                                columns)))
                                                results)
                          incomplete-inputs (filterv (fn [input]
                                                       (some #(not (contains? input %))
                                                             missing-columns))
                                                     inputs)
                          query-results (when (not-empty incomplete-inputs)
                                          (biffx/q conn
                                                   {:select (vec (conj missing-columns :xt/id))
                                                    :from schema
                                                    :where [:in :xt/id (mapv :xt/id incomplete-inputs)]}))
                          nil-map (zipmap missing-columns (repeat nil))
                          update-cache (fn [cache-value]
                                         (reduce (fn [cache-value record]
                                                   (update-in cache-value
                                                              [::cache schema (:xt/id record)]
                                                              #(merge nil-map % record)))
                                                 cache-value
                                                 query-results))
                          cache-value (cond
                                        (not resolver-cache*)
                                        (update-cache cache-value)

                                        (volatile? resolver-cache*)
                                        (vswap! resolver-cache* update-cache)

                                        :else
                                        (swap! resolver-cache* update-cache))]
                      (mapv (fn [{:keys [xt/id]}]
                              (-> (get-in cache-value [::cache schema id])
                                  lib.core/some-vals
                                  joinify-map
                                  (assoc :xt/id id)))
                            inputs))))))

(defn- find-modules [search-dirs]
  (->> search-dirs
       (mapcat #(ns-find/find-namespaces-in-dir (io/file %)))
       (keep (fn [ns-sym]
               (require ns-sym)
               (if-some [module-var (resolve (symbol (str ns-sym) "module"))]
                 (symbol module-var)
                 (do
                   (log/warn "No `module` var found in namespace:" ns-sym)
                   nil))))
       vec))

(defn generate-modules-file! [{:keys [output-file search-dirs]}]
  (when-some [module-symbols (not-empty (find-modules search-dirs))]
    (with-open [w (io/writer output-file)]
      (binding [*out* w]
        (biff/pprint (list 'ns 'com.yakread.modules
                           "This file is auto-generated by Biff. Any changes will be overwritten."
                           (concat '(:require)
                                   (for [sym module-symbols]
                                     [(symbol (namespace sym))]))))
        (println)
        (biff/pprint (list 'def 'modules module-symbols))))))

(defn base64-url-encode [ba]
  (.encodeToString (java.util.Base64/getUrlEncoder) ba))

(defn base64-url-decode [s]
  (.decode (java.util.Base64/getUrlDecoder) s))

(defn signature
  "Returns the hmac-sha1 as base64"
  [secret s]
  (-> (mac/hash s {:key secret :alg :hmac+sha256})
      base64-url-encode))

(defn unsafe [& html]
  {:dangerouslySetInnerHTML {:__html (apply str html)}})

(defmethod aero/reader 'biff/edn
  [_ _ value]
  (edn/read-string value))

(defn upsert [conn table on new-record]
  (let [[{existing-id :xt/id}] (biffx/q conn
                                        {:select :xt/id
                                         :from table
                                         :where (into [:and]
                                                      (map (fn [[k v]]
                                                             [:= k v]))
                                                      on)
                                         :limit 1})]
    (if existing-id
      [{:update table
        :set (dissoc new-record :xt/id)
        :where [:= :xt/id existing-id]}]
      [[:put-docs table (into {}
                              (filter (comp some? val))
                              (merge {:xt/id (gen/uuid)} new-record on))]
       (biffx/assert-unique table on)])))

(defmulti biff-tx-op (fn [ctx op]
                       (when (and (vector? op)
                                  (qualified-keyword? (first op)))
                         (first op))))

(defmethod biff-tx-op :default
  [_ op]
  [op])

(defmethod biff-tx-op :biff/assert-query
  [_ [_ query results]]
  (if (empty? results)
    ;; we need a special case because the second assert doesn't work when results are empty.
    [{:assert [:not-exists query]}]
    [{:assert [:=
               {:select [[[:nest_many query]]]}
               {:select [[[:array (for [record results]
                                    [:lift record])]]]}]}]))

(defmethod biff-tx-op :biff/upsert
  [{:keys [biff/conn]} [_ table on & records]]
  (let [query {:select (conj on :xt/id)
               :from table
               :where [:in
                       [:array on]
                       (for [record records]
                         [:array (mapv record on)])]}
        results (biffx/q conn query)
        on-fn (apply juxt on)
        on->id (into {} (map (juxt on-fn :xt/id)) results)
        new-records (into []
                          (keep (fn [record]
                                  (when-not (contains? on->id (on-fn record))
                                    (into {}
                                          (filter (comp some? val))
                                          (merge {:xt/id (gen/uuid)}
                                                 (dissoc record :biff/on-insert :biff/on-update)
                                                 (:biff/on-insert record))))))
                          records)
        existing-records (into []
                               (keep (fn [record]
                                       (when-some [id (on->id (on-fn record))]
                                         (merge (into {}
                                                      (map (fn [[k v]]
                                                             (if (keyword? v)
                                                               [k [:lift v]]
                                                               [k v])))
                                                      (dissoc record :biff/on-insert :biff/on-update))
                                                (:biff/on-update record)
                                                {:xt/id id}))))
                               records)]
    (vec (concat
          [[:biff/assert-query query results]]

          (when (not-empty new-records)
            [(into [:put-docs table] new-records)])

          (for [record existing-records]
            {:update table
             :set (apply dissoc record :xt/id on)
             :where [:= :xt/id (:xt/id record)]})))))

(defn resolve-tx-ops [ctx tx]
  (into []
        (mapcat (fn [tx-op]
                  (let [expanded (biff-tx-op ctx tx-op)]
                    (cond->> expanded
                      (not= expanded [tx-op]) (resolve-tx-ops ctx)))))
        tx))

(defn submit-tx [ctx tx]
  (biffx/submit-tx ctx (resolve-tx-ops ctx tx)))

(defn bundle [& {:as k->query}]
  {:select (mapv (fn [[k query]]
                   [[:nest_many query] k])
                 k->query)})

(defn gen-uuid [prefix]
  (biffx/prefix-uuid prefix (gen/uuid)))
