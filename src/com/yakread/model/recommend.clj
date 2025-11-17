(ns com.yakread.model.recommend
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff :refer [q]]
   [com.rpl.specter :as sp]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.pathom :as lib.pathom]
   [edn-query-language.core :as eql]
   [lambdaisland.uri :as uri]))

(def n-skipped (some-fn :item/n-skipped :item/n-skipped-with-digests))

;; TODO for further optimization:
;; - figure out why com.yakread.util.biff-staging/entity-resolver gets called 3,000 times in dev
;;   (pathom overhead is 50%)

(defn interleave-uniform [& colls]
  (lazy-seq
   (when-some [colls (not-empty (filter not-empty colls))]
     (let [[coll-a & coll-rest] (gen/shuffle colls)]
       (cons (first coll-a)
             (apply interleave-uniform (cons (rest coll-a) coll-rest)))))))

(defn take-rand
  ([xs]
   (take-rand 1 xs))
  ([minimum xs]
   (take (max minimum (* (gen/double) (count xs))) xs)))

(def n-sub-bookmark-recs 22)
(def n-for-you-recs 30)
(def n-icymi-recs 5)
(def n-digest-discover-recs 5)

(defn- skip->interaction [skipped-at]
  {:action :skipped
   :t skipped-at})

(defn usit->interaction [usit]
  (some (fn [[attr action]]
          (when-some [t (attr usit)]
            {:action action
             :t t}))
        [[:user-item/favorited-at :favorited]
         [:user-item/reported-at  :reported]
         [:user-item/disliked-at  :disliked]
         [:user-item/viewed-at    :viewed]]))

(def interaction->score
  {:skipped   -1
   :favorited  10
   :reported  -20
   :disliked  -10
   :viewed     2})

;; Forgetting curve
(let [;; S is set so that the first 10 out of 100 interactions have about 50% of the weight.
      S 15]
  (defn weight [index]
    (Math/exp (/ (- index) S))))

(comment
  ;; First 25 items (out of 400) have 23% of the weight; first 200 items have 88%
  (let [weights (mapv weight (range 400))
        total (apply + weights)]
    [(/ (apply + (take 25 weights)) total) ; want around 25%
     (/ (apply + (take 200 weights)) total) ; want around 80%
     ])) ; [0.22532621043101672 0.8807970779778829]

;; I didn't end up using this function, but seems like a shame to delete it.
#_(defn max-n-by [n f xs]
    (let [step (fn [pq x]
                 (let [priority (f x)]
                   (cond
                     (< (count pq) n) (assoc pq x priority)
                     (> priority (val (peek pq))) (-> pq
                                                      (dissoc (key (peek pq)))
                                                      (assoc x priority))
                     :else pq)))]
      (->> xs
           (reduce step (pm/priority-map))
           (sort-by val >)
           (mapv key))))

(defresolver sub-affinity*
  "Returns the 10 most recent interactions (e.g. viewed, liked, etc) for a given sub."
  [{:keys [biff/db]} subscriptions]
  {::pco/input [:sub/id
                :sub/title
                {:sub/user [:xt/id]}
                {:sub/items [:xt/id]}]
   ::pco/output [:sub/new
                 :sub/affinity-low*
                 :sub/affinity-high*
                 :sub/n-interactions]
   ::pco/batch? true}
  (let [q-inputs             (for [{:sub/keys [id user items]} subscriptions
                                   item items]
                               [(:xt/id user) id (:xt/id item)])
        user+sub->skips      (->> (q db
                                     '{:find [user sub t skip item]
                                       :in [[[user sub item]]]
                                       :where [[skip :skip/user user]
                                               [skip :skip/items item]
                                               [skip :skip/timeline-created-at t]]}
                                     q-inputs)
                                  (lib.core/group-by-to (juxt first second) #(nth % 2)))
        user+sub->user-items (->> (q db
                                     '{:find [user sub (pull usit [*])]
                                       :in [[[user sub item]]]
                                       :where [[usit :user-item/item item]
                                               [usit :user-item/user user]]}
                                     q-inputs)
                                  (lib.core/group-by-to (juxt first second) #(nth % 2)))]
    (mapv (fn [{:sub/keys [id user] :as sub}]
            (let [user-items   (get user+sub->user-items [(:xt/id user) id])
                  skips        (get user+sub->skips [(:xt/id user) id])
                  interactions (concat (mapv skip->interaction skips)
                                       (keep usit->interaction user-items))
                  scores       (->> interactions
                                    (sort-by :t #(compare %2 %1))
                                    (map-indexed (fn [i {:keys [action]}]
                                                   (* (interaction->score action)
                                                      (weight i)))))
                  seed-weight  (weight (count scores))

                  {alpha true beta false :or {alpha 0 beta 0}}
                  (update-vals (group-by pos? scores) #(Math/abs (apply + %)))

                  affinity     (fn [seed-alpha seed-beta]
                                 (let [alpha (+ alpha (* seed-alpha seed-weight))
                                       beta (+ beta (* seed-beta seed-weight))]
                                   (/ alpha (+ alpha beta))))]
              (merge sub
                     {:sub/all-interactions interactions ; for debugging/testing
                      :sub/n-interactions (count interactions)
                      :sub/scores scores
                      :sub/new (empty? interactions)
                      :sub/affinity-low* (affinity 0 5)
                      :sub/affinity-high* (affinity 5 0)})))
          subscriptions)))

(defn rerank
  "Shuffles xs with a bias toward keeping elements close to their original places. When p=1, returns xs in
   the original order; when p=0, does an unbiased shuffle. Returns a lazy sequence."
  [p xs]
  ((fn step [xs]
     (when (not-empty xs)
       (lazy-seq
        (let [i (or (some #(when (< (gen/double) p) %)
                          (range (count xs)))
                    (long (* (gen/double) (count xs))))]
          (cons (get xs i)
                (step (into (subvec xs 0 i) (subvec xs (inc i)))))))))
   (vec xs)))

(defn rank-by-freshness [items]
  (->> items
       (sort-by (juxt n-skipped (comp - inst-ms :item/ingested-at)))
       (rerank 0.1)))

(defresolver unread-subs [{:user/keys [subscriptions]}]
  {::pco/input [{:user/subscriptions [:xt/id :sub/unread]}]
   ::pco/output [{:user/unread-subscriptions [:xt/id]}]}
  {:user/unread-subscriptions (filterv #(not= 0 (:sub/unread %)) subscriptions)})

(defresolver selected-subs [{:user/keys [unread-subscriptions]}]
  {::pco/input [{:user/unread-subscriptions [:xt/id
                                             :sub/doc-type
                                             {(? :sub/mv) [(? :mv.sub/affinity-low)
                                                           (? :mv.sub/affinity-high)]}
                                             (? :sub/pinned-at)
                                             (? :sub/published-at)]}]
   ::pco/output [{:user/selected-subs [:xt/id
                                       :item/rec-type]}]}
  (let [new? (fn [sub] (and (= (:sub/doc-type sub) :sub/email)
                            (nil? (get-in sub [:sub/mv :mv.sub/affinity-low]))))
        {new-subs true old-subs false} (group-by new? (gen/shuffle unread-subscriptions))
        ;; We'll always show new subs first e.g. so the user will see any confirmation emails.
        new-subs (sort-by :sub/published-at #(compare %2 %1) new-subs)
        old-subs (concat (drop 3 new-subs) old-subs)
        new-subs (->> new-subs
                      (take 3)
                      (mapv #(assoc % :item/rec-type :item.rec-type/new-subscription)))
        {pinned true unpinned false} (->> (interleave-uniform
                                           ;; Do a mix of explore and exploit
                                           (sort-by #(get-in % [:sub/mv :mv.sub/affinity-low] 0.0) > old-subs)
                                           (sort-by #(get-in % [:sub/mv :mv.sub/affinity-high] 1.0) > old-subs))
                                          distinct
                                          (map-indexed (fn [i sub]
                                                         (assoc sub ::rank i)))
                                          (group-by (comp some? :sub/pinned-at)))
        ;; For icymi recs, we can't in this resolver filter out subs whose only unread items are
        ;; already being included in :user/digest-sub-items because :sub/new is based an
        ;; materialized data. So we return at least twice the number of subs the digest will
        ;; actually need, and in the next resolver we'll filter out subs if necessary.
        n-recs (max n-sub-bookmark-recs (* n-icymi-recs 2))
        ;; Interleave pinned and unpinned back into one sequence. Basically we sort by affinity,
        ;; but on each selection, the next pinned item has a 1/3 chance of being selected even if it has
        ;; lower affinity.
        ranked-subs (->> ((fn step [pinned unpinned]
                            (lazy-seq
                             (cond
                               (empty? pinned)
                               unpinned

                               (empty? unpinned)
                               pinned

                               (or (< (gen/double) 1/3)
                                   (<= (::rank (first pinned))
                                       (::rank (first unpinned))))
                               (cons (first pinned)
                                     (step (rest pinned) unpinned))

                               :else
                               (cons (first unpinned)
                                     (step pinned (rest unpinned))))))
                          pinned
                          unpinned)
                         (rerank 0.1)
                         (take (- n-recs (count new-subs)))
                         (mapv #(assoc % :item/rec-type :item.rec-type/subscription)))
        results (vec (concat (take n-recs new-subs) ranked-subs))]
    {:user/selected-subs results}))

(defn sub-recs-resolver [{:keys [op-name output-key extra-input wrap-input n-skipped-key]
                          :or {wrap-input identity}}]
  (pco/resolver
   op-name
   {::pco/input (eql/merge-queries
                 [{:user/selected-subs
                   [:item/rec-type
                    :sub/title
                    {:sub/unread-items
                     [:item/id
                      :item/ingested-at
                      n-skipped-key]}]}]
                 extra-input)
    ::pco/output [{output-key [:item/id
                               :item/rec-type]}]}
   (fn [_env input]
     (let [{:user/keys [selected-subs]} (wrap-input input)]
       {output-key
        (vec
         (for [{:keys [sub/unread-items item/rec-type]} selected-subs
               ;; This shouldn't be necessary, but apparently there's a bug in the :sub/unread indexer or
               ;; something.
               :when (< 0 (count unread-items))
               :let [most-recent (apply max-key (comp inst-ms :item/ingested-at) unread-items)
                     item (if (= 0 (get most-recent n-skipped-key))
                            most-recent
                            (->> unread-items
                                 rank-by-freshness
                                 first))]]
           (assoc item :item/rec-type rec-type)))}))))

(def for-you-sub-recs
  (sub-recs-resolver
   {:op-name `for-you-sub-recs
    :output-key :user/for-you-sub-recs
    :n-skipped-key :item/n-skipped}))

(def icymi-sub-recs
  (sub-recs-resolver
   {:op-name `icymi-sub-recs
    :output-key :user/icymi-sub-recs
    :extra-input [{:user/digest-sub-items [:xt/id]}]
    :n-skipped-key :item/n-skipped-with-digests
    :wrap-input (fn [input]
                  (let [exclude (into #{} (map :xt/id) (:user/digest-sub-items input))]
                    (->> input
                         (sp/setval [:user/selected-subs
                                     sp/ALL
                                     :sub/unread-items
                                     sp/ALL
                                     (comp exclude :item/id)]
                                    sp/NONE)
                         (sp/setval [:user/selected-subs sp/ALL (comp empty? :sub/unread-items)]
                                    sp/NONE))))}))

(defn bookmark-recs-resolver [{:keys [op-name
                                      output-key
                                      n-skipped-key
                                      extra-input
                                      wrap-input
                                      n-recs]
                               :or {wrap-input identity}}]
  (pco/resolver
   op-name
   {::pco/input (eql/merge-queries
                 [{:user/unread-bookmarks [:item/id
                                           :item/ingested-at
                                           n-skipped-key
                                           (? :item/url)]}]
                 extra-input)
    ::pco/output [{output-key [:item/id :item/rec-type]}]}
   (fn [_env input]
     (let [{:user/keys [unread-bookmarks]} (wrap-input input)]
       {output-key
        (into []
              (comp (lib.core/distinct-by (some-fn (comp :host uri/uri :item/url) :item/id))
                    (map #(assoc % :item/rec-type :item.rec-type/bookmark))
                    (take n-recs))
              (rank-by-freshness unread-bookmarks))}))))

(def for-you-bookmark-recs
  (bookmark-recs-resolver
   {:op-name `for-you-bookmark-recs
    :output-key :user/for-you-bookmark-recs
    :n-skipped-key :item/n-skipped
    :n-recs n-sub-bookmark-recs}))

(def icymi-bookmark-recs
  (bookmark-recs-resolver
   {:op-name `icymi-bookmark-recs
    :output-key :user/icymi-bookmark-recs
    :n-skipped-key :item/n-skipped-with-digests
    :n-recs n-icymi-recs
    :extra-input {:user/digest-bookmarks [:xt/id]}
    :wrap-input (fn [{:user/keys [unread-bookmarks digest-bookmarks] :as input}]
                  (let [exclude (into #{} (map :xt/id) digest-bookmarks)]
                    (assoc input :user/unread-bookmarks (into []
                                                              (remove (comp exclude :item/id))
                                                              unread-bookmarks))))}))

(defn- pick-by-skipped [a-items b-items]
  ((fn step [a-items b-items]
     (lazy-seq
      (cond
        (empty? a-items)
        b-items

        (empty? b-items)
        a-items

        :else
        (let [a-skipped (n-skipped (first a-items))
              b-skipped (n-skipped (first b-items))
              choice (if (< (gen/double) 0.25)
                       (gen/rand-nth [:a :b])
                       (gen/weighted {:a (inc b-skipped)
                                      :b (inc a-skipped)}))
              [choice-items other-items] (cond->> [a-items b-items]
                                           (= choice :b) reverse)]
          (cons (first choice-items)
                (step (rest choice-items)
                      (concat (rest other-items)
                              (take 1 other-items))))))))
   a-items
   b-items))

(defresolver candidates [{:keys [biff/db
                                 yakread.model/get-candidates]}
                         {user-id :user/id}]
  {::pco/input [(? :user/id)]
   ::pco/output (vec
                 (for [k [:user/item-candidates
                          :user/ad-candidates]]
                   {k [:xt/id
                       :candidate/type
                       :candidate/score
                       :candidate/last-liked
                       :candidate/n-skips]}))}
  (let [{:keys [item ad]} (get-candidates user-id)
        all-candidates (concat item ad)
        ;; TODO can we just use :item/n-skipped for this?
        item-id->n-skips (into {}
                               (when user-id
                                 (q db
                                    '{:find [item (count skip)]
                                      :in [user [item ...]]
                                      :where [[skip :skip/user user]
                                              [skip :skip/items item]]}
                                    user-id
                                    (mapv :xt/id all-candidates))))
        assoc-n-skips #(assoc % :candidate/n-skips (get item-id->n-skips (:xt/id %) 0))]
    {:user/item-candidates (mapv assoc-n-skips item)
     :user/ad-candidates (mapv assoc-n-skips ad)}))

(defresolver candidate-digest-skips [{:keys [item/n-digest-sends candidate/n-skips]}]
  {:candidate/n-skips-with-digests (+ n-digest-sends n-skips)})

(defresolver item-digest-skips [{:keys [item/n-digest-sends item/n-skipped]}]
  {:item/n-skipped-with-digests (+ n-digest-sends n-skipped)})

(defn discover-recs-resolver [{:keys [op-name output-key n-skips-key n-recs]}]
  (pco/resolver
   op-name
   {::pco/input [(? :user/id)
                 {:user/item-candidates [:xt/id
                                         :item/url
                                         :candidate/score
                                         :candidate/last-liked
                                         n-skips-key]}]
    ::pco/output [{output-key [:xt/id
                               :item/rec-type]}]}
   (fn [{:keys [biff/db]}
        {user-id :user/id
         candidates :user/item-candidates}]
     (let [;; TODO use an LRU cache probably
           read? (fn [url]
                   (boolean
                    (not-empty
                     (q db
                        '{:find [url]
                          :in [user url]
                          :where [[usit :user-item/user user]
                                  [usit :user-item/item item]
                                  [item :item/url url]
                                  (or [usit :user-item/viewed-at _]
                                      [usit :user-item/favorited-at _]
                                      [usit :user-item/disliked-at _]
                                      [usit :user-item/reported-at _])]}
                        user-id
                        url))))

           candidates (vec candidates) ; does this matter?
           url->host (into {} (map (juxt :item/url (comp :host uri/uri :item/url))) candidates)

           recommendations
           (loop [selected []
                  candidates candidates]
             (if (or (<= n-recs (count selected)) (empty? candidates))
               selected
               (let [selection (if (< (gen/double) 0.1)
                                 (gen/rand-nth candidates)
                                 (->> candidates
                                      take-rand
                                      (sort-by (comp - inst-ms :candidate/last-liked))
                                      take-rand
                                      gen/shuffle
                                      (sort-by (fn [{n-skips n-skips-key
                                                     :candidate/keys [score]}]
                                                 [n-skips (- score)]))
                                      (rerank 0.25)
                                      first))]
                 (if (read? (:item/url selection))
                   (recur selected (vec (remove #{selection} candidates)))
                   (recur (conj selected selection)
                          (filterv (fn [{:keys [item/url]}]
                                     (not= (url->host url)
                                           (url->host (:item/url selection))))
                                   candidates))))))]
    {output-key (mapv #(assoc % :item/rec-type :item.rec-type/discover) recommendations)}))))

(def discover-recs
  (discover-recs-resolver {:op-name `discover-recs
                           :output-key :user/discover-recs
                           :n-skips-key :candidate/n-skips
                           :n-recs n-for-you-recs}))

(def digest-discover-recs
  (discover-recs-resolver {:op-name `digest-discover-recs
                           :output-key :user/digest-discover-recs
                           :n-skips-key :candidate/n-skips-with-digests
                           :n-recs n-digest-discover-recs}))

(defresolver ad-score [{:keys [candidate/score ad/effective-bid]}]
  {:candidate/ad-score (* (max 0.0001 score) effective-bid)})

(defresolver ad-rec [{:keys [biff/db]}
                     {:keys [user/premium]
                      user-id :user/id
                      candidates :user/ad-candidates}]
  {::pco/input [(? :user/id)
                (? :user/premium)
                {:user/ad-candidates [:xt/id
                                      :candidate/n-skips
                                      :candidate/ad-score
                                      :ad/effective-bid
                                      :ad/approve-state
                                      (? :ad/paused)
                                      {:ad/user [:xt/id
                                                 (? :user/email)]}]}]
   ::pco/output [{:user/ad-rec [:xt/id
                                :ad/click-cost
                                :item/rec-type]}]}
  (when-not premium
    (let [clicked-ads (into #{}
                            (map first)
                            (when user-id
                              (q db
                                 '{:find [ad]
                                   :in [user [ad ...]]
                                   :where [[click :ad.click/user user]
                                           [click :ad.click/ad ad]]}
                                 user-id
                                 (mapv :xt/id candidates))))
          [first-ad second-ad] (->> candidates
                                    (remove (fn [{:keys [xt/id] :ad/keys [user paused approve-state]}]
                                              (or (clicked-ads id)
                                                  (not= approve-state :approved)
                                                (= user-id (:xt/id user))
                                                paused
                                                ;; Apparently when people requested to have their
                                                ;; accounts removed, I did so without changing their
                                                ;; ads. So we check here to make sure the ad user's
                                                ;; account wasn't removed.
                                                (nil? (:user/email user)))))
                                  gen/shuffle
                                  (sort-by :candidate/n-skips)
                                  (take-rand 2)
                                  (sort-by :candidate/ad-score >))
        ;; `click-cost` is the minimum amount that (:ad/bid first-ad) could've been while still
        ;; being first. The ad owner will be charged this amount if the user clicks the ad.
        click-cost (when second-ad
                     (max 1 (inc (int (* (:ad/effective-bid first-ad)
                                         (/ (:candidate/ad-score second-ad)
                                            (:candidate/ad-score first-ad)))))))]
    (when second-ad
      {:user/ad-rec (assoc first-ad
                           :ad/click-cost click-cost
                           :item/rec-type :item.rec-type/ad)}))))

(defn- take-items [n xs]
  (->> xs
       (lib.core/distinct-by :xt/id)
       (take n)))


(def runtime-pathom-keys
  [:com.wsscode.pathom3.connect.planner/graph
   :com.wsscode.pathom3.connect.planner/node
   :com.wsscode.pathom3.connect.runner/batch-pending*
   :com.wsscode.pathom3.connect.runner/batch-waiting*
   :com.wsscode.pathom3.connect.runner/graph-run-start-ms
   :com.wsscode.pathom3.connect.runner/node-run-stats*
   :com.wsscode.pathom3.connect.runner/resolver-cache*
   :com.wsscode.pathom3.connect.runner/root-query
   :com.wsscode.pathom3.connect.runner/source-entity
   :com.wsscode.pathom3.entity-tree/entity-tree*
   :com.wsscode.pathom3.path/path])

(defresolver for-you-recs [ctx {:user/keys [id]}]
  #::pco{:input [:user/id]
         :output [{:user/for-you-recs [:xt/id
                                       :item/rec-type
                                       :ad/click-cost]}]}
  (let [cache (:com.wsscode.pathom3.connect.runner/resolver-cache* ctx)
        input (->> [[{(? :user/for-you-sub-recs) [:xt/id
                                                  :item/n-skipped
                                                  :item/rec-type]}]
                    [{(? :user/for-you-bookmark-recs) [:xt/id
                                                       :item/n-skipped
                                                       :item/rec-type]}
                     {:user/discover-recs [:xt/id
                                           :item/rec-type]}
                     {(? :user/ad-rec) [:xt/id
                                        :item/rec-type
                                        :ad/click-cost]}]]
                   (pmap (fn [query]
                           (lib.pathom/process
                            (-> (apply dissoc ctx runtime-pathom-keys)
                                (assoc ::lib.pathom/resolver-cache* cache))
                            {:user/id id}
                            query)))
                   (apply merge))

        {:user/keys [ad-rec for-you-sub-recs for-you-bookmark-recs discover-recs]}
        input

        {new-sub-recs true
         other-sub-recs false} (group-by #(= :item.rec-type/new-subscription (:item/rec-type %))
                                         for-you-sub-recs)
        bookmark-sub-recs (->> (pick-by-skipped for-you-bookmark-recs other-sub-recs)
                               (concat new-sub-recs)
                               (take-items n-sub-bookmark-recs))
        recs (->> (concat (when ad-rec [ad-rec]) bookmark-sub-recs discover-recs)
                  (take-items n-for-you-recs)
                  vec)]
    {:user/for-you-recs recs}))

(defresolver icymi-recs [{:user/keys [icymi-sub-recs icymi-bookmark-recs]}]
  #::pco{:input [{(? :user/icymi-sub-recs) [:xt/id
                                            :item/n-skipped-with-digests
                                            :item/rec-type]}
                 {(? :user/icymi-bookmark-recs) [:xt/id
                                                 :item/n-skipped-with-digests
                                                 :item/rec-type]}]
         :output [{:user/icymi-recs [:xt/id
                                     :item/rec-type]}]}
  {:user/icymi-recs (into []
                          (take n-icymi-recs)
                          (pick-by-skipped icymi-bookmark-recs icymi-sub-recs))})

(def module
  {:resolvers [sub-affinity*
               for-you-sub-recs
               icymi-sub-recs
               for-you-bookmark-recs
               icymi-bookmark-recs
               candidates
               ad-score
               discover-recs
               digest-discover-recs
               ad-rec
               for-you-recs
               unread-subs
               selected-subs
               icymi-recs
               candidate-digest-skips
               item-digest-skips]})

(comment

  (let [ctx (biff/merge-context @com.yakread/system)
        user-id (biff/lookup-id (:biff/db ctx) :user/email "jacob@thesample.ai")]

    #_(lib.pathom/process (assoc-in ctx [:session :uid] user-id)
                        {:user/id user-id}
                        [{:user/sub-recs [:item/title]}]
                        #_[{:user/subscriptions [:sub/affinity-high :sub/title]}]
                        #_[{:user/discover-recs [:item/url]}])

    (->> (lib.pathom/process (assoc-in ctx [:session :uid] user-id)
                             {:user/id user-id}
                             #_[{:user/sub-recs [:item/title]}]
                             [{:user/subscriptions [:sub/affinity-low
                                                    :sub/affinity-high
                                                    :sub/title :sub/n-interactions]}]
                             #_[{:user/discover-recs [:item/url]}])
         :user/subscriptions
         (mapv :sub/n-interactions)
         frequencies
         sort
         #_(sort-by :sub/affinity-high >)
         #_(take 50)
         ))

  )
