(ns com.yakread.app.read-later.add
  (:require
   [clojure.string :as str]
   [com.yakread.lib.item :as lib.item]
   [com.yakread.lib.middleware :as lib.middle]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.route :refer [defget defpost href hx-redirect]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(def add-item-async
  (comp (lib.pipe/make
         (lib.item/add-item-machine
          {:user-item-kvs {:user-item/bookmarked-at :db/now
                           :user-item/favorited-at :db/dissoc
                           :user-item/disliked-at :db/dissoc
                           :user-item/reported-at :db/dissoc
                           :user-item/report-reason :db/dissoc}}))
        (fn [{{:keys [user/id url]} :biff/job :as ctx}]
          (-> ctx
              (assoc-in [:session :uid] id)
              (assoc-in [:params :url] url)))))

(defpost add-item
  (lib.item/add-item-machine
   {:user-item-kvs {:user-item/bookmarked-at :db/now
                    :user-item/favorited-at :db/dissoc
                    :user-item/disliked-at :db/dissoc
                    :user-item/reported-at :db/dissoc
                    :user-item/report-reason :db/dissoc}
    :redirect-to `page}))

(defpost add-batch
  :start
  (fn [{:keys [session] {:keys [batch]} :params}]
    (if-some [urls (->> (str/split (or batch "") #"\s+")
                        (filter #(str/starts-with? % "http"))
                        not-empty)]
      (merge (hx-redirect `page {:batch-added (count urls)})
             {:biff.pipe/next (for [[i url] (map-indexed vector urls)]
                                {:biff.pipe/current :biff.pipe/queue
                                 :biff.pipe.queue/id ::add-item
                                 :biff.pipe.queue/job {:user/id (:uid session)
                                                       :url url
                                                       :biff/priority i}})})
      (hx-redirect `page {:batch-error true}))))

(defget page "/read-later/add"
  [:app.shell/app-shell
   {(? :session/user) [:user/id]}]
  (fn [{:keys [biff/base-url params] :as ctx}
       {:keys [app.shell/app-shell session/user]}]
    (app-shell
     {:title "Add bookmarks"}
     (ui/page-header {:title     "Add bookmarks"
                      :back-href (href routes/bookmarks-page)})
     [:fieldset.disabled:opacity-60
      {:disabled (when-not user "disabled")}
      (ui/page-well
       (ui/section
        {}
        (when (:added params)
          (ui/callout {:ui/type :info :ui/icon nil} "Bookmark added."))
        (when (:error params)
          (ui/callout {:ui/type :error :ui/icon nil} "We weren't able to add that bookmark."))
        (let [modal-open (ui/random-id)]
          [:form {:hx-post (href add-item)
                  :hx-indicator (str "#" (ui/dom-id ::item-indicator))}
           (ui/modal
            {:open modal-open
             :title "Add articles via bookmarklet"}
            [:.p-4
             [:p "You can install the bookmarklet by dragging this link on to your browser toolbar or
                  bookmarks menu:"]
             [:p.my-6 [:a.text-xl.text-blue-600
                       {:href (str "javascript:window.location=\""
                                   base-url
                                   (href page)
                                   "?url=\"+encodeURIComponent(document.location)")}
                       "Read later | Yakread"]]
             [:p.mb-0 "Then click the bookmarklet to add the current article to Yakread."]])
           (ui/form-input
            {:ui/label "Article URL"
             :ui/submit-text "Add"
             :ui/description [:<> "You can also "
                              [:button.link {:type "button"
                                             :data-on-click (str "$" modal-open " = true")}
                               "add articles via bookmarklet"] "."]
             :ui/indicator-id (ui/dom-id ::item-indicator)
             :name "url"
             :value (:url params)
             :required true})])

        (when-some [n (:batch-added params)]
          (ui/callout {:ui/type :info :ui/icon nil} 
                      (str "Added " (ui/pluralize n "bookmark") "."
                           (when (< 1 n)
                             " There may be a delay before they show up in your account."))))
        (when (:batch-error params)
          (ui/callout {:ui/type :error :ui/icon nil} "We weren't able to add those bookmarks."))
        [:form {:hx-post (href add-batch)
                :hx-indicator (str "#" (ui/dom-id ::batch-indicator))}
         (ui/form-input
          {:ui/input-type :textarea
           :ui/label "List of article URLs, one per line"
           :ui/indicator-id (ui/dom-id ::batch-indicator)
           :ui/submit-text "Add"
           :name "batch"})]))])))

(def module
  {:routes [page
            ["" {:middleware [lib.middle/wrap-signed-in]}
             add-item
             add-batch]]
   :queues [{:id ::add-item
             :consumer #'add-item-async
             :n-threads 5}]})
