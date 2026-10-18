The root application and minimal embedded profile intentionally have separate
dependency authorities, but the embedded profile is now substantially behind
the stack being qualified for current telemetry capture.

Root update branch:
[`deps/sorted-json-sdk-stack-20261018`](https://github.com/chucklehead-dev/oscope/compare/d7558da043ad957515bbfa4b356c06198ade7fc0...a8b12af485c88de2d6fadba6d1c5df30c2104180).
The three-file root update does **not** claim to update or qualify the profile.

`profiles/embedded/deps.edn` currently pins SDK `8110c12f`, driver `3e3141fd`,
exporter `dfbc9ea6`, ordinary JSON `97298fd8`. The root candidate uses SDK
`7878a2eb`, driver `3d766fa`, exporter `4d48871c`, ordinary JSON `0f51b991`, with
native byte JSON `96f3e47d` opt-in. Shared source alone does not propagate these
dependency updates to applications selecting `:deps/root "profiles/embedded"`.

Follow-up acceptance:

- Refresh the profile's declared SDK/exporter/driver/ordinary JSON selections
  together, with explicit opt-in byte-JSON configuration if exposed. Preserve
  its no-server/no-viewer dependency boundary and consumer precedence.
- Choose an actually qualified runtime/floor; do not continue advertising an
  older floor merely because it is written in the previous profile. Preserve
  current runtime-control binaries and benchmark baselines as historical data.
- Update the profile's exact-root and mutation tests plus user documentation.
- Requalify authoritative application state plus embedded telemetry, typed
  capture/query, background-query retirement, bounded remote-destination
  behavior and fresh recovery where applicable. No live Langfuse or Samizdat
  integration claim from a hermetic fixture alone.
- Keep provider ownership, independent queues and shutdown/confirmation
  guarantees intact. Run the real profile fixture from its own working
  directory; root application tests do not qualify the profile.

This is a follow-up to the already completed profile delivery in #77, not a
reason to reopen it or rerun unchanged exhaustive models. Coordinate with the
existing #144 fixture work before superseding or rebasing it.
