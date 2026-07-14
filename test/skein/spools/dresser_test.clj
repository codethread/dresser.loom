(ns skein.spools.dresser-test
  "Tests for the skein.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [skein.spools.dresser :as dresser]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser.receipt :as receipt]
            [skein.spools.dresser.target :as target]
            [skein.spools.dresser.templates :as templates]
            [skein.spools.dresser.workflows :as dresser-workflows]
            [skein.spools.workflow :as workflow])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(deftest dresser-namespace-loads
  (is (some? dresser/release-version)))

(def expected-template-names
  #{"skein/config.json"
    "skein/spools.edn"
    "skein/init-minimal.clj"
    "skein/init-layered.clj"
    "skein/gitignore"
    "spool-repo/deps.edn"
    "spool-repo/src-ns.clj"
    "spool-repo/test-main.clj"
    "spool-repo/gitignore"
    "spool-repo/readme"
    "spool-repo/agents.md"
    "spool-repo/cljfmt.edn"
    "spool-repo/splint.edn"
    "spool-repo/makefile"
    "spool-repo/quality-aliases.edn"
    "skein-dir/deps.edn"
    "skein-dir/makefile"
    "skein-dir/agents.md"
    "skein-dir/claude.md"})

(def parameterized-template-names
  #{"spool-repo/deps.edn"
    "spool-repo/src-ns.clj"
    "spool-repo/test-main.clj"
    "spool-repo/readme"})

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (ex-data exception))))

(deftest v1-template-registry-is-complete
  (is (= expected-template-names (set (keys templates/templates)))))

(deftest parameterized-templates-render-name
  (doseq [template-name parameterized-template-names]
    (testing template-name
      (let [content (templates/template template-name {:name "acme"})]
        (is (str/includes? content "acme"))
        (is (not (str/includes? content "<name>"))))))
  (is (str/includes? (templates/template "spool-repo/deps.edn" {:name "acme"})
                     "skein.spools.acme-test")))

(deftest static-reference-template-is-exact
  (is (= "{\"configFormat\":\"alpha\"}\n"
         (templates/template "skein/config.json"))))

(deftest template-lookups-fail-with-data
  (let [unknown (thrown-data #(templates/template "not-a-template"))
        missing (thrown-data #(templates/template "spool-repo/deps.edn"))]
    (is (= "not-a-template" (:template unknown)))
    (is (contains? (:known unknown) "skein/config.json"))
    (is (= "spool-repo/deps.edn" (:template missing)))
    (is (= :name (:required missing)))
    (is (= {} (:params missing)))))

(deftest template-lines-fit-review-width
  (doseq [[template-name entry] templates/templates
          :let [content (if (fn? entry) (entry {:name "acme"}) entry)]
          [line-number line] (map-indexed vector (str/split-lines content))]
    (testing (str template-name ":" (inc line-number))
      (is (<= (count line) 180)))))

(def expected-aspect-ids
  {"spool-repo/repo-skeleton"
   {:setup [:write-deps :write-src-test :write-readme :write-gitignore]
    :gates [:test-suite :readme-sections]}
   "spool-repo/skein-workspace"
   {:setup [:write-workspace] :gates [:workspace-files]}
   "spool-repo/agent-docs"
   {:setup [:write-agents-md] :gates [:agents-md]}
   "spool-repo/quality"
   {:setup [:write-quality-config :write-makefile] :gates [:fmt-check :lint]}
   "skein-dir/workspace"
   {:setup [:write-workspace] :gates [:workspace-files :init-header]}
   "skein-dir/quality"
   {:setup [:write-quality-tooling] :gates [:fmt-check :lint]}
   "skein-dir/agent-docs"
   {:setup [:write-agent-docs] :gates [:agent-docs-files]}})

(deftest v1-aspect-registry-is-valid
  (is (= (set (keys expected-aspect-ids)) (set (keys aspects/registry))))
  (doseq [[aspect-key entry] aspects/registry
          :let [[_ flavour aspect-name] (re-matches #"([^/]+)/([^/]+)" aspect-key)]]
    (testing aspect-key
      (is (#{"spool-repo" "skein-dir"} flavour))
      (is (some? aspect-name))
      (is (integer? (:version entry)))
      (is (= (get expected-aspect-ids aspect-key)
             {:setup (mapv :id (:setup entry))
              :gates (mapv :id (:gates entry))}))
      (doseq [dep (:deps entry)]
        (is (contains? aspects/registry dep))
        (is (str/starts-with? dep (str flavour "/"))))
      (doseq [template-name (mapcat :templates (:setup entry))]
        (is (contains? templates/templates template-name)))
      (doseq [gate (:gates entry)]
        (is (vector? (:argv gate)))
        (is (every? string? (:argv gate)))))))

(deftest aspect-ordering-and-dependency-closure
  (let [spool-order (aspects/flavour-aspects "spool-repo")
        skein-order (aspects/flavour-aspects "skein-dir")]
    (is (< (.indexOf spool-order "spool-repo/repo-skeleton")
           (.indexOf spool-order "spool-repo/quality")))
    (is (< (.indexOf skein-order "skein-dir/workspace")
           (.indexOf skein-order "skein-dir/quality")))
    (is (< (.indexOf skein-order "skein-dir/workspace")
           (.indexOf skein-order "skein-dir/agent-docs"))))
  (is (= ["spool-repo/repo-skeleton" "spool-repo/quality"]
         (aspects/close-under-deps "spool-repo" ["spool-repo/quality"])))
  (is (= "missing" (:aspect (thrown-data
                              #(aspects/close-under-deps "spool-repo" ["missing"])))))
  (is (= "missing" (:aspect (thrown-data #(aspects/aspect "missing"))))))

(deftest material-fingerprint-is-stable-and-sensitive
  (let [topology-a (array-map :b 2 :a 1)
        topology-b (array-map :a 1 :b 2)
        baseline (aspects/fingerprint topology-a)]
    (is (= baseline (aspects/fingerprint topology-b)))
    (is (not= baseline (aspects/fingerprint {:a 1 :b 3})))
    (is (not=
         baseline
         (with-redefs [aspects/registry
                       (update-in aspects/registry
                                  ["spool-repo/repo-skeleton" :inspect]
                                  str " changed")]
           (aspects/fingerprint topology-a))))))

(defn- aspect-description [aspect-key verify-only]
  (let [params {:root "/tmp/x" :verify-only verify-only}]
    (workflow/describe ((dresser-workflows/aspect-workflow aspect-key) params)
                       params)))

(defn- expected-aspect-dependencies [entry]
  (let [setup-ids (mapv :id (:setup entry))
        setup-dependencies (map vector (cons :conflict setup-ids))
        gate-dependency (or (peek setup-ids) :conflict)]
    (into {:inspect [] :conflict [:inspect]}
          (concat (map vector setup-ids setup-dependencies)
                  (map (fn [{:keys [id]}] [id [gate-dependency]])
                       (:gates entry))))))

(deftest aspect-workflows-describe-both-modes
  (doseq [[aspect-key entry] aspects/registry]
    (testing aspect-key
      (let [setup-description (aspect-description aspect-key false)
            verify-description (aspect-description aspect-key true)
            setup-steps (:steps setup-description)
            verify-steps (:steps verify-description)
            setup-ids (mapv :id (:setup entry))
            gate-ids (mapv :id (:gates entry))
            expected-ids (into [:inspect :conflict] (concat setup-ids gate-ids))
            dependencies (expected-aspect-dependencies entry)]
        (is (= expected-ids (mapv :id setup-steps)))
        (is (= dependencies
               (into {} (map (juxt :id :depends-on)) setup-steps)))
        (is (= "checkpoint" (:kind (second setup-steps))))
        (is (= (set gate-ids)
               (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                     setup-steps)))
        (is (= gate-ids (mapv :id verify-steps)))
        (is (every? (comp empty? :depends-on) verify-steps))
        (is (every? #(= "shell" (:gate %)) verify-steps))))))

(deftest conflict-checkpoint-declares-policy-inputs
  (let [checkpoint (-> (aspect-description "spool-repo/repo-skeleton" false)
                       :steps
                       second)
        choices (into {} (map (juxt :key identity)) (:choices checkpoint))]
    (is (= "conflict" (name (:id checkpoint))))
    (is (= #{"clean" "apply-plan" "abort"} (set (keys choices))))
    (is (nil? (:input (choices "clean"))))
    (is (= [{"key" "decisions"
             "required" true
             "description" "Summary of per-file keep/merge/replace decisions."}]
           (:input (choices "apply-plan"))))
    (is (= ":dresser/abort" (:next (choices "abort"))))
    (is (= "reason" (get-in choices ["abort" :input 0 "key"])))
    (is (true? (get-in choices ["abort" :input 0 "required"])))))

(defn- compiled-step-map [constructor params]
  (let [payload (workflow/compile (constructor params) params)]
    (into {} (map (juxt :ref identity)) (rest (:strands payload)))))

(deftest aspect-workflows-compile-required-attributes
  (doseq [[aspect-key entry] aspects/registry
          :let [[flavour] (str/split aspect-key #"/" 2)
                params {:root "/tmp/x" :verify-only false}
                strands (compiled-step-map
                         (dresser-workflows/aspect-workflow aspect-key)
                         params)
                expected-common {"dresser/flavour" flavour
                                 "dresser/aspect" aspect-key
                                 "dresser/version" (:version entry)
                                 "dresser/root" "/tmp/x"}]]
    (testing aspect-key
      (doseq [{:keys [attributes]} (vals strands)]
        (is (= expected-common (select-keys attributes (keys expected-common)))))
      (is (str/includes? (get-in strands [:inspect :attributes "workflow/instruction"])
                         "Target root: /tmp/x"))
      (doseq [{:keys [id templates]} (:setup entry)]
        (let [instruction (get-in strands [id :attributes "workflow/instruction"])]
          (is (str/includes? instruction "Target root: /tmp/x"))
          (doseq [template-key templates]
            (is (str/includes? instruction template-key)))))
      (is (= "conflict-policy"
             (get-in strands [:conflict :attributes "workflow/decision-point"])))
      (doseq [{:keys [id argv timeout-secs]} (:gates entry)]
        (is (= {"workflow/gate" "shell"
                "shell/argv" argv
                "shell/cwd" "/tmp/x"
                "shell/timeout-secs" timeout-secs}
               (select-keys (get-in strands [id :attributes])
                            ["workflow/gate"
                             "shell/argv"
                             "shell/cwd"
                             "shell/timeout-secs"])))))))

(deftest flavour-workflow-describes-full-and-selected-aspects
  (let [constructor (dresser-workflows/flavour-workflow "spool-repo")
        full-params {:root "/tmp/x"}
        full (workflow/describe (constructor full-params) full-params)
        subset-params {:root "/tmp/x"
                       :aspects ["spool-repo/skein-workspace"]}
        subset (workflow/describe (constructor subset-params) subset-params)
        verify-params (assoc subset-params :verify-only true)
        verify (workflow/describe (constructor verify-params) verify-params)
        full-gates (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                         (:steps full))
        subset-gates (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                           (:steps subset))
        expected-full (into #{}
                            (mapcat (fn [aspect-key]
                                      (let [prefix (second (str/split aspect-key #"/" 2))]
                                        (map #(keyword (str prefix "--" (name (:id %))))
                                             (:gates (aspects/aspect aspect-key))))))
                            (aspects/flavour-aspects "spool-repo"))]
    (is (= expected-full full-gates))
    (is (= #{:skein-workspace--workspace-files} subset-gates))
    (is (= #{:skein-workspace--workspace-files :skein-workspace}
           (set (map :id (:steps verify)))))
    (is (= #{:skein-workspace--inspect
             :skein-workspace--conflict
             :skein-workspace--write-workspace
             :skein-workspace--workspace-files
             :skein-workspace}
           (set (map :id (:steps subset)))))
    (let [payload (workflow/compile (constructor subset-params) subset-params)
          root (first (:strands payload))
          gate (some #(when (= :skein-workspace--workspace-files (:ref %)) %)
                     (:strands payload))]
      (is (= {"dresser/flavour" "spool-repo"
              "dresser/root" "/tmp/x"}
             (select-keys (:attributes root)
                          ["dresser/flavour" "dresser/root"])))
      (is (= "/tmp/x" (get-in gate [:attributes "shell/cwd"]))))))

(deftest topology-is-deterministic-and-release-is-pinned
  (let [topology (dresser-workflows/describe-topology)]
    (is (= (sort (keys aspects/registry)) (vec (keys topology))))
    (doseq [[_ modes] topology]
      (is (= #{:setup :verify-only} (set (keys modes))))
      (is (seq (get-in modes [:setup :steps])))
      (is (every? #(= "step" (:kind %))
                  (get-in modes [:verify-only :steps]))))
    (is (= (aspects/releases aspects/release-version)
           (aspects/fingerprint topology))
        "bump the aspect version AND release-version, then re-pin releases")))

(deftest dresser-workflows-register-stable-names
  (is (= (set (keys dresser-workflows/workflow-definitions))
         (set (keys (dresser-workflows/register-workflows!)))))
  (is (= 10 (count dresser-workflows/workflow-definitions))))

(defn- temp-directory ^Path []
  (Files/createTempDirectory "dresser-test-" (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       ~@body
       (finally (delete-tree! ~binding)))))

(defn- git-root! ^Path [^Path parent name]
  (let [root (Files/createDirectory (.resolve parent name)
                                    (make-array FileAttribute 0))]
    (Files/createDirectory (.resolve root ".git") (make-array FileAttribute 0))
    root))

(deftest target-root-resolution-is-canonical-and-fail-loud
  (with-temp-dir [parent]
    (let [root (git-root! parent "repo")
          link (.resolve parent "linked-repo")]
      (Files/createSymbolicLink link root (make-array FileAttribute 0))
      (is (= (str (.toRealPath root (make-array java.nio.file.LinkOption 0)))
             (target/resolve-root link))))
    (let [missing (.resolve parent "missing")
          regular-file (.resolve parent "file")
          non-git (Files/createDirectory (.resolve parent "plain")
                                         (make-array FileAttribute 0))]
      (spit (.toFile regular-file) "not a directory")
      (doseq [[path reason] [[missing :missing]
                             [regular-file :not-directory]
                             [non-git :not-git-root]]]
        (testing (name reason)
          (let [data (thrown-data #(target/resolve-root path))]
            (is (= (str path) (:path data)))
            (is (= reason (:reason data)))))))))

(deftest dresser-run-identities-are-stable-and-separated
  (with-temp-dir [parent]
    (let [root-a (git-root! parent "alpha")
          root-b (git-root! parent "beta")
          setup (target/run-id "spool-repo" root-a)
          verify (target/verify-run-id "spool-repo" root-a)]
      (is (= setup (target/run-id "spool-repo" root-a)))
      (is (re-matches #"dresser-spool-repo-alpha-[0-9a-f]{8}" setup))
      (is (re-matches #"dresser-verify-spool-repo-alpha-[0-9a-f]{8}" verify))
      (is (not= setup verify))
      (is (not= setup (target/run-id "skein-dir" root-a)))
      (is (not= setup (target/run-id "spool-repo" root-b))))))

(deftest receipt-codec-round-trips-and-rejects-invalid-data
  (with-temp-dir [root]
    (let [value {:dresser/release 1
                 :dresser/fingerprint "abc"
                 :aspects {"spool-repo/repo-skeleton"
                           {:version 1 :release 1 :applied-at "2026-07-14"}}}]
      (is (nil? (receipt/read-receipt root)))
      (is (= value (receipt/write-receipt! root value)))
      (is (= value (receipt/read-receipt root))))
    (spit (io/file (.toFile root) ".skein" "conventions.edn") "[")
    (is (= :invalid-edn (:reason (thrown-data #(receipt/read-receipt root)))))
    (spit (io/file (.toFile root) ".skein" "conventions.edn") "[]")
    (is (= :not-a-map (:reason (thrown-data #(receipt/read-receipt root)))))))

(deftest failed-atomic-receipt-move-preserves-previous-file
  (with-temp-dir [root]
    (let [previous {:dresser/release 1 :aspects {}}
          replacement {:dresser/release 2 :aspects {}}
          write-with-move (ns-resolve 'skein.spools.dresser.receipt
                                      'write-receipt-with-move!)]
      (receipt/write-receipt! root previous)
      (is (thrown? RuntimeException
                   (write-with-move root replacement
                                    (fn [& _]
                                      (throw (RuntimeException. "move failed"))))))
      (is (= previous (receipt/read-receipt root)))
      (is (= #{"conventions.edn"}
             (set (map #(.getName %) (.listFiles (io/file (.toFile root) ".skein")))))))))

(deftest merge-aspect-records-explicit-provenance
  (is (= {:dresser/release 3
          :dresser/fingerprint "feed"
          :aspects {"spool-repo/repo-skeleton"
                    {:version 2 :release 3 :applied-at "2026-07-14"}}}
         (receipt/merge-aspect nil
                               "spool-repo/repo-skeleton"
                               {:version 2}
                               3
                               "feed"
                               "2026-07-14"))))

(deftest receipt-plan-classification-matrix
  (let [registry {:release 2
                  :fingerprint "release-two"
                  :releases {1 "release-one" 2 "release-two"}
                  :aspects {"a" 2 "b" 1}}
        classify #(receipt/plan-classification % registry)
        stamped (fn [release fingerprint aspects]
                  {:dresser/release release
                   :dresser/fingerprint fingerprint
                   :aspects aspects})]
    (is (= {"a" :new "b" :new} (classify nil)) "absent receipt")
    (is (= :pending
           (get (classify (stamped 1 "release-one" {"a" {:version 1}})) "a"))
        "aspect version behind")
    (is (= :current
           (get (classify (stamped 1 "release-one" {"a" {:version 2}})) "a"))
        "equal version and known matching lineage")
    (is (= :divergent
           (get (classify (stamped 1 "forked-release-one" {"a" {:version 2}})) "a"))
        "equal version and same-integer fork")
    (is (= :divergent
           (get (classify (stamped 0 "unknown" {"a" {:version 2}})) "a"))
        "equal version and unknown release")
    (is (= :ahead
           (get (classify (stamped 2 "release-two" {"a" {:version 3}})) "a"))
        "aspect version ahead")
    (is (= :ahead
           (get (classify (stamped 3 "future" {"a" {:version 2}})) "a"))
        "receipt release ahead")
    (is (= :removed
           (get (classify (stamped 2 "release-two" {"removed" {:version 1}}))
                "removed"))
        "receipt aspect absent from registry")))

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'skein.spools.dresser-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
