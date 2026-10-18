(require '[jolt.ffi :as ffi]
         '[clojure.data.json.jolt-native :as native]
         '[otel.sdk.tracer :as sdk] '[otel.sdk.export :as export]
         '[otel.exporter.memory :as memory] '[otel.resource :as resource]
         '[otel.trace :as trace] '[otel.exporter.chdb :as e])

;; collectorAPI.c declares both controls void(void). Bind process symbols so a
;; missing injected collector fails instead of silently loading inert stubs.
;; https://fuchsia.googlesource.com/third_party/binutils-gdb/+/refs/tags/gdb-16.1-release/gprofng/libcollector/collectorAPI.c
(ffi/load-library)
(ffi/defcfn resume-profile "collector_resume" [] :void)
(ffi/defcfn pause-profile "collector_pause" [] :void)

(let [n 2000
      input (into {"flag" false "count" 42 "service.name" "producer-shape"}
                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      sink (memory/exporter)
      provider (sdk/tracer-provider {:resource (resource/resource input)
                                     :processors [(export/simple-processor sink)]})
      tracer (sdk/get-tracer provider {:name "native-profile" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [span tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! span "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink) row @#'e/span-row]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operation #(native/write-prefixed-batch-bytes!
                       "PREFIX " (map (fn [span] (row span nil true)) spans) 67108864)
          expected (operation) expected-size (alength expected)]
      (dotimes [_ 2] (assert (= expected-size (alength (operation)))))
      (println :profile-ready :rows n :iterations 20)
      (flush)
      (resume-profile)
      (try (dotimes [_ 20] (assert (= expected-size (alength (operation)))))
           (finally (pause-profile)))
      (assert (= (String. expected "UTF-8") (String. (operation) "UTF-8")))
      (println :profile-complete :rows (* n 20) :byte-parity true))))
