(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[jolt.scheme :as scheme] '[jolt.traversal.experimental :as traversal])

;; Research specialization ONLY: every map is a pre-admitted flat attribute map.
;; It is not a general JSON replacement or an SDK-authorized adapter. No opaque
;; borrowed view escapes: a row's original immutable map is read synchronously
;; and only the independently owned output bytes leave the call.
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))

(defn sink-context [kind sdk-source]
  (assert (contains? #{:copy :sink :unquoted-mutant} kind))
  (let [copy? (= kind :copy)
        policy-source (if copy? sdk-source
                        (replace-once sdk-source
                          "(make-pmap (copy-node (pmap-root m)) (pmap-cnt m))" "m"))
        json (slurp (io/resource "jolt/codec/json.ss"))
        emit-number (if (= kind :unquoted-mutant) "(integer! v)"
                      "(begin (byte! 34) (integer! v) (byte! 34))")
        json (if copy? json
               (replace-once json
                 "(key! k) (byte! 58)\n      (emit! v (fx+ container-depth 1)) #t)"
                 (str "(key! k) (byte! 58)\n"
                      "      (when (fx>=? container-depth max-depth) (error 'sink \"depth limit exceeded\"))\n"
                      "      (cond ((string? v) (string! v))\n"
                      "            ((boolean? v) (string! (if v \"true\" \"false\")))\n"
                      "            ((fixnum? v) " emit-number ")\n"
                      "            (else (error 'sink \"unadmitted scalar\"))) #t)")))
        factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) "
                       "(let () " json "\nffc-json-project-prefixed-ascii-lines-bytes))"))
        writer (apply factory (traversal/native-kernels (traversal/open-traversal)))]
    {:writer writer :policy-maker (scheme/eval-string policy-source)
     :policy-source policy-source :json-source json}))

(def bytes-equal?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))

(defn sink-encode [context bindings maximum rows limit depth]
  (let [convert ((:policy-maker context) bindings maximum)]
    ((:writer context) "" rows
      (fn [m] (or (convert m) (throw (ex-info "Scalar input declined" {:type ::declined}))))
      limit depth)))
