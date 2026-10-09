# Native typed-span output

The experimental native compact span path fills one exact-sized output buffer
and then publishes an immutable vector. Previously each value/status append
copied the growing persistent vector tail. The public map projector and ordinary
portable vector projector keep their existing implementations.

Key normalization, membership checks and grouped duplicate values are unchanged.
The native builder reads and invokes the current status-projector Var for each
field, in the same order as before. A callback can replace that Var, reenter the
projector or throw; no incomplete output is returned. Pair destructuring still
accepts lists, short pairs and nil with the same missing-value defaults.

Each call owns its construction buffer. No input storage or previous result is
mutated or borrowed. Completed results remain independent, including when output
is large enough to require the runtime's persistent vector trie.

An exact-parent local row-construction screen preserved all10k rows and the full
6,068,398-byte JSON payload. Allocation fell from35.95MB to33.23MB per batch
(~7.6%); elapsed times overlapped. A normal confirmed local Durable export and
fresh stock recovery of230,000 full physical rows also passed. The short export
screen does not establish sustained p99, hosted S3 or Rust parity. Grouped-value
intermediates remain a separate opportunity, not eliminated by this change.

Regression controls cover one final seal, independent prior outputs, output
sizes crossing trie boundaries, live callback replacement, list/nil pairs,
exceptions, reentry, reverse-field rejected controls and existing typed statuses.
