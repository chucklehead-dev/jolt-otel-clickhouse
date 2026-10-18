# ASCII scratch-prefix candidate: not selected

Local-only candidatea3799fb37a3d234f6980e26db621e41c9162a944,
base96f3e47dba5970728866dacb5ac41a25135a2df5,
treef75616b40c119bb2c0fea0d26180b6a2f318ae75.
Branchperf/ascii-byte-scratch-string-20261018 remains clean and unpushed.

The candidate constructs an ASCII String directly from the used byte prefix
instead of copying that prefix into a temporary bytevector and decoding it.
Checked operations only; nonASCII retains the original decoder. No callback
visibility, publication, row budget or batch output boundary changed.

Focused3tests/31assertions and existing65tests/1006assertions passed
(session88619terminal0). Initial focused setup failed because it loaded only
the basic writer rather than the byte writer/sink-proof registry; initial
failure retained and not credited as success. Claude42509terminal0 reviewed
source read-only. Its fallback concern does not identify a changed behavior:
fallback deliberately calls the same existing UTF8 decoder; production default
escaping yields ASCII. Review is advisory, not a performance gate.

Actual library local-root overrides, fresh-processABBA, runtime7098,
exporter4d/SDK7878 held fixed. All complete5000-row bytes/hash controls passed.
Session89081terminal0. Pooled32normal-lazy observations per arm:

| | p50ms | p90ms | p95ms | p99ms | estimatedB/row |
| --- | ---: | ---: | ---: | ---: | ---: |
| Base96f |304.777|342.298|348.846|360.319|23917.2|
| Candidatea379 |333.940|422.321|451.527|547.690|23404.5|

~513B/row saved (~2.1%),~9.6%slower p50. Both candidate processes are slower
than bracketing baselines. Prepared mode supports the same selection.
Do not promote, repin, push or describe it as an upstream performance win.
Retain all previously selected cumulative improvements. No Durable throughput
or service-tail claim follows from this component screen.
