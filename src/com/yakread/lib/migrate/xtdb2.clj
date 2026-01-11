(ns com.yakread.lib.migrate.xtdb2
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [taoensso.nippy :as nippy]
   [xtdb.api :as xt]
   [xtdb.node :as xtn]
   [malli.core :as m]
   [malli.error :as me]
   [clojure.tools.logging :as log]
   [tick.core :as tick]))

(defn files [dir]
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
               (into (for [[table ids] (group-by new-id->table component-ids)]
                       (into [:delete-docs (merge {:from table} valid-from-to)] ids))))}))

(defn handle-put-op [{:keys [convert-doc malli-opts
                             new-id->table old-id->new-id old-id->component-ids
                             tx-ops]}
                     {:keys [doc id tx-time valid-from valid-to]}]
  ;; convert old doc to new docs
  ;; find out if any component entities need to be deleted
  ;; delete docs, put docs
  (let [input {:old-doc doc
               :old-id->new-id #(get old-id->new-id % %)}

        {:keys [table new-doc table->component-docs] :as output}
        (convert-doc input)

        _ (doseq [[table docs] (assoc table->component-docs
                                      table
                                      new-doc)
                  doc docs
                  :when (not (m/validate table new-doc malli-opts))]
            (throw (ex-info "Record doesn't match schema."
                            {:invalid-doc doc
                             :invalid-doc-table table
                             :convert-input input
                             :convert-output output
                             :explain (me/humanize (m/explain table doc))})))

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
                     [[:put-docs (assoc valid-from-to :into table) doc]])
                   (for [[table docs] table->component-docs]
                     (into [:put-docs (assoc valid-from-to :into table)] docs))
                   (for [[table ids] (group-by new-id->table deleted-component-ids)]
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
                     ((case op
                        ::xt/put handle-put-op
                        ::xt/delete handle-delete-op)
                      (merge opts state)
                      tx-op)))
            (assoc opts :tx-ops [])
            $)
    {:new-state (select-keys $ [:new-id->table :old-id->new-id :old-id->component-ids])
     :tx-ops (:tx-ops $)}))

(def state-file (io/file ".biff-migrate-state"))
(def tx-ops-file (io/file ".biff-migrate-state-tx-ops"))

(defn import!
  "Imports transactions from XTDB v1.

   node:       an XTDB v2 node
   dir:        the directory written to by com.biffweb.migrate.xtdb1/export!
   convert-doc: a function used to convert an XTDB1 document to one or more
                XTDB2 documents. The function has the following form:

     (convert-doc {:old-doc {...}, old-id->new-id (fn [id] ...)})
     => {:new-doc {...},
         :table <keyword>,
         :table->component-docs {<keyword> [{...}, {...}]}}

   Some convertions that convert-doc will likely need to do:

   - Update :xt/id to include a prefix as needed
   - Use old-id->new-id to update references/foreign keys to other documents
   - split vector/set values out into separate join table documents, via
     :table->component-docs
   - convert instants to zoned date times"
  [{:keys [node dir convert-doc malli-opts]}]
  ;; id->table, id->new-id, id->component-ids
  (let [{:keys [latest-state-file
                state]} (when (.exists state-file)
                          (nippy/thaw-from-file state-file))]
    (reduce (fn [state* f]
              (let [file-index (parse-long (.getName f))]
                (when (or (nil? latest-state-file)
                          (< latest-state-file file-index))
                  (log/info "state" file-index)
                  (let [{:keys [new-state]}
                        (xt2-ops (merge state
                                        state*
                                        {:convert-doc convert-doc
                                         :xt1-txes (nippy/thaw-from-file f)
                                         :malli-opts malli-opts}))]
                    (nippy/freeze-to-file
                     state-file
                     (assoc new-state :latest-state-file file-index))
                    new-state))))
            {}
            (files dir)))

  (let [{:keys [latest-tx-ops-file]} (nippy/thaw-from-file tx-ops-file)
        {:keys [state]} (nippy/thaw-from-file state-file)]
    (with-open [conn (.build (.createConnectionBuilder node))]
      (doseq [f (files dir)
              :let [file-index (parse-long (.getName f))]
              :when (or (nil? latest-tx-ops-file)
                        (< latest-tx-ops-file file-index))
              :let [_ (log/info "tx-ops" file-index)
                    {:keys [tx-ops]} (xt2-ops (merge state
                                                     {:convert-doc convert-doc
                                                      :xt1-txes (nippy/thaw-from-file f)
                                                      :malli-opts malli-opts}))]]
        (->> tx-ops
             (partition-all 1000)
             (run! #(xt/submit-tx conn %)))
        (nippy/freeze-to-file tx-ops-file {:latest-tx-ops-file file-index}))))

  :done)

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

(defn convert-common [doc old-id->new-id]
  (walk/postwalk
   (fn [x]
     (cond
       (uuid? x) (old-id->new-id x)
       (tick/instant? x) (tick/in x "UTC")
       :else x))
   doc))

(defn yakread-convert-doc [{:keys [old-doc old-id->new-id]}]
  (when-some [table (some required-attr->table (keys old-doc))]
    {:table table
     :new-doc (reduce (fn [doc f]
                        (f doc old-id->new-id))
                      old-doc
                      [;; TODO finish
                       #_(case table
                         :user ...
                         :sub ...
                         :item ...
                         :feed ...
                         ...)
                       convert-common])
     :table->component-docs nil}))

(comment

  (def node (xtn/start-node {}))
  (.close node)
  (io/delete-file state-file)

  (import! {:node node
            :dir "storage/migrate-export"
            :doc->table (constantly :stuff)
            :malli-opts com.yakread/malli-opts
            })

  (nippy/thaw-from-file state-file)

  (def xt1-txes (->> (files "../xtdb1/export")
                     (mapcat nippy/thaw-from-file)
                     vec))
  ;; =>
  '[#:xtdb.api{:tx-id 0,
               :tx-time #inst "2025-11-11T16:31:07.619-00:00",
               :tx-ops ([:xtdb.api/put {:value "a", :xt/id 1}])}
    #:xtdb.api{:tx-id 1,
               :tx-time #inst "2025-11-11T16:31:07.623-00:00",
               :tx-ops ([:xtdb.api/put {:value "b", :xt/id 1}])}
    #:xtdb.api{:tx-id 2,
               :tx-time #inst "2025-11-11T16:31:07.626-00:00",
               :tx-ops ([:xtdb.api/delete 1])}
    #:xtdb.api{:tx-id 3,
               :tx-time #inst "2025-11-11T16:31:07.630-00:00",
               :tx-ops
               ([:xtdb.api/put {:xtdb.api/evicted? true, :xt/id nil}])}
    #:xtdb.api{:tx-id 4,
               :tx-time #inst "2025-11-11T16:31:07.632-00:00",
               :tx-ops ([:xtdb.api/evict nil])}
    #:xtdb.api{:tx-id 5,
               :tx-time #inst "2025-11-11T16:31:07.637-00:00",
               :tx-ops
               ([:xtdb.api/put
                 #:xt{:fn
                      (fn
                        [ctx_ & args_]
                        [[:xtdb.api/put {:xt/id 3, :value "a"}]
                         [:xtdb.api/put {:xt/id 4, :value "b"}]]),
                      :id :my-tx-fn}])}
    #:xtdb.api{:tx-id 6,
               :tx-time #inst "2025-11-11T16:31:07.642-00:00",
               :tx-ops
               ([:xtdb.api/fn
                 :my-tx-fn
                 #:xtdb.api{:tx-ops
                            ([:xtdb.api/put {:value "a", :xt/id 3}]
                             [:xtdb.api/put {:value "b", :xt/id 4}])}])}
    #:xtdb.api{:tx-id 7,
               :tx-time #inst "2025-11-11T16:31:07.648-00:00",
               :tx-ops
               ([:xtdb.api/put
                 {:value "a", :xt/id 5}
                 #inst "2025-01-01T01:00:00.000-00:00"
                 #inst "2025-02-02T02:00:00.000-00:00"])}
    #:xtdb.api{:tx-id 8,
               :tx-time #inst "2025-11-11T16:31:07.650-00:00",
               :tx-ops ([:xtdb.api/delete 5])}
    #:xtdb.api{:tx-id 9,
               :tx-time #inst "2025-11-11T16:31:07.652-00:00",
               :tx-ops
               ([:xtdb.api/delete
                 5
                 #inst "2024-01-01T01:00:00.000-00:00"
                 #inst "2026-02-02T02:00:00.000-00:00"])}
    #:xtdb.api{:tx-id 10,
               :tx-time #inst "2025-11-11T16:31:07.655-00:00",
               :tx-ops
               ([:xtdb.api/put
                 {:value "a", :xt/id 6}
                 #inst "2030-01-01T01:00:00.000-00:00"
                 #inst "2030-02-02T02:00:00.000-00:00"])}
    #:xtdb.api{:tx-id 11,
               :tx-time #inst "2025-11-11T16:31:07.656-00:00",
               :tx-ops ([:xtdb.api/delete 6])}
    #:xtdb.api{:tx-id 12,
               :tx-time #inst "2025-11-11T16:31:07.659-00:00",
               :tx-ops
               ([:xtdb.api/put {:xtdb.api/evicted? true, :xt/id nil}])}
    #:xtdb.api{:tx-id 13,
               :tx-time #inst "2025-11-11T16:31:07.660-00:00",
               :tx-ops ([:xtdb.api/delete nil])}
    #:xtdb.api{:tx-id 14,
               :tx-time #inst "2025-11-11T16:31:07.662-00:00",
               :tx-ops ([:xtdb.api/evict nil])}]

  (xt2-ops {:doc->table (constantly :stuff)
            :id->table {}
            :xt1-txes xt1-txes})
  ;; =>
  {:tx-ops
   [[:put-docs
     :stuff
     {:value "a",
      :xt/id 1,
      :xt/valid-from #inst "2025-11-11T16:31:07.619-00:00"}]
    [:put-docs
     :stuff
     {:value "b",
      :xt/id 1,
      :xt/valid-from #inst "2025-11-11T16:31:07.623-00:00"}]
    [:delete-docs
     {:from :stuff, :xt/valid-from #inst "2025-11-11T16:31:07.626-00:00"}
     1]
    [:put-docs
     :stuff
     {:value "a",
      :xt/id 3,
      :xt/valid-from #inst "2025-11-11T16:31:07.642-00:00"}]
    [:put-docs
     :stuff
     {:value "b",
      :xt/id 4,
      :xt/valid-from #inst "2025-11-11T16:31:07.642-00:00"}]
    [:put-docs
     :stuff
     {:value "a",
      :xt/id 5,
      :xt/valid-from #inst "2025-01-01T01:00:00.000-00:00",
      :xt/valid-to #inst "2025-02-02T02:00:00.000-00:00"}]
    [:delete-docs
     {:from :stuff, :xt/valid-from #inst "2025-11-11T16:31:07.650-00:00"}
     5]
    [:delete-docs
     {:from :stuff,
      :xt/valid-from #inst "2024-01-01T01:00:00.000-00:00",
      :xt/valid-to #inst "2026-02-02T02:00:00.000-00:00"}
     5]
    [:put-docs
     :stuff
     {:value "a",
      :xt/id 6,
      :xt/valid-from #inst "2030-01-01T01:00:00.000-00:00",
      :xt/valid-to #inst "2030-02-02T02:00:00.000-00:00"}]
    [:delete-docs
     {:from :stuff, :xt/valid-from #inst "2025-11-11T16:31:07.656-00:00"}
     6]],
   :id->table {1 :stuff, 3 :stuff, 4 :stuff, 5 :stuff, 6 :stuff}}

  )

