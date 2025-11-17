(ns com.yakread.work.materialized-views
  (:require
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pipeline :as lib.pipe :refer [defpipe]]
   [xtdb.api :as-alias xt]))

(defpipe update-views
  :start
  (fn [{:keys [biff/job]}]
    {:biff.pipe/next [(:view job)]})

  :sub-affinity
  (fn [{:keys [biff/job]}]
    {:biff.pipe/next [(lib.pipe/pathom
                       {:sub/id (:sub/id job)}
                       [:sub/id
                        :sub/affinity-low*
                        :sub/affinity-high*
                        {(? :sub/mv) [(? :mv.sub/affinity-low)
                                      (? :mv.sub/affinity-high)]}])
                      :sub-affinity-update]})

  :sub-affinity-update
  (fn [{:keys [biff.pipe.pathom/output]}]
    (let [{:sub/keys [id affinity-low* affinity-high* mv]} output]
      (when (not= [affinity-low* affinity-high*]
                  [(:mv.sub/affinity-low mv) (:mv.sub/affinity-high mv)])
        {:biff.pipe/next [(lib.pipe/tx
                           [{:db/doc-type          :mv.sub
                             :db.op/upsert         {:mv.sub/sub id}
                             :mv.sub/affinity-low  affinity-low*
                             :mv.sub/affinity-high affinity-high*}])]})))

  :current-item
  (fn [{:biff/keys [db job]}]
    ;; TODO
    #_(let [{:user-item/keys [user item viewed-at]} job

          {current-item :user-item/item
           current-item-viewed-at :user-item/viewed-at}
          (first
           (q db
              '{:find (pull usit [:user-item/item
                                  :user-item/viewed-at])
                :in [user]
                :where [[mv :mv.user/user user]
                        [mv :mv.user/current-item item]
                        [usit :user-item/user user]
                        [usit :user-item/item item]]}
              user))

          new-current-item (cond
                             (and viewed-at
                                  (or (not current-item-viewed-at)
                                      (lib.core/increasing? current-item-viewed-at viewed-at)))
                             item

                             (and (not viewed-at)
                                  (= item current-item))
                             :db/dissoc)]
      (when new-current-item
        {:biff.pipe/next [(lib.pipe/tx
                           [{:db/doc-type :mv.user
                             :db.op/upsert {:mv.user/user user}
                             :mv.user/current-item new-current-item}])]}))))

(defn- sub-id [db user-id item-id]
  ;; TODO
  #_(let [{email-sub :item.email/sub
         feed :item.feed/feed} (xt/entity db item-id)]
    (or email-sub
        (when feed
          (biff/lookup-id db
                          :sub/user user-id
                          :sub.feed/feed (:xt/id feed))))))

(defpipe on-tx
  :start
  (fn [{:keys [biff/db] ::xt/keys [tx]}]
    {:biff.pipe/next
     (for [[op doc] (::xt/tx-ops tx)
           :when (= op ::xt/put)
           job (distinct
                (cond
                  (:user-item/user doc)
                  (concat (when-some [sub (sub-id db
                                                  (:user-item/user doc)
                                                  (:user-item/item doc))]
                            [{:view :sub-affinity :sub/id sub}])
                          [(merge {:view :current-item} doc)])

                  (:skip/user doc)
                  (for [item (:skip/items doc)
                        :let [sub (sub-id db (:skip/user doc) item)]
                        :when sub]
                    {:view :sub-affinity :sub/id sub})))]
       (lib.pipe/queue :work.materialized-views/update job))}))

(def module {:on-tx (fn [ctx tx] (on-tx (assoc ctx ::xt/tx tx)))
             :queues [{:id        :work.materialized-views/update
                       :consumer  #'update-views
                       :n-threads 2}]})
