(ns otel.exporter.chdb-child-provenance-test
  "Real shell children with fake compiler tokens: no native/Jolt child work."
  (:require [clojure.test :refer [deftest is]]
            [jolt.process :as process]
            [otel.exporter.chdb-test :as runner]))

(defn- private-runner [name]
  (or (ns-resolve 'otel.exporter.chdb-test name)
      (throw (ex-info "Required maintained runner seam missing" {}))))

(defn- fake-executable [directory name output exit]
  (let [file (java.io.File. directory name)]
    (spit file (str "#!/bin/sh\nprintf '%s\\n' '" output "'\nexit " exit "\n"))
    (let [child (process/process ["/bin/chmod" "+x" (.getAbsolutePath file)]
                                {:out :string :err :string})
          result (deref child 5000 ::timeout)]
      (when (= ::timeout result) (process/destroy-tree child))
      (when-not (and (map? result) (integer? (:exit result))
                     (zero? (:exit result)))
        (throw (ex-info "Public fake executable not executable" {}))))
    file))

(deftest selected-absolute-child-is-not-path-shadowed
  (let [placeholder (java.io.File/createTempFile "exporter-child-provenance-" ".fixture")
        directory (java.io.File. (str (.getAbsolutePath placeholder) "-children"))]
    (when-not (.mkdir directory)
      (throw (ex-info "Public fake child directory not created" {})))
    (let [shadow (fake-executable directory "jolt" "shadow-child" 0)
          selected (fake-executable directory "selected" "selected-child" 0)
          failed (fake-executable directory "failed" "selected-child-failed" 37)
          select (private-runner 'child-test-executable)
          launch (fn [command]
                   (let [child (process/process [command]
                                {:out :string :err :string
                                 :extra-env {"PATH" (.getAbsolutePath directory)}})
                         result (deref child 5000 ::timeout)]
                     (when (= ::timeout result)
                       (process/destroy-tree child))
                     result))]
      ;; The negative baseline actually executes the PATH-selected shell stub.
      (let [baseline (launch (select nil))]
        (is (= 0 (:exit baseline)))
        (is (= "shadow-child\n" (:out baseline))))
      (let [actual (launch (select (.getAbsolutePath selected)))]
        (is (= 0 (:exit actual)))
        (is (= "selected-child\n" (:out actual)))
        (is (not= "shadow-child\n" (:out actual))))
      ;; Feed an actual shell failure into the maintained parent accounting and
      ;; finalizer, not a parallel substitute failure implementation.
      (let [actual (launch (select (.getAbsolutePath failed)))
            owned-failures (atom 0)]
        (is (= 37 (:exit actual)))
        (is (= "selected-child-failed\n" (:out actual)))
        (with-redefs [runner/failures owned-failures]
          ((private-runner 'check) "public fake failed child" true
           (and (map? actual) (zero? (:exit actual))))
          (is (= 1 @owned-failures))
          (is (= {:failures 1}
                 (try ((private-runner 'finish-checks!)) nil
                      (catch Throwable error (ex-data error)))))))
      ;; Retain only inert public fixture scripts; do not destroy live children
      ;; or claim timeout settlement if no numeric child result was observed.
      (is (.isFile shadow)))))
