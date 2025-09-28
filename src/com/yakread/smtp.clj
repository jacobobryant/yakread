(ns com.yakread.smtp
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.smtp :as lib.smtp]
   [xtdb.api :as xt]) 
  (:import
   [org.jsoup Jsoup]))

(defn accept? [_] true)

(defn infer-post-url [headers html]
  (let [jsoup-parsed (Jsoup/parse html)
        url (some-> (or (some-> (get-in headers ["list-post" 0])
                                (str/replace #"(^<|>$)" ""))
                        (some-> (.select jsoup-parsed "a.post-title-link")
                                first
                                (.attr "abs:href"))
                        (some->> (.select jsoup-parsed "a")
                                 (filter #(re-find #"(?i)read online" (.text %)))
                                 first
                                 (#(.attr % "abs:href"))))
                    (str/replace #"\?.*" ""))]
    (when-not (some-> url (str/includes? "link.mail.beehiiv.com"))
      url)))

(def deliver*
  (lib.pipe/make
   :start (fn [{:keys [biff/db yakread/domain biff.smtp/message]}]
            (let [result (and (or (not domain) (= domain (:domain message)))
                              (some? (biff/lookup-id db :user/email-username (str/lower-case (:username message)))))
                  html (when result
                         (lib.smtp/extract-html message))]
              (log/info "receiving email for"
                        (str (str/lower-case (:username message)) "@" (:domain message)))
              (if-not result
                (log/warn "Rejected incoming email for"
                          (str (str/lower-case (:username message)) "@" (:domain message)))
                {:biff.pipe/next [:yakread.pipe/js :end]
                 :biff.pipe/catch :yakread.pipe/js
                 ::url (infer-post-url (:headers message) html)
                 :yakread.pipe.js/fn-name "juice"
                 :yakread.pipe.js/input {:html html}})))
   :end (fn [{:keys [biff.smtp/message biff/db biff.pipe/now ::url]
              {:keys [html]} :yakread.pipe.js/output}]
          (if-not html
            (do
              (log/warn "juice failed to parse message for" (:username message))
              {:biff.pipe/next [(lib.pipe/spit (str "storage/juice-failed/" (.toEpochMilli now) ".edn")
                                               (pr-str message))]})
            (let [html (-> html
                           lib.content/normalize
                           (str/replace #"#transparent" "transparent"))
                  raw-content-key (gen/uuid)
                  parsed-content-key (gen/uuid)
                  from (some (fn [k]
                               (->> (concat (:from message) (:reply-to message) [(:sender message)])
                                    (some k)))
                             [:personal :address])
                  text (lib.content/html->text html)
                  user-id (biff/lookup-id db :user/email-username (str/lower-case (:username message)))
                  sub (biff/lookup db :sub/user user-id :sub.email/from from)
                  sub-id (or (:xt/id sub) :db.id/new-sub)
                  first-header (fn [header-name]
                                 (some lib.smtp/decode-header (get-in message [:headers header-name])))]
              {:biff.pipe/next [(lib.pipe/s3 'yakread.s3.emails raw-content-key (:raw message) "text/plain")
                                (lib.pipe/s3 'yakread.s3.content parsed-content-key html "text/html")
                                :biff.pipe/tx]
               :biff.pipe.tx/input (concat
                                    [(lib.core/some-vals
                                      {:db/doc-type :item/email
                                       :item/ingested-at :db/now
                                       :item/title (:subject message)
                                       :item/url url
                                       :item/content-key parsed-content-key
                                       :item/published-at :db/now
                                       :item/excerpt (lib.content/excerpt text)
                                       :item/author-name from
                                       :item/lang (lib.content/lang html)
                                       :item/length (count text)
                                       :item.email/sub sub-id
                                       :item.email/raw-content-key raw-content-key
                                       :item.email/list-unsubscribe (first-header "list-unsubscribe")
                                       :item.email/list-unsubscribe-post (first-header "list-unsubscribe-post")
                                       :item.email/reply-to (some :address (:reply-to message))
                                       :item.email/maybe-confirmation (or (nil? sub) nil)})]
                                    (when-not sub
                                      [{:db/doc-type :sub/email
                                        :xt/id sub-id
                                        :sub/user user-id
                                        :sub.email/from from
                                        :sub/created-at :db/now}
                                       [::xt/fn :biff/ensure-unique {:sub/user user-id
                                                                     :sub.email/from from}]]))})))))
