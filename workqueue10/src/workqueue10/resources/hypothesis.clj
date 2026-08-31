(ns workqueue10.resources.hypothesis
  "The hypothesis (waymark-bug, slice 2 of the hypotheses epic, built
  to docs/spec-hypotheses.md § 'The hypothesis kind'): AN ADVERTISED
  BELIEF, RANKED BELOW ANYTHING DECLARED.

  The house already held the EVIDENCE layer and did not hold the
  BELIEF layer. A finding cites the rows it read; a sitting indexes
  what a person said and a reading reads it back; an outcome is an
  experiment the household taps or declines. What nothing held was a
  graded, decaying, auditable claim about this household — a sentence
  with a number on it that moved because of atoms you can name.

  THE SENTENCE THAT DECIDES EVERY FORK BELOW is the epic's own wall:
  a hypothesis is a PROPOSAL FOR AN EXPERIMENT and never a verdict.
  Nothing routes on it, nothing is auto-composed or auto-hidden
  because a number crossed a line, and a person's declared word
  outranks it always. `ranking_note`'s compression, one kind over: an
  agent's number is a nudge, never a verdict.

  ── THE MACHINE IS `value`'S, WITH ARITHMETIC UNDER IT ───────────────

  `observed → affirmed | dismissed | retired`, `restate` for the
  observer and `revise` for a person, and every door is jfv.10's door
  with its own reason restated. That is fork (j), and it is a
  deliberate refusal to invent a second affirmation grammar for a
  house that already argued this one out:

  - born `observed`, because a birth nobody's hand is on reads as a
    guess, and the summary renders `{state}` so it SAYS SO wherever it
    is cited — `summary/render` has no conditional, so a state is the
    only thing that can speak its own absence of a hand;
  - `restate` is the OBSERVER's (`only-the-observer-restates`): a
    reading learns more and rewords its own guess, and the row is
    still a guess when the handler is done;
  - `still_stands` is A PERSON's — the whole affirmation on an
    observed belief — grantable per waymark-sfe, never the row's own
    observer;
  - `revise` is a person's rewording and CLAIMS the row in one
    stroke, jfv.10's ruling read literally;
  - `dismiss` says *you read me wrong* — a different sentence from
    *this was true and is not now*, which is `retire`, and a composer
    reading the log must be able to tell them apart.

  NOTHING ABOUT THE POSTERIOR CHANGES WITH THE STATE. An affirmed
  hypothesis still folds its atoms; the affirmation says *this house
  agrees the claim is worth holding*, not *stop reading evidence*.
  What the state buys is the crown's tier: a person's yes outranks a
  number, always.

  ── `shape`, NOT `kind` ──────────────────────────────────────────────

  `kind` is the row envelope's own word and a data field wearing it
  would read as the row's type everywhere a card is rendered.
  `spec-threads.md`'s `chat_kind` decided this exact question and the
  reason transfers whole (fork (h)). The five shapes are the
  household's vocabulary rather than the schema's ambition: an
  interest, an intent, a pattern, a relationship, a gap.

  ── `about` IS A VECTOR OF ADDRESSES, NOT TYPED REFS ─────────────────

  The claim is about whatever the record holds — a person row, a value
  row, a thread, a task — and `/api/<plural>/<id>` is the one shape a
  citation may wear in this house (`insight/row-address`). A typed ref
  would force one kind per hypothesis and would refuse the interesting
  claims, which are exactly the ones that span two kinds (fork (i)).

  It is also THE LINK. An atom is a published or taken `insight`
  carrying one of the nine evidence words whose own citations overlap
  this set — or which cites this row's own address directly, the
  direct link a reading writes when it wants an atom on the belief
  rather than on its subject. So a reading may mint a hypothesis about
  the rows a standing pile of findings already cites, and every one of
  them is linked the moment the row exists, with no edit to any
  finding and no new field on `insight`.

  ── THE WORDING DOORS KEEP WHAT THEY ARE NOT TOLD (waymark-ilf) ──────

  A field a wording door was not sent is a field it does not touch.
  That is `waymark10.resource/apply-field-edits`'s law — *write
  exactly the input fields the caller sent, nothing else; an absent
  key is not an erase* — which is what every editor the framework
  GENERATES already does, and this kind arrived spelling the other
  answer by hand.

  It was the wrong one here for a reason no whole-form editor has:
  `about` is not a field, IT IS THE LINK. Nulling it orphans every
  atom at once, and `restate` is the one door that re-folds on the
  spot, so the damage is instant. It happened: a reading restated
  c2b2d2fa with a corrected `claim` alone, the row's only atom was cut
  loose, the posterior fell 0.5522 → 0.3000 — back to the prior — and
  nothing in the answer said an atom had been lost (journal 3d37ddeb,
  2026-08-31). The row was repaired by re-sending `about`.

  Nothing lawful was lost with the wholesale overwrite, because
  CLEARING `about` was never lawful: a claim about nothing is a mood,
  and `a-belief-stays-about-something` now stands on both wording
  doors to say so — `a-belief-cites-what-it-is-about`'s first arm,
  storage-free so that every scenario about a wording door stays in
  the check tier. Omit the field and it keeps; send it empty and the
  door refuses by name. The two rules are one sentence — a belief
  never stops being about something — said at the two places it can be
  broken.

  And a rewording that genuinely NARROWS `about` is a different act
  from a slip: it is allowed, but not quietly.
  `the-facts-behind-it-survive-a-rewording` is an E1 warning (the
  `calendar-clear` shape, one household over): a rewording that takes
  addresses OUT of `about` is answered, before it runs, with how many
  go and how many stay — old → new — beside the number of facts
  standing on the set it is cutting. Acknowledge by name and it
  proceeds. It counts addresses and quotes the row's own
  `atom_count`, so it costs a set difference and no query.

  ── NO DOOR SETS THE POSTERIOR ───────────────────────────────────────

  `posterior`, `posterior_log_odds`, `movement_7d`, `atom_count`,
  `atoms` and `last_moved` are absent from `:create-schema` and from
  every action's `:input`. There is no guard refusing them because
  there is no door to refuse at — the wall is STRUCTURAL, which is
  fork (g), and what it buys is the row's own promise: A HYPOTHESIS IS
  A CACHE OF AN ARITHMETIC ANYONE CAN REDO. Delete every posterior in
  the store and one pass rebuilds them identically.

  The arithmetic is `waymark10.belief` — three rules, forty lines, in
  Clojure rather than in a `:derived {:expr …}` tree because
  `waymark10.expr/ops` has `+ - *`, `min max abs` and no division and
  no logarithm, deliberately (fork (f), vocabulary § 2). The pass that
  runs it is `waymark10.server.belief`, elected and nightly. THE BIRTH
  RUNS IT TOO, over the deployment's own table, so a hypothesis a
  backfill mints tonight carries an honest number tonight rather than
  a prior until the small hours; the pass reweighs it by the
  HOUSEHOLD's table on its next turn, and the difference between the
  two tables is exactly what a household edited on its recipe row.

  ── `prior` IS A GUESS AND THE SCHEMA CANNOT SAY SO ──────────────────

  The spec bounds it 0.02–0.5: a belief nobody has evidence for starts
  unlikely, and one that starts above a coin toss is a conviction
  looking for support. The BOUND is a guard rather than the schema's
  own `{:min 0.02 :max 0.5}`, and the reason is mechanical and worth
  recording: `waymark10.schema`'s `:decimal` derives its generator
  from those bounds with `(long min)`/`(long max)`, so a band entirely
  inside 0 and 1 generates the single value 0 and the conformance
  walker would refuse every row it wrote. The schema holds the honest
  outer bound — a probability — and
  `a-prior-is-a-guess-not-a-conviction` holds the household's, with
  the sentence that names the fix, which is the half a refusal is for
  anyway (`insight`'s `evidence` made this same trade).

  ── `not-a-second-belief` ────────────────────────────────────────────

  `not-a-twin`'s wall, one kind over (waymark-8gc): a candidate whose
  `about` set overlaps a STANDING hypothesis of the SAME SHAPE is
  refused, naming that row's address and the shared address. Two
  beliefs about one thing cannot both move — they split their evidence
  and neither says anything. Restate the standing one.

  ADDRESSES, NEVER SENTENCES. Two claims that mean the same thing in
  different words stay two rows until a reading merges them by
  `restate`; there is no vector index and no similarity threshold
  (§ 'What is deliberately lost', 2). A door that judged whether two
  claims SAY the same thing would be a door guessing, and its refusal
  could not name the offending row.

  STANDING MEANS `observed` OR `affirmed`. A dismissed or retired
  belief blocks nothing: the house answered it, and asking again is
  allowed — which is also the way back from a dismissal, since neither
  terminal state has a door out. `insight`'s own *a dismissed prior
  blocks nothing, it only weighs on the rank*, read one kind over.

  ── THE EXTRACTION-BLIND RULE, AND WHAT IT COSTS THIS FILE ───────────

  The clerk fills the evidence types and NEVER SEES A POSTERIOR: no
  belief on its manifest, no `hypothesis` line in its leash, no
  movement block. The reading reads those; the sitting does not. A
  classifier that can see the belief it is feeding starts confirming
  it, and every likelihood ratio in the table assumes the type was
  assigned by somebody who did not know what it would do.

  Two consequences this kind is shaped by. The reading may not RETYPE
  an atom to move a belief — it may dismiss a wrong finding, publicly,
  with a word on the record, and the dismissed atom leaves the fold.
  And no run scores a hypothesis its own atoms built: four eyes on
  `observed_by` through `g/unless-granted`'s own-field arm, which no
  grant opens.

  ── WHAT IT IS NOT ───────────────────────────────────────────────────

  Not a field on `value` (fork (a)): a belief about a person is not a
  belief about a value, and the five shapes are household vocabulary.
  Not a framework kind, for the same reason. Not `:nav :primary`: a
  belief is permanently open by construction, so a primary hypothesis
  would card in do-now forever and a retired one would be
  congratulated as a deed — `value`'s own paragraph, and it is
  load-bearing rather than cosmetic."
  (:require [clojure.string :as str]
            [waymark10.belief :as belief]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario unless-granted]]
            [waymark10.guards :as g]
            [waymark10.resource :as resource]
            [waymark10.server.feed :as feed]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── addresses ───────────────────────────────────────────────────────

(defn- row-address
  "`/api/<plural>/<id>` → {:plural … :id …}, nil for anything else.
  `insight/row-address`'s body, and it is repeated rather than
  required because this kind must not depend on that one: the two
  sides of the citation join are declarations of equal standing, and a
  require in either direction would make one of them the other's
  library."
  [s]
  (let [parts (str/split (str s) #"/")]
    (when (and (= 4 (count parts))
               (= "" (nth parts 0))
               (= "api" (nth parts 1))
               (not (str/blank? (nth parts 2)))
               (not (str/blank? (nth parts 3)))
               (not (str/includes? (str s) "?")))
      {:plural (nth parts 2) :id (nth parts 3)})))

(defn- addresses
  "The `about` set as this file's walls read it: trimmed, blank-free,
  order preserved."
  [about]
  (into [] (comp (map #(str/trim (str %))) (remove str/blank?)) about))

(defn- about-after
  "What this write LEAVES the belief about — the field the caller
  sent, else the one the row already carries.

  The wording doors keep what they are not told (`rewritten`), so a
  wall reading `inp` alone would judge a field the write is not
  changing: it would refuse a lawful claim-only restate for omitting
  `about`, which is the opposite mistake from the one waymark-ilf was.
  A wall judges the ROW THIS WRITE WOULD LEAVE, and at create there is
  no row, so this is just the body."
  [row inp]
  (if (contains? inp :about)
    (:about inp)
    (get-in row [:data :about])))

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn a-belief-cites-what-it-is-about
  {:judges [:about]
   :reads [:storage]
   :vars [:count :offenders]
   :open "A belief names what it is about: at least one address, each of them /api/<collection>/<id> naming a collection this house serves — the person, the value, the conversation the claim is about. The half of this a wording door can break — leaving the set EMPTY — is `a-belief-stays-about-something`, which stands on `restate` and `revise`."
   :explain "A claim about nothing is a mood, not a hypothesis — name the rows it is about, as addresses like /api/people/01H… ({count} given{offenders}). A claim about this household as a whole has no address set this house can hold, and it is filed rather than smuggled."}
  [row inp ctx]
  ;; `cites-what-it-claims`'s body, one kind over, and the same
  ;; storage-free posture: the render probe carries no registry, so it
  ;; advertises optimistically and the write path always consults.
  ;;
  ;; It reads `about-after` rather than the body so that it says the
  ;; same thing its wording-door sibling says (waymark-ilf): a wall
  ;; judges the ROW THIS WRITE WOULD LEAVE. At create there is no row
  ;; and that is exactly the body, so nothing here changed.
  (let [ab (addresses (about-after row inp))
        rdef-of (:rdef-of ctx)]
    (cond
      (nil? rdef-of) (t/allow)

      (empty? ab) (t/deny {:vars {:count 0 :offenders ""}})

      :else
      (let [bad (into []
                      (remove (fn [href]
                                (when-some [{:keys [plural]} (row-address href)]
                                  (some? (rdef-of plural)))))
                      ab)]
        (if (seq bad)
          (t/deny {:vars {:count (count ab)
                          :offenders (str "; this house has nothing at "
                                          (g/listed bad))}})
          (t/allow))))))

(defguardfn a-belief-stays-about-something
  {:judges [:about]
   :vars [:standing]
   :open "A belief is about something at every door, not only at birth. Rewording it may change WHICH rows it is about; it may not leave the set empty — and it does not have to say anything at all about the set, because a field a wording door was not sent is a field it does not touch."
   :explain "This would leave the belief about nothing, and a claim about nothing is a mood rather than a hypothesis. The addresses are also THE LINK — every fact behind this belief arrives through them, and it stands on {standing} of them right now — so emptying the set would orphan all of them at once, in the same breath as the refold. Say the addresses this claim is about, or leave `about` out of the body entirely and the row keeps what it has."}
  [row inp _ctx]
  ;; `a-belief-cites-what-it-is-about`'s first arm, standing on the
  ;; WORDING doors (waymark-ilf) — and storage-free on purpose, so
  ;; every scenario about a wording door stays in the check tier
  ;; where its verdict costs nothing. The registry half of that wall
  ;; (does this house serve the collection you named?) stays at
  ;; create, where it can be paid for once.
  (if (empty? (addresses (about-after row inp)))
    (t/deny {:vars {:standing (count (addresses
                                      (get-in row [:data :about])))}})
    (t/allow)))

(defguardfn the-facts-behind-it-survive-a-rewording
  {:severity :warning
   :judges [:about]
   :vars [:before :after :dropped :atoms]
   :open "A rewording may change what a belief is about — but the address set IS the link every fact behind it arrives through, and the fold runs the moment the door closes. Narrowing the set is allowed; doing it without being told what goes with it is not."
   :explain "This rewording takes {dropped} of the {before} address(es) this belief is about out of the set, leaving {after} — and the set is THE LINK: the {atoms} fact(s) behind this belief all arrive through it. The refold runs the moment this door closes, so every atom that reached the claim only through a dropped address leaves the fold with it and the number falls back toward the prior it started at. Acknowledge this if that is what you mean. If you only meant to change the WORDS, leave `about` out of the body — an omitted field keeps what the row already holds."}
  [row inp _ctx]
  ;; THE ANSWER SAYS SO (waymark-ilf). `calendar-clear`'s shape, one
  ;; household over: the effect a write is about to have, in the
  ;; numbers the caller controls, BEFORE it has it — E1, so a reading
  ;; that means it acknowledges by name and proceeds, and one that
  ;; slipped reads a sentence instead of a posterior that fell.
  ;;
  ;; It counts ADDRESSES and quotes the standing atom count rather
  ;; than predicting the new one, which is the honest half: how many
  ;; atoms survive is a question about every finding in the house, and
  ;; a wall that read them all to answer it would drag every scenario
  ;; on this door into the conformance tier for a number it can only
  ;; say after the fold anyway. What the caller is doing to the LINK
  ;; is knowable from the row and the body, and it is the thing the
  ;; caller can actually take back.
  (let [standing (set (addresses (get-in row [:data :about])))
        proposed (set (addresses (about-after row inp)))
        dropped (into #{} (remove proposed) standing)]
    (if (empty? dropped)
      (t/allow)
      (t/deny {:vars {:before (count standing)
                      :after (count proposed)
                      :dropped (count dropped)
                      :atoms (or (get-in row [:data :atom_count]) 0)}}))))

(def ^:private prior-floor 0.02M)
(def ^:private prior-ceiling 0.5M)

(defguardfn a-prior-is-a-guess-not-a-conviction
  {:judges [:prior]
   :vars [:given]
   :open "Where the belief STARTS, before any evidence: between 0.02 and 0.5. A hypothesis is a thing to test, so it begins unlikely — 0.05 for a hunch, 0.2 for something the record already leans toward."
   :explain "A prior of {given} is not where a hypothesis starts. Below 0.02 the claim is one no evidence in this house could ever lift; at or above 0.5 it is already a conviction, and a conviction dressed as a hypothesis is the one thing this kind exists not to be. Say where you HONESTLY started — 0.05 for a hunch, 0.2 for something the record already leans toward — and let the atoms do the rest."}
  [_row inp _ctx]
  ;; SHAPE ONLY, so it is judged in the same breath as
  ;; `make check-queue` and its scenario needs no database. See the ns
  ;; docstring for why the band is here rather than on the schema: the
  ;; `:decimal` generator reads its own bounds, and a band inside 0..1
  ;; would make the conformance walker write rows it then refuses.
  (let [p (:prior inp)]
    (if (and (decimal? p)
             (or (neg? (.compareTo ^java.math.BigDecimal p prior-floor))
                 (>= (.compareTo ^java.math.BigDecimal p prior-ceiling) 0)))
      (t/deny {:vars {:given (str p)}})
      (t/allow))))

(def ^:private standing-states
  "The two states a belief STANDS in. A dismissed or retired
  hypothesis has been answered and blocks nothing."
  #{"observed" "affirmed" :observed :affirmed})

(def ^:private standing-page
  "How deep `not-a-second-belief` reads. `outcome/standing-page`'s
  number and its reasoning: a bounded read that missed the far tail
  lets a duplicate THROUGH rather than refusing a belief over a row it
  could not see, which is the right way for a wall reading a window to
  be wrong."
  200)

(defguardfn not-a-second-belief
  {:judges [:about :shape]
   :reads [:hypothesis]
   :vars [:standing :claim :shape :shared]
   :open "One belief of one shape about one thing. Two hypotheses about the same row cannot both move — they split the same atoms between them and neither ends up saying anything — so a candidate whose subject overlaps a standing belief of the same shape is refused by name, and the standing one is the row to reword."
   :explain "This house already holds that belief: /api/hypotheses/{standing} — “{claim}” — is a standing {shape} hypothesis about {shared}. Two beliefs about one thing split their evidence and neither moves, so reword that one (restate) rather than starting a second. If the house answered it — dismissed or retired — a fresh one is admitted."}
  [_row inp ctx]
  ;; `not-a-twin`'s shape (waymark-8gc) and `one-live-finding-per-offer`'s
  ;; posture: exact address overlap, nothing cleverer, and the storage-free
  ;; probe advertises optimistically because the write path always consults.
  (let [find' (:find ctx)]
    (if (nil? find')
      (t/allow)
      (let [shape (some-> (:shape inp) str str/trim not-empty)
            mine (set (addresses (:about inp)))]
        (if (or (nil? shape) (empty? mine))
          ;; the two walls above own those refusals
          (t/allow)
          (let [shared-with (fn [r]
                              (into [] (filter mine)
                                    (addresses (get-in r [:data :about]))))
                hit (->> (find' :hypothesis {:shape shape}
                                {:limit standing-page})
                         (into []
                               (comp (filter #(contains? standing-states
                                                         (:state %)))
                                     (map #(assoc % ::shared (shared-with %)))
                                     (filter (comp seq ::shared))))
                         (sort-by #(str (:id %)))
                         first)]
            (if (nil? hit)
              (t/allow)
              (t/deny {:vars {:standing (str (:id hit))
                              :claim (str (get-in hit [:data :claim]))
                              :shape shape
                              :shared (g/listed (::shared hit))}}))))))))

;; ── the answer is a person's, and it is grantable ───────────────────
;;
;; waymark-sfe, the owner's ruling of 2026-08-28 — "The whole reason we
;; have the access controls we have is so that I can ask you to do what
;; I want when I want. It doesn't make sense to disallow it, it just
;; makes sense to permission it" — applied to this kind at birth rather
;; than retrofitted onto it. `g/unless-granted` says three things:
;;
;;   a person passes, as ever;
;;   the agent that OBSERVED this row is refused, grant or no grant —
;;   `:own-field :observed_by` is four eyes, and the whole point of
;;   the extraction-blind split is that the run that read the evidence
;;   is not the run that says what it means;
;;   any other agent passes only under a grant admitting
;;   `hypothesis.<this door>` — the owner delegating in his own words.
;;
;; Minted per action because the refusal NAMES the door a scope would
;; have to admit, and a sentence saying "some door" would send a
;; refused reading to read source.

(defn- the-answer-is-a-persons
  "The wall on every door that ANSWERS a belief — `still_stands`,
  `revise`, `dismiss`, `retire`. What it guards is the answer rather
  than the writing, which is why the create door and `restate` carry
  it not at all: writing down what you noticed is the reading's work,
  and saying what it MEANS about this household is not."
  [action]
  (unless-granted
   :hypothesis action
   {:name :the-answer-is-a-persons
    :own-field :observed_by
    :explain "Answering a belief about this house is a person's word. You may write down what you think you see — that row is born observed and says so wherever it is cited — but marking your own reading affirmed, or dismissed, would be answering your own question in the owner's voice. Leave it observed and say what you found: publish a finding, cite the rows you read, and offer this row's own \"yes — that's true of us\" as the one next step. He answers with a tap, or rewords it himself, and either way the belief becomes the house's."}))

(defguardfn only-the-observer-restates
  {:reads [:principal]
   :explain "This door is the observer's own — it corrects a guess while it is still a guess, and leaves it a guess. Rewording a belief is a larger thing when a person does it: the claim becomes one this house holds, and the row becomes yours in the same stroke. That door is \"Reword\"."}
  [_row _inp ctx]
  ;; jfv.10's mirror wall, whole. It exists for the household rather
  ;; than for safety: a person who reached `restate` would be editing a
  ;; belief WITHOUT claiming it, which is not a thing anybody here
  ;; wants to do — and because both wording walls are pure functions of
  ;; the principal, each hand is offered exactly one wording door
  ;; instead of two that look alike. :system passes, for the reason it
  ;; passes everywhere: the engine's own actor is not the subject.
  (if (contains? #{:human :person} (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; ── the stamps, and the birth's own fold ────────────────────────────

(def ^:private authored-fields
  "The surface both wording doors may write — the same fields they
  take, so what is stored is exactly what was judged. A field the
  caller did not send is not one of them: see `rewritten`.
  `shape` stays OUT: a claim that changed shape is a different claim,
  and `not-a-second-belief` is keyed on the pair. `prior` stays out
  too, and that one matters: where a belief STARTED is a fact about
  the past, and a hand that could edit it could move any posterior
  anywhere without touching a single atom."
  [:claim :about])

(def ^:private atom-birth-cap
  "How many findings the birth reads to fold its own first posterior.
  Bounded because this runs inside the create transaction; generous
  because a house whose whole record is smaller than this pays
  nothing, and one that is larger has an archive whose oldest atoms
  are decayed past mattering."
  2000)

(defn- fold-now
  "The belief's own arithmetic, run over whatever the house already
  holds. Weighed by the DEPLOYMENT's table (`feed/default-evidence-lr`)
  rather than the household's, and the difference is recorded rather
  than hidden: a create ctx holds the write's transaction and no
  engine, so the recipe row is not reachable from here. The nightly
  pass reweighs by the household's own numbers, which is where an
  edited table takes effect — within a night, on every row at once,
  which is how a table of numbers is supposed to move a house."
  [row ctx]
  (let [find' (:find ctx)
        atoms (if find'
                (belief/atoms-of (find' :insight {} {:limit atom-birth-cap}))
                [])
        now (or (:now ctx) (java.time.Instant/now))]
    (update row :data merge
            (belief/cached
             (belief/fold-one feed/default-evidence-lr row atoms
                              (.toEpochMilli ^java.time.Instant now))))))

(defn- born
  "Whose reading this is, and what the record already says about it.

  `observed_by` is the ENGINE's stamp from the principal and never a
  field a body may name — `insight`'s `authored_by` and `value`'s
  `written_by`, one kind over — because it is the four-eyes field, and
  a caller who could write it could hand the answer to itself."
  [row ctx]
  (-> row
      (assoc-in [:data :observed_by] (get-in ctx [:principal :id]))
      (fold-now ctx)))

(defn- affirmed
  "\"This is one of ours.\" Who and when, from the engine's own :now and
  principal — never from a body and never from a clock this file
  reads. Stamped at every door a person's hand lands `affirmed`
  through, so a reader of rows learns what a reader of transitions
  would."
  [row ctx]
  (-> row
      (assoc-in [:data :affirmed_at] (:now ctx))
      (assoc-in [:data :affirmed_by] (get-in ctx [:principal :id]))))

(defn- rewritten
  "The authored surface, written FIELD BY FIELD — and a field the
  caller did not send KEEPS its standing value. Both wording doors
  write through here; what separates them is whether the landing is an
  answer.

  `waymark10.resource/apply-field-edits` IS the law, borrowed rather
  than restated: *write exactly the input fields the caller sent,
  nothing else — an absent key is not an erase.* It is what every
  editor the framework generates does, and this file shipped hand-
  spelling the opposite (an omitted optional CLEARED, `saved_view`'s
  posture). That is fine for a whole-form editor whose fields are
  words; it was wrong here because `about` is THE LINK, and clearing
  it cut every atom loose while `restate`'s own refold ran in the same
  breath — see the ns docstring, waymark-ilf."
  [row inp]
  (resource/apply-field-edits row inp authored-fields))

(defhandler apply-revision [row inp ctx]
  ;; the person's wording door. `:record true` carries what was
  ;; written, which is what makes the log the amendment history — and
  ;; the stamp is here because this door lands in `affirmed`:
  ;; rewording an observed belief CLAIMS it. The fold runs again
  ;; because `about` may have moved, and `about` IS the link.
  (-> row (rewritten inp) (affirmed ctx) (fold-now ctx)))

(defhandler apply-restatement [row inp ctx]
  ;; the observer's wording door. The same overwrite, the same refold,
  ;; and NO stamp: the row is still a guess when this handler is done
  ;; with it, which is the whole difference between the two doors.
  (-> row (rewritten inp) (fold-now ctx)))

(defhandler stamp-the-answer [row _inp ctx]
  ;; the tap. Nothing about the claim changes; what changes is whose
  ;; it is. The posterior does not move either — an affirmed belief
  ;; goes on reading its evidence.
  (affirmed row ctx))

;; ── the household's own words ───────────────────────────────────────
;; Spelled once and worn by both doors — the row schema and the
;; narrower create form — because three copies of one sentence is
;; three places for it to drift (`value`'s own `prose`, one kind over).

(def ^:private prose
  {:claim {:x-display
           {:label "The claim"
            :help "One sentence, in the words this household would use, and shaped so a person could agree or disagree with it — \"Jack wants to build things with his hands, not watch them being built\", \"Saturdays are the currency of this family, not evenings\". Not a plan and not a task: a claim about what is TRUE of the people here, which the record can then argue with."}}
   :shape {:x-display
           {:label "What kind of claim"
            :choices
            {"interest" "An interest — somebody cares about this thing more than the record has noticed"
             "intent" "An intent — somebody means to do this, or wants to, whether or not it has happened yet"
             "pattern" "A pattern — this is what actually happens here, week after week, whatever anybody says about it"
             "relationship" "A relationship — who matters to whom, and how"
             "gap" "A gap — what this house says and what it does are not the same thing, and the distance is the claim"}}}
   :about {:x-display
           {:label "What it is about"
            :help "The rows this claim is about, as addresses — /api/people/01H…, /api/values/01H…, /api/threads/01H… — one per row. At least one, always: a claim about nothing is a mood. It is also the LINK: any finding that cites one of these rows and carries one of the nine evidence words feeds this belief, with nothing further to fill in."}}
   :prior {:x-display
           {:label "Where it starts"
            :help "How likely this seemed BEFORE any evidence, as a number between 0.02 and 0.5 — 0.05 for a hunch, 0.2 for something the record already leans toward. It begins unlikely on purpose: a hypothesis is a thing to test, and one that starts above a coin toss is a conviction looking for support. It is written once, at birth, and no door changes it."}}
   :posterior {:x-display
               {:label "What the record now says"
                :help "How likely this claim looks after every atom that feeds it — computed, never typed. No door sets this and nobody can: it is a cache of an arithmetic anybody can redo from the atoms listed below, and it is clamped short of certainty on purpose, because a belief that reaches certainty stops reading evidence."}}
   :posterior_log_odds {:x-display
                        {:raw true
                         :label "…in log-odds"
                         :help "The same number in the units the arithmetic actually adds in: the starting point plus one term per occasion. Zero is a coin toss; negative is evidence against. Written by the engine."}}
   :movement_7d {:x-display
                 {:label "Moved this week"
                  :help "How far this belief moved in seven days, in log-odds — today's fold less the same fold with the clock set back a week. It moves when a new fact lands AND when an old one fades, and both are real news."}}
   :atom_count {:x-display
                {:label "Facts behind it"
                 :help "How many typed findings feed this belief right now. A dismissed finding is not one of them."}}
   :atoms {:x-display
           {:raw true
            :label "The atoms"
            :help "Every typed finding this belief is folded from, newest first, with what the table priced it at and when it was said. Written by the engine at every fold — this is the working, kept so the number is arguable rather than merely reported."}}
   :last_moved {:x-display
                {:label "Newest fact"
                 :help "When the most recent fact behind this belief was SAID — the occasion's own day where the finding named one. Empty means nothing has fed it yet."}}
   :observed_by {:x-display
                 {:raw true
                  :label "Noticed by"
                  :help "Whose reading this is. Stamped by the engine, never by a form — and whoever it names can never answer this row, however wide their grant."}}
   :affirmed_at {:x-display
                 {:label "Last affirmed"
                  :help "When a person last put their name to this claim. Written by the engine at the door. Empty means nobody has answered it yet."}}
   :affirmed_by {:x-display
                 {:raw true
                  :label "Affirmed by"
                  :help "Who said this is true of us. Written by the engine at the door."}}})

(defn- entry
  "One [:key props schema] entry of a form: the shared prose plus
  whatever this surface adds of its own."
  [k extra form]
  [k (merge (get prose k) extra) form])

(def ^:private claim-schema [:string {:min 1 :max 240}])
(def ^:private about-schema
  [:maybe [:vector {:max 12} [:string {:min 1 :max 200}]]])

(def revise-input
  "What `revise` and `restate` take: the claim and what it is about.
  `about` is OPTIONAL and omitting it keeps the standing set — a
  wording door writes the fields it was sent and no others
  (`rewritten`). `shape` and `prior` are not here at all — see
  `authored-fields`."
  [:map
   (entry :claim {} claim-schema)
   (entry :about {:optional true} about-schema)])

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Two tiers, read off the declarations rather than chosen. The doors
;; whose walls are pure functions of the principal (and the presented
;; grant) are CHECK TIER — judged with no database in the same breath
;; as the usability warnings. The create door's two world-reading
;; walls DEFER to the conformance tier, `insight`'s own paragraph:
;; both consult the registry or another row, which the check tier's
;; offline world cannot answer, so they are proved through the real
;; HTTP door — which is the stronger proof anyway, because it is what
;; a client sees.
;;
;; AND NONE NAMES `not-a-second-belief`, for the structural reason
;; `outcome/not-a-twin` and `insight/one-live-finding-per-offer` have
;; none either: the wall's whole question is what ANOTHER row already
;; holds, and a scenario holds one literal `:input` over an empty
;; store — so every scenario reaching that door would be an allow, and
;; a green one would prove nothing. Its claims are proved from the
;; wire, by `:feed/hypotheses` in the conformance pack.

(def ^:private a-noticed-belief
  {:claim "Jack wants to build things with his hands, not watch them being built"
   :shape "interest"
   :about ["/api/people/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
   :prior 0.1M
   :observed_by "reader"})

(defscenario a-reading-notices-something
  "The kind's whole reason, at the door it happens through. A reading
   that has read this house may write down what it thinks it sees —
   there is no wall here against an agent, which is jfv.10's ruling
   inherited whole — and the row it writes is born `observed`,
   carrying the reading's own id and saying \"observed, not yet
   affirmed\" on its summary line. Nothing about what this house
   BELIEVES has moved until a person taps."
  {:kind    :hypothesis
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "reader" :type :agent}
   :input   {:claim "Jack wants to build things with his hands, not watch them being built"
             :shape "interest"
             :about ["/api/people/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :prior 0.1M}
   :expect  {:allowed true}})

(defscenario a-belief-is-about-something
  "A claim about nothing is a mood. The `about` set is what the
   record can follow and it is also the LINK every atom arrives
   through, so a belief with an empty one would be a sentence nothing
   could ever feed — and the refusal names the shape an address takes."
  {:kind    :hypothesis
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "reader" :type :agent}
   :input   {:claim "This family is happier in the winter"
             :shape "pattern"
             :about []
             :prior 0.1M}
   :expect  {:refused :a-belief-cites-what-it-is-about}})

(defscenario a-prior-is-where-a-guess-starts
  "A hypothesis begins unlikely, because it is a thing to TEST. A
   prior at or above a coin toss is a conviction looking for support,
   and the whole design exists to keep those out — so the door says
   so, in the household's own numbers, rather than letting the
   arithmetic quietly start halfway home."
  {:kind    :hypothesis
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "reader" :type :agent}
   :input   {:claim "Jack wants to build things with his hands"
             :shape "interest"
             :about ["/api/people/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :prior 0.9M}
   :expect  {:refused :a-prior-is-a-guess-not-a-conviction
             :because "already a conviction"}})

(defscenario an-observer-does-not-answer-its-own-reading
  "THE FOUR-EYES WALL, and no grant opens it. The run that read the
   evidence is not the run that says what it means — that is the
   extraction-blind rule made structural rather than promised, and it
   is the reason every likelihood ratio in the table means anything at
   all."
  {:kind    :hypothesis
   :attempt :still_stands
   :row     {:state :observed :data a-noticed-belief}
   :as      {:id "reader" :type :agent}
   :expect  {:refused :the-answer-is-a-persons
             :because "names you as its observed by"}})

(defscenario an-ungranted-agent-does-not-answer
  "A second reading is not a second pair of eyes either — not without
   a grant. The wall is `g/unless-granted`, so the owner may hand a
   delegate his own yes through the approval_request door and read
   afterwards which grant it acted under; what he may not do is have
   it happen without him. (A scenario presents no grant, so the check
   tier proves exactly this half; the granted half is proved over the
   wire.)"
  {:kind    :hypothesis
   :attempt :still_stands
   :row     {:state :observed :data a-noticed-belief}
   :as      {:id "second-reader" :type :agent}
   :expect  {:refused :the-answer-is-a-persons
             :because "hypothesis.still_stands"}})

(defscenario a-person-answers-a-belief-with-one-tap
  "The affirmation, and it asks for nothing — structurally, so that a
   finding may OFFER it: an insight's offer must be no heavier than
   `selection`, and a door taking so much as one string renders
   `recall`. So the way a reading asks the house about a belief is the
   petition path `value` already owns: a finding, the rows it read,
   and this tap as the one next step."
  {:kind    :hypothesis
   :attempt :still_stands
   :row     {:state :observed :data a-noticed-belief}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-reading-corrects-what-it-observed
  "And the door that IS the reading's: it learned more, so it rewrites
   what it wrote down. The row stays `observed` — the record of what
   was noticed never promotes itself — and the fold runs again,
   because `about` is the link and rewording what a belief is about
   changes which facts feed it."
  {:kind    :hypothesis
   :attempt :restate
   :row     {:state :observed :data a-noticed-belief}
   :input   {:claim "Jack wants to build things with his hands, and says so most about the shop"
             :about ["/api/people/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
                     "/api/values/01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]}
   :as      {:id "reader" :type :agent}
   :expect  {:allowed true}})

(defscenario a-rewording-keeps-what-it-was-not-told
  "THE LIVE INCIDENT, AS AN OBLIGATION (waymark-ilf, journal
   3d37ddeb). A reading that learned better words for a claim sends
   the claim and nothing else — and the belief goes on being about the
   rows it was about, because a wording door writes the fields it was
   sent and no others. The wall that keeps a belief about something
   stands on this door too and it judges the row this write would
   LEAVE, so an omitted `about` is the standing one rather than an
   empty one: the write is admitted, nothing warns, and every atom
   arrives through the same addresses afterwards."
  {:kind    :hypothesis
   :attempt :restate
   :row     {:state :observed :data a-noticed-belief}
   :input   {:claim "Jack wants to build things with his hands — the shop, not the screen"}
   :as      {:id "reader" :type :agent}
   :expect  {:allowed true}})

(defscenario a-belief-does-not-stop-being-about-something
  "And the other half of the same sentence, which is why keeping is
   not a loss: a wording door may not empty the set either. A belief
   about nothing is a mood at birth and it is still a mood on a
   Tuesday — and because `about` IS the link, a door that admitted an
   empty one would orphan every fact behind the claim in a single
   stroke and refold to the prior before anybody read the answer."
  {:kind    :hypothesis
   :attempt :restate
   :row     {:state :observed :data a-noticed-belief}
   :input   {:claim "Jack wants to build things with his hands"
             :about []}
   :as      {:id "reader" :type :agent}
   :expect  {:refused :a-belief-stays-about-something
             :because "a claim about nothing is a mood"}})

(defscenario a-person-rewords-rather-than-restates
  "The mirror wall, and it is for the household rather than for
   safety. A person who reached the observer's door would be editing a
   belief WITHOUT claiming it, which is not a thing anybody here wants
   to do — and because both wording walls are pure functions of the
   principal, each hand is offered exactly one wording door instead of
   two that look alike."
  {:kind    :hypothesis
   :attempt :restate
   :row     {:state :observed :data a-noticed-belief}
   :input   {:claim "Reworded by the owner, but through the observer's door"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :only-the-observer-restates
             :because "That door is \"Reword\""}})

(defscenario a-dismissed-belief-is-over
  "\"You read me wrong\" is an answer and it is kept. A dismissed
   belief has no door out — asking again is a NEW hypothesis, which
   `not-a-second-belief` admits precisely because this one no longer
   stands. The record keeps both the guess and the answer, and the
   posterior under it goes on being honest so a reading can see what
   the house said no to."
  {:kind    :hypothesis
   :attempt :still_stands
   :row     {:state :dismissed :data a-noticed-belief}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Observed"}})

;; ── :hypothesis — a belief, with its working shown ──────────────────

(defresource hypothesis
  {:kind :hypothesis
   :plural "hypotheses"
   ;; see the ns docstring: a :primary belief would card in do-now
   ;; forever and a retired one would be celebrated as a deed
   :nav :secondary
   ;; THE AFFIRMATION AXIS, `value`'s column exactly (jfv.10, fork
   ;; (j)) — and it is a state rather than a stamp for jfv.10's own
   ;; reason: `summary/render` has no conditional, so a missing stamp
   ;; renders an em-dash and can never speak its own absence, while
   ;; `{state}` speaks on every envelope, every list line and every
   ;; transition record.
   :states [:observed :affirmed :dismissed :retired]
   :initial :observed
   ;; BOTH ANSWERS ARE TERMINAL, and that is the difference from
   ;; `value`, which has a `restore`. There is no way back from
   ;; either, because there does not need to be one: a belief the
   ;; house answered is answered, and asking again is asking again —
   ;; a NEW hypothesis, which `not-a-second-belief` admits precisely
   ;; because the old one no longer stands. A `restore` would be the
   ;; house un-answering itself, which is not a thing the record
   ;; should be able to say.
   :terminal #{:dismissed :retired}
   :summary "{data.claim} · {data.shape} · {state}"
   :label-template "{data.claim}"
   :display {:title "{data.claim}"}
   :deviations
   ["prior is a NUMBER a guard bounds rather than a vocabulary the schema publishes, so the effort-honesty check warns and the guard's :open acknowledges it. The band 0.02–0.5 cannot be schema properties: waymark10.schema's :decimal derives its generator from its own :min/:max with (long …), so a band entirely inside 0 and 1 generates the single value 0 and the conformance walker would refuse every row it wrote. The schema holds the honest outer bound and the door holds the household's, with the sentence that names the fix."
    "about is judged at the wording doors by a-belief-stays-about-something, which is storage-free on purpose (waymark-ilf: a wall that read rows would drag every scenario about restate and revise into the conformance tier, where a row staged by the walker cannot be restated by the reading that a scenario names) — so it does not get the effort-honesty exemption the create door's own citation wall gets from :reads [:storage], and two warnings ride on that. There is no vocabulary to publish: about is a vector of addresses to ANY row this house serves, so the legal answers are the whole record and a picker over them is the collection GET a client already has."
    "posterior, posterior_log_odds, movement_7d, atom_count, atoms and last_moved have no door and no guard — they are engine-written by waymark10.belief and absent from :create-schema and every :input, which is a structural wall rather than a judged one (docs/spec-hypotheses.md fork (g))."]
   :schema
   [:map
    (entry :claim {:sort true} claim-schema)
    (entry :shape {:filter #{:eq}}
           [:enum "interest" "intent" "pattern" "relationship" "gap"])
    ;; WHAT IT IS ABOUT, and the guard is what makes it mandatory
    ;; rather than the schema: a [:min 1] here would 422 before
    ;; anybody said WHY, and the sentence that names the fix is the
    ;; half a refusal is for (`insight`'s `evidence`, one kind over).
    (entry :about {:optional true} about-schema)
    ;; WHERE THE BELIEF STARTED. Bounded 0..1 here and 0.02..0.5 at
    ;; the door — see the ns docstring for why the band cannot be a
    ;; schema property.
    (entry :prior {} [:decimal {:min 0 :max 1}])
    ;; ── DERIVED, EVERY ONE OF THEM (fork (g)) ─────────────────────
    ;; Absent from :create-schema and from every :input below, so the
    ;; wall is structural: there is no door to refuse at. The engine
    ;; writes them — `waymark10.belief` computes, `born` folds at
    ;; birth, `waymark10.server.belief` refolds nightly — and every
    ;; one of them is reproducible from the atoms alone.
    (entry :posterior {:optional true :sort true}
           [:maybe [:decimal {:min 0 :max 1}]])
    (entry :posterior_log_odds {:optional true}
           [:maybe [:decimal {:min -20 :max 20}]])
    (entry :movement_7d {:optional true :sort true}
           [:maybe [:decimal {:min -20 :max 20}]])
    (entry :atom_count {:optional true} [:maybe [:int {:min 0}]])
    (entry :atoms {:optional true}
           [:maybe [:vector [:map-of :keyword :any]]])
    (entry :last_moved {:optional true :sort true}
           [:maybe :waymark/instant])
    ;; WHOSE READING, and it is the four-eyes field
    (entry :observed_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    (entry :affirmed_at {:optional true :sort true}
           [:maybe :waymark/instant])
    (entry :affirmed_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])]
   ;; the client states the claim, its shape, what it is about and
   ;; where it started. EVERY OTHER FIELD IS THE ENGINE'S.
   :create-schema
   [:map
    (entry :claim {} claim-schema)
    (entry :shape {}
           [:enum "interest" "intent" "pattern" "relationship" "gap"])
    (entry :about {:optional true} about-schema)
    (entry :prior {} [:decimal {:min 0 :max 1}])]
   ;; `shape`, `observed_by` and `affirmed_by` carry their own :filter
   ;; on the schema entries above — one concern, one home — so only the
   ;; machine's own column is spelled here. `state` is how a reading
   ;; asks for the beliefs that have been noticed and not yet answered.
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; THE COURTESY, AND IT IS WHAT MAKES A READING'S WORK POSSIBLE AT
   ;; ALL. Since waymark-rci an agent presenting no grant is scoped to
   ;; nothing, so the doors it may KNOCK ON are exactly its own
   ;; surface's — and `:actions` is that list. `create` and `restate`,
   ;; the two doors that are a reading's own: writing down what it
   ;; noticed, and correcting it while it is still a guess. The
   ;; `:decision` sugar spells the same pair one kind over
   ;; (`{:actions (into #{"create"} verdict-names)}`), and this kind is
   ;; hand-written, so it says it by hand.
   ;;
   ;; ANSWERING IS NOT ON IT, deliberately: `still_stands`, `revise`,
   ;; `dismiss` and `retire` are behind `the-answer-is-a-persons`, and
   ;; the courtesy only decides which doors are visible enough to knock
   ;; on — the guards judge every invoke regardless. Leaving them off
   ;; means an ungranted agent meets a mute 404 rather than a narrated
   ;; refusal at a door it was never going to pass.
   :own-surface {:by :observed_by :actions #{:create :restate}}
   ;; SHAPE FIRST, WORLD NEXT — `insight`'s ordering and its reason. A
   ;; malformed belief hears what is wrong with it before it hears
   ;; anything about the house it is landing in, and only the last
   ;; wall reads another row at all.
   :create-guards [a-belief-cites-what-it-is-about
                   a-prior-is-a-guess-not-a-conviction
                   not-a-second-belief]
   :on-create born
   :scenarios [a-reading-notices-something
               a-belief-is-about-something
               a-prior-is-where-a-guess-starts
               an-observer-does-not-answer-its-own-reading
               an-ungranted-agent-does-not-answer
               a-person-answers-a-belief-with-one-tap
               a-reading-corrects-what-it-observed
               a-rewording-keeps-what-it-was-not-told
               a-belief-does-not-stop-being-about-something
               a-person-rewords-rather-than-restates
               a-dismissed-belief-is-over]
   :actions
   ;; THE WORDING DOOR SPLITS BY HAND, and jfv.10 already paid for
   ;; this: `:to` is a static keyword, so one door cannot land in two
   ;; states, and a shared door would have had a reading's own
   ;; rewording land in `affirmed` — the observer answering its own
   ;; guess, which is the one thing this kind exists to forbid.
   {:revise {:from #{:observed :affirmed} :to :affirmed
             :input revise-input
             :edit {:prefill [:claim :about]}
             :record true
             :waives #{:large-effort}
             ;; WHOSE HAND FIRST, THEN WHAT THE WRITE LEAVES BEHIND.
             ;; The citation wall stands here as well as at birth
             ;; (waymark-ilf): a belief never stops being about
             ;; something, and a wording door is the only other place
             ;; that sentence can be broken.
             :guards [(the-answer-is-a-persons :revise)
                      a-belief-stays-about-something
                      the-facts-behind-it-survive-a-rewording]
             :handler apply-revision
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Rewording puts the claim in your words and makes it one this house holds; the log keeps what it used to say, who changed it and when. Whatever you leave out of the form stays as it is. The atoms behind it do not move — but if you change what it is ABOUT, you change which facts feed it, and the number under it is recomputed on the spot."}
             :display {:label "Reword" :order 1
                       :description "Say the claim differently, or change which rows it is about — and if this one was only observed, saying it in your own words is what makes it the house's"}}
    ;; THE OBSERVER'S OWN WORDING DOOR. The same overwrite, the same
    ;; refold, a self-loop, and no stamp: a reading that learns more
    ;; rewrites what it wrote down and the row is still a guess
    ;; afterwards.
    :restate {:from #{:observed} :to :observed
              :input revise-input
              :edit {:prefill [:claim :about]}
              :record true
              :waives #{:large-effort}
              :guards [only-the-observer-restates
                       a-belief-stays-about-something
                       the-facts-behind-it-survive-a-rewording]
              :handler apply-restatement
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "This overwrites what was noticed with what you now think you see; the log keeps the earlier reading. What you leave out of the body is left alone — send the claim by itself and the belief goes on being about the same rows, which is where every fact behind it arrives from. The belief stays observed either way — nothing here makes it the house's — and the number is refolded over whatever it is now about."}
              :display {:label "Correct what was noticed" :order 3
                        :description "New evidence changed the reading — rewrite it. It stays observed: only a person's hand makes it something this house holds"}}
    ;; THE AFFIRMATION, AND THE PETITION'S OWN DOOR. No :input,
    ;; deliberately and structurally: an insight's offer must be no
    ;; heavier than `selection`, and a door taking so much as one
    ;; string renders `recall`. Asking for nothing is what makes this
    ;; action a legal offer and a legal card chip.
    :still_stands {:from #{:observed} :to :affirmed
                   :guards [(the-answer-is-a-persons :still_stands)]
                   :handler stamp-the-answer
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "This says the claim is true of this house, and stamps your name and the date on it. The evidence goes on being read either way — affirming a belief does not stop it moving, it only says the house agrees it is worth holding."}
                   :display {:label "Yes — that's true of us" :order 2
                             :description "Somebody read this house and thinks this is so. Say whether they are right — it stays on the record either way, and the number under it keeps moving"}}
    ;; THE OTHER ANSWER TO A GUESS, and it is a separate door from
    ;; `retire` for the household's reason rather than a mechanical
    ;; one: *you read me wrong* and *that was true and is not now* are
    ;; different sentences, and the composer reading this log must be
    ;; able to tell them apart.
    :dismiss {:from #{:observed} :to :dismissed
              :guards [(the-answer-is-a-persons :dismiss)]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "This says the reading was wrong. The belief stays on the record with your answer beside it and stops being something this house holds. There is no way back: if you change your mind, the honest thing is a fresh claim, which the house will admit because this one no longer stands."}
              :display {:label "No — you read us wrong" :style :danger :order 7
                        :description "Somebody read this house and got it wrong. Say so — the guess and your answer both stay on record"}}
    :retire {:from #{:affirmed} :to :retired
             :guards [(the-answer-is-a-persons :retire)]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "This says it WAS true and is not now — a different sentence from \"you read us wrong\", and the log keeps which one you said. The row stays on record and stops being something this house holds."}
             :display {:label "This was true and is not now" :style :danger :order 8
                       :description "People change. Retiring says so, and says it differently from dismissing a bad guess"}}}})
