# Private ASCII flush experiment

Based on selected native JSON2172061060a102ccab779f5adc5d77f8b83a81f6. Replace
the non-batch private ASCII scratch->temporary bytevector->UTF8 String path with
direct checked String construction. All publications still occur, custom writer
text never enters that scratch, and default escaping guarantees ASCII bytes.
Public JSON and backend defaults remain unchanged.

Focused tests pass3tests/34assertions: ASCII/Unicode/control escaping, boundaries
through65537characters, custom-writer prefix visibility/rawUnicode/reentry, and
live direct-construction mechanism counting. This is not the full native suite.

Same-process rich5000-row component screen64samples emits identical8,648,398
bytes/hash in every arm. Lazy baseline/candidate p50 433.420/482.496ms, p90
642.574/523.124ms,p95/p99 994.722/559.541ms; prepared p50 513.002/493.148ms,p90
657.345/645.554ms,p95/p99 673.507/713.157ms. Estimated allocation drops by about
480bytes/row, but timing is mixed/noisy and larger nested strings need separate
regression qualification. No SDK/Durable pipeline execution or recovery here.

This candidate is local and unselected, not a merge proposal. No PR or consumer
repin; no independent review/wider qualification is claimed. Preserve source and
small scalar evidence rather than assuming every copy elimination is faster.
