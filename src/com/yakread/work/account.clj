(ns com.yakread.work.account
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pipeline :as pipe :refer [defpipe]]
   [xtdb.api :as-alias xt]))

(defpipe export-user-data
  :start
  (fn [{:keys [biff/job]}]
    (log/info ::start)
    (let [{:keys [user/id]} job]
      {:biff.pipe/next [(pipe/pathom {:user/id id}
                                     [:user/id
                                      :user/email
                                      :user/timezone
                                      :user.export/feed-subs
                                      :user.export/bookmarks
                                      :user.export/favorites])
                        :biff.pipe/temp-dir
                        :upload]}))

  :upload
  (fn [{:keys [biff/now
               biff.pipe.pathom/output]
        tmp-dir :biff.pipe.temp-dir/path}]
    (log/info ::upload)
    (let [{:user/keys [id email timezone]
           :user.export/keys [feed-subs bookmarks favorites]} output
          dir (io/file tmp-dir (str "yakread-export-"
                                    (lib.core/fmt-inst now "yyyy-MM-dd" timezone)
                                    "-" (inst-ms now) "-" id))
          zipfile (str dir ".zip")
          s3-key (.getName (io/file zipfile))]
      {:biff.pipe/next (concat
                        (for [[basename content] [["feed-subscriptions.opml" feed-subs]
                                                  ["bookmarks.csv" bookmarks]
                                                  ["favorites.csv" favorites]]]
                          (pipe/write (io/file dir basename) content))
                        [(pipe/shell "zip" "-r" s3-key (.getName dir) :dir tmp-dir)
                         (pipe/delete-files dir)
                         (pipe/s3 'yakread.s3.export
                                  s3-key
                                  (io/file zipfile)
                                  "application/zip")
                         (pipe/delete-files zipfile)
                         (pipe/s3-presigned-url 'yakread.s3.export
                                                s3-key
                                                (.plusSeconds now (* 60 60 24 7)))
                         :send])
       ::email email}))

  :send
  (fn [{:keys [biff.pipe.s3.presigned-url/output ::email]}]
    (log/info ::upload)
    {:biff.pipe/next [(pipe/email {:template :export
                                   :to email
                                   :download-url output})]}))

(defpipe delete-account
  :start
  (fn [{:keys [biff/job]}]
    (log/info "deleting account" job)
    (let [{:keys [user/id]} job]
      {:biff.pipe/next [(pipe/pathom
                         {:user/id id}
                         [:user/id
                          (? :user/email-username)
                          {(? :user/subscriptions) [:xt/id
                                                    (? :sub.feed/feed)]}
                          {(? :user/ad) [:xt/id
                                         (? :ad/customer-id)]}
                          (? :user/customer-id)])
                        :delete]}))

  :delete
  (fn [{:keys [biff.pipe.pathom/output
               biff/secret]}]
    (let [{:user/keys [id
                       subscriptions
                       ad
                       customer-id]} output]
      {:biff.pipe/next
       (concat
        (for [customer-id [(:user/customer-id output)
                           (get-in output [:user/ad :ad/customer-id])]
              :when customer-id]
          (pipe/http :delete
                     (str "https://api.stripe.com/v1/customers/" customer-id)
                     {:basic-auth [(secret :stripe/api-key)]
                      :socket-timeout 10000
                      :connection-timeout 10000}))
        [(pipe/tx (concat
                   [[::xt/evict id]]
                   (when (:user/ad output)
                     [[::xt/evict (get-in output [:user/ad :xt/id])]])
                   (for [{:keys [xt/id sub.feed/feed]} subscriptions
                         :when feed]
                     [::xt/delete id])
                   (when (:user/email-username output)
                     [{:db/doc-type :deleted-user
                       :deleted-user/email-username-hash (lib.core/sha256
                                                          (:user/email-username output))}])))
         :delete-email-batch])}))

  :delete-email-batch
  (fn [{:keys [biff/db biff/job session ::email-ids]}]
    ;; TODO
    #_(let [{:keys [user/id]} job
          email-ids (or email-ids
                        (q db
                           '{:find item
                             :in [user]
                             :where [[item :item.email/sub sub]
                                     [sub :sub/user user]]}
                           (:uid session)))
          batch (mapv #(xt/entity db %) (take 500 email-ids))
          remaining (drop 500 email-ids)]
      (when (not-empty batch)
        {:biff.pipe/next (concat
                          (for [email batch
                                [k config-ns] [[:item/content-key 'yakread.s3.content]
                                               [:item.email/raw-content-key 'yakread.s3.emails]]
                                :when (get email k)]
                            {:biff.pipe/current :biff.pipe/s3
                             :biff.pipe.s3/input {:key (str (get email k))
                                                  :config-ns config-ns
                                                  :method "DELETE"}})
                          [(pipe/tx
                            (for [{:keys [xt/id]} batch]
                              [::xt/evict id]))
                           :delete-email-batch])
         ::email-ids remaining}))))

(def module
  {:queues [{:id :work.account/export-user-data
             :consumer #'export-user-data
             :n-threads 1}
            {:id :work.account/delete-account
             :consumer #'delete-account
             :n-threads 1}]})
