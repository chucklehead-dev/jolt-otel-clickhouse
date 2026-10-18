;; UPPER-BOUND DIAGNOSTIC ONLY. Deliberately bypasses registered class
;; predicates for stock-tagged sorted maps. This is NOT compatible with custom
;; receiver predicates/extensions and MUST NOT be adopted as production code.
(let ((original value-host-tags)
      (string-owner protocol-string-tag-chain-owner)
      (primitive-owner protocol-primitive-tag-chain-owner)
      (tag (keyword "jolt" "type")) (sorted-map-tag (keyword "jolt" "sorted-map")))
  (define (candidate obj)
    (if (and (htable? obj) (eq? (jolt-ref-get obj tag) sorted-map-tag))
        (jch-tags "clojure.lang.PersistentTreeMap")
        (original obj)))
  (lambda (enabled?)
    (set! value-host-tags (if enabled? candidate original))
    ;; The diagnostic wrapper delegates unchanged for existing family-cache
    ;; domains; do not accidentally disable all primitive/string fast hits.
    (set! protocol-string-tag-chain-owner
      (if (and enabled? (eq? string-owner original)) candidate string-owner))
    (set! protocol-primitive-tag-chain-owner
      (if (and enabled? (eq? primitive-owner original)) candidate primitive-owner))
    enabled?))
