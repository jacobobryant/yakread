(ns com.yakread.app.subscriptions.view
  (:require
   [clojure.data.generators :as gen]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.biffweb.experimental :as biffx]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

 :user-item/user 

(fx/defroute-pathom mark-read
  [{:session/user [:xt/id]}
   {:params/item [:xt/id]}]

  :post
  (fn [{:biff/keys [conn now]} {:keys [session/user params/item]}]
    (merge {:status 204}
           (when (empty? (biffx/q conn
                                     {:select :xt/id
                                      :from :user-item
                                      :where [:and
                                              [:= :user-item/user (:xt/id user)]
                                              [:= :user-item/item (:xt/id item)]
                                              [:is-not :user-item/viewed-at nil]]
                                      :limit 1}))
             {:biff.fx/tx [[:patch-docs :user-item
                            {:xt/id (biffx/prefix-uuid (:xt/id user) (gen/uuid))
                             :user-item/user (:xt/id user)
                             :user-item/item (:xt/id item)
                             :user-item/viewed-at now}]]}))))

(fx/defroute-pathom mark-all-read
  [{:session/user [:xt/id]}
   {:params/sub [:sub/id
                 {:sub/items [:item/id
                              :item/unread]}]}]

  :post
  (fn [{:keys [biff/now]} {:keys [session/user params/sub]}]
    {:status 303
     :headers {"HX-Location" (href `page-route (:sub/id sub))}
     :biff.fx/tx [(into [:biff/upsert :user-item [:user-item/user :user-item/item]]
                        (for [{:item/keys [id unread]} (:sub/items sub)
                              :when unread]
                          {:xt/id (biffx/prefix-uuid (:xt/id user) (gen/uuid))
                           :user-item/user (:xt/id user)
                           :user-item/item id
                           :user-item/skipped-at now}))]}))

(fx/defroute-pathom read-content-route "/sub-item/:item-id/content"
  [{(? :params/item) [:item/ui-read-content
                      {:item/sub [:sub/id
                                  :sub/title
                                  (? :sub/subtitle)]}]}]

  :get
  (fn [_ {{:item/keys [ui-read-content sub]
           :as item} :params/item}]
    (when item
      [:<>
       (ui-read-content {:leave-item-redirect (href `page-route (get-in item [:item/sub :sub/id]))
                         :unsubscribe-redirect (href routes/subs-page)})
       [:div.h-10]
       (ui/page-header {:title     (:sub/title sub)
                        :subtitle  (:sub/subtitle sub)
                        :back-href (href routes/subs-page)
                        :no-margin true})
       [:.h-4]
       [:div#content (ui/lazy-load-spaced (href `page-content-route (:sub/id sub)))]])))

(let [record-click-url (fn [item]
                         (href mark-read {:item/id (:item/id item)}))]
  (fx/defroute-pathom read-page-route "/sub-item/:item-id"
    [{(? :params/item) [:item/id
                        (? :item/url)]}
     {:session/user [(? :user/use-original-links)]}]

    :get
    (fn [_ {:keys [params/item session/user]}]
      (let [{:item/keys [id url]} item
            {:user/keys [use-original-links]} user]
        (cond
          (nil? id)
          {:status 303
           :headers {"Location" (href routes/subs-page)}}

          (and use-original-links url)
          (ui/redirect-on-load {:redirect-url url
                                :beacon-url (record-click-url item)})

          :else
          {:biff.fx/next :render
           :biff.fx/pathom [:app.shell/app-shell
                            {:params/item [:item/id
                                           :item/title
                                           {:item/sub [:sub/id
                                                       :sub/title]}]}]})))

    :render
    (fn [_ {:keys [app.shell/app-shell params/item]}]
      (let [{:item/keys [id title sub]} item]
        (app-shell
         {:title title}
         [:div {:hx-post (record-click-url item) :hx-trigger "load" :hx-swap "outerHTML"}]
         (ui/lazy-load-spaced (href read-content-route id)))))))

(fx/defroute-pathom page-content-route "/subscription/:sub-id/content"
  [{:params/sub [:sub/id
                 :sub/title
                 {:sub/items
                  [:item/ui-read-more-card
                   :item/published-at]}]}]

  :get
  (fn [_ {{:sub/keys [id title items]} :params/sub}]
    [:<>
     [:.flex.gap-4.max-sm:px-4
      (ui/button {:ui/type :secondary
                  :ui/size :small
                  :hx-post (href mark-all-read {:sub/id id})}
        "Mark all as read")
      (ui/button {:ui/type :secondary
                  :ui/size :small
                  :hx-post (href routes/unsubscribe! {:sub/id id})
                  :hx-confirm (ui/confirm-unsub-msg title)}
        "Unsubscribe")]
     [:.h-6]
     [:div {:class '[flex flex-col gap-6
                     max-w-screen-sm]}
      (for [{:item/keys [ui-read-more-card]}
            (sort-by :item/published-at #(compare %2 %1) items)]
        (ui-read-more-card {:on-click-route read-page-route
                            :highlight-unread true
                            :show-author false}))]]))

(fx/defroute-pathom page-route "/subscription/:sub-id"
  [:app.shell/app-shell
   {:params/sub [:sub/id
                 :sub/title
                 (? :sub/subtitle)]}]

  :get
  (fn [_ {:keys [app.shell/app-shell]
          {:sub/keys [id title subtitle]} :params/sub}]
    (app-shell
     {:title title}
     (ui/page-header {:title     title
                      :subtitle  subtitle
                      :back-href (href routes/subs-page)
                      :no-margin true})
     [:.h-4]
     [:div#content (ui/lazy-load-spaced (href page-content-route id))])))

(def module
  {:routes [["" {:middleware [lib.middle/wrap-signed-in]}
             page-route
             page-content-route
             read-page-route
             read-content-route
             mark-read
             mark-all-read]]})
