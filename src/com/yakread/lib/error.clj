(ns com.yakread.lib.error
  (:require [com.yakread.lib.content :as lib.content]
            [clojure.walk :refer [postwalk]]))

(defn truncate [data]
  (postwalk (fn [data]
              (if (string? data)
                (lib.content/truncate data 500)
                data))
            data))

(defn merge-ex-data [ex & data]
  (doto (ex-info (.getMessage ex)
                 (truncate
                  (apply merge
                         (ex-data ex)
                         (when-not (instance? clojure.lang.ExceptionInfo ex)
                           {:biff.error/ex-type (type ex)})
                         data))
                 (ex-cause ex))
    (.setStackTrace (.getStackTrace ex))))

(defmacro with-ex-data [data & body]
  `(try
     ~@body
     (catch Exception e#
       (throw (merge-ex-data e# ~data)))))

(defn request-ex-data [{:keys [biff.error/param-keys
                               biff.error/param-paths]
                        :or {param-keys [:uri :params :path-params :session]
                             param-paths [[:reitit.core/match :data :name :biff/job]]}
                        :as request}]
  (let [queue-id (->> (:biff/queues request)
                      (filterv (fn [[id queue]]
                                 (= queue (:biff/queue request))))
                      (ffirst))]
    (reduce (fn [data path]
              (assoc-in data path (get-in request path)))
            (merge (select-keys request param-keys)
                   (when queue-id
                     {:biff/queue-id queue-id}))
            param-paths)))
