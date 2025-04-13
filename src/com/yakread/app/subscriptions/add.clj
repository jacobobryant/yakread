(ns com.yakread.app.subscriptions.add
  (:require [clojure.data.generators :as gen]
            [com.biffweb :as biff :refer [q <<-]]
            [com.yakread.lib.middleware :as lib.middle]
            [com.yakread.lib.pathom :as lib.pathom :refer [?]]
            [com.yakread.lib.route :refer [defget defpost href redirect]]
            [com.yakread.lib.rss :as lib.rss]
            [com.yakread.lib.ui :as ui]
            [com.yakread.lib.user :as lib.user]
            [com.yakread.routes :as routes]
            [xtdb.api :as xt]))

(let [response (fn [success username]
                 {:status 303
                  :headers (href `page-route (when-not success
                                               {:error "username-unavailable"
                                                :email-username username}))})]
  (defpost set-username
    :start
    (fn [{:keys [biff/db session params]}]
      (let [username (lib.user/normalize-email-username (:username params))]
        (cond
          (:user/email-username (xt/entity db (:uid session)))
          (response true nil)

          (or (empty? username)
              (biff/lookup-id db :user/email-username username))
          (response false (:username params))

          :else
          {:biff.pipe/next     [:biff.pipe/tx :end]
           :biff.pipe.tx/input [{:db/doc-type :user
                                 :db/op :update
                                 :xt/id (:uid session)
                                 :user/email-username [:db/unique username]}]
           :biff.pipe/catch    :biff.pipe/tx
           ::username          username})))

    :end
    (fn [{:keys [biff.pipe/exception ::username]}]
      (response (not exception) username))))

(defn- subscribe-feeds-tx [db user-id feed-urls]
  (let [url->feed (into {} (q db
                              '{:find [url feed]
                                :in [[url ...]]
                                :where [[feed :feed/url url]]}
                              feed-urls))
        new-urls  (remove url->feed feed-urls)]
    (for [[i url] (map-indexed vector feed-urls)
          :let [feed-id (get url->feed url (gen/uuid))]
          doc (concat [{:db/doc-type    :sub/feed
                        :db.op/upsert   {:sub/user user-id
                                         :sub.feed/feed feed-id}
                        :sub/created-at [:db/default :db/now]}]
                      (when-not (url->feed url)
                        [{:db/doc-type :feed
                          :db/op       :create
                          :xt/id       feed-id
                          :feed/url    [:db/unique url]}]))]
      doc)))

(defn- sync-rss-jobs [tx priority]
  (for [{:keys [feed/url xt/id]} tx
        :when url]
    {:biff.pipe/current :biff.pipe/queue
     :biff.pipe.queue/id :work.subscription/sync-feed
     :biff.pipe.queue/job {:feed/id id :biff/priority priority}}))

(defpost add-rss
  :start
  (fn [{{:keys [url]} :params}]
    {:biff.pipe/next       [:biff.pipe/http :add-urls]
     :biff.pipe.http/input {:url     (lib.rss/fix-url url)
                            :method  :get
                            :headers {"User-Agent" "https://yakread.com/"}}
     :biff.pipe/catch      :biff.pipe/http})

  :add-urls
  (fn [{:keys [biff/db session biff.pipe.http/output]}]
    (let [feed-urls (some->> output
                             lib.rss/parse-urls
                             (mapv :url)
                             (take 20)
                             vec)
          tx (subscribe-feeds-tx db (:uid session) feed-urls)]
      (if (empty? feed-urls)
        (redirect `page-route {:error "invalid-rss-feed" :url (:url output)})
        {:biff.pipe/next     (into [:biff.pipe/tx] (sync-rss-jobs tx 0))
         :biff.pipe.tx/input tx
         :biff.pipe.tx/retry :add-urls ; TODO implement
         :status             303
         :headers            {"Location" (href `page-route {:added-feeds (count feed-urls)})}}))))

(defpost add-opml
  :start
  (fn [{{{:keys [tempfile]} :opml} :params}]
    {:biff.pipe/next        [:biff.pipe/slurp :end]
     :biff.pipe.slurp/input tempfile})

  :end
  (fn [{:keys [biff/db session biff.pipe.slurp/output]}]
    (if-some [urls (not-empty (lib.rss/extract-opml-urls output))]
      (let [tx (subscribe-feeds-tx db (:uid session) urls)]
        {:biff.pipe/next     (into [:biff.pipe/tx] (sync-rss-jobs tx 5))
         :biff.pipe.tx/input tx
         :biff.pipe.tx/retry :end ; TODO implement
         :status             303
         :headers            {"Location" (href `page-route {:added-feeds (count urls)})}})
      (redirect `page-route {:error "invalid-opml-file"}))))

(defget page-route "/dev/subscriptions/add"
  [:app.shell/app-shell
   {(? :user/current) [:xt/id
                       (? :user/email-username)
                       (? :user/suggested-email-username)]}]

  (fn [{:keys [params] :as ctx}
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
           [:span.font-semibold username "@yakread.com"]]
          (biff/form
            {:action (href set-username)
             :hx-indicator "#username-indicator"}
            (ui/form-input
             {:ui/label "Username"
              :ui/description "You can subscribe to newsletters after you pick a username."
              :ui/postfix "@yakread.com"
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
                        {:href "javascript:window.location=\"https://yakread.com/subscriptions/add?url=\"+encodeURIComponent(document.location)"}
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
