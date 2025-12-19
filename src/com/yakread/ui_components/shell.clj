(ns com.yakread.ui-components.shell
  (:require
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.icons :as lib.icons]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]
   [ring.middleware.anti-forgery :as csrf]
   [ring.util.response :as ring-response]))

(defn css-path []
  (if-some [last-modified (some-> (io/resource "public/css/main.css")
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str "/css/main.css?t=" last-modified)
    "/css/main.css"))

(defresolver pages [{:keys [reitit.core/match]} {{:keys [user/roles]} :user/current}]
  #::pco{:input [{(? :user/current) [(? :user/roles)]}]}
  {:app.shell/pages
   (let [route-ns (-> match :data :name namespace)]
     (->> (cond-> [#:app.shell.page{:route-sym routes/for-you
                                    :title "For you"
                                    :icon "house"}
                   #:app.shell.page{:route-sym routes/subs-page
                                    :title "Subscriptions"
                                    :icon "rss"}
                   #:app.shell.page{:route-sym routes/bookmarks-page
                                    :title "Read later"
                                    :icon "bookmark"}
                   #:app.shell.page{:route-sym routes/favorites-page
                                    :title "Favorites"
                                    :icon "star"}
                   #:app.shell.page{:route-sym routes/settings-page
                                    :title "Settings"
                                    :icon "gear"}
                   #:app.shell.page{:route-sym routes/advertise
                                    :title "Advertise"
                                    :icon "dollar-sign"}]
            (contains? roles :admin) (conj #:app.shell.page{:route-sym routes/admin
                                                            :title "Admin"
                                                            :icon "lock"}))
          (mapv (fn [{:keys [app.shell.page/route-sym] :as page}]
                  (merge page #:app.shell.page{:href (href route-sym)
                                               :active (str/starts-with? route-ns (namespace route-sym))})))))})

(defresolver app-head [{user :session/user}]
  #::pco{:input [{(? :session/user) [:xt/id
                                     (? :user/timezone*)]}]}
  {:app.shell/app-head
   [[:link {:rel "stylesheet" :href (css-path)}]
    [:script {:src "/vendor/cdn.jsdelivr.net/npm/htmx.org@2.0.5/dist/htmx.min.js"}]
    [:script {:src "/vendor/unpkg.com/idiomorph@0.7.3.js"
              :integrity "sha384-JcorokHTL/m+D6ZHe2+yFVQopVwZ+91GxAPDyEZ6/A/OEPGEx1+MeNSe2OGvoRS9"
              :crossorigin "anonymous"}]
    [:script {:src "/vendor/unpkg.com/idiomorph@0.7.3/dist/idiomorph-ext.min.js"
              :integrity "sha384-szktAZju9fwY15dZ6D2FKFN4eZoltuXiHStNDJWK9+FARrxJtquql828JzikODob"
              :crossorigin "anonymous"}]
    [:script {:src "/vendor/unpkg.com/hyperscript.org@0.9.14.js"}]
    [:script {:src "/vendor/cdnjs.cloudflare.com/ajax/libs/dompurify/3.2.6/purify.min.js"}]
    [:script {:src "/js/main.js"}]
    [:script {:type "module" :src "/vendor/cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js"}]
    [:link {:rel "manifest", :href "/site.webmanifest"}]
    [:link {:rel "apple-touch-icon", :sizes "180x180", :href "/apple-touch-icon.png"}]
    [:link {:rel "icon", :type "image/png", :sizes "32x32", :href "/favicon-32x32.png"}]
    [:link {:rel "icon", :type "image/png", :sizes "16x16", :href "/favicon-16x16.png"}]
    [:link {:rel "mask-icon", :href "/safari-pinned-tab.svg", :color "#5bbad5"}]
    [:meta {:name "msapplication-TileColor", :content "#da532c"}]
    [:meta {:name "theme-color", :content "#222222"}]
    (when (and user (not (:user/timezone* user)))
      [:script
       (biff/unsafe
        (str "set_timezone('" (href routes/set-timezone) "', '" csrf/*anti-forgery-token* "');"))])]})

(def default-metadata
  #:base{:title "Yakread"
         :lang "en-US"
         :description "Read stuff that matters."
         :image "https://platypub.sfo3.cdn.digitaloceanspaces.com/270d320a-d9d8-4cdf-bbed-2078ca595b16"
         :font-families ["Inter:wght@400;500;600;700"]})

(defresolver sidebar [{current-user :user/current :keys [app.shell/pages]}]
  #::pco{:input [{(? :user/current) [:user/email]}
                 :app.shell/pages]}
  {:app.shell/sidebar
   [:div {:id "sidebar"
          :class '[max-md:fixed
                   -left-64
                   transition-all
                   inset-y-0
                   whitespace-nowrap
                   "w-[16rem]"
                   md:w-fit
                   "lg:w-[16rem]"
                   flex-shrink-0
                   bg-neut-900
                   text-neut-50]
          :style {:z-index 100001}}
    [:.sticky.top-0.py-2.flex.flex-col.px-4.h-screen
     [:.h-4]
     [:a.self-center {:href "/"}
      [:img {:class '["h-[27px]"
                      opacity-85
                      pl-2
                      pr-4
                      lg:pr-6
                      mb-4
                      lg:mb-8
                      max-md:hidden]
             :src "/img/logo-navbar.svg"
             :height "27px"}]]
     [:div {:hx-boost "true"
            :class '[flex flex-col gap-1]}
      (for [{:app.shell.page/keys [href title icon active]} pages]
        [:a {:href href
             :class (concat (if active
                              '[bg-neut-800
                                text-white]
                              '[text-neut-200
                                hover:bg-neut-800
                                hover:text-white])
                            '[py-3
                              px-2
                              flex
                              items-center
                              rounded
                              font-semibold])}
         (lib.icons/base (str icon (if active "-solid" "-regular"))
                         {:class "w-[18px] h-[18px] relative top-[1px]"})
         [:.w-2]
         [:span.leading-none title]])]
     [:.grow]
     (when current-user
       [:.relative
        [:div#user-dropdown
         {:class '[dropdown
                   hidden
                   absolute
                   bg-neut-700
                   py-1
                   text-sm
                   w-full
                   border
                   border-neut-600
                   shadow
                   rounded
                   "bottom-[2.25rem]"]}
         (biff/form
           {:action "/auth/signout"
            :class "inline"
            :hx-push-url "true"}
           [:button {:type "submit"
                     :class '[hover:bg-neut-800
                              px-2
                              py-1
                              w-full]}
            "Sign out"])]
        [:button
         {:class '[flex
                   items-center
                   gap-2
                   text-sm
                   p-2
                   max-w-full]
          :_ "on click toggle .hidden on #user-dropdown then halt"}
         [:div
          {:class '[truncate]
           :style {:text-overflow "ellipsis"}}
          (:user/email current-user)]
         (lib.icons/base "chevron-down-solid"
                         {:class '[h-4
                                   w-4
                                   relative
                                   "top-[1px]"
                                   text-neut-300]})]])]]})

(def navbar
  [:div
   [:div {:class '[md:hidden
                   absolute
                   inset-0
                   bg-black
                   opacity-50
                   hidden
                   navbar-button]
          :style {:z-index 100000}
          :_ (str "on click toggle .sidebar-visible on #sidebar then "
                  "toggle .hidden on .navbar-button")}]
   [:div {:class '[md:hidden
                   px-4
                   sm:px-8
                   bg-neut-900
                   text-neut-50]}
    [:div {:class '[py-3
                    flex
                    items-center
                    max-w-screen-sm
                    mx-auto]}
     [:a {:href "/"}
      [:img {:class '["h-[25px]"
                      opacity-85]
             :src "/img/logo-navbar.svg"
             :height "25px"}]]
     [:.grow.h-1]
     (for [[icon class] [["bars-regular"]
                         ["xmark-regular" '[hidden]]]]
       [:button.navbar-button
        {:_ (str "on click toggle .sidebar-visible on #sidebar then "
                 "toggle .hidden on .navbar-button")
         :style {:z-index 100001}
         :class class}
        (lib.icons/base icon {:class '[w-6 h-6]})])]]])

(defresolver app-shell [ctx
                        {:app.shell/keys [app-head pages sidebar]
                         :keys [session/signed-in]}]
  #::pco{:input [:app.shell/app-head
                 :app.shell/sidebar
                 :app.shell/pages
                 :session/signed-in]}
  {:app.shell/app-shell
   (let [active-page (first (filterv :app.shell.page/active pages))]
     (fn [{:keys [title wide description banner]} & content]
       (biff/base-html
        (-> default-metadata
            (biff/assoc-some :base/title (str (or title (:app.shell.page/title active-page))
                                              " | Yakread")
                             :base/description description)
            (update :base/head concat app-head))
        (when-not signed-in
          (ui/banner {:ui/kind :success}
                     [:a.underline {:href "/"} "Create an account"]
                     " to get the full Yakread Experience™."))
        navbar
        [:.bg-neut-75.grow.flex
         {:hx-headers (cheshire/generate-string
                       {:x-csrf-token csrf/*anti-forgery-token*})
          :hx-ext "morph"}
         sidebar
         [:.grow.flex.flex-col
          banner
          [:div {:class (concat '[mx-auto sm:px-8
                                  py-4 sm:pt-8 sm:pb-6
                                  flex flex-col
                                  w-full grow
                                  justify-between]
                                (when-not wide
                                  '[max-w-screen-sm]))}
           [:.flex.flex-col.grow content]
           (ui/footer {:show-recaptcha-message (not signed-in)})]]])))})

(def module
  {:resolvers [app-shell
               app-head
               pages
               sidebar]})
