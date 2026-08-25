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

  ── the four assembly checks, unchanged, moved to the doors ──

  `feed/check-recipe!` is the same function it always was — exactly
  one seam, at most one bottomless and it is last, sections in census
  order, every population one this engine holds — and it now runs as a
  GUARD at create and at revise (`the-assembly-checks-pass`), refusing
  with the sentences it already knew. An invalid recipe therefore
  cannot be stored, and the feed cannot break at read time from a bad
  row. `for-reader` re-judges anyway and falls back to the built-in
  when a stored row has gone stale — the saved_view lenient-render
  tradition: a redeploy that retires a population strands a row, and a
  stranded row must not take the morning down with it. The stamp says
  so out loud when it happens.

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
  input."
  [:label :order])

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
  against a vocabulary the JVM enumerates exhaustively.)"
  [data]
  {:order (mapv line-of (:order data))})

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
  {:judges [:order]
   :vars [:problems]
   :open "The law is feed/check-recipe! — the same four assembly checks that used to refuse the BOOT: exactly one seam, the archive last and bottomless, sections in census order, every population one this engine holds."
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
    [:section {:x-display {:label "Section"
                           :help "Which band of the page this line fills. The census is law and cannot be reordered — do now, decide, fuel, then the seam, then the archive — so a line out of that order is a typo, not a preference. Spell \"seam\" for the caught-up line itself."}}
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
   :order {:examples [order-example]
           :x-display {:label "The order, line by line"
                       :help "The whole feed, top to bottom: one entry per line, and the vector's order IS the page's order. The house's current order rides the feed document at recipe.order in exactly this shape — copy it and edit a line. Exactly one line is the seam; the bottomless line is last; the sections keep census order; every population is one this engine holds. A line that breaks any of those is refused here, with the sentence that says which."}}})

(defn- entry
  "One [:key props schema] entry of a form: the shared prose plus
  whatever this surface adds of its own."
  [k extra form]
  [k (merge (get prose k) extra) form])

(def ^:private recipe-input
  [:map
   (entry :label {} [:string {:min 1 :max 60}])
   (entry :order {} order-schema)])

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
            (entry :order {} order-schema)]
   ;; the client states whose order and what it says; the OWNER is the
   ;; engine's stamp (dashboard_slot's split, and dwelling's reason)
   :create-schema [:map
                   (entry :label {} [:string {:min 1 :max 60}])
                   (entry :scope {} [:enum "household" "mine"])
                   (entry :order {} order-schema)]
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
             :edit {:prefill [:label :order]}
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
