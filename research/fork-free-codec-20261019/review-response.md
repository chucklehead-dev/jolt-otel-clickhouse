# Independent review and disposition

Claude read-only review completed terminal0 on 2026-10-19. Source-only review,
no tests run by the reviewer. Raw output is preserved as `claude-review.txt`.
Codex independently checked the following findings rather than accepting the
review's speculative runtime representation claim.

1. **Suspected bare collision root: not a valid stock representation.** Stock
   v0.8.23 `host/chez/collections.ss` documents array-vector or hnode roots;
   `empty-pmap-hash`, `pmap-assoc` and `pmap-put-hash` preserve that invariant.
   Stock `pmap-assoc` itself uses the same hnode/array root distinction. Added
   `(hash-map "Aa" 1 "BB" 2)` coverage: it has a single hcoll child under an
   hnode root, and the library fold's callback sequence equals ordinary seq.
   No unneeded branch for a corrupted/forged runtime record was added.
2. **Surrogate branch coverage: accepted clarification and boundary controls.**
   Chez's character constructor rejects both ends of the surrogate range, so
   ordinary Chez strings cannot exercise the encoder's defensive branch.
   Tests now explicitly check those constructor rejections. This is a tested
   input-representation boundary, NOT a claim of testing an unreachable encoder
   branch. The independent parser corpus tests BMP boundaries, non-BMP text and
   control characters. Malformed-byte decoding is outside this encoder spike.
3. **Sparse percentile precision: already explicitly qualified.** The README
   now states p95/p99 are the observed maximum at 12 samples. Keep the requested
   labels with that limitation; no wider/tail guarantee is inferred. The table
   and sample-size wording were added while review was running.
4. **Collision-order explanation: accepted.** Added a comment explaining why
   physical collision alist order is reversed to match stock seq effects.
5. **Signed/unsigned scratch range: intentional.** Both endpoint and fallback
   sign checks pass; no numeric range was narrowed to make a test pass.
6. **ASCII formatting dependency: trusted stock boundary, not user callback.**
   Hardcoded JSON tokens, Scheme integer rendering and stock
   `jolt-flonum->string` supply this helper. Like the collection primitives,
   stock numeric rendering is trusted and the binary is pinned. Added finite
   exponent/subnormal/extreme-double values to the separate BB/Cheshire oracle;
   no per-character defensive scan added to the hot numeric path. Arbitrary
   raw Scheme mutation of runtime primitives is not supported by this explicit
   codec contract.

No product adoption, public compatibility promise or production-performance
qualification follows from this source review. The rich selected-encoder and
actual exporter/Durable gates in the README are still required.
