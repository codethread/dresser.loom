(ns skein.spools.dresser.specs
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
(s/def ::registry-entry
  (s/keys :req-un [::version ::deps ::owned ::inspect ::setup ::gates]))
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
(s/def ::registry-aspects (s/map-of ::aspect-key nat-int? :min-count 1))
(s/def ::registry-view
  (s/and
   map?
   #(every? (set (keys %)) [:release :fingerprint :releases :aspects])
   #(nat-int? (:release %))
   #(non-blank-string? (:fingerprint %))
   #(s/valid? ::releases (:releases %))
   #(s/valid? ::registry-aspects (:aspects %))))

(s/def ::template-name non-blank-string?)
(s/def ::template-param-key (s/or :keyword keyword? :string non-blank-string?))
(s/def ::template-param-value string?)
(s/def ::template-params (s/map-of ::template-param-key ::template-param-value))
(s/def ::template-input
  (s/and map?
         #(s/valid? ::template-name (:name %))
         #(s/valid? ::template-params (:params %))))

(s/def ::root root?)
(s/def ::verify-only boolean?)
(s/def ::aspects (s/coll-of ::aspect-key :kind vector?))
(s/def ::aspect-workflow-input
  (s/keys :req-un [::root] :opt-un [::verify-only]))
(s/def ::flavour-workflow-input
  (s/keys :req-un [::root] :opt-un [::verify-only ::aspects]))
(s/def ::reason non-blank-string?)
(s/def ::abort-workflow-input (s/keys :req-un [::reason]))

(s/def ::flavour non-blank-string?)
(s/def ::selection (s/nilable string?))
(s/def ::verify (s/nilable boolean?))
(s/def ::choice non-blank-string?)
(s/def ::input map?)
(s/def ::notes string?)
(s/def ::step non-blank-string?)
(s/def ::advance-opts
  (s/keys :opt-un [::choice ::input ::notes ::step]))
(s/def ::start-input
  (s/keys :req-un [::flavour ::root ::verify-only ::selection]))
(s/def ::next-input (s/keys :req-un [::flavour ::root ::verify]))
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

(s/def ::subcommand non-blank-string?)
(s/def ::op-input
  (s/and map?
         #(contains? % :op/args)
         #(map? (:op/args %))
         #(s/valid? ::subcommand (get-in % [:op/args :subcommand]))))

(def op-args-specs
  {"about" (s/keys :req-un [::subcommand])
   "aspects" (s/keys :req-un [::subcommand])
   "template" (s/and map?
                     #(s/valid? ::template-name (:name %))
                     #(or (not (contains? % :param))
                          (s/valid? ::template-params (:param %))))
   "plan" (s/keys :req-un [::subcommand ::root])
   "start" (s/and map?
                  #(s/valid? ::flavour (:flavour %))
                  #(s/valid? ::root (:root %))
                  #(or (not (contains? % :aspects)) (string? (:aspects %))))
   "verify" (s/and map?
                   #(s/valid? ::flavour (:flavour %))
                   #(s/valid? ::root (:root %))
                   #(or (not (contains? % :aspects)) (string? (:aspects %))))
   "next" (s/and map?
                 #(s/valid? ::flavour (:flavour %))
                 #(s/valid? ::root (:root %))
                 #(or (not (contains? % :verify)) (boolean? (:verify %))))
   "advance" (s/and map?
                    #(s/valid? ::flavour (:flavour %))
                    #(s/valid? ::root (:root %))
                    #(or (not (contains? % :verify)) (boolean? (:verify %)))
                    #(s/valid? ::advance-opts
                               (select-keys % [:choice :input :notes :step])))
   "stamp" (s/and map?
                  #(s/valid? ::aspect-key (:aspect %))
                  #(s/valid? ::root (:root %)))})
