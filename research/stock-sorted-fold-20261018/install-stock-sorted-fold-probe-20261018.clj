;; Load only in a disposable diagnostic process. Returns a factory; does not
;; install a default backend or change any library/runtime source on disk.
(require '[clojure.data.json.jolt-native :as probe-native]
         '[clojure.string :as string] '[jolt.scheme :as scheme])
(probe-native/load-payload-byte-buffer-writer!)
(scheme/eval-string (slurp "/home/chuck/ai-src/evidence/stock-sorted-fold-probe-20261018.ss"))
(assert (scheme/eval-string "(pmap? task-stock-sorted-ops)"))
;; Source-mode probe additionally attests the current node geometry and exact
;; stock operation-table match with a newly constructed, order-sensitive map.
;; A real runtime-owned implementation should own this at bootstrap instead.
(let [prototype (sorted-map "$fold-a" 7 "$fold-b" false "$fold-c" "marker")
      tree ((scheme/proc "task-stock-sorted-scalar-tree") prototype)
      fold (scheme/proc "task-stock-sorted-fold")]
  (assert (vector? tree))
  (assert (= [["$fold-a" 7] ["$fold-b" false] ["$fold-c" "marker"]]
             (fold tree (fn [k v acc] (conj acc [k v])) []))))
(def stock-sorted-fold-diagnostic-factory
  (let [source (slurp "/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")
        old "(emit-seq-map! x))"
        replacement "(let ((tree (task-stock-sorted-scalar-tree x)))
                        (if tree
                          (begin (byte! 123)
                            (task-stock-sorted-fold tree emit-map-entry! #f)
                            (byte! 125))
                          (emit-seq-map! x))))"]
    (assert (= 2 (count (string/split source (re-pattern (java.util.regex.Pattern/quote old))))))
    (scheme/eval-string (string/replace source old replacement))))
