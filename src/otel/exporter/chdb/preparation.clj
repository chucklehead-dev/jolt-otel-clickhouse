(ns otel.exporter.chdb.preparation
  "Internal resource owner for the explicit single-SDK-preparer adapter.
  No native operations or conversion hooks are selected here. The exporter
  supplies its fixed materializer, late validation/admission and settled await.")

(def ^:private seal (Object.))
(deftype ^:private Reservation [seal owner ordinal]
  Object
  (toString [_] "#<span preparation reservation>"))
(defmethod print-method Reservation [_ out]
  (.write ^java.io.Writer out "#<span preparation reservation>"))
(defmethod print-dup Reservation [_ out]
  (.write ^java.io.Writer out "#<span preparation reservation>"))

(defn- fail! [kind]
  (throw (ex-info "Invalid span preparation ownership transition" {:type kind})))

(defn reservation-byte-count
  "Conservative SQL+WAL array-capacity charge for at most n ASCII SQL bytes.
  WAL used bytes <=6n+11. Each nonfinal chunk uses >=capacity-12>=244 bytes
  and wastes <=12; the final chunk capacity is <=65536. Metadata, transient
  encoder storage, scoped native copies and total RSS are outside this charge.
  Coupled to the driver owned_ascii_wal.ss capacity/flush guards."
  [n]
  (when-not (and (integer? n) (<= 0 n)) (fail! ::invalid-byte-limit))
  (let [used (+ (* 6 n) 11)]
    (+ n used (* 12 (quot used 244)) 65536)))

(defn open-adapter
  "Return the SDK callback contract for ONE preparer. Reserve before encoding;
  count at most two slots including completed-but-unretired requests. Byte
  charges include retained SQL+WAL array capacity, not total RSS. materialize!
  returns {:prepared opaque-value :retained-bytes integer}; try-submit! returns
  the driver's nonwaiting admission map; await-confirmed! settles through
  interruption. admit!/try-submit! MUST be nonwaiting: fence! shares their
  short gate, never the materializer/await/release execution. Only :await! true
  means confirmed publication; :submit! true means admission only. A callback
  throw or known rejection before admission must not hide an admitted request.
  Private field/Var access is outside the supported capability API."
  [{:keys [max-batch-rows max-rows reservation-bytes max-bytes
           admit! release! materialize! try-submit! await-confirmed! on-error!]}]
  (when-not (and (every? #(and (integer? %) (pos? %))
                        [max-batch-rows max-rows reservation-bytes max-bytes])
                 (>= max-rows (* 2 max-batch-rows))
                 (>= max-bytes (* 2 reservation-bytes))
                 (every? fn? [admit! release! materialize! try-submit! await-confirmed!])
                 (or (nil? on-error!) (fn? on-error!)))
    (fail! ::invalid-options))
  (let [gate (Object.)
        owner (atom {:fenced? false :ordinal 0 :slots {} :rows 0 :bytes 0})
        report! (fn [error] (when on-error! (try (on-error! error) (catch Throwable _ nil))))]
    (letfn [(slot! [cap]
              (when-not (and (instance? Reservation cap)
                             (identical? seal (.-seal cap))
                             (identical? owner (.-owner cap)))
                (fail! ::invalid-reservation))
              (or (get-in @owner [:slots (.-ordinal cap)]) (fail! ::retired-reservation)))
            (change! [cap value]
              (swap! owner assoc-in [:slots (.-ordinal cap)] value))
            (drop-bytes! [cap phase]
              (let [slot (slot! cap)]
                (swap! owner
                       (fn [s] (-> s (update :bytes - (:bytes slot))
                                   (assoc-in [:slots (.-ordinal cap)]
                                             (assoc slot :phase phase :bytes 0 :prepared nil)))))))]
      {:reserve!
       (fn [n]
         (locking gate
           (when-not (and (integer? n) (<= 1 n max-batch-rows)) (fail! ::batch-limit))
           (let [s @owner]
             (when (and (not (:fenced? s)) (< (count (:slots s)) 2)
                        (not-any? #(contains? #{:reserved :preparing :prepared} (:phase %))
                                  (vals (:slots s)))
                        (<= (+ (:rows s) n) max-rows)
                        (<= (+ (:bytes s) reservation-bytes) max-bytes))
               (let [ordinal (inc (:ordinal s))
                     cap (Reservation. seal owner ordinal)]
                 (reset! owner (-> s (assoc :ordinal ordinal)
                                   (update :rows + n) (update :bytes + reservation-bytes)
                                   (assoc-in [:slots ordinal]
                                             {:phase :reserved :rows n :bytes reservation-bytes
                                              :admitted? false :prepared nil})))
                 cap)))))
       :prepare!
       (fn [cap spans]
         (locking gate
           (let [slot (slot! cap)]
             (when-not (and (= :reserved (:phase slot)) (not (:fenced? @owner))
                            (vector? spans)
                            (= (:rows slot) (count spans))) (fail! ::prepare-fenced))
             (when-not (true? (admit!)) (fail! ::exporter-admission-rejected))
             (change! cap (assoc slot :phase :preparing :admitted? true))))
         (try
           (let [{:keys [prepared retained-bytes]} (materialize! spans)]
             (locking gate
               (let [slot (slot! cap)]
                 (when-not (and (= :preparing (:phase slot)) prepared
                                (integer? retained-bytes) (pos? retained-bytes)
                                (<= retained-bytes (:bytes slot))) (fail! ::encoded-budget))
                 (swap! owner
                        (fn [s] (-> s (update :bytes - (- (:bytes slot) retained-bytes))
                                    (assoc-in [:slots (.-ordinal cap)]
                                              (assoc slot :phase :prepared :bytes retained-bytes
                                                     :prepared prepared)))))))
             true)
           (catch Throwable error
             (locking gate (drop-bytes! cap :prepare-failed))
             (report! error)
             (throw error))))
       :submit!
       (fn [cap]
         (locking gate
           (let [slot (slot! cap)]
             (when-not (and (= :prepared (:phase slot)) (not (:fenced? @owner))
                            (every? (fn [[ordinal older]]
                                      (or (>= ordinal (.-ordinal cap))
                                          (not (contains? #{:reserved :preparing :prepared}
                                                          (:phase older)))))
                                    (:slots @owner))) (fail! ::publication-fenced))
             ;; This callback revalidates the actual live plan/bindings, then
             ;; performs nonwaiting driver admission. No native wait under gate.
             (let [result (try-submit! (:prepared slot))]
               (cond
                 (and (= :admitted (:status result)) (:ticket result))
                 (do (change! cap (assoc slot :phase :submitted :ticket (:ticket result))) true)
                 (and (contains? #{:full :contended :closed} (:status result))
                      (not (contains? result :ticket))) false
                 :else
                 (do
                   ;; Unknown admission is NOT proof that nothing is active.
                   ;; Keep the reservation/admission; abort/release must fail.
                   (change! cap (assoc slot :phase :admission-unknown))
                   (fail! ::invalid-admission-result)))))))
       :await!
       (fn [cap]
         (let [ticket (locking gate
                        (let [slot (slot! cap)]
                          (when-not (= :submitted (:phase slot)) (fail! ::not-submitted))
                          (change! cap (assoc slot :phase :awaiting))
                          (:ticket slot)))]
           (try
             (let [receipt (await-confirmed! ticket)]
               (locking gate (change! cap (assoc (slot! cap) :phase :settled)))
               (contains? #{:committed :reconciled} (:status receipt)))
             (catch Throwable error
               (locking gate (change! cap (assoc (slot! cap) :phase :settled)))
               (report! error)
               (throw error)))))
       :abort!
       (fn [cap]
         (locking gate
           (when-not (contains? #{:reserved :prepared :prepare-failed} (:phase (slot! cap)))
             (fail! ::cannot-abort-active-request))
           (drop-bytes! cap :aborted)
           true))
       :release!
       (fn [cap]
         (let [admitted? (locking gate
                           (let [slot (slot! cap)]
                             (when-not (contains? #{:settled :aborted} (:phase slot))
                               (fail! ::cannot-release-active-request))
                             (change! cap (assoc slot :phase :releasing))
                             (:admitted? slot)))]
           (try
             ;; Existing exporter release may finish a concurrent owned close;
             ;; never hold the publication fence across that native wait.
             (when (and admitted? (not (true? (release!)))) (fail! ::release-unconfirmed))
             (locking gate (change! cap (assoc (slot! cap) :phase :released)))
             true
             (catch Throwable error
               (locking gate (change! cap (assoc (slot! cap) :phase :release-failed)))
               (report! error)
               (throw error)))))
       :retire!
       (fn [cap]
         (locking gate
           (let [slot (slot! cap)]
             (when-not (= :released (:phase slot)) (fail! ::not-released))
             (swap! owner (fn [s] (-> s (update :rows - (:rows slot))
                                     (update :bytes - (:bytes slot))
                                     (update :slots dissoc (.-ordinal cap))))))
           true))
       :fence! (fn [] (locking gate (swap! owner assoc :fenced? true) true))})))
