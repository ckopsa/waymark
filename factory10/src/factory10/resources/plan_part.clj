(ns factory10.resources.plan-part
  "The plan_part: one part of the business an org_plan is split into,
  as a tree the person and the onboarding seat build together before
  code is written (ticket 7fc1415a). The goal is the part with no
  parent; every other part hangs under one of the same plan.

  A part is born `proposed`. The plan's owner agrees it, many at once
  through the bulk door, or drops it with a reason, and a dropped
  part's children are dropped with it (the owns cascade, through the
  engine's `drop_with_parent`). Restating an agreed part sends it back
  to `proposed`: what was agreed is no longer what it says. An agent's
  agree and drop are held for the owner's tap.

  A part says where it came from: `stated` when the person said it,
  `inferred` when the seat guessed it. A person's create is stated; an
  agent's is inferred unless it says stated and quotes the brief's own
  words in `detail`.

  The plan's side — the counts by state and the tree drawn on its
  document — is `parts-field` and `tree-field` below, which org_plan's
  :computed names."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── reading the plan and the tree ────────────────────────────────────

(def ^:private part-page
  "The most parts one plan's tree is read with."
  1000)

(def ^:private deepest
  "The most ancestors a parent walk reads before it calls the tree a
  cycle."
  64)

(defn the-person
  "The id of the person this hand is, or acts for: a person's own id,
  an agent's `:acts-for`. The engine's own hand and an agent acting
  for nobody answer nil. org_plan reads its owner by it too."
  [ctx]
  (let [p (:principal ctx)]
    (some-> (case (:type p)
              :agent (:acts-for p)
              :system nil
              (:id p))
            str not-empty)))

(defn- read-row
  "The row of `kind` with `id`, read through the ctx `:read` hook, or
  nil when the id is empty, the row is gone or no hook is lent."
  [ctx kind id]
  (when-some [read' (:read ctx)]
    (when-some [id (some-> id str not-empty)]
      (try (read' kind id) (catch Exception _ nil)))))

(defn- plan-id-of [row inp]
  (some-> (or (:plan inp) (get-in row [:data :plan])) str not-empty))

(defn- parent-of
  "The parent the move leaves the part with: the input's when it names
  one (nil included, for a part made the goal), else the row's."
  [row inp]
  (some-> (if (contains? inp :parent) (:parent inp) (get-in row [:data :parent]))
          str not-empty))

(defn- ancestors-cycle?
  "True when walking up from `parent` reaches `self`, or walks past
  `deepest` without reaching the goal."
  [ctx self parent]
  (loop [at parent n 0]
    (cond
      (nil? at) false
      (= at self) true
      (>= n deepest) true
      :else (recur (some-> (read-row ctx :plan_part at) (get-in [:data :parent]) str not-empty)
                   (inc n)))))

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn the-plan-is-yours
  {:reads [:principal]
   :explain "A plan's parts are its owner's: only the person who owns the plan, or a seat acting for them, may write them."
   :open "Ask the plan's owner, or act for them."}
  ;; No :read lent (the render probe) allows, as org_plan's
  ;; the-plan-was-audited-at-its-head does: the door judges again with
  ;; a real hook behind it.
  [row inp ctx]
  (cond
    (= :system (:type (:principal ctx))) (t/allow)
    (nil? (:read ctx)) (t/allow)
    :else (let [plan (read-row ctx :org_plan (plan-id-of row inp))]
            (if (and plan (some? (the-person ctx))
                     (= (the-person ctx) (some-> (get-in plan [:data :owner]) str)))
              (t/allow)
              (t/deny)))))

(defguardfn the-plan-is-drafting
  {:reads []
   :vars [:state]
   :explain "The plan is {state}: its parts are made, restated and agreed only while it is drafting."
   :open "Work the parts of a plan that is drafting."}
  [row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [state (some-> (read-row ctx :org_plan (plan-id-of row inp)) :state name)]
      (if (= "drafting" state)
        (t/allow)
        (t/deny {:vars {:state (or state "not found")}})))))

(defguardfn the-parent-is-of-this-plan
  {:judges [:parent]
   :reads [:plan_part]
   :vars [:parent]
   :explain "The part {parent} is not a part of this plan: a part hangs under a part of its own plan."
   :open "Name a parent from this plan's parts, or none for the goal."}
  [row inp ctx]
  (let [parent (parent-of row inp)]
    (if (or (nil? parent) (nil? (:read ctx)))
      (t/allow)
      (let [p (read-row ctx :plan_part parent)]
        (if (and p (= (plan-id-of row inp) (some-> (get-in p [:data :plan]) str)))
          (t/allow)
          (t/deny {:vars {:parent parent} :evidence {:parent parent}}))))))

(defguardfn a-part-is-not-its-own-ancestor
  {:judges [:parent]
   :reads [:plan_part]
   :vars [:parent]
   :explain "Hanging this part under {parent} would make it its own ancestor."
   :open "Name a parent that is not this part or one of its children."}
  [row inp ctx]
  (let [self (some-> (:id row) str not-empty)
        parent (parent-of row inp)]
    (if (and self parent (or (= self parent)
                             (and (:read ctx) (ancestors-cycle? ctx self parent))))
      (t/deny {:vars {:parent parent} :evidence {:parent parent}})
      (t/allow))))

(defguardfn only-the-owner-agrees
  {:reads [:principal :within]
   :hold true
   :vars [:title]
   :explain "Agree the part \"{title}\"? Agreeing is the plan owner's word, so an agent's agree is held for the owner's tap, and the owner's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the owner's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :plan_part :agree (:id row)) (t/allow)
    (holds/approved-hold? ctx :plan_part :agree_many (:id row)) (t/allow)
    :else (t/deny {:vars {:title (str (get-in row [:data :title]))}})))

(defguardfn only-the-owner-drops
  {:reads [:principal :within]
   :hold true
   :vars [:title]
   :explain "Drop the part \"{title}\" and every part under it? Dropping is the plan owner's word, so an agent's drop is held for the owner's tap, and the owner's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the owner's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :plan_part :drop (:id row)) (t/allow)
    :else (t/deny {:vars {:title (str (get-in row [:data :title]))}})))

(defguardfn the-engine-drops-the-children
  {:reads [:principal]
   :hide true
   :explain "A part is dropped with its parent by the engine's cascade alone."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx))) (t/allow) (t/deny)))

(defscenario a-person-does-not-drop-a-part-with-its-parent
  "drop_with_parent is the owns cascade's door: a part goes with its
   parent because the engine walked it, never because a hand said so.
   A person who wants a part gone drops it, with a reason."
  {:kind    :plan_part
   :attempt :drop_with_parent
   :row     {:state :proposed
             :data {:plan "plan-1" :title "Running each client's study"
                    :source "stated"}}
   :as      {:id "colton" :type :human}
   :expect  {:refused :the-engine-drops-the-children
             :because "by the engine's cascade alone"}})

;; ── the birth and the doors ──────────────────────────────────────────

(defn- quoted
  "The quoted passages of `s`, in straight or curly double quotes."
  [s]
  (map second (re-seq #"[\"“]([^\"”]{3,})[\"”]" (str s))))

(defn- quotes-the-brief?
  "True when `detail` quotes words the plan's brief says."
  [detail brief]
  (let [brief (str/lower-case (str brief))]
    (boolean (some #(str/includes? brief (str/lower-case (str/trim %)))
                   (quoted detail)))))

(defn- stamp-the-part
  "The birth's stamps: where the part came from, and a home of
  `undecided` when none was said."
  [row ctx]
  (let [d (:data row)
        source (case (:type (:principal ctx))
                 :agent (if (and (= "stated" (some-> (:source d) name))
                                 (quotes-the-brief?
                                  (:detail d)
                                  (get-in (read-row ctx :org_plan (:plan d)) [:data :brief])))
                          "stated"
                          "inferred")
                 :system (or (some-> (:source d) name) "inferred")
                 "stated")]
    (cond-> (assoc-in row [:data :source] source)
      (str/blank? (some-> (:home d) name)) (assoc-in [:data :home] "undecided"))))

(defhandler restate-the-part [row inp _ctx]
  ;; A patch: the fields the door collected land, and the rest stand.
  (update row :data merge inp))

(defhandler drop-the-part [row inp _ctx]
  (assoc-in row [:data :dropped_because] (:reason inp)))

(defhandler drop-with-the-parent [row _inp _ctx]
  (assoc-in row [:data :dropped_because] "Its parent was dropped."))

(defhandler reopen-the-part [row _inp _ctx]
  (assoc-in row [:data :dropped_because] nil))

;; ── the plan's side: counts and the tree ────────────────────────────

(defn- parts-of-plan
  "Every part of the plan `plan`, read through the ctx `:find` hook, or
  nil with none lent."
  [plan ctx]
  (when-some [find' (:find ctx)]
    (vec (find' :plan_part {:plan (str (:id plan))} {:limit part-page}))))

(defn- parts-counts
  "The `parts` computed field on org_plan: the plan's parts counted by
  state, and in all."
  [plan ctx]
  (when-some [parts (parts-of-plan plan ctx)]
    (let [by (frequencies (map #(some-> (:state %) name) parts))]
      {:proposed (get by "proposed" 0)
       :agreed (get by "agreed" 0)
       :dropped (get by "dropped" 0)
       :total (count parts)})))

(defn- part-line [part depth]
  (let [d (:data part)
        home (some-> (:home d) name)
        home-name (some-> (:home_name d) str not-empty)]
    (str (apply str (repeat depth "  ")) "- **" (:title d) "**"
         (when-some [detail (some-> (:detail d) str not-empty)] (str " — " detail))
         " · " (or home "undecided") (when home-name (str " `" home-name "`"))
         (when (= "inferred" (some-> (:source d) name)) " · *inferred*")
         (when (not= "agreed" (some-> (:state part) name))
           (str " · " (some-> (:state part) name))))))

(defn- parts-tree
  "The `parts_tree` computed field on org_plan: the plan's parts as a
  markdown tree, each line its title, detail and home, with every
  inferred part marked. Siblings stand in `order`, then by title."
  [plan ctx]
  (when-some [parts (parts-of-plan plan ctx)]
    (when (seq parts)
      (let [ids (set (map #(str (:id %)) parts))
            ;; a parent outside the tree read hangs its part at the root
            parent (fn [p] (let [pid (some-> (get-in p [:data :parent]) str)]
                             (when (contains? ids pid) pid)))
            kids (group-by parent parts)
            sorted (fn [ps] (sort-by (juxt #(or (get-in % [:data :order]) Long/MAX_VALUE)
                                           #(str (get-in % [:data :title])))
                                     ps))
            walk (fn walk [pid depth]
                   (mapcat (fn [p] (cons (part-line p depth)
                                         (walk (str (:id p)) (inc depth))))
                           (sorted (get kids pid))))]
        (str/join "\n" (walk nil 0))))))

(def parts-field
  "org_plan's `parts` computed field (ticket 7fc1415a)."
  {:schema [:maybe [:map
                    [:proposed :int]
                    [:agreed :int]
                    [:dropped :int]
                    [:total :int]]]
   :x-display
   {:label "Its parts"
    :help "How many of the plan's parts are proposed, agreed and dropped, and how many there are in all."}
   :reads? true
   :fn parts-counts})

(def tree-field
  "org_plan's `parts_tree` computed field (ticket 7fc1415a)."
  {:schema [:maybe :string]
   :x-display
   {:widget "prose"
    :label "How the business splits"
    :help "The plan's parts as a tree under its goal: each part's title, what it means for this business, and where it will live. A part marked inferred is the seat's guess, not the person's words."}
   :reads? true
   :fn parts-tree})

;; ── :plan_part ──────────────────────────────────────────────────────

(def ^:private homes
  ["new_kind" "built_in" "seat" "rule" "person" "undecided"])

(def ^:private home-choices
  {"new_kind" "A new kind of row"
   "built_in" "Something the house already has"
   "seat" "A seat's work"
   "rule" "A rule on a door"
   "person" "A person's own work"
   "undecided" "Not decided yet"})

(def ^:private part-fields
  "The fields a create and a restate both take, but `plan` and
  `source`, which only the birth takes."
  [[:parent {:optional true :kind :plan_part
             :x-display {:label "Under"
                         :help "The part this one belongs under, from the same plan. Leave it empty for the goal."}}
    [:maybe :waymark/ref]]
   [:title {:examples ["Running each client's study"]
            :x-display {:label "The part"
                        :help "A few words, in the person's own language."}}
    [:string {:min 1 :max 120}]]
   [:detail {:optional true
             :examples ["Each client's R&D credit study, from the first interview to the filed form."]
             :x-display {:widget "prose" :label "What it means here"
                         :help "One or two sentences on what this part means for this business."}}
    [:maybe [:string {:max 600}]]]
   [:home {:optional true
           :x-display {:label "Where it lives" :choices home-choices}}
    [:maybe (into [:enum] homes)]]
   [:home_name {:optional true
                :x-display {:raw true :label "Which one"
                            :help "The name of the kind, seat or rule it lives in: study, or attachment."}}
    [:maybe [:string {:max 120}]]]
   [:order {:optional true
            :x-display {:label "Position"
                        :help "Where it stands among the parts under the same parent; lower comes first."}}
    [:maybe [:int {:min 0 :max 10000}]]]])

(def ^:private plan-field
  [:plan {:kind :org_plan
          :x-display {:label "The plan"
                      :help "The org plan this part belongs to."}}
   :waymark/ref])

(def ^:private source-field
  [:source {:optional true
            :x-display {:label "Where it came from"
                        :choices {"stated" "The person said it"
                                  "inferred" "The seat's guess"}
                        :help "Stamped at birth: a person's part is stated; an agent's is inferred unless it says stated and quotes the brief in its detail."}}
   [:maybe [:enum "stated" "inferred"]]])

(defresource plan-part
  {:kind :plan_part
   :plural "plan_parts"
   :purpose {:what "One part of the business an org plan is split into, in the person's words, with where it will live in the house."
             :when "Use it to lay out the plan's tree of parts with the person before any code is written, and to agree or drop each one."
             :not [{:kind :org_plan
                    :why "An org plan is the whole org and its pull request; a plan_part is one piece of what that org must do."}
                   {:kind :ticket
                    :why "A ticket asks for code to be written; a plan_part says what the business needs before anyone decides on code."}]
             :for :either
             :for-note "The onboarding seat proposes parts and the person adds their own; only the plan's owner agrees or drops them."}
   ;; the day job's work, as org_plan is
   :nav :secondary
   :states [:proposed :agreed :dropped]
   :initial :proposed
   ;; the owns cascade's door, walked by the engine and no seat
   :left-by [{:in #{:proposed :agreed} :by :engine :via :drop_with_parent :to :dropped}]
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :display {:title "{data.title}"}
   :filterable {:state #{:eq :in}
                :plan #{:eq}
                :parent #{:eq}
                :source #{:eq}}
   :sortable {:fields [:order] :default "order"}
   ;; A DROPPED PART TAKES ITS CHILDREN WITH IT, and theirs: the
   ;; cascade walks each child's drop_with_parent, which cascades again.
   :owns [{:kind :plan_part :via :parent
           :on {:drop :drop_with_parent :drop_with_parent :drop_with_parent}}]
   :on-create stamp-the-part
   :create-guards [the-plan-is-yours the-plan-is-drafting the-parent-is-of-this-plan]
   :schema
   (into [:map plan-field]
         (concat part-fields
                 [source-field
                  [:dropped_because {:optional true
                                     :x-display {:widget "prose" :label "Why it was dropped"}}
                   [:maybe [:string {:max 480}]]]]))
   :create-schema (into [:map plan-field] (conj part-fields source-field))
   :actions
   {:restate
    {:from #{:proposed :agreed} :to :proposed
     :input (into [:map] (map (fn [[k opts schema]] [k (assoc opts :optional true) schema]))
                  part-fields)
     :guards [the-plan-is-yours the-plan-is-drafting the-parent-is-of-this-plan
              a-part-is-not-its-own-ancestor]
     :handler restate-the-part
     :edit {:prefill [:parent :title :detail :home :home_name :order]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restate" :order 1
               :description "Say the part again; an agreed part goes back to proposed"}}

    :agree
    {:from #{:proposed} :to :agreed
     :input [:map]
     :guards [the-plan-is-yours the-plan-is-drafting only-the-owner-agrees]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Agree" :order 2
               :description "The owner agrees this part is part of the business"}}

    ;; agree, fanned: a bulk door has no row form, so it is its own door
    :agree_many
    {:from #{:proposed} :to :agreed
     :guards [the-plan-is-yours the-plan-is-drafting only-the-owner-agrees]
     :bulk {:max-items 200}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Agree selected"
               :description "The owner agrees every selected part at once"}}

    :drop
    {:from #{:proposed :agreed} :to :dropped
     :input [:map
             [:reason {:examples ["We do not do payroll; the client's accountant does."]
                       :x-display {:widget "prose" :label "Why it goes"
                                   :help "One sentence: why this part is not part of the business."}}
              [:string {:min 1 :max 480}]]]
     :guards [the-plan-is-yours only-the-owner-drops]
     :handler drop-the-part
     :touches [{:kind :plan_part :action :drop_with_parent :may true}]
     ;; the reason is composed, so it is drafted (ticket's `drop`)
     :edit {:draft {:shared true :live true}}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The part and every part under it are dropped. The way back is reopen, one part at a time, and a reopened part is proposed again, not agreed."}
     :display {:label "Drop" :order 3
               :description "Take this part and every part under it out of the plan"}}

    :drop_with_parent
    {:from #{:proposed :agreed} :to :dropped
     :guards [the-engine-drops-the-children]
     :handler drop-with-the-parent
     :touches [{:kind :plan_part :action :drop_with_parent :may true}]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The part is dropped with its parent. The way back is reopen, and a reopened part is proposed again."}
     :display {:label "Dropped with its parent" :order 10
               :description "The engine drops a part whose parent was dropped"}}

    :reopen
    {:from #{:dropped} :to :proposed
     :input [:map]
     :guards [the-plan-is-yours]
     :handler reopen-the-part
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 4
               :description "Bring a dropped part back as proposed"}}}
   :scenarios [a-person-does-not-drop-a-part-with-its-parent]})
