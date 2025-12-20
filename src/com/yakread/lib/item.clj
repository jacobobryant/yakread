(ns com.yakread.lib.item
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [com.biffweb.experimental :as biffx]
   [com.yakread.util.biff-staging :as biffs]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.route :refer [hx-redirect]]
   [com.yakread.lib.rss :as lib.rss]))

(def source-id (some-fn :item.feed/feed :item.email/sub))

(def published-at (some-fn :item/published-at :item/ingested-at))

(defn add-item-machine* [{:keys [get-url on-error on-success]}]
  {:post
   (fn [{:biff/keys [conn base-url] :as ctx}]
     (let [url (str/trim (get-url ctx))]
       (if-some [item (first
                       (biffx/q conn
                                {:select [:item._id :item/url]
                                 :from :item
                                 :left-join [:redirect [:= :redirect/item :item._id]]
                                 :where [:and
                                         [:or
                                          [:= :item/url url]
                                          [:= :redirect/url url]]
                                         [:= :item/doc-type "item/direct"]]
                                 :limit 1}))]
         (on-success ctx {:item/id (:xt/id item) :item/url (:item/url item)})
         {:biff.fx/http {:url url
                         :method  :get
                         :headers {"User-Agent" base-url}
                         :socket-timeout 5000
                         :connection-timeout 5000
                         :throw-exceptions false}
          :biff.fx/next :handle-http
          ::url         url})))

   :handle-http
   (fn [{:keys [biff.fx/http] :as ctx}]
     (if-not (and (not (:exception http))
                  (some-> http :headers (get "Content-Type") (str/includes? "text"))
                  (< (count (:body http)) (* 1000 1000)))
       (on-error ctx {:item/url (:url http)})
       {:com.yakread.fx/js {:fn-name "readability"
                            :input {:url (:url http) :html (:body http)}}
        :biff.fx/next :handle-readability
        ::url (:url http)
        ::final-url (str/replace (or (last (:trace-redirects http)) (:url http))
                                 #"\?.*" "")
        ::raw-html (:body http)}))

   :handle-readability
   (fn [{:keys [::url ::final-url ::raw-html biff/now]
         {:keys [content title byline length siteName textContent]} :com.yakread.fx/js
         :as ctx}]
     (if (empty? content)
       (on-error ctx {:item/url url})
       (let [{[image] :og/image
              [published-time] :article/published-time} (lib.content/pantomime-parse raw-html)
             content (lib.content/normalize content)
             inline-content (<= (count content) 1000)
             content-key (when-not inline-content (gen/uuid))
             item-id (gen/uuid)]
         [(when-not inline-content
            {:biff.fx/s3 {:config-ns 'yakread.s3.content
                          :method  "PUT"
                          :key     (str content-key)
                          :body    content
                          :headers {"x-amz-acl"    "private"
                                    "content-type" "text/html"}}})
          (merge-with into
                      {:biff.fx/tx
                       (filterv
                        some?
                        [[:put-docs :item
                          (lib.core/some-vals
                           {:xt/id item-id
                            :item/ingested-at now
                            :item/title title
                            :item/url final-url
                            :item/content-key content-key
                            :item/content (when inline-content content)
                            :item/published-at (some-> published-time lib.content/parse-instant)
                            :item/excerpt (some-> textContent lib.content/excerpt)
                            :item/feed-url (-> (lib.rss/parse-urls* url raw-html) first :url)
                            :item/lang (lib.content/lang raw-html)
                            :item/site-name siteName
                            :item/byline byline
                            :item/length length
                            :item/image-url image})]
                         (when (not= url final-url)
                           [:put-docs :redirect
                            {:xt/id (gen/uuid)
                             :redirect/url url
                             :redirect/item item-id}])])}
                      (on-success ctx {:item/id item-id :item/url url}))])))})

(defn add-item-machine [{:keys [user-item-kvs redirect-to]}]
  (add-item-machine*
   {:get-url
    (comp :url :params)

    :on-success
    (fn [{:keys [session biff/conn]} {:item/keys [id]}]
      (merge {:biff.fx/tx (biffs/upsert conn
                                        :user-item
                                        {:user-item/user (:uid session)
                                         :user-item/item id}
                                        user-item-kvs)}
             (some-> redirect-to (hx-redirect {:added true}))))

    :on-error
    (fn [_ _]
      (some-> redirect-to (hx-redirect {:error true})))}))
