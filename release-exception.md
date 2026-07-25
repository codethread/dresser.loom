# Spool installer retirement release exception

This record prepares `v1`, dresser's first release marker. It is not a tag or
a publication instruction.

- Previous marker: none. The repository was untagged and sha-pinned only, so
  no `v<int>` promise existed and no compatibility alarm baseline applies.
- Proposed marker: annotated `v1`.
- Affected root and names: `codethread/dresser`; the removed names are
  `ct.spools.dresser/install!` (prerequisite check, vocabulary declaration,
  workflow registration, op registration) and
  `ct.spools.dresser.workflows/register-workflows!` (its imperative
  registration loop). Activation is `contribute`/`reconcile` via the module
  lifecycle; the exported `ct.spools.dresser/spool` var supplies the lifecycle
  entry points.
- Authorization: pre-`v1` clean break under TEN-000@1, directed by skein-src
  ADR-003.P5 (epic waq0l, feature kst0n) — retiring `install!` everywhere so
  the module lifecycle is the one activation path.
- Known consumer: the dresser operator weaver world, whose `init.clj`
  activates dresser imperatively; the epic's consumer-cutover feature moves it
  to a guarded module declaration against this marker.
- Also in this marker: registry release 5. The scaffolded workspace templates
  emitted the removed `runtime/sync!`/`runtime/use!` lifecycle; they now emit
  the module bootstrap `mill init` writes, bumping both workspace aspects to
  v3.
- Decision: no compatibility shim. Keeping an installer would preserve the
  retired activation path this release exists to delete.

Rollback is a consumer action: retain or restore the previous sha pin. Tags
are immutable; corrections ship as the next marker.
