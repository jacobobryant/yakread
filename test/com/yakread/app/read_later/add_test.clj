(ns com.yakread.app.read-later.add-test
  (:require [clojure.test :refer [deftest is]]
            [com.yakread.app.read-later.add :as sut]
            [com.yakread.lib.test :as lib.test]
            [com.yakread :as main]))

(def fn-examples
  (lib.test/fn-examples
   [#'sut/add-item-async :start]
   [#_{:doc "translates url"
     :ctx {:biff/job {:user/id 1 :url "https://example.com"}}}
    #_{:doc "translates user id"
     :db-contents #{{:xt/id 2
                     :item/url "https://example.com"
                     :item/doc-type :item/direct}}
     :ctx {:biff/job {:user/id 1 :url "https://example.com"}}}]))

(def route-examples
  (lib.test/route-examples
   [::sut/add-batch :post :start]
   [{:doc "non-empty batch"
     :ctx {:session {:uid 1}
           :params {:batch "https://example.com foo http://obryant.dev/some-post bar"}}}
    {:doc "empty batch"
     :ctx {:session {:uid 1}
           :params {:batch "ttps://example.com foo ttp://obryant.dev/some-post bar"}}}]))

(defn get-context []
  {:biff/router          main/router
   :biff.test/current-ns (lib.test/current-ns)
   :biff.test/examples   (concat route-examples
                                 fn-examples)})

(deftest examples
  #_(lib.test/check-examples! (get-context)))

(comment
  (lib.test/write-examples! (get-context))
  ,)
