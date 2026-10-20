(ns otel.exporter.chdb-nested-fragments-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb :as exporter]
            [jdbc.core :as jdbc]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn fixture [] (into (sorted-map) (map (fn [i] [(str "k" i) i]) (range 12))))
(defn text [bytes] (String. bytes "UTF-8"))

(deftest scoped-exporter-matches-default-bytes-and-restores-owner
  (let [plain (#'exporter/load-experimental-span-codec! true true)
        scoped (#'exporter/load-experimental-span-codec! true true true)
        other (#'exporter/load-experimental-span-codec! true true true)
        records [(fixture) (fixture)]
        emit (fn [context]
               (text ((:encode context) "SQL\n" records
                       #(vector ((:nested context) %)) 100000)))
        expected (emit plain)]
    (is (= expected (emit scoped)))
    (is (rejects? #((:nested scoped) [])))
    (is (= expected
           (text ((:encode scoped) "SQL\n" records
                   (fn [record]
                     (is (= expected (emit other)))
                     (is (rejects?
                           #((:encode other) "" [record]
                              (fn [value] [((:nested scoped) value)]) 100000)))
                     [((:nested scoped) record)]) 100000))))
    (is (nil? @#'exporter/*experimental-nested-writer*))))

(deftest failed-projection-retires-scope-with-original-error
  (let [context (#'exporter/load-experimental-span-codec! false false true)
        marker (ex-info "synthetic-stop" {}) seen (atom [])]
    (is (identical? marker
          (try ((:encode context) "" [1 2]
                 (fn [n] (swap! seen conj n) ((:nested context) (fixture)) (throw marker)) 100000)
               nil (catch Throwable error error))))
    (is (= [1] @seen))
    (is (rejects? #((:nested context) [])))
    (is (nil? @#'exporter/*experimental-nested-writer*))))

(deftest option-and-capability-reject-before-storage
  (let [opens (atom 0) resolve-var ns-resolve
        config {:experimental-span-codec? true :signals #{:spans} :durable? true
                :json-backend :native-guarded-byte-batch :insert-format :json-compact-each-row
                :owned-statement-output? true :experimental-span-nested-fragment-cache? true}]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc) (throw (ex-info "Storage opened" {})))]
      (doseq [flag [true "yes" nil]]
        (is (= :otel.exporter.chdb/invalid-experimental-span-nested-fragments
               (try (exporter/exporter {:experimental-span-nested-fragment-cache? flag}) nil
                    (catch Throwable error (:type (ex-data error)))))))
      (with-redefs [ns-resolve (fn [namespace name]
                                (when-not (= ['jolt.codec.experimental 'with-nested-text-writer]
                                             [namespace name])
                                  (resolve-var namespace name)))]
        (is (= :otel.exporter.chdb/experimental-span-nested-fragments-unavailable
               (try (exporter/exporter config) nil
                    (catch Throwable error (:type (ex-data error)))))))
      (is (zero? @opens)))))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'otel.exporter.chdb-nested-fragments-test)]
    (assert (= 3 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
