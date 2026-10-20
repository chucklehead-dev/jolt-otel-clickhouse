;; Same-process old fused vs new separated native kernel. Reuses the existing
;; rich fixture, timers, full digests and changed-event controls. No storage I/O.
(require '[clojure.string :as str])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [source (slurp "/home/chuck/ai-src/worktrees/exporter-stock-payload-cache-20261019/incubator/codec/src/jolt/codec/experimental.clj")]
  (binding [*ns* *ns*]
    (load-string (replace-once source "(ns jolt.codec.experimental" "(ns research.fused-codec")))
  (assert (find-ns 'research.fused-codec)))
(def baseline-c ((ns-resolve 'research.fused-codec 'open-codec)))
(def baseline-encode @(ns-resolve 'research.fused-codec 'encode-prefixed-records))
(def baseline-nested @(ns-resolve 'research.fused-codec 'prepare-nested-text))
(let [source (slurp "/home/chuck/ai-src/evidence/library-nested-text-component-20261019.clj")
      source (replace-once source "(codec/encode-prefixed-records c"
                           "((if (= mode :string) baseline-encode codec/encode-prefixed-records) (if (= mode :string) baseline-c c)")
      source (replace-once source "(codec/write-nested c value)" "(baseline-nested baseline-c value)")
      source (replace-once source ":scope :full-rich-projection-nested-outer-component"
                           ":scope :fused-versus-separated-native-kernel-component :mode-labels {:string :fused :bytes :separated}")]
  (load-string source))
