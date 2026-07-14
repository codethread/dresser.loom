(ns skein.spools.dresser
  "Convention-convergence spool: versioned per-aspect setup/verify workflows
  driven from an operator weaver world against a target repo path.

  Contract: dresser.md (SPEC-Dresser-001 while in feature staging). Public
  functions take the runtime explicitly per the shared-spool rules.")

(def release-version
  "Monotonic release integer for the aspect registry as a whole. Bumped
  whenever any aspect version bumps; recorded in target receipts as the
  provenance anchor `plan` compares against."
  1)
