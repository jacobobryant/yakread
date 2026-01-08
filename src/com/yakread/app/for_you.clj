(ns com.yakread.app.for-you
  (:require
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]
   [com.yakread.util.biff-staging :as biffs]))

(defn- skip-tx [{:keys [biff/conn t]
                 new-skips :skip
                 user-id :user/id
                 rec-id :rec/id}]
  (when (and new-skips t)
    (let [[{[reclist] :reclists existing-skips :skips}]
          (biffx/q conn
                   (biffs/bundle
                    {:reclists {:select [:xt/id :reclist/clicked]
                                :from :reclist
                                :where [:and
                                        [:= :reclist/user user-id]
                                        [:= :reclist/created-at t]]}
                     :skips {:select [:skip._id :skip/item]
                             :from :reclist
                             :join [:skip [:= :skip/reclist :reclist._id]]
                             :where [:and
                                     [:= :reclist/user user-id]
                                     [:= :reclist/created-at t]]}}))

          old-clicked (:reclist/clicked reclist #{})
          new-clicked (conj old-clicked rec-id)
          delete-skips (into []
                             (comp (filter (comp new-clicked :skip/item))
                                   (map :xt/id))
                             existing-skips)
          create-skips-for (into []
                                 (remove (into new-clicked (map :skip/item) existing-skips))
                                 new-skips)
          reclist-id (or (:xt/id reclist)
                         (biffs/gen-uuid user-id))]
      (when (not= old-clicked new-clicked)
        (concat
         [[:biff/upsert :reclist [:reclist/user :reclist/created-at]
           {:reclist/user user-id
            :reclist/created-at t
            :reclist/clicked new-clicked
            :xt/id reclist-id}]]

         (when (not-empty delete-skips)
           [(into [:delete :skip] delete-skips)])

         (when (not-empty create-skips-for)
           [(into [:biff/upsert :skip [:skip/reclist :skip/item]]
                  (for [item-id create-skips-for]
                    {:skip/reclist reclist-id
                     :skip/item item-id
                     :xt/id (biffs/gen-uuid reclist-id)}))]))))))

(fx/defroute record-item-click
  :post
  (fn [{:biff/keys [conn safe-params now]}]
    (let [{:keys [action t skip] item-id :item/id user-id :user/id} safe-params]
      (if (not= action :action/click-item)
        (ui/on-error {:status 400})
        {:status 204
         :biff.fx/tx (concat
                      (skip-tx {:biff/conn conn
                                :user/id user-id
                                :rec/id item-id
                                :skip skip
                                :t t})
                      (when (empty? (biffx/q conn
                                             {:select 1
                                              :from :user-item
                                              :where [:and
                                                      [:= :user-item/user user-id]
                                                      [:= :user-item/item item-id]
                                                      [:is-not :user-item/viewed-at nil]]
                                              :limit 1}))
                        [[:biff/upsert :user-item [:user-item/user :user-item/item]
                          {:user-item/user user-id
                           :user-item/item item-id
                           :user-item/viewed-at now
                           :xt/id (biffs/gen-uuid user-id)}]]))}))))

(fx/defroute record-ad-click
  :post
  (fn [{:biff/keys [conn safe-params now]}]
    (let [{:keys [action skip t ad/click-cost ad.click/source]
           ad-id :ad/id
           user-id :user/id} safe-params]
      (if (not= action :action/click-ad)
        (ui/on-error {:status 400})
        {:status 204
         :biff.fx/tx (concat
                      (skip-tx {:biff/conn conn
                                :user/id user-id
                                :rec/id ad-id
                                :skip  skip
                                :t t})
                      (when (empty?
                             (biffx/q conn
                                      {:select 1
                                       :from :ad-click
                                       :where [:and
                                               [:= :ad.click/user user-id]
                                               [:= :ad.click/ad ad-id]]
                                       :limit 1}))
                        [[:biff/upsert :ad-click [:ad.click/user :ad.click/ad]
                          {:ad.click/user user-id
                           :ad.click/ad ad-id
                           :ad.click/created-at now
                           :ad.click/cost click-cost
                           :ad.click/source (or source :web)}]]))}))))

(fx/defroute-pathom page-content-route "/for-you/content"
  [{(? :session/user)
    [{(? :user/current-item)
      [:item/ui-read-more-card]}
     {:user/for-you-recs
      [:xt/id
       :rec/ui-read-more-card]}]}
   {(? :session/anon)
    [{:user/discover-recs
      [:item/id
       :item/url
       :item/ui-read-more-card]}]}]

  :get
  (fn [{:keys [biff/now params]} {:keys [user/discover-recs session/user session/anon]
                                  {:user/keys [current-item for-you-recs]} :session/user}]
    [:div {:class '[flex flex-col gap-6
                    max-w-screen-sm
                    h-full]}
     (when-let [{:keys [item/ui-read-more-card]} (and (:show-continue params) current-item)]
       [:div
        (ui-read-more-card {:on-click-route `read-page-route
                            :highlight-unread false
                            :show-author true})
        [:.h-5.sm:h-4]
        [:div.text-center
         (ui/muted-link {:href (href routes/history)}
                        "View reading history")]])
     (cond
       (every? empty? [for-you-recs (:user/discover-recs anon)])
       [:<>
        [:.grow]
        [:div.text-center "There's no content to recommend yet. Try adding some "
         [:span.inline-block
          (ui/web-link {:href (href routes/subs-page)} "subscriptions")
          " or "
          (ui/web-link {:href (href routes/bookmarks-page)} "bookmarks.")]]
        [:.grow]
        [:.grow]]

       user
       (for [[i {:rec/keys [ui-read-more-card]}] (map-indexed vector for-you-recs)]
         (ui-read-more-card {:on-click-route `read-page-route
                             :on-click-params {:skip (set (mapv :xt/id (take i for-you-recs)))
                                               :t now}
                             :highlight-unread false
                             :show-author true}))

       :else
       (for [[i {:item/keys [ui-read-more-card]}] (map-indexed vector (:user/discover-recs anon))]
         (ui-read-more-card {:highlight-unread false
                             :show-author true
                             :new-tab true})))]))

(fx/defroute-pathom page-route "/for-you"
  [:app.shell/app-shell]

  :get
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {}
     [:div#content.h-full (ui/lazy-load-spaced (href page-content-route {:show-continue true}))])))

(let [record-click-url (fn [{:keys [biff/href-safe
                                    params
                                    session
                                    biff.fx/pathom]}]
                         (href-safe record-item-click
                                    {:action  :action/click-item
                                     :user/id (:uid session)
                                     :item/id (get-in pathom [:params/item :item/id])
                                     :skip    (:skip params)
                                     :t       (:t params)}))]
  (fx/defroute-pathom read-page-route "/item/:item-id"
    [{(? :params/item) [:item/id
                        (? :item/url)]}
     {:session/user [(? :user/use-original-links)]}]

    :get
    (fn [{:keys [params] :as ctx} {:keys [params/item session/user]}]
      (let [{:item/keys [id url]} item
            {:user/keys [use-original-links]} user]
        (cond
          (nil? id)
          {:status 303
           :headers {"Location" (href page-route)}}

          (and use-original-links url)
          (ui/redirect-on-load {:redirect-url url
                                :beacon-url (when-not (:skip-record params)
                                              (record-click-url ctx))})

          :else
          {:biff.fx/next :render
           :biff.fx/pathom [:app.shell/app-shell
                            {:params/item [:item/ui-read-content
                                           :item/id
                                           (? :item/title)]}]})))

    :render
    (fn [{:keys [params] :as ctx} {:keys [app.shell/app-shell params/item]}]
      (let [{:item/keys [ui-read-content title]} item]
        (app-shell
         {:title title}
         (when-not (:skip-record params)
           [:div {:hx-post (record-click-url ctx)
                  :hx-trigger "load"
                  :hx-swap "outerHTML"}])
         (ui-read-content {:leave-item-redirect (href page-route)
                           :unsubscribe-redirect (href page-route)})
         [:div.h-10]
         [:div#content
          [:.flex.justify-center
           {:hx-get (href page-content-route)
            :hx-trigger "load delay:2s"
            :hx-swap "outerHTML"}
           [:img.h-10 {:src ui/spinner-gif}]]])))))

(fx/defroute click-ad-route "/c/:ewt"
  :get
  (fn [{:biff/keys [safe-params href-safe]}]
    (let [{:keys [action ad/url]} safe-params]
      (if (not= action :action/click-ad)
        (ui/on-error {:status 400})
        (ui/redirect-on-load
         {:redirect-url url
          :beacon-url (href-safe record-ad-click safe-params)})))))

(fx/defroute click-item-route "/r/:ewt"
  :get
  (fn [{:biff/keys [safe-params href-safe]}]
    (let [{:keys [action item/url item/id redirect]} safe-params]
      (if (not= action :action/click-item)
        (ui/on-error {:status 400})
        (ui/redirect-on-load
         {:redirect-url (if redirect
                          url
                          (href read-page-route id {:skip-record true}))
          :beacon-url (href-safe record-item-click safe-params)})))))

(def module
  {:routes [["" {:middleware [lib.mid/wrap-signed-in]}
             read-page-route]
            page-route
            page-content-route
            record-ad-click
            record-item-click
            click-ad-route
            click-item-route]})
