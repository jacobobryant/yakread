(ns com.yakread.app.home
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.icons :as lib.icons]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]
   [xtdb.api :as xt]))

(def home-head
  [[:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
   [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
   [:link {:href "https://fonts.googleapis.com/css2?family=Montserrat:wght@700&display=swap"
           :rel "stylesheet"}]
   [:script {:src "https://www.google.com/recaptcha/api.js" :async "async" :defer "defer"}]])

(def signin-head
  [[:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
   [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
   [:link {:href "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap"
           :rel "stylesheet"}]
   [:script {:src "https://www.google.com/recaptcha/api.js" :async "async" :defer "defer"}]])

(defn maybe-error [error-code code->message]
  (when error-code
    (ui/banner
     {:ui/kind :error}
     (get code->message error-code "There was an error."))))

(defn- signin-well [& content]
  [:div {:class '[sm:bg-white
                  "max-w-[26rem]"
                  mx-auto
                  p-8
                  pb-6
                  max-sm:p-4
                  sm:rounded-md
                  w-full]}
   [:img.mx-auto {:src "/img/logo-signin.svg"
                  :alt "Yakread logo"
                  :style {:max-width "200px"}}]
   [:.h-8]
   content])

(defn base-signin-page [{:keys [::errors params] :as ctx} & content]
  (ui/base-page
   (assoc ctx :base/head signin-head)
   (maybe-error (:error params) errors)
   [:div {:class (concat '[bg-neut-75
                           flex
                           flex-col
                           flex-grow
                           overflow-y-auto
                           sm:p-3
                           text-neut-900])}
    [:.h-8]
    [:.sm:flex-grow]
    (signin-well content)
    [:.flex-grow]
    [:.flex-grow]
    [:.sm:h-8.flex-shrink-0]
    (ui/footer {:show-recaptcha-message true})
    [:.h-4]]))

(def signin-button-classes
  '[g-recaptcha
    bg-tealv-500
    hover:bg-tealv-600
    text-center
    py-2
    px-4
    rounded-md
    text-white
    block
    w-full
    inter
    font-medium])

(def signin-page-route
  ["/signin"
   {:name ::signin-page-route
    :get
    (fn signin-page [{:keys [params recaptcha/site-key] :as ctx}]
      (base-signin-page
       (assoc ctx
              :base/title "Sign in | Yakread"
              ::errors {"recaptcha" (str "You failed the recaptcha test. Try again, "
                                         "and make sure you aren't blocking scripts from Google.")
                        "invalid-email" "Invalid email. Try again with a different address."
                        "send-failed" (str "We weren't able to send an email to that address. "
                                           "If the problem persists, try another address.")
                        "invalid-link" "Invalid or expired link. Sign in to get a new link."
                        "not-signed-in" "You must be signed in to view that page."})
       (biff/form
         {:id "signin-form"
          :action "/auth/send-code"
          :hidden {:on-error (href signin-page-route)
                   :redirect "/"
                   :ewt (:ewt params)}}
         (biff/recaptcha-callback "onSubscribe" "signin-form")
         [:.flex-grow (ui/text-input {:name "email"
                                      :placeholder "Enter your email address"
                                      :ui/size :large})]
         [:.h-3]
         [:button {:class signin-button-classes
                   :type "submit"
                   :data-sitekey site-key
                   :data-callback "onSubscribe"
                   :data-action "subscribe"}
          "Sign in"])
       [:.h-5]
       [:.text-center.leading-none "Don't have an account yet? " [:a.underline {:href "/"} "Sign up"] "."]))}])

(def verify-code-route
  ["/verify-code"
   {:name ::verify-code
    :get
    (fn verify-code [{:keys [biff/domain recaptcha/site-key params] :as ctx}]
      (base-signin-page
       (assoc ctx
              :base/title "Sign in | Yakread"
              ::errors {"invalid-code" "Invalid code."})
       (biff/form
         {:id "verify-code-form"
          :action "/auth/verify-code"
          :hidden {:email (:email params)}}
         (biff/recaptcha-callback "verifyCode" "verify-code-form")
         [:label.block {:for "code"} "Enter the 6-digit code we sent to "
          ;; Some people try to sign in using their @yakread.com address as a username.
          (if (some-> (:email params) str/lower-case (str/includes? (str "@" domain)))
            "your email address"
            (:email params)) ":"]
         [:.h-3]
         [:.flex-grow (ui/text-input {:name "code" :ui/size :large})]
         [:.h-3]
         [:button {:class signin-button-classes
                   :type "submit"
                   :data-sitekey site-key
                   :data-callback "verifyCode"}
          "Verify code"])
       [:.h-5]
       (biff/form
         {:action "/auth/send-code"
          :id "send-again"
          :hidden {:email (:email params)
                   :on-error (href signin-page-route)}}
         (biff/recaptcha-callback "sendAgain" "send-again")
         [:.flex.justify-center.items-center.text-neut-700
          [:button.text-center.underline.g-recaptcha
           {:type "submit"
            :data-sitekey site-key
            :data-callback "sendAgain"}
           "Send another code"]
          [:.px-2 ui/interpunct]
          [:a.underline {:href "/"} "Home"]])))}])

(def navbar
  [:.p-4.bg-neut-900.w-full
   [:.flex.mx-auto.max-w-screen-lg
    [:img {:class '["h-[27px]"
                    opacity-85]
           :src "/img/logo-navbar.svg"
           :height "27px"}]
    [:.grow]
    [:a.text-white.hover:underline {:href (href signin-page-route)} "Sign in"]]])

(def email-input-classes
  '[w-full
    shadow-md
    leading-6
    border-0
    text-black
    disabled:opacity-70
    bg-neut-50
    inter
    focus:ring-inset
    focus:ring-tealv-600
    py-2
    sm:py-1.5
    text-lg
    sm:text-xl
    grow
    rounded])

(def submit-classes
  '[g-recaptcha
    px-5
    py-2
    bg-tealv-500
    hover:bg-tealv-600
    text-white
    inter
    font-semibold
    leading-6
    disabled:opacity-70
    disabled:bg-tealv-500
    text-lg
    sm:text-xl
    whitespace-nowrap
    shadow-inner
    rounded])

(defn signup-section [{:keys [recaptcha/site-key] :as ctx}]
  [:div {:class '["max-w-[625px]"]}
   [:h1 {:class '[leading-none
                  text-3xl
                  sm:text-5xl
                  montserrat-bold]}
    "Read stuff that " [:span.underline "matters"] "."]
   [:.h-4]
   [:.text-lg.sm:text-2xl
    "Get a selection of long-tail essays, blog posts, and newsletters in your inbox daily. "
    "Curated with love (and AI)."]
   [:.h-5]
   (biff/form
    {:id "signup-form"
     :action "/auth/send-link"
     :hidden {:on-error "/"
              :redirect (href routes/for-you)}}
    (biff/recaptcha-callback "onSubscribe" "signup-form")
    [:.flex.max-sm:flex-col.w-full
     [:input {:type "email"
              :id "email"
              :name "email"
              :placeholder "Enter your email"
              :class email-input-classes}]
     [:.size-3]
     [:button {:type "submit"
               :class submit-classes
               :data-sitekey site-key
               :data-callback "onSubscribe"
               :data-action "subscribe"}
      "Join the herd"]]
    (when-some [error (ui/signup-error ctx)]
      [:.text-sm.text-redv-200.mt-1 error]))])

(defn feature-card [& {:keys [icon label content]}]
  [:.bg-white.p-3.shadow-md.border-l-4.border-tealv-500
   [:div.font-bold.text-stone-800
    (lib.icons/base icon {:class '[size-4]})
    [:span.align-middle " " label]]
   [:.h-1]
   [:div.text-stone-800 content]])

(def vert-sep
  [:.h-8.sm:h-10])

(def features-section
  [:.flex.flex-col.items-center
   [:.text-lg.sm:text-xl.text-center
    "Then make your daily reading digest personal:"]
   vert-sep
   [:.grid.md:grid-cols-3.gap-4.max-w-screen-lg.mx-auto
    (feature-card
     :label "Subscriptions"
     :icon "rss-regular"
     :content "Subscribe to your favorite newsletters and RSS feeds.")
    (feature-card
     :label "Read it later"
     :icon "bookmark-regular"
     :content "Save articles from around the web and read them later.")
    (feature-card
     :label "In case you missed it"
     :icon "sparkles-regular"
     :content "Yakread resurfaces your unread posts so you don't miss the good stuff.")]
   vert-sep
   [:a.underline.text-lg.text-center {:href (href routes/for-you)} "Take a look around >>"]])

(def testimonial
  [:.bg-white.sm:rounded.p-4.shadow-md
   [:.text-center.max-w-screen-sm.text-lg.sm:text-xl
    "“Yakread helps me spend more time reading long-form content instead of "
    "feeling like I'm wasting time scrolling through social media.”"]
   [:.h-3]
   [:.flex.gap-3.justify-center.items-center
    [:img.rounded-full {:src (ui/weserv {:url "https://cdn.findka.com/profile.jpg" :w 90 :h 90})
                        :class '["w-[45px]"
                                 flex-shrink-0]}]
    [:div.text-sm.leading-tight
     [:.font-semibold "Jacob O'Bryant"]
     [:.text-neut-800 "Creator of Yakread 😉"]]]])

(def home-page-route
  ["/"
   {:name ::home-page-route
    :get
    (fn home-page [{:keys [session biff/db query-string params yakread/analytics-snippet] :as ctx}]
      (if (and (not (:noredirect params))
               (some->> (:uid session) (xt/entity db)))
        {:status 303
         :headers {"location" (str (href routes/for-you) (when query-string "?") query-string)}}
        (ui/base-page
         (assoc ctx :base/head home-head)
         (when (:thesample params)
           (ui/banner {:ui/kind :warning}
                      "The Sample has been shut down and merged into Yakread. "
                      [:a.underline {:href "https://obryant.dev/p/shutting-down-the-sample/"}
                       "Read more"] "."))
         [:.bg-neut-75.grow.flex.flex-col.items-center
          navbar
          [:.h-6.sm:h-12.grow.bg-neut-900.w-full]
          [:.px-4.bg-neut-900.text-neut-50.w-full.flex.justify-center
           (signup-section ctx)]
          [:.h-8.sm:h-16.bg-neut-900.w-full]
          vert-sep
          [:.px-4 features-section]
          vert-sep
          [:.sm:px-4 testimonial]
          [:.h-6.grow]
          (ui/footer {:show-recaptcha-message true})
          [:.h-4]]
         (when-not (clojure.string/blank? analytics-snippet)
           [:div (biff/unsafe analytics-snippet)])))}])

(def link-sent-route
  ["/link-sent"
   {:name ::link-sent-route
    :get
    (fn link-sent [{:keys [biff/domain params] :as ctx}]
      (ui/plain-page
       (assoc ctx :base/title "Sign up | Yakread")
       [:.text-2xl.font-bold "Check your inbox"]
       [:.h-3]
       [:.text-lg
        (if (and (:email params)
                 (not (str/includes? (str/lower-case (:email params)) (str "@" domain))))
          [:<> "We've sent a sign-in link to " (:email params) "."]
          "We've sent you a sign-in link.")]))}])


(defn info-route [href config-key]
  [href
   {:get (fn [{url config-key}]
           {:status 303
            :headers {"location" url}})}])

(def about-route (info-route "/about" :yakread/about-url))
(def contact-route (info-route "/contact" :yakread/contact-url))
(def tos-route (info-route "/tos" :yakread/tos-url))
(def privacy-route (info-route "/privacy" :yakread/privacy-url))

(def module
  {:routes [home-page-route
            link-sent-route
            signin-page-route
            verify-code-route
            about-route
            contact-route
            tos-route
            privacy-route]})
