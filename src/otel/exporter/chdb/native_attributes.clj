(ns otel.exporter.chdb.native-attributes
  "Internal bounded attribute conversion for the explicit native byte backend."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(defmacro ^:private source []
  (let [generic (io/resource "otel/exporter/chdb/native_attributes.ss")
        clone (io/resource "otel/exporter/chdb/native_attribute_value_clone.ss")]
    (when-not (and generic clone)
      (throw (ex-info "Missing native attribute projection resource" {})))
    (str "(begin "
         "(unless (and "
         "(eq? (record-type-uid (record-type-descriptor pmap)) 'chez-pmap-v6) "
         "(equal? (record-type-field-names (record-type-descriptor pmap)) '#(root cnt hasheq meta)) "
         "(eq? (record-type-uid (record-type-descriptor hnode)) 'chez-hnode-v1) "
         "(equal? (record-type-field-names (record-type-descriptor hnode)) '#(bm arr)) "
         "(eq? (record-type-uid (record-type-descriptor hcoll)) 'chez-hcoll-v1) "
         "(equal? (record-type-field-names (record-type-descriptor hcoll)) '#(hash alist))) "
         "(error 'native-attributes \"Runtime map layout is not qualified\")) "
         "(unless (for-all (lambda (entry) "
         "(and (top-level-bound? (car entry)) "
         "(procedure? (top-level-value (car entry))) "
         "(bitwise-bit-set? (procedure-arity-mask (top-level-value (car entry))) (cadr entry)))) "
         ;; Record forms are lexical syntax, not top-level procedures. The
         ;; nongenerative UID/field fence above pins their known image layout;
         ;; these checks separately fence callable helper presence and arity.
         "'((make-pmap 2) (pmap-fold-seq-order 3) "
         "(jolt-transient-new 1) (jolt-persistent! 1) (tmap-put! 3) "
         "(amap-index 3) (vec-copy-range 3) (var-cell-deref 1) (jolt-invoke1 2))) "
         "(error 'native-attributes \"Required runtime projection primitives are unavailable\")) "
         "(let ((clone " (slurp clone) ") (generic " (slurp generic) ")) "
         "(lambda (m key-var value-var) "
         "(or (clone m key-var value-var) (generic m key-var value-var)))))")))

(def ^:private transform-source (source))

(defn load-transform!
  "Convert supported built-in maps, returning false for declined inputs.
  Converter Vars stay live per entry; input nodes are never mutated. Each call
  owns mutable construction storage and preserves sequence-order effects."
  [key-var value-var]
  (let [transform (scheme/eval-string transform-source)]
    (fn [attributes] (transform attributes key-var value-var))))
