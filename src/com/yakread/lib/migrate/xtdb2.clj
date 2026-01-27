(ns com.yakread.lib.migrate.xtdb2
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [com.biffweb.experimental :as biffx]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.nippy :as nippy]
   [tick.core :as tick]
   [xtdb.api :as xt]
   [xtdb.node :as xtn]))

(defn tx-files [dir]
  (->> (io/file dir)
       file-seq
       (filterv #(.isFile %))
       (sort-by #(parse-long (.getName %)))))

(defn op-seq [tx-op]
  (tree-seq #(= (first %) ::xt/fn)
            #(::xt/tx-ops (nth % 2))
            tx-op))

(defn normalize-tx [{::xt/keys [tx-ops tx-time]}]
  (for [tx-op tx-ops
        [op & args] (op-seq tx-op)
        :when (or (and (= op ::xt/delete)
                       (some? (first args)))
                  (and (= op ::xt/put)
                       (not (::xt/evicted? (first args)))
                       (not (:xt/fn (first args)))))
        :let [[doc-or-id valid-from valid-to] args]]
    {:op op
     :tx-time tx-time
     :valid-from valid-from
     :valid-to valid-to
     :doc (when (= op ::xt/put) doc-or-id)
     :id (case op
           ::xt/put (:xt/id doc-or-id)
           ::xt/delete doc-or-id
           nil)}))

(defn handle-delete-op [{:keys [old-id->new-id new-id->table old-id->component-ids tx-ops]}
                        {:keys [id tx-time valid-from valid-to]}]
  (let [new-id        (old-id->new-id id)
        table         (get new-id->table new-id)
        component-ids (get old-id->component-ids id)
        valid-from-to (into {}
                            (filter (comp some? val))
                            {:valid-from (or valid-from tx-time)
                             :valid-to valid-to})]
    {:tx-ops (cond-> tx-ops
               (some? table)
               (conj [:delete-docs
                      (merge {:from table} valid-from-to)
                      new-id])

               (not-empty component-ids)
               (into (for [[table ids] (group-by new-id->table component-ids)
                           :when (not-empty ids)]
                       (into [:delete-docs (merge {:from table} valid-from-to)] ids))))}))

(defn handle-put-op [{:keys [convert-doc malli-opts
                             new-id->table old-id->new-id old-id->component-ids
                             tx-ops] :as stuff-a}
                     {:keys [doc id tx-time valid-from valid-to] :as stuff-b}]
  ;; convert old doc to new docs
  ;; find out if any component entities need to be deleted
  ;; delete docs, put docs
  (let [input {:old-doc doc
               :old-id->new-id #(get old-id->new-id % %)}

        {:keys [table new-doc table->component-docs] :as output}
        (convert-doc input)

        _ (doseq [[table docs] (cond-> table->component-docs
                                 table (assoc table [new-doc]))
                  doc docs
                  :when (not (m/validate table doc malli-opts))]
            (throw (ex-info "Record doesn't match schema."
                            {:invalid-doc doc
                             :invalid-doc-table table
                             :convert-input input
                             :convert-output output
                             :explain (me/humanize (m/explain table doc malli-opts))})))

        old-component-ids (get old-id->component-ids id #{})
        new-component-ids (into #{}
                                (comp (mapcat val)
                                      (map :xt/id))
                                table->component-docs)
        all-component-ids (set/union old-component-ids new-component-ids)
        deleted-component-ids (set/difference old-component-ids new-component-ids)
        valid-from-to (into {}
                            (filter (comp some? val))
                            {:valid-from (or valid-from tx-time)
                             :valid-to valid-to})]
    {:tx-ops (vec (concat
                   tx-ops
                   (when (some? new-doc)
                     [[:put-docs (assoc valid-from-to :into table) new-doc]])
                   (for [[table docs] table->component-docs
                         :when (not-empty docs)]
                     (into [:put-docs (assoc valid-from-to :into table)] docs))
                   (for [[table ids] (group-by new-id->table deleted-component-ids)
                         :when (not-empty ids)]
                     (into [:delete-docs (assoc valid-from-to :from table)] ids))))
     :new-id->table (cond-> (or new-id->table {})
                      (some? table)
                      (assoc (:xt/id doc) table)

                      true
                      (into (for [[table docs] table->component-docs
                                  doc docs]
                              [(:xt/id doc) table])))
     :old-id->new-id (cond-> old-id->new-id
                       (and new-doc (not= (:xt/id new-doc) (:xt/id doc)))
                       (assoc (:xt/id doc) (:xt/id new-doc)))
     :old-id->component-ids (cond-> old-id->component-ids
                              (not-empty all-component-ids)
                              (assoc id all-component-ids))}))

(defn xt2-ops [{:keys [xt1-txes] :as opts}]
  (as-> xt1-txes $
    (mapcat normalize-tx $)
    (reduce (fn [state {:keys [op] :as tx-op}]
              (merge state
                     (try
                       ((case op
                          ::xt/put handle-put-op
                          ::xt/delete handle-delete-op)
                        (merge opts state)
                        tx-op)
                       (catch Exception e
                         (throw (ex-info "Error while processing tx operation"
                                         tx-op
                                         e))))))
            (assoc opts :tx-ops [])
            $)
    {:new-state (select-keys $ [:new-id->table :old-id->new-id :old-id->component-ids])
     :tx-ops (:tx-ops $)}))

(def state-file (io/file ".biff-migrate-state"))
(def tx-ops-file (io/file ".biff-migrate-state-tx-ops"))

(defn prep-state! [{:keys [dir convert-doc malli-opts dry-run]}]
  (let [{:keys [latest-state-file state]}
        (when (and (.exists state-file) (not dry-run))
          (nippy/thaw-from-file state-file))

        files (drop-while #(<= (parse-long (.getName %))
                               latest-state-file)
                          (tx-files dir))
        last-file (last files)]
    (reduce (fn [state f]
              (let [file-index (parse-long (.getName f))
                    _ (log/info "prep-state!" file-index)
                    {:keys [new-state]}
                    (xt2-ops (merge state
                                    {:convert-doc convert-doc
                                     :xt1-txes (nippy/thaw-from-file f)
                                     :malli-opts malli-opts}))]
                (when (and (or (= 0 (mod file-index 10))
                               (= f last-file))
                           (not dry-run))
                  (log/info "saving state")
                  (time
                   (nippy/freeze-to-file state-file
                                         {:state new-state
                                          :latest-state-file file-index})))
                new-state))
            state
            files)
    :done))

(defn tx-op-seq [{:keys [dir convert-doc malli-opts]}]
  (let [{:keys [state]} (nippy/thaw-from-file state-file)]
    (for [f (tx-files dir)
          :let [{:keys [tx-ops]} (xt2-ops (merge state
                                                 {:convert-doc convert-doc
                                                  :xt1-txes (nippy/thaw-from-file f)
                                                  :malli-opts malli-opts}))]
          tx-op tx-ops]
      tx-op)))

(defn prep-txes! [{:keys [txes-dir dry-run] :as opts}]
  ;; TODO make this resumable
  (when-not dry-run
    (run! io/delete-file (tx-files txes-dir)))
  (->> (tx-op-seq opts)
       (partition-all 10000)
       (map-indexed vector)
       (run! (fn [[i tx]]
               (when-not dry-run
                 (log/info "prep-txes!" i)
                 (let [file (io/file txes-dir (str i))]
                   (io/make-parents file)
                   (nippy/freeze-to-file file tx)))))))

(defn import! [{:keys [node txes-dir dry-run]}]
  (let [{:keys [latest-tx-ops-file]} (when (and (.exists tx-ops-file)
                                                (not dry-run))
                                       (nippy/thaw-from-file tx-ops-file))]
    (with-open [conn (.build (.createConnectionBuilder node))]
      (doseq [f (tx-files txes-dir)
              :let [file-index (parse-long (.getName f))]
              :when (or (nil? latest-tx-ops-file)
                        (< latest-tx-ops-file file-index))
              :let [_ (log/info "import!" file-index)
                    tx-ops (nippy/thaw-from-file f)]]
        (when-not dry-run
          (->> tx-ops
               (partition-all 100)
               (run! (fn [tx]
                       (xt/submit-tx conn tx))))
          (nippy/freeze-to-file tx-ops-file {:latest-tx-ops-file file-index}))))
    :done))

(defn migrate!
  "Imports transactions from XTDB v1.

   node:        an XTDB v2 node
   dir:         the directory written to by com.biffweb.migrate.xtdb1/export!
   convert-doc: a function used to convert an XTDB1 document to one or more
                XTDB2 documents. The function has the following form:

     (convert-doc {:old-doc {...}, old-id->new-id (fn [id] ...)})
     => {:new-doc {...},
         :table <keyword>,
         :table->component-docs {<keyword> [{...}, {...}]}}

   Some conversions that convert-doc will likely need to do:

   - Update :xt/id to include a prefix as needed
   - Use old-id->new-id to update references/foreign keys to other documents
   - split vector/set values out into separate join table documents, via
     :table->component-docs
   - convert instants to zoned date times"
  [opts]
  (prep-state! opts)
  (prep-txes! opts)
  (import! opts)
  :done)

(defn name-uuid [& strs]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (apply str strs))))

(def required-attr->table
  {:user/email                       :user
   :sub/user                         :sub
   :item/ingested-at                 :item
   :feed/url                         :feed
   :user-item/user                   :user-item
   :digest/user                      :digest
   :bulk-send/sent-at                :bulk-send
   :skip/user                        :reclist
   :ad/user                          :ad
   :ad.click/user                    :ad-click
   :ad.credit/ad                     :ad-credit
   :mv.sub/sub                       :mv-sub
   :mv.user/user                     :mv-user
   :deleted-user/email-username-hash :deleted-user})


(def table->prefixed-by
  {:sub :sub/user
   :item (some-fn :item.feed/feed
                  :item.email/sub
                  (constantly "0000"))
   :user-item :user-item/user
   :digest :digest/user
   :reclist :skip/user
   :ad :ad/user
   :ad-click :ad.click/ad
   :ad-credit :ad.credit/ad
   :mv-sub :mv.sub/sub
   :mv-user :mv.user/user})

(defn convert-common [table doc old-id->new-id]
  (let [prefix-fn (get table->prefixed-by table)
        doc (walk/postwalk
             (fn [x]
               (cond
                 (uuid? x) (old-id->new-id x)
                 (tick/instant? x) (tick/in x "UTC")
                 :else x))
             doc)]
    (cond-> doc
      prefix-fn (update :xt/id #(biffx/prefix-uuid (prefix-fn doc) %)))))

(defn convert-user {:table :user}
  [{:user/keys [timezone] :as doc} _]
  {:new-doc (-> doc
                (dissoc :user/timezone)
                (assoc :user/timezone* (str timezone)))})

(defn convert-user-item {:table :user-item}
  [doc _]
  {:new-doc (dissoc doc :user-item/position)})

(defn convert-digest {:table :digest}
  [doc _]
  (let [digest-items (for [k [:digest/icymi :digest/discover]
                           item (get doc k)
                           :let [new-id (name-uuid (:xt/id doc) item k)
                                 new-id (biffx/prefix-uuid item new-id)]]
                       {:xt/id new-id
                        :digest-item/digest (:xt/id doc)
                        :digest-item/item item
                        :digest-item/kind (keyword (name k))})]
    {:new-doc (dissoc doc :digest/icymi :digest/discover)
     :table->component-docs {:digest-item digest-items}}))

(defn convert-reclist {:table :reclist}
  [doc _]
  (let [skips (for [item (:skip/items doc)
                    :let [new-id (name-uuid (:xt/id doc) item)
                          new-id (biffx/prefix-uuid (:xt/id doc) new-id)]]
                {:xt/id new-id
                 :skip/reclist (:xt/id doc)
                 :skip/item item})]
    {:new-doc (-> doc
                  (set/rename-keys {:skip/user :reclist/user
                                    :skip/timeline-created-at :reclist/created-at
                                    :skip/clicked :reclist/clicked})
                  (dissoc :skip/items))
     :table->component-docs {:skip skips}}))

(defn convert-ad {:table :ad}
  [doc _]
  {:new-doc (cond-> doc
              (:ad/card-details doc)
              (update :ad/card-details set/rename-keys {:exp_year :exp-year
                                                        :exp_month :exp-month}))})

(defn convert-default [doc _]
  {:new-doc doc})

(def table->convert-doc-fn
  (into {}
        (map (juxt (comp :table meta) deref))
        [#'convert-user
         #'convert-user-item
         #'convert-digest
         #'convert-reclist
         #'convert-ad]))

(defn yakread-convert-doc [{:keys [old-doc old-id->new-id]}]
  (when-some [table (some required-attr->table (keys old-doc))]
    (let [convert-fn (get table->convert-doc-fn table convert-default)]
      (try
        (-> (convert-common table old-doc old-id->new-id)
            (convert-fn old-id->new-id)
            (assoc :table table))
        (catch Exception e
          (throw (ex-info "Error while converting doc"
                          {:doc old-doc}
                          e)))))))

(defn clear-import-state! []
  (doseq [f [state-file tx-ops-file]
          :when (.exists f)]
    (io/delete-file f)))

(comment

  (def node
    (xtn/start-node
     {:log-clusters {:kafka-cluster [:kafka {:bootstrap-servers "localhost:9092"
                                             ;:properties-map {"max.request.size" "5242880"}
                                             }]}


      :log
      [:kafka {:cluster :kafka-cluster :topic "xtdb-yakread" :epoch 0}]
      ;[:local {:path "storage/xtdb2/log"}]

      :storage [:remote
                {:object-store [:s3 {,,,}]}]
      :disk-cache {:path "storage/xtdb2/storage-cache"}}))
  (.close node)

  (dissoc (xtdb.api/status node) :metrics)

  (xt/q node "select count(*) from xt.txs where committed = true")
  100358
  (xt/q node "select * from xt.txs order by system_time desc limit 3")


  (time (xt/q node "select count(*) from user"))

  (require 'com.yakread)
  (let [opts {:node node
              :dir "storage/migrate-export"
              :txes-dir "storage/migrate-export-txes"
              :convert-doc yakread-convert-doc
              :malli-opts com.yakread/malli-opts}]
    #_(prep-state! opts)
    #_(prep-txes! opts)
    (import! opts))

  (def e *e)

  (def stuff (-> e ex-cause ex-data))


  (inc 3)
  (nippy/thaw-from-file state-file)

  (def xt1-txes (->> (tx-files "../xtdb1/export")
                     (mapcat nippy/thaw-from-file)
                     vec))


  (->> (file-seq (io/file "storage/migrate-export-txes"))
       (filter #(.isFile %))
       (mapcat nippy/thaw-from-file)
       ;(keep (comp :into second))
       ;frequencies
       (filter (fn [[op opts & docs]]
                 (#{:reclist :skip} (:into opts))))
       )

  )
