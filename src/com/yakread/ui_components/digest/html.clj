(ns com.yakread.ui-components.digest.html
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver ?]]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.ui-email :as uie]
   [com.yakread.routes :as routes]
   [lambdaisland.uri :as uri]))

(defn ui-item [{:keys [details title excerpt url image]}]
  [:div {:style {:margin-bottom (if excerpt "30px" "20px")}}
   [:div {:style {:font-size "90%"
                  :line-height "20px"
                  :color "#595959"}}
    details]
   [:div [:a {:href url
              :style {:text-decoration "none"
                      :color "#079a82"
                      :font-weight "600"
                      :font-size "17px"
                      :line-height "23px"}}
          title]]
   (when excerpt
     [:div {:style {:color "#595959"
                    :font-size "16px"
                    :line-height "22.85px"
                    :margin-top "2px"}}
      (when (and excerpt image)
        [:img {:src (ui/weserv {:url image :w 150 :h 150 :fit "cover" :a "attention"})
               :align "left"
               :style {:float "left"
                       :border-radius "4px"
                       :object-position "center"
                       :object-fit "cover"
                       :width "57px"
                       :height "57px"
                       :max-width "100%"
                       :margin-top "6px"
                       :margin-right "7px"}}])
      excerpt])
   [:div {:style {:clear "left"}}]])

(defn section-title [content]
  [:h3 {:style {:font-weight "700"
                     :font-size "18px"
                     :line-height "28px"
                     :margin "0"
                     :padding-bottom "12px"
                     :border-bottom "1px solid #e1e1e1"
                     :margin-bottom "20px"}}
        content])


(defresolver settings [{:keys [biff/base-url]} {:digest.settings/keys [freq-text time-text]}]
  {::settings
   [:div {:style {:font-style "italic"
                  :font-size "90%"
                  :text-align "center"}}
    "You're getting this personalized digest "
    [:span {:style {:font-weight "600"
                    :display "inline-block"}}
     freq-text]
    " at "
    [:span {:style {:font-weight "600"
                    :display "inline-block"}} time-text] ". "
    [:a {:href (str base-url (href routes/settings-page))
         :style {:color "#079a82"
                 :text-decoration "none"}} "Change settings"] "."]})

(defresolver sponsored [{:user/keys [ad-rec id]}]
  {::pco/input [{:user/ad-rec [:xt/id
                               :ad/title
                               :ad/host
                               :ad/description
                               :ad/image-url
                               :ad/url-with-protocol
                               :ad/click-cost
                               :ad/recording-url]}]
   ::pco/output [::sponsored]}
  (let [{:ad/keys [title
                   description
                   image-url
                   host
                   recording-url]} ad-rec]
    {::sponsored
     [:<>
      (section-title "Sponsored")
      (ui-item {:url (recording-url {:ad.click/source :email
                                     :user/id id})
                :title title
                :image image-url
                :excerpt description
                :details host})]}))

(defn compact-section [title op-name input-key output-key]
  (pco/resolver
   op-name
   {::pco/input [:user/id
                 {input-key [:item/id
                             :item/digest-url
                             :item/ui-details*
                             (? :item/clean-title)
                             (? :item/url)]}]
    ::pco/output [output-key]}
   (fn [_env input]
     (when-some [items (not-empty (get input input-key))]
       {output-key
        [:<>
         (section-title title)
         (for [{:item/keys [digest-url
                            clean-title
                            ui-details*]} items]
           (ui-item {:url (digest-url {:user/id (:user/id input)})
                     :title clean-title
                     :details (biff/join ui/interpunct (ui-details* {:show-author true}))}))]}))))

(def subscriptions
  (compact-section "Subscriptions" `subscriptions :user/digest-sub-items ::subscriptions))

(def bookmarks
  (compact-section "Bookmarks" `bookmarks :user/digest-bookmarks ::bookmarks))

(def icymi
  (compact-section "In case you missed it" `icymi :user/icymi-recs ::icymi))

(defresolver discover [{:user/keys [digest-discover-recs] user-id :user/id}]
  {::pco/input [:user/id
                {:user/digest-discover-recs [:item/digest-url
                                             (? :item/clean-title)
                                             (? :item/image-url)
                                             (? :item/excerpt)
                                             (? :item/url)]}]
   ::pco/output [::discover]}
  (when (not-empty digest-discover-recs)
    {::discover
     [:<>
      (section-title "Discover")
      (for [{:item/keys [digest-url
                         clean-title
                         image-url
                         excerpt
                         url]} digest-discover-recs]
        (ui-item {:url (digest-url {:user/id user-id})
                  :title clean-title
                  :image image-url
                  :excerpt excerpt
                  :details (some-> url uri/uri :host str/trim not-empty)}))]}))

(defn open-yakread-button [base-url]
  (uie/button
    {:href (str base-url (href routes/for-you))
     :label "Open Yakread"
     :bg-color "#17b897"
     :text-color "white"}))

(defresolver html [{:biff/keys [base-url]}
                   {:digest/keys [subject-item
                                  unsubscribe-url]
                    ::keys [settings
                            sponsored
                            subscriptions
                            bookmarks
                            icymi
                            discover]}]
  {::pco/input [{(? :digest/subject-item) [:item/clean-title]}
                ::settings
                (? ::sponsored)
                (? ::subscriptions)
                (? ::bookmarks)
                (? ::icymi)
                (? ::discover)
                :digest/unsubscribe-url]
   ::pco/output [:digest/html]}
  (when-some [sections (->> [sponsored
                             subscriptions
                             bookmarks
                             icymi
                             discover]
                            (filterv some?)
                            not-empty)]
    {:digest/html
     (uie/html
      {:logo-on-click base-url
       :logo-src (str base-url "/img/logo-navbar.png")
       :title (get subject-item :item/clean-title "Your reading digest")
       :unsubscribe-url unsubscribe-url
       :content [:<>
                 settings
                 (uie/h-space "24px")
                 (open-yakread-button base-url)
                 (uie/h-space "24px")
                 (biff/join (uie/h-space "40px") sections)]})}))

(def module {:resolvers [sponsored
                         subscriptions
                         bookmarks
                         icymi
                         discover
                         settings
                         html]})
