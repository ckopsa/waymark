(ns workqueue10.resources.value
  "The value (waymark-jfv.2): what this house actually cares about,
  written down by the person who cares about it.

  The owner's own words are the whole reason this kind exists:

    Use AI to create a menu of outcomes aligned with my values, and
    reduce the friction of reaching them by aligning the actions with
    the activities I love.

  And the sharper claim underneath them, which is what the row is
  shaped by: TIME FOLLOWS THE FRICTION GRADIENT ACROSS VALUES, NOT THE
  VALUES THEMSELVES. Building with AI is cheap to start and wins the
  evening; making memories with the family is valued MORE and costs
  more to begin. A house that only ever showed itself what was true
  would lose that contest every night, because the truth is that the
  cheap thing is cheap. So the declaration comes first: `making
  memories with the family`, `Grandpa is cared for`, `building` — each
  one beside the activities he LOVES, because a value with no loved
  activity next to it is precisely the high-friction value the whole
  thesis is about.

  DECLARED IS LAW. LEARNED IS EVIDENCE THAT FILES ASKS. That is the
  epic's sentence and this file is where it is made structural rather
  than promised. `written-by-a-person` refuses an agent at every door
  this kind has — create, revise, still_stands, retire, restore — and
  the refusal NAMES THE LAWFUL PATH rather than just closing:
  publish an `insight`, cite the rows you read, and offer this value's
  own `still_stands` as the one next step. `feed_recipe`'s wall is the
  precedent and the reason transfers exactly: an agent that could
  rewrite what the house cares about would be a composer grading its
  own homework, which is the backdoor the whole no-scoring-function
  posture exists to keep shut.

  THE PETITION, AND THE CODE CONSTRAINT THAT DECIDES ITS SHAPE. The
  epic asks for learned signals to petition an amendment only the
  owner ratifies. The door for `an agent says something with evidence
  and offers a next step` already exists — the insight — and its
  create wall `offers-something-light` refuses any offered action
  heavier than `selection`, while `demand/field-class` reads EVERY
  plain string as recall. Every door that could take a new wording
  takes a string. So no petition can offer the amendment itself, and
  the honest v1 is the one below: `still_stands` asks for nothing,
  renders `assent`, and is therefore a legal insight offer — the tap
  means `I have read this`, and the REWORDING is the owner's own
  `revise`, on this row, through this row's own screen.

  `still_stands` IS NOT A HOLLOW TAP, and that is worth saying out
  loud because it looks like one. An unreviewed petition and a
  reviewed-but-unchanged declaration are different facts, and the
  second is the most useful thing a composer can learn: THE OWNER READ
  THE EVIDENCE AND THE VALUE STANDS ANYWAY — which means the friction
  is in the plan and not in the declaration. `reviewed_by` and
  `reviewed_at` are that fact, stamped by the engine on a self-loop.

  A one-tap ratification of an EXACT proposed wording wants waymark-0k4's
  staged proposal generalized past `feed_recipe`; that is the other
  half of waymark-xw3 and it is filed rather than built.

  LOVED ACTIVITIES ARE FREE WORDS, AND THAT SET IS THE VOCABULARY.
  Two alternatives were weighed and both lose. Linking to
  eveningplan10's `activity` kind borrows another domain's nouns for a
  word like `the shop`. Minting a `loved_activity` kind is a row per
  word — a noun for a noun's sake. The household's own sentence — `I
  love the shop, I love building with the boys, I love cooking with a
  podcast on` — IS the declaration, and what those words buy
  downstream is a checkable citation: a plan's routing, when it names
  a loved activity, must match a word some declared value carries, and
  the refusal narrates the legal words. There is no picker, and there
  will not be one until a row-backed `x-options` source exists
  (waymark-90k); a guard and a good sentence is this tree's usual
  first answer anyway.

  :nav :secondary IS LOAD-BEARING AND IS NOT COSMETIC. `next_actions`
  claims the OPEN rows of every :nav :primary kind, and `fuel` speaks
  only about :nav :primary kinds. A value is permanently open by
  construction — `declared` is where it lives, not where it waits — so
  a :primary value would card in do-now forever and a retired one
  would be congratulated as a deed. `feed_recipe` is :secondary for
  exactly this reason. (Hand-written kinds inherit no :nav: the
  :decision sugar is what hands tickler and insight :system for free,
  and the sugar cannot spell this kind — a value is long-lived law,
  not a one-shot verdict.)

  SCOPE IS feed_recipe'S, VERBATIM. `household` or `mine`, with the
  owner STAMPED BY THE ENGINE and never trusted from the body. The
  wall that matters is the stamp rather than concealment:
  `this-is-yours-to-declare` refuses a `mine` value to anyone but its
  owner, and a `household` value is any adult's to write — the
  deviation waymark-4yn already recorded for the household recipe. A
  second pair of eyes on a household value is waymark-pcr's question
  and is not re-decided here.

  THE SMALLEST HONEST MACHINE. Two states, `declared` and `retired`,
  neither terminal, reversible both ways. There is no `proposed`,
  because nothing but a person may declare one and a person declaring
  one has already decided; there is no `amended`, because the amendment
  is a REVISION of a standing law and not a new state of it — the row's
  own transitions are the record of who changed what, when, and from
  which law revision. Retiring is how a house stops hearing about an
  outcome for good, and it is the only honest way: the plans that
  serve a retired value stop being staged."
  (:require [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn written-by-a-person
  {:reads [:principal]
   :explain "What this house cares about is written by a person. An agent that could declare or reword a value would be a composer grading its own homework — publish an insight instead, citing the rows you read, and offer this value's own \"these still stand\" as the one next step; the owner answers with a tap and does his own rewording."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind — the render probe and the
  ;; real invoke read the same fact, so no probe path opens a door
  ;; (feed_recipe/written-by-a-person, line for line). :system is the
  ;; ENGINE's own actor — a migration, a seed, the conformance walker —
  ;; and is not what this wall is about; the wall is about the composer.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

(defguardfn this-is-yours-to-declare
  {:reads [:principal]
   :explain "This one is somebody else's to say. A value scoped \"mine\" belongs to the member the engine stamped on it; a value the whole house holds is scoped \"household\", and anybody here may write that one."}
  [row _inp ctx]
  ;; reads the ROW rather than storage — a stamped owner is already on
  ;; it — so this stays in the no-database check tier beside the agent
  ;; wall. Absent at create time, deliberately: a create has no row
  ;; yet, and `stamp-owner` writes the caller's own id or nothing, so
  ;; there is no foreign owner a create could name.
  (let [scope (str (get-in row [:data :scope]))
        owner (get-in row [:data :owner])]
    (if (and (= "mine" scope) (not= owner (:id (:principal ctx))))
      (t/deny)
      (t/allow))))

;; ── the stamps ──────────────────────────────────────────────────────

(def authored-fields
  "The surface `revise` overwrites wholesale — the same fields the door
  takes, so what is stored is exactly what was judged. `:scope` and
  `:owner` stay OUT: a value never changes whose law it is, and the
  owner is the engine's stamp rather than anybody's input."
  [:name :says :loved])

(defhandler apply-revision [row inp _ctx]
  ;; wholesale over authored-fields: an omitted optional CLEARS, so a
  ;; value whose loved words are dropped really has none. The
  ;; transition carries what was written (:record true), which is what
  ;; makes the log the amendment history the epic asked for.
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) authored-fields))))

(defhandler stamp-the-review [row _inp ctx]
  ;; "I have read the petition and it stands." Who and when, from the
  ;; engine's own :now and principal — never from a body, and never
  ;; from a clock this file reads.
  (-> row
      (assoc-in [:data :reviewed_at] (:now ctx))
      (assoc-in [:data :reviewed_by] (get-in ctx [:principal :id]))))

(defn- stamp-owner
  "Whose law this is, written by the ENGINE and never trusted from the
  body (feed_recipe's `stamp-owner`, one kind over). A \"mine\" value
  is the writer's own — a person cannot put words in somebody else's
  mouth by naming them in a form — and a \"household\" value is nobody's
  in particular, so it carries no owner at all."
  [row ctx]
  (assoc-in row [:data :owner]
            (when (= "mine" (str (get-in row [:data :scope])))
              (:id (:principal ctx)))))

;; ── the household's own words ───────────────────────────────────────
;; Spelled once and worn by both doors — the row schema and the
;; narrower create form — because three copies of one sentence is
;; three places for it to drift (dashboard.clj's `slot-prose`, same
;; reasoning).

(def ^:private says-example
  "Something in the textarea instead of a blank page (waymark-0ee's
  composition policy). Offered, never applied — and it is the owner's
  own kind of sentence rather than a lorem paragraph, because what a
  person is being asked for here is unusual enough that a shape helps."
  (str "The boys will remember what we made together, not what I"
       " shipped. Evenings go to the screen because the screen is"
       " already open and the shop is cold — so the point of writing"
       " this down is that the house can push back."))

(def ^:private prose
  {:name {:x-display
          {:label "The value"
           :help "In your own words, short — \"making memories with the family\", \"Grandpa is cared for\", \"building\". Not a goal and not a task: the thing that would still matter if none of this week's work existed."}}
   :says {:examples [says-example]
          :x-display
          {:widget "prose"
           :label "Why it matters"
           :help "A paragraph, for you and for whoever reads this house's plans. Say what this is actually about and what gets in its way — this is the sentence a composed plan is judged against later, so an honest one is worth more than a tidy one."}}
   :loved {:x-display
           {:label "Activities you love that serve it"
            :help "The things you would happily start on a tired Tuesday — \"the shop\", \"woodworking\", \"building with the boys\", \"cooking with a podcast on\". Free words, in your spelling: this list IS the house's vocabulary, and a plan that routes through one of them has to name it exactly as you wrote it. Leave it empty and you are saying this value has nothing cheap to start with, which is worth knowing too."}}
   :scope {:x-display
           {:label "Whose value"
            :choices {"household" "The house's — something this family holds together"
                      "mine" "Mine — my own, and nobody else's to reword"}}}
   :owner {:x-display
           {:raw true
            :label "Whose"
            :help "The member whose own value this is, stamped by the engine when the scope is mine. A household value carries none."}}
   :reviewed_at {:x-display
                 {:label "Last affirmed"
                  :help "When somebody last read a petition against this value and said it stands anyway. Written by the engine at the tap, never by a form."}}
   :reviewed_by {:x-display
                 {:raw true
                  :label "Affirmed by"
                  :help "Who said it still stands. Written by the engine at the tap."}}})

(defn- entry
  "One [:key props schema] entry of a form: the shared prose plus
  whatever this surface adds of its own."
  [k extra form]
  [k (merge (get prose k) extra) form])

(def revise-input
  "What :revise takes: the whole authored surface, overwritten
  wholesale. Public for the reason `feed_recipe/recipe-input` is — the
  door a staged amendment would one day be validated against has to be
  the door itself rather than a copy of it that could drift."
  [:map
   (entry :name {} [:string {:min 1 :max 80}])
   (entry :says {} [:string {:min 1 :max 2000}])
   (entry :loved {:optional true}
          [:maybe [:vector {:max 12} [:string {:min 1 :max 40}]]])])

;; ── the law, written down as scenarios ──────────────────────────────
;; All five are CHECK-TIER, and the reason is the same one feed_recipe
;; gave: both walls read only what a declaration-time world can
;; honestly supply — the principal, and the row the scenario hands
;; them. `make check-queue` judges them with no database at all, in
;; the same breath as the usability warnings.

(def ^:private a-declared-value
  {:name "making memories with the family"
   :says "The boys will remember what we made together, not what I shipped."
   :loved ["the shop" "woodworking" "building with the boys"]
   :scope "household"})

(def ^:private a-value-of-my-own
  {:name "building"
   :says "The part of the work that is mine, and the one that is easiest to start."
   :loved ["building with AI"]
   :scope "mine"
   :owner "colton"})

(defscenario an-agent-does-not-declare-a-value
  "No agent writes down what this family cares about. A composer that
   could declare a value would be authoring the very law its own plans
   are judged against, and the refusal says what it may do instead."
  {:kind    :value
   :attempt :create
   :input   {:name "shipping more"
             :says "The house would get more done if it valued throughput."
             :scope "household"}
   :as      {:id "compiler" :type :agent}
   :expect  {:refused :written-by-a-person
             :because "publish an insight instead"}})

(defscenario an-agent-does-not-amend-one
  "And no agent rewords one either — which is the half that matters,
   because rewording is the quiet way to author. The lawful path is a
   petition: cite the rows, offer the value's own \"these still
   stand\", and let the owner do his own rewording."
  {:kind    :value
   :attempt :revise
   :row     {:state :declared :data a-declared-value}
   :input   {:name "making memories with the family, when there is time"
             :says "Softened after six weeks of evenings that went elsewhere."}
   :as      {:id "compiler" :type :agent}
   :expect  {:refused :written-by-a-person
             :because "publish an insight instead"}})

(defscenario a-person-amends-what-the-house-declared
  "A member of the house rewords a household value freely — the wall
   bars the composer, not the family. The row's own transitions are
   the amendment history: who changed it, when, and from what."
  {:kind    :value
   :attempt :revise
   :row     {:state :declared :data a-declared-value}
   :input   {:name "making memories with the family"
             :says "The boys will remember what we made together. Saturdays are the real currency."
             :loved ["the shop" "woodworking" "building with the boys"]}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario somebody-elses-value-is-not-yours-to-reword
  "A value scoped \"mine\" is one member's own sentence. Another adult
   may read it, may plan around it, and may not put different words in
   it — a household value is the shape for something the house holds
   together."
  {:kind    :value
   :attempt :revise
   :row     {:state :declared :data a-value-of-my-own}
   :input   {:name "building, but less of it"
             :says "Reworded by somebody it does not belong to."}
   :as      {:id "iris" :type :person}
   :expect  {:refused :this-is-yours-to-declare
             :because "somebody else's to say"}})

(defscenario a-petition-is-answered-with-one-tap
  "The petition path's own door, from the owner's side: an agent's
   finding offers this action, it asks for nothing, and the answer is
   a thumb. \"I read the evidence and it stands anyway\" is a fact the
   house keeps, and it is the one that separates a wrong value from a
   wrong plan."
  {:kind    :value
   :attempt :still_stands
   :row     {:state :declared :data a-declared-value}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── :value — declared law, petitioned by evidence ───────────────────

(defresource value
  {:kind :value
   :plural "values"
   ;; see the ns docstring: a :primary value would card in do-now
   ;; forever and a retired one would be celebrated as a deed
   :nav :secondary
   :states [:declared :retired]
   :initial :declared
   :terminal #{}
   :summary "{data.name} · {data.scope} · {state}"
   :label-template "{data.name}"
   :display {:title "Value"}
   :schema [:map
            (entry :name {:sort :default} [:string {:min 1 :max 80}])
            (entry :says {} [:string {:min 1 :max 2000}])
            ;; FREE WORDS, and this set is the household's vocabulary.
            ;; No enum (the words are the family's, not the schema's),
            ;; no ref (another domain's nouns are not these), no kind
            ;; of its own (a noun for a list of words). Twelve is a
            ;; ceiling on a list, not a policy: a person who loves
            ;; thirteen things is writing a diary, not a declaration.
            (entry :loved {:optional true}
                   [:maybe [:vector {:max 12} [:string {:min 1 :max 40}]]])
            (entry :scope {:filter #{:eq}} [:enum "household" "mine"])
            (entry :owner {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])
            (entry :reviewed_at {:optional true :sort true}
                   [:maybe :waymark/instant])
            (entry :reviewed_by {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])]
   ;; the client states whose value and what it says; the OWNER is the
   ;; engine's stamp, and the review stamps are the tap's
   :create-schema [:map
                   (entry :name {} [:string {:min 1 :max 80}])
                   (entry :says {} [:string {:min 1 :max 2000}])
                   (entry :loved {:optional true}
                          [:maybe [:vector {:max 12}
                                   [:string {:min 1 :max 40}]]])
                   (entry :scope {} [:enum "household" "mine"])]
   ;; scope, owner and reviewed_by carry their own :filter on the
   ;; schema entries above — one concern, one home — so only the
   ;; machine's own column is spelled here. :state is what jfv.3's
   ;; names-a-value reads when it asks whether a value is still held.
   :filterable {:state #{:eq :in}}
   :create-guards [written-by-a-person]
   :on-create stamp-owner
   :scenarios [an-agent-does-not-declare-a-value
               an-agent-does-not-amend-one
               a-person-amends-what-the-house-declared
               somebody-elses-value-is-not-yours-to-reword
               a-petition-is-answered-with-one-tap]
   :actions
   {:revise {:from #{:declared} :to :declared
             :input revise-input
             :edit {:prefill [:name :says :loved]}
             ;; the overwrite writes the whole authored surface and is
             ;; non-reversible, so the log carries what was written —
             ;; which is what makes the transitions the amendment
             ;; history, and the ratification the epic asked for
             :record true
             ;; `says` is required prose, but :revise PREFILLS the
             ;; paragraph that already stands and is :record true — the
             ;; form is never blank and the prior words live in the
             ;; transition log, so a mis-click loses an in-progress
             ;; edit and not the declaration. A shared live draft is
             ;; not warranted for a law one person writes (the journal's
             ;; own waiver, one kind over).
             :waives #{:large-effort}
             :guards [written-by-a-person this-is-yours-to-declare]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Rewording overwrites the declaration with what you write; the log keeps the prior words, who changed them and when, so the amendment is a record rather than a replacement."}
             :handler apply-revision
             :display {:label "Reword" :order 1
                       :description "Say it differently, or change the activities you love that serve it — this is the ratification, and only a person here may make it"}}
    ;; THE PETITION'S OWN DOOR. No :input, deliberately and structurally:
    ;; an insight's offer must be no heavier than `selection`, and a
    ;; door taking so much as one string renders `recall`. Asking for
    ;; nothing is what makes this action a legal offer and a legal
    ;; card chip — the tap says "I have read this", and the rewording
    ;; is `revise`, one door up, by the owner's own hand.
    :still_stands {:from #{:declared} :to :declared
                   :guards [written-by-a-person]
                   :handler stamp-the-review
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "This records that somebody read what was found and the value stands anyway — a fact the house keeps, and the one that tells a composer the friction is in the plan rather than in the declaration. Nothing about the value changes."}
                   :display {:label "These still stand" :order 2
                             :description "You read the evidence and the value holds — stamp the date and move on"}}
    :retire {:from #{:declared} :to :retired :undo :restore
             :guards [written-by-a-person this-is-yours-to-declare]
             :safety {:idempotent true :confirm false}
             :display {:label "Retire" :style :danger :order 8
                       :description "This is not one of ours any more — the plans that serve it stop being offered, the row stays on record, and restore brings it back"}}
    :restore {:from #{:retired} :to :declared :undo :retire
              :guards [written-by-a-person this-is-yours-to-declare]
              :safety {:idempotent true :confirm false}
              :display {:label "Restore" :order 1
                        :description "Hold it again"}}}})
