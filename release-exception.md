# MSR-09 Dresser release exception

This record prepares Dresser's next marker, `v4`. It is not a tag or a
publication instruction.

- Previous marker: annotated `v3` at `d296a9f`.
- Proposed marker: annotated `v4` after the candidate is landed.
- Affected root: the card target `codethread/dresser.spool` and its generated
  `spool-repo` targets. This checkout's current `origin` is
  `codethread/dresser.loom`; the distinction is recorded in
  `release/msr09-release.json` and published verification requires the origin
  URL explicitly.
- Domain identity retained: `ct.spools.dresser`, `dresser/*`, and the Dresser
  workflow names remain Dresser-owned. The product-owned core identity moves
  to `millstrand.*`, `io.millstrand/millstrand`, and `.millstrand`/`.ms`.
- Core input: `release/msr04-release.json`, an immutable SHA record for
  `codethread/millstrand` at
  `5790c459e9bb692b5e975f9715df7d5b403feff2`. No core tag, `:git/tag`, or
  `v1` core marker is used.
- Generated repositories consume that SHA in `deps.edn` and in the CI
  workflow's checkout `ref`; `:local/root` is rejected from generated release
  metadata. Local sibling checkouts are development-only test paths.
- `.millstrand` and `.ms` are equivalent workspace spellings. The pre-tag
  verifier proves that both select the same Millstrand database after a real
  stop and reopen.
- `bin/verify-generated-repo` drives `strand dresser plan/start`, answers every
  fresh-target checkpoint deterministically, stamps every shipped aspect, reruns
  `plan` and `verify`, runs the generated quality commands, then regenerates
  against a committed baseline and requires zero diff.
- The Dresser release record keeps the card target and current origin separate;
  no repository rename is inferred by the verifier.
- `bin/identity-check` is a fail-closed audit over active source, tests,
  templates, workspace config, build files, and release docs. Its empty
  allowlist records that no active legacy identity exception remains.
- Release proof: run `make all`, then
  `MILL_BIN=/path/to/mill bin/verify-generated-repo --mode pre-tag
  --source-root "$PWD" --core-release release/msr04-release.json`.
  Post-tag proof uses `--mode published --repository
  https://github.com/codethread/dresser.loom.git --tag v4 --sha <sha>`.

Rollback is a consumer action: retain or restore the previous Dresser SHA pin.
No tag or publication is performed by this branch.
