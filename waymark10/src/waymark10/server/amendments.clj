(ns waymark10.server.amendments
  "Amendments (docs/spec-amendment.md §14 step 2): a row that wraps one
  seat or promise restate. R-A.2.1-2.7 and R-A.10.2: the fields, the
  states and their doors, the lane a patch may name
  (`an-amendment-stays-in-its-lane`), and the engine's own walls on
  `replay` and `settle`. `replay` is a stub that writes an empty report
  naming `capped` blind; there is no `adopt` and no `revert` yet (steps
  7 and 9), so `trial` and `settled` are declared and not reached.
  Opted into like `promise`, through an app's resources vector."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.goals :as goals]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(def engine-actor
  (t/principal {:id "waymark10-amendments" :type :system :display "Amendments"}))

(def lane
  "R-A.10.2: the fields a patch may name, by the target's kind."
  {"seat" #{"charter" "instructions" "walk" "walk_promises" "judgment"
            "rows_per_firing" "cadence_seconds"}
   "promise" #{"name" "holds" "by" "remedy"}})

(defn- seat?
  "A seat's own principal: an agent that acts for no person."
  [p]
  (and (= :agent (:type p)) (nil? (some-> (:acts-for p) str not-empty))))

(defn- filer-of [p]
  (if (= :system (:type p)) "engine" (some-> (or (:acts-for p) (:id p)) str not-empty)))

(g/defguard an-amendment-writer
  {:reads [:principal :member :domain]
   :explain "An amendment is the house owner's to file: this door wants the factory domain's owner, or a seat whose grant names `amendment`."
   :open "The factory domain's owner is set by restating that domain; a seat is given `amendment` on its grant."}
  [_row _inp ctx]
  (if (goals/goal-writer? ctx) (t/allow) (t/deny)))

(g/defguard the-filer-or-the-owner
  {:reads [:principal :member :domain]
   :explain "Only the one who filed this amendment, or the house owner, restates or withdraws it."
   :open "The owner files a new amendment of their own."}
  [row _inp ctx]
  (let [p (:principal ctx)
        filer (some-> (fget (:data row) :filed_by) text not-empty)]
    (if (or (and filer (= filer (filer-of p)))
            (and (not (seat? p)) (not= :system (:type p)) (goals/goal-writer? ctx)))
      (t/allow)
      (t/deny))))

(g/defguard the-engine-moves-it
  {:reads [:principal]
   :hide true
   :explain "An amendment's replay and its verdict are the engine's own reading; no seat replays its own change or judges its own trial (R-A.2.5)."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx))) (t/allow) (t/deny)))

(g/defguard a-seats-amendment-answers-a-promise
  {:judges [:answers]
   :reads [:principal]
   :explain "A seat files an amendment against the promises it breaks: name at least one in `answers`, as {promise, breach}."
   :open "Name the promise and the id of its break transition; a person's amendment may name none."}
  [row inp ctx]
  (let [answers (if (contains? inp :answers) (:answers inp) (fget (:data row) :answers))]
    (if (or (not (seat? (:principal ctx))) (seq answers)) (t/allow) (t/deny))))

(defn- out-of-lane
  "Why `changes` leaves the lane (R-A.10.2), or nil. `read'` reads the
  target when the ctx carries it."
  [changes read']
  (let [kind (some-> (fget changes :kind) text)
        id (some-> (fget changes :id) text)
        door (some-> (fget changes :door) text)
        patch (fget changes :patch)
        fields (sort (map text (keys patch)))
        admits (get lane kind)]
    (cond
      (nil? admits)
      (str "a `" kind "` row is outside it: an amendment restates one seat or one promise"
           " — use the " kind " kind's own doors yourself")

      (not= "restate" door)
      (str "the door `" door "` is not `restate`: no seat is created, parked, unparked,"
           " merged or retired by an amendment — use the raw door " kind "." door " yourself")

      (empty? fields)
      "its patch names no field"

      :else
      (or (some (fn [f]
                  (when-not (admits f)
                    (str "`" f "` is outside it on a " kind
                         " — change it on the raw door " kind ".restate yourself")))
                fields)
          (when read'
            (let [target (try (read' (keyword kind) id) (catch Exception _ nil))
                  was (fget (:data target) :cadence_seconds)
                  now (fget patch :cadence_seconds)]
              (cond
                (nil? target) (str "it names no " kind " `" id "`")
                (and (= "seat" kind) (some? now) (number? was)
                     (not (and (number? now) (> now was))))
                (str "`cadence_seconds` may only lengthen, and it is " was
                     " now — shorten it on the raw door seat.restate yourself"))))))))

(g/defguard an-amendment-stays-in-its-lane
  {:judges [:changes]
   :reads [:seat :promise]
   :vars [:problem]
   :explain "An amendment changes only what its lane admits: {problem}."
   :open "Patch a seat's charter, instructions, walk, walk_promises, judgment, rows_per_firing or a longer cadence_seconds, or a promise's name, holds, by or remedy; anything else is the person's, on the target's own door."}
  [_row inp ctx]
  (if-some [changes (:changes inp)]
    (if-some [problem (out-of-lane changes (:read ctx))]
      (t/deny {:vars {:problem problem}})
      (t/allow))
    (t/allow)))

(def ^:private title-field
  [:title {:x-display {:label "Amendment" :help "A sentence a person reads: meal-planner walks next week's plan."}}
   [:string {:min 3 :max 200}]])

(def ^:private answers-field
  [:answers {:optional true
             :x-display {:label "Answers" :help "[{promise, breach}]: each promise id and the id of its break transition. A seat's amendment names at least one; a person's may name none."}}
   [:maybe [:vector {:max 20}
            [:map
             [:promise [:string {:min 1 :max 120}]]
             [:breach {:optional true} [:maybe [:string {:max 120}]]]]]]])

(def ^:private changes-field
  [:changes {:x-display {:label "Changes" :help "{kind, id, door, patch}: one seat or promise, its restate door, and the fields it changes by name."}}
   [:map
    [:kind [:string {:min 1 :max 80}]]
    [:id {:x-ref {:kind-from :kind}} [:string {:min 1 :max 120}]]
    [:door [:string {:min 1 :max 80}]]
    [:patch [:map-of :keyword :any]]]])

(def ^:private report-schema [:maybe [:map-of :keyword :any]])

(defn- optional [[k props s]]
  [k (assoc props :optional true) s])

(def ^:private engine-fields
  [:before :after :replay :trial :verdict :mode :filed_by :key :withdrawn_because])

(defn- born-amendment
  "The engine writes what the filer may not: `filed_by` is the create's
  principal, the owner is the filer unless it names one, and every
  engine field starts empty."
  [row ctx]
  (let [p (:principal ctx)
        owner (or (some-> (fget (:data row) :owner) text not-empty)
                  (when (not= :system (:type p)) (filer-of p)))]
    (-> row
        (update :data #(apply dissoc % (concat engine-fields (map name engine-fields))))
        (assoc-in [:data :filed_by] (filer-of p))
        (cond-> owner (assoc-in [:data :owner] owner)))))

(defhandler restate-amendment
  [row inp _ctx]
  (update row :data #(-> %
                         (merge (select-keys inp [:title :answers :changes]))
                         (dissoc "replay")
                         (assoc :replay nil))))

(defhandler replay-amendment
  [row _inp _ctx]
  ;; the stub (§14 step 2): no history is read yet, so the report is
  ;; empty and says so — it never answers a partial number as a whole
  (assoc-in row [:data :replay]
            {:rows_read 0
             :walk {:added [] :dropped []}
             :holders {:moved [] :to_person [] :to_nobody []}
             :promises {}
             :refusals {:would_refuse 0}
             :blind ["capped"]}))

(defhandler withdraw-amendment
  [row inp _ctx]
  (assoc-in row [:data :withdrawn_because] (:why inp)))

(def ^:private engine-safety
  {:idempotent false :reversible false :confirm false
   :one-way "The engine's reading; a restate sends the amendment back to be read again."})

(defresource amendment
  {:kind :amendment
   :plural "amendments"
   :nav :secondary
   :states [:draft :replayed :trial :settled :reverted :withdrawn]
   :initial :draft
   :terminal #{:reverted :withdrawn}
   ;; reached by `adopt` (step 7) and left by `revert` (step 9)
   :allow-dead #{:trial :settled :reverted}
   :retain {:data true}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :schema [:map
            title-field
            answers-field
            changes-field
            [:owner {:optional true
                     :x-ref {:principal true}
                     :x-display {:label "Owner" :help "The member who adopts: the house owner unless the filer names another."}}
             [:maybe [:string {:max 120}]]]
            [:before {:optional true} report-schema]
            [:after {:optional true} report-schema]
            [:replay {:optional true
                      :x-display {:label "Replay" :help "The engine's replay report (§3): what the last weeks would have been under the change, and what it was blind to."}}
             report-schema]
            [:trial {:optional true} report-schema]
            [:verdict {:optional true}
             [:maybe [:enum "better" "same" "worse" "quiet" "confounded" "unjudged"]]]
            [:mode {:optional true} [:maybe [:enum "watch" "revert"]]]
            [:filed_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 120}]]]
            [:key {:optional true} [:maybe [:string {:max 200}]]]
            [:withdrawn_because {:optional true} [:maybe [:string {:max 480}]]]]
   :filterable {:state #{:eq :in}}
   :create-guards [an-amendment-writer an-amendment-stays-in-its-lane
                   a-seats-amendment-answers-a-promise]
   :on-create born-amendment
   :actions
   {:restate {:from #{:draft :replayed} :to :draft
              :input [:map (optional title-field) answers-field (optional changes-field)]
              :record true
              :guards [the-filer-or-the-owner an-amendment-stays-in-its-lane
                       a-seats-amendment-answers-a-promise]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A restate clears the replay; the engine replays the row as it now stands."}
              :handler restate-amendment
              :display {:label "Restate" :style :primary :order 1}}
    :replay {:from #{:draft} :to :replayed
             :guards [the-engine-moves-it]
             :safety engine-safety
             :handler replay-amendment
             :display {:label "Replay" :order 2}}
    :settle {:from #{:trial} :to :settled
             :guards [the-engine-moves-it]
             :safety engine-safety
             :display {:label "Settle" :order 3}}
    :withdraw {:from #{:draft :replayed} :to :withdrawn
               :input [:map [:why {:x-display {:label "Why" :help "One sentence for the owner: why this amendment is let go."}}
                             [:string {:min 1 :max 480}]]]
               :record true
               :guards [the-filer-or-the-owner]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A withdrawn amendment stays in the log; a new one is one create away."}
               :handler withdraw-amendment
               :display {:label "Withdraw" :style :danger :order 9}}}})
