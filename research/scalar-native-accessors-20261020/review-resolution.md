# Review and published selection

Claude Sonnet read-only review of kernel commit15b15f3: **APPROVE**, no changes
requested. Codex independently checked the exact upstream v0.8.23 helper source
and all reported gates. The reviewer confirmed equivalence after the existing
five-element pvec guard, evaluation/cycle-guard ordering, generic-read
non-vacuity and sliced-vector coverage. It did not run tests or establish a
performance result. The cold schema-learning scan remains unchanged.

Follow-up selection uses published component15b15f31d1f7261e84c10ef69868726f1226cd76
in deps.edn, the coordinate-bound resource oracle and consumer documentation.
No SDK/driver/data.json pins or options/defaults change. Actual resolver gates
against the new Git component passed 71 tests / 553 assertions and four shape
tests / 15 assertions, including their exact resource-path assertions. Local
incubator sources were not selected in those gates. An older/wrong coordinate
still fails the exact resource oracle. Hosted checks remain separate evidence.
