(ns workqueue10.resources.person
  "The person (waymark-jfv.11): who is who in this house, so plans stop
  guessing.

  EVERY NAME IN THIS FILE IS INVENTED. This house's own roster is
  production data and lives nowhere in this tree — it is written
  through the ordinary create door after a deploy, by the family, in
  their own words. A fixture that carried the real one would be this
  kind's own bug: a roster nobody typed is a roster somebody guessed.
  The invented cast below (Odell, a grandfather; Bram, his CNA; Marta,
  a contractor; Nessa, a caregiver who left) exists so the prose and
  the scenarios can say something concrete.

  The owner's own words are what this kind is downstream of, and they
  arrived as a correction rather than a wish. A composer read this
  house's record, found a woodworking build, found a caregiver's name
  in the same neighbourhood of rows, and composed `build the
  finger-joint box with him` — a Saturday afternoon with a son. HE IS A
  GRANDPARENT'S CNA. Every row the composer read was correct; the
  relationship it assembled out of them was invented, because
  relationships were nowhere in the record, and a composer without them
  builds wrong stories from right rows.

  So: a ROSTER. Their everyday name, how they relate to this house in
  the owner's own words, who they relate THROUGH when they relate
  through somebody, when they were born if the family knows, and
  whether they are in this house's life now or were. That is the whole
  of it. It is deliberately not a genealogy, not an address book and
  not a contact list — a roster exists so that a sentence with a name
  in it can be checked.

  ── PERSONS ARE NOT MEMBERS ──

  A member is a login principal: somebody who holds a session, is
  granted capabilities, and whose id is stamped on transitions. Most of
  the people in this house's life will never log in — a one-year-old
  cannot, a CNA has no business doing so, and a contractor would find
  it strange to be asked. The roster is about WHO SOMEBODY IS to this
  family; the member registry is about WHO IS AT THE DOOR. Conflating
  them would have meant either minting login identities for toddlers or
  leaving them out of the plans that are mostly about them.

  NO `member_id` LINK IN v1, and it was weighed rather than forgotten.
  It is one optional field and nothing reads it yet — but it is not the
  cheap field it looks like, because setting it is an IDENTITY
  ASSERTION (`this person IS that principal`) and the hand most likely
  to reach for it is the observer's, which is the one hand that must
  never make one. An honest link needs its own wall — a person's own,
  and plausibly only about himself — and that wall is worth writing
  when something actually reads the link. Filed as waymark-jfv.12 rather
  than guessed at.

  ── `born`, NOT `age`, AND THE REASON IS THE BEAD'S OWN ──

  An age is a fact about the morning somebody typed it. `the middle
  boy, son, 7` is true today, quietly wrong next spring, and
  embarrassingly wrong in three years — and this kind exists precisely
  so a composer stops working from numbers nobody checked. A DATE never
  goes stale: it is what a family already knows about its own children,
  it is on the fridge, and it is the only spelling that can answer the
  question a planner will actually ask next (`is somebody's birthday
  inside this week?`). A birth YEAR was the middle road and loses on
  the youngest person in a house: a year is ±12 months, and twelve
  months is the entire difference between a one-year-old and a
  two-year-old.

  It is OPTIONAL, and the optionality is the honest half of the
  decision. Nobody is writing down a grandfather's birthday to satisfy
  a form, and no house knows its contractor's. An empty `born` says
  `this house does not track that about them`, which is true and
  useful; a guessed year would have said something false with a
  number's confidence.

  ── `through` — THE FIELD THE MISCOMPOSITION WOULD HAVE READ ──

  The caregiver does not relate to this house. He relates to THE
  GRANDFATHER, and the house relates to the grandfather. That is the
  fact the composer did not have, and it is one ref: `through_id`, a
  person, optional, carrying the maintained `through_name` garnish so a
  card reads `Bram · Odell's CNA · through Odell` without a join.

  A REF AND NOT A NAME, deliberately. A free-text `through` would have
  been the same class of thing the bead is about — a name in a sentence
  that nothing checks. The roster is small, it is seeded in dependency
  order (the person somebody relates through is written first), and
  `relates-through-somebody-here` refuses a dangling one at every door
  that can write it. The same wall refuses a row that relates through
  ITSELF, which is the one cycle worth spending a check on; deeper
  rings (A through B through A) are not walled: waymark-jfv.15 records
  that, and says what would have to be true before walking the chain
  is worth a read per hop.

  ── CURRENT AND PAST ARE STATES, AND SO IS THE AFFIRMATION ──

  A CNA who leaves is a transition worth a record — that is the bead's
  ruling and it is the right one. A boolean would have flipped in
  silence; a state change is dated, attributed, and readable in the
  log, which is exactly what the finding that filed this bead needed: a
  week of dropped cleaning is assigned to a caregiver who no longer
  works here, and nothing in this house ever heard she left. The
  dispatch letter read that as a cadence problem. It is a staffing
  change the rotation was never told about, and no amount of pushing
  harder was ever going to fix it.

  The second axis is waymark-jfv.10's, mirrored on purpose: AN AGENT
  MAY WRITE WHAT IT OBSERVES; ONLY A PERSON SAYS WHO WE KNOW. A person
  row an agent writes is born `observed` and SAYS SO wherever it is
  cited, for jfv.10's own reason — `summary/render` has no conditional,
  so a missing stamp renders an em-dash and can never speak its own
  absence, while `{state}` speaks on every envelope, every list line
  and every transition record. `:filterable :state` then makes `who has
  been written down and not yet answered` a query this house already
  owns.

  …AND SINCE waymark-sfe (2026-08-28), \"only a person says who we
  know\" reads with one clause more: only a person, OR a delegate the
  person permissioned at this very door. The owner's ruling — \"it
  doesn't make sense to disallow it, it just makes sense to permission
  it\" — turned the outright agent refusal into a grant check, so an
  agent presenting a scope that names `person.still_with_us` may say
  the owner's yes on the owner's instruction, and the transition
  records which grant it said it under. The agent that WROTE the row
  is still refused whatever it presents: an observer never answers its
  own reading, and that half is four eyes rather than permission.

  SO THE TWO AXES SHARE ONE COLUMN: `observed`, `current`, `past`. The
  combination the column cannot hold is `observed AND past` — an
  unaffirmed departure — and it is not a loss, because nothing may plan
  with a `past` person and nothing may plan with an `observed` one
  either. What a row in `observed` means is `somebody wrote this down
  and the house has not answered`; what the house's answer is (`yes,
  and they are with us`, `they were, and they are not now`, `that is
  not somebody we know`) is which door it walks out through.

  ── THE COST, PAID IN THE OPEN, TWICE ──

  1. THE WORDING DOOR SPLITS BY HAND, jfv.10's cost inherited whole.
     `:to` is a static keyword, so one door cannot land in two states,
     and a shared door would have had an observer's own correction land
     in `current` — the observer answering its own guess. So `revise`
     is a person's, lands in `current`, and correcting an observed
     person CLAIMS them in the same stroke; `restate` is the
     observer's, runs `observed → observed`, and never leaves the
     record of what was noticed.

  2. `now_past` CARRIES NO `:undo`, AND THE DEPARTED CAREGIVER BOUGHT
     THAT. An `:undo` must return exactly where it departed from, so
     jfv.10 split `retire` (from `declared`, with an undo) from
     `dismiss` (from `observed`). Here the departure from `observed` is
     not an edge case — it is the FIRST case: the motivating row is a
     person an agent found in the record who has ALREADY LEFT. Making
     the owner affirm her as current before marking her past would
     write a lie for one transaction on the way to the truth. So
     `now_past` leaves from both states, carries no `:undo`, and
     `restore` is its own door landing in `current` — a person reaching
     for it has held them again with his own hand.

  `dismiss` survives beside it for the household's reason rather than
  the mechanical one: `she left` and `that is not somebody we know` are
  different sentences, and the composer reading this log must be able
  to tell a STAFFING CHANGE from a BAD GUESS. Both land in `past`,
  because a person this house is not planning with is a person this
  house is not planning with — the one place the mirror strains, since
  `past` carries a `was here once` that a hallucination never earned.
  The doors are what keep them apart; the state only keeps them out of
  plans.

  ── NO SCOPE, AND THE ABSENCE IS A DECISION ──

  `value` carries `household` / `mine` because a value is a sentence
  about one member's inner life and another adult has no business
  rewording it. A person is a fact about the household's WORLD, and
  there is no honest `mine` reading of `he is the grandfather's CNA` —
  the boys' grandfather is not one member's grandfather in the sense
  that would need a wall. So there is no scope, no owner stamp and no
  `a-private-value-is-a-persons-own`: every row here is the house's,
  which is also why `restate` needs no ownership wall.

  `written_by` STAYS, because it answers the question scope never did:
  whose hand wrote this row down. It is the first thing anybody will
  want to filter on after a week of a composer writing things down.

  ── NO DEFAULT FILTER, ON PURPOSE ──

  A roster that opened on `state=current` would hide exactly the rows
  that need a person: the ones somebody wrote down and nobody has
  answered. The page shows everybody and the state says which is which.

  ── :nav :secondary, AND NO CARD ──

  `next_actions` claims the OPEN rows of every `:nav :primary` kind and
  `fuel` congratulates their endings. A person is permanently open by
  construction — `current` is where somebody LIVES, not where they wait
  — so a `:primary` person would card in do-now forever and a departed
  CNA would be celebrated as a deed. `value`'s reasoning, one kind
  over, and the same answer.

  THIS KIND POPULATES NOTHING AND THEREFORE OWES NO PACK OBLIGATION.
  Persons are a roster, not cards: nothing about a person is a thing to
  do, and the feed's job is what the house could do next. What reads
  the roster is `outcome/names-a-person` at the composer's create door,
  and one day waymark-jfv.5's contract. When something DOES render
  persons — a roster screen with the unanswered ones on top is the
  obvious first ask — that is when a population and its pack obligation
  are earned."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario unless-granted]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the walls ───────────────────────────────────────────────────────
;;
;; The hand-walls are `value`'s law with `person`'s own sentences. The
;; law is shared deliberately and the WORDS are not: a refusal is
;; reading material for whoever hit it, and telling a composer to
;; "publish an insight offering this VALUE's own yes" when what it was
;; writing down was a caregiver would be the framework talking to
;; itself.

(defn- a-persons-hand?
  "Somebody in this house, as opposed to the composer or the engine.
  `types/actor-types` is #{:human :agent :system} and a scenario may
  spell a member `:person`, so both words for the same hand are named
  here rather than in the several places that could drift apart."
  [ctx]
  (contains? #{:human :person} (:type (:principal ctx))))

;; THE ROSTER WALL, GRANTABLE (waymark-sfe, the owner's ruling of
;; 2026-08-28). `value`'s law with `person`'s own sentence, exactly as
;; before — and now `value`'s new shape too: a person passes; an agent
;; that WROTE this row is refused, grant or no grant (`:own-field
;; :written_by`, which is four eyes and the whole of jfv.11's "the
;; observer does not answer its own reading"); any other agent passes
;; only under a grant admitting `person.<this door>`.
(defn- roster-wall
  "`only-a-person-says-who-we-know`, for one door. It stands on every
  door that lands a row in `current` or takes somebody out of the
  house's life, and on none that only writes down what was seen."
  [action]
  (unless-granted
   :person action
   {:name :only-a-person-says-who-we-know
    :own-field :written_by
    :explain "Who is in this family's life is the family's own sentence. You may write down somebody you found in this house's record — that row is born observed and says so wherever it is cited — but answering for it would be telling the owner who his people are. Leave it observed and say what you found: publish an insight, cite the rows the name came from, and offer this row's own \"yes — still with us\" as the one next step. He answers with a tap, or puts it in his own words, and either way the row becomes this house's."}))

(defguardfn only-the-observer-corrects
  {:reads [:principal]
   :explain "This door is the observer's own — it fixes what an agent wrote down while the row is still a guess, and leaves it a guess. Correcting somebody is a larger thing when you do it: those words become how this house describes a person, and the row becomes the house's in the same stroke. That door is \"Put it in your words\"."}
  [_row _inp ctx]
  ;; the mirror of the wall above, and it exists for the household
  ;; rather than for safety: a person who reached `restate` would be
  ;; editing the roster WITHOUT answering it, which is not a thing
  ;; anybody here wants to do — and because both walls are pure
  ;; functions of the principal, each hand is offered exactly one
  ;; correcting door instead of two that look alike.
  (if (a-persons-hand? ctx)
    (t/deny)
    (t/allow)))

(defguardfn relates-through-somebody-here
  {:judges [:through_id]
   :reads [:person]
   :vars [:problem]
   :open "Somebody may relate to this house THROUGH somebody else — a grandparent's caregiver belongs to the grandparent, not to the house. When a row says so, it names a person this house actually holds."
   :explain "That is not somebody this house can relate anyone through: {problem}"}
  [row inp ctx]
  (let [read' (:read ctx)
        tid (some-> (:through_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      ;; ABSENT IS THE COMMON CASE. Most of this house relates to it
      ;; directly, and a door that demanded a chain would invent one.
      (nil? tid) (t/allow)
      ;; the storage-free probe (render, the partial rehearsal) has no
      ;; read hook: advertise optimistically, as every world-reading
      ;; wall in this tree does. The write path always carries it.
      (nil? read') (t/allow)

      (= tid (str (:id row)))
      (deny (str "it is this row itself. Nobody relates to a house"
                 " through themselves — leave it empty and the relation"
                 " you already wrote says how they relate directly."))

      :else
      (let [through (read' :person tid)]
        (if (nil? through)
          (deny (str "this house has nobody at " tid
                     " — read /api/people and name one of those, or"
                     " write them down first and then say so."))
          (t/allow))))))

;; ── the stamps ──────────────────────────────────────────────────────

(def authored-fields
  "The surface `revise` and `restate` overwrite wholesale — the same
  fields both doors take, so what is stored is exactly what was judged.
  `written_by` and the affirmation stamps stay OUT: they are the
  engine's, not anybody's input."
  [:name :relation :through_id :born])

(defn- rewritten
  "The authored surface, overwritten wholesale: an omitted optional
  CLEARS, so a person whose `through` is dropped really relates to this
  house directly now. Both correcting doors write through here; what
  separates them is whether the landing is an answer."
  [row inp]
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) authored-fields))))

(defn- affirmed
  "\"Yes, that is who they are to us.\" Who and when, from the engine's
  own :now and principal — never from a body, and never from a clock
  this file reads.

  Stamped at EVERY door a person's hand lands `current` through, and on
  a roster that stamp earns its keep twice over: it is the answer to
  `is the contractor still ours, or is that just what the row has said
  since March?`. A roster nobody has confirmed in a year is a roster
  that has started guessing again."
  [row ctx]
  (-> row
      (assoc-in [:data :affirmed_at] (:now ctx))
      (assoc-in [:data :affirmed_by] (get-in ctx [:principal :id]))))

(defhandler apply-revision [row inp ctx]
  ;; the household's own correcting door. The transition carries what
  ;; was written (:record true), so the log is the history of how this
  ;; house has described somebody — and the stamp is here because this
  ;; door lands in `current`: putting an observed person in your own
  ;; words CLAIMS them.
  (-> row (rewritten inp) (affirmed ctx)))

(defhandler apply-restatement [row inp _ctx]
  ;; the observer's correcting door. The same overwrite and NO stamp:
  ;; the row is still a guess when this handler is done with it, which
  ;; is the whole difference between the two doors.
  (rewritten row inp))

(defhandler stamp-the-answer [row _inp ctx]
  ;; the tap, the departure and the return. Nothing about who they are
  ;; changes; what changes is that this house has said so, and when.
  (affirmed row ctx))

(defn- stamp-the-writer
  "Whose hand wrote this row down — ANY principal, agent or person,
  written by the ENGINE and never trusted from the body (`value`'s
  `written_by`, `insight`'s `authored_by`, `outcome`'s `composed_by`).

  There is no `owner` beside it here, because a person has no scope:
  see the ns docstring. This kind's rows are the house's, all of them."
  [row ctx]
  (assoc-in row [:data :written_by] (:id (:principal ctx))))

(defn- born-into
  "The birth state, and the birth stamps. A person writing somebody
  down has already decided who they are, so his row lands `current` and
  is answered in the same breath; anybody else's lands `observed` — the
  initial — and says so wherever this house cites it until a hand moves
  it.

  The engine's own actors (a seed, a migration, the conformance walker)
  land `observed` too, and that is the point of the default rather than
  an oversight: a row nobody's hand can be found on is a guess, and a
  guess is the safe direction for this to fail in."
  [row ctx]
  (let [row (stamp-the-writer row ctx)]
    (if (a-persons-hand? ctx)
      (-> row (assoc :state :current) (affirmed ctx))
      row)))

;; ── the household's own words ───────────────────────────────────────
;; Spelled once and worn by all three surfaces — the row schema, the
;; narrower create form, and the correcting input — because three
;; copies of one sentence is three places for it to drift.

(def ^:private prose
  {:name {:x-display
          {:label "Their name"
           :help "What this family calls them out loud — the everyday name, not the one on a birth certificate. It is the name a plan will use in a sentence somebody reads standing up on a Saturday morning."}}
   :relation {:x-display
              {:label "How they relate to this house"
               :help "In your own words — \"wife\", \"son\", \"the grandfather's CNA\", \"contractor\". Not a category off a list: the words you would actually use. These are the words a composer reads before it builds an afternoon around somebody, and the whole reason this row exists is that it once had to guess."}}
   :through_id {:x-display
                {:label "Who they relate through"
                 :help "Leave this empty for everybody who belongs to this house directly. Fill it in when somebody is in your life BECAUSE of somebody else — a grandparent's caregiver, a contractor a neighbour sent. A caregiver is not this house's; he is the person he cares for's, and a plan that forgets that pairs the wrong people."}}
   :through_name {:x-display
                  {:raw true
                   :label "Through"
                   :help "The name of the person they relate through, kept beside the reference by the engine so a card reads without a second lookup."}}
   :born {:x-display
          {:label "Born"
           :help "The day they were born, if this house knows it — and it is a DATE rather than an age on purpose: an age is a fact about the morning somebody typed it, and this roster exists so a plan stops working from numbers nobody checked. Leave it empty for everybody whose birthday is not this family's business; an empty one says so honestly, where a guessed year would say something false with a number's confidence."}}
   :written_by {:x-display
                {:raw true
                 :label "Written down by"
                 :help "Whose hand wrote this row — somebody in the house, or an agent that found the name in this house's record. Stamped by the engine, never by a form."}}
   :affirmed_at {:x-display
                 {:label "Last confirmed"
                  :help "When somebody here last said this is still true — the tap, a correction in your own words, or holding them again after they had gone past. Written by the engine at the door. Empty means nobody has answered yet."}}
   :affirmed_by {:x-display
                 {:raw true
                  :label "Confirmed by"
                  :help "Who said this is one of ours. Written by the engine at the door."}}})

(defn- entry
  "One [:key props schema] entry of a form: the shared prose plus
  whatever this surface adds of its own."
  [k extra form]
  [k (merge (get prose k) extra) form])

(def revise-input
  "What `revise` and `restate` both take: the whole authored surface,
  overwritten wholesale. Public for the reason `value/revise-input` and
  `feed_recipe/recipe-input` are — the door a staged correction would
  one day be validated against has to be the door itself rather than a
  copy of it that could drift."
  [:map
   (entry :name {} [:string {:min 1 :max 80}])
   (entry :relation {} [:string {:min 1 :max 80}])
   (entry :through_id {:optional true :kind :person} [:maybe :waymark/ref])
   (entry :born {:optional true} [:maybe :waymark/date])])

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; TWO TIERS, and which scenario lands in which is read off the
;; declarations rather than chosen (scenario.clj's own rule). The three
;; whose door carries only a HAND wall are judged by `make check-queue`
;; with no database at all; the other three share a door with
;; `relates-through-somebody-here`, which reads another row, so they
;; defer to the conformance suite and are attempted through the real
;; HTTP door there.
;;
;; AND THAT SPLIT IS WHY THREE SENTENCES OF THIS KIND'S LAW ARE PROVED
;; IN person_test RATHER THAN HERE. A conformance-tier scenario cannot
;; be attempted AS AN AGENT: the walker presents no grant, the router
;; default-denies with 404, and `packs/wire-verdict` reads a 404 as
;; unreadable on purpose — a hide-flagged guard conceals rather than
;; narrates, so no scenario may name one through the door. That is a
;; framework hole and is filed as waymark-zs9 — `packs/leash!` already
;; knows how to mint the grant a scenario would need. Every door
;; this kind lets an agent WRITE through checks the `through` ref, and
;; checking it means reading the roster, so `an agent may write down
;; somebody it found` and `an agent may correct what it observed` have
;; no expressible scenario. They are proved over the real handler by an
;; agent HOLDING A LEASH instead, which is the stronger sentence
;; anyway: the check tier can only say the wall would not refuse.
;;
;; The cast is invented — see the ns docstring. A scenario's data
;; becomes a REAL ROW in the conformance walker's database, staged
;; through this kind's own create door, which is both why the fixtures
;; below carry nothing the create model would refuse and why this
;; house's own roster may not appear here.

(def ^:private somebody-observed
  {:name "Bram"
   :relation "Odell's CNA"})

(def ^:private somebody-current
  {:name "Odell"
   :relation "grandfather"})

(defscenario an-agent-does-not-say-who-is-family
  "THE WALL THIS KIND IS FOR. An agent may say whom it found; it may
   not answer for the family about who they are to it, because that is
   the exact sentence the miscomposition invented. The refusal names
   the lawful path: publish an insight, cite the rows the name came
   from, and offer this row's own tap — to the people whose sentence
   it is."
  {:kind    :person
   :attempt :still_with_us
   :row     {:state :observed :data somebody-observed}
   :as      {:id "compiler" :type :agent}
   :expect  {:refused :only-a-person-says-who-we-know
             :because "publish an insight"}})

(defscenario a-person-puts-it-in-his-own-words-rather-than-restating
  "The mirror wall, and it is for the household rather than for safety.
   A person who reached the observer's door would be editing the roster
   WITHOUT answering it — and because both walls are pure functions of
   the principal, each hand is offered exactly one correcting door
   instead of two that look alike."
  {:kind    :person
   :attempt :restate
   :row     {:state :observed :data somebody-observed}
   :input   {:name "Bram"
             :relation "Odell's CNA"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :only-the-observer-corrects
             :because "Put it in your words"}})

(defscenario an-agent-does-not-write-somebody-out-of-this-house
  "The departure door, from the wrong side. An agent that notices a
   caregiver has stopped appearing in the record has noticed something
   worth saying — and saying it is an insight, not a state change. Who
   has left this family's life is the family's sentence in both
   directions."
  {:kind    :person
   :attempt :now_past
   :row     {:state :observed :data somebody-observed}
   :as      {:id "compiler" :type :agent}
   :expect  {:refused :only-a-person-says-who-we-know
             :because "telling the owner who his people are"}})

(defscenario a-guess-is-answered-with-one-tap
  "The petition path's own door, from the house's side: an agent's
   finding offers this action, it asks for nothing, and the answer is a
   thumb. On an observed row it is the whole answer. On a current one
   it is the roster's freshness — \"yes, the contractor is still ours\"
   — which is the fact that stops a roster quietly going stale into a
   composer's next plan."
  {:kind    :person
   :attempt :still_with_us
   :row     {:state :observed :data somebody-observed}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-departed-person-is-not-corrected-back-into-the-week
  "The machine itself keeps a past person out of the correcting door,
   with no guard behind it, which is the strongest way a promise can be
   made. Somebody who has gone past comes back through `restore` — one
   hand, one sentence, on the record — and only then are their details
   the house's to reword."
  {:kind    :person
   :attempt :revise
   :row     {:state :past :data somebody-current}
   :input   {:name "Odell"
             :relation "grandfather"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Current"}})

(defscenario a-relation-runs-through-somebody-this-house-holds
  "The ref is CHECKED, which is the difference between a roster and a
   document. `through` was the one fact the miscomposition would have
   read — the caregiver relates to the grandfather, and the house
   relates to the grandfather — so a chain that names nobody is refused
   where it was written rather than discovered by a plan built on it."
  {:kind    :person
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:name "Bram"
             :relation "Odell's CNA"
             :through_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9C0"}
   :expect  {:refused :relates-through-somebody-here
             :because "this house has nobody at"}})

;; ── :person — the roster this house plans from ──────────────────────

(defresource person
  {:kind :person
   :plural "people"
   ;; see the ns docstring: a :primary person would card in do-now
   ;; forever and a departed CNA would be congratulated as a deed
   :nav :secondary
   ;; TWO AXES, ONE COLUMN. `current` / `past` is the bead's own ruling
   ;; — a CNA who leaves is a transition worth a record, not a boolean
   ;; that flips in silence — and `observed` is waymark-jfv.10's
   ;; affirmation axis riding the same column, because only a STATE can
   ;; speak: the summary below renders `{state}`, so a row nobody has
   ;; answered says "Observed" on its own line, in every list and in
   ;; every transition record, where a missing stamp would have
   ;; rendered an em-dash. `observed` is the initial and a person's
   ;; create promotes out of it (`born-into`), so the direction a bug
   ;; fails in is the safe one.
   :states [:observed :current :past]
   :initial :observed
   :terminal #{}
   :summary "{data.name} · {data.relation} · {state}"
   :label-template "{data.name}"
   :display {:title "Person"}
   :links [{:rel "through" :kind :person
            :href "/api/people/{data.through_id}"
            :summary "The person they relate to this house through"}]
   :schema [:map
            ;; :eq-filterable since waymark-36s: `thread`'s
            ;; :participants is a :many external-keyed ref matched on
            ;; THIS field, and the framework's assembly check demands
            ;; the matched target field be :eq-filterable — the
            ;; resolution is one indexed read per distinct name, on
            ;; the promoted column or not at all. `relation` still
            ;; carries none, deliberately (exact-match on free prose
            ;; is a trap); a name is the opposite case — it is
            ;; precisely what an outside system hands you to match on.
            (entry :name {:sort :default :filter #{:eq}}
                   [:string {:min 1 :max 80}])
            ;; FREE WORDS, and they are the owner's. No enum: a house's
            ;; relationships are not a schema's to enumerate, and
            ;; "the grandfather's CNA" is not a category — it is the
            ;; sentence a family actually says.
            (entry :relation {} [:string {:min 1 :max 80}])
            ;; A REF, NOT A NAME. See the ns docstring: a free-text
            ;; `through` would have been another unchecked name in a
            ;; sentence, which is the class of thing this bead exists
            ;; to end. The garnish beside it is the engine's.
            (entry :through_id {:optional true :kind :person
                                :label :through_name :filter #{:eq}}
                   [:maybe :waymark/ref])
            (entry :through_name {:optional true}
                   [:maybe [:string {:max 80}]])
            ;; A DATE AND NOT AN AGE, and never both — an age stored
            ;; beside a date is a second answer waiting to disagree
            ;; with the first.
            (entry :born {:optional true} [:maybe :waymark/date])
            (entry :written_by {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])
            (entry :affirmed_at {:optional true :sort true}
                   [:maybe :waymark/instant])
            (entry :affirmed_by {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])]
   ;; the client states who somebody is; the WRITER and the answer
   ;; stamps are the engine's, and so is the `through_name` garnish
   :create-schema [:map
                   (entry :name {} [:string {:min 1 :max 80}])
                   (entry :relation {} [:string {:min 1 :max 80}])
                   (entry :through_id {:optional true :kind :person}
                          [:maybe :waymark/ref])
                   (entry :born {:optional true} [:maybe :waymark/date])]
   ;; through_id, written_by and affirmed_by carry their own :filter on
   ;; the schema entries above — one concern, one home — so only the
   ;; machine's own column is spelled here. `relation` deliberately
   ;; carries none: exact-match on free prose is a trap, and the words
   ;; there are the family's rather than a vocabulary. :state is what
   ;; `outcome/names-a-person` reads when it asks whether a companion is
   ;; somebody this house is actually in a relationship with, and it is
   ;; also how a reader asks for the rows written down and not yet
   ;; answered. NO :default-filters, on purpose: a roster that opened on
   ;; the current people would hide exactly the rows that need a person.
   :filterable {:state #{:eq :in}}
   ;; NO agent wall at this door — waymark-jfv.10's ruling, read across
   ;; to the kind that most needs it: a composer that may not write
   ;; down the people it finds will keep inventing them instead.
   :create-guards [relates-through-somebody-here]
   :on-create born-into
   :scenarios [an-agent-does-not-say-who-is-family
               a-person-puts-it-in-his-own-words-rather-than-restating
               an-agent-does-not-write-somebody-out-of-this-house
               a-guess-is-answered-with-one-tap
               a-departed-person-is-not-corrected-back-into-the-week
               a-relation-runs-through-somebody-this-house-holds]
   :actions
   ;; THE CORRECTING DOOR SPLITS BY HAND, waymark-jfv.10's cost paid
   ;; again: `:to` is a static keyword, so one door cannot land in two
   ;; states, and a shared door would have had an observer's own
   ;; correction land in `current` — the observer answering its own
   ;; guess, which is the one thing this kind exists to forbid.
   {:revise {:from #{:observed :current} :to :current
             :input revise-input
             :edit {:prefill [:name :relation :through_id :born]}
             ;; the overwrite writes the whole authored surface and is
             ;; non-reversible, so the log carries what was written —
             ;; which is what makes the transitions the history of how
             ;; this house has described somebody
             :record true
             :guards [(roster-wall :revise)
                      relates-through-somebody-here]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "This overwrites how the house describes them with what you write; the log keeps the earlier words, who changed them and when. If they were only observed in your record, saying it in your own words is also what makes the row this house's."}
             :handler apply-revision
             :display {:label "Put it in your words" :order 1
                       :description "Fix their name, how they relate to this house, who they relate through, or when they were born — and if this one was only observed in your record, saying it yourself is what makes it the house's"}}
    ;; THE OBSERVER'S OWN CORRECTING DOOR. The same overwrite, the same
    ;; input, a self-loop, and no answer stamp: an agent that learns
    ;; more rewrites what it wrote down and the row is still a guess
    ;; afterwards. No ownership wall, because this kind has no scope —
    ;; every row here is the house's.
    :restate {:from #{:observed} :to :observed
              :input revise-input
              :edit {:prefill [:name :relation :through_id :born]}
              :record true
              :guards [only-the-observer-corrects
                       relates-through-somebody-here]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "This overwrites what was observed with what you now think you see; the log keeps the earlier reading. The row stays observed either way — nothing here makes it the house's, and no plan may name them until somebody says so."}
              :handler apply-restatement
              :display {:label "Correct what was observed" :order 3
                        :description "New evidence changed the reading — rewrite it. It stays observed: only a person's hand puts somebody in this family's life"}}
    ;; THE ANSWER, AND THE ROSTER'S FRESHNESS — one tap doing both. No
    ;; :input, deliberately and structurally: an insight's offer must be
    ;; no heavier than `selection`, and a door taking so much as one
    ;; string renders `recall`. Asking for nothing is what makes this
    ;; action a legal offer and a legal card chip. From `observed` it is
    ;; the whole answer; from `current` it is "yes, still — as of
    ;; today", which is the fact that keeps a roster from going quietly
    ;; stale into a composer's next plan.
    :still_with_us {:from #{:observed :current} :to :current
                    :guards [(roster-wall :still_with_us)]
                    :handler stamp-the-answer
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "This says they are somebody in this family's life, and stamps the date. From an observed row it is what lets a plan name them at all; from one already current it records that the roster is still true today. Nothing about who they are changes."}
                    :display {:label "Yes — still with us" :order 2
                              :description "Somebody in this house's life, described right. If they were only observed in your record, this is the tap that lets a plan name them; if they were already ours, this says the roster is still true"}}
    ;; THE DEPARTURE, and the reason it leaves from BOTH states is the
    ;; whole of the bead's motivating finding: the first person this
    ;; roster will ever hold in `observed` is a caregiver who has
    ;; already left. Making the owner affirm her as current on the way
    ;; to past would write a lie for one transaction. It carries no
    ;; `:undo` for exactly that reason — an `:undo` must return where it
    ;; departed from, and this one departs from two places.
    :now_past {:from #{:observed :current} :to :past
               :guards [(roster-wall :now_past)]
               :handler stamp-the-answer
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "They were in this house's life and they are not now — a caregiver who left, a contractor whose job is done. No plan may name them from here on, the row and everything it says stays on record, and \"with us again\" brings them back if that changes."}
               :display {:label "That's past now" :order 7
                         :description "Somebody who was in this family's life and is not now — the row stays, the plans stop naming them, and anything the house still has standing in their name is a finding rather than a chore that needs pushing"}}
    ;; THE OTHER ANSWER TO A GUESS, and it is a separate door for the
    ;; household's reason rather than a mechanical one: "she left" and
    ;; "that is not somebody we know" are different sentences, and a
    ;; composer reading this log has to be able to tell a STAFFING
    ;; CHANGE from a bad guess. Both land in `past`, because a person
    ;; this house is not planning with is a person this house is not
    ;; planning with.
    :dismiss {:from #{:observed} :to :past
              :guards [(roster-wall :dismiss)]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "This says the reading was wrong: no plan may name them, and what was written down stays on record with your answer beside it. \"With us again\" brings them back if you change your mind — and they come back as this house's."}
              :display {:label "Not somebody we know" :style :danger :order 8
                        :description "Somebody read this house and put a name in the roster that does not belong to it. Say so — the record keeps both the guess and your answer"}}
    ;; They come back CURRENT rather than observed: a person reaching
    ;; for this door has held them again with his own hand, so it stamps
    ;; like every other landing in `current`.
    :restore {:from #{:past} :to :current
              :guards [(roster-wall :restore)]
              :handler stamp-the-answer
              ;; `:reversible false` because there is no `:undo`
              ;; POINTER, not because the door is one-way in life —
              ;; "that's past now" walks it back, and the sentence says
              ;; so. The pointer is absent for `now_past`'s reason: it
              ;; departs from two states, so the pair is not symmetric.
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "This puts them back in this house's life as the house's own — a plan may name them again, and the date is stamped. If that was wrong, \"that's past now\" is the way back; the log keeps every crossing either way."}
              :display {:label "With us again" :order 1
                        :description "The caregiver came back, the job started again, or the row was marked past by mistake — hold them again, and holding them again is saying so"}}}})
