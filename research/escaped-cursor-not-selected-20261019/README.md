# Common escape local-cursor experiment: not selected

Local data.json candidate `4ae33889a4bd2e44a01bdffb3e17ed557e4de904`, parent
selected `2172061060a102ccab779f5adc5d77f8b83a81f6`, branch
`perf/escaped-ascii-cursor-20261019`. Five-file patch retained here; no PR,
consumer pin change or default install change was made for this experiment.

It directly emits quote/backslash/slash escape pairs through the local cursor
only when cursor<=65534. Both write indices are below65536, nextcursor<=65536;
the existing full-buffer loop and one-byte-remaining scalar fallback handle
boundaries. All other escapes, classification/visibility and budgets remain.
Focused tests: 2 /100 assertions. Old-source control: 97 pass /3 mechanism
failures /0 errors. Existing byte/sorted/segmented tests:67 /1106; native hooks
38 /621; omission4 /16, all green. Claude session9772 completed exit0, no defects.
Main checked its two nonblocking test comments: source replacement controls
are self-checking; bare-string n65533/65534/65535 explicitly span two/one/zero
remaining scratch bytes after the opening quote. Initial verbose red output is
compressed; final red logs are scalar. That was log hygiene, not a test weakening.

## Measurements and decision

Four fresh full-encoding processes in ABBA order have byte-identical complete
8,648,398-byte output, ordinary reference and changed-input controls. Pooled
lazy p50 was300.156 ->216.481ms, unchanged ~17.23KB/row allocation. However,
baseline process medians323.267/289.974ms vary and exceed prior nearby windows.
That apparent28% difference is NOT accepted as a causal throughput improvement.

Four real SDK/local-Durable processes, same7098/exporter4d/SDK7878/driver3d,
only JSON217 vs4ae changes, yield:

| Measurement | Baseline217 | Candidate4ae |
| --- | ---: | ---: |
| Confirmed-window rows/s | 10,076.787 | 10,447.436 |
| Estimated allocation B/row | 25,234.458 | 25,233.948 |
| Callback p50/p90 ms | 451.401 /613.371 | 435.766 /589.749 |
| Callback p95/p99 ms | 735.378 /761.903 | 649.484 /786.931 |

Two repetitions per arm, large process variability and worse observed p99 do
not establish a reliable service-level win. All700,000 rows pass independent
older-stack full physical/typed/status/multiplicity recovery. Runner48503 exit0.
All four validated synthetic stores QCUKMkAP/nNZMn1Ej/7ItKUMcZ/u8aFIa9F under
/tmp/escape-cursor-sdk-abba-20261019.* were retired with copied/cmp receipts.

A SAME-PROCESS diagnostic uses one compiled candidate factory body, changing
only a private branch flag between balanced groups. It retains public writer
and normal budget/classification semantics, but is source-overlay research,
NOT product qualification. Entry/branch calibration confirms loader2 calls,
fast-off0 direct escapes, fast-on3 direct escapes for the same quoted/slashed
input, outputs equal. This avoids trusting an unused toggle or ignored redefs.
The64-sample full-output control passes: lazy p50 off216.196/on220.835ms;
prepared p50 off231.948/on230.899ms. No repeatable direct-escape speedup emerges.
The different-code fresh-process and same-body flag experiments answer different
questions; compiler layout effects are not ruled out. Neither justifies adopting
this extra unchecked-store path as a demonstrated performance fix now.

No S3, sustained-tail, Rust-relative or25k/20k objective is achieved here. Keep
all earlier selected cumulative wins. Next work uses actual correlated worker
phases to target larger remaining costs rather than claiming this apparent win.
