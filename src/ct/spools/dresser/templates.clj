(ns ct.spools.dresser.templates
  "Canonical source-data templates for dresser's v1 convention aspects.

  Each template's content is a real file under
  `resources/ct/spools/dresser/templates/`, so tab-sensitive and escape-heavy
  formats stay reviewable and diffable as themselves. `templates` remains the
  single contract surface: template key -> string, or fn of a params map.
  The `template` function validates `{:name ... :params ...}` against the
  authoritative `::specs/template-input` shape. Parameterized templates include
  `spool-repo/quality.yml`, whose params require `:name` and `:millstrand-sha`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :as spool]
            [ct.spools.dresser.specs :as specs]))

(def ^:private resource-dir "ct/spools/dresser/templates/")

(defn- resource-path
  "Resource path backing `template-name`.

  Clojure-suffixed keys gain a `.template` extension: millstrand's spool sync treats
  every `.clj`/`.cljc` file under a root's `:paths` as a namespace source and
  load-files it, which would evaluate these fragments as code."
  [template-name]
  (str resource-dir template-name
       (when (re-find #"\.cljc?$" template-name) ".template")))

(defn- resource-content
  "Read a template's backing resource, failing loudly when it is absent.

  A missing resource reading as nil would silently corrupt both convergence
  output and the release fingerprint."
  [template-name]
  (let [path (resource-path template-name)]
    (if-let [url (io/resource path)]
      (slurp url :encoding "UTF-8")
      (spool/fail! "Missing dresser template resource"
                   {:template template-name
                    :resource path}))))

(defn- require-param [params template-name param]
  (let [value (get params param)]
    (when-not (specs/non-blank-string? value)
      (spool/fail! (str "Template requires a non-blank " param)
                   {:template template-name
                    :required param
                    :params params}))
    value))

(defn- render-params
  "Render a parameterized template by substituting each `<param>` placeholder.

  A placeholder is spelled exactly as its parameter, which is what lets
  `fingerprint-params` render every template back to its backing resource's
  own bytes — the content the release fingerprint hashes."
  [template-name param-keys params]
  (reduce (fn [content param]
            (str/replace content
                         (str "<" (name param) ">")
                         (require-param params template-name param)))
          (resource-content template-name)
          param-keys))

(defn- keywordize-params [params]
  (into {}
        (map (fn [[key value]] [(keyword (name key)) value]))
        params))

(def ^:private static-template-names
  ["millstrand/config.json"
   "millstrand/spools.edn"
   "millstrand/init-minimal.clj"
   "millstrand/init-layered.clj"
   "millstrand/gitignore"
   "spool-repo/gitignore"
   "spool-repo/agents.md"
   "spool-repo/cljfmt.edn"
   "spool-repo/splint.edn"
   "spool-repo/makefile"
   "spool-repo/quality.mk"
   "spool-repo/quality-aliases.edn"
   "spool-repo/docs.mk"
   "spool-repo/mkdocs-hooks.py"
   "spool-repo/pages.yml"
   "millstrand-dir/deps.edn"
   "millstrand-dir/makefile"
   "millstrand-dir/agents.md"
   "millstrand-dir/claude.md"])

(def ^:private parameterized-template-names
  "Template key -> the params its placeholders consume, in substitution order.

  `:name` substitutes first everywhere so a repo-authored value carrying a
  literal `<name>` is never re-read as a placeholder."
  {"spool-repo/deps.edn" [:name]
   "spool-repo/src-ns.clj" [:name]
   "spool-repo/test-main.clj" [:name]
   "spool-repo/readme" [:name]
   "spool-repo/mkdocs.yml" [:name :repo-name :site-name :site-description]
   "spool-repo/generate-api-docs.clj" [:name :repo-name :git-branch]
   "spool-repo/quality.yml" [:name :millstrand-sha]})

(def fingerprint-params
  "Params rendering every template back to its backing resource's exact bytes.

  Each parameter maps to its own placeholder, so the release fingerprint hashes
  resource content rather than a sample rendering: adding a parameter to a new
  template cannot move an existing template's recorded hash, and a template that
  demands a parameter no longer fails fingerprinting the way a `:name`-only stub
  made it."
  (into {}
        (map (juxt identity #(str "<" (name %) ">")))
        (into (sorted-set) cat (vals parameterized-template-names))))

(def templates
  "Canonical template content by key: a string, or a fn of a params map."
  (merge (into {} (map (juxt identity resource-content)) static-template-names)
         (into {}
               (map (fn [[template-name param-keys]]
                      [template-name #(render-params template-name param-keys %)]))
               parameterized-template-names)))

(defn template
  "Return canonical template content after validating the ::specs/template-input shape.

  Unknown names and missing parameters, including `:millstrand-sha` for the
  quality workflow, fail loudly."
  ([name]
   (template name {}))
  ([name params]
   (spool/require-valid! ::specs/template-input
                         {:name name :params params}
                         "Dresser template input has an invalid shape")
   (let [params (keywordize-params params)
         entry (get templates name ::unknown)]
     (when (= ::unknown entry)
       (spool/fail! "Unknown dresser template"
                    {:template name
                     :known (set (keys templates))}))
     (if (fn? entry)
       (entry params)
       entry))))
