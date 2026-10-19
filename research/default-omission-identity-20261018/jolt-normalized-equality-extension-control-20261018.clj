;; Public legacy registration exposes the compatibility boundary of the draft
;; runtime fast reject. This is an isolated-process research control only.
(def marker (fn [x] x))
(__register-eq!
  (fn [a b]
    (and (identical? a marker) (map? b) (not (sorted? b))))
  (fn [_ _] true))
(prn {:scope :normalized-only-legacy-equality-extension
      :plain (= marker {"a" 1}) :sorted (= marker (sorted-map "a" 1))})
