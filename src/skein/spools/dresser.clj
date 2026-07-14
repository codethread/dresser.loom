(ns skein.spools.dresser
  "Convention-convergence spool: versioned per-aspect setup/verify workflows
  driven from an operator weaver world against a target repo path."
  (:require [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as fmt]
            [skein.api.spool.alpha :as spool]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser.receipt :as receipt]
            [skein.spools.dresser.target :as target]
            [skein.spools.dresser.templates :as templates]
            [skein.spools.dresser.workflows :as dresser-workflows]
            [skein.spools.workflow :as workflow]))

(def release-version
  "Compatibility alias for the aspect registry's release version."
  aspects/release-version)

(defn- check-prereqs!
  "Fail loudly unless the workflow lifecycle resolves and shell is installed."
  [resolve-fn executors]
  (let [lifecycle (try
                    (resolve-fn 'skein.spools.workflow/start!)
                    (catch Throwable _ nil))]
    (when-not lifecycle
      (spool/fail! "Dresser requires the workflow spool on the classpath"
                   {:prerequisite :workflow
                    :symbol 'skein.spools.workflow/start!}))
    (when-not (contains? executors :shell)
      (spool/fail! "Dresser requires the shell workflow executor"
                   {:prerequisite :shell
                    :registered-executors (set (keys executors))}))
    {:workflow lifecycle :shell (:shell executors)}))

(defn- current-fingerprint []
  (get aspects/releases aspects/release-version))

(defn- aspect-projection []
  (into (sorted-map)
        (map (fn [[aspect-key {:keys [version deps gates]}]]
               [aspect-key
                {:version version
                 :deps (vec deps)
                 :gates (mapv (comp name :id) gates)}]))
        aspects/registry))

(defn about
  "Return dresser's authored semantic discovery document."
  []
  {:purpose
   (fmt/reflow
    "|Converge repositories on versioned formatting, linting, test, agent-guidance,
     |and Skein-workspace conventions through inspectable, verifiable workflows.")
   :flavours
   (into (sorted-map)
         (for [flavour ["spool-repo" "skein-dir"]]
           [flavour {:aspects (aspects/flavour-aspects flavour)}]))
   :semantics
   {:receipt
    (fmt/reflow
     "|The checked-in .skein/conventions.edn receipt records green verification,
      |aspect versions, release lineage, and a release fingerprint; it does not prove
      |that files have not drifted since stamping.")
    :plan
    (fmt/reflow
     "|Plan compares the receipt with the live pinned registry and reports every
      |aspect as new, pending, current, divergent, ahead, or removed.")
    :verify
    (fmt/reflow
     "|Verify runs the registry's mechanical gates against the target without
      |running inspect, conflict, or setup steps.")
    :stamp
    (fmt/reflow
     "|Stamp advances one receipt aspect only after the latest setup generation's
      |complete expected gate set has shell-recorded success evidence.")}
   :quickstart
   (fmt/fill
    "|Run `git init` first when scaffolding a new target; every target must already
     |be a git worktree root.
     |
     |Inspect `strand dresser aspects`, then use `strand dresser plan <root>` to
     |compare a target receipt with this release.
     |
     |Use `strand help dresser` for the installed command and argument surface.")})

(defn aspects-view
  "Return the versioned registry projection exposed by `dresser aspects`."
  []
  {:release aspects/release-version
   :fingerprint (current-fingerprint)
   :releases (into (sorted-map) aspects/releases)
   :aspects (aspect-projection)})

(defn template-view
  "Return one canonical template, rendering any supplied params."
  [name params]
  (let [params (into {}
                     (map (fn [[key value]] [(keyword (clojure.core/name key)) value]))
                     (or params {}))]
    {:template name
     :params params
     :content (templates/template name params)}))

(defn- provenance-view [stamp]
  (let [receipt-release (:dresser/release stamp)
        receipt-fingerprint (:dresser/fingerprint stamp)
        lineage-fingerprint (get aspects/releases receipt-release)
        verdict (cond
                  (nil? stamp) :unstamped
                  (and (integer? receipt-release)
                       (> receipt-release aspects/release-version)) :ahead
                  (= receipt-fingerprint lineage-fingerprint) :known
                  :else :divergent)]
    {:verdict verdict
     :receipt-release receipt-release
     :receipt-fingerprint receipt-fingerprint
     :lineage-fingerprint lineage-fingerprint}))

(defn plan
  "Resolve root and compare its receipt with the live versioned registry."
  [root]
  (let [root (target/resolve-root root)
        stamp (receipt/read-receipt root)
        registry-view {:release aspects/release-version
                       :fingerprint (current-fingerprint)
                       :releases aspects/releases
                       :aspects (into (sorted-map)
                                      (map (fn [[key entry]] [key (:version entry)]))
                                      aspects/registry)}]
    {:root root
     :release aspects/release-version
     :fingerprint (current-fingerprint)
     :aspects (receipt/plan-classification stamp registry-view)
     :provenance (provenance-view stamp)}))

(def ^:private dresser-arg-spec
  {:op "dresser"
   :doc "Inspect and converge repository conventions."
   :subcommands
   {"about" {:doc "Explain dresser's conventions and receipt semantics."}
    "aspects" {:doc "List the installed aspect registry and release lineage."}
    "template" {:doc "Render one canonical template."
                :flags {:param {:type :map
                                :doc "Template parameter as name=value; repeatable keys accumulate into a map."}}
                :positionals [{:name :name
                               :required? true
                               :doc "Template key, such as spool-repo/deps.edn."}]}
    "plan" {:doc "Compare a target receipt with this registry release."
            :positionals [{:name :root
                           :required? true
                           :doc "Existing git worktree root."}]}}})

(defn dresser-op
  "Dispatch parsed read-only dresser subcommands."
  [{:op/keys [args]}]
  (case (:subcommand args)
    "about" (about)
    "aspects" (aspects-view)
    "template" (template-view (:name args) (:param args))
    "plan" (plan (:root args))))

(defn- op-registered? [runtime op-name]
  (boolean (some #(= (clojure.core/name op-name) (:name %)) (weaver/ops runtime))))

(defn- register-or-replace-op! [runtime]
  (let [metadata {:doc "Inspect and converge repository conventions."
                  :arg-spec dresser-arg-spec
                  :hook-class :read}]
    (if (op-registered? runtime 'dresser)
      (weaver/replace-op! runtime 'dresser metadata 'skein.spools.dresser/dresser-op)
      (weaver/register-op! runtime 'dresser metadata 'skein.spools.dresser/dresser-op))))

(defn install!
  "Install dresser vocabulary, workflows, and declared-subcommand op."
  []
  (check-prereqs! requiring-resolve (workflow/registered-executors))
  (let [runtime (current/runtime)]
    {:installed true
     :namespace 'skein.spools.dresser
     :vocab (vocab/declare! runtime
                            {:kind :attr-namespace
                             :name "dresser"
                             :owner :skein/spools-dresser
                             :keys ["dresser/flavour" "dresser/aspect"
                                    "dresser/version" "dresser/root"]
                             :doc "Dresser target and aspect identity attributes on workflow roots and steps."})
     :workflows (dresser-workflows/register-workflows!)
     :op (register-or-replace-op! runtime)}))
