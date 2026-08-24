(ns hooks.waymark10.resource
  "The defresource editor gate: unknown declaration / action / flow-opt
  keys get in-editor findings, and the macro reads as a def so vars
  resolve. Computed (non-literal) maps lint nothing — the runtime
  declaration gate still catches them.

  The three key sets are COPIES of waymark10.declaration/top-level-keys,
  waymark10.declaration/action-keys, and waymark10.resource/flow-opt-keys
  — a kondo hook cannot require project code. Drift dies in CI, not in
  an editor: waymark10.declaration-test reads this file and asserts the
  sets stay equal."
  (:require [clj-kondo.hooks-api :as api]))

(def top-level-keys
  #{:kind :states :initial :terminal :plural :nav :domain :adoption
    :schema :create-schema :fields :shape :upcasts
    :flow :actions :create-guards :create-action-names :allow-dead :renames
    :derived :one-of :unique :part-scopes
    :filterable :sortable :faceted :worksheet :default-filters :views
    :owns :links :related
    :display :label-template :summary :deviations
    :on-create :mirror
    :retain
    :scenarios})

(def action-keys
  #{:from :to :input :guards :safety :display :handler :emits :edit
    :place :bulk :batch :waives :touches :unless :record :undo})

(def flow-opt-keys
  #{:requires :args :input :confirm :undo :one-way :safety :display
    :record :edit :place :handler :emits :waives :unless :touches})

(defn- entries [node]
  (when (and node (api/map-node? node))
    (partition 2 (:children node))))

(defn- lint-keys! [node legal what]
  (doseq [[k _] (entries node)
          :let [kw (api/sexpr k)]
          :when (and (keyword? kw) (not (contains? legal kw)))]
    (api/reg-finding! (assoc (meta k)
                             :message (str "unknown " what " key " kw)
                             :type :waymark10/unknown-key))))

(defn- val-of [map-node key]
  (some (fn [[k v]] (when (= key (api/sexpr k)) v))
        (entries map-node)))

(defn defresource [{:keys [node]}]
  (let [[_ name-node rmap] (:children node)]
    (when (and name-node rmap)
      (when (api/map-node? rmap)
        (lint-keys! rmap top-level-keys "declaration")
        (doseq [[_ a] (entries (val-of rmap :actions))]
          (when (api/map-node? a)
            (lint-keys! a action-keys "action")))
        (when-some [flow (val-of rmap :flow)]
          (when (api/vector-node? flow)
            (doseq [row (:children flow)
                    :when (api/vector-node? row)]
              (lint-keys! (nth (:children row) 3 nil)
                          flow-opt-keys "flow opt")))))
      ;; defresource IS (def name rmap): vars resolve, noise dies
      {:node (api/list-node [(api/token-node 'def) name-node rmap])})))
