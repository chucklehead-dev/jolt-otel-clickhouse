# Read-only review: function versus sorted collection equality

Review exact casselc/jolt commit388a3ad613f35a519aeefb0b895ff82b17088616,
base v0.8.23a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5, in
/home/chuck/ai-src/worktrees/jolt-0823-sorted-function-equality-20261018.
Three changed files: host/chez/host-table.ss, test/chez/sorted-test.ss,
CHANGELOG.md. Read source/tests only; no edits, commands, credentials, data,
other repos, delegation or publication. <=650words, concrete findings and
semantic risks, not a generic summary or invented performance qualification.

Change: sorted-fast= rejects a concrete Scheme procedure as the other operand
before constructing a plain map/set. It runs only after the normal equality
registry selects the existing sorted arm; it does not move type rejection ahead
of user predicates or change registry ordering. Native procedures represent
ordinary Clojure functions here; records/reify/host maplike/setlike/callable
objects retain all existing fallback behavior. Legitimate sorted/plain/sorted
different-comparator comparisons are unchanged.

Tests pass68/68 on latest source (mandatory Chez10.4.1). Existing sorted
collection gates plus false values both operand orders/empty/nonempty; explicit
fast-branch verdict; later selected custom equality handler still wins; native
allocation at10kand40k entries remains208..496bytes/comparison, versus full
normalization formerly. The same new test file against the older function body
is running as an expected-failure control. No new compiler source changed, seed
remint required, upstream PR submitted or global install modified.

Pay particular attention to semantic boundaries: earlier behavior normalized
the whole sorted collection and redispatched (= function plain-map/set), so
later-in-the-walk callbacks could run again on artificially normalized operands,
and normalizing a map could run key hash/equality callbacks before concluding
false. The new mismatch avoids that internal normalization. Are any supported
representations actually concrete procedures yet legitimately map/set-equal?
Does a custom equality extension that only recognizes a normalized operand have
a compatibility contract we would be breaking? Distinguish natural Clojure type
semantics from arbitrary native/private extension behavior; don't silently
claim every callback side effect is unchanged. Identify any practical missing
negative control or blocker before promoting this as the underlying fix.

Motivation: an ordinary function marker compared against a nested sorted map
currently allocates a full HAMT to answer false. A separate bounded library
experiment checking its known default marker by identity saved~6.7KB/row and
~22%full-encoding p50, with stable complete output, but changes default-marker
equality semantics at that library boundary. This runtime alternative should
help existing data.json and other consumers without that library modification.
Its actual consumer performance is NOT measured yet; don't assume equal wins.
