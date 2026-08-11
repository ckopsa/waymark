(ns scratch)

(require '[waymark10.dev :as dev] :reload-all)

(defresource task
  {:kind :task
   :states [:todo :done]
   :initial :todo
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 120}]]]
   :actions
   {:complete {:from #{:todo} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Marking done is final for now."}}}})

(defresource fart
  {:kind :fart
   :states [:taking_form :ready_to_expel :expelled]
   :initial :taking_form
   :terminal #{:expelled}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:smell [:string {:min 1 :max 120}]]
            [:potency [:string {:min 1 :max 120}]]
            ]
   :actions
   {
    :compress {:from #{:taking_form} :to :ready_to_expel
               :safety {:idempotent true :reversible true :confirm false}}
    :decompress {:from #{:ready_to_expel} :to :taking_form
               :safety {:idempotent true :reversible true :confirm false}}
    :expel {:from #{:ready_to_expel} :to :expelled
               :safety {:idempotent true :reversible false :confirm false :one-way "You can't undo a fart."}}}})

(def e (dev/scratch! [task]))
(def r (dev/create! e :task {:title "try waymark10"}))
(dev/act! e :task (:id r) :complete nil)
(dev/why-not e :task (:id r) :complete)
(dev/explain e :task)

(require '[waymark10.server.engine :as engine]
         '[waymark10.server.store.postgres :as pg] :reload-all)
;; task from before, or [waymark10.fixtures :as fx] for meal/plan

(def st (pg/storage "jdbc:postgresql://localhost:5433/waymark10_scratch?user=ckopsa"))
(def eng (engine/engine {:storage st :resources [task]}))
(def srv (engine/start! eng 8123))
(engine/stop! eng srv)

(require '[waymark10.dev :as dev] :reload-all)

(def h (dev/serve! [task]))
;; waymark10 UI: http://localhost:8123/api/-/ui

(def h (dev/restart! h [task task]))

(dev/stop! eng)
