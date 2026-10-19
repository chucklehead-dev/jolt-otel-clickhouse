# Policy-anchor storage decoding experiment

Based on selected SDK7878a2eb4340519d6843d13490a5a7c99ebade8a. Decode the
immutable SDK-owned binding pairs once into private arrays, while retaining
all live policy Var dereferences before and after every admission. Conversion
output is still fresh; no values, policy decisions or conversion results cached.
The constructors are internal and receive valid immutable defining-SDK bindings;
malformed external binding structures are not a supported compatibility surface.

Hosted runtime7098, pinned Chez10.4.1:12tests/166assertions pass, including all
six individual policy slots, preinstalled policy replacements, restored roots,
fresh result identity, unsupported values/maps and native geometry controls.
Old source fails exactly four new storage-decode mechanism assertions while
18other assertions pass, with no errors. Claude review26768 completed; no
semantic defect found by inspection. Its single-anchor test-coverage note was
addressed by the six distinct anchors and independently rerun gates above.

Same-process rich5000-row full encoding screen with original layout fences and
defining-SDK loaders,64samples: lazy baseline/candidate p50 469.217/458.589ms,
p90 522.519/562.319ms,p95/p99 585.268/569.713ms; prepared p50 503.008/479.242ms,
p90 532.887/559.545ms,p95/p99 545.143/665.641ms. Estimated allocation unchanged
around16.4KB/row. All8,648,398-byte payload hashes match. Wrapper/arm selection
overhead is shared; this is a component source overlay, not a product binary,
SDK/Durable execution, sustained tail or S3 qualification.

Small and mixed timing differences do not establish a repeatable pipeline win.
Keep local/unselected; no PR, consumer pin or throughput claim. Prefer larger
measured work such as bounded independent SDK lanes and nested JSON materialization.
