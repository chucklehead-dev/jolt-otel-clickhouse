;; Small synthetic corpus for a separate process/runtime/parser to consume.
(require '[jolt.scheme :as s])
(def here (first *command-line-args*))
(s/eval-string (slurp (str here "/traversal.ss")))
(s/eval-string (slurp (str here "/json.ss")))
(def encode (s/proc "ffc-json-bytes"))
(def encode-ascii (s/proc "ffc-json-ascii-bytes"))
(doseq [i (range 180)]
  (let [x {"i" i "negative" (- i) "min" -9223372036854775808
           "max" 18446744073709551615 "fraction" (/ (double i) 7.0)
           "extremes" [0.0 -0.0 1.0e-300 1.0e300 Double/MIN_VALUE Double/MAX_VALUE]
           "unicode" (str "\u0000\u001f\u007f\u0080\u07ff\u0800\uffff😀\"\\/\n" i)
           "nested" [nil true false {} [] {"x" [1 2 3]}]}]
    (println (pr-str {:expected x :wire (String. (encode x 65536 128) "UTF-8")
                     :ascii-wire (String. (encode-ascii x 65536 128) "UTF-8")}))))
