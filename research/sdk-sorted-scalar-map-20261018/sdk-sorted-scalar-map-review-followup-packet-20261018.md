Read-only narrow followup, INLINE SOURCE ONLY, no tools/edits/publication. SDK7878a2e (only comment clarification since reviewed1fa2614). Your sole previous finding imagined SDK reset-sorted-map-policy! changing default :ops after namespace load. That API does not exist in this SDK. Operation-table replacement requires per-object jolt.host/ref-put! (supported decline) or private core sm-ops/constructor mutation (explicitly outside ordinary-core trust). Frozen prototype identity is INTENTIONAL safety admission; custom ops must decline, never be sampled as new stock. The comment about temporary policy replacement refers ONLY six mutable SDK scalar roots (try-scalar-string, canonicalize, limits, string-bytes, min/max). Their original identities are defined by SDK and checked per call; tests restore them and admission recovers. New wording makes this explicit. Moving prototype to lazy first-call would not improve supported correctness and could capture substituted core ops; main rejects that suggestion. No functional code changed since review; fullSDK471/4089, SDKfocused6/78, exporter54/969 pass. In <=300words, re-evaluate only whether the prior availability claim holds under ACTUAL stated scope, and identify any concrete remaining defect in provided logic. Do not invent an operation-policy API. It is okay to disagree with main if you can provide a supported witness. No other defects were found in original full review.

(def ^:private scalar-sorted-map-prototype
  (sorted-map "$scalar-probe-a" 7 "$scalar-probe-b" "probe-text" "$scalar-probe-c" false))

(defn- load-sorted-scalar-string-map-converter []
  (try
    (require 'otel.any-value-native)
    ((ns-resolve 'otel.any-value-native 'load-sorted-string-map-converter!)
     [[#'try-scalar-string scalar-text-helper]
      [#'canonicalize scalar-text-canonicalizer]
      [#'default-limits scalar-default-limits]
      [#'string-bytes original-string-bytes]
      [#'min-int64 scalar-map-min-int64]
      [#'max-int64 scalar-map-max-int64]]
     (quot (:max-bytes scalar-default-limits) 4)
     scalar-sorted-map-prototype)
    (catch Throwable _ (constantly nil))))

(def ^:private sorted-scalar-string-map-converter
  (delay (load-sorted-scalar-string-map-converter)))

(defn try-sorted-scalar-string-map
  "Return a fresh plain scalar-text map from an admitted sorted map, or nil.

  Unlike try-scalar-string-map, this deliberately normalizes to the same plain
  map as (into {} (map scalar-conversion) input); it does not preserve sorted
  map kind. Only stock operations, string keys, bounded strings/Booleans/native
  fixnums and at most 128 entries qualify under the unchanged SDK policy.
  Unknown operations/layouts, malformed node shapes/counts, changed policies and unsupported
  hosts decline without invoking callbacks. No comparator is called. Metadata
  is dropped, input is untouched, and nil requires the caller's normal path.
  Ordinary core behavior and private implementation roots remain trusted;
  this is not a seal against their mutation or a cross-thread redefinition lock."
  [value]
  (or (@sorted-scalar-string-map-converter value) nil))
;; SDK-owned policy checks; ordinary core behavior/private runtime roots are
;; trusted. Accept only the defining SDK's stock sorted-map operation table.
;; Unknown/custom operations decline before invoking a callback or comparator.
(lambda (bindings maximum prototype)
  (let ((tag (keyword "jolt" "type")) (sorted-tag (keyword "jolt" "sorted-map"))
        (ops-key (keyword #f "ops")) (tree-key (keyword #f "tree"))
        (count-key (keyword #f "cnt")))
    (unless (and (htable? prototype) (eq? (jolt-ref-get prototype tag) sorted-tag)
                 (pmap? (jolt-ref-get prototype ops-key)))
      (error 'sorted-scalar-string-map "Unsupported sorted-map prototype"))
    (let ((stock-ops (jolt-ref-get prototype ops-key)))
      (define (same-policy?)
        (let loop ((i 0))
          (or (= i (pvec-count bindings))
              (let ((entry (pvec-nth! bindings i)))
                (and (eq? (var-cell-deref (pvec-nth! entry 0)) (pvec-nth! entry 1))
                     (loop (+ i 1)))))))
      (define (admitted-tree? tree count)
        (let ((seen 0))
          (define (walk node)
            (or (jolt-nil? node)
                (and (pvec? node) (= (pvec-count node) 5) (< seen 128)
                     (begin (set! seen (+ seen 1)) #t)
                     (string? (pvec-nth! node 1))
                     (let ((v (pvec-nth! node 2)))
                       (or (boolean? v) (fixnum? v)
                           (and (string? v) (<= (string-length v) maximum))))
                     (walk (pvec-nth! node 3)) (walk (pvec-nth! node 4)))))
          (and (walk tree) (= seen count))))
      (define (text value)
                 (let ((result (jolt-persistent! out)))
                   (and (pmap? result) (= (pmap-cnt result) 3) (pmap-array? result)
                        (let ((root (pmap-root result)))
                          (and (= (vector-length root) 6)
                               (equal? root
                                 '#("$scalar-probe-a" "7" "$scalar-probe-b" "probe-text"
                                    "$scalar-probe-c" "false")))))))))
        (error 'sorted-scalar-string-map "Unsupported sorted-node geometry"))
      (lambda (m)
        (and (htable? m) (eq? (jolt-ref-get m tag) sorted-tag)
             (eq? (jolt-ref-get m ops-key) stock-ops) (same-policy?)
             (let ((count (jolt-ref-get m count-key)) (tree (jolt-ref-get m tree-key)))
               (and (fixnum? count) (<= 0 count 128)
                    (admitted-tree? tree count) (same-policy?)
                    (let ((out (jolt-transient-new empty-pmap)))
                      (copy-tree! tree out)
                      (jolt-persistent! out)))))))))
    (with-redefs [any/string-bytes (fn [_] (swap! calls inc) 0)]
      (is (nil? (any/try-sorted-scalar-string-map m))))
    (with-redefs [any/default-limits (into {} any/default-limits)]
      (is (nil? (any/try-sorted-scalar-string-map m))))
    (with-redefs [any/min-int64 -1] (is (nil? (any/try-sorted-scalar-string-map m))))
    (with-redefs [any/max-int64 1] (is (nil? (any/try-sorted-scalar-string-map m))))
    (is (zero? @calls))))

(deftest unknown-table-operations-decline-without-trial-callbacks
  (doseq [operation [:seq :count :get]]
    (let [m (fixture 16) calls (atom 0)]
      (host/ref-put! m :ops
                     (assoc (host/ref-get m :ops) operation
                            (fn [& _] (swap! calls inc) (throw (ex-info "custom-op" {})))))
      (is (nil? (any/try-sorted-scalar-string-map m)))
      (is (zero? @calls)))))

(deftest comparator-is-never-called-during-admission-or-copy
  (let [armed (atom false) calls (atom 0)
        m (into (sorted-map-by (fn [a b]
                                (when @armed (swap! calls inc) (throw (ex-info "comparison" {})))
                                (compare b a))) (fixture 16))
        expected (oracle m)]
    (reset! armed true)
    (is (= expected (any/try-sorted-scalar-string-map m)))
    (is (zero? @calls))))

(deftest malformed-node-shapes-and-counts-decline
  (doseq [[field value] [[:cnt -1] [:cnt 0] [:cnt "one"]
                         [:tree [nil "a" 1 nil]] [:tree [nil "a" 1 nil nil :extra]]
                         [:tree [nil :not-string 1 nil nil]]
                         [:tree [nil "a" 1 :not-a-node nil]]]]
    (let [m (sorted-map "a" 1)]

