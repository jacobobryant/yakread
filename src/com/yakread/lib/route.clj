(ns com.yakread.lib.route
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pathom :as lib.pathom]
   [com.yakread.lib.pipeline :as lib.pipe]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.util.biff-staging :as biffs]
   [lambdaisland.uri :as uri]
   [reitit.core :as reitit]
   [ring.middleware.anti-forgery :as csrf]
   [taoensso.nippy :as nippy]
   [com.wsscode.pathom3.connect.operation :refer [?]]))

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

(defn action [action-name route & {:as opts}]
  (let [url (href route (:params opts {}))
        opts (-> opts
                 (dissoc :params)
                 (assoc-in [:headers :x-csrf-token] csrf/*anti-forgery-token*))]
    (str "@" (name action-name) "('" url "', " (cheshire/generate-string opts) ")")))

(defn redirect [& args]
  {:status 303
   :headers {"Location" (apply href args)}})

(defn hx-redirect [& args]
  {:status 303
   :headers {"HX-Redirect" (apply href args)}})

(defn call [router route-name method ctx]
  ((get-in (reitit/match-by-name router route-name)
           [:data method :handler])
   ctx))

(defn handler [router route-name method]
  (get-in (reitit/match-by-name router route-name)
          [:data method :handler]))

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

(defn safe-for-url? [s]
  (boolean (re-matches #"[a-zA-Z0-9-_.+!*]+" s)))

(defn- autogen-endpoint [ns* sym]
  (let [href (str "/_biff/api/" ns* "/" sym)]
    (assert (safe-for-url? (str sym)) (str "URL segment would contain invalid characters: " sym))
    (assert (safe-for-url? (str ns*)) (str "URL segment would contain invalid characters: " ns*))
    href))

(defmacro defget
  ([sym query handler]
   `(defget ~sym ~(autogen-endpoint *ns* sym) ~query ~handler))
  ([sym path query handler]
   `(def ~sym [~path {:name ~(keyword (str *ns*) (str sym))
                      :get (wrap-nippy-params (lib.pathom/handler ~query ~handler))}])))

(defmacro defpost [sym & pipe-args]
  `(def ~sym [~(autogen-endpoint *ns* sym)
              {:name ~(keyword (str *ns*) (str sym))
               :post (wrap-nippy-params (lib.pipe/make ~@pipe-args))}]))

(defmacro defpost-pathom [sym query f & args]
  `(defpost ~sym
     :start (lib.pipe/pathom-query ~query :start*)
     :start* (let [f# ~f]
               (fn [ctx#]
                 (f# ctx# (:biff.pipe.pathom/output ctx#))))
     ~@args))

(defmacro defget-pipe [sym & pipe-args]
  `(def ~sym [~(autogen-endpoint *ns* sym)
              {:name ~(keyword (str *ns*) (str sym))
               :get (wrap-nippy-params (lib.pipe/make ~@pipe-args))}]))
