(ns com.yakread.work.subscription
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.util.biff-staging :as biffs]
   [rum.core :as rum]
   [tick.core :as tick])
  (:import
   [org.jsoup Jsoup]))

(def ^:private epoch (java.time.Instant/ofEpochMilli 0))

(defn active-user-ids [conn now]
  (let [t0 (tick/<< now (tick/of-months 6))]
    (->> (biffx/q conn
                  {:union [{:select [[:xt/id :user/id]]
                            :from :user
                            :where [:< t0 :user/joined-at]}
                           {:select [[:user-item/user :user/id]]
                            :from :user-item
                            :where [:< t0 :user-item/viewed-at]}
                           {:select [[:ad/user :user/id]]
                            :from :ad
                            :where [:< t0 :ad/updated-at]}
                           {:select [[:ad.click/user :user/id]]
                            :from :ad-click
                            :where [:< t0 :ad.click/created-at]}]})
         (mapv :user/id))))

;; TODO modify sync waiting period based on :feed/failed-syncs
(fx/defmachine sync-all-feeds!
  :start
  (fn [{:keys [biff/conn biff/now yakread.work.sync-all-feeds/enabled biff/queues]}]
    (when (and enabled (= 0 (.size (:work.subscription/sync-feed queues))))
      (let [user-ids (active-user-ids conn now)
            t0 (tick/<< now (tick/of-hours 12))
            feeds (biffx/q conn
                           {:select :feed._id
                            :from :sub
                            :join [:feed [:= :sub.feed/feed :feed._id]]
                            :where [:and
                                    [:in :sub/user user-ids]
                                    [:or
                                     [:is :feed/synced-at nil]
                                     [:< :feed/synced-at t0]]]})]
        (log/info "Syncing" (count feeds) "feeds")
        {:biff.fx/queue {:jobs (for [{:keys [xt/id]} feeds]
                                 [:work.subscription/sync-feed {:feed/id id}])}}))))

(defn- entry->html [entry]
  (->> (concat (:contents entry) [(:description entry)])
       (sort-by (fn [{content-type :type}]
                  (str/includes? (or content-type "") "html"))
                #(compare %2 %1))
       first
       :value))

(comment
  (biffx/q (:biff/conn (repl/context)) "select * from feed")
  (sync-feed! (merge (repl/context)
                     {:biff/job {:feed/id #uuid "61781ae0-e119-3d49-e200-cb9c698396bb"}})))

;; TODO update url for feeds that change their URL
(fx/defmachine sync-feed!
  :start
  (fn [{:biff/keys [conn base-url] {:keys [feed/id]} :biff/job}]
    (let [[{:feed/keys [url etag last-modified failed-syncs]}]
          (biffx/q conn {:select [:feed/url
                                  :feed/etag
                                  :feed/last-modified
                                  :feed/failed-syncs]
                         :from :feed
                         :where [:= :xt/id id]})]
      {:biff.fx/next :parse
       :biff.fx/http {:method :get
                      :url url
                      :headers (into {}
                                     (remove (comp nil? val))
                                     {"User-Agent" base-url
                                      "If-None-Match" etag
                                      "If-Modified-Since" last-modified})
                      :socket-timeout     5000
                      :connection-timeout 5000
                      :throw-exceptions false
                      :as :stream}
       :feed/failed-syncs failed-syncs}))

  :parse
  (fn [{:keys [biff/conn biff/job biff.fx/http biff/now feed/failed-syncs]}]
    (let [{feed-id :feed/id}          job
          {:keys [headers exception]} http
          remus-output                (when-not exception
                                        (biff/catchall (remus/parse-http-resp http)))
          success                     (some? remus-output)

          {:keys [title description image entries]} remus-output

          feed-title (some-> title (lib.content/truncate 100))
          feed-tx [[:patch-docs :feed
                    (into {}
                          (filter (comp some? val))
                          {:xt/id              feed-id
                           :feed/synced-at     now
                           :feed/failed-syncs  (if success 0 (inc (or failed-syncs 0)))
                           :feed/title         (some-> title (lib.content/truncate 100))
                           :feed/description   (some-> description (lib.content/truncate 300))
                           :feed/image-url     (if (string? image) image (:url image))
                           :feed/etag          (lib.core/pred-> (get headers "Etag") coll? first)
                           :feed/last-modified (lib.core/pred-> (get headers "Last-Modified") coll? first)})]]

          items    (doall
                    (for [entry (take 20 entries)
                          :let [html (entry->html entry)
                                text (or (:textContent entry)
                                         (some-> html (Jsoup/parse) (.text)))
                                title (or (:title entry)
                                          (some-> text not-empty (lib.content/truncate 75))
                                          "[no title]")
                                use-text-for-title (and (not (:title entry))
                                                        (not-empty text))
                                html (or html
                                         (when (:link entry)
                                           (rum/render-static-markup
                                            [:a {:href (:link entry)} (:link entry)])))]
                          :when (some? html)]
                      (into {}
                            (remove (comp nil? val))
                            {:xt/id             (biffs/gen-uuid feed-id)
                             :item.feed/feed    feed-id
                             :item/title        title
                             :item/content-key  (when (< 1000 (count html))
                                                  (biffs/gen-uuid))
                             :item/content      html
                             :item/ingested-at  now
                             :item/lang         (lib.content/lang html)
                             :item/paywalled    (some-> text str/trim (str/ends-with? "Read more"))
                             :item/url          (some-> (:link entry) str/trim)
                             :item/published-at (some-> (some entry [:published-date :updated-date])
                                                        (tick/in "UTC"))
                             :item/author-name  (or (-> entry :authors first :name)
                                                    feed-title)
                             :item/author-url   (some-> entry :authors first :uri str/trim)
                             :item/excerpt      (lib.content/excerpt
                                                 (if use-text-for-title
                                                   (str (when (str/ends-with? title "…")
                                                          "…")
                                                        (subs text (count (str/replace title #"…$" ""))))
                                                   text))
                             :item/length       (count text)
                             :item/byline       (:byline entry)
                             :item/image-url    (some-> (:og/image entry) str/trim)
                             :item/site-name    (:siteName entry)
                             :item.feed/guid    (:uri entry)})))

          titles   (not-empty (keep :item/title items))
          guids    (not-empty (keep :item.feed/guid items))
          existing (when (or titles guids)
                     (biffx/q conn
                              {:select [:item/title :item.feed/guid]
                               :from :item
                               :where [:and
                                       [:= :item.feed/feed feed-id]
                                       (concat [:or]
                                               (when titles
                                                 [[:in :item/title titles]])
                                               (when guids
                                                 [[:in :item.feed/guid guids]]))]}))
          existing-titles (into #{} (keep :item/title) existing)
          existing-guids  (into #{} (keep :item.feed/guid) existing)

          items     (into []
                          (remove (some-fn (comp existing-titles :item/title)
                                           (comp existing-guids :item.feed/guid)))
                          items)
          s3-inputs (vec
                     (for [{:item/keys [content content-key]} items
                           :when content-key]
                       {:config-ns 'yakread.s3.content
                        :method  "PUT"
                        :key     (str content-key)
                        :body    content
                        :headers {"x-amz-acl"    "private"
                                  "content-type" "text/html"}}))
          items     (mapv (fn [item]
                            (cond-> item
                              (:item/content-key item) (dissoc :item/content)))
                          items)]
      [{:biff.fx/tx feed-tx}
       {:biff.fx/s3 s3-inputs}
       {:biff.fx/tx [(into [:put-docs :item] items)]}])))

(def module
  {:tasks [{:task     #'sync-all-feeds!
            :schedule (lib.core/every-n-minutes 30)}]
   :queues [{:id        :work.subscription/sync-feed
             :consumer  #'sync-feed!
             :n-threads 4}]})
