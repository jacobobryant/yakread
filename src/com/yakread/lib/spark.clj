(ns com.yakread.lib.spark
  (:require
   [clojure.tools.logging :as log]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
   [com.yakread.lib.ads :as lib.ads]
   [com.yakread.lib.pathom :refer [? process]]
   [xtdb.api :as xt :refer [q]])
  (:import
   [com.yakread AverageRating]
   [java.time Instant Period]
   [org.apache.spark.api.java JavaSparkContext]
   [org.apache.spark.mllib.recommendation ALS Rating]
   [scala.reflect ClassTag$]))

(defn- median [xs]
  (first (take (/ (count xs) 2) xs)))

(defresolver item-candidates [{:keys [biff/db]} _]
  {::pco/output [{::item-candidates [:xt/id
                                     :item/url]}]}
  {::item-candidates
   (mapv first
         (q db
            '{:find [(pull direct-item [:xt/id :item/url])]
              :in [approved]
              :timeout 999999
              :where [[direct-item :item.direct/candidate-status approved]]}
            :approved))})

(defresolver ads [{:biff/keys [db now]} _]
  {::pco/output [{::all-ads [:xt/id]}
                 {::ad-candidates [:xt/id]}]}
  (let [all-ads (mapv first
                      (q db
                         '{:find [(pull ad [*])]
                           :where [[ad :ad/user]]}))
        ad->recent-cost (q db
                           '{:find [ad (sum cost)]
                             :in [t]
                             :where [[click :ad.click/ad ad]
                                     [click :ad.click/cost cost]
                                     [click :ad.click/created-at created-at]
                                     [(< t created-at)]]}
                           (.minus now (Period/ofWeeks 1)))
        all-ads (mapv (fn [ad]
                        (assoc ad :ad/recent-cost (get ad->recent-cost (:xt/id ad) 0)))
                      all-ads)]
    {::all-ads all-ads
     ::ad-candidates (filterv lib.ads/active? all-ads)}))

(defresolver ad-ratings [{:keys [biff/db]} {::keys [all-ads]}]
  {::pco/input [{::all-ads [:xt/id]}]
   ::pco/output [{::ad-ratings [:rating/candidate
                                :rating/user
                                :rating/value
                                :rating/created-at]}]}
  (let [ad-ids (mapv :xt/id all-ads)
        skip-info (q db
                     '{:find [ad user (count skip) (max t)]
                       :keys [ad user n-skips last-skipped]
                       :in [[ad ...]]
                       :where [[skip :skip/items ad]
                               [skip :skip/user user]
                               [skip :skip/timeline-created-at t]]}
                     ad-ids)
        click-info (q db
                      '{:find [ad user (max t)]
                        :keys [ad user last-clicked]
                        :in [[ad ...]]
                        :where [[click :ad.click/user user]
                                [click :ad.click/ad ad]
                                [click :ad.click/created-at t]]}
                      ad-ids)
        interaction-info (->> (concat skip-info click-info)
                              (group-by (juxt :ad :user))
                              (vals)
                              (mapv #(apply merge %)))]
    {::ad-ratings (vec
                   (for [{:keys [ad user n-skips last-skipped last-clicked]} interaction-info]
                     {:rating/candidate ad
                      :rating/user user
                      :rating/value (if last-clicked
                                      0.75
                                      (max 0 (- 0.5 (* 0.1 n-skips))))
                      :rating/created-at (or last-clicked last-skipped)}))}))

(defresolver dedupe-item-id [{:keys [biff/db]} {::keys [item-candidates]}]
  {::pco/input [{::item-candidates [:xt/id
                                    :item/url]}]
   ::pco/output [::dedupe-item-id]}
  (let [item-id->url (into {}
                           (q db
                              '{:find [item url]
                                :in [[url ...]]
                                :where [[item :item/url url]]}
                              (mapv :item/url item-candidates)))
        url->item-candidate-id (into {}
                                     (map (juxt :item/url :xt/id))
                                     item-candidates)]
    {::dedupe-item-id (comp url->item-candidate-id item-id->url)}))

(defresolver item-ratings [{:keys [biff/db]} {::keys [item-candidates dedupe-item-id]}]
  {::pco/input [{::item-candidates [:xt/id
                                    :item/url]}
                ::dedupe-item-id]
   ::pco/output [{::item-ratings [:rating/user
                                  :rating/candidate
                                  :rating/value
                                  :rating/created-at]}]}
  (let [all-item-ids (mapv first
                           (q db
                              '{:find [item]
                                :in [[url ...]]
                                :where [[item :item/url url]]}
                              (mapv :item/url item-candidates)))
        dedupe-usit (fn [usit]
                      (update usit :user-item/item dedupe-item-id))
        usit-key (juxt :user-item/user :user-item/item)
        item-usits (->> (q db
                           '{:find [(pull usit [*])]
                             :in [[item ...]]
                             :where [[usit :user-item/item item]]
                             :timeout 120000}
                           all-item-ids)
                        (mapv (comp dedupe-usit first))
                        (group-by usit-key)
                        (vals)
                        (mapv #(apply merge %)))
        skip-usits (->> (q db
                           '{:find [user item (count skip) (max t)]
                             :keys [user-item/user
                                    user-item/item
                                    user-item/skips
                                    user-item/skipped-at]
                             :in [[item ...]]
                             :where [[skip :skip/items item]
                                     [skip :skip/user user]
                                     [skip :skip/timeline-created-at t]]}
                           all-item-ids)
                        (mapv dedupe-usit)
                        (group-by usit-key)
                        (vals)
                        (mapv (fn [usits]
                                (apply merge-with #(if (number? %1) (+ %1 %2) %2) usits))))
        combined-usits (vals (merge-with merge
                                         (into {} (map (juxt usit-key identity) item-usits))
                                         (into {} (map (juxt usit-key identity) skip-usits))))]
    {::item-ratings
     (vec (for [usit combined-usits
                :let [[created-at value]
                      (some (fn [[k value]]
                              (when-some [t (k usit)]
                                [t value]))
                            [[:user-item/favorited-at 1]
                             [:user-item/disliked-at 0]
                             [:user-item/reported-at 0]
                             [:user-item/viewed-at 0.75]
                             [:user-item/skipped-at (->> (:user-item/skips usit 0)
                                                         (* 0.1)
                                                         (- 0.5)
                                                         (max 0))]
                             [:user-item/bookmarked-at 0.6]])]
                :when value]
            {:rating/user (:user-item/user usit)
             :rating/candidate (:user-item/item usit)
             :rating/value value
             :rating/created-at created-at}))}))

(defresolver spark-model [{:keys [yakread/spark]}
                          {::keys [item-ratings ad-ratings item-candidates all-ads]}]
  {::pco/input [{::item-ratings [:rating/user
                                 :rating/candidate
                                 :rating/value]}
                {::ad-ratings [:rating/user
                               :rating/candidate
                               :rating/value]}
                {::item-candidates [:xt/id]}
                {::all-ads [:xt/id]}]
   ::pco/output [::predict-fn]}
  (let [all-ratings (concat item-ratings ad-ratings)
        [[index->candidate candidate->index]
         [_ user->index]] (for [k [:rating/candidate :rating/user]
                                :let [index->x (->> all-ratings
                                                    (mapv k)
                                                    distinct
                                                    (map-indexed vector)
                                                    (into {}))]]
                            [index->x (into {} (map (fn [[k v]] [v k])) index->x)])
        spark-ratings (->> (for [{:rating/keys [user candidate value]} all-ratings]
                             (Rating. (int (user->index user))
                                      (int (candidate->index candidate))
                                      (double value)))
                           (.parallelize spark)
                           (.rdd)
                           (.cache))
        rank 10
        iterations 20
        lambda 0.1
        alpha 0.05
        _ (log/info "training ALS")
        als (when (not-empty all-ratings)
              (ALS/trainImplicit spark-ratings rank iterations lambda alpha))
        _ (log/info "done training ALS")]
    {::predict-fn (fn [user-id]
                    (let [candidate->score
                          (into {}
                                (map (fn [^Rating rating]
                                       [(index->candidate (.product rating))
                                        (.rating rating)]))
                                (when-let [user-idx (and als (user->index user-id))]
                                  (.recommendProducts als user-idx (count index->candidate))))]
                      (fn [candidate-id candidate-type]
                        (or (candidate->score candidate-id)
                            0.1))))}))

(defresolver get-candidates [{::keys [item-ratings
                                      ad-ratings
                                      item-candidates
                                      ad-candidates
                                      predict-fn]}]
  {::pco/input [{::item-ratings [:rating/candidate
                                 :rating/user
                                 :rating/value
                                 :rating/created-at]}
                {::ad-ratings [:rating/candidate
                               :rating/user
                               :rating/value
                               :rating/created-at]}
                {::item-candidates [:xt/id]}
                {::ad-candidates [:xt/id]}
                ::predict-fn]
   ::pco/output [:yakread.model/get-candidates]}
  (let [all-ratings (concat item-ratings ad-ratings)
        candidate->ratings (group-by :rating/candidate all-ratings)
        candidate->n-ratings (update-vals candidate->ratings count)
        candidate->last-liked (update-vals candidate->ratings
                                           (fn [ratings]
                                             (->> ratings
                                                  (keep (fn [{:rating/keys [value created-at]}]
                                                          (when (< 0.5 value)
                                                            created-at)))
                                                  (apply max-key
                                                         inst-ms
                                                         (Instant/ofEpochMilli 0)))))]
    (log/info "done")
    {:yakread.model/get-candidates
     (fn [user-id]
       (let [predict (predict-fn user-id)]
         (into {}
               (for [[type* candidates] [[:item item-candidates]
                                         [:ad ad-candidates]]]
                 [type*
                  (->> (for [{:keys [xt/id]} candidates]
                         {:xt/id id
                          :candidate/type type*
                          :candidate/score (predict id type*)
                          :candidate/last-liked (get candidate->last-liked
                                                     id
                                                     (Instant/ofEpochMilli 0))
                          :candidate/n-ratings (get candidate->n-ratings id 0)})
                       (sort-by (juxt :candidate/n-ratings
                                      (comp - inst-ms :candidate/last-liked)))
                       vec)]))))}))

(defresolver item-candidate-ids [{::keys [item-candidates]}]
  {:yakread.model/item-candidate-ids (into #{} (map :xt/id) item-candidates)})

(defresolver all-liked-items [{:keys [biff/db]} _]
  {::pco/output [{:yakread.model/all-liked-items
                  [:item/id :item/n-likes]}]}
  {:yakread.model/all-liked-items
   (vec (q db
           '{:find [item (count usit)]
             :keys [item/id item/n-likes]
             :timeout 240000
             :order-by [[(count usit) :desc]]
             :where [[usit :user-item/item item]
                     [usit :user-item/favorited-at]]}))})

(def ^:private pathom-env (pci/register [item-candidates
                                         ads
                                         ad-ratings
                                         dedupe-item-id
                                         item-ratings
                                         spark-model
                                         get-candidates
                                         item-candidate-ids
                                         all-liked-items]))

(defn new-model [ctx]
  (log/info "updating model")
  (merge {:yakread.model/item-candidate-ids #{}
          :yakread.model/get-candidates (constantly {})}
         (process (merge ctx pathom-env {:biff/now (Instant/now)})
                  {}
                  [(? :yakread.model/item-candidate-ids)
                   (? :yakread.model/get-candidates)
                   {:yakread.model/all-liked-items [:item/id :item/n-likes]}])))

(defn use-spark [ctx]
  (let [spark (doto (JavaSparkContext. "local[*]" "yakread")
                (.setCheckpointDir "storage/spark-checkpoint"))
        ctx (assoc ctx :yakread/spark spark)]
    (-> ctx
        (assoc :yakread/model (atom (new-model ctx)))
        (update :biff/stop conj #(.close spark)))))

(comment
  (-> @(:yakread/model @com.yakread/system)
      :yakread.model/get-candidates
      )

  (time (do (new-model (repl/context))
          :done))

  (do (reset! (:yakread/model (repl/context))
              (new-model (repl/context)))
    :done)

  )
