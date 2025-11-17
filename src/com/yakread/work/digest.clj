(ns com.yakread.work.digest
  (:require
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pipeline :as lib.pipe :refer [defpipe]]))

(defn in-send-time-window? [{:keys [biff/now]}
                            {:user/keys [digest-days send-digest-at timezone]
                             ;; TODO rely on pathom for defaults
                             :or {digest-days #{:sunday :monday :tuesday :wednesday :thursday :friday :saturday}
                                  send-digest-at (java.time.LocalTime/of 8 0)
                                  timezone (java.time.ZoneId/of "US/Pacific")}}]
  (let [now-date (.. now
                     (atZone (java.time.ZoneId/of "US/Pacific"))
                     (toLocalDate))
        send-at-begin (java.time.ZonedDateTime/of
                       now-date
                       send-digest-at
                       timezone)
        send-at-begin (cond-> send-at-begin
                        (lib.core/increasing? now (.toInstant send-at-begin))
                        (.minusDays 1))
        send-at-end (.plusHours send-at-begin 2)
        now-day (.. now (atZone timezone) (getDayOfWeek))]
    (and (lib.core/increasing? (.toInstant send-at-begin)
                               now
                               (.toInstant send-at-end))
         (boolean
          (some (fn [day]
                  (= (java.time.DayOfWeek/valueOf (str/upper-case (name day)))
                     now-day))
                digest-days)))))

(defn send-digest? [{:keys [biff/now]} user]
  (and (lib.core/increasing? (:user/digest-last-sent user lib.core/epoch)
                             (.minusSeconds now (* 60 60 18)))
       (in-send-time-window? {:biff/now now} user)
       (not (:user/suppressed-at user))))

(defpipe queue-prepare-digest
  :start
  (fn [{:keys [biff/db biff/queues yakread.work.digest/enabled] :as ctx}]
    ;; There is a small race condition where the queue could be empty even though the
    ;; :work.digest/prepare-digest consumer(s) are still processing jobs, in which case the
    ;; corresponding users could receive two digests. To deal with that, we could
    ;; have the :work.digest/send-digest queue consumer check for the most recently sent digest for
    ;; each user and make sure it isn't within the past e.g. 6 hours. Probably doesn't matter
    ;; though.
    ;; TODO
    #_(when (and enabled (= 0 (.size (:work.digest/prepare-digest queues))))
      (let [users (->> (q db '{:find (pull user [*])
                               :where [[user :user/email]]})
                       (filterv #(send-digest? ctx %))
                       (sort-by :user/email))]
        (when (not-empty users)
          (log/info "Sending digest to" (count users) "users"))
        (if (= enabled :dry-run)
          (run! #(log/info (:user/email %)) users)
          {:biff.pipe/next (for [user users]
                             (lib.pipe/queue :work.digest/prepare-digest user))})))))

(defpipe prepare-digest
  :start
  (fn [{user :biff/job}]
    {:biff.pipe/next [(lib.pipe/pathom {:user/id (:xt/id user)}
                                       [(? :digest/payload)
                                        {(? :digest/subject-item)       [:item/id
                                                                         :item/title]}
                                        {(? :user/ad-rec)               [:ad/id]}
                                        {(? :user/icymi-recs)           [:item/id]}
                                        {(? :user/digest-discover-recs) [:item/id]}])
                      :end]
     ;; hack until model code is refactored to not use session.
     :session {:uid (:xt/id user)}})

  :end
  (fn [{:keys [biff.pipe.pathom/output] user :biff/job}]
    (when (:digest/payload output)
      (let [digest-id (gen/uuid)
            tx [(into {:db/doc-type    :digest
                       :xt/id          digest-id
                       :digest/user    (:xt/id user)
                       :digest/sent-at :db/now}
                      (filter (comp lib.core/something? val))
                      {:digest/subject  (get-in output [:digest/subject-item :item/id])
                       :digest/ad       (get-in output [:user/ad-rec :ad/id])
                       :digest/icymi    (mapv :item/id (:user/icymi-recs output))
                       :digest/discover (mapv :item/id (:user/digest-discover-recs output))})
                {:db/doc-type :user
                 :xt/id (:xt/id user)
                 :db/op :update
                 :user/digest-last-sent :db/now}]]
        {:biff.pipe/next [(lib.pipe/tx tx)
                          (lib.pipe/queue :work.digest/send-digest
                                          {:user/email (:user/email user)
                                           :digest/id digest-id
                                           :digest/payload (:digest/payload output)})]}))))

(def default-payload-size-limit (* 50 1000 1000))
(def default-n-emails-limit 50)

(defpipe send-digest
  :start
  (fn [{:keys [biff/queues ::n-emails-limit]
        :or {n-emails-limit default-n-emails-limit}}]
    (cond
      (= 0 (.size (:work.digest/prepare-digest queues)))
      ;; Wait in case the last jobs are still being processed.
      {:biff.pipe/next [(lib.pipe/sleep 100000) :biff.pipe/drain-queue :start*]}

      (<= n-emails-limit (.size (:work.digest/send-digest queues)))
      {:biff.pipe/next [:biff.pipe/drain-queue :start*]}

      :else
      {:biff.pipe/next [(lib.pipe/sleep 5000) :start]}))

  :start*
  (fn [{:biff/keys [jobs secret]
        ::keys [payload-size-limit n-emails-limit]
        :or {payload-size-limit default-payload-size-limit
             n-emails-limit default-n-emails-limit}}]
    (let [jobs* (map #(assoc % :digest/payload-str (cheshire/generate-string (:digest/payload %)))
                     jobs)
          last-job (->> jobs*
                        (map-indexed vector)
                        (reductions (fn [{:keys [size]} [i job]]
                                      (assoc job
                                             :size (+ size (count (:digest/payload-str job)))
                                             :index i))
                                    {:size 0})
                        rest
                        ;; Mailersend limits bulk requests to 50 MB / 500 email objects.
                        ;; https://developers.mailersend.com/api/v1/email.html#send-bulk-emails
                        (take-while #(and (< (:size %) payload-size-limit)
                                          (< (:index %) n-emails-limit)))
                        last)
          n-jobs       (inc (get last-job :index -1))
          requeue-jobs (drop n-jobs jobs)
          jobs         (take n-jobs jobs*)
          body         (str "[" (str/join "," (mapv :digest/payload-str jobs)) "]")]
      (log/info "Bulk sending to" (count jobs) "users")
      (doseq [{:keys [user/email]} jobs]
        (log/info "Sending to" email))
      {:biff.pipe/next (concat
                        (for [job requeue-jobs]
                          (lib.pipe/queue :work.digest/send-digest job))
                        [(lib.pipe/http :post
                                        "https://api.mailersend.com/v1/bulk-email"
                                        {:oauth-token (secret :mailersend/api-key)
                                         :content-type :json
                                         :as :json
                                         :body body})
                         :record-bulk-send])
       ::digest-ids (mapv :digest/id jobs)}))

  :record-bulk-send
  (fn [{:biff.pipe.http/keys [input output]
        :keys [::digest-ids]}]
    {:biff.pipe/next [(lib.pipe/tx
                       [{:db/doc-type :bulk-send
                         :bulk-send/sent-at :db/now
                         :bulk-send/payload-size (count (:body input))
                         :bulk-send/mailersend-id (get-in output [:body :bulk_email_id])
                         :bulk-send/digests digest-ids}])
                      ;; Mailersend limits bulk request to 15 / minute.
                      ;; https://developers.mailersend.com/api/v1/email.html#send-bulk-emails
                      (lib.pipe/sleep (+ (/ 60000 9) 1000))]}))

(def module
  {:tasks [{:task #'queue-prepare-digest
            :schedule (lib.core/every-n-minutes 30)}]
   :queues [{:id :work.digest/prepare-digest
             :consumer #'prepare-digest
             :n-threads 4}
            {:id :work.digest/send-digest
             :consumer #'send-digest
             :n-threads 1}]})

(comment
  ;; integration test
  (repl/with-context
    (fn [{:keys [biff/db] :as ctx}]
      (doseq [user (q db
                      '{:find (pull user [*])
                        :in [[email ...]]
                        :where [[user :user/email email]]}
                      ;; insert emails here
                      [])]
        (biff/submit-job ctx :work.digest/prepare-digest user))))
  )
