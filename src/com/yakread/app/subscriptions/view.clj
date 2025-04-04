(ns com.yakread.app.subscriptions.view
  (:require [clojure.string :as str]
            [cheshire.core :as cheshire]
            [com.biffweb :as biff :refer [<<-]]
            [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
            [com.yakread.lib.content :as lib.content]
            [com.yakread.lib.htmx :as lib.htmx]
            [com.yakread.lib.icons :as lib.icons]
            [com.yakread.lib.middleware :as lib.middle]
            [com.yakread.lib.pipeline :as lib.pipe]
            [com.yakread.lib.route :refer [defget defpost-pathom href ?]]
            [com.yakread.lib.serialize :as lib.serialize]
            [com.yakread.lib.ui :as ui]
            [com.yakread.routes :as routes]
            [com.yakread.model.subscription :as model.sub]
            [lambdaisland.uri :as uri]
            [xtdb.api :as xt]
            [rum.core :as rum]))

(defn- redirect-to-sub [sub-id]
  (if sub-id
    (href `page-route sub-id)
    (href routes/subs-page)))

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

(defget read-content-route "/dev/sub-item/:item-id/content"
  [{(? :params/item) [:item/ui-read-content
                      {:item/sub [:sub/id
                                  :sub/title]}]}]
  (fn [_ {{:item/keys [ui-read-content sub]
           :as item} :params/item}]
    (when item
      [:<>
       (ui-read-content {})
       [:div.h-10]
       (ui/page-header {:title     (:sub/title sub)
                        :back-href (href routes/subs-page)})
       [:div#content (ui/lazy-load-spaced (href `page-content-route (:sub/id sub)))]])))

(defget read-page-route "/dev/sub-item/:item-id"
  [:app.shell/app-shell
   {(? :params/item) [:item/id
                      :item/title
                      {:item/sub [:sub/id
                                  :sub/title]}]}]
  (fn [_ {:keys [app.shell/app-shell]
          {:item/keys [id title sub] :as item} :params/item}]
    (if (nil? item)
      {:status 303
       :headers {"Location" (href routes/subs-page)}}
      (app-shell
       {:title title}
       (ui/lazy-load-spaced (href read-content-route id))))))

(defget page-content-route "/dev/subscription/:sub-id/content"
  [{:params/sub [:sub/id
                 :sub/title
                 {:sub/items
                  [:item/ui-read-more-card]}]}]
  (fn [_ {{:sub/keys [id title items]} :params/sub}]
    [:<>
     [:.flex.gap-4.max-sm:px-4
      {:class '["-mt-4" mb-8]}
      (ui/button {:ui/type :secondary
                  :ui/size :small
                  :hx-post (href mark-all-read {:sub/id id})}
        "Mark all as read")
      (ui/button {:ui/type :secondary
                  :ui/size :small
                  :hx-post (href routes/unsubscribe! {:sub/id id})
                  :hx-confirm (ui/confirm-unsub-msg title)}
        "Unsubscribe")]
     [:div {:class '[flex flex-col gap-6
                     max-w-screen-sm]}
      (for [{:item/keys [ui-read-more-card]}
            (sort-by :item/published-at #(compare %2 %1) items)]
        (ui-read-more-card {:on-click-route read-page-route
                            :highlight-unread true
                            :show-author false}))]]))

(defget page-route "/dev/subscription/:sub-id"
  [:app.shell/app-shell
   {:params/sub [:sub/id
                 :sub/title]}]
  (fn [_ {:keys [app.shell/app-shell]
          {:sub/keys [id title]} :params/sub}]
    (app-shell
     {:title title}
     (ui/page-header {:title     title
                      :back-href (href routes/subs-page)})
     [:div#content (ui/lazy-load-spaced (href page-content-route id))])))

(def module
  {:routes [["" {:middleware [lib.middle/wrap-signed-in]}
             page-route
             page-content-route
             read-page-route
             read-content-route
             mark-read
             mark-unread
             toggle-favorite
             not-interested
             mark-all-read]]})
