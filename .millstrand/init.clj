(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval.
(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]})
