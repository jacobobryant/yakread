(ns com.yakread.lib.route
  (:require
   [clojure.string :as str]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.util.biff-staging :as biffs]
   [lambdaisland.uri :as uri]
   [taoensso.nippy :as nippy]))

(defn- encode-uuid [x]
  (if (uuid? x)
    (lib.serialize/uuid->url x)
    x))

(def ^:dynamic *testing* false)

(defn nippy-params [params]
  {:npy (biffs/base64-url-encode (nippy/freeze params))})

(defn href [route & args]
  (let [path-template (first
                       (if (symbol? route)
                         @(resolve route)
                         route))
        template-segments (str/split path-template #":[^/]+")
        {path-args false query-params true} (group-by map? args)
        path-args (mapv encode-uuid path-args)
        query-params (apply merge query-params)
        path (apply str (lib.core/interleave-all template-segments path-args))]
    (if *testing*
      [path query-params]
      (str path
           (when (not-empty query-params)
             (str "?" (uri/map->query-string (nippy-params query-params))))))))

(defn href-safe [{:keys [biff/jwt-secret]} route query-params]
  (let [path (first
              (if (symbol? route)
                @(resolve route)
                route))
        path-segments (str/split path #"/")
        token (delay (lib.serialize/ewt-encode (jwt-secret) query-params))]
    (cond
      (some #{":ewt"} path-segments)
      (str/join "/" (mapv #(if (= ":ewt" %) @token %) path-segments))

      (not-empty query-params)
      (str path "?" (uri/map->query-string {:ewt @token}))

      :else
      path)))

(defn redirect [& args]
  {:status 303
   :headers {"Location" (apply href args)}})

(defn hx-redirect [& args]
  {:status 303
   :headers {"HX-Redirect" (apply href args)}})

(defn wrap-nippy-params [handler]
  (fn
    ([{:keys [biff/jwt-secret params path-params] :as ctx}]
     (let [params (merge params path-params)]
       (handler
        (cond-> ctx
          (:npy params) (update :params merge (nippy/thaw (biffs/base64-url-decode (:npy params))))
          (:ewt params) (assoc :biff/safe-params (lib.serialize/ewt-decode (jwt-secret) (:ewt params)))))))
    ([ctx handler-id]
     (handler ctx handler-id))))
