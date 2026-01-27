(ns repl
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [com.biffweb.experimental :as biffx]
   [com.yakread]
   [tick.core :as tick]))


(defn node []
  (:biff/node @com.yakread/system))

(defn conn []
  (:biff/conn @com.yakread/system))

(defn user-id [email]
  (biffx/q (node) ["select _id from user where user$email = ?" email]))

(defn time-q [query]
  (time (do (biffx/q (conn) query) :done)))

(defn explain [query]
  (let [results (biffx/q (conn)
                         (-> query
                             biffx/format-query
                             (update 0 #(str "explain " %))))
        max-depth (apply max 0 (mapv (comp count :depth) results))]
    (vec (for [{:keys [depth] :as row} results]
           (assoc row :depth (subs (apply str depth (repeat max-depth " "))
                                   0
                                   max-depth))))))

(defn explain-analyze [query]
  (let [results (biffx/q (conn)
                         (-> query
                             biffx/format-query
                             (update 0 #(str "explain analyze " %))))
        max-depth (apply max 0 (mapv (comp count :depth) results))]
    (vec (for [{:keys [depth] :as row} results]
           (assoc row :depth (subs (apply str depth (repeat max-depth " "))
                                   0
                                   max-depth))))))

(defn explain-q [query]
  (time (pprint/print-table (explain-analyze query))))

(defn explain-all [query]
  (pprint/print-table (explain query))
  (explain-q query))

(defn q [query]
  (biffx/q (conn) query))

(def jacob-user-id #uuid "e86e5e14-0001-46eb-9d11-134162ce930f")

(comment

  (com.yakread/refresh)

  (time
   (biffx/q (node)
            "select * from user limit 2"
            ))

  (user-id "jacob@thesample.ai") ;

  


  {:select [[:user-item/item :item/id]]
   :from :user-item
   :where [:and
           [:= :user-item/user user-id]
           [:is-not :user-item/bookmarked-at nil]]}

  (def query com.yakread.util.biff-staging/query*)

  (dissoc query :where)

  (def conn (.build (.createConnectionBuilder (node))))

  (time
   (do
     (biffx/q conn
              {:select [:xt/id
                        :item/published-at
                        ;:item.email/sub
                        ;:item/url
                        ;:item/length
                        ;:item/site-name
                        ;:item/byline
                        ;:item/author-name
                        :item/doc-type
                        ;:item/title
                        ;:item.feed/feed
                        ;:item/ingested-at
                        ],
               :from :item
               :where [:in :xt/id (take 10 item-ids)]})
     :done))

  (def user-ids (mapv :xt/id (biffx/q conn "select _id from user")))

  (take 20 user-ids)

  (time
   (do
     (biffx/q conn
              {:select [:xt/id
                        :item.email/sub
                        :item/url
                        :item/length
                        :item/site-name
                        :item/byline
                        :item/author-name
                        :item/doc-type
                        :item/title
                        :item.feed/feed
                        :item/ingested-at
                        ],
               :from :item
               :where [:in :xt/id item-ids]})
     :done))

  (time
   (do
     (biffx/q conn
              {:select [:xt/id
                        :user/email],
               :from :user
               :where [:in :xt/id (take 100 user-ids)]})
     :done))

  (biffx/q conn "select _id, sub$user from sub limit 5")
  (biffx/q conn "select _id, item$feed$feed, item$email$sub from item limit 5")
  (biffx/q conn "select _id, user_item$user from user_item limit 5")

  (biffx/q conn "select * from sub limit 1")

  (user-id "jacob@thesample.ai")

  (count item-ids)
  (take 3 item-ids)
  (def item-ids (mapv :item/id (q {:select [[:user-item/item :item/id]]
                                   :from :user-item
                                   :where [:and
                                           [:= :user-item/user jacob-user-id]
                                           [:is-not :user-item/bookmarked-at nil]]})))

  (time-q {:select [[:user-item/item :item/id]]
           :from :user-item
           :where [:and
                   [:= :user-item/user user-id]
                   [:is-not :user-item/bookmarked-at nil]]})


  (biffx/format-query {:explain_analyze [{:select :foo}]})

  (explain-all {:select [:xt/id :user-item/item]
                :from :user-item
                :where [:= :user-item/user jacob-user-id]})

  (time-q {:select [[[:count :*]]] :from :item})

  (->> (explain-all {:select [:xt/id :user-item/item]
                     :from :user-item
                     :where [:= :user-item/user jacob-user-id]})
       (mapv #(subs (str (:xt/id %)) 0 4))
       distinct)

  (explain-all {:select [:xt/id]
                :from :user-item
                :where [:= :user-item/user jacob-user-id]
                :limit 1})

  (explain-all {:select :xt/id
                :from :item
                :where [:= :item/doc-type [:lift :item/direct]]})

  (q {:select [[[:count :*]]] :from :user-item})
  (time (q {:select [[[:count :*]]] :from :item}))

  (explain-all {:select :item/ingested-at :from :item :where [:= :xt/id (rand-nth item-ids)]})

  (explain-all {:select [:xt/id]
                :from :user-item
                :where [:= :user-item/user jacob-user-id]
                :limit 1})

  (explain-all {:select :xt/id
                :from :item
                :where [:= :item/ingested-at #time/zoned-date-time "2024-05-24T10:03:33.693Z[UTC]"]
                :limit 1})

  (explain-all {:select :*
      :from :item
      :where [:= :xt/id (rand-nth rand-item-ids)]})

  (def rand-item-ids (mapv :xt/id (q {:select :item._id
                                      :from :item
                                      :join [:sub [:= :sub._id :item.email/sub]]
                                      :where [:= :sub/user jacob-user-id]
                                      })))

  #time/zoned-date-time "2022-08-17T19:50:01.398Z[UTC]"

  (biffx/q conn
           {:select [[[:count :*]]]
            :from :user-item
            :where [:= :user-item/user user-id]})

  (count item-ids)
  (first user-item-inputs)
  (def user-item-inputs @com.yakread.model.item/inputs*)
  ((mapv count @com.yakread.model.item/inputs*))


  (malli.core/validate :sub
                       {:xt/id #uuid "0000732b-22ec-7d5b-d550-01646623aba8",
                        :sub/created-at
                        #time/zoned-date-time "2024-06-14T04:57:05.749Z[UTC]",
                        :sub.email/from "Mando Support",
                        :sub/user #uuid "83ad562d-13b7-413b-99c5-7ebf92ddb3a4"}
                       com.yakread/malli-opts)



  (def big-query (edn/read-string (slurp "big-query.edn")))
  (def item-ids (get-in big-query [:where 2]))
  (count item-ids)

 

  (time-q {:select [:item/published-at
                    :item.email/sub
                    :item/url
                    :item/length
                    :item/site-name
                    :item/byline
                    :item/author-name
                    :item/doc-type
                    :xt/id
                    :item/title
                    :item.feed/feed
                    :item/ingested-at]
           :from :item
           :where [:in :xt/id (take 10 item-ids)]})

  (explain-all {:select [:xt/id :item/url]
                :from :item
                :where
                #_[:= :xt/id (first item-ids)]
                [:in :xt/id (take 1 item-ids)]})

  (let [t (tick/zoned-date-time)]
    (count (q {:select [:xt/id :user-item/item]
               :from :user-item
               :where [:and
                       [:= :user-item/user jacob-user-id]
                       [:< :user-item/bookmarked-at t]]})))


  (explain-all
   {:select 1
    :from :user-item
    :where [:and
            [:= :user-item/user jacob-user-id]
            [:= :user-item/item #uuid "e86e2a79-bfda-4da4-b3c4-ac6137ae634f"]
            [:is-not :user-item/viewed-at nil]]
    :limit 1})

  (time (xtdb.api/q (conn) ["explain analyze select user$email from user where _id = ?" jacob-user-id]))

  (explain-q {:select [:xt/id :item/url]
              :from :item
              :where
              [:in :xt/id (take 1 item-ids)]
              ;[:= :xt/id (first item-ids)]
              })

  (doseq [i [10 100 1000]]
    (println "# item-ids =" i)
    (explain-q {:select [:xt/id :user-item/item]
                :from :user-item
                :where [:and
                        [:= :user-item/user jacob-user-id]
                        [:in :user-item/item (take i item-ids)]]})
    (println)
    (println))


  (spit "benchmark-item-ids.edn" (pr-str item-ids))

  )
