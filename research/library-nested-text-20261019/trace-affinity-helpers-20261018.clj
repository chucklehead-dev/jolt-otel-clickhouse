(ns otel.research.trace-affinity
  "Research-only, fixed-lifetime trace routing. Not a supported SDK processor.")

(defn lane
  "Select one lane for this live router. Missing/non-string IDs use lane zero.
  SDK ID validation remains the SDK's responsibility. No cross-process hash
  stability, lane resizing, delivery or global ordering guarantee is implied."
  [span lane-count]
  (when-not (contains? #{1 2} lane-count)
    (throw (ex-info "Unsupported research lane count" {:lane-count lane-count})))
  (let [trace-id (get-in span [:span-context :trace-id])]
    (if (string? trace-id) (mod (hash trace-id) lane-count) 0)))

(defn route!
  "Dispatch exactly once; callers retain all admission/resource ownership."
  [processors span emit!]
  (let [selected (lane span (count processors))]
    (emit! (nth processors selected) span)
    selected))

(defn group-traces
  "Retain all original fields except group eight adjacent spans under the
  first span's trace ID. Used by both the synthetic writer and its reader."
  [spans]
  (let [spans (vec spans)]
    (mapv (fn [index span]
            (assoc-in span [:span-context :trace-id]
                      (get-in (nth spans (* 8 (quot index 8))) [:span-context :trace-id])))
          (range (count spans)) spans)))
