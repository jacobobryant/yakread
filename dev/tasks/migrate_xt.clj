(ns tasks.migrate-xt
  (:require [com.biffweb.config :as config]
            [xtdb.node :as xtn]
            [com.biffweb.migrate.xtdb2 :as migrate]))

(defn use-xtdb2 [{:keys [biff/secret]
                  :biff.xtdb2.storage/keys [bucket endpoint access-key]}]
  (let [secret-key
        (secret :biff.xtdb2.storage/secret-key)

        node
        (xtn/start-node {:log [:local {:path "storage/xtdb2/log"}]
                         :storage [:remote
                                   {:object-store [:s3
                                                   {:bucket bucket
                                                    :endpoint endpoint
                                                    :credentials {:access-key access-key
                                                                  :secret-key secret-key}}]
                                    :local-disk-cache "storage/xtdb2/storage-cache"}]})]
    {:biff/node node
     :biff/stop (list #(.close node))}))

(def key->table
  {:user/email :users
   :sub/user :subs
   :item/ingested-at :items
   :feed/url :feeds
   :user-item/user :user-items
   :digest/user :digests
   :bulk-send/sent-at :bulk-sends
   :skip/user :skips
   :ad/user :ads
   :ad.click/user :ad-clicks
   :ad.credit/ad :ad-credits
   :mv.sub/sub :mv-subs
   :mv.user/user :mv-users
   :deleted-user/email-username-hash :deleted-users})

(defn doc->table [doc]
  (some key->table (keys doc)))

(defn -main []
  (let [{:keys [biff/node]} (-> {:biff.config/skip-validation true}
                                config/use-aero-config
                                use-xtdb2)]
    (prn node)
    ;; todo translation for many-to-many docs
    #_(migrate/import! node
                       "storage/migrate-export"
                       doc->table)))
