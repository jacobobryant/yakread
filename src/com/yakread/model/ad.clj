(ns com.yakread.model.ad
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.misc.coll :as wss-coll]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pipeline :as lib.pipe :refer [defpipe]]
   [com.yakread.routes :as routes]
   [lambdaisland.uri :as uri]
   [tick.core :as tick]))

;; TODO keep recent-cost updated (or calculate it on the fly if it's fast enough)
(defresolver effective-bid [{:ad/keys [bid budget recent-cost]}]
  {:ad/effective-bid (min bid (max 0 (- budget recent-cost)))})

(defresolver ad-id [{:keys [xt/id ad/user]}]
  {:ad/id id})

(defresolver xt-id [{:keys [ad/id]}]
  {:xt/id id})

(defresolver user-ad [{:keys [biff/conn]} {:keys [xt/id]}]
  {::pco/output [{:user/ad [:xt/id]}]}
  (when-some [ad (first (biffx/q conn
                                 {:select :xt/id
                                  :from :ad
                                  :where [:= :ad/user id]}))]
    {:user/ad ad}))

(defresolver url-with-protocol [{:keys [ad/url]}]
  {:ad/url-with-protocol (lib.content/add-protocol url)})

(defresolver recording-url [{:biff/keys [base-url href-safe]}
                            {:ad/keys [id url-with-protocol click-cost]}]
  {:ad/recording-url
   (fn [{:keys [params :ad.click/source]
         user-id :user/id}]
     (str base-url
          (href-safe routes/click-ad
                     (merge params
                            {:action :action/click-ad
                             :ad/id id
                             :ad/url url-with-protocol
                             :ad/click-cost click-cost
                             :ad.click/source source
                             :user/id user-id}))))})

(def ^:private required-fields
  [:ad/payment-method
   :ad/bid
   :ad/budget
   :ad/url
   :ad/title
   :ad/description
   :ad/image-url])

(defresolver state [{:ad/keys [paused
                               payment-failed
                               approve-state]
                     :as ad}]
  {::pco/input (into [(? :ad/paused)
                      (? :ad/payment-failed)
                      :ad/approve-state]
                     (mapv ? required-fields))
   ::pco/output [:ad/state
                 :ad/incomplete-fields]}
  (let [incomplete-fields (remove (comp lib.core/something? ad) required-fields)]
    {:ad/state (cond
                 payment-failed :payment-failed
                 paused :paused
                 (not-empty incomplete-fields) :incomplete
                 (= approve-state :approved) :running
                 (= approve-state :rejected) :rejected
                 (= approve-state :pending) :pending)
     :ad/incomplete-fields incomplete-fields}))

(defresolver n-clicks [{:keys [biff/conn]} {:keys [ad/id]}]
  {:ad/n-clicks
   (-> (biffx/q conn
                {:select [[[:count [:distinct :ad.click/user]] :cnt]]
                 :from :ad-click
                 :where [:= :ad.click/ad id]})
       first
       :cnt)})

(defresolver host [{:keys [ad/url-with-protocol]}]
  {:ad/host (some-> url-with-protocol uri/uri :host str/trim not-empty)})

(defresolver last-clicked [{:keys [biff/conn]} ads]
  {::pco/input [:xt/id]
   ::pco/output [:ad/last-clicked]
   ::pco/batch? true}
  (->> (biffx/q conn
                {:select [[:ad.click/ad :xt/id]
                          [[:max :ad.click/created-at] :ad/last-clicked]]
                 :from :ad-click
                 :where [:in :ad.click/ad (mapv :xt/id ads)]})
       (wss-coll/restore-order ads :xt/id)))

(defresolver amount-pending [{:keys [biff/conn]} ads]
  {::pco/input [:xt/id]
   ::pco/output [:ad/amount-pending]
   ::pco/batch? true}
  (->> (biffx/q conn
                {:select [[:ad.credit/ad :xt/id]
                          [[:sum :ad.credit/amount] :ad/amount-pending]]
                 :from :ad-credit
                 :where [:and
                         [:in :ad.credit/ad (mapv :xt/id ads)]
                         [:= :ad.credit/charge-status [:lift :pending]]]})
       (wss-coll/restore-order ads :xt/id)))

(defresolver chargeable [{:keys [biff/now]} {:ad/keys [payment-method
                                                       payment-failed
                                                       balance
                                                       amount-pending
                                                       paused
                                                       last-clicked]}]
  {::pco/input [(? :ad/payment-method)
                (? :ad/payment-failed)
                :ad/balance
                (? :ad/amount-pending)
                (? :ad/paused)
                (? :ad/last-clicked)
                ;; ensure these are set / user account hasn't been deleted
                :ad/customer-id
                {:ad/user [:user/email]}]}
  {:ad/chargeable
   (boolean
    (and payment-method
         (not payment-failed)
         (not amount-pending)
         last-clicked
         (< (tick/between last-clicked now :days) 60)
         (<= (if paused 50 500) balance)))})

(defpipe get-stripe-status
  :start
  (fn [{:keys [biff/secret biff.pipe.pathom/output]}]
    (let [{:ad.credit/keys [ad created-at]} output
          {:ad/keys [customer-id]} ad]
      {:biff.pipe/next [(lib.pipe/http :get
                                       "https://api.stripe.com/v1/payment_intents"
                                       {:basic-auth [(secret :stripe/api-key) ""]
                                        :flatten-nested-form-params true
                                        :as :json
                                        :query-params {:limit 100
                                                       :customer customer-id
                                                       :created {:gt (-> created-at
                                                                         tick/instant
                                                                         inst-ms
                                                                         (quot 1000)
                                                                         str)}}})
                        :end]}))

  :end
  (fn [{credit :biff.pipe.pathom/output
        http-output :biff.pipe.http/output}]
    (when-some [status (some (fn [{:keys [metadata status]}]
                               (when (= (str (:xt/id credit))
                                        (:charge_id metadata))
                                 status))
                             (get-in http-output [:body :data]))]
      {:ad.credit/stripe-status status})))

(defresolver stripe-status [ctx {:ad.credit/keys [charge-status] :as credit}]
  {::pco/input [:xt/id
                {:ad.credit/ad [:ad/customer-id]}
                :ad.credit/created-at
                :ad.credit/charge-status]
   ::pco/output [:ad.credit/stripe-status]}
  (when (= charge-status :pending)
    (get-stripe-status (assoc ctx :biff.pipe.pathom/output credit))))

(defresolver pending-charge [{:keys [biff/conn]} {:keys [xt/id]}]
  {::pco/output [{:ad/pending-charge [:xt/id]}]}
  (when-some [credit (first (biffx/q conn
                                     {:select :xt/id
                                      :from :ad-credit
                                      :where [:and
                                              [:= :ad.credit/ad id]
                                              [:= :ad.credit/charge-status [:lift :pending]]]}))]
    {:ad/pending-charge credit}))

(defresolver pending-charges [{:keys [biff/conn]} _]
  {::pco/output [{:admin/pending-charges [:xt/id]}]}
  {:admin/pending-charges
   (biffx/q conn
            {:select :xt/id
             :from :ad-credit
             :where [:= :ad.credit/charge-status [:lift :pending]]})})

(def module {:resolvers [ad-id
                         xt-id
                         effective-bid
                         user-ad
                         url-with-protocol
                         recording-url
                         state
                         n-clicks
                         host
                         amount-pending
                         chargeable
                         stripe-status
                         pending-charge
                         pending-charges
                         last-clicked]})
