(ns jolt.codec.record-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]))

(defrecord Pair [a b])
(defrecord Empty [])
(defrecord Wide [a b c d e f g h i j])
(def calls (atom 0))
(defrecord CustomSeq [a]
  clojure.lang.Seqable
  (seq [_] (swap! calls inc) (seq {:changed true})))
(deftype NotAMap [a])
(defn fails? [f] (try (f) false (catch Throwable _ true)))

(deftest records-fold-in-declared-then-extension-order
  (let [walk (traversal/open-traversal)
        values [(->Empty) (assoc (->Empty) :extension false)
                (->Pair nil false) (with-meta (->Pair 1 false) {:ignored true})
                (reduce (fn [r i] (assoc r (str "extension-" i) i)) (->Pair 1 false) (range 20))
                (->Wide 0 1 2 3 4 5 6 7 8 9)]]
    (doseq [value values]
      (is (= (mapv vec (seq value))
             (traversal/fold-map walk value (fn [k v a] (conj a [k v])) []))))
    (let [seen (atom []) failure (ex-info "visitor" {})]
      (is (identical? failure
            (try (traversal/fold-map walk (->Pair 1 2)
                   (fn [k _ _] (swap! seen conj k) (throw failure)) nil)
                 nil (catch Throwable e e))))
      (is (= [:a] @seen)))
    (is (= [[:a [[:a 3] [:b 4]]] [:b [[:a 3] [:b 4]]]]
           (traversal/fold-map walk (->Pair 1 2)
             (fn [k _ a] (conj a [k (traversal/fold-map walk (->Pair 3 4)
                                      (fn [k v a] (conj a [k v])) [])])) [])))))

(deftest record-json-keeps-field-order-and-values-without-map-copy
  (let [plain (codec/open-codec) cached (codec/open-codec {:flat-map-cache? true})]
    (doseq [encoder [plain cached]]
      (is (= "{}" (codec/write-nested encoder (->Empty))))
      (is (= "{\"a\":null,\"b\":false}" (codec/write-nested encoder (->Pair nil false))))
      (is (= "{\"a\":0,\"b\":1,\"c\":2,\"d\":3,\"e\":4,\"f\":5,\"g\":6,\"h\":7,\"i\":8,\"j\":9}"
             (codec/write-nested encoder (->Wide 0 1 2 3 4 5 6 7 8 9))))
      (let [value (assoc (->Pair 1 false) :extra 2 :another 3)
            fields (mapv (fn [[k v]] (str "\"" (name k) "\":" v)) (seq value))
            expected (str "{" (str/join "," fields) "}")]
        (is (= expected (codec/write-nested encoder value)))))))

(deftest records-reject-custom-seq-and-unsupported-field-values
  (let [walk (traversal/open-traversal) encoder (codec/open-codec)]
    (reset! calls 0)
    (is (fails? #(traversal/fold-map walk (->CustomSeq 1) (fn [& _] nil) nil)))
    (is (fails? #(codec/write-nested encoder (->CustomSeq 1))))
    (is (zero? @calls))
    (is (fails? #(codec/write-nested encoder (NotAMap. 1))))
    (is (fails? #(codec/write-nested encoder (->Pair (Object.) 2))))
    (is (fails? #(codec/encode-prefixed-records encoder "" [(->Pair 1 2)] identity 1)))
    (is (fails? #(codec/write-nested encoder
                   (reduce (fn [r _] (->Pair r false)) nil (range 130)))))))

(deftest records-work-in-nested-text-scopes-without-cache-aliasing
  (let [encoder (codec/open-codec {:flat-map-cache? true}) value (->Pair 1 false)
        baseline (String. (codec/encode-prefixed-records encoder "" [value]
                            (fn [v] [(codec/prepare-nested-text encoder v)]) 1000) "UTF-8")]
    (codec/with-nested-text-writer encoder
      (fn [prepare]
        (is (= baseline (String. (codec/encode-prefixed-records encoder "" [value]
                                  (fn [v] [(prepare v)]) 1000) "UTF-8")))
        (is (not= baseline (String. (codec/encode-prefixed-records encoder "" [(->Pair 2 false)]
                                     (fn [v] [(prepare v)]) 1000) "UTF-8")))
        (is (= baseline (String. (codec/encode-prefixed-records encoder "" [value]
                                  (fn [v] [(prepare v)]) 1000) "UTF-8")))))))
