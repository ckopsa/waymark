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
  months, then half a year — which the crown's RANK then reads
  (waymark-1uv.10): a recomposition staged before that date is
  admitted and cooled, every day early holding it back on the card,
  never refused at the door. The wall that used to stand there was the
  person's own verdict used as a cap on the composer's writing, and
  the epic ruled a cap on writing a proxy for a rank on attention
  (docs/spec-outcome-menu.md § 'Ranked, not capped'). Half a year
  forever, because the only honest way to stop hearing about an
  outcome is to retire the value it serves.

  ── NO BURIAL WITHOUT A DIAGNOSIS (waymark-8um.4) ──

  Laws v3, law 4: when a high-value card keeps losing, the system
  must first produce a friction diagnosis — an insight proposing a
  recomposition — before the old form may fade. Nothing in this tree
  fades a card (the floor holds, waymark-8um.3), so the one moment a
  composer ACTS on a lost contest is the recomposition itself, and
  that is where the duty is a wall rather than prose:
  `no-burial-without-a-diagnosis` stands at this create door, in
  front of the floor, and refuses a `supersedes` whose prior was
  SHOWN AND DECLINED, or SHOWN AND PASSED OVER until its week ran out,
  unless `diagnosis_id` names an insight that cites the prior in its
  evidence and that the house has not dismissed. The diagnosis IS an
  insight — law 4's own word, and the composer's leash already holds
  `insight.create` — so it cards in decide, the house may take or
  dismiss it, and a dismissal with a quick reason teaches again.

  WHAT MAKES 'SHOWN' A FACT: the decline itself for a declined prior
  (a person read the card and answered it), and `feed_view` rows for
  an expired one. A prior never on a recording screen recomposes
  freely — that teaches the composer about the RANK, not about the
  house (waymark-1uv.4's distinction, landed here before the cap comes
  off) — and so does one whose exposure nobody was keeping a record
  of: the wall reads what the record holds and never guesses past it.
  `GET /api/-/diagnosis` is the document this wall points at.

  ── NOT A TWIN (waymark-8gc) ──

  One standing bundle per evidence row. `not-a-twin` reads the
  offered and accepted outcomes of EVERY composer and refuses a
  candidate that cites a row one of them already cites, naming that
  outcome's address and the shared address in the sentence. It is
  exact address overlap and nothing cleverer — no goal-text
  similarity, which is a judgment a door cannot make honestly.

  Two things it deliberately does not touch. A candidate carrying a
  `request_id` passes whatever it cites: a member asked for it by
  hand, and a person's pull is not refused for resembling something
  the house already holds. And an outcome's OWN value address is
  subtracted from both sides before the sets meet, because
  `value_id` is a declared field — treating it as a shared reading
  would make this wall one standing outcome per value, which is the
  cap waymark-1uv.3 removed rather than the dedupe law 1ag argued
  for.

  ── AND NOT COMPOSED OUT OF A CLOSED BOOK (waymark-euj) ──

  A sitting staged a Saturday that was already over — a goal about
  August 23rd, staged on the 28th, citing one mirrored task whose
  status said `done`, with a piece that prioritized that finished
  task. Every wall above passed it, because each was asking a
  different question: the address was real, the value was held, and
  nothing standing cited that task PRECISELY BECAUSE it was finished.

  `composes-from-what-stands` asks the missing one: is anything the
  composer read still open? At least one cited row must STAND, and
  what standing means is read off the kind rather than listed here —
  the value and person and outcome vocabularies this file already
  keeps, then `feed/work-over?` (the kind's own `:over`, or its
  terminal states), then the clock for a kind with no ending to
  declare. A row it cannot classify STANDS: the wall never guesses
  past what it can read. The refusal names every closed row and the
  word it is closed with, so one round trip fixes the bundle.

  The bundle's own value is subtracted first, as `not-a-twin`
  subtracts it, which means citing the value ALONE does not satisfy
  this wall. Serving a value is not the same act as reading the
  house.

  ── AND STAGED BEHIND A BUTTON THAT IS THERE (waymark-euj) ──

  The same specimen's piece named a real row and a real door, in a
  state that door leaves from — and `prioritize` on a task the house
  finished is a tap nobody would ever make. `the-door-is-open-now`
  asks the engine, not itself: `render/action-availability` is the
  envelope's own `actions`/`unavailable` partition asked one door at
  a time, so the wall admits exactly what the household's screen would
  render and quotes the door's own unavailable reason when it would
  not. That is the ns's rule about second opinions kept rather than
  broken — the wall does not predict another kind's guards, it runs
  them the way the screen does.

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

  ── AND NEITHER IS THE COMPANION (waymark-jfv.11) ──

  A composer read this house's record, found a woodworking build, found
  a caregiver's name in the same neighbourhood of rows, and composed
  `build the finger-joint box with him` — a Saturday afternoon with a
  son. HE IS A GRANDPARENT'S CNA. Every row it read was correct; the
  relationship it assembled out of them was invented, because
  relationships were nowhere in the record.

  So `companion_id` is a CHECKED ref into the `person` roster, with the
  `value_id`/`value_name` label doctrine one field over, and
  `names-a-person` at the create door. Absent is allowed and is the
  common case — Grandpa's paperwork is nobody's afternoon but the
  owner's, and a door that demanded a companion would teach the
  composer to invent one, which is the bug. What the wall checks is
  that the companion EXISTS and that this house currently HOLDS them:
  an `observed` guess is refused (the composer may write a person down;
  it may not compose against its own unanswered reading of who somebody
  is) and so is a `past` one — the bead's own finding, where a dropped
  cleaning cluster still names a CNA who left.

  WHAT IT DOES NOT CHECK IS FIT, and the honesty of saying so is the
  whole of the v1 scope. Whether a CNA is the right person for a box in
  the shop is a judgment, not a predicate, and a guard that pretended
  otherwise would be a second opinion about the household's own life —
  the same refusal `the-prepared-input-fits-the-door` makes about
  another kind's guards. The relation is on the person's row for the
  composer to READ, the wall's own `:open` sentence says to read it,
  and holding a composer to it is waymark-jfv.5's contract.

  ── THE TARGET WAS AN ENUM AND IS NOW INSPECTABLE (waymark-jfv.9) ──

  jfv.3 closed `target_kind` to `[:task :event]` and `target_action`
  to `:create`, implicitly and only, and the argument was `insight`'s:
  'an insight's target is data chosen by its author — no `:touches`
  could advertise it, and no grant would re-gate it'. The owner ruled
  otherwise, and the ruling is the law this kind now runs under:

    A piece can do whatever it wants, but I just need to be able to
    inspect the impact — what it's actually going to do.

  So the wall is gone and THREE CONSTRAINTS stand where it stood, none
  of them about what a piece is allowed to name:

  1. THE IMPACT LINE, written by the engine at staging (`impact`,
     `feed/piece-impact`) — one arm per FORM, every word derived from
     the target's own declaration: the door by its own label, the row
     by its own `:label-template`, the move by the action's `:to`,
     what it carries by the prepared body. The composer cannot reach
     a word of it. It is the thing the ruling actually asked for.
  2. THE PREPARED INPUT JUDGED AGAINST THE TARGET'S OWN DOOR at
     staging — the create model for a create, the named action's own
     `:input` for an invoke, read off `ctx :rdef-of` rather than
     copied, in that door's own three steps and order.
  3. THE TARGET'S OWN GUARDS JUDGING AGAIN AT THE TAP, as the member,
     inside the transaction, behind a version fence.

  TWO FORMS AND NOT THREE. `create` births a row, `invoke` moves one
  through its own named door — and UPDATE is not a third, because in
  this framework a rewording IS an action (`revise`, `restate`), so an
  edit is the invoke arm naming a wording door. Said out loud in
  `forms` rather than left to be rediscovered.

  ── VALIDATED AT STAGING, JUDGED AGAIN AT THE TAP ──

  `the-prepared-input-fits-the-door` runs the prepared input through
  the very door it will knock on at staging, exactly as that door
  would: decode, defaults, closed errors. That is 0k4's
  letter-addressing lesson — a button that fails is worse than a
  button that was never offered — and for the invoke form
  `the-row-it-names-is-there` adds the other half of it: the row is
  really here, and the door it names is one that row can walk through
  today.

  The world still moves between staging and the tap: the Wednesday
  slot fills, the list is thrown away, the authority conflicts the
  row, somebody completes the task the piece was going to reopen. Two
  answers, and they are different questions:

  - THE TARGET'S OWN GUARDS JUDGE AT THE TAP, inside the transaction,
    and their refusal is what the household reads. What is deliberately
    NOT built here is a second staleness oracle: a wall that tried to
    predict another kind's guards would be a second opinion about that
    kind's law, and it would be wrong first.
  - AND FOR AN INVOKE, THE VERSION FENCE. That one is not a second
    opinion about anybody's law; it is a fact about THIS row's own
    sentence — the impact line describes a target as it stood at
    staging, so `the-target-has-not-moved` refuses when it has, naming
    the drift, and `materialize` hands the target its own etag on top.

  The way out of either is two taps — `Not this` on the stale piece,
  `Make it so` again.

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
                                   defscenario unless-granted]]
            [waymark10.guards :as g]
            [waymark10.schema :as schema]
            ;; the impact line's one derivation (waymark-jfv.17), which
            ;; lives beside `outcome-says` because it is the OTHER
            ;; engine sentence on this same card and both callers —
            ;; this door at staging, the population at the read — have
            ;; to run the identical function or a stored line and a
            ;; derived one would drift. `waymark10.recipe_proposal`
            ;; reaches for `feed/recipe-diff` from a resource ns for
            ;; the same reason, one kind over.
            [waymark10.server.feed :as feed]
            ;; the ONE fact about this engine an open piece has to know
            ;; (waymark-jfv.9): which doors keep half their work outside
            ;; the transaction. Read by name so the wall and the effect
            ;; cannot drift — see `the-door-carries-its-own-effect`.
            [waymark10.server.grants :as grants]
            ;; the fence's HTTP half, spelled the way an honest client
            ;; spells it — `recipe_proposal/apply-the-order`'s own
            ;; reach, one kind over
            [waymark10.server.invoke :as inv]
            ;; how the ENGINE judges whether a door is open on a row —
            ;; the envelope's own `actions`/`unavailable` partition,
            ;; asked one door at a time (waymark-euj). A piece is
            ;; staged behind a button, so the wall that checks the
            ;; button reads the same code the screen does rather than
            ;; forming a second opinion about another kind's guards.
            [waymark10.server.render :as render]
            [waymark10.types :as t]
            [workqueue10.resources.tickler :as tickler])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

;; ── what a piece may become ─────────────────────────────────────────

(def forms
  "THE TWO HONEST SHAPES A PIECE'S TARGET WEARS (waymark-jfv.9), and
  the third one is a mirage this def exists to say so about.

  - `create` — the piece BIRTHS a row. jfv.3's whole world: a task
    joins the queue, a hold joins the calendar, and the target has no
    id because it does not exist yet.
  - `invoke` — the piece MOVES a row that already stands, through
    that row's own named door: `{target_kind, target_id,
    target_action, prepared}`. `buy the box stock` becomes
    `grocery_list.add_item` on the list the house is already
    carrying; `reopen the dropped task` becomes that task's own door.

  AND UPDATE IS NOT A THIRD FORM, which was weighed here rather than
  left to be discovered. An edit looks like its own shape and is not
  one: in this framework a rewording is an ACTION — `revise`,
  `restate`, `set_priority` — declared on the kind with its own
  `:input`, its own guards and its own `:to`. So `update` IS invoke
  naming a wording door, and giving it a form of its own would have
  bought a second spelling for one law and a second arm for the impact
  line to drift in.

  The set is closed and short because a FORM is a shape of THIS row,
  which a reader can enumerate. What is deliberately not closed any
  more is what the form points AT — see `target_kind` below."
  ["create" "invoke"])

(def form-enum
  "The schema form `form` wears — a real closed enum, so the demand
  class is `selection` and a form renders a picker rather than a blank
  box."
  (into [:enum] forms))

(def advertised-creates
  "The kinds whose CREATE door `take` still advertises in `:touches`.

  THIS USED TO BE A WALL AND IS NOT ONE ANY MORE. jfv.3 declared
  `materializable [:task :event]` as a closed enum on `target_kind`,
  and the argument for it was good: `insight` had recorded the one
  cross-write no declaration could name, and closing the KIND made
  'only the input is data' true. waymark-jfv.9's ruling took it down,
  in the owner's own words:

    A piece can do whatever it wants, but I just need to be able to
    inspect the impact — what it's actually going to do.

  So the wall came off and three constraints stand in its place: the
  IMPACT LINE the engine writes at staging, the prepared input judged
  against the target's OWN door at staging, and the target's own
  guards judging again at the tap, as the member, behind a version
  fence. What is left here is ADVERTISEMENT: the two kinds a piece
  ordinarily births, kept so `:touches` says something true and
  `checks_assembly/check-touches` can verify it at assembly.

  It is honestly INCOMPLETE and that is written down rather than
  papered over — see `touched-creates`."
  [:task :event])

(def touched-creates
  "The advertised blast radius of a `take`, and the honest statement of
  what it cannot name.

  `:touches` is a DECLARATION-time advertisement: `check-touches`
  refuses an entry naming a door that does not exist, and the
  conformance pack's `:core/touches` asks that every declared touch
  actually fired. Neither direction can help an OPEN piece, because an
  open piece's target is chosen by its author at staging — which is
  waymark-iqa.6's own refused primitive, arriving from the other side.
  The framework has no spelling for a dynamic touch (`resource.clj`
  admits exactly `:kind`, `:action`, `:may`), and the one precedent for
  a handler whose writes vary — `worksheet`'s `apply`, which replays
  arbitrary lines through arbitrary targets — declares NO `:touches` at
  all and carries its blast radius in `:safety :consequence` prose.

  So this is the middle: the two create doors a piece ordinarily walks
  through, each `:may true` — because a given tap walks through ONE of
  them, or through neither when the piece is an invoke — plus the
  honest statement, said in the two places a household actually reads
  and in the one a machine does:

  1. the `:safety :one-way` on `take`, in the household's own words;
  2. the IMPACT LINE on the row, which names the exact kind, the exact
     door and the exact row this particular tap reaches — the per-ROW
     blast radius the per-ACTION declaration cannot carry;
  3. `target_kind` / `target_action` / `target_id` as ordinary fields
     on the wire, so a client reads the pair without parsing prose.

  A piece that creates or invokes some other kind is LAWFUL and simply
  unadvertised here. That is the cost of the ruling, stated rather
  than hidden."
  (mapv (fn [k] {:kind k :action :create :may true}) advertised-creates))

(defn- form-of
  "Which shape this piece's target wears, read off the row. A piece
  born before the field existed is a `create`, because that is what
  every piece was."
  [d]
  (if (= "invoke" (some-> (:form d) str str/trim not-empty))
    :invoke
    :create))

;; ── the leash and the ceiling, as household numbers ─────────────────
;;
;; NO CAP ON STAGING LIVES HERE ANY MORE (waymark-1uv.3, 2026-08-26).
;; `weekly-cap` — two a week per composer — and its guard
;; `outcomes-are-few` stood at the create door from jfv.3 to 1uv.2,
;; and the whole of their argument was "so the composer must rank".
;; That is a rank on attention asking a wall to do its job (the
;; epic's clause 4), and the crown's declared rank (feed/default-
;; crown-rank, waymark-1uv.2) now does it where the household can read
;; it: the machine writes without limit, the rank chooses which
;; bundles fill the crown's slots, and an unanswered row teaches the
;; composer nothing an unshown one would not, because the view-event
;; record tells NEVER SHOWN from PASSED OVER. The person's own pull
;; (`request_id`, waymark-jfv.20) survives the cap it was written to
;; get past as the rank's first tier, and `the-request-is-open` still
;; checks the citation. docs/spec-outcome-menu.md § 'Ranked, not
;; capped' and § 'Built — 1uv.3' carry the ruling and the reasons.

(def leash-days
  "How long a staged outcome stands before the house stops being
  asked. SEVEN, and the number is the section's own sentence: the
  crown of the feed says THIS WEEK COULD HOLD, and an outcome still
  asking on the eighth morning is describing a week that is over.
  Engine-owned — the person who benefits from a short leash is the
  household, and the one filling the form is not."
  7)

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

;; ── the outcome's create walls ──────────────────────────────────────
;;
;; SHAPE FIRST, WORLD NEXT — insight's ordering and its reason: a
;; malformed outcome should hear what is wrong with it before anything
;; about the world it lands in. There is no PACE wall to come last any
;; more (waymark-1uv.3): what a composer may stage is unbounded, and
;; what the household is shown is the crown's rank's to decide.

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
                                          (g/listed bad))}})
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
                                        (g/listed loved))
                                   (str ", and the value you named lists no"
                                        " loved activity at all — which is"
                                        " precisely the high-friction value"
                                        " the whole idea is about. Leave the"
                                        " routing empty and say so."))}}))))))

(def ^:private in-our-lives
  "The person states an outcome may name as a companion — ONE, and the
  narrowness is the decision (waymark-jfv.11).

  waymark-jfv.10 widened `held-states` to admit an OBSERVED value, and
  the reasoning was good there: refusing to compose against a value an
  agent wrote down would have made the owner's ruling a permission with
  nothing behind it, and the card says which. THAT ARGUMENT DOES NOT
  CARRY ACROSS, and the difference is the whole of waymark-jfv.11.

  A wrong value is a wrong sentence about what somebody cares about; a
  wrong PERSON is the exact failure this wall exists to stop. An agent
  may write a person row — that is jfv.10's ruling, honoured — but if
  it could then compose against its own unanswered guess, it could
  still write `the caregiver, son` and build a Saturday on it, and the
  wall would be paper. So an `observed` companion is refused, and the
  refusal names the lawful path: publish an insight, offer that row's
  own tap, and compose with them once the house has answered.

  `past` is refused for the household's reason, and it is the finding
  the bead was filed on: the dropped cleaning cluster of Aug 8–15 is
  assigned to a CNA who left. A plan built around somebody who is gone
  is not a plan that needs pushing harder — it is a staffing change the
  rotation never heard about."
  #{"current"})

(defguardfn names-a-person
  {:judges [:companion_id]
   :reads [:person]
   :vars [:problem]
   :open "A plan may name somebody to do it WITH, and when it does it names a person off this house's roster — one the house has answered and still holds. Read /api/people first: the relation written there is how you learn whether the pairing you are about to compose is one this family would recognise."
   :explain "That is not somebody this house can build an afternoon around: {problem}"}
  [_row inp ctx]
  ;; THE WALL THE MISCOMPOSITION WOULD HAVE HIT. A composer read
  ;; correct rows, found a caregiver's name among them, and paired a
  ;; woodworking Saturday with him as though he were a son — he is a
  ;; grandparent's CNA. Every row it read was right; the relationship
  ;; it assembled was invented, because relationships were nowhere in
  ;; the record.
  ;;
  ;; What this guard can honestly check is that the companion EXISTS
  ;; and that this house currently holds them. It cannot check that the
  ;; pairing FITS — whether a CNA is the right person for a box in the
  ;; shop is a judgment, not a predicate — and pretending otherwise
  ;; would be a second opinion about the household's own life. So the
  ;; relation is on the row for the composer to read and for jfv.5's
  ;; contract to be held to, and the :open sentence says where to look.
  (let [read' (:read ctx)
        pid (some-> (:companion_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      ;; ABSENT IS ALLOWED, ON PURPOSE, and it is the common case.
      ;; Grandpa's paperwork is nobody's afternoon but the owner's, and
      ;; a door that demanded a companion would teach the composer to
      ;; invent one — which is the precise bug this wall is for.
      (nil? pid) (t/allow)
      (nil? read') (t/allow)

      :else
      (let [row (read' :person pid)
            nm (pr-str (str (get-in row [:data :name])))
            rel (str (get-in row [:data :relation]))]
        (cond
          (nil? row)
          (deny (str "this house has nobody at " pid
                     " — read /api/people and name one of those. If the"
                     " person is real and simply not written down, write"
                     " them down first: their name, and how they relate"
                     " to this house in the household's own words."))

          (= "observed" (name (:state row)))
          (deny (str nm " is in the roster as observed — somebody wrote"
                     " the name down (\"" rel "\") and nobody here has"
                     " answered yet. A week is not spent on the strength"
                     " of a guess about who somebody is. Publish an"
                     " insight, offer that row's own \"yes — still with"
                     " us\", and compose this once the house has said so."))

          (not (in-our-lives (name (:state row))))
          (deny (str nm " is past — " rel ", and not one now. A plan may"
                     " not be built around somebody who has left; if this"
                     " house still has work standing in their name, that"
                     " is a finding about the rotation rather than an"
                     " afternoon to offer."))

          :else (t/allow))))))

(defn- cites?
  "Does an evidence vector name this outcome's address? Trimmed and
  exact — an address is the one shape a citation wears (`row-address`)."
  [evidence oid]
  (let [want (str "/api/outcomes/" oid)]
    (boolean (some #(= want (str/trim (str %))) evidence))))

(defguardfn no-burial-without-a-diagnosis
  {:judges [:diagnosis_id]
   :reads [:outcome :feed_view :insight]
   :vars [:problem]
   :open "A recomposition of an outcome the house was shown and turned down — declined, or left to lapse after it had been on a recording screen — names a diagnosis first: an insight citing the prior outcome in its evidence, still standing. A prior never shown, or one nobody was keeping a record of, recomposes freely; that taught you about the rank, not about the house. Read /api/-/diagnosis before you recompose."
   :explain "No burial without a diagnosis: {problem}"}
  [_row inp ctx]
  ;; LAW 4, AS A WALL (waymark-8um.4). It stands IN FRONT of the floor
  ;; on purpose — the epic's own sentence is that the composer's duty
  ;; fires first — so a composer that comes back the next morning with
  ;; no diagnosis hears about the diagnosis, and only a composer that
  ;; has one hears about the date. The floor's own refusal already
  ;; says "diagnose the friction in the meantime"; this is that
  ;; sentence made structural.
  ;;
  ;; The prior's absence or openness is left to the floor wall, whose
  ;; sentences those are; this wall speaks only about exposure and
  ;; the diagnosis. A `:find` for `feed_view` answers nil on an engine
  ;; assembled without the feed module, and nil reads as UNKNOWN —
  ;; the wall never guesses past what the record holds.
  (let [read' (:read ctx)
        find' (:find ctx)
        sid (some-> (:supersedes inp) str str/trim not-empty)
        did (some-> (:diagnosis_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (nil? read') (t/allow)
      (and (nil? sid) (nil? did)) (t/allow)

      (nil? sid)
      (deny (str "diagnosis_id names /api/insights/" did " but this outcome"
                 " supersedes nothing. A diagnosis is about a prior outcome"
                 " — name the one it diagnoses in supersedes, or cite none."))

      :else
      (let [prior (read' :outcome sid)
            state (some-> prior :state name)
            shown? (case state
                     "declined" true
                     "expired" (when find'
                                 (boolean
                                  (some #(seq (find' :feed_view {:card_id %}
                                                     {:limit 1}))
                                        (feed/card-ids :outcome sid))))
                     false)
            insight (when did (read' :insight did))]
        (cond
          ;; the floor wall's sentences, left to it
          (or (nil? prior) (= "offered" state)) (t/allow)

          (and shown? (nil? did))
          (deny (str "/api/outcomes/" sid " was "
                     (if (= "declined" state)
                       "shown and declined"
                       "on a recording screen and left to lapse")
                     ", and that is a diagnosis to write before a"
                     " recomposition. Read /api/-/diagnosis?outcome=" sid
                     " — how many mornings, answered how, with which"
                     " reasons — publish an insight that cites /api/outcomes/"
                     sid " in its evidence and proposes the smaller step,"
                     " the loved activity, or the better time, and name it"
                     " here as diagnosis_id."))

          (nil? did) (t/allow)

          (nil? insight)
          (deny (str "this house has no insight " did " — read /api/insights"
                     " and name one of those, or publish the diagnosis first."))

          (not (cites? (get-in insight [:data :evidence]) sid))
          (deny (str "/api/insights/" did " does not cite /api/outcomes/" sid
                     " in its evidence. A diagnosis names what it diagnoses;"
                     " one that cites something else is a finding about"
                     " something else."))

          (= "dismissed" (name (:state insight)))
          (deny (str "the house dismissed /api/insights/" did " — read the"
                     " reason it gave, if it gave one, and diagnose again."
                     " A recomposition built on a diagnosis the house said"
                     " was not useful is the old ask in a new hat."))

          :else (t/allow))))))

(defguardfn a-recomposition-waits-its-turn
  {:judges [:supersedes]
   :reads [:outcome]
   :vars [:problem]
   :open "A declined outcome comes back recomposed, not repeated: the one it supersedes exists, and the house has answered it. How soon it comes back is not this door's question — the decline stamps when the house is willing to hear it again, and the crown's rank holds an early recomposition back by every day it is early, on the card, in words."
   :explain "That recomposition does not stand: {problem}"}
  [_row inp ctx]
  ;; TWO ARMS, AND THE THIRD WENT TO THE RANK (waymark-1uv.10). This
  ;; door used to refuse a recomposition staged before the prior's
  ;; `not_before` — the person's decline, stamped as a date, used as a
  ;; wall on the composer's writing. The verdict is real and is still
  ;; honoured; what moved is WHERE: `feed/crown-lift` reads the same
  ;; stamp (and `declined_count` beside it) and cools the recomposition
  ;; until the date, with the card's why saying so. Law 4 wanted the
  ;; row WRITTEN — the diagnosis is the composer's work order, and a
  ;; door that refused it for two months was a wall against the duty.
  ;; The two arms that stay are shape and a dedupe law, not pace:
  ;; there is no such outcome; the outcome is still on offer, so
  ;; recomposing it is asking the same question twice.
  (let [read' (:read ctx)
        sid (some-> (:supersedes inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (nil? sid) (t/allow)
      (nil? read') (t/allow)

      :else
      (let [prior (read' :outcome sid)]
        (cond
          (nil? prior)
          (deny (str "this house serves no outcome " sid
                     " — supersede one that exists, or name none."))

          (= "offered" (name (:state prior)))
          (deny (str "/api/outcomes/" sid " is still on the fridge waiting"
                     " for an answer. Recomposing something nobody has"
                     " declined yet is asking the same question twice."))

          :else (t/allow))))))

;; ── the anti-twin wall (waymark-8gc) ────────────────────────────────
;;
;; THE LAW WAS PROSE AND IS NOW A DOOR. SITTING.md has said it since
;; the composer had a manifest — *never a twin of a standing outcome;
;; a candidate that cites the same evidence row as one already
;; offered or accepted is a twin, and the rank cannot tell twins
;; apart* — and nothing enforced it: a sitting on 2026-08-27 staged
;; duplicates and every one was admitted, because no wall here had
;; ever looked at what ANOTHER outcome cites.
;;
;; It is NOT a cap, and it does not reopen what waymark-1uv's law 1
;; settled (*the machine may write without limit*). A cap refuses the
;; Nth row because it is the Nth; this refuses a row because the
;; house already holds one built on the same reading, whoever wrote
;; it. A duplicate is not indexing — it is one index written twice,
;; and the rank cannot choose between two cards that say the same
;; thing. waymark-1ag's sentence, in its own words: dedupe is a law,
;; not a cap.

(def ^:private standing-states
  "The three states an outcome STANDS in — on the fridge waiting for an
  answer, back with the composer for a re-plan the household asked for
  (waymark-9xn), and answered yes. Those are the ones a twin would sit
  beside. A declined or expired outcome is answered and gone, which
  is why the recomposition case needs no exemption written for it: a
  recomposition re-cites its declined prior's evidence on purpose,
  and the prior is not in this list.

  `iterating` is here for the twin wall's own reason said once more: a
  bundle off the feed for a rework is the LOUDEST kind of standing —
  the household asked for that very goal to be re-planned — so a
  second bundle over the rows it cites is a twin whoever wrote it, and
  the composer that would write it is the one holding the rework."
  ["offered" "iterating" "accepted"])

(def ^:private standing-page
  "How deep the wall reads per standing state. A household's fridge
  holds tens, not thousands; a bounded read that missed the far tail
  of a pathological store would let a twin through rather than refuse
  a bundle over a row it could not see, which is the right way for a
  wall reading a window to be wrong."
  200)

(defn- own-value-address
  "A row's own value, written as the address it would wear in an
  evidence list, or nil.

  SUBTRACTED FROM BOTH SIDES before two bundles are compared, and the
  reason is the difference between citing and serving: `value_id` is
  a declared field, so an outcome that also lists its own value in
  `evidence` has read nothing the field did not already say. Counting
  that as a shared row would make this wall 'one standing outcome per
  value' — a cap on the value, which is precisely the wall
  waymark-1uv.3 took off this door. ANOTHER outcome's value, cited as
  a reading, is an ordinary shared row and still twins."
  [vid]
  (when-some [v (some-> vid str str/trim not-empty)]
    (str "/api/values/" v)))

(defn- read-rows
  "The set of row addresses a bundle actually READ: its evidence,
  trimmed, blank-free, less its own value's address."
  [evidence own-value]
  (into #{}
        (comp (map #(str/trim (str %)))
              (remove str/blank?)
              (remove #(= own-value %)))
        evidence))

(defguardfn not-a-twin
  {:judges [:evidence]
   :reads [:outcome]
   :vars [:standing :shared :state]
   :open "One standing bundle per evidence row. An outcome that is offered, being reworked, or accepted already speaks for the rows it cites — whoever composed it — so a second bundle built on one of those same rows is a twin, and the rank cannot tell twins apart. A person's own pull is the exception: when somebody asked, they get one."
   :explain "That is a twin of a bundle this house is already holding: {shared} is cited by /api/outcomes/{standing}, which is {state}. Read that one — compose from rows nobody has composed yet, or, if its PLAN is what is wrong, leave it standing and let the household's own iterate ask you to rework it in place."}
  [_row inp ctx]
  ;; ONE EXEMPTION, AND IT IS THE PERSON'S: a `request_id` means a
  ;; member asked for this bundle by hand (`the-request-is-open`
  ;; checks the citation itself, right behind this wall), and a pull
  ;; is never refused for looking like something the house already
  ;; holds — the person holds it and asked anyway.
  ;;
  ;; The row named in `supersedes` is left out of the comparison, for
  ;; the same reason it is not this wall's business: a recomposition
  ;; re-cites what its prior cited, on purpose, and WHETHER it may
  ;; replace that prior is `a-recomposition-waits-its-turn`'s question
  ;; and `no-burial-without-a-diagnosis`'s. A declined prior never
  ;; reaches this line anyway (it is not standing); an accepted one
  ;; would, and refusing a recomposition as a twin of the very row it
  ;; names would be this wall answering a question two other walls
  ;; already own.
  (let [find' (:find ctx)
        rid (some-> (:request_id inp) str str/trim not-empty)]
    (cond
      ;; the storage-free probe advertises optimistically, exactly as
      ;; `cites-what-it-read` and `insight/cites-what-it-claims` do —
      ;; the write path always carries the consult
      (nil? find') (t/allow)
      (some? rid) (t/allow)

      :else
      (let [mine (read-rows (:evidence inp)
                            (own-value-address (:value_id inp)))
            sid (some-> (:supersedes inp) str str/trim not-empty)]
        (if (empty? mine)
          (t/allow)
          (let [hit (->> standing-states
                         (into []
                               (comp (mapcat #(find' :outcome {:state %}
                                                     {:limit standing-page}))
                                     (remove #(= sid (str (:id %))))
                                     (map (fn [r]
                                            {:id (str (:id r))
                                             :state (name (:state r))
                                             ;; a VECTOR because the
                                             ;; comparison wants order,
                                             ;; not because `g/listed`
                                             ;; needs one: it seqs
                                             ;; before `distinct` now
                                             ;; (waymark-g4e), so the
                                             ;; set that threw here on
                                             ;; 2026-08-28 is an
                                             ;; ordinary argument
                                             :shared
                                             (into []
                                                   (filter mine)
                                                   (read-rows
                                                    (get-in r [:data :evidence])
                                                    (own-value-address
                                                     (get-in r [:data :value_id]))))}))
                                     (filter (comp seq :shared))))
                         (sort-by :id)
                         first)]
            (if (nil? hit)
              (t/allow)
              (t/deny {:vars {:standing (:id hit)
                              :state (:state hit)
                              :shared (g/listed (:shared hit))}}))))))))

;; ── the closed-book wall (waymark-euj) ──────────────────────────────
;;
;; A GEMINI SITTING ON 2026-08-28 STAGED A SATURDAY THAT WAS ALREADY
;; OVER. The bundle's goal was "Sacrament talk drafted and ready for
;; August 23" — staged on the 28th — and the only row it cited was a
;; mirrored task whose `status` said `done`, with a piece that
;; prioritized that finished task. Every door here passed it:
;; `cites-what-it-read` (the address is real), `names-a-value` (the
;; value is held), `not-a-twin` (nothing standing cites that task —
;; BECAUSE it is finished). The journal said, verbatim, "to satisfy
;; the floor requirement, I staged an outcome". There is no floor.
;;
;; So this wall asks the one question none of the others did: is
;; anything the composer read STILL OPEN? A week cannot be planned out
;; of a finished record, and a bundle whose every citation is closed
;; is not a plan — it is a composer filling in a form from the
;; archive.
;;
;; WHAT "STANDS" MEANS IS READ OFF THE KIND, never guessed. In order:
;;
;; 1. THE KINDS THAT SAY IT HERE. Three vocabularies already stand in
;;    this file, written for other walls and true for this one:
;;    `held-states` (a value this house is holding), `standing-states`
;;    (an outcome on the fridge or said yes to — accepted is terminal
;;    to the MACHINE and live to the household, which is exactly why
;;    the machine's word is the wrong one here), and, for a person,
;;    "not past". That last is deliberately WIDER than
;;    `in-our-lives`: composing a Saturday around an unanswered guess
;;    about who somebody is is `names-a-person`'s refusal, and this
;;    wall is about closed books rather than about affirmation. An
;;    observed person is a book nobody has shut.
;; 2. THE KIND'S OWN ENDING VOCABULARY, for everything else:
;;    `feed/work-over?`, which reads the kind's declared `:over` when
;;    it has one and its terminal states when it does not. That is the
;;    one place the household's word for "finished" lives, and reusing
;;    it means this wall and the archive can never disagree about a
;;    row. It is what catches the specimen: task declares
;;    `:over {:field :status :accomplished #{"done"} :let-go
;;    #{"dropped"}}`, so a done task is over however fresh its sync
;;    state reads.
;; 3. THE CLOCK, for a row whose kind has no ending to declare. An
;;    event's machine is the sync machine and it is never terminal, so
;;    "is this still ahead of us" is a fact about `ends_at` (or
;;    `starts_at` when nothing named an end) against `(:now ctx)`, and
;;    only a real instant is judged.
;; 4. AND ANYTHING ELSE STANDS. A row this house has not got, a kind
;;    with no ending and no clock — the wall never guesses past what
;;    it can read, the same rule `no-burial-without-a-diagnosis` holds
;;    about exposure. A wall that refused on what it could not classify
;;    would refuse honest bundles for a living.
;;
;; The bundle's OWN value address is subtracted first, exactly as
;; `not-a-twin` subtracts it and for the same reason: `value_id` is a
;; declared field, so an outcome that also lists its value under
;; `evidence` has read nothing the field did not already say. The
;; consequence is deliberate — citing the value ALONE does not satisfy
;; this wall, because serving a value is not the same act as reading
;; the house.

(def ^:private still-with-us
  "The person states that are an OPEN book. Wider than `in-our-lives`
  on purpose: `names-a-person` refuses an `observed` companion because
  a week may not be spent on a guess about who somebody is, and that
  is a wall about affirmation. This one is about endings — a person
  nobody has answered yet is not a closed book, and `past` is."
  #{"current" "observed"})

(def ^:private standing-words
  "The kinds whose 'still open' this file already says out loud, in
  the vocabularies other walls here are written against. Everything
  else asks its own declaration (`feed/work-over?`) and then the
  clock."
  {:value  held-states
   :person still-with-us
   :outcome (set standing-states)})

(defn- ending-word
  "The word a finished row is finished WITH, read the way the feed
  reads it: the value of the kind's declared `:over` field when it
  declared one (a mirror's lifecycle is data), the machine state
  otherwise."
  [rd row]
  (let [f (:field (feed/over-vocabulary rd))]
    (if f
      (str (get-in row [:data f]))
      (name (:state row)))))

(defn- past-word
  "What a row says about its own clock when the clock has run out, or
  nil. `ends_at` is the fact; `starts_at` answers for a row that
  named no end. Only a real instant is judged — a kind that spells
  its time some other way is one this wall cannot classify, and an
  unclassifiable row stands."
  [row now]
  (let [inst (fn [k] (let [v (get-in row [:data k])]
                       (when (instance? Instant v) v)))
        ends (inst :ends_at)
        starts (inst :starts_at)]
    (when (instance? Instant now)
      (cond
        (and ends (.isBefore ^Instant ends ^Instant now))
        (str "ended " ends)

        (and (nil? ends) starts (.isBefore ^Instant starts ^Instant now))
        (str "began " starts " and named no end")))))

(defn- closed-word
  "How this address is FINISHED — a short phrase for the refusal's
  sentence — or nil when the row still stands, which includes every
  row this wall cannot honestly classify."
  [rdef-of read' now href]
  (when-some [{:keys [plural id]} (row-address href)]
    (when-some [rd (rdef-of plural)]
      (when-some [row (read' (:kind rd) id)]
        (if-some [open (standing-words (:kind rd))]
          (when-not (open (name (:state row)))
            (str "is " (name (:state row))))
          (if (feed/work-over? rd row)
            (str "is " (ending-word rd row))
            (past-word row now)))))))

(defguardfn composes-from-what-stands
  {:judges [:evidence]
   :reads [:storage]
   :vars [:closed]
   :open "An outcome is composed out of a book that is still open: at least one row it cites is still standing — a task nobody has finished, an event still ahead, a person this house holds, a value it still keeps, a finding nobody has answered. Its own value does not count: that is what the bundle SERVES, and value_id already says it."
   :explain "Nothing this bundle cites is still standing: {closed} A week cannot be planned out of a finished record — read what is still open (/api/tasks?status=open, the calendar ahead of today, the value's own screen) and compose from a row the house still has in front of it."}
  [_row inp ctx]
  (let [read' (:read ctx)
        rdef-of (:rdef-of ctx)
        now (:now ctx)
        deny (fn [closed] (t/deny {:vars {:closed closed}}))]
    (cond
      ;; the storage-free probe advertises optimistically, exactly as
      ;; `cites-what-it-read` and `not-a-twin` do
      (or (nil? read') (nil? rdef-of)) (t/allow)

      :else
      (let [mine (sort (read-rows (:evidence inp)
                                  (own-value-address (:value_id inp))))]
        (cond
          ;; NOTHING BUT THE VALUE (or nothing at all — that one is
          ;; `cites-what-it-read`'s sentence and it speaks first).
          ;; Serving a value is not reading the house.
          (empty? mine)
          (deny (str "it cites nothing but the value it serves, and"
                     " value_id already said that."))

          :else
          (let [words (mapv (fn [href] [href (closed-word rdef-of read' now href)])
                            mine)]
            (if (some (comp nil? second) words)
              (t/allow)
              (deny (str (str/join "; " (map (fn [[h w]] (str h " " w)) words))
                         ".")))))))))

(defn- cited-request
  "The composition request an outcome cites, trimmed, or nil — the one
  spelling the wall and the staging hook both read (waymark-jfv.20).
  Since waymark-1uv.3 nothing counts against it: a citation is the
  rank's first tier (`feed/crown-key`), not a pass through a cap."
  [inp]
  (some-> (:request_id inp) str str/trim not-empty))

(defguardfn the-request-is-open
  {:judges [:request_id]
   :reads [:composition_request :now]
   :vars [:problem]
   :open "An outcome may answer a person's own request for another — and when it does, the request is one this house is holding open: it exists, nobody has answered it, its week has not run out, and if the person named the value it should serve, this outcome serves that value."
   :explain "That request cannot admit this outcome: {problem}"}
  [_row inp ctx]
  ;; THE PERSON'S PULL, CHECKED (waymark-jfv.20). A wall on CITING,
  ;; not on writing, and it stays whole under the epic that removed
  ;; the cap it once stood in front of (waymark-1uv.3): a cited outcome
  ;; still answers the request in the same stroke (`stage-the-outcome`
  ;; invokes the request's own door), and a citation that is not good
  ;; would burn a person's pull on an outcome that answered nothing.
  ;; What the citation BUYS changed — it used to be admission past the
  ;; cap; it is now the crown rank's first tier, above every bundle
  ;; nobody asked for. The four arms are the bead's own list, and the
  ;; second is 'one request, one outcome' seen from the outcome's side
  ;; — the request's `answer` door moved it to `answered` the instant
  ;; the first outcome citing it was staged, so the second meets a
  ;; state rather than a count.
  (let [read' (:read ctx)
        rid (cited-request inp)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (nil? rid) (t/allow)
      (nil? read') (t/allow)
      :else
      (let [r (read' :composition_request rid)
            good (get-in r [:data :good_until])
            aim (some-> (get-in r [:data :value_id]) str str/trim not-empty)
            vid (some-> (:value_id inp) str str/trim not-empty)]
        (cond
          (nil? r)
          (deny (str "this house holds no request " rid
                     " — read /api/composition_requests?state=offered and"
                     " cite one of those, or cite none."))

          (= "answered" (name (:state r)))
          (deny (str "/api/composition_requests/" rid " was already"
                     " answered, by /api/outcomes/"
                     (get-in r [:data :answered_by])
                     ". One request admits one outcome; the person asks"
                     " again with one tap if they want another."))

          (not= "offered" (name (:state r)))
          (deny (str "/api/composition_requests/" rid " is "
                     (name (:state r)) " — the week it was asked in is"
                     " over, and a request the house is no longer"
                     " holding open admits nothing."))

          (and good (not (pos? (compare good (:now ctx)))))
          (deny (str "/api/composition_requests/" rid " ran out at "
                     good ". The person asked about a week that is over;"
                     " if the want survived they will ask again."))

          (and aim (not= aim vid))
          (deny (str "the person asked for an outcome serving /api/values/"
                     aim " and this one serves "
                     (if vid (str "/api/values/" vid) "no value at all")
                     ". A request that names its aim admits only an"
                     " outcome that serves it — the pull was for THAT,"
                     " not for anything."))

          :else (t/allow))))))

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

(defn- fits?
  "One model, one body, the target door's own three steps and their
  order (`invoke/create-in-tx!` and `invoke/invoke-in-tx!` step 7 use
  exactly these): decode, fill declared defaults, refuse unknowns.
  Returns the errors map, or nil when the door would take it."
  [model body]
  (schema/closed-errors model (schema/apply-defaults
                               model (schema/decode model body))))

(defguardfn the-prepared-input-fits-the-door
  {:judges [:form :target_kind :target_action :target_id :prepared]
   :reads [:storage]
   :vars [:target :problems]
   :open "A piece is judged against the very door it will knock on, read off the registry rather than copied — a create against that kind's create model, an invoke against that action's own input model — decoded, defaulted and closed, exactly as that door does it."
   :explain "That is not something {target} would take, so nobody could ever tap it: {problems}"}
  [_row inp ctx]
  ;; WIDENED BY waymark-jfv.9 FROM ONE ARM TO TWO. jfv.3 judged a
  ;; create body against a create model, which was the whole of the
  ;; world while `target_action` was `:create` implicitly and only.
  ;; With the enum gone, this wall is one of the three things standing
  ;; where it stood — and the load-bearing half of it is that BOTH arms
  ;; read the model off `ctx :rdef-of` rather than copying it, so a
  ;; staged piece cannot drift away from the door it is about.
  (let [rdef-of (:rdef-of ctx)
        k (some-> (:target_kind inp) str str/trim not-empty)
        action (some-> (:target_action inp) str str/trim not-empty)
        prepared (:prepared inp)
        invoke? (= :invoke (form-of inp))
        deny (fn [target problems]
               (t/deny {:vars {:target target :problems problems}}))]
    (if (nil? rdef-of)
      ;; the storage-free probe again — no registry, no verdict
      (t/allow)
      (let [rd (some-> k rdef-of)]
        (cond
          (nil? rd)
          (deny (or k "that") "this house serves no such kind at all.")

          (not (map? prepared))
          (deny k (str "a piece carries the input its door will take, as"
                       " an object — this one carries "
                       (pr-str prepared) "."))

          (not invoke?)
          ;; THE CREATE ARM, jfv.3's, unchanged: the target kind's own
          ;; create model, the same value its door validates against
          (if-some [errs (fits? (or (:create-schema rd) (:schema rd)) prepared)]
            (deny (str k "'s own create door") (pr-str errs))
            (t/allow))

          ;; ── THE INVOKE ARM ──
          (nil? action)
          (deny k (str "an invoke piece names the DOOR it will knock on."
                       " Read /api/" (:plural rd) " and take the action's"
                       " own name off a row's envelope."))

          (str/blank? (str (:target_id inp)))
          (deny (str k "." action)
                (str "an invoke piece names the ROW it will move, by id."
                     " A door with nothing behind it is a create wearing"
                     " somebody else's clothes — say form \"create\" and"
                     " mean it."))

          :else
          (let [adefn (get-in rd [:actions (keyword action)])]
            (cond
              (nil? adefn)
              (deny (str k "." action)
                    (str "that kind has no such door. The ones it has are "
                         (g/listed (map name (keys (:actions rd)))) "."))

              ;; ctx :invoke refuses a bulk action by name — its row
              ;; form does not exist — so a piece naming one could
              ;; never be tapped
              (:bulk adefn)
              (deny (str k "." action)
                    (str "that is a collection door rather than a row's,"
                         " so it has no row form to tap."))

              (nil? (:input adefn))
              (if (seq prepared)
                (deny (str k "." action)
                      (str "that door takes no input at all, and it refuses"
                           " a body rather than ignoring one — prepare {}."))
                (t/allow))

              :else
              ;; THE TARGET ACTION'S OWN INPUT MODEL, the same value
              ;; `invoke-in-tx!` step 7 validates against
              (if-some [errs (fits? (:input adefn) prepared)]
                (deny (str k "." action) (pr-str errs))
                (t/allow)))))))))

(defguardfn the-door-carries-its-own-effect
  {:judges [:form :target_kind :target_action]
   :reads [:storage]
   :vars [:door]
   :explain "{door} is a door whose work is not finished when the row moves: this engine lands the rest of it at the wire boundary, after the transaction, and a tap fired from a piece never reaches out there. The row would go terminal and the thing it was FOR would never happen — so the piece is refused here rather than half-kept later. Answer that one on its own screen."}
  [_row inp _ctx]
  ;; NOT A CAPABILITY WALL, and the difference is the whole reason this
  ;; guard is allowed to exist under waymark-jfv.9's ruling. Governance
  ;; doors are not walled off here — an approval, a grant, a value's
  ;; own affirmation are all a member's to tap through a piece, and
  ;; their own guards judge. What this refuses is one FACT ABOUT THIS
  ;; ENGINE: `grants/approval-effects!` mints the approved ask's grant
  ;; POST-COMMIT, at the router's boundary, so a piece-fired approve
  ;; would move the ask to `approved` — terminally — and mint nothing.
  ;;
  ;; The set is read off `grants/wire-boundary-effects` rather than
  ;; spelled here, so the wall and the effect cannot drift, and
  ;; waymark-442.14 (move the effect into the verdict handler) empties
  ;; the set and dissolves the wall with it.
  (let [k (some-> (:target_kind inp) str str/trim not-empty)
        a (some-> (:target_action inp) str str/trim not-empty)]
    (if (and (= :invoke (form-of inp)) k a
             (contains? grants/wire-boundary-effects
                        [(keyword k) (keyword a)]))
      (t/deny {:vars {:door (str k "." a)}})
      (t/allow))))

(defguardfn the-row-it-names-is-there
  {:reads [:storage]
   :vars [:problem]
   :open "An invoke piece is staged against a row that exists and a door that row can actually walk through today — the letter-addressing lesson, one kind over: a button that fails is worse than a button that was never offered."
   :explain "That piece could not be tapped as it stands: {problem}"}
  ;; NO :judges, and the absence is forced the same way
  ;; `the-outcome-is-still-open`'s is: this wall reads a ROW in another
  ;; kind's collection, which is not one of this door's input fields
  ;; being judged — the id is, and `the-prepared-input-fits-the-door`
  ;; is where the id's SHAPE is refused. Here the subject is the row.
  [_row inp ctx]
  (let [read' (:read ctx)
        rdef-of (:rdef-of ctx)
        k (some-> (:target_kind inp) str str/trim not-empty)
        tid (some-> (:target_id inp) str str/trim not-empty)
        action (some-> (:target_action inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (or (not= :invoke (form-of inp)) (nil? read') (nil? rdef-of)
            (nil? k) (nil? tid) (nil? action))
      (t/allow)
      (let [rd (rdef-of k)
            adefn (get-in rd [:actions (keyword action)])
            row (when rd (read' (:kind rd) tid))]
        (cond
          (nil? rd) (t/allow)                ; the shape wall's sentence
          (nil? adefn) (t/allow)             ; likewise
          (nil? row)
          (deny (str "this house has no " k " " tid " — read /api/"
                     (:plural rd) " and name one of those."))

          (not (contains? (:from adefn) (keyword (name (:state row)))))
          (deny (str "/api/" (:plural rd) "/" tid " is "
                     (name (:state row)) " today, and " k "." action
                     " leaves from "
                     (g/listed (map name (:from adefn)))
                     ". A piece staged against a row that has already"
                     " moved past its door is one nobody can answer."))

          :else (t/allow))))))

(defguardfn the-door-is-open-now
  {:reads [:storage]
   :vars [:door :reason]
   :open "An invoke piece is staged behind a button, so the button has to be there: the target action is judged AVAILABLE on that row now, by the same code the row's own envelope uses — its guards, in their own order, over the real row. A door shut against YOUR hand is not shut — a member taps, not you — but one the row's own law shuts is. Read the target's envelope and stage against a door its actions actually offer."
   :explain "{door} is not open on that row: {reason} A piece behind a shut door is one nobody could ever tap, so it is refused at staging rather than left on the crown to fail."}
  ;; NO :judges, for `the-row-it-names-is-there`'s reason exactly: the
  ;; subject is a row in another kind's collection, not a field of
  ;; this door's input.
  ;;
  ;; WHY IT IS NOT A SECOND OPINION (waymark-euj). The ns docstring
  ;; says, and means, that a wall predicting another kind's guards
  ;; would be wrong first — so this one does not predict them. It
  ;; calls `render/action-availability`, which IS the envelope's
  ;; partition asked one door at a time: same guard order, same
  ;; law resolution, same empty-admission narration, and the refusal
  ;; quotes THAT DOOR'S OWN unavailable reason rather than a sentence
  ;; written here. If the household's screen would render the button,
  ;; this admits the piece; if the screen would print a reason instead,
  ;; that reason is what the composer reads.
  ;;
  ;; WHAT IT STILL DOES NOT DO IS PREDICT THE TAP. The world moves
  ;; between staging and Saturday, and the target's own guards judge
  ;; again inside the transaction with `the-target-has-not-moved`
  ;; beside them. This closes only the case where the door was ALREADY
  ;; shut at staging — the specimen's `prioritize` on a finished task —
  ;; which is not a race and never was.
  ;;
  ;; THE PROBE'S CTX IS THE RENDER PROBE'S, built the way
  ;; `render/envelope` builds it: the composer's own principal, this
  ;; write's clock and services, the read hooks, and `:mode :probe`.
  ;; A create guard's ctx already carries no pen (`invoke/guard-ctx`
  ;; strips :invoke/:create), and `:within` is dropped because the
  ;; door being probed was not opened by anybody — nobody is tapping
  ;; yet, and a guard reading `:within` should hear the truth.
  ;;
  ;; AND THE HAND IS NOT THE COMPOSER'S, WHICH IS THE ONE CORRECTION
  ;; THIS WALL OWES ITSELF. The probe can only ask as the principal it
  ;; has — the composer — while the tap will be a MEMBER's. So a
  ;; denier that declares `:reads [:principal]` is refusing THIS HAND
  ;; rather than shutting the door: `g/role`, `g/not-the-field`, the
  ;; four-eyes and actor-type walls. This wall stands down for those,
  ;; every one of them, because refusing a piece because the composer
  ;; could not tap it would refuse the household its own Saturday —
  ;; a plan a member may lawfully take is precisely what a composer is
  ;; for. `outcome_piece.take` runs the target's guards again, as the
  ;; member, and THAT is where a hand is judged.
  ;;
  ;; A HIDDEN door is left to `the-prepared-input-fits-the-door`, whose
  ;; sentence about a door this kind does not have is the honest one:
  ;; concealment means the action does not exist for this principal,
  ;; and narrating "it is hidden from you" would be the leak the hide
  ;; flag exists to prevent.
  [_row inp ctx]
  (let [read' (:read ctx)
        rdef-of (:rdef-of ctx)
        k (some-> (:target_kind inp) str str/trim not-empty)
        tid (some-> (:target_id inp) str str/trim not-empty)
        action (some-> (:target_action inp) str str/trim not-empty)]
    (if (or (not= :invoke (form-of inp)) (nil? read') (nil? rdef-of)
            (nil? k) (nil? tid) (nil? action))
      (t/allow)
      (let [rd (rdef-of k)
            row (when rd (read' (:kind rd) tid))
            probe (t/ctx {:principal (:principal ctx)
                          :now (:now ctx)
                          :services (:services ctx)
                          :mode :probe
                          :read read'
                          :find (:find ctx)})
            verdict (when row (render/action-availability rd action row probe))
            about-the-hand? (boolean
                             (some #{:principal}
                                   (:reads (:denier verdict))))]
        (if (and (= :unavailable (:status verdict)) (not about-the-hand?))
          (t/deny {:vars {:door (str "/api/" (:plural rd) "/" tid " · " action)
                          :reason (str (:reason verdict))}})
          ;; no row, no such door, hidden, open, or shut against the
          ;; composer's own hand: every one of those is another wall's
          ;; sentence, another principal's question, or an admission
          (t/allow))))))

(defguardfn the-target-has-not-moved
  {:reads [:storage]
   :vars [:problem]
   :open "The impact line a person taps under describes the row as it stood when the piece was staged. A target that has moved since is refused BY NAME, never quietly written over."
   :explain "That row has moved since this was staged — {problem} The way through is two taps: not this on the stale piece, and ask for it again against what the house reads now."}
  [row _inp ctx]
  ;; THE FENCE, AND WHY IT IS A GUARD RATHER THAN ONLY AN :if-match.
  ;; `materialize` DOES hand the target its own etag (recipe_proposal's
  ;; spelling, and 0k4's rule that a cross-write supplies the fence
  ;; rather than waiving it) — but `invoke-in-tx!` step 6 consults it
  ;; only when the TARGET action declares `:safety :fence`, which in
  ;; this framework is implied by an `:edit` and absent everywhere
  ;; else. So a `complete`-shaped door would have taken the stale tap
  ;; without a word. This wall is the half that always fires, and it is
  ;; `recipe_proposal/the-order-has-not-moved` generalized past one
  ;; kind: the staged version, the current version, and a refusal that
  ;; names the drift.
  (let [d (:data row)
        read' (:read ctx)
        rdef-of (:rdef-of ctx)
        k (some-> (:target_kind d) str str/trim not-empty)
        tid (some-> (:target_id d) str str/trim not-empty)
        staged (:target_version d)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (or (not= :invoke (form-of d)) (nil? read') (nil? rdef-of)
            (nil? k) (nil? tid))
      (t/allow)
      (let [rd (rdef-of k)
            cur (when rd (read' (:kind rd) tid))]
        (cond
          (nil? rd) (t/allow)

          (nil? staged)
          (deny (str "this piece carries no version of /api/"
                     (:plural rd) "/" tid " to have been staged against,"
                     " so nothing here can promise the sentence on the"
                     " card is still true."))

          (nil? cur)
          (deny (str "/api/" (:plural rd) "/" tid " is not there any more."))

          (not= (long staged) (long (:version cur)))
          (deny (str "/api/" (:plural rd) "/" tid " was at v" staged
                     " when this was staged and is at v" (:version cur)
                     " now — it reads " (name (:state cur)) " today."))

          :else (t/allow))))))

(def ^:private open-to-a-piece
  "The outcome states a piece may be STAGED under (waymark-9xn). Two,
  and the second is the point of the state: while a bundle is
  `iterating` the composer is staging exactly the replacements the
  household asked for, so the create door is where a new piece
  belongs — a wall that read `offered` alone would have refused the
  rework it invited. What a piece may be TAPPED under is a narrower
  question and a different wall (`the-bundle-is-taking-answers`),
  because a bundle in the composer's hands is not asking anybody
  anything."
  #{"offered" "iterating"})

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

          (not (contains? open-to-a-piece (name (:state o))))
          (deny (str "/api/outcomes/" (:id o) " is "
                     (name (:state o)) " now — the house has already"
                     " answered it."))

          (and good-until (not (pos? (compare good-until (:now ctx)))))
          (deny (str "the week /api/outcomes/" (:id o) " was for ran out at "
                     good-until ". Compose it again against the week the"
                     " house is actually having."))

          :else (t/allow))))))

(defguardfn the-bundle-is-taking-answers
  {:reads [:outcome]
   :vars [:problem]
   :open "A piece is answered while its bundle is on the fridge asking. A bundle the household sent back for a re-plan is off the feed until the composer answers, and its pieces are the very thing being revised — so nothing under it is tappable while the plan is in the composer's hands."
   :explain "There is nothing to answer here yet: {problem}"}
  ;; NO :judges, `the-outcome-is-still-open`'s reasoning exactly: the
  ;; subject is the parent ROW, named through :reads, and `take` takes
  ;; no input for a :judges to grade.
  ;;
  ;; IT DENIES ONE STATE AND NO OTHER (waymark-9xn). Answered, lapsed
  ;; and orphaned bundles are the older wall's sentence and it stands
  ;; in front of this one; what this adds is the case that wall now
  ;; lets through on purpose, because the composer has to be able to
  ;; stage the replacements: a bundle mid-rework, whose pieces are
  ;; half-withdrawn and half-new, and whose owner asked for exactly
  ;; that.
  [row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [o (outcome-of row inp ctx)]
      (if (and o (= "iterating" (name (:state o))))
        (t/deny {:vars {:problem
                        (str "/api/outcomes/" (:id o) " is being reworked —"
                             " you said the plan was wrong and the composer"
                             " has not answered yet. Taking this piece now"
                             " would land a step of the very plan you sent"
                             " back. The bundle returns to the fridge, with"
                             " revised pieces, the moment the composer"
                             " commits its rework.")}})
        (t/allow)))))

(defguardfn a-bundle-is-small
  {:judges [:outcome_id]
   :reads [:outcome_piece]
   :vars [:ceiling]
   :open "Two to five pieces. A bundle tracks what the world can actually hold — a Saturday afternoon, not a whole week."
   :explain "That outcome already carries {ceiling} pieces, which is a week rather than an afternoon. Whatever else this was going to be belongs in its own outcome, and the cap is what makes you choose."}
  [row inp ctx]
  ;; THE CAP COUNTS WHAT IS ON OFFER, NOT WHAT WAS WITHDRAWN
  ;; (waymark-9j2). The ceiling is a claim about the world — how many
  ;; things one afternoon can hold — so a piece the household declined,
  ;; the week mooted, or the composer REWORKED away is not one of them,
  ;; and it must not spend the slot the replacement needs. Before the
  ;; iterate loop a piece under an offered outcome was always offered,
  ;; so the `:state "offered"` filter changes no answer that stood then;
  ;; it is what lets replace-in-place stay under five across rounds.
  (if (nil? (:find ctx))
    (t/allow)
    (let [oid (some-> (or (get-in row [:data :outcome_id])
                          (:outcome_id inp))
                      str str/trim not-empty)
          siblings (when oid
                     ((:find ctx) :outcome_piece
                      {:outcome_id oid :state "offered"}
                      {:limit (inc (long bundle-ceiling))}))]
      (if (< (count siblings) (long bundle-ceiling))
        (t/allow)
        (t/deny {:vars {:ceiling bundle-ceiling}})))))

;; ── the rework walls (waymark-9j2, waymark-9xn) ─────────────────────
;;
;; THE ITERATION LOOP, AND WHY IT IS NOT A DECLINE. A person can say
;; three things about a bundle today — take it (`make_it_so`), set it
;; aside for the week (`not_this_week`), let the clock have it
;; (`expire`) — and none of them is "the outcome is right, the PLAN is
;; wrong, workshop it with me". That gesture is the whole point of the
;; system (tune the steps of desirable outcomes over time,
;; bd memory project-narrative-the-feed), and it wants two doors the
;; three verbs above cannot spell: one that KEEPS the outcome standing
;; while asking for a re-plan, and one that lets the composer revise a
;; STANDING outcome's pieces IN PLACE — replace, re-time, add, remove —
;; rather than staging a competing twin the rank cannot tell apart or
;; waiting for a decline it has not earned.
;;
;; `iterate` (the person's, on the outcome) moves it to `iterating`:
;; the goal is kept, the plan is in the composer's hands, and the
;; bundle LEAVES THE FEED until the rework brings it back. The owner's
;; ruling of 2026-08-28 is what put the state there, and it is the
;; correction of 9j2's own self-loop: *when I iterate on an outcome, it
;; doesn't remove it from my feed. I think it should. It should
;; probably go into a state of 'Needs Iterating' and then I shouldn't
;; see it until it's been iterated on.* He named it as a reason he had
;; stopped answering the fridge at all: the crown reads `state=offered`
;; and kept re-asking the question he had just answered. The state is
;; the answer — the population takes `offered` and iterating is gone by
;; construction, no flag and no second filter to keep in step — and the
;; note still rides as a `remark` on the outcome's thread, so a
;; critique of the PLAN lands where the composer already listens
;; (waymark-b4s) rather than as a fourth verdict word.
;;
;; `rework` (the composer's, one on the outcome and one on each piece)
;; is the answer, and the outcome's own is now THE DOOR BACK:
;; `iterating → offered`, bumping `plan_revision`, stamping
;; `reworked_at` and replying on the thread. It is deliberately NOT
;; walled by four-eyes — the composer is not DECIDING anything the
;; household consented to, it is un-proposing and re-proposing its OWN
;; suggestion — so the wall is the inverse: authorship, plus the STATE
;; as the invitation. Two stamps used to carry that invitation
;; (`iterate_requested_at` later than `reworked_at`); the state carries
;; it now, and the stamps stay as the record of the round rather than
;; as a wall's subject. The person still taps the revised pieces; a
;; rework changes what is on offer, never what the house has said.

(defn- reworks-wall
  "`only-its-composer-reworks`, minted per kind because the grant token
  it names is the kind's own (`outcome.rework`, `outcome_piece.rework`)
  — `verdict-wall`'s shape, one law over.

  THE INVERSE OF THE FOUR-EYES WALL, and the reason a rework is safe to
  hand an agent at all: whoever STAGED a proposal may pull it back to
  replace it. A rework materializes nothing and decides nothing — it
  withdraws an unanswered suggestion so a better one can stand — so the
  law that keeps it honest is authorship, not personhood.

  AND SINCE waymark-9xn IT IS GRANTABLE, by the owner's ruling of
  2026-08-28 read through the other side of the mirror (waymark-sfe:
  *it doesn't make sense to disallow it, it just makes sense to
  permission it*). `g/is-the-field` refused every other hand outright,
  which meant a bundle whose composer is gone — a suspended principal,
  a leash that lapsed, an agent retired between sittings — sat in
  `iterating` with a plan nobody in this house could ever revise, and
  the household could not delegate the revision either. Now: the
  composer walks it with no grant at all, and another agent walks it
  under a grant that admits `<kind>.rework` on that very row, which
  exists only because a person approved an approval_request naming it."
  [kind]
  (g/author-or-granted
   kind :rework
   {:field :composed_by
    :name :only-its-composer-reworks
    :explain "The composer that staged this reworks it. A rework is un-proposing your own suggestion so you can offer a better one — it is not a verdict the household taps, and it is not a door onto somebody else's composition. If you did not stage it and the household wants you to take the plan over anyway — because whoever wrote it is gone — that is a grant a person approves, naming this row."}))

(defguardfn the-parent-invited-a-rework
  {:reads [:outcome]
   :vars [:problem]
   :open "A piece is withdrawn only while the bundle it belongs to is ITERATING — the household's own \"the plan is wrong, workshop it\" is what puts an outcome in that state, and it is the state that opens the composer's in-place revision. A piece under a bundle still on the fridge is one the house is being asked about right now, and pulling it out from under them is nobody's to do."
   :explain "This piece's bundle is not open for a rework: {problem}"}
  [row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [o (outcome-of row inp ctx)]
      (cond
        (nil? o)
        (t/deny {:vars {:problem (str "it names no outcome this house serves,"
                                      " so there is no invitation to read.")}})

        (= "iterating" (name (:state o)))
        (t/allow)

        :else
        (t/deny {:vars {:problem (str "/api/outcomes/" (:id o) " is "
                                      (name (:state o)) ", not iterating."
                                      " Withdrawing a piece is one move"
                                      " inside a re-plan a person asked"
                                      " for; without the ask, the pieces"
                                      " stand as offered for the household"
                                      " to answer.")}})))))

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

;; THE VERDICT WALL, GRANTABLE (waymark-sfe, the owner's ruling of
;; 2026-08-28: "The whole reason we have the access controls we have is
;; so that I can ask you to do what I want when I want. It doesn't make
;; sense to disallow it, it just makes sense to permission it.")
;;
;; It used to refuse EVERY agent outright, which meant a person could
;; not say "decline these thirty-one, these words" even though the
;; grants machine already spells that scope to the letter. It now
;; refuses an agent UNLESS the grant it presented admits this very
;; door — and a scope naming `outcome.not_this_week` exists only
;; because a person tapped an approval_request in the feed, so the
;; agent acts on instruction and never on initiative.
;;
;; WHAT DID NOT MOVE. `the-composer-does-not-decide` still stands in
;; front of `make_it_so`, `not_this_week`, `take` and `not_this`, and
;; it refuses EVERY principal that staged the row, grant or no grant.
;; The two doors it never stood on — `iterate` and `moot` — got four
;; eyes for the agent inside this wall (`:own-field :composed_by`),
;; because their old comment said the quiet part: "`a-person-answers`
;; blocks every agent, which is also the four-eyes wall for free, since
;; the composer is one". The moment the wall stops blocking every
;; agent, that freebie has to be paid for.
(defn- verdict-wall
  "`a-person-answers`, for one door of one kind (`:outcome` or
  `:outcome_piece`). The sentence is the household's, unchanged; what
  is new is that it says which token a scope would have to admit."
  [kind action]
  (unless-granted
   kind action
   {:name :a-person-answers
    :own-field :composed_by
    :explain "A person answers an outcome — every part of it, both ways round. A house running two agents would otherwise have one stage the bundle and the other tap it through, and the four-eyes wall would have been walked around rather than kept. If you are an agent and you think this outcome is right, say so where an agent may: publish an insight citing what you read."}))

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

(defguardfn the-plan-is-not-under-rework
  {:vars [:problem]
   :open "Make it so takes a plan the household has read as it stands. A bundle sent back for a re-plan is one the person has already said is wrong, so the door that would take it whole stays shut until the composer answers and the revised bundle comes back to the fridge."
   :explain "This one is not ready to be taken: {problem}"}
  ;; THE ONE VERDICT `iterating` CLOSES (waymark-9xn), and it is closed
  ;; with a sentence rather than by the machine's own out-of-state
  ;; refusal on purpose: *Available in state(s) Offered* is true and
  ;; says nothing about what happened or when the bundle comes back.
  ;; The other two verdicts stay open from here — a decline is always
  ;; allowed, and the clock still answers — because neither of them
  ;; contradicts what the person said. Accepting the plan you have just
  ;; called wrong is the one that does.
  [row _inp _ctx]
  (if (= "iterating" (name (:state row)))
    (t/deny {:vars {:problem
                    (str "you asked the composer to rework this plan, and"
                         " it has not answered yet — taking it now would"
                         " take the pieces you said were wrong. Wait for"
                         " the rework, or, if the week itself is wrong,"
                         " say not this week.")}})
    (t/allow)))

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
        prior (when (and sid (:read ctx)) ((:read ctx) :outcome sid))
        rid (cited-request (:data row))]
    ;; THE REQUEST IS ANSWERED IN THE SAME STROKE (waymark-jfv.20),
    ;; through its own door, before this row's own insert — the id was
    ;; minted ahead of this hook, so the request can name the outcome
    ;; that answered it. The door's one wall reads `(:within ctx)`,
    ;; which names THIS create, and opens for nothing else; a refusal
    ;; inside it rolls the whole staging back, so no outcome ever
    ;; reads staged against a request that did not move. The
    ;; storage-free probe never runs :on-create, so there is no probe
    ;; arm to guard here.
    (when (and rid (:invoke ctx))
      ((:invoke ctx) :composition_request rid :answer {:outcome_id (:id row)}))
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
  ;; The schedule is stamped here too: how long before the house is
  ;; willing to hear this outcome recomposed, and how many times it
  ;; has said so. Since waymark-1uv.10 both stamps are INPUTS TO THE
  ;; CROWN'S RANK rather than a wall at the create door — a
  ;; recomposition staged before `not_before` is admitted and cooled
  ;; by every day it is early, with the card saying so — so what is
  ;; written here is the person's verdict in the shape the rank reads.
  ;; The schedule is the tickler's own — a week, three weeks, two
  ;; months, half a year — called by name, with `now` handed in, so
  ;; nothing here reads a clock and the same inputs answer the same
  ;; instant in a test, in a scenario and in the house.
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

(defn- about-of
  "The outcome's goal, capped to the remark kind's `about` ceiling (200)
  so a long goal cannot 422 the remark and roll back the gesture that
  files it — the label copied at birth so the turn reads later even if
  the row behind it is gone."
  [d]
  (let [g (str (:goal d))]
    (if (> (count g) 200) (subs g 0 200) g)))

(defhandler ask-to-iterate [row inp ctx]
  ;; THE PERSON'S GESTURE (waymark-9j2). It KEEPS the outcome offered —
  ;; the self-loop is the point, the bundle is still asking — and does
  ;; two things: stamps the open invitation the composer reads, and
  ;; files the note as a turn in the outcome's thread, so a critique of
  ;; the PLAN lands where the composer already listens (waymark-b4s)
  ;; rather than as a fourth verdict word. The remark is created through
  ;; the member's OWN hand (`ctx :create` carries the outer principal,
  ;; the materialize handler's own finding), so `said_by` is stamped as
  ;; the person and the thread reads honestly. The storage-free probe
  ;; carries no `:create` and never runs a writing handler, so the
  ;; remark arm is guarded by its presence.
  (let [d (:data row)]
    (when (:create ctx)
      ((:create ctx) :remark
       {:subject_kind "outcome"
        :subject_id (:id row)
        :subject_href (str "/api/outcomes/" (:id row))
        :about (about-of d)
        :says (:says inp)}))
    (assoc-in row [:data :iterate_requested_at] (:now ctx))))

(defhandler rework-the-plan [row inp ctx]
  ;; THE COMPOSER'S ANSWER, on the outcome (waymark-9j2). It commits a
  ;; round: closes the open invitation (`reworked_at` now stands at or
  ;; past `iterate_requested_at`), counts the round the card shows
  ;; (`plan_revision`), and replies on the same thread — the composer's
  ;; turn, so the thread's last word is its own and the sitting reads
  ;; the work order as answered. The pieces themselves are withdrawn and
  ;; re-staged through their own doors before this; this is the commit
  ;; that says the new plan is ready for the household to answer.
  ;;
  ;; AND NOTHING COUNTS THE CHANGES (waymark-vf8). A round that
  ;; withdrew no piece and staged none is admitted exactly like any
  ;; other: the composer read the note and stands by the plan, or
  ;; cannot stage what was asked for, and either way the honest answer
  ;; is to hand the bundle back with `says` telling the household so —
  ;; the crown shows it again and they may still decline it. A door
  ;; that demanded a diff would have taught the composer to stage a
  ;; cosmetic one, and a separate "decline to rework" door would be a
  ;; second way to say the one thing this door already says.
  (let [d (:data row)]
    (when (:create ctx)
      ((:create ctx) :remark
       {:subject_kind "outcome"
        :subject_id (:id row)
        :subject_href (str "/api/outcomes/" (:id row))
        :about (about-of d)
        :says (:says inp)}))
    (-> row
        (assoc-in [:data :reworked_at] (:now ctx))
        (update-in [:data :plan_revision] (fn [n] (inc (long (or n 0))))))))

;; THE PIECE'S TWO BIRTH STAMPS, and neither is the caller's to give.
;;
;; WHO STAGED IT, its own rather than the parent's copy, because the
;; four-eyes wall on a PIECE has to read the piece — a wall that
;; reached up to the bundle would be a wall a piece staged by somebody
;; else walked straight through.
;;
;; AND WHAT THE TAP WILL DO (waymark-jfv.17), which is the owner's own
;; discomfort answered on the record: *I'm not yet comfortable using
;; the crown because I'm not sure what impact the actions will have.*
;; It is computed HERE, at staging, from the prepared input and the
;; target kind's own declaration — `recipe_proposal`'s `diff` posture
;; exactly, and for its reason: the sentence a person taps under has
;; to be the engine's reading and never the stager's description of
;; it. `says` is the composer's prose and stays the composer's; this
;; line is beside it and the composer cannot reach a word of it.
;;
;; IT RUNS AFTER `the-prepared-input-fits-the-door`, which is the
;; whole reason it can be trusted: create guards are judged before
;; :on-create, so by the time this reads `prepared` that map has
;; already been decoded, defaulted and closed against the very create
;; model the tap will knock on. A line about an input the door would
;; refuse is a line that never gets written, because the row does not.
;;
;; AND, FOR AN INVOKE PIECE, THE VERSION IT WAS STAGED AGAINST
;; (waymark-jfv.9). It is the third stamp and the second half of the
;; impact line's integrity: the sentence describes a row as it stood at
;; this instant, so the instant is written down beside it and
;; `the-target-has-not-moved` asks about it at the tap. Nobody may
;; supply it — a piece that could name its own version could name a
;; version the row is about to reach.
(defhandler stamp-the-composer [row ctx]
  (let [rdef-of (:rdef-of ctx)
        d (:data row)
        k (some-> (:target_kind d) str str/trim not-empty)
        trdef (when (and rdef-of k) (rdef-of k))
        ;; this kind's own declaration, read off the registry rather
        ;; than reached for by name: the verb in the sentence is the
        ;; label of whatever action this piece advertises as primary,
        ;; so renaming the tap renames it in the sentence too
        prdef (when rdef-of (rdef-of "outcome_piece"))
        tid (some-> (:target_id d) str str/trim not-empty)
        ;; the target ROW, read through the write's own transaction —
        ;; the invoke arm names it and states where it stands, and
        ;; `the-row-it-names-is-there` has already refused a piece
        ;; whose row is not here
        trow (when (and (= :invoke (form-of d)) trdef tid (:read ctx))
               ((:read ctx) (:kind trdef) tid))]
    (cond-> (assoc-in row [:data :composed_by] (:id (:principal ctx)))
      trow (assoc-in [:data :target_version] (:version trow))
      ;; the storage-free probe carries no registry (the same nil
      ;; `the-prepared-input-fits-the-door` allows optimistically);
      ;; it also never runs :on-create, so this arm is the belt
      (and trdef prdef)
      (assoc-in [:data :impact] (feed/piece-impact prdef trdef d trow)))))

(defhandler record-the-verdict [row _inp ctx]
  (assoc-in row [:data :decided_by] (:id (:principal ctx))))

(defhandler materialize [row _inp ctx]
  ;; THE TAP IS THE WRITE, and it is the member's own — both arms.
  ;; `ctx :create` and `ctx :invoke` carry the OUTER principal, so the
  ;; row that lands or moves carries the MEMBER's name on its own
  ;; transition and is judged by its own kind's guards as that member.
  ;; That is also the whole of the staleness answer: the world is
  ;; re-judged here, by the target's own law, and its refusal is what
  ;; the household reads. Same transaction, so a refusal rolls the tap
  ;; back and the piece does not read taken.
  (let [d (:data row)
        k (keyword (str (:target_kind d)))
        plural (:plural ((:rdef-of ctx) k))
        stamp (fn [row href]
                (-> row
                    (assoc-in [:data :decided_by] (:id (:principal ctx)))
                    (assoc-in [:data :materialized] href)))]
    (if (= :invoke (form-of d))
      ;; THE INVOKE ARM (waymark-jfv.9). The fence is SUPPLIED rather
      ;; than waived — waymark-0k4's rule, and `recipe_proposal`'s own
      ;; spelling: the caller is the only one who knows which version
      ;; it MEANT to write over, so it hands the target its own etag
      ;; the way an honest client would. `the-target-has-not-moved` has
      ;; already asked the same question in the household's words,
      ;; because this half only fires on a target whose door declares a
      ;; fence, and most doors do not.
      (let [tid (str (:target_id d))]
        ((:invoke ctx) k tid (keyword (str (:target_action d)))
         (not-empty (:prepared d))
         {:if-match (inv/etag k tid (:target_version d))})
        (stamp row (str "/api/" plural "/" tid)))
      (let [res ((:create ctx) k (:prepared d))]
        (stamp row (str "/api/" plural "/" (str (get-in res [:row :id]))))))))

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
   :form "create"
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

;; NO SCENARIO NAMES `names-a-person`, AND THE ABSENCE IS STRUCTURAL
;; RATHER THAN AN OMISSION (waymark-jfv.11). A scenario's `:input` is a
;; literal map and a `:given` row's id is minted by the walker, so no
;; scenario can cite a row it staged — which means the only arm a
;; scenario could reach is a DANGLING companion, and `names-a-value`
;; stands in front of it and refuses the same body first for its own
;; reason. All three of this wall's arms (nobody there, an observed
;; guess, somebody who has left) want a live engine holding real
;; person rows anyway, so they are proved by workqueue10.person-test
;; over the real ring handler — `take` and `make_it_so`'s own posture,
;; two kinds up, for the same reason.

;; NO SCENARIO NAMES `the-request-is-open` EITHER, and for the same
;; structural reason `names-a-person` has none (waymark-jfv.20): a
;; scenario's `:input` is a literal map, so the only request it could
;; cite is a dangling one — and `names-a-value` stands in front of this
;; wall and refuses the same body first, because the value it cites is
;; dangling too. All five arms of the wall (no such request, already
;; answered, expired, lapsed, an aim not served) want a live engine
;; holding real request rows anyway, and workqueue10.outcome-test § 15
;; proves them over the real ring handler.

;; AND NONE NAMES `no-burial-without-a-diagnosis`, for the same
;; structural reason a third time (waymark-8um.4): every arm of the
;; wall is about a PRIOR ROW — declined, expired with view rows behind
;; it, or never shown — and a scenario's literal `:input` can only cite
;; a dangling one, which the wall lets through on purpose so the floor
;; wall may say "supersede one that exists". The whole ordering claim
;; (the duty before the date; never-shown recomposes freely; a cited
;; diagnosis must cite the prior and still stand) is proved by
;; workqueue10.outcome-test § 16 over the real ring handler, and
;; `:feed/diagnosis` in the conformance pack proves the refusal and the
;; document from the wire.

;; AND NONE NAMES `not-a-twin`, for the structural reason a fourth
;; time (waymark-8gc): the wall's whole question is what ANOTHER row
;; already cites, and a scenario holds one literal `:input` over an
;; empty store — there is no standing outcome for a candidate to twin,
;; so every scenario reaching this door would be an allow. The claims
;; (a twin refused by name, distinct evidence admitted, a cited
;; request admitted despite the overlap, a recomposition of a declined
;; prior admitted) are proved by workqueue10.outcome-test § 22 over
;; the real ring handler, where a first bundle can actually stand.

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
             :form "create"
             :target_kind "event"
             :prepared {:title "Shop afternoon with Jack"
                        :when "Saturday at 2"}}
   :expect  {:refused :the-prepared-input-fits-the-door}})

(defscenario an-invoke-piece-fits-the-door-it-will-knock-on
  "The same wall, the other arm (waymark-jfv.9). An open piece may
   name any door in the house, so the door's OWN input model is what
   judges what it carries — here a composer that offered to rank a
   task at minus one, which `task.prioritize` declares as an int no
   smaller than zero. Refused where it was written, for the reason the
   create arm is: a button that fails is worse than a button that was
   never offered."
  {:kind    :outcome_piece
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"
             :says "Push the stock-cutting up the queue"
             :form "invoke"
             :target_kind "task"
             :target_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B7"
             :target_action "prioritize"
             :prepared {:priority -1}}
   :expect  {:refused :the-prepared-input-fits-the-door
             :because "priority"}})

(defscenario an-invoke-piece-names-a-door-that-exists
  "An open target is not a free-text target. The door has to be one
   the kind actually declares — and the refusal LISTS the ones it
   does, because a composer discovering a vocabulary one round trip at
   a time is a composer wasting a sitting."
  {:kind    :outcome_piece
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"
             :says "Un-drop the task the source let go"
             :form "invoke"
             :target_kind "task"
             :target_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B7"
             :target_action "undrop"
             :prepared {}}
   :expect  {:refused :the-prepared-input-fits-the-door
             :because "no such door"}})

(defscenario a-piece-does-not-half-approve-an-ask
  "The one door an open piece may not name, and it is not a wall about
   authority — the ruling took those down. `approval_request.approve`
   mints its grant POST-COMMIT, at the router's boundary, so a tap
   fired from inside a transaction would move the ask to `approved`,
   terminally, and mint nothing: the household would read an approved
   ask and the composer would still have no leash. The refusal names
   the lawful path, and waymark-442.14 is the bead that empties this
   set."
  {:kind    :outcome_piece
   :attempt :create
   :as      {:id "colton" :type :person}
   :input   {:outcome_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B2"
             :says "Approve the composer's own ask"
             :form "invoke"
             :target_kind "approval_request"
             :target_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B8"
             :target_action "approve"
             :prepared {}}
   :expect  {:refused :the-door-carries-its-own-effect
             :because "on its own screen"}})

;; ── the rework scenarios (waymark-9j2, waymark-9xn) ─────────────────
;;
;; THE WALLS THAT READ ONLY THE PRINCIPAL AND THE ROW are proved here
;; at check tier, the file's own rule for a wall a literal `:input` can
;; reach: `a-person-answers` on `iterate` (the gesture is the
;; household's), `only-its-composer-reworks` on the outcome's rework
;; door (the mirror of four-eyes, grantable since 9xn), and
;; `a-decline-is-allowed-from-under-a-rework` on `not_this_week`
;; from the new state (a person's no is never held hostage to an
;; agent's answer). The wall that reads the PARENT
;; (`the-parent-invited-a-rework`), the one on `make_it_so` (see the
;; note above that scenario), and the whole loop's convergence want a
;; live engine, so they and the church example are proved by
;; workqueue10.outcome-test § 21 over the real ring handler.

(defscenario an-agent-does-not-iterate-an-outcome
  "Iterate is the household's own gesture — the goal is right, workshop
   the plan with me — so it is a person's to make, like every other
   answer to a bundle. An agent that thinks the plan is wrong reworks
   it when asked, or says so on the thread; it does not tap the
   household's own \"iterate\" on its behalf."
  {:kind    :outcome
   :attempt :iterate
   :input   {:says "The park walk clashes with 9am church."}
   :row     {:state :offered :data a-composed-outcome}
   :as      {:id "composer" :type :agent}
   :expect  {:refused :a-person-answers
             :because "A person answers"}})

(defscenario only-the-composer-that-staged-an-outcome-reworks-it
  "The authorship wall on the bundle's own rework door: the composer
   that staged the outcome commits the round, and nobody else reaches
   its plan. A person answers the revised pieces; they do not rework
   them, and a second agent does not either — a rework is un-proposing
   your OWN suggestion, the mirror of the four-eyes wall."
  {:kind    :outcome
   :attempt :rework
   :input   {:says "Moved breakfast after church and swapped the walk."}
   :row     {:state :iterating :data a-composed-outcome}
   :as      {:id "colton" :type :person}
   :expect  {:refused :only-its-composer-reworks
             :because "composer that staged"}})

;; NO SCENARIO NAMES `the-plan-is-not-under-rework`, and the absence is
;; structural rather than an omission — the third time this file has
;; had to write that sentence down (`names-a-person`'s note, the
;; piece's `only-its-composer-reworks` note). `make_it_so` carries
;; `something-is-still-on-offer`, which reads `:outcome_piece`, so any
;; scenario attempting that door DEFERS to the conformance suite; and
;; the conformance walker stages its subject through the kind's own
;; create door, which for an outcome demands a value this house holds
;; and evidence it can read — rows a declaration-time literal cannot
;; mint. So a deferred scenario on this door cannot be staged at all,
;; which is a fact about the walker rather than about the wall. It is
;; proved where it can be: workqueue10.outcome-test § 21, over the real
;; ring handler, against a real value and a real bundle.

(defscenario a-decline-is-allowed-from-under-a-rework
  "A decline is always allowed, and this is the half of `iterating` a
   machine could easily have got wrong: a person who asked for a
   re-plan and then decided the WEEK itself is wrong must not have to
   wait on the composer's turnaround to say so. Holding a person's own
   no hostage to an agent's answer would be the exact inversion of law
   6."
  {:kind    :outcome
   :attempt :not_this_week
   :row     {:state :iterating :data a-composed-outcome}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; NO SCENARIO NAMES `only-its-composer-reworks` ON THE PIECE, and the
;; absence is structural rather than an omission — the same shape the
;; `names-a-person` note one section up records. The piece's `rework`
;; carries `the-parent-invited-a-rework`, which reads the parent
;; `:outcome`, so any scenario attempting it defers to the conformance
;; suite — and a conformance-tier scenario stages its subject through
;; the create door AS THE WALKER, stamping the walker's id into
;; `composed_by`, which would make `is-the-field` ALLOW the very actor
;; the scenario means to refuse. It is ONE guard object shared by both
;; rework doors, and the outcome-level twin above proves it refuses at
;; check tier; the piece door's own refusal, and the whole loop, are
;; proved by workqueue10.outcome-test § 21 over the real ring handler,
;; where the test holds `composed_by` itself.

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
   :companion_id
   {:x-display
    {:label "Who it is with"
     :help "Somebody off this house's roster, when the outcome is one done WITH a person — /api/people. Leave it empty when it is nobody's afternoon but the owner's; most of the valuable ones are. The relation written on their row is how you learn whether the pairing is one this family would recognise: a grandparent's caregiver is not a son, and a plan that gets that wrong is wrong in the way nobody notices until Saturday."}}
   :companion_name
   {:x-display
    {:raw true
     :label "With"
     :help "Their name, copied by the engine so the card reads without a second lookup."}}
   :request_id
   {:x-display
    {:label "The request it answers"
     :help "When a person tapped \"compose me another\" and this outcome is the answer, name their request — /api/composition_requests?state=offered. A bundle that answers a person's own request stands first in the crown, above every one nobody asked for, and no number on the recipe moves it below; the request is checked to be open, and if the person named the value it should serve, this outcome serves that one. One request admits one outcome."}}
   :supersedes
   {:x-display
    {:label "The outcome this recomposes"
     :help "When the house said not this week and you are bringing it back in a different shape, name the one it replaces. The house keeps the chain, and each decline pushes the next hearing further out — a week, then three, then two months, then half a year."}}
   :diagnosis_id
   {:x-display
    {:label "The diagnosis it recomposes against"
     :help "The insight that says WHY the outcome this supersedes did not land — shown how many mornings, answered how, with which reasons (read /api/-/diagnosis) — and proposes the smaller step, the loved activity, or the better time. It cites the prior outcome in its evidence. Required when that prior was shown and declined, or shown and left to lapse; a prior never shown recomposes without one."}}
   :composed_by {:x-display {:raw true :label "Composed by"}}
   :decided_by {:x-display {:raw true :label "Answered by"}}
   :declined_count
   {:x-display
    {:label "Times this line of thinking was turned down"
     :help "Carried down the supersedes chain by the engine. It is the only input to how far out each decline stamps the next hearing, and the crown's card says it beside how early a recomposition arrived."}}
   :not_before
   {:x-display
    {:label "Hearable again from"
     :help "Stamped when the house says not this week, off the tickler's own schedule. A recomposition staged before this is admitted and RANKED for it — every day early holds it back in the crown, and its card says so — because the point of a decline is that the composer diagnoses the friction, and a diagnosis is a row it has to be allowed to write. The date is the person's verdict about when they will hear it, and the rank keeps that verdict where the person can read it."}}
   :good_until
   {:x-display
    {:label "For the week ending"
     :help "When the house stops being asked. Seven days from staging, written by the engine — the section is called \"this week could hold\", and an outcome still asking on the eighth morning is describing a week that is over."}}
   :iterate_requested_at
   {:x-display
    {:label "Asked to iterate at"
     :help "When a person last tapped \"iterate\" — kept the outcome, asked the composer to rework the plan. Engine-written. The composer reads it as an open invitation until it commits a rework; a fresh iterate after a round reopens it."}}
   :reworked_at
   {:x-display
    {:label "Plan last reworked at"
     :help "When the composer last committed a round of rework, engine-written. While this stands earlier than the iterate above (or is absent), a rework request is open — which is what the piece-withdrawal door and the outcome's own rework door both read."}}
   :plan_revision
   {:x-display
    {:label "Plan version"
     :help "How many times this outcome's plan has been reworked in place from a person's note, engine-written. Zero until the first rework; the card reads it to say \"reworked from your note\" and count the rounds."}}})

(def ^:private piece-prose
  {:outcome_id
   {:x-display
    {:label "The outcome it belongs to"
     :help "The bundle this piece is one part of. A piece with no bundle is a task nobody asked for."}}
   :says
   {:x-display
    {:label "What this piece is"
     :help "The one thing this piece does, in the words the household would use out loud — \"cut the stock to length Friday evening, twenty minutes\". Say what changes and what it costs; this is the line somebody reads before deciding whether it happens."}}
   :form
   {:x-display
    {:label "What the tap does"
     :choices {"create" "Creates a row — an errand, a hold on the calendar, something that does not exist yet"
               "invoke" "Moves a row that already stands, through that row's own named door"}}}
   :target_kind
   {:x-display
    {:label "What it reaches"
     :help "The kind this piece writes — \"task\", \"event\", \"grocery_list\", any kind this house serves. Read /.well-known/waymark for the list. It is checked against the registry when the piece is staged, and what the tap will actually do is written out on the row underneath."}}
   :target_id
   {:x-display
    {:label "The row it moves"
     :help "For an invoke: the id of the row this piece will move. Leave it empty for a create — there is no row yet, which is the whole difference between the two."}}
   :target_action
   {:x-display
    {:label "The door it knocks on"
     :help "For an invoke: the action's own name, exactly as that kind declares it — \"complete\", \"add_item\", \"still_stands\". Read a row's envelope and take the name off its actions. Leave it empty for a create."}}
   :target_version
   {:x-display
    {:raw true
     :label "Staged against version"
     :help "Where the target row stood when this piece was written, stamped by the engine. If it has moved since, the tap is refused by name rather than written over the top — the sentence somebody reads has to describe the world they are reading it in."}}
   :prepared
   {:x-display
    {:label "The input, already filled in"
     :help "Exactly the body the door will take — the create model for a create, the action's own input for an invoke, and {} for a door that takes none. This is where the friction is pre-paid, and it is checked against that very door when the piece is staged rather than when somebody taps it."}}
   :impact
   {:x-display
    {:widget "prose"
     :label "What the tap will do"
     :help "The engine's own reading of this piece, in the household's words: what one tap creates or moves, what it is called, which door it goes through, what it carries, where it lands, and what it does not touch. Written when the piece was staged, from the target kind's own declaration and the prepared input — nobody types it and nobody can edit it, which is the point, and it is the whole of what replaced the old closed list of what a piece was allowed to be. The composer says what the piece IS; this says what saying yes to it DOES."}}
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
   ;; FIVE STATES, and the middle one is the whole of waymark-9xn: an
   ;; outcome the household sent back for a re-plan is NOT answered and
   ;; NOT asking — it is in the composer's hands, and the feed's
   ;; `outcomes` population takes `offered`, so `iterating` leaves the
   ;; crown by construction rather than by a second filter somebody has
   ;; to remember. It is not terminal: `rework` is the door back,
   ;; `not_this_week` still answers it, and the leash still ends it.
   :states [:offered :iterating :accepted :declined :expired]
   :initial :offered
   :terminal #{:accepted :declined :expired}
   ;; WORDS DO NOT ANSWER AN ITERATE (waymark-vf8). The state above is
   ;; a work order, and the specimen that filed the bead is what a work
   ;; order looks like when it can be answered in prose: a sitting read
   ;; two iterate notes, replied *understood, I will rework this to
   ;; include getting Howie to his friend's birthday party*, reworked
   ;; nothing, and left both bundles in `iterating` at revision 0 — off
   ;; the household's feed, waiting on a composer, with the thread
   ;; reading as though they had been answered. The owner: *should we
   ;; make it so it can't promise, it can only act — by only giving the
   ;; option to resolve the iteration and include a comment?*
   ;;
   ;; So the composer's only door on an iterating bundle is `rework`,
   ;; and `remark`'s create wall reads THIS map to say so (the
   ;; framework owns the predicate — agent, this state, holds this door
   ;; — and this declaration owns the sentence). It binds exactly the
   ;; hand that can act: the row's own `composed_by`, or an agent under
   ;; a grant admitting `outcome.rework` on this very row. A person's
   ;; turn is untouched, and so is the turn of an agent with no rework
   ;; door — words are all it has.
   :answered-at-a-door
   {:iterating
    {:door :rework
     :whose :composed_by
     :explain "This bundle is handed back for a rework. Answer at {door} — withdraw or stage what changes and say why in says; a rework that changes nothing is a lawful answer too. Words alone do not answer an iterate."}}
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
            :summary "The outcome this one recomposes"}
           {:rel "diagnosis" :kind :insight
            :href "/api/insights/{data.diagnosis_id}"
            :summary "The diagnosis this recomposition was built against"}
           {:rel "companion" :kind :person
            :href "/api/people/{data.companion_id}"
            :summary "Who this outcome is with, off the house's roster"}
           {:rel "request" :kind :composition_request
            :href "/api/composition_requests/{data.request_id}"
            :summary "The person's request this outcome answers"}
           ;; THE THREAD (waymark-9j2, waymark-b4s): the conversation
           ;; about this outcome — the person's iterate notes and the
           ;; composer's rework replies, oldest first, read as a
           ;; conversation off the remark kind's own default sort.
           {:rel "thread" :kind :remark
            :href "/api/remarks?subject_kind=outcome&subject_id={id}"
            :summary "The conversation about this outcome — iterate notes and rework replies"}]
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
    ;; THE COMPANION (waymark-jfv.11), and it is a CHECKED ref rather
    ;; than a name in the goal sentence. The bead's own argument for
    ;; the check is the miscomposition that filed it: a plan may not
    ;; name a companion the roster does not hold, and a roster nothing
    ;; consults is a document. The garnish beside it is the label
    ;; doctrine `value_id`/`value_name` already wears, one field over.
    ;;
    ;; NO :filter, and the omission is deliberate rather than an
    ;; oversight. Only `filterable ∪ sortable` fields become generated
    ;; columns, so a filter here would move this kind's STORAGE facet
    ;; and mint a law revision on an outcome whose machine did not
    ;; change — for a query nothing asks yet. "Every outcome that ever
    ;; named one particular person" is a real question and a cheap
    ;; follow-up (waymark-jfv.14); it is not this bead's, and buying it
    ;; now would cost
    ;; the whole household a revision at the next boot.
    (oe :companion_id {:optional true :kind :person :label :companion_name}
        [:maybe :waymark/ref])
    (oe :companion_name {:optional true} [:maybe [:string {:max 80}]])
    (oe :evidence {:optional true}
        [:maybe [:vector [:string {:min 1 :max 200}]]])
    (oe :supersedes {:optional true :kind :outcome :filter #{:eq}}
        [:maybe :waymark/ref])
    ;; THE DIAGNOSIS IT RECOMPOSES AGAINST (waymark-8um.4) — an
    ;; insight, law 4's own word for it. NO :filter, `request_id`'s
    ;; reasoning one field down: nothing queries outcomes by diagnosis
    ;; (the diagnosis document reads the chain from the outcome's
    ;; side), so it lands in `data`, the storage facet does not move
    ;; and the migrate plan stays empty.
    (oe :diagnosis_id {:optional true :kind :insight} [:maybe :waymark/ref])
    ;; THE REQUEST IT ANSWERS (waymark-jfv.20). NO :filter, on purpose:
    ;; the join runs the other way — the request stamps the outcome
    ;; that answered it — so nothing queries outcomes by request, and
    ;; a generated column here would move this kind's storage facet
    ;; and mint a revision for a question nobody asks (442.9's
    ;; witnesses, applied once more). It lands in `data`, the migrate
    ;; plan stays empty, and the hash does not move.
    (oe :request_id {:optional true :kind :composition_request}
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
        [:maybe :waymark/instant])
    ;; THE ITERATE LOOP'S THREE STAMPS (waymark-9j2), engine-written and
    ;; out of the create model like the five above. NO :filter on any of
    ;; them, `request_id`'s reasoning once more: the composer reads them
    ;; off its own outcomes (own-surface), the guards read them off the
    ;; row in hand, and "an open request" is a two-field comparison a
    ;; generated column cannot express anyway — so they land in `data`,
    ;; the storage facet does not move and the migrate plan stays empty.
    (oe :iterate_requested_at {:optional true} [:maybe :waymark/instant])
    (oe :reworked_at {:optional true} [:maybe :waymark/instant])
    (oe :plan_revision {:optional true} [:maybe [:int {:min 0}]])]
   :create-schema
   [:map
    (oe :goal {} [:string {:min 1 :max 240}])
    (oe :value_id {:kind :value} :waymark/ref)
    (oe :routing {} [:string {:min 1 :max 600}])
    (oe :routes_through {:optional true} [:maybe [:string {:min 1 :max 40}]])
    (oe :companion_id {:optional true :kind :person} [:maybe :waymark/ref])
    (oe :evidence {:optional true}
        [:maybe [:vector [:string {:min 1 :max 200}]]])
    (oe :supersedes {:optional true :kind :outcome} [:maybe :waymark/ref])
    (oe :diagnosis_id {:optional true :kind :insight} [:maybe :waymark/ref])
    (oe :request_id {:optional true :kind :composition_request}
        [:maybe :waymark/ref])]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "offered"}
   :sortable {:fields [:created_at :good_until] :default "-created_at"}
   ;; the composer reads what it staged and how it was answered — that
   ;; IS the diagnosis feed. The VERDICT doors are still not listed: the
   ;; four-eyes wall refuses the stager at every one of them, so
   ;; advertising them would be advertising a 409 to the only principal
   ;; the courtesy is for (recipe_proposal's reasoning, whole). `rework`
   ;; is the exception the iterate loop adds (waymark-9j2), and it
   ;; belongs here for the opposite reason: it is the ONE door the
   ;; stager may walk — un-proposing its own suggestion is authorship,
   ;; not a verdict — so it is offered to the composer on its own
   ;; outcomes, and its own guards hide it until a person's iterate
   ;; stands open. Staging itself still rides an ordinary grant rather
   ;; than this courtesy: an outcome is a bundle of prepared WRITES, and
   ;; which agents may put one in front of the house is a thing the
   ;; house says out loud, once, at the grant door.
   :own-surface {:by :composed_by :actions #{:rework}}
   :on-create stage-the-outcome
   ;; shape first, world next, and NO PACE WALL LAST (waymark-1uv.3):
   ;; `outcomes-are-few` stood here until the crown's rank landed, and
   ;; left when it did. A malformed outcome hears what is wrong with
   ;; it; a well-formed one is staged, however many this composer has
   ;; staged this week, and the rank decides what the house is shown.
   ;; The person's pull (waymark-jfv.20) is still checked last, so a
   ;; citation that is not good refuses the staging before the request
   ;; could be answered by it.
   ;; …and THE DUTY BEFORE THE DATE (waymark-8um.4): the diagnosis
   ;; wall stands in front of the floor, because the epic's sentence
   ;; is that the composer's duty fires first — a recomposition with
   ;; no diagnosis hears about the diagnosis; one with a diagnosis
   ;; hears about the date
   ;; …and NOT A TWIN (waymark-8gc), standing between the
   ;; recomposition walls and the person's pull: after a bundle's
   ;; shape and its prior are judged, and before the pull is, because
   ;; the pull is this wall's own exemption — a cited request means a
   ;; person asked, and a person's ask is never refused for resembling
   ;; something the house already holds.
   ;; …and THE CLOSED BOOK (waymark-euj) with the citation walls it
   ;; belongs to: `cites-what-it-read` judges the SHAPE of what was
   ;; read, this judges whether any of it is still open, and
   ;; `not-a-twin` judges whether somebody already composed it. Shape
   ;; first, then the world — so a composer citing a malformed address
   ;; hears about the address before it hears about the archive.
   :create-guards [cites-what-it-read
                   names-a-value
                   names-a-person
                   routes-through-something-loved
                   composes-from-what-stands
                   no-burial-without-a-diagnosis
                   a-recomposition-waits-its-turn
                   not-a-twin
                   the-request-is-open]
   :actions
   {:make_it_so
    ;; `:from` NAMES `iterating` AND A GUARD REFUSES IT (waymark-9xn),
    ;; which is the deliberate spelling of "make_it_so is refused from
    ;; iterating WITH A SENTENCE": out of state the machine answers
    ;; *Available in state(s) Offered; the resource is Iterating*,
    ;; which is true and tells a person nothing about what they did or
    ;; when the bundle comes back. `the-plan-is-not-under-rework` says
    ;; it in the household's words, and the envelope renders the door
    ;; unavailable-with-a-reason instead of absent.
    {:from #{:offered :iterating} :to :accepted
     :guards [the-composer-does-not-decide (verdict-wall :outcome :make_it_so)
              the-plan-is-not-under-rework
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
    ;; FROM EITHER STANDING STATE (waymark-9xn): a decline is always
    ;; allowed. A person who asked for a rework and then decided the
    ;; week itself is wrong must not have to wait for the composer to
    ;; answer before they can say so — that would be the machine
    ;; holding a person's own no hostage to an agent's turnaround.
    :not_this_week
    {:from #{:offered :iterating} :to :declined
     :guards [the-composer-does-not-decide (verdict-wall :outcome :not_this_week)]
     :handler moot-the-rest
     :touches [{:kind :outcome_piece :action :moot}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The whole bundle leaves the feed and stays on record. It means the WEEK was wrong rather than the pieces — the remaining pieces are set aside rather than refused, so nobody reads this as a verdict on any one of them. The house will hear it recomposed, later, not tomorrow."}
     ;; …and it may say WHY, in one more optional tap after it lands
     ;; (waymark-jfv.16). `:reasons` is advertisement — it rides no
     ;; fingerprint facet and moves no hash — and it says only this:
     ;; when this verdict has settled, the card offers the four quick
     ;; words. The tap itself stays input-free and `assent`, which is
     ;; the whole constraint the reason kind exists to respect.
     :display {:label "Not this week" :order 2 :reasons true
               :description "The timing is wrong — set the whole thing aside, and say when the house is willing to hear it again"}}
    ;; THE LEASH KEEPS RUNNING IN `iterating` (waymark-9xn), and this
    ;; is what keeps the new state from being a hole to fall into: a
    ;; bundle whose composer never answers the rework lapses on its own
    ;; week like any other, and `expire` tidies it. Nothing is stuck
    ;; because an agent went quiet.
    :expire
    {:from #{:offered :iterating} :to :expired
     :guards [the-leash-has-run-out]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The week already answered this one; the row now says so."}
     :display {:label "Expire" :order 8
               :description "Tidy an outcome the week ran out on"}}
    ;; ── THE ITERATE LOOP (waymark-9j2, waymark-9xn) ──
    ;;
    ;; `iterate` KEEPS the outcome and asks for a re-plan — and since
    ;; waymark-9xn it MOVES it, `offered → iterating`, off the feed
    ;; until the composer's rework brings it back. From `iterating` it
    ;; is a self-loop: a second thought about the same unanswered plan
    ;; is another turn on the thread, and nothing about the row's
    ;; standing changed, so there is no state for it to move to.
    ;;
    ;; It is a person's gesture — `a-person-answers` blocks
    ;; every agent that holds no grant admitting `outcome.iterate`, and
    ;; blocks THIS outcome's own composer whatever it holds (waymark-sfe
    ;; moved four eyes inside that wall; until then the agent wall was
    ;; the four-eyes wall for free, since the composer is one) — and
    ;; `:record true` retains the note the handler
    ;; files as a remark on the outcome's thread. `:touches` names that
    ;; cross-write so a reader can see it coming.
    :iterate
    {:from #{:offered :iterating} :to :iterating
     :guards [(verdict-wall :outcome :iterate)]
     :handler ask-to-iterate
     :input [:map
             [:says
              {:examples ["Sunday breakfast is fine but the park walk clashes with 9am church, and it is too hot for a walk later."]
               :x-display
               {:widget "prose"
                :label "What to change, and why"
                :help "Say what is wrong with the PLAN while keeping the outcome — the wrong time, the conflict, the step that does not fit. The composer reads this as a turn in the thread and reworks the pieces; the outcome leaves your feed until it comes back revised."}}
              [:string {:min 1 :max 600}]]]
     :record true
     ;; a short note, and the wall against losing it on a mis-click is
     ;; the composer answering the thread, not a draft box — the remark
     ;; kind's own `reword` waiver, for the same reason.
     :waives #{:large-effort}
     :touches [{:kind :remark :action :create}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The outcome is kept — this does not accept it, decline it, or retire it. It tells the composer the goal is right but the plan needs work, in your own words, and hands the plan back for a rework. The bundle leaves your feed while it is being reworked and returns with revised pieces; your note joins the outcome's thread."}
     :display {:label "Iterate" :order 3
               :description "Keep the outcome, hand the plan back — say what is wrong, and it leaves your feed until the composer answers with revised pieces"}}
    ;; `rework` is the composer's commit of one re-plan round, and since
    ;; waymark-9xn it is THE DOOR BACK: `iterating → offered`. It counts
    ;; the round on the card, replies on the thread, and puts the bundle
    ;; in front of the household again with its revised pieces. NOT
    ;; four-eyes — `only-its-composer-reworks` is the inverse wall, and
    ;; grantable since 9xn so an ORPHANED bundle is not a plan nobody
    ;; can revise. There is no separate invitation guard any more: the
    ;; STATE is the invitation, which is one fact instead of two stamps
    ;; that had to be kept in step.
    :rework
    {:from #{:iterating} :to :offered
     :guards [(reworks-wall :outcome)]
     :handler rework-the-plan
     ;; `says` IS THE ANSWER, so it is required and it is short
     ;; (waymark-vf8): the composer has no other turn on an iterating
     ;; bundle — the remark door is walled shut against the very hand
     ;; that holds this one — and what rides here is posted on the
     ;; thread as that turn. 240, the house's own note ceiling
     ;; (`verdict-action`'s), because one turn back to the household
     ;; answering their note is a sentence or two and not an essay;
     ;; the round's real answer is what the pieces now say.
     :input [:map
             [:says
              {:examples ["Moved breakfast after church and swapped the park walk for the shaded creek trail at 8am, before the heat."]
               :x-display
               {:widget "prose"
                :label "What you changed, and why"
                :help "One turn back to the household: what the rework did to the plan, in answer to their note — or, when you read the note and the plan still stands, why it stands. This is your turn in the thread, and the card reads it as the reason the plan changed."}}
              [:string {:min 1 :max 240}]]]
     :record true
     :waives #{:large-effort}
     :touches [{:kind :remark :action :create}]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This commits a round of rework: it puts the bundle back on the household's feed, counts the round on the card, and replies on the thread with what you say here. Withdraw the pieces that were wrong and stage their replacements first — and if, having read the note, you stand by the plan, commit anyway and say why: a round that changes no piece is a lawful answer, and the household then decides for itself."}
     :display {:label "Rework the plan" :order 4
               :description "Commit a round of re-planning an outcome the house handed back — returns it to the feed and counts the round"}}}
   :scenarios [the-composer-does-not-answer-its-own-outcome
               an-agent-does-not-answer-an-outcome
               an-answered-outcome-does-not-come-back
               a-live-outcome-is-not-expired-out-of-the-way
               an-outcome-with-nothing-behind-it-is-refused
               an-outcome-names-a-value-this-house-holds
               an-agent-does-not-iterate-an-outcome
               only-the-composer-that-staged-an-outcome-reworks-it
               a-decline-is-allowed-from-under-a-rework]})

;; ── :outcome_piece — one concrete thing, one tap ────────────────────

(defresource outcome-piece
  {:kind :outcome_piece
   :plural "outcome_pieces"
   :nav :system
   ;; `:reworked` is the fifth state (waymark-9j2), and it is neither of
   ;; the two declines: a piece the composer PULLED BACK to replace, not
   ;; a piece the household refused. It teaches nothing (unlike
   ;; `declined`, "never this piece") and it is not the week stepping
   ;; over it (unlike `moot`) — it is the composer un-proposing its own
   ;; offer inside a re-plan the person asked for, and `make_it_so` takes
   ;; only what is still `offered`, so a reworked piece lands nothing.
   :states [:offered :taken :declined :moot :reworked]
   :initial :offered
   :terminal #{:taken :declined :moot :reworked}
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
    ;; THE FORM (waymark-jfv.9), and it is EXPLICIT on the row rather
    ;; than derived from which of the target fields happen to be
    ;; present. Three walls read it, and a wall that had to infer its
    ;; own subject from an absence would be a wall that guessed: a
    ;; create with a stray `target_id` and an invoke that forgot one
    ;; are different mistakes, and each deserves its own sentence.
    ;;
    ;; OPTIONAL HERE, REQUIRED IN THE CREATE MODEL. Four pieces stood
    ;; in production before this law and every one of them is a create,
    ;; which is exactly what `form-of` reads an absent field as; no
    ;; backfill, and nothing is written to those rows to say what they
    ;; already are.
    ;;
    ;; NO :filter AND NO :sort on this or on the three fields below,
    ;; deliberately: only `filterable ∪ sortable` becomes a generated
    ;; column, so four new fields land in the `data` jsonb, the table
    ;; projection is unchanged, the storage facet does not move and the
    ;; migrate plan stays EMPTY. 442.9's witnesses, applied a third
    ;; time. "Every piece that ever invoked" is a real question and a
    ;; cheap follow-up; buying it now would cost the household a
    ;; revision and a DDL at the next boot.
    (pe :form {:optional true} [:maybe form-enum])
    ;; THE ENUM DIED HERE (waymark-jfv.9). jfv.3 closed this field to
    ;; `[:task :event]` and the argument was good; the owner's ruling
    ;; replaced the wall with inspection — *a piece can do whatever it
    ;; wants, but I just need to be able to inspect the impact.* What
    ;; stands in its place is `impact` two fields down, written by the
    ;; engine at staging from the target's own declaration, plus the
    ;; target's own door judging at the tap as the member.
    (pe :target_kind {:filter #{:eq}} [:string {:min 1 :max 64}])
    ;; THE ROW AND THE DOOR, for the invoke form. Plain strings and not
    ;; a `:waymark/ref`, because a ref names ONE kind at declaration
    ;; time and this one is chosen at staging — `recipe_proposal`'s
    ;; `target_id` is the same shape for a narrower version of the same
    ;; reason.
    (pe :target_id {:optional true} [:maybe [:string {:max 64}]])
    (pe :target_action {:optional true} [:maybe [:string {:min 1 :max 64}]])
    ;; ENGINE-WRITTEN: where the target stood when this was staged.
    ;; `the-target-has-not-moved` asks about it at the tap, and
    ;; `materialize` hands it to the target's own fence as an etag.
    (pe :target_version {:optional true} [:maybe [:int {:min 0}]])
    ;; THE FREE DATA, and what makes it safe is no longer the enum
    ;; above it — it is that this map is judged at staging against the
    ;; very model the door will judge it against (its create model, or
    ;; the named action's own `:input`), read off the registry rather
    ;; than copied, and judged again by that door's own guards at the
    ;; tap, as the member.
    (pe :prepared {} [:map-of :keyword :any])
    ;; ENGINE-WRITTEN (waymark-jfv.17), and OPTIONAL for a reason that
    ;; is about the rows already standing rather than about the field.
    ;; Four pieces were on offer in production when this law landed,
    ;; staged before it existed and carrying no such sentence. A
    ;; required field would have demanded a backfill — the engine's
    ;; reading written onto them from OUTSIDE the staging door that
    ;; owns it, which is the one property this whole bead is about. So
    ;; absent is legal, and `feed/piece-impact-of` runs the identical
    ;; derivation at the read for any piece that has none: the four
    ;; live pieces gained their line on the next morning's feed and
    ;; nothing was written to get it there.
    ;;
    ;; NO :filter AND NO :sort, deliberately — only
    ;; `filterable ∪ sortable` becomes a generated column, so this
    ;; moves no column, no index, and therefore no storage facet and
    ;; no fingerprint. Nothing queries a sentence.
    (pe :impact {:optional true} [:maybe [:string {:max 600}]])
    (pe :materialized {:optional true} [:maybe [:string {:max 200}]])
    (pe :composed_by {:optional true :filter #{:eq}}
        [:maybe [:string {:max 128}]])
    (pe :decided_by {:optional true} [:maybe [:string {:max 128}]])]
   :create-schema
   [:map
    (pe :outcome_id {:kind :outcome} :waymark/ref)
    (pe :says {} [:string {:min 1 :max 240}])
    ;; REQUIRED, and it is the one place jfv.9 chose the stricter
    ;; spelling. A default would have meant a composer that forgot the
    ;; field silently got `create` — and the form is the difference
    ;; between birthing a row and moving one, which is precisely the
    ;; thing that must never be arrived at by omission. It also keeps
    ;; the `create` fingerprint facet (declared defaults) empty, so the
    ;; only hash that moves is the one whose law really did.
    (pe :form {} form-enum)
    (pe :target_kind {} [:string {:min 1 :max 64}])
    (pe :target_id {:optional true} [:maybe [:string {:max 64}]])
    (pe :target_action {:optional true} [:maybe [:string {:min 1 :max 64}]])
    (pe :prepared {} [:map-of :keyword :any])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "created_at"}
   ;; the composer reads its own pieces and how they were answered; the
   ;; verdict doors stay unlisted (four-eyes refuses the stager), and
   ;; `rework` is the one it may walk — withdrawing its own unanswered
   ;; offer (waymark-9j2), hidden by its own guards until the bundle's
   ;; iterate request is open.
   :own-surface {:by :composed_by :actions #{:rework}}
   :on-create stamp-the-composer
   ;; shape first, world next, pace last — the same order one kind up.
   ;; `the-door-carries-its-own-effect` sits with the shape walls
   ;; because what it reads is a fact about this ENGINE's declarations
   ;; and not about any row.
   ;; `the-door-is-open-now` stands behind `the-row-it-names-is-there`
   ;; and not in front of it (waymark-euj): out-of-state is the older
   ;; wall's sentence, and it is the better one — it names the states
   ;; the door leaves from, which is what a composer needs to fix the
   ;; piece. What this adds is the case that wall cannot see: the row
   ;; IS in a state the door leaves from, and the door's own guards
   ;; refuse it anyway.
   :create-guards [the-prepared-input-fits-the-door
                   the-door-carries-its-own-effect
                   the-row-it-names-is-there
                   the-door-is-open-now
                   the-outcome-is-still-open
                   a-bundle-is-small]
   :actions
   {:take
    {:from #{:offered} :to :taken
     :guards [the-composer-does-not-decide (verdict-wall :outcome_piece :take)
              the-outcome-is-still-open the-bundle-is-taking-answers
              the-target-has-not-moved]
     :handler materialize
     :touches touched-creates
     :safety {:idempotent true :reversible false :confirm false
              ;; the honest statement `:touches` cannot carry
              ;; (waymark-jfv.9), in the household's own words and in
              ;; the one place a person taps under
              :one-way "This writes the row named in the line above, through that row's OWN door, with YOUR name on it — a new task joins the queue, a hold joins the calendar, or a row that already stands moves. It reaches exactly what that line names and nothing else, and the door's own law judges it as you. The way back is that row's own doors; this piece is answered either way."}
     :display {:label "Yes" :style :primary :order 1
               :description "Make this one real — it lands as a row of its own, under your name"}}
    :not_this
    {:from #{:offered} :to :declined
     :guards [the-composer-does-not-decide (verdict-wall :outcome_piece :not_this)]
     :handler record-the-verdict
     ;; THE TEACHING REFUSAL. Distinct from the parent's not_this_week
     ;; and from moot below, and the difference is the signal: this one
     ;; says THE COMPOSITION WAS WRONG — do not bring this piece back.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This piece is wrong and the record says so — which is the part a composer can actually learn from. The rest of the outcome is untouched; say yes to whatever is still right."}
     ;; THE TEACHING REFUSAL LEARNS TO SPEAK (waymark-jfv.16): after it
     ;; settles, the card offers four quick words — wrong time, wrong
     ;; piece, not this way, never this — one more optional tap, and a
     ;; sentence one screen deeper. Silence stays a complete answer.
     :display {:label "Not this" :order 2 :reasons true
               :description "This part was wrong — decline it and leave the rest of the bundle standing"}}
    :moot
    {:from #{:offered} :to :moot
     :guards [(verdict-wall :outcome_piece :moot)]
     :handler record-the-verdict
     ;; SET ASIDE, NOT REFUSED. This is what "not this week" on the
     ;; parent does to every piece still standing, and it is offered
     ;; here on its own for the honest case: the piece is beside the
     ;; point now, and the house does not want the composer to learn
     ;; anything from it. No SEPARATE four-eyes wall: nothing is
     ;; created and nothing is refused, so there is nothing here for a
     ;; stager to grade — the four eyes inside `a-person-answers`
     ;; (waymark-sfe, `:own-field :composed_by`) is there for the other
     ;; reason, which is that a grant must never let a composer set its
     ;; own piece aside.
     ;;
     ;; AND NO `:reasons` EITHER (waymark-jfv.16), for exactly that
     ;; reason said once more: this verdict's whole meaning is that
     ;; there is nothing here to learn, so offering four words that
     ;; teach a composer something would be the verdict contradicting
     ;; itself on the same line.
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Beside the point now — the piece steps out of the way without being counted as a refusal, so nobody reads it as a verdict on the idea."}
     :display {:label "Beside the point" :order 3
               :description "Set this piece aside without teaching the composer anything about it"}}
    ;; THE COMPOSER PULLS A PIECE BACK (waymark-9j2), so a better one
    ;; can stand in its place. NOT a verdict and NOT four-eyes — the
    ;; wall is authorship (`only-its-composer-reworks`) plus the parent
    ;; bundle's open invitation (`the-parent-invited-a-rework`). It
    ;; takes no input and moves the piece to `reworked`; the transition
    ;; log carries who withdrew it, and the replacement is a new piece
    ;; through the ordinary create door.
    :rework
    {:from #{:offered} :to :reworked
     :guards [(reworks-wall :outcome_piece) the-parent-invited-a-rework]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "You are pulling this piece back so a better one can stand in its place — it leaves the bundle without being declined or set aside, so the household never reads it as a verdict on the idea, and it lands nothing if the outcome is later accepted. Stage the replacement as a new piece."}
     :display {:label "Rework" :order 4
               :description "Withdraw your own piece from an outcome under an open iterate request, to replace it"}}}
   :scenarios [the-composer-does-not-answer-its-own-piece
               an-agent-does-not-answer-a-piece
               a-taken-piece-does-not-come-back
               a-piece-fits-the-door-it-will-knock-on
               an-invoke-piece-fits-the-door-it-will-knock-on
               an-invoke-piece-names-a-door-that-exists
               a-piece-does-not-half-approve-an-ask]})
