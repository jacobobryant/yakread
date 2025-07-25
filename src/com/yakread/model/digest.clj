(ns com.yakread.model.digest
  (:require
   [com.biffweb :as biff :refer [q]]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]])
  (:import
   [java.time Period]
   [java.time.format DateTimeFormatter]))

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
                     [(< t0 ingested-at)]]}
           (cond->> (.minus now (Period/ofWeeks 52)) ; TODO
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
     :user/digest-last-sent nil #_digest-last-sent ; TODO
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
                                 {:keys [user/email]
                                  :digest/keys [html
                                                text
                                                subject-item]}]
  {::pco/input [:user/email
                (? :digest/html)
                (? :digest/text)
                {(? :digest/subject-item) [:item/id
                                           :item/title]}]
   ::pco/output [:digest/payload]}
  (when html
    {:digest/payload {:from {:email from :name "Yakread"}
                      :reply_to {:email reply-to :name "Yakread"}
                      :to [{:email email}]
                      :subject (get subject-item :item/title "Your reading digest")
                      :html html
                      :text text}}))

(def module
  {:resolvers [digest-sub-items
               digest-bookmarks
               settings-info
               subject-item
               mailersend-payload]})
