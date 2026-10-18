# Rich-row component measurements

Qualified hosted runtime7098a8d6, exporter4d48871, SDK7878a2e,
optional JSON96f3e47. All local invocations use the mandatory Chez10.4.1
wrapper. These are preparation/encoding screens, not Durable execution,
service-tail qualification, queue tests or hosted S3 results.

The complete 5000-row/28-column lazy/prepared comparison emits8648398bytes,
SHA256862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39.
It checks a whole rich row against the ordinary writer, changed-input hash
sensitivity, and every measured output's size/hash. Preparation occurs inside
the real native batch binding. Whole-batch mapv is diagnostic only: it changes
realization/budget/error ordering and is not selected as a product optimization.

Initial preparation p50~260ms/~20269estimatedB/row; total~364ms, with a
~90ms remainder. Component measurements overlap and must not be summed:
resource attrs21.8ms/2124B, span attrs7.7ms/827B,
event JSON84.1ms/13357B, event columns26.1ms/3308B,
projector10.4ms/540B, base row194.4ms/19468B,
full row238.3ms/20268B. All are per5000rows, with allocation per row.
Counter-derived allocation is an estimate using live heap plus collected
bytes, not a claim that live heap alone is a cumulative allocation counter.

## Fresh text-versus-byte nested JSON screen

Terminal0, session27247. Existing public hooks only; no production source edit.
Same5000event arrays, real byte-batch context,32rotatingABBA observations.
Every returned String equals its ordinary-writer reference; concatenated
reference SHA2566298eaf8c7060eff9a3e92c7c5d25bd069a69d617c7005ba8356331af2b16f03.

| Nested event JSON | p50ms | p90ms | p95/p99ms | estimatedB/row |
| --- | ---: | ---: | ---: | ---: |
| Existing byte hook |79.914|96.351|96.768|13149.9|
| Existing key-caching text hook |248.192|293.292|327.351|44142.8|

The text hook is~3.1xslower and allocates~3.4xmore here. Keep the byte hook.
This does not prove every possible text implementation is slower; it rules
out switching to the existing implementation as an immediate solution.
Sixteen observations per arm do not qualify production p99.

No database/store/native result bulk was generated or retained.

## Constructor hypothesis ruled out

Fresh16rounds/4components in forward/reverse order, same real batch binding,
terminal0(session27769). Constructor~0.524ms/5000writers,~268estimatedB/row;
empty companion~1.049ms/~268B; single-scalar JSON~6.805ms/~892B;
rich event JSON~106.458ms/~13085B. These overlapping components are not
additive, and absolute times are not paired with the prior screen's schedule.
All returned event Strings equal ordinary references. Sink creation is not
the allocation/time explanation; do not introduce a sink-reuse contract merely
to address this workload. Initial summary script had a pair-shape typo and
failed; the corrected summarizer completed successfully from unchanged raw
observations. No measured driver/source was changed during this run.
