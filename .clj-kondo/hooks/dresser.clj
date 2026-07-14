(ns hooks.dresser
  (:require [clj-kondo.hooks-api :as api]))

(defn with-temp-dir
  "Analyze [binding] as the resource symbol bound by with-temp-dir."
  [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        binding (first (:children bindings))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [binding (api/token-node nil)])
                   body))}))
