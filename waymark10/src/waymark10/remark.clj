(ns waymark10.remark
  "The thread's turn (waymark-b4s): words on a subject, with no
  verdict attached.

  The owner's own words are what this kind is downstream of:

    You suggest some actions to take, I engage either by taking
    actions or responding. Then you respond to my response and away
    we go. Maybe this is how we refine actions against outcomes more
    organically.

  ── WHAT ALREADY SPOKE, AND WHAT COULD NOT ──

  The house could already speak twice: a `verdict_reason` speaks while
  ANSWERING (a quick word riding a decline, a sentence one screen
  deeper), and an `insight` speaks as a FINDING (the composer's
  diagnosis, citing what it read). What nobody could say was a turn
  that is JUST WORDS — 'what if Saturday instead?', 'the wood arrived
  today', 'ask Jack, not Andree' — words that change no state, decline
  nothing, and want an answer rather than a tap. That is the turn this
  kind holds, and a conversation is nothing but these laid end to end
  on one subject.

  ── THE THREAD IS THE SUBJECT, NOT A NEW CONTAINER ──

  There is no thread row and none is wanted. The thread's edges
  already live in the house — an outcome carries `request_id`, a
  reason carries its subject, a diagnosis cites the decline it answers
  — and this kind joins them the same way: `{subject_kind,
  subject_id}` names what the words are about (the tickler's shape,
  one kind serving every row in the house), and reading a thread is
  reading the remarks on one subject oldest-first, beside the verdicts
  and reasons that subject already collected. `in_reply_to` may name
  the one remark this one answers when the subject alone is not
  enough; it is a courtesy to the reader, never a container.

  ── WHO SPEAKS, AND THE ONE WALL ──

  Anybody named — a person's turn and a composer's reply land through
  the same create, which is the point: the conversation is symmetric
  even though the power is not (a person's words can carry a verdict
  one door over; a composer's words can only ever propose). The wall
  is `verdict_reason`'s, inherited whole: the words are the sayer's
  own. The engine stamps `said_by` from whoever posted, a body naming
  somebody else is refused in the household's words, and rewording is
  the sayer's own hand — a record of what somebody meant that a second
  party may rewrite is not a record of what they meant.

  ── WHAT IT DOES NOT CHECK ──

  Nothing here reads the subject — the tickler's posture, for the
  tickler's reason: a turn naming any row in the house cannot ask a
  kind-specific question of it, and a wall that tried would be a wall
  that guessed. And there is no unique index, deliberately: a verdict
  happens once and its reason is one row, but a conversation is many
  turns on one subject, and a wall against saying two things would be
  a wall against the very shape this kind exists for.

  ── WHO READS IT ──

  The sayer, at their own address, with no grant (`:own-surface {:by
  :said_by}`). A COMPOSER reads the house's turns through an ordinary
  grant the household approves by name — `{:kind \"remark\" :actions
  [\"create\"]}`, read plus the door to answer — and its sitting's
  duty to the unanswered turn lives in the sitting's own contract, not
  in a door: no law can make an answer good, only visible."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def remark-kind
  "The record's kind keyword — the definite marker, never a name
  string."
  :remark)

;; ── the walls ───────────────────────────────────────────────────────

(g/defguard a-remark-is-your-own
  {:judges [:said_by]
   :reads [:principal]
   :vars [:named :you]
   :explain "This would file a remark under {named}, and you are {you}. A turn in a conversation is the sayer's own voice — words put in another member's mouth are exactly the record the next reader would then answer."}
  [_row inp ctx]
  (let [named (some-> (:said_by inp) str str/trim not-empty)
        me (str (:id (:principal ctx)))]
    (if (or (nil? named) (= named me))
      (t/allow)
      (t/deny {:vars {:named named :you me}}))))

(g/defguard the-remark-is-your-own-hand
  {:reads [:principal]
   :vars [:whose :you]
   :open "Rewording is the sayer's own hand. There is no editor's door here and none is wanted — a conversation whose turns a second party may rewrite is not a conversation."
   :explain "This is {whose}'s remark and you are {you}. Read it, answer it with a turn of your own — but the words in it stay theirs."}
  [row _inp ctx]
  ;; verdict_reason's load-bearing wall, inherited for the same
  ;; reason: the own-surface affordance is answered at KIND level by
  ;; grants/visibility, so a composer holding a remark grant is
  ;; ADVERTISED this door on rows that are not its own. The door
  ;; refuses, by name, in the household's words.
  (let [whose (str (get-in row [:data :said_by]))
        me (str (:id (:principal ctx)))]
    (if (= whose me)
      (t/allow)
      (t/deny {:vars {:whose whose :you me}}))))

(defhandler stamp-the-sayer
  [row ctx]
  ;; whoever posts is whose turn it is — verdict_reason's stamp, the
  ;; wall above having already refused a body that named somebody else
  (assoc-in row [:data :said_by] (:id (:principal ctx))))

(defhandler rewrite-the-words
  [row inp _ctx]
  (assoc-in row [:data :says] (:says inp)))

;; ── the law, written down as scenarios ──────────────────────────────

(defscenario nobody-speaks-in-somebody-elses-voice
  "A turn is first-person or it is nothing — the reason kind's law,
   held again here, because this is the other kind in the house whose
   whole purpose is to be read back later as what somebody said."
  {:kind    :remark
   :attempt :create
   :input   {:said_by "colton"
             :subject_kind "outcome"
             :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :says "Saturday would work better than Friday."}
   :as      {:id "iris" :type :person}
   :expect  {:refused :a-remark-is-your-own
             :because "the sayer's own voice"}})

(defscenario nobody-rewords-somebody-elses-turn
  "…and the deeper layer. A composer granted the remark door so it can
   ANSWER is advertised the reword door on every turn it can read —
   the own-surface affordance is kind-level — so the wall is what
   keeps the power to reply from carrying a quiet edit on the very
   words being replied to."
  {:kind    :remark
   :attempt :reword
   :row     {:state :noted
             :data {:subject_kind "outcome"
                    :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
                    :says "Saturday would work better than Friday."
                    :said_by "colton"}}
   :input   {:says "Friday is fine after all."}
   :as      {:id "agent-cairn" :type :agent}
   :expect  {:refused :the-remark-is-your-own-hand
             :because "the words in it stay theirs"}})

;; ── the prose the doors wear ────────────────────────────────────────

(def ^:private prose
  {:subject_kind
   {:x-display
    {:label "What sort of row"
     :help "The kind this turn is about — outcome, outcome_piece, tickler, insight, composition_request. A name beside the id rather than a reference, because a conversation may be about anything in the house."}}
   :subject_id
   {:x-display
    {:label "Which row"
     :help "That row's own id, the one in its address bar. The thread is every remark naming this pair, oldest first, beside the verdicts and reasons the row already collected."}}
   :subject_href
   {:optional true
    :x-display
    {:hidden true
     :label "Its address"
     :help "Where the row lives, as it appears after the site name. Leave it blank and the remark still works; fill it in and it can take you straight back."}}
   :about
   {:x-display
    {:label "What it is about"
     :help "The subject, in the words the household would use out loud — copied at birth so the turn still reads months later with the row behind it possibly long gone."}}
   :says
   {:examples ["What if we held Saturday morning instead — the wood arrives Friday."]
    :x-display
    {:widget "prose"
     :label "The turn"
     :help "What you want to say about this — a question, a suggestion, a fact the composer should know. No verdict rides this: nothing is accepted or declined by saying something."}}
   :in_reply_to
   {:x-display
    {:raw true
     :label "Answers"
     :help "The remark this one answers, by id, when the subject alone would not make it clear. A courtesy to the reader — the thread is the subject either way."}}
   :said_by
   {:x-display
    {:raw true
     :label "Said by"
     :help "Whose turn this is. The engine stamps it from whoever posted, and a body naming somebody else is refused by name — nobody speaks in another member's voice."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

;; ── :remark — the thread's turn ─────────────────────────────────────

(defresource remark
  {:kind :remark
   :plural "remarks"
   ;; a record of something said, not work to do — verdict_reason's
   ;; :system, for the same reason: a :primary spelling would card
   ;; every sentence in do-now beside the actual work
   :nav :system
   ;; one state, deliberately not terminal — reword is its only exit
   ;; and that self-loop is the sayer's own editing hand
   ;; (verdict_reason's :noted, inherited whole)
   :states [:noted]
   :initial :noted
   :terminal #{}
   ;; NO :unique, deliberately — a conversation is many turns on one
   ;; subject (see the ns docstring); the reason kind's one-per-verdict
   ;; index would refuse the second sentence ever said
   :summary "{data.says} · said by {data.said_by}"
   :label-template "{data.says}"
   ;; the row's own line, never the kind label (waymark-iqa.22's law)
   :display {:title "{data.says}"}
   :links [{:rel "subject" :href "/#{data.subject_href}"
            :summary "The row this turn is about, on its own screen"}]
   :schema
   [:map
    (entry :subject_kind {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :subject_id {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])
    (entry :about {:optional true} [:maybe [:string {:max 200}]])
    (entry :says {} [:string {:min 1 :max 600}])
    (entry :in_reply_to {:optional true} [:maybe [:string {:max 64}]])
    (entry :said_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])]
   ;; :said_by rides the create model and is stamped over —
   ;; verdict_reason's deliberate redundancy, so a body naming somebody
   ;; else hears the law's sentence rather than "unknown field"
   :create-schema
   [:map
    (entry :subject_kind {} [:string {:min 1 :max 64}])
    (entry :subject_id {} [:string {:min 1 :max 64}])
    (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])
    (entry :about {:optional true} [:maybe [:string {:max 200}]])
    (entry :says {} [:string {:min 1 :max 600}])
    (entry :in_reply_to {:optional true} [:maybe [:string {:max 64}]])
    (entry :said_by {:optional true} [:maybe [:string {:max 128}]])]
   :filterable {:state #{:eq :in}}
   ;; oldest-first is how a conversation reads, and unlike the reason
   ;; kind's newest-first record, the DEFAULT should read like one
   :sortable {:fields [:created_at] :default "created_at"}
   ;; the sayer reads and rewords their own turns with no grant; a
   ;; composer holds {:kind "remark" :actions ["create"]} — reading
   ;; plus the door to answer, and nothing else
   :own-surface {:by :said_by :actions #{:reword}}
   :on-create stamp-the-sayer
   :create-guards [a-remark-is-your-own]
   :actions
   {:reword
    {:from #{:noted} :to :noted
     :input [:map
             [:says {:x-display
                     {:widget "prose"
                      :label "The turn"
                      :help "Your own words, changed by your own hand. The turns answering this one were answering what it said before — reword for clarity, not for revision of the record."}}
              [:string {:min 1 :max 600}]]]
     :guards [the-remark-is-your-own-hand]
     :edit {:prefill [:says]}
     :record true
     ;; `says` is required prose, but :reword PREFILLS the turn that
     ;; already stands and is :record true — the form is never blank
     ;; and the prior words live in the transition log, so a mis-click
     ;; loses an in-progress edit and not the turn (ranking_note's
     ;; `restate`, the same waiver for the same reason).
     :waives #{:large-effort}
     :handler rewrite-the-words
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Reword" :order 1
               :description "Change your own words — whose turn it is and what it answers stay put"}}}
   :scenarios [nobody-speaks-in-somebody-elses-voice
               nobody-rewords-somebody-elses-turn]})
