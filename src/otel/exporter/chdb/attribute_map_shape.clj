(ns otel.exporter.chdb.attribute-map-shape
  "Optional Jolt/Chez shape check; unsupported native capabilities decline."
  (:require [jolt.scheme :as scheme]))

(defn- make-native-shape-check []
  (try
    (let [check (scheme/eval-string
        "(letrec
           ((pair-length (lambda (key value)
                           (and (string? key) (string? value) (string-length value))))
            (node-length
              (lambda (node)
                (let ((entries (hnode-arr node)))
                  (let loop ((index 0) (maximum 0))
                    (if (= index (vector-length entries)) maximum
                        (let* ((child (vector-ref entries index))
                               (needed (cond ((hnode? child) (node-length child))
                                             ((hcoll? child) #f)
                                             (else (pair-length (car child) (cdr child))))))
                          (and needed (loop (+ index 1) (fxmax maximum needed))))))))))
           (lambda (value)
             (and (pmap? value)
                  (let ((root (pmap-root value)))
                    (if (hnode? root)
                        (and (> (pmap-cnt value) 8) (node-length root))
                        (and (<= (pmap-cnt value) 8)
                             (let loop ((index 0) (maximum 0))
                               (if (= index (vector-length root)) maximum
                                   (let ((needed (pair-length (vector-ref root index)
                                                              (vector-ref root (+ index 1)))))
                                     (and needed (loop (+ index 2) (fxmax maximum needed))))))))))))")
          wide (into {} (map (fn [index] [(str "shape-" index) "value"])) (range 16))]
      ;; Return only a character count, never an input/callback/entry sequence.
      ;; Exercise every representation before accepting this optional capability.
      (if (and (= 5 (check {"a" "value"}))
               (= 0 (check {"a" ""}))
               (= 0 (check {}))
               (= 2 (check {"a" "a😀"}))
               (false? (check {"a" 1}))
               (false? (check (hash-map "a" "value")))
               (= 5 (check wide))
               (= (hash "Aa") (hash "BB"))
               (false? (check (assoc wide "Aa" "first" "BB" "second")))
               (false? (check (apply array-map (mapcat identity wide)))))
        check
        (constantly false)))
    (catch Throwable _ (constantly false))))

(def ^:private native-shape-check (delay (make-native-shape-check)))

(defn reusable-string-map-shape?
  "Admit builtin string maps only when rebuilding preserves kind and order.
  Large explicit array maps promote; small hash maps lose their kind; hash
  collision buckets can reverse. All decline. Walk the Chez representation without
  constructing entries, sequences or another map. This does not qualify SDK
  normalization policy or bounds, and invokes no user-supplied converters."
  [value]
  (if (@native-shape-check value) true false))

(defn reusable-string-map-required-chars
  "Maximum value character count for an admissible builtin string map, or nil.
  Shape and lengths share one callback-free walk. This is not policy acceptance:
  callers must compare with the current SDK-owned bound. Empty maps return zero."
  [value]
  (let [result (@native-shape-check value)]
    (when (integer? result) result)))
