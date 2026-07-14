(ns skein.spools.dresser-test
  "Tests for the skein.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [skein.spools.dresser :as dresser]
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

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'skein.spools.dresser-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
