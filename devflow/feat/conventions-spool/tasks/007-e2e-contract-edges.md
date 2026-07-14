# Task 7: Contract-edge e2e test matrix and the spool-repo e2e

**Document ID:** `TASK-Dresser-007@2`

## TASK-Dresser-007.P1 Scope

Type: AFK

## TASK-Dresser-007.P2 Must implement exactly

- **TASK-Dresser-007.MI1:** New test ns `test/skein/spools/dresser_edges_test.clj` (added to the `-main` runner's ns list) on task-006's fixture helpers, one focused deftest per edge: full `spool-repo` fixture run end to end (fixture target seeded so its `clojure -M:test` gate passes quickly — a scaffold-shaped repo with `:test` roots pointing at the same sibling skein checkout), stamped and `plan`-current across all four aspects; `--aspects` subset (start `quality` alone → dep pulled in, only those two aspects poured); both flavours concurrently on one root (distinct run-ids, both drivable); second `start` on an active (flavour, root, mode) fails loudly; completed run → new `start` → fresh generation, and `stamp` refuses generation-one green gates (stale-green rejection message); conflict `apply-plan` path (customized owned file: `:decisions` input recorded in `workflow/outcome-input`, run continues, keep-that-still-passes converges); conflict `abort` path (routes to abort workflow, run completes, no gates run); op-level `plan` divergence (receipt with bogus fingerprint → `divergent`; receipt release int ahead → `ahead`); `verify` on a converged fixture pours gates only (no inspect/conflict/setup strands) and is drivable via `next`/`advance` `--verify`.
- **TASK-Dresser-007.MI2:** No `Thread/sleep`; every wait through `poll-until-deadline!` with explicit deadlines. Extract shared assertions into the fixtures ns rather than copying.

## TASK-Dresser-007.P3 Done when

- **TASK-Dresser-007.DW1:** `clojure -M:test` exits 0 with every listed edge as a named deftest in `skein.spools.dresser-edges-test`.

## TASK-Dresser-007.P4 Out of scope

- **TASK-Dresser-007.OS1:** Live-repo adoption exercises (coordinator work); docs (task 8).

## TASK-Dresser-007.P5 References

- **TASK-Dresser-007.REF1:** PLAN-Dresser-001.V2/V3 (`conventions-spool.plan.md`) — the authoritative edge list.
- **TASK-Dresser-007.REF2:** SPEC-Dresser-001.IC4/IC5/IC6/DC3/DC7 for the exact semantics under test.
