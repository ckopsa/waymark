(ns workqueue10.resources.outcome
  "The outcome and its pieces (waymark-jfv.3): the composed bundle,
  with honest consent.

  The owner's own words are what this kind is downstream of:

    Use AI to create a menu of outcomes aligned with my values, and
    reduce the friction of reaching them by aligning the actions with
    the activities I love.

  A `value` (waymark-jfv.2) is what the house cares about. An OUTCOME
  is one thing that value could actually become this week, with the
  friction already paid: the goal in the value's own terms, the
  routing citation that says which loved activity the road runs
  through, the rows the composer read to arrive at it — and two to
  five PIECES, each one a concrete unit of work already prepared to
  the shape its own door will take. `make a memory with Jack` is a
  slogan. `The finger-joint box, Saturday 2pm, stock already cut, and
  it uses the shop because you said you love the shop` is an outcome.

  ── WHY IT IS TWO KINDS AND NOT ONE ──

  The epic's centrepiece is CONSENT GRANULARITY: a refusal has to be
  able to name WHICH piece was wrong, or the composer learns nothing
  from it. That could have been one verdict on the bundle carrying a
  list of pieces. It cannot be, and the finding is structural rather
  than aesthetic (docs/spec-outcome-menu.md § 'Why per-piece rows'):

  1. THE SUGAR DROPS IT. `verdict-action` reads a verdict's `:input`
     only when `:note` is spelled, so `accept {pieces: […]}` is not
     expressible in the spelling the four `:decision` instances use.
  2. HAND-WRITTEN, IT FALLS OFF THE CARD. `demand/field-class` never
     inspects an array's `:items`, so `[:vector [:enum …]]` renders
     `recall` — heavier than `feed/card-ceiling` — and
     `feed/split-verbs` moves the verdict off the card into a link.
     waymark-iqa.4 found this with a note; this is the same finding
     one shape over.
  3. A PICKER IS NOT A THUMB. A single `:kind` ref WOULD render
     `selection`, so 'decline one named piece' is technically
     expressible on the parent — but an action with an input opens a
     dialog, and the unit of consent here is a thumb.

  So each piece is its own row with its own note-free, input-free
  verdicts, and the bundle's coherence lives on the parent. A PARTIAL
  ACCEPT is not a third verb: tap `Not this` on the pieces you do not
  want, then `Make it so`, which takes the pieces still offered. The
  reverse order works identically.

  ── THE TWO DECLINES MEAN DIFFERENT THINGS AND MUST STAY DIFFERENT ──

  `not_this` on a PIECE says THE COMPOSITION WAS WRONG. `not_this_week`
  on the OUTCOME says THE TIMING WAS WRONG — and its handler moves
  every still-offered piece to `moot` rather than to `declined`, so a
  composer reading the trail never mistakes a bad week for a verdict
  on a piece it should stop proposing. That distinction is the whole
  of the teaching signal, and it is rows and transitions rather than
  prose: who answered, when, which piece, which way.

  A DECLINED OUTCOME DOES NOT COME BACK AS ITSELF. The tickler's
  self-loop was right for a marker and is wrong here, because a
  recomposed outcome has different pieces and a self-loop would make
  the row lie about what it offers. Recomposition is a NEW outcome
  naming `supersedes`, and the decline stamps `not_before` off the
  tickler's own backoff schedule — a week, then three, then two
  months, then half a year — which `a-recomposition-waits-its-turn`
  then holds. Half a year forever, because the only honest way to stop
  hearing about an outcome is to retire the value it serves.

  ── THE TAP IS THE WRITE, AND IT IS THE MEMBER'S OWN ──

  `ctx :invoke` and `ctx :create` carry the OUTER principal (the
  waymark-iqa.6 finding, confirmed by waymark-0k4's staged proposal),
  so the task and the event that land carry the MEMBER's name on their
  create transitions and are judged by their own kinds' guards as that
  member. Nothing acts as a system actor and nothing is minted
  post-commit. Three consequences, each inherited whole from 0k4:

  1. ONE TRANSACTION PER TAP. `make_it_so` fans out through
     `ctx :invoke` inside the same transaction, so a refusal anywhere
     rolls the whole tap back and the outcome does not read accepted
     while a piece silently did not land. A household would rather
     have three of four things on the calendar than none — which is
     exactly why the PIECES are separately tappable in the first
     place; what it must never have is a bundle that lies.
  2. NO DETERMINISTIC INNER KEY. The verdict doors ARE the idempotency
     boundary: a second tap meets a terminal row. This is the sharp
     difference from `grants/approval-effects!`, which needed one
     BECAUSE it runs post-commit under a system actor with no verdict
     row of its own.
  3. NO FENCE IS SUPPLIED, AND THE ABSENCE IS THE RULE RATHER THAN A
     WAIVER. 0k4 handed `feed_recipe`'s `:revise` an `:if-match`
     because an `:edit` IMPLIES the fence. A piece's `take` declares
     no edit and takes no input, so no fence is implied — there is
     nothing here to waive.

  ── THE TARGET IS NOT FREE DATA ──

  `insight` recorded the one cross-write no declaration could name:
  'an insight's target is data chosen by its author — no `:touches`
  could advertise it, and no grant would re-gate it', so its offer is
  an ADDRESS rather than a trigger. A piece closes every clause of
  that sentence. `target_kind` is a DECLARED enum of the household's
  work kinds; `:touches` names the union literally, so
  `checks_assembly/check-touches` verifies it at assembly and the
  envelope advertises the blast radius. Only the INPUT is data — the
  `ingredient/absorb-duplicate` shape, one notch wider.

  ── VALIDATED AT STAGING, JUDGED AGAIN AT THE TAP ──

  `the-prepared-input-fits-the-door` runs the prepared input through
  the target kind's OWN create model at staging, exactly as its door
  would: decode, defaults, closed errors. That is 0k4's
  letter-addressing lesson — a button that fails is worse than a
  button that was never offered.

  The world still moves between staging and the tap: the Wednesday
  slot fills, the list is thrown away, the authority conflicts the
  row. The answer is that THE TARGET'S OWN CREATE GUARDS JUDGE AT THE
  TAP, inside the transaction, and their refusal is what the household
  reads. What is deliberately NOT built here is a second staleness
  oracle: a wall that tried to predict another kind's guards would be
  a second opinion about that kind's law, and it would be wrong first.
  The way out is two taps — `Not this` on the stale piece, `Make it
  so` again.

  ── WHY THE :decision SUGAR IS DECLINED FOR BOTH ──

  Not taste. Three of its recorded limits, all fatal here: a verdict
  cannot declare `:touches`, and both accepts cross-write; `:decider`
  has no principal-type dimension, so it cannot say A PERSON, NEVER AN
  AGENT, which is half of what makes a composer safe; and the sugar's
  single open state cannot express the `moot` arm. As
  `recipe_proposal` did, the walls are still THE SUGAR'S OWN GUARDS —
  `g/not-the-field` is the very guard `desugar-decision` would have
  minted — so the law is the sugar's law and not a lookalike. That the
  sugar cannot spell a cross-writing verdict is filed (waymark-bro)
  rather than fixed here.

  ── :nav :system, AND :own-surface ──

  Hand-written kinds inherit no `:nav`, and a `:primary` outcome would
  card in do-now and be congratulated by fuel for the house having
  ACCEPTED A PROPOSAL — the exact self-referential loop this epic is
  trying not to build. `:own-surface {:by :composed_by :actions #{}}`
  on both: the composer reads what it staged and how it was answered
  (that IS the diagnosis feed waymark-8um law 4 consumes), and the
  verdict doors are not listed because the four-eyes wall refuses the
  stager at every one of them — advertising a door that answers 409 to
  the only principal the courtesy is for would be a lie.

  Staging rides an ordinary grant at the MCP door, `recipe_proposal`'s
  posture rather than `insight`'s open create door, and for its
  reason: a finding is a sentence the household reads, an outcome is a
  bundle of prepared WRITES a member enacts with a tap, and which
  agents may put one of those in front of the house is a decision the
  house should get to make out loud, once, at the grant."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [workqueue10.resources.tickler :as tickler])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

;; ── what a piece may become ─────────────────────────────────────────

(def materializable
  "The household's WORK kinds a piece may birth, as a DECLARED set —
  the answer to the primitive waymark-iqa.6 refused.

  TWO, and the smallness is the decision rather than an omission. The
  reference composition (the artifact 'The Feed, Composed') composed
  exactly three sorts of thing: errands, calendar holds, and grocery
  lines. Errands are `task`. Calendar holds are `event`. A grocery
  LINE is not a row at all — it is an item inside a `grocery_list`,
  added by that kind's own `add_item` action, and a piece may only
  CREATE in v1 — so the honest spelling of 'buy the stock' today is a
  task that says so.

  What is NOT in here, by construction rather than by a blocklist:
  every governance kind (`grant`, `capability`, `approval_request`,
  `permission_slip`, `member`, `role`), every editorial kind
  (`feed_recipe`, `recipe_proposal`, `value`, `outcome`,
  `outcome_piece`), and the kinds a composer has no business birthing
  on somebody's behalf — `chore_run` (an occurrence of a chore the
  house DECLARED, whose schedule is the chore's own law), `media` (a
  shelf entry is a wish, not friction pre-paid), `letter` (nobody
  signs somebody else's name).

  It grows by law revision, which is the point of it being declared:
  widening the set is a change to the household's law that a reader
  can see, not a field a composer fills in."
  [:task :event])

(def target-kind-enum
  "The schema form `target_kind` wears — the same set, spelled as the
  closed enum a form and a wire read."
  (into [:enum] (map name) materializable))

(def touched-creates
  "The blast radius, named LITERALLY so `checks_assembly/check-touches`
  can verify it at assembly and the envelope can advertise it. One
  entry per materializable kind, and it must stay the union of the set
  above — that agreement is what makes 'only the input is data' true."
  (mapv (fn [k] {:kind k :action :create}) materializable))

;; ── the leashes and the caps, as household numbers ──────────────────

(def leash-days
  "How long a staged outcome stands before the house stops being
  asked. SEVEN, and the number is the section's own sentence: the
  crown of the feed says THIS WEEK COULD HOLD, and an outcome still
  asking on the eighth morning is describing a week that is over.
  Engine-owned — the person who benefits from a short leash is the
  household, and the one filling the form is not."
  7)

(def weekly-cap
  "Outcomes one composer may stage in a week. TWO, and the number is
  the whole of the wall: a composer that could stage ten would never
  have to decide which one mattered, and a household that woke up to
  ten would stop reading the section by Thursday. The cap exists so
  the composer RANKS, which is why it stands at the create door and
  not in the population — a filter would bury what a wall would have
  refused, and eight ignored rows would teach the composer that this
  house does not care.

  Per AUTHOR rather than per house: `insights-are-capped`'s own
  reasoning, and `resource/pacing-guards`' — a house-wide cap would
  let a noisy composer silence a quiet one. It counts ROWS in the
  store, so unlike the in-process pacing atoms it is shared across
  processes."
  2)

(def bundle-ceiling
  "The most pieces one outcome may carry. FIVE, because the epic's own
  sentence is that a bundle tracks REAL-WORLD COHERENCE — a Saturday
  afternoon, not a whole week — and six things is a week. The floor
  (an outcome with one piece is a finding; publish an insight) has no
  door to stand at, because the parent is born before any piece
  exists and no create door can count what has not been written yet;
  it is the composer's duty in prose and the population's judgment at
  offer time."
  5)

;; ── the week, and where its boundary lives ──────────────────────────
;;
;; MONDAY 00:00 UTC — `store/utc-week-start`, called by name so there
;; is never a second truncation. The week the household is HAVING,
;; not a rolling 168 hours, which is `insights-are-capped`'s own
;; reasoning about the calendar day one window up: the house reads
;; "two a week" and means the week it is in. A rolling window would
;; have been cheaper and would have made the sentence a lie.

(defn- week-start ^Instant [^Instant now] (store/utc-week-start now))

;; ── addresses, read rather than guessed ─────────────────────────────

(defn- row-address
  "`/api/<plural>/<id>` → {:plural … :id …}, nil for anything else —
  `insight/row-address`, the same shape and the same refusals: query
  strings, action doors and bare ids are not addresses."
  [s]
  (let [parts (str/split (str s) #"/")]
    (when (and (= 4 (count parts))
               (= "" (nth parts 0))
               (= "api" (nth parts 1))
               (not (str/blank? (nth parts 2)))
               (not (str/blank? (nth parts 3)))
               (not (str/includes? (str s) "?")))
      {:plural (nth parts 2) :id (nth parts 3)})))

(defn- listed
  "A short, ordered rendering of what went wrong — EVERY offender, not
  the first, because a composer fixing them one round trip at a time
  is a composer burning its cap."
  [xs]
  (str/join ", " (map pr-str (sort (distinct xs)))))

;; ── the outcome's create walls ──────────────────────────────────────
;;
;; SHAPE FIRST, WORLD NEXT, PACE LAST — insight's ordering and its
;; reason: a malformed outcome should hear what is wrong with it
;; rather than that the week is full, and because the cap counts ROWS
;; a refused create spends nothing.

(defguardfn cites-what-it-read
  {:judges [:evidence]
   :reads [:storage]
   :vars [:count :offenders]
   :open "An outcome cites the rows it was built from: at least one address, each of them /api/<collection>/<id> naming a collection this house serves."
   :explain "An outcome with nothing behind it is a suggestion — cite the rows you actually read, as addresses like /api/tasks/01H… ({count} given{offenders})."}
  [_row inp ctx]
  (let [ev (into [] (remove str/blank?) (map str (:evidence inp)))
        rdef-of (:rdef-of ctx)]
    (cond
      ;; the storage-free probe (render, the partial rehearsal) has no
      ;; registry in scope: advertise optimistically, exactly as
      ;; insight/cites-what-it-claims does. The write path always
      ;; carries the consult.
      (nil? rdef-of) (t/allow)

      (empty? ev)
      (t/deny {:vars {:count 0 :offenders ""}})

      :else
      (let [bad (into []
                      (remove (fn [href]
                                (when-some [{:keys [plural]} (row-address href)]
                                  (some? (rdef-of plural)))))
                      ev)]
        (if (seq bad)
          (t/deny {:vars {:count (count ev)
                          :offenders (str "; this house has nothing at "
                                          (listed bad))}})
          (t/allow))))))

(defn- value-row
  "The value an outcome names, read through the write's own
  transaction. nil when the probe carries no read hook, which every
  wall below treats as 'advertise optimistically'."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [vid (some-> (:value_id inp) str str/trim not-empty)]
      (read' :value vid))))

(def ^:private held-states
  "The value states an outcome may be staged against — waymark-jfv.10
  widened this from the one state jfv.2 had.

  `observed` is admitted deliberately: a value an agent wrote down is
  a value this house may still spend a Saturday on, and refusing to
  compose against one would have made the owner's ruling
  (`there's nothing wrong with you learning what my values are`) a
  permission with nothing behind it. What an observed value does NOT
  get is silence about its standing — `feed/outcome-says` puts it on
  the card in the household's own words, so the person answering the
  bundle knows whether the value under it is his or somebody's
  reading of him.

  `retired` stays out, and that covers a dismissed guess as well as an
  abandoned law: both mean the house is not holding it."
  #{"observed" "declared"})

(defguardfn names-a-value
  {:judges [:value_id]
   :reads [:value]
   :vars [:problem]
   :open "Every outcome serves one value this house is holding — declared by a member, or observed in its record and not yet answered. That is what makes it an outcome rather than a good idea."
   :explain "That is not a value this house is holding: {problem}"}
  [_row inp ctx]
  (let [read' (:read ctx)
        vid (some-> (:value_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (nil? read') (t/allow)

      (nil? vid)
      (deny (str "it names no value at all. An outcome nobody can say"
                 " WHY about is a task list with a nicer heading."))

      :else
      (let [row (read' :value vid)]
        (cond
          (nil? row)
          (deny (str "this house has no value " vid
                     " — read /api/values and name one of those."))

          (not (held-states (name (:state row))))
          (deny (str "the value " (pr-str (get-in row [:data :name]))
                     " is retired. The house stopped holding that one"
                     " — or answered it as a reading that was wrong —"
                     " and either way that is exactly how a value stops"
                     " being offered outcomes that serve it."))

          :else (t/allow))))))

(defguardfn routes-through-something-loved
  {:judges [:routes_through]
   :reads [:value]
   :vars [:named :legal]
   :open "The routing citation is CHECKED: a loved activity is one the value itself declares, in the owner's own spelling. Naming none is allowed — some outcomes route through nothing anybody loves."
   :explain "Nobody wrote down loving {named}{legal} — the whole point of the routing is that it cites something the owner actually said, so an invented one teaches this house nothing."}
  [_row inp ctx]
  (let [named (some-> (:routes_through inp) str str/trim not-empty)]
    (cond
      ;; ABSENT IS ALLOWED, ON PURPOSE. Grandpa's paperwork routes
      ;; through nothing anybody loves, and a door that forced a
      ;; citation would be a door that taught the composer to invent
      ;; one. An outcome with no loved activity beside it says so, and
      ;; its routing prose has to earn the ask some other way.
      (nil? named) (t/allow)
      (nil? (:read ctx)) (t/allow)

      :else
      (let [row (value-row inp ctx)
            ;; the wall reads the value THIS outcome cites, not the
            ;; whole shelf: a plan that borrowed the shop from a
            ;; different value would be citing a routing that does not
            ;; serve the goal it claims to serve
            loved (into [] (remove str/blank?)
                        (map str (get-in row [:data :loved])))]
        (cond
          ;; names-a-value stands in front of this one and has already
          ;; refused a value that is not there; a missing row here is
          ;; the probe's world, not a caller's mistake
          (nil? row) (t/allow)

          (some (fn [w] (= (str/trim w) named)) loved)
          (t/allow)

          :else
          (t/deny {:vars {:named (pr-str named)
                          :legal (if (seq loved)
                                   (str ". What that value says it loves is "
                                        (listed loved))
                                   (str ", and the value you named lists no"
                                        " loved activity at all — which is"
                                        " precisely the high-friction value"
                                        " the whole idea is about. Leave the"
                                        " routing empty and say so."))}}))))))

(defguardfn a-recomposition-waits-its-turn
  {:judges [:supersedes]
   :reads [:outcome :now]
   :vars [:problem]
   :open "A declined outcome comes back recomposed, not repeated — and not straight away. The decline stamps when the house is willing to hear it again: a week, then three, then two months, then half a year."
   :explain "That recomposition is early: {problem}"}
  [_row inp ctx]
  (let [read' (:read ctx)
        sid (some-> (:supersedes inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (nil? sid) (t/allow)
      (nil? read') (t/allow)

      :else
      (let [prior (read' :outcome sid)
            floor (get-in prior [:data :not_before])]
        (cond
          (nil? prior)
          (deny (str "this house serves no outcome " sid
                     " — supersede one that exists, or name none."))

          (= "offered" (name (:state prior)))
          (deny (str "/api/outcomes/" sid " is still on the fridge waiting"
                     " for an answer. Recomposing something nobody has"
                     " declined yet is asking the same question twice."))

          (and floor (pos? (compare floor (:now ctx))))
          (deny (str "the house said not this week, and meant it until "
                     floor ". Diagnose the friction in the meantime —"
                     " a recomposition that arrives the next morning is"
                     " the same ask in a new hat."))

          :else (t/allow))))))

(defguardfn outcomes-are-few
  {:reads [:principal :now :outcome]
   :vars [:limit :retry_at]
   :open "Two outcomes a week, per composer, Monday to Monday — the cap is what makes a composer rank rather than dump."
   :explain "That is {limit} outcomes staged this week, which is the week's whole allowance; the next one opens on Monday ({retry_at}). Rank what is left and bring the best of it then."}
  [_row _inp ctx]
  ;; the storage-free probe never spends a slot — letters-are-paced's
  ;; own discipline, and the same one pacing-guards keeps
  (if (nil? (:find ctx))
    (t/allow)
    (let [pid (:id (:principal ctx))
          monday (week-start (:now ctx))
          staged (into []
                       (filter (fn [r]
                                 (and (some? (:created-at r))
                                      (not (pos? (compare monday
                                                          (:created-at r)))))))
                       ((:find ctx) :outcome {:composed_by pid} {:limit 500}))]
      (if (< (count staged) (long weekly-cap))
        (t/allow)
        (let [next-monday (.plus monday 7 ChronoUnit/DAYS)]
          (t/deny {:vars {:limit weekly-cap :retry_at (str next-monday)}
                   :retry-at next-monday}))))))

;; ── the piece's create walls ────────────────────────────────────────

(defn- outcome-of
  "The outcome a piece hangs from, whichever door is asking: the row's
  own field at a verdict, the input's at a birth."
  [row inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [oid (some-> (or (get-in row [:data :outcome_id])
                                (:outcome_id inp))
                            str str/trim not-empty)]
      (read' :outcome oid))))

(defguardfn the-prepared-input-fits-the-door
  {:judges [:target_kind :prepared]
   :reads [:storage]
   :vars [:target :problems]
   :open "A piece is judged against the create model of the very kind it will knock on — decoded, defaulted and closed, exactly as that door does it."
   :explain "That is not something {target}'s own create door would take, so nobody could ever tap it: {problems}"}
  [_row inp ctx]
  (let [rdef-of (:rdef-of ctx)
        k (some-> (:target_kind inp) str str/trim not-empty)
        prepared (:prepared inp)
        deny (fn [target problems]
               (t/deny {:vars {:target target :problems problems}}))]
    (if (nil? rdef-of)
      ;; the storage-free probe again — no registry, no verdict
      (t/allow)
      (let [rd (some-> k rdef-of)]
        (cond
          ;; the enum in the schema has already refused an undeclared
          ;; word with a 422; this is the belt for the day the set and
          ;; the registry disagree
          (nil? rd)
          (deny (or k "that") "this house serves no such kind at all.")

          (not (map? prepared))
          (deny k (str "a piece carries the input its door will take, as"
                       " an object — this one carries "
                       (pr-str prepared) "."))

          :else
          ;; THE TARGET'S OWN CREATE MODEL, read off the registry
          ;; rather than copied — the same value its door validates
          ;; against, in the same three steps and the same order
          ;; (invoke/create-in-tx!): decode, fill declared defaults,
          ;; refuse unknowns.
          (let [model (or (:create-schema rd) (:schema rd))
                decoded (schema/apply-defaults
                         model (schema/decode model prepared))]
            (if-some [errs (schema/closed-errors model decoded)]
              (deny k (pr-str errs))
              (t/allow))))))))

;; NO :judges, and the absence is forced rather than sloppy: this one
;; guard stands at the piece's CREATE door (where the outcome arrives
;; in the input) and at its `take` (where it is already on the row,
;; and the action takes no input at all). `check-guard-declarations`
;; refuses a :judges on an input-free action, and rightly — so the
;; subject of this wall is named as what it truly is, the outcome ROW,
;; through :reads.
(defguardfn the-outcome-is-still-open
  {:reads [:outcome :now]
   :vars [:problem]
   :open "A piece only exists inside a live outcome — the bundle is what a piece means, and the week is what the bundle was for."
   :explain "The outcome this piece belongs to is not taking answers any more: {problem}"}
  [row inp ctx]
  (let [read' (:read ctx)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (nil? read')
      (t/allow)
      (let [o (outcome-of row inp ctx)
            good-until (get-in o [:data :good_until])]
        (cond
          (nil? o)
          (deny (str "it names no outcome this house serves. A piece with"
                     " no bundle is a task nobody asked for — write the"
                     " outcome first, then hang its pieces on it."))

          (not= "offered" (name (:state o)))
          (deny (str "/api/outcomes/" (:id o) " is "
                     (name (:state o)) " now — the house has already"
                     " answered it."))

          (and good-until (not (pos? (compare good-until (:now ctx)))))
          (deny (str "the week /api/outcomes/" (:id o) " was for ran out at "
                     good-until ". Compose it again against the week the"
                     " house is actually having."))

          :else (t/allow))))))

(defguardfn a-bundle-is-small
  {:judges [:outcome_id]
   :reads [:outcome_piece]
   :vars [:ceiling]
   :open "Two to five pieces. A bundle tracks what the world can actually hold — a Saturday afternoon, not a whole week."
   :explain "That outcome already carries {ceiling} pieces, which is a week rather than an afternoon. Whatever else this was going to be belongs in its own outcome, and the cap is what makes you choose."}
  [row inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [oid (some-> (or (get-in row [:data :outcome_id])
                          (:outcome_id inp))
                      str str/trim not-empty)
          siblings (when oid
                     ((:find ctx) :outcome_piece {:outcome_id oid}
                      {:limit (inc (long bundle-ceiling))}))]
      (if (< (count siblings) (long bundle-ceiling))
        (t/allow)
        (t/deny {:vars {:ceiling bundle-ceiling}})))))

;; ── the verdict walls, shared by both kinds ─────────────────────────

(def the-composer-does-not-decide
  "The four-eyes wall, doing the load-bearing work on FIVE doors. It
  is the same guard `desugar-decision` would have minted —
  `g/not-the-field`, by the factory, so the law is the sugar's law and
  not a lookalike — and it is what makes 'the composer only proposes'
  structural rather than promised: whoever staged a bundle is
  incapable of answering any part of it."
  (g/not-the-field
   :composed_by
   {:name :the-composer-does-not-decide
    :explain "The composing is yours; the answer is the household's. Whoever staged this cannot be the one to take it, decline it, or let it go."}))

(defguardfn a-person-answers
  {:reads [:principal]
   :explain "A person answers an outcome — every part of it, both ways round. A house running two agents would otherwise have one stage the bundle and the other tap it through, and the four-eyes wall would have been walked around rather than kept. If you are an agent and you think this outcome is right, say so where an agent may: publish an insight citing what you read."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind, so the render probe and
  ;; the real invoke read the same fact (feed_recipe's own posture,
  ;; value.clj's one kind over). :system is the ENGINE's own actor — a
  ;; migration, a seed, the conformance walker — and is not what this
  ;; wall is about; the wall is about the composer.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

(defguardfn the-leash-has-not-run-out
  {:reads [:now]
   :vars [:expired_at]
   :open "An outcome stands for a week. Enforcement is live at the door: a lapsed outcome materializes nothing, whatever state its row is still resting in."
   :explain "This one was for the week that ended {expired_at}. The slots it found are somebody else's by now — ask the composer for it again against the week the house is in."}
  [row _inp ctx]
  (let [exp (get-in row [:data :good_until])]
    (if (and exp (not (pos? (compare exp (:now ctx)))))
      (t/deny {:vars {:expired_at (str exp)}})
      (t/allow))))

(defguardfn the-leash-has-run-out
  {:reads [:now]
   :vars [:good_until]
   :open "Expiring is bookkeeping: it tidies a row the household has already stopped being asked about, and it cannot be used to take a live outcome off the table."
   :explain "This outcome is still live until {good_until} — say not this week if the house does not want it; expiring is for the ones the clock has already answered."}
  [row _inp ctx]
  (let [exp (get-in row [:data :good_until])]
    (if (and exp (pos? (compare exp (:now ctx))))
      (t/deny {:vars {:good_until (str exp)}})
      (t/allow))))

(defguardfn something-is-still-on-offer
  {:reads [:outcome_piece]
   :open "Make it so takes the pieces STILL OFFERED. With none left it would be a tap that landed nothing while the row read accepted, which is the one thing an apply must never do."
   :explain "Every piece of this one has already been answered, so there is nothing left for Make it so to do. If the whole bundle was wrong, say not this week — that is the answer the composer can learn from."}
  [row _inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [open ((:find ctx) :outcome_piece
                {:outcome_id (:id row) :state "offered"} {:limit 1})]
      (if (seq open) (t/allow) (t/deny)))))

;; ── the stamps and the handlers ─────────────────────────────────────

;; The birth stamps, and none of them is the caller's to give: WHO
;; composed it (a bundle that could name somebody else as its author
;; is a bundle that can frame them), HOW LONG the house is asked, and
;; how many times this line of thinking has already been turned down
;; — read off the outcome it supersedes, so the backoff lengthens
;; across a chain instead of resetting every time the composer
;; rephrases.
(defhandler stage-the-outcome [row ctx]
  (let [sid (some-> (get-in row [:data :supersedes]) str str/trim not-empty)
        prior (when (and sid (:read ctx)) ((:read ctx) :outcome sid))]
    (-> row
        (assoc-in [:data :composed_by] (:id (:principal ctx)))
        (assoc-in [:data :good_until]
                  (.plusSeconds ^Instant (:now ctx)
                                (* 86400 (long leash-days))))
        (assoc-in [:data :declined_count]
                  (long (or (get-in prior [:data :declined_count]) 0))))))

(defhandler take-the-rest [row _inp ctx]
  ;; THE FAN-OUT, IN ONE TRANSACTION. Every piece still offered goes
  ;; through its OWN take door — its guards, its handler, its
  ;; transition — under the member who tapped, because ctx :invoke
  ;; carries the outer principal. A refusal inside any of them rolls
  ;; the whole tap back and this row does not read accepted: waymark-0k4
  ;; decision 1, for its reason — an apply that landed nothing must
  ;; not read as applied.
  ;;
  ;; Oldest first, so the refusal a household reads names the piece it
  ;; would have read first on the card. No fence is handed over: take
  ;; declares no :edit and takes no input, so none is implied.
  (let [pieces ((:find ctx) :outcome_piece
                {:outcome_id (:id row) :state "offered"}
                {:limit (inc (long bundle-ceiling))})]
    (doseq [p (sort-by :created-at pieces)]
      ((:invoke ctx) :outcome_piece (:id p) :take nil))
    (assoc-in row [:data :decided_by] (:id (:principal ctx)))))

(defhandler moot-the-rest [row _inp ctx]
  ;; NOT THIS WEEK IS A VERDICT ON THE TIMING, so the pieces are made
  ;; MOOT and never DECLINED — a composer reading this trail has to be
  ;; able to tell 'the week was wrong' from 'that part was wrong', and
  ;; the states are where that difference lives.
  ;;
  ;; The floor is stamped here too: how long before the house is
  ;; willing to hear this outcome recomposed. The schedule is the
  ;; tickler's own — a week, three weeks, two months, half a year —
  ;; called by name, with `now` handed in, so nothing here reads a
  ;; clock and the same inputs answer the same instant in a test, in a
  ;; scenario and in the house.
  (let [said (inc (long (or (get-in row [:data :declined_count]) 0)))
        pieces ((:find ctx) :outcome_piece
                {:outcome_id (:id row) :state "offered"}
                {:limit (inc (long bundle-ceiling))})]
    (doseq [p (sort-by :created-at pieces)]
      ((:invoke ctx) :outcome_piece (:id p) :moot nil))
    (-> row
        (assoc-in [:data :decided_by] (:id (:principal ctx)))
        (assoc-in [:data :declined_count] said)
        (assoc-in [:data :not_before] (tickler/next-offer (:now ctx) said)))))

;; The piece's one birth stamp: who staged it. Its own, rather than
;; the parent's copy, because the four-eyes wall on a PIECE has to
;; read the piece — a wall that reached up to the bundle would be a
;; wall a piece staged by somebody else walked straight through.
(defhandler stamp-the-composer [row ctx]
  (assoc-in row [:data :composed_by] (:id (:principal ctx))))

(defhandler record-the-verdict [row _inp ctx]
  (assoc-in row [:data :decided_by] (:id (:principal ctx))))

(defhandler materialize [row _inp ctx]
  ;; THE TAP IS THE WRITE. ctx :create carries the OUTER principal, so
  ;; the task or the event that lands carries the MEMBER's name on its
  ;; create transition and is judged by its own kind's create guards as
  ;; that member — which is also the whole of the staleness answer: the
  ;; world is re-judged here, by the target's own law, and its refusal
  ;; is what the household reads. Same transaction, so a refusal rolls
  ;; the tap back and the piece does not read taken.
  (let [k (keyword (str (get-in row [:data :target_kind])))
        res ((:create ctx) k (get-in row [:data :prepared]))
        plural (:plural ((:rdef-of ctx) k))
        rid (str (get-in res [:row :id]))]
    (-> row
        (assoc-in [:data :decided_by] (:id (:principal ctx)))
        (assoc-in [:data :materialized] (str "/api/" plural "/" rid)))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; TWO TIERS, and which scenario lands in which is read off the
;; declarations rather than chosen (scenario.clj's own rule).
;;
;; THE VERDICT WALLS ARE PROVED ON THE DECLINE DOORS, at check tier,
;; with no database at all — `not_this` and `not_this_week` carry only
;; the four-eyes wall and the agent wall, both of which read the
;; principal and the row and nothing else. That is not a soft choice:
;; a conformance-tier ACTION scenario stages its subject through the
;; kind's own create door AS THE WALKER, which would stamp the
;; walker's id into `composed_by` and make the four-eyes wall answer
;; about the wrong person (`recipe_proposal` recorded this exactly).
;; So `take` and `make_it_so` — whose walls read other rows — are
;; proved by workqueue10.outcome-test against an engine it holds, and
;; the two verdict walls they SHARE with the declines are proved right
;; here, one guard object, five doors.
;;
;; THE CREATE WALLS DEFER to the conformance tier, because all five
;; read the registry or the store, and they are attempted AS A PERSON
;; deliberately: the staging walls judge the BODY, so they say the
;; same thing to whoever wrote it, and who may reach the door at all
;; is the grant's question and a different sentence.

(def ^:private a-composed-outcome
  {:goal "One Saturday afternoon in the shop with Jack, and a finished box at the end of it"
   :value_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :value_name "making memories with the family"
   :routing "It runs through the shop, which you wrote down as something you love — so the hard part is already the easy part."
   :routes_through "the shop"
   :evidence ["/api/values/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
              "/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]
   :composed_by "composer"
   :declined_count 0
   :good_until (Instant/parse "2026-09-01T09:00:00Z")})

(def ^:private a-prepared-piece
  {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"
   :says "Cut the stock to length Friday evening — twenty minutes, and Saturday starts with the glue-up"
   :target_kind "task"
   :prepared {:title "Cut the box stock to length"}
   :composed_by "composer"})

(defscenario the-composer-does-not-answer-its-own-outcome
  "Four eyes, and it is the whole bead: an agent that could stage a
   bundle and answer it would be putting its own composition on the
   household's Saturday. The wall is structural, not a policy the
   composer is trusted to keep — and it stands on the DECLINE too,
   because quietly withdrawing what a member never saw is the same
   power in reverse."
  {:kind    :outcome
   :attempt :not_this_week
   :row     {:state :offered :data a-composed-outcome}
   :as      {:id "composer" :type :agent}
   :expect  {:refused :the-composer-does-not-decide}})

(defscenario an-agent-does-not-answer-an-outcome
  "The second wall behind the four-eyes one, and the reason a house
   running two agents is no different from a house running one: the
   answer belongs to a person either way, and the refusal names where
   an agent MAY say what it thinks."
  {:kind    :outcome
   :attempt :not_this_week
   :row     {:state :offered :data a-composed-outcome}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :a-person-answers
             :because "A person answers"}})

(defscenario an-answered-outcome-does-not-come-back
  "Accepted is an answer and it is kept. The machine itself refuses
   the second question, with no guard behind it, which is the
   strongest way a promise can be made — and it is why a recomposition
   has to be a NEW outcome rather than this one asking again."
  {:kind    :outcome
   :attempt :not_this_week
   :row     {:state :accepted :data a-composed-outcome}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Offered"}})

(defscenario a-live-outcome-is-not-expired-out-of-the-way
  "Expiring is bookkeeping, never a way to take a live outcome off the
   household's table. Only the clock retires one; a person who does
   not want it says not this week, on the record, where the composer
   can read it."
  {:kind    :outcome
   :attempt :expire
   :at      "2026-08-25T09:00:00Z"
   :row     {:state :offered :data a-composed-outcome}
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-leash-has-run-out
             :because "still live until"}})

(defscenario an-outcome-with-nothing-behind-it-is-refused
  "An outcome is authored interpretation sitting on top of the
   household's own ledger, so it says what it read. A bundle with no
   citations is a suggestion, and the feed already has enough of
   those."
  {:kind    :outcome
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:goal "A Saturday in the shop"
             :value_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :routing "It uses the shop."}
   :expect  {:refused :cites-what-it-read}})

(defscenario an-outcome-names-a-value-this-house-holds
  "No outcome without the value it serves. The citation is checked
   against the house's own declarations rather than taken on trust,
   because 'aligned with my values' is the entire claim this kind
   makes and an unchecked claim is a slogan."
  {:kind    :outcome
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:goal "A Saturday in the shop"
             :value_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B9"
             :routing "It uses the shop."
             :evidence ["/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]}
   :expect  {:refused :names-a-value
             :because "this house has no value"}})

(defscenario the-composer-does-not-answer-its-own-piece
  "The same wall, one row down, and it has to be here as well as on
   the parent: the pieces are where the consent actually happens, so a
   composer that could decline its own piece could shape the bundle
   the household ends up saying yes to."
  {:kind    :outcome_piece
   :attempt :not_this
   :row     {:state :offered :data a-prepared-piece}
   :as      {:id "composer" :type :agent}
   :expect  {:refused :the-composer-does-not-decide}})

(defscenario an-agent-does-not-answer-a-piece
  "And no other agent either. Materialization happens under a
   member's tap or it does not happen — that sentence is what makes
   this kind grantable at the MCP door in the first place."
  {:kind    :outcome_piece
   :attempt :not_this
   :row     {:state :offered :data a-prepared-piece}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :a-person-answers
             :because "A person answers"}})

(defscenario a-taken-piece-does-not-come-back
  "One tap, one row. A piece that has already landed its task cannot
   be declined afterwards — the machine refuses the second answer
   with no guard behind it, which is exactly why no deterministic key
   is minted for the inner write."
  {:kind    :outcome_piece
   :attempt :not_this
   :row     {:state :taken :data a-prepared-piece}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Offered"}})

(defscenario a-piece-fits-the-door-it-will-knock-on
  "The friction is pre-paid at STAGING, not discovered at the tap. A
   piece whose prepared input its own target would refuse — here a
   composer that invented a field name the calendar has never heard of
   — is refused where it was written. A button that fails is worse
   than a button that was never offered."
  {:kind    :outcome_piece
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"
             :says "Put the box on the calendar"
             :target_kind "event"
             :prepared {:title "Shop afternoon with Jack"
                        :when "Saturday at 2"}}
   :expect  {:refused :the-prepared-input-fits-the-door}})

;; ── the prose the doors wear ────────────────────────────────────────
;;
;; Spelled ONCE and worn by both the row schema and the create model,
;; the way feed_recipe's and value's own `prose` maps are — three
;; copies of one sentence is three places for it to drift.

(def ^:private outcome-prose
  {:goal
   {:x-display
    {:label "What this week could hold"
     :help "The outcome in the value's own terms, in one sentence somebody reads standing up — \"one Saturday afternoon in the shop with Jack, and a finished box at the end of it\". Not a task and not a slogan: the thing that would actually have happened."}}
   :value_id
   {:x-display
    {:label "The value it serves"
     :help "Which of the house's declared values this outcome is FOR. It has to be one that is still held — retiring a value is exactly how the house stops being offered outcomes that serve it."}}
   :value_name
   {:x-display
    {:raw true
     :label "Value"
     :help "The value's own words, copied by the engine so the card reads without a second lookup."}}
   :routing
   {;; Something in the textarea instead of a blank page (waymark-0ee's
    ;; composition policy, and value.clj's `says-example` one kind
    ;; over). Offered, never applied — and it is the reference
    ;; composition's own kind of sentence, because what a composer is
    ;; being asked for here is unusual enough that a shape helps.
    :examples
    ["It runs through the shop, which you wrote down as something you love — so the expensive part, getting started, is already paid. The stock is cut; Saturday opens with the glue-up rather than with a trip to the lumber yard."]
    :x-display
    {:widget "prose"
     :label "Why this one is cheap to start"
     :help "The citation, in the household's words — which loved activity the road runs through and why that lowers the cost of starting. This is the arbitrage said out loud: spend the thing that is easy to begin on the thing that matters more and costs more to begin."}}
   :routes_through
   {:x-display
    {:label "The loved activity it runs through"
     :help "One activity, spelled exactly as the value spells it — \"the shop\", \"cooking with a podcast on\". Leave it EMPTY when the outcome routes through nothing anybody loves; some of the most valuable ones do, and inventing a routing to fill the box teaches this house nothing."}}
   :evidence
   {:x-display
    {:label "What you read"
     :help "The rows this outcome was built from, as addresses — /api/tasks/01H… — one per row you actually looked at. At least one, always: an outcome sits on top of the household's own ledger, and the house can follow the citations down into it."}}
   :supersedes
   {:x-display
    {:label "The outcome this recomposes"
     :help "When the house said not this week and you are bringing it back in a different shape, name the one it replaces. The house keeps the chain, and each decline pushes the next hearing further out — a week, then three, then two months, then half a year."}}
   :composed_by {:x-display {:raw true :label "Composed by"}}
   :decided_by {:x-display {:raw true :label "Answered by"}}
   :declined_count
   {:x-display
    {:label "Times this line of thinking was turned down"
     :help "Carried down the supersedes chain by the engine. It is the only input to how long the house waits before it will hear this again."}}
   :not_before
   {:x-display
    {:label "Hearable again from"
     :help "Stamped when the house says not this week. A recomposition staged before this is refused — the point of a decline is that the composer goes away and diagnoses the friction, not that it rephrases by morning."}}
   :good_until
   {:x-display
    {:label "For the week ending"
     :help "When the house stops being asked. Seven days from staging, written by the engine — the section is called \"this week could hold\", and an outcome still asking on the eighth morning is describing a week that is over."}}})

(def ^:private piece-prose
  {:outcome_id
   {:x-display
    {:label "The outcome it belongs to"
     :help "The bundle this piece is one part of. A piece with no bundle is a task nobody asked for."}}
   :says
   {:x-display
    {:label "What this piece is"
     :help "The one thing this piece does, in the words the household would use out loud — \"cut the stock to length Friday evening, twenty minutes\". Say what changes and what it costs; this is the line somebody reads before deciding whether it happens."}}
   :target_kind
   {:x-display
    {:label "What it becomes"
     :choices {"task" "A task — an errand, a call, twenty minutes of prep"
               "event" "An event — a hold on the family calendar"}}}
   :prepared
   {:x-display
    {:label "The row, already filled in"
     :help "Exactly the body the target's own create door will take — this is where the friction is pre-paid, and it is checked against that door when the piece is staged rather than when somebody taps it."}}
   :materialized
   {:x-display
    {:raw true
     :label "What it became"
     :help "The address of the row that landed, written by the engine at the tap. Whoever tapped is the row's author, on its own create transition."}}
   :composed_by {:x-display {:raw true :label "Composed by"}}
   :decided_by {:x-display {:raw true :label "Answered by"}}})

(defn- entry [prose k extra form]
  [k (merge (get prose k) extra) form])

(defn- oe [k extra form] (entry outcome-prose k extra form))
(defn- pe [k extra form] (entry piece-prose k extra form))

;; ── :outcome — the bundle the household answers ─────────────────────

(defresource outcome
  {:kind :outcome
   :plural "outcomes"
   ;; hand-written kinds inherit no :nav, and :system is the honest
   ;; one: next_actions claims the open rows of every :primary kind
   ;; and fuel congratulates their endings, so a :primary outcome
   ;; would card in do-now beside the actual work and the house would
   ;; be congratulated for ACCEPTING A PROPOSAL. The work it becomes
   ;; is celebrated on its own rows, where it belongs.
   :nav :system
   :states [:offered :accepted :declined :expired]
   :initial :offered
   :terminal #{:accepted :declined :expired}
   :summary "{data.goal} · for {data.value_name} · {state}"
   :label-template "{data.goal}"
   ;; the title is the GOAL, templated (waymark-jfv.4). A static noun
   ;; was fine while this kind had no card; the feed's crown takes its
   ;; heading from `:display :title` like every other card, and a card
   ;; on top of the page headed "Outcome" would be the one element
   ;; there that said nothing. `:display` is advertisement and rides
   ;; no fingerprint facet, so this moves no hash.
   :display {:title "{data.goal}"}
   :links [{:rel "value" :kind :value
            :href "/api/values/{data.value_id}"
            :summary "The value this outcome serves"}
           {:rel "pieces" :kind :outcome_piece
            :href "/api/outcome_pieces?outcome_id={id}"
            :summary "The pieces this bundle is made of"}
           {:rel "supersedes" :kind :outcome
            :href "/api/outcomes/{data.supersedes}"
            :summary "The outcome this one recomposes"}]
   :schema
   [:map
    (oe :goal {} [:string {:min 1 :max 240}])
    ;; the label garnish (prep_task's meal_name, chore_run's
    ;; chore_name): the engine maintains the value's own words beside
    ;; the ref, so the card and the summary read without a join
    (oe :value_id {:kind :value :label :value_name :filter #{:eq}}
        :waymark/ref)
    (oe :value_name {:optional true} [:maybe [:string {:max 80}]])
    (oe :routing {} [:string {:min 1 :max 600}])
    (oe :routes_through {:optional true} [:maybe [:string {:min 1 :max 40}]])
    (oe :evidence {:optional true}
        [:maybe [:vector [:string {:min 1 :max 200}]]])
    (oe :supersedes {:optional true :kind :outcome :filter #{:eq}}
        [:maybe :waymark/ref])
    ;; ENGINE-WRITTEN, all five. They are in the row schema because
    ;; they are the row's document; they are out of the create model
    ;; because none of them is anybody's to supply.
    (oe :composed_by {:optional true :filter #{:eq}}
        [:maybe [:string {:max 128}]])
    (oe :decided_by {:optional true} [:maybe [:string {:max 128}]])
    (oe :declined_count {:optional true} [:maybe [:int {:min 0}]])
    (oe :not_before {:optional true :filter #{:before}}
        [:maybe :waymark/instant])
    (oe :good_until {:optional true :filter #{:before}}
        [:maybe :waymark/instant])]
   :create-schema
   [:map
    (oe :goal {} [:string {:min 1 :max 240}])
    (oe :value_id {:kind :value} :waymark/ref)
    (oe :routing {} [:string {:min 1 :max 600}])
    (oe :routes_through {:optional true} [:maybe [:string {:min 1 :max 40}]])
    (oe :evidence {:optional true}
        [:maybe [:vector [:string {:min 1 :max 200}]]])
    (oe :supersedes {:optional true :kind :outcome} [:maybe :waymark/ref])]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "offered"}
   :sortable {:fields [:created_at :good_until] :default "-created_at"}
   ;; the composer reads what it staged and how it was answered — that
   ;; IS the diagnosis feed. It reads no doors: the four-eyes wall
   ;; refuses the stager at every verdict, so listing them would
   ;; advertise doors that answer 409 to the only principal the
   ;; courtesy is for (recipe_proposal's reasoning, whole). Staging
   ;; itself rides an ordinary grant rather than this courtesy, for
   ;; the same kind's reason: an outcome is a bundle of prepared
   ;; WRITES, and which agents may put one in front of the house is a
   ;; thing the house says out loud, once, at the grant door.
   :own-surface {:by :composed_by :actions #{}}
   :on-create stage-the-outcome
   ;; shape first, world next, PACE LAST: a malformed outcome hears
   ;; what is wrong with it rather than that the week is full, and
   ;; because the cap counts ROWS a refused create spends nothing
   :create-guards [cites-what-it-read
                   names-a-value
                   routes-through-something-loved
                   a-recomposition-waits-its-turn
                   outcomes-are-few]
   :actions
   {:make_it_so
    {:from #{:offered} :to :accepted
     :guards [the-composer-does-not-decide a-person-answers
              the-leash-has-not-run-out something-is-still-on-offer]
     :handler take-the-rest
     ;; the honest blast radius: the pieces' own verdict door, and
     ;; through it the two work kinds a piece may birth. check-touches
     ;; verifies every pair at assembly, and render puts them on the
     ;; wire, so nobody taps this without being able to read what it
     ;; reaches.
     :touches (into [{:kind :outcome_piece :action :take}] touched-creates)
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This makes the pieces still on offer real, through their own doors, with YOUR name on them — the tasks land in the queue and the holds land on the calendar as if you had written them yourself. The way back is those rows' own doors. Anything you already said no to stays said no."}
     :display {:label "Make it so" :style :primary :order 1
               :description "Take the pieces still on offer — they land as real rows, under your name, in one go"}}
    :not_this_week
    {:from #{:offered} :to :declined
     :guards [the-composer-does-not-decide a-person-answers]
     :handler moot-the-rest
     :touches [{:kind :outcome_piece :action :moot}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The whole bundle leaves the feed and stays on record. It means the WEEK was wrong rather than the pieces — the remaining pieces are set aside rather than refused, so nobody reads this as a verdict on any one of them. The house will hear it recomposed, later, not tomorrow."}
     :display {:label "Not this week" :order 2
               :description "The timing is wrong — set the whole thing aside, and say when the house is willing to hear it again"}}
    :expire
    {:from #{:offered} :to :expired
     :guards [the-leash-has-run-out]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The week already answered this one; the row now says so."}
     :display {:label "Expire" :order 8
               :description "Tidy an outcome the week ran out on"}}}
   :scenarios [the-composer-does-not-answer-its-own-outcome
               an-agent-does-not-answer-an-outcome
               an-answered-outcome-does-not-come-back
               a-live-outcome-is-not-expired-out-of-the-way
               an-outcome-with-nothing-behind-it-is-refused
               an-outcome-names-a-value-this-house-holds]})

;; ── :outcome_piece — one concrete thing, one tap ────────────────────

(defresource outcome-piece
  {:kind :outcome_piece
   :plural "outcome_pieces"
   :nav :system
   :states [:offered :taken :declined :moot]
   :initial :offered
   :terminal #{:taken :declined :moot}
   :summary "{data.says} · {state}"
   :label-template "{data.says}"
   :display {:title "Piece of an outcome"}
   :links [{:rel "outcome" :kind :outcome
            :href "/api/outcomes/{data.outcome_id}"
            :summary "The bundle this piece belongs to"}
           {:rel "materialized" :href "/#{data.materialized}"
            :summary "The row this piece became, once somebody took it"}]
   :schema
   [:map
    (pe :outcome_id {:kind :outcome :filter #{:eq}} :waymark/ref)
    (pe :says {} [:string {:min 1 :max 240}])
    (pe :target_kind {:filter #{:eq}} target-kind-enum)
    ;; THE ONE PIECE OF FREE DATA IN THIS KIND, and the reason it is
    ;; safe is the enum above it: the SHAPE varies, the KIND does not.
    ;; It is judged at staging against the very create model its door
    ;; will judge it against, and again by that door's own guards at
    ;; the tap.
    (pe :prepared {} [:map-of :keyword :any])
    (pe :materialized {:optional true} [:maybe [:string {:max 200}]])
    (pe :composed_by {:optional true :filter #{:eq}}
        [:maybe [:string {:max 128}]])
    (pe :decided_by {:optional true} [:maybe [:string {:max 128}]])]
   :create-schema
   [:map
    (pe :outcome_id {:kind :outcome} :waymark/ref)
    (pe :says {} [:string {:min 1 :max 240}])
    (pe :target_kind {} target-kind-enum)
    (pe :prepared {} [:map-of :keyword :any])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "created_at"}
   :own-surface {:by :composed_by :actions #{}}
   :on-create stamp-the-composer
   ;; shape first, world next, pace last — the same order one kind up
   :create-guards [the-prepared-input-fits-the-door
                   the-outcome-is-still-open
                   a-bundle-is-small]
   :actions
   {:take
    {:from #{:offered} :to :taken
     :guards [the-composer-does-not-decide a-person-answers
              the-outcome-is-still-open]
     :handler materialize
     :touches touched-creates
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This writes the row shown, through its own door, with YOUR name on it — the task joins the queue, the hold joins the calendar. The way back is that row's own doors; this piece is answered either way."}
     :display {:label "Yes" :style :primary :order 1
               :description "Make this one real — it lands as a row of its own, under your name"}}
    :not_this
    {:from #{:offered} :to :declined
     :guards [the-composer-does-not-decide a-person-answers]
     :handler record-the-verdict
     ;; THE TEACHING REFUSAL. Distinct from the parent's not_this_week
     ;; and from moot below, and the difference is the signal: this one
     ;; says THE COMPOSITION WAS WRONG — do not bring this piece back.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This piece is wrong and the record says so — which is the part a composer can actually learn from. The rest of the outcome is untouched; say yes to whatever is still right."}
     :display {:label "Not this" :order 2
               :description "This part was wrong — decline it and leave the rest of the bundle standing"}}
    :moot
    {:from #{:offered} :to :moot
     :guards [a-person-answers]
     :handler record-the-verdict
     ;; SET ASIDE, NOT REFUSED. This is what "not this week" on the
     ;; parent does to every piece still standing, and it is offered
     ;; here on its own for the honest case: the piece is beside the
     ;; point now, and the house does not want the composer to learn
     ;; anything from it. No four-eyes wall: nothing is created and
     ;; nothing is refused, so there is nothing here for a stager to
     ;; grade.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Beside the point now — the piece steps out of the way without being counted as a refusal, so nobody reads it as a verdict on the idea."}
     :display {:label "Beside the point" :order 3
               :description "Set this piece aside without teaching the composer anything about it"}}}
   :scenarios [the-composer-does-not-answer-its-own-piece
               an-agent-does-not-answer-a-piece
               a-taken-piece-does-not-come-back
               a-piece-fits-the-door-it-will-knock-on]})
