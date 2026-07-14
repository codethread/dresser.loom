# Conventions Spool Plan

**Document ID:** `PLAN-Dresser-001`
**Feature:** `conventions-spool`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** none yet (this repo is new)
**Feature specs:** [specs/dresser.md](./specs/dresser.md)
**Status:** Active
**Last Updated:** 2026-07-14

## PLAN-Dresser-001.P1 Goal and scope

Deliver `dresser.spool`: a distributable spool implementing SPEC-Dresser-001 — versioned per-aspect convention convergence for two flavours (`spool-repo`, `skein-dir`), driven from an operator weaver world against a target repo path, verified by `:shell` gates, receipted in the target at `.skein/conventions.edn`. Includes the repo's own converged shape (it must satisfy its own `spool-repo` flavour), a standalone test suite against a sibling skein-src checkout, and the two user-requested live adoption exercises.

## PLAN-Dresser-001.P2 Approach

- **PLAN-Dresser-001.A1:** Build bottom-up in one repo: templates ns (pure data) → aspect registry ns (aspect defs: version, deps, inspect/setup instructions, gate specs, all as data) → workflow construction (generic builder that compiles an aspect entry into a workflow with setup/verify-only modes; flavour umbrella chains aspect workflows via `call` in dependency order) → runtime ns (`install!`, target-root resolution, run-id derivation, receipt read/write, op handlers) → op registration. All explicit-runtime per the shared-spool rules; ergonomics stay in the consumer's config.
- **PLAN-Dresser-001.A2:** Test-first at the seams that are cheap to get wrong: root resolution (canonicalization, non-git rejection), receipt round-trip + provenance divergence classification, registry fingerprint drift alarm, workflow compilation in both modes (via `describe`, no weaver needed), then end-to-end fixture runs with `skein.test.alpha/with-weaver-world` (pour, drive steps as a fake agent, let real shell gates verify a real fixture tree, stamp, re-plan).
- **PLAN-Dresser-001.A3:** Distribution shape follows the reference repos exactly (deps.edn `:test` alias with sibling local roots, three-section README, contract doc `dresser.md` distilled from SPEC-Dresser-001 at promotion time).

## PLAN-Dresser-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Dresser-001.AA1 | `src/skein/spools/dresser*` (this repo, new) | Spool implementation: templates, registry, workflows, ops |
| PLAN-Dresser-001.AA2 | `test/skein/spools/*` (this repo, new) | Standalone suite with `-main`, run via `clojure -M:test` |
| PLAN-Dresser-001.AA3 | Repo root (this repo, new) | deps.edn, README, AGENTS.md, Makefile, `.skein/` quartet, `.cljfmt.edn`/`.splint.edn` — the repo eats its own `spool-repo` flavour |
| PLAN-Dresser-001.AA4 | skein-src `spools/README.md` | One index row for the external spool (at land) |
| PLAN-Dresser-001.AA5 | `~/dev/projects/kanban.spool`, `~/dev/projects/notes` | Adoption-exercise changes, left for owner review (branch / uncommitted) |

## PLAN-Dresser-001.P4 Contract and migration impact

- **PLAN-Dresser-001.CM1:** All new surface, no migrations: the `dresser` op, `dresser/*` vocabulary, the receipt schema, and the registry/release versioning contract — all specified in SPEC-Dresser-001 (feature-local, promoted to this repo's root spec at finish).

## PLAN-Dresser-001.P5 Implementation phases

### PLAN-Dresser-001.PH1 Repo scaffold

Outcome: dresser.spool repo with deps.edn (`:test` alias vs sibling checkout), `.skein/` quartet, AGENTS.md, minimal README; `clojure -M:test` runs an empty suite green.

### PLAN-Dresser-001.PH2 Data layer

Outcome: templates ns + aspect registry ns with the seven v1 aspects as data; registry fingerprint drift-alarm test green; `template`/`aspects` projections pure-tested.

### PLAN-Dresser-001.PH3 Workflow compiler

Outcome: aspect-entry → workflow construction for setup and verify-only modes, flavour umbrellas via `call`; `describe`-level tests prove step/gate/checkpoint shapes and mode conditions without a weaver.

### PLAN-Dresser-001.PH4 Runtime and ops

Outcome: `install!` (prereq fail-loud, vocab, workflow registration, op), root resolution, run-id derivation, receipt read/write + `plan` classification, `start`/`verify`/`next`/`advance`/`stamp` handlers; unit tests for resolution/receipt/plan; end-to-end fixture test for a full `skein-dir` run and a `spool-repo` run in a weaver world.

### PLAN-Dresser-001.PH5 Self-hosting + docs

Outcome: the repo passes its own `spool-repo` flavour (quality aspect gates green locally); README gets the full Prerequisites / Dependency information / Activation / Quickstart recipe; `dresser.md` contract doc written.

### PLAN-Dresser-001.PH6 Adoption exercises

Outcome: flavour 1 driven against kanban.spool (dedicated branch, unmerged), flavour 2 against notes (uncommitted); both stamped green from a disposable operator world; findings recorded on the kanban card.

## PLAN-Dresser-001.P6 Validation strategy

- **PLAN-Dresser-001.V1:** `clojure -M:test` (standalone suite, sibling checkout) green — the repo's CI-equivalent gate.
- **PLAN-Dresser-001.V2:** Fixture end-to-end: both flavours converge disposable fixture repos with real shell gates; receipt written only after green; `plan` reports current, then pending after a simulated version bump, then divergent on a simulated fork receipt.
- **PLAN-Dresser-001.V3:** Self-hosting: dresser.spool's own tree passes `verify` for `spool-repo` aspects it claims.
- **PLAN-Dresser-001.V4:** Live exercises per S7 leave green gates, a stamp, and no commits by the agent.

## PLAN-Dresser-001.P7 Risks and open questions

- **PLAN-Dresser-001.R1:** Shell-gate environment drift (java/clojure availability in gate processes spawned by the weaver). Mitigation: gates use argv-only commands resolvable on PATH; the quality aspect's gate commands are the same `clojure -M:...` aliases the templates install; fixture tests run the real executor early (PH4) to surface env issues.
- **PLAN-Dresser-001.R2:** Conflict-checkpoint ergonomics on heavily customized targets (notes). Mitigation: inspect-step instructions require a written merge plan before the checkpoint; the notes exercise is the deliberate hard case and feeds v2 adjustments via card notes.
- **PLAN-Dresser-001.R3:** Registry fingerprint too brittle (noisy failures on refactors). Mitigation: fingerprint only material data (instructions, templates, gate argv, step topology from `describe`), not source text.

## PLAN-Dresser-001.P8 Task context

- **PLAN-Dresser-001.TC1:** Read SPEC-Dresser-001 first; it owns all contracts. Reference repos: `~/dev/projects/devflow.spool` (minimal shape), `~/dev/projects/kanban.spool` (with extras). Engine contracts: skein-src `spools/workflow.md` §3–5, `spools/executors/shell.md` (gate attrs, error/recovery), `docs/spools/writing-shared-spools.md` (explicit-runtime rules, test harness pattern). Quality-aspect sources of truth: skein-src `deps.edn` aliases (cljfmt 0.13.1, clj-kondo 2025.06.05, splint 1.21.0) and `.cljfmt.edn`/`.splint.edn`. The skein-src coordination world drives tracking (card `niu25`, run `conventions-spool`); implementation work happens in this repo.

## PLAN-Dresser-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
