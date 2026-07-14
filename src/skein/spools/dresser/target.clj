(ns skein.spools.dresser.target
  "Canonical target-root resolution and stable dresser run identities."
  (:require [skein.api.spool.alpha :as spool])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption NoSuchFileException Path Paths)
           (java.security MessageDigest)))

(def ^:private no-link-options (make-array LinkOption 0))

(defn- path-of ^Path [path]
  (Paths/get (str path) (make-array String 0)))

(defn resolve-root
  "Return path's canonical absolute git-worktree root, or fail with context."
  [path]
  (let [offending (str path)]
    (try
      (let [root (.toRealPath (path-of path) no-link-options)]
        (when-not (Files/isDirectory root no-link-options)
          (spool/fail! "Dresser target root is not a directory"
                       {:path offending :resolved (str root) :reason :not-directory}))
        (let [git-entry (.resolve root ".git")]
          (when-not (or (Files/isDirectory git-entry no-link-options)
                        (Files/isRegularFile git-entry no-link-options))
            (spool/fail! "Dresser target root has no .git entry"
                         {:path offending
                          :resolved (str root)
                          :reason :not-git-root})))
        (str root))
      (catch NoSuchFileException exception
        (spool/fail! "Dresser target root does not exist"
                     {:path offending :reason :missing} exception))
      (catch clojure.lang.ExceptionInfo exception
        (throw exception))
      (catch Exception exception
        (spool/fail! "Dresser target root cannot be resolved"
                     {:path offending :reason :unresolvable} exception)))))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- identity-for [prefix flavour root]
  (let [canonical (resolve-root root)
        basename (str (.getFileName (path-of canonical)))]
    (str prefix (name flavour) "-" basename "-" (subs (sha256 canonical) 0 8))))

(defn run-id
  "Return the stable setup run id for flavour and target root."
  [flavour root]
  (identity-for "dresser-" flavour root))

(defn verify-run-id
  "Return the stable verify-only run id for flavour and target root."
  [flavour root]
  (identity-for "dresser-verify-" flavour root))
