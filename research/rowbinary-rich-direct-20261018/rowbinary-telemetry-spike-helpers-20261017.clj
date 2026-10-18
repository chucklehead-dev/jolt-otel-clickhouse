(require '[jolt.scheme :as scheme] '[clojure.string :as str])

(def rb-encode
  (scheme/eval-string (slurp "/home/chuck/ai-src/evidence/rowbinary-telemetry-spike-20261017.ss")))

(defn rb-type [text]
  ;; Closed SPIKE subset, not a general ClickHouse type parser.
  (case (str/replace text " " "")
    ("String" "LowCardinality(String)") [0]
    ("Int64" "DateTime64(9)" "DateTime64(9,'UTC')") [1]
    "UInt64" [2] "UInt8" [3] "Bool" [4] "Float64" [7]
    "Array(DateTime64(9))" [5 [1]]
    ("Array(String)" "Array(LowCardinality(String))") [5 [0]]
    ("Map(String,String)" "Map(LowCardinality(String),String)") [6 [0] [0]]
    ("Array(Map(String,String))" "Array(Map(LowCardinality(String),String))") [5 [6 [0] [0]]]
    (throw (ex-info "Unsupported spike column type" {}))))

(defn rb-sql-string [s] (str/replace (str/replace s "\\" "\\\\") "'" "\\'"))
(defn rb-column [s] (str "`" (str/replace s "`" "``") "`"))
(defn rb-prefix [columns types]
  (str "INSERT INTO otel_traces (" (str/join "," (map rb-column columns))
       ") SELECT * FROM format(RowBinary, '"
       (rb-sql-string (str/join "," (map #(str (rb-column %1) " " %2) columns types)))
       "', base64Decode('"))
(defn rb-sql [prefix bytes]
  (str prefix (.encodeToString (java.util.Base64/getEncoder) bytes) "'))"))

;; Known bytes independently constructed before this serializer existed.
(let [bytes (rb-encode [[2] [0]]
                       [[42 "hello"] [18446744073709551615N "λ?\"\\\n\u0000😀"]]
                       nil 1024)]
  (assert (= "KgAAAAAAAAAFaGVsbG///////////wvOuz8iXAoA8J+YgA=="
             (.encodeToString (java.util.Base64/getEncoder) bytes))))
