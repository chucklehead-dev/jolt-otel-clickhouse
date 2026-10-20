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
         "'((make-pmap 2) "
         "(jolt-transient-new 1) (jolt-persistent! 1) (tmap-put! 3) "
         "(amap-index 3) (vec-copy-range 3) (var-cell-deref 1) (jolt-invoke1 2))) "
         "(error 'native-attributes \"Required runtime projection primitives are unavailable\")) "
         "(unless (and (procedure? pmap-fold-seq-order) "
         "(bitwise-bit-set? (procedure-arity-mask pmap-fold-seq-order) 3)) "
         "(error 'native-attributes \"Map traversal is unavailable\")) "
         "(let ((clone " (slurp clone) ") (generic " (slurp generic) ")) "
         "(lambda (key-var value-var original-key) "
         "(lambda (m) "
         "(or (clone m key-var value-var original-key) (generic m key-var value-var))))))")))

(def ^:private transform-source (source))

(defn load-transform!
  "Convert supported built-in maps, returning false for declined inputs.
  Converter Vars stay live per entry; input nodes are never mutated. Each call
   owns mutable construction storage and preserves sequence-order effects.
   The internal third argument must be the exporter's original string-identity
   key converter, not an arbitrary callback; two-argument callers retain the
   fully generic live converter path."
  ([key-var value-var] (load-transform! key-var value-var false))
  ([key-var value-var original-key]
   (load-transform! key-var value-var original-key nil))
  ([key-var value-var original-key folder]
   (let [factory (if folder
                   ((scheme/eval-string (str "(lambda (pmap-fold-seq-order) " transform-source ")")) folder)
                   (scheme/eval-string transform-source))
         transform (factory key-var value-var original-key)]
     ;; Bind stable Var identities once, on the native side. Dereferencing the
     ;; Vars stays per entry. Keep a Clojure IFn facade while reducing each
     ;; native call to its sole varying argument, the map being converted.
     (fn [attributes] (transform attributes)))))
