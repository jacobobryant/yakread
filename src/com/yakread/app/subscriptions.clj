(ns com.yakread.app.subscriptions
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.route :as lib.route :refer [defget defpost-pathom href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]
   [xtdb.api :as-alias xt]))

(defpost-pathom unsubscribe
  [{:params/sub [:sub/id
                 :sub/doc-type
                 {(? :sub/latest-item)
                  [:item/id
                   (? :item.email/list-unsubscribe)
                   (? :item.email/list-unsubscribe-post)]}]}
   (? :params/redirect-url)]
  (fn [_ {{:sub/keys [id doc-type latest-item] :as sub} :params/sub
          :keys [params/redirect-url]}]
    (let [base {:status 204
                :headers {"HX-Location" (or redirect-url (href routes/subs-page))}
                :biff.pipe/next [:biff.pipe/tx]
                :biff.pipe.tx/retry false}]
      (case doc-type
        :sub/feed
        (merge base
               {:biff.pipe.tx/input [[::xt/delete id]]})

        :sub/email
        (let [{:item.email/keys [list-unsubscribe list-unsubscribe-post]} latest-item
              url (second (re-find #"<(http[^>]+)>" (or list-unsubscribe "")))
              email (second (re-find #"<mailto:([^>]+)>" (or list-unsubscribe "")))]
          (merge-with into
                      base
                      {:biff.pipe.tx/input [{:db/doc-type :sub/email
                                             :db/op :update
                                             :xt/id id
                                             :sub.email/unsubscribed-at :db/now}]}
                      (cond
                        (and url (= (some-> list-unsubscribe-post str/lower-case)
                                    "list-unsubscribe=one-click"))
                        {:biff.pipe/next [:biff.pipe/http]
                         :biff.pipe.http/input {:url url :method :post}
                         :biff.pipe/catch :biff.pipe/http}

                        email
                        {:biff.pipe/next [:biff.pipe/email]
                         :biff.pipe.email/input {:to email :subject "unsubscribe"}}

                        url
                        {:headers {"HX-Trigger" (cheshire/generate-string {:yak/open-new-tab url})}})))))))

(defpost-pathom toggle-pin
  [{:params/sub [:sub/id
                 :sub/doc-type
                 (? :sub/pinned-at)]}]
  (fn [_ {{:sub/keys [id pinned-at doc-type]}
          :params/sub}]
    {:biff.pipe/next             [:biff.pipe/tx (lib.pipe/render `page-content-route)]
     :biff.pipe.tx/input         [{:db/doc-type   doc-type
                                   :db/op         :update
                                   :xt/id         id
                                   :sub/pinned-at (if pinned-at
                                                    :db/dissoc
                                                    :db/now)}]}))

(defpost-pathom resubscribe
  [{:params.checked/subscriptions
    [:sub/id]}]
  (fn [_ {:keys [params.checked/subscriptions]}]
    {:biff.pipe/next [:biff.pipe/tx]
     :biff.pipe.tx/input (for [{:sub/keys [id]} subscriptions]
                           {:db/doc-type :sub/email
                            :db/op :update
                            :xt/id id
                            :sub.email/unsubscribed-at :db/dissoc})
     :status 204
     :headers {"HX-Redirect" (href `unsubs-page)}}))

(defresolver sub-card [{:sub/keys [id title unread published-at pinned-at]
                        :keys [sub.feed/feed sub.email/from]}]
  #::pco{:input [:sub/id
                 :sub/title
                 :sub/unread
                 {(? :sub.feed/feed) [:feed/url]}
                 (? :sub.email/from)
                 (? :sub/published-at)
                 (? :sub/pinned-at)]}
  {:sub.view/card
   [:.relative
    [:div {:class '[absolute top-1.5 right-4 sm:right-0]}
     (ui/overflow-menu
      {:ui/rounded true}
      (ui/overflow-button
       {:hx-post (href toggle-pin {:sub/id id
                                   :tab (if pinned-at "pinned" "unpinned")})
        :hx-target "#content"
        :hx-swap "innerHTML"}
       (if pinned-at "Unpin" "Pin"))
      (ui/overflow-button
       {:hx-post (href unsubscribe {:sub/id id})
        :hx-confirm (ui/confirm-unsub-msg title)}
       "Unsubscribe"))]
    [:a {:href (href routes/view-sub-page id)
         :class (concat '[block
                          bg-white
                          shadow
                          p-2
                          text-sm
                          hover:bg-neut-50
                          max-sm:mx-4]
                        (when (< 0 unread)
                          '[border-l-4
                            border-tealv-500]))}
     [:.truncate.font-semibold.mr-6 (or (not-empty title) "[no title]")]
     [:.text-neut-600.mr-6 unread " unread posts"
      ui/interpunct
      [:span.underline
       (cond
         feed "rss"
         from "email")]]]]})

(defn- empty-state []
  (ui/empty-page-state {:icons ["envelope-regular-sharp"
                                "square-rss-regular-sharp"]
                        :text [:span {:class '["max-w-[22rem]"
                                               inline-block]}
                               "Customize your experience by subscribing to newsletters and RSS feeds."]
                        :btn-label "Add subscriptions"
                        :btn-href (href routes/add-sub-page)}))

(defget unsubs-page "/subscriptions/unsubscribed"
  [:app.shell/app-shell
   {:session/user
    [{:user/unsubscribed
      [:sub/id
       :sub/title
       :sub.email/unsubscribed-at]}]}]
  (fn [_ {:keys [app.shell/app-shell]
          {:user/keys [unsubscribed]} :session/user}]
    (app-shell
     {:title "Unsubscribed"}
     (ui/page-header {:title     "Unsubscribed"
                      :back-href (href routes/subs-page)})
     (ui/page-well
      [:form.space-y-8
       {:hx-post (href resubscribe)}
       (ui/callout {:ui/type :info}
                   "These newsletters are hidden from your subscriptions list. Even if you move a
                    newsletter back to your subscriptions, you won't receive new posts unless you
                    re-subscribe on the newsletter's website.")
       (for [{:sub/keys [id title]} (sort-by :sub.email/unsubscribed-at #(compare %2 %1) unsubscribed)]
         (ui/checkbox {:name (str "subs[" id "]") :ui/label title}))
       (ui/button {:type "submit"} "Move to subscriptions")]))))

(defget page-content-route "/subscriptions/content"
  [{:session/user
    [{:user/subscriptions [:sub/id
                           :sub.view/card
                           (? :sub/published-at)
                           (? :sub/pinned-at)]}
     {:user/unsubscribed [:sub/id]}]}]
  (fn [{:keys [params]} {{:user/keys [subscriptions unsubscribed]} :session/user}]
    (let [{pinned-subs true unpinned-subs false} (group-by (comp some? :sub/pinned-at) subscriptions)
          show-tabs (every? not-empty [pinned-subs unpinned-subs])
          active-tab (if (= (:tab params) "unpinned") :unpinned :pinned)]
      [:.h-full {:id (ui/dom-id ::content)}
       (ui/page-header {:title    "Subscriptions"
                        :add-href (href routes/add-sub-page)
                        :actions (when (not-empty unsubscribed)
                                   (ui/overflow-menu
                                    {:ui/rounded true
                                     :ui/icon "ellipsis-regular"
                                     :ui/hover-shade :dark}
                                    (ui/overflow-button
                                     {:href (href unsubs-page)}
                                     "Unsubscribed newsletters")))})
       (if (empty? subscriptions)
         (empty-state)
         [:div.grow.flex.flex-col
          (when show-tabs
            [:.flex.gap-4.mb-6.max-sm:mx-4
             (ui/pill {:ui/label "Pinned"
                       :class '[pinned-tab]
                       :data-active (str (= active-tab :pinned))
                       :_ (str "on click set @data-active of .unpinned-tab to 'false' "
                               "then set @data-active of .pinned-tab to 'true'")})
             (ui/pill {:class '[unpinned-tab]
                       :ui/label "Unpinned"
                       :data-active (str (= active-tab :unpinned))
                       :_ (str "on click set @data-active of .pinned-tab to 'false' "
                               "then set @data-active of .unpinned-tab to 'true'")})])
          (for [[tab subscriptions] [[:pinned pinned-subs]
                                     [:unpinned unpinned-subs]]
                :when (not-empty subscriptions)]
            (ui/card-grid
             {:ui/cols 5
              :class [(if (= tab :pinned) "pinned-tab" "unpinned-tab")
                      "data-[active=false]:hidden"]
              :data-active (str (or (not show-tabs) (= tab active-tab)))}
             (->> subscriptions
                  (sort-by :sub/published-at #(compare %2 %1))
                  (mapv :sub.view/card))))])])))

(defget page-route "/subscriptions"
  [:app.shell/app-shell (? :user/current)]
  (fn [{:keys [params]} {:keys [app.shell/app-shell] user :user/current}]
    (app-shell
     {:wide true}
     (if user
       [:.grow.flex.flex-col#content
        (ui/lazy-load-spaced (href page-content-route params))]
       [:<>
        (ui/page-header {:title    "Subscriptions"
                         :add-href (href routes/add-sub-page)})
        (empty-state)]))))

(def module
  {:resolvers [sub-card]
   :routes [page-route
            ["" {:middleware [lib.mid/wrap-signed-in]}
             page-content-route
             toggle-pin
             resubscribe
             unsubs-page
             unsubscribe]]})
