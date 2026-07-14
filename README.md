# dresser.spool

`skein.spools.dresser` converges repository conventions through versioned
Skein workflows. An operator drives setup work against a target git root;
shell gates verify the result; an explicit stamp writes the checked-in receipt
at `.skein/conventions.edn`.

The spool is trusted Clojure code for a live Skein weaver. It has no
`spool.edn` manifest: approve source in `spools.edn` or `spools.local.edn`, run
`sync!`, then activate it explicitly with `use!`. The full behavior contract is
in [dresser.md](./dresser.md).

## Prerequisites

- A Skein checkout and a live weaver configured from an operator workspace.
- Explicit approval of the workflow spool root at `<skein>/spools/workflow`.
- A 40-hex git SHA pin for this repository, or a local development override.
- The shell executor namespace. It ships on the workflow spool classpath, needs
  no separate source approval, and is activation-only. Activate it after the
  workflow engine.

Dresser installs no prerequisite transitively. The target repository does not
need Skein or a running weaver; it only needs to be an existing git worktree
root.

## Dependency information

Approve every source spool explicitly. A workspace using a local Skein checkout
and a pinned dresser release can use:

```clojure
{:spools
 {skein.spools/workflow
  {:local/root "/path/to/skein/spools/workflow"}

  codethread/dresser
  {:git/url "git@github.com:codethread/dresser.spool.git"
   :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

To pin the workflow root from git instead, replace its local coordinate with:

```clojure
skein.spools/workflow
{:git/url "https://github.com/codethread/skein.git"
 :git/sha "<40-hex-skein-sha>"
 :deps/root "spools/workflow"}
```

For local dresser development, add a gitignored `spools.local.edn` overlay:

```clojure
{:spools
 {codethread/dresser
  {:local/root "/Users/you/dev/dresser.spool"}}}
```

## Activation

Sync approved roots, then activate the workflow engine, the classpath shell
executor, and dresser in that order:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/sync! runtime)

(runtime/use! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/use! runtime
  :shell-executor
  {:ns 'skein.spools.executors.shell
   :call 'skein.spools.executors.shell/install!
   :after [:workflow]
   :required? true})

(runtime/use! runtime
  :dresser
  {:ns 'skein.spools.dresser
   :spools ['codethread/dresser]
   :call 'skein.spools.dresser/install!
   :after [:workflow :shell-executor]
   :required? true})
```

The shell executor is present through the approved workflow root; omitting a
separate `:spools` guard on its activation is intentional.

## Quickstart

Initialize a target before starting a run. Dresser resolves the path once and
requires it to be the git worktree root:

```sh
git init /path/to/target
strand dresser aspects
strand dresser plan /path/to/target
strand dresser start spool-repo /path/to/target
```

Drive every non-gate item returned by `next`. Complete inspect and setup steps
after doing the stated work; answer every conflict checkpoint. Shell gates run
through the executor and do not need `advance`:

```sh
strand dresser next spool-repo /path/to/target
strand dresser advance spool-repo /path/to/target \
  --step <ready-strand-id> --notes "completed the instructed work"
strand dresser advance spool-repo /path/to/target \
  --step <checkpoint-strand-id> --choice clean
```

Use `--choice apply-plan --input decisions='<summary>'` when files need explicit
keep/merge/replace decisions, or `--choice abort --input reason='<reason>'` to
route to the abort stage. Repeat `next` and `advance` until the run reports done.
Then stamp each adopted aspect from that setup run's latest green gate evidence:

```sh
strand dresser stamp spool-repo/repo-skeleton /path/to/target
strand dresser stamp spool-repo/skein-workspace /path/to/target
strand dresser stamp spool-repo/agent-docs /path/to/target
strand dresser stamp spool-repo/quality /path/to/target
strand dresser plan /path/to/target
```

The receipt records verified adoption; it does not prove the files have not
drifted. Recheck selected aspects without setup work by starting `verify`, then
inspect that run with `next --verify`:

```sh
strand dresser verify spool-repo /path/to/target \
  --aspects spool-repo/skein-workspace,spool-repo/agent-docs
strand dresser next spool-repo /path/to/target --verify
```

One operator world may manage a target at a time. Concurrent dresser runs
against the same target from different operator worlds are out of contract;
receipt writes are atomic per file but last-writer-wins across worlds.

## Development

This checkout expects Skein at `../skein-src`; the test alias adds both the
base checkout and its workflow spool root:

```sh
clojure -M:test
PATH=/opt/homebrew/opt/openjdk/bin:$PATH make fmt-check lint test
```

The repository's `.skein/init.clj` remains the minimal batteries bootstrap.
Dresser is not activated in its own workspace.
