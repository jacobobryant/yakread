(ns com.yakread.lib.pathom
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.runner :as runner]
   [com.wsscode.pathom3.interface.eql :as eql]
   [com.yakread.lib.error :as lib.error]
   [taoensso.tufte :refer [p]]
   #_[xtdb.api :as xt]))

;; TODO try to do this without monkey patching
(alter-var-root #'runner/processor-exception (constantly (fn [_ ex] ex)))
(alter-var-root #'runner/report-resolver-error (constantly (fn [_ _ ex] (throw ex))))

(def ? pco/?)

(defn process [{:keys [::resolver-cache] :or {resolver-cache (atom {})} :as ctx} & args]
  (try
    (do #_#_with-open [db (if (:biff.index/indexes ctx)
                            (biff/open-db-with-index ctx)
                            (xt/open-db (:biff.xtdb/node ctx)))]
      (apply eql/process
             (-> ctx
                 #_(assoc :biff/db db :biff.db/basis (xt/db-basis db))
                 (runner/with-resolver-cache resolver-cache))
             args))
    (catch Exception e
      (if-some [unreachable (get-in (ex-data e)
                                    [:com.wsscode.pathom3.connect.planner/graph
                                     :com.wsscode.pathom3.connect.planner/unreachable-paths])]
        (throw (ex-info "Pathom can't find a path for the current entity"
                        {:pathom/unreachable-paths unreachable}))
        (throw e)))))

(defn handler [query f]
  (fn handler*
    ([request]
     (let [extra {:biff/now (java.time.Instant/now)
                  :biff/seed (long (* (rand) Long/MAX_VALUE))}
           request (merge request extra)]
       (binding [gen/*rnd* (java.util.Random. (:biff/seed extra))]
         (lib.error/with-ex-data (merge (lib.error/request-ex-data request) extra)
           (handler* request (process request query))))))
    ([request input]
     (f request input))))

(def plan-cache-kw :com.wsscode.pathom3.connect.planner/plan-cache*)

(defn wrap-debug [{:keys [config] f :resolve :as resolver}]
  (let [{:keys [biff/debug ::pco/op-name]} config]
    (when debug
      (println ":biff/debug set for" op-name))
    (let [f (if-not debug
              f
              (fn [ctx params]
                (if (or (not (fn? debug))
                        (debug ctx params))
                  (do
                    (println op-name)
                    (biff/pprint params)
                    (println "=>")
                    (let [ret (f (assoc ctx :biff/debug true) params)]
                      (biff/pprint ret)
                      (println)
                      ret))
                  (f ctx params))))
          f (fn [ctx params]
              (p op-name (f ctx params)))]
      (assoc resolver :resolve f))))
