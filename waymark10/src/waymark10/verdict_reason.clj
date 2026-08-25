(ns waymark10.verdict-reason
  "The reason door (waymark-jfv.16): the taps learn to speak.

  The owner's own words are what this kind is downstream of:

    We should be able to reject with feedback, so the system can learn
    over time beyond just inaction why we're not engaging.

  A decline already teaches something — `not_this` on a piece says the
  composition was wrong, `not_this_week` on a bundle says the timing
  was — but it says it in the vocabulary of STATES, which is four
  words wide and cannot tell *the wrong Saturday* from *the wrong
  Saturday for me*. This kind is where the household says which.

  ── TWO LAYERS, AND THE FIRST ONE IS ONE TAP ──

  The constraint that shapes the whole design is the one waymark-iqa.4
  found and waymark-jfv.3 met from another side: A VERDICT THAT
  COLLECTS ANYTHING FALLS OFF THE CARD. `demand/effort` reduces an
  action to its WORST input field, an optional `[:maybe [:string]]`
  renders `anyOf [string, null]` and reads `recall`, and
  `feed/split-verbs` moves anything heavier than `feed/card-ceiling`
  out of `actions` and into `heavier` — a link, not a thumb. So the
  decline stays exactly what it was: input-free, `assent`, one tap.

  What speaks is the SETTLED CARD. After the verdict lands, the card
  offers four quick reasons — a small closed enum, one more OPTIONAL
  tap, selection effort, still inside the card grammar. Tapping one
  CREATES A ROW HERE. Tapping none is a complete answer, and that is
  not a courtesy: silence is what a household says most of the time,
  and a door that needed an answer would be a door that manufactured
  one.

  The second layer is deeper and it is this row's own screen. `words`
  is free text in the member's own voice, optional at birth and
  addable afterwards through `say_more` — which is `composition`
  effort by construction, so it can never climb back onto a card.

  THE QUICK WORD IS REQUIRED AND THE SENTENCE IS NOT, and that order
  is the design rather than an accident of the schema. A row exists
  because a chip was tapped; the sentence deepens a word that is
  already on the record. The alternative — both optional, with a wall
  refusing the row that says neither — was written and taken out
  again: it made a `composition` field guard-judged, which is exactly
  what `usability/effort-honesty` warns about (a blank box where a
  picker belongs), and it bought a case the four words already cover
  by picking the closest and saying the rest underneath.

  ── WHY A ROW OF ITS OWN, AND NOT A FIELD ON THE VERDICT ──

  Three shapes were weighed. Two are not merely heavier — they are
  refused by this framework's own gates.

  1. AN OPTIONAL INPUT ON THE VERDICT DOOR. Dead twice. It changes the
     demand class (above), so the one-tap decline becomes a dialog;
     and the row a decline leaves behind is TERMINAL, so there is no
     second firing to supply the reason with. The engine's natural
     replay answers a second tap with the first tap's own response.
  2. THE REASON AS AN AMENDMENT — a `:record true` revision on the
     declined row. `checks/check-terminal-no-exit` refuses any action
     whose `:from` intersects `:terminal` BY NAME, so a follow-up door
     on a declined piece is not a design choice this tree can make:
     terminal states carry no actions, self-loops included.
  3. A ROW OF ITS OWN. A create is always open — there is no state to
     be out of — and the subject is named the way the tickler names
     one, as `{subject_kind, subject_id}` rather than as a ref, so ONE
     KIND SERVES EVERY VERDICT IN THE HOUSE. A declined outcome, a
     declined piece, a let-go tickler, a dismissed insight, a
     dismissed guess about who somebody is: the same four words, the
     same row, the same read.

  ── WHERE IT LIVES, AND WHY THAT IS THE FRAMEWORK ──

  `feed_view` is the precedent and the reasoning is the same one
  (waymark-8um.1): a record a SCREEN posts, about cards the feed
  itself minted, named by the feed module as a keyword and enrolled
  `:always`. The chips are drawn by `135-feed-screen.js`, which is the
  generic page and knows no application's kind names; the door it
  posts to arrives on the feed document (`reasons`) exactly as the
  view door does, read off the declaration's own `:plural` and its own
  enum. An application kind would have made the framework's own screen
  reach for a name only one deployment has.

  ── WHAT IT DOES NOT CHECK ──

  Nothing here reads the subject. The tickler's own posture, for the
  tickler's own reason: a marker naming any row in the house cannot
  ask a kind-specific question of it, and a wall that tried would be a
  wall that guessed. The `verdict` is a plain string for `feed_view`'s
  reason one field over — a record whose schema refused an action name
  the engine has since renamed would be a record that could not be
  written, which is the wrong way round.

  What it DOES check is that the reason is the sayer's own, and that
  one verdict collects one reason — the wall in the household's words,
  the unique index as the fact under a race, which is `feed_view`'s
  belt and braces exactly.

  ── WHO READS IT ──

  The person who said it, at their own address, with no grant
  (`:own-surface {:by :said_by}`). A COMPOSER reads it through an
  ordinary grant the household approves by name —
  `{:kind \"verdict_reason\" :actions []}` — the insight precedent,
  conferring reading and nothing else because there is nothing else to
  confer. That grant is the input waymark-8um law 4's diagnosis duty
  has been waiting for: non-engagement made of words instead of
  silence."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def reason-kind
  "The record's kind keyword — the definite marker, never a name
  string."
  :verdict_reason)

;; ── the four words, and they are the household's ────────────────────

(def reasons
  "THE QUICK REASONS, in the order a card shows them. Four, and the
  smallness is the decision rather than an omission: a chip row is
  read standing up, and a menu of nine is a form wearing chips.

  They are the four axes a decline can actually run along — WHEN, WHAT,
  HOW, and EVER — because those are the four different things a
  composer would do differently next time. A fifth that meant 'I do not
  want to say' would be the silence that is already available by not
  tapping.

  The tokens are the wire's; the sentences beside them are the
  household's own and are what a chip and a select both render
  (`:x-display {:choices …}`, one spelling for both surfaces)."
  [["wrong_time" "Wrong time"]
   ["wrong_piece" "Wrong piece"]
   ["wrong_way" "Not this way"]
   ["never_this" "Never this"]])

(def reason-enum
  "The schema form `reason` wears — the same four, spelled as the
  closed enum a form and a wire read."
  (into [:enum] (map first) reasons))

(def reason-choices
  "Token → the words a person reads. The generic form renders it
  (170-forms.js), the feed's settled card renders it as a chip
  (135-feed-screen.js, off the published schema rather than off a
  second copy), and the usability battery insists it exists."
  (into {} reasons))

;; ── the walls ───────────────────────────────────────────────────────

(g/defguard a-reason-is-your-own
  {:judges [:said_by]
   :reads [:principal]
   :vars [:named :you]
   :explain "This would file a reason under {named}, and you are {you}. A reason is why the person who answered answered that way — nobody explains somebody else's no, because a sentence put in another member's mouth is exactly the record a composer would then learn from."}
  [_row inp ctx]
  (let [named (some-> (:said_by inp) str str/trim not-empty)
        me (str (:id (:principal ctx)))]
    (if (or (nil? named) (= named me))
      (t/allow)
      (t/deny {:vars {:named named :you me}}))))

(g/defguard one-reason-per-verdict
  {:reads [:principal :verdict_reason]
   :vars [:verdict :subject]
   :open "One verdict, one reason. The answer happened once; why it happened is one row, and it grows by saying more rather than by being said again."
   :explain "You already said why {verdict} on {subject}. Nothing was written and nothing was lost — the reason is on the record, and its own screen is where you add to it in your own words."}
  [_row inp ctx]
  (let [find' (:find ctx)]
    ;; the storage-free probe advertises optimistically — feed_view's
    ;; own posture, and the write path always carries the consult
    (if (nil? find')
      (t/allow)
      (let [k (str (:subject_kind inp))
            id (str (:subject_id inp))
            verdict (str (:verdict inp))
            already (find' reason-kind
                           {:subject_kind k :subject_id id :verdict verdict}
                           {:limit 1})]
        (if (seq already)
          (t/deny {:vars {:verdict verdict :subject (str k " " id)}})
          (t/allow))))))

(g/defguard the-reason-is-your-own-hand
  {:reads [:principal]
   :vars [:whose :you]
   :open "Saying more is the sayer's own hand. There is no editor's door here and none is wanted — a record of what somebody meant that a second party may rewrite is not a record of what they meant."
   :explain "This is {whose}'s reason and you are {you}. Read it, learn from it, recompose against it — but the words in it stay theirs."}
  [row _inp ctx]
  ;; THE WALL THE PACK FOUND, and it is load-bearing rather than
  ;; belt-and-braces. `grants/visibility`'s `:action?` answers the
  ;; own-surface affordance at KIND level — `(contains? (:actions
  ;; (own-of k)) a)`, with no row in the question — so a composer
  ;; holding `{:kind "verdict_reason" :actions []}` is ADVERTISED this
  ;; door on rows that are not theirs. That advertisement is the
  ;; framework's shape and not this kind's to change; what is this
  ;; kind's is that the door refuse, by name, in the household's own
  ;; words. Without it a read-only diagnosis grant would carry a quiet
  ;; edit on the very sentences it was granted to read.
  (let [whose (str (get-in row [:data :said_by]))
        me (str (:id (:principal ctx)))]
    (if (= whose me)
      (t/allow)
      (t/deny {:vars {:whose whose :you me}}))))

(defhandler stamp-the-sayer
  [row ctx]
  ;; the one stamp, and it is not the caller's to give — whoever posts
  ;; is whose reason it is (`feed_view/stamp-the-viewer`, and
  ;; `recipe_proposal`'s `proposed_by` behind that). The wall above has
  ;; already refused a body that named somebody else; this is what
  ;; makes a body that named NOBODY land on the right person anyway.
  (assoc-in row [:data :said_by] (:id (:principal ctx))))

(defhandler write-the-words
  [row inp _ctx]
  ;; the deeper layer, and it is a wholesale overwrite of ONE field:
  ;; the quick word stands as it was tapped, and saying more never
  ;; silently regrades a chip into something else.
  (assoc-in row [:data :words] (:words inp)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; ONE SCENARIO, AND IT DEFERS TO THE SUITE. `a-reason-is-your-own`
;; reads the caller and the body and nothing else, so it would be
;; judged in `check` beside the usability warnings — but a create
;; scenario is judged against the door's WHOLE guard chain, and
;; `one-reason-per-verdict` reads rows, so the tier is the chain's
;; rather than this wall's. `one-reason-per-verdict` itself is proved
;; over the wire, where the pack meets it against a real second tap.

(defscenario nobody-explains-somebody-elses-no
  "A reason is first-person or it is nothing. A row that could name
   another member as its sayer is a row that can put words in their
   mouth — and this is the one kind in the house whose whole purpose
   is to be read back later as what somebody meant."
  {:kind    :verdict_reason
   :attempt :create
   :input   {:said_by "colton"
             :subject_kind "outcome_piece"
             :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :verdict "not_this"
             :reason "wrong_time"}
   :as      {:id "iris" :type :person}
   :expect  {:refused :a-reason-is-your-own
             :because "why the person who answered"}})

(defscenario nobody-rewrites-somebody-elses-reason
  "…and the same sentence about the deeper layer. A composer holding a
   read grant over this kind is ADVERTISED this door — the own-surface
   affordance is answered at kind level by grants/visibility, with no
   row in the question — so the wall is what keeps a read-only
   diagnosis from carrying a quiet edit on the very sentences it was
   granted to read."
  {:kind    :verdict_reason
   :attempt :say_more
   :row     {:state :noted
             :data {:subject_kind "outcome_piece"
                    :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
                    :verdict "not_this"
                    :reason "wrong_time"
                    :said_by "colton"}}
   :input   {:words "Actually it was fine."}
   :as      {:id "agent-ari" :type :agent}
   :expect  {:refused :the-reason-is-your-own-hand
             :because "the words in it stay theirs"}})

;; ── the prose the doors wear ────────────────────────────────────────

(def ^:private prose
  {:subject_kind
   {:x-display
    {:label "What sort of row"
     :help "The kind whose verdict this explains — outcome_piece, outcome, tickler, insight. Kept as a name beside the id rather than as a reference, because a reason may be about anything the house answers and a reference names one kind only."}}
   :subject_id
   {:x-display
    {:label "Which row"
     :help "That row's own id, the one in its address bar. The reason holds a pointer, never a copy, so the answer stays where it lives."}}
   :subject_href
   {:optional true
    :x-display
    {:hidden true
     :label "Its address"
     :help "Where the row lives, as it appears after the site name (/api/outcome_pieces/01H…). Leave it blank and the reason still works; fill it in and it can take you straight back."}}
   :about
   {:x-display
    {:label "What it was about"
     :help "The thing that was answered, in the words the household would use out loud — copied at birth so this row still reads months later with the row behind it possibly long gone."}}
   :verdict
   {:x-display
    {:label "The answer this explains"
     :help "The door that was tapped, by its own name — not_this, not_this_week, let_it_go, dismiss. A plain name and not a closed list, because a record that refused an action the engine has since renamed would be a record nobody could write."}}
   :reason
   {:x-display
    {:label "The quick word"
     :choices reason-choices
     :help "One of four, and one tap: the timing was wrong, this part was wrong, the shape of it was wrong, or this is not something to bring back at all. Pick the closest one and say the rest in your own words underneath."}}
   :words
   {:examples ["The Saturday is right — it is the drive there that never happens."]
    :x-display
    {:widget "prose"
     :label "In your own words"
     :help "Anything the quick word could not carry — what would have had to be different, or what is actually in the way. Never required: the word on its own is a complete answer, and this box exists for the times it is not."}}
   :said_by
   {:x-display
    {:raw true
     :label "Said by"
     :help "Whose reason this is. The engine stamps it from whoever posted, and a body naming somebody else is refused by name — nobody explains another member's no."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

;; ── :verdict_reason — why the house said no ─────────────────────────

(defresource verdict-reason
  {:kind :verdict_reason
   :plural "verdict_reasons"
   ;; hand-written kinds inherit no :nav, and :system is the honest one
   ;; here for `feed_view`'s reason: a reason is a record of an answer
   ;; already given, so a :primary spelling would card it in do-now
   ;; beside the actual work and congratulate the house in fuel for
   ;; having explained itself.
   :nav :system
   ;; ONE STATE, and it is deliberately NOT terminal. A reason happened
   ;; — there is no lifecycle here and none is wanted — but the second
   ;; layer is an edit of this row and `checks/check-terminal-no-exit`
   ;; refuses an action out of a terminal state by name. So :noted is
   ;; the only state, `say_more` is its only exit, and that self-loop
   ;; IS the deeper screen (`feed_view_consent` is the precedent for an
   ;; empty :terminal; the tickler's `not_now` is the precedent for a
   ;; verdict that returns to where it stood).
   :states [:noted]
   :initial :noted
   :terminal #{}
   ;; ONE ROW PER VERDICT — the same rule `one-reason-per-verdict` says
   ;; in the household's words, said again in the storage's, and the
   ;; pair is belt and braces for `feed_view`'s own reason: the guard is
   ;; the sentence a person reads, the index is the fact under a race.
   ;;
   ;; The order of the three fields is the only index this declaration
   ;; can ask for, and it is spent on the reader that is coming: a
   ;; composer's diagnosis reads every reason about one subject, so
   ;; (subject_kind, subject_id) is a prefix of the group.
   :unique [[:subject_kind :subject_id :verdict]]
   :summary "{data.verdict} on {data.about} · {data.reason}"
   :label-template "{data.about}"
   :display {:title "Why the house said no"}
   ;; the way back to what was answered: the row's own screen, when
   ;; whoever tapped knew the address. A reason with no href relates to
   ;; nothing and the link simply omits — the framework's own rule, and
   ;; the tickler's `subject` link one kind over.
   :links [{:rel "subject" :href "/#{data.subject_href}"
            :summary "The row this reason is about, on its own screen"}]
   :schema
   [:map
    (entry :subject_kind {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :subject_id {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])
    (entry :about {:optional true} [:maybe [:string {:max 200}]])
    (entry :verdict {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :reason {:filter #{:eq}} reason-enum)
    (entry :words {:optional true} [:maybe [:string {:max 600}]])
    (entry :said_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])]
   ;; :said_by is in the CREATE model and then stamped over, and the
   ;; redundancy is `feed_view_consent`'s deliberate one: left out, a
   ;; body that named somebody else would be refused by the closed
   ;; schema as a stray key, and "unknown field" is not the sentence
   ;; this law wants to say.
   :create-schema
   [:map
    (entry :subject_kind {} [:string {:min 1 :max 64}])
    (entry :subject_id {} [:string {:min 1 :max 64}])
    (entry :subject_href {:optional true} [:maybe [:string {:max 500}]])
    (entry :about {:optional true} [:maybe [:string {:max 200}]])
    (entry :verdict {} [:string {:min 1 :max 64}])
    (entry :reason {} reason-enum)
    (entry :words {:optional true} [:maybe [:string {:max 600}]])
    (entry :said_by {:optional true} [:maybe [:string {:max 128}]])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; the sayer reads their own reasons and may add to them, with no
   ;; grant — a note you cannot re-read is not a note. A COMPOSER holds
   ;; an ordinary read grant instead (`{:kind "verdict_reason"
   ;; :actions []}`), which is the insight precedent and confers
   ;; reading and nothing else.
   :own-surface {:by :said_by :actions #{:say_more}}
   :on-create stamp-the-sayer
   ;; SHAPE FIRST, WORLD LAST — insight's ordering, inherited whole: a
   ;; body that says nothing hears about itself before it hears
   ;; anything about the house, and because the last wall reads ROWS a
   ;; refused create spends nothing.
   :create-guards [a-reason-is-your-own
                   one-reason-per-verdict]
   :actions
   {;; THE SECOND LAYER, and it can never climb onto a card: a prose
    ;; box is `composition`, which is heavier than `feed/card-ceiling`,
    ;; so `split-verbs` renders it as a place to GO. That is the whole
    ;; shape of this bead said in one declaration — the quick word is a
    ;; thumb, the sentence is a screen.
    :say_more
    {:from #{:noted} :to :noted
     :input [:map
             [:words {:optional true
                      :x-display
                      {:widget "prose"
                       :label "In your own words"
                       :help "What the quick word could not carry — what would have had to be different, or what is actually in the way. Clearing it is allowed: this is your own note about your own answer."}}
              [:maybe [:string {:max 600}]]]]
     :guards [the-reason-is-your-own-hand]
     :edit {:prefill [:words]}
     :record true
     :handler write-the-words
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Say more" :order 1
               :description "Add the part the four words could not carry — or change what you wrote; the quick word stands either way"}}}
   :scenarios [nobody-explains-somebody-elses-no
               nobody-rewrites-somebody-elses-reason]})
