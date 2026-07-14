(ns skein.spools.dresser-edges-test
  "End-to-end contract edges for dresser run identity, routing, evidence, and plan."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.api.spool.alpha :as spool]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.dresser :as dresser]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser-fixtures :as fixtures]
            [skein.spools.dresser.receipt :as receipt]
            [skein.spools.dresser.target :as target]
            [skein.spools.workflow :as workflow]))

(defn- op! [runtime & args]
  (weaver/op! runtime 'dresser (vec args)))

(defn- advance-ready! [runtime flavour root & args]
  (let [step (first (apply op! runtime "next" flavour (str root) args))]
    (apply op! runtime "advance" flavour (str root)
           (concat args ["--step" (:id step) "--notes" "edge fixture advanced step"]))))

(defn- receipt-for [release fingerprint aspect-key]
  {:dresser/release release
   :dresser/fingerprint fingerprint
   :aspects {aspect-key {:version (:version (aspects/aspect aspect-key))
                         :release release
                         :applied-at "2026-07-14"}}})

(deftest full-spool-repo-run-stamps-all-aspects-and-plans-current
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "fixture")
          aspect-keys (aspects/flavour-aspects "spool-repo")]
      (fixtures/seed-spool-repo! root)
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root))
          (fixtures/assert-done! (fixtures/drive-spool-repo! runtime root))
          (let [stamp (fixtures/stamp-and-assert-current! root aspect-keys)
                planned (op! runtime "plan" (str root))]
            (is (= aspects/release-version (:dresser/release stamp)))
            (is (= (aspects/releases aspects/release-version)
                   (:dresser/fingerprint stamp)))
            (is (= (set aspect-keys) (set (keys (:aspects stamp)))))
            (is (every? #{:current} (map (:aspects planned) aspect-keys)))))))))

(deftest quality-subset-pulls-dependency-and-pours-only-closed-selection
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "subset")
          run-id (target/run-id "spool-repo" root)
          expected #{"spool-repo/repo-skeleton" "spool-repo/quality"}]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/quality")
          (is (= expected (fixtures/poured-aspects runtime run-id)))
          (is (str/starts-with? (:title (first (op! runtime "next" "spool-repo"
                                                    (str root))))
                                "Inspect repo-skeleton")))))))

(deftest both-flavours-run-concurrently-on-one-root-and-are-drivable
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "concurrent")
          spool-run (target/run-id "spool-repo" root)
          skein-run (target/run-id "skein-dir" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (op! runtime "start" "skein-dir" (str root)
               "--aspects" "skein-dir/workspace")
          (is (not= spool-run skein-run))
          (is (= #{spool-run skein-run}
                 (set (map #(spool/attr-get % :workflow/run-id)
                           (workflow/active-runs "dresser")))))
          (advance-ready! runtime "spool-repo" root)
          (advance-ready! runtime "skein-dir" root)
          (is (= "checkpoint"
                 (:kind (first (op! runtime "next" "spool-repo" (str root))))))
          (is (= "checkpoint"
                 (:kind (first (op! runtime "next" "skein-dir" (str root)))))))))))

(deftest second-start-on-active-flavour-root-mode-fails-loudly
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "active")
          run-id (target/run-id "spool-repo" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (let [exception (fixtures/capture-exception
                           #(op! runtime "start" "spool-repo" (str root)))]
            (is (some? exception))
            (is (str/includes? (ex-message exception) "Active workflow run already exists"))
            (is (= run-id (:run-id (ex-data exception))))))))))

(deftest repeated-run-uses-fresh-generation-and-rejects-stale-green-gates
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "generation")
          aspect-key "spool-repo/agent-docs"
          run-id (target/run-id "spool-repo" root)]
      (fixtures/write-spool-repo-step-files! root "Write AGENTS.md")
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (fixtures/assert-done! (fixtures/drive-spool-repo! runtime root))
          (is (= :current (:plan (dresser/stamp! aspect-key root))))
          (fixtures/await-next-clock-second!)
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (let [history (workflow/run-history run-id)
                exception (fixtures/capture-exception #(dresser/stamp! aspect-key root))
                violations (set (map :violation (:violations (ex-data exception))))]
            (is (= 2 (count history)))
            (is (apply not= (map #(get-in % [:root :id]) history)))
            (is (str/includes? (ex-message exception) "stamp evidence failed"))
            (is (seq (set/intersection
                      #{:gate-not-closed :outcome-by :exit-code}
                      violations))
                "generation-one green gates are stale for generation two")))))))

(deftest apply-plan-records-decisions-and-kept-customization-can-converge
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "keep")
          aspect-key "spool-repo/agent-docs"
          custom "# Local agent policy\n\n<!-- mill:skein-prime -->\nKeep this local rule.\n"
          agents-file (io/file (str root) "AGENTS.md")]
      (spit agents-file custom)
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (advance-ready! runtime "spool-repo" root)
          (let [checkpoint (first (op! runtime "next" "spool-repo" (str root)))]
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id checkpoint)
                 "--choice" "apply-plan"
                 "--input" "decisions=keep AGENTS.md local policy"))
          (let [setup (first (op! runtime "next" "spool-repo" (str root)))]
            (is (= "Write AGENTS.md" (:title setup)))
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id setup) "--notes" "kept customized passing file"))
          (fixtures/assert-done! (fixtures/wait-for-attention! runtime
                                                               (target/run-id "spool-repo" root)))
          (let [choice (some #(when (= :choice (:type %)) %)
                             (:events (first (workflow/run-history
                                              (target/run-id "spool-repo" root)))))
                decisions (or (get-in choice [:input :decisions])
                              (get-in choice [:input "decisions"]))]
            (is (= "apply-plan" (:outcome choice)))
            (is (= "keep AGENTS.md local policy" decisions))
            (is (= custom (slurp agents-file)))
            (is (= :current (:plan (dresser/stamp! aspect-key root))))))))))

(deftest abort-routes-to-terminal-workflow-and-runs-no-gates
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "abort")
          run-id (target/run-id "spool-repo" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (advance-ready! runtime "spool-repo" root)
          (let [checkpoint (first (op! runtime "next" "spool-repo" (str root)))]
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id checkpoint)
                 "--choice" "abort"
                 "--input" "reason=fixture conflict"))
          (is (= "Record dresser abort"
                 (:title (first (op! runtime "next" "spool-repo" (str root))))))
          (let [result (advance-ready! runtime "spool-repo" root)
                gates (filter #(= "shell" (spool/attr-get % :workflow/gate))
                              (fixtures/all-run-strands runtime run-id))]
            (is (true? (:done result)))
            (is (= 2 (count (workflow/run-history run-id))))
            (is (seq gates))
            (is (every? #(nil? (spool/attr-get % :shell/exit-code)) gates))
            (is (every? #(not= "shell" (spool/attr-get % :workflow/outcome-by))
                        gates))))))))

(deftest plan-op-classifies-bogus-fingerprint-and-future-release
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "plan")
          aspect-key "spool-repo/repo-skeleton"
          release aspects/release-version]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (receipt/write-receipt! root (receipt-for release "bogus" aspect-key))
          (let [plan (op! runtime "plan" (str root))]
            (is (= :divergent (get-in plan [:aspects aspect-key])))
            (is (= :divergent (get-in plan [:provenance :verdict]))))
          (let [future (inc release)]
            (receipt/write-receipt! root (receipt-for future "future" aspect-key))
            (let [plan (op! runtime "plan" (str root))]
              (is (= :ahead (get-in plan [:aspects aspect-key])))
              (is (= :ahead (get-in plan [:provenance :verdict]))))))))))

(deftest verify-on-converged-fixture-pours-only-gates-and-uses-verify-addressing
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "verify")
          aspect-key "spool-repo/agent-docs"
          run-id (target/verify-run-id "spool-repo" root)
          release aspects/release-version
          fingerprint (aspects/releases release)]
      (fixtures/write-spool-repo-step-files! root "Write AGENTS.md")
      (receipt/write-receipt! root (receipt-for release fingerprint aspect-key))
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (is (= :current (get-in (op! runtime "plan" (str root))
                                  [:aspects aspect-key])))
          (op! runtime "verify" "spool-repo" (str root) "--aspects" aspect-key)
          (let [strands (fixtures/latest-generation-strands runtime run-id)
                ready (op! runtime "next" "spool-repo" (str root) "--verify")]
            (is (= 1 (count ready)))
            (is (= #{"shell"} (set (map :gate ready))))
            (is (not-any? #(or (str/starts-with? (:title %) "Inspect ")
                               (= "checkpoint" (spool/attr-get % :workflow/role))
                               (str/starts-with? (:title %) "Write "))
                          strands))
            (let [result (op! runtime "advance" "spool-repo" (str root)
                              "--verify" "--step" (:id (first ready))
                              "--notes" "verify edge manually advanced")]
              (is (true? (:done result)))
              (is (empty? (op! runtime "next" "spool-repo" (str root)
                               "--verify"))))))))))
