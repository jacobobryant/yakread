(ns com.yakread.model.old-schema)

(defn inherit [base-schema & map-args]
  [:merge base-schema (into [:map {:closed true}] map-args)])

(def ? {:optional true})
;; target is used by lib.test for generating test docs
(defn r [target] {:biff/ref (if (coll? target) target #{target})})
(defn ?r [target] (assoc (r target) :optional true))

(def schema
  {::string  [:string {:max 2000}]
   ::day     [:enum :sunday :monday :tuesday :wednesday :thursday :friday :saturday]
   ::cents   [:int {:biff.form/parser #(Math/round (* 100 (Float/parseFloat %)))}]

   :user [:map {:closed true}
          [:xt/id                     :uuid]
          [:user/email                ::string]
          [:user/roles              ? [:set [:enum :admin]]]
          [:user/joined-at          ? :time/instant]
          [:user/digest-days        ? [:set ::day]]
          [:user/send-digest-at     ? :time/local-time]
          [:user/timezone           ? :time/zone-id]
          [:user/digest-last-sent   ? :time/instant]
          [:user/from-the-sample    ? :boolean]
          ;; When the user views an item, when possible, open the original URL in a new tab instead
          ;; of displaying the item within Yakread.
          [:user/use-original-links ? :boolean]
          ;; The user reported our emails as spam or emails to them hard-bounced, so don't send them
          ;; any more emails.
          [:user/suppressed-at      ? :time/instant]
          ;; Used for email subscriptions (<username>@yakread.com)
          [:user/email-username     ? ::string]
          ;; Stripe ID
          [:user/customer-id        ? :string]
          [:user/plan               ? [:enum :quarter :annual]]
          [:user/cancel-at          ? :time/instant]]

   :sub/base  [:map {:closed true}
               [:xt/id                    :uuid]
               [:sub/user       (r :user) :uuid]
               [:sub/created-at           :time/instant]
               [:sub/pinned-at  ?         :time/instant]]
   :sub/feed  (inherit :sub/base
                       [:sub.feed/feed (r :feed) :uuid])
   ;; :sub-email is automatically created when the user receives an email with a new From field.
   :sub/email (inherit :sub/base
                       [:sub.email/from              ::string]
                       ;; If the user unsubscribes, instead of deleting the :sub-email, we set this
                       ;; flag. Then even if the newsletter sends more emails, we won't accidentally
                       ;; re-subscribe them.
                       [:sub.email/unsubscribed-at ? :time/instant])
   :sub/any   [:or :sub/feed :sub/email]

   :item/base  [:map {:closed true}
                [:xt/id               :uuid]
                [:item/ingested-at    :time/instant]
                [:item/title        ? ::string]
                [:item/url          ? ::string]
                [:item/redirect-urls ? [:set ::string]]
                ;; If the content is <= 1000 chars, put it in XT, otherwise, put it in S3
                [:item/content      ? ::string]
                [:item/content-key  ? :uuid]
                [:item/published-at ? :time/instant]
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
                        [:item.email/sub                   (r :sub/email) :uuid]
                        ;; For the raw email -- processed email goes in :item/content-key
                        [:item.email/raw-content-key                      :uuid]
                        [:item.email/list-unsubscribe      ?              [:string {:max 5000}]]
                        [:item.email/list-unsubscribe-post ?              ::string]
                        [:item.email/reply-to              ?              ::string]
                        [:item.email/maybe-confirmation    ?              :boolean])
   ;; Items fetched from a user-supplied URL (bookmarked or favorited)
   :item/direct (inherit :item/base
                         [:item/doc-type [:= :item/direct]]
                         [:item.direct/candidate-status ? [:enum :ingest-failed :blocked :approved]])
   :item/any    [:or :item/feed :item/email :item/direct]

   :feed [:map {:closed true}
          [:xt/id                :uuid]
          [:feed/url             ::string]
          [:feed/synced-at     ? :time/instant]
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
   :user-item [:map {:closed true}
               [:xt/id                                  :uuid]
               [:user-item/user          (r :user)      :uuid]
               [:user-item/item          (r :item/any)  :uuid]
               [:user-item/viewed-at     ?              :time/instant]
               ;; User clicked "mark all as read"
               [:user-item/skipped-at    ?              :time/instant]
               [:user-item/bookmarked-at ?              :time/instant]
               [:user-item/favorited-at  ?              :time/instant]
               [:user-item/position      (?r :position) :uuid]
               ;; User clicked thumbs-down. Mutually exclusive with :user-item/favorited-at
               [:user-item/disliked-at   ?              :time/instant]
               ;; This item was recommended in For You and the user reported it.
               [:user-item/reported-at   ?              :time/instant]
               [:user-item/report-reason ?              ::string]]

   ;; Digest emails
   :digest [:map {:closed true}
            [:xt/id                             :uuid]
            [:digest/user     (r :user)         :uuid]
            [:digest/sent-at                    :time/instant]
            [:digest/subject  (?r :item/any)    :uuid]
            [:digest/ad       (?r :ad)          :uuid]
            [:digest/icymi    (?r :item/any)    [:vector :uuid]]
            [:digest/discover (?r :item/direct) [:vector :uuid]]]

   :bulk-send [:map {:closed true}
               [:xt/id :uuid]
               [:bulk-send/sent-at                   :time/instant]
               [:bulk-send/payload-size              :int]
               [:bulk-send/mailersend-id             :string]
               [:bulk-send/digests       (r :digest) [:vector :uuid]]]

   ;; When the user clicks on item in For You, any previous items they scrolled past get added to a
   ;; :skip document.
   :skip [:map {:closed true}
          [:xt/id                                       :uuid]
          [:skip/user                (r :user)          :uuid]
          [:skip/timeline-created-at                    :time/instant]
          [:skip/items               (r :timeline/item) [:set :uuid]]
          ;; Used to remove items from :skip/items if they get clicked later. Should NOT be used to
          ;; determine if an item/ad has ever been clicked/viewed.
          [:skip/clicked             (r :timeline/item) [:set :uuid]]]

   ;; Split this out of :user-item because it'll change very frequently.
   :position [:map {:closed true}
              [:xt/id          :uuid]
              [:position/value :int]]

   :timeline/item [:or :item/any :ad]

   :ad [:map {:closed true}
        [:xt/id                       :uuid]
        [:ad/user           (r :user) :uuid]
        [:ad/approve-state            [:enum :pending :approved :rejected]]
        [:ad/updated-at               :time/instant]
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
        ;; TODO dedupe with :user/customer-id
        [:ad/customer-id    ?         :string]
        [:ad/session-id     ?         :string]
        [:ad/payment-method ?         :string]
        [:ad/card-details   ?         [:map {:closed true}
                                       [:brand     :string]
                                       [:last4     :string]
                                       [:exp_year  :int]
                                       [:exp_month :int]]]]

   :ad.click [:map {:closed true}
              [:xt/id                          :uuid]
              [:ad.click/user        (r :user) :uuid]
              [:ad.click/ad          (r :ad)   :uuid]
              [:ad.click/created-at            :time/instant]
              [:ad.click/cost                  ::cents]
              [:ad.click/source                [:enum :web :email]]]

   :ad.credit [:map {:closed true}
               [:xt/id                           :uuid]
               [:ad.credit/ad            (r :ad) :uuid]
               ;; Are we charging their card or giving them free ad credit?
               [:ad.credit/source                [:enum :charge :manual]]
               [:ad.credit/amount                ::cents]
               [:ad.credit/created-at            :time/instant]
               ;; We store :xt/id in the Stripe payment intent metadata and use it to look up the
               ;; charge status.
               [:ad.credit/charge-status ?       [:enum :pending :confirmed :failed]]]

   :mv.sub [:map {:closed true}
            [:xt/id                          :uuid]
            [:mv.sub/sub            (r :sub) :uuid]
            [:mv.sub/affinity-low   ?        :double]
            [:mv.sub/affinity-high  ?        :double]
            [:mv.sub/last-published ?        :time/instant]
            [:mv.sub/unread         ?        :int]
            [:mv.sub/read           ?        :int]]

   :mv.user [:map {:closed true}
             [:xt/id :uuid]
             [:mv.user/user         (r :user)      :uuid]
             [:mv.user/current-item (?r :item/any) :uuid]]

   :deleted-user [:map {:closed true}
                  [:xt/id                            :uuid]
                  [:deleted-user/email-username-hash :string]]})

(def module {})
