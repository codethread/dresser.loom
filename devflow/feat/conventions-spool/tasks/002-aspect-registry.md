# Task 2: Aspect registry with lineage fingerprint and drift alarm

**Document ID:** `TASK-Dresser-002@2`

## TASK-Dresser-002.P1 Scope

Type: AFK

## TASK-Dresser-002.P2 Must implement exactly

- **TASK-Dresser-002.MI1:** `src/skein/spools/dresser/aspects.clj` — ns `skein.spools.dresser.aspects`: the seven v1 aspect definitions as data per SPEC-Dresser-001.IC8, keyed `"spool-repo/repo-skeleton"` etc. Each entry: `:version` (int), `:deps` (vector of same-flavour aspect keys), `:owned` (vector of root-relative paths), `:inspect` (instruction string), `:setup` (vector of `{:id :title :instruction :templates}`), `:gates` (vector of `{:id :title :argv :timeout-secs}` with IC8's exact argv). The step/gate ids below are **authoritative** (stamp evidence and the fingerprint depend on them; do not invent others):
  - `spool-repo/repo-skeleton`: setup `[:write-deps :write-src-test :write-readme :write-gitignore]`; gates `[:test-suite :readme-sections]`.
  - `spool-repo/skein-workspace`: setup `[:write-workspace]`; gates `[:workspace-files]`.
  - `spool-repo/agent-docs`: setup `[:write-agents-md]`; gates `[:agents-md]`.
  - `spool-repo/quality`: setup `[:write-quality-config :write-makefile]`; gates `[:fmt-check :lint]`.
  - `skein-dir/workspace`: setup `[:write-workspace]`; gates `[:workspace-files :init-header]`.
  - `skein-dir/quality`: setup `[:write-quality-tooling]`; gates `[:fmt-check :lint]`.
  - `skein-dir/agent-docs`: setup `[:write-agent-docs]`; gates `[:agent-docs-files]`.
  Setup instructions are authored here as the registry's data (cite the relevant template keys and the conflict-decision discipline from SPEC DC3); titles are short imperative phrases.
- **TASK-Dresser-002.MI2:** Public: `registry`, `release-version` (single source of truth here; `skein.spools.dresser/release-version` re-derives or is removed in task 5's edit — leave a note), `(aspect key)` fail-loud lookup, `(flavour-aspects flavour)` dependency-ordered (topological; fail loudly on cycles/unknown deps), `(close-under-deps flavour keys)` returning the dependency-closed, ordered selection (fail loudly on unknown keys), and `(material-data topology)` — a deterministic, sorted structure of DC6's material inputs (aspect versions, owned paths, deps DAG, instructions, template contents via task-001's registry, gate argv/timeouts) **plus** the caller-supplied `topology` value. `(fingerprint topology)` = hex SHA-256 of its canonical `pr-str`. Release **pinning and the drift alarm move to task 3**, where describe-topology first exists; this task ships `releases` as `{}` with a docstring saying task 3 pins it.
- **TASK-Dresser-002.MI3:** Tests: every `:deps` ref exists in the same flavour; every `:templates` ref exists in task-001's registry; every gate argv is a vector of strings; every aspect key parses as `<flavour>/<aspect>` with flavour in `#{"spool-repo" "skein-dir"}`; the id tables above match the registry exactly; `close-under-deps` pulls `repo-skeleton` in when only `quality` is selected and rejects unknown keys; `(fingerprint t)` is stable for equal inputs and differs when one instruction or the topology changes.

## TASK-Dresser-002.P3 Done when

- **TASK-Dresser-002.DW1:** `clojure -M:test` exits 0.
- **TASK-Dresser-002.DW2:** `(flavour-aspects "spool-repo")` orders repo-skeleton before quality; `(flavour-aspects "skein-dir")` orders workspace before quality and agent-docs.

## TASK-Dresser-002.P4 Out of scope

- **TASK-Dresser-002.OS1:** Workflow compilation, release pinning/drift alarm (task 3), receipt/plan logic, ops.

## TASK-Dresser-002.P5 References

- **TASK-Dresser-002.REF1:** SPEC-Dresser-001.DC2/DC6/IC8/IC10 in `specs/dresser.md`.
- **TASK-Dresser-002.REF2:** Task 001's `skein.spools.dresser.templates` registry.
