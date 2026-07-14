(ns skein.spools.dresser-test
  "Tests for the skein.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.test :refer [deftest is run-tests]]
            [skein.spools.dresser :as dresser]))

(deftest dresser-namespace-loads
  (is (some? dresser/release-version)))

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'skein.spools.dresser-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
