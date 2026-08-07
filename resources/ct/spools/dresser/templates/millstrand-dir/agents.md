# Millstrand workspace config

Keep configuration split one file per concern and activate each file from `init.clj`:

- `config.clj` — named queries and CLI ops
- `workflows.clj` — hand-authored workflows
- `harnesses.clj` — harness seats and routing policy
- `reviewers.clj` — reviewer rosters

Use the change/reload ladder in Millstrand's `docs/spools/customisation.md`; reload config instead of restarting the weaver.
Smoke-test every config change in a disposable world before applying it to the coordination world.
