(ns com.yakread.lib.smtp
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.core :as lib.core]
   [rum.core :as rum])
  (:import
   [javax.mail
    Authenticator
    Message$RecipientType
    PasswordAuthentication
    Session
    Transport]
   [javax.mail.internet
    InternetAddress
    MimeBodyPart
    MimeMessage
    MimeMultipart
    MimeUtility]
   [org.subethamail.smtp.helper SimpleMessageListener SimpleMessageListenerAdapter]
   [org.subethamail.smtp.server SMTPServer]))

(defn session [{:keys [host port username password]}]
  (let [props (merge {"mail.smtp.host" host
                      "mail.smtp.port" (str port)}
                     (when password
                       {"mail.smtp.auth" "true"
                        "mail.smtp.starttls.enable" "true"
                        "mail.smtp.ssl.protocols" "TLSv1.2"
                        "mail.smtp.user" username
                        "mail.smtp.password" password}))
        java-props (java.util.Properties.)
        _ (doseq [[k v] props]
            (.put java-props k v))
        auth (when password
               (proxy [Authenticator] []
                 (getPasswordAuthentication []
                   (PasswordAuthentication. username password))))]
    (Session/getInstance java-props auth)))

(defn parse
  ([raw]
   (parse raw (Session/getInstance (java.util.Properties.))))
  ([raw session]
   (MimeMessage. session (io/input-stream (.getBytes raw)))))

(defn send-local! [{:keys [path from to subject rum port host]
                    :or {host "localhost"
                         port 2525}}]
  (let [session* (session {:host host :port (str port)})
        msg (if path
              (parse (slurp path) session*)
              (MimeMessage. session*))]
    (when from
      (.setFrom msg (InternetAddress. from))
      (.setReplyTo msg (into-array [(InternetAddress. from)])))
    (when to
      (.setRecipients msg Message$RecipientType/TO to))
    (when subject
      (.setSubject msg subject))
    (when rum
      (.setContent msg (doto (MimeMultipart.)
                         (.addBodyPart (doto (MimeBodyPart.)
                                         (.setContent (rum/render-static-markup rum)
                                                      "text/html; charset=utf-8"))))))
    (Transport/send msg)))

(defn- datafy-headers [part]
  (lib.core/group-by-to #(str/lower-case (.getName %))
                        #(.getValue %)
                        (enumeration-seq (.getAllHeaders part))))

(defn- datafy-address [address]
  (lib.core/some-vals
   {:address (.getAddress address)
    :personal (.getPersonal address)
    :type (.getType address)}))

(defn- datafy-content [content]
  (cond
    (string? content)
    content

    (instance? MimeMultipart content)
    (lib.core/some-vals
     {:preamble (.getPreamble content)
      :parts (mapv #(datafy-content (.getBodyPart content %))
                   (range (.getCount content)))})

    (instance? MimeBodyPart content)
    (lib.core/some-vals
     {:headers (datafy-headers content)
      :file-name (biff/catchall (.getFileName content))
      :content (datafy-content (or (biff/catchall (.getContent content)) ""))})

    :else
    content))

(defn- to-details [to]
  (let [[username domain] (str/split to #"@")]
    {:to to
     :username username
     :domain domain}))

(defn datafy-message [msg]
  (let [headers (datafy-headers msg)]
    (lib.core/some-vals
     {:headers headers
      :sender (some-> (biff/catchall (.getSender msg)) datafy-address)
      :from (not-empty (mapv datafy-address (biff/catchall (.getFrom msg))))
      :reply-to (not-empty (mapv datafy-address (biff/catchall (.getReplyTo msg))))
      :recipients (not-empty (mapv datafy-address (biff/catchall (.getAllRecipients msg))))
      :subject (.getSubject msg)
      :content (some-> (biff/catchall (.getContent msg)) datafy-content not-empty)})))

(defn deliver-opts [to raw]
  (let [msg (parse raw)
        headers (datafy-headers msg)]
    (lib.core/some-vals
     (merge {:raw raw
             :headers headers
             :sender (some-> (biff/catchall (.getSender msg)) datafy-address)
             :from (not-empty (mapv datafy-address (biff/catchall (.getFrom msg))))
             :reply-to (not-empty (mapv datafy-address (biff/catchall (.getReplyTo msg))))
             :recipients (not-empty (mapv datafy-address (biff/catchall (.getAllRecipients msg))))
             :subject (.getSubject msg)
             :content (some-> (biff/catchall (.getContent msg)) datafy-content not-empty)}
            (to-details to)))))

(defn use-server [{:biff.smtp/keys [port accept?]
                   deliver* :biff.smtp/deliver
                   :or {port 2525}
                   :as ctx}]
  (let [server (SMTPServer.
                (SimpleMessageListenerAdapter.
                 (proxy [SimpleMessageListener] []
                   (accept [from to]
                     (accept? (assoc (biff/merge-context ctx)
                                     :biff.smtp/message
                                     (to-details to))))
                   (deliver [from to data]
                     (deliver* (assoc (biff/merge-context ctx)
                                      :biff.smtp/message
                                      (deliver-opts to (slurp data))))))))]
    (.setPort server port)
    (.start server)
    (-> ctx
        (assoc :biff.smtp/server server)
        (update :biff/stop conj #(.stop server)))))

(defn parts-seq [message]
  (->> (tree-seq map? #(get-in % [:content :parts]) message)
       (map #(assoc % :content-type (get-in % [:headers "content-type" 0] "")))))

(defn decode-header [s]
  (try
    (MimeUtility/decodeText s)
    (catch Exception e
      nil)))

(defn extract-html [message]
  (let [{:keys [content content-type]}
        (->> (parts-seq message)
             (filterv (comp string? :content))
             (sort-by (fn [{:keys [content content-type]}]
                        [(str/includes? content-type "html")
                         (str/includes? content "</div>")
                         (str/includes? content "<html")
                         (str/includes? content "<p>")])
                      #(compare %2 %1))
             first)]
    (if-not (str/includes? content-type "text/plain")
      content
      (rum/render-static-markup
       [:html
        [:body
         [:div {:style {:padding "1rem"}}
          (->> (str/split-lines content)
               (biff/join [:br]))]]]))))

