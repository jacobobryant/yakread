(ns benchmark
  (:require [com.biffweb.experimental :as biffx]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint :refer [print-table]]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn get-config []
  {:disk-cache
   {:path "storage/xtdb2/storage-cache"},

   :log
   [:local {:path "storage/xtdb2/log" :epoch (parse-long (System/getenv "XTDB_LOG_EPOCH"))}]

   :storage
   [:remote
    {:object-store
     [:s3
      {:bucket (System/getenv "XTDB_STORAGE_BUCKET")
       :endpoint (System/getenv "XTDB_STORAGE_ENDPOINT")
       :credentials
       {:access-key (System/getenv "XTDB_STORAGE_ACCESS_KEY")
        :secret-key (System/getenv "XTDB_STORAGE_SECRET_KEY")}}]}]})

(defn start-node []
  (let [node (xtn/start-node (get-config))
        datasource (HikariDataSource.
                    (doto (HikariConfig.)
                      (.setDataSource node)))]
    {:node node
     :conn datasource
     :close (fn []
              (.close datasource)
              (.close node))}))

(defonce system (atom {}))

(defn start-system []
  (reset! system (start-node)))

(defn stop-system []
  ((:close @system)))

(defn conn []
  (:conn @system))

(defn print-explain-results [results]
  (let [max-depth (apply max 0 (mapv (comp count :depth) results))]
    (print-table (for [{:keys [depth] :as row} results]
                   (assoc row :depth (subs (apply str depth (repeat max-depth " "))
                                           0
                                           max-depth))))))

(defn explain [query]
  (let [[sql & args] (biffx/format-query query)
        results      (xt/q (conn) (into [(str "explain " sql)] args))]
    (print-explain-results results)))

(defn explain-analyze [query]
  (let [[sql & args] (biffx/format-query query)
        results      (time (xt/q (conn) (into [(str "explain analyze " sql)] args)))]
    (print-explain-results results)))

(defn explain-all [query]
  (explain query)
  (explain-analyze query))

(defn run-benchmark []
  (let [user-id #uuid "e86e5e14-0001-46eb-9d11-134162ce930f"
        item-ids (edn/read-string (slurp "benchmark-item-ids.edn"))]
    (doseq [i [10 100 1000]]
      (println "# item-ids =" i)
      (explain-analyze
       {:select [:xt/id :user-item/item]
        :from :user-item
        :where [:and
                [:= :user-item/user user-id]
                [:in :user-item/item (take i item-ids)]]})
      (println)
      (println))))


(comment
  ;; Run `source config.env; clj -M:run nrepl`, then:

  (start-system)

  (run-benchmark)

  (stop-system)
  )
