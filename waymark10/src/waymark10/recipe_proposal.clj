(ns waymark10.recipe-proposal
  "The staged proposal (waymark-0k4): an agent prepares an exact change
  to the feed's order, and a person's tap applies it.

  waymark-4yn put a wall at `feed_recipe`'s doors — the order the feed
  is read in is written by a PERSON, because a composer that could
  rewrite its own editorial frame is the ranking model this whole
  surface exists to keep out. The wall stands. What it left open was
  the other half: the lawful path it named was 'publish an insight',
  and an insight carries prose and an address, so a member who agreed
  with a finding still had to re-type the order by hand into a form.

  A proposal closes that. It is a ROW, staged by whoever noticed, and
  it carries the whole of the change: the order the house reads today,
  the order proposed in its place, the diff between them in the
  household's own words, what was read to arrive at it, and — for the
  household — two buttons. The wall is untouched. The write still
  belongs to a person.

  ── THE ASYMMETRY IS THE WHOLE POINT ──

  An agent may CREATE one of these and may not create a `feed_recipe`.
  That is not a loophole in the wall, it is what the wall is for: a
  proposal changes nothing until a member says so, and the member sees
  in full what they are saying yes to. Say it the other way round and
  it is plainer still — this kind is grantable at the MCP door
  (`{:kind \"recipe_proposal\" :actions [\"create\"]}`) precisely
  BECAUSE holding that grant confers no power over the feed's order.

  An agent needs that grant. A person does not, and never did: humans
  are unscoped in this engine, so staging is a form a member fills in
  like any other. Which agents may put a prepared write in front of
  the household is a thing the household says out loud, once, at the
  grant door.

  ── THE APPLY IS THE MEMBER'S OWN WRITE ──

  `apply` invokes `feed_recipe`'s OWN doors — `:revise` on the named
  row, or the create door when the house has no row of its own yet —
  through `ctx :invoke` / `ctx :create`, which carry the OUTER
  principal (server/invoke's `make-ctx`, the finding waymark-iqa.6
  recorded). So the recipe's transition names the MEMBER WHO TAPPED,
  the recipe's own guards judge the write, and `written-by-a-person`
  passes for the honest reason: a person is writing.

  Three consequences worth stating, because each is a thing that could
  have been done differently and was not:

  1. IT IS ONE TRANSACTION. The proposal moves to `applied` and the
     recipe moves, or neither does. `grants/approval-effects!` is the
     other precedent available here and it is declined on purpose: it
     runs POST-COMMIT at the wire boundary, under a SYSTEM actor, and
     warns to *err* when its effect refuses. All three are wrong for
     this bead — the actor has to be the member, an apply that landed
     nothing must not read as applied, and there is nothing here that
     needs to survive a redelivery, because the verdict door IS the
     idempotency boundary (a second tap meets a terminal row).
  2. NO DETERMINISTIC KEY IS MINTED FOR THE INNER WRITE, for the same
     reason: `ctx :invoke` runs inside the outer write's transaction
     and stores no idempotency record of its own. The inner write is
     not independently addressable and must not be.
  3. THE CITATION RIDES BOTH WAYS. The proposal stamps `applied_to`
     with the recipe it landed on; the recipe's `revise` is `:record
     true`, so its transitions log keeps the exact order that was
     written, under the member's name, at that moment. Reading either
     row tells you the whole story.

  ── VALIDATED AT STAGING, NOT AT APPLY ──

  A proposal that would be refused when somebody taps it is refused
  when it is STAGED — the letter-addressing lesson, and the reason is
  household rather than technical: a button that fails is worse than a
  button that was never offered. Five walls at the create door, in
  shape-then-world-then-pace order:

  - `the-prepared-input-fits-the-door` judges {label, order} against
    `feed-recipe/recipe-input` — literally the schema `:revise` takes.
  - `the-order-will-assemble` runs `feed/check-recipe!`, the same four
    assembly checks that used to refuse the BOOT and now refuse the
    recipe's own doors.
  - `it-cites-what-it-read` refuses a proposal with nothing behind it.
  - `the-staging-is-current` is the first that reads the world: the
    order you say the house reads today has to BE the order the house
    reads today.
  - `staged-changes-are-few` caps what one principal may have waiting
    at three. It is the second of two walls on the asking and they
    answer different questions: the GRANT says WHICH agents may reach
    this door at all (unlike `insight`, this kind's own-surface does
    not carry `create` — see `:own-surface` below), and the CAP says
    how often anybody may walk through it. A household with one
    trusted composer still does not want its decide section filled.

  ── AND STALE AT APPLY, WHICH IS A DIFFERENT QUESTION ──

  The same fact is asked again at the tap (`the-order-has-not-moved`),
  because the world moves between staging and answering. A proposal
  staged against an order somebody has since revised refuses, by name,
  with the sentence that says what to do about it. It does not
  silently apply over the top: the diff a person read would then be a
  description of a world that no longer existed, and the tap would
  have meant something other than what it said.

  ── THE LEASH ──

  Seven days, stamped at birth, engine-owned. A proposal nobody
  answered is not a proposal the house owes anything to, and the
  feed's own decide section is the scarcest surface in the building.
  Enforcement is LIVE at the door and at the read — `expire` is
  bookkeeping anybody may run once the clock has passed, and no
  sweeper drives it, which is `grant`'s own recorded posture inherited
  whole rather than re-decided.

  ── WHY THE :decision SUGAR IS NOT SPELLED HERE ──

  It would fit the machine exactly — offered → applied/declined is
  verdict-shaped, and the four-eyes wall below is `g/not-the-field`,
  the very guard `desugar-decision` mints. It is declined for the
  sugar's OWN recorded limit, in its own words: \"a decision kind that
  needs an extra birth stamp has no spelling yet\". This kind needs
  two — the diff the engine computes at staging, and the leash — on
  top of the stager's name, and `:on-create` has one home."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.feed :as feed]
            [waymark10.server.invoke :as inv]
            [waymark10.feed-recipe :as feed-recipe]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def kind
  "The recipe_proposal kind keyword — the definite marker, never a
  name string."
  :recipe_proposal)

(def leash-days
  "How long a staged proposal stands before the house stops being
  asked. SEVEN, and the number is the household's rather than the
  machine's: a change to the order the morning is read in is worth a
  week of mornings to notice, and an eighth morning of the same
  unanswered card is a card the house has learned to scroll past.

  Engine-owned, deliberately. The `:decision` sugar's `:expires` lets
  an asker propose a shorter leash and caps how long it may ask for;
  neither half is worth the surface here, because the person who
  benefits from a short leash is the household and the person filling
  the form is not."
  7)

;; ── the two orders, as the diff reads them ──────────────────────────

(defn- as-recipe-order
  "One order in the EDITOR's wire shape → the recipe-map shape
  `feed/order-diff`, `feed/line-says` and `feed/check-recipe!` all
  speak. `feed-recipe/recipe-of` is the one converter and it is used
  by name so there is never a second."
  [wire-order]
  (:order (feed-recipe/recipe-of {:order wire-order})))

(defn- same-order?
  "Do two orders SAY the same thing? Compared as recipe maps rather
  than as wire spellings, because the two sides of every comparison
  this kind makes arrive by different routes: one is what the feed
  document published (`feed/order-as-written`, which emits exactly the
  keys a line means and no others), the other is what the row stored
  (whatever the form posted, closed schema and all). A seam line
  carrying a stray `:take` is the same order either way, and a
  proposal refused for a key nobody can see would be a refusal nobody
  could act on."
  [a b]
  (= (as-recipe-order a) (as-recipe-order b)))

(defn diff-of
  "The staged diff: what changes, line by line, in the household's own
  words. Public because it is what the create hook writes and what a
  test reads back — and pure, so the same two orders answer the same
  sentences in a scenario, in a suite and in the house."
  [current proposed]
  (feed/order-diff (as-recipe-order current) (as-recipe-order proposed)))

;; ── addresses, read rather than guessed ─────────────────────────────

(defn- address?
  "Is this string `/api/<collection>/<id>` — the one shape a citation
  may wear, and the shape the household's own URL bar already carries?

  It checks the SHAPE and not the registry, which is the one place
  this file departs from `insight/cites-what-it-claims`. A registry
  consult would be a `:reads [:storage]` on the create door, and the
  create door is where three other walls stand that a
  declaration-time world can answer completely — so the whole birth of
  a proposal would drop out of the no-database tier to re-check a fact
  the address shape already tells. What a bad collection name costs is
  a dead link on a card; what the tier costs is every staging law
  going unproven until a suite runs."
  [s]
  (let [parts (str/split (str s) #"/")]
    (and (= 4 (count parts))
         (= "" (nth parts 0))
         (= "api" (nth parts 1))
         (not (str/blank? (nth parts 2)))
         (not (str/blank? (nth parts 3)))
         (not (str/includes? (str s) "?")))))

;; ── the create walls ────────────────────────────────────────────────
;;
;; SHAPE FIRST, WORLD NEXT, PACE LAST. A malformed proposal hears what
;; is wrong with its own body before it hears anything about the
;; house, and hears that the fridge is full only once it is worth
;; putting on the fridge — insight's ordering, and its reason: the cap
;; counts ROWS, so a refused create spends nothing.
;;
;; The first three need no storage at all, which is why the two
;; staging scenarios below are the two that refuse THERE: what they
;; claim is true whatever order the engine they meet is reading, so
;; they survive the conformance tier the fourth wall drops the whole
;; door into.

;; NO :open ON THE TWO SHAPE WALLS, and the absence is deliberate.
;; `:open` is the closure rule's escape hatch and it means one
;; particular thing everywhere else in this tree — "the legal tokens
;; are the registry's, one GET away, and enumerating them into the
;; form would duplicate the registry" — which the usability battery
;; then re-raises as an opinion (`effort-honesty`: the engine knows
;; the answers and the human is typing them from memory). Neither of
;; these judges a token set at all: one judges SHAPE against another
;; door's schema, the other judges the shape of an address. Wearing
;; `:open` would have been claiming a gap that is not there, and
;; earning a warning nobody could ever clear.

(g/defguard the-prepared-input-fits-the-door
  {:judges [:label :order]
   :vars [:problems]
   :explain "That is not something feed_recipe's revise door would take, so nobody could ever apply it: {problems}"}
  [_row inp _ctx]
  ;; feed-recipe/recipe-input IS the revise input — the same value,
  ;; not a copy — so this cannot drift away from the door it is about
  (if-some [errs (schema/errors feed-recipe/recipe-input
                                {:label (:label inp) :order (:order inp)})]
    (t/deny {:vars {:problems (pr-str errs)}})
    (t/allow)))

(g/defguard the-order-will-assemble
  {:judges [:order]
   :vars [:problems]
   :open "The law is feed/check-recipe! — the same four assembly checks that refuse a recipe at its own doors: exactly one seam, the archive last and bottomless, sections in census order, every population one this engine holds."
   :explain "That order will not assemble, so nobody could ever apply it: {problems}"}
  [_row inp _ctx]
  (try
    (feed/check-recipe! {:order (as-recipe-order (:order inp))})
    (t/allow)
    (catch clojure.lang.ExceptionInfo e
      (if (:waymark10/definition-error (ex-data e))
        (t/deny {:vars {:problems (str/replace (ex-message e)
                                               #"^definition error: " "")}})
        (throw e)))))

(g/defguard it-cites-what-it-read
  {:judges [:evidence]
   :vars [:count]
   :explain "A change to the house's own order with nothing behind it is a preference — cite the rows you read, at least one, each as an address like /api/tasks/01H… ({count} usable given)."}
  [_row inp _ctx]
  (let [ev (into [] (remove str/blank?) (map str (:evidence inp)))]
    (if (and (seq ev) (every? address? ev))
      (t/allow)
      (t/deny {:vars {:count (count (filter address? ev))}}))))

(def open-cap
  "How many staged changes one principal may have waiting at once.
  THREE, and the number is the whole of the wall: the decide section
  is the scarcest surface in the building, the create door is open to
  any named principal (the insight precedent — the asking door is
  never concealed), and a surface that can be filled is a surface that
  will be. A composer with three proposals already on the fridge has a
  ranking problem, and this is where it finds out.

  It is the OPEN cap rather than a rate, which is `pacing-guards`' own
  reading of which of the two a household actually feels: a verdict on
  what is already waiting comes before a new ask. It counts ROWS, so
  unlike the in-process pacing atoms it is shared across processes."
  3)

(g/defguard staged-changes-are-few
  {:reads [:principal :recipe_proposal]
   :vars [:cap :waiting]
   :explain "You have {cap} changes already waiting on the house: {waiting}. A proposal is a question, and a question nobody answered is not a reason to ask another — the decide section is the scarcest surface in the building. Let one of those be answered first, or decline it yourself."}
  [_row _inp ctx]
  ;; the storage-free probe never spends a slot — letters-are-paced's
  ;; own discipline, and the same one pacing-guards keeps
  (if (nil? (:find ctx))
    (t/allow)
    (let [pid (:id (:principal ctx))
          open ((:find ctx) kind {:proposed_by pid :state "offered"}
                {:limit (inc (long open-cap))})]
      (if (< (count open) (long open-cap))
        (t/allow)
        (t/deny {:vars {:cap open-cap
                        :waiting (str/join ", " (sort (map :id open)))}})))))

(g/defguard the-staging-is-current
  {:judges [:target_id :current_order]
   :reads [:feed_recipe]
   :vars [:problem]
   :open "A proposal is staged against the order actually in force — the one riding the feed document at recipe.order — so the diff describes the world the household is living in."
   :explain "This is not staged against what the house reads today: {problem}"}
  [_row inp ctx]
  (let [read' (:read ctx)
        find' (:find ctx)
        tid (some-> (:target_id inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (nil? read')
      ;; the storage-free probe advertises optimistically — the
      ;; saved_view / insight posture, and the write path always
      ;; carries the consult
      (t/allow)
      (if tid
        (let [row (read' :feed_recipe tid)]
          (cond
            (nil? row)
            (deny (str "this house serves no feed_recipe " tid
                       " — stage against a row that exists, or against the"
                       " built-in by leaving the target empty."))

            (not= "active" (name (:state row)))
            (deny (str "feed_recipe " tid " is "
                       (name (:state row)) ", not active — a retired order"
                       " is not what anybody is reading."))

            (not (same-order? (:current_order inp)
                              (get-in row [:data :order])))
            (deny (str "/api/feed_recipes/" tid " does not say today what"
                       " this proposal says it says. Read recipe.order out"
                       " of the feed document again and stage against that."))

            :else (t/allow)))
        ;; NO TARGET: the proposal stages a CREATE against the order
        ;; this deployment ships with, which is production's own world
        ;; — no row exists at all. The one thing that can be checked is
        ;; that it is still true.
        (let [held (when find'
                     (find' :feed_recipe {:scope "household" :state "active"}
                            {:limit 1}))]
          (cond
            (seq held)
            (deny (str "this house has written its own order already"
                       " (/api/feed_recipes/" (:id (first held)) ") — stage"
                       " against that row rather than against the built-in,"
                       " or the change would throw away what the house"
                       " wrote."))

            (try (feed/check-recipe!
                  {:order (as-recipe-order (:current_order inp))})
                 false
                 (catch clojure.lang.ExceptionInfo _ true))
            (deny (str "the order you say the house reads today will not"
                       " assemble, so it is not the one in force. Copy"
                       " recipe.order out of the feed document."))

            :else (t/allow)))))))

;; ── the verdict walls ───────────────────────────────────────────────

(def the-proposer-does-not-decide
  "The four-eyes wall, doing the load-bearing work. It is the SAME
  guard `desugar-decision` would have minted — `g/not-the-field`, by
  the factory, so the law is the sugar's law and not a lookalike — and
  it is what makes 'an agent never writes the order' structural rather
  than promised: whoever staged a proposal is incapable of answering
  it, so the tap belongs to somebody else by construction."
  (g/not-the-field
   :proposed_by
   {:name :the-proposer-does-not-decide
    :explain "The change is yours; the answer is the household's. Whoever staged this cannot be the one to apply or decline it."}))

(g/defguard a-person-answers
  {:reads [:principal]
   :explain "A person answers a proposed change to the feed's order — both ways round. A house running two agents would otherwise have one stage the change and the other tap it through, or quietly decline what a member never saw, and the recipe's wall would have been walked around rather than kept."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind, so the render probe and
  ;; the real invoke read the same fact — feed-recipe's own posture,
  ;; one kind over. This is a SECOND wall and not a copy of that one:
  ;; that one is about WRITING the order, this one is about ANSWERING
  ;; somebody else's staged change, and the two sentences differ
  ;; because the two doors do. feed_recipe's own guard still stands
  ;; behind this one, inside the apply, and would refuse anyway — the
  ;; belt is here so the refusal arrives at the door a person tapped,
  ;; in words about that door.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

(g/defguard the-leash-has-not-run-out
  {:reads [:now]
   :vars [:expired_at]
   :open "A staged proposal stands for seven days. Enforcement is live at the door: a lapsed proposal applies nothing, whatever state its row is still resting in."
   :explain "This proposal lapsed at {expired_at}. The order it was staged against has had a week to move; stage it again against what the house reads today, and the house will see the diff that is true now."}
  [row _inp ctx]
  (let [exp (get-in row [:data :expires_at])]
    (if (and exp (not (pos? (compare exp (:now ctx)))))
      (t/deny {:vars {:expired_at (str exp)}})
      (t/allow))))

(g/defguard the-leash-has-run-out
  {:reads [:now]
   :vars [:expires_at]
   :open "Expiring is bookkeeping: it tidies a row the household has already stopped being asked about, and it cannot be used to take a live proposal off the table."
   :explain "This proposal is still live until {expires_at} — decline it if the house does not want it; expiring is for the ones the clock has already answered."}
  [row _inp ctx]
  (let [exp (get-in row [:data :expires_at])]
    (if (and exp (pos? (compare exp (:now ctx))))
      (t/deny {:vars {:expires_at (str exp)}})
      (t/allow))))

(g/defguard the-order-has-not-moved
  {:reads [:feed_recipe]
   :vars [:problem]
   :open "The diff a person reads is a description of the world they are reading it in. A proposal staged against an order that has since changed is re-staged, never quietly applied over the top."
   :explain "The order changed since this was staged — {problem} Re-stage against the current order and the diff will say what is true now."}
  [row _inp ctx]
  (let [read' (:read ctx)
        find' (:find ctx)
        d (:data row)
        tid (some-> (:target_id d) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (nil? read')
      (t/allow)
      (if tid
        (let [target (read' :feed_recipe tid)]
          (cond
            (nil? target)
            (deny (str "/api/feed_recipes/" tid " is not there any more."))

            (not= "active" (name (:state target)))
            (deny (str "/api/feed_recipes/" tid " is "
                       (name (:state target)) " now, not active."))

            (not (same-order? (:current_order d)
                              (get-in target [:data :order])))
            (deny (str "/api/feed_recipes/" tid " reads differently now"
                       " than it did when this was staged."))

            :else (t/allow)))
        (let [held (when find'
                     (find' :feed_recipe {:scope "household" :state "active"}
                            {:limit 1}))]
          (if (seq held)
            (deny (str "this house has written its own order since"
                       " (/api/feed_recipes/" (:id (first held))
                       "), and this was staged against the built-in."))
            (t/allow)))))))

;; ── the handlers ────────────────────────────────────────────────────

(defhandler stage-the-proposal
  [row ctx]
  ;; the three birth stamps, and none of them is the caller's to give:
  ;; WHO staged it (a proposal that could name somebody else as its
  ;; author is a proposal that can frame them), HOW LONG it stands,
  ;; and WHAT CHANGES — the diff, computed by the engine from the two
  ;; orders in the row, so the sentence a person taps under is the
  ;; engine's own reading and never the stager's description of it
  (let [d (:data row)]
    (-> row
        (assoc-in [:data :proposed_by] (:id (:principal ctx)))
        (assoc-in [:data :expires_at]
                  (.plusSeconds ^Instant (:now ctx)
                                (* 86400 (long leash-days))))
        (assoc-in [:data :diff] (diff-of (:current_order d) (:order d))))))

(defhandler apply-the-order
  [row _inp ctx]
  ;; THE TAP IS THE WRITE. ctx :invoke and ctx :create carry the OUTER
  ;; principal, so what lands on feed_recipe is the MEMBER's write:
  ;; their name on the transition, their principal at the recipe's own
  ;; guards, and `written-by-a-person` passing because a person is
  ;; writing. Same transaction, so a refusal inside rolls the whole
  ;; tap back and the proposal does not read as applied.
  (let [d (:data row)
        input {:label (:label d) :order (:order d)}
        tid (some-> (:target_id d) str str/trim not-empty)
        res (if tid
              ;; feed_recipe's :revise declares an :edit, and an edit
              ;; IMPLIES the fence (resource.clj: "an Edit implies the
              ;; fence"), so this hands over the target's own current
              ;; etag exactly as an honest client would — the
              ;; worksheet's spelling, which is the other place in this
              ;; tree that applies a staged change through a target
              ;; kind's own doors. `the-order-has-not-moved` has
              ;; already read the row this version belongs to; the
              ;; fence is the second half of the same sentence, said in
              ;; HTTP's words rather than the household's.
              (let [target ((:read ctx) :feed_recipe tid)]
                ((:invoke ctx) :feed_recipe tid :revise input
                 {:if-match (inv/etag :feed_recipe tid (:version target))}))
              ((:create ctx) :feed_recipe (assoc input :scope "household")))
        rid (str (get-in res [:row :id]))]
    (-> row
        (assoc-in [:data :decided_by] (:id (:principal ctx)))
        (assoc-in [:data :applied_to] (str "/api/feed_recipes/" rid)))))

(defhandler record-the-verdict
  [row _inp ctx]
  (assoc-in row [:data :decided_by] (:id (:principal ctx))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; TWO TIERS, and which scenario lands in which is read off the
;; declarations rather than chosen — scenario.clj's own rule: a door
;; whose every guard reads only the clock and the caller is judged in
;; this process with no database at all.
;;
;; So the walls that stand on `decline` and `expire` are proved for
;; free, in the same breath as the usability warnings, and they are
;; the same two walls `apply` carries — one guard object, two doors.
;; The create door defers, because its last two walls read rows — the
;; house's recipes and this stager's own waiting proposals — and its
;; two scenarios are chosen to be the two that refuse BEFORE either is
;; reached, so what they claim is true whatever order the engine they
;; meet is reading and whatever is already on its fridge.
;;
;; WHAT IS NOT HERE, AND WHERE IT IS INSTEAD. Three of this kind's
;; laws cannot be a scenario at all, and the reason is structural
;; rather than an omission: `apply` carries a row-reading wall, so it
;; is conformance tier; and a conformance-tier ACTION scenario stages
;; its row through the kind's own create door AS THE WALKER, which
;; would stamp the walker's name into `proposed_by` and make the
;; four-eyes wall answer about the wrong person. Those three — the
;; whole apply path, the stale-target refusal, and the leash at the
;; tap — are proved where they can be proved honestly: the feed pack's
;; `:feed/staged-proposals` obligation walks the first two from the
;; wire, and `recipe-proposal-test` judges the leash against a clock
;; it holds.

(def ^:private a-staged-change
  {:proposal "Three media cards crowd out everything else in do-now"
   :label "The school-run morning"
   :evidence ["/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
   :current_order [{:section "do_now" :population "next_actions" :take 5}
                   {:section "seam" :sentence "That's the house, caught up."}]
   :order [{:section "do_now" :population "next_actions" :take 3}
           {:section "seam" :sentence "That's the house, caught up."}]
   :proposed_by "composer"
   :expires_at (Instant/parse "2026-09-01T09:00:00Z")})

(defscenario the-stager-does-not-answer-its-own-proposal
  "Four eyes, and it is the whole bead: an agent that could stage a
   change and answer it would be writing the feed's own order under a
   member's roof. The wall is structural, not a policy the composer is
   trusted to keep — and it stands on BOTH answers, because quietly
   declining what a member never saw is the same power in reverse."
  {:kind    :recipe_proposal
   :attempt :decline
   :row     {:state :offered :data a-staged-change}
   :as      {:id "composer" :type :agent}
   :expect  {:refused :the-proposer-does-not-decide}})

(defscenario an-agent-does-not-answer-a-staged-change
  "The second wall behind the four-eyes one, and the reason a house
   running two agents is no different from a house running one: the
   answer belongs to a person either way."
  {:kind    :recipe_proposal
   :attempt :decline
   :row     {:state :offered :data a-staged-change}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :a-person-answers
             :because "A person answers"}})

(defscenario an-answered-proposal-does-not-come-back
  "Applied is an answer and it is kept. The machine itself refuses the
   second question, with no guard behind it, which is the strongest
   way a promise can be made."
  {:kind    :recipe_proposal
   :attempt :decline
   :row     {:state :applied :data a-staged-change}
   :as      {:id "iris" :type :person}
   :expect  {:refused :out-of-state
             :because "Offered"}})

(defscenario a-live-proposal-is-not-expired-out-of-the-way
  "Expiring is bookkeeping, never a way to take a live proposal off
   the household's table. Only the clock retires a proposal; a person
   who does not want it declines it, on the record."
  {:kind    :recipe_proposal
   :attempt :expire
   :at      "2026-08-25T09:00:00Z"
   :row     {:state :offered :data a-staged-change}
   :as      {:id "iris" :type :person}
   :expect  {:refused :the-leash-has-run-out
             :because "still live until"}})

(defscenario a-proposal-that-would-not-assemble-is-refused-now
  "A change nobody could ever apply is refused where it is written,
   not where it is tapped — a button that fails is worse than a button
   that was never offered."
  {:kind    :recipe_proposal
   :attempt :create
   :input   {:proposal "Say we are caught up, twice"
             :label "Twice caught up"
             :evidence ["/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :current_order [{:section "do_now" :population "next_actions" :take 5}
                             {:section "seam" :sentence "That's the house, caught up."}]
             :order [{:section "do_now" :population "next_actions" :take 5}
                     {:section "seam" :sentence "That's the house, caught up."}
                     {:section "seam" :sentence "Really, caught up."}]}
   ;; A PERSON, and deliberately: the staging walls judge the BODY, so
   ;; they say the same thing to whoever wrote it. Who may reach this
   ;; door at all is the leash's question and a different sentence.
   :as      {:id "iris" :type :person}
   :expect  {:refused :the-order-will-assemble
             :because "exactly one entry carries :seam true"}})

(defscenario a-proposal-with-nothing-behind-it-is-refused
  "A change to the order the whole house reads in is not a preference:
   cite the rows you read, or do not ask."
  {:kind    :recipe_proposal
   :attempt :create
   :input   {:proposal "It would feel better this way"
             :label "A feeling"
             :current_order [{:section "do_now" :population "next_actions" :take 5}
                             {:section "seam" :sentence "That's the house, caught up."}]
             :order [{:section "do_now" :population "next_actions" :take 3}
                     {:section "seam" :sentence "That's the house, caught up."}]}
   :as      {:id "iris" :type :person}
   :expect  {:refused :it-cites-what-it-read}})

;; ── the prose the doors wear ────────────────────────────────────────
;;
;; Spelled ONCE and worn by both the row schema and the create model,
;; the way feed_recipe's own `prose` map is — three copies of one
;; sentence is three places for it to drift.

(def ^:private prose
  {:proposal
   {:x-display
    {:label "What you are proposing"
     :help "One sentence, in the words the household would use — what you noticed and what you want changed about the order the morning is read in. Somebody is reading this on a phone between two other things, so say the thing rather than leading up to it."}}
   :label
   {:x-display
    {:label "What the order will be called"
     :help "The name the house's order carries once this is applied — \"the school-run morning\", \"Colton's own\". Short enough to read on a phone. This is the label feed_recipe's own revise door takes, so it is judged here by that door's schema."}}
   :target_id
   {:x-display
    {:label "The order being changed"
     :help "The feed_recipe row this revises, by its own id. Leave it EMPTY to stage against the built-in — the order this deployment ships with, which is what a house that has never written its own order is reading. The card says which of the two it is, because they are different proposals."}}
   :current_order
   {:examples [feed-recipe/order-example]
    :x-display
    {:label "The order the house reads today"
     :help "What you are staging against, copied out of the feed document at recipe.order — exactly the shape it rides there. This is not decoration: the diff is computed from it, and a proposal that disagrees with what the house actually reads is refused here rather than applied on top of somebody else's edit."}}
   :order
   {:examples [feed-recipe/order-example]
    :x-display
    {:label "The order you propose in its place"
     :help "The whole feed, top to bottom, as it would read once this is applied — one entry per line, and the vector's order IS the page's order. Start from the order above and change what you mean to change. Exactly one line is the seam; the bottomless line is last; the sections keep census order; every population is one this engine holds."}}
   :evidence
   {:x-display
    {:label "What you read"
     :help "The rows this proposal is built on, as addresses — /api/tasks/01H… — one per row you actually looked at. At least one, always: the house can follow them, and a change to the order everybody reads in should be answerable by somebody who wants to check."}}
   :diff
   {:x-display
    {:label "What changes"
     :help "The two orders read side by side, line by line, written by the engine when this was staged. Nobody types this and nobody can edit it — it is what the house is being asked to say yes to."}}
   :proposed_by {:x-display {:raw true :label "Staged by"}}
   :decided_by {:x-display {:raw true :label "Answered by"}}
   :applied_to {:x-display {:raw true :label "The order it landed on"}}
   :expires_at
   {:x-display
    {:label "Stands until"
     :help "When the house stops being asked. Seven days from staging, written by the engine — a proposal nobody answered in a week is describing a week-old house."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

(defresource recipe-proposal
  {:kind :recipe_proposal
   :plural "recipe_proposals"
   :nav :system
   :states [:offered :applied :declined :expired]
   :initial :offered
   :terminal #{:applied :declined :expired}
   :summary "{data.proposal} · staged by {data.proposed_by} · {state}"
   :label-template "{data.proposal}"
   :display {:title "Proposed change to the feed's order"}
   ;; the way to the order this is about, and the way to the order it
   ;; became. A proposal staged against the BUILT-IN names no target,
   ;; so that link simply omits — the framework's own rule, and the
   ;; same posture task's :origin link takes.
   :links [{:rel "target" :kind :feed_recipe
            :href "/api/feed_recipes/{data.target_id}"
            :summary "The order this proposal changes"}
           {:rel "applied" :href "/#{data.applied_to}"
            :summary "The order this proposal became, once a member applied it"}]
   :schema
   [:map
    (entry :proposal {} [:string {:min 1 :max 240}])
    (entry :label {} [:string {:min 1 :max 60}])
    (entry :target_id {:optional true :filter #{:eq}}
           [:maybe [:string {:max 64}]])
    (entry :current_order {} feed-recipe/order-schema)
    (entry :order {} feed-recipe/order-schema)
    (entry :evidence {:optional true}
           [:maybe [:vector [:string {:min 1 :max 200}]]])
    ;; ENGINE-WRITTEN, all four. They are in the row schema because
    ;; they are the row's document; they are out of the create model
    ;; below because none of them is anybody's to supply.
    (entry :diff {:optional true}
           [:maybe [:vector [:string {:max 600}]]])
    (entry :proposed_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    (entry :decided_by {:optional true} [:maybe [:string {:max 128}]])
    (entry :applied_to {:optional true} [:maybe [:string {:max 200}]])
    (entry :expires_at {:optional true :filter #{:before} :sort true}
           [:maybe :waymark/instant])]
   :create-schema
   [:map
    (entry :proposal {} [:string {:min 1 :max 240}])
    (entry :label {} [:string {:min 1 :max 60}])
    (entry :target_id {:optional true} [:maybe [:string {:max 64}]])
    (entry :current_order {} feed-recipe/order-schema)
    (entry :order {} feed-recipe/order-schema)
    (entry :evidence {:optional true}
           [:maybe [:vector [:string {:min 1 :max 200}]]])]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "offered"}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; READ-ONLY, and the empty :actions is the whole of the decision.
   ;;
   ;; Whoever staged a proposal reads their own with no grant, because
   ;; a composer that could not see what it staged could not tell an
   ;; applied change from a declined one and would stage it again
   ;; tomorrow — insight's courtesy, taken whole.
   ;;
   ;; What is NOT taken is insight's open CREATE door. `insight`'s
   ;; own-surface carries "create", so an unleashed agent may publish a
   ;; finding and the daily cap is the only wall; this kind departs
   ;; from that precedent deliberately. A finding is a sentence the
   ;; household reads. A proposal is a prepared WRITE that a member
   ;; enacts with one tap, and which agents may put one of those in
   ;; front of the house is a decision the house should get to make —
   ;; so staging rides an ordinary grant (`{:kind "recipe_proposal"
   ;; :actions ["create"]}`), which agents reach through the MCP door
   ;; like any other leashed write. Humans are unscoped and stage
   ;; without one, as they always could.
   ;;
   ;; The verdict doors are not here either, and could not be: the
   ;; four-eyes wall refuses the stager at both of them, so listing
   ;; them would advertise doors that answer 409 to the only principal
   ;; the courtesy is for. A stager wanting its own change back has no
   ;; verb yet; that is a `withdraw`, filed rather than smuggled in.
   :own-surface {:by :proposed_by :actions #{}}
   :on-create stage-the-proposal
   ;; shape first, world next, PACE LAST: a malformed proposal hears
   ;; what is wrong with it rather than that the fridge is full, and
   ;; because the cap counts ROWS a refused create spends nothing
   ;; (insight's ordering, inherited whole)
   :create-guards [the-prepared-input-fits-the-door
                   the-order-will-assemble
                   it-cites-what-it-read
                   the-staging-is-current
                   staged-changes-are-few]
   :actions
   {;; BOTH ANSWERS ARE ONE TAP AND NEITHER TAKES A NOTE. A note would
    ;; make the verdict a `recall` demand and feed/split-verbs would
    ;; move it off the card into `heavier` (waymark-iqa.4's finding,
    ;; inherited whole) — and both of these answers are meant to be
    ;; tapped, from the feed, by somebody standing up.
    :apply {:from #{:offered} :to :applied
            :guards [the-proposer-does-not-decide
                     a-person-answers
                     the-leash-has-not-run-out
                     the-order-has-not-moved]
            :handler apply-the-order
            :touches [{:kind :feed_recipe :action :revise}
                      {:kind :feed_recipe :action :create}]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Applying writes the order shown, through the recipe's own door, with YOUR name on it — the next feed read is in the new order. The way back is the recipe's own doors: revise it, or retire it and the house reads what this deployment ships with."}
            :display {:label "Apply" :style :primary :order 1
                      :description "Write this order, as you — the change lands on the recipe through its own revise door and the transition carries your name"}}
    :decline {:from #{:offered} :to :declined
              :guards [the-proposer-does-not-decide a-person-answers]
              :handler record-the-verdict
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "The proposal leaves the feed and stays on record. Nothing is deleted and nothing is hidden; the house has simply answered it."}
              :display {:label "Decline" :order 2
                        :description "Say no to this change — it stops asking, and the record keeps who said so"}}
    ;; the bookkeeping verb. It changes nothing anybody can feel: the
    ;; population already stopped offering a lapsed proposal and the
    ;; apply door already refuses one. What it buys is a row whose
    ;; state says what is true, which is what makes the collection
    ;; readable a month later.
    :expire {:from #{:offered} :to :expired
             :guards [the-leash-has-run-out]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "The clock already answered this one; the row now says so."}
             :display {:label "Expire" :order 8
                       :description "Tidy a proposal the week ran out on"}}}
   :scenarios [the-stager-does-not-answer-its-own-proposal
               an-agent-does-not-answer-a-staged-change
               an-answered-proposal-does-not-come-back
               a-live-proposal-is-not-expired-out-of-the-way
               a-proposal-that-would-not-assemble-is-refused-now
               a-proposal-with-nothing-behind-it-is-refused]})
