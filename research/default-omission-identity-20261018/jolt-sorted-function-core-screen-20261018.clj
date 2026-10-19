;; Source-overlay screen only. Unchanged JSON/exporter; NOT a real-binary
;; runtime upgrade or Durable throughput qualification.
(require '[jolt.scheme :as scheme])
(let [arm (System/getenv "ROW_EMISSION_JSON_ARM")
      source (slurp "/home/chuck/ai-src/worktrees/jolt-0823-sorted-function-equality-20261018/host/chez/host-table.ss")
      start (.indexOf source "(define (sorted-fast= s o)")
      end (.indexOf source "(register-eq-arm!" start)]
  (assert (contains? #{"baseline" "candidate"} arm))
  (assert (and (<= 0 start) (< start end)))
  (when (= "candidate" arm) (scheme/eval-string (subs source start end)))
  (let [probe (scheme/eval-string
                "(lambda (m f)
                   (let ((b (sstats-bytes (statistics))))
                     (do ((i 0 (+ i 1))) ((= i 100))
                       (unless (not (jolt=2 f m))
                         (error 'core-screen \"wrong equality result\")))
                     (quotient (- (sstats-bytes (statistics)) b) 100)))")
        m (into (sorted-map) (map (fn [i] [i i])) (range 17))
        allocation (probe m identity)]
    ;; This goes through actual jolt=2, not just the replaced helper. A source
    ;; overlay ignored by an already-inlined handler cannot pass this control.
    (assert (if (= "candidate" arm) (< allocation 1024) (> allocation 2048)))
    (spit (str (first *command-line-args*) ".mechanism.edn")
          (pr-str {:scope :actual-equality-core-overlay-mechanism
                   :arm arm :estimated-bytes-per-comparison allocation
                   :candidate-source "388a3ad6" :json "96f3e47"}))))
(load-file "/home/chuck/ai-src/evidence/json-rich-row-vs-emission-output-20261018.clj")
