(ns com.yakread.util.biff-staging
  (:require [clojure.walk :as walk]
            [com.biffweb :as biff]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.planner :as-alias pcp]
            [malli.core :as malli]
            [malli.registry :as malr]
            [reitit.core :as reitit]
            [xtdb.api :as xt]
            [com.biffweb.protocols :as biff.proto]))

(defn doc-asts [{:keys [registry] :as malli-opts}]
  (for [schema-k (keys (malr/schemas (:registry malli-opts)))
        :let [schema (try (malli/deref-recursive schema-k malli-opts) (catch Exception _))]
        :when schema
        :let [schemas (volatile! [])
              _ (malli/walk schema (fn [schema _ _ _]
                                     (vswap! schemas conj schema)))]
        schema @schemas
        :let [ast (malli/ast schema)]
        :when (and ast
                   (= (:type ast) :map)
                   (contains? (:keys ast) :xt/id))]
    (assoc-in ast [:properties :schema] schema-k)))

(def schema-info
  (memoize
   (fn [malli-opts]
     (let [asts      (doc-asts malli-opts)
           all-attrs (->> asts
                          (mapcat (comp keys :keys))
                          set)
           ref-attrs (->> asts
                          (mapcat :keys)
                          (keep (fn [[k {:keys [properties]}]]
                                  (when (:biff/ref properties)
                                    k)))
                          set)]
       {:all-attrs all-attrs
        :ref-attrs ref-attrs}))))

(defn joinify [malli-opts doc]
  (let [{:keys [ref-attrs]} (schema-info malli-opts)]
    (->> (keys doc)
         (filterv ref-attrs)
         (reduce (fn [doc k]
                   (update doc k #(hash-map :xt/id %)))
                 doc))))

(defn pull-resolvers [malli-opts]
  (let [{:keys [all-attrs ref-attrs]} (schema-info malli-opts)]
    (concat
     [(pco/resolver `entity-resolver
                    {::pco/input [:xt/id]
                     ::pco/output (vec (for [k all-attrs]
                                         (if (ref-attrs k)
                                           {k [:xt/id]}
                                           k)))}
                    (fn [{:keys [biff/db]} {:keys [xt/id]}]
                      (joinify malli-opts (xt/entity db id))))]
     (for [attr ref-attrs
           :let [attr (keyword (namespace attr) (str "_" (name attr)))]]
       (pco/resolver (symbol (subs (str attr) 1))
                     {::pco/input [:xt/id]
                      ::pco/output [{attr [:xt/id]}]}
                     (fn [{:keys [biff/db]} {:keys [xt/id]}]
                       (update (xt/pull db [{attr [:xt/id]}] id)
                               attr
                               vec)))))))

;; TODO maybe use this somewhere
(defn wrap-db-with-index [handler]
  (fn [{:keys [biff/db] :as ctx}]
    (if (satisfies? biff.proto/IndexDatasource db)
      (handler ctx)
      (with-open [db (biff/open-db-with-index ctx)]
        (handler (assoc ctx :biff/db db))))))
