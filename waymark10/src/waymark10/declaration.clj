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
   :kind :states :initial :terminal :plural :nav :domain :adoption
   ;; data
   :schema :create-schema :fields :shape :upcasts
   ;; behavior
   :flow :actions :create-guards :create-action-names :allow-dead :renames
   ;; the standalone verdict (spec-decision-kind): one key desugaring
   ;; into states, verdict actions, decider walls, the leash and the
   ;; pacing — a spelling, never a mechanism, resolved before the
   ;; check battery and before the fingerprint
   :decision
   ;; who sees their OWN rows of this kind with no grant at all
   ;; (spec-decision-kind seam 2): the negotiation courtesy that used
   ;; to be a literal set of kind names in grants.clj, relocated to
   ;; the seven declarations that always owned it
   :own-surface
   ;; derivations & constraints
   :derived :one-of :unique :part-scopes
   ;; collection surface
   :filterable :sortable :faceted :worksheet :default-filters :views
   ;; edges
   :owns :links :related
   ;; advertisement
   :display :label-template :summary :deviations
   ;; hooks & engine weaves
   :on-create :mirror
   ;; what the log carries forward past the write (spec-decision-record,
   ;; spec-time-travel): {:judgment true} retains the evidence the
   ;; guards read, {:data true} the document as it stood. ONE map, two
   ;; entries, default off — write amplification on the one write path
   ;; is bytes on every transition of every kind forever
   :retain
   ;; proof — declared policy tests (waymark10.scenario), judged where
   ;; they can be judged for free and by the suite where they cannot.
   ;; Data, never law: fingerprint-of's whitelist does not name them,
   ;; so editing a scenario mints no revision
   :scenarios])

(def ^:private action-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        action-keys))

(def filter-ops
  "The closed set of ops a :filterable entry (or colocated :filter)
  may declare — the collection grammar (waymark10.server.collections)
  serves exactly these: field= (:eq), field=a,b (:in), field_ne=
  (:ne), field_gte=/field_lte= (:range), field_after= (:after),
  field_before= (:before), field_set= (:set), field_contains=
  (:contains). checks.clj refuses an op outside this set at boot."
  #{:eq :in :ne :range :after :before :set :contains})

(def link-keys
  "One :links entry's whole authored surface — the same closure
  action-keys already gives :actions, extended here so a typo'd link
  key (:rel misspelled, an :emebd) refuses at the def site instead of
  rendering nothing. :download marks a byte route (a redirect or a
  file body): clients navigate it in the BROWSER, never through an
  in-app XHR router that would swallow the redirect. :external marks
  an :href that leaves this engine entirely (another engine's row, a
  foreign system): the same real-browser navigation as :download,
  under an honest name — a client must never read an origin hop as
  bytes to save."
  [:rel :owns :edge :href :kind :summary :badge :embed :where :download
   :external])

(def ^:private link-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        link-keys))

(def view-keys
  "One :views entry's whole authored surface — an alternate collection
  view (:deck swipe-triage, :feed sequential). The same closure the
  other surfaces get: a typo'd view key refuses at the def site instead
  of advertising nothing. The semantic rules (deck gestures name
  reversible actions, the queue drains itself) live in checks.clj."
  [:name :kind :where :right :left :card :display])

(def ^:private view-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        view-keys))

(def ^:private declaration-schema
  (m/schema
   (-> [:map {:closed true}]
       (into (map (fn [k] [k {:optional true} :any]))
             (remove #{:actions :links :views} top-level-keys))
       (conj [:actions {:optional true} [:map-of :keyword action-schema]])
       (conj [:links {:optional true} [:vector link-schema]])
       (conj [:views {:optional true} [:vector view-schema]]))))

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
      (case (first (:path e))
        :actions (refuse! kind-label e action-keys "action")
        :views (refuse! kind-label e view-keys "view")
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
