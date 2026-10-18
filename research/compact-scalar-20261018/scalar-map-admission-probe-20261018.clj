(require '[jolt.scheme :as scheme] '[clojure.string :as string]
         '[clojure.java.io :as io] '[otel.any-value :as any]
         '[otel.exporter.chdb :as e])
(let [m (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "s" 1 false i)]) (range 16)))
      old "(jolt-invoke1 (var-cell-deref value-var) (cdr child))"
      clone (scheme/eval-string (string/replace
                                 (slurp (io/resource "otel/exporter/chdb/native_attribute_value_clone.ss"))
                                 old "(value-var (cdr child))"))
      factory (scheme/eval-string (slurp "/home/chuck/ai-src/evidence/scalar-map-factory-20261018.ss"))
      fast (factory clone (fn [_] (throw (ex-info "fast-path-declined" {})))
                    #'e/key-string #'e/value-string
                    @#'e/original-attribute-key-string @#'e/original-attribute-value-string
                    #'any/default-string-value-max-chars any/default-string-value-max-chars)
      shape (scheme/eval-string "(lambda (m) (vector (pmap? m) (pmap-cnt m) (hnode? (pmap-root m))))")]
  (prn {:shape (str (shape m)) :maximum (any/default-string-value-max-chars)
        :sdk-default (any/default-scalar-string?)
        :key-original (identical? @#'e/key-string @#'e/original-attribute-key-string)
        :value-original (identical? @#'e/value-string @#'e/original-attribute-value-string)})
  (assert (= (into {} (map (fn [[k v]] [k (@#'e/value-string v)]) m)) (fast m)))
  (prn {:actual-fast-path-witness true}))
