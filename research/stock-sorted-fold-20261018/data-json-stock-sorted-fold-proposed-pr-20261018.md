# perf: use optional runtime-owned stock sorted-map folding

## Summary

Let the explicit native byte writer use the runtime's stock sorted-map fold
when available. SDK/application sorted maps currently require temporary entry
and sequence objects plus publication around potentially custom operations.
The runtime can identify its stock operations and visit raw key/value pairs
without copying its private tree layout into data.json.

Both helper procedures and their supported arities are required. Older
runtimes, unavailable helpers and custom per-object operations keep the existing
sequence path. Every child still uses live JSONWriter resolution; custom map
methods, key/value options, prefix edits, exceptions, budgets and reentry retain
their behavior. Ordinary JSON calls and backend defaults are unchanged.

Includes fork changelog, strict capability tests and CI. Existing generic-seq
mechanism test now uses distinct custom-ops identity so it remains effective
even when stock folding is available; a separate same-output mutant proves
stock-fold selection. Wrong-arity/noncallable helpers decline without trial calls.

## Validation

- Exact helper-source overlay: 65 tests/1006 assertions, explicit entry,
  completion/pass markers and exact namespace counts. This is NOT a rebuilt
  latest Jolt or complete artifact qualification.
- Actual helper-absent runtime: byte/sorted/segmented60/982 pass. Ordinary native
  suite38/621 passes separately. CI parses no local credentials or user data.
- Hosted CI at6305f3d passed compatibility/native/JVM and version matrix. Native
  job logs confirm actual60/982 and65/1006 execution, not just step names.
- Final follow-up96f3e47 refreshes pinned helper source646fcfcb (bounded invalid
  argument diagnostic) and adds explicit pass/failure fields to CI markers.
  actionlint and the exact parsed overlay step pass locally. Final-head
  [compatibility CI](https://github.com/casselc/data.json/actions/runs/39146080863)
  and [version-matrix CI](https://github.com/casselc/data.json/actions/runs/39146081324)
  independently pass at96f3e47.
- Independent source and CI reviews completed. Requested arity controls and
  clearer outcome markers added and tested. No global backend selection changes.

## Review branch

https://github.com/casselc/data.json/compare/a18ae06c787e6e03709bd78f1b92911130a613f1...96f3e47dba5970728866dacb5ac41a25135a2df5

Fork-only experimental backend topic. No upstream Clojure contribution or PR
submitted; this is not a proposal to add Jolt tree knowledge to stock data.json.
