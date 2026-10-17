(ns otel.exporter.chdb.attribute-map-shape
  "Optional Jolt/Chez shape check; unsupported native capabilities decline."
  (:require [jolt.scheme :as scheme]))

(defn- make-native-shape-check []
  (try
    (let [check (scheme/eval-string
        "(letrec
           ((string-pair? (lambda (key value) (and (string? key) (string? value))))
            (string-node?
              (lambda (node)
                (let ((entries (hnode-arr node)))
                  (let loop ((index 0))
                    (or (= index (vector-length entries))
                        (let ((child (vector-ref entries index)))
                          (and (cond ((hnode? child) (string-node? child))
                                     ((hcoll? child) #f)
                                     (else (string-pair? (car child) (cdr child))))
                               (loop (+ index 1))))))))))
           (lambda (value)
             (and (pmap? value)
                  (let ((root (pmap-root value)))
                    (if (hnode? root)
                        (and (> (pmap-cnt value) 8) (string-node? root))
                        (and (<= (pmap-cnt value) 8)
                             (let loop ((index 0))
                               (or (= index (vector-length root))
                                   (and (string-pair? (vector-ref root index)
                                                      (vector-ref root (+ index 1)))
                                        (loop (+ index 2)))))))))))")
          wide (into {} (map (fn [index] [(str "shape-" index) "value"])) (range 16))]
      ;; Scheme can leave an unbound reference dormant until a branch runs.
      ;; Exercise both representations and collision/promotion declines now,
      ;; before accepting an optional capability, never on application data.
      (if (and (true? (check {"a" "value"}))
               (false? (check {"a" 1}))
               (false? (check (hash-map "a" "value")))
               (true? (check wide))
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
  (@native-shape-check value))
