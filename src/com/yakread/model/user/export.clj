(ns com.yakread.model.user.export 
  (:require
   [clojure.data.csv :as csv]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
   [rum.core :as rum]))

(defn- generate-opml [urls]
  (str
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
   (rum/render-static-markup
    [:opml {:version "1.0"} "\n"
     "  " [:head [:title "Yakread RSS subscriptions"]] "\n"
     "  " [:body "\n"
           (for [url urls]
             [:<> "    " [:outline {:type "rss" :xmlUrl url}] "\n"])]])))

(defresolver feed-subs [{:keys [biff/conn]} {:keys [user/id]}]
  {::pco/input [:user/id]}
  {:user.export/feed-subs
   (->> (biffx/q conn
                 {:select :feed/url
                  :from :sub
                  :join [:feed [:= :sub.feed/feed :feed._id]]
                  :where [:= :sub/user id]})
        (mapv :feed/url)
        sort
        generate-opml)})

;; TODO
;; - make this part of biffx/q
;; - pull syntax
;; - use schema + join-key to infer table, nest one vs. nest many
(defn- nest-one [join-key table columns]
  [[:nest_one {:select columns
               :from table
               :where [:= :xt/id join-key]}]
   join-key])

(defn- item-resolver [op-name output-key query-key csv-label]
  (pco/resolver
   op-name
   {::pco/input [:user/id]
    ::pco/output [output-key]}
   (fn [{:keys [biff/conn]} {:keys [user/id]}]
     {output-key
      (let [rows (->> (biffx/q conn
                               {:select [query-key
                                         :user-item/viewed-at
                                         (nest-one :user-item/item
                                                   :item
                                                   [:item/url :item/title :item/author-name])]
                                :from :user-item
                                :where [:and
                                        [:= :user-item/user id]
                                        [:is-not query-key nil]]})
                      (sort-by query-key #(compare %2 %1))
                      (mapv (juxt (comp :item/url :user-item/item)
                                  (comp :item/title :user-item/item)
                                  (comp :item/author-name :user-item/item)
                                  query-key
                                  :user-item/viewed-at))
                      (cons ["URL" "Title" "Author" csv-label "Read at"]))]
        (with-out-str
         (csv/write-csv *out* rows)))})))

(def bookmarks (item-resolver `bookmarks
                              :user.export/bookmarks
                              :user-item/bookmarked-at
                              "Bookmarked at"))

(def favorites (item-resolver `favorites
                              :user.export/favorites
                              :user-item/favorited-at
                              "Favorited at"))

(def module
  {:resolvers [feed-subs
               bookmarks
               favorites]})
