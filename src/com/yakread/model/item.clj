(ns com.yakread.model.item
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.s3 :as lib.s3]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.lib.user-item :as lib.user-item]
   [com.yakread.routes :as routes]
   [rum.core :as rum]
   [tick.core :as tick])
  (:import
   (com.vdurmont.emoji EmojiParser)
   (org.jsoup Jsoup)))

(defresolver user-favorites [{:keys [biff/conn biff/now params]} {:keys [user/id]}]
  #::pco{:output [{:user/favorites [:item/id
                                    {:item/user-item [:xt/id]}]}
                  :user/more-favorites]}
  (let [{:keys [before]} (get-in params [:pathom-params :user/bookmarks])
        before (if (tick/zoned-date-time? before)
                 before
                 now)
        page-size 200
        results (into []
                      (map (fn [{:keys [xt/id user-item/item]}]
                             {:item/id item
                              :item/user-item {:xt/id id}}))
                      (biffx/q conn
                               {:select [:xt/id :user-item/item]
                                :from :user-item
                                :where [:and
                                        [:= :user-item/user id]
                                        [:< :user-item/favorited-at before]]
                                :order-by [[:user-item/favorited-at :desc]]
                                :limit page-size}))]

    {:user/favorites results
     :user/more-favorites (= page-size (count results))}))

(defresolver user-bookmarks [{:keys [biff/conn biff/now params]} {:keys [user/id]}]
  #::pco{:output [{:user/bookmarks [:item/id
                                    {:item/user-item [:xt/id]}]}
                  :user/more-bookmarks]}
  (let [{:keys [before]} (get-in params [:pathom-params :user/bookmarks])
        before (if (tick/zoned-date-time? before)
                 before
                 now)
        page-size 200
        results (into []
                      (map (fn [{:keys [xt/id user-item/item]}]
                             {:item/id item
                              :item/user-item {:xt/id id}}))
                      (biffx/q conn
                               {:select [:xt/id :user-item/item]
                                :from :user-item
                                :where [:and
                                        [:= :user-item/user id]
                                        [:< :user-item/bookmarked-at before]]
                                :order-by [[:user-item/bookmarked-at :desc]]
                                :limit page-size}))]
    {:user/bookmarks results
     :user/more-bookmarks (= page-size (count results))}))

(defresolver unread-bookmarks [{:user/keys [bookmarks]}]
  #::pco{:input [{:user/bookmarks [:item/id
                                   :item/unread]}]
         :output [{:user/unread-bookmarks [:item/id]}]}
  {:user/unread-bookmarks (filterv :item/unread bookmarks)})

(defresolver n-skipped [{:keys [biff/conn session]} items]
  #::pco{:input [:xt/id]
         :output [:item/n-skipped]
         :batch? true}
  (let [results (biffx/q conn
                         {:select [[:skip/item :xt/id]
                                   [[:count :skip._id] :item/n-skipped]]
                          :from :skip
                          :join [:reclist [:= :skip/reclist :reclist._id]]
                          :where [:and
                                  [:= :reclist/user (:uid session)]
                                  [:in :skip/item (mapv :xt/id items)]]})]
    (lib.core/restore-order items
                            :xt/id
                            results
                            (fn [{:keys [xt/id]}]
                              {:xt/id id
                               :item/n-skipped 0}))))

(defresolver user-item [{:keys [biff/conn session]} items]
  #::pco{:input [:xt/id]
         :output [{:item/user-item [:xt/id]}]
         :batch? true}
  (let [item-ids (into #{} (map :xt/id items))
        results (into []
                      (keep (fn [{:keys [xt/id user-item/item]}]
                             (when (item-ids item)
                               {:xt/id item :item/user-item {:xt/id id}})))
                      (biffx/q conn
                               {:select [:xt/id :user-item/item]
                                :from :user-item
                                :where [:= :user-item/user (:uid session)]}))]
    (lib.core/restore-order items :xt/id results)))

(defresolver image-from-feed [{:keys [biff/conn]} items]
  #::pco{:input [(? :item/feed-url)
                 {(? :item.feed/feed) [:feed/image-url]}]
         :output [:item/image-url]
         :batch? true}
  (let [feed-urls (keep :item/feed-url items)
        url->image (into {}
                         (map (juxt :feed/url :feed/image-url))
                         (when (not-empty feed-urls)
                           (biffx/q conn
                                    {:select [:feed/url :feed/image-url]
                                     :from :feed
                                     :where [:in :feed/url feed-urls]})))]
    (mapv (fn [{:keys [item.feed/feed item/feed-url]}]
            (if-some [image (or (:feed/image-url feed)
                                (url->image feed-url))]
              {:item/image-url image}
              {}))
          items)))

(defresolver unread [{:keys [item/user-item]}]
  #::pco{:input [{(? :item/user-item) [(? :user-item/viewed-at)
                                       (? :user-item/skipped-at)
                                       (? :user-item/favorited-at)
                                       (? :user-item/disliked-at)
                                       (? :user-item/reported-at)]}]}
  {:item/unread (not (lib.user-item/read? user-item))})

(defresolver history-items [{:keys [biff/conn] :as ctx}
                            {:keys [session/user
                                    params/paginate-after]}]
  #::pco{:input [{:session/user [:xt/id]}
                 (? :params/paginate-after)]
         :output [{:user/history-items [:xt/id
                                        {:item/user-item [:xt/id]}]}]}
  (let [{:keys [batch-size] :or {batch-size 100}} (pco/params ctx)]
    {:user/history-items
     (->> (biffx/q conn
                   {:select [:xt/id
                             :user-item/item
                             :user-item/viewed-at
                             :user-item/favorited-at
                             :user-item/disliked-at
                             :user-item/reported-at]
                    :from :user-item
                    :where [:= :user-item/user (:xt/id user)]})
          (keep (fn [usit]
                  (when-some [t (some->> [:user-item/viewed-at
                                          :user-item/favorited-at
                                          :user-item/disliked-at
                                          :user-item/reported-at]
                                         (keep usit)
                                         not-empty
                                         (apply tick/max))]
                    (assoc usit :t t))))
          (sort-by :t #(compare %2 %1))
          (drop-while (fn [{:keys [user-item/item]}]
                        (and paginate-after
                             (not= item paginate-after))))
          (remove (comp #{paginate-after} :user-item/item))
          (take batch-size)
          (mapv (fn [{:keys [xt/id user-item/item]}]
                  {:xt/id item
                   :item/user-item {:xt/id id}})))}))

;; TODO why do I have to put ? in the input?
(defresolver current-item [input]
  #::pco{:input [{(? :user/mv) [{(? :mv.user/current-item) [:xt/id]}]}]
         :output [{:user/current-item [:item/id :item/rec-type]}]}
  (when-some [id (get-in input [:user/mv :mv.user/current-item :xt/id])]
    {:user/current-item
     {:item/id id
      :item/rec-type :item.rec-type/current}}))

(defresolver source [{:keys [item.email/sub item.feed/feed]}]
  {::pco/input [{(? :item.email/sub) [:xt/id]}
                {(? :item.feed/feed) [:xt/id]}]
   ::pco/output [{:item/source [:xt/id]}]}
  (when-some [source (or sub feed)]
    {:item/source source}))

(defresolver email-sub [{:keys [item.email/sub]}]
  {::pco/input [{:item.email/sub [:xt/id]}]
   ::pco/output [{:item/sub [:xt/id]}]}
  {:item/sub sub})

(defresolver feed-sub [{:keys [biff/conn session]} inputs]
  {::pco/input [{:item.feed/feed [:xt/id]}]
   ::pco/output [{:item/sub [:xt/id]}]
   ::pco/batch? true}
  (let [feed-ids (into [] (map (comp :xt/id :item.feed/feed)) inputs)
        feed->sub (into {}
                        (map (juxt :sub.feed/feed :xt/id))
                        (when (not-empty feed-ids)
                          (biffx/q conn
                                   {:select [:xt/id :sub.feed/feed]
                                    :from :sub
                                    :where [:and
                                            [:= :sub/user (:uid session)]
                                            [:in :sub.feed/feed feed-ids]]})))]
    (mapv (fn [{:keys [item.feed/feed]}]
            {:item/sub {:xt/id (get feed->sub (:xt/id feed))}})
          inputs)))

(defresolver from-params-unsafe [{:keys [path-params params]} _]
  #::pco{:output [{:params/item-unsafe [:xt/id]}]}
  (when-some [item-id (or (some-> (:item-id path-params) lib.serialize/url->uuid)
                          (:item/id params))]
    {:params/item-unsafe {:xt/id item-id}}))

(defresolver from-params [{:keys [session yakread.model/item-candidate-ids]}
                          {:keys [params/item-unsafe]}]
  #::pco{:input [{:params/item-unsafe [:xt/id
                                       {(? :item/sub) [:xt/id
                                                       :sub/user]}
                                       {(? :item/user-item) [:xt/id]}]}]
         :output [{:params/item [:xt/id
                                 {:item/sub [:xt/id
                                             :sub/user]}
                                 {:item/user-item [:xt/id]}]}]}
  (when (or (= (:uid session) (get-in item-unsafe [:item/sub :sub/user :xt/id]))
            (not-empty (:item/user-item item-unsafe))
            (contains? item-candidate-ids (:xt/id item-unsafe)))
    {:params/item item-unsafe}))

(defresolver item-id [{:keys [xt/id item/ingested-at]}]
  {:item/id id})

(defresolver xt-id [{:keys [item/id]}]
  {:xt/id id})

(defresolver content [ctx {:item/keys [content-key url]}]
  #::pco{:input [(? :item/content-key)
                 (? :item/url)]
         :output [:item/content]}
  (cond
    content-key
    {:item/content (:body
                    (lib.s3/request ctx {:config-ns 'yakread.s3.content
                                         :method "GET"
                                         :key (str content-key)}))}

    url
    {:item/content (rum/render-static-markup [:a {:href url} url])}))

(defresolver clean-html [{:item/keys [content]}]
  {:item/clean-html
   (let [doc (Jsoup/parse content)]
     (-> doc
         (.select "a[href]")
         (.attr "target" "_blank"))
     (doseq [img (.select doc "img[src^=http://]")]
       (.attr img "src" (str/replace (.attr img "src")
                                     #"^http://"
                                     "https://")))
     (.outerHtml doc))})

(defresolver doc-type [{:keys [item.feed/feed
                               item.email/sub]}]
  #::pco{:input [{(? :item.feed/feed) [:xt/id]}
                 {(? :item.email/sub) [:xt/id]}]
         :output [:item/doc-type]}
  (cond
    feed {:item/doc-type :item/feed}
    sub {:item/doc-type :item/email}))

(defresolver digest-url [{:biff/keys [base-url href-safe]} {:item/keys [id url rec-type]}]
  {::pco/input [:item/id
                (? :item/url)
                (? :item/rec-type)]}
  {:item/digest-url
   (fn [{user-id :user/id}]
     (str base-url
          (href-safe routes/click-item
                     (merge {:action   :action/click-item
                             :user/id  user-id
                             :item/id  id}
                            (when (and url (= rec-type :item.rec-type/discover))
                              {:redirect true
                               :item/url url})))))})

(defresolver clean-title [{:keys [item/title]}]
  {:item/clean-title (str/trim (EmojiParser/removeAllEmojis title))})

(defresolver digest-sends [{:keys [biff/conn session]} items]
  {::pco/input [:xt/id]
   ::pco/output [:item/n-digest-sends]
   ::pco/batch? true}
  (let [results (biffx/q conn
                         {:union
                          [{:select [[:digest/ad :xt/id]
                                     [[:count :xt/id]
                                      :item/n-digest-sends]]
                            :from :digest
                            :where [:and
                                    [:= :digest/user (:uid session)]
                                    [:in :digest/ad (mapv :xt/id items)]]}
                           {:select [[:digest-item/item :xt/id]
                                     [[:count :digest-item/digest]
                                      :item/n-digest-sends]]
                            :from :digest-item
                            :join [:digest [:= :digest-item/digest :digest._id]]
                            :where [:and
                                    [:= :digest/user (:uid session)]
                                    ;; TODO seems like it might be faster without this assuming #
                                    ;; digests is << # candidate items
                                    [:in :digest-item/item (mapv :xt/id items)]]}]})]
    (lib.core/restore-order items
                            :xt/id
                            results
                            (fn [{:keys [xt/id]}]
                              {:xt/id id
                               :item/n-digest-sends 0}))))

(def module
  {:resolvers [user-favorites
               user-bookmarks
               clean-html
               content
               doc-type
               from-params
               from-params-unsafe
               image-from-feed
               item-id
               email-sub
               feed-sub
               unread
               user-item
               xt-id
               n-skipped
               unread-bookmarks
               history-items
               current-item
               source
               digest-url
               clean-title
               digest-sends]})
