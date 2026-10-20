(ns jolt.codec.reader.experimental-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [jolt.scheme :as scheme]
            [jolt.codec.reader.experimental :as reader]))

(defn number-reader [text options]
  (when-not (re-matches #"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?" text)
    (throw (ex-info "Invalid numeric token" {})))
  (if (:bigdec options) (bigdec text) (edn/read-string text)))

(defn fixture []
  (let [fallbacks (atom []) numbers (atom [])
        capability (reader/open-reader
                     {:number-reader (fn [text options]
                                       (swap! numbers conj [text options])
                                       (number-reader text options))
                      :fallback-reader (fn [text options]
                                         (swap! fallbacks conj [text options])
                                         [:fallback text options])})]
    ;; Runtime qualification must not invoke user adapters.
    (is (empty? @fallbacks)) (is (empty? @numbers))
    [capability fallbacks numbers]))

(deftest independent-reader-values-and-owned-state
  (let [[capability fallbacks _] (fixture)]
    (doseq [[text expected] [["false" false] ["null" nil] ["true" true]
                             ["{}" {}] ["[]" []] ["42" 42]
                             ["[false,null,-7,1.25]" [false nil -7 1.25]]
                             ["{\"a\":[false,null,7],\"b\":\"text\"}"
                              {"a" [false nil 7] "b" "text"}]
                             [" \n {\"a\":7} \t " {"a" 7}]]]
      (is (= expected (reader/read-string capability text))))
    (is (= (bigdec "1.25") (reader/read-string capability "1.25" {:bigdec true})))
    (is (empty? @fallbacks))
    (is (nil? (find-ns 'clojure.data.json)))
    (is (nil? (find-ns 'jolt.codec.experimental)))
    (is (nil? (find-ns 'jolt.traversal.experimental)))))

(deftest unicode-and-simple-escape-domain
  (let [[capability fallbacks _] (fixture)]
    (doseq [cp (range 256)]
      (is (= (str (char cp))
             (reader/read-string capability (str "\"\\u" (format "%04x" cp) "\"")))))
    (doseq [[text expected] [["\"\\uD83D\\uDE80\"" "🚀"]
                             ["\"\\uDBFF\\uDFFF\"" (str (char 1114111))]
                             ["\"λé🚀\"" "λé🚀"]
                             ["\"\\b\\f\\n\\r\\t\\/\\\\\\\"\"" "\b\f\n\r\t/\\\""]]]
      (is (= expected (reader/read-string capability text))))
    (is (empty? @fallbacks))))

(deftest unsupported-options-bypass-before-any-number-conversion
  (let [[capability fallbacks numbers] (fixture)]
    (doseq [options [{:key-fn keyword} {:value-fn identity}
                     {:extra-data-fn identity} {:future-option true}
                     {:key-fn nil}]]
      (is (= [:fallback "[7]" options] (reader/read-string capability "[7]" options))))
    (is (= 5 (count @fallbacks))) (is (empty? @numbers))))

(deftest declines-own-no-partial-result-and-fallback-runs-once
  (let [[capability fallbacks _] (fixture)
        deep (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")))]
    (doseq [text ["" " " "[1,]" "{\"a\":}" "01" "-01" "1e+"
                  "true false" "{}tail" "\"\\uZZZZ\"" "\"\\uD800\""
                  "\"\\uDC00\"" "\"unterminated" "\"raw\ncontrol\""
                  "{\"a\":1,\"a\":2}" "{\"a\":1,\"\\u0061\":2}" deep]]
      (let [before (count @fallbacks)]
        (is (= [:fallback text {}] (reader/read-string capability text)))
        (is (= (inc before) (count @fallbacks)))))
    (is (= [:fallback "" {:eof-error? false :eof-value :eof}]
           (reader/read-string capability "" {:eof-error? false :eof-value :eof})))))

(deftest failure-propagation-and-reentry
  (let [failure (ex-info "fallback sentinel" {:test :sentinel})
        capability (reader/open-reader {:number-reader (fn [& _] (throw failure))
                                        :fallback-reader (fn [& _] (throw failure))})]
    (is (identical? failure (try (reader/read-string capability "7")
                                 (catch Exception e e))))
    (is (identical? failure (try (reader/read-string capability "[1,]")
                                 (catch Exception e e)))))
  (let [current (atom nil) nested (atom [])
        capability (reader/open-reader
                     {:number-reader (fn [text options]
                                       (swap! nested conj (reader/read-string @current "\"nested\""))
                                       (number-reader text options))
                      :fallback-reader (fn [& _] (throw (ex-info "Unexpected fallback" {})))})]
    (reset! current capability)
    (is (= [7] (reader/read-string capability "[7]")))
    (is (= ["nested"] @nested))))

(deftest rejects-invalid-capabilities-and-options
  (doseq [options [nil {} {:number-reader number-reader}
                   {:number-reader number-reader :fallback-reader identity :extra true}]]
    (is (thrown? Exception (reader/open-reader options))))
  (is (thrown? Exception (reader/read-string {} "null")))
  (let [[capability _ _] (fixture)]
    (is (thrown? Exception (reader/read-string capability nil)))
    (is (thrown? Exception (reader/read-string capability "null" nil)))))

(deftest explicit-acceptance-and-decline-results
  (let [[capability fallbacks numbers] (fixture)]
    (is (= [false 5] (reader/try-read-string capability "false")))
    (is (= [nil 4] (reader/try-read-string capability "null")))
    (is (= ["🚀" 3] (reader/try-read-string capability "\"🚀\" \t")))
    (is (nil? (reader/try-read-string capability "[1,]")))
    (reset! numbers [])
    (is (nil? (reader/try-read-string capability "[7]" {:key-fn keyword})))
    (is (empty? @numbers)) (is (empty? @fallbacks))
    ;; Do not widen a strict fallback's domain or sanitize permissive values.
    (let [text (str "\"x\\ny" (char 1) "z\"")]
      (is (nil? (reader/try-read-string capability text)))
      (is (= [:fallback text {}] (reader/read-string capability text))))))

(deftest strict-control-policy-belongs-to-fallback
  (let [failure (ex-info "strict fallback rejected raw control" {})
        capability (reader/open-reader {:number-reader number-reader
                                        :fallback-reader (fn [& _] (throw failure))})]
    (doseq [text [(str "\"raw" (char 1) "control\"")
                  (str "\"escaped\\nraw" (char 1) "control\"")]]
      (is (nil? (reader/try-read-string capability text)))
      (is (identical? failure (try (reader/read-string capability text)
                                   (catch Exception e e)))))
    (is (= (str "escaped\nraw" (char 1) "control")
           (reader/read-string capability "\"escaped\\nraw\\u0001control\"")))))

(deftest rejects-malformed-native-result-shapes
  ;; Fault-inject only the private capability factory, not normal caller values.
  ;; This exercises the boundary check; it is not a parser-semantic proof.
  (doseq [bad [[42 0] [42 -1] [42 99] [42 1.5] [42] {:value 42}]]
    (let [capability
          (with-redefs-fn {#'reader/backend (delay [(fn [& _] bad) identity])}
            #(reader/open-reader {:number-reader number-reader
                                  :fallback-reader (fn [& _] :unexpected)}))]
      (is (thrown? Exception (reader/try-read-string capability "42"))))))

(deftest strict-policy-control-detects-the-original-token-kernel
  (let [source @#'reader/token-source
        mutant-source (str/replace source "((fx< (char->integer ch) 32) #f)" "")
        _ (is (not= source mutant-source))
        parse (scheme/eval-string @#'reader/whole-source)
        token (scheme/eval-string mutant-source)
        failure (ex-info "strict fallback sentinel" {})
        options {:number-reader number-reader :fallback-reader (fn [& _] (throw failure))}
        correct (reader/open-reader options)
        mutant (with-redefs-fn {#'reader/backend (delay [parse token])}
                 #(reader/open-reader options))
        text (str "\"x\\ny" (char 1) "z\"")]
    (is (nil? (reader/try-read-string correct text)))
    ;; Removing the real check restores native acceptance of an invalid token.
    ;; The strict-fallback property therefore rejects this known-bad kernel.
    (is (vector? (reader/try-read-string mutant text)))))

(defn -main [& _]
  (let [result (run-tests 'jolt.codec.reader.experimental-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
