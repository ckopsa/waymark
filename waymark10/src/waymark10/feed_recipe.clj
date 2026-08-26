(ns waymark10.feed-recipe
  "The feed's recipe, as a row (waymark-4yn). Until now the order the
  feed reads in was an ENGINE OPT — `(:feed eng feed/default-recipe)`,
  read once at the route's build site — so every taste adjustment was
  a code edit and a deploy. The owner asked for runtime tuning, and
  this is the saved_view answer to that ask: user-authored
  configuration as a declared KIND, with guards at its own doors.

  `saved_view` is the precedent and it is followed line for line. A
  developer declares the recipe once per deploy (`feed/default-recipe`,
  or the app's `:feed` opt); a `feed_recipe` row is the same shape
  authored at RUNTIME by a person, and everything a kind gets for free
  — storage, forms, grants, events, transitions — comes with it. The
  one departure is enrollment: saved_view is app-opt-in because a
  saved view targets APP kinds and an app may reasonably not want the
  surface at all, while a feed recipe names the FEED's own populations
  and census. The vocabulary belongs to the module, so the kind rides
  the module (`waymark10.modules`, :feed, `:enroll :always`).

  ── the resolution order ──

  One lookup per feed read, in `for-reader` below:

    1. the reading member's own ACTIVE row (scope \"mine\", owner =
       the principal id), newest first;
    2. the household's ACTIVE default (scope \"household\");
    3. the engine opt — the built-in, which stays the ultimate
       fallback so an engine holding no rows at all still serves.

  NO CACHE, deliberately, and the trade is recorded: the day-stable
  law would make a per-day memo safe against the SEED, but a recipe is
  edited in the middle of a morning and the whole point of this bead
  is that the next read shows the change. A cache measured in hours
  would make the editor feel broken; the cost it saves is one indexed
  row read beside the dozen population queries the same request
  already runs.

  ── the assembly checks, unchanged, moved to the doors ──

  `feed/check-recipe!` is the same function it always was — exactly
  one seam, at most one bottomless and it is last, sections in census
  order, every population one this engine holds, and (waymark-8um.3)
  the contest's two numbers are numbers — and it now runs as a
  GUARD at create and at revise (`the-assembly-checks-pass`), refusing
  with the sentences it already knew. An invalid recipe therefore
  cannot be stored, and the feed cannot break at read time from a bad
  row. `for-reader` re-judges anyway and falls back to the built-in
  when a stored row has gone stale — the saved_view lenient-render
  tradition: a redeploy that retires a population strands a row, and a
  stranded row must not take the morning down with it. The stamp says
  so out loud when it happens.

  ── AND THE CONTEST'S TWO NUMBERS (waymark-8um.3) ──

  Laws v3 law 5: *the ranking formula is DATA the owner can read.* The
  formula is `{:window_days :cools_after}` — how far back the contest
  counts a member's own looking, and how many days a card may sit
  untouched before it steps back inside its own line — and it lives
  HERE, on the row, because this is where data the household reads is
  kept. Riding the recipe rather than an engine opt is what makes it
  tunable through the form that already exists, diffable by
  `recipe_proposal`, walled by `written-by-a-person` below, and
  revertible out of this row's own transitions. None of those four had
  to be built.

  Absent leaves the deployment's own numbers standing; the way to say
  NO contest is `cools_after 0`, which is a number a person can see
  rather than a key they have to know to delete.

  ── HUMAN-WRITABLE ONLY ──

  `written-by-a-person` is the third law's wall at this kind's doors:
  an AGENT principal may not create, revise, retire or restore a
  recipe. A composer holding a recipe-write grant is a ranking model
  editing its own editorial frame, which is the backdoor the whole
  no-scoring-function posture exists to keep shut. The lawful path for
  an agent is the one the feed already has — publish an `insight`, with
  its citations and one physical next step, and a member answers it
  with a tap. The scenario below proves the refusal without a database.

  ── the stamp ──

  `for-reader` answers `{:recipe … :source {…}}`, and the source rides
  the feed document under `recipe.source`: which row answered, its id
  and version, or \"built-in\". A mid-day edit is therefore VISIBLE —
  explain stays truthful about whose order it is narrating — and the
  row's own transitions log is the tuning history, with one-tap revert
  through the ordinary doors.

  Lifecycle: active → retired, reversible both ways (:undo pairs).
  Retiring the household default is how a house goes back to what the
  deployment ships with."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.feed :as feed]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def kind
  "The feed_recipe kind keyword — the definite marker every read below
  addresses the registry by, never a name string."
  :feed_recipe)

(def recipe-fields
  "The authored surface :revise overwrites wholesale — the same fields
  the write gate judges, so what is stored is exactly what was judged.
  `:scope` and `:owner` stay OUT: a row never changes whose morning it
  arranges, and the owner is the engine's stamp rather than anybody's
  input.

  `:formula` joined with waymark-8um.3 and it belongs here for the
  reason the whole kind exists: law 5 says the contest's rule is DATA
  the household can read, and this is where data the household reads is
  kept. Riding this row rather than an engine opt is what makes the
  contest tunable through the form that already exists, diffable by
  `recipe_proposal`, walled by `written-by-a-person`, and revertible
  out of the row's own transitions — four properties nobody had to
  build a second time. `:revise` prefills it, so a person editing the
  order never silently clears the numbers.

  `:crown_rank` joined with waymark-1uv.2 for the same reason one
  section up: the crown's rank is six numbers a person can read (four
  at 1uv.2; `:early` with waymark-1uv.10; `:judged` with
  waymark-1uv.6), and they live where the contest's two live so the
  same form, the same diff, the same wall and the same transitions
  govern them. `:insight_rank` joined with waymark-1uv.8, the findings'
  six, for the same reason once more.

  `:tickler_rank` joined with waymark-1uv.9 for the same reason again:
  the ticklers line ranks its due pile by five numbers a person can
  read, and they live where the crown's six live."
  [:label :order :formula :crown_rank :insight_rank :tickler_rank])

;; ── the wire shape ↔ the recipe map ─────────────────────────────────

(defn- blank->nil [s] (when-not (str/blank? (str s)) (str s)))

(defn- line-of
  "One stored order entry as the recipe map `feed/check-recipe!` and
  `feed/document` read. Section \"seam\" IS the seam — the machine sets
  `:seam true` rather than asking a person to tick a box beside a word
  that already says it, which is one fewer way to get a recipe
  half-right."
  [e]
  (let [sect (keyword (str (:section e)))]
    (if (= :seam sect)
      (cond-> {:seam true}
        (blank->nil (:sentence e)) (assoc :sentence (blank->nil (:sentence e)))
        (blank->nil (:says e)) (assoc :says (blank->nil (:says e))))
      (cond-> {:section sect}
        (blank->nil (:population e)) (assoc :population
                                            (keyword (blank->nil (:population e))))
        (some? (:take e)) (assoc :take (:take e))
        (seq (:kinds e)) (assoc :kinds (mapv keyword (:kinds e)))
        (blank->nil (:says e)) (assoc :says (blank->nil (:says e)))
        (true? (:bottomless e)) (assoc :bottomless true)))))

(defn recipe-of
  "One feed_recipe row's data as the recipe map the feed reads —
  saved_view's `view-of`, one kind over. The ORDER is the whole of what
  a row states, and the two other keys of a recipe map stay the
  deployment's:

  `:salt` cannot be here. It is the seed's input, and a recipe that
  could rewrite the salt would be a re-roll button — a reader turning
  the day's order over until it liked one, which is the ranking model
  arriving through the editor instead of through a query parameter
  (`encode-cursor`'s own paragraph, one door along).

  `:zone` is not here either, and that one is a judgment rather than a
  wall: when the feed rolls is a fact about where the house IS, not a
  taste the morning is tuned by. It stayed an engine opt, where a
  deployment states it once. (The battery agreed from the other
  direction: a free-text zone box is a blank rectangle a guard judges
  against a vocabulary the JVM enumerates exhaustively.)

  `:formula` IS here (waymark-8um.3), and the difference from those two
  is the difference law 5 draws: the salt is the seed's input and the
  zone is where the house stands, but *how long a card I have already
  scrolled past keeps its place* is a taste, said in two numbers, in
  the household's own hand. Absent leaves the deployment's own numbers
  standing (`usable` merges over the built-in); the way to say NO
  contest is `cools_after 0`, which is a number a person can see rather
  than a key they have to know to delete.

  `:crown-rank` is here too (waymark-1uv.2), the same sentence at the
  crown: *which of the composed weeks is worth my Saturday* is a taste,
  said in five numbers, in the household's own hand."
  [data]
  (let [numbers (fn [m] (into {}
                              (keep (fn [[k v]]
                                      (when (some? v)
                                        [k (if (string? v) (parse-long v) v)])))
                              m))]
    (cond-> {:order (mapv line-of (:order data))}
      (map? (:formula data))
      (assoc :formula
             (numbers {:window-days (get-in data [:formula :window_days])
                       :cools-after (get-in data [:formula :cools_after])}))
      (map? (:crown_rank data))
      (assoc :crown-rank
             (numbers {:declared (get-in data [:crown_rank :declared])
                       :cooled (get-in data [:crown_rank :cooled])
                       :declined (get-in data [:crown_rank :declined])
                       :fresh (get-in data [:crown_rank :fresh])
                       :early (get-in data [:crown_rank :early])
                       :judged (get-in data [:crown_rank :judged])}))
      ;; …and the findings' six (waymark-1uv.8), the same way
      (map? (:insight_rank data))
      (assoc :insight-rank
             (numbers {:diagnosis (get-in data [:insight_rank :diagnosis])
                       :declared (get-in data [:insight_rank :declared])
                       :cooled (get-in data [:insight_rank :cooled])
                       :dismissed (get-in data [:insight_rank :dismissed])
                       :declined (get-in data [:insight_rank :declined])
                       :fresh (get-in data [:insight_rank :fresh])}))
      ;; …and the fridge's five (waymark-1uv.9), the same sentence
      (map? (:tickler_rank data))
      (assoc :tickler-rank
             (numbers {:overdue (get-in data [:tickler_rank :overdue])
                       :not_now (get-in data [:tickler_rank :not_now])
                       :cooled (get-in data [:tickler_rank :cooled])
                       :front_door (get-in data [:tickler_rank :front_door])
                       :age (get-in data [:tickler_rank :age])})))))

;; ── the guards ──────────────────────────────────────────────────────

(g/defguard written-by-a-person
  {:reads [:principal]
   :explain "The feed's order is written by a person. An agent that could rewrite the recipe would be a ranking model editing its own editorial frame — publish an insight instead, with its citations and its one next step, and a member answers it with a tap."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind, so the render probe and
  ;; the real invoke read the same fact and no probe path opens a door
  ;; (reentry-targets-durable's posture). :system is the ENGINE's own
  ;; actor — a migration, a seed, the conformance walker — and is not
  ;; what this wall is about; the wall is about the composer.
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

(g/defguard the-assembly-checks-pass
  ;; :judges names :order alone even though check-recipe! now judges
  ;; the formula too (waymark-8um.3). `:judges` beside an `:open` is a
  ;; claim that this guard holds legal tokens the schema could not
  ;; publish — and the formula's are published: two ints, 1–365 and
  ;; 0–365, in the form's own schema. Naming it here would claim a gap
  ;; that is not there and earn an effort-honesty warning nobody could
  ;; ever clear, which is 0k4's own sentence about `:open` on a shape
  ;; wall, one door over.
  {:judges [:order]
   :vars [:problems]
   :open "The law is feed/check-recipe! — the same assembly checks that used to refuse the BOOT: exactly one seam, the archive last and bottomless, sections in census order, every population one this engine holds, the contest's two numbers are numbers, and so are the crown's four."
   :explain "This order will not assemble: {problems}"}
  [_row inp _ctx]
  ;; the schema has already validated the SHAPE (invoke validates
  ;; before it runs a guard), so what is left for this to catch is the
  ;; LAW — and the sentences are the ones check-recipe! already knows
  (try
    (feed/check-recipe! (recipe-of inp))
    (t/allow)
    (catch clojure.lang.ExceptionInfo e
      (if (:waymark10/definition-error (ex-data e))
        (t/deny {:vars {:problems (str/replace (ex-message e)
                                               #"^definition error: " "")}})
        (throw e)))))

;; ── the read-time resolution ────────────────────────────────────────

(defn- newest-active
  "The newest ACTIVE recipe row matching an equality map, or nil. The
  raw store row, read the way `feed/rows-of` reads one: a kind in the
  registry whose table this engine never made is not something the
  feed gets to fail over, so the read answers nothing and the built-in
  answers the request.

  Two active rows in one scope do not fight in the dark, which is why
  no singleton guard stands here: the newest wins, the STAMP names it
  by id and version in the document, and the loser is one retire away.
  A singleton would also have had to read storage, which would have
  dropped this kind's whole door out of the no-database check tier —
  and the agent wall is a sentence worth proving where the author
  looks."
  [eng where]
  (when (get (inv/resources eng) kind)
    (let [st (:storage eng)]
      (first
       (try
         (store/with-tx
           st (fn [tx] (store/query-rows st tx kind (assoc where :state "active")
                                         {:limit 1 :newest-first true})))
         (catch Exception _ []))))))

(defn- source-of
  "The stamp one resolved row earns: which row answered, and the
  sentence a person reads when they wonder why their morning looks
  like this."
  [scope row]
  (let [label (str (get-in row [:data :label]))
        self (str "/api/feed_recipes/" (:id row))]
    {"source" (name scope)
     "label" label
     "self" self
     "id" (str (:id row))
     "version" (:version row)
     "says"
     (str (if (= :member scope)
            "Your own order answered this read, not the house's: "
            "This house's own order answered this read: ")
          (pr-str label) " (" self ", version " (:version row) ")."
          " Its transitions are the tuning history — every edit, who made"
          " it and when — and the way back is the row's own doors: revise"
          " it, or retire it and the deployment's built-in order answers"
          " again.")}))

(def built-in-source
  "The stamp when no row answered. It is not an apology — the built-in
  IS the household default until somebody deliberately overrides it
  (see the seeding decision in docs/spec-feed.md § Built — 4yn)."
  {"source" "built-in"
   "says" (str "No stored recipe answered this read: the order below is the"
               " one this deployment ships with. To change it, create a"
               " feed_recipe — scope \"household\" for everybody's morning,"
               " or \"mine\" for your own — and the order above is the"
               " starting point, in exactly the shape that form takes.")})

(defn- usable
  "One candidate row → {:recipe … :source …}, or nil when the row will
  not assemble. A redeploy can strand a stored recipe (a population
  retired out from under it), and a stranded recipe must not take the
  morning down: it degrades to the built-in, the saved_view lenient
  tradition, and the stamp says which row was skipped and why so its
  author can fix it rather than wonder."
  [built-in scope row]
  (when row
    (let [asked (merge built-in (recipe-of (:data row)))]
      (try
        {:recipe (feed/check-recipe! asked)
         :source (source-of scope row)}
        (catch clojure.lang.ExceptionInfo e
          (when-not (:waymark10/definition-error (ex-data e)) (throw e))
          nil)))))

(defn for-reader
  "The recipe this read is answered with, and the stamp that says which
  one it was: `{:recipe … :source {…}}`.

  `built-in` is the engine opt the route already checked at build
  time, so the fallback is a value in hand rather than a second
  reading of the world. `principal` is WHOSE feed is being computed —
  under a preview that is the previewed member, not the previewer, for
  the same reason the visibility is theirs: a preview that answered
  through the reader's own order would be a preview of a feed nobody
  has."
  [eng built-in principal]
  (let [pid (blank->nil (:id principal))
        stranded (volatile! nil)
        try-row (fn [scope where]
                  (when-some [row (newest-active eng where)]
                    (or (usable built-in scope row)
                        (do (vreset! stranded
                                     (str "feed_recipe /api/feed_recipes/"
                                          (:id row) " is active but will not"
                                          " assemble against this engine —"
                                          " skipped, and the next recipe down"
                                          " answered instead. Revise it and it"
                                          " takes over again."))
                            nil))))]
    (cond-> (or (when pid (try-row :member {:scope "mine" :owner pid}))
                (try-row :household {:scope "household"})
                {:recipe built-in :source built-in-source})
      ;; a stranding is carried onto WHATEVER answered, never dropped:
      ;; the row that could not assemble is its author's to fix, and a
      ;; feed that quietly served the next recipe down without saying
      ;; so would hide the one fact the author needs
      @stranded (assoc-in [:source "stranded"] @stranded))))

;; ── the handlers ────────────────────────────────────────────────────

(defhandler apply-recipe
  [row inp _ctx]
  ;; the overwrite is wholesale over recipe-fields: an omitted optional
  ;; clears, so the stored fields are exactly the set the guard judged
  (update row :data
          (fn [d] (into d (map (fn [k] [k (get inp k)])) recipe-fields))))

(defn- stamp-owner
  "Whose morning this arranges, written by the ENGINE and never trusted
  from the body (dwelling.clj's `stamp-owner`, one kind over). A
  \"mine\" row is the writer's own — a person cannot rearrange somebody
  else's feed by naming them in a form — and a \"household\" row is
  nobody's in particular, so it carries no owner at all."
  [row ctx]
  (assoc-in row [:data :owner]
            (when (= "mine" (str (get-in row [:data :scope])))
              (:id (:principal ctx)))))

;; ── the prose ───────────────────────────────────────────────────────

(def order-example
  "Something in the textarea instead of a blank page (waymark-0ee's
  composition policy). Offered, never applied — and it is itself a
  LEGAL recipe, so a person who keeps it and edits one number has a
  feed rather than a refusal. The house's own current order rides the
  feed document at `recipe.order`, in exactly this shape, which is
  what a first edit actually starts from."
  [{:section "do_now" :population "next_actions" :take 5}
   {:section "decide" :population "asks" :take 3}
   {:section "seam" :sentence "That's the house, caught up."}
   {:section "archive" :population "memories" :take 6 :bottomless true}])

(def ^:private population-note
  (str "One of this engine's populations: "
       (str/join ", " (map name (sort (keys feed/populations))))
       "."))

(def order-schema
  "One recipe line per entry, and the vector's ORDER is the feed's
  order — the same sentence `feed/default-recipe` has always carried.

  The two vocabularies here are CLOSED at declaration time (the census
  and the population registry are both literals a reviewer reads on one
  screen), so they are `:enum`s rather than `:x-options` recipes: the
  legal words ride the published schema itself, where a picker can read
  them with no fetch at all. `:kinds` is the one runtime vocabulary —
  the kinds THIS engine serves — and it wears the ordinary
  `{:from :kinds}` recipe beside its prose (waymark-8sg, and
  waymark-7rw for the fact that the spelling reaches inside an item
  map: an entry's fields are each other's siblings)."
  [:vector
   [:map
    ;; the help READS the census rather than spelling it a second
    ;; time — waymark-jfv.4 added a section, and a sentence naming the
    ;; bands in prose is the copy that goes stale the moment one does
    [:section {:x-display {:label "Section"
                           :help (str "Which band of the page this line"
                                      " fills. The census is law and cannot"
                                      " be reordered — "
                                      (str/join ", "
                                                (map #(str/replace (name %)
                                                                   "_" " ")
                                                     feed/census))
                                      " — so a line out of that order is a"
                                      " typo, not a preference. Spell"
                                      " \"seam\" for the caught-up line"
                                      " itself.")}}
     (into [:enum] (map name) feed/census)]
    [:population {:optional true
                  :x-display {:label "Population"
                              :help (str "Where this line's cards come from."
                                         " " population-note
                                         " A seam line names none.")}}
     [:maybe (into [:enum] (map name) (sort (keys feed/populations)))]]
    [:take {:optional true
            :x-display {:label "How many"
                        :help "How many cards this line contributes to one page. A population with nothing to say contributes nothing and the seam moves up."}}
     [:maybe [:int {:min 1 :max 50}]]]
    [:kinds {:optional true
             :x-options {:from :kinds}
             :x-display {:label "Only these kinds"
                         :help "Narrow this line to particular kinds — the way a house dedicates its first do-now slots to the work queue. The mixer's claim is total, so a later line never re-offers what this one named."}}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    [:says {:optional true
            :x-display {:widget "prose"
                        :label "What this line is for"
                        :help "This line's own sentence, in the household's words — read back to whoever asks why a card is here. Leave it empty and the line narrates itself from its section, its take and its population."}}
     [:maybe [:string {:max 400}]]]
    [:sentence {:optional true
                :x-display {:label "The seam's words"
                            :help "What the caught-up line says — \"That's the house, caught up.\" Only a seam line uses it."}}
     [:maybe [:string {:max 200}]]]
    [:bottomless {:optional true
                  :x-display {:label "Never ends"
                              :help "This line pages forever, walked by the cursor. At most one line may say so, and it is the last one."}}
     [:maybe :boolean]]]])

(def formula-schema
  "The contest, as two numbers (waymark-8um.3, laws v3 law 5). It is a
  MAP of exactly two integers and it will never be anything else — the
  moment this schema needs a third field, somebody is building the
  hidden model law 5 forbids, and the epic's own paragraph is the
  citation.

  The bounds are the same ones `feed/check-recipe!`'s fifth check
  applies, said here so the FORM refuses a nonsense number before
  anybody has to read a guard's sentence about it."
  [:map
   [:window_days {:optional true
                  :x-display {:label "How far back it counts"
                              :help "How many days of your own looking the contest reads. Outside this window a card is unseen again — something you scrolled past in June is not something you are bored of in August."}}
    [:maybe [:int {:min 1 :max 365}]]]
   [:cools_after {:optional true
                  :x-display {:label "Days before a card cools a step"
                              :help "How many days a card may sit on your feed untouched before it steps back behind the fresher cards in its own line. Twice that many days is two steps. Zero turns the contest off entirely and the seed alone decides."}}
    [:maybe [:int {:min 0 :max 365}]]]])

(def crown-rank-schema
  "The crown's rank, as six numbers (waymark-1uv.2, laws v3 law 5 at
  the crown; `:early` joined with waymark-1uv.10 when the decline's
  date left the create door for the rank; `:judged` with
  waymark-1uv.6, the weight of an agent's own score). Six and not
  seven, because the seventh input — a bundle answering a person's
  own request stands first — is a TIER and not a weight: no number a
  household writes may put the machine's initiative above a person's
  own ask.

  The bounds are the ones `feed/check-recipe!`'s sixth check applies,
  said here so the FORM refuses a nonsense number before anybody has
  to read a guard's sentence about it. Zero is legal for every one of
  them; all six at zero is the seed alone."
  [:map
   [:declared {:optional true
               :x-display {:label "A declared value lifts"
                           :help "How much serving a value a PERSON declared lifts a bundle over one serving a value an agent only observed and nobody has affirmed."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:cooled {:optional true
             :x-display {:label "Each cooled step holds"
                         :help "How much each step the contest says a bundle has cooled — the same days-in-window arithmetic as the sections below, off your own record of what you were shown — holds it back."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:declined {:optional true
               :x-display {:label "Each rank of a quick word holds"
                           :help "How much the strongest quick word the house said about the line of thinking a bundle recomposes holds it back, per rank of the word: wrong time is one of these, wrong piece two, not this way three, never this four. One number, so the order of the words cannot be edited upside down."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:fresh {:optional true
            :x-display {:label "Each day left lifts"
                        :help "How much each day still left on a bundle's week lifts it, so a bundle nearer its lapse ranks lower than one the composer just staged."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:early {:optional true
            :x-display {:label "Each day early holds"
                        :help "How much each day a recomposition arrives before the day you said you would hear that line of thinking again holds it back. The day is your own not-this-week, stamped a week, three, two months or half a year out; a recomposition staged before it is shown last rather than not at all, and its card says how early it is."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:judged {:optional true :x-display {:label "An agent's judgment moves" :help "How far an agent's own score of a bundle — 0 to 1, with one sentence, quoted on the card as the agent's — may move it either way: a score of 1 lifts it this much, 0 holds it this much, and a half is silence. Low on purpose; a wrong judgment is a nudge, never a verdict, and 0 turns agents' judgments off without deleting a word of them."}} [:maybe [:int {:min 0 :max 100}]]]])

(def insight-rank-schema
  "The findings' rank, as six numbers (waymark-1uv.8, laws v3 law 5 at
  the insights line) — `crown-rank-schema`'s shape one field over, and
  no tier anywhere in it: nothing in the findings' line is a person's
  own request. The bounds are the ones `feed/check-recipe!`'s seventh
  check applies, said here so the FORM refuses a nonsense number
  before anybody has to read a guard's sentence about it. Zero is
  legal for every one of them; all six at zero is the seed alone."
  [:map
   [:diagnosis {:optional true
                :x-display {:label "A diagnosis lifts"
                            :help "How much a finding that is a diagnosis — one offering a value's or a person's own affirmation, a step on an outcome, or built on an outcome you declined — is lifted over a plain finding. The composer's duty firing first, as a number."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:declared {:optional true
               :x-display {:label "A declared value lifts"
                           :help "How much a finding whose next step serves a value a PERSON declared is lifted over one whose step serves a value an agent only observed, or none."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:cooled {:optional true
             :x-display {:label "Each cooled step holds"
                         :help "How much each step the contest says a finding has cooled — the same days-in-window arithmetic as the sections below, off your own record of what you were shown — holds it back."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:dismissed {:optional true
                :x-display {:label "Each prior dismissal holds"
                            :help "How much each finding you already dismissed on the same next step holds a new one back. The house's own verdict record on the same question, counted."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:declined {:optional true
               :x-display {:label "Each rank of a quick word holds"
                           :help "How much the strongest quick word you said on those dismissals holds a new finding back, per rank of the word: wrong time is one of these, wrong piece two, not this way three, never this four. One number, so the order of the words cannot be edited upside down."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:fresh {:optional true
            :x-display {:label "Each day of freshness lifts"
                        :help "How much each day of freshness a finding has left in the contest's window lifts it — published today is the whole window, two weeks ago is nothing, and older than that is simply old. Newer first, bounded so an old finding sinks to the bottom and no further."}}
    [:maybe [:int {:min 0 :max 100}]]]])

(def tickler-rank-schema
  "The ticklers line's rank, as five numbers (waymark-1uv.9, laws v3
  law 5 at the fridge). Five and not six, for the crown's reason: the
  sixth input — an item a person set aside by their own hand stands
  first — is a TIER and not a weight.

  The bounds are the ones `feed/check-recipe!`'s seventh check
  applies, said here so the FORM refuses a nonsense number before
  anybody has to read a guard's sentence about it. Zero is legal for
  every one of them; all five at zero is the seed alone."
  [:map
   [:overdue {:optional true
              :x-display {:label "Each day past its date lifts"
                          :help "How much each day a set-aside item has stood past its own date lifts it. The date is yours — set when you put it off, or written by your own not-now — and an item with no date is due today, not overdue."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:not_now {:optional true
              :x-display {:label "Each not-now holds"
                          :help "How much each time the house has already said not now to an item holds it back. The house keeps the count; the rank reads it as cooler, never louder."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:cooled {:optional true
             :x-display {:label "Each cooled step holds"
                         :help "How much each step the contest says an item has cooled — the same days-in-window arithmetic as the sections below, off your own record of what you were shown — holds it back."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:front_door {:optional true
                 :x-display {:label "A front-door row lifts"
                             :help "How much an item whose row is a kind this house goes to — a task, a film — is lifted over one whose row is a line inside somebody else's."}}
    [:maybe [:int {:min 0 :max 100}]]]
   [:age {:optional true
          :x-display {:label "Each month on the pile lifts"
                      :help "How much each month an item's row has sat on the dropped pile lifts it, so the things the house forgot longest come back first among equals."}}
    [:maybe [:int {:min 0 :max 100}]]]])

(def ^:private prose
  "The household's own words for the authored fields, spelled ONCE and
  worn by all three doors — the row schema, the create form's narrower
  ask, and :revise. Three copies of one sentence is three places for
  it to drift (dashboard.clj's `slot-prose`, same reasoning)."
  {:label {:x-display {:label "Name"
                       :help "What this order is called — \"the school-run morning\", \"Colton's own\". Short enough to read on a phone."}}
   :scope {:x-display {:label "Whose order"
                       :choices {"household" "The house's — everybody reads this order unless they have written their own"
                                 "mine" "Mine — my own morning, and nobody else's changes"}}}
   :owner {:x-display {:raw true
                       :label "Whose"
                       :help "The member whose own order this is, stamped by the engine when the scope is mine. A household order carries none."}}
   :formula {:examples [{:window_days 14 :cools_after 3}]
             :x-display {:label "The contest"
                         :help "Two numbers, and the whole of how the order is weighted by what you have already been shown: how far back it counts, and how many days a card may sit untouched before it steps back inside its own line. It reads your own rows and nobody else's, it never empties a line, and the crown and everything waiting on your answer are outside it. Leave it empty for this deployment's own numbers; set cools_after to 0 to turn it off."}}
   :crown_rank {:examples [{:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}]
                :x-display {:label "The crown's rank"
                            :help "Six numbers, and the whole of how the crown chooses which composed weeks fill its slots: what a declared value lifts a bundle, what each cooled step holds it, what each rank of the house's quick word about a line of thinking holds it, what each day left on its week lifts it, what each day early a recomposition arrives — before the day you said you would hear that line again — holds it, and how far an agent's own score of a bundle may move it either way. A bundle answering your own request stands first whatever these say. The floor still shows every slot the take promises; the rank only chooses which. Leave it empty for this deployment's own numbers; set all six to 0 for the seed alone."}}
   :insight_rank {:examples [{:diagnosis 10 :declared 5 :cooled 2 :dismissed 3 :declined 2 :fresh 1}]
                  :x-display {:label "The findings' rank"
                              :help "Six numbers, and the whole of how the findings line chooses which of an agent's findings fill its slots — there is no cap on how many it may publish: what a diagnosis is lifted over a plain finding, what a next step serving a value you declared lifts it, what each cooled step holds it, what each finding you already dismissed on the same next step holds a new one, what each rank of the quick word you said on those holds it, and what each day of freshness left in the window lifts it. The floor still shows every slot the take promises; the rank only chooses which. Leave it empty for this deployment's own numbers; set all six to 0 for the seed alone."}}
   :tickler_rank {:examples [{:overdue 1 :not_now 4 :cooled 2 :front_door 5 :age 1}]
                  :x-display {:label "The fridge's rank"
                              :help "Five numbers, and the whole of how the things you set aside are ranked when their date comes round: what each day past its own date lifts an item, what each not-now already said holds it, what each cooled step holds it, what a row this house goes to lifts it, and what each month on the dropped pile lifts it. An item a person set aside by their own hand stands first whatever these say. The floor still shows every slot the take promises; the rank only chooses which. Leave it empty for this deployment's own numbers; set all five to 0 for the seed alone."}}
   :order {:examples [order-example]
           :x-display {:label "The order, line by line"
                       :help "The whole feed, top to bottom: one entry per line, and the vector's order IS the page's order. The house's current order rides the feed document at recipe.order in exactly this shape — copy it and edit a line. Exactly one line is the seam; the bottomless line is last; the sections keep census order; every population is one this engine holds. A line that breaks any of those is refused here, with the sentence that says which."}}})

(defn- entry
  "One [:key props schema] entry of a form: the shared prose plus
  whatever this surface adds of its own."
  [k extra form]
  [k (merge (get prose k) extra) form])

(def recipe-input
  "What :revise takes: the whole authored surface, overwritten
  wholesale. PUBLIC since waymark-0k4, and for one reason — a staged
  proposal prepares this very input and is refused at STAGING when it
  would not fit the door, so the schema the proposal is judged against
  has to be the schema the door actually takes rather than a copy of
  it that could drift. Nothing about the declaration moved: this is
  the same value under the same name, no longer behind a `^:private`."
  [:map
   (entry :label {} [:string {:min 1 :max 60}])
   (entry :order {} order-schema)
   (entry :formula {:optional true} [:maybe formula-schema])
   (entry :crown_rank {:optional true} [:maybe crown-rank-schema])
   (entry :insight_rank {:optional true} [:maybe insight-rank-schema])
   (entry :tickler_rank {:optional true} [:maybe tickler-rank-schema])])

;; ── the law, written down ───────────────────────────────────────────
;; The one sentence this kind most wants checked, and the reason it
;; can be checked with no database at all: both guards on the create
;; door read only what a declaration-time world can honestly supply —
;; the principal, and the input itself.

(defscenario an-agent-does-not-write-the-order
  "No agent rewrites the order it is read in: a composer that could
   edit the recipe would be ranking its own work, and the way it asks
   for a change is an insight a person answers."
  {:kind    :feed_recipe
   :attempt :create
   :input   {:label "Findings first"
             :scope "household"
             :order [{:section "do_now" :population "insights" :take 5}
                     {:section "seam" :sentence "That's the house, caught up."}]}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :written-by-a-person
             :because "The feed's order is written by a person"}})

(defscenario a-person-writes-the-order
  "A member of the house rearranges their own morning — the wall bars
   the composer, not the household."
  {:kind    :feed_recipe
   :attempt :create
   :input   {:label "Queue first"
             :scope "mine"
             :order [{:section "do_now" :population "next_actions" :take 2
                      :kinds ["task"]}
                     {:section "seam" :sentence "That's the house, caught up."}]}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

(defscenario a-feed-with-two-seams-is-refused
  "A recipe that says 'that's everything' twice never assembles, and
   the refusal names the check rather than the boot."
  {:kind    :feed_recipe
   :attempt :create
   :input   {:label "Twice caught up"
             :scope "household"
             :order [{:section "do_now" :population "next_actions" :take 5}
                     {:section "seam" :sentence "That's the house, caught up."}
                     {:section "seam" :sentence "Really, caught up."}]}
   :as      {:id "colton" :type :human}
   :expect  {:refused :the-assembly-checks-pass
             :because "exactly one entry carries :seam true"}})

(defresource feed-recipe
  {:kind :feed_recipe
   :plural "feed_recipes"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :summary "{data.label} · {data.scope} · {state}"
   :label-template "{data.label}"
   :schema [:map
            (entry :label {:sort :default} [:string {:min 1 :max 60}])
            (entry :scope {:filter #{:eq}}
                   [:enum "household" "mine"])
            (entry :owner {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])
            (entry :order {} order-schema)
            (entry :formula {:optional true} [:maybe formula-schema])
            (entry :crown_rank {:optional true} [:maybe crown-rank-schema])
            (entry :insight_rank {:optional true} [:maybe insight-rank-schema])
            (entry :tickler_rank {:optional true} [:maybe tickler-rank-schema])]
   ;; the client states whose order and what it says; the OWNER is the
   ;; engine's stamp (dashboard_slot's split, and dwelling's reason)
   :create-schema [:map
                   (entry :label {} [:string {:min 1 :max 60}])
                   (entry :scope {} [:enum "household" "mine"])
                   (entry :order {} order-schema)
                   (entry :formula {:optional true} [:maybe formula-schema])
                   (entry :crown_rank {:optional true}
                          [:maybe crown-rank-schema])
                   (entry :insight_rank {:optional true}
                          [:maybe insight-rank-schema])
                   (entry :tickler_rank {:optional true}
                          [:maybe tickler-rank-schema])]
   ;; scope and owner carry their own :filter on the schema entries
   ;; above — one concern, one home — so only the machine's own column
   ;; is spelled here
   :filterable {:state #{:eq :in}}
   :scenarios [an-agent-does-not-write-the-order
               a-person-writes-the-order
               a-feed-with-two-seams-is-refused]
   :on-create stamp-owner
   :create-guards [written-by-a-person the-assembly-checks-pass]
   :actions
   {:revise {:from #{:active} :to :active
             :input recipe-input
             ;; the prefill carries the FORMULA too (waymark-8um.3),
             ;; and it must: :revise overwrites recipe-fields
             ;; wholesale, so a form that did not hand the contest's
             ;; numbers back would clear them every time somebody moved
             ;; a line — and the crown's four (waymark-1uv.2), the
             ;; findings' six (waymark-1uv.8) and the fridge's five
             ;; (waymark-1uv.9), for the same reason each time
             :edit {:prefill [:label :order :formula :crown_rank :insight_rank
                              :tickler_rank]}
             ;; the overwrite writes the whole authored surface and is
             ;; non-reversible, so the log carries what was written —
             ;; which is what makes the transitions the tuning history
             :record true
             :guards [written-by-a-person the-assembly-checks-pass]
             :safety {:idempotent true :reversible false :confirm false}
             :handler apply-recipe
             :display {:label "Revise" :order 1
                       :description "Rewrite this order — the same four assembly checks that used to refuse the boot judge it here, and the next feed read shows the change"}}
    :retire {:from #{:active} :to :retired :undo :restore
             :guards [written-by-a-person]
             :safety {:idempotent true :confirm false}
             :display {:label "Retire" :style :danger :order 8
                       :description "Stop reading in this order — the house falls back to its default, or to what this deployment ships with; restore brings it back"}}
    :restore {:from #{:retired} :to :active :undo :retire
              :guards [written-by-a-person]
              :safety {:idempotent true :confirm false}
              :display {:label "Restore" :order 1}}}})
