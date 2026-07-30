(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval. Its source
;; collects the operation and lifecycle forms that own its contribution.
(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last. Each source collects its contribution and named lifecycle effects;
;; declarations here name only source targets and world policy.
(runtime/module! runtime :guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]
   :required? true})

(runtime/module! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :after [:guild]
   :required? true})

(runtime/module! runtime :kanban/peering
  {:ns 'ct.spools.kanban.peering
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :required? true})
