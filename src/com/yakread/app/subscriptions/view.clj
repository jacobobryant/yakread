(ns com.yakread.app.subscriptions.view
  (:require
   [com.biffweb :as biff]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.route :as lib.route :refer [defget defpost-pathom href]]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(defpost-pathom mark-read
  [{:session/user [:xt/id]}
   {:params/item [:xt/id]}]
  (fn [{:keys [biff/db]} {:keys [session/user params/item]}]
    ;; TODO
    #_{:status 204
     :biff.pipe/next [:biff.pipe/tx]
     :biff.pipe.tx/input (when-not (biff/lookup-id
                                    db
                                    :user-item/user (:xt/id user)
                                    :user-item/item (:xt/id item))
                           [{:db/doc-type :user-item
                             :db.op/upsert {:user-item/user (:xt/id user)
                                            :user-item/item (:xt/id item)}
                             :user-item/viewed-at :db/now}])}))

(defpost-pathom mark-all-read
  [{:session/user [:xt/id]}
   {:params/sub [:sub/id
                 {:sub/items [:item/id
                              :item/unread
                              {(? :item/user-item) [:xt/id
                                                    (? :user-item/skipped-at)]}]}]}]
  (fn [_ {:keys [session/user params/sub]}]
    {:status 303
     :headers {"HX-Location" (href `page-route (:sub/id sub))}
     :biff.pipe/next [:biff.pipe/tx]
     :biff.pipe.tx/input
     (for [{:item/keys [id unread]} (:sub/items sub)
           :when unread]
       {:db/doc-type :user-item
        :db.op/upsert {:user-item/user (:xt/id user)
                       :user-item/item id}
        :user-item/skipped-at [:db/default :db/now]})}))

(defget read-content-route "/sub-item/:item-id/content"
  [{(? :params/item) [:item/ui-read-content
                      {:item/sub [:sub/id
                                  :sub/title
                                  (? :sub/subtitle)]}]}]
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

(def read-page-route
  ["/sub-item/:item-id"
   {:name ::read-page-route
    :get
    (let [record-click-url (fn [item]
                             (href mark-read {:item/id (:item/id item)}))]
      (lib.route/wrap-nippy-params
       (lib.pipe/make
        :start
        (lib.pipe/pathom-query [{(? :params/item) [:item/id
                                                   (? :item/url)]}
                                {:session/user [(? :user/use-original-links)]}]
                               :start*)

        :start*
        (fn [{:keys [biff.pipe.pathom/output]}]
          (let [{:keys [params/item session/user]} output
                {:item/keys [id url]} item
                {:user/keys [use-original-links]} user]
            (cond
              (nil? id)
              {:status 303
               :headers {"Location" (href routes/subs-page)}}

              (and use-original-links url)
              (ui/redirect-on-load {:redirect-url url
                                    :beacon-url (record-click-url item)})

              :else
              {:biff.pipe/next [:biff.pipe/pathom :render]
               :biff.pipe.pathom/query [:app.shell/app-shell
                                        {:params/item [:item/id
                                                       :item/title
                                                       {:item/sub [:sub/id
                                                                   :sub/title]}]}]})))

        :render
        (fn [{:keys [biff.pipe.pathom/output]}]
          (let [{:keys [app.shell/app-shell params/item]} output
                {:item/keys [id title sub]} item]
            (app-shell
             {:title title}
             [:div {:hx-post (record-click-url item) :hx-trigger "load" :hx-swap "outerHTML"}]
             (ui/lazy-load-spaced (href read-content-route id))))))))}])

(defget page-content-route "/subscription/:sub-id/content"
  [{:params/sub [:sub/id
                 :sub/title
                 {:sub/items
                  [:item/ui-read-more-card
                   :item/published-at]}]}]
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

(defget page-route "/subscription/:sub-id"
  [:app.shell/app-shell
   {:params/sub [:sub/id
                 :sub/title
                 (? :sub/subtitle)]}]
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
