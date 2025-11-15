(ns com.yakread.work.subscription-test
  (:require
   [clojure.test :refer [deftest]]
   [com.yakread :as main]
   [com.yakread.lib.test :as lib.test]
   [com.yakread.work.subscription :as sut]))

(def inst-2020 (java.time.Instant/parse "2020-01-01T00:00:00Z"))

(defn published-at-types [ctx _]
  (->> (sut/sync-feed! ctx :end)
       :biff.pipe.tx/input
       (keep :item/published-at)
       (mapv (comp str type))
       set))

(def sync-all-feeds-examples
  (lib.test/fn-examples
   [#'sut/sync-all-feeds! :start]
   [{:doc "skip feeds synced in the past 4 hours"
     :db-contents #{{:xt/id 1 :feed/url "1"}
                    {:xt/id 2 :feed/url "2" :feed/synced-at inst-2020}
                    {:xt/id 3 :feed/url "3" :feed/synced-at (.minusSeconds inst-2020 (* 60 60 24))}}
     :ctx {:biff/now inst-2020}}]

   [#'sut/sync-feed! :start]
   [{:doc "Include etag and last-modified"
     :db-contents #{{:xt/id 1
                     :feed/url "https://example.com/feed.xml"
                     :feed/etag "some-etag"
                     :feed/last-modified "some-last-modified"}}
     :ctx {:biff/job {:feed/id 1}}}]

   [#'sut/sync-feed! :end]
   [{:doc "save feed items"
     :fixture :obryant-dev-feed-xml
     :ctx {:biff/job {:feed/id 1}}}
    {:doc "skip existing items"
     :db-contents #{{:xt/id 2
                     :item.feed/feed "some-feed"
                     :item/title "Impressions on Sudbury Schooling"}
                    {:xt/id 3
                     :item.feed/feed "some-feed"
                     :item.feed/guid "https://obryant.dev/p/platypub-back-to-square-one/"}}
     :fixture :obryant-dev-feed-xml
     :ctx {:biff/job {:feed/id "some-feed"}}}]

   [#'published-at-types nil]
   [{:doc ":item/published-at should be a java.time.Instant"
     :fixture :obryant-dev-feed-xml
     :ctx {:biff/job {:feed/id 1}}}]))

(defn get-context []
  (let [current-ns (lib.test/current-ns)]
    {:biff/router          main/router
     :biff.test/current-ns current-ns
     :biff.test/fixtures   (lib.test/read-fixtures! current-ns)
     :biff.test/examples   sync-all-feeds-examples}))

(deftest examples
  #_(lib.test/check-examples! (get-context)))

(comment
  ;; Generate fixtures
  (let [{:keys [com.yakread.pipe/remus]} (:biff.pipe/global-handlers main/initial-system)
        remus* (fn [url]
                 (remus {:com.yakread.pipe.remus/url "https://obryant.dev/feed.xml"}))]
    (lib.test/write-fixtures!
     (lib.test/current-ns)
     {:obryant-dev-feed-xml (remus* "https://obryant.dev/feed.xml")}))

  (lib.test/write-examples! (get-context))
  ,)
