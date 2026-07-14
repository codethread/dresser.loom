(ns skein.spools.dresser.receipt
  "Filesystem receipt codec and pure receipt/registry plan classification."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [skein.api.spool.alpha :as spool])
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
        (when-not (map? value)
          (spool/fail! "Dresser receipt must contain an EDN map"
                       {:root (str root)
                        :path (str file)
                        :reason :not-a-map
                        :value value}))
        value))))

(defn- move-atomically! [source target options]
  (Files/move source target options))

(defn- write-receipt-with-move!
  "Three-argument atomic-write seam; move-fn is injectable for failure tests."
  [root receipt move-fn]
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
