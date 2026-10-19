(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[jolt.scheme :as scheme])
(native/load-payload-byte-buffer-writer!)
(let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
      gated (str/replace source "(if (and (or (fx=? cp 34)"
              "(if (and (vector-ref research-mode 0) (or (fx=? cp 34)")
      observed (str/replace gated "(#3%bytevector-u8-set! buf cursor 92)"
                 "(begin (vector-set! research-mode 1 (+ 1 (vector-ref research-mode 1)))
                         (#3%bytevector-u8-set! buf cursor 92))")
      _ (assert (true? (not= source gated)))
      _ (assert (true? (not= gated observed)))
      stage (scheme/eval-string
              (str "(let ((research-mode (vector #t 0))) " observed
                " (jolt-vector djn-make-byte-buffer-writer
                    (lambda (flag) (vector-set! research-mode 0 flag)
                                   (vector-set! research-mode 1 0))
                    (lambda () (vector-ref research-mode 1))))"))
      loader-calls (atom 0)
      expected (str (json/write-str "\"\\/") "\n")
      controls
      (with-redefs [native/load-payload-byte-buffer-writer!
                    (fn [] (swap! loader-calls inc) ((nth stage 0)))]
        (mapv (fn [fast?]
                ((nth stage 1) fast?)
                (let [output (String. (native/write-prefixed-batch-bytes! "" ["\"\\/"] 100)
                               "UTF-8")
                      escapes ((nth stage 2))]
                  (assert (= expected output))
                  (assert (= (if fast? 3 0) escapes))
                  {:fast? fast? :direct-escapes escapes :output-equal true}))
          [false true]))]
  (assert (= 2 @loader-calls))
  (prn {:gate :shared-code-toggle-live-entry-control :passed true
        :loader-calls @loader-calls :controls controls}))
