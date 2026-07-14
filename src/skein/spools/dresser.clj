(ns skein.spools.dresser
  "Convention-convergence spool: versioned per-aspect setup/verify workflows
  driven from an operator weaver world against a target repo path."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as fmt]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :as spool]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser.receipt :as receipt]
            [skein.spools.dresser.target :as target]
            [skein.spools.dresser.templates :as templates]
            [skein.spools.dresser.workflows :as dresser-workflows]
            [skein.spools.workflow :as workflow])
  (:import (java.time LocalDate)))

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

(defn- selected-aspects [flavour selection]
  (if (some? selection)
    (let [keys (mapv str/trim (str/split selection #"," -1))]
      (when (some str/blank? keys)
        (spool/fail! "Dresser --aspects must be a comma-separated list of aspect keys"
                     {:flavour flavour :aspects selection}))
      (aspects/close-under-deps flavour keys))
    (aspects/flavour-aspects flavour)))

(defn- start-run! [flavour root verify-only selection]
  (let [root (target/resolve-root root)
        selected (selected-aspects flavour selection)
        run-id ((if verify-only target/verify-run-id target/run-id) flavour root)
        params {:root root :verify-only verify-only :aspects selected}]
    (workflow/start! run-id
                     (keyword "dresser" flavour)
                     params
                     {:family "dresser"})))

(defn start
  "Start a setup run for flavour and root."
  [flavour root selection]
  (start-run! flavour root false selection))

(defn verify
  "Start a verify-only run for flavour and root."
  [flavour root selection]
  (start-run! flavour root true selection))

(defn- addressed-run-id [flavour root verify?]
  (let [root (target/resolve-root root)]
    ((if verify? target/verify-run-id target/run-id) flavour root)))

(defn next-steps
  "Return the ready frontier for a setup run, or verify run when verify? is true."
  [flavour root verify?]
  (workflow/next-steps (addressed-run-id flavour root verify?)))

(defn advance!
  "Advance a setup run, or verify run when verify? is true."
  [flavour root verify? opts]
  (workflow/advance! (addressed-run-id flavour root verify?)
                     (assoc opts :by "dresser")))

(defn- keywordize-input [input]
  (into {}
        (map (fn [[key value]] [(keyword (name key)) value]))
        (or input {})))

(defn- attr [strand key]
  (spool/attr-get strand key))

(defn- expected-gates [aspect-key]
  (into (sorted-map)
        (map (fn [{:keys [id title]}] [(name id) title]))
        (:gates (aspects/aspect aspect-key))))

(defn- latest-generation-gates [run-id aspect-key]
  (let [history (workflow/run-history run-id)
        root-id (get-in (peek history) [:root :id])
        strands (:strands (graph/subgraph (current/runtime) [root-id]))]
    {:root-id root-id
     :gates (filterv #(and (= aspect-key (attr % :dresser/aspect))
                           (= "shell" (attr % :workflow/gate)))
                     strands)}))

(defn- evidence-violations [expected gates]
  (let [expected-by-title (into {} (map (fn [[id title]] [title id])) expected)
        identified (group-by #(get expected-by-title (:title %)) gates)
        missing (remove #(contains? identified %) (keys expected))
        unexpected (get identified nil)]
    (vec
     (concat
      (map (fn [gate-id] {:violation :missing-gate :gate gate-id}) missing)
      (map (fn [gate]
             {:violation :unexpected-gate :gate (:id gate) :title (:title gate)})
           unexpected)
      (mapcat
       (fn [[gate-id _]]
         (let [matches (get identified gate-id)]
           (concat
            (when (> (count matches) 1)
              [{:violation :duplicate-gate
                :gate gate-id
                :strand-ids (mapv :id matches)}])
            (mapcat
             (fn [gate]
               (cond-> []
                 (not= "closed" (:state gate))
                 (conj {:violation :gate-not-closed :gate gate-id :state (:state gate)})

                 (not= "shell" (attr gate :workflow/outcome-by))
                 (conj {:violation :outcome-by
                        :gate gate-id
                        :actual (attr gate :workflow/outcome-by)
                        :expected "shell"})

                 (not= 0 (attr gate :shell/exit-code))
                 (conj {:violation :exit-code
                        :gate gate-id
                        :actual (attr gate :shell/exit-code)
                        :expected 0})

                 (some? (attr gate :shell/error))
                 (conj {:violation :shell-error
                        :gate gate-id
                        :actual (attr gate :shell/error)})))
             matches))))
       expected)))))

(defn stamp!
  "Stamp one aspect from the latest setup generation's durable gate evidence."
  [aspect-key root]
  (let [root (target/resolve-root root)
        [flavour aspect-name :as parts] (str/split aspect-key #"/" 2)]
    (when-not (= 2 (count parts))
      (spool/fail! "Dresser stamp aspect must be <flavour>/<aspect>"
                   {:aspect aspect-key}))
    (let [entry (aspects/aspect aspect-key)
          run-id (target/run-id flavour root)
          expected (expected-gates aspect-key)
          {:keys [root-id gates]} (latest-generation-gates run-id aspect-key)
          violations (evidence-violations expected gates)]
      (when (seq violations)
        (spool/fail! (str "Dresser stamp evidence failed for " flavour "/" aspect-name
                          ": "
                          (str/join ", "
                                    (map #(str (name (:violation %))
                                               "[" (:gate %) "]")
                                         violations)))
                     {:aspect aspect-key
                      :run-id run-id
                      :generation root-id
                      :violations violations}))
      (let [updated (receipt/merge-aspect (receipt/read-receipt root)
                                          aspect-key
                                          entry
                                          aspects/release-version
                                          (current-fingerprint)
                                          (str (LocalDate/now)))
            written (receipt/write-receipt! root updated)]
        {:aspect aspect-key
         :entry (get-in written [:aspects aspect-key])
         :plan (get-in (plan root) [:aspects aspect-key])}))))

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
                           :doc "Existing git worktree root."}]}
    "start" {:doc "Start a setup convergence run."
             :flags {:aspects {:type :string
                               :doc "Comma-separated full aspect keys."}}
             :positionals [{:name :flavour :required? true}
                           {:name :root :required? true}]}
    "verify" {:doc "Start a verify-only run."
              :flags {:aspects {:type :string
                                :doc "Comma-separated full aspect keys."}}
              :positionals [{:name :flavour :required? true}
                            {:name :root :required? true}]}
    "next" {:doc "Return the run's ready frontier."
            :flags {:verify {:type :boolean
                             :doc "Address the verify-only run."}}
            :positionals [{:name :flavour :required? true}
                          {:name :root :required? true}]}
    "advance" {:doc "Advance one ready run step or checkpoint."
               :flags {:verify {:type :boolean
                                :doc "Address the verify-only run."}
                       :choice {:type :string}
                       :input {:type :map}
                       :notes {:type :string}
                       :step {:type :string}}
               :positionals [{:name :flavour :required? true}
                             {:name :root :required? true}]}
    "stamp" {:doc "Stamp one aspect after latest-generation gates pass."
             :positionals [{:name :aspect :required? true}
                           {:name :root :required? true}]}}})

(defn dresser-op
  "Dispatch parsed dresser subcommands."
  [{:op/keys [args]}]
  (case (:subcommand args)
    "about" (about)
    "aspects" (aspects-view)
    "template" (template-view (:name args) (:param args))
    "plan" (plan (:root args))
    "start" (start (:flavour args) (:root args) (:aspects args))
    "verify" (verify (:flavour args) (:root args) (:aspects args))
    "next" (next-steps (:flavour args) (:root args) (:verify args))
    "advance" (advance! (:flavour args)
                        (:root args)
                        (:verify args)
                        (cond-> {}
                          (contains? args :choice) (assoc :choice (:choice args))
                          (contains? args :input) (assoc :input (keywordize-input (:input args)))
                          (contains? args :notes) (assoc :notes (:notes args))
                          (contains? args :step) (assoc :step (:step args))))
    "stamp" (stamp! (:aspect args) (:root args))))

(defn- op-registered? [runtime op-name]
  (boolean (some #(= (clojure.core/name op-name) (:name %)) (weaver/ops runtime))))

(defn- register-or-replace-op! [runtime]
  (let [metadata {:doc "Inspect and converge repository conventions."
                  :arg-spec dresser-arg-spec
                  :hook-class :mutating}]
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
