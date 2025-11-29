(ns com.yakread.model.subscription
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.item :as lib.item]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.lib.user-item :as lib.user-item]
   [com.yakread.util.biff-staging :as biffs]
   [xtdb.api :as-alias xt]))

(defresolver user-subs [{:keys [biff/db]} {:keys [user/id]}]
  #::pco{:output [{:user/subscriptions [:sub/id]}
                  {:user/unsubscribed [:sub/id]}]}
  (as-> id $
    (q db
       '{:find [sub t]
         :in [user]
         :where [[sub :sub/user user]
                 [(get-attr sub :sub.email/unsubscribed-at nil) [t ...]]]}
       $)
    (group-by (comp some? second) $)
    (update-vals $ #(mapv (fn [[id _]] {:sub/id id}) %))
    (merge {true [] false []} $)
    (set/rename-keys $ {false :user/subscriptions true :user/unsubscribed})))

(defresolver email-title [{:keys [sub.email/from]}]
  {:sub/title (str/replace from #"\s<.*>" "")})

(defresolver feed-sub-title [{:keys [sub.feed/feed]}]
  #::pco{:input [{:sub.feed/feed [:feed/title]}]}
  {:sub/title (:feed/title feed)})

(defresolver email-subtitle [{:keys [sub.email/latest-item]}]
  {::pco/input [{:sub.email/latest-item [:item.email/reply-to]}]}
  {:sub/subtitle (:item.email/reply-to latest-item)})

(defresolver feed-sub-subtitle [{:keys [sub.feed/feed]}]
  #::pco{:input [{:sub.feed/feed [:feed/url]}]}
  {:sub/subtitle (:feed/url feed)})

(defresolver latest-email-item [{:keys [biff/db]} {:sub/keys [doc-type id]}]
  {::pco/output [{:sub.email/latest-item [:item/id]}]}
  (when (= doc-type :sub/email)
    {:sub.email/latest-item
     {:item/id (ffirst (q db
                          '{:find [item t]
                            :in [sub]
                            :order-by [[t :desc]]
                            :limit 1
                            :where [[item :item.email/sub sub]
                                    [item :item/ingested-at t]]}
                          id))}}))

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
  ;; TODO
  {:sub/unread 0
   #_(let [total (or (biff/index-get db :unread source-id) 0)
         _read (or (biff/index-get db :unread [(:xt/id user) source-id]) 0)
         unread (- total _read)]
     unread)})

(defresolver published-at [{:keys [biff/db]} {:keys [sub/source-id]}]
  {::pco/output [:sub/published-at]}
  ;; TODO
  #_(when-some [published-at (biff/index-get db :last-published source-id)]
    {:sub/published-at published-at}))

(defresolver items [{:keys [biff/db]} subscriptions]
  {::pco/input [:sub/source-id
                :sub/doc-type]
   ::pco/output [{:sub/items [:xt/id]}]
   ::pco/batch? true}
  (let [{feed-subs :sub/feed
         email-subs :sub/email} (group-by :sub/doc-type subscriptions)
        feed-source->items (->> (q db
                                   '{:find [source item]
                                     :in [[source ...]]
                                     :where [[item :item.feed/feed source]]
                                     :timeout 240000}
                                   (mapv :sub/source-id feed-subs))
                                (lib.core/group-by-to first #(array-map :xt/id (second %))))
        email-source->items (->> (q db
                                    '{:find [source item]
                                      :in [[source ...]]
                                      :where [[item :item.email/sub source]]
                                      :timeout 240000}
                                    (mapv :sub/source-id email-subs))
                                 (lib.core/group-by-to first #(array-map :xt/id (second %))))
        doc-type->source->items {:sub/email email-source->items
                                 :sub/feed feed-source->items}]
    (mapv (fn [{:sub/keys [source-id doc-type] :as sub}]
            (merge sub
                   {:sub/items (get-in doc-type->source->items [doc-type source-id] [])}))
          subscriptions)))

(defresolver latest-item [{:keys [biff/db]} {:sub/keys [source-id doc-type]}]
  {::pco/output [{:sub/latest-item [:xt/id]}]}
  (when-some [id (ffirst
                  (q db
                     {:find '[item t]
                      :in '[source]
                      :where [['item
                               (case doc-type
                                 :sub/feed :item.feed/feed
                                 :sub/email :item.email/sub)
                               'source]
                              '[item :item/ingested-at t]]
                      :order-by '[[t :desc]]
                      :limit 1}
                     source-id))]
    {:sub/latest-item {:xt/id id}}))

(defresolver from-params [{:keys [biff/db biff/malli-opts session path-params params]} _]
  #::pco{:output [{:params/sub [:xt/id]}]}
  (let [sub-id (or (:sub/id params)
                   (lib.serialize/url->uuid (:sub-id path-params)))
        sub (when (some? sub-id)
              ;; TODO
              #_(xt/entity db sub-id))]
    #_(when (and sub (= (:uid session) (:sub/user sub)))
      {:params/sub (biffs/joinify @malli-opts sub)})))

(defresolver params-checked [{:keys [biff/db biff/malli-opts session form-params params]} _]
  #::pco{:output [{:params.checked/subscriptions [:sub/id]}]}
  ;; TODO
  #_(let [subs* (mapv #(some->> % name parse-uuid (xt/entity db))
                    (keys (:subs params)))]
    (when (every? #(= (:uid session) (:sub/user %)) subs*)
      {:params.checked/subscriptions (mapv #(biffs/joinify @malli-opts %) subs*)})))

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
         ;; When a new item is created, increment its source's unread count.
         (and (= op ::xt/put) source-id (-> (:xt/id doc) index-get nil?))
         {(:xt/id doc) source-id
          source-id ((fnil inc 0) (index-get source-id))}

         ;; When an item is read, decrement its source's unread count.
         (and (= op ::xt/put) (:user-item/user doc))
         (let [new-doc-read? (lib.user-item/read? doc)
               old-doc-read? (boolean (index-get (:xt/id doc)))
               source-id (index-get (:user-item/item doc))]
           (when (and source-id (not= new-doc-read? old-doc-read?))
             (let [id [(:user-item/user doc) source-id]
                   n-read ((fnil (if new-doc-read? inc dec) 0) (index-get id))]
               {(:xt/id doc) (when new-doc-read? true)
                id           (when (not= n-read 0) n-read)}))))))})

(defresolver unread-items [{:keys [biff/db]} subscriptions]
  #::pco{:input [{:sub/user [:xt/id]}
                 {:sub/items [:xt/id]}]
         :output [{:sub/unread-items [:xt/id]}]
         :batch? true}
  (let [inputs (for [{:sub/keys [user items]} subscriptions
                     item items]
                 [(:xt/id user) (:xt/id item)])
        user->read-items (into {}
                               (map (fn [[user results]]
                                      [user (set (mapv second results))]))
                               (group-by
                                first
                                (q db
                                   '{:find [user item]
                                     :in [[[user item]]]
                                     :timeout 120000
                                     :where [[usit :user-item/item item]
                                             [usit :user-item/user user]
                                             (or-join [usit]
                                               [usit :user-item/viewed-at _]
                                               [usit :user-item/skipped-at _]
                                               [usit :user-item/favorited-at _]
                                               [usit :user-item/disliked-at _]
                                               [usit :user-item/reported-at _])]}
                                   inputs)))]
    (mapv (fn [{:sub/keys [user items] :as sub}]
            (let [read-items (get user->read-items (:xt/id user) #{})
                  unread-items (filterv (complement (comp read-items :xt/id)) items)]
              (merge sub
                     {:sub/unread-items unread-items})))
          subscriptions)))

(defresolver mv [{:keys [biff/db]} subs*]
  {::pco/input  [:sub/id]
   ::pco/output [{:sub/mv [:xt/id]}]
   ::pco/batch?  true}
  (let [sub->mv (into {}
                      (q db
                         '{:find [sub mv]
                           :in [[sub ...]]
                           :where [[mv :mv.sub/sub sub]]}
                         (mapv :sub/id subs*)))]
    (mapv (fn [{:keys [sub/id] :as sub}]
            (merge sub
                   (when-some [mv-id (sub->mv id)]
                     {:sub/mv {:xt/id mv-id}})))
          subs*)))

(def module {:resolvers [user-subs
                         sub-info
                         sub-id->xt-id
                         email-title
                         feed-sub-title
                         unread
                         published-at
                         items
                         latest-item
                         from-params
                         params-checked
                         unread-items
                         feed-sub-subtitle
                         latest-email-item
                         email-subtitle
                         mv]
             :indexes [last-published-index
                       unread-index]})
