# Independent review and CI follow-up

Claude Sonnet read-only review of the runtime/test/docs slice at `1d803ba`
against `6a98b94`: **Approve; no blockers found**. Source inspection covered
backend selection, supported signal preflight, reentrant/concurrent binding
isolation, native oracle, CI and evidence. This is an advisory source review,
not an executed integration or security proof. Codex owns validation.

The sole optional suggestion questioned whether the negative-zero oracle
self-check detects removal of its exemption. Codex tested that exact mutation
on official stock Jolt 0.8.23: the original canonicalizer distinguishes -0.0
and 0.0; disabling `clojure.string/starts-with?` makes both canonicalize to
integer zero, so the existing inequality check would fail. No source change
is warranted for that suggestion. Command (same published alias/test paths
as the native runner):

```clojure
(require '[otel.exporter.chdb-mixed-codec-native :as n]
         '[clojure.string :as s])
(assert (not= (n/canonical-cell -0.0) (n/canonical-cell 0.0)))
(with-redefs [s/starts-with? (fn [& _] false)]
  (assert (= (n/canonical-cell -0.0) (n/canonical-cell 0.0))))
;; :negative-zero-preserved-and-erasure-mutation-detected; terminal 0
```

First hosted run: 39381157072 at `1d803ba`. Source 28/287, standalone 28/287,
published exporter 62/497 and capability checks passed. Native phase correctly
rejected `:native-version`: the driver's installer fetched its default 26.7.3,
not the 26.9.0 used locally and required by this gate. This is a CI setup
failure, not a successful native test. The follow-up pins both the official
26.9.0 archive and extracted library checksums explicitly, using the already
qualified native-269 workflow's pins. Version assertions remain unchanged;
the corrected hosted result is still a required publication gate.
