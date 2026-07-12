(ns waymark10.declaration
  "The authored-surface gate: the resource map's KEY SETS, closed
  before normalize-resource runs.

  Leaves stay :any — malli forms, handler fns, guard maps, upcast fns
  are other gates' law (the schema registry, normalize-action,
  parse-flow-row, desugar-fields). The one law added here is closure:
  an unknown top-level or action key refuses at the def site with a
  path, where today a typo like :filtrable or :guard silently
  declares nothing. The usability doctrine turned inward — everything
  the framework knows about its own vocabulary, the author sees
  without decoding."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def action-keys
  "normalize-action's whole authored surface, plus the :undo pointer
  verify-undo-pointers reads and strips."
  [:from :to :input :guards :safety :display :handler :emits :edit
   :place :bulk :batch :waives :touches :unless :record :undo])

(def top-level-keys
  "Everything normalize-resource, the check batteries, assembly, and
  the engine's own kinds read from an AUTHORED map. A new declaration
  key that skips this list refuses every boot — loudly, at the def
  site, which is the point."
  [;; identity & machine
   :kind :states :initial :terminal :plural :nav :adoption
   ;; data
   :schema :create-schema :fields :shape :upcasts
   ;; behavior
   :flow :actions :create-guards :create-action-names :allow-dead :renames
   ;; derivations & constraints
   :derived :one-of :unique :part-scopes
   ;; collection surface
   :filterable :sortable :faceted
   ;; edges
   :owns :links :related
   ;; advertisement
   :display :label-template :summary :deviations
   ;; hooks & engine weaves
   :on-create :mirror])

(def ^:private action-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        action-keys))

(def ^:private declaration-schema
  (m/schema
   (-> [:map {:closed true}]
       (into (map (fn [k] [k {:optional true} :any]))
             (remove #{:actions} top-level-keys))
       (conj [:actions {:optional true} [:map-of :keyword action-schema]]))))

(def ^:private lone-action-schema (m/schema action-schema))

(defn- render-path [in]
  (if (empty? in)
    "top level"
    (str/join "." (map #(if (keyword? %) (name %) (str %)) in))))

(defn errors
  "Every declaration-surface violation as {:path [...] :message ...},
  nil when the key sets are clean."
  [rmap]
  (when-some [ex (m/explain declaration-schema rmap)]
    (seq (for [{:keys [in] :as e} (:errors ex)]
           {:path (vec in) :message (me/error-message e)}))))

(defn- refuse!
  [kind-label {:keys [path message]} legal what]
  (throw (t/definition-error
          (str kind-label " [declaration] " (render-path path)
               " — " message "; " what " keys are "
               (vec (sort legal)))
          {:check :declaration :path path})))

(defn check!
  "Throw the first declaration-surface violation as a definition
  error carrying {:check :declaration :path [...]}."
  [rmap]
  (when-some [[e & _] (errors rmap)]
    (let [kind-label (or (some-> (:kind rmap) name) "(unnamed kind)")]
      (if (= :actions (first (:path e)))
        (refuse! kind-label e action-keys "action")
        (refuse! kind-label e top-level-keys "declaration"))))
  rmap)

(defn check-action!
  "The same gate over one action map alone — what defaction's eager
  def-site validation runs, so a stray key fails at its own line with
  the path spelled [:actions aname key]."
  [kind aname a]
  (when (map? a)
    (when-some [ex (m/explain lone-action-schema a)]
      (when-some [{:keys [in] :as e} (first (:errors ex))]
        (refuse! (str (some-> kind name) " action " (some-> aname name))
                 {:path (into [:actions aname] in)
                  :message (me/error-message e)}
                 action-keys "action"))))
  a)
