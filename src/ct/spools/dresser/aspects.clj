(ns ct.spools.dresser.aspects
  "Versioned convention aspects and their material lineage data."
  (:require [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [ct.spools.dresser.specs :as specs]
            [ct.spools.dresser.templates :as templates])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def release-version
  "Monotonic release for the complete aspect registry."
  5)

(def releases
  "Published release fingerprints. Historical entries are immutable."
  {1 "03cc25f420a0ef5b961d909205af6c0f2990819f0d858c6797a58ce1390ae498"
   2 "13bc922fb126113db697af8bf825c83fe0908a92536e7e7d9c983f6d39d282b3"
   3 "3241b836a15e41a30428db2d09df9b24a4570abb1f273c50f898dc2b19ca1f89"
   4 "8a0623a366b78d71a5dce9304a82904b38ad80f9454a54550d4c385bfb39f036"
   5 "c65d4c1710f6ec952da6afb57bda81e0b8ae0ee663c33a33ef7954963f800516"})

(def ^:private conflict-discipline
  "Honor the recorded conflict decisions for every owned file: keep preserves the customization, merge reconciles it with the canonical template, and replace uses the canonical template.")

(defn- setup [id title instruction template-keys]
  {:id id
   :title title
   :instruction (str instruction " " conflict-discipline)
   :templates template-keys})

(def registry
  "The seven versioned dresser aspects, keyed by <flavour>/<aspect>."
  {"spool-repo/repo-skeleton"
   {:version 3
    :deps []
    :owned ["deps.edn"
            "src/ct/spools/<name>.clj"
            "test/ct/spools/<name>_test.clj"
            "README.md"
            ".gitignore"]
    :inspect "Compare every owned file with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-deps "Write deps.edn"
                   "Converge deps.edn using template spool-repo/deps.edn."
                   ["spool-repo/deps.edn"])
            (setup :write-src-test "Write source and test"
                   "Converge the source and fail-loud test namespaces using templates spool-repo/src-ns.clj and spool-repo/test-main.clj."
                   ["spool-repo/src-ns.clj" "spool-repo/test-main.clj"])
            (setup :write-readme "Write README"
                   "Converge README.md using template spool-repo/readme."
                   ["spool-repo/readme"])
            (setup :write-gitignore "Write gitignore"
                   "Converge .gitignore using template spool-repo/gitignore."
                   ["spool-repo/gitignore"])]
    :gates [{:id :test-suite
             :title "Run test suite"
             :argv ["clojure" "-M:test"]
             :timeout-secs 600}
            {:id :readme-sections
             :title "Check README sections"
             :argv ["sh" "-c" "grep -q '## Prerequisites' README.md && grep -q '## Dependency information' README.md && grep -q '## Activation' README.md"]
             :timeout-secs 30}]}

   "spool-repo/skein-workspace"
   {:version 3
    :deps []
    :owned [".skein/config.json" ".skein/spools.edn" ".skein/init.clj" ".skein/.gitignore"]
    :inspect "Compare the .skein bootstrap quartet with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-workspace "Write Skein workspace"
                   "Converge the bootstrap quartet using templates skein/config.json, skein/spools.edn, skein/init-minimal.clj, and skein/gitignore."
                   ["skein/config.json" "skein/spools.edn" "skein/init-minimal.clj" "skein/gitignore"])]
    :gates [{:id :workspace-files
             :title "Check workspace files"
             :argv ["sh" "-c" "test -f .skein/init.clj && test -f .skein/spools.edn && test -f .skein/.gitignore && grep -q configFormat .skein/config.json"]
             :timeout-secs 30}]}

   "spool-repo/agent-docs"
   {:version 1
    :deps []
    :owned ["AGENTS.md"]
    :inspect "Compare AGENTS.md with the canonical agent guidance, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-agents-md "Write AGENTS.md"
                   "Converge AGENTS.md using template spool-repo/agents.md."
                   ["spool-repo/agents.md"])]
    :gates [{:id :agents-md
             :title "Check AGENTS.md"
             :argv ["sh" "-c" "grep -q 'mill:skein-prime' AGENTS.md && test $(wc -l < AGENTS.md) -le 70"]
             :timeout-secs 30}]}

   "spool-repo/quality"
   {:version 2
    :deps ["spool-repo/repo-skeleton"]
    :owned [".cljfmt.edn" ".splint.edn" "deps.edn" "Makefile"]
    :inspect "Compare the quality configuration, deps.edn aliases, and Makefile with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-quality-config "Write quality config"
                   "Converge .cljfmt.edn, .splint.edn, and deps.edn quality aliases using templates spool-repo/cljfmt.edn, spool-repo/splint.edn, and spool-repo/quality-aliases.edn."
                   ["spool-repo/cljfmt.edn" "spool-repo/splint.edn" "spool-repo/quality-aliases.edn"])
            (setup :write-makefile "Write Makefile"
                   "Converge Makefile using template spool-repo/makefile."
                   ["spool-repo/makefile"])]
    :gates [{:id :fmt-check
             :title "Check formatting"
             :argv ["make" "fmt-check"]
             :timeout-secs 300}
            {:id :lint
             :title "Run linters"
             :argv ["make" "lint"]
             :timeout-secs 600}]}

   "skein-dir/workspace"
   {:version 3
    :deps []
    :owned [".skein/config.json" ".skein/spools.edn" ".skein/init.clj" ".skein/.gitignore"]
    :inspect "Compare the self-contained .skein workspace with the layered canonical templates, record richer existing files as conflicts, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-workspace "Write layered workspace"
                   "Converge the workspace using templates skein/config.json, skein/spools.edn, skein/init-layered.clj, and skein/gitignore."
                   ["skein/config.json" "skein/spools.edn" "skein/init-layered.clj" "skein/gitignore"])]
    :gates [{:id :workspace-files
             :title "Check workspace files"
             :argv ["sh" "-c" "test -f .skein/init.clj && test -f .skein/spools.edn && test -f .skein/.gitignore && grep -q configFormat .skein/config.json"]
             :timeout-secs 30}
            {:id :init-header
             :title "Check init header"
             :argv ["sh" "-c" "head -20 .skein/init.clj | grep -qi 'startup entrypoint'"]
             :timeout-secs 30}]}

   "skein-dir/quality"
   {:version 2
    :deps ["skein-dir/workspace"]
    :owned [".skein/deps.edn" ".skein/Makefile"]
    :inspect "Compare workspace-local quality tooling with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-quality-tooling "Write quality tooling"
                   "Converge .skein/deps.edn and .skein/Makefile using templates skein-dir/deps.edn and skein-dir/makefile."
                   ["skein-dir/deps.edn" "skein-dir/makefile"])]
    :gates [{:id :fmt-check
             :title "Check formatting"
             :argv ["make" "-C" ".skein" "fmt-check"]
             :timeout-secs 300}
            {:id :lint
             :title "Run linter"
             :argv ["make" "-C" ".skein" "lint"]
             :timeout-secs 600}]}

   "skein-dir/agent-docs"
   {:version 1
    :deps ["skein-dir/workspace"]
    :owned [".skein/AGENTS.md" ".skein/CLAUDE.md"]
    :inspect "Compare workspace-local agent guidance with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-agent-docs "Write agent docs"
                   "Converge .skein/AGENTS.md and .skein/CLAUDE.md using templates skein-dir/agents.md and skein-dir/claude.md."
                   ["skein-dir/agents.md" "skein-dir/claude.md"])]
    :gates [{:id :agent-docs-files
             :title "Check agent docs"
             :argv ["sh" "-c" "test -f .skein/AGENTS.md && test -f .skein/CLAUDE.md && grep -q 'AGENTS.md' .skein/CLAUDE.md"]
             :timeout-secs 30}]}})

(defn- validated-registry []
  (spool/require-valid! ::specs/registry registry
                        "Dresser aspect registry has an invalid shape"))

(defn aspect
  "Return an aspect definition, failing loudly when key is unknown."
  [key]
  (or (get (validated-registry) key)
      (spool/fail! "Unknown dresser aspect"
                   {:aspect key :known (set (keys registry))})))

(defn- key-flavour [key]
  (first (str/split key #"/" 2)))

(defn- ordered-selection [flavour selected]
  (let [available (set (for [key (keys registry) :when (= flavour (key-flavour key))] key))]
    (when (empty? available)
      (spool/fail! "Unknown dresser flavour"
                   {:flavour flavour :known #{"spool-repo" "skein-dir"}}))
    (doseq [key selected]
      (when-not (contains? available key)
        (spool/fail! "Unknown dresser aspect for flavour"
                     {:flavour flavour :aspect key :known available})))
    (let [state (atom {})
          result (transient [])]
      (letfn [(visit [key trail]
                (case (get @state key)
                  :done nil
                  :visiting (spool/fail! "Cycle in dresser aspect dependencies"
                                         {:flavour flavour :cycle (conj trail key)})
                  (do
                    (swap! state assoc key :visiting)
                    (doseq [dep (:deps (aspect key))]
                      (when-not (contains? available dep)
                        (spool/fail! "Unknown or cross-flavour aspect dependency"
                                     {:flavour flavour :aspect key :dependency dep}))
                      (visit dep (conj trail key)))
                    (swap! state assoc key :done)
                    (conj! result key))))]
        (doseq [key (sort selected)] (visit key []))
        (persistent! result)))))

(defn flavour-aspects
  "Return all aspects for flavour in deterministic dependency order."
  [flavour]
  (validated-registry)
  (ordered-selection flavour
                     (for [key (keys registry) :when (= flavour (key-flavour key))] key)))

(defn close-under-deps
  "Return selected full aspect keys, closed under dependencies and ordered."
  [flavour keys]
  (validated-registry)
  (ordered-selection flavour keys))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonical item)]))
                       value)
    (set? value) (mapv canonical (sort-by pr-str value))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- template-contents []
  (into (sorted-map)
        (map (fn [[key entry]]
               [key (if (fn? entry) (entry {:name "<name>"}) entry)]))
        templates/templates))

(defn material-data
  "Return canonical material inputs for the current release plus workflow topology."
  [topology]
  (validated-registry)
  (canonical
   {:aspects
    (into (sorted-map)
          (map (fn [[key {:keys [version deps owned inspect setup gates]}]]
                 [key {:version version
                       :deps deps
                       :owned owned
                       :inspect inspect
                       :setup (mapv #(select-keys % [:instruction :templates]) setup)
                       :gates (mapv #(select-keys % [:argv :timeout-secs]) gates)}]))
          registry)
    :templates (template-contents)
    :topology topology}))

(defn fingerprint
  "Return the lowercase hexadecimal SHA-256 of canonical material data."
  [topology]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (material-data topology)) StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) digest))))
