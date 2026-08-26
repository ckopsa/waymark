(ns workqueue10.resources.insight
  "The insight (waymark-iqa.6): the one card in the feed that is not a
  row the household already had. Everything else the feed shows is a
  projection of work somebody wrote down; an insight is a FINDING —
  the compiler read the house, noticed that the porch project has been
  stalled since June, and says so in one sentence with the one
  physical next step attached.

  IT ONLY EVER OFFERS. That is the epic's law and this file is where
  it is made structural rather than promised:

  - NO CITATION, NO PUBLISH. `cites-what-it-claims` refuses an
    insight with an empty `evidence` list, and refuses an address
    naming a collection this engine does not serve. A finding with
    nothing behind it is an opinion, and the household has enough of
    those.
  - NO OFFERED ACTION, NO PUBLISH. `offers-something-light` refuses
    an offer whose kind or action this engine does not declare, and
    refuses one that would cost more than a tap. This is the ONE
    place the feed's ≤-selection rule is a DOOR rather than a
    projection, because it is the one place a verb is DECLARED
    (by the author, in data) rather than inherited from a row.
  - RANKED, NOT CAPPED. Until waymark-1uv.8 a third wall stood here:
    `insights-are-capped`, three findings a day per author, *the wall
    that makes the compiler rank instead of dump*. It was the
    precedent the outcome cap copied and it was the same proxy — a
    wall on the writer standing in for a rank on the reader — and it
    left under the owner's ruling (docs/spec-outcome-menu.md § 'Ranked,
    not capped'): the finding IS the indexing that ruling said not to
    limit, its write pushes nothing and mails nobody, and the offer
    below is an ADDRESS that writes no other row. What protects the
    household's attention now is the feed's own rank on the insights
    line (`feed/default-insight-rank`, six numbers on the recipe row,
    read back on every insight card), with `:take` as the exposure
    floor. A compiler may publish as many findings as it finds; the
    rank decides which two a person reads today.
  - THE FINDER DOES NOT DECIDE. `:decider {:not {:field
    :authored_by}}` — the four-eyes wall doing real work. The agent
    that published a finding is structurally incapable of accepting
    it; acceptance belongs to a member and is audited as that
    member's own transition.

  THE COMPILER IS NOT IN HERE, AND MUST NOT BE. It is an external
  agent at the MCP door holding a grant — the dispatch-probe pattern
  (docs/spec-feed.md § 'The compiler contract') — wearing
  `waymark_query` / `waymark_get` / `waymark_invoke` like any other
  leash. This file is the engine's half of that contract: the kind,
  its law, and its scenarios. Automated cadence is waymark-53u; a
  human running the probe is a valid v1 and the rank reads either
  way.

  WHAT ACCEPTING DOES, AND WHY IT DOES NOT FIRE THE OFFER ITSELF.
  `take` records the household's answer and moves the finding to
  `taken`; the OFFER is reached through the card's own `offer` link,
  where the reader's grant gates the verb exactly as the row's own
  screen would. That is not a shortfall of nerve, it is the only
  honest door available, and the evidence is in three places:

  1. `invoke/make-ctx` hands a handler's `ctx :invoke` the OUTER
     principal (`{:principal principal}`, invoke.clj ~:294), which is
     exactly right here — the outer principal IS the accepting
     member. So waymark-442.14's recorded worry does not apply to
     this bead, and it is closed rather than inherited.
  2. But grant projection is a REQUEST-level wall: `router/check-row!`,
     `check-action!` and `grants/check-args!` all read
     `(:waymark10/visibility req)`, and a handler ctx carries no
     visibility. A cross-write from a handler is therefore gated by
     the DECLARATION, never by the caller's grant.
  3. Which every other cross-write in the tree can afford, because
     every one of them names its target kind and action as LITERALS
     (`ingredient/absorb-duplicate`, `grocery_list`) and advertises
     the pair in `:touches`, which `checks-assembly/check-touches`
     verifies at assembly. An insight's target is DATA, chosen by the
     author — so it is the one cross-write no declaration could name,
     no `:touches` could advertise, and no grant would re-gate. A
     leashed compiler could then propose `grant.extend` on its own
     leash and have one member's tap widen it.

  So the offer is an ADDRESS rather than a trigger, and the address is
  checked at the door against the registry so the author cannot invent
  it. waymark-iqa.18 is the seam that would change this answer: a
  handler ctx that carries the caller's visibility.

  NO NOTES ON EITHER VERDICT, DELIBERATELY. waymark-iqa.4 found it
  first: the sugar's note input is `[:maybe [:string {:max 240}]]`,
  which `demand/field-class` reads as `recall` — heavier than the
  feed's card ceiling — so `feed/split-verbs` would move that verdict
  out of `actions` and into `heavier`, and the one-tap answer would
  become a link. Both answers here are meant to be tapped. A finding
  that wants a written reason wants a second door, not a note."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.dsl :refer [defguardfn defresource defscenario]]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── addresses, read rather than guessed ─────────────────────────────

(defn row-address
  "`/api/<plural>/<id>` → {:plural … :id …}, nil for anything else.
  An address is the ONE shape a citation and an offer may wear, and it
  is the shape the household's own URL bar already carries — the same
  fact `feed/screen-of` prefixes with `/#` and the same one
  `workqueue10.sources.waymark/with-origin` derives a `source_ui_href`
  from. Query strings, action doors (`/-/`) and bare ids are not
  addresses and are refused rather than repaired."
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
  "A short, ordered rendering of what went wrong — the refusal names
  every offending address rather than the first, because a compiler
  fixing them one round trip at a time is a compiler wasting its
  reader's morning."
  [xs]
  (str/join ", " (map pr-str (sort (distinct xs)))))

;; ── the create walls ────────────────────────────────────────────────
;;
;; Both refuse AT THE DOOR and both carry :vars, so the refusal
;; sentence names the fix — which, per spec-decision-record's second
;; thesis, is also the evidence the decision record keeps. Both are
;; about SHAPE: what a finding cites and what it offers. There is no
;; PACE wall after them any more — `insights-are-capped` stood third
;; until waymark-1uv.8 and left for the reason the ns docstring gives;
;; how many findings a person reads is the feed's rank's business, and
;; how many an agent may write is nobody's.

(defguardfn cites-what-it-claims
  {:judges [:evidence]
   :reads [:storage]
   :vars [:count :offenders]
   :open "A finding cites the rows it read: at least one address, each of them /api/<collection>/<id> naming a collection this house serves."
   :explain "A finding with nothing behind it is an opinion — cite the rows you read, as addresses like /api/tasks/01H… ({count} given{offenders})."}
  [_row inp ctx]
  (let [ev (into [] (remove str/blank?) (map str (:evidence inp)))
        rdef-of (:rdef-of ctx)]
    (cond
      ;; the storage-free probe (render, the partial rehearsal) has no
      ;; registry in scope: advertise optimistically, exactly as
      ;; saved_view/composes-declared-primitives does. The write path
      ;; always carries the consult.
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

(defguardfn offers-something-light
  {:judges [:offer_kind :offer_id :offer_action :offer_href]
   :reads [:storage]
   :vars [:problem]
   :open "An insight offers one next step: a kind and an action this house declares, an action light enough to tap, and the row's own address to reach it at."
   :explain "That offer is not something the house can do in a tap: {problem}"}
  [_row inp ctx]
  (let [rdef-of (:rdef-of ctx)
        kind (some-> (:offer_kind inp) str str/trim not-empty)
        aname (some-> (:offer_action inp) str str/trim not-empty)
        oid (some-> (:offer_id inp) str str/trim not-empty)
        href (some-> (:offer_href inp) str str/trim not-empty)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (if (nil? rdef-of)
      ;; the storage-free probe again — no registry, no verdict
      (t/allow)
      (let [rd (some-> kind rdef-of)
            a (when rd (get-in rd [:actions (keyword aname)]))]
        (cond
          (nil? kind)
          (deny (str "it names no kind. A finding the household cannot act"
                     " on is a sentence, and the feed already has prose."))

          (nil? rd)
          (deny (str "this house serves nothing called " (pr-str kind) "."))

          (nil? oid)
          (deny (str "it names no row — say WHICH " kind " the next step"
                     " is about."))

          (nil? aname)
          (deny (str "it names no action of " (pr-str kind) "."))

          (nil? a)
          (deny (str (pr-str kind) " declares no action called "
                     (pr-str aname) "; it answers to "
                     (listed (map name (keys (:actions rd)))) "."))

          :else
          (let [effort (if (:input a)
                         (demand/effort a (schema/json-schema (:input a))
                                        (get-in rd [:part-scopes (:place a) :key]))
                         "assent")]
            (cond
              ;; the ≤-selection rule, enforced at a door for the one
              ;; and only time. feed/card-ceiling is the same word on
              ;; the projection side; a second number here would be a
              ;; second opinion about what fits under a thumb.
              (demand/heavier? effort "selection")
              (deny (str (name (:kind rd)) "." aname " asks for " effort
                         " — a card offers a decision, never a form."
                         " Offer something tappable and let the row's"
                         " own screen take the typing."))

              (nil? href)
              (deny (str "it gives no address. The card has to be able to"
                         " REACH the offer: /api/" (:plural rd) "/" oid "."))

              (not= {:plural (:plural rd) :id oid} (row-address href))
              (deny (str "the address " (pr-str href) " is not where that"
                         " row lives — " kind " " oid " is at /api/"
                         (:plural rd) "/" oid "."))

              :else (t/allow))))))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Two tiers, and which is which is read off the declarations rather
;; than chosen. The two create refusals DEFER to the conformance tier
;; — both walls consult the registry (:reads [:storage]), which the
;; check tier's offline world cannot answer — so they are proved
;; through the real HTTP door against the house's own engine, which
;; is the stronger proof anyway: what a CLIENT sees. The two verdict
;; scenarios are check tier, judged with no database in the same
;; breath as the usability warnings.

(def ^:private a-published-finding
  {:finding "The porch project has not moved since June, and the next physical step is one tap away"
   :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
   :offer_kind "tickler"
   :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :offer_action "take_it_back"
   :offer_href "/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
   :authored_by "compiler"})

(defscenario no-citation-no-publish
  "A finding with nothing behind it does not reach the house. The
   compiler cites the rows it read or it does not publish — an
   uncited claim is an opinion wearing a card's clothes, and the
   refusal names the shape a citation takes."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-24T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "Three chores have been skipped two weeks running"
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "take_it_back"
             :offer_href "/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"}
   :expect  {:refused :cites-what-it-claims}})

(defscenario no-offered-action-no-publish
  "Every insight carries the one physical next step, and the house
   refuses the ones that do not. A finding that only observes is a
   notification, and this surface is not the notifier."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-24T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "The porch project has not moved since June"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]}
   :expect  {:refused :offers-something-light}})

(defscenario the-finder-does-not-decide
  "An agent that publishes a finding cannot be the one to accept it.
   That is the whole of 'it only ever offers' — not a policy the
   compiler is trusted to keep, but a wall it cannot walk through."
  {:kind    :insight
   :attempt :take
   :row     {:state :published :data a-published-finding}
   :as      {:id "compiler" :type :agent}
   :expect  {:refused :the-finder-does-not-decide}})

(defscenario a-dismissed-finding-does-not-come-back
  "Not useful is an answer, and it is kept. A dismissed finding is
   over — the compiler may find the same thing again tomorrow and
   publish it, and the feed's rank reads this dismissal against the
   new one: a finding on a next step the house already said no to
   stands below a fresh one (waymark-1uv.8), and never in front of it
   by being published again."
  {:kind    :insight
   :attempt :take
   :row     {:state :dismissed :data a-published-finding}
   :as      {:id "iris" :type :person}
   :expect  {:refused :out-of-state
             :because "Published"}})

(defscenario a-value-may-be-petitioned
  "The petition path of waymark-jfv.2, proved at the door it depends
   on — and it is the same path after jfv.10 moved the value kind's
   wall. An agent may now WRITE a value, but it may not AFFIRM one:
   `still_stands` is the one door on that kind still walled against
   agents, because an observer marking its own reading confirmed
   would be speaking in the owner's voice. So the way an agent asks
   for an amendment is unchanged: a finding, the rows it read, and the
   value's own `still_stands` as the one next step.
   `offers-something-light` admits it because that action asks for
   NOTHING and renders `assent`; the tap means 'yes, this one is
   ours', and the rewording is the owner's own `revise` on the value's
   own screen. Every door that could take the new wording takes a
   string, and a string is `recall` — which is exactly why the
   petition offers the tap and not the words."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-24T09:00:00Z"
   :as      {:id "petitioner" :type :agent}
   :input   {:finding "Six weeks of evenings went to building; the shop has not been opened since June"
             :evidence ["/api/values/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "value"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "still_stands"
             :offer_href "/api/values/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"}
   :expect  {:allowed true}})

;; ── :insight — the finding, with a next step attached ───────────────

(defresource insight
  {:kind :insight
   :plural "insights"
   :label-template "{data.finding}"
   :summary "{data.finding} · found by {data.authored_by} · {state}"
   :display {:title "Insight"}
   ;; THE OFFER IS AN ADDRESS. The card sends whoever taps it to the
   ;; row's own screen, where the row's own doors are and where the
   ;; reader's own grant gates them — the tickler's `subject` link
   ;; exactly, and for the same reason it gave: a link is the honest
   ;; way back to work that lives somewhere else. `/#` + the row's
   ;; href is `feed/screen-of`'s spelling, written by hand because a
   ;; declared link cannot ask the engine for a plural.
   :links [{:rel "offer" :href "/#{data.offer_href}"
            :summary "The row this finding is about, on its own screen"}]
   :decision
   {;; THE OPEN STATE IS `published`, not the sugar's default
    ;; `offered`. published → taken / dismissed is the epic's own
    ;; vocabulary and it is the true word here: an insight is not a
    ;; request waiting on somebody, it is a finding put where the
    ;; household will see it. (The epic also listed `seen` and
    ;; `pinned`; both are dropped deliberately — per-card seen state
    ;; is exactly what the feed's third law forbids, and the spec's
    ;; own punt says so: 'No seen/unseen state, ever'. If pinning is
    ;; ever wanted it is a third VERDICT, not a read receipt.)
    :offered :published
    ;; the finding itself, in a sentence the household reads. Under
    ;; the display battery's long-text line on purpose: a finding is a
    ;; claim, not an essay, and a card is read standing up.
    :asks    {:field :finding :max 240
              :x-display
              {:label "What you found"
               :help "One sentence, in the words the household would use — what you noticed and why it matters. Somebody is reading this on a phone between two other things, so say the thing rather than leading up to it."}}
    ;; stamped from the principal: authorship is a household fact, not
    ;; a field a caller may name for somebody else. The sugar makes it
    ;; :raw, which is right — this is a principal id, and a display
    ;; layer that dressed an agent up as a person would be hiding the
    ;; one fact the reader most needs.
    :by      :authored_by
    ;; THE FOUR-EYES WALL, DOING REAL WORK. Not a courtesy and not a
    ;; convention: it is what makes 'it only ever offers' structural.
    ;; :anyone exists (waymark-iqa.4 added it for the tickler) and an
    ;; insight must never reach for it.
    :decider {:not {:field :authored_by
                    :name :the-finder-does-not-decide
                    :explain "The finding is yours; the answer is the household's. Whoever published this cannot be the one to accept it."}}
    :stamps  {:decided-by :decided_by}
    ;; the author reads its own findings with no grant — a compiler
    ;; that could not see what it published could not tell a taken
    ;; finding from a dismissed one, and would publish it again
    ;; tomorrow. The verdict doors ride the courtesy too and meet the
    ;; wall's honest 409 rather than a mute 404.
    :own-surface true
    ;; :pacing is deliberately unspelled, and since waymark-1uv.8 the
    ;; reason is the ns docstring's rather than a sugar bug: findings
    ;; are ranked, not capped.
    :verdicts
    ;; BOTH ARE NOTE-FREE AND BOTH ARE ONE TAP. A :note would make the
    ;; verdict a `recall` demand and `feed/split-verbs` would move it
    ;; off the card into `heavier` (waymark-iqa.4's second finding).
    [{:name :take :to :taken
      :label "Do it" :style :primary :order 1
      :safety {:idempotent true :reversible false :confirm false
               :one-way "Taking a finding is the house saying yes to it — the record keeps who said so and when. The work itself is on its own screen, through this card's offer link, where its own doors are."}}
     {:name :dismiss :to :dismissed
      ;; …and it may say WHY (waymark-jfv.16): the four quick words on
      ;; the settled card, one more optional tap, a sentence one screen
      ;; deeper. `:display` is spelled whole because a verdict's own
      ;; display wins whole over the sugar's label/order pair; the two
      ;; facts are unchanged and `:display` rides no fingerprint facet,
      ;; so this kind's hash does not move.
      ;;
      ;; `:take` carries none, and the asymmetry is the point: a
      ;; composer learns from what the house turned down. Why somebody
      ;; said yes is the work itself, on its own rows.
      :display {:label "Not useful" :order 2 :reasons true}
      :safety {:idempotent true :reversible false :confirm false
               :one-way "The finding leaves the feed and stays on record. Nothing is deleted and nothing is hidden; the house has simply answered it."}}]}
   :schema
   [:map
    ;; WHAT IT READ. A vector of addresses, and the guard is what makes
    ;; it mandatory rather than the schema: a [:min 1] here would 422
    ;; before anybody said WHY, and the sentence that names the fix is
    ;; the half spec-decision-record wants kept.
    [:evidence {:optional true
                :x-display
                {:label "What you read"
                 :help "The rows this finding is built on, as addresses — /api/tasks/01H… — one per row you actually looked at. At least one, always: the house can follow them, and a claim nobody can check is a claim nobody should act on."}}
     [:maybe [:vector [:string {:min 1 :max 200}]]]]
    ;; THE OFFER, in four agreeing parts. The pair is what the law
    ;; judges; the address is what the card reaches; the guard proves
    ;; they say the same thing, so an author cannot name one row and
    ;; link another.
    [:offer_kind {:optional true :filter #{:eq}
                  :x-display
                  {:label "The next step's kind"
                   :help "Which sort of row the next step happens on — task, tickler, chore_run. It has to be something this house actually declares."}}
     [:maybe [:string {:max 64}]]]
    [:offer_id {:optional true
                :x-display
                {:label "The next step's row"
                 :help "The row's own id, the one in its address bar. The finding points at work that already exists; it never invents any."}}
     [:maybe [:string {:max 64}]]]
    [:offer_action {:optional true
                    :x-display
                    {:label "The next step"
                     :help "The action's own name on that kind — the one thing you are proposing somebody do. It has to be light enough to tap: a decision, never a form."}}
     [:maybe [:string {:max 64}]]]
    [:offer_href {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 200}]]]]
   ;; shape, and only shape: a malformed finding hears what is wrong
   ;; with it, and a well-formed one is published however many came
   ;; before it today — the feed's rank, not a wall here, decides
   ;; which a person reads (waymark-1uv.8).
   :create-guards [cites-what-it-claims offers-something-light]
   :scenarios [no-citation-no-publish
               no-offered-action-no-publish
               a-value-may-be-petitioned
               the-finder-does-not-decide
               a-dismissed-finding-does-not-come-back]})
