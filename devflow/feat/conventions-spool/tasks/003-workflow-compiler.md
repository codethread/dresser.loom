# Task 3: Workflow compiler for aspect and flavour definitions

**Document ID:** `TASK-Dresser-003@2`

## TASK-Dresser-003.P1 Scope

Type: AFK

## TASK-Dresser-003.P2 Must implement exactly

- **TASK-Dresser-003.MI1:** `src/skein/spools/dresser/workflows.clj` — ns `skein.spools.dresser.workflows`: `(aspect-workflow aspect-key)` returning a workflow constructor (1-arg fn of params) that builds, per SPEC-Dresser-001.DC3, from the task-002 registry entry: step `:inspect` (`:self`, instruction = the entry's `:inspect` text plus the owned-file list and the target root from params, `:condition [:!= :verify-only true]`); checkpoint `:conflict` (`:kind :human`, `:condition [:!= :verify-only true]`, choices `clean` (no input), `apply-plan` (declared required input `:decisions`), `abort` (required `:reason`, `:next :dresser/abort`), `workflow/decision-point "conflict-policy"`); the entry's setup steps (`:self`, `:condition [:!= :verify-only true]`, `:depends-on` chained inspect → conflict → setup in declared order, each instruction citing its template keys and the resolved root); the entry's gates (`workflow/gate :shell`, `shell/argv`/`shell/timeout-secs` from the entry, `shell/cwd` = the `:root` param, `:depends-on` the last setup step — or, in verify-only compilation, spliced to entry via the engine's condition splicing). Steps and gates carry attributes `dresser/flavour`, `dresser/aspect`, `dresser/version`, `dresser/root`.
- **TASK-Dresser-003.MI2:** `(flavour-workflow flavour)` returning a constructor whose params carry the selection: it reads `:aspects` from the resolved params (a vector of dependency-closed aspect keys; default = all of `flavour-aspects`) and `workflow/call`s each selected aspect's workflow in that order, threading `{:root ... :verify-only ...}`; the flavour root strand carries the IC7 attributes (`dresser/flavour`, `dresser/root`). Plus `abort-workflow` (one `:self` step recording the reason). `(register-workflows!)` registers `:dresser/spool-repo`, `:dresser/skein-dir`, `:dresser/abort`, and one `:dresser/<flavour>.<aspect>` name per aspect via `workflow/register-workflow!`.
- **TASK-Dresser-003.MI3:** Describe-level tests (no weaver): for each aspect, `workflow/describe` in setup mode contains inspect + conflict + setup + gates with correct dependency topology and gate attributes; in verify-only mode contains **only** the gates, as entry steps; the conflict checkpoint declares the three choices with `apply-plan`'s required `:decisions` input; `spool-repo` full-flavour describe contains all four aspects' gates while `{:aspects ["spool-repo/skein-workspace"]}` contains only that aspect's strands; params `{:root "/tmp/x"}` reach `shell/cwd`.
- **TASK-Dresser-003.MI4:** Topology + release pinning: `(describe-topology)` — a deterministic projection (sorted by aspect key) of every aspect workflow's `describe` in both modes (step ids, kinds, depends-on, gate ids) — feeds task-002's `(fingerprint topology)`. Pin `releases` in `skein.spools.dresser.aspects` to `{1 <computed>}` and add the drift-alarm test: `(fingerprint (describe-topology))` must equal `(releases release-version)`, with a failure message saying "bump the aspect version AND release-version, then re-pin releases".

## TASK-Dresser-003.P3 Done when

- **TASK-Dresser-003.DW1:** `clojure -M:test` exits 0 including the describe-level assertions.

## TASK-Dresser-003.P4 Out of scope

- **TASK-Dresser-003.OS1:** Starting runs, ops, receipt/stamp logic (tasks 4–5).

## TASK-Dresser-003.P5 References

- **TASK-Dresser-003.REF1:** SPEC-Dresser-001.DC3/DC4/D3 in `specs/dresser.md`.
- **TASK-Dresser-003.REF2:** Engine contracts: skein-src `spools/workflow.md` §3 (builders, conditions, splicing, gates, `call`), §5 (checkpoint choices/`:next`), and `spools/executors/shell.md` (gate attribute vocabulary).
- **TASK-Dresser-003.REF3:** Worked precedent for registered stage names and choice shapes: `~/dev/projects/devflow.spool/src/skein/spools/devflow.clj`.
