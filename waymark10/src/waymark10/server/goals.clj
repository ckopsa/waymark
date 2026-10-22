(ns waymark10.server.goals
  "Goals (docs/spec-measures.md §3, build step 2): a measure's target is
  data, not law. A `goal` row names a measure by its token
  `<kind>.<name>`, an op (\">=\" or \"<=\") and a target; the owner
  restates it in place for a holiday and no law revision is wanted —
  the log keeps every restate's values, so an old target still reads.
  At most one goal per measure is active; retire it and another may
  be made.

  Opted into like `dashboard`: an app puts `goal` in its resources
  vector. The doors are held by the house's owner — the factory
  domain's, read the way `roles/manager?` reads it, so every person
  while it has none — and by any seat whose grant names `goal`: an
  agent that acts for nobody and the engine's own actors keep their
  grant rules here. A delegate is judged as its person.

  A declared measure's :default-target is not written here: the
  maintainer writes it as the first goal (maintainer/default-goals!)."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.measures :as measures]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.math BigDecimal)))

(set! *warn-on-reflection* true)

(def ^:private owner-domain
  ;; roles' factory-domain, spelled here: it is private there
  "factory")

;; ── who writes goals ────────────────────────────────────────────────

(defn goal-writer?
  "Does the ctx's principal walk the goal doors? A person passes when
  the factory domain has no owner, or when it is that owner. A
  delegate is judged as its person; any other agent and the engine's
  own actors keep their grant rules. A ctx with no read hooks passes."
  [ctx]
  (let [p (:principal ctx)
        delegate (when (= :agent (:type p))
                   (some-> (:acts-for p) str not-empty))
        pid (or delegate (some-> (:id p) str not-empty))
        read' (:read ctx)
        find' (:find ctx)]
    (cond
      (and (contains? #{:agent :system} (:type p)) (nil? delegate)) true
      (or (nil? read') (nil? find')) true
      :else
      (let [owner (some-> (first (find' :domain {:name owner-domain} {:limit 1}))
                          (get-in [:data :owner]) str not-empty)
            own (when pid
                  (or (read' :member pid)
                      (first (find' :member {:subject pid} {:limit 1}))))]
        (boolean
         (or (nil? owner)
             (= owner pid)
             (= owner (some-> (:id own) str))))))))

(g/defguard a-goal-writer
  {:reads [:principal :member :domain]
   :explain "A goal is the house owner's to set: this door wants the factory domain's owner, or a seat whose grant names `goal`."
   :open "The factory domain's owner is set by restating that domain; a seat is given `goal` on its grant."}
  [_row _inp ctx]
  (if (goal-writer? ctx) (t/allow) (t/deny)))

;; ── one active goal per measure ─────────────────────────────────────

(defn- decimal-of ^BigDecimal [v]
  (try (bigdec (str v)) (catch Exception _ nil)))

(defn target-text
  "A goal's target as the owner reads it: a percent for a :rate measure
  (0.95 → 95%), the plain number otherwise. rdef-of is the ctx
  :rdef-of consult; without it, or for a token no kind declares, the
  plain number."
  [rdef-of token target]
  (let [d (decimal-of target)
        [k n] (str/split (str token) #"\." 2)
        stat (when (and rdef-of k n)
               (some-> (rdef-of k) :measures (get (keyword n)) :stat measures/spelled))
        plain (fn [^BigDecimal x] (.toPlainString (.stripTrailingZeros x)))]
    (cond (nil? d) (str target)
          (= "rate" stat) (str (plain (.multiply d 100M)) "%")
          :else (plain d))))

(defn declared-measure?
  "Does `token`, `<kind>.<name>`, name a measure its kind declares?
  rdef-of is the ctx :rdef-of consult."
  [rdef-of token]
  (let [[k n] (str/split (str token) #"\." 2)]
    (boolean (and (not (str/blank? k)) (not (str/blank? n))
                  (some-> (rdef-of k) :measures (contains? (keyword n)))))))

(g/defguard measure-is-declared
  {:judges [:measure]
   :reads [:storage]
   :explain "`{measure}` names no declared measure; a goal's measure is `<kind>.<name>`, a measure its kind declares."
   :open "The declared measures are listed in discover, each by its `<kind>.<name>` token."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if (or (nil? (:measure inp)) (declared-measure? rdef-of (:measure inp)))
      (t/allow)
      (t/deny {:vars {:measure (str (:measure inp))}}))
    ;; no registry in scope (a render probe): advertise optimistically —
    ;; the write path always carries the consult
    (t/allow)))

(defn- active-goal
  "The active goal row naming `token`, or nil. Reads through the ctx
  :find hook (the write's own transaction)."
  [ctx token]
  (when-some [find' (:find ctx)]
    (first (find' :goal {:measure (str token) :state "active"} {:limit 1}))))

(g/defguard one-active-goal
  {:judges [:measure]
   :reads [:goal]
   :vars [:op :target :goal]
   :explain "`{measure}` already has a goal of {op} {target}; restate it — goal {goal}."
   :open "The active goals are the goals collection, one query away; a retired goal leaves its measure free."}
  [_row inp ctx]
  (if-some [held (when (some? (:measure inp)) (active-goal ctx (:measure inp)))]
    (t/deny {:vars {:measure (str (:measure inp))
                    :op (get-in held [:data :op])
                    :target (target-text (:rdef-of ctx) (:measure inp)
                                         (get-in held [:data :target]))
                    :goal (str (:id held))}})
    (t/allow)))

;; ── the resource ────────────────────────────────────────────────────

(def ^:private op-schema
  (into [:enum] measures/goal-ops))

(def ^:private target-field
  [:target {:examples [0.95M]
            :x-display {:label "Target"
                        :help "The number the measure is held to. A rate is a fraction: 0.95 is 95%."}}
   [:decimal]])

(def ^:private op-field
  [:op {:x-display {:label "Held to"
                    :help "\">=\" when the measure should stay at or above the target, \"<=\" when at or below."}}
   op-schema])

(def ^:private note-field
  [:note {:optional true
          :x-display {:label "Note"
                      :help "A sentence for whoever reads the goal later: why this target, or why it moved."}}
   [:maybe [:string {:max 240}]]])

(defn- optional
  "An entry a restate may leave out."
  [[k props s]]
  [k (assoc props :optional true) s])

(defhandler restate-goal
  [row inp _ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (update row :data merge (select-keys inp [:target :op :note])))

(defresource goal
  {:kind :goal
   :plural "goals"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.measure} {data.op} {data.target} · {state}"
   :label-template "{data.measure}"
   :schema [:map
            [:measure {:examples ["order.shipped_on_time"]
                       :x-display {:raw true
                                   :label "Measure"
                                   :help "The measure's token, <kind>.<name>, as discover lists it. One active goal per measure."}}
             [:string {:min 3 :max 120}]]
            op-field
            target-field
            note-field]
   :filterable {:state #{:eq :in}
                :measure #{:eq}}
   :create-guards [a-goal-writer measure-is-declared one-active-goal]
   :actions
   {:restate {:from #{:active} :to :active
              :input [:map (optional op-field) (optional target-field) note-field]
              :record true
              :guards [a-goal-writer]
              :safety {:idempotent true :reversible true :confirm false}
              :handler restate-goal
              :display {:label "Restate" :style :primary :order 1
                        :description "Move the target, the op or the note in place — the log keeps the old values"}}
    :retire {:from #{:active} :to :retired
             :guards [a-goal-writer]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired goal stays in the log; a new goal for the measure is one create away."}
             :display {:label "Retire" :style :danger :order 9
                       :description "Stop holding the measure to this goal — its readings go unjudged until a new one is made"}}}})
