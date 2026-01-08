(ns com.yakread.work.account
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [com.biffweb.experimental :as biffx]
   [com.yakread.util.biff-staging :as biffs]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [tick.core :as tick]
   [xtdb.api :as-alias xt]))

(fx/defmachine export-user-data
  :start
  (fn [{:keys [biff/job]}]
    (log/info ::start)
    (let [{:keys [user/id]} job]
      {:biff.fx/pathom {:entity {:user/id id}
                        :query [:user/id
                                :user/email
                                :user/timezone
                                :user.export/feed-subs
                                :user.export/bookmarks
                                :user.export/favorites]}
       :biff.fx/temp-dir {}
       :biff.fx/next :upload}))

  :upload
  (fn [{:keys [biff/now biff.fx/pathom biff.fx/temp-dir]}]
    (log/info ::upload)
    (let [{:user/keys [id email timezone]
           :user.export/keys [feed-subs bookmarks favorites]} pathom
          dir (io/file temp-dir (str "yakread-export-"
                                     (tick/format "yyyy-MM-dd" (tick/in now timezone))
                                     "-" (inst-ms (tick/instant now)) "-" id))
          zipfile (str dir ".zip")
          s3-key (.getName (io/file zipfile))]
      [{:biff.fx/write (for [[basename content] [["feed-subscriptions.opml" feed-subs]
                                                 ["bookmarks.csv" bookmarks]
                                                 ["favorites.csv" favorites]]]
                         {:file (io/file dir basename) :content content})}
       {:biff.fx/shell ["zip" "-r" s3-key (.getName dir) :dir temp-dir]}
       {:biff.fx/delete-files dir}
       {:biff.fx/s3 {:config-ns 'yakread.s3.export
                     :key       s3-key
                     :method    "PUT"
                     :body      (io/file zipfile)
                     :headers   {"x-amz-acl"    "private"
                                 "content-type" "application/zip"}}}
       {:biff.fx/delete-files zipfile}
       {:biff.fx/s3-presigned-url {:config-ns 'yakread.s3.export
                                   :key s3-key
                                   :method "GET"
                                   :expires-at (tick/>> (tick/instant now) (tick/of-days 7))}}
       {:biff.fx/next :send
        ::email email}]))

  :send
  (fn [{:keys [biff.fx/s3-presigned-url ::email]}]
    (log/info ::upload)
    {:biff.fx/email {:template :export
                     :to email
                     :download-url s3-presigned-url}}))

(fx/defmachine delete-account
  :start
  (fn [{:keys [biff/job]}]
    (log/info "deleting account" job)
    (let [{:keys [user/id]} job]
      {:biff.fx/pathom {:entity {:user/id id}
                        :query [:user/id
                                (? :user/email-username)
                                {(? :user/subscriptions) [:xt/id
                                                          (? :sub.feed/feed)]}
                                {(? :user/ad) [:xt/id
                                               (? :ad/customer-id)]}
                                (? :user/customer-id)]}
       :biff.fx/next :delete}))

  :delete
  (fn [{:keys [biff.fx/pathom biff/secret]}]
    (let [{:user/keys [id subscriptions ad customer-id email-username]} pathom]
      [{:biff.fx/http (for [customer-id [customer-id (:ad/customer-id ad)]
                            :when customer-id]
                        {:method :delete
                         :url (str "https://api.stripe.com/v1/customers/" customer-id)
                         :basic-auth [(secret :stripe/api-key)]
                         :socket-timeout 10000
                         :connection-timeout 10000})}
       {:biff.fx/tx (concat
                     [[:erase-docs :user id]]
                     (when ad
                       [[:erase-docs :ad (:xt/id ad)]])
                     (for [{:keys [xt/id sub.feed/feed]} subscriptions
                           :when feed]
                       [:delete-docs :sub id])
                     (when email-username
                       [[:put-docs :deleted-user
                         {:xt/id (biffs/gen-uuid)
                          :deleted-user/email-username-hash (lib.core/sha256 email-username)}]]))
        :biff.fx/next :delete-email-batch}]))

  :delete-email-batch
  (fn [{:keys [biff/conn biff/job session ::email-ids]}]
    (let [{:keys [user/id]} job
          email-ids (or email-ids
                        (->> {:select :item._id
                              :from :sub
                              :where [:and
                                      [:= :sub/user (:uid session)]
                                      [:is-not :sub.email/from nil]]
                              :join [:item [:= :item.email/sub :sub._id]]}
                             (biffx/q conn)
                             (mapv :xt/id)))
          batch (when (not-empty email-ids)
                  (biffx/q conn
                           {:select [:xt/id :item/content-key :item.email/raw-content-key]
                            :from :item
                            :where [:in :xt/id (take 500 email-ids)]}))
          remaining (drop 500 email-ids)]
      (when (not-empty batch)
        [{:biff.fx/s3 (for [email batch
                            [k config-ns] [[:item/content-key 'yakread.s3.content]
                                           [:item.email/raw-content-key 'yakread.s3.emails]]
                            :when (get email k)]
                        {:key (str (get email k))
                         :config-ns config-ns
                         :method "DELETE"})}
         {:biff.fx/tx [(into [:erase-docs :item] (map :xt/id) batch)]}
         {:biff.fx/next :delete-email-batch
          ::email-ids remaining}]))))

(def module
  {:queues [{:id :work.account/export-user-data
             :consumer #'export-user-data
             :n-threads 1}
            {:id :work.account/delete-account
             :consumer #'delete-account
             :n-threads 1}]})
