(ns com.yakread.lib.pathom
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.yakread.lib.error :as lib.error]
   [taoensso.tufte :refer [p]]))

(defn handler [query f]
  (fn handler*
    ([request]
     (let [extra {:biff/now (java.time.Instant/now)
                  :biff/seed (long (* (rand) Long/MAX_VALUE))}
           request (merge request extra)]
       (binding [gen/*rnd* (java.util.Random. (:biff/seed extra))]
         (lib.error/with-ex-data (merge (lib.error/request-ex-data request) extra)
           (handler* request (p.eql/process request query))))))
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
