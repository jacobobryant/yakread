(ns com.yakread.lib.auth
  (:require [com.biffweb :as biff]
            [clj-http.client :as http]
            [com.yakread.util :as util]
            [clojure.string :as str]
            [rum.core :as rum]
            [xtdb.api :as xt]))

(defn passed-recaptcha? [{:keys [biff/secret biff.recaptcha/threshold params]
                          :or {threshold 0.5}}]
  (or (nil? (secret :recaptcha/secret-key))
      (let [{:keys [success score]}
            (:body
             (http/post "https://www.google.com/recaptcha/api/siteverify"
                        {:form-params {:secret (secret :recaptcha/secret-key)
                                       :response (:g-recaptcha-response params)}
                         :as :json}))]
        (and success (or (nil? score) (<= threshold score))))))

(defn email-valid? [ctx email]
  (and email
       (re-matches #".+@.+\..+" email)
       (not (re-find #"\s" email))))

(defn new-link [{:keys [biff.auth/check-state
                        biff.auth/link-expire-minutes
                        biff.auth/allowed-redirects
                        biff/base-url
                        biff/secret
                        anti-forgery-token
                        params]}
                email]
  (str base-url "/auth/verify-link/"
       (biff/jwt-encrypt
        (biff/assoc-some
         {:intent "signin"
          :email email
          :exp-in (* 60 link-expire-minutes)}
         :state (when check-state
                  (biff/sha256 anti-forgery-token))
         :redirect (get allowed-redirects (:redirect params)))
        (secret :biff/jwt-secret))))

(defn new-code [length]
  (let [rng (java.security.SecureRandom/getInstanceStrong)]
    (format (str "%0" length "d")
            (.nextInt rng (dec (int (Math/pow 10 length)))))))

(defn send-link! [{:keys [biff.auth/email-validator
                          biff/db
                          biff.auth/get-user-id
                          biff/send-email
                          params]
                   :as ctx}]
  (let [email (biff/normalize-email (:email params))
        url (new-link ctx email)
        user-id (delay (get-user-id db email))]
    (cond
     (not (passed-recaptcha? ctx))
     {:success false :error "recaptcha"}

     (not (email-validator ctx email))
     {:success false :error "invalid-email"}

     (not (send-email ctx
                      {:template :signin-link
                       :to email
                       :url url
                       :user-exists (some? @user-id)}))
     {:success false :error "send-failed"}

     :else
     {:success true :email email :user-id @user-id})))

(defn verify-link [{:keys [biff.auth/check-state
                           biff/secret
                           path-params
                           params
                           anti-forgery-token]}]
  (let [{:keys [intent email state]
         :as jwt} (-> (merge params path-params)
                      :token
                      (biff/jwt-decrypt (secret :biff/jwt-secret)))
        valid-state (= state (biff/sha256 anti-forgery-token))
        valid-email (= email (:email params))]
    (merge jwt
           (cond
            (not= intent "signin")
            {:success false :error "invalid-link"}

            (or (not check-state) valid-state valid-email)
            {:success true}

            (some? (:email params))
            {:success false :error "invalid-email"}

            :else
            {:success false :error "invalid-state"}))))

(defn send-code! [{:keys [biff.auth/email-validator
                          biff/db
                          biff/send-email
                          biff.auth/get-user-id
                          params]
                   :as ctx}]
  (let [email (biff/normalize-email (:email params))
        code (new-code 6)
        user-id (delay (get-user-id db email))]
    (cond
     (not (passed-recaptcha? ctx))
     {:success false :error "recaptcha"}

     (not (email-validator ctx email))
     {:success false :error "invalid-email"}

     (not (send-email ctx
                      {:template :signin-code
                       :to email
                       :code code
                       :user-exists (some? @user-id)}))
     {:success false :error "send-failed"}

     :else
     {:success true :email email :code code :user-id @user-id})))

;;; HANDLERS -------------------------------------------------------------------

(defn send-link-handler [{:keys [biff.auth/single-opt-in
                                 biff.auth/new-user-tx
                                 biff/db
                                 params]
                          :as ctx}]
  (let [{:keys [success error email user-id]} (send-link! ctx)]
    (when (and success single-opt-in (not user-id))
      (biff/submit-tx (assoc ctx :biff.xtdb/retry false) (new-user-tx ctx email)))
    {:status 303
     :headers {"location" (if success
                            (str "/link-sent?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-link-handler [{:keys [biff.auth/app-path
                                   biff.auth/allowed-redirects
                                   biff.auth/invalid-link-path
                                   biff.auth/new-user-tx
                                   biff.auth/get-user-id
                                   biff.xtdb/node
                                   session
                                   params
                                   path-params]
                            :as ctx}]
  (let [{:keys [success error email redirect]} (verify-link ctx)
        existing-user-id (when success (get-user-id (xt/db node) email))
        token (:token (merge params path-params))]
    (when (and success (not existing-user-id))
      (biff/submit-tx ctx (new-user-tx ctx email)))
    {:status 303
     :headers {"location" (cond
                           success
                           (or redirect app-path)

                           (= error "invalid-state")
                           (str "/verify-link?token=" token)

                           (= error "invalid-email")
                           (str "/verify-link?error=incorrect-email&token=" token)

                           :else
                           invalid-link-path)}
     :session (cond-> session
                success (assoc :uid (or existing-user-id
                                        (get-user-id (xt/db node) email))))}))

(defn send-code-handler [{:keys [biff.auth/single-opt-in
                                 biff.auth/allowed-redirects
                                 biff.auth/new-user-tx
                                 biff/db
                                 params]
                          :as ctx}]
  (let [{:keys [success error email code user-id]} (send-code! ctx)]
    (when success
      (biff/submit-tx (assoc ctx :biff.xtdb/retry false)
        (concat [(biff/assoc-some
                  {:db/doc-type :biff.auth/code
                   :db.op/upsert {:biff.auth.code/email email}
                   :biff.auth.code/code code
                   :biff.auth.code/created-at :db/now
                   :biff.auth.code/failed-attempts 0}
                  :biff.auth.code/redirect (get allowed-redirects (:redirect params)))]
                (when (and single-opt-in (not user-id))
                  (new-user-tx ctx email)))))
    {:status 303
     :headers {"location" (if success
                            (str "/verify-code?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-code-handler [{:keys [biff.auth/app-path
                                   biff.auth/new-user-tx
                                   biff.auth/get-user-id
                                   biff.xtdb/node
                                   biff/db
                                   params
                                   session]
                            :as ctx}]
  (let [email (biff/normalize-email (:email params))
        code (biff/lookup db :biff.auth.code/email email)
        success (and (passed-recaptcha? ctx)
                     (some? code)
                     (< (:biff.auth.code/failed-attempts code) 3)
                     (not (biff/elapsed? (:biff.auth.code/created-at code) :now 3 :minutes))
                     (= (:code params) (:biff.auth.code/code code)))
        existing-user-id (when success (get-user-id db email))
        tx (cond
            success
            (concat [[::xt/delete (:xt/id code)]]
                    (when-not existing-user-id
                      (new-user-tx ctx email)))

            (and (not success)
                 (some? code)
                 (< (:biff.auth.code/failed-attempts code) 3))
            [{:db/doc-type :biff.auth/code
              :db/op :update
              :xt/id (:xt/id code)
              :biff.auth.code/failed-attempts [:db/add 1]}])]
    (biff/submit-tx ctx tx)
    (if success
      {:status 303
       :headers {"location" (:biff.auth.code/redirect code app-path)}
       :session (assoc session :uid (or existing-user-id
                                        (get-user-id db email)))}
      {:status 303
       :headers {"location" (str "/verify-code?error=invalid-code&email=" email)}})))

(defn signout [{:keys [session]}]
  {:status 303
   :headers {"location" "/"}
   :session (dissoc session :uid)})

;;; ----------------------------------------------------------------------------

(defn new-user-tx [ctx email]
  [{:db/doc-type    :user
    :db.op/upsert   {:user/email email}
    :user/joined-at (java.time.Instant/now)}])

(defn get-user-id [db email]
  (biff/lookup-id db :user/email email))

(def default-options
  #:biff.auth{:app-path "/app"
              :allowed-redirects #{} ; new
              :link-expire-minutes 60 ; new
              :invalid-link-path "/signin?error=invalid-link"
              :check-state true
              :new-user-tx new-user-tx
              :get-user-id get-user-id
              :single-opt-in false
              :email-validator email-valid?})

(defn wrap-options [handler options]
  (fn [ctx]
    (handler (merge options ctx))))

(defn module [options]
  {:schema {:biff.auth.code/id :uuid
            :biff.auth/code [:map {:closed true}
                             [:xt/id                          :biff.auth.code/id]
                             [:biff.auth.code/email           :string]
                             [:biff.auth.code/code            :string]
                             [:biff.auth.code/created-at      inst?]
                             [:biff.auth.code/failed-attempts integer?]
                             [:biff.auth.code/redirect        {:optional true} :string]]}
   :routes [["/auth" {:middleware [[wrap-options (merge default-options options)]]}
             ["/send-link"          {:post send-link-handler}]
             ["/verify-link/:token" {:get verify-link-handler}]
             ["/verify-link"        {:post verify-link-handler}]
             ["/send-code"          {:post send-code-handler}]
             ["/verify-code"        {:post verify-code-handler}]
             ["/signout"            {:post signout}]]]})

;;; FRONTEND HELPERS -----------------------------------------------------------

(def recaptcha-disclosure
  [:div {:style {:font-size "0.75rem"
                 :line-height "1rem"
                 :color "#4b5563"}}
   "This site is protected by reCAPTCHA and the Google "
   [:a {:href "https://policies.google.com/privacy"
        :target "_blank"
        :style {:text-decoration "underline"}}
    "Privacy Policy"] " and "
   [:a {:href "https://policies.google.com/terms"
        :target "_blank"
        :style {:text-decoration "underline"}}
    "Terms of Service"] " apply."])

(defn recaptcha-callback [fn-name form-id]
  [:script
   (biff/unsafe
    (str "function " fn-name "(token) { "
         "document.getElementById('" form-id "').submit();"
         "}"))])
