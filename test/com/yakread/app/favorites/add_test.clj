(ns com.yakread.app.favorites.add-test
  (:require [clojure.test :refer [deftest is]]
            [com.yakread.app.favorites.add :as sut]
            [com.yakread.lib.pipeline :as lib.pipe]
            [com.yakread.lib.test :as lib.test]
            [com.yakread :as main]
            [clj-http.client :as http]))

(def route-examples
  (lib.test/route-examples
   [::sut/add-item :post :start]
   [{:doc "item exists"
     :db-contents #{{:xt/id 2
                     :item/url "https://example.com"
                     :item/doc-type :item/direct}}
     :ctx {:session {:uid 1}
           :params {:url "https://example.com"}}}
    {:doc "item doesn't exist"
     :db-contents #{}
     :ctx {:session {:uid 1}
           :params {:url "https://example.com"}}}]

   [::sut/add-item :post :handle-http]
   [{:doc "Received html"
     :ctx {:biff.pipe.http/output {:url "https://example.com"
                                   :headers {"Content-Type" "text/html"}
                                   :body "<p>how now brown cow</p>"}}}
    {:doc "Didn't receive html"
     :ctx {:biff.pipe.http/output {:url "https://example.com"
                                   :headers {"Content-Type" "image/png"}
                                   :body "<p>how now brown cow</p>"}}}]

   [::sut/add-item :post :handle-readability]
   [{:doc "parse obryant.dev post"
     :fixture :post-0
     :ctx {:session {:uid 1}}}
    {:doc "parse kibty.town post"
     :fixture :post-1
     :ctx {:session {:uid 1}}}
    {:doc "empty content"}]))

(defn get-context []
  (let [ns* (lib.test/current-ns)]
    {:biff/router          main/router
     :biff.test/current-ns ns*
     :biff.test/examples   route-examples
     :biff.test/fixtures   (lib.test/read-fixtures! ns*)}))

(deftest examples
  #_(lib.test/check-examples! (get-context)))

(comment
  (lib.test/write-examples! (get-context))

  (lib.test/write-fixtures!
   (lib.test/current-ns)
   (into {}
         (for [[i url] (map-indexed vector ["https://obryant.dev/p/you-can-unbundle-social-media/"
                                            "https://kibty.town/blog/todesktop/"])
               :let [html (:body (http/get url))]]
           [(keyword (str "post-" i))
            {:com.yakread.lib.item/url url
             :com.yakread.lib.item/raw-html html
             :yakread.pipe.js/output (lib.pipe/call-js "readability" {:url url :html html})}])))

  ,)
