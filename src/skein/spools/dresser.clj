(ns skein.spools.dresser
  "Convention-convergence spool: versioned per-aspect setup/verify workflows
  driven from an operator weaver world against a target repo path.

  Contract: dresser.md (SPEC-Dresser-001 while in feature staging). Public
  functions take the runtime explicitly per the shared-spool rules."
  (:require [skein.spools.dresser.aspects :as aspects]))

(def release-version
  "Compatibility alias; the aspect registry is the single source of truth.
  Task 5 may remove this alias while expanding the public spool surface."
  aspects/release-version)
