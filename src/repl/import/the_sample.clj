(ns repl.import.the-sample
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff :refer [q]]
   [com.yakread.lib.item :as lib.item]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.smtp :as lib.smtp])
  (:import
   [org.jsoup Jsoup]
   [java.net URLDecoder]))

;; (defn uuid-from [x]
;;   (binding [gen/*rnd* (java.util.Random. (hash x))]
;;     (gen/uuid)))
;; 
;; 
;; 
;; (defn do-it [{:keys [biff.xtdb/node] :as ctx}]
;;   (with-open [from-node (open-from-node)]
;;     (import-all-users! from-node node))
;;   ctx)
;; 


(defn clean-url [url]
  (str/replace url #"\?.*" ""))

(defn strip-brackets [s]
  (str/replace s #"(^<|>$)" ""))

(defn infer-post-url [headers html]
  (let [jsoup-parsed (Jsoup/parse html)
        url (some-> (or (some-> (get-in headers ["list-post" 0]) strip-brackets)
                        (some-> (.select jsoup-parsed "a.post-title-link")
                                first
                                (.attr "abs:href"))
                        (some->> (.select jsoup-parsed "a")
                                 (filter #(re-find #"(?i)read online" (.text %)))
                                 first
                                 (#(.attr % "abs:href"))))
                    clean-url)]
    (when-not (some-> url (str/includes? "link.mail.beehiiv.com"))
      url)))

(defn fetch-issue [issue]
  (biff/s3-request
   {:biff/secret {:biff.s3/secret-key ""}
    :biff.s3/access-key ""
    :biff.s3/origin ""
    :biff.s3/bucket ""
    :biff.s3/method "GET"
    :biff.s3/key (str issue "-raw")}))

(defn get-url [issue]
  (let [message (some-> (biff/catchall (fetch-issue issue))
                          :body
                          lib.smtp/parse
                          lib.smtp/datafy-message)
        {:keys [headers]} message
        html (some-> message lib.smtp/extract-html)]
    (when html
      (infer-post-url headers html))))

(def add-item-async
  (comp (lib.pipe/make
         (lib.item/add-item-pipeline*
          {:get-url
           (comp :url :params)

           :on-success
           (fn [{:keys [session ::read-at]} {:item/keys [id]}]
             {:biff.pipe/next [:biff.pipe/tx]
              :biff.pipe.tx/input [{:db/doc-type :user-item
                                    :db.op/upsert {:user-item/user (:uid session)
                                                   :user-item/item id}
                                    :user-item/favorited-at read-at
                                    :user-item/bookmarked-at :db/dissoc
                                    :user-item/disliked-at :db/dissoc
                                    :user-item/reported-at :db/dissoc
                                    :user-item/report-reason :db/dissoc}]})

           :on-error
           (fn [_ {:item/keys [url]}]
             (log/warn "add-item-async failed" url)
             (spit "failed-urls.txt" (str url "\n") :append true))}))
        (fn [{{:keys [user/id url]} :biff/job :as ctx}]
          (-> ctx
              (assoc-in [:session :uid] id)
              (assoc-in [:params :url] url)))))

(defn read-users []
  (with-open [r (io/reader "sample-export/users.edn")]
    (into [] (map edn/read-string) (line-seq r))))

(defn read-issue-urls []
  (->> (io/file "sample-export/issue-urls/")
       file-seq
       (filterv #(.isFile %))
       (mapv (comp edn/read-string slurp))
       (apply merge)))

(defn read-likes []
  (let [issue->url (read-issue-urls)]
    (with-open [r (io/reader "sample-export/likes.edn")]
      (into []
            (keep (fn [s]
                    (let [{:keys [event/user
                                  rec/read-at
                                  event/issue
                                  rec/subscribed-at]} (edn/read-string s)
                          url (issue->url issue)]
                      (when url
                        {:url url
                         :read-at (or read-at subscribed-at)
                         :user user}))))
            (line-seq r)))))

(defn add-item! [ctx {:keys [user url read-at]}]
  (let [{:keys [biff/db] :as ctx} (biff/merge-context ctx)
        failed (str/includes? (slurp "failed-urls.txt") url)
        usit (when-not failed
               (first
                (q db
                   '{:find (pull usit [* {:user-item/item [*]}])
                     :in [user url]
                     :where [[usit :user-item/user user]
                             [usit :user-item/item item]
                             (or [item :item/url url]
                                 [item :item/redirect-urls url])]}
                   user
                   url)))]
    (log/info "add-item!" user url failed (some? usit))
    (when-not (or failed usit)
      (Thread/sleep 100)
      (add-item-async (assoc ctx
                             ::read-at (.toInstant (or read-at (java.util.Date.)))
                             :biff/job {:user/id user :url url})))
    usit))


(defn stop-it-now []
  true)

(defn import-likes! [ctx]
  (biff/catchall-verbose
   (let [likes (read-likes)]
     (log/info "importing" (count likes) "likes")
     (doseq [[i batch] (map-indexed vector (partition-all 100 (shuffle likes)))]
       (when-not (stop-it-now)
         (log/info "import-likes!" i)
         (doseq [like batch]
           (when-not (stop-it-now)
             (add-item! ctx like)))))
     ((:biff/send-email ctx)
      ctx
      {:template :alert
       :subject "import likes finished"
       :rum [:div "done"]}))))

(defn do-it [ctx]
  (future
    (biff/catchall-verbose
     (let [issues (with-open [r (io/reader "sample-export/likes.edn")]
                    (into #{}
                          (map (comp :event/issue edn/read-string))
                          (line-seq r)))]
       (doseq [[i issues] (map-indexed vector (partition-all 100 (sort issues)))
               :let [f (io/file "sample-export/issue-urls" (str i))]
               :when (and (not (.exists f)) (not (stop-it-now)))]
         (io/make-parents f)
         (spit f
               (pr-str
                (into {}
                      (keep (fn [issue]
                              (when-some [url (get-url issue)]
                                [issue url])))
                      issues)))
         (log/info "batch" i "done")) 
       (import-likes! ctx))))
  ctx)

#_(defn extract-postmark-url [redirect-url]
    (let [parts (str/split redirect-url #"/")
          ; find the part that looks like a url-encoded domain (contains "%2F")
          maybe-url (first (filter #(str/includes? % "%2F") parts))]
      (when maybe-url
        (str/replace (str "https://" (URLDecoder/decode maybe-url "UTF-8"))
                     #"\?.*"
                     ""))))



(comment

  (require '[com.yakread :as main])

  (defn context [& {:keys [session-email]
                    :or {session-email ""}}]
    (let [{:keys [biff/db] :as ctx} (biff/merge-context @main/system)]
      (merge ctx
             (when session-email
               {:session {:uid (biff/lookup-id db :user/email session-email)}}))))

  (with-open [r (io/reader "sample-export/users.edn")]
    (into #{}
          (map (comp :event/issue edn/read-string))
          (line-seq r)))

  (def users (read-users))
  (count users)

  (def email->existing-user
    (into {}
          (q (:biff/db (context))
             '{:find [email user]
               :in [[email ...]]
               :where [[user :user/email email]]}
             (mapv :user/email users))))
  (count email->existing-user)

  (def new-users
    (->> users
         (remove (comp (set (keys email->existing-user)) :user/email))
         vec))

  (def now (java.time.Instant/now))
  (biff/submit-tx
   (context)
   (for [{:keys [xt/id user/email user/days]} (drop 2 new-users)]
     {:db/doc-type :user
      :xt/id id
      :user/email email
      :user/digest-days days
      :user/from-the-sample true
      :user/joined-at now}))

  (read-likes)

  (def issue->url (read-issue-urls))

  (def first-like (first (read-likes)))

  first-like

  (time (add-item! @main/system first-like))

  (time (add-item! @main/system {:user (random-uuid)
                                 :read-at (java.util.Date.)
                                 :url ""}))
  (println (slurp "failed-urls.txt"))

  (def url->issue (into {} (map (comp vec reverse)) issue->url))
  (take 3 url->issue)
  (spit "failed-urls.txt" "")

  (future (import-likes! @main/system))
  


  )
