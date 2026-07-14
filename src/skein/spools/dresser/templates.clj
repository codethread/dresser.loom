(ns skein.spools.dresser.templates
  "Canonical source-data templates for dresser's v1 convention aspects."
  (:require [skein.api.spool.alpha :as spool]
            [skein.spools.dresser.specs :as specs]))

(def skein-config-json
  "{\"configFormat\":\"alpha\"}\n")

(def skein-spools-edn
  "{:spools {}}\n")

(def skein-init-minimal
  (str "(require '[skein.api.current.alpha :as current]\n"
       "         '[skein.api.runtime.alpha :as runtime])\n"
       "\n"
       "(def runtime (current/runtime))\n"
       "\n"
       "(runtime/sync! runtime)\n"
       "(runtime/use! runtime :skein/spools-batteries\n"
       "              {:ns 'skein.spools.batteries\n"
       "               :call 'skein.spools.batteries/activate!})\n"))

(def skein-init-layered
  (str ";; Startup entrypoint for this repo's coordination world. Keep one file per concern:\n"
       ";;   config.clj     — named queries and the CLI op surface\n"
       ";;   workflows.clj  — hand-authored workflows\n"
       ";;   harnesses.clj  — harness seats and routing policy\n"
       ";;   reviewers.clj  — reviewer rosters\n"
       ";; Add each concern's activation below in dependency order. Reload config changes instead\n"
       ";; of restarting the weaver, and smoke-test them in a disposable world first.\n"
       skein-init-minimal))

(def skein-gitignore
  (str ".cpcache/\n"
       "config.local.json\n"
       "init.local.clj\n"
       "spools.local.edn\n"
       "state/\n"
       "data/\n"
       "weaver.*\n"
       "*.sqlite\n"
       "*.sqlite-*\n"))

(defn- require-name [{:keys [name] :as params} template-name]
  (when-not (specs/non-blank-string? name)
    (spool/fail! "Template requires a non-blank :name"
                 {:template template-name
                  :required :name
                  :params params}))
  name)

(defn spool-repo-deps-edn [params]
  (let [name (require-name params "spool-repo/deps.edn")]
    (str "{:paths [\"src\"]\n"
         " :aliases\n"
         " {:test {:extra-paths [\"test\"]\n"
         "         ;; io.skein/skein exposes only Skein's base classpath (src/, batteries).\n"
         "         ;; The workflow engine and its shell executor live in a spool root off\n"
         "         ;; that classpath and join the test JVM as their own dep.\n"
         "         :extra-deps {io.skein/skein {:local/root \"../skein-src\"}\n"
         "                      io.skein/workflow-spool {:local/root \"../skein-src/spools/workflow\"}}\n"
         "         :jvm-opts [\"--enable-native-access=ALL-UNNAMED\"]\n"
         "         :main-opts [\"-m\" \"skein.spools." name "-test\"]}}}\n")))

(defn spool-repo-src-ns [params]
  (let [name (require-name params "spool-repo/src-ns.clj")]
    (str "(ns skein.spools." name "\n"
         "  \"Shared spool implementation.\")\n")))

(defn spool-repo-test-main [params]
  (let [name (require-name params "spool-repo/test-main.clj")]
    (str "(ns skein.spools." name "-test\n"
         "  \"Tests for the skein.spools." name " shared spool.\"\n"
         "  (:require [clojure.test :refer [run-tests]]\n"
         "            [skein.spools." name "]))\n"
         "\n"
         "(defn -main\n"
         "  \"Run the standalone " name " spool test suite.\"\n"
         "  [& _args]\n"
         "  (let [summary (run-tests 'skein.spools." name "-test)]\n"
         "    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))\n")))

(def spool-repo-gitignore
  (str "# Clojure / tools.deps\n"
       ".cpcache/\n"
       ".clj-kondo/\n"
       ".lsp/.cache/\n"
       "target/\n"
       "classes/\n"
       "pom.xml.asc\n"
       "*.jar\n"
       ".nrepl-port\n"
       ".rebel_readline_history\n"
       "\n"
       "# Editors / OS\n"
       ".calva/\n"
       "*.iml\n"
       ".idea/\n"
       ".DS_Store\n"))

(defn spool-repo-readme [params]
  (let [name (require-name params "spool-repo/readme")]
    (str "# " name ".spool\n"
         "\n"
         "A shared Skein spool.\n"
         "\n"
         "## Prerequisites\n"
         "\n"
         "Approve this spool and each prerequisite explicitly in the operator workspace.\n"
         "\n"
         "## Dependency information\n"
         "\n"
         "Pin this repository in `.skein/spools.edn`; add all prerequisite coordinates beside it.\n"
         "\n"
         "## Activation\n"
         "\n"
         "After approval, sync the operator runtime and activate `skein.spools." name "`.\n")))

(def spool-repo-agents-md
  (str "# Agents\n"
       "\n"
       "<!-- mill:skein-prime -->\n"
       "## Skein / strand\n"
       "\n"
       "This repo uses Skein strands to track work. Orientation ships in the `mill` CLI:\n"
       "\n"
       "- `mill skein prime` — where the Skein source and docs live, and how to extend this repo's `.skein/` config.\n"
       "- `mill strand prime` — the strand planning/tracking workflow; run it before multi-step work.\n"
       "<!-- /mill:skein-prime -->\n"))

(def spool-repo-cljfmt-edn
  (str ";; cljfmt runs with its defaults, which track the community style guide\n"
       ";; (semantic indentation, single blank lines, preserved ns ordering).\n"
       ";; Add :extra-indents here only for project macros the defaults misindent.\n"
       "{}\n"))

(def spool-repo-splint-edn
  (str "{:output {:format \"clj-kondo\"}\n"
       " :parallel true\n"
       " :silent false\n"
       "\n"
       " ;; Clojure 1.12 method-value syntax (`^[] String/toUpperCase`, `Class/.instanceMethod`)\n"
       " ;; is an opt-in stylistic migration, not a defect: the codebase deliberately and\n"
       " ;; uniformly uses traditional interop, splint's own autocorrect cannot resolve the\n"
       " ;; receiver class (it emits a literal `CLASS` placeholder for instance calls), and the\n"
       " ;; rewrite would demand hand-authored `^[..]` param-tags at every one of ~500 call\n"
       " ;; sites. Adjudicated off; revisit only as a deliberate whole-repo modernisation.\n"
       " lint/prefer-method-values {:enabled false}\n"
       "\n"
       " ;; Skein is a long-lived daemon (weaver) that dynamically loads and resolves trusted\n"
       " ;; spool/config code. Every `(catch Throwable ...)` here is a deliberate boundary that\n"
       " ;; MUST see Errors too: dynamic `requiring-resolve`/`load-file`/EDN reads that wrap any\n"
       " ;; failure (incl. LinkageError/NoClassDefFoundError) in diagnostic ex-info and rethrow;\n"
       " ;; resilience nets on long-lived loops/threads (scheduler dispatch, event system) that\n"
       " ;; must never let one tick kill the runtime clock; cleanup handlers that `addSuppressed`\n"
       " ;; and rethrow the original; and process-boundary exit handlers. All 33 sites were\n"
       " ;; audited as load-bearing (narrowing to Exception would silently drop Error handling);\n"
       " ;; splint itself ships this rule `:safe false`, calling out mission-critical processes.\n"
       " ;; New lazy catches are caught by change review, not this rule. Adjudicated off.\n"
       " lint/catch-throwable {:enabled false}}\n"))

(def spool-repo-makefile
  (str ".PHONY: fmt fmt-check lint test\n"
       "\n"
       "fmt:\n"
       "\tclojure -M:format/fix\n"
       "\n"
       "fmt-check:\n"
       "\tclojure -M:format\n"
       "\n"
       "lint:\n"
       "\tclojure -M:lint/clj-kondo\n"
       "\tclojure -M:lint/splint\n"
       "\n"
       "test:\n"
       "\tclojure -M:test\n"))

(def spool-repo-quality-aliases-edn
  (str "{:format {:extra-deps {dev.weavejester/cljfmt {:mvn/version \"0.13.1\"}}\n"
       "          :main-opts [\"-m\" \"cljfmt.main\" \"check\" \"src\" \"test\"]}\n"
       " :format/fix {:extra-deps {dev.weavejester/cljfmt {:mvn/version \"0.13.1\"}}\n"
       "              :main-opts [\"-m\" \"cljfmt.main\" \"fix\" \"src\" \"test\"]}\n"
       " :lint/clj-kondo {:extra-deps {clj-kondo/clj-kondo {:mvn/version \"2025.06.05\"}}\n"
       "                  :main-opts [\"-m\" \"clj-kondo.main\" \"--lint\" \"src\" \"test\"]}\n"
       " :lint/splint {:extra-deps {io.github.noahtheduke/splint {:mvn/version \"1.21.0\"}}\n"
       "               :main-opts [\"-m\" \"noahtheduke.splint\" \"--output\" \"clj-kondo\" \"src\" \"test\"]}}\n"))

(def skein-dir-deps-edn
  (str "{:paths [\".\"]\n"
       " :aliases\n"
       " {:format {:extra-deps {dev.weavejester/cljfmt {:mvn/version \"0.13.1\"}}\n"
       "           :main-opts [\"-m\" \"cljfmt.main\" \"check\" \".\"]}\n"
       "  :format/fix {:extra-deps {dev.weavejester/cljfmt {:mvn/version \"0.13.1\"}}\n"
       "               :main-opts [\"-m\" \"cljfmt.main\" \"fix\" \".\"]}\n"
       "  :lint {:extra-deps {clj-kondo/clj-kondo {:mvn/version \"2025.06.05\"}}\n"
       "         :main-opts [\"-m\" \"clj-kondo.main\" \"--lint\" \".\"]}}}\n"))

(def skein-dir-makefile
  (str ".PHONY: fmt fmt-check lint\n"
       "\n"
       "fmt:\n"
       "\tclojure -M:format/fix\n"
       "\n"
       "fmt-check:\n"
       "\tclojure -M:format\n"
       "\n"
       "lint:\n"
       "\tclojure -M:lint\n"))

(def skein-dir-agents-md
  (str "# Skein workspace config\n"
       "\n"
       "Keep configuration split one file per concern and activate each file from `init.clj`:\n"
       "\n"
       "- `config.clj` — named queries and CLI ops\n"
       "- `workflows.clj` — hand-authored workflows\n"
       "- `harnesses.clj` — harness seats and routing policy\n"
       "- `reviewers.clj` — reviewer rosters\n"
       "\n"
       "Use the change/reload ladder in Skein's `docs/spools/customisation.md`; reload config instead of restarting the weaver.\n"
       "Smoke-test every config change in a disposable world before applying it to the coordination world.\n"))

(def skein-dir-claude-md
  "Read `.skein/AGENTS.md` before changing Skein workspace configuration.\n")

(def templates
  {"skein/config.json" skein-config-json
   "skein/spools.edn" skein-spools-edn
   "skein/init-minimal.clj" skein-init-minimal
   "skein/init-layered.clj" skein-init-layered
   "skein/gitignore" skein-gitignore
   "spool-repo/deps.edn" spool-repo-deps-edn
   "spool-repo/src-ns.clj" spool-repo-src-ns
   "spool-repo/test-main.clj" spool-repo-test-main
   "spool-repo/gitignore" spool-repo-gitignore
   "spool-repo/readme" spool-repo-readme
   "spool-repo/agents.md" spool-repo-agents-md
   "spool-repo/cljfmt.edn" spool-repo-cljfmt-edn
   "spool-repo/splint.edn" spool-repo-splint-edn
   "spool-repo/makefile" spool-repo-makefile
   "spool-repo/quality-aliases.edn" spool-repo-quality-aliases-edn
   "skein-dir/deps.edn" skein-dir-deps-edn
   "skein-dir/makefile" skein-dir-makefile
   "skein-dir/agents.md" skein-dir-agents-md
   "skein-dir/claude.md" skein-dir-claude-md})

(defn template
  "Return canonical template content, failing loudly for unknown names or missing params."
  ([name]
   (template name {}))
  ([name params]
   (spool/require-valid! ::specs/template-input
                         {:name name :params params}
                         "Dresser template input has an invalid shape")
   (let [entry (get templates name ::unknown)]
     (when (= ::unknown entry)
       (spool/fail! "Unknown dresser template"
                    {:template name
                     :known (set (keys templates))}))
     (if (fn? entry)
       (entry params)
       entry))))
