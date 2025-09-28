(ns com.yakread.app.admin.dashboard
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as sh]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [defget href]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.ui :as ui])
  (:import [java.time ZonedDateTime ZoneId]))

(defn past-30-days [now timezone]
  (let [today-zdt (ZonedDateTime/ofInstant now (ZoneId/of timezone))]
    (->> (iterate #(.minusDays % 1) today-zdt)
         (take 30)
         (mapv #(.toLocalDate %)))))

(declare page-route)

(defget page-content-route "/admin/dashboard/content"
  [{:admin/recent-users
    [:user/email
     :user/joined-at]}
   :admin/dau
   :admin/revenue]
  (fn [{:biff/keys [now queues]} {:admin/keys [recent-users dau revenue]}]
    (ui/wide-page-well
     [:.grid.xl:grid-cols-2.gap-8
      (ui/section
       {:title "Queues"}
       (ui/table
         ["Queue" "# jobs"]
         (->> (update-vals queues count)
              (sort-by second >))))

      (ui/section
       {:title "Recent signups"}
       (ui/table
         ["Email" "Joined"]
         (for [{:user/keys [email joined-at]} (sort-by :user/joined-at #(compare %2 %1) recent-users)]
           [email
            ;; TODO use config for timezone
            (lib.core/fmt-inst joined-at "yyyy-MM-dd hh:mm a" "America/Denver")])))

      (ui/section
       {:title "Daily metrics"}
       (ui/table
         ["Date" "DAU" "Revenue"]
         (for [date (past-30-days now "America/Denver")]
           [date
            (get dau date 0)
            (ui/fmt-cents (get revenue date 0))])))])))

(defget page-route "/admin/dashboard"
  [:app.shell/app-shell]
  (fn [ctx {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :dashboard)
     (ui/lazy-load (href page-content-route)))))

(defget logs-page "/admin/logs"
  []
  (fn [ctx _]
    [:pre
     (:out (sh/sh "journalctl" "-u" "app" "-n" "300"))]))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            page-content-route
            logs-page]})
