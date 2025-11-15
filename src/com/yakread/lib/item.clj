(ns com.yakread.lib.item
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.route :refer [hx-redirect]]
   [com.yakread.lib.rss :as lib.rss]))

(def source-id (some-fn :item.feed/feed :item.email/sub))

(def published-at (some-fn :item/published-at :item/ingested-at))

(defn add-item-pipeline* [{:keys [get-url on-error on-success]}]
  {:start
   (fn [{:biff/keys [db base-url] :as ctx}]
     (let [url (str/trim (get-url ctx))]
       ;; TODO
       (if-some [item (or (biff/lookup db :item/url url :item/doc-type :item/direct)
                             (biff/lookup db :item/redirect-urls url :item/doc-type :item/direct))]
         (on-success ctx {:item/id (:xt/id item) :item/url (:item/url item)})
         {:biff.pipe/next       [:biff.pipe/http :handle-http]
          :biff.pipe.http/input {:url url
                                 :method  :get
                                 :headers {"User-Agent" base-url}
                                 :socket-timeout 5000
                                 :connection-timeout 5000}
          :biff.pipe/catch      :biff.pipe/http
          ::url                 url})))

   :handle-http
   (fn [{:keys [biff.pipe.http/output ::url] :as ctx}]
     (if-not (and (some-> output :headers (get "Content-Type") (str/includes? "text"))
                  (< (count (:body output)) (* 1000 1000)))
       (on-error ctx {:item/url url})
       {:biff.pipe/next [:yakread.pipe/js :handle-readability]
        :biff.pipe/catch :yakread.pipe/js
        ::url url
        ::final-url (str/replace (or (last (:trace-redirects output)) url)
                                 #"\?.*" "")
        ::raw-html (:body output)
        :yakread.pipe.js/fn-name "readability"
        :yakread.pipe.js/input {:url url :html (:body output)}}))

   :handle-readability
   (fn [{:keys [::url ::final-url ::raw-html]
         {:keys [content title byline length siteName textContent]} :yakread.pipe.js/output
         :as ctx}]
     (if (empty? content)
       (on-error ctx {:item/url url})
       (let [{[image] :og/image
              [published-time] :article/published-time} (lib.content/pantomime-parse raw-html)
             content (lib.content/normalize content)
             inline-content (<= (count content) 1000)
             item (lib.core/some-vals
                   {:db/doc-type :item/direct
                    :item/doc-type :item/direct
                    :xt/id (gen/uuid)
                    :item/ingested-at :db/now
                    :item/title title
                    :item/url final-url
                    :item/redirect-urls (when (not= url final-url)
                                          #{url})
                    :item/content-key (when-not inline-content (gen/uuid))
                    :item/content (when inline-content content)
                    :item/published-at (some-> published-time lib.content/parse-instant)
                    :item/excerpt (some-> textContent lib.content/excerpt)
                    :item/feed-url (-> (lib.rss/parse-urls* url raw-html) first :url)
                    :item/lang (lib.content/lang raw-html)
                    :item/site-name siteName
                    :item/byline byline
                    :item/length length
                    :item/image-url image})]
         (merge-with into
                     (when-not inline-content
                       {:biff.pipe/next
                        [{:biff.pipe/current :biff.pipe/s3
                          :biff.pipe.s3/input {:config-ns 'yakread.s3.content
                                               :method  "PUT"
                                               :key     (str (:item/content-key item))
                                               :body    content
                                               :headers {"x-amz-acl"    "private"
                                                         "content-type" "text/html"}}}]})
                     {:biff.pipe/next [:biff.pipe/tx]
                      :biff.pipe.tx/input [item]}
                     (update (on-success ctx {:item/id (:xt/id item) :item/url url})
                             :biff.pipe/next
                             #(remove #{:biff.pipe/tx} %))))))})

(defn add-item-pipeline [{:keys [user-item-kvs redirect-to]}]
  (add-item-pipeline*
   {:get-url
    (comp :url :params)

    :on-success
    (fn [{:keys [session]} {:item/keys [id]}]
      (merge {:biff.pipe/next [:biff.pipe/tx]
              :biff.pipe.tx/input [(merge {:db/doc-type :user-item
                                           :db.op/upsert {:user-item/user (:uid session)
                                                          :user-item/item id}}
                                          user-item-kvs)]}
             (some-> redirect-to (hx-redirect {:added true}))))

    :on-error
    (fn [_ _]
      (some-> redirect-to (hx-redirect {:error true})))}))
