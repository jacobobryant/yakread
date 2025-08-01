(ns com.yakread.app.favorites.add
  (:require
   [com.yakread.lib.item :as lib.item]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.pathom :as lib.pathom :refer [?]]
   [com.yakread.lib.route :refer [defget defpost href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(defpost add-item
  (lib.item/add-item-pipeline
   {:user-item-kvs {:user-item/favorited-at :db/now
                    :user-item/disliked-at :db/dissoc
                    :user-item/reported-at :db/dissoc
                    :user-item/report-reason :db/dissoc
                    :user-item/bookmarked-at :db/dissoc}
    :redirect-to `page}))

(defget page "/dev/favorites/add"
  [:app.shell/app-shell
   {(? :session/user) [:user/id]}]
  (fn [{:keys [params] :as ctx}
       {:keys [app.shell/app-shell session/user]}]
    (app-shell
     {:title "Add favorites"}
     (ui/page-header {:title     "Add favorites"
                      :back-href (href routes/favorites-page)})
     [:fieldset.disabled:opacity-60
      {:disabled (when-not user "disabled")}
      (ui/page-well
       (ui/section
        {}
        (when (:added params)
          (ui/callout {:ui/type :info :ui/icon nil} "Article added."))
        (when (:error params)
          (ui/callout {:ui/type :error :ui/icon nil} "We weren't able to add that article."))
        (let [modal-open (ui/random-id)]
          [:form
           {:hx-post (href add-item)
            :hx-indicator (str "#" (ui/dom-id ::indicator))}
           (ui/modal
            {:open modal-open
             :title "Add favorites via bookmarklet"}
            [:.p-4
             [:p "You can install the bookmarklet by dragging this link on to your browser toolbar or
                  bookmarks menu:"]
             [:p.my-6 [:a.text-xl.text-blue-600
                       {:href "javascript:window.location=\"https://yakread.com/favorites/add?url=\"+encodeURIComponent(document.location)"}
                       "Add to favorites | Yakread"]]
             [:p.mb-0 "Then click the bookmarklet to add the current article to your favorites."]])
           (ui/form-input
            {:ui/label "Article URL"
             :ui/submit-text "Add"
             :ui/description [:<> "You can also "
                              [:button.link {:type "button"
                                             :data-on-click (str "$" modal-open " = true")}
                               "add articles via bookmarklet"] "."]
             :ui/indicator-id (ui/dom-id ::indicator)
             :name "url"
             :value (:url params)
             :required true})])))])))

(def module
  {:routes [page
            ["" {:middleware [lib.middle/wrap-signed-in]}
             add-item]]})
