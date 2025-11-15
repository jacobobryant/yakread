(ns com.yakread.app.subscriptions.add-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest]]
   [com.yakread :as main]
   [com.yakread.app.subscriptions.add :as sut]
   [com.yakread.lib.test :as lib.test]))

(def username-examples
  (lib.test/route-examples
   [::sut/set-username :post :start]
   [{:doc         "already has a username"
     :db-contents #{{:xt/id 1
                     :user/email-username "abc"}}
     :ctx         {:session {:uid 1}
                   :params {:username "123"}}}
    {:doc         "invalid username"
     :db-contents #{}
     :ctx         {:session {:uid 1}
                   :params {:username "admin"}}}
    {:doc         "username taken"
     :db-contents #{{:xt/id 2
                     :user/email-username "abc"}}
     :ctx         {:session {:uid 1}
                   :params {:username "abc"}}}
    {:doc         "username not taken"
     :db-contents #{}
     :ctx         {:session {:uid 1}
                   :params {:username "abc"}}}]

   [::sut/set-username :post :end]
   [{:doc        "fail"
     :handler-id :end
     :ctx        {::sut/username "hello"
                  :biff.pipe/exception true}}
    {:doc        "succeed"
     :handler-id :end
     :ctx        {::sut/username "hello"}}]))

(def rss-examples
  (lib.test/route-examples
   [::sut/add-rss :post :start]
   [{:doc "fix the url"
     :ctx {:params {:url "example.com"}}}]

   [::sut/add-rss :post :add-urls]
   [{:doc     "invalid url"
     :fixture :example-com
     :ctx     {:session {:uid 1}}}
    {:doc     "auto-discovery"
     :fixture :obryant-dev
     :ctx     {:session {:uid 1}}}
    {:doc     "direct url"
     :fixture :obryant-dev-feed-xml
     :ctx     {:session {:uid 1}}}
    {:doc         "don't create feed doc if someone already subscribes"
     :fixture     :obryant-dev
     :ctx         {:session {:uid 1}}
     :db-contents #{{:xt/id 2
                     :feed/url "https://obryant.dev/feed.xml"}}}]))

(def opml-examples
  (lib.test/route-examples
   [::sut/add-opml :post :start]
   [{:doc "slurp the uploaded file"
     :ctx {:params {:opml {:tempfile "/tmp/some-file"}}}}]

   [::sut/add-opml :post :end]
   [{:doc     "extract and save the opml urls"
     :fixture :sample-opml
     :ctx     {:session {:uid 1}}}
    {:doc "no urls found, show an error"
     :ctx {:session {:uid 1}
           :biff.pipe.slurp/output ""}}]))

(defn get-context []
  (let [current-ns (lib.test/current-ns)
        fixtures   (merge (lib.test/read-fixtures! current-ns)
                          {:sample-opml {:biff.pipe.slurp/output
                                         (slurp (io/file (lib.test/dirname current-ns) "sample.opml"))}})]
    {:biff/router          main/router
     :biff.test/current-ns current-ns
     :biff.test/fixtures   fixtures
     :biff.test/examples   (concat username-examples
                                   rss-examples
                                   opml-examples)}))

(deftest examples
  #_(lib.test/check-examples! (get-context)))

(comment
  ;; Generate fixtures
  (let [{:biff.pipe/keys [http]} (:biff.pipe/global-handlers main/initial-system)
        http-get (fn [url]
                   (http {:biff.pipe.http/input
                          {:url url
                           :method :get
                           :headers {"User-Agent" "https://yakread.com"}}}))]
    (lib.test/write-fixtures!
     (lib.test/current-ns)
     {:example-com          (http-get "https://example.com")
      :obryant-dev          (http-get "https://obryant.dev")
      :obryant-dev-feed-xml (http-get "https://obryant.dev/feed.xml")}))

  (lib.test/write-examples! (get-context))

  ,)
