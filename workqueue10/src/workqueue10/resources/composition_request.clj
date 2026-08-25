(ns workqueue10.resources.composition-request
  "The composition request (waymark-jfv.20): the person pulls, and the
  cap only ever walled the machine.

  The owner's ruling, verbatim, is what this kind is downstream of:

    I want to be able to just keep requesting outcomes.

  `outcome/outcomes-are-few` is two a week per composer, Monday to
  Monday, and the reasoning behind it is still right: a composer that
  could stage ten would never have to rank, and a household that woke
  up to ten would stop reading the crown by Thursday. But the cap was
  written against the MACHINE'S INITIATIVE, and a person asking for
  another is consent given in advance — waymark-8um law 6 (the person
  spins; the system never spins for them) applied to composition. A
  wall that refused the person's own pull would be the system deciding
  how much the household is allowed to want.

  ── WHAT A REQUEST IS ──

  One row, born by ONE TAP, carrying at most one thing: the value the
  person would like the next outcome to serve (`value_id`, optional —
  'compose me another' with no aim is the common case and the one the
  crown's chip mints). The engine stamps who asked and how long the
  ask stands. It is not a decision to make and it is not a task; it is
  a standing invitation that one outcome may answer.

  ── THE THREE WALLS, AND WHY EACH IS WHERE IT IS ──

  1. ONLY A PERSON ASKS (`only-a-person-asks`, at the create door). An
     agent that could mint requests would kill the cap through the
     back door — stage two, ask itself for a third, stage it. This is
     the same shape as `outcome/a-person-answers` and for the same
     reason: a house running two agents must be no different from a
     house running one. :system stays admitted (the walker, a seed);
     the wall is about the composer.

  2. THE AIM IS A VALUE THIS HOUSE HOLDS
     (`aims-at-a-value-this-house-holds`). Absent is allowed; named,
     it is checked the way `outcome/names-a-value` checks it, against
     the same two states, so a request cannot aim at a value the
     house has retired and then admit an outcome past the cap for it.

  3. AN OUTCOME ANSWERS A REQUEST, AND NOTHING ELSE DOES
     (`answered-by-a-composition`, on `answer`). This is the wall the
     bead's 'one request, one outcome' rests on, and it could not be
     built until the framework said which hand opened a door:
     `(:within ctx)` is the write a nested ctx :invoke was opened
     inside of, nil at the wire (waymark-jfv.20 grew it). The outcome's
     own `:on-create` invokes `answer` in the same transaction, before
     the outcome's own insert — the row id is minted ahead of the hook
     — so the request reads answered the instant the outcome exists,
     names the outcome that answered it, and a second outcome citing
     the same request meets `answered` at the outcome's own door. A
     direct POST from anybody, person or agent, is refused: not
     because they may not (the grant already says what an agent may
     reach) but because a request 'answered' with no outcome behind it
     would be the person's pull burned by a hand that composed nothing.

  ── NOT A DECIDE-SECTION CITIZEN, DECIDED AND RECORDED ──

  The bead asked whether an undecided request should nag. It should
  not: your own request is not a decision to make, and a card that
  reminded you that you asked would be the feed manufacturing a thing
  to answer. The crown carries the standing request instead
  (`feed/document`'s `crown` key) — where the person who asked is
  already looking, in one sentence, with no verb on it.

  ── THE HONEST NOTE ABOUT TIME ──

  A request is answered at the composer's NEXT SITTING, not on the
  tap. Until waymark-53u gives the composer a pulse (sessions lapse at
  eight hours, grants leash at twenty-four, and every sitting needs a
  fresh knock and approve), the tap writes an invitation and the
  answer arrives when somebody sits the composer down. The row's own
  prose says so, in the `value_id` help and in `expire`'s sentence,
  rather than letting the chip imply a vending machine.

  ── :nav :system ──

  A request is neither work nor a decision, and a `:primary` row here
  would card in do-now beside the actual work and be congratulated by
  fuel for having ASKED — the exact self-referential loop the outcome
  kinds already refuse for themselves. Filterable by who asked, so the
  crown reads one person's standing requests in one query, and by
  state, so a composer under its grant (`{kind composition_request,
  actions []}` — read only, and the read is the whole of what it
  needs) can ask for the ones nobody has answered."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

;; ── the leash, as a household number ────────────────────────────────

(def leash-days
  "How long a request stands before the house stops holding it open.
  SEVEN, the outcome's own leash (`outcome/leash-days`) and for the
  same sentence: the crown says THIS WEEK COULD HOLD, and a request
  still open on the eighth morning is asking about a week that is
  over. A composer that sits down a week late composes against the
  week the house is actually having, and the person asks again with
  one tap if the want survived. Spelled here rather than read off the
  outcome ns so the two kinds do not require each other in a ring."
  7)

;; ── the walls ───────────────────────────────────────────────────────

(defn- an-agent?
  "The one predicate the person-wall is a pure function of. :system is
  the ENGINE's own actor — a migration, a seed, the conformance walker
  — and is not what the wall is about; the wall is about the composer."
  [ctx]
  (= :agent (:type (:principal ctx))))

(defguardfn only-a-person-asks
  {:reads [:principal]
   :explain "Asking for another outcome is the household's own pull, and the weekly cap is what it pulls PAST — so a request has to come from a person's hand. A composer that could ask itself for a third would have walked around the one wall that makes it rank. If you are an agent and you think the house would want another, say so where an agent may: publish an insight, cite what you read, and let a person tap."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind, so the render probe and
  ;; the real invoke read the same fact — value/written-by-a-person's
  ;; posture, and outcome/a-person-answers', one door over
  (if (an-agent? ctx) (t/deny) (t/allow)))

(def ^:private held-states
  "The value states a request may aim at — `outcome/held-states`, the
  same two, spelled here for the ring reason `leash-days` gives. An
  observed value is admitted on purpose: a person asking for an
  outcome that serves a value an agent noticed is the person answering
  the observation with a want, which is the strongest 'yes' a reading
  can get short of the affirmation tap."
  #{"observed" "declared"})

(defguardfn aims-at-a-value-this-house-holds
  {:judges [:value_id]
   :reads [:value]
   :vars [:problem]
   :open "A request may name the value the next outcome should serve. When it does, it names one this house is holding — declared by a member, or observed and not yet answered. Naming none is the common case: 'compose me another' is a whole request."
   :explain "That is not a value this house is holding: {problem}"}
  [_row inp ctx]
  (let [read' (:read ctx)
        vid (some-> (:value_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      ;; absent is the common case and the chip's own spelling
      (nil? vid) (t/allow)
      ;; the storage-free probe: advertise optimistically, the write
      ;; path always carries the read
      (nil? read') (t/allow)
      :else
      (let [row (read' :value vid)]
        (cond
          (nil? row)
          (deny (str "this house has no value " vid
                     " — read /api/values and name one of those, or"
                     " leave the aim empty."))

          (not (held-states (name (:state row))))
          (deny (str "the value " (pr-str (get-in row [:data :name]))
                     " is retired. The house stopped holding that one,"
                     " and asking for an outcome that serves it would"
                     " be asking the composer to bring it back through"
                     " the side door."))

          :else (t/allow))))))

(defguardfn answered-by-a-composition
  {:reads [:within]
   :explain "A request is answered by an outcome — the composer stages one that cites it, and the staging itself marks the request answered in the same stroke. Nothing else answers one: not a person's tap and not an agent's post, because a request marked answered with no outcome behind it would be the household's own pull burned by a hand that composed nothing. To answer this, stage an outcome at /api/outcomes with request_id naming it."}
  [_row _inp ctx]
  ;; THE ONE READ OF `:within` IN THE TREE, so far. At the wire and on
  ;; the render probe it is nil, and the door renders refused — which
  ;; is the truth, because no client taps it. Inside `outcome`'s own
  ;; create it names that write, and the door opens. The action name
  ;; is the outcome's create door's own (`:create-action-names`, whose
  ;; default is `:create`), read off the ctx rather than assumed.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :outcome kind) (= :create action))
      (t/allow)
      (t/deny))))

(defguardfn the-ask-has-run-out
  {:reads [:now]
   :vars [:good_until]
   :open "Expiring is bookkeeping: it tidies a request the week has already answered, and it cannot be used to take a live one off the table."
   :explain "This request still stands until {good_until}. A person's pull is not something the machine withdraws early — it lapses with the week, and the composer answers it at its next sitting if that comes first."}
  [row _inp ctx]
  (let [exp (get-in row [:data :good_until])]
    (if (and exp (pos? (compare exp (:now ctx))))
      (t/deny {:vars {:good_until (str exp)}})
      (t/allow))))

;; ── the stamps ──────────────────────────────────────────────────────

;; WHO ASKED and HOW LONG IT STANDS, neither the caller's to give: a
;; request that could name somebody else as its author would be a
;; request that spends somebody else's pull, and the leash is the
;; household's number rather than the asker's.
(defhandler stamp-the-asker [row ctx]
  (-> row
      (assoc-in [:data :requested_by] (:id (:principal ctx)))
      (assoc-in [:data :good_until]
                (.plusSeconds ^Instant (:now ctx)
                              (* 86400 (long leash-days))))))

;; WHICH OUTCOME ANSWERED IT — the one field the answer door takes,
;; handed over by the outcome's own staging with the id it minted for
;; itself before its insert. It is the join a composer reads back:
;; "the request I answered, and with what".
(defhandler record-the-outcome [row inp _ctx]
  (assoc-in row [:data :answered_by] (some-> (:outcome_id inp) str str/trim)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; BOTH ARE CHECK-TIER: the answer-wall reads `:within` (nil at the
;; check tier, exactly as at the wire) and the expiry-wall reads the
;; clock — so `make check-queue` judges them with no database.
;;
;; NO SCENARIO NAMES `only-a-person-asks`, AND THE ABSENCE IS FORCED
;; RATHER THAN AN OMISSION. A create attempt runs every create guard,
;; and `aims-at-a-value-this-house-holds` reads a kind — so a scenario
;; on this door is deferred to the conformance tier, where it would be
;; attempted AS AN AGENT through the real router, and the router's
;; default deny answers an unleashed agent 404 before any wall speaks
;; (waymark-zs9: the walker holds no leash). The wall is proved where
;; an agent can be leashed: workqueue10.outcome-test
;; (`an-agent-does-not-mint-a-request`) and the `:feed/outcomes`
;; obligation, both by an agent HOLDING a grant over this very door.
;; What else wants a live engine — a request admitting a third outcome
;; past the cap, a second citation meeting `answered`, a request
;; naming a value refusing an outcome that serves another — is proved
;; in the same file, where the cap's own rows are.

(def ^:private a-standing-request
  {:requested_by "colton"
   :good_until (Instant/parse "2026-09-01T09:00:00Z")})

(defscenario nothing-but-a-staging-answers-a-request
  "The answer door opens from INSIDE an outcome's own create and from
   nowhere else — a client's knock, which is what a scenario is,
   arrives with no `:within` and is refused by name. This is the wall
   'one request, one outcome' rests on, and it is a wall rather than a
   grant's promise because the framework now says which hand opened a
   door."
  {:kind    :composition_request
   :attempt :answer
   :row     {:state :offered :data a-standing-request}
   :as      {:id "colton" :type :person}
   :input   {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"}
   :expect  {:refused :answered-by-a-composition
             :because "stage an outcome"}})

(defscenario a-live-request-is-not-expired-out-of-the-way
  "Expiring is bookkeeping, never a way to withdraw a person's pull
   early. The clock retires a request; nothing else does."
  {:kind    :composition_request
   :attempt :expire
   :at      "2026-08-25T09:00:00Z"
   :row     {:state :offered :data a-standing-request}
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-ask-has-run-out
             :because "still stands"}})

;; ── the prose the doors wear ────────────────────────────────────────

(def ^:private prose
  {:value_id
   {:x-display
    {:label "The value it should serve"
     :help "Which of the house's values the next outcome should be FOR, if you have one in mind — leave it empty and the composer chooses. Either way the answer comes at the composer's next sitting rather than on the tap: this writes an invitation, and somebody sits the composer down to read it."}}
   :value_name
   {:x-display
    {:raw true
     :label "Value"
     :help "The value's own words, copied by the engine so the row reads without a second lookup."}}
   :requested_by {:x-display {:raw true :label "Asked by"}}
   :answered_by
   {:x-display
    {:raw true
     :label "Answered by"
     :help "The outcome that answered this request, written by the engine when the composer staged it. One request admits one outcome past the weekly cap, and this is the one."}}
   :good_until
   {:x-display
    {:label "Standing until"
     :help "When the house stops holding this request open. Seven days from the tap, written by the engine — the crown says \"this week could hold\", and a request still open on the eighth morning is asking about a week that is over. Ask again with one tap if the want survived."}}})

(defn- entry [k extra form] [k (merge (get prose k) extra) form])

;; ── :composition_request — the person's pull ────────────────────────

(defresource composition-request
  {:kind :composition_request
   :plural "composition_requests"
   ;; see the ns docstring: a request is neither work nor a decision
   :nav :system
   :states [:offered :answered :expired]
   :initial :offered
   :terminal #{:answered :expired}
   :summary "Compose me another · {state}"
   :label-template "Compose me another"
   :display {:title "Request for another outcome"}
   :links [{:rel "value" :kind :value
            :href "/api/values/{data.value_id}"
            :summary "The value the next outcome should serve"}
           {:rel "outcome" :kind :outcome
            :href "/api/outcomes/{data.answered_by}"
            :summary "The outcome that answered this request"}]
   :schema
   [:map
    ;; the aim, with the label garnish `outcome.value_id` wears —
    ;; the engine maintains the value's own words beside the ref.
    ;; Filterable so a composer can read "the requests aimed at this
    ;; value" in one query; this is a NEW table, so the generated
    ;; column costs the household no revision on a row that exists.
    (entry :value_id {:optional true :kind :value :label :value_name
                      :filter #{:eq}}
           [:maybe :waymark/ref])
    (entry :value_name {:optional true} [:maybe [:string {:max 80}]])
    ;; ENGINE-WRITTEN, all three: in the row schema because they are
    ;; the row's document, out of the create model because none of
    ;; them is anybody's to supply
    (entry :requested_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    ;; ENGINE-WRITTEN at the answer, and named for WHAT it is rather
    ;; than `outcome_id`: the answer door's input carries the outcome's
    ;; id under that name, and a document field spelled the same would
    ;; make the door read as an EDIT of this row (the battery's own
    ;; rule, and it is right — an edit implies a fence, and this door
    ;; is opened by another kind's hand with no etag to give)
    (entry :answered_by {:optional true} [:maybe [:string {:max 64}]])
    (entry :good_until {:optional true :filter #{:before}}
           [:maybe :waymark/instant])]
   :create-schema
   [:map
    (entry :value_id {:optional true :kind :value} [:maybe :waymark/ref])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at :good_until] :default "-created_at"}
   :on-create stamp-the-asker
   ;; shape first, world next — and no pace wall at all, on purpose:
   ;; the person can always pull, and a cap on asking would be the cap
   ;; this kind exists to get past, one door over
   :create-guards [only-a-person-asks
                   aims-at-a-value-this-house-holds]
   :actions
   {:answer
    {:from #{:offered} :to :answered
     ;; a typed ref, so the picker, the navigable reference and the
     ;; convention check read one declaration — and it resolves to
     ;; nothing at the instant it is written, on purpose: the outcome
     ;; is inserted right after its own :on-create hands this id over
     ;; (dangling refs are a guard's business, never the engine's, and
     ;; the wall above already says whose hand this is)
     :input [:map [:outcome_id
                   {:kind :outcome
                    :x-display
                    {:label "The outcome that answers it"
                     :help "The outcome's own id, handed over by its staging — the one this request admitted past the weekly cap."}}
                   :waymark/ref]]
     :guards [answered-by-a-composition]
     :handler record-the-outcome
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The request is answered — the outcome named here is the one it admitted past the weekly cap, and no second outcome may cite it. Nobody taps this: the composer's own staging does, in the same stroke that writes the outcome."}
     :display {:label "Answered by an outcome" :order 9
               :description "Written by the engine when a composer stages an outcome citing this request — never tapped by hand"}}
    :expire
    {:from #{:offered} :to :expired
     :guards [the-ask-has-run-out]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The week already answered this one; the row now says so. Ask again with one tap if the want survived."}
     :display {:label "Expire" :order 8
               :description "Tidy a request the week ran out on"}}}
   :scenarios [nothing-but-a-staging-answers-a-request
               a-live-request-is-not-expired-out-of-the-way]})
