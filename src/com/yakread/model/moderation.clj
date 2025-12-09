(ns com.yakread.model.moderation
  (:require
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]))

(defresolver next-batch [{:keys [biff/conn yakread.model/all-liked-items]} _]
  {::pco/output [{:admin.moderation/next-batch [:xt/id :item/n-likes]}
                 :admin.moderation/remaining
                 :admin.moderation/approved
                 :admin.moderation/blocked
                 :admin.moderation/ingest-failed]}
  (let [direct-items (biffx/q conn
                              {:select [:xt/id :item/url :item/doc-type :item.direct/candidate-status]
                               :from :item
                               :where [:= :item/doc-type "item/direct"]})
        url->direct-item (into {} (map (juxt :item/url identity)) direct-items)
        item->url (into {}
                        (map (juxt :xt/id :item/url))
                        (biffx/q conn
                                 {:select [:xt/id :item/url]
                                  :from :item
                                  :where [:in :xt/id (mapv :item/id all-liked-items)]}))
        direct-item-id->likes (->> all-liked-items
                                   (mapv (fn [{:keys [item/id item/n-likes]}]
                                           (when-some [id (-> id item->url url->direct-item :xt/id)]
                                             {id n-likes})))
                                   (apply merge-with +))
        liked-direct-items (->> direct-items
                                (into []
                                      (comp (map #(assoc % :item/n-likes (direct-item-id->likes (:xt/id %))))
                                            (filter :item/n-likes)
                                            ;; remove the ones that have already been approved or
                                            ;; blocked.
                                            (remove :item.direct/candidate-status)))
                                (sort-by :item/n-likes >)
                                vec)
        statuses (into {}
                       (map (juxt :item.direct/candidate-status :count))
                       (biffx/q conn
                                {:select [:item.direct/candidate-status
                                          [[:count :xt/id] :count]]
                                 :from :item
                                 :where [:is-not :item.direct/candidate-status nil]}))]
    {:admin.moderation/remaining (count liked-direct-items)
     :admin.moderation/approved (get statuses :approved 0)
     :admin.moderation/blocked (get statuses :blocked 0)
     :admin.moderation/ingest-failed (get statuses :ingest-failed 0)
     :admin.moderation/next-batch (vec (take 50 liked-direct-items))}))

(def module {:resolvers [next-batch]})
