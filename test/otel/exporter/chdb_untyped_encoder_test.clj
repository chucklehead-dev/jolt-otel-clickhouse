(ns otel.exporter.chdb-untyped-encoder-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [jdbc.chdb :as chdb]
            [otel.sdk.export :as export]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-untyped-encoder-native-test :as native-gate]
            [otel.exporter.chdb-test-support :as support]))

(def shape {:name "shape" :start-time-unix-nano 0 :end-time-unix-nano 0
            :events [] :links [] :attributes {} :resource {:attributes {}}})
(defn with-encoder [target spans encoder]
  (with-redefs [exporter/untyped-span-encoder (delay encoder)]
    (export/export-spans! target spans)))

(defn baseline [s] (json/write-str (#'exporter/span-row s nil)))
(defn failed? [f] (try (f) false (catch Exception _ true)))

(deftest native-differential-covers-entire-row-schema
  (let [columns native-gate/selected-columns]
    (is (= 24 (count columns)))
    (is (= (set (keys (#'exporter/span-row shape nil))) (set columns)))
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (native-gate/sha256 "")))))

(deftest exact-parity-and-real-fast-path
  (let [encoder (#'exporter/compile-untyped-span-encoder)
        s shape
        cases [s (dissoc s :events :links :attributes :resource)
               (assoc s :name "a\"\\\nλ😀" :attributes {"bool" true "nil" nil "min" -9223372036854775808 "max" 9223372036854775807})
               (assoc s :events [{:timestamp-unix-nano 1 :name "event" :attributes {"x" "λ"}}])
               (assoc s :attributes {"nested" {"a" [1 2]}})
               (assoc s :attributes {:key "value"})
               (assoc s :links [{:span-context {:trace-id "abc" :span-id "def"} :attributes {"x" 1}}])
               (assoc s :span-context {:trace-state [["a" "b"]]})]]
    (is (ifn? encoder))
    (doseq [span cases] (is (= (baseline span) (encoder span))))
    (let [expected (baseline s)]
      (with-redefs [exporter/span-row (fn [& _] (throw (ex-info "must not materialize" {})))]
        (is (= expected (encoder s)))))
    (let [calls (atom 0) original #'exporter/span-row]
      ;; Save dereferenced root before rebinding.
      (let [row @original]
        (with-redefs [exporter/span-row (fn [& args] (swap! calls inc) (apply row args))]
          (encoder (assoc s :attributes {"nested" [1 2]}))
          (is (= 1 @calls)))))))

(deftest incremental-byte-and-failure-contract
  (let [seen (atom []) encoder (fn [x] (swap! seen conj x) x)]
    (is (= "λ\n" (#'exporter/untyped-span-payload encoder ["λ"] 3)))
    (reset! seen [])
    (is (failed? #(#'exporter/untyped-span-payload encoder ["λ" "later"] 2)))
    (is (= ["λ"] @seen)))
  (let [seen (atom []) encoder (fn [x] (swap! seen conj x)
                               (if (= x :invalid) (throw (ex-info "late invalid" {})) "{}"))]
    (is (failed? #(#'exporter/untyped-span-payload encoder [:first :invalid :later])))
    (is (= [:first :invalid] @seen))))

(deftest untyped-attribute-wire-cache-is-batch-local-and-order-safe
  (let [encoder (#'exporter/compile-untyped-span-encoder)
        same {"stable" "λ"}
        ordered-a (array-map "first" "a" "second" "b")
        ordered-b (array-map "second" "b" "first" "a")
        encode #(#'exporter/untyped-span-payload encoder %)
        baseline-payload #(apply str (map (fn [span] (str (baseline span) "\n")) %))]
    ;; One resource map and one span map are serialized exactly once per
    ;; payload despite appearing in two rows.  The writer sees only data.json
    ;; output; no custom JSON escaping is introduced by this cache.
    (let [calls (atom 0) original @#'exporter/attrs
          spans [(assoc shape :attributes same) (assoc shape :attributes same)]
          expected (baseline-payload spans)]
      (with-redefs [exporter/attrs (fn [m] (swap! calls inc) (original m))]
        (is (= expected (encode spans)))
        (is (= 2 @calls))))
    ;; Equal logical maps with a distinct ordered entry sequence must not share
    ;; a wire cache entry.  data.json follows received iteration order.
    (let [calls (atom 0) original @#'exporter/attrs
          spans [(assoc shape :attributes ordered-a) (assoc shape :attributes ordered-b)]
          expected (baseline-payload spans)]
      (with-redefs [exporter/attrs (fn [m] (swap! calls inc) (original m))]
        (is (= expected (encode spans)))
        (is (= 3 @calls))))))

(defn target [extra]
  (exporter/->ChdbExporter {} false #{:spans}
    (atom (support/exporter-state (merge {:closed-signals #{} :durable? true
                                       :persistence-barrier (fn [_] {:status :committed})} extra)))))

(deftest public-parity-and-typed-fallback
  (let [encoder (#'exporter/compile-untyped-span-encoder)]
    (doseq [projector [nil (fn [_] {"Extra" 7})]]
      (let [calls (atom []) t (target {:typed-span-projector projector})]
        (with-redefs [jdbc/execute! (fn [_ sql] (swap! calls conj sql))]
          (export/export-spans! t [shape])
          (with-encoder t [shape]
            (if projector (fn [_] (throw (ex-info "typed must bypass" {}))) encoder)))
        (is (= 2 (count @calls)))
        (is (= (str "insert into otel_traces FORMAT JSONEachRow\n"
                    (json/write-str (#'exporter/span-row shape projector)) "\n")
               (first @calls)))
        (is (= (first @calls) (second @calls)))))))

(deftest opt-in-durable-phase-receipts-are-aggregate-only
  (let [receipts (exporter/durable-phase-receipts)
        calls (atom [])
        t (target {:durable-phase-receipts receipts})]
    (with-redefs [jdbc/execute! (fn [_ sql] (swap! calls conj sql))]
      (is (true? (export/export-spans! t [shape shape]))))
    ;; Receipt data proves each acknowledged boundary completed, but retains no
    ;; query/payload/row/attribute material from the spans.
    (is (= {:payload-built {:count 1 :spans 2}
            :native-execute-returned {:count 1 :spans 2}
            :persistence-barrier-returned {:count 1 :spans 2}}
           (into {} (map (fn [[phase values]]
                           [phase (select-keys values [:count :spans])]) @receipts))))
    (is (every? #(and (integer? (:nanos %)) (not (neg? (:nanos %))))
                (vals @receipts)))
    (is (not-any? string? (tree-seq coll? seq @receipts)))
    (is (= 1 (count @calls))))
  (let [receipts (exporter/durable-phase-receipts)
        t (target {:typed-span-projector (fn [_] {"Extra" 7})
                   :durable-phase-receipts receipts})]
    (with-redefs [jdbc/execute! (fn [& _] nil)]
      (is (true? (export/export-spans! t [shape]))))
    (is (= {:payload-built {:count 0 :nanos 0 :spans 0}
            :native-execute-returned {:count 0 :nanos 0 :spans 0}
            :persistence-barrier-returned {:count 0 :nanos 0 :spans 0}}
           @receipts))))

(deftest phase-receipts-cannot-change-durable-acknowledgement
  (let [receipts (exporter/durable-phase-receipts)
        t (target {:durable-phase-receipts receipts})]
    (add-watch receipts ::throwing-watch (fn [& _] (throw (ex-info "diagnostic failure" {}))))
    (try
      (with-redefs [jdbc/execute! (fn [& _] nil)]
        (is (true? (export/export-spans! t [shape]))))
      (finally
        (remove-watch receipts ::throwing-watch)))))

(deftest failed-durable-barrier-has-no-completed-barrier-receipt
  (let [receipts (exporter/durable-phase-receipts)
        t (target {:durable-phase-receipts receipts
                   :persistence-barrier (fn [_] (throw (ex-info "barrier failed" {})))})]
    (with-redefs [jdbc/execute! (fn [& _] nil)]
      (is (false? (export/export-spans! t [shape]))))
    (is (= {:count 0 :nanos 0 :spans 0}
           (:persistence-barrier-returned @receipts)))))

(deftest invalid-or-oversize-never-reaches-jdbc-or-later-row
  (doseq [oversize? [false true]]
    (let [seen (atom []) calls (atom 0)
          big (when oversize? (apply str (repeat (* 8 1024 1024) "x")))
          encoder (fn [s] (swap! seen conj (:name s))
                    (if (= "second" (:name s))
                      (if oversize? big (throw (ex-info "invalid" {}))) "{}"))]
      (with-redefs [jdbc/execute! (fn [& _] (swap! calls inc))]
        (is (false? (with-encoder (target {})
                       (mapv #(assoc shape :name %) ["first" "second" "later"]) encoder))))
      (is (= ["first" "second"] @seen))
      (is (zero? @calls)))))

(deftest ordinary-route-bypasses-prototype
  (let [calls (atom []) t (target {:durable? false})]
    (with-redefs [chdb/insert-json-rows! (fn [& args] (swap! calls conj (vec (rest args))))]
      (is (true? (export/export-spans! t [shape])))
      (is (true? (with-encoder t [shape]
                   (fn [_] (throw (ex-info "ordinary must bypass" {})))))))
    (is (= 2 (count @calls)))
    (is (= (first @calls) (second @calls)))))

(deftest actual-invalid-fallback-stops-before-later-row
  (let [encoder (#'exporter/compile-untyped-span-encoder)
        row @#'exporter/span-row seen (atom []) calls (atom 0) t (target {})]
    (with-redefs [exporter/span-row (fn [s p] (swap! seen conj (:name s)) (row s p))
                  jdbc/execute! (fn [& _] (swap! calls inc))]
      (is (false? (with-encoder t [shape (assoc shape :name "invalid" :start-time-unix-nano -1)
                                  (assoc shape :name "later" :attributes {"nested" []})] encoder))))
    (is (= ["invalid"] @seen))
    (is (= :otel.exporter.chdb/invalid-timestamp-nanos (:type (ex-data (:last-error @(:state t))))))
    (is (zero? @calls))))

(deftest frozen-fixture-exact-payload
  (when-let [path (System/getenv "BENCH_FROZEN_FIXTURE")]
    (let [spans (edn/read-string (slurp path)) encoder (#'exporter/compile-untyped-span-encoder)]
      (is (= 512 (count spans)))
      (is (every? #'exporter/untyped-span-eligible? spans))
      (is (= (#'exporter/json-each-row-payload (map #(#'exporter/span-row % nil) spans))
             (#'exporter/untyped-span-payload encoder spans))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-untyped-encoder-test)]
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
