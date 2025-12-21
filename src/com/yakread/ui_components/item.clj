(ns com.yakread.ui-components.item
  (:require [com.biffweb :as biff]
            [clojure.string :as str]
            [com.yakread.lib.content :as lib.content]
            [com.yakread.lib.route :refer [href]]
            [com.yakread.lib.ui :as ui]
            [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver ?]]
            [com.yakread.routes :as routes]
            [lambdaisland.uri :as uri]
            [tick.core :as tick])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defn- reading-minutes [n-characters]
  (max 1 (Math/round (/ n-characters 900.0))))

(defn- detail-list [list-items]
  [:<>
   (->> list-items
        (mapv #(vector :span.inline-block %))
        (biff/join ui/interpunct))])

(defresolver details* [{:item/keys [doc-type
                                    byline
                                    author-name
                                    url
                                    published-at
                                    ingested-at
                                    length
                                    rec-type
                                    source]}]
  {::pco/input [:item/id
                :item/title
                :item/ingested-at
                :item/doc-type
                (? :item/byline)
                (? :item/author-name)
                (? :item/site-name)
                (? :item/url)
                (? :item/published-at)
                (? :item/length)
                (? :item/rec-type)
                {(? :item/source) [:source/title]}]}
  {:item/ui-details*
   (fn [{:keys [show-author show-reading-time label-type]
         :or {show-reading-time true
              label-type :html}}]
     (->> [(when show-author
             (some-> (or (:source/title source) author-name byline) str/trim not-empty))
           (when (= doc-type :item/direct)
             (some-> url uri/uri :host str/trim not-empty))
           (let [;; TODO get timezone for user
                 zdt (or published-at ingested-at)
                 same-year (= (tick/year zdt)
                              (tick/year (tick/in (tick/instant) "UTC")))]
             (tick/format (if same-year
                            "d MMM"
                            "d MMM yyyy")
                          zdt))
           (when (and show-reading-time length)
             (ui/pluralize (reading-minutes length) "minute"))
           (when-some [label (case rec-type
                               :item.rec-type/bookmark "Bookmarked"
                               :item.rec-type/subscription "Subscribed"
                               :item.rec-type/new-subscription "New subscription"
                               :item.rec-type/discover "Discover"
                               :item.rec-type/current "Continue reading"
                               nil)]
             (case label-type
               :html [:span {:style {:text-decoration "underline"}} label]
               :text label))]
          (filter some?)))})

(defresolver details [{:item/keys [ui-details*]}]
  {:item/ui-details
   (fn [params]
     (detail-list (ui-details* params)))})

(defn- read-more-card* [{:keys [highlight details title description image-url clamp]}]
  [:div {:class (concat '[bg-white hover:bg-neut-50
                          p-4
                          sm:shadow]
                        (when highlight
                          '[max-sm:border-t-4 sm:border-l-4 border-tealv-500]))}
   [:.text-neut-600.text-sm.line-clamp-2.wrap-anywhere
    {:style {:overflow-wrap "anywhere"}}
    details]
   [:.h-1]
   [:h3 {:class '[font-bold text-xl text-neut-800
                  leading-tight
                  line-clamp-2]
         :style {:overflow-wrap "anywhere"}}
    title]
   [:.h-2]
   [:.flex.gap-3.justify-between
    [:div
     (when description
       [:.text-neut-600.mb-1
        (when clamp
          {:style {:overflow-wrap "anywhere"}
           :class '[line-clamp-4]})
        description])
     [:div {:class '[text-tealv-600 font-semibold
                     hover:underline
                     inline-block]}
      "Read more."]]
    (when image-url
      [:.relative.flex-shrink-0
       [:img {:src (ui/weserv {:url image-url
                               :w 150
                               :h 150
                               :fit "cover"
                               :a "attention"})
              :_ "on error remove me"
              :class '[rounded
                       object-cover
                       object-center
                       "mt-[6px]"
                       "w-[5.5rem]"
                       "h-[5.5rem]"]}]
       [:div {:style {:box-shadow "inset 0 0px 6px 1px #0000000d"}
              :class '[absolute
                       inset-x-0
                       "top-[6px]"
                       "h-[5.5rem]"
                       rounded]}]])]])

(defresolver item-read-more-card [{:item/keys [id ui-details title excerpt unread image-url url]}]
  {::pco/input [:item/id
                :item/unread
                :item/ui-details
                (? :item/title)
                (? :item/image-url)
                (? :item/excerpt)
                (? :item/url)]}
  {:item/ui-read-more-card
   (fn [{:keys [highlight-unread on-click-route show-author on-click-params new-tab]}]
     [:a {:href (if on-click-route
                  (href on-click-route id on-click-params)
                  url)
          :target (when new-tab "_blank")}
      (read-more-card* {:highlight (and highlight-unread unread)
                        :details (ui-details {:show-author show-author})
                        :title title
                        :description (when (not= excerpt "Read more")
                                       (lib.content/clean-string excerpt))
                        :clamp  true
                        :image-url image-url})])})

(defn ad-card-base [{:keys [href]
                     :ad/keys [url-with-protocol
                               title
                               description
                               image-url]}]
  [:a {:href href :target "_blank"}
   (read-more-card* {:highlight true
                     :details (detail-list [(some-> url-with-protocol uri/uri :host str/trim not-empty)
                                            [:span.underline "Ad"]])
                     :title title
                     :description description
                     :image-url image-url})])

(defresolver ad-preview-card
  [{:biff/keys [base-url]}
   {:ad/keys [url-with-protocol title description image-url]
    :or {title "Lorem ipsum dolor sit amet"
         description (str "Consectetur adipiscing elit, sed do eiusmod "
                          "tempor incididunt ut labore et dolore magna aliqua. "
                          "Ut enim ad minim veniam, quis nostrud exercitation "
                          "ullamco laboris nisi ut aliquip ex ea commodo consequat.")
         url-with-protocol "https://example.com"
         image-url (str base-url "/android-chrome-512x512.png")}}]
  {::pco/input [(? :ad/url-with-protocol)
                (? :ad/title)
                (? :ad/description)
                (? :ad/image-url)]
   ::pco/output [:ad/ui-preview-card]}
  {:ad/ui-preview-card
   (ad-card-base {:href url-with-protocol
                  :ad/url-with-protocol url-with-protocol
                  :ad/title title
                  :ad/description description
                  :ad/image-url image-url})})

(defresolver ad-read-more-card [{:keys [session]}
                                {:ad/keys [url-with-protocol
                                           recording-url
                                           title
                                           description
                                           image-url]}]
  {::pco/input [:ad/id
                :ad/url-with-protocol
                :ad/recording-url
                :ad/click-cost
                :ad/title
                :ad/description
                :ad/image-url]}
  {:ad/ui-read-more-card
   (fn [{:keys [on-click-params]}]
     (ad-card-base {:href (recording-url
                           {:params on-click-params
                            :user/id (:uid session)
                            :ad.click/source :web})
                    :ad/url-with-protocol url-with-protocol
                    :ad/title title
                    :ad/description description
                    :ad/image-url image-url}))})

(defresolver rec-read-more-card [props]
  {::pco/input [(? :item/ui-read-more-card)
                (? :ad/ui-read-more-card)]
   ::pco/output [:rec/ui-read-more-card]}
  (when-some [ui-card (some props [:item/ui-read-more-card :ad/ui-read-more-card])]
    {:rec/ui-read-more-card ui-card}))

(defresolver small-card [{:item/keys [id title ui-details]}]
  #::pco{:input [:item/id
                 (? :item/title)
                 :item/ui-details]}
  {:item/ui-small-card
   [:a {:href (href routes/read-item id)
        :class '[block
                 bg-white hover:bg-neut-50
                 shadow
                 p-2
                 text-sm]}
    [:.font-semibold.mr-6.line-clamp-2 (or (not-empty title) "[no title]")]
    [:.text-neut-800.mr-6.line-clamp-2 (ui-details {:show-author true :show-reading-time false})]]})

(def module
  {:resolvers [details*
               details
               ad-preview-card
               ad-read-more-card
               item-read-more-card
               rec-read-more-card
               small-card]})
