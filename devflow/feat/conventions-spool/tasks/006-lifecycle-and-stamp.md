# Task 6: Run lifecycle, stamp evidence, and the skein-dir e2e

**Document ID:** `TASK-Dresser-006@2`

## TASK-Dresser-006.P1 Scope

Type: AFK

## TASK-Dresser-006.P2 Must implement exactly

- **TASK-Dresser-006.MI1:** Lifecycle subcommands on the `dresser` op: `start <flavour> <root>` (`--aspects` comma subset → `aspects/close-under-deps`, fail loudly on unknown keys; `workflow/start!` with the registered flavour name, params `{:root <resolved> :verify-only false :aspects <selection>}`, `:family "dresser"`, run-id `target/run-id`; fail loudly if that run-id has an active root); `verify <flavour> <root>` (same with `:verify-only true` and `target/verify-run-id`); `next <flavour> <root>` and `advance <flavour> <root>` (`--verify` flag switches to the verify run-id; `--choice`/`--input`/`--notes`/`--step` pass-through to `workflow/advance!`); `stamp <flavour>/<aspect> <root>` per SPEC IC5 — locate the **setup** run's latest generation (`workflow/run-history` roots; last entry), graph-read its gate strands for the aspect (registry gate ids + `dresser/aspect` attr), enforce the full evidence predicate (complete expected gate set, all closed, `workflow/outcome-by "shell"`, `shell/exit-code` 0, no `shell/error`, current generation only — each violation named in the failure), then `receipt/merge-aspect` (clock acquired here as ISO date) + `write-receipt!`, returning the written entry plus the new `plan` state for that aspect.
- **TASK-Dresser-006.MI2:** Weaver-world e2e (fixture helpers into `test/skein/spools/dresser_fixtures.clj`): full `skein-dir` run against a `git init` temp target — `start`, drive inspect/conflict(`clean`)/setup via `advance` while the test (as driving agent) writes the owned files from templates, real shell executor closes the gates (`poll-until-deadline!`, no `Thread/sleep`), `stamp` all three aspects, assert receipt contents and op-level `plan` all-`current`; assert nothing outside `.skein/` changed in the target (tree snapshot before/after). Red-gate recovery in the same fixture family: break an owned file so a gate exits non-zero, assert `shell/error` stamped and `stamp` refuses naming the gate, fix the file, clear `shell/error`, executor reruns, `stamp` passes.
- **TASK-Dresser-006.MI3:** Stamp-evidence unit-level refusals (small poured fixtures, no full flavour needed): missing expected gate (pour a subset run, stamp full aspect → refusal names the missing gate id); force-closed gate (`workflow/complete!` `:by "human"` → refusal names `outcome-by`).

## TASK-Dresser-006.P3 Done when

- **TASK-Dresser-006.DW1:** `clojure -M:test` exits 0 including the full skein-dir e2e, the recovery case, and both refusal cases.

## TASK-Dresser-006.P4 Out of scope

- **TASK-Dresser-006.OS1:** The remaining contract-edge matrix incl. the spool-repo fixture e2e (task 7); docs (task 8).

## TASK-Dresser-006.P5 References

- **TASK-Dresser-006.REF1:** SPEC-Dresser-001.IC3/IC4/IC5/DC7 in `specs/dresser.md`.
- **TASK-Dresser-006.REF2:** skein-src `spools/executors/shell.md` (error/recovery, `shell/*` attrs) and `spools/workflow.md` §4 (run lifecycle, run-history).
