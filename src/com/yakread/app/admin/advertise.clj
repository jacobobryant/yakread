(ns com.yakread.app.admin.advertise
  (:require
   [clojure.tools.logging :as log]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.icons :as lib.icons]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui])
  (:import
   [java.time ZonedDateTime]))

(declare page-route)

(fx/defroute update-ad
  :post
  (fn [{{:keys [ad]} :params}]
    {:biff.fx/tx [[:patch-docs :ad ad]]
     :status 200
     :body ""}))

(fx/defroute create-pending-charges
  :post
  (fn [{{:keys [tx]} :params}]
    {:biff.fx/tx tx
     :headers {"hx-refresh" "true"}
     :status 204}))

(fx/defroute-pathom handle-pending-charges
  [{:admin/pending-charges
    [:xt/id
     (? :ad.credit/stripe-status)
     :ad.credit/amount
     {:ad.credit/ad [:xt/id
                     :ad/title
                     :ad/customer-id
                     :ad/payment-method
                     {:ad/user [:user/email]}]}]}]

  :post
  (fn [{:keys [biff/secret]} output]
    (let [{new* nil succeeded "succeeded" failed "requires_payment_method"
           :as charges-by-status}
          (group-by :ad.credit/stripe-status (:admin/pending-charges output))]
      (log/info "handling pending charges" {:new (count new*)
                                            :succeeded (count succeeded)
                                            :faild (count failed)})
      (concat
       {:status 204
        :headers {"hx-refresh" "true"}
        :biff.fx/tx (concat
                     ;; record succeeded payment
                     (for [{:keys [xt/id] :ad.credit/keys [amount ad]} succeeded
                           :let [{ad-id :xt/id} ad]]
                       [[:patch-docs :ad-credit {:xt/id id :ad.credit/charge-status :confirmed}]
                        {:update :ad
                         :set {:ad/balance [:- :ad/balance amount]}
                         :where [:= :xt/id ad-id]}])

                     ;; record failed payment
                     (for [{:keys [xt/id] :ad.credit/keys [ad]} failed
                           :let [{ad-id :xt/id} ad]]
                       [[:patch-docs :ad-credit {:xt/id id :ad.credit/charge-status :failed}]
                        [:patch-docs :ad {:xt/id ad-id :ad/payment-failed true}]]))}

       ;; create payment intent
       (for [{:keys [xt/id]
              :ad.credit/keys [amount ad]} new*
             :let [{:ad/keys [customer-id payment-method user]} ad
                   {:user/keys [email]} user]]
         {:biff.fx/http {:method :post
                         :url "https://api.stripe.com/v1/payment_intents"
                         :basic-auth [(secret :stripe/api-key) ""]
                         :headers {"Idempotency-Key" (str id)}
                         :flatten-nested-form-params true
                         :form-params {:amount amount
                                       :currency "usd"
                                       :confirm true
                                       :customer customer-id
                                       :payment_method payment-method
                                       :off_session true
                                       :description "Yakread advertising"
                                       :receipt_email email
                                       :metadata {:charge_id (str id)}}}})))))

(defresolver pending-ads [{:keys [admin/ads]}]
  {::pco/input [{:admin/ads [:xt/id
                             :ad/state
                             (? :ad/ui-preview-card)]}]
   ::pco/output [::pending-ads]}
  {::pending-ads
   (let [pending (filterv #(= :pending (:ad/state %)) ads)]
     [:.flex.flex-wrap
      (for [{:ad/keys [ui-preview-card] id :xt/id} pending]
        [:.pending-ad.flex.flex-col.gap-4.mb-8.mr-8
         [:.flex.gap-2
          (for [[state label icon] [[:approved "Approve" "check-solid"]
                                    [:rejected "Reject" "xmark-solid"]]]
            (ui/button {:hx-post (href update-ad {:ad {:xt/id id :ad/approve-state state}})
                        :hx-target "closest .pending-ad"
                        :hx-swap "outerHTML"
                        :ui/icon icon}
              label))]
         [:.max-w-screen-sm ui-preview-card]])])})

(defresolver ads-table [{:keys [admin/ads] admin :session/user}]
  {::pco/input [{:admin/ads [:xt/id
                             {:ad/user [:user/email]}
                             (? :ad/title)
                             :ad/state
                             :ad/balance
                             (? :ad/bid)
                             (? :ad/budget)
                             :ad/updated-at
                             (? :ad/chargeable)
                             (? :ad/amount-pending)
                             {(? :ad/pending-charge) [:xt/id
                                                      (? :ad.credit/stripe-status)]}]}
                {:session/user [:user/timezone]}]}
  {::ads-table
   (let [pending-ads (filterv :ad/pending-charge ads)
         charge-tx (for [{:keys [xt/id ad/balance ad/chargeable]} ads
                         :when chargeable]
                     {:db/doc-type :ad.credit
                      :ad.credit/ad id
                      :ad.credit/source :charge
                      :ad.credit/amount balance
                      :ad.credit/created-at :db/now
                      :ad.credit/charge-status :pending})]
     (ui/wide-page-well
      [:div.flex.gap-4
       (ui/button {:hx-post (href create-pending-charges {:tx charge-tx})
                   :disabled (empty? charge-tx)}
         "Create pending charges")
       (ui/button {:hx-post (href handle-pending-charges)
                   :disabled (empty? pending-ads)}
         "Handle pending charges")]
      (ui/table
        ["Email" "Title" "State" "Balance" "Bid" "Budget" "Updated" "Chargeable" "Pending" "Stripe status"]
        (for [{:ad/keys [user title state balance bid budget updated-at chargeable amount-pending pending-charge]}
              (sort-by (fn [{:ad/keys [chargeable amount-pending updated-at balance]}]
                         (if (or chargeable amount-pending)
                           [0 (- balance)]
                           [1 (- (inst-ms updated-at))]))
                       ads)]
          [(:user/email user)
           title
           [:div.px-1 {:class [(case state
                                 :running "bg-tealv-50"
                                 (:paused :incomplete) "bg-yellv-50"
                                 (:rejected :payment-failed) "bg-redv-50"
                                 nil)]}
            (name state)]
           (some-> balance ui/fmt-cents)
           (some-> bid ui/fmt-cents)
           (some-> budget ui/fmt-cents)
           (.toLocalDate (ZonedDateTime/ofInstant updated-at (:user/timezone admin)))
           (when chargeable
             (lib.icons/base "check-solid" {:class "w-4 h-4"}))
           (some-> amount-pending ui/fmt-cents)
           (:ad.credit/stripe-status pending-charge)]))))})

(fx/defroute-pathom page-content-route "/admin/advertise/content"
  [::pending-ads
   ::ads-table]

  :get
  (fn [_ {::keys [pending-ads ads-table]}]
    [:<>
     pending-ads
     ads-table]))

(fx/defroute-pathom page-route "/admin/advertise"
  [:app.shell/app-shell]

  :get
  (fn [ctx {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :advertise)
     (ui/lazy-load (href page-content-route)))))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            page-content-route
            update-ad
            create-pending-charges
            handle-pending-charges]
   :resolvers [pending-ads
               ads-table]})
