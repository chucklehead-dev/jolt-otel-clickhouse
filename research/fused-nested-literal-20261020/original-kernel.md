# Original published byte-kernel regression screen

This follow-up does NOT confirm a fusion win. Keep draft PR155 gated.
Same process / same raw adapters / same current traversal + scalar-plan source,
original published68ee1ca8 byte kernel vs candidate classic/fused byte kernels.
Independent private backend tags: values never cross kernel owners. Actual SDK
policy/cache/shape learning, typed projection, full5k rich rows are included;
public codec startup/loader guards, native I/O and Durable storage are excluded.
Full bytes matched in every sample; changed-event controls differed.

16 samples per arm, interleaved original/classic/fused/fused/classic/original.

| Arm | p50 ms | p90 ms | p95/p99 ms | Estimated B/row |
|---|---:|---:|---:|---:|
| Original published |175.27|252.48|274.90|9768.3|
| Candidate classic |182.06|252.41|298.56|9801.3|
| Candidate fused |194.79|250.87|251.62|9930.4|

CPU closely tracks wall time. Large original/classic samples exist with little
GC; do not explain the variability away as GC/descheduling without evidence.
No precise causal percentage, population-tail, target, S3 or Rust-relative
claim follows. Do not replace this negative result with an unchanged favorable
rerun. Prior native comparison and its control drift remain disclosed separately.

Official stock0.8.23, Chez10.4.1. Original json.ss SHA256:
`5b9cc251976491faa16c51d493e1fd32a90c13108cb1ea1f70e3facdd18e9166`.
Executable/source paths and all raw samples are retained in original-kernel.edn.
Process75541 exited0; no database or bulk store was created by this screen.
The script is original-kernel-screen.clj. From the research app's dependency
context, pass an output EDN path and original published component json.ss path.
