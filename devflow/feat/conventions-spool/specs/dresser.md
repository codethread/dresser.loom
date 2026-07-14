# Dresser — convention convergence spool

**Document ID:** `SPEC-Dresser-001@2`
**Status:** Implemented
**Last Updated:** 2026-07-14
**Related RFCs:** None (see PROP-Dresser-001@2; distribution per skein-src RFC-017; @2 folds review notes `zr92l`/`jjcew`)
**Code:** `src/ct/spools/dresser.clj` and `src/ct/spools/dresser/`

## SPEC-Dresser-001.P1 Purpose

Dresser bootstraps and maintains opinionated repo conventions — formatting, linting, test harness shape, agent guidance, and `.skein` workspace layout — as versioned, verifiable convergence workflows. It runs in an operator's weaver world and acts on a target repo that needs no skein knowledge of its own.

## SPEC-Dresser-001.P2 Goals

- **SPEC-Dresser-001.G1:** A target repo can be brought onto the conventions (fresh or partially/customly adopted) by driving an agent-interpreted workflow whose completion is gated on mechanical verification in that repo.
- **SPEC-Dresser-001.G2:** A target repo carries a durable, checked-in receipt of what it adopted; consumers discover pending convention upgrades by bumping their sha pin and running `plan`.
- **SPEC-Dresser-001.G3:** Convergence is re-checkable at any time without re-running setup (`verify`).

## SPEC-Dresser-001.P3 Non-goals

- **SPEC-Dresser-001.NG1:** No mechanical file transformation of targets; file edits are driving-agent work. Gates only run non-destructive checks.
- **SPEC-Dresser-001.NG2:** No engine or executor changes; pure userland composition on `skein.spools.workflow` + `skein.spools.executors.shell`.
- **SPEC-Dresser-001.NG3:** No skein-src-specific gates (spool-suite-gate, smoke suites) in any aspect.
- **SPEC-Dresser-001.NG4:** No host-root footprint from the `skein-dir` flavour (everything under `.skein/`).
- **SPEC-Dresser-001.NG5:** No transitive prerequisite installation (RFC-017): the README documents approvals/activation; dresser installs nothing.
- **SPEC-Dresser-001.NG6:** No multi-operator coordination on one target: concurrent dresser runs against the same target root from different operator worlds are out of contract (the receipt write is atomic per file, but last-writer-wins across worlds; the README says so).

## SPEC-Dresser-001.P4 Domain concepts

- **SPEC-Dresser-001.DC1 Flavour:** a target shape. v1 ships `spool-repo` (a shared-spool repo shaped like devflow.spool/kanban.spool) and `skein-dir` (a self-contained `.skein/` workspace in any host repo).
- **SPEC-Dresser-001.DC2 Aspect:** the unit of convention, versioned independently, keyed `<flavour>/<aspect>`. The normative v1 inventory is P5.IC8.
- **SPEC-Dresser-001.DC3 Aspect workflow:** one workflow definition per aspect with three fixed regions: an **inspect** step (agent compares target against the aspect's owned files and canonical templates, and records findings plus a keep/merge/replace plan per conflicting file in the step's completion notes); a **conflict checkpoint** (always compiled and always answered — `:kind :human`, choices `clean` [inspect found nothing to decide, no input], `apply-plan` [declared required input `:decisions`, a summary of per-file keep/merge/replace choices; recorded as `workflow/outcome-input` so downstream steps and auditors can read it], `abort` [required `:reason`, routes to the abort stage]); **setup** steps (agent-driven, instructions as step data, honoring the recorded decisions); then **verification gates**. "Keep" means the customization stays; the aspect still converges only if the gates pass over the kept files. In verify-only mode a param condition (`[:= :verify-only true]` on inspect/checkpoint/setup) drops everything but the gates; condition splicing reattaches the gates as entry steps. Setup and verify therefore share one compiled contract.
- **SPEC-Dresser-001.DC4 Target root:** a canonicalized absolute path resolved once at op entry (symlinks resolved); it must exist and be a git worktree root (`.git` present as dir or file), else fail loudly. All step instructions and every gate `shell/cwd` derive from this single resolved root. Scaffolding a brand-new repo starts with `git init` (README quickstart), keeping the identity contract uniform.
- **SPEC-Dresser-001.DC5 Receipt (stamp):** EDN at `<root>/.skein/conventions.edn`: `{:dresser/release <int> :dresser/fingerprint <hex-string> :aspects {<flavour>/<aspect> {:version <int> :release <int> :applied-at <iso-date>}}}`. A receipt of green verification, never proof of current convergence. Written atomically (temp file + rename) only by the `stamp` subcommand.
- **SPEC-Dresser-001.DC6 Release lineage:** the registry declares a monotonic release integer plus a **fingerprint per release**: `releases` maps every published release int to the hex SHA-256 of a canonical EDN rendering of that release's material data (aspect versions, owned file paths, the aspect dependency DAG, step instructions, template contents, gate argv/timeouts, and step topology from `describe`). The current release's fingerprint is recomputed by the drift-alarm test; historical entries are frozen. Receipts record both the release int and its fingerprint, so `plan` can detect a same-integer fork.
- **SPEC-Dresser-001.DC7 Run generation:** the engine may pour a new root for a run-id once the prior root closed, and retains history. Dresser always addresses the **latest** generation: `next`/`advance` operate on the active root; `stamp` audits gates belonging to the latest generation only, never historical green gates.

## SPEC-Dresser-001.P5 Interfaces and contracts

- **SPEC-Dresser-001.IC1 Activation and prerequisite checks:** operator workspace approves+activates the workflow spool, activates the shell executor (classpath, activation-only), then activates dresser. `install!` fails loudly unless (a) `skein.spools.workflow` resolves on the classpath (`requiring-resolve` of its lifecycle fns succeeds) and (b) `(workflow/registered-executors)` contains `:shell`. `install!` is idempotent: re-registration replaces dresser's own workflow names and op (the engine's documented replace semantics) and re-declares the vocabulary. It registers the workflows, the `dresser/*` attribute vocabulary, and the `dresser` op.
- **SPEC-Dresser-001.IC2 Runtime posture:** dresser's data layers (registry, templates, receipt codec, plan classification, root resolution) are pure or filesystem-only and take no runtime. Weaver-touching functions follow the workflow engine's own surface, which resolves the ambient runtime internally; dresser threads no runtime through it, exactly as `ct.spools.devflow` (the worked distributed precedent) does. When the engine grows explicit-runtime arities, dresser adopts them; until then dresser documents that its runs live in the operator world's published runtime, and its tests exercise it the same way devflow.spool's do.
- **SPEC-Dresser-001.IC3 Op surface:** one declared-subcommand op `dresser`: `about`; `aspects`; `template <name>`; `plan <root>`; `start <flavour> <root>` (optional `--aspects` comma-list subset, validated against the registry and closed under dependencies); `verify <flavour> <root>` (same selection, verify-only mode); `next <flavour> <root>` and `advance <flavour> <root>` (`--choice`/`--input`/`--notes`/`--step` pass-through; both take `--verify` to address the verify run per IC4); `stamp <flavour>/<aspect> <root>`. Every subcommand that takes `<root>` resolves it per DC4 before anything else. `start` fails loudly if the run-id already has an active root.
- **SPEC-Dresser-001.IC4 Run identity:** setup run-id is `dresser-<flavour>-<basename>-<8-hex-of-sha256(canonical-root)>`; verify run-id is the same with a `dresser-verify-` prefix instead of `dresser-`. Both flavours may run concurrently against one root (distinct run-ids); one active run per (flavour, root, mode). `next`/`advance` address the setup run by default and the verify run with a `--verify` flag; `stamp` audits the setup run only.
- **SPEC-Dresser-001.IC5 Stamp evidence:** `stamp <flavour>/<aspect> <root>` accepts exactly this, else fails loudly naming what was violated: the run's latest generation contains the aspect's full expected gate set (by gate id, from the registry); every one is `closed` with `workflow/outcome-by "shell"`, `shell/exit-code` `0`, and no `shell/error`; none is missing, extra-force-closed (`outcome-by` ≠ `"shell"`), or from an older generation. Evidence is read from the gate **strands** themselves — a graph query over the run's latest-generation subgraph by `workflow/run-id` + `dresser/aspect`, whose attribute maps (including `shell/*`) are durable and readable after the run root closes; the workflow step-view projection is not the read path. On acceptance it merges the receipt entry and provenance atomically per DC5.
- **SPEC-Dresser-001.IC6 Plan classification:** for each registry aspect against the receipt — absent → `new`; version < registry → `pending`; version == registry and receipt fingerprint == the lineage fingerprint for the receipt's release → `current`; version == registry but fingerprint/release unknown to or mismatching the lineage → `divergent`; version or release ahead of the registry → `ahead` (operator's pin is older than the target's receipt). Receipt aspects unknown to the registry → `removed`. Every non-`current` state is reported explicitly; nothing is silently dropped.
- **SPEC-Dresser-001.IC7 Attribute vocabulary:** `dresser/*`, declared at install: `dresser/flavour`, `dresser/aspect`, `dresser/version`, `dresser/root` on run roots and steps; gates additionally carry the standard `shell/*` request attributes.
- **SPEC-Dresser-001.IC8 v1 aspect inventory (normative).** Owned files are relative to the resolved root. Gates are argv vectors (with `shell/timeout-secs`); `sh -c` is used only where a compound check needs it.
  - **`spool-repo/repo-skeleton` v1** (deps: none). Owned: `deps.edn` (`:paths ["src"]`, `:test` alias with sibling `io.skein/skein` + `io.skein/workflow-spool` local roots, `--enable-native-access=ALL-UNNAMED`, `-m ct.spools.<name>-test`), `src/ct/spools/<name>.clj`, `test/ct/spools/<name>_test.clj` (with fail-loud `-main`), `.gitignore`, `README.md` with the three recipe sections. Gates: `["clojure" "-M:test"]` (timeout 600); `["sh" "-c" "grep -q '## Prerequisites' README.md && grep -q '## Dependency information' README.md && grep -q '## Activation' README.md"]` (timeout 30).
  - **`spool-repo/skein-workspace` v1** (deps: none). Owned: `.skein/config.json` (`{"configFormat":"alpha"}`), `.skein/spools.edn` (`{:spools {}}`), `.skein/init.clj` (minimal batteries bootstrap), `.skein/.gitignore` (reference list). Gates: `["sh" "-c" "test -f .skein/init.clj && test -f .skein/spools.edn && test -f .skein/.gitignore && grep -q configFormat .skein/config.json"]` (timeout 30).
  - **`spool-repo/agent-docs` v1** (deps: none). Owned: `AGENTS.md` carrying the `mill:skein-prime` block. Gates: `["sh" "-c" "grep -q 'mill:skein-prime' AGENTS.md && test $(wc -l < AGENTS.md) -le 70"]` (timeout 30).
  - **`spool-repo/quality` v1** (deps: repo-skeleton). Owned: `.cljfmt.edn`, `.splint.edn`, deps.edn aliases `:format`, `:format/fix`, `:lint/clj-kondo`, `:lint/splint` (cljfmt 0.13.1, clj-kondo 2025.06.05, splint 1.21.0 — skein-src's pins), `Makefile` targets `fmt`, `fmt-check`, `lint`, `test`. Gates: `["make" "fmt-check"]` (timeout 300); `["make" "lint"]` (timeout 600).
  - **`skein-dir/workspace` v1** (deps: none). Owned: `.skein/config.json`, `.skein/spools.edn`, `.skein/init.clj` (one-file-per-concern header discipline: the header comment names each concern file and its activation, matching the layered reference), `.skein/.gitignore`. Existing richer files are conflict-checkpoint material, not overwrite targets. Gates: as `spool-repo/skein-workspace` plus `["sh" "-c" "head -20 .skein/init.clj | grep -qi 'startup entrypoint'"]` (timeout 30).
  - **`skein-dir/quality` v1** (deps: workspace). Owned: `.skein/deps.edn` (`:format`/`:format/fix`/`:lint` aliases over the workspace's `.clj` files), `.skein/Makefile` (`fmt`, `fmt-check`, `lint` running those aliases from `.skein/`). Gates: `["make" "-C" ".skein" "fmt-check"]` (timeout 300); `["make" "-C" ".skein" "lint"]` (timeout 600).
  - **`skein-dir/agent-docs` v1** (deps: workspace). Owned: `.skein/AGENTS.md` (working discipline for the workspace config code: change/reload ladder pointer, disposable-world smoke rule, file-per-concern map), `.skein/CLAUDE.md` (one-line pointer to `.skein/AGENTS.md`). Never touches host-root files (NG4). Gates: `["sh" "-c" "test -f .skein/AGENTS.md && test -f .skein/CLAUDE.md && grep -q 'AGENTS.md' .skein/CLAUDE.md"]` (timeout 30).
- **SPEC-Dresser-001.IC9 Template delivery:** templates are data in the spool source (string defs / pure fns of params), keyed `<flavour>/<slug>` (shared quartet templates under `skein/<slug>`), surfaced via `template <name>` and referenced by name from step instructions; no classpath-resource loading.
- **SPEC-Dresser-001.IC10 Registry invariant:** any material change to an aspect bumps its version and the release; the release fingerprint (DC6) is pinned in the drift-alarm test, which recomputes it from live data and fails on any unbumped material change. Historical lineage entries are append-only.

## SPEC-Dresser-001.P6 Design decisions

### SPEC-Dresser-001.D1 Operator-world execution

- **Decision:** Dresser runs where it is activated; the target is only a resolved path (DC4); gates execute in the target via `shell/cwd`.
- **Rationale:** Kills the bootstrap circularity (a fresh target has no weaver) and keeps targets skein-free; disposable operator worlds make runs cheap.
- **Rejected:** Running in the target's own workspace (chicken-and-egg for flavour `skein-dir`; forces every target to approve spools).

### SPEC-Dresser-001.D2 Receipt/verification split

- **Decision:** `plan` diffs the receipt with lineage-fingerprint provenance (IC6); `verify` re-runs gates (actual status); `stamp` advances the receipt only on the evidence predicate (IC5).
- **Rationale:** The repo is the durable record but receipt equality can't prove convergence after local drift, and integers alone can't detect a same-number fork; code wins, so status is re-derived from the tree and provenance from content lineage.
- **Rejected:** A combined "status" op trusting the stamp; graph-recorded state (wrong world, not durable with the repo); integer-only provenance (fork-blind, review `jjcew` finding 2).

### SPEC-Dresser-001.D3 One definition, two modes

- **Decision:** Each aspect workflow compiles in setup mode or verify-only mode via a param condition; aspect subsets are selected at umbrella construction (constructor fns receive the selection — `call` carries no conditions).
- **Rationale:** Setup's completion gates and `verify`'s checks are the same compiled data, so they cannot drift apart.
- **Rejected:** Separate verify workflows (drift risk); gate-less verify via op-side shelling (duplicates the executor's contract); conditions on `call` (unsupported by the engine).

### SPEC-Dresser-001.D4 Ambient engine surface, pure data core

- **Decision:** Follow devflow.spool's runtime posture (IC2) rather than demanding explicit-runtime lifecycle arities the engine does not expose.
- **Rationale:** The workflow engine's public lifecycle resolves the ambient runtime internally; the distributed precedent (devflow, kanban) builds on it directly; NG2 forbids the engine change.
- **Rejected:** Private runtime-binding shims around engine calls (forbidden by the shared-spool guide for a spool's own operation); blocking on an engine API change (out of scope).

## SPEC-Dresser-001.P7 Open questions

- **SPEC-Dresser-001.Q1:** Final published name (`dresser.spool` working name; rename is pre-publish only).
