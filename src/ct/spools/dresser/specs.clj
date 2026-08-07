(ns ct.spools.dresser.specs
  "Named boundary-data specs shared across dresser's public seams."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn aspect-key? [value]
  (and (non-blank-string? value)
       (boolean (re-matches #"[^/]+/[^/]+" value))))

(defn root? [value]
  (and (some? value) (non-blank-string? (str value))))

(s/def ::aspect-key aspect-key?)
(s/def ::version nat-int?)
(s/def ::release nat-int?)
(s/def ::fingerprint non-blank-string?)
(s/def ::id keyword?)
(s/def ::title non-blank-string?)
(s/def ::instruction non-blank-string?)
(s/def ::templates (s/coll-of non-blank-string? :kind vector?))
(s/def ::setup-entry (s/keys :req-un [::id ::title ::instruction ::templates]))
(s/def ::argv (s/coll-of string? :kind vector? :min-count 1))
(s/def ::timeout-secs pos-int?)
(s/def ::gate-entry (s/keys :req-un [::id ::title ::argv ::timeout-secs]))
(s/def ::deps (s/coll-of ::aspect-key :kind vector?))
(s/def ::owned (s/coll-of non-blank-string? :kind vector?))
(s/def ::inspect non-blank-string?)
(s/def ::setup (s/coll-of ::setup-entry :kind vector?))
(s/def ::gates (s/coll-of ::gate-entry :kind vector? :min-count 1))
;; A registry checkpoint is a decision no shell gate can make or verify, so its
;; whole contract is the choice set: every choice needs an operator-readable
;; label and description, and any choice demanding evidence names the spec that
;; judges it rather than describing the map in prose.
(s/def ::checkpoint-choice
  (s/and map?
         #(keyword? (:key %))
         #(non-blank-string? (:label %))
         #(non-blank-string? (:description %))
         #(or (not (contains? % :next)) (keyword? (:next %)))
         #(or (not (contains? % :input))
              (and (map? (:input %))
                   (qualified-keyword? (:spec (:input %)))
                   (non-blank-string? (:doc (:input %)))))))
(s/def ::choices (s/coll-of ::checkpoint-choice :kind vector? :min-count 2))
(s/def ::checkpoint-entry
  (s/keys :req-un [::id ::title ::instruction ::choices]))
(s/def ::checkpoints (s/coll-of ::checkpoint-entry :kind vector? :min-count 1))
(s/def ::registry-entry
  (s/keys :req-un [::version ::deps ::owned ::inspect ::setup ::gates]
          :opt-un [::checkpoints]))
(s/def ::registry (s/map-of ::aspect-key ::registry-entry :min-count 1))

(s/def ::applied-at non-blank-string?)
(s/def ::receipt-entry (s/keys :req-un [::version ::release ::applied-at]))
(s/def ::receipt-aspects (s/map-of ::aspect-key ::receipt-entry))
(s/def :dresser/release nat-int?)
(s/def :dresser/fingerprint non-blank-string?)
(s/def ::receipt-map
  (s/and
   (s/keys :req [:dresser/release :dresser/fingerprint])
   #(contains? % :aspects)
   #(s/valid? ::receipt-aspects (:aspects %))))
(s/def ::receipt (s/nilable ::receipt-map))

(s/def ::releases (s/map-of nat-int? non-blank-string? :min-count 1))
(s/def ::classification-aspects (s/map-of ::aspect-key nat-int? :min-count 1))
(s/def ::classification-registry-view
  (s/and
   map?
   #(every? (set (keys %)) [:release :fingerprint :releases :aspects])
   #(nat-int? (:release %))
   #(non-blank-string? (:fingerprint %))
   #(s/valid? ::releases (:releases %))
   #(s/valid? ::classification-aspects (:aspects %))))

(s/def ::registry-projection-entry
  (s/and
   map?
   #(= #{:version :deps :gates} (set (keys %)))
   #(s/valid? ::version (:version %))
   #(s/valid? ::deps (:deps %))
   #(s/valid? (s/coll-of non-blank-string? :kind vector?) (:gates %))))
(s/def ::registry-projection-aspects
  (s/map-of ::aspect-key ::registry-projection-entry :min-count 1))
(s/def ::registry-view
  (s/and
   map?
   #(= #{:release :fingerprint :releases :aspects} (set (keys %)))
   #(s/valid? ::release (:release %))
   #(s/valid? ::fingerprint (:fingerprint %))
   #(s/valid? ::releases (:releases %))
   #(s/valid? ::registry-projection-aspects (:aspects %))))

(s/def ::topology-step
  (s/and
   map?
   #(= #{:id :role :depends-on} (set (keys %)))
   #(keyword? (:id %))
   #(non-blank-string? (:role %))
   #(s/valid? (s/coll-of keyword? :kind vector?) (:depends-on %))))
(s/def ::topology-steps (s/coll-of ::topology-step :kind vector? :min-count 1))
(s/def ::topology-gates (s/coll-of keyword? :kind vector? :min-count 1))
(s/def ::topology-mode
  (s/and
   map?
   #(= #{:steps :gates} (set (keys %)))
   #(s/valid? ::topology-steps (:steps %))
   #(s/valid? ::topology-gates (:gates %))))
(s/def ::topology-modes
  (s/and
   map?
   #(= #{:setup :verify-only} (set (keys %)))
   #(s/valid? ::topology-mode (:setup %))
   #(s/valid? ::topology-mode (:verify-only %))))
(s/def ::topology (s/map-of ::aspect-key ::topology-modes :min-count 1))

(s/def ::template-name non-blank-string?)
(s/def ::template-param-key (s/or :keyword keyword? :string non-blank-string?))
(s/def ::template-param-value string?)
(s/def ::template-params (s/map-of ::template-param-key ::template-param-value))
(defn- template-param [params key]
  (if (contains? params key)
    (get params key)
    (get params (name key))))

(defn immutable-sha? [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-f]{40}" value))))

(s/def ::millstrand-sha immutable-sha?)
(s/def ::quality-template-params
  (s/and
   ::template-params
   #(non-blank-string? (template-param % :name))
   #(s/valid? ::millstrand-sha (template-param % :millstrand-sha))))

(defn- template-params-spec [template-name]
  (case template-name
    "spool-repo/quality.yml" ::quality-template-params
    ::template-params))

(s/def ::template-input
  (s/and map?
         #(s/valid? ::template-name (:name %))
         #(s/valid? (template-params-spec (:name %)) (:params %))))

(s/def ::root root?)
(s/def ::verify-only boolean?)
(s/def ::aspects (s/coll-of ::aspect-key :kind vector?))
(s/def ::aspect-workflow-input
  (s/keys :req-un [::root] :opt-un [::verify-only]))
;; An umbrella's aspect set belongs to the definition, not to a run's params:
;; the engine's :condition grammar cannot exclude a `call`, so which aspects a
;; definition covers is fixed where it is authored and no param can move it.
(s/def ::flavour-workflow-input
  (s/keys :req-un [::root] :opt-un [::verify-only]))
(s/def ::reason non-blank-string?)
(s/def ::abort-workflow-input (s/keys :req-un [::reason]))
(s/def ::decisions non-blank-string?)
(s/def ::conflict-decisions-input (s/keys :req-un [::decisions]))
(s/def ::site-url non-blank-string?)
(s/def ::pages-enabled-input (s/keys :req-un [::site-url]))

(s/def ::flavour non-blank-string?)
(s/def ::selection (s/nilable string?))
(s/def ::verify (s/nilable boolean?))
(s/def ::choice non-blank-string?)
(s/def ::input map?)
(s/def ::step non-blank-string?)
(s/def ::advance-opts
  (s/keys :opt-un [::choice ::input ::step]))
(s/def ::start-input
  (s/keys :req-un [::flavour ::root ::verify-only ::selection]))
(s/def ::ready-input (s/keys :req-un [::flavour ::root ::verify]))
(s/def ::advance-input
  (s/and map?
         #(s/valid? ::flavour (:flavour %))
         #(s/valid? ::root (:root %))
         #(or (nil? (:verify %)) (boolean? (:verify %)))
         #(s/valid? ::advance-opts (:opts %))))
(s/def ::stamp-input
  (s/and map?
         #(s/valid? ::aspect-key (:aspect %))
         #(s/valid? ::root (:root %))))

;; The weaver hands :subcommand through as the matched path vector, e.g.
;; ["advance"]; dresser's surface is depth-1 so every path has one segment.
(s/def ::subcommand (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def ::op-input
  (s/and map?
         #(contains? % :op/args)
         #(map? (:op/args %))
         #(s/valid? ::subcommand (get-in % [:op/args :subcommand]))))

(def op-args-specs
  {["about"] (s/keys :req-un [::subcommand])
   ["aspects"] (s/keys :req-un [::subcommand])
   ["template"] (s/and map?
                       #(s/valid? ::template-name (:name %))
                       #(or (not (contains? % :param))
                            (s/valid? ::template-params (:param %))))
   ["plan"] (s/keys :req-un [::subcommand ::root])
   ["start"] (s/and map?
                    #(s/valid? ::flavour (:flavour %))
                    #(s/valid? ::root (:root %))
                    #(or (not (contains? % :aspects)) (string? (:aspects %))))
   ["verify"] (s/and map?
                     #(s/valid? ::flavour (:flavour %))
                     #(s/valid? ::root (:root %))
                     #(or (not (contains? % :aspects)) (string? (:aspects %))))
   ["next"] (s/and map?
                   #(s/valid? ::flavour (:flavour %))
                   #(s/valid? ::root (:root %))
                   #(or (not (contains? % :verify)) (boolean? (:verify %))))
   ["advance"] (s/and map?
                      #(s/valid? ::flavour (:flavour %))
                      #(s/valid? ::root (:root %))
                      #(or (not (contains? % :verify)) (boolean? (:verify %)))
                      #(s/valid? ::advance-opts
                                 (select-keys % [:choice :input :step])))
   ["stamp"] (s/and map?
                    #(s/valid? ::aspect-key (:aspect %))
                    #(s/valid? ::root (:root %)))})
