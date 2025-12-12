(ns com.yakread.lib.fx
  (:require
   [clj-http.client :as http]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.yakread.lib.pathom :as lib.pathom]))

;; TODO add observability/monitoring stuff
;; and exception catching stuff
(defn machine [& {:as state->transition-fn}]
  (fn run
    ([ctx state]
     ((or (get state->transition-fn state)
          (throw (ex-info (str "Invalid state: " state) {})))
      ctx))
    ([{:biff.fx/keys [handlers] :as ctx}]
     (loop [ctx ctx
            state :start
            trace []]
       (let [t-fn (or (get state->transition-fn state)
                      (throw (ex-info (str "Invalid state: " state) {})))
             result (t-fn ctx)
             results (if (map? result)
                       [result]
                       result)
             results (mapv (fn [m]
                             (let [state-output (apply dissoc m (keys handlers))
                                   fx-input     (select-keys m (keys handlers))
                                   fx-output    (into {}
                                                      (map (fn [[k v]]
                                                             [k ((get handlers k)
                                                                 ctx
                                                                 v)]))
                                                      fx-input)]
                               {:biff.fx/state-output state-output
                                :biff.fx/fx-input fx-input
                                :biff.fx/fx-output fx-output}))
                           results)
             trace (conj trace
                         {:biff.fx/state state
                          :biff.fx/results results})
             {:biff.fx/keys [state-output fx-output fx-input]} (apply merge-with merge results)
             ctx (merge ctx
                        fx-output
                        state-output
                        {:biff.fx/trace trace
                         :biff.fx/fx-input fx-input})
             next-state (:biff.fx/next state-output)]
         (if next-state
           (recur ctx next-state trace)
           state-output))))))

(defn safe-for-url? [s]
  (boolean (re-matches #"[a-zA-Z0-9-_.+!*]+" s)))

(defn- autogen-endpoint [ns* sym]
  (let [href (str "/_biff/api/" ns* "/" sym)]
    (assert (safe-for-url? (str sym)) (str "URL segment would contain invalid characters: " sym))
    (assert (safe-for-url? (str ns*)) (str "URL segment would contain invalid characters: " ns*))
    href))

(let [all-methods [:get :post :put :delete :head :options :trace :patch :connect]]
  (defn route* [uri & {:as state->transition-fn}]
    (let [machine* (machine state->transition-fn)]
      [uri
       (into {}
             (comp (filter state->transition-fn)
                   (map (fn [method]
                          [method machine*])))
             all-methods)])))

(defn wrap-pathom [f]
  (fn [ctx]
    (f ctx (:biff.fx/pathom ctx))))

(defn wrap-hiccup [f]
  (fn [& args]
    (let [result (apply f args)]
      (if (and (vector? result) (keyword? (first result)))
        {:body result}
        result))))

(defmacro defroute [sym & args]
  (let [[uri & kvs] (if (string? (first args))
                      args
                      (into [nil] args))
        uri (or uri (autogen-endpoint *ns* sym))]
    `(let [params# (array-map ~@kvs)]
       (def ~sym
         (route* ~uri (-> params#
                          (update-vals wrap-hiccup)
                          (merge {:start (fn [{:keys [~'request-method]}]
                                           {:biff.fx/next ~'request-method})})))))))

(defmacro defroute-pathom [sym & args]
  (let [[uri query & kvs] (if (string? (first args))
                            args
                            (into [nil] args))
        uri (or uri (autogen-endpoint *ns* sym))]
    `(let [query# ~query
           params# (array-map ~@kvs)]
       (def ~sym
         (route* ~uri (-> params#
                          (update-vals (comp wrap-hiccup wrap-pathom))
                          (merge {:start (fn [{:keys [~'request-method]}]
                                           {:biff.fx/pathom query#
                                            :biff.fx/next ~'request-method})})))))))

(def handlers
  {:biff.fx/http (fn [_ctx request]
                   (-> (http/request request)
                       (assoc :url (:url request))
                       (dissoc :http-client)))
   :biff.fx/email (fn [{:keys [biff/send-email] :as ctx} input]
                    ;; This can be used in cases where we want a generic email interface not tied
                    ;; to a particular provider. For sending digests we need mailersend-specific
                    ;; features, so we use :biff.pipe/http there instead.
                    (send-email ctx input))
   :biff.fx/tx biffx/submit-tx
   :biff.fx/pathom (fn [ctx input]
                     (let [{:keys [entity query]} (if (map? input)
                                                    input
                                                    {:query input})]
                       (lib.pathom/process ctx (or entity {}) query)))
   :biff.fx/slurp (fn [_ctx file]
                    (slurp file))
   :biff.fx/queue (fn [ctx {:keys [id job wait-for-result]}]
                    (cond-> ((if wait-for-result
                               biff/submit-job-for-result
                               biff/submit-job)
                             ctx
                             id
                             job)
                      wait-for-result deref))
   
   ;; TODO
   ;;:biff.pipe/s3 (fn [{:keys [biff.pipe.s3/input] :as ctx}]
   ;;                (assoc ctx :biff.pipe.s3/output (lib.s3/request ctx input)))
   ;;:biff.pipe.s3/presigned-url (fn [{:keys [biff.pipe.s3.presigned-url/input] :as ctx}]
   ;;                              (assoc ctx :biff.pipe.s3.presigned-url/output (lib.s3/presigned-url ctx input)))
   ;;:biff.pipe/sleep (fn [{:keys [biff.pipe.sleep/ms] :as ctx}]
   ;;                   (Thread/sleep (long ms))
   ;;                   ctx)
   ;;:biff.pipe/drain-queue (fn [{:biff/keys [job queue] :as ctx}]
   ;;                         (let [ll (java.util.LinkedList.)]
   ;;                           (.drainTo queue ll)
   ;;                           (assoc ctx :biff/jobs (into [job] ll))))
   ;;:biff.pipe/spit (fn [{:biff.pipe.spit/keys [file content] :as ctx}]
   ;;                  (io/make-parents (io/file file))
   ;;                  (clojure.core/spit file content)
   ;;                  ctx)
   ;;:biff.pipe/write (fn [{:biff.pipe.write/keys [file content] :as ctx}]
   ;;                   (io/make-parents (io/file file))
   ;;                   (with-open [w (io/writer file)]
   ;;                     (if (string? content)
   ;;                       (.write w content)
   ;;                       (doseq [line content]
   ;;                         (.write w line)
   ;;                         (.write line "\n"))))
   ;;                   ctx)
   ;;:biff.pipe/temp-dir (fn [{:keys [biff.pipe.temp-dir/prefix] :or {prefix "biff"} :as ctx}]
   ;;                      (assoc ctx
   ;;                             :biff.pipe.temp-dir/path
   ;;                             (.toFile (java.nio.file.Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute [])))))
   ;;:biff.pipe/delete-files (fn [{:keys [biff.pipe.delete-files/path] :as ctx}]
   ;;                          (run! io/delete-file (reverse (file-seq (io/file path))))
   ;;                          ctx)
   ;;:biff.pipe/shell (fn [{:keys [biff.pipe.shell/args] :as ctx}]
   ;;                   (assoc ctx :biff.pipe.shell/output (apply shell/sh args)))
   
   })
