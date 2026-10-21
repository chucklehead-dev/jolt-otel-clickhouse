# Adoption review

Claude read-only exact d885c9b: APPROVE, no blockers. Main independently ran
the actual dependency/Git/native gates and verified SDK PR68's merge tree.
Review did not execute tests or independently establish replay/performance.

Two factual clarifications to the review prose:

- An intermediate SDK DOES exist: previous default14f58e5 supports shape plans
  but not the new encoder handoff. The new merged6cd57c8 includes both through
  ancestry. The current exact-coordinate oracle rejects any other SDK SHA;
  existing old-coordinate controls are not a claim that no intermediate version
  exists. Runtime missing-capability rejection remains separately tested.
- The GLM timeout/no-evidence statement is in the adoption README, not the PR
  body. It is not evidence for any overlap or performance claim.

The local alias intentionally loads checkout codec source. The stock alias
loads published Git code and asserts SDK+codec coordinates. Existing older
nested-scope capability snapshots exercise that older capability, not views.
Historical measurements/receipts and their original dependency identities were
not rewritten when updating the current pins and qualification oracles.
