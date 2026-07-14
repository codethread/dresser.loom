# Task 1: Templates namespace with v1 template data

**Document ID:** `TASK-Dresser-001`

## TASK-Dresser-001.P1 Scope

Type: AFK

## TASK-Dresser-001.P2 Must implement exactly

- **TASK-Dresser-001.MI1:** `src/ct/spools/dresser/templates.clj` — ns `ct.spools.dresser.templates`: every v1 template as source data (string defs, or pure fns of a params map where the content needs a `<name>` substitution), plus a public registry map `templates` keyed by template name string, and `(template name)` / `(template name params)` lookup fns that fail loudly (`skein.api.spool.alpha/fail!`) on unknown names or missing required params.
- **TASK-Dresser-001.MI2:** Template keys and content per SPEC-Dresser-001.IC8/IC9. Shared quartet under `skein/`: `skein/config.json` (`{"configFormat":"alpha"}` + newline), `skein/spools.edn` (`{:spools {}}` + newline), `skein/init-minimal.clj` (byte-identical to devflow.spool's committed `.skein/init.clj`), `skein/init-layered.clj` (the `skein-dir` variant: same bootstrap plus a leading "Startup entrypoint" header comment documenting the one-file-per-concern layout convention), `skein/gitignore` (devflow.spool's `.skein/.gitignore` list). Flavour `spool-repo/`: `spool-repo/deps.edn` (fn of `{:name}`; the reference `:paths`/`:test` alias shape from this repo's own deps.edn), `spool-repo/src-ns.clj` and `spool-repo/test-main.clj` (fns of `{:name}`; minimal ns + fail-loud `-main` test runner), `spool-repo/gitignore` (this repo's root `.gitignore`), `spool-repo/readme` (fn of `{:name}`; skeleton with `## Prerequisites`, `## Dependency information`, `## Activation` sections), `spool-repo/agents.md` (the `mill:skein-prime` AGENTS.md), `spool-repo/cljfmt.edn`, `spool-repo/splint.edn` (skein-src's root configs, comments included), `spool-repo/makefile` (targets `fmt`, `fmt-check`, `lint`, `test` over `clojure -M:format/fix`, `-M:format`, `-M:lint/clj-kondo` + `-M:lint/splint`, `-M:test`), `spool-repo/quality-aliases.edn` (EDN fragment: the `:format`, `:format/fix`, `:lint/clj-kondo`, `:lint/splint` alias map with cljfmt 0.13.1, clj-kondo 2025.06.05, splint 1.21.0 over paths `src` and `test`). Flavour `skein-dir/`: `skein-dir/deps.edn` (aliases `:format`, `:format/fix`, `:lint` over the workspace's own `.clj` files, paths `["."]`), `skein-dir/makefile` (targets `fmt`, `fmt-check`, `lint` running those aliases from the `.skein` dir), `skein-dir/agents.md` (working discipline for `.skein` config code: file-per-concern map placeholder, reload-over-restart pointer, disposable-world smoke rule), `skein-dir/claude.md` (one line pointing at `.skein/AGENTS.md`).
- **TASK-Dresser-001.MI3:** Tests in `test/ct/spools/dresser_test.clj` (extend the existing ns): registry completeness (every IC8-referenced key present), parameterized templates render with a sample name and contain no leftover placeholder, unknown-name and missing-param lookups throw with data, and no template line exceeds column 180.

## TASK-Dresser-001.P3 Done when

- **TASK-Dresser-001.DW1:** `clojure -M:test` (from the repo root, with sibling `../skein-src` present) exits 0 with the new assertions included.
- **TASK-Dresser-001.DW2:** `(template "spool-repo/deps.edn" {:name "acme"})` returns content containing `skein.spools.acme-test`, and `(template "skein/config.json")` round-trips as the exact reference bytes.

## TASK-Dresser-001.P4 Out of scope

- **TASK-Dresser-001.OS1:** Aspect registry, workflows, ops, receipt logic (later tasks).

## TASK-Dresser-001.P5 References

- **TASK-Dresser-001.REF1:** SPEC-Dresser-001.IC8/IC9 (this folder, `specs/dresser.md`) — authoritative template inventory.
- **TASK-Dresser-001.REF2:** Reference bytes: `~/dev/projects/devflow.spool/.skein/*`, `~/dev/projects/devflow.spool/.gitignore`, `~/dev/projects/devflow.spool/AGENTS.md`, skein-src `.cljfmt.edn`/`.splint.edn`, skein-src `deps.edn` aliases (`:format`, `:lint/*`), and this repo's `deps.edn`.
- **TASK-Dresser-001.REF3:** Long-prose rule: author multi-line strings as plain defs with explicit `\n` joins or `|`-margin blocks via `skein.api.format.alpha`; no line past column 180.
