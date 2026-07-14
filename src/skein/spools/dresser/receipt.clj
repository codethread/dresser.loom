(ns skein.spools.dresser.receipt
  "Filesystem receipt codec and pure receipt/registry plan classification."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [skein.api.spool.alpha :as spool]
            [skein.spools.dresser.specs :as specs])
  (:import (java.nio.file CopyOption Files StandardCopyOption)))

(defn- receipt-file [root]
  (io/file (str root) ".skein" "conventions.edn"))

(defn read-receipt
  "Read root's dresser receipt. Return nil if absent; reject malformed data."
  [root]
  (let [file (receipt-file root)]
    (when (.exists file)
      (let [value (try
                    (edn/read-string (slurp file))
                    (catch Exception exception
                      (spool/fail! "Cannot parse dresser receipt"
                                   {:root (str root)
                                    :path (str file)
                                    :reason :invalid-edn}
                                   exception)))]
        (try
          (spool/require-valid! ::specs/receipt value
                                "Dresser receipt has an invalid shape")
          (catch clojure.lang.ExceptionInfo exception
            (spool/fail! "Dresser receipt has an invalid shape"
                         (assoc (ex-data exception)
                                :root (str root)
                                :path (str file)
                                :reason :invalid-shape)
                         exception)))))))

(defn- move-atomically! [source target options]
  (Files/move source target options))

(defn- write-receipt-with-move!
  "Three-argument atomic-write seam; move-fn is injectable for failure tests."
  [root receipt move-fn]
  (spool/require-valid! ::specs/receipt receipt
                        "Dresser receipt has an invalid shape")
  (let [directory (io/file (str root) ".skein")
        target (.toPath (receipt-file root))]
    (Files/createDirectories
     (.toPath directory)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (let [temporary (Files/createTempFile (.toPath directory)
                                          ".conventions-"
                                          ".tmp"
                                          (make-array java.nio.file.attribute.FileAttribute 0))
          options (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING])]
      (try
        (spit (.toFile temporary) (str (pr-str receipt) "\n"))
        (move-fn temporary target options)
        receipt
        (finally
          (Files/deleteIfExists temporary))))))

(defn write-receipt!
  "Atomically replace root's dresser receipt and return receipt."
  [root receipt]
  (write-receipt-with-move! root receipt move-atomically!))

(defn merge-aspect
  "Purely stamp one aspect and current release provenance into receipt."
  [receipt aspect-key entry release fingerprint applied-at]
  (spool/require-valid! ::specs/receipt receipt
                        "Dresser receipt has an invalid shape")
  (spool/require-valid! ::specs/aspect-key aspect-key
                        "Dresser receipt aspect key has an invalid shape")
  (spool/require-valid! ::specs/version (:version entry)
                        "Dresser registry aspect version has an invalid shape")
  (spool/require-valid! ::specs/release release
                        "Dresser release has an invalid shape")
  (spool/require-valid! ::specs/fingerprint fingerprint
                        "Dresser fingerprint has an invalid shape")
  (spool/require-valid! ::specs/applied-at applied-at
                        "Dresser applied-at date has an invalid shape")
  (-> (or receipt {})
      (assoc :dresser/release release
             :dresser/fingerprint fingerprint)
      (assoc-in [:aspects aspect-key]
                {:version (:version entry)
                 :release release
                 :applied-at applied-at})))

(defn- classify-present [receipt registry-view receipt-entry registry-version]
  (let [receipt-release (:dresser/release receipt)
        registry-release (:release registry-view)
        receipt-version (:version receipt-entry)]
    (cond
      (or (> receipt-version registry-version)
          (> receipt-release registry-release)) :ahead
      (< receipt-version registry-version) :pending
      (= (:dresser/fingerprint receipt)
         (get (:releases registry-view) receipt-release)) :current
      :else :divergent)))

(defn plan-classification
  "Classify every registry and receipt aspect against published lineage."
  [receipt registry-view]
  (spool/require-valid! ::specs/receipt receipt
                        "Dresser receipt has an invalid shape")
  (spool/require-valid! ::specs/registry-view registry-view
                        "Dresser registry view has an invalid shape")
  (let [registry-aspects (:aspects registry-view)
        receipt-aspects (:aspects receipt)]
    (into (sorted-map)
          (for [aspect-key (into (set (keys registry-aspects))
                                 (keys receipt-aspects))]
            [aspect-key
             (cond
               (not (contains? registry-aspects aspect-key)) :removed
               (not (contains? receipt-aspects aspect-key)) :new
               :else (classify-present receipt
                                       registry-view
                                       (get receipt-aspects aspect-key)
                                       (get registry-aspects aspect-key)))]))))
