(ns com.yakread.app.advertise
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.route :as lib.route :refer [defget defget-pipe defpost
                                                defpost-pathom href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]
   [rum.core :as rum]))

(def required-field->label
  {:ad/payment-method "Payment Method"
   :ad/bid "Maximum cost-per-click"
   :ad/budget "Maximum weekly budget"
   :ad/url "Ad URL"
   :ad/title "Title"
   :ad/description "Description"
   :ad/image-url "Image"})

(defn render [& body]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup [:<> body])})

(declare page-route)
(declare image-upload-input)
(declare form)

(defn fmt-dollars [amount]
  (format "%.2f" (/ amount 100.0)))

(defn obfuscate-url!
  "Some ad blockers block requests to URLs that include 'advertise' in them."
  [route-var]
  (alter-var-root route-var update 0 str/replace #"ad" "womp"))

(defpost-pathom delete-payment-method
  [{:session/user [{:user/ad [:ad/id :ad/payment-method]}]}]
  (fn [{:keys [biff/secret]} {{{:ad/keys [id payment-method]} :user/ad} :session/user}]
    {:biff.pipe/next [(lib.pipe/tx
                       [{:db/doc-type :ad
                         :db/op :update
                         :xt/id id
                         :ad/payment-method :db/dissoc
                         :ad/card-details :db/dissoc
                         :ad/updated-at :db/now}])
                      (lib.pipe/http
                       :post
                       (str "https://api.stripe.com/v1/payment_methods/" payment-method "/detach")
                       {:basic-auth [(secret :stripe/api-key)]})]
     :status 200
     :headers {"HX-Location" "/advertise"}}))

(defget-pipe receive-payment-method
  :start
  (fn [{:keys [biff/db biff/secret params]}]
    (let [{:keys [session-id]} params
          ;; TODO
          ad-id (biff/lookup-id db :ad/session-id session-id)]
      (if ad-id
        {:biff.pipe/next [(lib.pipe/http
                           :get
                           (str "https://api.stripe.com/v1/checkout/sessions/" session-id)
                           {:basic-auth [(secret :stripe/api-key)]
                            :multi-param-style :array
                            :as :json
                            :query-params {:expand ["setup_intent" "setup_intent.payment_method"]}})
                          :save-payment-method]
         :ad/id ad-id}
        {:biff.pipe/next [:redirect]})))

  :save-payment-method
  (fn [{:keys [biff.pipe.http/output] ad-id :ad/id}]
    (let [pm (get-in output [:body :setup_intent :payment_method])]
      {:biff.pipe/next [(lib.pipe/tx
                         [{:db/doc-type :ad
                           :db/op :update
                           :xt/id ad-id
                           :ad/session-id :db/dissoc
                           :ad/payment-method (:id pm)
                           :ad/card-details (select-keys (:card pm)
                                                         [:brand :last4 :exp_year :exp_month])
                           :ad/updated-at :db/now}])
                        :redirect]}))

  :redirect
  (constantly {:status 303
               :headers {"Location" "/advertise"}}))

(defpost add-payment-method
  :start
  (lib.pipe/pathom-query [{:session/user
                           [:xt/id
                            :user/email
                            {(? :user/ad)
                             [(? :ad/customer-id)]}]}]
                         :check-for-customer)

  :check-for-customer
  (fn [{:keys [biff/secret biff.pipe.pathom/output]}]
    (if-some [customer-id (get-in output [:session/user :user/ad :ad/customer-id])]
      {:biff.pipe/next [:create-session]
       :ad/customer-id customer-id}
      {:biff.pipe/next [(lib.pipe/http
                         :post
                         "https://api.stripe.com/v1/customers"
                         {:basic-auth [(secret :stripe/api-key)]
                          :form-params {:email (get-in output [:session/user :user/email])}
                          :as :json})
                        :create-customer]}))

  :create-customer
  (fn [{:keys [biff.pipe.http/output session]}]
    (let [customer-id (get-in output [:body :id])]
      {:biff.pipe/next [(lib.pipe/tx [{:db/doc-type :ad
                                       :db.op/upsert {:ad/user (:uid session)}
                                       :ad/customer-id customer-id
                                       :ad/approve-state [:db/default :pending]
                                       :ad/balance [:db/default 0]
                                       :ad/recent-cost [:db/default 0]
                                       :ad/updated-at :db/now}])
                        :create-session]
       :ad/customer-id customer-id}))

  :create-session
  (fn [{:keys [biff/base-url biff/secret ad/customer-id]}]
    {:biff.pipe/next [(lib.pipe/http
                       :post
                       "https://api.stripe.com/v1/checkout/sessions"
                       {:basic-auth [(secret :stripe/api-key)]
                        :multi-param-style :array
                        :form-params
                        {:payment_method_types ["card"]
                         :mode "setup"
                         :customer customer-id
                         :success_url (str base-url
                                           (href receive-payment-method)
                                           "?session-id={CHECKOUT_SESSION_ID}")
                         :cancel_url (str base-url (href page-route))}
                        :as :json})
                      :redirect]})

  :redirect
  (fn [{:keys [biff.pipe.http/output session]}]
    (let [{:keys [url id]} (:body output)]
      {:biff.pipe/next [(lib.pipe/tx [{:db/doc-type :ad
                                       :db.op/upsert {:ad/user (:uid session)}
                                       :ad/session-id id
                                       :ad/updated-at :db/now}])]
       :status 303
       :headers {"Location" url}})))

(defpost-pathom save-ad
  [{:session/user
    [:xt/id
     {(? :user/ad)
      [(? :ad/url)
       (? :ad/title)
       (? :ad/description)
       (? :ad/image-url)
       :ad/approve-state]}]}]
  (fn [{:keys [biff.form/params]} {:keys [session/user]}]
    (let [{:ad/keys [budget url]} params
          {old-ad :user/ad} user
          keys* [:ad/bid
                 :ad/budget
                 :ad/url
                 :ad/title
                 :ad/description
                 :ad/image-url
                 :ad/paused]
          new-ad (-> (merge (zipmap keys* (repeat :db/dissoc)) params)
                     (select-keys keys*)
                     (merge (when budget
                              {:ad/budget (max budget (* 10 (:ad/bid params 0)))})
                            (when url
                              {:ad/url (lib.content/add-protocol url)})))
          state (if (every? #(= (% old-ad) (% new-ad))
                            [:ad/url :ad/title :ad/description :ad/image-url])
                  (:ad/approve-state old-ad :pending)
                  :pending)
          tx [(merge {:db/doc-type :ad
                      :db.op/upsert {:ad/user (:xt/id user)}
                      :ad/approve-state state
                      :ad/balance [:db/default 0]
                      :ad/recent-cost [:db/default 0]
                      :ad/updated-at :db/now}
                     new-ad)]]
      {:status 303
       :headers {"Location" (href page-route {:saved true})}
       :biff.pipe/next [:biff.pipe/tx]
       :biff.pipe.tx/input tx})))
(obfuscate-url! #'save-ad)

(defpost upload-image
  :start
  (fn [{:keys [biff/base-url yakread.s3.images/edge biff.form/params multipart-params]}]
    (let [image-id (gen/uuid)
          file-info (get multipart-params "image-file")
          url (str edge "/" image-id)]
      {:biff.pipe/next [:biff.pipe/s3 :biff.pipe/http :biff.pipe/pathom :render]
       :biff.pipe.s3/input {:config-ns 'yakread.s3.images
                            :method "PUT"
                            :key (str image-id)
                            :body (:tempfile file-info)
                            :headers {"x-amz-acl" "public-read"
                                      "content-type" (:content-type file-info)}}
       :biff.pipe.http/input {:url     (ui/weserv {:url url
                                                   :w 150
                                                   :h 150
                                                   :fit "cover"
                                                   :a "attention"})
                              :method  :get
                              :headers {"User-Agent" base-url}}
       :biff.form/params (assoc params :ad/image-url url)
       :biff.pipe.pathom/query [{::form-ad [:ad/ui-preview-card]}]
       ::url url}))

  :render
  (fn [{:keys [biff.pipe.pathom/output ::url]}]
    (render (image-upload-input {:value url})
            [:div#preview {:hx-swap-oob "true"}
             (get-in output [::form-ad :ad/ui-preview-card])])))
(obfuscate-url! #'upload-image)

(defpost change-image
  :start
  (let [ret (delay (image-upload-input {}))]
    (fn [_] @ret)))
(obfuscate-url! #'change-image)

(let [form-keys [:ad/bid
                 :ad/budget
                 :ad/title
                 :ad/description
                 :ad/image-url
                 :ad/url]]
  (defresolver form-ad [ctx params]
    {::pco/input [{:session/user
                   [{(? :user/ad) [:xt/id]}]}]
     ::pco/output [{::form-ad (into [:xt/id] form-keys)}]}
    {::form-ad (merge (when-some [id (get-in params [:session/user :user/ad :xt/id])]
                        {:xt/id id})
                      (select-keys (:biff.form/params ctx) form-keys))}))

(defget refresh-preview
  [{::form-ad [:ad/ui-preview-card]}]
  (fn [ctx params]
    [:div#preview (get-in params [::form-ad :ad/ui-preview-card])]))
(obfuscate-url! #'refresh-preview)

(def autocomplete
  ["/wompvertise/autocomplete"
   {:get
    (lib.pipe/make
     :start
     (fn [{:keys [biff.form/params]}]
       (let [{:ad/keys [url]} params]
         {:biff.pipe/next [(lib.pipe/http :get url)
                           :query]
          :biff.pipe/catch :biff.pipe/http}))

     :query
     (fn [{:keys [biff.form/params biff.pipe.http/output]}]
       (let [parsed (or (lib.content/pantomime-parse (:body output)) {})
             ad-from-url {:ad/title (some-> (some parsed [:title :og/title :dc/title])
                                            first
                                            (str/replace #" \| Substack$" "")
                                            (lib.content/truncate 75))
                          :ad/description (some-> (some parsed [:description :og/description])
                                                  first
                                                  (lib.content/truncate 250))
                          :ad/image-url (lib.core/pred-> (first (some parsed [:image :og/image :twitter/image]))
                                                         :url
                                                         :url)}
             ad (merge params
                       ad-from-url
                       (lib.core/filter-vals params lib.core/something?))]
         {:biff.form/params ad
          :biff.pipe/next [(lib.pipe/pathom {} [{::form-ad [:ad/ui-preview-card]}])
                           :render]}))

     :render
     (fn [{:keys [biff.pipe.pathom/output biff.form/params]}]
       (render (form {:ad params})
               [:div#preview {:hx-swap-oob "true"
                              :hx-swap "morph"}
                (get-in output [::form-ad :ad/ui-preview-card])])))}])

(def preview-hx-opts
  {:hx-get (href refresh-preview)
   :hx-target "#preview"
   :hx-trigger "input changed delay:0.5s"
   :hx-swap "morph"
   :hx-include "closest form"
   :hx-params "not __anti-forgery-token"})

(defn image-upload-input [{:keys [value error]}]
   [:div#image-upload
    [:input {:type "hidden" :name (pr-str :ad/image-url) :value value}]
    (if value
      [:<>
       (ui/input-label {} (required-field->label :ad/image-url))
       [:.flex.items-center
        [:img.rounded
         {:src (ui/weserv {:url value
                           :w 150
                           :h 150
                           :fit "cover"
                           :a "attention"})
          :style {:object-fit "cover"
                  :object-position "center"
                  :width "5.5rem"
                  :height "5.5rem"}}]
        [:.w-3]
        [:button.text-blue-600.hover:underline
         {:hx-trigger "click"
          :hx-post (href change-image)
          :hx-target "#image-upload"
          :hx-swap "outerHTML"}
         "Change"]]]
      (ui/form-input
       {:ui/label (required-field->label :ad/image-url)
        :ui/description "Should be at least 150x150"
        :type "file"
        :indicator-id "image-upload-indicator"
        :accept "image/apng, image/avif, image/gif, image/jpeg, image/png, image/svg+xml, image/webp"
        :name "image-file"
        :hx-post (href upload-image)
        :hx-target "#image-upload"
        :hx-swap "outerHTML"
        :hx-include "closest form"
        :hx-indicator "#image-upload-indicator"
        :hx-encoding "multipart/form-data"}))
    (when error
      (ui/input-error error))])

(defn form-input [opts]
  (let [label (or (:ui/label opts)
                  (required-field->label (:name opts)))]
    (ui/form-input (assoc opts :ui/label label))))

(defn form [{:keys [ad params]}]
  (biff/form
   {:action (href save-ad)
    :class '[flex flex-col gap-6]}
   [:.grid.sm:grid-cols-2.gap-6
    (ui/form-input {:ui/label "Maximum cost-per-click"
                    :ui/icon "dollar-sign-regular"
                    :ui/description "The higher you bid, the more frequently your ad will be shown."
                    :name :ad/bid
                    :placeholder "1.50"
                    :value (some-> (:ad/bid ad) fmt-dollars)})
    (ui/form-input {:ui/label "Maximum weekly budget"
                    :ui/icon "dollar-sign-regular"
                    :ui/description "Must be at least 10x your maximum cost-per-click."
                    :name :ad/budget
                    :placeholder "75.00"
                    :value (some-> (:ad/budget ad) fmt-dollars)})]
   (form-input {:name :ad/url
                :placeholder "https://example.com?utm_source=yakread"
                :hx-get (href autocomplete)
                :hx-target "closest form"
                :hx-swap "morph"
                :hx-include "closest form"
                :hx-params "not __anti-forgery-token"
                :hx-indicator "#autocomplete-indicator"
                :value (:ad/url ad)})
   (form-input (merge {:ui/description "Maximum 75 characters."
                       :name :ad/title
                       :value (:ad/title ad)}
                      preview-hx-opts))
   (form-input (merge {:ui/input-type :textarea
                       :ui/description "Maximum 250 characters."
                       :rows 3
                       :name :ad/description
                       :value (:ad/description ad)}
                      preview-hx-opts))
   (image-upload-input {:value (:ad/image-url ad)
                        :error (when (= (:error params) "image")
                                 "We weren't able to upload that file.")})
   (ui/checkbox {:ui/label "Pause ad"
                 :ui/label-position :above
                 :id "pause"
                 :name :ad/paused
                 :checked (when (:ad/paused ad)
                            "checked")})
   [:div (ui/confirmed-submit {:ui/submitted (:saved params)})]))

(defn view-payment-method [{:keys [ad]}]
  [:div#payment-method
   (ui/input-label {} (:ad/payment-method required-field->label))
   (if-some [{:keys [brand last4 exp_year exp_month]} (:ad/card-details ad)]
     [:.flex.items-baseline.gap-3
      [:div
       [:span.align-middle (str/upper-case brand) " ending in " last4
        " (expires " exp_month "/" exp_year ")"]
       ". "
       [:button {:class '[font-medium text-neut-400 hover:underline]
                 :hx-post (href delete-payment-method)
                 :hx-confirm "Remove your payment method? Your ad will stop running."
                 :hx-indicator "#delete-pm-indicator"}
        "Remove card"]
       "."]
      [:img.h-6.htmx-indicator.hidden
       {:id "delete-pm-indicator"
        :src "/img/spinner2.gif"}]]
     (biff/form
       {:action (href add-payment-method)
        :hx-boost "false"}
       (ui/button
         {:type "Submit"}
         "Add a payment method")))])

;; TODO add param to app-shell for including plausible
(defget page-route "/advertise"
  [:app.shell/app-shell
   {(? :session/user)
    [:xt/id
     {(? :user/ad)
      [(? :ad/bid)
       (? :ad/budget)
       (? :ad/url)
       (? :ad/title)
       (? :ad/description)
       (? :ad/image-url)
       (? :ad/paused)
       (? :ad/balance)
       (? :ad/payment-failed)
       (? :ad/card-details)
       :ad/n-clicks
       :ad/ui-preview-card
       :ad/state
       :ad/incomplete-fields]}]}
   {(? :session/anon)
    [:ad/ui-preview-card]}]
  (fn [{:keys [params] form-params :biff.form/params}
       {:keys [app.shell/app-shell session/user session/anon]}]
    (let [{:user/keys [ad]} user
          {:ad/keys [n-clicks balance state]} ad]
      (app-shell
       {:title "Advertise"
        :banner (case state
                  :payment-failed
                  (ui/banner-error
                   "Your ad is paused because payment failed. "
                   "Update your payment method to resume.")

                  :running
                  (ui/banner-success "Your ad is running.")

                  :pending
                  (ui/banner-success "Your ad will begin running after it is approved.")

                  :rejected
                  (ui/banner-error
                   "Your ad was rejected. If you update your ad, it will be "
                   "reviewed again.")

                  :incomplete
                  (ui/banner-warning
                   [:div "Your ad is incomplete. Please fill out the remaining fields: "
                    (str/join ", " (keep required-field->label (:ad/incomplete-fields ad)))
                    "."])

                  :paused
                  (ui/banner-warning "Your ad is paused.")

                  nil)}
       (ui/page-header {:title "Advertise"
                        :description "Grow your newsletter with Yakread's self-serve, pay-per-click ads."})
       [:fieldset.disabled:opacity-60
        {:disabled (when-not user "disabled")}

        (ui/page-well
         (ui/section
          {}
          [:div "Your ad will be displayed on the For You page and in the digest emails. "
           "You'll be charged for each unique click your ad receives."]
          (when (or (#{:running :pending} state)
                    (not= 0 (or n-clicks 0))
                    (not= 0 (or balance 0)))
            [:<>
             [:ul.my-0.gap-2.flex.flex-col
              [:li
               "Your ad has received "
               [:span.font-bold n-clicks] " click" (when (not= 1 n-clicks) "s")
               "."]
              (if (<= 0 balance)
                [:li "Your balance is "
                 [:span.font-bold "$" (fmt-dollars balance)]
                 "."]
                [:li "You have "
                 [:span.font-bold "$" (fmt-dollars (- balance))]
                 " of ad credit."])]
             [:hr]])
          [:div (view-payment-method {:ad ad})]
          (form {:ad (merge ad params) :params params})
          [:.text-sm.text-neut-600
           "Ads are approved manually before running. See the "
           [:a.link.inline-block {:href "/ad-policy" :target "_blank"} "ad content policy"] "."]))

        [:.h-10]
        [:h3.font-bold.text-lg.max-sm:px-4 "Preview"]
        [:.h-3]
        [:div#preview
         (if user
           (:ad/ui-preview-card ad)
           (:ad/ui-preview-card anon))]]))))

;; TODO move this out of source control in some way
(def policy-route
  ["/ad-policy"
   {:get (fn [_]
           (ui/plain-page
            {:base/title "Ad content policy | Yakread"}
            [:div.text-black
             (ui/page-well
              (ui/section
               {:title "Ad content policy"}
               [:div.prose.text-left
                [:p "I reserve the right to reject an ad for any reason, including but not limited to:"]
                [:ul
                 [:li "It's clickbait, ragebait, or another form of low-quality content."]
                 [:li "It's NSFW."]
                 [:li "It's advertising a competitor to Yakread (i.e. another reading app)."]
                 [:li "I don't think many Yakread subscribers would be interested."]]
                [:p "If you're unsure about something, feel free to "
                 [:a.link {:href (href routes/contact)} "contact me"] "."]]))]))}])

(def module {:routes [page-route
                      policy-route
                      ["" {:middleware [lib.mid/wrap-signed-in]}
                       save-ad
                       upload-image
                       change-image
                       autocomplete
                       refresh-preview
                       add-payment-method
                       receive-payment-method
                       delete-payment-method]]
             :resolvers [form-ad]})
