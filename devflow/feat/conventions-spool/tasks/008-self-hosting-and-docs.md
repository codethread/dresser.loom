# Task 8: Self-hosting quality pass and shipping docs

**Document ID:** `TASK-Dresser-008`

## TASK-Dresser-008.P1 Scope

Type: AFK

## TASK-Dresser-008.P2 Must implement exactly

- **TASK-Dresser-008.MI1:** Make this repo pass its own `spool-repo` conventions: add `.cljfmt.edn`, `.splint.edn`, the quality aliases (`:format`, `:format/fix`, `:lint/clj-kondo`, `:lint/splint`) to `deps.edn`, and a root `Makefile` with `fmt`, `fmt-check`, `lint`, `test` targets — all byte-consistent with the task-001 templates. Run `make fmt` and fix any lint findings so `make fmt-check lint test` all exit 0.
- **TASK-Dresser-008.MI2:** Self-hosting verify (PLAN V4): a deftest driving `dresser verify` in a weaver world against **this repo's own root** for the aspects whose gates do not recurse into the suite — `spool-repo/skein-workspace` and `spool-repo/agent-docs` — asserting the verify run completes with gates green. The `repo-skeleton` (`clojure -M:test` gate would recurse the running suite) and `quality` aspects are covered by DW1's direct `make fmt-check lint test` instead; say so in a comment.
- **TASK-Dresser-008.MI3:** Write `dresser.md` (repo root): the contract doc distilled from SPEC-Dresser-001 — overview, flavours/aspects table with versions and gate ids, run lifecycle (start → drive → gates → stamp), receipt/plan/verify semantics incl. all six classification states, the `dresser/*` attribute table, and failure/recovery (red gate → fix → clear `shell/error` → rerun → stamp). Follow the contract voice of skein-src `spools/workflow.md`.
- **TASK-Dresser-008.MI4:** Rewrite `README.md` into the full consumer recipe mirroring `~/dev/projects/devflow.spool/README.md`: intro, `## Prerequisites` (skein checkout, workflow spool root approval, shell executor note: classpath, activation-only), `## Dependency information` (spools.edn snippet: `skein.spools/workflow` local-root or `:deps/root` git coordinate, `codethread/dresser` `:git/url`+`:git/sha` placeholder; spools.local.edn override), `## Activation` (init.clj snippet: sync!, workflow `use!` `:required? true`, shell executor `use!` `:after` workflow, dresser `use!` `:after` both, `:call 'skein.spools.dresser/install!`), `## Quickstart` (git init a target, `strand dresser start ...`, drive with `next`/`advance`, `stamp`, `plan`), `## Development` (`clojure -M:test` with sibling checkout). State the NG6 single-operator constraint beside the receipt description. Keep `.skein/init.clj` the minimal batteries file (template source of truth; dresser is not activated in its own workspace).

## TASK-Dresser-008.P3 Done when

- **TASK-Dresser-008.DW1:** `make fmt-check lint test` all exit 0 from the repo root.
- **TASK-Dresser-008.DW2:** The self-hosting verify deftest passes; README contains all five sections with copy-pasteable snippets; `dresser.md` covers every `dresser` subcommand and every classification state.

## TASK-Dresser-008.P4 Out of scope

- **TASK-Dresser-008.OS1:** Publishing to GitHub, sha pinning, live adoption runs (coordinator work).

## TASK-Dresser-008.P5 References

- **TASK-Dresser-008.REF1:** SPEC-Dresser-001 (all sections) and task-001 templates (self-hosting must match them).
- **TASK-Dresser-008.REF2:** `~/dev/projects/devflow.spool/README.md` and skein-src `docs/spools/writing-shared-spools.md`.
