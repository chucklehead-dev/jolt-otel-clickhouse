(ns clojure.data.json-ascii-flush-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as scheme]))

(defn- native-text [value]
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (json/write-str value)))

(deftest ascii-publications-retain-exact-escaping-and-boundaries
  (doseq [n [0 1 15 16 127 128 511 512 513 1023 1024 1025 65535 65536 65537]
          tail ["" "λ😀\u2028\"/\\\n\u0000\u007f"]]
    (let [value [{:prefix (apply str (repeat n "a"))
                  :nested {:value tail :flags [true false nil 42 -17 2.5]}}]
          expected (binding [json/*experimental-native-writer* nil] (json/write-str value))]
      (is (= expected (native-text value)))))
  (let [value {:all-ascii (apply str (map char (range 128)))}]
    (is (= (binding [json/*experimental-native-writer* nil] (json/write-str value))
           (native-text value)))))

(deftest custom-writer-observes-prefix-reentry-and-raw-unicode
  (let [run (fn [native?]
              (let [seen (atom [])
                    custom (reify json/JSONWriter
                             (-write [_ out options]
                               (swap! seen conj (.toString out))
                               ;; Reentry must not overwrite the outer scratch.
                               (let [inner (json/write-str {:inside "λ😀"})]
                                 (.write out (str "[" inner ",\"λ😀\"]")))))
                    value [{:before "x" :custom custom :after "y"}]
                    text (binding [json/*experimental-native-writer*
                                   (when native? (native/load-payload-byte-buffer-writer!))]
                           (json/write-str value))]
                {:text text :prefixes @seen}))]
    (is (= (run false) (run true)))))

(deftest private-ascii-materialization-is-live
  (native/load-payload-byte-buffer-writer!)
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        observed (str/replace source "(let ((text (make-string used)))"
                   "(let ((text (begin (set! copies (+ copies 1)) (make-string used))))")
        stage (scheme/eval-string
                (str "(let ((copies 0)) " observed
                     " (jolt-vector djn-make-byte-buffer-writer (lambda () copies)))"))
        expected (binding [json/*experimental-native-writer* nil]
                   (json/write-str [{:name "λ😀" :another-key "value"}]))
        actual (binding [json/*experimental-native-writer* ((nth stage 0))]
                 (json/write-str [{:name "λ😀" :another-key "value"}]))]
    (is (= expected actual))
    (is (pos? ((nth stage 1))))))

(defn -main [& _]
  (let [result (run-tests 'clojure.data.json-ascii-flush-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
