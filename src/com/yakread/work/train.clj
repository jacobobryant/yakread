(ns com.yakread.work.train
  (:require
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.yakread.util.biff-staging :as biffs]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.item :as lib.item]
   [com.yakread.lib.spark :as lib.spark]))

(defn retrain [{:keys [yakread/model] :as ctx}]
  (reset! model (lib.spark/new-model ctx)))

(fx/defmachine add-candidate!
  (lib.item/add-item-machine*
   {:get-url    (comp :item/url :biff/job)
    :on-success (fn [_ctx item]
                  ;(log/info "Ingested candidate" (pr-str (:item/url item)))
                  {})
    :on-error   (fn [{:keys [biff/now]} {:keys [item/url biff.fx/http]}]
                  (let [{:keys [status]} (ex-data (:exception http))]
                    (if (= status 429)
                      (do
                        (log/warn "Received status 429 when fetching candidate" url)
                        {:biff.fx/sleep 10000})
                      [{:biff.fx/tx [[:put-docs :item
                                      {:xt/id (biffs/gen-uuid "0000")
                                       :item/url url
                                       :item/ingested-at now
                                       :item/doc-type :item/direct
                                       :item.direct/candidate-status :ingest-failed}]]}
                       {:biff.fx/sleep 2000}])))}))

(fx/defmachine queue-add-candidate
  :start
  (fn [{:keys [biff/conn biff/queues yakread.work.queue-add-candidate/enabled]}]
    (when-let [urls (and enabled
                         (= 0 (.size (:work.train/add-candidate queues)))
                         (->> {:select :item/url
                               :from :item
                               :where [:in
                                       :item/url
                                       {:select :item/url
                                        :from :item
                                        :join [:user-item [:= :item._id :user-item/item]]
                                        :where [:is-not :user-item/favorited-at nil]}]
                               :having [:not [:bool_or [:coalesce [:= :item/doc-type "item/direct"] false]]]}
                              (biffx/q conn)
                              (mapv :item/url)
                              not-empty))]
      (log/info "Found" (count urls) "candidate URLs")
      {:biff.fx/queue {:jobs (for [url urls]
                               [:work.train/add-candidate {:item/url url}])}})))

(def module
  {:tasks [{:task     #'retrain
            :schedule (lib.core/every-n-minutes 60)}
           {:task     #'queue-add-candidate
            :schedule (lib.core/every-n-minutes (* 60 12))}]
   :queues [{:id        :work.train/add-candidate
             :consumer  #'add-candidate!
             :n-threads 1}]})

(comment
  (time
   (do
    (retrain (biff/merge-context @com.yakread/system))
    :done))

  )
