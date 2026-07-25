(ns ct.spools.dresser.templates
  "Canonical source-data templates for dresser's v1 convention aspects.

  Each template's content is a real file under
  `resources/ct/spools/dresser/templates/`, so tab-sensitive and escape-heavy
  formats stay reviewable and diffable as themselves. `templates` remains the
  single contract surface: template key -> string, or fn of a params map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [ct.spools.dresser.specs :as specs]))

(def ^:private resource-dir "ct/spools/dresser/templates/")

(defn- resource-path
  "Resource path backing `template-name`.

  Clojure-suffixed keys gain a `.template` extension: skein's spool sync treats
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

(defn- require-name [{:keys [name] :as params} template-name]
  (when-not (specs/non-blank-string? name)
    (spool/fail! "Template requires a non-blank :name"
                 {:template template-name
                  :required :name
                  :params params}))
  name)

(defn- render-name
  "Render a parameterized template by substituting its `<name>` placeholder.

  `<name>` is the placeholder aspects/material-data already renders these
  templates with, so each backing resource is byte-identical to the content
  the release fingerprint hashes."
  [template-name params]
  (str/replace (resource-content template-name)
               "<name>"
               (require-name params template-name)))

(def ^:private static-template-names
  ["skein/config.json"
   "skein/spools.edn"
   "skein/init-minimal.clj"
   "skein/init-layered.clj"
   "skein/gitignore"
   "spool-repo/gitignore"
   "spool-repo/agents.md"
   "spool-repo/cljfmt.edn"
   "spool-repo/splint.edn"
   "spool-repo/makefile"
   "spool-repo/quality.mk"
   "spool-repo/quality-aliases.edn"
   "skein-dir/deps.edn"
   "skein-dir/makefile"
   "skein-dir/agents.md"
   "skein-dir/claude.md"])

(def ^:private parameterized-template-names
  ["spool-repo/deps.edn"
   "spool-repo/src-ns.clj"
   "spool-repo/test-main.clj"
   "spool-repo/readme"])

(def templates
  "Canonical template content by key: a string, or a fn of a params map."
  (merge (into {} (map (juxt identity resource-content)) static-template-names)
         (into {}
               (map (fn [template-name]
                      [template-name #(render-name template-name %)]))
               parameterized-template-names)))

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
