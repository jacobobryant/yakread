(ns com.yakread.lib.fx
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as http]
   [clojure.walk :as walk]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.yakread.lib.s3 :as lib.s3]))

(defn- truncate-str
  "Truncates a string s to be at most n characters long, appending an ellipsis if any characters were removed."
  [s n]
  (if (<= (count s) n)
    s
    (str (subs s 0 (dec n)) "…")))

(defn- truncate [data]
  (walk/postwalk (fn [data]
                   (if (string? data)
                     (truncate-str data 500)
                     data))
                 data))

(defn- step [{:keys [state->transition-fn
                     ctx
                     state
                     trace]}]
  (let [{:keys [biff.fx/handlers]} ctx
        t-fn (or (get state->transition-fn state)
                 (throw (ex-info "Invalid state" {:state state})))
        result (t-fn ctx)
        results (if (map? result)
                  [result]
                  result)
        results (mapv (fn [m]
                        (let [state-output (apply dissoc m (keys handlers))
                              fx-input     (select-keys m (keys handlers))
                              fx-output    (into {}
                                                 (map (fn [[k v]]
                                                        [k (try
                                                             ((get handlers k) ctx v)
                                                             (catch Exception e
                                                               (throw (ex-info "Exception while running biff.fx effect"
                                                                               (truncate
                                                                                {:effect k
                                                                                 :input v})
                                                                               e))))]))
                                                 fx-input)]
                          {:biff.fx/state-output state-output
                           :biff.fx/fx-input fx-input
                           :biff.fx/fx-output fx-output}))
                      results)
        trace (conj trace
                    {:biff.fx/state state
                     :biff.fx/results results})
        {:biff.fx/keys [state-output fx-output fx-input]} (apply merge-with merge results)]
    {:next-state (:biff.fx/next state-output)
     :ctx (merge ctx
                 fx-output
                 state-output
                 {:biff.fx/trace trace
                  :biff.fx/fx-input fx-input})
     :trace trace
     :state-output state-output}))

;; TODO set and log gen/*rnd*
(defn machine [machine-name & {:as state->transition-fn}]
  (assert (contains? state->transition-fn :start)
          "machine must have a :start state")
  (fn run
    ([ctx state]
     ((or (get state->transition-fn state)
          (throw (ex-info (str "Invalid state: " state) {})))
      ctx))
    ([ctx]
     (loop [ctx   ctx
            state :start
            trace []]
       (let [{:keys [next-state ctx trace state-output]}
             (try
               (step {:state->transition-fn state->transition-fn
                      :ctx ctx
                      :state state
                      :trace trace})
               (catch Exception e
                 (throw (ex-info "Exception while running biff.fx machine"
                                 {:machine machine-name
                                  :state state
                                  :trace (truncate trace)}
                                 e))))]
         (if next-state
           (recur ctx next-state trace)
           state-output))))))

(defn safe-for-url? [s]
  (boolean (re-matches #"[a-zA-Z0-9-_.+!*]+" s)))

(defn- autogen-endpoint [ns* sym]
  (let [href (str "/_biff/api/" ns* "/" sym)]
    (doseq [segment [ns* sym]]
      (assert (safe-for-url? (str segment))
              (str "URL segment would contain invalid characters: " segment)))
    href))

(let [all-methods [:get :post :put :delete :head :options :trace :patch :connect]]
  (defn route* [uri route-name & {:as state->transition-fn}]
    (let [machine* (machine route-name state->transition-fn)]
      [uri
       (into {:name route-name}
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
        uri (or uri (autogen-endpoint *ns* sym))
        route-name (keyword (str *ns*) (str sym))]
    `(def ~sym
       (let [[& {:as params#}] [~@kvs]]
         (route* ~uri
                 ~route-name
                 (-> params#
                     (update-vals wrap-hiccup)
                     (merge {:start (fn [{:keys [~'request-method]}]
                                      {:biff.fx/next ~'request-method})})))))))

(defmacro defroute-pathom [sym & args]
  (let [[uri query & kvs] (if (string? (first args))
                            args
                            (into [nil] args))
        uri (or uri (autogen-endpoint *ns* sym))
        route-name (keyword (str *ns*) (str sym))]
    `(def ~sym
       (let [query# ~query
             [& {:as params#}] [~@kvs]]
         (route* ~uri
                 ~route-name
                 (-> params#
                     (update-vals (comp wrap-hiccup wrap-pathom))
                     (merge {:start (fn [{:keys [~'request-method]}]
                                      {:biff.fx/pathom query#
                                       :biff.fx/next ~'request-method})})))))))

(defmacro defmachine [sym & args]
  (let [machine-name (keyword (str *ns*) (str sym))]
    `(def ~sym (machine ~machine-name ~@args))))

(defn call-js [{:biff/keys [secret] :as ctx} params]
  (let [{:keys [base-url fn-name input local]} (merge (biff/select-ns-as ctx 'com.yakread.fx.js nil)
                                                      params)]
    (if local
      (-> (biff/sh
           "node" "-e" "console.log(JSON.stringify(require('./main.js').main(JSON.parse(fs.readFileSync(0)))))"
           :dir (str "cloud-fns/packages/yakread/" fn-name)
           :in (cheshire/generate-string input))
          (cheshire/parse-string true)
          :body)
      (-> (str base-url fn-name)
          (http/post {:headers {"X-Require-Whisk-Auth" (secret :com.yakread.fx.js/secret)}
                      :as :json
                      :form-params input
                      :socket-timeout 10000
                      :connection-timeout 10000})
          :body))))

(comment
  (for [local [true false]]
    (call-js @com.yakread/system
             {:fn-name "readability",
              :input {:url "https://example.com?foo=bar", :html "hello"}
              :local local})))

(def handlers
  {:biff.fx/http (fn [_ctx request]
                   (try
                     (-> (http/request request)
                         (assoc :url (:url request))
                         (dissoc :http-client))
                     (catch Exception e
                       (if (get request :throw-exceptions true)
                         (throw e)
                         {:url (:url request)
                          :exception e}))))
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
                       (p.eql/process ctx (or entity {}) query)))
   :biff.fx/slurp (fn [_ctx file]
                    (slurp file))
   :biff.fx/queue (fn [ctx {:keys [id job jobs wait-for-result]}]
                    (if jobs
                      (mapv (fn [[id job]]
                              (biff/submit-job ctx id job))
                            jobs)
                      (cond-> ((if wait-for-result
                                 biff/submit-job-for-result
                                 biff/submit-job)
                               ctx
                               id
                               job)
                        wait-for-result deref)))
   :biff.fx/s3 (fn [ctx input]
                 (if (map? input)
                   (lib.s3/request ctx input)
                   (mapv #(lib.s3/request ctx %) input)))
   :biff.fx/call (fn [ctx sym]
                   ((requiring-resolve sym) ctx))

   :com.yakread.fx/js call-js

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
