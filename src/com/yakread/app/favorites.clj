(ns com.yakread.app.favorites
  (:require
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [defget href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(defn- empty-state []
  (ui/empty-page-state {:icons ["star-regular-sharp"]
                        :text "Your starred articles will be saved here."
                        :btn-label "Add articles"
                        :btn-href (href routes/add-favorite-page)}))

(fx/defroute-pathom page-content "/favorites/content"
  [{:session/user
    [{:user/favorites [:item/id
                       :item/ui-small-card
                       {:item/user-item [:user-item/favorited-at]}]}]}]

  :get
  (fn [_ {{:user/keys [favorites]} :session/user}]
    (if (empty? favorites)
      (empty-state)
      (ui/card-grid
       {:ui/cols 4}
       (->> favorites
            (sort-by (comp :user-item/favorited-at :item/user-item) #(compare %2 %1))
            (mapv :item/ui-small-card))))))

(fx/defroute-pathom page "/favorites"
  [:app.shell/app-shell (? :user/current)]

  :get
  (fn [_ {:keys [app.shell/app-shell] user :user/current}]
    (app-shell
     {:wide true}
     [:<>
      (ui/page-header {:title    "Favorites"
                       :add-href (href routes/add-favorite-page)})
      (if user
        [:div#content.h-full (ui/lazy-load-spaced (href page-content))]
        (empty-state))])))

(def module
  {:routes [page
            ["" {:middleware [lib.mid/wrap-signed-in]}
             page-content]]})
