(ns com.yakread.lib.email
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [com.yakread.lib.ui-email :as uie]))

(defn- button [{:keys [href]} label]
  (uie/button
    {:href href
     :label label
     :bg-color "#17b897"
     :text-color "white"}))

(defn- signin-link [{:keys [to url user-exists]}]
  (if user-exists
    {:to to
     :subject "Sign in to Yakread"
     :html-body (uie/html
                 :title "Sign in to Yakread"
                 :hide-unsubscribe true
                 :content
                 [:<>
                  [:div
                   "We received a request to sign in to Yakread using this email address. "
                   "Click the link to sign in:"]
                  (uie/h-space "32px")
                  (button {:href url} "Click here to sign in")
                  (uie/h-space "32px")
                  [:div "If you did not request this link, you can ignore this email."]
                  (uie/h-space "8px")])
     :text-body (str "We received a request to sign in to Yakread using this email address. "
                     "Click here to sign in:\n"
                     "\n"
                     url "\n"
                     "\n"
                     "If you did not request this link, you can ignore this email."
                     "\n"
                     uie/address)
     :stream "outbound"}
    {:to to
     :subject "Sign up for Yakread"
     :html-body (uie/html
                 :title "Sign up for Yakread"
                 :hide-unsubscribe true
                 :content
                 [:<>
                  [:div "Welcome to Yakread! Click the link to create your account:"]
                  (uie/h-space "32px")
                  (button {:href url} "Click here to sign up")
                  (uie/h-space "32px")
                  [:div "If you didn't mean to sign up for Yakread, you can ignore this email."]
                  (uie/h-space "8px")])
     :text-body (str "Welcome to Yakread! Click here to create your account:\n"
                     "\n"
                     url "\n"
                     "\n"
                     "If you didn't mean to sign up for Yakread, you can ignore this email."
                     "\n"
                     uie/address)
     :stream "outbound"}))

(defn- signin-code [{:keys [to code]}]
  {:to to
   :subject "Sign in to Yakread"
   :html-body (uie/html
               :title "Sign in to Yakread"
               :hide-unsubscribe true
               :content
               [:<>
                [:div
                 "We received a request to sign in to Yakread using this email address. "
                 "Enter the following code to sign in:"]
                (uie/h-space "16px")
                [:div {:style {:font-size "2rem"}} code]
                (uie/h-space "16px")
                [:div
                 "This code will expire in three minutes. "
                 "If you did not request this code, you can ignore this email."]
                (uie/h-space "8px")])
   :text-body (str "Welcome to Yakread! "
                   "Enter the following code to sign in:\n"
                   "\n"
                   code "\n"
                   "\n"
                   "This code will expire in three minutes. "
                   "If you did not request this code, you can ignore this email."
                   "\n"
                   uie/address)
   :stream "outbound"})

(defn- template [k opts]
  ((case k
     :signin-link signin-link
     :signin-code signin-code)
   opts))

(defn- send-postmark* [{:keys [biff/secret postmark/from]} form-params]
  (http/post "https://api.postmarkapp.com/email"
             {:headers {"X-Postmark-Server-Token" (secret :postmark/api-key)}
              :as :json
              :content-type :json
              :form-params (merge {:from from} (cske/transform-keys csk/->PascalCase form-params))
              :throw-exceptions false}))

(defn- unsuppress! [{:keys [biff/secret]} email]
  (log/info "inactive, unsuppressing")
  (< (:status (http/post "https://api.postmarkapp.com/message-streams/outbound/suppressions/delete"
                         {:headers {"X-Postmark-Server-Token" (secret :postmark/api-key)}
                          :as :json
                          :content-type :json
                          :form-params {:Suppressions [{:EmailAddress email}]}}))
     400))

(defn- send-postmark [ctx form-params]
  (let [result (send-postmark* ctx form-params)
        success (< (:status result) 400)
        inactive (when-not success
                   (some-> (:body result)
                           (cheshire/parse-string true)
                           :ErrorCode
                           (= 406)))
        result (if (and inactive
                        (= (:stream form-params) "outbound")
                        (unsuppress! ctx (:to form-params)))
                 (do
                   (Thread/sleep 3000)
                   (send-postmark* ctx form-params))
                 result)
        success (< (:status result) 400)]
    (when-not success
      (log/error (:body result)))
    success))

(defn- send-console [ctx form-params]
  (println "TO:" (:to form-params))
  (println "SUBJECT:" (:subject form-params))
  (println)
  (println (:text-body form-params))
  (println)
  (println "To send emails instead of printing them to the console, add your"
           "API keys for Postmark and Recaptcha to config.edn.")
  true)

(defn send-email [{:keys [biff/secret recaptcha/site-key] :as ctx} opts]
  (let [form-params (if-some [template-key (:template opts)]
                      (template template-key opts)
                      opts)]
    (if (every? some? [(secret :postmark/api-key)
                       (secret :recaptcha/secret-key)
                       site-key])
      (send-postmark ctx form-params)
      (send-console ctx form-params))))
