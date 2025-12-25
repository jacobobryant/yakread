(ns com.yakread.lib.test
  (:require
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.classpath :as cp]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test.check.generators :as tc-gen]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.stuartsierra.dependency :as dep]
   [com.yakread :as main]
   [com.yakread.lib.route :as lib.route]
   [com.yakread.util.biff-staging :as biffs]
   [malli.experimental.time.generator]
   [malli.generator :as malli.g]
   [tick.core :as tick]
   [time-literals.read-write :as time-literals]
   [xtdb.api :as xt]
   [xtdb.node :as xtn])
  (:import
   [java.time Instant]))

(defn submit-tx [node tx]
  (->> tx
       (biffs/resolve-tx-ops {:biff/conn node})
       (mapv biffx/format-query)
       (xt/submit-tx node)))

(defn- truncate-str
  "Truncates a string s to be at most n characters long, appending an ellipsis if any characters were removed."
  [s n]
  (if (<= (count s) n)
    s
    (str (subs s 0 (dec n)) "…")))

(defn truncate [data]
  (walk/postwalk (fn [data]
                   (if (string? data)
                     (truncate-str data 50)
                     data))
                 data))

(defn start-test-node [table->records]
  (let [node (xtn/start-node {})]
    (xt/submit-tx node (for [[table records] table->records]
                         (into [:put-docs table] records)))
    node))

(defmacro with-node [[node-sym db-contents] & body]
  `(with-open [~node-sym (start-test-node ~db-contents)]
     ~@body))

(defn test-route [route state {:keys [request-method db] :as ctx}]
  (let [[_ {f (or request-method state)}] route]
    ;; TODO use a var from this namespace maybe?
    (binding [lib.route/*testing* true]
      (if db
        (with-node [node db]
          (f (assoc ctx :biff/conn node) state))
        (f ctx state)))))

(defn- read-string* [s & [extra-readers]]
  (edn/read-string {:readers (merge time-literals/tags
                                    {'biff/file clojure.java.io/file}
                                    extra-readers)
                    :default (fn [tag value] value)} s))

(defn- find-resources [ext]
  (->> (cp/classpath-directories)
       (mapcat file-seq)
       (filter #(and (.isFile %)
                     (.endsWith (.getName %) ext)))
       (map #(.getPath %))))

(defn- truncate-ex [ex filter-str]
  (try
    (doto ex
      (.setStackTrace
       (->> (.getStackTrace ex)
            (take-while #(not (str/includes? (.getClassName %) filter-str)))
            (into-array java.lang.StackTraceElement))))
    (catch Exception _
      ex)))

(defn tapped*
  "Calls (f) and returns a tuple of f's return value along with a vector of everything that was
   tap>'d while f was running."
  [f]
  (let [done (promise)
        tap-results (atom [])
        tap-fn (fn [x]
                 (if (= x ::done)
                   (deliver done nil)
                   (swap! tap-results conj x)))
        _ (add-tap tap-fn)
        result (f)]
    (tap> ::done)
    @done
    (remove-tap tap-fn)
    [result @tap-results]))

(defmacro tapped [& body]
  `(tapped* (fn [] ~@body)))

(defn- sorted-map* [m]
  (apply sorted-map (apply concat m)))

(defn eval* [example]
  (let [[result tap-results]
        (tapped
         (try
           {:result (binding [gen/*rnd* (java.util.Random. 0)]
                      (walk/postwalk identity (eval (:eval example))))}
           (catch Exception e
             ;; Only show the part of the stack trace that come from the eval'd code.
             {:ex (truncate-ex e "com.yakread.lib.test$eval_STAR_")})))]
    (sorted-map*
     (merge result
            {:eval (:eval example)}
            (when (not-empty tap-results)
              {:tapped tap-results})
            (select-keys example [:doc])))))

(defn pprint-dispatch [obj]
  (if (instance? java.io.File obj)
    (.write *out* (str "#biff/file " (pr-str (.getPath obj))))
    (clojure.pprint/simple-dispatch obj)))

(defn pretty-spit [file x]
  (pprint/with-pprint-dispatch pprint-dispatch
    (spit file (with-out-str (biff/pprint x)))))

(defn load-fixtures [test-filename sym->fixture-code]
  (let [fixture-file (io/file (str/replace test-filename #"_test.edn$" "_fixtures.edn"))
        existing-fixtures (when (.exists fixture-file)
                            (read-string* (slurp fixture-file)))
        new-fixtures (-> (apply dissoc sym->fixture-code (keys existing-fixtures))
                         (update-vals eval))
        fixtures (-> (merge existing-fixtures new-fixtures)
                     (select-keys (keys sym->fixture-code))
                     not-empty)]
    (cond
      (and (empty? fixtures) (.exists fixture-file))
      (io/delete-file fixture-file)

      (not= existing-fixtures fixtures)
      (pretty-spit fixture-file fixtures))
    fixtures))

(let [limit 60000]
  ;; string literals are apparently limited to 65535 bytes when compiling (eval'ing) code.
  (defn break-up-strings [x]
    (walk/postwalk
     (fn [x]
       (if (and (string? x) (< limit (count x)))
         `(str ~@(mapv #(apply str %) (partition-all limit x)))
         x))
     x)))

(defn run-examples! [& {:keys [ext]}]
  (doseq [f (find-resources (or ext "_test.edn"))
          :let [f-contents (read-string* (slurp f)
                                         {'error (constantly nil)})
                ns-sym (symbol (str "tmp" (rand-int 999999)))
                tests (binding [*ns* (create-ns ns-sym)]
                        (refer 'clojure.core)
                        (run! require (:require f-contents))
                        (doseq [[sym fixture] (load-fixtures f (:fixtures f-contents))]
                          (eval `(def ~sym ~(break-up-strings fixture))))
                        (->> (:tests f-contents)
                             (remove #{'_} )
                             (mapv eval*)))
                _ (remove-ns ns-sym)
                ;; Add some underscores for visual separation.
                tests (vec (interleave tests (repeat '_)))]]
    (when (not= tests (:tests f-contents))
      (println "Updating tests in" f)
      (pretty-spit f (assoc f-contents :tests tests)))))

(defn instant [year & [month & [day & [hour & [minute & [second*]]]]]]
  (Instant/parse (format "%04d-%02d-%02dT%02d:%02d:%02dZ"
                         (or year 2000)
                         (or month 1)
                         (or day 1)
                         (or hour 0)
                         (or minute 0)
                         (or second* 0))))

(defn zdt [& args]
  (tick/in (apply instant args) "UTC"))

(defn uuid [n]
  (parse-uuid (format "00000000-0000-0000-0000-%012d" n)))

(defn queue [& jobs]
  (let [queue (java.util.concurrent.PriorityBlockingQueue. 11 (fn [a b] 0))]
    (run! #(.add queue %) jobs)
    queue))

(defrecord BiffSystem []
  java.io.Closeable
  (close [this]
    (doseq [f (:biff/stop this)]
      (log/info "stopping:" (str f))
      (f))))

(def initial-system
  (merge main/initial-system
         {:biff.xtdb/topology :memory
          :biff.index/dir :tmp}))

(defn start!
  ([components]
   (start! initial-system components))
  ([system components]
   (map->BiffSystem
    (reduce (fn [system component]
              (log/info "starting:" (str component))
              (component system))
            system
            components))))

;;; ---

#_(defn- actual [{:keys [biff.test/fixtures
                       biff.test/empty-db
                       biff/modules
                       biff/router]
                :as ctx*}
               {:keys [route-name mutation fn-sym index-id method handler-id ctx fixture db-contents]
                :or {method :post}
                :as example}]
  (let [sut* (cond
               ;; TODO take the route directly instead of using router
               route-name (lib.route/handler router route-name method)
               fn-sym (requiring-resolve fn-sym)
               ;; TODO do a helpful error message if this is missing
               mutation (if-some [mutation-var (resolve mutation)]
                          (:biff/mutation (meta mutation-var))
                          (throw (ex-info (str "Couldn't resolve mutation: " mutation) {})))
               index-id (->> @modules
                             (mapcat :indexes)
                             (filterv (comp #{index-id} :id))
                             first
                             :indexer)
               :else (throw (ex-info "You must include either :route-name or :fn-sym in the test case"
                                     example)))
        sut (if (contains? example :handler-id)
              #(sut* % handler-id)
              sut*)
        ctx (merge ctx* ctx (some-> fixture fixtures))]
    (if (not-empty db-contents)
      (with-open [node (xt/start-node {})]
        (xt/await-tx node (xt/submit-tx node (for [doc db-contents]
                                               [:xtdb.api/put doc])))
        (sut (assoc ctx :biff/db (xt/db node))))
      (sut (assoc ctx :biff/db empty-db)))))

(defn dirname [current-ns]
  (str "test/"
       (-> (str current-ns)
           (str/replace "." "/")
           (str/replace "-" "_"))
       "/"))

(defn- examples-path [current-ns]
  (str (dirname current-ns) "examples.edn"))

(defn- fixtures-path [current-ns]
  (str (dirname current-ns) "fixtures.edn"))

(defn read-fixtures! [current-ns]
  (-> (fixtures-path current-ns)
      slurp
      read-string*))

(defn write-fixtures! [current-ns fixtures]
  (with-open [o (io/writer (fixtures-path current-ns))]
    (pprint fixtures o)))

(defn read-examples! [current-ns]
  (let [file (io/file (examples-path current-ns))]
    (when (.exists file)
      (read-string* (slurp file)))))

#_(defn write-examples! [{:biff.test/keys [current-ns examples] :as ctx}]
  (binding [gen/*rnd* (java.util.Random. 0)
            *print-namespace-maps* true
            lib.route/*testing* true]
    (with-open [node (xt/start-node {})]
      (let [ctx (assoc ctx :biff.test/empty-db (xt/db node))
            examples (mapv #(assoc % :expected (actual ctx %)) examples)
            file (io/file (examples-path current-ns))]
        (io/make-parents file)
        (with-open [o (io/writer file)]
          (binding [*out* o]
            (println "[")
            (doseq [example examples]
              (pprint example)
              (println))
            (println "]")))))))

#_(defn check-examples! [{:biff.test/keys [examples current-ns] :as ctx}]
  (binding [gen/*rnd* (java.util.Random. 0)
            *print-namespace-maps* true
            lib.route/*testing* true]
    (let [written-examples (read-examples! current-ns)]
      (if (not= examples (mapv #(dissoc % :expected) written-examples))
        (test/report {:type :fail
                      :message (str "Example test cases for "
                                    current-ns
                                    " have changed. Please call write-examples!")})
        (with-open [node (xt/start-node {})]
          (let [ctx (assoc ctx :biff.test/empty-db (xt/db node))]
            (doseq [[i {:keys [doc expected] :as example}] (map-indexed vector written-examples)
                    :let [_actual (actual ctx example)]]
              (test/report {:type (if (= expected _actual) :pass :fail)
                            :expected expected
                            :actual _actual
                            :message (str "Example "
                                          (pr-str (dissoc example :expected))
                                          " failed.")}))))))))

(defmacro current-ns []
  *ns*)

(defn route-examples [& {:as examples}]
  (for [[[route-name method handler-id] examples] examples
        example examples]
    (merge {:route-name route-name
            :method method
            :handler-id handler-id}
           example)))

(defn mutation-examples [& {:as examples}]
  (for [[[mutation-sym handler-id] examples] examples
        example examples]
    (merge {:mutation mutation-sym
            :handler-id handler-id}
           example)))

(defn fn-examples [& {:as examples}]
  (for [[[fn-var handler-id] examples] examples
        :let [m (meta fn-var)]
        example examples]
    (merge {:fn-sym (symbol (str (:ns m)) (str (:name m)))
            :handler-id handler-id}
           example)))

(defn index-examples [& {:as examples}]
  (for [[index-id examples] examples
        example examples]
    (merge {:index-id index-id}
           example)))

(defn- rank [graph x overrides]
  (or (get overrides x)
      (if-some [deps (get-in graph [:dependencies x])]
        (inc (apply max (mapv #(rank graph % overrides) deps)))
        1)))

;; TODO
;; - support ref attrs that are cardinality-many
;; - set default weights such that explicitly passed schemas are equal
;; - improve shrinking
;; - maybe don't use fugato
(defn make-model [{:keys [biff/malli-opts schemas rank-overrides]}]
  (when-not (set? schemas)
    (throw (ex-info (str "`schemas` must be a set; got " (type schemas) " instead.")
                    {:schemas schemas})))
  (let [schema->ast (into {}
                          (map (fn [ast]
                                 [(-> ast :properties :schema)
                                  ast]))
                          (biffs/doc-asts main/malli-opts))
        graph (reduce (fn [graph [doc-schema target-schema]]
                        (dep/depend graph doc-schema target-schema))
                      (dep/graph)
                      (for [[doc-schema ast] schema->ast
                            [_ {:keys [properties]}] (:keys ast)
                            target-schema (:biff/ref properties)]
                        [doc-schema target-schema]))
        schemas (filterv (fn [schema]
                           (or (schemas schema)
                               (some #(dep/depends? graph % schema) schemas)))
                         (dep/topo-sort graph))]
    (into {}
          (for [schema schemas
                :let [deps (get-in graph [:dependencies schema])
                      attr->properties (update-vals (get-in schema->ast [schema :keys])
                                                    :properties)
                      required-deps (->> (vals attr->properties)
                                         (remove :optional)
                                         (mapcat :biff/ref))
                      attr->targets (into {}
                                          (keep (fn [[attr properties]]
                                                  (when-some [targets (:biff/ref properties)]
                                                    [attr targets])))
                                          attr->properties)]]
            [schema {:freq (Math/pow 2 (rank graph schema rank-overrides))
                     :run? (fn [state]
                             (every? #(contains? state %) required-deps))
                     :args (fn [state]
                             (tc-gen/tuple
                              (reduce (fn [gen [attr targets]]
                                        (tc-gen/bind gen
                                                     (fn [doc]
                                                       (if (or (not (contains? doc attr))
                                                               (empty? targets))
                                                         (tc-gen/return (dissoc doc attr))
                                                         (tc-gen/let [target (tc-gen/elements targets)
                                                                      target-id (tc-gen/elements (keys (get state target)))]
                                                           (assoc doc attr target-id))))))
                                      (malli.g/generator schema malli-opts)
                                      (for [[attr targets] attr->targets]
                                        [attr (filterv #(contains? state %) targets)]))))
                     :next-state (fn [state {[doc] :args}]
                                   (-> state
                                       (assoc-in [schema (:xt/id doc)] doc)
                                       (update ::referenced (fnil into #{}) (keep doc (keys attr->targets)))))
                     :valid? (fn [state {[doc] :args}]
                               (->> (keys attr->targets)
                                    (keep doc)
                                    (every? #(contains? (::referenced state) %))))}]))))

(defn- indexer-actual [indexer docs]
  (->> docs
       (reduce (fn [changes doc]
                 (merge changes
                        (indexer #:biff.index{:index-get changes
                                              :op :xtdb.api/put
                                              :doc doc})))
               {})
       (remove (comp nil? val))
       (into {})))

#_(defn indexer-prop [{:keys [indexer model-opts expected-fn]}]
  (let [model (make-model model-opts)]
    (prop/for-all [commands (fugato/commands model {} 5 1)]
      (let [docs (mapv (comp first :args) commands)
            expected (expected-fn docs)
            actual (indexer-actual indexer docs)]
        (is (= expected actual))))))

(def ^:dynamic *defspec-opts* {:num-tests 25})

(defn instant [& [year month day hour minute _second millisecond]]
  (Instant/parse (format "%d-%02d-%02dT%02d:%02d:%02d.%03dZ"
                         (or year 1970)
                         (or month 1)
                         (or day 1)
                         (or hour 0)
                         (or minute 0)
                         (or _second 0)
                         (or millisecond 0))))

(defn- write-trace! [{:keys [indexer id]} result]
  (let [tmp-file (io/file (System/getProperty "java.io.tmpdir")
                          (str "biff-trace-" (subs (str id) 1) "-" (:seed result) ".edn"))
        docs (for [{[doc] :args} (-> result :shrunk :smallest first)]
               doc)
        [changes steps] (reduce (fn [[changes steps] doc]
                                  (let [new-changes (indexer #:biff.index{:index-get changes
                                                                          :op :xtdb.api/put
                                                                          :doc doc})]
                                    [(merge changes new-changes)
                                     (into steps [doc
                                                  '=>
                                                  new-changes
                                                  '_])]))
                                [{} []]
                                docs)
        state (->> changes
                   (remove (comp nil? val))
                   (into {}))]
    (with-open [file (io/writer tmp-file)]
      (binding [*out* file]
        (biff/pprint (conj steps state))))
    (str tmp-file)))

#_(defn test-index [index opts]
  (let [num-tests (:num-tests opts)
        prop-opts (assoc (select-keys opts [:model-opts :expected-fn])
                         :indexer (:indexer index))
        quick-check-opts (apply dissoc opts (keys prop-opts))
        {:keys [shrunk] :as result} (tc/quick-check (:num-tests quick-check-opts)
                                                    (indexer-prop prop-opts)
                                                    quick-check-opts)
        result (cond-> result
                 true (dissoc :shrunk :fail)
                 shrunk (assoc :biff/trace (write-trace! index result))
                 true (assoc :index (:id index)))]
    result))

#_(defmacro deftest-index [index opts]
  (assert (symbol? index) (str "First argument must be a symbol, instead got: " (pr-str index)))
  `(test/deftest ~(symbol (str (name index) "-test"))
     (prn (test-index ~index ~opts))))
