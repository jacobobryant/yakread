(ns com.yakread.app.for-you.history
  (:require
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(fx/defroute-pathom next-batch "/history/next"
  [{:session/user
    [{:user/history-items [:item/id
                           :item/ui-small-card]}]}]

  :get
  (fn [_ {{:user/keys [history-items]} :session/user}]
    (into [:<>]
          (cond-> (mapv #(ui/card-grid-card {} (:item/ui-small-card %)) history-items)
            (not-empty history-items)
            (conj (ui/lazy-load (href next-batch {:after (:item/id (last history-items))})))))))

(fx/defroute-pathom page "/history"
  [:app.shell/app-shell :user/current]

  :get
  (fn [_ {:keys [app.shell/app-shell] user :user/current}]
    (app-shell
     {:wide true}
     [:<>
      (ui/page-header {:title    "Reading History"
                       :back-href (href routes/for-you)})
      [:div#content.h-full
       (ui/card-grid*
        {:ui/cols 4}
        (ui/lazy-load {:class '[col-span-full]} (href next-batch)))]])))

(def module
  {:routes [["" {:middleware [lib.mid/wrap-signed-in]}
             page
             next-batch]]})
