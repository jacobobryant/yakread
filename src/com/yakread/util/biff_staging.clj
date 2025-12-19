(ns com.yakread.util.biff-staging
  (:require
   [buddy.core.mac :as mac]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.find :as ns-find]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [malli.core :as malli]
   [malli.registry :as malr]
   ;;[xtdb.api :as xt]
   [aero.core :as aero]
   [com.wsscode.misc.coll :as wss-coll]))

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

(defn schema-info [malli-opts]
  (into {}
        (keep (fn [schema-k]
                (let [attrs (volatile! {})]
                  (some-> (try (malli/deref-recursive schema-k malli-opts) (catch Exception _))
                          (malli/walk (fn [schema _ _ _]
                                        (let [ast (malli/ast schema)]
                                          (when (and (= (:type ast) :map)
                                                     (contains? (:keys ast) :xt/id))
                                            (vswap! attrs attr-union (:keys ast)))))))
                  (when (not-empty @attrs)
                    [schema-k @attrs]))))
        (keys (malr/schemas (:registry malli-opts)))))

(defn field-asts [malli-opts]
  (apply merge (vals (schema-info malli-opts))))

(defn- expects [env]
  (-> env
      ::pcp/node
      ::pcp/expects
      keys
      vec))

(defn xtdb2-resolvers [malli-opts]
  ;; TODO maybe add reverse resolvers too
  (for [[schema attrs] (schema-info malli-opts)
        :let [ref? (fn [attr]
                     (boolean (get-in attrs [attr :properties :biff/ref])))
              joinify (fn [[k v]]
                        (if (ref? k)
                          [k {:xt/id v}]
                          [k v]))]
        :when (not (qualified-keyword? schema))]
    (pco/resolver (symbol "com.yakread.util.biff-staging"
                          (str (name schema) "-xtdb2-resolver"))
                  {::pco/input [:xt/id]
                   ::pco/output (vec (for [k (keys attrs)
                                           :when (not= k :xt/id)]
                                       (if (ref? k)
                                         {k [:xt/id]}
                                         k)))
                   ::pco/batch? true
                   ::pco/cache-key (fn [env input]
                                     [::xtdb2-resolvers schema input (expects env)])}
                  (fn [{:keys [biff/conn] :as env} inputs]
                    ;; TODO
                    ;; - see if the `columns` stuff causes any issues, e.g. do we need to mess with
                    ;;   the cache key. e.g. can we break it with a self-reference that requests
                    ;;   additional columns.
                    ;; - use a fixed db snapshot
                    (let [columns (into [:xt/id] (filter attrs) (expects env))]
                      (->> (biffx/q conn
                                    {:select columns
                                     :from schema
                                     :where [:in :xt/id (mapv :xt/id inputs)]})
                           (mapv #(into {} (map joinify) %))
                           (wss-coll/restore-order inputs :xt/id)))))))

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

(defn signature [secret s]
  "Returns the hmac-sha1 as base64"
  (-> (mac/hash s {:key secret :alg :hmac+sha256})
      base64-url-encode))

(defn unsafe [& html]
  {:dangerouslySetInnerHTML {:__html (apply str html)}})

(defmethod aero/reader 'biff/edn
  [_ _ value]
  (edn/read-string value))
