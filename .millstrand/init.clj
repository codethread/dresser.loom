(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval.
(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last. Each source collects its contribution and named lifecycle effects;
;; declarations here name only source targets and world policy.
