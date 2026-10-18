(require '[otel.any-value :as any] '[otel.attributes :as attributes]
         '[jolt.scheme :as scheme])
(let [input (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "s" 1 false i)]) (range 16)))
      normalized (attributes/normalize input)
      builtin? (scheme/eval-string "(lambda (m) (pmap? m))")]
  (assert (= input normalized))
  (prn {:input-type (str (type input)) :normalized-type (str (type normalized))
        :input-builtin-hamt (builtin? input) :normalized-builtin-hamt (builtin? normalized)
        :input-sdk-bulk-selected (some? (any/try-scalar-string-map input))
        :normalized-sdk-bulk-selected (some? (any/try-scalar-string-map normalized))
        :values-equal true :attribute-count (count normalized)}))
