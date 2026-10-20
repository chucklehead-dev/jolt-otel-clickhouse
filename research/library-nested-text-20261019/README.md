# Private nested JSON text experiment

Base: `88df3f5a70f56c97e4af53207091072b6dc6a82d`. Driver candidate:
`abb630a1709bafdb3f7caef51c5ad4a98410e391`. Official stock Jolt 0.8.23,
libchdb 26.9.0; source-mode only. No default switch or protocol change.

The library retains encoded nested JSON as private ASCII bytes. The outer
codec writes these as JSON strings, not raw JSON values. Public `write-nested`
still returns String. This removes a bytes-to-String-to-bytes conversion;
caller buffers and telemetry are not adopted or changed.

## Results and limits

- Full rich row projection/nested/outer component:32 interleaved observations,
  16 per mode,5k rows. All full-output digests match; changed-event control
  rejects. Median241ms/15.3KB per row with Strings versus204ms/13.1KB with
  private text. No native/Durable I/O or production tails in this component.
- Actual SDK/local-Durable four-process String/bytes/bytes/String comparison:
  all700k physical rows independently recovered and validated. Each arm uses
  one exporter worker,20k queue,5k batches,32 measured callbacks plus3 warmups.
- Whole-window estimated allocation30.57KB versus28.40KB/row, about7% lower.
  Pooled9.07k versus9.37k rows/s, apparent3.3% difference; control drift4.7%.
  **No qualified end-to-end throughput improvement. Targets remain unmet.**
- Callback p50/p90/p95/p99 and all coarse counters are in the bounded scalar
  receipts.64 callbacks per arm do not establish production-tail behavior.
- String control substitutes only `prepare-nested-text` with public
  `write-nested` during exporter construction and the run. Byte arms leave
  the candidate production method unchanged. This is an explicit research
  selector, not a newly supported exporter option or an encoder replacement.
- Candidate patch and source checksums capture the measured source. A later
  startup-only nested producer/consumer smoke check was added in response to
  Claude review; it does not run per export. Do not claim final-source hashes
  equal measured-source hashes.
- All synthetic stores were removed only after writer, independent reader and
  validator success and copied/compared receipts. No persisted objects retained.

No AOT, S3, full Oscope startup, stock ecosystem dependency set, fault injection
or model-checking qualification is claimed. No model changed. The next useful
performance action is refreshing coarse projection/native/WAL attribution on
the stock candidate, not further isolated outer-encoder polishing.
