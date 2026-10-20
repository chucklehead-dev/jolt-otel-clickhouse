(ns otel.exporter.chdb-stock-published-codec-test
  "Check the published subproject, not the checkout-local codec alias."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [otel.exporter.chdb-preparation-test]
            [otel.exporter.chdb-stock-payload-cache-test]
            [otel.exporter.chdb-experimental-codec-test]
            [otel.exporter.chdb-nested-fragments-test]
            [jolt.codec.flat-map-cache-test]
            [jolt.codec.nested-fragment-test]
            [jolt.codec.row-view-test]
            [jolt.codec.experimental-test]
            [jolt.traversal.leaf-block-test]
            [jolt.traversal.experimental-test]))

(def ^:private published-root
  "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/0af1008185cf84577298d3a0a0b2db87b97bddd5/incubator/codec/src/")

(defn- published-resource? [resource path]
  (str/ends-with? (str path) (str "/" published-root resource)))

(defn -main [& _]
  ;; An accidental :experimental-span-codec alias would shadow these resources
  ;; with worktree files. Require the exact independently resolved subproject.
  (doseq [resource ["jolt/codec/experimental.clj" "jolt/codec/json.ss"
                   "jolt/traversal/experimental.clj" "jolt/traversal/kernel.ss"]]
    (let [positive (str "/fixture/" published-root resource)]
      ;; These check exact coordinate selection, not older-code incompatibility.
      (assert (published-resource? resource positive))
      (assert (not (published-resource? resource
                     (str/replace positive "0af1008185cf84577298d3a0a0b2db87b97bddd5"
                                  "627993767b9d2adf26576ae7470081d85ab943f1"))))
      (assert (not (published-resource? resource
                     (str/replace positive "chucklehead-dev_jolt-otel-clickhouse.git"
                                  "different-owner_different-repo.git"))))
      (assert (not (published-resource? resource
                     (str/replace positive "incubator/codec/src/" "src/"))))
      (assert (not (published-resource? resource (str positive ".extra"))))
      (assert (published-resource? resource (io/resource resource)))))
  (let [result (test/run-tests 'otel.exporter.chdb-preparation-test
                             'otel.exporter.chdb-stock-payload-cache-test
                             'otel.exporter.chdb-experimental-codec-test
                             'otel.exporter.chdb-nested-fragments-test
                             'jolt.codec.flat-map-cache-test
                             'jolt.codec.nested-fragment-test
                             'jolt.codec.row-view-test
                             'jolt.codec.experimental-test
                             'jolt.traversal.leaf-block-test
                             'jolt.traversal.experimental-test)]
    (assert (= 52 (:test result)))
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
