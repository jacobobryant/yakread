(ns com.yakread.app.read-later
  (:require
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(defn- empty-state []
  (ui/empty-page-state {:icons ["bookmark-regular-sharp"]
                        :text "Bookmark articles so you can read them later."
                        :btn-label "Add bookmarks"
                        :btn-href (href routes/add-bookmark-page)}))

(fx/defroute-pathom page-content "/read-later/content"
  [{:session/user
    [{:user/bookmarks [:item/id
                       :item/ui-small-card
                       {:item/user-item [:user-item/bookmarked-at]}]}
     :user/more-bookmarks]}]

  :get
  (fn [{:keys [params]} {{:user/keys [bookmarks more-bookmarks]} :session/user}]
    (cond
      (not-empty bookmarks)
      [:<>
      (ui/card-grid
        {:ui/cols 4}
        (->> bookmarks
             (sort-by (comp :user-item/bookmarked-at :item/user-item) #(compare %2 %1))
             (mapv :item/ui-small-card)))
       (when more-bookmarks
         [:<>
          [:.h-4]
          (ui/lazy-load-spaced
           (href page-content {:pathom-params
                               {:user/bookmarks
                                {:before (get-in (peek bookmarks)
                                                 [:item/user-item :user-item/bookmarked-at])}}}))])]

      (nil? (get-in params [:pathom-params :user/bookmarks]))
      (empty-state)

      :else [:<>])))

(fx/defroute-pathom page "/read-later"
  [:app.shell/app-shell (? :user/current)]

  :get
  (fn [_ {:keys [app.shell/app-shell] user :user/current}]
    (app-shell
     {:wide true}
     [:<>
      (ui/page-header {:title    "Read later"
                       :add-href (href routes/add-bookmark-page)})
      (if user
        [:div#content.h-full (ui/lazy-load-spaced (href page-content))]
        (empty-state))])))

(def module
  {:routes [page
            ["" {:middleware [lib.mid/wrap-signed-in]}
             page-content]]})
