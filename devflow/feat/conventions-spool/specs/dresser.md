# Dresser — convention convergence spool

**Document ID:** `SPEC-Dresser-001`
**Status:** Planned
**Last Updated:** 2026-07-14
**Related RFCs:** None (see PROP-Dresser-001; distribution per skein-src RFC-017)
**Code:** Not implemented yet (will be `src/skein/spools/dresser*` in this repo)

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

## SPEC-Dresser-001.P4 Domain concepts

- **SPEC-Dresser-001.DC1 Flavour:** a target shape. v1 ships `spool-repo` (a shared-spool repo shaped like devflow.spool/kanban.spool) and `skein-dir` (a self-contained `.skein/` workspace in any host repo).
- **SPEC-Dresser-001.DC2 Aspect:** the unit of convention, versioned independently, keyed `<flavour>/<aspect>`. v1 registry: `spool-repo/{repo-skeleton, skein-workspace, agent-docs, quality}`, `skein-dir/{workspace, quality, agent-docs}`. Aspects declare flavour-local dependencies (e.g. `quality` after the skeleton/workspace aspect).
- **SPEC-Dresser-001.DC3 Aspect workflow:** one workflow definition per aspect: an inspect step (agent compares target against canonical form and writes a findings/merge plan), a conflict checkpoint (`:kind :human`; explicit keep/merge/replace decisions when existing customized files would change; skippable-by-choice when inspect finds a clean target), setup steps (agent-driven, instructions as step data), and `:shell` verification gates whose pass defines "converged". The same definition compiles in verify-only mode (a param condition drops inspect/conflict/setup, leaving gates), so setup and verify cannot drift.
- **SPEC-Dresser-001.DC4 Target root:** a canonicalized absolute path resolved once at op entry; it must exist and be a git worktree root, else fail loudly. All step instructions and every gate `shell/cwd` derive from this single resolved root. Scaffolding a brand-new repo starts with `git init` (README quickstart), keeping the identity contract uniform.
- **SPEC-Dresser-001.DC5 Receipt (stamp):** EDN at `<root>/.skein/conventions.edn`: `{:dresser/release <int> :aspects {<flavour>/<aspect> {:version <int> :release <int> :applied-at <iso-date>}}}`. A receipt of green verification, never proof of current convergence.
- **SPEC-Dresser-001.DC6 Release version:** a monotonic integer constant in the spool source, bumped whenever any aspect version bumps. Provenance anchor for `plan`'s divergence detection.

## SPEC-Dresser-001.P5 Interfaces and contracts

- **SPEC-Dresser-001.IC1 Activation:** operator workspace approves+activates the workflow spool, activates the shell executor (classpath, activation-only), then activates dresser. `install!` fails loudly when the workflow registry or a registered `:shell` executor is absent. `install!` registers the workflows, the `dresser/*` attribute vocabulary, and the `dresser` op.
- **SPEC-Dresser-001.IC2 Op surface:** one declared-subcommand op `dresser` (help tier for free): `about`; `aspects` (registry projection: flavour, aspect, version, deps, release); `template <name>` (emit canonical template content); `plan <root>` (receipt diff + provenance: reports per-aspect current/pending/new, and loudly flags unknown/ahead/divergent receipts); `start <flavour> <root>` (optional `--aspects` subset; resolves/validates root, computes run-id, pours the flavour run); `verify <flavour> <root>` (verify-only compile of the selected aspects); `next <root>` / `advance <root>` (thin wrappers over the engine's `next-steps`/`advance!` keyed by the derived run-id, `advance` accepting `--choice`/`--input`/`--notes`); `stamp <flavour>/<aspect> <root>` (mechanical: verifies the run's gates for that aspect are closed-green in the graph, then atomically merges the receipt entry — the only stamp writer).
- **SPEC-Dresser-001.IC3 Run identity:** run-id is derived deterministically from flavour + canonical root (basename plus short content hash of the path), so `next`/`advance`/`stamp` need only the root; one active run per (flavour, root) — a second `start` while one is active fails loudly.
- **SPEC-Dresser-001.IC4 Attribute vocabulary:** `dresser/*`, declared at install: `dresser/flavour`, `dresser/aspect`, `dresser/version`, `dresser/root` on run/step strands; gates additionally carry the standard `shell/*` request attributes.
- **SPEC-Dresser-001.IC5 Template delivery:** templates are data in the spool source (string defs / pure fns of params), surfaced only via the `template` subcommand and step instructions; no classpath-resource loading, so delivery is independent of consumer classloader layout.
- **SPEC-Dresser-001.IC6 Registry invariant:** any material change to an aspect's templates, instructions, or gates bumps that aspect's version and the release version. Enforced by a drift-alarm test pinning a content fingerprint of the registry (aspect definitions + templates + compiled `describe` projections) per release.
- **SPEC-Dresser-001.IC7 Shared-spool discipline:** every public fn takes `runtime` first; state (if any) via `spool-state` with a state-version; no `skein.userland.alpha`; fail loudly via `skein.api.spool.alpha`; attribute writes are deltas.

## SPEC-Dresser-001.P6 Design decisions

### SPEC-Dresser-001.D1 Operator-world execution

- **Decision:** Dresser runs where it is activated; the target is only a resolved path (DC4); gates execute in the target via `shell/cwd`.
- **Rationale:** Kills the bootstrap circularity (a fresh target has no weaver) and keeps targets skein-free; disposable operator worlds make runs cheap.
- **Rejected:** Running in the target's own workspace (chicken-and-egg for flavour `skein-dir`; forces every target to approve spools).

### SPEC-Dresser-001.D2 Receipt/verification split

- **Decision:** `plan` diffs the receipt (pending-by-receipt + provenance divergence); `verify` re-runs gates (actual status); `stamp` advances the receipt only on graph-verified green gates.
- **Rationale:** The repo is the durable record but a receipt equality can't prove convergence after local drift; code wins, so status must be re-derived from the tree.
- **Rejected:** A combined "status" op that trusts the stamp (false-current on drift); recording applied state in the operator's graph (not durable with the repo, wrong world).

### SPEC-Dresser-001.D3 One definition, two modes

- **Decision:** Each aspect workflow compiles in setup mode or verify-only mode via a param condition.
- **Rationale:** Setup's completion gates and `verify`'s checks are the same compiled data, so they cannot drift apart.
- **Rejected:** Separate verify workflows (drift risk); gate-less verify via op-side shelling (would duplicate the executor's contract).

### SPEC-Dresser-001.D4 Aspect versions + release int, no semver

- **Decision:** Integer per-aspect versions plus one monotonic release integer; `plan` compares equality/monotonicity and flags anything else as divergence.
- **Rationale:** Sha pins are the real behavior contract (RFC-017); integers are only for receipt comparison, and anything non-monotonic means a fork/downgrade the operator must look at.
- **Rejected:** Semver/ranges (rejected by RFC-017 for spools generally); content-hash-only receipts (opaque to humans reading the stamp in review).

## SPEC-Dresser-001.P7 Open questions

- **SPEC-Dresser-001.Q1:** Final published name (`dresser.spool` working name; rename is pre-publish only).
