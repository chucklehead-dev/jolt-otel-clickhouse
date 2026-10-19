The measured production source matches exporter commit
`26067f50914a3fd248da7bc5820bc14153154b19`. At execution time the worktree was
3731f87 plus that candidate source, which was captured here and checksum-checked
after every arm before being committed. These snapshots, not current absolute
paths after later fixes, are authoritative for the historical comparison.

Review corrections in 7cc1a7b changed startup error wording and test classpaths,
not rendering/persistence. A separate final on-arm recovery gate validates that
corrected commit; do not pool it into the four-arm timing comparison.
