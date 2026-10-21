;; Follow-up changes code generation, not fixture/threshold/sample selection.
;; Keep the original callback body intact; inline the value writer on prefix hits.
(load-file "research/template-key-prefixes-20261021/prototype.clj")
(def inline-source
  (let [entry-start (str/index-of original-source "(define (scalar-text-entry! k ordinal printed?)")
        entry-end (str/index-of original-source "    (define (emit! x depth)")
        entry (subs original-source entry-start entry-end)
        body-start (str/index-of entry "(when (fx>=? container-depth max-depth)")
        body-end (str/index-of entry "\n      #t)")
        body (replace-once (subs entry body-start body-end)
                           "(vector-ref scalar-values ordinal)"
                           "(vector-ref scalar-values (vector-ref ordinals i))")
        extracted-start (str/index-of candidate-source "(define (scalar-text-value! ordinal)")
        extracted-end (str/index-of candidate-source ";; Encoder-local weak template roots.")]
    (assert (every? some? [entry-start entry-end body-start body-end extracted-start extracted-end]))
    (-> candidate-source
        (replace-once (subs candidate-source extracted-start extracted-end) entry)
        (replace-once "(scalar-text-value! (vector-ref ordinals i))" (str "(begin " body ")")))))
(def inline-disabled-source
  (replace-once inline-source "(fx<? template-attempts 8)" "(fx<? template-attempts 0)"))
(def inline-native (compile-prefix-native inline-source inline-disabled-source))
