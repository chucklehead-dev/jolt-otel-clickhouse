;; Research only: existing exact WAL kernel versus checked four-byte clean-run
;; scanning. No driver/runtime replacement; no native execution or persistence.
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[clojure.data.json :as json] '[jolt.scheme :as scheme]
         '[jdbc.chdb.owned-statement :as owned]
         '[jdbc.chdb.durable.digest :as digest]
         '[otel.exporter.chdb-benchmark :as bench])
(defn replace-once [text needle replacement]
  (assert (= 2 (count (str/split text (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace text needle replacement))
(defn q [xs fraction]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* fraction (count xs))))))))
(let [[report] *command-line-args*
      source (slurp (io/resource "jdbc/chdb/owned_ascii_wal.ss"))
      helpers "    ;; Conservative clean words: all bytes >=48, none backslash.\n    ;; Private input is already ASCII. All accesses/copies remain checked.\n    (define (clean-word? i)\n      (let* ((word (bytevector-u32-native-ref input i))\n             (slashes (fxxor word #x5c5c5c5c)))\n        (and (fx=? (fxand (fx- (fxior word #x80808080) #x30303030) #x80808080) #x80808080)\n             (fx=? (fxand (fx- slashes #x01010101) (fxnot slashes) #x80808080) 0))))\n    (define (clean-end i at)\n      (let ((bound (fxmin n (fx+ i (fx- capacity at 12)))))\n        (let scan ((j i))\n          (if (and (fx<=? (fx+ j 4) bound) (clean-word? j))\n              (scan (fx+ j 4)) j))))\n"
      branch "             ((fx= escape 0)\n              (if (and (fx>=? cp 48) (fixnum? #x80808080) (fx=? (fxand i 3) 0)\n                       (fx<=? (fx+ i 4) n) (fx<=? (fx+ at 4) (fx- capacity 12))\n                       (clean-word? i))\n                  (let* ((end (clean-end (fx+ i 4) (fx+ at 4))) (count (fx- end i)))\n                    (bytevector-copy! input i buffer at count)\n                    (loop end (fx+ at count)))\n                  (begin (#3%bytevector-u8-set! buffer at cp)\n                         (loop (fx+ i 1) (fx+ at 1)))))"
      candidate (-> source
                    (replace-once "    (bytevector-copy! durable-wal-prefix 0 buffer 0 8)"
                                  (str helpers "    (bytevector-copy! durable-wal-prefix 0 buffer 0 8)"))
                    (replace-once "             ((fx= escape 0)\n              (#3%bytevector-u8-set! buffer at cp)\n              (loop (fx+ i 1) (fx+ at 1)))" branch))
      kernels {:old (scheme/eval-string source) :candidate (scheme/eval-string candidate)}
      flatten (fn [[size chunks]]
                (let [result (byte-array size)]
                  (loop [offset 0 remaining (seq chunks)]
                    (when remaining
                      (let [[bytes used] (first remaining)]
                        (assert (<= 1 used (alength bytes) 65536))
                        (System/arraycopy bytes 0 result offset used)
                        (recur (+ offset used) (next remaining))))) result))
      ;; Byte-array input is used only by isolated kernels; the real driver
      ;; admits an independently copied, ASCII-qualified owned snapshot.
      verify (fn [text]
               (let [input (.getBytes text "UTF-8")
                     old (flatten ((kernels :old) input))
                     new (flatten ((kernels :candidate) input))]
                 (assert (java.util.Arrays/equals old new))
                 ;; Large boundaries compare every byte directly. Parsing the
                 ;; same large scalar with the ordinary reader is not the
                 ;; performance question; small cases cover the roundtrip.
                 (when (< (count text) 1024)
                   (assert (= {"sql" text} (json/read-str (String. new "UTF-8")))))))
      _ (doseq [length [0 1 2 3 4 5 7 8 11 12 13 31 63 127 245 246 247 248 249 250
                         251 252 253 254 255 256 257 65520 65521 65522 65523 65524 65525
                         65526 65527 65528 65529 65530 65531 65532 65533 65534 65535 65536 65537]
                token ["a" "Z09_" "abc\\" "ab\"" "a/" "\u0000\n\t" " ,.:;" "\u007f"]]
          (verify (apply str (take length (cycle token)))))
      _ (doseq [cp (range 128) alignment (range 4)]
          (verify (str (apply str (repeat alignment "a")) (char cp) "0123456789_abcdef")))
      _ (do (println :boundary-and-all-ascii-controls-passed) (flush))
      ;; Rich JSONCompactEachRow-like SQL: controls, numeric tokens, many
      ;; identifiers, escaped nested JSON. Same 5k-row width in both arms.
      row (str "[1750000000000000000,\"0123456789abcdef0123456789abcdef\",\"0123456789abcdef\","
               "\"span-name\",{\"service.name\":\"fixture\",\"key\":\"quote\\\"/\\\\\"},"
               "false,123,\"{\\\"event\\\":\\\"value\\\"}\"]\n")
      rich (str "INSERT INTO otel_traces FORMAT JSONCompactEachRow\n" (apply str (repeat 5000 row)))
      shapes {:rich rich :long-clean (apply str (repeat 800000 "a"))
              :escape-heavy (apply str (repeat 400000 "\\\""))}
      _ (doseq [[_ text] shapes] (verify text))
      _ (do (println :large-shapes-byte-identical) (flush))
      inputs (into {} (map (fn [[shape text]] [shape (.getBytes text "UTF-8")]) shapes))
      hashes (into {} (map (fn [[shape bytes]]
                            [shape (digest/sha256-bytes (flatten ((kernels :old) bytes)))]) inputs))
      _ (doseq [[_ bytes] inputs _ (range 3) [_ kernel] kernels] (kernel bytes))
      observations (vec (for [block (range 24) arm (if (even? block) [:old :candidate] [:candidate :old])
                              shape [:rich :long-clean :escape-heavy]]
                          (let [before (#'bench/counter-sample) start (System/nanoTime)
                                output ((kernels arm) (inputs shape))
                                elapsed (- (System/nanoTime) start) after (#'bench/counter-sample)]
                            (assert (= (hashes shape) (digest/sha256-bytes (flatten output))))
                            {:block block :arm arm :shape shape :nanos elapsed
                             :counters (#'bench/counter-delta before after)})))
      result {:scope :wal-clean-run-component-only :runtime :official-stock-0.8.23
              :native-io false :durable-io false :target-qualified false
              :existing-source-sha256 (digest/sha256-bytes (.getBytes source "UTF-8"))
              :candidate-source candidate :checks :exact-output-and-json-roundtrip
              :summary (into {} (for [arm [:old :candidate] shape (keys shapes)
                                     :let [samples (filter #(and (= arm (:arm %)) (= shape (:shape %))) observations)]]
                                  [[arm shape] {:samples (count samples)
                                                :bytes (alength (inputs shape))
                                                :ms (into {} (for [[name fraction] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                                               [name (/ (q (map :nanos samples) fraction) 1e6)]))}]))
              :observations observations}]
  (spit report (pr-str result))
  (prn (:summary result)))
