# Task 5: install!, vocabulary, and the read-only op surface

**Document ID:** `TASK-Dresser-005@2`

## TASK-Dresser-005.P1 Scope

Type: AFK

## TASK-Dresser-005.P2 Must implement exactly

- **TASK-Dresser-005.MI1:** Extend `src/ct/spools/dresser.clj` (ns `ct.spools.dresser`): `(install!)` per SPEC-Dresser-001.IC1. The prerequisite check is a seam: a private `(check-prereqs! resolve-fn executors)` taking a resolver fn and the executor map, called by `install!` with `requiring-resolve` and `(workflow/registered-executors)` — fail loudly unless the workflow lifecycle resolves and `:shell` is registered. Then declare the `dresser/*` vocabulary (`dresser/flavour`, `dresser/aspect`, `dresser/version`, `dresser/root` — mirror kanban.spool's `declare!` idiom), call task-003's `(register-workflows!)`, and register the `dresser` op with declared `:subcommands`. Idempotent: re-running `install!` succeeds (inspect kanban.spool / skein-src config for the replace-or-deregister idiom). Replace the standalone `release-version` def here with a re-export or removal in favour of `ct.spools.dresser.aspects/release-version` (one source of truth).
- **TASK-Dresser-005.MI2:** Read-only subcommand handlers, composing tasks 2–4: `about` (structured JSON: purpose, flavours/aspects, receipt/plan/verify/stamp semantics, quickstart pointers; prose via `skein.api.format.alpha`), `aspects` (registry projection incl. versions, deps, gate ids, release + fingerprint), `template <name>` (task-001 lookup; `--param name=value` repeatable flag for parameterized templates), `plan <root>` (resolve root per DC4, read receipt, classify per IC6 against the live registry view incl. `releases` lineage, return the per-aspect states plus provenance verdict).
- **TASK-Dresser-005.MI3:** Tests: `check-prereqs!` both failure branches via the seam (resolver returning nil; executor map without `:shell`) and the success branch; weaver-world (devflow_test's `with-runtime` fixture pattern; require + `install!` the shell executor in the fixture) — `install!` twice succeeds; op registered with all subcommands (`help` tier present); `about`/`aspects`/`template` return the declared shapes; `plan` against a fresh `git init` fixture reports all seven aspects `new`, and against a hand-written receipt reports the IC6 states (reuse task-004's matrix fixtures at the op level).

## TASK-Dresser-005.P3 Done when

- **TASK-Dresser-005.DW1:** `clojure -M:test` exits 0 including the install-idempotence and op-level plan assertions.

## TASK-Dresser-005.P4 Out of scope

- **TASK-Dresser-005.OS1:** start/verify/next/advance/stamp (task 6), contract-edge matrix (task 7), docs (task 8).

## TASK-Dresser-005.P5 References

- **TASK-Dresser-005.REF1:** SPEC-Dresser-001.IC1/IC2/IC3/IC6/IC7 in `specs/dresser.md`.
- **TASK-Dresser-005.REF2:** Op/vocab idiom: `~/dev/projects/kanban.spool/src`, skein-src `docs/spools/writing-shared-spools.md` ("The discovery surface your spool ships").
- **TASK-Dresser-005.REF3:** Fixture pattern: `~/dev/projects/devflow.spool/test/ct/spools/devflow_test.clj`.
