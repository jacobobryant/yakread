(ns com.yakread.smtp-test
  (:require
   [com.yakread :as main]
   [com.yakread.lib.test :as lib.test]
   [com.yakread.smtp :as sut]))

;; TODO add some tests after setting up fixtures

(def fn-examples
  (lib.test/fn-examples
   [#'sut/deliver* :start]
   #_[{:doc "test for my-fn"
       :db-contents #{{:xt/id 1
                       :user/email "hello@example.com"}}
       :ctx {:params {:foo "bar"}}}]
   ))

(def route-examples
  (lib.test/route-examples
   #_[:app/my-route :post :start]
   #_[{:doc "test for :app/my-route"
       :db-contents #{{:xt/id 1
                       :user/email "hello@example.com"}}
       :ctx {:params {:foo "bar"}}}]))

(defn get-context []
  {:biff/router          main/router
   :biff.test/current-ns (lib.test/current-ns)
   :biff.test/examples   (concat route-examples
                                 fn-examples)})

;; (deftest examples
;;   (lib.test/check-examples! (get-context)))

(comment
  (lib.test/write-examples! (get-context))
  ,)
