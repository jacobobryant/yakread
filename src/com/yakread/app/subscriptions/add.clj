(ns com.yakread.app.subscriptions.add
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.route :refer [href redirect]]
   [com.yakread.lib.rss :as lib.rss]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.user :as lib.user]
   [com.yakread.routes :as routes]))

(let [response (fn [success username]
                 {:status 303
                  :headers {"location" (href `page-route (when-not success
                                                           {:error "username-unavailable"
                                                            :email-username username}))}})]
  (fx/defroute set-username
    :post
    (fn [{:keys [biff/conn session params]}]
      (let [username (lib.user/normalize-email-username (:username params))]
        (cond
          (not-empty (biffx/q conn {:select 1
                                    :from :user
                                    :where [:and
                                            [:= :xt/id (:uid session)]
                                            [:is-not :user/email-username nil]]}))
          (response true nil)

          (or (empty? username)
              ;; TODO
              (not-empty
               (biffx/q conn
                        {:union
                         [{:select 1
                           :from :user
                           :where [:= :user/email-username username]}
                          {:select 1
                           :from :deleted-user
                           :where [:= :deleted-user/email-username-hash (lib.core/sha256 username)]}]})))
          (response false (:username params))

          :else
          (merge (response true username)
                 {:biff.fx/tx [[:patch-docs :user
                                {:xt/id (:uid session)
                                 :user/email-username username}]
                               (biffx/assert-unique :user {:user/email-username username})]}))))))

(defn- subscribe-feeds-tx [{:keys [biff/conn biff/now session]} feed-urls]
  (let [user-id (:uid session)
        results (biffx/q conn
                         {:select [[:feed._id :feed-id]
                                   :feed/url
                                   [:sub._id :sub-id]]
                          :from :feed
                          :left-join [:sub [:= :feed._id :sub.feed/feed]]
                          :where [:and
                                  [:in :feed/url feed-urls]
                                  [:or
                                   [:is :sub/user nil]
                                   [:= :sub/user user-id]]]})
        url->feed (into {} (map (juxt :feed/url :feed-id)) results)
        existing-sub-feed-ids (into #{}
                                    (comp (filter :sub-id)
                                          (map :feed-id))
                                    results)
        new-feed-docs (for [url feed-urls
                            :when (not (url->feed url))]
                        {:xt/id (gen/uuid)
                         :feed/url url})
        url->feed (into url->feed
                        (map (juxt :feed/url :xt/id))
                        new-feed-docs)
        new-sub-docs (for [feed (vals url->feed)
                           :when (not (existing-sub-feed-ids feed))]
                       {:xt/id (biffx/prefix-uuid user-id (gen/uuid))
                        :sub/user user-id
                        :sub/created-at now
                        :sub.feed/feed feed})]
    {:feed-ids (vals url->feed)
     :tx (concat
          (when (not-empty new-feed-docs)
            [{:assert [:not-exists
                       {:select [:inline 1]
                        :from :feed
                        :where [:in :feed/url (mapv :feed/url new-feed-docs)]
                        :limit [:inline 1]}]}
             (into [:put-docs :feed] new-feed-docs)])
          (when (not-empty new-sub-docs)
            [{:assert [:not-exists
                       {:select [:inline 1]
                        :from :sub
                        :where [:and
                                [:= :sub/user user-id]
                                [:in :sub.feed/feed (mapv :sub.feed/feed new-sub-docs)]]
                        :limit [:inline 1]}]}
             (into [:put-docs :sub] new-sub-docs)]))}))

(defn- sync-rss-jobs [feed-ids priority]
  {:jobs (for [id feed-ids]
           [:work.subscription/sync-feed
            {:feed/id id :biff/priority priority}])})

(fx/defroute add-rss
  :post
  (fn [{:keys [biff/base-url] {:keys [url]} :params}]
    {:biff.fx/http {:url     (lib.content/add-protocol url)
                    :method  :get
                    :headers {"User-Agent" base-url}
                    :throw-exceptions false}
     :biff.fx/next :add-urls})

  :add-urls
  (fn [{:keys [biff.fx/http] :as ctx}]
    (let [feed-urls (some->> http
                             lib.rss/parse-urls
                             (mapv :url)
                             (take 20)
                             vec)]
      (if (empty? feed-urls)
        (redirect `page-route {:error "invalid-rss-feed" :url (:url http)})
        (let [{:keys [tx feed-ids]} (subscribe-feeds-tx ctx feed-urls)]
          [{:biff.fx/tx tx}
           {:biff.fx/queue (sync-rss-jobs feed-ids 0)
            :status 303
            :headers {"Location" (href `page-route {:added-feeds (count feed-urls)})}}])))))


(fx/defroute add-opml
  :post
  (fn [{{{:keys [tempfile]} :opml} :params}]
    {:biff.fx/slurp tempfile
     :biff.fx/next  :end})

  :end
  (fn [{:keys [biff.fx/slurp] :as ctx}]
    (if-some [urls (not-empty (lib.rss/extract-opml-urls slurp))]
      (let [{:keys [tx feed-ids]} (subscribe-feeds-tx ctx urls)]
        [{:biff.fx/tx tx}
         {:biff.fx/queue (sync-rss-jobs feed-ids 5)
          :status        303
          :headers       {"Location" (href `page-route {:added-feeds (count urls)})}}])
      (redirect `page-route {:error "invalid-opml-file"}))))

(fx/defroute-pathom page-route "/subscriptions/add"
  [:app.shell/app-shell
   {(? :user/current) [:xt/id
                       (? :user/email-username)
                       (? :user/suggested-email-username)]}]

  :get
  (fn [{:biff/keys [domain base-url] :keys [params] :as ctx}
       {:keys [app.shell/app-shell] user :user/current}]
    (app-shell
     {:title "Add subscriptions"}
     (ui/page-header {:title     "Add subscriptions"
                      :back-href (href routes/subs-page)})
     [:fieldset.disabled:opacity-60
      {:disabled (when-not user "disabled")}
      (ui/page-well
       (ui/section
        {:title "Newsletters"}
        (if-some [username (:user/email-username user)]
          [:div "Sign up for newsletters with "
           [:span.font-semibold username "@" domain]]
          (biff/form
            {:action (href set-username)
             :hx-indicator "#username-indicator"}
            (ui/form-input
             {:ui/label "Username"
              :ui/description "You can subscribe to newsletters after you pick a username."
              :ui/postfix (str "@" domain)
              :ui/submit-text "Save"
              :ui/indicator-id "username-indicator"
              :ui/error (when (= (:error params) "username-unavailable")
                          "That username is unavailable.")
              :name "username"
              :value (or (:email-username params)
                         (:user/suggested-email-username user))
              :required true}))))
       (ui/section
        {:title "RSS feeds"}
        (when-some [n (:added-feeds params)]
          [:div {:class '[bg-tealv-50
                          border-l-4
                          border-tealv-200
                          p-3
                          text-neut-800]}
           "Subscribed to " (ui/pluralize n "feed") "."])
        (let [modal-open (ui/random-id)]
          (biff/form
            {:action (href add-rss)
             :hx-indicator (str "#" (ui/dom-id ::rss-indicator))}
            (ui/modal
             {:open modal-open
              :title "Subscribe via bookmarklet"}
             [:.p-4
              [:p "You can install the bookmarklet by dragging this link on to your browser toolbar or
                   bookmarks menu:"]
              [:p.my-6 [:a.text-xl.text-blue-600
                        {:href (str "javascript:window.location=\""
                                    base-url
                                    (href page-route)
                                    "?url=\"+encodeURIComponent(document.location)")}
                        "Subscribe | Yakread"]]
              [:p.mb-0 "Then click the bookmarklet to subscribe to the RSS feed for the current page."]])
            (ui/form-input
             {:ui/label "Website or feed URL" ; TODO spinner icon
              :ui/submit-text "Subscribe"
              :ui/description [:<> "You can also "
                               [:button.link {:type "button"
                                              :data-on-click (str "$" modal-open " = true")}
                                "subscribe via bookmarklet"] "."]
              :ui/indicator-id (ui/dom-id ::rss-indicator)
              :ui/error (when (= (:error params) "invalid-rss-feed")
                          "We weren't able to subscribe to that URL.")
              :name "url"
              :value (:url params)
              :required true})))
        (biff/form
          {:action (href add-opml)
           :hx-indicator (str "#" (ui/dom-id ::opml-indicator))
           :enctype "multipart/form-data"}
          (ui/form-input
           {:ui/label "OPML file"
            :ui/submit-text "Import"
            :ui/submit-opts {:class '["w-[92px]"]}
            :ui/indicator-id (ui/dom-id ::opml-indicator)
            :ui/error (when (= (:error params) "invalid-opml-file")
                        "We weren't able to import that file.")
            :name "opml"
            :type "file"
            :accept ".opml"
            :required true}))))])))

(def module
  {:routes [page-route
            ["" {:middleware [lib.middle/wrap-signed-in]}
             set-username
             add-rss
             add-opml]]})
