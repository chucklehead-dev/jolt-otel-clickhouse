;; Explicit disposable source overlay of the proposed runtime helper and its
;; optional consumer. NOT a replacement compiler, public API, or default backend.
(require '[jolt.scheme :as probe-scheme]
         '[clojure.data.json.jolt-native :as probe-native])
(probe-native/load-payload-byte-buffer-writer!)
(let [source (slurp "/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018/host/chez/host-table.ss")
      start (.indexOf source ";; Internal native-consumer helpers,")
      end (.indexOf source ";; --- extend the collection dispatchers")]
  (assert (and (<= 0 start) (< start end)))
  (probe-scheme/eval-string (subs source start end)))
(assert ((probe-scheme/proc "stock-sorted-map?") (sorted-map "probe" 7)))
(def stock-sorted-fold-diagnostic-factory
  (probe-scheme/eval-string
    (slurp "/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")))
(assert (probe-scheme/eval-string "(and djn-stock-sorted-map-folder #t)"))
