# Dresser convention spool

This document defines the contract of `ct.spools.dresser`. Dresser runs in
an operator's weaver world and treats a target repository as a canonical
absolute path. The target needs no Skein configuration of its own. It must
already exist and be a git worktree root.

Dresser does not edit files mechanically. Workflow instructions assign inspect
and setup work to the driving agent. Shell gates provide non-destructive
verification. A checked-in receipt records which verified aspect versions were
stamped.

## Flavours and aspects

An aspect is independently versioned and addressed as `<flavour>/<aspect>`.
Dependencies are closed automatically when a subset is selected.

| Flavour | Aspect | Version | Depends on | Gate ids |
|---|---|---:|---|---|
| `spool-repo` | `repo-skeleton` | 3 | — | `test-suite`, `readme-sections` |
| `spool-repo` | `skein-workspace` | 2 | — | `workspace-files` |
| `spool-repo` | `agent-docs` | 1 | — | `agents-md` |
| `spool-repo` | `quality` | 2 | `spool-repo/repo-skeleton` | `fmt-check`, `lint` |
| `skein-dir` | `workspace` | 2 | — | `workspace-files`, `init-header` |
| `skein-dir` | `quality` | 2 | `skein-dir/workspace` | `fmt-check`, `lint` |
| `skein-dir` | `agent-docs` | 1 | `skein-dir/workspace` | `agent-docs-files` |

`spool-repo` describes a shared-spool repository with source, tests, root
quality tooling, agent guidance, and a minimal `.skein` workspace. `skein-dir`
describes a self-contained `.skein` workspace and never owns host-root files.

## Commands

`strand dresser about` returns purpose, flavour, receipt, plan, verify, stamp,
and quickstart semantics. `strand dresser aspects` returns the release,
fingerprint, historical lineage, aspect versions, dependencies, and gate ids.
`strand dresser template <name>` returns canonical template content; repeat
`--param name=value` for required parameters.

`strand dresser plan <root>` resolves the target and compares its receipt with
the active registry. `strand dresser start <flavour> <root>` starts a setup run.
`strand dresser verify <flavour> <root>` starts a verify-only run. Both accept
`--aspects` with a comma-separated list of full aspect keys and close the list
under dependencies.

`strand dresser next <flavour> <root>` returns the setup run's ready frontier.
It passes through the step-view vector owned by
`skein.spools.workflow/ready` without reshaping it.
`strand dresser advance <flavour> <root>` completes one ready agent step or
checkpoint and accepts `--step`, `--choice`, and `--input`. Add
`--verify` to either command to address the verify run. Verify runs contain only
shell gates, so normally the shell executor advances them.

`strand dresser stamp <flavour>/<aspect> <root>` validates the latest setup
molecule's durable gate evidence and updates the receipt for one aspect.
Stamp never uses a verify run or an older setup molecule as evidence.

## Run lifecycle

`start` resolves the target root, validates the flavour and selected aspects,
and pours one setup workflow. Each aspect has an inspect step, a required human
conflict checkpoint, zero or more setup steps, then its verification gates.
Inspect findings and the proposed keep/merge/replace decision for every
conflicting file inform the checkpoint choice, which is always explicit:

- `clean`: inspection found nothing requiring a decision.
- `apply-plan`: requires `decisions`, summarizing the per-file choices, judged
  by `:ct.spools.dresser.specs/conflict-decisions-input`.
- `abort`: requires `reason` and routes to the abort stage, judged by
  `:ct.spools.dresser.specs/abort-workflow-input` — the same spec the abort
  stage's own params answer to, so a reason the checkpoint accepts is a reason
  the continuation can start on.

A run without `--aspects` starts the registered umbrella `:dresser/<flavour>`
by name. A selection is narrower than any published definition — a `call` takes
no condition, so which aspects a definition covers is fixed where it is
authored — so dresser builds the narrower umbrella and pours the value. Both
paths produce the same graph for the same aspect set; only a full-flavour run
records `workflow/definition-name` on its root.

The driver repeats `next` and `advance` for agent-owned work. Ready gates belong
to the shell executor. A zero exit closes a gate with
`workflow/outcome-by = "shell"` and `shell/exit-code = 0`. The workflow closes
only after every selected aspect's work and gates close. `stamp` is a separate,
explicit operation after the run is done.

Setup run ids have the form
`dresser-<flavour>-<basename>-<root-hash>`. Verify run ids use the
`dresser-verify-` prefix. One active run is allowed per flavour, canonical root,
and mode; both flavours and both modes have distinct identities. Dresser always
addresses the latest retained molecule for that run id.

## Receipt, plan, verify, and stamp

The receipt is `.skein/conventions.edn` under the target root:

```clojure
{:dresser/release 1
 :dresser/fingerprint "<release-sha256>"
 :aspects
 {"spool-repo/agent-docs"
  {:version 1 :release 1 :applied-at "2026-07-14"}}}
```

It is an atomic, checked-in record of green setup-run verification. It is not
proof of current convergence after later file changes. One operator world may
manage a target at a time; concurrent writes from separate worlds are atomic
per file but last-writer-wins.

`plan` reports every registry aspect and every receipt-only aspect. Its six
classification states are:

| State | Meaning |
|---|---|
| `new` | Registry aspect has no receipt entry. |
| `pending` | Receipt aspect version is lower than the registry version. |
| `current` | Versions match and the receipt release fingerprint matches known lineage. |
| `divergent` | Versions match, but the receipt release is unknown or its fingerprint mismatches lineage. |
| `ahead` | Receipt aspect version or release is newer than the active registry. |
| `removed` | Receipt contains an aspect absent from the active registry. |

`verify` pours the same aspect definitions with inspect, checkpoint, and setup
steps removed by conditions. Only the registry gates run. It reports actual
tree status but does not change the receipt.

`stamp` accepts an aspect only when the latest setup molecule contains its
complete expected gate set and each gate is closed by `shell`, has exit code
zero, and carries no `gate/error`. Missing, unexpected, duplicate,
force-closed, failed, or historical gates are rejected. On acceptance, stamp
atomically merges the aspect version, current release, release fingerprint, and
application date into the receipt.

## Attributes

The dresser attribute namespace is declared by the module's reconcile on an
applied contribution.

| Attribute | Present on | Meaning |
|---|---|---|
| `dresser/flavour` | run roots and steps | Target convention flavour. |
| `dresser/aspect` | aspect steps and gates | Full `<flavour>/<aspect>` key. |
| `dresser/version` | aspect steps and gates | Registry version of that aspect. |
| `dresser/root` | run roots and steps | Canonical absolute target root. |
| `dresser/gate-id` | aspect gates | Registry gate id the stamp evidence matches on. |

Shell gates additionally carry the standard `workflow/gate` and `shell/*`
request/outcome attributes, plus `gate/error` — the failure stamp shared by every
gate executor. Dresser reads these; it defines no alternate executor state.

## Failure and recovery

A non-zero command, timeout, spawn failure, or invalid gate request leaves the
gate ready and stamps `gate/error`; it does not close the gate or the run.
Inspect the gate's `gate/error`, `shell/output`, and `shell/exit-code`, then fix
the target or gate request. Clear the durable error to request a deterministic
rerun:

```sh
strand update <gate-id> --attributes '{"gate/error":null}'
```

Removal is the executors' clearing idiom, so dresser reads the key exactly as
they write it: an absent `gate/error` is cleared, and only a non-blank stamp
blocks `stamp`.

The shell executor scans the now-ready, unclaimed gate again. When it exits
zero, continue until the run is done, then call `stamp`. A crash leaving
`shell/running` without a live process uses the same recovery pattern after
clearing `shell/running`. Never stamp by force-closing a gate: stamp requires
executor-recorded evidence from the latest setup molecule.

Installation fails loudly unless the workflow lifecycle is on the classpath
and the `:shell` executor is registered. Unknown templates, flavours, aspects,
selection entries, invalid roots, active duplicate runs, and invalid stamp
evidence also fail with structured data.
