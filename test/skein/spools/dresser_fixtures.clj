(ns skein.spools.dresser-fixtures
  "Filesystem and workflow-driving helpers for dresser's disposable-world e2e tests."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.dresser.target :as target]
            [skein.spools.dresser.templates :as templates]
            [skein.spools.executors.shell :as shell-executor]
            [skein.spools.workflow :as workflow])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent Executors ThreadFactory)))

(defn install-serial-shell!
  "Install the real shell executor with a deterministic one-worker test pool."
  []
  (let [workers (Executors/newSingleThreadExecutor
                 (reify ThreadFactory
                   (newThread [_ runnable]
                     (doto (Thread. runnable "dresser-test-reed-worker")
                       (.setDaemon true)))))
        new-state (fn []
                    {:scan-monitor (Object.)
                     :worker-executor workers
                     :close-fn #(.shutdownNow workers)})]
    (with-redefs-fn {(ns-resolve 'skein.spools.executors.shell 'new-state)
                     new-state}
      shell-executor/install!)))

(defn temp-directory ^Path []
  (Files/createTempDirectory "dresser-e2e-" (make-array FileAttribute 0)))

(defn delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       ~@body
       (finally (delete-tree! ~binding)))))

(defn git-init-root! ^Path [^Path parent name]
  (let [root (.resolve parent name)
        {:keys [exit err]} (sh/sh "git" "init" "--quiet" (str root))]
    (when-not (zero? exit)
      (throw (ex-info "git init failed" {:root (str root) :exit exit :stderr err})))
    root))

(defn- write-template! [root relative template-key]
  (let [file (io/file (str root) relative)]
    (io/make-parents file)
    (spit file (templates/template template-key))))

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
  (let [ready (workflow/next-steps run-id)
        strands (mapv #(weaver/show runtime (:id %)) ready)
        stalled (some #(when (spool/attr-get % :shell/error) %) strands)
        driver-ready (filterv #(not= "shell" (:gate %)) ready)]
    (cond
      (workflow/done? run-id) {:reason :done :ready ready}
      stalled {:reason :stalled :ready ready :gate stalled}
      (seq driver-ready) {:reason :driver :ready driver-ready}
      :else nil)))

(defn wait-for-attention!
  "Poll until a run is done, stalled, or needs its driving agent."
  [runtime run-id]
  (spool/poll-until-deadline!
   {:deadline (+ (System/currentTimeMillis) 600000)
    :poll-ms 25
    :check #(attention runtime run-id)
    :pred->result identity
    :on-timeout (fn [_]
                  (throw (ex-info "Timed out waiting for dresser run"
                                  {:run-id run-id
                                   :ready (workflow/next-steps run-id)})))}))

(defn await-next-clock-second!
  "Cross the store's second-resolution timestamp boundary via the shared poller."
  []
  (let [second (quot (System/currentTimeMillis) 1000)]
    (spool/poll-until-deadline!
     {:deadline (+ (System/currentTimeMillis) 2000)
      :poll-ms 10
      :check #(quot (System/currentTimeMillis) 1000)
      :pred->result #(when (> % second) %)
      :on-timeout (fn [last-second]
                    (throw (ex-info "Clock did not cross a timestamp boundary"
                                    {:initial second :last last-second})))})))

(defn drive-skein-dir!
  "Drive all agent-owned work, letting the real shell executor own gates.

  before-advance runs after setup templates are written and before that setup
  step closes, allowing a test to introduce a deliberate gate failure."
  ([runtime root]
   (drive-skein-dir! runtime root {}))
  ([runtime root {:keys [before-advance]}]
   (let [run-id (target/run-id "skein-dir" root)]
     (loop []
       (let [{:keys [reason ready] :as state} (wait-for-attention! runtime run-id)]
         (case reason
           :done state
           :stalled state
           :driver
           (let [step (first ready)
                 base ["advance" "skein-dir" (str root)]]
             (if (= "checkpoint" (:kind step))
               (weaver/op! runtime 'dresser (conj base "--choice" "clean"))
               (do
                 (when-not (str/starts-with? (:title step) "Inspect ")
                   (write-step-files! root (:title step)))
                 (when before-advance (before-advance step))
                 (weaver/op! runtime 'dresser
                             (conj base "--notes" "fixture driver completed step"))))
             (recur))))))))
