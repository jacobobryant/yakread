(ns com.yakread.model.subscription
  (:require
   [clojure.string :as str]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.serialize :as lib.serialize]
   [xtdb.api :as-alias xt]))

(defresolver user-subs [{:keys [biff/conn]} {:keys [user/id]}]
  #::pco{:output [{:user/subscriptions [:xt/id]}
                  {:user/unsubscribed [:xt/id]}]}
  (let [{subbed false
         unsubbed true} (->> (biffx/q conn
                                       {:select [:xt/id :sub.email/unsubscribed-at]
                                        :from :sub
                                        :where [:= :sub/user id]})
                              (group-by (comp some? :sub.email/unsubscribed-at)))]
    {:user/subscriptions (or subbed [])
     :user/unsubscribed (mapv #(select-keys % [:xt/id]) unsubbed)}))

(defresolver email-title [{:keys [sub.email/from]}]
  {:sub/title (str/replace from #"\s<.*>" "")})

(defresolver feed-sub-title [{:keys [sub.feed/feed]}]
  #::pco{:input [{:sub.feed/feed [:feed/title]}]}
  {:sub/title (:feed/title feed)})

(defresolver email-subtitle [{:keys [sub.email/latest-item]}]
  {::pco/input [{:sub.email/latest-item [:item.email/reply-to]}]}
  {:sub/subtitle (:item.email/reply-to latest-item)})

(defresolver feed-sub-subtitle [{:keys [sub.feed/feed]}]
  #::pco{:input [{:sub.feed/feed [:feed/url]}]}
  {:sub/subtitle (:feed/url feed)})

(defresolver latest-email-item [{:keys [biff/conn]} {:sub/keys [doc-type id]}]
  {::pco/output [{:sub.email/latest-item [:xt/id]}]}
  (when-some [item (when (= doc-type :sub/email)
                     (first (biffx/q conn
                                     {:select :xt/id
                                      :from :item
                                      :where [:= :item.email/sub id]
                                      :order-by [[:item/ingested-at :desc]]
                                      :limit 1})))]
    {:sub.email/latest-item item}))

(defresolver sub-id->xt-id [{:keys [sub/id]}]
  {:xt/id id})

(defresolver sub-info [{:keys [xt/id sub.feed/feed sub.email/from]}]
  #::pco{:input [:xt/id
                 :sub/user
                 (? :sub.feed/feed)
                 (? :sub.email/from)]
         :output [:sub/id
                  :sub/source-id
                  :sub/doc-type]}
  (if from
    {:sub/id id
     :sub/source-id id
     :sub/doc-type :sub/email}
    {:sub/id id
     :sub/source-id (:xt/id feed)
     :sub/doc-type :sub/feed}))

(defn- doc-type->source-key [doc-type]
  (case doc-type
    :sub/email :item.email/sub
    :sub/feed :item.feed/feed))

(defresolver total [{:keys [biff/conn]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type]
   ::pco/output [:sub/total]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (biffx/q conn
                         {:union-all
                          (for [[doc-type source-ids]  doc-type->source-ids
                                :let [source-key (doc-type->source-key doc-type)]]
                            {:select [[source-key :sub/source-id]
                                      [:%count.* :sub/total]]
                             :from :item
                             :where [:in source-key source-ids]})})]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:keys [sub/source-id]}]
                              {:sub/source-id source-id
                               :sub/total 0}))))

(defresolver items-read [{:keys [biff/conn]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type :sub/user]
   ::pco/output [:sub/items-read]
   ::pco/batch? true}
  (let [results (biffx/q conn
                         {:union
                          (for [[user-id inputs] (group-by :sub/user inputs)
                                [doc-type inputs] (group-by :sub/doc-type inputs)
                                :let [source-ids (mapv :sub/source-id inputs)
                                      source-key (doc-type->source-key doc-type)]]
                            {:select [[:user-item/user :sub/user]
                                      [source-key :sub/source-id]
                                      [[:count :user-item/item] :sub/items-read]]
                             :from :user-item
                             :join [:item [:= :item._id :user-item/item]]
                             :where [:and
                                     [:= :user-item/user user-id]
                                     [:in source-key source-ids]
                                     [:is-not [:coalesce
                                               :user-item/viewed-at
                                               :user-item/skipped-at
                                               :user-item/favorited-at
                                               :user-item/disliked-at
                                               :user-item/reported-at]
                                      nil]]})})]
    (lib.core/restore-order inputs
                            (juxt :sub/user :sub/source-id)
                            results
                            (fn [{:sub/keys [user source-id]}]
                              {:sub/user user
                               :sub/source-id source-id
                               :sub/items-read 0}))))

(defresolver items-unread [{:sub/keys [total items-read]}]
  {:sub/items-unread (- total items-read)
   ;; for backwards compat
   :sub/unread (- total items-read)})

(defresolver published-at [{:keys [biff/conn]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type :sub/created-at]
   ::pco/output [:sub/published-at]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (biffx/q conn
                         {:union-all
                          (for [[doc-type source-ids]  doc-type->source-ids
                                :let [source-key (doc-type->source-key doc-type)]]
                            {:select [[source-key :sub/source-id]
                                      [[:max [:coalesce :item/published-at :item/ingested-at]]
                                       :sub/published-at]]
                             :from :item
                             :where [:in source-key source-ids]})})]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:sub/keys [source-id created-at]}]
                              {:sub/source-id source-id
                               :sub/published-at created-at}))))

(defresolver items [{:keys [biff/conn]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type]
   ::pco/output [{:sub/items [:xt/id]}]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (->> (biffx/q conn
                              {:union-all
                               (for [[doc-type source-ids]  doc-type->source-ids
                                     :let [source-key (doc-type->source-key doc-type)]]
                                 {:select [[source-key :sub/source-id]
                                           :xt/id]
                                  :from :item
                                  :where [:in source-key source-ids]})})
                     (group-by :sub/source-id)
                     (mapv (fn [[source-id items]]
                             {:sub/source-id source-id
                              :sub/items (mapv #(select-keys % [:xt/id]) items)})))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:sub/keys [source-id]}]
                              {:sub/source-id source-id
                               :sub/items []}))))

(defresolver latest-item [{:keys [biff/conn]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type :sub/published-at]
   ::pco/output [{:sub/latest-item [:xt/id]}]
   ::pco/batch? true}
  (let [doc-type->subs (group-by :sub/doc-type inputs)
        results (into []
                      (map (fn [{:keys [sub/source-id xt/id]}]
                             {:sub/source-id source-id
                              :sub/latest-item {:xt/id id}}))
                      (biffx/q conn
                               ;; TODO try [:coalesce :item.feed/feed :item.email/sub]
                               {:union-all
                                (for [[doc-type subs*]  doc-type->subs
                                      :let [source-key (doc-type->source-key doc-type)]]
                                  {:select [[source-key :sub/source-id]
                                            :xt/id]
                                   :from :item
                                   :where [:in
                                           [:array [source-key [:coalesce
                                                                :item/published-at
                                                                :item/ingested-at]]]
                                           (for [{:sub/keys [source-id published-at]} subs*]
                                             [:array [source-id published-at]])]})}))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results)))

(defresolver from-params [{:keys [biff/conn session path-params params]} _]
  #::pco{:output [{:params/sub [:xt/id :sub/user]}]}
  (let [sub-id (or (:sub/id params)
                   (lib.serialize/url->uuid (:sub-id path-params)))
        [sub] (when (some? sub-id)
                (biffx/q conn
                         {:select [:xt/id :sub/user]
                          :from :sub
                          :where [:= :xt/id sub-id]}))]
    (when (and sub (= (:uid session) (:sub/user sub)))
      {:params/sub sub})))

;; TODO turn from-params into a batch resolver and delete this
(defresolver params-checked [{:keys [biff/conn session params]} _]
  #::pco{:output [{:params.checked/subscriptions [:sub/id]}]}
  (let [sub-ids (mapv #(some-> % name parse-uuid) (keys (:subs params)))
        subs* (biffx/q conn
                       {:select [:xt/id :sub/user]
                        :from :sub
                        :where [:in :xt/id sub-ids]})]

    (when (and (= (count sub-ids) (count subs*))
               (every? #(= (:uid session) (:sub/user %)) subs*))
      {:params.checked/subscriptions subs*})))

(defresolver unread-items [{:keys [biff/conn]} subscriptions]
  #::pco{:input [{:sub/user [:xt/id]}
                 {:sub/items [:xt/id]}]
         :output [{:sub/unread-items [:xt/id]}]
         :batch? true}
  (let [results (biffx/q conn
                         {:select [:user-item/user :user-item/item]
                          :from :user-item
                          :where [:and
                                  [:in
                                   [:array [:user-item/user :user-item/item]]
                                   (for [{:sub/keys [user items]} subscriptions
                                         item items]
                                     [:array [(:xt/id user) (:xt/id item)]])]
                                  [:is-not
                                   [:coalesce
                                    :user-item/viewed-at
                                    :user-item/skipped-at
                                    :user-item/favorited-at
                                    :user-item/disliked-at
                                    :user-item/reported-at]
                                   nil]]})
        user->read-items (update-vals (group-by :user-item/user results)
                                      (fn [results]
                                        (into #{} (map :user-item/item) results)))]
    (mapv (fn [{:sub/keys [user items]}]
            (let [read-items (get user->read-items (:xt/id user) #{})
                  unread-items (filterv (complement (comp read-items :xt/id)) items)]
              {:sub/unread-items unread-items}))
          subscriptions)))

(defresolver mv [{:keys [biff/conn]} subs*]
  {::pco/input  [:xt/id]
   ::pco/output [{:sub/mv [:xt/id]}]
   ::pco/batch?  true}
  (let [results (mapv (fn [{:keys [sub/mv xt/id]}]
                        {:xt/id id
                         :sub/mv {:xt/id mv}})
                      (biffx/q conn
                               {:select [[:xt/id :sub/mv]
                                         [:mv.sub/sub :xt/id]]
                                :from :mv-sub
                                :where [:in :mv.sub/sub (mapv :xt/id subs*)]}))]
    (lib.core/restore-order subs* :xt/id results)))

(def module {:resolvers [user-subs
                         sub-info
                         sub-id->xt-id
                         email-title
                         feed-sub-title
                         items-unread
                         published-at
                         items
                         latest-item
                         from-params
                         params-checked
                         unread-items
                         feed-sub-subtitle
                         latest-email-item
                         email-subtitle
                         mv
                         total
                         items-read]})
