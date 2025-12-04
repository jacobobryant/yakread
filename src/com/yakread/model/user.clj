(ns com.yakread.model.user
  (:require
   [clojure.string :as str]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.user :as lib.user]
   [tick.core :as tick]))

(defresolver session-user [{:keys [session]} _]
  #::pco{:output [{:session/user [:xt/id]}]}
  (when (:uid session)
    {:session/user {:xt/id (:uid session)}}))

(defresolver session-anon [{:keys [session]} _]
  #::pco{:output [{:session/anon []}]}
  (when-not (:uid session)
    {:session/anon {}}))

(defresolver signed-in [{:keys [session]} _]
  #::pco{:output [:session/signed-in]}
  {:session/signed-in (some? (:uid session))})

;; TODO switch everything to :session/user
(defresolver current-user [{:keys [session]} _]
  #::pco{:output [{:user/current [:xt/id]}]}
  (when (:uid session)
    {:user/current {:xt/id (:uid session)}}))

(defresolver suggested-email-username [{:keys [biff/conn]} {:user/keys [email email-username]}]
  #::pco{:input [:user/email (? :user/email-username)]
         :output [:user/suggested-email-username]}
  (when-not email-username
    (let [suggested (some-> email
                            (str/split #"@")
                            first
                            str/lower-case
                            (str/replace #"(\+.*|yakread)" "")
                            lib.user/normalize-email-username)]
      (when-not (some->> suggested (lib.user/email-username-taken? conn))
        {:user/suggested-email-username suggested}))))

(defresolver user-id [{:keys [xt/id user/email]}]
  {:user/id id})

(defresolver xt-id [{:keys [user/id]}]
  {:xt/id id})

(defresolver default-digest-days [_]
  {::pco/input [:user/email]}
  {:user/digest-days #{:sunday :monday :tuesday :wednesday :thursday :friday :saturday}})

(defresolver default-send-digest-at [_]
  {::pco/input [:user/email]}
  {:user/send-digest-at (tick/time "08:00")})

(defresolver default-timezone [{:user/keys [timezone]}]
  {::pco/input [:user/email
                (? :user/timezone)]
   ::pco/output [:user/timezone
                 :user/timezone*]}
  {:user/timezone* timezone
   :user/timezone (or timezone (tick/zone "US/Pacific"))})

(defresolver premium [{:keys [biff/now]} {:user/keys [plan cancel-at]}]
  {::pco/input [(? :user/plan)
                (? :user/cancel-at)]}
  {:user/premium (boolean
                  (and plan
                       (or (not cancel-at)
                           (tick/<= now cancel-at))))})

(defresolver mv [{:keys [biff/conn]} {:user/keys [id]}]
  {::pco/output [{:user/mv [:xt/id]}]}
  (when-some [mv-user
              (first (biffx/q conn
                              {:select :xt/id
                               :from :mv-user
                               :where [:= :mv.user/user id]
                               :limit 1}))]
    {:user/mv mv-user}))

(defresolver account-deletable [{:user/keys [ad plan cancel-at]}]
  {::pco/input [{(? :user/ad) [:ad/balance]}
                (? :user/plan)
                (? :user/cancel-at)]
   ::pco/output [:user/account-deletable
                 :user/account-deletable-message]}
  (let [{:ad/keys [balance]} ad]
    (zipmap [:user/account-deletable :user/account-deletable-message]
            (cond
              (and balance (<= 50 balance))
              [false (str "You have an advertising balance of " (ui/fmt-cents balance) ". Please enable "
                          "the \"pause\" setting on your ad and ensure you have a valid payment "
                          "method set. After your account is charged, you may delete your account.")]

              (and plan (not cancel-at))
              [false (str "You have an active premium subscription. Please cancel your subscription "
                          "before deleting your account.")]

              :else
              [true ::pco/unknown-value]))))

(def module {:resolvers [session-user
                         session-anon
                         signed-in
                         current-user
                         suggested-email-username
                         user-id
                         xt-id
                         default-digest-days
                         default-send-digest-at
                         default-timezone
                         premium
                         mv
                         account-deletable]})
