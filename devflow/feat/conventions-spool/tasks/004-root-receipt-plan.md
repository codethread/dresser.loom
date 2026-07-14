# Task 4: Root resolution, receipt codec, plan classification

**Document ID:** `TASK-Dresser-004@2`

## TASK-Dresser-004.P1 Scope

Type: AFK

## TASK-Dresser-004.P2 Must implement exactly

- **TASK-Dresser-004.MI1:** `src/ct/spools/dresser/target.clj` — ns `ct.spools.dresser.target`: `(resolve-root path)` per SPEC-Dresser-001.DC4 — canonicalize (`.toRealPath`, resolving symlinks), require an existing directory containing `.git` (dir or file), return the canonical absolute string; fail loudly with the offending path and reason otherwise. `(run-id flavour root)` and `(verify-run-id flavour root)` per IC4 (`dresser-` vs `dresser-verify-` prefix, `<flavour>-<basename>-<first 8 hex of SHA-256 of the canonical root>`).
- **TASK-Dresser-004.MI2:** `src/ct/spools/dresser/receipt.clj` — ns `ct.spools.dresser.receipt`: `(read-receipt root)` (nil when absent; fail loudly on unparseable EDN or a non-map), `(write-receipt! root receipt)` (atomic: temp file in `.skein/` + `Files/move` with `ATOMIC_MOVE`, creating `.skein/` if needed; a private 3-arity seam takes the move fn so tests can inject failure), `(merge-aspect receipt aspect-key entry release fingerprint applied-at)` pure merge per DC5 (`applied-at` is an explicit ISO-date argument; the caller acquires the clock), `(plan-classification receipt registry-view)` returning per-aspect states per IC6 (`new`/`pending`/`current`/`divergent`/`ahead`/`removed`) where `registry-view` is `{:release int :fingerprint hex :releases {int hex} :aspects {key version}}`.
- **TASK-Dresser-004.MI3:** Tests: symlinked path resolves to its real root; missing path, file-not-dir, and non-git dir each throw with data; run-id and verify-run-id are stable, mutually distinct, and differ across flavours and roots; receipt round-trip preserves data; atomic write via the injected-failure seam leaves the previous receipt intact and no temp/partial file behind; classification matrix — absent receipt → all `new`; **aspect version** behind → `pending`; equal version + lineage-matching fingerprint → `current`; equal version + unknown fingerprint → `divergent` (same-integer fork); **aspect version ahead of the registry's** → `ahead`; **receipt release int ahead of the registry's** → `ahead`; receipt aspect not in registry → `removed`.

## TASK-Dresser-004.P3 Done when

- **TASK-Dresser-004.DW1:** `clojure -M:test` exits 0 including the full classification matrix.

## TASK-Dresser-004.P4 Out of scope

- **TASK-Dresser-004.OS1:** Ops, stamping evidence, workflow lifecycle (task 5).

## TASK-Dresser-004.P5 References

- **TASK-Dresser-004.REF1:** SPEC-Dresser-001.DC4/DC5/DC6/IC4/IC6 in `specs/dresser.md`.
- **TASK-Dresser-004.REF2:** Task 002's `releases`/`fingerprint` (the lineage inputs to classification).
