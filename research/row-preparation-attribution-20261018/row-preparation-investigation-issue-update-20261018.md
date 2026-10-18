Preparation is now split on actual SDK-produced records (2,000 spans, 16 samples
per phase): attribute shaping ~64 ms, nested event JSON ~71 ms, outer encoding
~45 ms. Component times are not additive or tail qualification.

Four controls did **not** establish a new product speed win:

- Native public reduction saved ~11.5 KB/row, but only 6/16 full-encoding pairs
  were faster; average paired ratio ~1.030. Portable reduction was slower.
- Bypassing TreeMap class predicates (unsafe upper bound) barely changed full
  p50 and saved under 1 KB/row. It does not justify a compiler/domain migration.
- Calling the same native writer hook without the public writer bridge was
  flat/slower. Existing tests passed, but the prototype was reverted and its
  temporary worktree removed; patch/scalars retained as negative evidence.
- Omitting writer synchronization (unsafe, single-thread-only upper bound)
  improved full p50 only ~6%. Locks were not removed from product code.

The available native profiler also failed qualification despite exit 0:
gprofng warned its sampling timer changed to zero, and only 62 ms of CPU samples
were captured in a 3-second active window. Most were unresolved generated code.
Tracked separately in [aspect-packs #181](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/181).

No new performance patch was adopted in this pass. The reviewed JSON `a18ae06`
and exporter `20999f9` candidate remains unchanged with green CI and the earlier
independent recovery proof. Next credible direction is an SDK-owned scalar
normalization capability for sorted inputs, preserving source-owned policy
checks and declining before custom operations—not an unguarded tree walker.
