(ns com.yakread.ui-components.item.read
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.icons :as lib.icons]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(fx/defroute-pathom mark-unread
  [{:params/item [{:item/user-item [:xt/id]}]}
   :params/redirect-url]

  :post
  (fn [_ {:keys [params/item params/redirect-url]}]
    {:status 204
     :headers {"HX-Location" redirect-url}
     :biff.fx/tx [{:update :user-item
                   :set {:user-item/viewed-at nil
                         :user-item/favorited-at nil
                         :user-item/disliked-at nil
                         :user-item/reported-at nil
                         :user-item/report-reason nil
                         :user-item/skipped-at nil}
                   :where [:= :xt/id (get-in item [:item/user-item :xt/id])]}]}))

(fx/defroute-pathom toggle-favorite
  [{:params/item
    [:item/like-button*
     {:item/user-item
      [:xt/id
       (? :user-item/favorited-at)]}]}]

  :post
  (fn [{:keys [biff/now]} {:keys [params/item]}]
    (let [user-item (:item/user-item item)
          favorited (boolean (:user-item/favorited-at user-item))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body ((:item/like-button* item) {:active (not favorited)})
       :biff.fx/tx [{:update :user-item
                     :set {:user-item/favorited-at (when-not favorited now)
                           :user-item/disliked-at nil
                           :user-item/reported-at nil
                           :user-item/report-reason nil}
                     :where [:= :xt/id (:xt/id user-item)]}]})))

(fx/defroute-pathom not-interested
  [{:params/item [{:item/user-item [:xt/id]}]}
   :params/redirect-url]

  :post
  (fn [{:keys [biff/now]} {:params/keys [item redirect-url]}]
    {:status 204
     :headers {"HX-Location" redirect-url}
     :biff.fx/tx [{:update :user-item
                   :set {:user-item/favorited-at nil
                         :user-item/disliked-at now}
                   :where [:= :xt/id (get-in item [:item/user-item :xt/id])]}]}))

(defn bar-button-icon-label [icon text]
  [:.flex.justify-center
   (lib.icons/base icon
                   {:class '[w-5
                             h-5
                             flex-shrink-0
                             "mt-[2px]"]})
   [:.w-1.sm:w-2]
   [:span text]])

(defn bar-button [{:ui/keys [active icon] :keys [href] :as opts} & contents]
  [(if href :a :button) (ui/dom-opts opts
                                     '[block
                                       hover:bg-neut-50
                                       inter
                                       py-2
                                       w-full]
                                     (when active
                                       'font-semibold))
   (if icon
     (bar-button-icon-label icon contents)
     contents)])

(defresolver like-button* [{:item/keys [id]}]
  {:item/like-button*
   (fn [{:keys [active]}]
     (bar-button
      {:ui/active active
       :ui/icon (if active "star-solid" "star-regular")
       :hx-post (href toggle-favorite {:item/id id})
       :hx-swap "outerHTML"}
      "Like"))})

(defresolver like-button [{:item/keys [like-button* user-item]}]
  #::pco{:input [{(? :item/user-item) [(? :user-item/favorited-at)]}]}
  {:item/like-button
   (like-button* {:active (boolean (:user-item/favorited-at user-item))})})

(defresolver share-button [{:item/keys [url]}]
  {:item/share-button
   (bar-button
    {:data-url url
     :_ "on click
         writeText(@data-url) on navigator.clipboard
         toggle .hidden on .toggle in me
         wait 1s
         toggle .hidden on .toggle in me"}
    [:span.toggle (bar-button-icon-label "share-nodes-regular" "Share")]
    [:span.toggle.hidden.text-gray-700.text-sm "Copied to clipboard."])})

(defn query-encode [s]
  (some-> s
          (java.net.URLEncoder/encode "UTF-8")
          (str/replace "+" "%20")))

(defresolver button-bar [{:keys [com.yakread/sign-redirect]}
                         {:item/keys [id title sub like-button share-button]
                          :item.email/keys [reply-to]}]
  #::pco{:input [:item/id
                 :item/like-button
                 (? :item/title)
                 (? :item/share-button)
                 (? :item.email/reply-to)
                 {(? :item/sub) [:sub/id :sub/title]}]}
  {:item/ui-button-bar
   (fn [{:keys [leave-item-redirect
                unsubscribe-redirect]}]
     (let [leave-item-redirect-params (sign-redirect leave-item-redirect)
           unsubscribe-redirect-params (sign-redirect unsubscribe-redirect)]
       [:div {:class '[bg-white
                       flex
                       sticky
                       bottom-0
                       bg-neut-50]
              :style {:box-shadow "0 -4px 6px -1px rgb(0 0 0 / 0.1), 0 -2px 4px -2px rgb(0 0 0 / 0.1)"}}
        (when share-button
          [:.flex-1 share-button])
        (when reply-to
          [:.flex-1
           (bar-button
            {:ui/icon "reply-regular"
             :href (str "mailto:" (query-encode reply-to) "?subject=" (query-encode (str "Re: " title)))}
            "Reply")])

        ;;(cond
        ;;  (some (or item {}) [:item.rss/feed-url :item/inferred-feed-url])
        ;;  [:.flex-1 (subscribe-button ctx)])
        [:.flex-1 like-button]


        (ui/overflow-menu
         {:ui/direction :up}
         (ui/overflow-button
          {:hx-post (href mark-unread (merge {:item/id id} leave-item-redirect-params))}
          "Mark unread")
         (ui/overflow-button
          {:hx-post (href not-interested (merge {:item/id id} leave-item-redirect-params))}
          "Not interested")
         (when sub
           (ui/overflow-button
            {:hx-post (href routes/unsubscribe! (merge {:sub/id (:sub/id sub)} unsubscribe-redirect-params))
             :hx-confirm (ui/confirm-unsub-msg (:sub/title sub))}
            "Unsubscribe"))
         ;; TODO report button
         )]))})

(defresolver ui-read-content [{:item/keys [id url ui-details doc-type title clean-html ui-button-bar]}]
  {::pco/input [:item/id
                :item/doc-type
                :item/ui-details
                (? :item/url)
                (? :item/title)
                (? :item/clean-html)
                :item/ui-button-bar]}
  {:item/ui-read-content
   (fn [opts]
     [:div {:_ (str "on load wait 100 ms then call resumePosition(me) then "
                    ;; TODO figure out how to not make this global
                    "set window.elt to me "
                    "js document.addEventListener('scroll', (e) => { "
                    "  savePosition(elt); "
                    "});")
            :data-item-id id}
      [:div {:class '[text-sm text-neut-800
                      max-sm:mx-4
                      flex justify-between gap-6]}
       [:div (ui-details {:show-author true})]
       (when url
         [:a {:class '[underline
                       whitespace-nowrap
                       max-sm:hidden]
              :href url :target "_blank"}
          "View original"])]
      [:.h-1]
      [:h1 {:class '[font-bold text-2xl text-neut-900
                     max-sm:mx-4]}
       title]
      (when url
        [:a {:class '[max-sm:mx-4
                      sm:hidden
                      text-neut-800 text-sm
                      underline
                      whitespace-nowrap]
             :href url
             :target "_blank"}
         "View original"])
      [:.h-6]
      [:.px-4.bg-white.contain-content.overflow-hidden
       [:div {:id "post-content"
              :data-contents clean-html
              :_ "on load call renderPost()"
              :class (if (= doc-type :item/email)
                       '[py-4]
                       '[hover:prose-a:underline
                         lg:prose-h1:text-4xl
                         prose-a:text-tealv-600
                         prose-blockquote:border-l-tealv-500
                         prose-h1:text-3xl
                         prose-lg
                         prose-neut-900
                         prose-quoteless
                         py-4])}]]
      (ui-button-bar opts)])})

(def module
  {:routes [["" {:middleware [lib.middle/wrap-signed-in]}
             mark-unread
             toggle-favorite
             not-interested]]
   :resolvers [button-bar
               like-button*
               like-button
               share-button
               ui-read-content]})
