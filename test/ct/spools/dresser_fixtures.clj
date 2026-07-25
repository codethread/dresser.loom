(ns ct.spools.dresser-fixtures
  "Filesystem and workflow-driving helpers for dresser's disposable-world e2e tests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [skein.api.graph.alpha :as graph]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :as spool]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [ct.spools.dresser :as dresser]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.target :as target]
            [ct.spools.dresser.templates :as templates]
            [skein.spools.executors.shell :as shell-executor]
            [skein.spools.workflow :as workflow]
            [skein.test.alpha :as t])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDate)
           (java.util.concurrent AbstractExecutorService)))

(def ^:private max-driver-steps 64)
(def ^:private attention-timeout-ms 5000)

(defn- direct-executor []
  (let [shutdown? (atom false)]
    (proxy [AbstractExecutorService] []
      (execute [command]
        (when @shutdown?
          (throw (java.util.concurrent.RejectedExecutionException.)))
        (.run ^Runnable command))
      (shutdown [] (reset! shutdown? true))
      (shutdownNow [] (reset! shutdown? true) [])
      (isShutdown [] @shutdown?)
      (isTerminated [] @shutdown?)
      (awaitTermination [_timeout _unit] true))))

(defn activate-module!
  "Activate a spool module on a bare test runtime from the JVM image.

  Validates the namespace's exported `spool` declaration, then declares its
  `:ns` source target with `:load :image` (plus an optional `:after` edge) via
  `runtime/module!`. Production carries `:spools` guards instead. Throws with
  the full refresh result unless the module's outcome is applied or unchanged,
  so a fixture failure names the refusal instead of cascading into unrelated
  assertions. Returns the refresh result."
  [rt key ns-sym entry-points & {:keys [after]}]
  (spool/require-valid! ::spool/spool entry-points
                        "Spool entry-point declaration is invalid")
  (let [result (runtime/module! rt key (cond-> {:ns ns-sym :load :image}
                                         after (assoc :after after)))
        status (get-in result [:modules key :status])]
    (when-not (contains? #{:applied :unchanged} status)
      (throw (ex-info "Spool module activation failed"
                      {:module/key key :module/status status :result result})))
    result))

(defn activate-workflow!
  "Activate the workflow spool module on a bare test runtime."
  [rt]
  (activate-module! rt :workflow 'skein.spools.workflow workflow/spool))

(defn activate-serial-shell!
  "Activate the real shell executor module with deterministic inline workers.

  The redef window covers the module refresh, whose reconcile materializes
  the runtime-owned worker pool through the redefined state builder."
  [rt]
  (let [workers (direct-executor)
        new-state (fn []
                    {:scan-monitor (Object.)
                     :worker-executor workers
                     :close-fn #(.shutdown workers)})]
    (with-redefs-fn {(ns-resolve 'skein.spools.executors.shell 'new-state)
                     new-state}
      #(activate-module! rt :shell 'skein.spools.executors.shell shell-executor/spool
                         :after [:workflow]))))

(defn activate-dresser!
  "Activate the dresser module ordered after the workflow module."
  [rt]
  (activate-module! rt :dresser 'ct.spools.dresser dresser/spool :after [:workflow]))

(defn with-dresser-runtime
  "Run f in a disposable weaver world with dresser and either real or inert shell.

  f receives runtime and config-dir."
  ([f]
   (with-dresser-runtime {:real-shell? true} f))
  ([{:keys [real-shell?] :or {real-shell? true}} f]
   (t/with-weaver-world [ctx {:storage :sqlite-memory}]
     (weaver-runtime/with-runtime-binding
       (:runtime ctx)
       #(let [rt (:runtime ctx)]
          (activate-workflow! rt)
          (if real-shell?
            (activate-serial-shell! rt)
            (workflow/register-executor! :shell (constantly nil)))
          (activate-dresser! rt)
          (binding [dresser/*current-date* (LocalDate/of 2026 7 14)]
            (f rt (:config-dir ctx))))))))

(defn temp-directory ^Path []
  (Files/createTempDirectory "dresser-e2e-" (make-array FileAttribute 0)))

(defn delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       (do ~@body)
       (finally (delete-tree! ~binding)))))

(defn git-init-root! ^Path [^Path parent name]
  (let [root (.resolve parent name)
        {:keys [exit err]} (sh/sh "git" "init" "--quiet" (str root))]
    (when-not (zero? exit)
      (throw (ex-info "git init failed" {:root (str root) :exit exit :stderr err})))
    root))

(defn- write-content! [root relative content]
  (let [file (io/file (str root) relative)]
    (io/make-parents file)
    (spit file content)))

(defn- write-template!
  ([root relative template-key]
   (write-template! root relative template-key nil))
  ([root relative template-key params]
   (write-content! root relative (templates/template template-key (or params {})))))

(defn- fixture-name [root]
  (-> (io/file (str root)) .getName (str/replace #"[^A-Za-z0-9_-]" "-")))

(defn- sibling-skein-root []
  (.getCanonicalPath (io/file "../skein-src")))

(defn- spool-repo-deps [root]
  (-> (templates/template "spool-repo/deps.edn" {:name (fixture-name root)})
      (str/replace "../skein-src" (sibling-skein-root))))

(defn- merge-quality-aliases! [root]
  (let [deps-file (io/file (str root) "deps.edn")
        deps (edn/read-string (slurp deps-file))
        quality (edn/read-string (templates/template "spool-repo/quality-aliases.edn"))]
    (with-open [writer (io/writer deps-file)]
      (pp/pprint (update deps :aliases merge quality) writer))))

(defn write-spool-repo-step-files!
  "Write the spool-repo files owned by one setup step from canonical templates."
  [root title]
  (let [name (fixture-name root)
        params {:name name}
        ns-path (str/replace name "-" "_")]
    (case title
      "Write deps.edn"
      (write-content! root "deps.edn" (spool-repo-deps root))

      "Write source and test"
      (do
        (write-template! root (str "src/ct/spools/" ns-path ".clj")
                         "spool-repo/src-ns.clj" params)
        (write-template! root (str "test/ct/spools/" ns-path "_test.clj")
                         "spool-repo/test-main.clj" params))

      "Write README"
      (write-template! root "README.md" "spool-repo/readme" params)

      "Write gitignore"
      (write-template! root ".gitignore" "spool-repo/gitignore")

      "Write Skein workspace"
      (doseq [[relative template-key]
              [[".skein/config.json" "skein/config.json"]
               [".skein/spools.edn" "skein/spools.edn"]
               [".skein/init.clj" "skein/init-minimal.clj"]
               [".skein/.gitignore" "skein/gitignore"]]]
        (write-template! root relative template-key))

      "Write AGENTS.md"
      (write-template! root "AGENTS.md" "spool-repo/agents.md")

      "Write quality config"
      (do
        (write-template! root ".cljfmt.edn" "spool-repo/cljfmt.edn")
        (write-template! root ".splint.edn" "spool-repo/splint.edn")
        (merge-quality-aliases! root))

      "Write Makefile"
      (write-template! root "Makefile" "spool-repo/makefile")

      nil)))

(defn seed-spool-repo!
  "Seed a scaffold-shaped spool repo whose real registry gates can run."
  [root]
  (doseq [title ["Write deps.edn"
                 "Write source and test"
                 "Write README"
                 "Write gitignore"
                 "Write Skein workspace"
                 "Write AGENTS.md"
                 "Write quality config"
                 "Write Makefile"]]
    (write-spool-repo-step-files! root title))
  root)

(defn write-step-files!
  "Write the skein-dir files owned by one setup step from canonical templates."
  [root title]
  (case title
    "Write layered workspace"
    (doseq [[relative template-key]
            [[".skein/config.json" "skein/config.json"]
             [".skein/spools.edn" "skein/spools.edn"]
             [".skein/init.clj" "skein/init-layered.clj"]
             [".skein/.gitignore" "skein/gitignore"]]]
      (write-template! root relative template-key))

    "Write quality tooling"
    (doseq [[relative template-key]
            [[".skein/deps.edn" "skein-dir/deps.edn"]
             [".skein/Makefile" "skein-dir/makefile"]]]
      (write-template! root relative template-key))

    "Write agent docs"
    (doseq [[relative template-key]
            [[".skein/AGENTS.md" "skein-dir/agents.md"]
             [".skein/CLAUDE.md" "skein-dir/claude.md"]]]
      (write-template! root relative template-key))

    nil))

(defn snapshot-outside-skein
  "Snapshot every host-tree path and file byte outside .skein/."
  [root]
  (let [root-path (.toPath (io/file (str root)))]
    (into (sorted-map)
          (for [file (file-seq (.toFile root-path))
                :let [path (.toPath file)
                      relative (str (.relativize root-path path))]
                :when (and (not (str/blank? relative))
                           (not (or (= relative ".skein")
                                    (str/starts-with? relative (str ".skein" java.io.File/separator)))))]
            [relative
             (if (.isDirectory file)
               :directory
               (vec (Files/readAllBytes path)))]))))

(defn- attention [runtime run-id]
  (shell-executor/scan!)
  (let [ready (workflow/ready run-id)
        strands (mapv #(weaver/show runtime (:id %)) ready)
        stalled (some #(when (spool/attr-get % :gate/error) %) strands)
        driver-ready (filterv #(not= "shell" (:gate %)) ready)]
    (cond
      (workflow/done? run-id) {:reason :done :ready ready}
      stalled {:reason :stalled :ready ready :gate stalled}
      (seq driver-ready) {:reason :driver :ready driver-ready}
      :else nil)))

(defn wait-for-attention!
  "Poll until a run is done, stalled, or needs its driving agent."
  [runtime run-id]
  (spool/poll-until!
   (runtime/clock runtime)
   {:timeout-ms attention-timeout-ms
    :poll-ms 25
    :check #(attention runtime run-id)
    :pred->result identity
    :on-timeout (fn [_]
                  (throw (ex-info "Timed out waiting for dresser run"
                                  {:run-id run-id
                                   :ready (workflow/ready run-id)})))}))

(defn- require-driver-budget! [run-id driven]
  (when (>= driven max-driver-steps)
    (throw (ex-info "Dresser fixture exceeded its deterministic driver-step budget"
                    {:run-id run-id
                     :driven driven
                     :max-driver-steps max-driver-steps
                     :ready (workflow/ready run-id)}))))

(defn drive-skein-dir!
  "Drive all agent-owned work, letting the real shell executor own gates.

  before-advance runs after setup templates are written and before that setup
  step closes, allowing a test to introduce a deliberate gate failure."
  ([runtime root]
   (drive-skein-dir! runtime root {}))
  ([runtime root {:keys [before-advance]}]
   (let [run-id (target/run-id "skein-dir" root)]
     (loop [driven 0]
       (let [{:keys [reason ready] :as state} (wait-for-attention! runtime run-id)]
         (case reason
           :done state
           :stalled state
           :driver
           (let [_ (require-driver-budget! run-id driven)
                 step (first ready)
                 base ["advance" "skein-dir" (str root)]]
             (if (= "checkpoint" (:role step))
               (weaver/op! runtime 'dresser (conj base "--choice" "clean"))
               (do
                 (when-not (str/starts-with? (:title step) "Inspect ")
                   (write-step-files! root (:title step)))
                 (when before-advance (before-advance step))
                 (weaver/op! runtime 'dresser base)))
             (recur (inc driven)))))))))

(defn drive-spool-repo!
  "Drive all spool-repo agent work, leaving gates to the real shell executor."
  [runtime root]
  (let [run-id (target/run-id "spool-repo" root)]
    (loop [driven 0]
      (let [{:keys [reason ready] :as state} (wait-for-attention! runtime run-id)]
        (case reason
          :done state
          :stalled state
          :driver
          (let [_ (require-driver-budget! run-id driven)
                step (first ready)
                base ["advance" "spool-repo" (str root)]]
            (if (= "checkpoint" (:role step))
              (weaver/op! runtime 'dresser (conj base "--choice" "clean"))
              (do
                (when-not (str/starts-with? (:title step) "Inspect ")
                  (write-spool-repo-step-files! root (:title step)))
                (weaver/op! runtime 'dresser base)))
            (recur (inc driven))))))))

(defn latest-molecule-strands
  "Return every strand under run-id's latest molecule."
  [runtime run-id]
  (let [root-id (or (:id (workflow/current-root run-id))
                    (get-in (peek (workflow/run-history run-id)) [:root :id]))]
    (:strands (graph/subgraph runtime [root-id]))))

(defn poured-aspects
  "Return dresser aspect keys present in run-id's latest molecule."
  [runtime run-id]
  (into #{} (keep #(spool/attr-get % :dresser/aspect))
        (latest-molecule-strands runtime run-id)))

(defn all-run-strands
  "Return strands from every retained molecule for run-id."
  [runtime run-id]
  (mapcat (fn [molecule]
            (:strands (graph/subgraph runtime [(get-in molecule [:root :id])])))
          (workflow/run-history run-id)))

(defn capture-exception
  "Return an ExceptionInfo thrown by f, or nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      exception)))

(defn assert-done!
  "Assert a driver state completed and include its diagnostic shape on failure."
  [state]
  (is (= :done (:reason state)) (pr-str state)))

(defn stamp-and-assert-current!
  "Stamp every aspect and assert both stamp and plan report current."
  [root aspect-keys]
  (doseq [aspect-key aspect-keys]
    (let [result (dresser/stamp! aspect-key root)]
      (is (= aspect-key (:aspect result)))
      (is (= :current (:plan result)))))
  (let [stamp (receipt/read-receipt root)
        planned (dresser/plan root)]
    (is (= (set aspect-keys) (set (keys (:aspects stamp)))))
    (is (every? #{:current} (map (:aspects planned) aspect-keys)))
    stamp))
