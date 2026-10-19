# Read-only review: default JSON omission identity

Review only exact casselc/data.json commit
2172061060a102ccab779f5adc5d77f8b83a81f6 against parent
96f3e47dba5970728866dacb5ac41a25135a2df5 in
/home/chuck/ai-src/worktrees/data-json-default-omission-identity-20261018.
Read source/tests/docs only. Do not edit, run commands, inspect credentials,
telemetry, stores, environment files, other repositories, or publish/delegate.
Return <=700 words: concrete correctness defects, semantic/contract risks and
missing controls, with exact file/line references. Source review is not a
performance or end-to-end durability qualification.

Nine changed files: .github/workflows/jolt.yml, CHANGELOG.md, README.md,
deps.edn, src/main/clojure/clojure/data/json.clj,
src/main/clojure/clojure/data/json/jolt_native.ss,
src/main/clojure/clojure/data/json/jolt_byte_writer.ss,
src/test/clojure/clojure/data/json_test.clj,
src/test/jolt/clojure/data/json_default_omission_test.clj.

The portable write-object function already distinguishes the exact defining
default-value-fn from custom callbacks. Only the default omission check changes
from equality to identity. Custom value functions keep =, including callable
objects with custom equality, transformation order and omission behavior.
Reader value functions are untouched. Native djn-flags permits only the stock
callback identities (plus boolean escape switches); other options decline to
portable. The byte writer admits the captured default option object only.
Both admitted native checks use eq? for that same default function marker.

IMPORTANT semantic boundary: this intentionally removes arbitrary equality-
extension effects from the DEFAULT marker check. It does not purport to preserve
those effects or silently declare every old behavior unchanged. README and
changelog make this explicit. Existing native publication boundaries are retained
conservatively, even though the marker check itself is now callback-free.
Assess whether the narrowed semantics are justified by the known stock
function marker; flag any remaining option path where custom callable equality
could accidentally be bypassed. Do not suggest changing arbitrary equality
runtime-wide or bypassing custom collection operations.

Motivation: in the qualified Jolt runtime, comparing the default function with
a sorted map constructs a whole plain map before concluding unequal. A no-GC
allocation probe attributes about 6.7KB/record to omission equality in a nested
event fixture. Eliminating general traversal or callbacks elsewhere is NOT
proposed; only this known marker's comparison changes. The matched speed test
is pending and must not be assumed successful.

Main-agent gates already pass: focused4tests/16assertions; earlier baseline
fails the two default-equality mechanism checks (ordinary and native), while
the custom comparison control passes. Native byte/sorted/segmented65tests/
1006assertions; native live-hook/string38tests/621assertions; Jolt portable
compatibility runner; JVM Clojure1.12.2 90tests/254assertions. The focused native
test installs an isolated-process equality observer BEFORE loading JSON because
with-redefs of the public = Var cannot observe an intrinsified operation. It
includes an independent positive observer control and is a separate CI process.

Check marker identity, custom-callable fallbacks, manually supplied option/stock
capabilities within the documented private ABI, metadata-wrapped functions,
reentry/callback visibility and whether regression controls are non-vacuous.
