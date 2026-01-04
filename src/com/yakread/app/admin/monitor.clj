(ns com.yakread.app.admin.monitor
  (:require
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [taoensso.tufte :as tufte]))

(declare page-route)

(fx/defroute-pathom page-route "/admin/monitor"
  [:app.shell/app-shell]

  :get
  (fn [{:keys [com.yakread/pstats]} {:keys [app.shell/app-shell]}]
    (let [days (take 7 (iterate #(.minusDays % 1) (java.time.LocalDate/now)))
          pstats (keep (comp @pstats str) days)
          pstats (if (empty? pstats)
                   nil
                   (reduce tufte/merge-pstats pstats))]
      [:pre
       (tufte/format-pstats
        (update @pstats :stats update-keys #(lib.content/truncate (str %) 50)))])))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route]})
