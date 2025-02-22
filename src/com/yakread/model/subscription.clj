(ns com.yakread.model.subscription
  (:require [com.biffweb :as biff :refer [q <<-]]
            [com.yakread.util.biff-staging :as biffs]
            [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver ?]]
            [com.yakread.lib.error :as lib.error]
            [com.yakread.lib.item :as lib.item]
            [com.yakread.lib.route :as lib.route]
            [com.yakread.lib.serialize :as lib.serialize]
            [com.yakread.lib.user-item :as lib.user-item]
            [clojure.string :as str]
            [clojure.set :as set]
            [xtdb.api :as xt])
  (:import [java.time Instant]))

(defresolver email-title [{:keys [sub.email/from]}]
  {:sub/title (str/replace from #"\s<.*>" "")})

(defresolver feed-title [{:keys [sub.feed/feed]}]
  #::pco{:input [{:sub.feed/feed [:feed/url
                                  (? :feed/title)]}]}
  {:sub/title (or (:feed/title feed)
                  (:feed/url feed))})

(defresolver sub-id->xt-id [{:keys [sub/id]}]
  {:xt/id id})

(defresolver sub-info [{:keys [xt/id sub/user sub.feed/feed sub.email/from]}]
  #::pco{:input [:xt/id
                 :sub/user
                 (? :sub.feed/feed)
                 (? :sub.email/from)]
         :output [:sub/id
                  :sub/source-id
                  :sub/doc-type]}
  (if from
    {:sub/id id
     :sub/source-id id
     :sub/doc-type :sub/email}
    {:sub/id id
     :sub/source-id (:xt/id feed)
     :sub/doc-type :sub/feed}))

(defresolver unread [{:keys [biff/db]} {:sub/keys [user source-id]}]
  {:sub/unread
   (let [total (or (biff/index-get db :unread source-id) 0)
         _read (or (biff/index-get db :unread [(:xt/id user) source-id]) 0)
         unread (- total _read)]
     unread)})

(defresolver published-at [{:keys [biff/db]} {:keys [sub/source-id]}]
  {::pco/output [:sub/published-at]}
  (when-some [published-at (biff/index-get db :last-published source-id)]
    {:sub/published-at published-at}))

(defresolver items [{:keys [biff/db]} {:sub/keys [source-id doc-type]}]
  {::pco/output [{:sub/items [:xt/id]}]}
  {:sub/items
   (mapv #(hash-map :xt/id %)
         (q db
            {:find 'item
             :in '[source]
             :where [['item
                      (case doc-type
                        :sub/feed :item.feed/feed
                        :sub/email :item.email/sub)
                      'source]]}
            source-id))})

(defresolver from-params [{:keys [biff/db biff/malli-opts session path-params params]} _]
  #::pco{:output [{:params/sub [:xt/id]}]}
  (let [sub-id (or (:sub/id params)
                   (lib.serialize/url->uuid (:sub-id path-params)))
        sub (when (some? sub-id)
              (xt/entity db sub-id))]
    (when (and sub (= (:uid session) (:sub/user sub)))
      {:params/sub (biffs/joinify @malli-opts sub)})))

(defn- index-update [index-get id f]
  (let [old-doc (index-get id)
        new-doc (f old-doc)]
    (when (not= old-doc new-doc)
      {id new-doc})))

(def last-published-index
  {:id :last-published
   :version 1
   :schema [:tuple :uuid :time/instant] ;; TODO maybe enforce this in tests/dev or something
   :indexer
   (fn [{:biff.index/keys [index-get op doc]}]
     (when-let [id (and (= op ::xt/put)
                        (lib.item/source-id doc))]
       (index-update index-get
                     id
                     (fn [last-published]
                       (->> [last-published (lib.item/published-at doc)]
                            (filterv some?)
                            (apply max-key inst-ms))))))})

(def unread-index
  {:id :unread
   :version 0
   :schema [:or {:registry {}}
            ;; user+source -> read
            [:tuple [:tuple :uuid :uuid] :int]
            ;; source -> total
            [:tuple :uuid :int]
            ;; item -> source
            [:tuple :uuid :uuid]
            ;; rec -> item-read?
            [:tuple :uuid [:enum true]]]
   :indexer
   (fn [{:biff.index/keys [index-get op doc]}]
     (let [source-id (lib.item/source-id doc)]
       (cond
         (and (= op ::xt/put) source-id (-> (:xt/id doc) index-get nil?))
         {(:xt/id doc) source-id
          source-id ((fnil inc 0) (index-get source-id))}

         (and (= op ::xt/put) (:user-item/user doc))
         (let [new-doc-read? (lib.user-item/read? doc)
               old-doc-read? (boolean (index-get (:xt/id doc)))]
           (when (not= new-doc-read? old-doc-read?)
             (let [id [(:user-item/user doc) (index-get (:user-item/item doc))]
                   n-read ((fnil (if new-doc-read? inc dec) 0) (index-get id))]
               {(:xt/id doc) (when new-doc-read? true)
                id           (when (not= n-read 0) n-read)}))))))})

(def module {:resolvers [sub-info
                         sub-id->xt-id
                         email-title
                         feed-title
                         unread
                         published-at
                         items
                         from-params]
             :indexes [last-published-index
                       unread-index]})
