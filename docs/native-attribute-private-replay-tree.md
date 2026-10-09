# Private replay storage for native attribute conversion

This changes only the explicitly selected, source-only native attribute
transform. The default exporter and JSON backend are unchanged.

The existing optimization admits built-in hash maps with 9–128 entries,
string keys and no hash-collision buckets. It calls the live key and value
converters for every entry in the same order as the map's sequence. Other
layouts keep the established generic conversion path.

Previously, conversion built an output tree and a separate vector of converted
entries. The new version uses the completed output leaves as the replay record.
It links each private child node before filling it. If a converted key changes,
the ordinary map builder receives the completed prefix in sequence order,
then the changed entry, before conversion proceeds. Empty private slots are
skipped. No converter is called twice.

Output node arrays are always fresh. A leaf pair can be shared with the immutable
input only when both converter results are identical to the original key and
value references. Persistent and transient updates replace leaves rather than
mutating shared pairs. Invocation-local trees and fallback builders are never
published incomplete; failures and nested calls do not share construction state.

## Evidence and limits

Parent exporter `0d1e41ed2ef3288a250b9533dd55b491ab210d0a`, compiler
`20f25cf4cbef710ab00cd7ea7b903233477557e0`, JSON `c2cf28a` (production writer
identical to `ceca453`), SDK `19fc49d`; mandatory Chez10.4.1.

Prepared 10,000 wide typed rows, ABBA20samples/arm with three warmups:
allocation33.237/33.238MB ->30.678/30.678MB, about2.56MB (7.7%) less.
Median timings109.607/104.428ms ->104.379/102.904ms; timing overlaps and is not
a demonstrated Durable p99 win. Exact rows and6,068,398 final bytes matched.
The first prototype screen resolved Var cells per map, unlike the real loader,
and is not an equivalent performance comparison. The corrected screen captures
Var cells once, while still dereferencing their live roots for every converter.

Actual-source focused suites passed25tests688assertions. Tests cover first,
middle and last key changes, duplicates, layout/collision fallback, live root
changes, failures, reentry, input ownership, ordering and JSON parity. New tests
positively locate an actual partially filled child, exercise fallback across a
nested wide conversion, and verify unchanged leaf sharing with fresh arrays and
persistent/transient update isolation. A delayed-child-publication mutant is
rejected by an expected semantic failure, not a parser or native error.

An independent internal read-only source review found no blocking issue. This
does not replace required Claude review, real Durable writes/fresh recovery,
full local tail qualification, application socket or hostedS3 qualification.
No native lifecycle, WAL, lease, ACK, timestamp or model transition changed.
No exhaustive model rerun was triggered, and no Lemonade server was accessed.
