(ns otel.exporter.chdb-ordinary-transport-diagnostics-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [otel.exporter.chdb-ordinary-transport-benchmark :as benchmark]))

(deftest known-schema-categories-are-closed
  (doseq [category [:invalid-plan :duplicate-version :unknown-version
                    :migration-drift :nonconsecutive-history :migration-failed]]
    (is (= {:category category :phase :statement :version 4 :statement-index 0}
           (benchmark/schema-failure-diagnostic
            {:type (keyword "otel.exporter.chdb.schema" (name category))
             :phase :statement :version 4 :statement-index 0}))))
  (is (= {:category :migration-failed :phase :record :version 1 :statement-index :unknown}
         (benchmark/schema-failure-diagnostic
          {:type :otel.exporter.chdb.schema/migration-failed
           :phase :record :version 1 :statement-index 0}))))

(deftest unknown-and-malicious-data-cannot-leak
  (let [secret "PRIVATE-payload-SQL-message-checksum-cause"
        public (benchmark/schema-failure-diagnostic
                {:type secret :phase secret :version secret :statement-index secret
                 :message secret :statement secret :checksum secret :cause secret})]
    (is (= {:category :unknown :phase :unknown :version :unknown :statement-index :unknown}
           public))
    (is (not (str/includes? (pr-str public) secret)))
    (is (= #{:category :phase :version :statement-index} (set (keys public)))))
  (is (= {:category :unknown :phase :unknown :version :unknown :statement-index :unknown}
         (benchmark/schema-failure-diagnostic nil)))
  (doseq [version [-1 0 5 128 1.5 "1" nil]]
    (is (= :unknown (:version (benchmark/schema-failure-diagnostic {:version version})))))
  (doseq [index [-1 128 1.5 "0" nil]]
    (is (= :unknown (:statement-index
                     (benchmark/schema-failure-diagnostic
                      {:phase :statement :statement-index index})))))
  (is (= 127 (:statement-index
              (benchmark/schema-failure-diagnostic
               {:phase :statement :statement-index 127}))))
  (is (= :unknown (:statement-index
                   (benchmark/schema-failure-diagnostic
                    {:phase :record :statement-index 127})))))

(deftest setup-markers-preserve-original-calls-and-outcomes
  (let [original-failure (ex-info "PRIVATE-primary" {})
        original-schema otel.exporter.chdb.schema/ensure-schema!
        original-emit @#'benchmark/emit-diagnostic!
        calls (atom 0)]
    ;; Observer failure is isolated independently of the actual setup failure.
    (with-redefs [clojure.core/println (fn [& _] (throw (ex-info "PRIVATE-output" {})))]
      (is (nil? (original-emit :benchmark-setup :schema :enter))))
    (with-redefs [otel.exporter.chdb.schema/ensure-schema!
                  (fn [_] (swap! calls inc) (throw original-failure))]
      (let [actual (try (benchmark/setup (Object.)) nil (catch Throwable error error))]
        (is (identical? original-failure actual))
        (is (= 1 @calls))))
    (is (identical? original-schema otel.exporter.chdb.schema/ensure-schema!))))

(deftest successful-setup-result-is-unchanged
  (let [connection (Object.) capability (Object.) projector identity calls (atom [])]
    (with-redefs [otel.exporter.chdb.schema/ensure-schema!
                  (fn [actual] (is (identical? connection actual)) (swap! calls conj :schema))
                  otel.exporter.chdb.attribute-registry-installer/install-approved!
                  (fn [& _] (swap! calls conj :installer)
                    {:status :active :descriptor-set capability})
                  otel.exporter.chdb.attribute-projection/trace-projector
                  (fn [actual target] (is (identical? capability actual))
                    (is (identical? connection target)) projector)
                  otel.exporter.chdb.attribute-projection/confirmed-span-fields
                  (fn [actual target] (is (identical? capability actual))
                    (is (identical? connection target)) [])]
      (is (= {:projector projector :fields [] :capability capability}
             (benchmark/setup connection)))
      (is (= [:schema :installer] @calls)))))

(deftest maintained-profile-and-failure-exit-source-contract
  ;; Source guard, not a substitute for separately owned real child exit tests.
  (let [source (slurp "bench/otel/exporter/chdb_ordinary_transport_benchmark.clj")]
    (is (= 1 (count (re-seq #"\(System/exit 1\)" source))))
    (is (not (str/includes? source "(System/exit 0)")))
    (is (str/includes? source ":semantic-control {:warmups 0 :measured 1}"))
    (is (str/includes? source "(require! (= expected actual))"))))
