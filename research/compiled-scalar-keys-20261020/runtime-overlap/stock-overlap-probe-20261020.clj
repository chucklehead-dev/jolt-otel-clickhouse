;; Runtime capability diagnostic, NOT chDB buffer-lifetime/pipeline proof.
(require '[jolt.ffi :as ffi] '[jolt.scheme :as scheme] '[jolt.host :as host])
(ffi/load-library (first *command-line-args*))
(ffi/defcfn native-phase "overlap_probe_phase" [] :int)
(ffi/defcfn reset-phase "overlap_probe_reset" [] :int)
(ffi/defcfn native-wait "overlap_probe_wait" [:int] :int :blocking)
(def cpu-work
  (scheme/eval-string
    "(lambda () (let loop ((i 1000000) (answer 0)) (if (fx=? i 0) answer (loop (fx- i 1) (fx+ answer 1)))))"))

;; Sequential negative control. Scheme work/collection happens after native exit.
(assert (= 0 (reset-phase)))
(assert (= 0 (native-wait 25)))
(assert (= 1000000 (cpu-work)))
(System/gc)
(assert (= 2 (native-phase)))

(assert (= 0 (reset-phase)))
(let [worker (future (native-wait 3000))]
  (try
    (let [deadline (+ (System/nanoTime) 10000000000)]
      (loop []
        (when (= 0 (native-phase))
          (assert (< (System/nanoTime) deadline) "native worker did not enter")
          (Thread/sleep 1) (recur))))
    ;; This is C-side entry, not a promise delivered before the actual call.
    (assert (= 1 (native-phase)))
    (let [gc-before (host/gc-count) start (System/nanoTime)
          answer (cpu-work)
          _ (System/gc)
          gc-after (host/gc-count) phase-after (native-phase)]
      (assert (= 1000000 answer))
      (assert (> gc-after gc-before) "no observed collection")
      (assert (= 1 phase-after) "native call exited before overlap was established")
      (spit (second *command-line-args*)
        (pr-str {:runtime :stock-jolt-0.8.23 :native-phase-before 1
                 :native-phase-after phase-after :gc-count-delta (- gc-after gc-before)
                 :scheme-work-result answer :elapsed-nanos (- (System/nanoTime) start)
                 :sequential-negative-control true :scalar-only-ffi true
                 :native-buffer-loans false :chdb-io false :pipeline-safety-proven false})))
    (finally
      ;; Do not unload/remove the library or let main exit before this joins.
      (assert (= 0 @worker)))))
(assert (= 2 (native-phase)))
(shutdown-agents)
(println "PASS: Scheme CPU work and an observed GC completed during native C wait; worker joined.")
