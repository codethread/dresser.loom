(ns skein.spools.dresser-test
  "Tests for the skein.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [skein.spools.dresser :as dresser]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser.templates :as templates]))

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

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'skein.spools.dresser-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
