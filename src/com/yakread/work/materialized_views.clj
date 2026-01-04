(ns com.yakread.work.materialized-views
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.fx :as fx]
   [xtdb.api :as-alias xt]
   [tick.core :as tick]))

(fx/defmachine update-views
  :start
  (fn [{:keys [biff/job]}]
    {:biff.fx/next (:view job)})

  :sub-affinity
  (fn [{:keys [biff/job]}]
    {:biff.fx/pathom {:entity {:sub/id (:sub/id job)}
                      :query [:sub/id
                              :sub/affinity-low*
                              :sub/affinity-high*
                              {(? :sub/mv) [(? :mv.sub/affinity-low)
                                            (? :mv.sub/affinity-high)]}]}
     :biff.fx/next :sub-affinity-update})

  :sub-affinity-update
  (fn [{:keys [biff.fx/pathom]}]
    (let [{:sub/keys [id affinity-low* affinity-high* mv]} pathom]
      (when (not= [affinity-low* affinity-high*]
                  [(:mv.sub/affinity-low mv) (:mv.sub/affinity-high mv)])
        {:biff.fx/tx [[:biff/upsert :mv-sub [:mv.sub/sub]
                       {:xt/id (biffx/prefix-uuid id (gen/uuid))
                        :mv.sub/sub id
                        :mv.sub/affinity-low  affinity-low*
                        :mv.sub/affinity-high affinity-high*}]]})))

  :current-item
  (fn [{:biff/keys [conn job]}]
    (let [{:user-item/keys [user item viewed-at]} job

          {current-item :user-item/item
           current-item-viewed-at :user-item/viewed-at}
          (first
           (biffx/q conn
                    {:select [:user-item/item :user-item/viewed-at]
                     :from :mv-user
                     :join [:user-item [:= :user-item/item :mv.user/current-item]]
                     :where [:= :mv.user/user user]}))

          new-current-item (cond
                             (and viewed-at
                                  (or (not current-item-viewed-at)
                                      (tick/<= current-item-viewed-at viewed-at)))
                             item

                             (and (not viewed-at)
                                  (= item current-item))
                             ::remove)]
      (when new-current-item
        {:biff.fx/tx [{:biff/upsert :mv-user [:mv.user/user]
                       {:xt/id (biffx/prefix-uuid user (gen/uuid))
                        :mv.user/user user
                        :mv.user/current-item (when (not= new-current-item ::remove)
                                                new-current-item)}}]}))))

(fx/defmachine on-tx
  :start
  (fn [{:keys [biff/conn record]}]
    {:biff.fx/queue
     {:jobs
      (for [job
            (distinct
             (cond
               (:user-item/user record)
               (concat
                (when-some [sub-id (-> (biffx/q
                                        conn
                                        {:select [[[:coalesce :item.email/sub :sub._id]
                                                   :sub/id]]
                                         :from :item
                                         :where [:= :item._id (:user-item/item record)]
                                         :left-join [:sub [:and
                                                           [:is-not :item.feed/feed nil]
                                                           [:= :sub.feed/feed :item.feed/feed]
                                                           [:= :sub/user (:user-item/user record)]]]
                                         :limit 1})
                                       first
                                       :sub/id)]
                  [{:view :sub-affinity :sub/id sub-id}])
                [(merge record {:view :current-item})])

               (:skip/item record)
               (when-some [sub-id (-> (biffx/q
                                       conn
                                       {:select [[[:coalesce :item.email/sub :sub._id]
                                                  :sub/id]]
                                        :from :reclist
                                        :where [:= :reclist._id (:skip/reclist record)]
                                        :join [:item [:= :item._id (:skip/item record)]]
                                        :left-join [:sub [:and
                                                          [:is-not :item.feed/feed nil]
                                                          [:= :sub.feed/feed :item.feed/feed]
                                                          [:= :sub/user :reclist/user]]]
                                        :limit 1})
                                      first
                                      :sub/id)]
                 [{:view :sub-affinity :sub/id sub-id}])))]
        [:work.materialized-views/update job])}}))

(def module {:on-tx (fn [ctx record] (on-tx (assoc ctx :record record)))
             :queues [{:id        :work.materialized-views/update
                       :consumer  #'update-views
                       :n-threads 2}]})
