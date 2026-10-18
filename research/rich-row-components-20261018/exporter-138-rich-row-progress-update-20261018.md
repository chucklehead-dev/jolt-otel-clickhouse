Oscope [#168](https://github.com/chucklehead-dev/oscope/pull/168) merged as
`11feb1f5` after all six checks passed. Its merge tree equals the reviewed and
tested head. The separately pinned minimal embedded profile remains #167 work.

Fresh local component measurements on hosted runtime `7098a8d6`, exporter
`4d48871`, SDK `7878a2e`, optional JSON `96f3e47`:

- Preparing 5,000 rich rows takes roughly 260 ms and 20.3 KB estimated
  allocation per row; outer emission adds roughly 90 ms in that screen.
  Preparation includes nested event JSON, which alone measures about
  13.4 KB/row. Overlapping component medians are not additive.
- The ASCII scratch-prefix candidate passed focused and existing contracts,
  but fresh-process ABBA was slower: encoding p50 305 → 334 ms, saving only
  about 513 B/row. Not promoted; earlier cumulative improvements remain.
- Switching nested output to the existing native text writer is also negative:
  p50 80 → 248 ms and estimated allocation 13.1 → 44.1 KB/row, with every
  returned String matching ordinary-writer references. Keep the byte writer.
- Repeated sink construction is not the explanation: 5,000 StringWriters take
  about 0.5 ms and 268 B/row in a separate overlapping component screen.
  No sink pooling or weaker writer-visibility contract is proposed.

Latest-release source v0.8.23 was also exercised on the real Clojure exporter,
not just its raw Scheme SDK helpers. Four fresh configured-JSON processes in
open/direct/direct/open order gave p50 row preparation 573 → 501 ms, with no
allocation reduction and worse observed p90/p95/p99. An untimed mechanism
probe counted 162 direct-linked exporter forms and verified actual splicing;
tested key/value-policy and public JSON-writer replacements remain observable.
This is **not** a native-writer, Durable throughput or tail qualification, nor
a product runtime upgrade. The native writer needs more than the one helper
suggested by the initial source audit; that audit was corrected rather than
used as a port plan.

Next target remains nested encoding and row conversion, with a complete native
capability comparison needed before adopting latest-runtime direct-linking.
No new product optimization selected in this pass; no protocol/model transition
changed and no unchanged exhaustive run was launched. Small source/counter
receipts are committed in the research checkpoint; no store/build bulk or live
local benchmark remains from these screens. The prior ~9,160 rows/s local
Durable result remains the last matched throughput evidence, not a new S3 claim.
