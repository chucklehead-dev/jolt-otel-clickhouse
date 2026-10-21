# Source review and process caveat

Claude returned APPROVE with no implementation blockers. It checked logical vs
physical limits, cache-hit budgets/depth, separate raw/escaped table ownership,
recursive literals, output independence, cleanup and hedged measurement claims.
Codex owns the executed source/standalone and published consumer gates.

The review was requested atc02c6aa, but test namespace isolation changed while
it ran. Claude explicitly noticed this and reread the current layout, including
the new fused test namespace. This is NOT a clean static exact-commit review:
do not claim HEAD remainedc02c6aa or ignore the process note. Actual subsequent
checkpoint9452d99 has identical production source to c02c6aa (verified with
git diff). Its test/CI layout is the corrected42c548f; extra9452d99 changes
are receipts/docs. Claude found the final namespace membership/counts consistent.
Before a final PR/adoption, freeze the actual completed packet for the final
review rather than modifying its tree during review. This branch remains an
unadopted experiment with no PR, not a final publication gate bypass.

Two advisory notes are retained, not falsely claimed resolved: new fused entry
points lack a dedicated construction-time runtime smoke and are currently
retrieved from the memoized backend vector rather than Codec fields. If product
adoption is worthwhile, integrate these capabilities/preflight deliberately
instead of adding another implicit late fallback or an unqualified default.

"Default paths unaffected" in the source review is semantic, not proof of no
shared-path performance/allocation overhead. All benchmark arms share the
candidate encoder. No causality/native-throughput/tail qualification follows.

## Frozen follow-up completed

After the actual native comparison, source checkpoint
ddb1e1ab7b6b9dc1e884c988ab824e0fd822ea29 closed both advisory notes: explicit
Codec fields and startup smoke/cleanup for both fused capabilities. Source and
neutral-directory standalone46/769 pass; byte kernel remains c02c6aa.

Final follow-up reviewed only the immutable source/test/scalar-evidence packet
`evidence/fused-native-final-review-20261020/packet.md`, SHA256
154f2060ab36c273ecd940eb379644f3ab6b6b530966c549960e2d985334dbd6.
The packet was not edited while review ran. Claude returned APPROVE with no
blockers for wiring, arithmetic, scope-selection/validation and bounded claims.
It reiterated that original-encoder regression isolation and a precise causal/
tail claim remain unfinished. See final-review.txt. This supersedes the
pending-final-packet gate, not those performance/adoption gates or the earlier
moving-tree caveat. No review is claimed to have personally executed the runs.
