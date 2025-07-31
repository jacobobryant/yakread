(ns com.yakread.lib.middleware
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff :refer [q]]
   [com.wsscode.pathom3.error :as p.error]
   [com.yakread.lib.datastar :as lib.d*]
   [com.yakread.lib.form :as lib.form]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.routes :as routes]
   [com.yakread.settings :as settings]
   [com.yakread.util :as util]
   [ring.util.request :as ring-req]
   [rum.core :as rum]
   [taoensso.tufte :as tufte]
   [xtdb.api :as xt]) 
  (:import
   [com.stripe.net Webhook]))

(defn wrap-signed-in [handler]
  (fn [{:keys [session uri biff/href-safe] :as ctx}]
    (if (:uid session)
      (handler ctx)
      {:status 303
       :headers {"location" (str (href-safe routes/signin-page {:redirect uri})
                                 "&error=not-signed-in")}})))

(defn wrap-edn-json-params [handler]
  (fn [{:keys [params] :as ctx}]
    (handler
     (cond-> ctx
       (:edn params) (update :params merge (edn/read-string (:edn params)))))))

(defn wrap-pathom-error [handler]
  (fn [ctx]
    (try
      (handler ctx)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (and (= (::p.error/cause data) ::p.error/attribute-missing)
                 (some #(str/starts-with? (namespace %) "params") (keys (:missing data))))
            {:status 400 :body "invalid input" :headers {"Content-type" "text/plain"}}

            (and (= (::p.error/cause data) ::p.error/attribute-missing)
                 (some #(= "session" (namespace %)) (keys (:missing data))))
            {:status 401}

            :else
            (throw e)))))))

(defn- render [body]
  (str "<!DOCTYPE html>\n" (rum/render-static-markup body)))

(defn wrap-render-rum [handler]
  (fn [ctx]
    (let [response (handler ctx)]
      (cond
        (vector? response)
        {:status 200
         :headers {"content-type" "text/html"}
         :body (render response)}

        (vector? (:body response))
        (update response :body render)

        :else
        response))))

;; TODO rewrite this
#_(defn wrap-auth-aliases [handler]
  (fn [{:keys [biff/db params uri] :as req}]
    (let [email (biff/normalize-email (:email params))
          [username domain] (some-> email (str/split #"@"))
          alias-for (and (str/starts-with? uri "/auth/")
                         (= domain "yakread.com")
                         (-> (biff/lookup db '[{:conn/user [:user/email]}] :conn.email/username username)
                             :conn/user
                             :user/email))
          req (if alias-for
                (assoc-in req [:params :email] alias-for)
                req)
          resp (handler req)
          resp (if (and alias-for (get-in resp [:headers "location"]))
                 (update-in resp [:headers "location"] str/replace alias-for (:email params))
                 resp)]
      resp)))

(defn wrap-profiled [handler]
  (fn [ctx]
    (let [[result pstats] (tufte/profiled {} (handler ctx))]
      (println (tufte/format-pstats @pstats))
      result)))

(defn wrap-stripe-event [handler]
  (fn [{:keys [biff/secret headers] :as req}]
    (if (and (= (href routes/stripe-webhook) (:uri req))
             (secret :stripe/webhook-secret))
      (try
       (let [body-str (ring-req/body-string req)]
         (Webhook/constructEvent body-str
                                 (headers "stripe-signature")
                                 (secret :stripe/webhook-secret))
         (handler (assoc req
                         :body (-> body-str
                                   (.getBytes "UTF-8")
                                   (java.io.ByteArrayInputStream.)))))
       (catch Exception e
         (log/error e "Error while handling stripe webhook event")
         {:status 400 :body ""}))
      (handler req))))

(def default-site-middleware
  [biff/wrap-site-defaults
   wrap-render-rum
   #_wrap-auth-aliases
   wrap-edn-json-params
   wrap-pathom-error
   lib.d*/wrap-sse-response
   lib.form/wrap-parse-form])
