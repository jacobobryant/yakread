(ns com.yakread.app.settings
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.form :as lib.form]
   [com.yakread.lib.fx :as fx :refer [defroute defroute-pathom]]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [tick.core :as tick])
  (:import
   [java.time LocalTime ZoneId]
   [java.time.format DateTimeFormatter]))

(defn days-checkboxes [selected-days]
  [:.grid.grid-cols-2.sm:grid-cols-4.gap-x-6#days
   (for [k [:monday :tuesday :wednesday :thursday :friday :saturday :sunday]]
     [:.py-2
      (ui/checkbox {:ui/label (str/capitalize (name k))
                    :name :user/digest-days
                    :value (name k)
                    :checked (when (contains? selected-days k)
                               "checked")})])])

(declare page)
(declare account-deleted)

(defroute set-timezone
  :post
  (fn [{:keys [session biff.form/params]}]
    {:biff.fx/tx [[:patch-docs :user
                   {:xt/id (:uid session)
                    :user/timezone* (:user/timezone* params)}]]
     :status 204}))

(defroute save-settings
  :post
  (fn [{:keys [session biff.form/params]}]
    {:biff.fx/tx [[:patch-docs :user
                   (merge {:xt/id (:uid session)
                           :user/use-original-links false}
                          (select-keys params [:user/digest-days
                                               :user/send-digest-at
                                               :user/timezone*
                                               :user/use-original-links]))]]
     :status 303
     :headers {"location" (href page)}}))

(defroute stripe-webhook "/stripe/webhook"
  :post
  (fn [{:keys [body-params] :as ctx}]
    (log/info "received stripe event" (:type body-params))
    (if-some [next-state (case (:type body-params)
                           "customer.subscription.created" :update-plan
                           "customer.subscription.updated" :update-plan
                           "customer.subscription.deleted" :delete-plan
                           nil)]
      {:biff.fx/next next-state}
      {:status 204}))

  :update-plan
  (fn [{:keys [biff/conn stripe/quarter-price-id body-params]}]
    (let [{:keys [customer items cancel_at]} (get-in body-params [:data :object])
          price-id (get-in items [:data 0 :price :id])
          plan (if (= quarter-price-id price-id)
                 :quarter
                 :annual)
          [{user-id :xt/id}] (biffx/q conn
                                      {:select :xt/id
                                       :from :user
                                       :where [:= :user/customer-id customer]})]
      {:biff.fx/tx [{:update :user
                     :set {:user/plan plan
                           :user/cancel-at (when cancel_at
                                             (tick/in (tick/instant (* cancel_at 1000))
                                                      "UTC"))}
                     :where [:= :xt/id user-id]}]
       :status 204}))

  :delete-plan
  (fn [{:keys [biff/conn body-params]}]
    (let [{:keys [customer]} (get-in body-params [:data :object])
          [{user-id :xt/id}] (biffx/q conn
                                      {:select :xt/id
                                       :from :user
                                       :where [:= :user/customer-id customer]})]
      {:biff.fx/tx [{:update :user
                     :set {:user/plan nil
                           :user/cancel-at nil}
                     :where [:= :xt/id user-id]}]
       :status 204})))

(defroute manage-premium
  :post
  (fn [_]
    {:biff.fx/pathom [{:session/user [:user/customer-id]}]
     :biff.fx/next :create-session})

  :create-session
  (fn [{:keys [biff/base-url biff/secret biff.fx/pathom]}]
    (let [{:user/keys [customer-id]} (:session/user pathom)]
      {:biff.fx/http {:method :post
                      :url "https://api.stripe.com/v1/billing_portal/sessions"
                      :basic-auth [(secret :stripe/api-key)]
                      :form-params {:customer customer-id
                                    :return_url (str base-url (href page))}
                      :as :json
                      :socket-timeout 10000
                      :connection-timeout 10000}
       :biff.pipe/next :redirect}))

  :redirect
  (fn [{:keys [biff.fx/http]}]
    {:status 303
     :headers {"location" (get-in http [:body :url])}}))

(defroute upgrade-premium
  :post
  (fn [_]
    {:biff.fx/queue [{:session/user [:user/premium
                                     :user/email
                                     (? :user/customer-id)]}]
     :biff.fx/next :check-customer-id})

  :check-customer-id
  (fn [{:keys [biff/secret biff.fx/pathom]}]
    (let [{:user/keys [premium customer-id email]} (:session/user pathom)]
      (cond
        premium
        {:status 303 :headers {"location" (href page)}}

        (not customer-id)
        {:biff.fx/http {:method :post
                        :url "https://api.stripe.com/v1/customers"
                        :basic-auth [(secret :stripe/api-key)]
                        :form-params {:email email}
                        :as :json}
         :biff.fx/next :create-session}

        :else
        {:biff.fx/next :create-session
         :user/customer-id customer-id})))

  :create-session
  (fn [{:biff/keys [base-url secret]
        :keys [session
               params
               biff.fx/http
               user/customer-id
               stripe/quarter-price-id
               stripe/annual-price-id]}]
    (let [customer-id (or customer-id (get-in http [:body :id]))
          price-id (if (= (:plan params) "quarter")
                     quarter-price-id
                     annual-price-id)]
      [(when http
         {:biff.fx/tx [[:patch-docs :user
                        {:xt/id (:uid session)
                         :user/customer-id customer-id}]]})
       {:biff.fx/http {:method :post
                       :url "https://api.stripe.com/v1/checkout/sessions"
                       :basic-auth [(secret :stripe/api-key)]
                       :multi-param-style :array
                       :form-params {:mode "subscription"
                                     :allow_promotion_codes true
                                     :customer customer-id
                                     "line_items[0][quantity]" 1
                                     "line_items[0][price]" price-id
                                     :success_url (str base-url (href page {:upgraded (:plan params)}))
                                     :cancel_url (str base-url (href page))}
                       :as :json
                       :socket-timeout 10000
                       :connection-timeout 10000}
        :biff.fx/next :redirect}]))

  :redirect
  (fn [{:keys [biff.fx/http]}]
    {:status 303
     :headers {"location" (get-in http [:body :url])}}))

(defroute export-data
  :post
  (fn [{:keys [session]}]
    {:biff.fx/queue {:id :work.account/export-user-data
                     :job {:user/id (:uid session)}}
     :status 204}))

(defroute-pathom delete-account
  [{:session/user [:xt/id
                   :user/email
                   :user/account-deletable
                   (? :user/account-deletable-message)]}]

  :post
  (fn [{:keys [headers session]} {:keys [session/user]}]
    (let [{:user/keys [email account-deletable account-deletable-message]} user]
      (cond
        (not account-deletable)
        {:status 400
         :headers {"hx-redirect" (href page {:error-msg account-deletable-message})}}

        (not= (get headers "hx-prompt") email)
        {:status 400
         :headers {"hx-redirect" (href page {:error-msg (str "The email address you entered "
                                                             "was incorrect.")})}}

        :else
        {:biff.fx/queue {:id :work.account/delete-account
                         :job {:user/id (:xt/id user)}}
         :status 204
         :headers {"hx-redirect" (href account-deleted)}
         :session {}}))))

(defn time-text [local-time]
  (.format local-time (DateTimeFormatter/ofPattern "h:mm a")))

(defresolver main-settings [{:keys [session/user]}]
  {::pco/input [{(? :session/user) [:xt/id
                                    :user/email
                                    :user/digest-days
                                    :user/send-digest-at
                                    :user/timezone
                                    (? :user/use-original-links)]}]}
  {::main-settings
   (let [{:user/keys [digest-days send-digest-at timezone use-original-links]} user]
     (ui/section
      {}
      (biff/form
        {:action (href save-settings)
         :class '[flex flex-col gap-6]}
        [:div
         (ui/input-label {} "Which days would you like to receive the digest email?")
         (days-checkboxes digest-days)]
        [:div
         (ui/input-label {} "What time of day would you like to receive the digest email?")
         (ui/select {:name :user/send-digest-at
                     :ui/options (for [i (range 24)
                                       :let [value (LocalTime/of i 0)]]
                                   {:label (time-text value) :value (str value)})
                     :ui/default (str send-digest-at)})]
        [:div
         (ui/input-label {} "Your timezone:")
         (ui/select {:name :user/timezone
                     :ui/options (for [zone-str (sort (ZoneId/getAvailableZoneIds))]
                                   {:label zone-str :value zone-str})
                     :ui/default (str timezone)})]

        [:div
         (ui/checkbox {:name :user/use-original-links
                       :ui/label "Open links on the original website:"
                       :ui/label-position :above
                       :checked (when use-original-links
                                  "checked")})]

        [:div (ui/button {:type "submit"} "Save")])))})

(defresolver premium [{:keys [session/user]}]
  {::pco/input [{(? :session/user) [(? :user/plan)
                                    (? :user/cancel-at)]}]}
  {::premium
   (let [{:user/keys [premium plan cancel-at timezone]} user]
     (ui/section
      {:title "Premium"}
      [:div
       (if premium
         [:<>
          [:div
           (if cancel-at
             [:<> "You're on the premium plan until "
              (.format (.atZone cancel-at timezone)
                       (DateTimeFormatter/ofPattern "d MMMM yyyy"))
              ". After that, you'll be downgraded to the free plan. "]
             [:<>
              "You're on the "
              (case plan
                :quarter "$30 / 3 months"
                :annual "$60 / 12 months"
                "premium")
              " plan. "])
           (biff/form
             {:action (href manage-premium)
              :hx-boost "false"
              :class "inline"}
             [:button.link {:type "submit"} "Manage your subscription"])
           "."]]
         [:<>
          [:div "Support Yakread by upgrading to a premium plan without ads:"]
          [:.h-6]
          [:div {:class '[flex flex-col sm:flex-row justify-center gap-4 sm:gap-12 items-center]}
           (biff/form
             {:action (href upgrade-premium)
              :hx-boost "false"
              :hidden {:plan "quarter"}}
             (ui/button {:type "submit" :class "!min-w-[150px]"} "$30 / 3 months"))
           (biff/form
             {:action (href upgrade-premium)
              :hx-boost "false"
              :hidden {:plan "annual"}}
             (ui/button {:type "submit" :class "!min-w-[150px]"} "$60 / 12 months"))]
          [:.h-6]])]))})

(defresolver account [{:keys [session/user]}]
  {::pco/input [{(? :session/user) [:user/email
                                    :user/account-deletable
                                    (? :user/account-deletable-message)]}]}
  {::account
   (let [{:user/keys [account-deletable account-deletable-message]} user]
     (ui/section
      {:title "Account"}
      (ui/button {:hx-post (href export-data)
                  :_ (str "on htmx:afterRequest alert('Your data is being exported. "
                          "When it is ready, a download link will be emailed to you.')")
                  :ui/icon "cloud-arrow-down"
                  :class '[w-full max-w-40]} "Export data")
      (ui/button (merge
                  {:ui/icon "xmark-solid"
                   :ui/type :danger
                   :class ["max-w-40"]}
                  (if account-deletable
                    {:hx-post (href delete-account)
                     :hx-prompt "Account deletion is irreversible. To confirm, enter your email address."}
                    {:_ (str "on click call alert('" account-deletable-message "')")}))
        "Delete account")))})

(defroute-pathom page "/settings"
  [:app.shell/app-shell
   {(? :session/user) [:xt/id]}
   ::main-settings
   ::premium
   ::account]

  :get
  (fn [{:keys [params]} {:keys [app.shell/app-shell
                                session/user]
                         ::keys [main-settings premium account]}]
    (app-shell
     {:title "Settings"}
     (ui/page-header {:title "Settings"})
     [:fieldset.disabled:opacity-60
      {:disabled (when-not user "disabled")}
      (ui/page-well main-settings
                    premium
                    account)]
     (when (:error-msg params)
       [:div.hidden {:data-error-msg (:error-msg params)
                     :_ "init call alert(me.getAttribute('data-error-msg')) then remove me"}]))))

(def account-deleted
  ["/account-deleted"
   {:get (fn [_] (ui/plain-page {} "Your account has been deleted."))}])

(def unsubscribe-success
  ["/unsubscribed"
   {:get (fn [_] (ui/plain-page {} "You have been unsubscribed."))}])

(defroute click-unsubscribe-route "/unsubscribe/:ewt"
  :get
  (fn [{:keys [uri]}]
    [:html
     [:body
      (biff/form {:action uri})
      [:script (biff/unsafe "document.querySelector('form').submit();")]]])

  :post
  (fn [{:biff/keys [safe-params]}]
    (let [{:keys [action user/id]} safe-params]
      (if (not= action :action/unsubscribe)
        (ui/on-error {:status 400})
        {:biff.fx/tx [[:patch-docs :user
                       {:xt/id id
                        :user/digest-days #{}}]]
         :status 303
         :headers {"location" (href unsubscribe-success)}}))))

(def parser-overrides
  {:user/digest-days (fn [x]
                       (into #{}
                             (map keyword)
                             (cond-> x (not (vector? x)) vector)))})

(def module
  {:routes [page
            unsubscribe-success
            account-deleted
            click-unsubscribe-route
            ["" {:middleware [lib.mid/wrap-signed-in
                              [lib.form/wrap-parse-form {:overrides parser-overrides}]]}
             save-settings
             set-timezone
             manage-premium
             upgrade-premium
             export-data
             delete-account]]
   :api-routes [stripe-webhook]
   :resolvers [main-settings
               premium
               account]})
