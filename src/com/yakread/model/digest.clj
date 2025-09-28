(ns com.yakread.model.digest
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.routes :as routes])
  (:import
   [java.time Period]
   [java.time.format DateTimeFormatter]))

;; TODO rethink what things should be in here vs model.recommend

(defn recent-items [{:biff/keys [db now]
                     :user/keys [digest-last-sent]
                     :keys [all-item-ids]}]
  (mapv (fn [[id]]
          {:xt/id id})
        (q db
           '{:find [item ingested-at]
             :order-by [[ingested-at :desc]]
             :limit 50
             :in [t0 [item ...]]
             :where [[item :item/ingested-at ingested-at]
                     [(< t0 ingested-at)]]
             :timeout 240000}
           (cond->> (.minus now (Period/ofWeeks 2))
             digest-last-sent (max-key inst-ms digest-last-sent))
           all-item-ids)))

(defresolver digest-sub-items [{:biff/keys [db now]} {:user/keys [digest-last-sent subscriptions]}]
  {::pco/input [(? :user/digest-last-sent)
                {:user/subscriptions [{:sub/items [:xt/id]}]}]
   ::pco/output [{:user/digest-sub-items [:xt/id]}]}
  {:user/digest-sub-items
   (recent-items
    {:biff/db db
     :biff/now now
     :user/digest-last-sent digest-last-sent
     :all-item-ids (mapv :xt/id (mapcat :sub/items subscriptions))})})

(defresolver digest-bookmarks [{:biff/keys [db now]} {:user/keys [digest-last-sent bookmarks]}]
  {::pco/input [:user/id
                (? :user/digest-last-sent)
                {:user/bookmarks [:xt/id]}]
   ::pco/output [{:user/digest-bookmarks [:xt/id]}]}
  {:user/digest-bookmarks
   (recent-items
    {:biff/db db
     :biff/now now
     :user/digest-last-sent digest-last-sent
     :all-item-ids (mapv :xt/id bookmarks)})})

(defresolver settings-info [{:user/keys [digest-days send-digest-at]}]
  {:digest.settings/freq-text (case (count digest-days)
                                7 "daily"
                                1 "weekly"
                                (str (count digest-days) "x/week"))
   :digest.settings/time-text (.format send-digest-at (DateTimeFormatter/ofPattern "h:mm a"))})

(defresolver subject-item [{:keys [user/digest-discover-recs]}]
  {::pco/input [{:user/digest-discover-recs [:item/id
                                             (? :item/title)]}]
   ::pco/output [{:digest/subject-item [:item/id]}]}
  (when-some [item (->> digest-discover-recs
                        (filter :item/title)
                        first)]
    {:digest/subject-item item}))

(defresolver mailersend-payload [{:mailersend/keys [from reply-to]}
                                 {:user/keys [email from-the-sample]
                                  :digest/keys [html
                                                text
                                                subject-item]}]
  {::pco/input [:user/email
                (? :user/from-the-sample)
                (? :digest/html)
                (? :digest/text)
                {(? :digest/subject-item) [:item/id
                                           :item/title]}]
   ::pco/output [:digest/payload]}
  (when html
    {:digest/payload {:from {:email from
                             :name (if from-the-sample
                                     "Yakread (formerly The Sample)"
                                     "Yakread")}
                      :reply_to {:email reply-to :name "Yakread"}
                      :to [{:email email}]
                      :subject (-> subject-item
                                   (get :item/title "Your reading digest")
                                   ;; turns out spam filters don't like this in subject lines
                                   (str/replace #"(?i)dark web" ""))
                      :html html
                      :text text
                      :precedence_bulk true}}))

(defresolver unsubscribe-url [{:biff/keys [base-url href-safe]}
                               {:keys [user/id]}]
  {:digest/unsubscribe-url
   (str base-url (href-safe routes/unsubscribe {:action :action/unsubscribe
                                                :user/id id}))})

(def module
  {:resolvers [digest-sub-items
               digest-bookmarks
               settings-info
               subject-item
               mailersend-payload
               unsubscribe-url]})
