(ns com.yakread.lib.core
  (:require
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [potemkin.collections :as potemkin.c])
  (:import [java.time ZonedDateTime ZoneId]
           [java.security MessageDigest]))

(defn pred->
  "(if (pred x) (f x) x)"
  [x pred f]
  (if (pred x)
    (f x)
    x))

;; https://ask.clojure.org/index.php/12125/add-interleave-all-like-partition-all-is-to-partition
(defn interleave-all
  "Like interleave, but stops when the longest seq is done, instead of
   the shortest."
  {:copyright "Rich Hickey, since this is a modified version of interleave"}
  ([] ())
  ([c1] (lazy-seq c1))
  ([c1 c2]
   (lazy-seq
    (let [s1 (seq c1) s2 (seq c2)]
      (cond
       (and s1 s2) ; there are elements left in both
       (cons (first s1) (cons (first s2)
                              (interleave-all (rest s1) (rest s2))))
       s1 ; s2 is done
       s1
       s2 ; s1 is done
       s2))))
  ([c1 c2 & colls]
   (lazy-seq
    (let [ss (filter identity (map seq (conj colls c2 c1)))]
      (concat (map first ss) (apply interleave-all (map rest ss)))))))

(defn group-by-to [f g xs]
  (update-vals (group-by f xs) #(mapv g %)))

(defn some-vals [m]
  (into {}
        (remove (comp nil? val))
        m))

(defn distinct-by
  "Returns a lazy sequence of the elements of coll with duplicates removed.
  Returns a stateful transducer when no collection is provided."
  {:added "1.0"
   :static true}
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [y (f input)]
            (if (contains? @seen y)
              result
              (do (vswap! seen conj y)
                  (rf result input)))))))))
  ([f coll]
   (let [step (fn step [xs seen]
                (lazy-seq
                  ((fn [[input :as xs] seen]
                     (when-let [s (seq xs)]
                       (let [y (f input)]
                         (if (contains? seen y)
                           (recur (rest s) seen)
                           (cons input (step (rest s) (conj seen y)))))))
                   xs seen)))]
     (step coll #{}))))

(defn every-n-minutes [n]
  (fn []
    (iterate #(.plusSeconds % (* 60 n)) (java.time.Instant/now))))

(potemkin.c/def-map-type DerefMap [m]
  (get [_ k default-value] (get @m k default-value))
  (assoc [_ k v] (assoc @m k v))
  (dissoc [_ k] (dissoc @m k))
  (keys [_] (keys @m))
  (meta [_] (meta @m))
  (empty [_] {})
  (with-meta [_ new-meta] (with-meta @m new-meta))
  clojure.lang.IDeref
  (deref [this] @m))

(defmethod clojure.core/print-method DerefMap [x writer]
  (print-method @x writer))

(prefer-method pprint/simple-dispatch clojure.lang.IPersistentMap clojure.lang.IDeref)

(defn something? [x]
  (cond
    (string? x)
    (boolean (not-empty (str/trim x)))

    (coll? x)
    (boolean (not-empty x))

    :else
    (some? x)))

(defn filter-vals [m f]
  (into {} (filter (comp f val)) m))

(defn increasing? [& xs]
  (every? (fn [[a b]] (<= (compare a b) 0))
          (partition 2 1 xs)))

(def epoch (java.time.Instant/ofEpochMilli 0))

(defn fmt-inst [inst fmt timezone]
  (-> inst
      (ZonedDateTime/ofInstant (if (string? timezone)
                                 (ZoneId/of timezone)
                                 timezone))
      (.format (java.time.format.DateTimeFormatter/ofPattern fmt))))

(defn sha256 [s]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes s "UTF-8"))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn restore-order
  "Like com.wsscode.misc.coll/restore-order, but key can be a function instead of just a keyword."
  [inputs key items & [default-fn]]
  (let [index (into {}
                    (map (juxt key identity))
                    items)]
    (into []
          (map (fn [input]
                 (or (get index (key input))
                     (when default-fn
                       (default-fn input))
                     {})))
          inputs)))
