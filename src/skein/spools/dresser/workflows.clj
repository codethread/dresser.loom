(ns skein.spools.dresser.workflows
  "Workflow constructors compiled from the versioned dresser aspect registry."
  (:require [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [skein.spools.dresser.aspects :as aspects]
            [skein.spools.dresser.specs :as specs]
            [skein.spools.workflow :as workflow]))

(defn- aspect-parts [aspect-key]
  (let [[flavour aspect-name :as parts] (str/split aspect-key #"/" 2)]
    (when-not (= 2 (count parts))
      (throw (ex-info "Dresser aspect key must be <flavour>/<aspect>"
                      {:aspect aspect-key})))
    [flavour aspect-name]))

(defn- param-value [key]
  (fn [params]
    (get params key)))

(defn- aspect-attributes [flavour aspect-key version]
  {"dresser/flavour" flavour
   "dresser/aspect" aspect-key
   "dresser/version" version
   "dresser/root" (param-value :root)})

(defn- inspect-instruction [entry]
  (fn [{:keys [root]}]
    (str (:inspect entry)
         " Owned files: " (str/join ", " (:owned entry)) "."
         " Target root: " root ".")))

(defn- setup-instruction [setup]
  (fn [{:keys [root]}]
    (str (:instruction setup)
         " Template keys: " (str/join ", " (:templates setup)) "."
         " Target root: " root ".")))

(defn- conflict-checkpoint [attributes]
  (workflow/checkpoint
   :conflict
   "Resolve owned-file conflicts"
   :kind :human
   :condition [:!= :verify-only true]
   :depends-on [:inspect]
   :choices [{:key :clean
              :label "Clean"
              :description "Inspection found no owned-file conflicts."}
             {:key :apply-plan
              :label "Apply plan"
              :description "Apply the recorded keep/merge/replace decisions."
              :input [{:key :decisions
                       :required true
                       :description "Summary of per-file keep/merge/replace decisions."}]}
             {:key :abort
              :label "Abort"
              :description "Stop convention convergence for this target."
              :next :dresser/abort
              :input [{:key :reason
                       :required true
                       :description "Why convention convergence was aborted."}]}]
   :attributes (assoc attributes
                      "workflow/decision-point" "conflict-policy")))

(defn- setup-steps [entry attributes]
  (second
   (reduce (fn [[dependency steps] setup]
             [(:id setup)
              (conj steps
                    (workflow/step
                     (:id setup)
                     (:title setup)
                     :self
                     :condition [:!= :verify-only true]
                     :depends-on [dependency]
                     :attributes (assoc attributes
                                        "workflow/instruction"
                                        (setup-instruction setup))))])
           [:conflict []]
           (:setup entry))))

(defn- gate-steps [entry dependency attributes]
  (second
   (reduce (fn [[gate-dependency steps] {:keys [id title argv timeout-secs]}]
             [id
              (conj steps
                    (workflow/gate
                     id
                     title
                     :shell
                     :depends-on [gate-dependency]
                     :attributes (assoc attributes
                                        "dresser/gate-id" (name id)
                                        "shell/argv" argv
                                        "shell/cwd" (param-value :root)
                                        "shell/timeout-secs" timeout-secs)))])
           [dependency []]
           (:gates entry))))

(defn aspect-workflow
  "Return the one-argument workflow constructor for `aspect-key`."
  [aspect-key]
  (spool/require-valid! ::specs/aspect-key aspect-key
                        "Dresser aspect workflow key has an invalid shape")
  (let [entry (aspects/aspect aspect-key)
        [flavour aspect-name] (aspect-parts aspect-key)
        attributes (aspect-attributes flavour aspect-key (:version entry))]
    (fn [params]
      (spool/require-valid! ::specs/aspect-workflow-input params
                            "Dresser aspect workflow input has an invalid shape")
      (let [{:keys [verify-only]} params
            setup (setup-steps entry attributes)
            gate-dependency (or (:id (peek (:setup entry))) :conflict)]
        (apply workflow/workflow
               (str "Dresser " aspect-key)
               {:params {:root (workflow/param :required true)
                         :verify-only (workflow/param :default (boolean verify-only))}}
               (concat
                [(workflow/step
                  :inspect
                  (str "Inspect " aspect-name)
                  :self
                  :condition [:!= :verify-only true]
                  :attributes (assoc attributes
                                     "workflow/instruction"
                                     (inspect-instruction entry)))
                 (conflict-checkpoint attributes)]
                setup
                (gate-steps entry gate-dependency attributes)))))))

(def spool-repo-repo-skeleton-workflow
  (aspect-workflow "spool-repo/repo-skeleton"))

(def spool-repo-skein-workspace-workflow
  (aspect-workflow "spool-repo/skein-workspace"))

(def spool-repo-agent-docs-workflow
  (aspect-workflow "spool-repo/agent-docs"))

(def spool-repo-quality-workflow
  (aspect-workflow "spool-repo/quality"))

(def skein-dir-workspace-workflow
  (aspect-workflow "skein-dir/workspace"))

(def skein-dir-quality-workflow
  (aspect-workflow "skein-dir/quality"))

(def skein-dir-agent-docs-workflow
  (aspect-workflow "skein-dir/agent-docs"))

(defn- call-id [aspect-key]
  (keyword (second (aspect-parts aspect-key))))

(defn- aspect-call [aspect-key dependency root verify-only]
  (cond-> (workflow/call
           (call-id aspect-key)
           (aspect-workflow aspect-key)
           {:root root
            :verify-only (boolean verify-only)}
           :title (str "Complete " aspect-key))
    dependency (assoc :depends-on [dependency])))

(defn flavour-workflow
  "Return the one-argument umbrella constructor for `flavour`."
  [flavour]
  (spool/require-valid! ::specs/flavour flavour
                        "Dresser workflow flavour has an invalid shape")
  (fn [params]
    (spool/require-valid! ::specs/flavour-workflow-input params
                          "Dresser flavour workflow input has an invalid shape")
    (let [{:keys [aspects root verify-only]} params
          selected (or aspects (aspects/flavour-aspects flavour))
          calls (second
                 (reduce (fn [[dependency result] aspect-key]
                           (let [id (call-id aspect-key)]
                             [id (conj result (aspect-call aspect-key
                                                           dependency
                                                           root
                                                           verify-only))]))
                         [nil []]
                         selected))]
      (apply workflow/workflow
             (str "Dresser " flavour)
             {:params {:root (workflow/param :required true)
                       :verify-only (workflow/param :default (boolean verify-only))
                       :aspects (workflow/param :default selected)}
              :attributes {"dresser/flavour" flavour
                           "dresser/root" (or root (param-value :root))}}
             calls))))

(def spool-repo-workflow
  (flavour-workflow "spool-repo"))

(def skein-dir-workflow
  (flavour-workflow "skein-dir"))

(defn abort-workflow
  "Return the terminal workflow that records an abort reason."
  [params]
  (spool/require-valid! ::specs/abort-workflow-input params
                        "Dresser abort workflow input has an invalid shape")
  (workflow/workflow
   "Dresser abort"
   {:params {:reason (workflow/param :required true)}}
   (workflow/step
    :abort
    "Record dresser abort"
    :self
    :attributes {"workflow/instruction"
                 (fn [params]
                   (str "Record why dresser convergence was aborted: "
                        (:reason params)))})))

(def workflow-definitions
  "Stable registered workflow names and their resolvable constructors."
  {:dresser/spool-repo 'skein.spools.dresser.workflows/spool-repo-workflow
   :dresser/skein-dir 'skein.spools.dresser.workflows/skein-dir-workflow
   :dresser/abort 'skein.spools.dresser.workflows/abort-workflow
   :dresser/spool-repo.repo-skeleton
   'skein.spools.dresser.workflows/spool-repo-repo-skeleton-workflow
   :dresser/spool-repo.skein-workspace
   'skein.spools.dresser.workflows/spool-repo-skein-workspace-workflow
   :dresser/spool-repo.agent-docs
   'skein.spools.dresser.workflows/spool-repo-agent-docs-workflow
   :dresser/spool-repo.quality
   'skein.spools.dresser.workflows/spool-repo-quality-workflow
   :dresser/skein-dir.workspace
   'skein.spools.dresser.workflows/skein-dir-workspace-workflow
   :dresser/skein-dir.quality
   'skein.spools.dresser.workflows/skein-dir-quality-workflow
   :dresser/skein-dir.agent-docs
   'skein.spools.dresser.workflows/skein-dir-agent-docs-workflow})

(defn register-workflows!
  "Register all dresser umbrella, abort, and aspect workflows."
  []
  (into {}
        (map (fn [[name constructor]]
               [name (workflow/register-workflow! name constructor)]))
        workflow-definitions))

(defn- topology-mode [aspect-key verify-only]
  (let [params {:root "<root>" :verify-only verify-only}
        description (workflow/describe ((aspect-workflow aspect-key) params)
                                       params)]
    {:steps (mapv #(select-keys % [:id :kind :depends-on])
                  (:steps description))
     :gates (into [] (keep #(when (:gate %) (:id %))) (:steps description))}))

(defn describe-topology
  "Return deterministic setup and verify-only topology for every aspect."
  []
  (into (sorted-map)
        (map (fn [aspect-key]
               [aspect-key
                {:setup (topology-mode aspect-key false)
                 :verify-only (topology-mode aspect-key true)}]))
        (sort (keys aspects/registry))))
