(ns ct.spools.dresser-test
  "Tests for the ct.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :as spool]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [ct.spools.dresser :as dresser]
            [ct.spools.dresser.aspects :as aspects]
            [ct.spools.dresser-edges-test]
            [ct.spools.dresser-fixtures :as fixtures]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.specs :as specs]
            [ct.spools.dresser.target :as target]
            [ct.spools.dresser.templates :as templates]
            [ct.spools.dresser.workflows :as dresser-workflows]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as t])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(deftest dresser-exports-the-spool-lifecycle-surface
  (is (ifn? dresser/contribute))
  (is (ifn? dresser/reconcile))
  (is (= {:contribute 'contribute
          :reconcile 'reconcile}
         dresser/spool)))

(defn- with-runtime [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (fixtures/activate-serial-shell! (:runtime ctx))
         (f (:runtime ctx) (:config-dir ctx))))))

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

(defn- thrown-exception [f]
  (try
    (f)
    nil
    (catch Throwable exception
      exception)))

(deftest advance-input-keys-fail-with-an-actionable-error
  (let [keywordize-input (ns-resolve 'ct.spools.dresser 'keywordize-input)
        exception (thrown-exception #(keywordize-input {42 "invalid"}))]
    (is (= "Dresser advance input keys must be keywords, strings, or symbols"
           (ex-message exception)))
    (is (= [42] (:invalid-keys (ex-data exception))))
    (is (= #{:keyword :string :symbol}
           (:allowed-key-types (ex-data exception))))))

(deftest v1-template-registry-is-complete
  (is (= expected-template-names (set (keys templates/templates)))))

(deftest parameterized-templates-render-name
  (doseq [template-name parameterized-template-names]
    (testing template-name
      (let [content (templates/template template-name {:name "acme"})]
        (is (str/includes? content "acme"))
        (is (not (str/includes? content "<name>"))))))
  (is (str/includes? (templates/template "spool-repo/deps.edn" {:name "acme"})
                     "ct.spools.acme-test")))

(deftest static-reference-template-is-exact
  (is (= "{\"configFormat\":\"alpha\"}\n"
         (templates/template "skein/config.json")))
  (is (str/includes? (templates/template "skein/gitignore") ".cpcache/\n")))

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

(deftest aspect-registry-is-valid
  (is (= (set (keys expected-aspect-ids)) (set (keys aspects/registry))))
  (is (= {"spool-repo/repo-skeleton" 3
          "spool-repo/skein-workspace" 3
          "spool-repo/agent-docs" 1
          "spool-repo/quality" 2
          "skein-dir/workspace" 3
          "skein-dir/quality" 2
          "skein-dir/agent-docs" 1}
         (into {} (map (fn [[key entry]] [key (:version entry)])) aspects/registry)))
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
    (workflow/describe (dresser-workflows/aspect-workflow aspect-key) params)))

(defn- expected-aspect-dependencies [entry]
  (let [setup-ids (mapv :id (:setup entry))
        setup-dependencies (map vector (cons :conflict setup-ids))
        gate-ids (mapv :id (:gates entry))
        gate-dependencies (map vector
                               (cons (or (peek setup-ids) :conflict) gate-ids))]
    (into {:inspect [] :conflict [:inspect]}
          (concat (map vector setup-ids setup-dependencies)
                  (map vector gate-ids gate-dependencies)))))

(defn- expected-verify-dependencies [gate-ids]
  (into {}
        (map vector gate-ids (cons [] (map vector gate-ids)))))

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
        (is (= "checkpoint" (:role (second setup-steps))))
        (is (= (set gate-ids)
               (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                     setup-steps)))
        (is (= gate-ids (mapv :id verify-steps)))
        (is (= (expected-verify-dependencies gate-ids)
               (into {} (map (juxt :id :depends-on)) verify-steps)))
        (is (every? #(= "shell" (:gate %)) verify-steps))))))

(deftest conflict-checkpoint-declares-policy-inputs
  (let [checkpoint (-> (aspect-description "spool-repo/repo-skeleton" false)
                       :steps
                       second)
        choices (into {} (map (juxt :key identity)) (:choices checkpoint))]
    (is (= "conflict" (name (:id checkpoint))))
    (is (= #{"clean" "apply-plan" "abort"} (set (keys choices))))
    (is (nil? (:input-spec (choices "clean"))))
    (is (= {"spec" "ct.spools.dresser.specs/conflict-decisions-input"
            "doc" "Summary of per-file keep/merge/replace decisions."}
           (:input-spec (choices "apply-plan"))))
    (is (= ":dresser/abort" (:next (choices "abort"))))
    ;; The abort choice and the workflow it routes to answer to one spec, so a
    ;; reason the checkpoint accepts is a reason the continuation can start on.
    (is (= {"spec" "ct.spools.dresser.specs/abort-workflow-input"
            "doc" "Why convention convergence was aborted."}
           (:input-spec (choices "abort"))))))

(defn- compiled-step-map [definition params]
  (let [payload (workflow/compile definition params)]
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
        (is (= {"dresser/gate-id" (name id)
                "workflow/gate" "shell"
                "shell/argv" argv
                "shell/cwd" "/tmp/x"
                "shell/timeout-secs" timeout-secs}
               (select-keys (get-in strands [id :attributes])
                            ["dresser/gate-id"
                             "workflow/gate"
                             "shell/argv"
                             "shell/cwd"
                             "shell/timeout-secs"])))))))

;; Umbrellas reach their aspects by registered name, so describing one needs the
;; live registry the module publishes — unlike an aspect definition, which
;; carries no calls and describes anywhere.
(deftest flavour-workflow-describes-full-and-selected-aspects
  (with-runtime
    (fn [runtime _]
      (fixtures/activate-dresser! runtime)
      (let [params {:root "/tmp/x"}
            full (workflow/describe :dresser/spool-repo params)
            subset-definition (dresser-workflows/flavour-workflow
                               "spool-repo" ["spool-repo/skein-workspace"])
            subset (workflow/describe subset-definition params)
            verify (workflow/describe subset-definition
                                      (assoc params :verify-only true))
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
        (let [payload (workflow/compile subset-definition params)
              root (first (:strands payload))
              gate (some #(when (= :skein-workspace--workspace-files (:ref %)) %)
                         (:strands payload))]
          (is (= {"dresser/flavour" "spool-repo"
                  "dresser/root" "/tmp/x"}
                 (select-keys (:attributes root)
                              ["dresser/flavour" "dresser/root"])))
          (is (= "/tmp/x" (get-in gate [:attributes "shell/cwd"]))))))))

(deftest topology-is-deterministic-and-release-is-pinned
  (let [topology (dresser-workflows/describe-topology)]
    (is (= (sort (keys aspects/registry)) (vec (keys topology))))
    (doseq [[_ modes] topology]
      (is (= #{:setup :verify-only} (set (keys modes))))
      (is (seq (get-in modes [:setup :steps])))
      (is (every? #(= "step" (:role %))
                  (get-in modes [:verify-only :steps]))))
    (is (= (aspects/releases aspects/release-version)
           (aspects/fingerprint topology))
        "bump the aspect version AND release-version, then re-pin releases")))

(deftest release-lineage-preserves-v1-receipt-classification
  (let [release-one "03cc25f420a0ef5b961d909205af6c0f2990819f0d858c6797a58ce1390ae498"
        registry-view {:release aspects/release-version
                       :fingerprint (aspects/releases aspects/release-version)
                       :releases aspects/releases
                       :aspects (into {} (map (fn [[key entry]]
                                                [key (:version entry)]))
                                      aspects/registry)}
        receipt {:dresser/release 1
                 :dresser/fingerprint release-one
                 :aspects {"spool-repo/repo-skeleton"
                           {:version 1 :release 1 :applied-at "2026-07-14"}
                           "spool-repo/agent-docs"
                           {:version 1 :release 1 :applied-at "2026-07-14"}}}
        classification (receipt/plan-classification receipt registry-view)]
    (is (= release-one (get aspects/releases 1)))
    (is (= :pending (get classification "spool-repo/repo-skeleton")))
    (is (= :current (get classification "spool-repo/agent-docs")))))

(defn- registered-definition [wf-name]
  (some-> (dresser-workflows/workflow-definitions wf-name)
          requiring-resolve
          deref))

(deftest dresser-workflows-declare-stable-resolvable-names
  (is (= 10 (count dresser-workflows/workflow-definitions)))
  (doseq [aspect-key (keys aspects/registry)]
    (is (contains? dresser-workflows/workflow-definitions
                   (dresser-workflows/registered-name aspect-key))
        aspect-key))
  (doseq [wf-name (keys dresser-workflows/workflow-definitions)]
    (let [definition (registered-definition wf-name)]
      (is (map? definition) (str wf-name))
      (is (seq (:steps definition)) (str wf-name))
      (is (specs/non-blank-string? (:doc definition)) (str wf-name))
      (is (qualified-keyword? (:param-spec definition)) (str wf-name)))))

(deftest dresser-workflow-entrypoints-match-how-each-name-is-reached
  ;; Umbrellas begin runs, the abort stage is only ever routed to, and an aspect
  ;; is only ever expanded inline by its umbrella.
  (is (= #{:start} (:entrypoints (registered-definition :dresser/spool-repo))))
  (is (= #{:start} (:entrypoints (registered-definition :dresser/skein-dir))))
  (is (= #{:continue} (:entrypoints (registered-definition :dresser/abort))))
  (doseq [aspect-key (keys aspects/registry)]
    (is (= #{:call}
           (:entrypoints (registered-definition
                          (dresser-workflows/registered-name aspect-key))))
        aspect-key)))

(defn- temp-directory ^Path []
  (Files/createTempDirectory "dresser-test-" (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       (do ~@body)
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
            (is (= reason (:reason data))))))
      (let [exception (thrown-exception #(target/resolve-root (str "bad" \u0000 "path")))
            data (ex-data exception)]
        (is (= :unresolvable (:reason data)))
        (is (= "java.nio.file.InvalidPathException" (:cause-type data)))
        (is (str/includes? (:cause-message data) "Nul character"))
        (is (= [:exists :directory :git-worktree-root]
               (:allowed-root-constraints data)))
        (is (instance? java.nio.file.InvalidPathException (.getCause exception)))))))

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
    (let [data (thrown-data #(receipt/read-receipt root))]
      (is (= :invalid-shape (:reason data)))
      (is (map? (:explain data))))))

(deftest malformed-receipt-plan-input-yields-structured-spec-error
  (let [malformed {:dresser/fingerprint "release-one"
                   :aspects {"spool-repo/a"
                             {:version 1 :release 1 :applied-at "2026-07-14"}}}
        registry {:release 1
                  :fingerprint "release-one"
                  :releases {1 "release-one"}
                  :aspects {"spool-repo/a" 1}}
        exception (thrown-exception #(receipt/plan-classification malformed registry))]
    (is (instance? clojure.lang.ExceptionInfo exception))
    (is (= malformed (:value (ex-data exception))))
    (is (map? (:explain (ex-data exception))))))

(deftest failed-atomic-receipt-move-preserves-previous-file
  (with-temp-dir [root]
    (let [previous {:dresser/release 1 :dresser/fingerprint "one" :aspects {}}
          replacement {:dresser/release 2 :dresser/fingerprint "two" :aspects {}}
          write-with-move (ns-resolve 'ct.spools.dresser.receipt
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
                  :aspects {"spool-repo/a" 2 "spool-repo/b" 1}}
        classify #(receipt/plan-classification % registry)
        stamped (fn [release fingerprint aspects]
                  {:dresser/release release
                   :dresser/fingerprint fingerprint
                   :aspects aspects})]
    (is (= {"spool-repo/a" :new "spool-repo/b" :new} (classify nil))
        "absent receipt")
    (is (= :pending
           (get (classify (stamped 1 "release-one"
                                   {"spool-repo/a" {:version 1
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "aspect version behind")
    (is (= :current
           (get (classify (stamped 1 "release-one"
                                   {"spool-repo/a" {:version 2
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and known matching lineage")
    (is (= :divergent
           (get (classify (stamped 1 "forked-release-one"
                                   {"spool-repo/a" {:version 2
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and same-integer fork")
    (is (= :divergent
           (get (classify (stamped 0 "unknown"
                                   {"spool-repo/a" {:version 2
                                                    :release 0
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and unknown release")
    (is (= :ahead
           (get (classify (stamped 2 "release-two"
                                   {"spool-repo/a" {:version 3
                                                    :release 2
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "aspect version ahead")
    (is (= :ahead
           (get (classify (stamped 3 "future"
                                   {"spool-repo/a" {:version 2
                                                    :release 3
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "receipt release ahead")
    (is (= :removed
           (get (classify (stamped 2 "release-two"
                                   {"spool-repo/removed"
                                    {:version 1
                                     :release 2
                                     :applied-at "2026-07-14"}}))
                "spool-repo/removed"))
        "receipt aspect absent from registry")))

(deftest unsupported-dresser-subcommand-fails-with-alternatives
  (let [exception (thrown-exception
                   #(dresser/dresser-op {:op/args {:subcommand ["explode"]}}))
        data (ex-data exception)]
    (is (= "Unsupported dresser subcommand" (ex-message exception)))
    (is (= ["explode"] (:subcommand data)))
    (is (= #{["about"] ["aspects"] ["template"] ["plan"] ["start"] ["verify"]
             ["next"] ["advance"] ["stamp"]}
           (set (:allowed data))))))

(defn- git-init-root! ^Path [^Path parent name]
  (let [root (.resolve parent name)
        {:keys [exit err]} (sh/sh "git" "init" "--quiet" (str root))]
    (when-not (zero? exit)
      (throw (ex-info "git init failed" {:root (str root) :exit exit :stderr err})))
    root))

(deftest activation-is-idempotent-and-declares-the-complete-op-surface
  (with-runtime
    (fn [runtime _]
      (let [first-activation (fixtures/activate-dresser! runtime)
            second-activation (fixtures/activate-dresser! runtime)
            op (weaver/resolve-op runtime 'dresser)
            subcommands (get-in op [:arg-spec :subcommands])
            help (weaver/op! runtime 'help ["dresser"])
            declaration (->> (vocab/declarations runtime {:kind :attr-namespace})
                             (filter #(= "dresser" (:name %)))
                             first)]
        (is (= :applied (get-in first-activation [:modules :dresser :status])))
        (is (= :unchanged (get-in second-activation [:modules :dresser :status])))
        (is (= #{"about" "aspects" "template" "plan" "start" "verify"
                 "next" "advance" "stamp"}
               (set (keys subcommands))))
        (is (= #{"about" "aspects" "template" "plan" "start" "verify"
                 "next" "advance" "stamp"}
               (set (map :name (get-in help [:node :children])))))
        (is (= {"about" :read "aspects" :read "template" :read "plan" :read
                "start" :mutating "verify" :mutating "next" :read
                "advance" :mutating "stamp" :mutating}
               (update-vals subcommands :hook-class)))
        (is (every? #(= :standard (:deadline-class %)) (vals subcommands)))
        (is (= ["dresser/flavour" "dresser/aspect" "dresser/version" "dresser/root"
                "dresser/gate-id"]
               (:keys declaration)))
        (is (= (set (keys dresser-workflows/workflow-definitions))
               (set (keys (workflow/workflows)))))))))

(deftest contribute-publishes-owner-complete-partitions
  (let [contribution (dresser/contribute {})
        entry (get-in contribution [:ops "dresser"])]
    (is (= #{workflow/definition-kind :ops} (set (keys contribution))))
    (is (= dresser-workflows/workflow-definitions
           (get contribution workflow/definition-kind)))
    (is (= "dresser" (:name entry)))
    (is (= 'ct.spools.dresser/dresser-op (:fn entry)))
    (is (= 'ct.spools.dresser (:provenance entry)))
    (is (= #{"about" "aspects" "template" "plan" "start" "verify"
             "next" "advance" "stamp"}
           (set (keys (get-in entry [:arg-spec :subcommands])))))))

(defn- with-runtime-without-executor [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (f (:runtime ctx))))))

(deftest reconcile-refuses-an-applied-contribution-without-a-shell-executor
  (with-runtime-without-executor
    (fn [rt]
      (let [data (thrown-data
                  #(dresser/reconcile {:runtime rt
                                       :module/contribution {:status :applied}}))]
        (is (= :shell (:prerequisite data)))
        (is (set? (:executors data)))))))

(deftest module-activation-fails-loudly-without-a-shell-executor
  (with-runtime-without-executor
    (fn [rt]
      (let [data (thrown-data #(fixtures/activate-dresser! rt))]
        (is (= :dresser (:module/key data)))))))

(deftest publication-refuses-a-partition-missing-a-name-its-definitions-reach
  ;; What naming aspects and the abort stage by registered keyword buys: an
  ;; owner partition that drops a name its own definitions route to or call is
  ;; refused before it goes live, instead of failing at the pour.
  (with-runtime-without-executor
    (fn [rt]
      (workflow/register-executor! :shell (constantly nil))
      (doseq [dropped [:dresser/abort :dresser/spool-repo.quality]]
        (with-redefs [dresser-workflows/workflow-definitions
                      (dissoc dresser-workflows/workflow-definitions dropped)]
          (let [data (thrown-data #(fixtures/activate-dresser! rt))
                conflict (get-in data [:result :conflicts 0 :data])]
            (is (= :dresser (:module/key data)) (str dropped))
            (is (= :refused (get-in data [:result :status])) (str dropped))
            (is (= :workflow/reference-unregistered (:reason conflict)) (str dropped))
            (is (= dropped (:target conflict)) (str dropped))))))))

(deftest reconcile-refuses-an-unsupported-contribution-status
  (let [data (thrown-data
              #(dresser/reconcile {:runtime nil
                                   :module/key :dresser
                                   :module/contribution {:status :bogus}}))]
    (is (= :bogus (:status data)))
    (is (= #{:applied :removed} (:allowed data)))
    (is (= 'ct.spools.dresser/reconcile (:reconciler data)))))

(deftest module-removal-by-omission-retracts-definitions-and-op
  ;; The deletion-by-omission proof: a full refresh re-collects from startup
  ;; files, where this imperative test declaration does not appear, so the
  ;; kernel removes the module, runs reconcile's :removed branch, and retracts
  ;; the op and every workflow definition without dresser code participating.
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "omission")]
      (with-runtime-without-executor
        (fn [runtime]
          (workflow/register-executor! :shell (constantly nil))
          (fixtures/activate-dresser! runtime)
          (weaver/op! runtime 'dresser
                      ["start" "skein-dir" (str root)
                       "--aspects" "skein-dir/agent-docs"])
          (is (= (set (keys dresser-workflows/workflow-definitions))
                 (set (keys (workflow/workflows)))))
          (let [result (runtime/refresh! runtime)]
            (is (= :removed (get-in result [:modules :dresser :status]))))
          (is (empty? (filter #(= "dresser" (namespace %))
                              (keys (workflow/workflows)))))
          (is (= 'dresser (:operation (thrown-data
                                       #(weaver/resolve-op runtime 'dresser))))))))))

(deftest read-only-ops-return-declared-shapes
  (with-runtime
    (fn [runtime _]
      (fixtures/activate-dresser! runtime)
      (let [about (weaver/op! runtime 'dresser ["about"])
            registry (weaver/op! runtime 'dresser ["aspects"])
            rendered (weaver/op! runtime 'dresser
                                 ["template" "spool-repo/deps.edn"
                                  "--param" "name=acme"])]
        (is (= #{:receipt :plan :verify :stamp} (set (keys (:semantics about)))))
        (is (= #{"spool-repo" "skein-dir"} (set (keys (:flavours about)))))
        (is (seq (:quickstart about)))
        (is (= aspects/release-version (:release registry)))
        (is (= (aspects/releases aspects/release-version) (:fingerprint registry)))
        (is (= (set (keys aspects/registry)) (set (keys (:aspects registry)))))
        (is (every? #(contains? % :gates) (vals (:aspects registry))))
        (is (= {:name "acme"} (:params rendered)))
        (is (str/includes? (:content rendered) "ct.spools.acme-test"))))))

(deftest plan-op-resolves-root-and-reports-receipt-states-and-provenance
  (with-temp-dir [parent]
    (let [root (git-init-root! parent "fresh")]
      (with-runtime
        (fn [runtime _]
          (fixtures/activate-dresser! runtime)
          (let [fresh (weaver/op! runtime 'dresser ["plan" (str root)])]
            (is (= (str (.toRealPath root (make-array java.nio.file.LinkOption 0)))
                   (:root fresh)))
            (is (= (set (keys aspects/registry)) (set (keys (:aspects fresh)))))
            (is (every? #{:new} (vals (:aspects fresh))))
            (is (= :unstamped (get-in fresh [:provenance :verdict]))))
          (let [aspect-key "spool-repo/repo-skeleton"
                aspect-version (get-in aspects/registry [aspect-key :version])
                fingerprint (aspects/releases aspects/release-version)]
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version 0
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (is (= :pending
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects aspect-key])))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version aspect-version
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (let [known (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :current (get-in known [:aspects aspect-key])))
              (is (= :known (get-in known [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint "forked"
              :aspects {aspect-key {:version aspect-version
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (let [divergent (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :divergent (get-in divergent [:aspects aspect-key])))
              (is (= :divergent (get-in divergent [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version (inc aspect-version)
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (is (= :ahead
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects aspect-key])))
            (receipt/write-receipt!
             root
             {:dresser/release (inc aspects/release-version)
              :dresser/fingerprint "future"
              :aspects {aspect-key {:version aspect-version
                                    :release (inc aspects/release-version)
                                    :applied-at "2026-07-14"}}})
            (let [ahead (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :ahead (get-in ahead [:aspects aspect-key])))
              (is (= :ahead (get-in ahead [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {"spool-repo/removed" {:version 1
                                              :release aspects/release-version
                                              :applied-at "2026-07-14"}}})
            (is (= :removed
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects "spool-repo/removed"])))))))))

(defn- with-runtime-without-shell [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (workflow/register-executor! :shell (constantly nil))
         (f (:runtime ctx))))))

(deftest lifecycle-ops-address-setup-and-verify-runs
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "lifecycle")]
      (with-runtime-without-shell
        (fn [runtime]
          (fixtures/activate-dresser! runtime)
          (let [setup (weaver/op! runtime 'dresser
                                  ["start" "skein-dir" (str root)
                                   "--aspects" "skein-dir/agent-docs"])
                setup-ready (weaver/op! runtime 'dresser
                                        ["next" "skein-dir" (str root)])]
            (is (= (:ready setup) setup-ready))
            (is (= 1 (count setup-ready)))
            (is (str/starts-with? (:title (first setup-ready)) "Inspect "))
            (is (= :active-run
                   (let [data (thrown-data
                               #(weaver/op! runtime 'dresser
                                            ["start" "skein-dir" (str root)]))]
                     (when (= (target/run-id "skein-dir" root) (:run-id data))
                       :active-run))))
            (weaver/op! runtime 'dresser
                        ["advance" "skein-dir" (str root)
                         "--step" (:id (first setup-ready))])
            (let [checkpoint (first (weaver/op! runtime 'dresser
                                                ["next" "skein-dir" (str root)]))
                  after-choice (weaver/op! runtime 'dresser
                                           ["advance" "skein-dir" (str root)
                                            "--step" (:id checkpoint)
                                            "--choice" "apply-plan"
                                            "--input" "decisions=replace"])]
              (is (= "checkpoint" (:role checkpoint)))
              (is (= "Write layered workspace"
                     (get-in after-choice [:ready 0 :title]))))
            (let [verify (weaver/op! runtime 'dresser
                                     ["verify" "skein-dir" (str root)
                                      "--aspects" "skein-dir/agent-docs"])
                  verify-ready (weaver/op! runtime 'dresser
                                           ["next" "skein-dir" (str root) "--verify"])]
              (is (= (:ready verify) verify-ready))
              (is (= #{"shell"} (set (map :gate verify-ready))))
              (is (not= (target/run-id "skein-dir" root)
                        (target/verify-run-id "skein-dir" root))))
            (is (= "unknown"
                   (:aspect (thrown-data
                             #(weaver/op! runtime 'dresser
                                          ["verify" "skein-dir" (str root)
                                           "--aspects" "unknown"])))))))))))

(deftest this-repo-passes-non-recursive-self-hosting-verification
  ;; repo-skeleton would recurse through clojure -M:test. quality and
  ;; repo-skeleton are covered directly by make fmt-check lint test instead.
  (let [root (.getCanonicalPath (io/file "."))
        selected "spool-repo/skein-workspace,spool-repo/agent-docs"
        run-id (target/verify-run-id "spool-repo" root)]
    (fixtures/with-dresser-runtime
      (fn [runtime _]
        (weaver/op! runtime 'dresser
                    ["verify" "spool-repo" root "--aspects" selected])
        (fixtures/assert-done! (fixtures/wait-for-attention! runtime run-id))
        (let [gates (filterv #(= "shell" (spool/attr-get % :workflow/gate))
                             (fixtures/latest-molecule-strands runtime run-id))]
          (is (= #{"spool-repo/skein-workspace" "spool-repo/agent-docs"}
                 (set (map #(spool/attr-get % :dresser/aspect) gates))))
          (is (every? #(= "closed" (:state %)) gates))
          (is (every? #(= "shell" (spool/attr-get % :workflow/outcome-by)) gates))
          (is (every? #(zero? (spool/attr-get % :shell/exit-code)) gates))
          (is (every? #(nil? (spool/attr-get % :gate/error)) gates)))))))

(defn- evidence-workflow [aspect-key gates]
  (apply workflow/workflow
         "Poured stamp evidence fixture"
         (map (fn [{:keys [id title]}]
                (workflow/gate id title :shell
                               :attributes {"dresser/aspect" aspect-key
                                            "dresser/gate-id" (name id)
                                            "shell/argv" ["true"]
                                            "shell/cwd" "/tmp"
                                            "shell/timeout-secs" 30}))
              gates)))

(defn- violation-types [data gate-id]
  (into #{}
        (keep #(when (= gate-id (:gate %)) (:violation %)))
        (:violations data)))

(deftest stamp-refuses-missing-expected-gate
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "missing-gate")
          aspect-key "skein-dir/workspace"
          run-id (target/run-id "skein-dir" root)
          only-gate [(first (:gates (aspects/aspect aspect-key)))]]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key only-gate) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "shell"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "init-header") :missing-gate))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-refuses-gate-id-mismatch-even-when-title-matches
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "wrong-gate-id")
          aspect-key "skein-dir/agent-docs"
          run-id (target/run-id "skein-dir" root)
          expected-gate (first (:gates (aspects/aspect aspect-key)))
          wrong-gate [(assoc expected-gate :id :old-agent-docs-files)]]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key wrong-gate) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "shell"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "agent-docs-files") :missing-gate))
            (is (contains? (violation-types data "old-agent-docs-files")
                           :unexpected-gate))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-without-setup-history-is-structured-evidence-failure
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "no-history")
          aspect-key "skein-dir/agent-docs"]
      (with-runtime-without-shell
        (fn [_]
          (let [exception (thrown-exception #(dresser/stamp! aspect-key root))
                data (ex-data exception)]
            (is (str/includes? (ex-message exception) "stamp evidence failed"))
            (is (nil? (:molecule data)))
            (is (= [{:violation :missing-molecule
                     :run-id (target/run-id "skein-dir" root)}]
                   (:violations data)))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-refuses-force-closed-gate
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "human-gate")
          aspect-key "skein-dir/agent-docs"
          run-id (target/run-id "skein-dir" root)
          gates (:gates (aspects/aspect aspect-key))]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key gates) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "human"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "agent-docs-files") :outcome-by))
            (is (= "human"
                   (:actual (some #(when (= :outcome-by (:violation %)) %)
                                  (:violations data)))))))))))

(deftest skein-dir-e2e-stamps-all-aspects-without-touching-host-tree
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "skein-dir")]
      (spit (.toFile (.resolve root "HOST.txt")) "host-owned\n")
      (let [before (fixtures/snapshot-outside-skein root)]
        (with-runtime
          (fn [runtime _]
            (fixtures/activate-dresser! runtime)
            (weaver/op! runtime 'dresser ["start" "skein-dir" (str root)])
            (let [state (fixtures/drive-skein-dir! runtime root)]
              (is (= :done (:reason state)) (pr-str state)))
            (doseq [aspect-key (aspects/flavour-aspects "skein-dir")]
              (let [result (weaver/op! runtime 'dresser
                                       ["stamp" aspect-key (str root)])]
                (is (= aspect-key (:aspect result)))
                (is (= :current (:plan result)))
                (is (re-matches #"\d{4}-\d{2}-\d{2}"
                                (get-in result [:entry :applied-at])))))
            (let [stamp (receipt/read-receipt root)
                  planned (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= aspects/release-version (:dresser/release stamp)))
              (is (= (aspects/releases aspects/release-version)
                     (:dresser/fingerprint stamp)))
              (is (= (set (aspects/flavour-aspects "skein-dir"))
                     (set (keys (:aspects stamp)))))
              (is (every? #{:current}
                          (map (:aspects planned)
                               (aspects/flavour-aspects "skein-dir")))))))
        (is (= before (fixtures/snapshot-outside-skein root)))))))

(deftest red-gate-recovery-refuses-old-green-evidence-then-stamps
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "recovery")
          aspect-key "skein-dir/workspace"]
      (with-runtime
        (fn [runtime _]
          (fixtures/activate-dresser! runtime)
          (weaver/op! runtime 'dresser
                      ["start" "skein-dir" (str root)
                       "--aspects" aspect-key])
          (let [state (fixtures/drive-skein-dir! runtime root)]
            (is (= :done (:reason state)) (pr-str state)))
          (is (= :current (:plan (dresser/stamp! aspect-key root))))
          (weaver/op! runtime 'dresser
                      ["start" "skein-dir" (str root)
                       "--aspects" aspect-key])
          (let [state (fixtures/drive-skein-dir!
                       runtime root
                       {:before-advance
                        (fn [step]
                          (when (= "Write layered workspace" (:title step))
                            (spit (io/file (str root) ".skein" "init.clj")
                                  ";; deliberately broken\n")))})
                failed-gate (:gate state)
                refusal (thrown-data #(dresser/stamp! aspect-key root))]
            (is (= :stalled (:reason state)))
            (is (= "Check init header" (:title failed-gate)))
            (is (some? (spool/attr-get failed-gate :gate/error)))
            (is (contains? (violation-types refusal "init-header") :gate-error))
            (fixtures/write-step-files! root "Write layered workspace")
            ;; The executor re-arms on gate/error absence, so the clear is a
            ;; nil-patch removal, not a blank overwrite.
            (weaver/update! runtime (:id failed-gate)
                            {:attributes {"gate/error" nil}})
            (is (= :done (:reason (fixtures/drive-skein-dir! runtime root))))
            (is (= :current (:plan (dresser/stamp! aspect-key root))))))))))

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'ct.spools.dresser-test
                           'ct.spools.dresser-edges-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
