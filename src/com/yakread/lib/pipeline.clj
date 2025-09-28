(ns com.yakread.lib.pipeline
  (:refer-clojure :exclude [spit])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [cheshire.core :as cheshire]
   [clj-http.client :as http]
   [clojure.data.generators :as gen]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [com.biffweb :as biff]
   [com.yakread.lib.datastar :as lib.d*]
   [com.yakread.lib.error :as lib.error]
   [com.yakread.lib.pathom :as lib.pathom]
   [com.yakread.lib.s3 :as lib.s3]
   [remus]
   [xtdb.api :as xt]
   [taoensso.tufte :refer [p]]))

;; TODO rename to :biff/now, :biff/seed
(defn make [& {:as id->handler}]
  (fn execute
    ([ctx handler-id]
     ((or (get id->handler handler-id)
          (throw (ex-info (str "No handler for " handler-id) {})))
      ctx))
    ([{:biff.pipe/keys [global-handlers] :as ctx*}]
     (let [id->handler (merge global-handlers id->handler)
           ctx (assoc ctx* :biff.pipe/next [:start])]
       (loop [{[next-step & remaining] :biff.pipe/next :as result} ctx]
         (if-not next-step
           result
           (let [{:keys [biff.pipe/current]
                  :as extra-params} (if (map? next-step)
                                      next-step
                                      {:biff.pipe/current next-step})
                 now (java.time.Instant/now)
                 ctx (merge ctx
                            result
                            extra-params
                            {:biff.pipe/next remaining
                             :biff.pipe/now now})
                 handler (or (get id->handler current)
                             (throw (ex-info (str "No handler for " current) {})))
                 seed (long (* (rand) Long/MAX_VALUE))]
             (recur (try
                      (when (:biff.pipe/verbose ctx)
                        (log/info "calling pipe handler for" current))
                      (binding [gen/*rnd* (java.util.Random. seed)]
                        (p current (handler ctx)))
                      (catch Exception e
                        (if (= (:biff.pipe/catch ctx) current)
                          (assoc ctx :biff.pipe/exception e)
                          (throw (lib.error/merge-ex-data
                                  e
                                  (lib.error/request-ex-data ctx*)
                                  (apply dissoc ctx (keys ctx*))
                                  {:biff.pipe/seed seed
                                   :biff.pipe/db-basis (some-> (:biff/db ctx) xt/db-basis)})))))))))))))

(defmacro defpipe [sym & args]
  `(def ~sym (make ~@args)))

;; TODO move into Biff with option
(defn- replace-db-now [tx]
  (let [now (java.time.Instant/now)]
    (walk/postwalk (fn [x]
                     (if (= x :db/now)
                       now
                       x))
                   tx)))

(defn pathom-query [query next-state]
  (constantly
   {:biff.pipe/next [:biff.pipe/pathom next-state]
    :biff.pipe.pathom/query query}))

(defn call-js [{:biff/keys [secret]
                :yakread.pipe.js/keys [base-url fn-name input local] :as ctx}]
  (let [result (if local
                 (-> (biff/sh
                      "node" "-e" "console.log(JSON.stringify(require('./main.js').main(JSON.parse(fs.readFileSync(0)))))"
                      :dir (str "cloud-fns/packages/yakread/" fn-name)
                      :in (cheshire/generate-string input))
                     (cheshire/parse-string true)
                     :body)
                 (-> (str base-url fn-name)
                     (http/post {:headers {"X-Require-Whisk-Auth" secret}
                                 :as :json
                                 :form-params input
                                 :socket-timeout 10000
                                 :connection-timeout 10000})
                     :body))]
    (assoc ctx :yakread.pipe.js/output result)))

(def global-handlers
  {:biff.pipe/http (fn [{:biff.pipe.http/keys [input] :as ctx}]
                     (assoc ctx :biff.pipe.http/output (-> (http/request input)
                                                           (assoc :url (:url input))
                                                           (dissoc :http-client))))
   :biff.pipe/email (fn [{:keys [biff/send-email biff.pipe.email/input] :as ctx}]
                      ;; This can be used in cases where we want a generic email interface not tied
                      ;; to a particular provider. For sending digests we need mailersend-specific
                      ;; features, so we use :biff.pipe/http there instead.
                      (assoc ctx :biff.pipe.email/output (send-email ctx input)))
   :biff.pipe/tx (fn [{:biff.pipe.tx/keys [input retry] :as ctx}]
                   (assoc ctx :biff.pipe.tx/output
                          (biff/submit-tx
                            (cond-> ctx
                              (some? retry) (assoc :biff.xtdb/retry retry))
                            (replace-db-now input))))
   :biff.pipe/pathom (fn [{:biff.pipe.pathom/keys [entity query] :as ctx}]
                       (assoc ctx
                              :biff.pipe.pathom/output
                              (lib.pathom/process ctx (or entity {}) query)))
   :biff.pipe/slurp (fn [{:keys [biff.pipe.slurp/input] :as ctx}]
                      (assoc ctx :biff.pipe.slurp/output (slurp input)))
   :biff.pipe/render (fn [{:keys [biff/router biff.pipe.render/route-name] :as ctx}]
                       #_(lib.route/call router route-name :get ctx))
   :biff.pipe/render* (fn [{:keys [biff.pipe.render/route] :as ctx}]
                        ((get-in @(resolve route) [1 :get]) ctx))
   :biff.pipe/render-sse (fn [{:keys [biff.pipe.render-sse/route] :as ctx}]
                           {:sse [(lib.d*/merge-fragments ((get-in @(resolve route) [1 :get]) ctx))]})
   :biff.pipe/queue (fn [{:biff.pipe.queue/keys [id job wait-for-result] :as ctx}]
                      (assoc ctx
                             :biff.pipe.queue/output
                             (cond-> ((if wait-for-result
                                        biff/submit-job-for-result
                                        biff/submit-job)
                                      ctx
                                      id
                                      job)
                               wait-for-result deref)))
   :com.yakread.pipe/remus (fn [{:com.yakread.pipe.remus/keys [url opts] :as ctx}]
                             (assoc ctx
                                    :com.yakread.pipe.remus/output
                                    (update (remus/parse-url url opts) :response dissoc :http-client :body)))
   :yakread.pipe/js call-js
   :biff.pipe/s3 (fn [{:keys [biff.pipe.s3/input] :as ctx}]
                   (assoc ctx :biff.pipe.s3/output (lib.s3/request ctx input)))
   :biff.pipe.s3/presigned-url (fn [{:keys [biff.pipe.s3.presigned-url/input] :as ctx}]
                                 (assoc ctx :biff.pipe.s3.presigned-url/output (lib.s3/presigned-url ctx input)))
   :biff.pipe/sleep (fn [{:keys [biff.pipe.sleep/ms] :as ctx}]
                      (Thread/sleep (long ms))
                      ctx)
   :biff.pipe/drain-queue (fn [{:biff/keys [job queue] :as ctx}]
                            (let [ll (java.util.LinkedList.)]
                              (.drainTo queue ll)
                              (assoc ctx :biff/jobs (into [job] ll))))
   :biff.pipe/spit (fn [{:biff.pipe.spit/keys [file content] :as ctx}]
                     (io/make-parents (io/file file))
                     (clojure.core/spit file content)
                     ctx)
   :biff.pipe/write (fn [{:biff.pipe.write/keys [file content] :as ctx}]
                      (io/make-parents (io/file file))
                      (with-open [w (io/writer file)]
                        (if (string? content)
                          (.write w content)
                          (doseq [line content]
                            (.write w line)
                            (.write line "\n"))))
                      ctx)
   :biff.pipe/temp-dir (fn [{:keys [biff.pipe.temp-dir/prefix] :or {prefix "biff"} :as ctx}]
                         (assoc ctx
                                :biff.pipe.temp-dir/path
                                (.toFile (java.nio.file.Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute [])))))
   :biff.pipe/delete-files (fn [{:keys [biff.pipe.delete-files/path] :as ctx}]
                             (run! io/delete-file (reverse (file-seq (io/file path))))
                             ctx)
   :biff.pipe/shell (fn [{:keys [biff.pipe.shell/args] :as ctx}]
                      (assoc ctx :biff.pipe.shell/output (apply shell/sh args)))})

(defn email [input]
  {:biff.pipe/current :biff.pipe/email
   :biff.pipe.email/input input})

(defn shell [& args]
  {:biff.pipe/current :biff.pipe/shell
   :biff.pipe.shell/args (vec args)})

(defn delete-files [path]
  {:biff.pipe/current :biff.pipe/delete-files
   :biff.pipe.delete-files/path path})

(defn spit [file content]
  {:biff.pipe/current :biff.pipe/spit
   :biff.pipe.spit/file file
   :biff.pipe.spit/content content})

(defn write [file content]
  {:biff.pipe/current :biff.pipe/write
   :biff.pipe.write/file file
   :biff.pipe.write/content content})

(defn s3-presigned-url [config-ns k expires-at]
  {:biff.pipe/current  :biff.pipe.s3/presigned-url
   :biff.pipe.s3.presigned-url/input {:key (str k)
                                      :config-ns config-ns
                                      :method "GET"
                                      :expires-at expires-at}})

(defn s3 [config-ns k & [body content-type]]
  {:biff.pipe/current  :biff.pipe/s3
   :biff.pipe.s3/input (merge {:key (str k)
                               :config-ns config-ns}
                              (if body
                                {:method  "PUT"
                                 :body    body
                                 :headers {"x-amz-acl"    "private"
                                           "content-type" content-type}}
                                {:method "GET"}))})

(defn tx [input-tx]
  {:biff.pipe/current :biff.pipe/tx
   :biff.pipe.tx/input input-tx})

(defn queue [id job]
  {:biff.pipe/current :biff.pipe/queue
   :biff.pipe.queue/id id
   :biff.pipe.queue/job job})

(defn sleep [ms]
  {:biff.pipe/current :biff.pipe/sleep
   :biff.pipe.sleep/ms ms})

(defn http [method url & [opts]]
  {:biff.pipe/current :biff.pipe/http
   :biff.pipe.http/input (merge {:method method :url url} opts)})

(defn pathom [entity query]
  {:biff.pipe/current :biff.pipe/pathom
   :biff.pipe.pathom/entity entity
   :biff.pipe.pathom/query query})

(defn render [route]
  {:biff.pipe/current :biff.pipe/render*
   :biff.pipe.render/route route})
