(ns com.yakread.model.user.export 
  (:require
   [com.biffweb :refer [q]]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
   [rum.core :as rum]
   [clojure.data.csv :as csv]))

(defn- generate-opml [urls]
  (str
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
   (rum/render-static-markup
    [:opml {:version "1.0"} "\n"
     "  " [:head [:title "Yakread RSS subscriptions"]] "\n"
     "  " [:body "\n"
           (for [url urls]
             [:<> "    " [:outline {:type "rss" :xmlUrl url}] "\n"])]])))

(defresolver feed-subs [{:keys [biff/db]} {:keys [user/id]}]
  {::pco/input [:user/id]}
  {:user.export/feed-subs
   (->> (q db
           '{:find url
             :in [user]
             :where [[sub :sub/user user]
                     [sub :sub.feed/feed feed]
                     [feed :feed/url url]]}
           id)
        sort
        generate-opml)})

(defn- item-resolver [op-name output-key query-key csv-label]
  (pco/resolver
   op-name
   {::pco/input [:user/id]
    ::pco/output [output-key]}
   (fn [{:keys [biff/db]} {:keys [user/id]}]
     {output-key
      (let [rows (->> (q db
                         {:find (list 'pull
                                      'usit
                                      [query-key
                                       :user-item/viewed-at
                                       {:user-item/item
                                        [:item/url
                                         :item/title
                                         :item/author-name]}])
                           :in '[user]
                           :timeout 999999
                           :where [['usit :user-item/user 'user]
                                   ['usit query-key]]}
                         id)
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
