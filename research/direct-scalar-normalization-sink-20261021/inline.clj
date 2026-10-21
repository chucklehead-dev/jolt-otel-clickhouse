(load-file "research/direct-scalar-normalization-sink-20261021/prototype.clj")

;; Strict component floor: validation is fused with emission. A rejection
;; discards this entire private output; there is NO SDK fallback/replay adapter.
;; Not suitable as a drop-in for effectful full-span projectors. Maximum100 is
;; fixed for this screen, not a general public normalization configuration.
(defn inline-context [sdk-source]
  (let [base (sink-context :sink sdk-source)
        policy-source (replace-once (:policy-source base) "(admitted? (pmap-root m))" "#t")
        json (replace-once (:json-source base)
               "(define (map-entry! k v printed?)\n      (when printed? (byte! 44)) (key! k) (byte! 58)\n      (when (fx>=? container-depth max-depth)"
               (str "(define (map-entry! k v printed?)\n      (when printed? (byte! 44))\n"
                    "      (unless (and (string? k)\n"
                    "                   (or (boolean? v) (fixnum? v)\n"
                    "                       (and (string? v) (<= (string-length v) 100))))\n"
                    "        (error 'sink \"unadmitted scalar\"))\n"
                    "      (key! k) (byte! 58)\n      (when (fx>=? container-depth max-depth)"))
        factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) "
                       "(let () " json "\nffc-json-project-prefixed-ascii-lines-bytes))"))]
    {:writer (apply factory (traversal/native-kernels (traversal/open-traversal)))
     :policy-maker (scheme/eval-string policy-source)
     :policy-source policy-source :json-source json}))
