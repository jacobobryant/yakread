(ns com.yakread.model.schema
  (:require [tick.core :as tick]
            [com.biffweb.experimental :as biffx]))

(defn table [table-name & args]
  (let [[options map-args] (if (map? (first args))
                             [(first args) (rest args)]
                             [{} args])
        options (assoc options :biff/table table-name)
        map-schema (into [:map (merge {:closed true} options)] map-args)]
    (if-some [prefix-fn (:biff/prefixed-by options)]
      [:and
       map-schema
       [:fn {:error/message ":xt/id should be prefixed properly"}
        (fn [m]
          (= (:xt/id m)
             (biffx/prefix-uuid (prefix-fn m) (:xt/id m))))]]
      map-schema)))

(defn inherit [base-schema & table-args]
  [:merge base-schema (into [:map] table-args)])

(def ? {:optional true})
;; target is used by lib.test for generating test docs
(defn r [target] {:biff/ref (if (coll? target) target #{target})})
(defn ?r [target] (assoc (r target) :optional true))

(def schema
  {::string  [:string {:max 2000}]
   ::day     [:enum :sunday :monday :tuesday :wednesday :thursday :friday :saturday]
   ::cents   [:int {:biff.form/parser #(Math/round (* 100 (Float/parseFloat %)))}]
   ::zdt     [:fn tick/zoned-date-time?]

   :user [:map {:biff/table :user
                :closed true}
          [:xt/id                     :uuid]
          [:user/email                ::string]
          [:user/roles              ? [:set [:enum :admin]]]
          [:user/joined-at          ? ::zdt]
          [:user/digest-days        ? [:set ::day]]
          [:user/send-digest-at     ? :time/local-time]
          [:user/timezone*          ? ::string]
          [:user/digest-last-sent   ? ::zdt]
          [:user/from-the-sample    ? :boolean]
          ;; When the user views an item, when possible, open the original URL in a new tab instead
          ;; of displaying the item within Yakread.
          [:user/use-original-links ? :boolean]
          ;; The user reported our emails as spam or emails to them hard-bounced, so don't send them
          ;; any more emails.
          [:user/suppressed-at      ? ::zdt]
          ;; Used for email subscriptions (<username>@yakread.com)
          [:user/email-username     ? ::string]
          ;; Stripe ID
          [:user/customer-id        ? :string]
          [:user/plan               ? [:enum :quarter :annual]]
          [:user/cancel-at          ? ::zdt]]

   :sub/base  [:map {:biff/table :sub
                     :biff/prefixed-by :sub/user}
               [:xt/id                    :uuid]
               [:sub/user       (r :user) :uuid]
               [:sub/created-at           ::zdt]
               [:sub/pinned-at  ?         ::zdt]]
   :sub/feed  (inherit :sub/base
                       [:sub.feed/feed (r :feed) :uuid])
   ;; :sub-email is automatically created when the user receives an email with a new From field.
   :sub/email (inherit :sub/base
                       [:sub.email/from              ::string]
                       ;; If the user unsubscribes, instead of deleting the :sub-email, we set this
                       ;; flag. Then even if the newsletter sends more emails, we won't accidentally
                       ;; re-subscribe them.
                       [:sub.email/unsubscribed-at ? ::zdt])
   :sub/any   [:or :sub/feed :sub/email]
   :sub       :sub/any

   :item/base  [:map {:biff/table :item
                      :biff/prefixed-by (some-fn :item.feed/feed
                                                 :item.email/sub
                                                 (constantly "0000"))
                      :closed true}
                [:xt/id               :uuid]
                [:item/ingested-at    ::zdt]
                [:item/title        ? ::string]
                [:item/url          ? ::string]
                [:item/redirect-urls ? [:set ::string]]
                ;; If the content is <= 1000 chars, put it in XT, otherwise, put it in S3
                [:item/content      ? ::string]
                [:item/content-key  ? :uuid]
                [:item/published-at ? ::zdt]
                [:item/excerpt      ? ::string]
                [:item/author-name  ? ::string]
                [:item/author-url   ? ::string]
                ;; An autodiscovered feed url, parsed from the item's content. Contrast with
                ;; :item.feed/feed -> :feed/url, which is the feed URL from which this item was
                ;; fetched.
                [:item/feed-url     ? ::string]
                [:item/lang         ? ::string]
                [:item/site-name    ? ::string]
                [:item/byline       ? ::string]
                [:item/length       ? :int]
                [:item/image-url    ? ::string]
                [:item/paywalled    ? :boolean]]
   :item/feed  (inherit :item/base
                        [:item.feed/feed (r :feed) :uuid]
                        ;; The RSS <guid> / Atom <id> field.
                        [:item.feed/guid ? ::string])
   :item/email (inherit :item/base
                        [:item.email/sub                   (r :sub) :uuid]
                        ;; For the raw email -- processed email goes in :item/content-key
                        [:item.email/raw-content-key                :uuid]
                        [:item.email/list-unsubscribe      ?        [:string {:max 5000}]]
                        [:item.email/list-unsubscribe-post ?        ::string]
                        [:item.email/reply-to              ?        ::string]
                        [:item.email/maybe-confirmation    ?        :boolean])
   ;; Items fetched from a user-supplied URL (bookmarked or favorited)
   :item/direct (inherit :item/base
                         [:item/doc-type [:= :item/direct]]
                         [:item.direct/candidate-status ? [:enum :ingest-failed :blocked :approved]])
   :item/any    [:or :item/feed :item/email :item/direct]
   :item        :item/any

   :redirect [:map {:biff/table :redirect
                    :closed true}
              [:xt/id         :uuid]
              [:redirect/url  ::string]
              [:redirect/item :uuid]]

   :feed [:map {:biff/table :feed
                :closed true}
          [:xt/id                :uuid]
          [:feed/url             ::string]
          [:feed/synced-at     ? ::zdt]
          [:feed/title         ? ::string]
          [:feed/description   ? ::string]
          [:feed/image-url     ? ::string]
          [:feed/etag          ? ::string]
          [:feed/last-modified ? ::string]
          [:feed/failed-syncs  ? :int]
          ;; Only :approved feeds can be recommended to other users in For You.
          [:feed/moderation    ? [:enum :approved :blocked]]]

   ;; The relationship between a :user and an :item. Ways a :user-item can be created:
   ;; - user clicks on it from For You/Subscriptions
   ;; - user adds it to Read Later/Favorites
   ;; - we send the user a digest email using this item's title in the subject line
   ;; - we recommend the item in For You and the user reports it
   :user-item [:map {:biff/table :user-item
                     :biff/prefixed-by :user-item/user
                     :closed true}
               [:xt/id                             :uuid]
               [:user-item/user          (r :user) :uuid]
               [:user-item/item          (r :item) :uuid]
               [:user-item/viewed-at     ?         ::zdt]
               ;; User clicked "mark all as read"
               [:user-item/skipped-at    ?         ::zdt]
               [:user-item/bookmarked-at ?         ::zdt]
               [:user-item/favorited-at  ?         ::zdt]
               ;; User clicked thumbs-down. Mutually exclusive with :user-item/favorited-at
               [:user-item/disliked-at   ?         ::zdt]
               ;; This item was recommended in For You and the user reported it.
               [:user-item/reported-at   ?         ::zdt]
               [:user-item/report-reason ?         ::string]]

   ;; Digest emails
   :digest [:map {:biff/table :digest
                  :biff/prefixed-by :digest/user
                  :closed true}
            [:xt/id                            :uuid]
            [:digest/user     (r :user)        :uuid]
            [:digest/sent-at                   ::zdt]
            [:digest/subject  (?r :item)       :uuid]
            [:digest/ad       (?r :ad)         :uuid]
            ;; migrate to :digest-item
            #_[:digest/icymi    (?r :item)     [:vector :uuid]]
            #_[:digest/discover (?r :item)     [:vector :uuid]]
            [:digest/bulk-send (?r :bulk-send) :uuid]]

   :digest-item [:map {:biff/table :digest-item
                       :biff/prefixed-by :digest-item/item
                       :closed true}
                 [:xt/id              :uuid]
                 [:digest-item/digest :uuid]
                 [:digest-item/item   :uuid]
                 [:digest-item/kind   [:enum :icymi :discover]]]

   :bulk-send [:map {:biff/table :bulk-send
                     :closed true}
               [:xt/id :uuid]
               [:bulk-send/sent-at                    ::zdt]
               [:bulk-send/payload-size               :int]
               [:bulk-send/mailersend-id              :string]
               ;; migrate to :digest/bulk-send
               #_[:bulk-send/digests      (r :digest) [:vector :uuid]]]

   ;; When the user clicks on item in For You, any previous items they scrolled past get added to a
   ;; :skip document.
   :reclist [:map {:biff/table :reclist
                   :biff/prefixed-by :reclist/user
                   :closed true}
             [:xt/id                                :uuid]
             [:reclist/user       (r :user)         :uuid]
             [:reclist/created-at                   ::zdt]
             ;; Used to prevent clicked items from getting added to :skip/items if subsequent items
             ;; are also clicked. Should NOT be used to determine if an item/ad has ever been
             ;; clicked/viewed: not all clicks originate from For You.
             [:reclist/clicked    (r :reclist-item) [:set :uuid]]]

   :skip [:map {:biff/table :skip
                :biff/prefixed-by :skip/reclist
                :closed true}
          [:xt/id                          :uuid]
          [:skip/reclist                   :uuid]
          [:skip/item    (r :reclist-item) :uuid]]

   :timeline/item [:or :item/any :ad]
   :timeline-item :timeline/item
   :reclist-item  [:or :item/any :ad]

   :ad [:map {:biff/table :ad
              :biff/prefixed-by :ad/user
              :closed true}
        [:xt/id                       :uuid]
        [:ad/user           (r :user) :uuid]
        [:ad/approve-state            [:enum :pending :approved :rejected]]
        [:ad/updated-at               ::zdt]
        [:ad/balance                  ::cents]
        ;; Balance accrued from ad clicks in the past 7 days
        [:ad/recent-cost              ::cents] ; remove?
        [:ad/bid            ?         ::cents]
        ;; Max amount that balance should increase by in a 7-day period
        [:ad/budget         ?         ::cents]
        [:ad/url            ?         ::string]
        [:ad/title          ?         [:string {:max 75}]]
        [:ad/description    ?         [:string {:max 250}]]
        [:ad/image-url      ?         ::string]
        [:ad/paused         ?         :boolean]
        [:ad/payment-failed ?         :boolean]
        ;; Stripe info
        ;; TODO maybe dedupe with :user/customer-id
        [:ad/customer-id    ?         :string]
        [:ad/session-id     ?         :string]
        [:ad/payment-method ?         :string]
        [:ad/card-details   ?         [:map {:closed true}
                                       [:brand     :string]
                                       [:last4     :string]
                                       [:exp-year  :int]
                                       [:exp-month :int]]]]

   :ad.click [:map {:biff/table :ad-click
                    :biff/prefixed-by :ad.click/ad
                    :closed true}
              [:xt/id                          :uuid]
              [:ad.click/user        (r :user) :uuid]
              [:ad.click/ad          (r :ad)   :uuid]
              [:ad.click/created-at            ::zdt]
              [:ad.click/cost                  ::cents]
              [:ad.click/source                [:enum :web :email]]]
   :ad-click :ad.click

   :ad.credit [:map {:biff/table :ad-credit
                     :biff/prefixed-by :ad.credit/ad
                     :closed true}
               [:xt/id                           :uuid]
               [:ad.credit/ad            (r :ad) :uuid]
               ;; Are we charging their card or giving them free ad credit?
               [:ad.credit/source                [:enum :charge :manual]]
               [:ad.credit/amount                ::cents]
               [:ad.credit/created-at            ::zdt]
               ;; We store :xt/id in the Stripe payment intent metadata and use it to look up the
               ;; charge status.
               [:ad.credit/charge-status ?       [:enum :pending :confirmed :failed]]]
   :ad-credit :ad.credit

   :mv.sub [:map {:biff/table :mv-sub
                  :biff/prefixed-by :mv.sub/sub
                  :closed true}
            [:xt/id                          :uuid]
            [:mv.sub/sub            (r :sub) :uuid]
            [:mv.sub/affinity-low   ?        :double]
            [:mv.sub/affinity-high  ?        :double]
            [:mv.sub/last-published ?        ::zdt]
            [:mv.sub/unread         ?        :int]
            [:mv.sub/read           ?        :int]]
   :mv-sub :mv.sub

   :mv.user [:map {:biff/table :mv-user
                   :biff/prefixed-by :mv.user/user
                   :closed true}
             [:xt/id :uuid]
             [:mv.user/user         (r :user)      :uuid]
             [:mv.user/current-item (?r :item)     :uuid]]
   :mv-user :mv.user

   :deleted-user [:map {:biff/table :deleted-user
                        :closed true}
                  [:xt/id                            :uuid]
                  [:deleted-user/email-username-hash :string]]})

(def module
  {:schema schema})
