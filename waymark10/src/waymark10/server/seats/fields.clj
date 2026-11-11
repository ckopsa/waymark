(ns waymark10.server.seats.fields
  "The seat's field schemas and the help said beside them (ticket
  9b8c96ae, round six: moved out of `waymark10.server.seats`
  unchanged): the mode and serves choices, the transcript, inbox and
  feed fields, a wake entry's schema, `delivers` and `delegates`, and
  the help sentences the create door, the restate and the row share.
  Each is said once here so the three doors cannot drift apart. It
  requires nothing of the seats namespace; the seat resource refers
  each by name."
  (:require [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]))

(set! *warn-on-reflection* true)

(def mode-choices
  "R-10.8's two words, in the person's own terms. Said once and shown
  at all three doors, because a seat whose mode reads one way on the
  create form and another on the restate is a seat nobody can move."
  {"fired" "Fired — a cadence, a wake or your own fire starts the run, and the run's own hook closes the sitting"
   "interactive" "Interactive — you sit here yourself, from your own machine, across as many turns as the work takes; nothing fires it"})

(def serves-choices
  "Whom a seat works for (epic aff24e84, piece 5), in the person's own
  terms. Said once and shown at every door that takes the field."
  {"own" "Own — this seat works for its own domain"
   "any" "Any — it is a service: the mayor of the domain that asked for a ticket grooms and ranks it"})

(def mode-help
  "The seat's OWN reading of R-10.8, and the reason the field is on the
  seat rather than on the principal."
  "Who sits here. A fired seat is the seat's work day: it wakes on its cadence, on a wake or on your fire, and a Routine's run does the work. An interactive seat is its training day: you sit in it yourself, the corrections you make are the record a step down the ladder reads, and nothing fires it — a Routine's run that tries is refused.")

(def keep-transcripts-values
  "What a seat keeps of what its sittings said (docs/spec-transcript.md
  R-3.7). `fired` is the default: a Routine's run is the office's own
  work, and an interactive sitting holds a person's own words, so it
  is kept only when the person says so on the seat."
  ["none" "fired" "all"])

(def ^:private keep-transcripts-choices
  {"none" "Keep no transcript: the sit answers no upload key"
   "fired" "Keep the transcripts of fired runs, not of a person's own sittings"
   "all" "Keep every sitting's transcript, a person's own included"})

(def ^:private keep-transcripts-help
  "Whether this seat's sittings keep what they said. A kept transcript is readable only by a grant that names it, and the sweep deletes its lines after the days below.")

(def ^:private transcript-days-help
  "How long the lines of a sealed transcript are kept. After that the sweep deletes them; the transcript row keeps each file's line count and chain, so the record still says what existed.")

(def keep-transcripts-field
  [:keep_transcripts {:default "fired"
                      :examples ["fired"]
                      :x-display {:label "Keep what its sittings said"
                                  :choices keep-transcripts-choices
                                  :help keep-transcripts-help}}
   (into [:enum] keep-transcripts-values)])

(def transcript-days-field
  [:transcript_days {:default 30
                     :examples [30]
                     :x-display {:label "Days a transcript is kept"
                                 :help transcript-days-help}}
   [:int {:min 1 :max 3650}]])

(def ^:private inbox-help
  "What the engine holds for this seat's sittings to pull, said the way a subscriber's `only` is: a kind, and the actions on it that count, or an empty list for every action. Leave it empty for a seat with no inbox; an interactive seat left empty hears its tickets, changes, held calls, approval requests, seats and sittings. A cue is a standing note: the inbox attaches it to each event the cue matches, so the seat reads what to do beside the event.")

(def ^:private inbox-cue-schema
  "One cue of a seat's inbox (ticket ab77e635): a wake-entry-shaped
  entry, and the note the inbox door attaches to each event the entry
  matches. An empty `actions` is every action of the kind, as it is in
  `only`. A cue with no `note` is a wake entry as it stands, and the
  door attaches `cue-note`'s default sentence (ticket 1f3aa7cd)."
  [:map
   [:kind {:x-options {:from :kinds}
           :x-display {:label "Kind"
                       :help "The collection whose events carry this note — one kind name this engine serves."}}
    [:string {:min 1 :max 64}]]
   [:actions {:x-options {:from :actions :of :kind :each true}
              :x-display {:label "Actions"
                          :help "Which transitions of that kind carry this note, by name. An empty list is every action of the kind."}}
    [:vector [:string {:min 1 :max 64}]]]
   [:filter {:optional true
             :x-display {:label "Only rows matching"
                         :spelled-by-hand grants/filter-spelled-by-hand
                         :help "Which rows this cue is about: field=value pairs in the shape of that kind's own query, the collection grammar's eq. The row is read as it stands when the inbox serves the event. Omit it and every row of the kind matches."}}
    [:maybe grants/filter-map-schema]]
   [:note {:optional true
           :examples ["An ask from household: groom it."]
           :x-display {:label "Note"
                       :help "What this seat does about such an event, in one line. The inbox attaches it to each event this cue matches. Leave it out and the event carries a default sentence: that the row arrived and may wait on your tap."}}
    [:string {:min 1 :max 280}]]])

(def inbox-field
  "THE SEAT'S INBOX. A cloud session cannot run a local receiver, so
  the engine holds the inbox and the sitting pulls it; the sit answers
  the address and a key for it (`issue-inbox-key!`). Absent is no
  inbox for a fired seat, and `default-inbox` for an interactive one
  (`inbox-of`). Each kind and each action is judged by `inbox-names-real-kinds`
  and `inbox-names-real-actions`.

  The field says where its tokens come from the way a wake
  entry's `kind` does (`:x-options {:from :kinds}`): the kinds are
  the keys of `only`, and the chips beside the box offer them. `only`
  itself is a map-of, whose keys no form can list, so it wears the
  reason it is typed (`:spelled-by-hand`), as the scope's `filter`
  does.

  `cues` are standing notes (`inbox-cue-schema`). They never widen or
  narrow what the door serves: `only` decides that. An inbox that
  states `cues` and no `only` keeps the `only` it would have had with
  no inbox stated (`inbox-of`)."
  [:inbox {:optional true
           :examples [{:only {:seat ["restate" "park"]}}]
           :x-options {:from :kinds}
           :x-display {:label "Its inbox"
                       :help inbox-help}}
   [:maybe [:map
            [:only {:optional true
                    :x-options {:from :kinds}
                    :x-display {:label "Kinds and their actions"
                                :spelled-by-hand "A kind → actions map, the shape of a subscriber's `only`, and a form cannot list its keys, so each pair is typed: a kind name, then the list of its actions that count, or an empty list for every action. The chips beside the box offer every kind name."
                                :help "Which transitions the engine holds for this seat's sittings: each kind by name, and the actions on it that count. An empty list counts every action of that kind."}}
             [:map-of :keyword [:vector [:string {:min 1 :max 64}]]]]
            [:cues {:optional true
                    :x-display {:label "Cues"
                                :help "Standing notes for the events this seat hears. Each cue names a kind, its actions and, when it needs one, a filter on the row; the inbox attaches the cue's note to each event it matches. A cue does not change which events the inbox holds. At most 20."}}
             [:vector {:max 20} inbox-cue-schema]]
            [:held_call {:optional true
                         :x-display {:label "Held calls"
                                     :help "Say own and the inbox hears the held calls this seat filed itself, from create through allow, refuse and land, and no other seat's. Leave it out and `only` decides, as for any kind."}}
             [:maybe [:enum "own"]]]]]])

(def feed-url-field
  "THE SEAT'S OUTSIDE FEED. An inbox outside the engine streams this
  seat's subscription deliveries, and admits a caller by asking the key
  check door (docs/spec-seat.md § 16). The sit answers a short-lived
  token for it (`issue-feed-token!`), so a session never sends the seat
  key to that address. Absent is no feed. It is not `inbox`, which the
  engine holds itself."
  [:feed_url {:optional true
              :examples ["https://inbox.kopsa.info/feed/mayor"]
              :x-display {:label "External feed"
                          :help "An outside inbox that streams this seat's subscription deliveries; the sit answer hands out a short-lived token for it."}}
   [:maybe [:string {:min 9 :max 500}]]])

(def idle-help
  "R-12.25's safety net under the wait, said where a person sets it."
  "How long an interactive sitting may go untallied before the engine closes it. The Stop hook tallies after every turn, so this is the gap that says somebody shut the laptop — the sweep then closes the sitting with the last tally's counts rather than leaving it open forever. It means nothing to a fired seat.")

;; ── what wakes a seat, entry by entry (R-12.22, R-12.24) ───────────
;;
;; A wake entry wore the scope entry's schema while a wake was
;; one thing: a transition the seat asked to be woken by. R-12.24
;; gives it a second thing to be — a COUNT wake, which says how many
;; rows must be waiting before the seat is worth waking — so the
;; entry has a schema of its own here. The scope's `kind` and
;; `actions` are the same two fields, and three fields a leash has no
;; use for join them:
;;
;;   filter     which rows count, in the shape of that kind's query
;;              where clause — `grants/filter-map-schema`, the scope
;;              entry's own filter shape, spelled once and worn twice.
;;              On a transition wake it is judged against the MOVED
;;              ROW (waymark-fp62.7.20); on a count wake it picks the
;;              rows counted. Absent, a transition wake matches every
;;              row and a count wake counts the kind's default filter:
;;              the queue a walk works through.
;;   at_least   the size that wakes the seat counting UP: the count
;;              at or ABOVE which it is worth waking. Absent, the
;;              entry is a transition wake and behaves exactly as it
;;              always did.
;;   at_most    the size that wakes the seat counting DOWN: the count
;;              at or BELOW which it is worth waking (waymark-fp62.13).
;;              Zero is the EMPTY queue, and the empty queue is the
;;              one thing nothing could wake a seat on before: a
;;              planner's work begins when no plan is waiting, and a
;;              cadence was the only thing that could start it.
;;   settle_seconds
;;              the quiet time before the seat wakes
;;              (waymark-fp62.17). Every other entry fires on the
;;              LEADING edge: the first match wakes the seat. An
;;              entry with a settle fires on the TRAILING edge
;;              instead. A match writes the moment the wake is due on
;;              the schedule row, and a later match moves that moment
;;              forward, so the seat wakes after the matches stop. A
;;              family chat is the case: the first reply is the
;;              middle of a conversation, and a seat woken by it
;;              reads a chat that is half answered. A count entry may
;;              settle too, because a count is a LEVEL, and a settled
;;              level is a level that held for that long.
;;
;; An entry names ONE of the two sizes. Both in one entry is not a
;; narrower wake, it is two questions the engine cannot answer with
;; one count, so the SCHEMA refuses it (`wake-entry-one-size?`) and
;; the create and the restate say so in the entry's own place.
;;
;; The rest of a scope entry — ids, fields, hashed, args — is a
;; leash's vocabulary and not a wake's: WHAT a woken session may see
;; is decided by the seat's `scope`, one field up, and a wake entry
;; that repeated it would be a second leash nobody is holding.
(def ^:private wake-entry-fields
  "The wake entry's FIELDS, as the map a client draws a row from. The
  law of the entry is `wake-entry-schema` below, which is this map and
  the one rule a map cannot say."
  [:map
   [:kind {:x-options {:from :kinds}
           :x-display {:label "Kind"
                       :help "The collection whose transitions wake this seat — one kind name this engine serves."}}
    [:string {:min 1 :max 64}]]
   [:actions {:x-options {:from :actions :of :kind :each true}
              :x-display {:label "Actions"
                          :help "Which transitions of that kind count, by name. An empty list is every action of the kind, on a transition wake and a count wake alike."}}
    [:vector [:string {:min 1 :max 64}]]]
   ;; no :x-options, for the scope filter's reason verbatim: the
   ;; vocabulary here is the legal KEYS of an object, and the recipe's
   ;; composition words both describe a value BUILT from tokens
   [:filter {:optional true
             :x-display {:label "Only rows matching"
                         :spelled-by-hand grants/filter-spelled-by-hand
                         :help "Which rows this entry is about: field=value pairs in the shape of that kind's own query, the collection grammar's eq. A count wake counts the rows that match; a transition wake wakes this seat only when the row that moved matches. Omit it and every matching transition wakes the seat, and a count wake counts under the kind's own default filter — the queue a walk works through."}}
    [:maybe grants/filter-map-schema]]
   [:at_least {:optional true
               :examples [20]
               :x-display {:label "Rows waiting before it wakes"
                           :help "The size that wakes this seat. The engine counts the rows matching this entry when one of its actions commits, and fires once the count is at or above this number; the fire names no row, so the session walks the queue. Omit it and every matching transition wakes the seat, one row at a time."}}
    [:int {:min 1}]]
   [:at_most {:optional true
              :examples [0]
              :x-display {:label "Rows left before it wakes"
                          :help "The size that wakes this seat as the queue DRAINS. The engine counts the rows matching this entry when one of its actions commits, and fires once the count is at or below this number; the fire names no row, so the session walks the queue and the charter says what to make. Zero wakes the seat when the last matching row leaves, which is the seat whose work begins on an empty queue. An entry names at_least or at_most, and never both."}}
    [:int {:min 0}]]
   [:settle_seconds {:optional true
                     :examples [900]
                     :x-display
                     {:label "Quiet time before it wakes, in seconds"
                      :help "How long the matches must stop before this entry wakes the seat. A match does not fire the seat; it moves the wake forward by this many seconds, and the seat wakes when nothing has matched for that long. The fire names no row, so the session walks the queue. Use it for a conversation, where the first message is not the whole of it. Omit it and the first match wakes the seat at once."}}
    [:int {:min 1 :max 604800}]]
   ;; the settle's cap (ticket 8f482592): matches that arrive faster
   ;; than the settle must not hold the wake back for ever
   [:max_wait_seconds {:optional true
                       :examples [2700]
                       :x-display
                       {:label "Longest wait after the first match, in seconds"
                        :help "The longest a settling entry holds the wake after the FIRST match it heard. Matches that keep arriving move the wake forward only up to this point, so a busy collection cannot keep the seat from waking. Omit it and the wake waits at most three times the quiet time. It means nothing on an entry with no quiet time."}}
    [:int {:min 1 :max 604800}]]])

(defn- wake-entry-one-size?
  "R-12.24's one rule a `:map` cannot say: an entry names at_least or
  at_most, never both. A non-map answers true, because the map beside
  this one has already refused it and one wrong entry owes a person
  one sentence."
  [e]
  (not (and (map? e) (some? (:at_least e)) (some? (:at_most e)))))

(def wake-entry-schema
  "One wake entry: the fields above, and the rule that the two
  sizes are alternatives.

  The rule is malli's, not a guard's, so it lands where a person's
  eyes are — the entry's own place in the 422 — and it lands at BOTH
  write doors without either of them repeating it.

  The `:json-schema` property is what keeps the form. A client draws a
  list of maps as ROWS when the items projection carries `properties`
  (waymark-fp62.7.9), and an `:and` projects to `allOf`, which carries
  none: the seat's wake entries would have fallen back to the JSON box the
  rows replaced. So the projection published for this node is the
  MAP's own, computed from `wake-entry-fields` rather than spelled a
  second time. Nothing is hidden by that: the rule refuses a shape
  JSON Schema has no word for, and the field help says it in prose."
  [:and
   {:json-schema (schema/json-schema wake-entry-fields)}
   wake-entry-fields
   [:fn {:error/message "An entry names at_least or at_most, not both."}
    #'wake-entry-one-size?]])

(def instructions-example
  "The `instructions` example the seat's doors show."
  "Read the fire text below and do what it says. Sit in the seat it names with waymark_sit, passing the seat's name and the key you were given. Then walk the rows the sit hands you, one at a time, and take the door the charter chooses for each.")

(def charter-example
  "The `charter` example the seat's doors show."
  "Decide whether a message asks something of this house, and say what it asks in one line. A receipt for something already bought asks nothing. A person waiting on an answer asks something, even when they are polite about it.")

(def max-open-sittings-help
  "The one help sentence `max_open_sittings` carries at the row, the
  create door and the restate, with the budget said beside it."
  "How many sittings of this seat may run at once, each on a row of its own. One keeps the seat as it always was: an open sitting holds every wake. Above one, a wake starts another run while a row of the walk is left that no open sitting holds. The week's fuel is unchanged and counts every sitting, so three sittings at once spend it three times as fast.")

(def acts-as-cast-help
  "The help `acts_as_cast` carries at the seat's doors."
  "Tick it and this seat plays a seed's cast — the customers, suppliers and callers of an org on a clone. Only a clone takes it: on any other engine the create and the restate refuse it.")

(def delivers-help
  "The help `delivers` carries at the seat's doors."
  "What counts as this seat's delivery, each entry a kind and an action. A sitting that made one of these transitions closes as submitted, so a seat whose work is not a change is not counted cut short or idle. Leave it out and a change's submit is the delivery.")

(def delivers-schema
  "SEAT HEALTH: what `sitting-health` calls `submitted` for this seat.
  Absent means `default-delivers`."
  [:vector {:min 1 :max 16}
   [:map
    [:kind {:examples ["change"]
            :x-display {:label "The kind it moves"}}
     [:string {:min 1 :max 64}]]
    [:action {:examples ["submit"]
              :x-display {:label "The action that delivers"}}
     [:string {:min 1 :max 64}]]]])

(def delegates-schema
  "THE CEILING (server/delegation, invariant 2). A seat that carries one
  is a delegating seat: its sitter may open and tune other seats, and
  every seat it writes must fit under this. The scope is in `scope`'s
  own shape, and a child's entries are fitted under it entry by entry
  and filter by filter. It is not the author's own scope: a mayor may
  give bench.write on repo bench without holding it. The three caps
  bound the rest of the office."
  [:map
   [:scope {:examples [grants/scope-example]
            :x-display
            {:label "What its seats may open"
             :help "The widest scope any seat it authors may carry, entry by entry: a child entry fits when it names the same kind, only actions listed here, only ids listed here when this names ids, and every filter pair this entry carries. It need not be inside the delegating seat's own scope."}}
    grants/scope-schema]
   [:budget_usd_per_week {:examples [5M]
                          :x-display
                          {:label "Most fuel per authored seat, in dollars a week"
                           :help "No seat it authors may be given more than this for seven days."}}
    [:decimal {:min 0 :max 100000}]]
   [:sitting_budget_tokens {:examples [60000]
                            :x-display
                            {:label "Most tokens per sitting of an authored seat"
                             :help "No seat it authors may carry a larger sitting ceiling, and none may ignore the ceiling."}}
    [:int {:min 20000 :max 10000000}]]
   [:held_for {:optional true
               :x-display
               {:label "Models its seats may be held for"
                :help "The model row ids a seat it authors may name in held_for and substitute_for. Leave it empty and any model may be named."}}
    [:maybe [:vector [:string {:min 1 :max 64}]]]]])

(def delegates-field-help
  "The help `delegates` carries at the seat's doors."
  "Leave it empty for a seat that authors nothing. Fill it and the seat's sitter may open and tune other seats inside it; each one is born parked and waits on your unpark, and anything past it waits on your tap.")
