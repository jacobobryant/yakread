(ns com.yakread.app.for-you
  (:require [com.biffweb :as biff]
            [com.yakread.lib.content :as lib.content]
            [com.yakread.lib.middleware :as lib.mid]
            [com.yakread.lib.route :refer [defget defpost-pathom href ?]]
            [com.yakread.lib.ui :as ui]))

(defget page-content-route "/dev/for-you/content"
  [{:session/user
    [{:user/for-you-recs
      [:item/ui-read-more-card]}]}]
  (fn [_ {{:user/keys [for-you-recs]} :session/user}]
    [:div {:class '[flex flex-col gap-6
                    max-w-screen-sm]}
     (for [{:item/keys [ui-read-more-card]} for-you-recs]
       (ui-read-more-card {:on-click-route read-page-route
                           :highlight-unread false
                           :show-author true}))]))

(defget read-page-route "/dev/item/:item-id"
  [:app.shell/app-shell
   {(? :params/item) [:item/ui-read-content
                      :item/id
                      :item/title]}]
  (fn [_ {:keys [app.shell/app-shell]
          {:item/keys [ui-read-content id title] :as item} :params/item}]
    (if (nil? item)
      {:status 303
       :headers {"Location" (href `page-route)}}
      (app-shell
       {:title title}
       (ui-read-content {})
       [:div.h-10]
       ;; todo make sure mark-read finishes before this queries
       [:div#content (ui/lazy-load-spaced (href page-content-route))]))))

(defget page-route "/dev/for-you"
  [:app.shell/app-shell]
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {}
     [:div#content (ui/lazy-load-spaced (href page-content-route))])))

(def module
  {:routes [page-route
            page-content-route
            read-page-route]})
