(ns otel.exporter.chdb.payload-chunks
  "Internal bounded preparation for proposed physical metric chunks.
  No driver calls, admission, publication or persistence receipts. Not wired
  into exporter defaults. Encoding callbacks are trusted serialization work.")

(def ^:private physical-ceiling (* 8 1024 1024))
(def ^:private logical-ceiling (* 64 1024 1024))
(def ^:private row-ceiling 65536)

(defn- fail! [kind limit]
  ;; Only fixed tags and numeric limits, never telemetry or serialized output.
  (throw (ex-info "Metric chunk preparation limit or contract rejected"
                  {:type kind :limit limit})))

(defn- positive-limit? [value ceiling]
  (and (integer? value) (<= 1 value ceiling)))

(defn prepare-payloads!
  "Prepare a finite logical batch as ordered, bounded JSONEachRow chunks.

  Options are explicit: :encode-row returns one JSON string without LF;
  :max-chunk-bytes <= 8 MiB, :max-logical-bytes <= 64 MiB and :max-rows <= 65536.
  The byte budgets include each LF. A row is never split; an oversized row
  fails. Serialization occurs once per visited row, with no restart at a chunk
  boundary. No later row is requested after byte rejection. Lazy sources may
  realize their own chunks; callbacks may allocate before a size is known.

  Returns bounded payload text and counts, not acknowledgement. All preparation
  finishes before a caller could submit the returned chunks. Serialized output
  retention is bounded by max-logical-bytes; input/serializer heap is not.
  The caller must qualify its writer binding, row validity and publication
  contract separately."
  [rows {:keys [encode-row max-chunk-bytes max-logical-bytes max-rows] :as options}]
  (when-not (and (map? options)
                (= #{:encode-row :max-chunk-bytes :max-logical-bytes :max-rows}
                   (set (keys options)))
                (ifn? encode-row)
                (positive-limit? max-chunk-bytes physical-ceiling)
                (positive-limit? max-logical-bytes logical-ceiling)
                (<= max-chunk-bytes max-logical-bytes)
                (positive-limit? max-rows row-ceiling)
                (or (nil? rows) (sequential? rows)))
    (fail! ::invalid-options 0))
  (loop [remaining (seq rows) out (StringBuilder.)
         chunk-bytes 0 chunk-rows 0 chunks [] total-bytes 0 total-rows 0]
    (if (seq remaining)
      (do
        (when (>= total-rows max-rows)
          (fail! ::row-limit max-rows))
        (let [encoded (encode-row (first remaining))]
          (when-not (string? encoded)
            (fail! ::invalid-encoded-row 0))
          ;; Keep this isolated preparer compatible with the actual root pin.
          ;; A native size kernel is not assumed from a newer local chDB tree.
          (let [size (inc (alength (.getBytes ^String encoded "UTF-8")))
                next-total (+ total-bytes size)]
            (when (> size max-chunk-bytes)
              (fail! ::oversized-row max-chunk-bytes))
            (when (> next-total max-logical-bytes)
              (fail! ::logical-limit max-logical-bytes))
            (let [split? (> (+ chunk-bytes size) max-chunk-bytes)
                  chunks (if split?
                           (conj chunks {:payload (.toString out)
                                         :byte-count chunk-bytes :row-count chunk-rows})
                           chunks)
                  out (if split? (StringBuilder.) out)]
              (.append out encoded)
              (.append out "\n")
              (recur (next remaining) out
                     (+ (if split? 0 chunk-bytes) size)
                     (inc (if split? 0 chunk-rows)) chunks next-total
                     (inc total-rows))))))
      {:chunks (if (pos? chunk-rows)
                 (conj chunks {:payload (.toString out)
                               :byte-count chunk-bytes :row-count chunk-rows})
                 chunks)
       :byte-count total-bytes :row-count total-rows})))
