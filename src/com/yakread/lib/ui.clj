(ns com.yakread.lib.ui
  (:require
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.icons :as lib.icons]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.routes :as routes]
   [com.yakread.util.biff-staging :as biffs]
   [lambdaisland.uri :as uri]
   [ring.util.response :as ring-response]
   [rum.core :as rum]))

;;;; Utilities

(defn fmt-cents [cents]
  (format "$%.2f" (/ cents 100.0)))

(def ^:private chars* (mapv char (concat (range (int \a) (inc (int \z)))
                                         (range (int \A) (inc (int \Z))))))

(defn random-id []
  (apply str "_" (repeatedly 22 #(gen/rand-nth chars*))))

(def json cheshire/generate-string)

(defn- dissoc-ns [m ns-str]
  (into {}
        (remove #(= ns-str (namespace (key %))))
        m))

(defn dom-opts [{name* :name :as opts} & classes]
  (-> opts
      (update :class concat (flatten classes))
      (dissoc-ns "ui")
      (merge (when (or (keyword? name*) (vector? name*))
               {:name (pr-str name*)}))))

(def spinner-gif "/img/spinner2.gif")

(def interpunct " · ")

(defn pluralize [n label]
  (str n " " label (when (not= 1 n) "s")))

(defn dom-id [x]
  (str/replace (str x) #"[^a-zA-Z0-9-]" "_"))

(defn weserv [opts]
  (str (apply uri/assoc-query "https://images.weserv.nl/"
              (apply concat opts))))

;;;; Misc

(defn footer [{:keys [dark-mode show-recaptcha-message]}]
  [:.text-sm.text-center.mt-10.justify-self-end
   {:class (if dark-mode
             '[text-neut-100]
             '[text-neut-600])}
   [:div
    (biff/join
     interpunct
     (for [[href label] [[(href routes/about) "About"]
                         [(href routes/contact) "Contact"]
                         [(href routes/tos) "Terms of Service"]
                         [(href routes/privacy) "Privacy Policy"]]]
       [:a.underline {:href href :target "_blank"} label]))]
   (when show-recaptcha-message
     [:<>
      [:.h-3]
      [:div
       "This site is protected by reCAPTCHA and the Google "
       [:a.underline
        {:href "https://policies.google.com/privacy",
         :target "_blank"}
        "Privacy Policy"]
       " and "
       [:a.underline
        {:href "https://policies.google.com/terms",
         :target "_blank"}
        "Terms of Service"]
       " apply."]])])

(defn signup-error [params]
  (case (not-empty (:error params))
    nil nil
    "recaptcha" (str "You failed the recaptcha test. Try again, "
                     "and make sure you aren't blocking scripts from Google.")
    "invalid-email" "Invalid email. Try again with a different address."
    "send-failed" (str "We weren't able to send an email to that address. "
                       "If the problem persists, try another address.")
    "There was an error."))

(defn css-path []
  (if-some [last-modified (some-> (io/resource "public/css/main.css")
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str "/css/main.css?t=" last-modified)
    "/css/main.css"))

(defn base-head []
  [:<>
   [:link {:rel "stylesheet" :href (css-path)}]
   [:link {:rel "manifest", :href "/site.webmanifest"}]
   [:link {:rel "apple-touch-icon", :sizes "180x180", :href "/apple-touch-icon.png"}]
   [:link {:rel "icon", :type "image/png", :sizes "32x32", :href "/favicon-32x32.png"}]
   [:link {:rel "icon", :type "image/png", :sizes "16x16", :href "/favicon-16x16.png"}]
   [:link {:rel "mask-icon", :href "/safari-pinned-tab.svg", :color "#5bbad5"}]
   [:meta {:name "msapplication-TileColor", :content "#da532c"}]
   [:meta {:name "theme-color", :content "#222222"}]] )

(defn lazy-load
  ([href] (lazy-load {} href))
  ([opts href]
   [:.flex.justify-center
    (merge {:hx-get href
            :hx-trigger "intersect once"
            :hx-swap "outerHTML"}
           opts)
    [:img.h-10 {:src spinner-gif}]]))

(defn lazy-load-spaced [href]
  [:.flex.justify-center.items-center.grow
   {:hx-get href
    :hx-trigger "intersect once"
    :hx-swap "outerHTML"}
   [:img.h-10 {:src spinner-gif}]])

(defn callout [{:ui/keys [icon] type* :ui/type :as opts} & contents]
  [:div (dom-opts opts
                  '[;border-l-4
                    p-4
                    text-neut-800
                    flex items-start gap-3]
                  (case type*
                    :info 'bg-tealv-75
                    :error 'bg-redv-50))
   (when-some [icon (cond
                      (contains? opts :ui/icon) icon
                      (= type* :info) "circle-info")]
     (lib.icons/base icon {:class '[w-5 pt-1 flex-shrink-0]}))
   contents])

(defn banner [{:keys [ui/kind] :as opts} & content]
  [:div (dom-opts opts
                  '[p-3
                    border-b
                    text-neut-800]
                  (case kind
                    :success '[bg-tealv-50
                               border-tealv-100]
                    :info    '[bg-sky-50
                               border-sky-100]
                    :error   '[bg-redv-50
                               border-redv-100]
                    :warning '[bg-yellv-50
                               border-yellv-100]))
   content])

(defn banner-success [& content]
  (banner {:ui/kind :success} content))

(defn banner-warning [& content]
  (banner {:ui/kind :warning} content))

(defn banner-error [& content]
  (banner {:ui/kind :error} content))

(defn confirm-unsub-msg [title]
  (str "Unsubscribe from "
       (-> (or title "this publication") str/trim (str/replace #"\s+" " "))
       "?"))

(defn muted-link [opts & body]
  [:a.text-neut-600.underline opts body])

(defn web-link [opts & body]
  [:a.text-blue-600.hover:underline opts body])

;;;; Buttons

(defn button [{:ui/keys [size icon] type_ :ui/type :as opts} & contents]
  [(if (contains? opts :href) :a :button)
   (dom-opts opts
             '[inter font-medium
               disabled:opacity-70]
             (when (not= type_ :link)
               '[rounded-md
                 "min-w-[5rem]"])
             (case type_
               :primary '[bg-tealv-500 hover:bg-tealv-600 disabled:hover:bg-tealv-500
                          text-neut-50]
               :secondary '[border
                            text-white
                            bg-neut-500 hover:bg-neut-400 disabled:hover:bg-neut-500]
               :danger '[bg-redv-700 hover:bg-redv-800 disabled:bg-redv-200 disabled:hover:bg-redv-200
                         text-neut-50 disabled:text-white]
               :link   '[text-blue-600]
               :muted-link '[text-gray-600]
               '[bg-neut-700 hover:bg-neut-800 disabled:hover:bg-neut-700
                 text-neut-50])
             (case size
               :small '[text-sm
                        px-3 py-1]
               :large '[max-sm:text-sm
                        px-3 py-2]
               '[text-sm
                 px-3 py-2]))
   (when icon
     [:<>
      (lib.icons/base icon {:class "w-4 h-4"})
      " "])
   contents])

(defn form-button [{:keys [ui/form-opts] :as opts} & content]
  (biff/form (dom-opts form-opts)
    (button opts content)))

(defn overflow-button [{:keys [href] :as opts} & contents]
  [(if href :a :button)
   (update opts
           :class
           concat
           '[block
             w-full
             px-3 py-2
             hover:bg-neut-75
             first:rounded-t last:rounded-b
             inter text-sm
             whitespace-nowrap text-left])
   contents])

(defn overflow-menu [{:ui/keys [direction icon rounded hover-shade]
                      :or {direction :down
                           icon "ellipsis-vertical-regular"
                           hover-shade :regular}
                      :as opts}
                     & contents]
  (let [open (random-id)]
    [:.relative.flex.items-center
     {:data-signals (json {open false})
      :data-on-click__outside (str "$" open " = false")
      :class [(case direction
                :up "translate-y-[-100%]"
                :down 'translate-y-full)]}
     (cond->> (list [:button (-> opts
                                 (dom-opts '[flex flex-none
                                             px-1 py-2
                                             h-full
                                             text-neut-600]
                                           (case hover-shade
                                             :regular 'hover:bg-neut-50
                                             :dark 'hover:bg-neut-200)
                                           (when rounded
                                             '[rounded-lg])
                                           (case direction
                                             :up 'translate-y-full
                                             :down "translate-y-[-100%]"))
                                 (merge {:data-on-click (str "$" open " = !$" open)}))
                     (lib.icons/base icon
                                     {:class '[w-8
                                               h-5
                                               flex-shrink-0]})]
                    [:div {:data-show (str "$" open)
                           :class (concat
                                   '[dropdown
                                     rounded
                                     absolute right-0
                                     bg-white
                                     border border-neut-300
                                     shadow-sm]
                                   (if (= direction :down)
                                     '[top-0 mt-2]
                                     '[bottom-0 mb-2]))}
                     contents])
       (= direction :up) reverse)]))

(defn confirmed-submit
  ([{:keys [ui/submitted] :as opts} content confirmed-content]
   (if submitted
     (button {:type "submit" :_ "init wait 1.5s then put 'Save' into me"} confirmed-content)
     (button {:type "submit"} content)))
  ([opts]
   (confirmed-submit opts "Save" "Saved 🗸")))

(defn pill [{:keys [ui/label href] :as opts}]
  [(if href :a :button)
   (dom-opts opts
             '[py-1 px-3 rounded
               "data-[active=true]:bg-neut-800"
               "data-[active=true]:text-white"
               "data-[active=false]:underline"
               "data-[active=false]:text-neut-800"])
   label])

;;;; Inputs

(defn- text-input* [element {:ui/keys [size] type_ :type :as opts}]
  [element
   (dom-opts (merge (when (= element :textarea)
                      {:rows 4})
                    opts)
             '[w-full
               rounded-md
               shadow-inner
               border-0
               ring-1 ring-inset ring-neut-100
               text-black inter leading-6
               bg-neut-50 disabled:opacity-70]
             (if (= type_ "file")
               '["file:py-[8px]" file:px-3
                 file:border-0
                 file:bg-neut-100 file:text-neut-800 text-neut-600
                 focus:outline-tealv-600]
               '[focus:ring-inset
                 focus:ring-tealv-600
                 py-2])
             (case size
               :large nil
               '[text-sm]))])

(defn text-input [opts]
  (text-input* :input (merge {:type "text"} opts)))

(defn textarea [opts]
  (text-input* :textarea opts))

(defn overlay-text-input [{:ui/keys [icon postfix size input-type]
                           :or {input-type :text}
                           :as opts}]
  [:.relative
   [:div {:class '[absolute
                   inset-0
                   flex items-center
                   pointer-events-none]}
    (when icon
      (lib.icons/base icon {:class '[size-4
                                     ml-4
                                     text-stone-600]}))
    [:.grow]
    [:div {:class (concat
                   '[h-full
                     text-neut-600
                     flex items-center
                     p-3]
                   (case size
                     :large '[max-sm:text-sm]
                     '[text-sm]))}
     postfix]]
   ((case input-type
      :text text-input
      :textarea textarea)
    (cond-> opts
      icon (update :class concat '[pl-10])))])

(def label-text-classes
  '[inter font-medium tracking-wide
    text-sm
    text-neut-900])

(defn input-label [opts & contents]
  [:label.block.mb-2 opts
   [:div {:class label-text-classes}
    contents]])

(defn input-description [& contents]
  (into [:.text-sm.text-gray-600.mt-1] contents))

(defn input-error [& contents]
  (into [:.text-sm.text-redv-500.mt-1] contents))

(defn form-input [{name_ :name :as opts}]
  (let [{:ui/keys [label error description
                   size indicator-id
                   submit-opts submit-text
                   input-type]
         :keys [id required] :as opts} (merge {:id name_} opts)
        input (overlay-text-input opts)
        submit-button (button (-> {:type "submit"
                                   :ui/size size}
                                  (merge submit-opts)
                                  (update :class concat '[leading-6]))
                        submit-text)]
    [:div.w-full
     (when label
       (input-label
        {:for id
         :required required}
        [:.flex.items-center.gap-2
         label
         (when indicator-id
           [:img.h-5.htmx-indicator.hidden
            {:id indicator-id
             :src spinner-gif}])]))


     (cond
       (not submit-text)
       input

       (= input-type :textarea)
       [:<>
        input
        [:.h-2]
        [:.flex.justify-end submit-button]]

       :else
       [:.flex.gap-3.w-full
        [:.grow input]
        [:.flex-shrink-0
         submit-button]])
     (case size
       :large [:.h-1]
       nil)
     ;; TODO handle case when error/description is set and input-type is :textarea
     (some->> error input-error)
     (some->> description input-description)]))

(defn checkbox [{:ui/keys [label size label-position]
                 :as opts}]
  [:label (dom-opts {}
                    '[flex gap-2 cursor-pointer]
                    (if (= label-position :above)
                      [label-text-classes
                       'flex-col-reverse]
                      '[items-center
                        inter text-neut-800])
                    (when (not= size :large)
                      '[text-sm]))
   [:input (dom-opts (merge {:type "checkbox"} opts)
                     '[text-tealv-500
                       "border border-neut-300"
                       form-checkbox
                       focus:ring-1 focus:ring-neut-300]
                     (when (= size :large)
                       '[size-6]))]
   [:span label]])

(defn select
  [{:ui/keys [options default] :as opts}]
  [:select (dom-opts opts
                     '[appearance-none
                       ring-1 ring-inset ring-neut-100 focus:ring-inset focus:ring-tealv-600
                       cursor-pointer
                       border border-0 rounded border-gray-300
                       py-1 pl-2 pr-10
                       bg-gray-50 text-black
                       inter text-sm leading-tight])
   (for [{:keys [label value]} options]
     [:option.cursor-pointer
      {:value value
       :selected (when (= value default) "selected")}
      label])])

;;;; Layout

(defn page-header [& {:keys [title subtitle add-href back-href actions no-margin]}]
  [:div.max-sm:px-4
   {:class (when-not no-margin
             '[mb-8])}
   (when back-href
     [:a {:href back-href
          :class '[text-neut-600
                   hover:text-neut-900
                   mb-2
                   flex
                   items-center
                   gap-1
                   text-sm
                   inter
                   font-medium
                   w-fit]}
      (lib.icons/default chevron-left-regular w-3 h-3)
      "Back"])
   [:.flex.items-center.gap-4
    [:div
     [:h2.font-bold.text-2xl title]
     (when subtitle
       [:.break-all subtitle])]
    [:.grow]
    actions
    (when add-href
      [:a {:href add-href
           :class '[text-tealv-500
                    hover:text-tealv-600
                    bg-neut-50
                    rounded-full]}
       (lib.icons/base "circle-plus-solid"
                       {:class '[h-8
                                 w-8
                                 md:h-10
                                 md:w-10]})])]])

(defn page-well [& contents]
  [:div {:class '[bg-white
                  p-4
                  sm:rounded shadow
                  flex flex-col gap-10
                  max-w-screen-sm]}
   contents])

(defn wide-page-well [& contents]
  [:div {:class '[bg-white
                  p-4
                  sm:rounded shadow
                  flex flex-col gap-10]}
   contents])

(defn section [{:keys [title]} & contents]
  [:div
   (when title
     [:<>
      [:h3.font-bold.text-lg title]
      [:.h-3]
      [:hr.border-neut-100]
      [:.h-6]])
   [:.flex.flex-col.gap-6
    contents]])

(defn modal [{:keys [open title]} & content]
  [:div {:class '[fixed inset-0
                  flex flex-col items-center
                  overflow-y-auto
                  p-4
                  z-30]
         :aria-modal "true",
         :role "dialog"
         :data-signals (json {open false})
         :data-show (str "$" open)}
   [:.grow]
   [:div.fixed.inset-0.bg-gray-500.bg-opacity-75
    {:aria-hidden "true"
     :data-on-click (str "$" open " = false")}]
   [:.bg-white.rounded.max-w-screen-sm.w-full.transform
    [:.flex.h-12.border-b.items-center.p-3
     [:h3.font-bold.text-lg title]
     [:.grow]
     [:button.flex {:type "button"
                    :data-on-click (str "$" open " = false")}
      (lib.icons/base "xmark-regular" {:class "w-6 h-6"})]]
    content]
   [:.grow]])

(defn card-grid-card [{:keys [index total]} card]
  [:.yak-card
   {:class "[&>*]:size-full"
    :style {:z-index (when (and index total)
                       (- (+ 10 total) index))}}
   card])

(defn card-grid* [{:ui/keys [cols] :as opts} children]
  [:div (dom-opts opts
                  '["grid grid-cols-1 sm:grid-cols-2"
                    gap-4]
                  (case cols
                    4 "xl:grid-cols-3 2xl:grid-cols-4"
                    5 "lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5"))
   children])

(defn card-grid [opts cards]
  (let [total (count cards)]
    (card-grid*
     opts
     (for [[i card] (map-indexed vector cards)]
       (card-grid-card {:index i :total total} card)))))

(defn card-list [& content]
  [:div {:class '[flex flex-col gap-6
                  max-w-screen-sm]}
   content])

(defn table [headers rows]
  [:table
   [:thead.text-left
    [:tr
     (for [header headers]
       [:th header])]]
   [:tbody
    (for [row rows]
      [:tr.even:bg-neut-50
       (for [cell row]
         [:td cell])])]])

;;;; Composites

(defn empty-page-state [{:keys [icons text btn-label btn-href]}]
  [:.flex.flex-col.h-full
   [:.grow]
   [:.flex.justify-center.gap-8.text-neut-800
    (for [icon icons]
      (lib.icons/base icon {:class '[h-12 w-12]}))]
   [:.h-4]
   [:p.text-center.text-neut-800.mb-0.text-lg
    text]
   [:.h-6]
   [:.flex.justify-center.gap-4
    (button {:href btn-href :ui/type :primary} btn-label)]
   [:.grow]
   [:.grow]])

(defn base-page [ctx & body]
  (biff/base-html
   (-> {:base/title "Yakread"
        :base/description "Read stuff that matters."
        :base/image "https://platypub.sfo3.cdn.digitaloceanspaces.com/270d320a-d9d8-4cdf-bbed-2078ca595b16"
        :base/lang "en-US"}
       (merge ctx)
       (update :base/head conj (base-head)))
   body))

(defn plain-page [ctx & content]
  (base-page
   ctx
   [:.p-4.bg-neut-900.w-full
    [:.flex.mx-auto.max-w-screen-sm
     [:a {:href "/"}
      [:img {:class '["h-[27px]"
                      opacity-85]
             :src "/img/logo-navbar.svg"
             :height "27px"}]]
     [:.grow]]]
   [:.bg-neut-900.flex-grow.p-3.flex.flex-col.text-white
    [:.flex.flex-col.max-w-screen-sm.mx-auto.w-full.flex-grow.text-center.text-lg
     [:.flex-grow]
     content
     [:.flex-grow]
     [:.flex-grow]
     (footer {:dark-mode true :show-recaptcha-message false})
     [:.h-4]]]))

(defn not-found-html []
  (rum/render-static-markup
   (plain-page
    {}
    [:div.text-white "Page not found. Try the "
     [:a.underline {:href "/"} "home page"] " instead."])))

(defn internal-server-error-html []
  (rum/render-static-markup
   (plain-page
    {}
    [:div
     "There was an unexpected error. If the problem persists, "
     [:a.underline {:href (href routes/contact)} "send me an email"]
     "."])))

(defn on-error [{:keys [status]}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (case status
           404 (not-found-html)
           400 (not-found-html)
           500 (internal-server-error-html)
           405 "<h1>Method Not Allowed</h1>"
           406 "<h1>Not Acceptable</h1>")})

(defn redirect-on-load [{:keys [redirect-url beacon-url]}]
  [:html
   [:body
    [:div#params {:data-redirect-url redirect-url}]
    (biff/form {:action beacon-url})
    [:script (biffs/unsafe
              "params = document.querySelector('#params');"
              "const form = document.querySelector('form');"
              "const data = new FormData(form);"
              "const blob = new Blob([new URLSearchParams([...data])], {"
              "  type: 'application/x-www-form-urlencoded'"
              "});"
              "navigator.sendBeacon(form.action, blob);"
              "window.location = params.getAttribute('data-redirect-url');")]]])
