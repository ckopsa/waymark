(ns waymark10.ranking-note
  "The ranking note (waymark-1uv.6): an agent's score and one sentence
  about a ranked row, as DATA, stamped with who wrote it — option M of
  the epic *Ranked, not capped*.

  The owner's ruling was that an agent may tune the rank, or supply a
  judgment to it. Tuning landed first (waymark-1uv.5: numbers for a
  declared formula, proposed through `recipe_proposal`, applied by a
  person). This is the judgment: when the crown's five counts cannot
  express the thing an agent knows — *this is the outcome he has been
  circling for a month* — the agent writes a score, 0 to 1, and one
  sentence, and the crown's formula reads the score as ONE MORE
  WEIGHTED INPUT beside the counts (`feed/crown-lift`, the `:judged`
  number on `crown_rank`, default 1 — a nudge, never a verdict). The
  card quotes the sentence AS THE AGENT'S, with the agent's name on
  it, the way it quotes the composer's `routing` today and never lets
  the engine's impact line blur into it. A row nobody scored ranks
  without it; an agent that is wrong is one weight turned down.

  ── A KIND, NOT A FIELD ON `outcome` ──

  The bead asked which. A field would have moved the outcome kind's
  own schema for a fact that is not the outcome's — it is the AGENT's,
  about the outcome — and it would not generalize: the insights line
  and the ticklers line are getting ranks of their own (waymark-1uv.8,
  waymark-1uv.9) and a judgment about a finding is the same judgment
  as one about a bundle. So the subject is named the way the tickler
  and the reason kind name theirs, as `{subject_kind, subject_id}`
  rather than as a ref, and ONE KIND SERVES EVERY RANKED ROW IN THE
  HOUSE. Only the crown reads it today; the insights and ticklers
  lines may read it later, and nothing here has to change for them to.

  ── WHOSE IT IS, AND WHOSE IT NEVER BECOMES ──

  A note is born the agent's and stays the agent's. There is NO
  affirmation axis here, on purpose, and the omission is the design
  rather than a shortcut: a value an agent observed may be affirmed
  by a person and become the house's (waymark-jfv.10), because a value
  is the house's law and the observer was guessing at it. A judgment
  about which bundle deserves a Saturday is not a fact about the house
  that a person could confirm — it is an agent's opinion, weighed as
  one, and a person who agreed with it would say so by tapping the
  bundle. So: an agent may create one and restate its own; a PERSON
  may dismiss one (*not this one's word, not on this row*); nothing
  turns it into the household's. A person does not write one at all —
  a person ranking a bundle first has a door for that already
  (`compose me another` is the rank's first tier), and a judgment with
  a member's name on it would ride the crown as the house's own word
  wearing an agent's weight.

  ── THE WALLS ──

  - THE FOUR-EYES WALL, GENERIC. An agent never scores a row it wrote:
    a composer ranking its own bundle first is the exact hole this kind
    would otherwise open. `not-your-own-row` reads the subject kind's
    own `:own-surface :by` — `composed_by` for an outcome,
    `authored_by` for an insight — so the wall is the subject's own
    sentence about ownership and never a copy of it. A kind with no
    own-surface has no author to wall against.
  - ONE LIVE NOTE PER {ROW, AUTHOR}. A second is refused by name;
    `restate` is how a judgment changes, and the earlier score stays
    in the row's own transitions. After a person dismisses one, the
    same agent may write a new one — a dismissal answers a judgment,
    not the agent — and the person may dismiss that too.
  - A SCORE WITH NOTHING BEHIND IT IS AN OPINION. `cites-what-it-read`
    is `outcome/cites-what-it-read` to the letter: at least one
    address, each `/api/<collection>/<id>` naming a collection this
    house serves. The citations are the BIRTH's and `restate` does
    not take them: what the agent read is the fact the note was made
    from, checked once under the registry consult, and a restatement
    is a change of mind about that reading — the score and the
    sentence. (A shape-only evidence wall on the restate door was
    written and taken out: an action-door guard that judges free text
    must either escape closure with `:open`, which
    `usability/effort-honesty` re-raises as a warning, or read rows,
    which would drop the door's scenarios out of the no-database
    tier — and the walker, a system actor, cannot stage a note
    through a birth door that admits only agents.)
  - THE VISIBILITY WALL IS THE ROUTER'S. An agent reads the row it
    scores under its own grant, and cites it; a guard ctx carries no
    visibility (insight's recorded finding, waymark-iqa.18's seam), so
    what this door can honestly check is that the row EXISTS and is
    not the agent's own. The grant the scoring agent holds is the wall
    on what it may read, and the spec records the scope.

  ── WHERE IT LIVES ──

  The feed module, enrolled `:always` beside `verdict_reason`, for the
  same reason: it names no application vocabulary, the crown that reads
  it is the feed's own, and `waymark10.server.feed` reads it by keyword
  the way it reads the reason kind."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def note-kind
  "The note's kind keyword — the definite marker, never a name string."
  :ranking_note)

;; ── addresses, read rather than guessed ─────────────────────────────

(defn- row-address
  "`/api/<plural>/<id>` → {:plural … :id …}, nil for anything else —
  `insight/row-address` and `outcome/row-address`, the same shape and
  the same refusals: query strings, action doors and bare ids are not
  addresses."
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
  "A short, ordered rendering of what went wrong — every offender,
  not the first."
  [xs]
  (str/join ", " (map pr-str (sort (distinct xs)))))

(defn- addresses-of [inp]
  (into [] (remove str/blank?) (map str (:evidence inp))))

;; ── the walls ───────────────────────────────────────────────────────
;;
;; SHAPE FIRST, WORLD NEXT — insight's ordering and outcome's: who may
;; write one, then what it cites, then the row it is about, then
;; whether this author already said so.

(g/defguard a-judgment-is-an-agents
  {:reads [:principal]
   :open "A ranking note is an agent's word beside the engine's numbers, and stays the agent's. A person does not write one: a person who wants a bundle first has a door for that already, and a judgment with a member's name on it would ride the crown as the house's own word wearing an agent's weight."
   :explain "A ranking note is an agent's judgment, quoted as the agent's. Yours is a verdict — the doors on the card are yours, and \"compose me another\" puts what you asked for first. If an agent's note is wrong, dismiss it; if the weight is wrong, the crown's rank is a number on the recipe."}
  [_row _inp ctx]
  ;; a pure function of the principal's kind, so the render probe and
  ;; the real invoke read the same fact (feed_recipe's own posture).
  ;; :agent and nothing else — :system (a migration, a seed, the
  ;; conformance walker) is refused too, on purpose: a seed that wrote
  ;; judgments would be the engine having opinions about its own
  ;; crown, which is the one voice this input must never carry. That
  ;; is also why every scenario on this kind's action doors is
  ;; check-tier: the walker could not stage a note to attempt one.
  (if (= :agent (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard cites-what-it-read
  {:judges [:evidence]
   :reads [:storage]
   :vars [:count :offenders]
   :open "A ranking note cites the rows it was judged from: at least one address, each of them /api/<collection>/<id> naming a collection this house serves."
   :explain "A score with nothing behind it is an opinion — cite the rows you actually read, as addresses like /api/outcomes/01H… ({count} given{offenders})."}
  [_row inp ctx]
  (let [ev (addresses-of inp)
        rdef-of (:rdef-of ctx)]
    (cond
      ;; the storage-free probe (render, the partial rehearsal) has no
      ;; registry in scope: advertise optimistically, exactly as
      ;; outcome/cites-what-it-read does. The write path always
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

(g/defguard not-your-own-row
  {:reads [:storage]
   :vars [:problem]
   :open "A note is about a row that exists, of a kind this house serves — and never a row the agent itself wrote. The subject kind's own :own-surface says whose a row is, so a composer cannot rank its own bundle first and a compiler cannot score its own finding."
   :explain "That row is not one you may score: {problem}"}
  ;; NO :judges, for `outcome/the-row-it-names-is-there`'s reason: this
  ;; wall reads a ROW in another kind's collection, and the subject of
  ;; the judgment is the row rather than the id's shape.
  [_row inp ctx]
  (let [read' (:read ctx)
        rdef-of (:rdef-of ctx)
        k (some-> (:subject_kind inp) str str/trim not-empty)
        sid (some-> (:subject_id inp) str str/trim not-empty)
        pid (str (:id (:principal ctx)))
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (or (nil? read') (nil? rdef-of) (nil? k) (nil? sid))
      ;; the storage-free probe, or a body the schema has already
      ;; refused — advertise optimistically
      (t/allow)
      (let [rd (rdef-of k)
            row (when rd (read' (:kind rd) sid))
            ;; the subject's own ownership sentence, normalized by
            ;; waymark10.resource — a vector of branches, each a path
            ;; into the document
            branches (:by (:own-surface rd))]
        (cond
          (nil? rd)
          (deny (str "this house serves nothing called " (pr-str k) "."))

          (nil? row)
          (deny (str "this house has no " k " " sid " — read /api/"
                     (:plural rd) " and name one of those."))

          (some (fn [branch] (= pid (str (get-in row (into [:data] branch)))))
                branches)
          (deny (str "/api/" (:plural rd) "/" sid " is your own — you wrote"
                     " it, and a judgment that ranks your own work is the"
                     " four-eyes wall walked around. Another agent may score"
                     " it; you may not."))

          :else (t/allow))))))

(g/defguard one-live-note-per-row-and-author
  {:reads [:principal :ranking_note]
   :vars [:subject]
   :open "One live note per row per author. A judgment changes by being restated, and the earlier score stays in the row's own transitions; it is never said twice."
   :explain "You already have a live note on {subject}. Restate it — its own screen takes a new score, a new sentence and the rows you read — rather than writing a second one beside it."}
  [_row inp ctx]
  (let [find' (:find ctx)]
    ;; the storage-free probe advertises optimistically — feed_view's
    ;; own posture, and the write path always carries the consult
    (if (nil? find')
      (t/allow)
      (let [k (str (:subject_kind inp))
            sid (str (:subject_id inp))
            pid (str (:id (:principal ctx)))
            already (find' note-kind
                           {:subject_kind k :subject_id sid :judged_by pid
                            :state "live"}
                           {:limit 1})]
        (if (seq already)
          (t/deny {:vars {:subject (str k " " sid)}})
          (t/allow))))))

(g/defguard the-judgment-is-your-own
  {:reads [:principal]
   :vars [:whose :you]
   :open "Restating is the author's own hand. A judgment somebody else could rewrite would not be a record of what the agent judged — and a person who disagrees dismisses it rather than editing it."
   :explain "This is {whose}'s judgment and you are {you}. Read it, weigh it, dismiss it if it is wrong — but the score and the sentence stay theirs."}
  [row _inp ctx]
  ;; the same wall `verdict_reason/the-reason-is-your-own-hand` keeps
  ;; and for the same reason: the own-surface affordance is answered
  ;; at KIND level, so a reader granted this kind is ADVERTISED the
  ;; door on rows that are not theirs, and the door has to refuse by
  ;; name
  (let [whose (str (get-in row [:data :judged_by]))
        me (str (:id (:principal ctx)))]
    (if (= whose me)
      (t/allow)
      (t/deny {:vars {:whose whose :you me}}))))

(g/defguard a-person-dismisses
  {:reads [:principal]
   :open "A person dismisses a note. An agent does not — not its own (it restates), and not another's (a house running two agents would otherwise have one silence the other's judgment before a person read it)."
   :explain "Dismissing an agent's judgment is a person's answer. If it is your own note, restate it; if it is another agent's, say what you read where an agent may — a note of your own, on the same row."}
  [_row _inp ctx]
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

;; ── the stamps ──────────────────────────────────────────────────────

(defhandler stamp-the-author
  [row ctx]
  ;; whoever posts is whose judgment it is — `verdict_reason`'s
  ;; `stamp-the-sayer`, one kind over. The field is in the create
  ;; model and stamped over so a body naming somebody else is refused
  ;; by name rather than as a stray key.
  (assoc-in row [:data :judged_by] (:id (:principal ctx))))

(defhandler write-the-judgment
  [row inp _ctx]
  ;; the judgment, overwritten: the score and the sentence. The
  ;; citations stand as the note was born with them — what the agent
  ;; READ is the fact this row was made from, checked once at the birth
  ;; door under the registry consult — and the transitions log keeps
  ;; the score and sentence that stood before.
  (-> row
      (assoc-in [:data :score] (:score inp))
      (assoc-in [:data :says] (:says inp))))

(defhandler stamp-the-dismisser
  [row _inp ctx]
  (assoc-in row [:data :dismissed_by] (:id (:principal ctx))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; TWO TIERS, read off the declarations (scenario.clj's own rule). The
;; two doors on a LIVE note carry walls that read the principal and
;; the row and nothing else, so their scenarios are judged with no
;; database in the same breath as the usability warnings. The create
;; door's chain reads the registry, the subject row and this kind's own
;; rows, so a create scenario is the chain's tier and defers to the
;; suite — `a-person-does-not-write-a-judgment` is declared here and
;; proved over the wire, where the walker attempts it as the person it
;; names. The four-eyes wall (an agent scoring its own outcome) needs
;; a subject row the agent itself staged, which no scenario can
;; arrange — the walker stages `:given` rows under its own name — so it
;; is proved live in workqueue10.outcome-test, against the household's
;; own composer.

(def ^:private a-live-note
  {:subject_kind "outcome"
   :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :score 0.8M
   :says "This is the outcome he has been circling for a month — the shop, the boys, a box at the end of it."
   :evidence ["/api/outcomes/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
              "/api/values/01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]
   :judged_by "cairn"})

(defscenario a-person-does-not-write-a-judgment
  "A ranking note is an agent's word and stays one. A person's answer
   is a verdict on the card, or a request that stands first — never
   a score with a member's name on it riding the crown at an agent's
   weight."
  {:kind    :ranking_note
   :attempt :create
   :input   {:subject_kind "outcome"
             :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :score 0.9M
             :says "This one, obviously."
             :evidence ["/api/outcomes/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]}
   :as      {:id "colton" :type :person}
   :expect  {:refused :a-judgment-is-an-agents
             :because "an agent's judgment"}})

(defscenario nobody-restates-somebody-elses-judgment
  "Restating is the author's own hand. A second agent that could
   rewrite the first's score would be putting words in its mouth, and
   the crown would quote them under the wrong name."
  {:kind    :ranking_note
   :attempt :restate
   :row     {:state :live :data a-live-note}
   :input   {:score 0.1M
             :says "Actually, not this one."}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :the-judgment-is-your-own
             :because "stay theirs"}})

(defscenario an-agent-does-not-dismiss-a-judgment
  "Dismissing is a person's answer. An agent that could dismiss
   another's note could silence a judgment before a person read it;
   an agent that changed its own mind restates."
  {:kind    :ranking_note
   :attempt :dismiss
   :row     {:state :live :data a-live-note}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :a-person-dismisses
             :because "a person's answer"}})

(defscenario a-dismissed-judgment-is-not-restated
  "Dismissed is answered. The agent may judge the row again in a new
   note — which the person may dismiss again — but the note the house
   answered stays answered."
  {:kind    :ranking_note
   :attempt :restate
   :row     {:state :dismissed :data (assoc a-live-note :dismissed_by "colton")}
   :input   {:score 0.9M
             :says "Once more."}
   :as      {:id "cairn" :type :agent}
   :expect  {:refused :out-of-state
             :because "Live"}})

;; ── the prose the doors wear ────────────────────────────────────────

(def ^:private prose
  {:subject_kind
   {:x-display
    {:label "What sort of row"
     :help "The kind of the row this judgment is about — outcome today; insight or tickler when their lines learn to read it. Kept as a name beside the id rather than as a reference, because a judgment may be about anything a rank places and a reference names one kind only."}}
   :subject_id
   {:x-display
    {:label "Which row"
     :help "That row's own id, the one in its address bar. The note holds a pointer, never a copy, so the row stays where it lives."}}
   :subject_href
   {:x-display
    {:hidden true
     :label "Its address"
     :help "Where the row lives, as it appears after the site name (/api/outcomes/01H…). Leave it blank and the note still works; fill it in and it can take a reader straight back."}}
   :score
   {:x-display
    {:label "Your score"
     :help "0 to 1. One is \"this is the one\", zero is \"not this\", and a half is silence — the crown reads it as one weighted number beside its own counts, and the house sets that weight. A wrong score is a nudge, never a verdict."}}
   :says
   {:examples ["This is the outcome he has been circling for a month — the shop, the boys, a box at the end of it."]
    :x-display
    {:widget "prose"
     :label "In one sentence"
     :help "Why, in one sentence a person reads standing up. The card quotes it under your name, beside the engine's own numbers — it is your word and stays yours, so say the thing rather than leading up to it."}}
   :evidence
   {:x-display
    {:label "What you read"
     :help "The rows this judgment rests on, as addresses — /api/outcomes/01H… — one per row you actually looked at. At least one, always: a score with nothing behind it is an opinion, and the house can follow the citations down."}}
   :judged_by
   {:x-display
    {:raw true
     :label "Scored by"
     :help "Whose judgment this is. The engine stamps it from whoever posted, and a body naming somebody else is refused by name — the card quotes the sentence under this name."}}
   :dismissed_by
   {:x-display
    {:raw true
     :label "Dismissed by"
     :help "The person who answered this judgment, stamped by the engine when they did."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

(def ^:private the-judgment
  "What a judgment is made of, worn by the birth door and by `restate`:
  the score and the sentence."
  [(entry :score {} [:decimal {:min 0 :max 1}])
   (entry :says {} [:string {:min 1 :max 240}])])

(def ^:private judgment-input
  "The birth door's half of the judgment: the score, the sentence, and
  the rows it rests on. `restate` wears `the-judgment` alone — the
  citations are the birth's."
  (conj the-judgment
        (entry :evidence {:optional true}
               [:maybe [:vector [:string {:min 1 :max 200}]]])))

;; ── :ranking_note — the agent's score and sentence ──────────────────

(defresource ranking-note
  {:kind :ranking_note
   :plural "ranking_notes"
   ;; hand-written kinds inherit no :nav, and :system is the honest
   ;; one for `verdict_reason`'s reason: a note is an input to a rank,
   ;; not work, and a :primary spelling would card it in do-now and
   ;; congratulate the house in fuel for an agent having had an
   ;; opinion.
   :nav :system
   ;; LIVE until a person answers it. `restate` is a self-loop on the
   ;; live row — the tickler's `not_now` and the reason kind's
   ;; `say_more` are the precedents — so the newest judgment is the
   ;; row and the older ones are its transitions; `dismissed` is
   ;; terminal, and a new judgment after it is a new row.
   :states [:live :dismissed]
   :initial :live
   :terminal #{:dismissed}
   :summary "{data.judged_by} scores {data.subject_kind} {data.score} · {state}"
   :label-template "{data.says}"
   :display {:title "An agent's judgment"}
   ;; the way back to what was judged: the row's own screen, when the
   ;; author knew the address — the reason kind's `subject` link
   :links [{:rel "subject" :href "/#{data.subject_href}"
            :summary "The row this judgment is about, on its own screen"}]
   :schema
   (into [:map
          (entry :subject_kind {:filter #{:eq}} [:string {:min 1 :max 64}])
          (entry :subject_id {:filter #{:eq}} [:string {:min 1 :max 64}])
          (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])]
         (concat judgment-input
                 ;; :judged_by carries its :filter so the one-live-note
                 ;; wall and a diagnosis read (*what did this agent
                 ;; score, and how was it answered*) push down as a
                 ;; query cond
                 [(entry :judged_by {:optional true :filter #{:eq}}
                         [:maybe [:string {:max 128}]])
                  (entry :dismissed_by {:optional true}
                         [:maybe [:string {:max 128}]])]))
   ;; :judged_by is in the CREATE model and then stamped over —
   ;; `verdict_reason`'s deliberate redundancy: left out, a body naming
   ;; somebody else would be refused by the closed schema as a stray
   ;; key, and "unknown field" is not the sentence this law wants.
   :create-schema
   (into [:map
          (entry :subject_kind {} [:string {:min 1 :max 64}])
          (entry :subject_id {} [:string {:min 1 :max 64}])
          (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])]
         (concat judgment-input
                 [(entry :judged_by {:optional true} [:maybe [:string {:max 128}]])]))
   :filterable {:state #{:eq :in}}
   :default-filters {:state "live"}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; the author reads its own judgments and may restate them, with no
   ;; grant — an agent that could not see what it scored could not tell
   ;; a dismissed note from a live one. CREATE is not on the courtesy:
   ;; a nudge on the crown is a thing the house says out loud, once, at
   ;; the grant door (outcome's posture, not insight's).
   :own-surface {:by :judged_by :actions #{:restate}}
   :on-create stamp-the-author
   :create-guards [a-judgment-is-an-agents
                   cites-what-it-read
                   not-your-own-row
                   one-live-note-per-row-and-author]
   :actions
   {:restate
    {:from #{:live} :to :live
     :input (into [:map] the-judgment)
     :guards [the-judgment-is-your-own]
     :edit {:prefill [:score :says]}
     :record true
     ;; `says` is required prose, but :restate PREFILLS the sentence
     ;; that already stands and is :record true — the form is never
     ;; blank and the prior words live in the transition log, so a
     ;; mis-click loses an in-progress edit and not the judgment
     ;; (value's `restate`, the same waiver for the same reason).
     :waives #{:large-effort}
     :handler write-the-judgment
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The score and the sentence are overwritten with what you write; the rows you cited at birth stand, the log keeps what stood before, and the crown reads the new judgment at its next read."}
     :display {:label "Restate" :order 1
               :description "Change your score or your sentence — the earlier judgment stays in the log, and the crown reads the new one"}}
    :dismiss
    {:from #{:live} :to :dismissed
     :guards [a-person-dismisses]
     :handler stamp-the-dismisser
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The judgment stops counting and stays on record under the agent's name. Nothing is deleted; the crown ranks the row without it from the next read, and the agent may judge the row again in a new note."}
     :display {:label "Dismiss" :order 2
               :description "Answer this judgment: not this agent's word, not on this row — the crown ranks without it"}}}
   :scenarios [a-person-does-not-write-a-judgment
               nobody-restates-somebody-elses-judgment
               an-agent-does-not-dismiss-a-judgment
               a-dismissed-judgment-is-not-restated]})
