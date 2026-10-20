;; Small read/write diagnostic on OWN temporary file, no native database.
(require '[jdbc.chdb.byte-output :as output] '[jolt.scheme :as scheme])
(import '[java.nio.file Files OpenOption] '[java.io BufferedOutputStream])
(let [path (Files/createTempFile "native-floor-loan-probe-" ".bin" (make-array java.nio.file.attribute.FileAttribute 0))
      describe (scheme/eval-string
                 "(lambda (out)
                    (if (and (jhost? out) (vector? (jhost-state out)))
                        (let ((s (jhost-state out)))
                          (jolt-vector (jhost-tag out) (vector-length s)
                            (and (> (vector-length s) 0) (port? (vector-ref s 0)))
                            (and (> (vector-length s) 0) (port? (vector-ref s 0))
                                 (file-port? (vector-ref s 0)))))
                        #f))")
      bytes (byte-array [1 2 3 4])]
  (try
    (with-open [raw (Files/newOutputStream path (make-array OpenOption 0))
                buffered (BufferedOutputStream. raw)]
      (prn {:raw (describe raw) :buffered (describe buffered)
            :same-object (identical? raw buffered)
            :raw-borrow-fast? (#'output/try-write! raw bytes 4)
            :buffered-borrow-fast? (#'output/try-write! buffered bytes 4)}))
    (finally (assert (Files/deleteIfExists path)))))
