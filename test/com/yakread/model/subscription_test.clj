(ns com.yakread.model.subscription-test
  (:require
   [clojure.test :refer [deftest]]
   [com.yakread.lib.test :as lib.test]
   [com.yakread.model.subscription :as sut]
   [xtdb.api :as-alias xt]))

(def index-examples
  (lib.test/index-examples
   :last-published
   [{:doc "New feed"
     :ctx #:biff.index{:index-get {}
                       :op ::xt/put
                       :doc {:item.feed/feed 1
                             :item/published-at (lib.test/instant 2024)}}}
    {:doc "Existing feed"
     :ctx #:biff.index{:index-get {1 (lib.test/instant 2024)}
                       :op ::xt/put
                       :doc {:item.feed/feed 1
                             :item/published-at (lib.test/instant 2025)}}}
    {:doc ":item/ingested-at"
     :ctx #:biff.index{:index-get {1 (lib.test/instant 2024)}
                       :op ::xt/put
                       :doc {:item.feed/feed 1
                             :item/ingested-at (lib.test/instant 2025)}}}
    {:doc "old item -- no-op"
     :ctx #:biff.index{:index-get {1 (lib.test/instant 2024)}
                       :op ::xt/put
                       :doc {:item.feed/feed 1
                             :item/ingested-at (lib.test/instant 2023)}}}

    {:doc "email item"
     :ctx #:biff.index{:index-get {}
                       :op ::xt/put
                       :doc {:item.email/sub 1
                             :item/published-at (lib.test/instant 2024)}}}]))

(defn get-context []
  {:biff/modules         (delay [sut/module])
   :biff.test/current-ns (lib.test/current-ns)
   :biff.test/examples   index-examples})

(deftest examples
  #_(lib.test/check-examples! (get-context)))

#_(deftest-index sut/last-published-index
  {:num-tests   25
   :model-opts  {:biff/malli-opts main/malli-opts
                 :schemas #{:item/feed :item/email}
                 :rank-overrides {:sub/email 1}}
   :expected-fn (fn [docs]
                  (-> (group-by lib.item/source-id docs)
                      (dissoc nil)
                      (update-vals (fn [docs]
                                     (->> docs
                                          (mapv lib.item/published-at)
                                          (apply max-key inst-ms))))))})

#_(deftest-index sut/unread-index
  {:num-tests   25
   :model-opts  {:biff/malli-opts main/malli-opts
                 :schemas #{:item/feed :item/email :user-item :item/direct}
                 :rank-overrides {:sub/email 1}}
   :expected-fn (fn [docs]
                  (let [db (->> docs
                                (filterv lib.item/source-id)
                                (group-by lib.item/source-id)
                                (mapcat (fn [[source-id docs]]
                                          (into {source-id (count docs)}
                                                (for [{:keys [xt/id]} docs]
                                                  [id source-id]))))
                                (into {}))]
                    (merge db
                           (->> docs
                                (filterv lib.user-item/read?)
                                (group-by (juxt :user-item/user (comp db :user-item/item)))
                                (mapcat (fn [[id docs]]
                                          (into {id (count docs)}
                                                (for [{:keys [xt/id]} docs]
                                                  [id true]))))))))})

(comment

  (unread-index-test)

  (lib.test/write-examples! (get-context))
  ,)
