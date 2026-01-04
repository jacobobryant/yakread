(ns com.yakread.work.digest
  (:require
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [tick.core :as tick]))

(defn in-send-time-window? [{:keys [biff/now user]}]
  (let [{:user/keys [digest-days send-digest-at timezone]
         ;; TODO rely on pathom for defaults
         :or {digest-days #{:sunday :monday :tuesday :wednesday :thursday :friday :saturday}
              send-digest-at (tick/time "08:00")
              timezone "US/Pacific"}} user

        timezone (java.time.ZoneId/of timezone)
        now-date (tick/date (tick/in now timezone))
        send-at-begin (-> now-date
                          (tick/at send-digest-at)
                          (tick/in timezone))
        send-at-begin (cond-> send-at-begin
                        (tick/<= now send-at-begin)
                        (tick/<< (tick/of-days 1)))
        send-at-end (tick/>> send-at-begin (tick/of-hours 2))
        now-day (tick/day-of-week (tick/in now timezone))]
    (and (tick/<= send-at-begin now send-at-end)
         (contains? digest-days (keyword (str/lower-case now-day))))))

(defn send-digest? [{:keys [biff/now user]}]
  (and (tick/<= (tick/of-hours 18)
                (tick/between (:user/digest-last-sent user lib.core/epoch) now))
       (in-send-time-window? {:biff/now now :user user})
       (not (:user/suppressed-at user))))

(fx/defmachine queue-prepare-digest
  :start
  (fn [{:keys [biff/conn biff/queues yakread.work.digest/enabled biff/now]}]
    ;; There is a small race condition where the queue could be empty even though the
    ;; :work.digest/prepare-digest consumer(s) are still processing jobs, in which case the
    ;; corresponding users could receive two digests. To deal with that, we could
    ;; have the :work.digest/send-digest queue consumer check for the most recently sent digest for
    ;; each user and make sure it isn't within the past e.g. 6 hours. Probably doesn't matter
    ;; though. Queues should probably expose the number of in-progress jobs.
    (when (and enabled (= 0 (.size (:work.digest/prepare-digest queues))))
      (let [users (->> (biffx/q conn
                                {:select [:xt/id
                                          :user/email
                                          :user/digest-last-sent
                                          :user/suppressed-at
                                          :user/digest-days
                                          :user/send-digest-at
                                          :user/timezone]
                                 :from :user})
                       (filterv #(send-digest? {:biff/now now :user %}))
                       (sort-by :user/email))]
        (when (not-empty users)
          (log/info "Sending digest to" (count users) "users"))
        (if (= enabled :dry-run)
          (run! #(log/info (:user/email %)) users)
          {:biff.fx/queue {:jobs (for [user users]
                                   [:work.digest/prepare-digest user])}})))))

(fx/defmachine prepare-digest
  :start
  (fn [{user :biff/job}]
    {:biff.fx/pathom {:entity {:user/id (:xt/id user)}
                      :query  [(? :digest/payload)
                               {(? :digest/subject-item)       [:item/id
                                                                :item/title]}
                               {(? :user/ad-rec)               [:ad/id]}
                               {(? :user/icymi-recs)           [:item/id]}
                               {(? :user/digest-discover-recs) [:item/id]}]}
     :biff.fx/next :end
     ;; hack until model code is refactored to not use session.
     :session {:uid (:xt/id user)}})

  :end
  (fn [{:keys [biff.fx/pathom biff/now] user :biff/job}]
    (when (:digest/payload pathom)
      (let [digest-id (biffx/prefix-uuid (:xt/id user) (gen/uuid))
            digest-items (for [[k kind] [[:user/icymi-recs :icymi]
                                         [:user/digest-discover-recs :discover]]
                               item (get pathom k)]
                           {:xt/id (biffx/prefix-uuid (:item/id item) (gen/uuid))
                            :digest-item/digest digest-id
                            :digest-item/item (:item/id item)
                            :digest-item/kind kind})
            tx (concat
                [[:patch-docs :user
                  {:xt/id (:xt/id user)
                   :user/digest-last-sent now}]
                 [:put-docs :digest
                  (into {:xt/id          digest-id
                         :digest/user    (:xt/id user)
                         :digest/sent-at now}
                        (filter (comp lib.core/something? val))
                        {:digest/subject  (get-in pathom [:digest/subject-item :item/id])
                         :digest/ad       (get-in pathom [:user/ad-rec :ad/id])})]]
                (when (not-empty digest-items)
                  [(into [:put-docs :digest-item] digest-items)]))]
        [{:biff.fx/tx tx}
         {:biff.fx/queue {:id :work.digest/send-digest
                          :job {:user/email (:user/email user)
                                :digest/id digest-id
                                :digest/payload (:digest/payload pathom)}}}]))))

(def default-payload-size-limit (* 50 1000 1000))
(def default-n-emails-limit 50)

(fx/defmachine send-digest
  :start
  (fn [{:keys [biff/queues ::n-emails-limit]
        :or {n-emails-limit default-n-emails-limit}}]
    (cond
      (= 0 (.size (:work.digest/prepare-digest queues)))
      ;; Wait in case the last jobs are still being processed.
      [{:biff.fx/sleep 100000}
       {:biff.fx/drain-queue nil
        :biff.fx/next :start*}]

      (<= n-emails-limit (.size (:work.digest/send-digest queues)))
      {:biff.fx/drain-queue nil
       :biff.pipe/next :start*}

      :else
      {:biff.fx/sleep 5000
       :biff.fx/next :start}))

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
      {:biff.fx/queue {:jobs (for [job requeue-jobs]
                               [:work.digest/send-digest job])}
       :biff.fx/http {:method :post
                      :url "https://api.mailersend.com/v1/bulk-email"
                      :oauth-token (secret :mailersend/api-key)
                      :content-type :json
                      :as :json
                      :body body}
       :biff.fx/next :record-bulk-send
       ::payload-size (count body)
       ::digest-ids (mapv :digest/id jobs)}))

  :record-bulk-send
  (fn [{:keys [biff/now ::digest-ids ::payload-size biff.fx/http]}]
    (let [bulk-send-id (gen/uuid)]
      [{:biff.fx/tx [[:put-docs :bulk-send
                      {:xt/id bulk-send-id
                       :bulk-send/sent-at now
                       :bulk-send/payload-size payload-size
                       :bulk-send/mailersend-id (get-in http [:body :bulk_email_id])}]
                     (into [:patch-docs :digest]
                           (for [id digest-ids]
                             {:xt/id id
                              :digest/bulk-send bulk-send-id}))]}
       ;; Mailersend limits bulk request to 15 / minute.
       ;; https://developers.mailersend.com/api/v1/email.html#send-bulk-emails
       {:biff.fx/sleep (long (+ (/ 60000 9) 1000))}])))

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
