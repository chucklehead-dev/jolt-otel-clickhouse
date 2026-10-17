(ns otel.exporter.chdb-curl-owner-ledger
  "Test-only native call ledger. Tokens identify lifetimes, never C addresses.
  This observes calls, not caller authority after a raw address is reissued.")

(defn initial [] {:active [] :owners []})

(defn- require! [condition]
  (when-not condition
    (throw (ex-info "Invalid native owner event" {:check ::invalid-event}))))

(defn transition [state action handle-or-token]
  (let [active (:active state) owners (:owners state)
        entry (some #(when (identical? handle-or-token (:handle %)) %) active)
        completion? (contains? #{:performed :cleaned} action)
        token (if completion? handle-or-token (:token entry))]
    (case action
      :init
      (do (require! (nil? entry))
          (let [token (count owners)]
            (-> state
                (update :active conj {:handle handle-or-token :token token})
                (update :owners conj {:requests 0 :in-flight 0 :cleanups 0 :retiring? false})
                (assoc :token token))))
      (:perform :retire)
      (do (require! (some? entry))
          (if (= action :perform)
            (do (require! (zero? (get-in state [:owners token :in-flight])))
                (-> state (update-in [:owners token :in-flight] inc) (assoc :token token)))
            (do (require! (zero? (get-in state [:owners token :in-flight])))
                (-> state
                    (assoc :active (filterv #(not= token (:token %)) active))
                    (assoc-in [:owners token :retiring?] true)
                    (assoc :token token)))))
      (:performed :cleaned)
      (do (require! (and (integer? token) (<= 0 token) (< token (count owners))))
          (if (= action :performed)
            (do (require! (pos? (get-in state [:owners token :in-flight])))
                (-> state (update-in [:owners token :in-flight] dec)
                    (update-in [:owners token :requests] inc) (assoc :token token)))
            (do (require! (and (true? (get-in state [:owners token :retiring?]))
                               (zero? (get-in state [:owners token :cleanups]))))
                (-> state (assoc-in [:owners token :retiring?] false)
                    (update-in [:owners token :cleanups] inc) (assoc :token token)))))
      (do (require! false) state))))

(defn step! [ledger action handle-or-token]
  ;; swap! retries only this pure transition. Its returned immutable snapshot
  ;; carries this call's token even if another thread updates the atom next.
  (:token (swap! ledger transition action handle-or-token)))

(defn settled? [state]
  (and (empty? (:active state))
       (every? #(and (= 1 (:cleanups %)) (zero? (:in-flight %))
                      (false? (:retiring? %))) (:owners state))))
