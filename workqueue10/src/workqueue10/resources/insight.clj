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
    A door that takes typing is not an offer: `task.prioritize` asks
    for a rank, which renders `recall`, and it is refused here however
    natural it reads in a sentence. Prepared input belongs in an
    outcome PIECE, which is where the house lets a composer type on
    somebody's behalf; a card offers a decision.
  - THE ADDRESS IS DERIVED, NEVER ASKED FOR (waymark-42m). An offer's
    href was declared hidden and required in the same breath: the
    guard refused a finding for omitting a field the composer's own
    form never showed it, and a sitting burned three refusals on it.
    `derive-the-offer-address` now writes `/api/<plural>/<id>` at
    birth from the kind and id the author already named, so the
    hidden field is hidden because nobody need supply it. An href the
    author DOES supply is still checked against that pair — naming
    one row and linking another is the one thing this field can still
    get wrong, and the refusal names the address the row actually
    lives at.
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
  - ONE LIVE FINDING PER OFFER, and it is not the cap coming back
    (waymark-1ag). `one-live-finding-per-offer` refuses a finding
    whose `{offer_kind, offer_id, offer_action}` a PUBLISHED finding
    already carries AND which was built on one of the same evidence
    rows, naming that finding's address and the shared row. A cap
    refuses the Nth row because it is the Nth; this refuses a row
    because the house is already asking exactly that question, off
    exactly that reading, and nobody has answered it —
    `outcome/not-a-twin`'s law one kind over. Answer the standing one
    and the question is open again: a dismissed prior blocks nothing,
    it only weighs on the rank. The evidence half of the key is what
    keeps the diagnosis duty dischargeable; the wall's own section
    below says why.
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
            [waymark10.guards :as g]
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
                                          (g/listed bad))}})
          (t/allow))))))

(defguardfn offers-something-light
  {:judges [:offer_kind :offer_id :offer_action :offer_href]
   :reads [:storage]
   :vars [:problem]
   :open "An insight offers one next step: a kind and an action this house declares, on a row it names, and the action light enough to tap. The address the card reaches it at is derived from that pair — supply one only if it says the same thing."
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
                     (g/listed (map name (keys (:actions rd)))) "."))

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

              ;; NO ADDRESS IS NOT A REFUSAL any more (waymark-42m):
              ;; the kind and the id ARE the address, and
              ;; `derive-the-offer-address` writes it at birth. The
              ;; wall that stood here refused a composer for omitting a
              ;; field the create form hides from it.
              (nil? href) (t/allow)

              (not= {:plural (:plural rd) :id oid} (row-address href))
              (deny (str "the address " (pr-str href) " is not where that"
                         " row lives — " kind " " oid " is at /api/"
                         (:plural rd) "/" oid "."))

              :else (t/allow))))))))

;; ── one live finding per offer (waymark-1ag) ────────────────────────
;;
;; DEDUPE IS A LAW, NOT A CAP. `insights-are-capped` refused the Nth
;; finding BECAUSE IT WAS THE Nth, and it left with waymark-1uv.8;
;; nothing here brings it back. This wall refuses a finding because
;; the house is ALREADY HOLDING the same question, unanswered — which
;; is `not-a-twin`'s sentence one kind over (waymark-8gc), and why
;; these were two beads rather than one: an outcome twins on the ROWS
;; IT READ, a finding twins on the NEXT STEP IT OFFERS.
;;
;; Two findings citing one task are two findings. Two findings both
;; offering `complete` on that same task, built on the same reading,
;; are one card written twice, and the rank cannot tell them apart —
;; it places equals by hash(seed ‖ card_id), so the insights line's
;; take of two would go to one question.
;;
;; THE KEY IS THE OFFER TRIPLE **AND** A SHARED EVIDENCE ROW, and the
;; second half was not in waymark-1ag's own sentence — it is what
;; building the wall found. The triple alone DEADLOCKS THE DIAGNOSIS
;; DUTY: `outcome/no-burial-without-a-diagnosis` makes a composer
;; publish an insight citing the declined prior and offering the
;; VALUE's `still_stands` before it may recompose, so two bundles on
;; one value, declined a fortnight apart, owe two diagnoses with the
;; identical offer triple — and the composer cannot clear the first
;; one itself, because the four-eyes wall means the finder does not
;; decide. A wall keyed on the triple alone would leave that composer
;; unable to discharge a duty the house imposes on it, which is not a
;; law, it is a trap. Two diagnoses about two different bundles cite
;; different rows and are admitted; a finding re-asking the SAME
;; question off the SAME reading is refused.
;;
;; This is also the shape the house had already written down for this
;; arm before the door existed: waymark-8gc's own description
;; ("identical evidence set + identical offer_kind/offer_id/
;; offer_action") and `scripts/sitting-run.sh verify`'s insight fault
;; line, which pairs shared evidence with the offer triple and needs
;; no edit now that the door agrees with it. The honest boundary is
;; `not-a-twin`'s: exact address overlap, nothing cleverer, and a
;; compiler that genuinely read somewhere else is asking about
;; something else.
;;
;; LIVE MEANS `published`. A taken finding was answered yes, a
;; dismissed one answered no; both are terminal and both leave the
;; feed, so neither stands in the way of asking again. The rank
;; already owns the answered ones — `feed/insight-record` counts
;; DISMISSED priors on the same offer and holds a fresh finding DOWN
;; rather than out (waymark-1uv.8) — and the two must not disagree:
;; the rank's business is what the house answered, this wall's is the
;; one question still open.

(def ^:private live-state
  "The one state a finding STANDS in. `published` is this kind's open
  state (the `:offered` sugar renamed — see `:decision` below), and
  `taken` / `dismissed` are both terminal."
  "published")

(def ^:private live-page
  "How deep the wall reads. `outcome/standing-page`'s number and its
  reasoning: a household's feed holds tens of live findings, and a
  bounded read that missed the far tail of a pathological store lets a
  duplicate THROUGH rather than refusing a finding over a row it could
  not see — the right way for a wall reading a window to be wrong."
  200)

(defn- offer-key
  "The question a finding asks, as the triple that identifies it —
  `[kind id action]`, each trimmed — or nil when any part is missing.
  Nil means `offers-something-light` has already refused this finding
  (shape first, world next), so this wall says nothing about it."
  [kind id action]
  (let [t #(some-> % str str/trim not-empty)
        k (t kind) i (t id) a (t action)]
    (when (and k i a) [k i a])))

(defn- read-rows
  "The set of row addresses a finding actually READ: its evidence,
  trimmed and blank-free. `outcome/read-rows` without the value
  subtraction, which has no meaning here — an insight serves no value,
  so everything in its `evidence` is something it went and looked at."
  [evidence]
  (into #{} (comp (map #(str/trim (str %))) (remove str/blank?)) evidence))

(defguardfn one-live-finding-per-offer
  {:judges [:offer_kind :offer_id :offer_action :evidence]
   :reads [:insight]
   :vars [:standing :offer :address :shared]
   :open "One live finding per next step, per reading. A finding nobody has answered is still asking its question, so a second finding built on one of the same rows and offering the same action on the same row asks it twice — and the rank, which places equals by a hash, would spend the line on one question. Answer the standing one, or read somewhere the house is not already being asked about."
   :explain "The house is already asking that: /api/insights/{standing} offers {offer} on {address}, reads {shared} the way this one does, and nobody has answered it. Read that finding — if it is wrong, dismiss it, and a fresh one on the same next step is admitted the moment it is answered. This is not a cap on what you may publish; it is one question at a time."}
  [_row inp ctx]
  (let [find' (:find ctx)]
    ;; the storage-free probe advertises optimistically, exactly as
    ;; `cites-what-it-claims` and `outcome/not-a-twin` do — the write
    ;; path always carries the consult
    (if (nil? find')
      (t/allow)
      (if-some [k (offer-key (:offer_kind inp) (:offer_id inp)
                             (:offer_action inp))]
        (let [mine (read-rows (:evidence inp))
              ;; a VECTOR out of the intersection, because `g/listed`
              ;; renders it and a sorted list is the sentence
              shared-with (fn [r]
                            (into [] (filter mine)
                                  (read-rows (get-in r [:data :evidence]))))
              hit (->> (find' :insight {:state live-state} {:limit live-page})
                       (into []
                             (comp (filter #(= k (offer-key
                                                  (get-in % [:data :offer_kind])
                                                  (get-in % [:data :offer_id])
                                                  (get-in % [:data :offer_action]))))
                                   (map #(assoc % ::shared (shared-with %)))
                                   (filter (comp seq ::shared))))
                       (sort-by #(str (:id %)))
                       first)]
          (if (nil? hit)
            (t/allow)
            (t/deny {:vars {:standing (str (:id hit))
                            :offer (nth k 2)
                            ;; the offered row's own address, which the
                            ;; standing finding carries because
                            ;; `derive-the-offer-address` wrote it at
                            ;; birth; the kind and the id are the
                            ;; honest fallback for a row written before
                            ;; that hook landed
                            :address (or (some-> (get-in hit [:data :offer_href])
                                                 str str/trim not-empty)
                                         (str (nth k 0) " " (nth k 1)))
                            :shared (g/listed (::shared hit))}})))
        ;; no offer at all — `offers-something-light` owns that refusal
        (t/allow)))))

;; ── the typing agrees with itself (waymark-2m2) ─────────────────────
;;
;; THE FOURTH WALL, AND IT IS NOT A WALL ON TYPING. Every one of the
;; four evidence fields is optional and this guard says nothing about a
;; finding that carries none of them, or one of them, or all four
;; coherently. It refuses exactly two sentences that CONTRADICT
;; THEMSELVES — a typed fact whose words cannot both be true — and it
;; refuses them because the likelihood ratio behind each word assumes
;; the word means what it says.
;;
;; 1. `unprompted_mention` with `solicited: true`. The type IS *nobody
;;    asked*; the flag IS *the house asked*. One of the two words is
;;    wrong and the clerk knows which, and the refusal says so rather
;;    than silently discounting an atom priced at 8 for having been
;;    prompted — which is how a table gets quietly wrong.
;;
;; 2. `costly_action` with `cost: "none"`. An action that cost nothing
;;    is not a costly action; it is a mention, or a detail, or nothing.
;;    This is the door docs/spec-hypotheses.md names when it prices the
;;    type at 20 for `high` and 5 for `low` — there is no third number,
;;    because there is no third case.
;;
;; A costly action with NO `cost` at all is admitted and reads as the
;; LOW number, which is the conservative direction and the one worth
;; being wrong in: a fact somebody spent something on counts for
;; something even where the clerk could not say how much.
;;
;; Shape-only, so it needs no storage and both scenarios below are
;; judged in the same breath as `make check-queue`.

(defguardfn the-typing-agrees-with-itself
  {:judges [:evidence_type :solicited :cost]
   :vars [:problem]
   :open "Two of the nine words carry a second fact inside them: an unprompted mention is one NOBODY ASKED for, and a costly action is one that COST something. Say either of those and then contradict it, and the finding is refused — not because typing a fact is hard, but because the number behind each word assumes the word is true. All four typing fields stay optional; leaving them blank is always lawful."
   :explain "Those words disagree with each other: {problem}"}
  [_row inp _ctx]
  (let [ty (some-> (:evidence_type inp) str str/trim not-empty)
        cost (some-> (:cost inp) str str/trim not-empty)
        solicited (:solicited inp)
        deny (fn [problem] (t/deny {:vars {:problem problem}}))]
    (cond
      (and (= "unprompted_mention" ty) (true? solicited))
      (deny (str "an unprompted mention is one NOBODY ASKED for, and"
                 " solicited says the house asked. If somebody put it to"
                 " them, this is not an unprompted mention — try"
                 " solicited_praise, or question_asked, or minimal_response."
                 " If they truly volunteered it, solicited is false."))

      (and (= "costly_action" ty) (= "none" cost))
      (deny (str "a costly action that cost nothing is not a costly action —"
                 " the whole of why this one counts most is that talk is free"
                 " and this was not. Say low or high if it cost them"
                 " something, and otherwise this is an unprompted mention, a"
                 " specific detail, or nothing at all."))

      :else (t/allow))))

;; ── the address, derived at birth ───────────────────────────────────

(defn- derive-the-offer-address
  "The offer's href, written from the pair the author already named
  (waymark-42m). `/api/<plural>/<id>` is the one address shape this
  house speaks — `row-address` above reads it, `feed/screen-of`
  prefixes it, and the registry is what turns a kind token into its
  plural — so asking a composer to spell it a second time was asking
  it to repeat the engine back to itself, through a field the create
  form declares hidden.

  Fills a BLANK only: an href the author supplied stands, and
  `offers-something-light` has already refused it if it points
  somewhere else. Silent when the kind is one this house does not
  serve or the id is missing — the guard refused that finding before
  this hook ever ran, and a hook that invented an address for a
  refused row would be writing fiction.

  It runs beside the decision sugar's own birth stamp (`:authored_by`
  from the principal), which lands first — resource.clj's
  desugar-decision composes the two in that order."
  [row ctx]
  (let [{:keys [offer_kind offer_id offer_href]} (:data row)
        rdef-of (:rdef-of ctx)
        kind (some-> offer_kind str str/trim not-empty)
        oid (some-> offer_id str str/trim not-empty)
        rd (when (and rdef-of kind) (rdef-of kind))]
    (if (and rd oid (str/blank? (str offer_href)))
      (assoc-in row [:data :offer_href] (str "/api/" (:plural rd) "/" oid))
      row)))

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
;;
;; AND NONE NAMES `one-live-finding-per-offer`, for the structural
;; reason `outcome/not-a-twin` has none either (waymark-8gc, and the
;; same paragraph one file over): the wall's whole question is what
;; ANOTHER row already offers, and a scenario holds one literal
;; `:input` over an empty store — so every scenario reaching this door
;; would be an allow, and a green one would prove nothing. The claims
;; (a second live finding on the same offer refused by name, the first
;; still published; a DISMISSED prior admitting a fresh one; a
;; different `offer_action` on the same row admitted) are proved by
;; `workqueue10.insight-rank-test` over the real ring handler, where a
;; first finding can actually stand, and by `:feed/insights` in the
;; conformance pack from the wire.

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

(defscenario an-offer-needs-no-address
  "The kind and the id ARE the address, so a composer that names them
   has said everything. This is waymark-42m's own scenario: the
   create form declares `offer_href` hidden, and the guard used to
   refuse the finding for leaving it blank — a wall a composer could
   not see and could only learn by hitting. Now the pair is enough
   and the engine writes the href at birth."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-28T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "The porch project has not moved since June, and the reminder is still standing"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "take_it_back"}
   :expect  {:allowed true}})

(defscenario an-offer-points-at-its-own-row
  "An address the author DOES spell must be the row's own. Naming one
   row and linking another is the one thing this field can still get
   wrong — the card would send a reader somewhere the finding never
   claimed anything about — and the refusal names where the row
   actually lives."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-28T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "The porch project has not moved since June"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "take_it_back"
             :offer_href "/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"}
   :expect  {:refused :offers-something-light}})

(defscenario a-form-is-not-a-tap
  "`task.prioritize` reads like the obvious next step and is not one:
   it takes a rank, the rank renders `recall`, and a card that
   collects a number is a form. The refusal is the ≤-selection rule
   doing its one door's worth of work — and it is why prepared input
   lives in an outcome PIECE, where a composer may type on the
   household's behalf, rather than in a finding's offer."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-28T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "The dentist call has sat unranked at the tail of the queue for three weeks"
             :evidence ["/api/tasks/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "task"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "prioritize"}
   :expect  {:refused :offers-something-light}})

(defscenario a-typed-fact-may-be-left-untyped
  "The load-bearing scenario of waymark-2m2, and it proves a wall that
   is NOT there. All four typing fields are optional: a clerk that
   cannot say which of the nine words a fact is leaves them blank, the
   finding lands exactly as it always did, and the reading reads it as
   a likelihood ratio of 1 — which is silence. Every finding written
   before the hypotheses epic is in this state, and none of them owes
   a backfill.

   ITS OWN ROW, and the reason is worth keeping: this is the only one
   of the three that is an ALLOW, so it is the only one that reaches
   the third wall — and `one-live-finding-per-offer` refused it in the
   conformance pack, off the finding `an-offer-needs-no-address`
   leaves standing on the shared tickler. Shape-first ordering hides
   that from the two refusals above (the typing wall answers them
   before the world is read); an allow has to walk the whole door."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "The gutters have gone another fortnight without a call"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9C1"]
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9C1"
             :offer_action "take_it_back"}
   :expect  {:allowed true}})

(defscenario nobody-asked-for-an-unprompted-mention
  "A fact cannot be both volunteered and asked for. The type says
   nobody asked; the flag says the house did; one of the two words is
   wrong, and the clerk is the only one who knows which — so the
   refusal names both and lets it choose, rather than quietly
   discounting an atom priced at 8 for having been prompted."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "Iris talked about the darkroom again"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "take_it_back"
             :evidence_type "unprompted_mention"
             :solicited true
             :episode "thread/7fda11c6 2026-08-24"}
   :expect  {:refused :the-typing-agrees-with-itself
             :because "NOBODY ASKED"}})

(defscenario a-costly-action-cost-something
  "The whole of why a costly action counts most is that talk is free
   and this was not — so an action that cost NOTHING is not one. Said
   at the door rather than priced at some third number, because there
   is no third case: it was a mention, or a detail, or nothing."
  {:kind    :insight
   :attempt :create
   :at      "2026-08-30T09:00:00Z"
   :as      {:id "compiler" :type :agent}
   :input   {:finding "Iris put the darkroom weekend on the calendar"
             :evidence ["/api/ticklers/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
             :offer_kind "tickler"
             :offer_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :offer_action "take_it_back"
             :evidence_type "costly_action"
             :cost "none"
             :episode "thread/7fda11c6 2026-08-24"}
   :expect  {:refused :the-typing-agrees-with-itself
             :because "cost nothing is not a costly action"}})

;; ── :insight — the finding, with a next step attached ───────────────

(defresource insight
  {:kind :insight
   :plural "insights"
   :label-template "{data.finding}"
   :summary "{data.finding} · found by {data.authored_by} · {state}"
   ;; the row's own line, not the kind label (waymark-iqa.22): the
   ;; finding IS the heading — "Insight" above the sentence was a form
   ;; label, and the task card ("Call the dentist") had it right
   :display {:title "{data.finding}"}
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
      ;; deeper. Since waymark-hcr they are the FINDING's four rather
      ;; than the household's — too thin, not backed, already known,
      ;; not true — because a finding is a claim and *wrong time* is
      ;; not a thing anybody means about a sentence; which four a
      ;; subject gets is `verdict-reason/reason-sets`, and nothing here
      ;; names them. `:display` is spelled whole because a verdict's own
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
                     :help "The action's own name on that kind — the one thing you are proposing somebody do. It has to be light enough to tap: a decision, never a form. A door that asks for nothing (complete, take_it_back, still_stands) is offerable; one that takes input is not, so prioritize — which wants a rank — belongs in an outcome piece instead."}}
     [:maybe [:string {:max 64}]]]
    ;; HIDDEN BECAUSE IT IS DERIVED, not because it is secret
    ;; (waymark-42m): `derive-the-offer-address` writes it at birth
    ;; from the two fields above. It stays writable so a caller that
    ;; already holds the address may say it — and be held to it.
    [:offer_href {:optional true :x-display {:hidden true}}
     [:maybe [:string {:max 200}]]]
    ;; ── WHAT KIND OF EVIDENCE THIS IS (waymark-2m2) ─────────────────
    ;;
    ;; Four OPTIONAL fields, and optional is load-bearing: an untyped
    ;; finding is exactly as lawful as it was the day before this bead
    ;; and weighs exactly what it always weighed — a likelihood ratio
    ;; of 1, which is silence. Nothing here is a wall, nothing here is
    ;; required by any guard, and a clerk who does not know which of
    ;; the nine words a fact is leaves all four blank rather than
    ;; guessing. That is the honest failure and it costs nothing.
    ;;
    ;; The nine words are a TAXONOMY OF HOW THE FACT ARRIVED, never a
    ;; judgment of what it means. A clerk filling these has read one
    ;; message and is answering *did anybody ask them?*, *what did it
    ;; cost them?*, *when was this?* — questions a person could answer
    ;; from the same message without holding any belief at all. The
    ;; belief is the reading's, and the reading computes it from these
    ;; on the fly (`scripts/sitting-run.sh`'s WHAT MOVED THIS WEEK);
    ;; nothing is stored here but what was observed.
    ;;
    ;; The numbers each word carries are DATA on the feed_recipe row —
    ;; `feed/default-evidence-lr`, printed on every feed document
    ;; beside `crown_rank` with a sentence quoting them back, tunable
    ;; through `recipe_proposal`. Law 5's posture one surface over: a
    ;; weight a household cannot read is the hidden model, whatever it
    ;; is weighing.
    ;;
    ;; ONE WALL STANDS OVER THE FOUR AND IT IS NOT ABOUT TYPING:
    ;; `the-typing-agrees-with-itself` refuses the two sentences that
    ;; contradict themselves — an unprompted mention the house asked
    ;; for, a costly action that cost nothing — because the number
    ;; behind each of those words assumes the word is true. Everything
    ;; else about these fields is optional, always.
    [:evidence_type
     {:optional true :filter #{:eq}
      :x-display
      {:label "How the fact arrived"
       :help "Which of the nine ways this fact reached the house — how it arrived, never what it means. Leave it blank if none of them fits; an untyped fact is a lawful fact and the reading simply reads it as saying nothing either way."
       :choices
       {"unprompted_mention" "They brought it up themselves — nobody asked, and they said it anyway"
        "solicited_praise" "They said something nice about it because you asked — the politest thing a person can say, and the least it can mean"
        "question_asked" "They asked a question about it — wanting to know more is cheap to say and hard to fake"
        "specific_detail" "They knew a detail — a name, a date, how it works — that only somebody who has actually been near it would know"
        "costly_action" "They spent something real on it — money, a day, a drive, a thing they gave up to do it. Say what it cost below: high or low, never none"
        "declined_invite" "They were asked and said no — a real answer, and it points the other way"
        "statement_against_interest" "They said something that cost them to say — an admission, a thing that made them look worse"
        "complaint_while_continuing" "They complained about it and kept doing it anyway — the grumbling is not the signal, the staying is"
        "minimal_response" "They answered, barely — a word, a thumb, and nothing after it"}}}
     [:maybe [:enum "unprompted_mention" "solicited_praise" "question_asked"
              "specific_detail" "costly_action" "declined_invite"
              "statement_against_interest" "complaint_while_continuing"
              "minimal_response"]]]
    [:solicited
     {:optional true :filter #{:eq}
      :x-display
      {:label "Did somebody ask them?"
       :help "True if this came out because the house asked — a question, a nudge, a poll. False if they volunteered it. It is a DISCOUNT rather than a tenth word: an answer to a question you put in somebody's mouth counts for a fraction of the same words unprompted, and the fraction is on the recipe row where anybody can read it. True on an unprompted mention is refused, because those two words cannot both be right."}}
     [:maybe :boolean]]
    [:cost
     {:optional true :filter #{:eq}
      :x-display
      {:label "What it cost them"
       :help "It PRICES a costly action — low and high are the two numbers that word carries, and none is refused there, because an action that cost nothing is not a costly action. On any of the other eight words it is simply recorded and reads at 1; the house keeps it so it can find out later whether the other eight want a cost-graded scale too."
       :choices {"none" "Nothing — a word, a tap, a moment"
                 "low" "A little — some minutes, a small effort, mild awkwardness"
                 "high" "Something real — money, a day, giving something else up, saying a thing that made them look worse"}}}
     [:maybe [:enum "none" "low" "high"]]]
    [:episode
     {:optional true :filter #{:eq}
      :x-display
      {:label "Which occasion"
       :help "Where and when this happened, as a source and a day — \"thread/7fda11c6 2026-08-24\". The same evening counts once, however excited it was: five messages in one conversation are one occasion, and the reading folds them together rather than counting the excitement five times. Two different days are two occasions even if the words were identical."}}
     [:maybe [:string {:max 120}]]]]
   :on-create derive-the-offer-address
   ;; SHAPE FIRST, WORLD NEXT — outcome's ordering and its reason. A
   ;; malformed finding hears what is wrong with it before it hears
   ;; anything about the house it is landing in, and only the third
   ;; wall reads another row at all. A well-formed finding on a next
   ;; step nobody is already asking about is published however many
   ;; came before it today: the feed's rank, not a wall here, decides
   ;; which a person reads (waymark-1uv.8), and the third wall counts
   ;; questions rather than rows (waymark-1ag).
   :create-guards [cites-what-it-claims offers-something-light
                   ;; the typing wall is SHAPE, so it stands with the
                   ;; other two and above the one that reads the world
                   the-typing-agrees-with-itself
                   one-live-finding-per-offer]
   :scenarios [no-citation-no-publish
               no-offered-action-no-publish
               a-value-may-be-petitioned
               an-offer-needs-no-address
               an-offer-points-at-its-own-row
               a-form-is-not-a-tap
               a-typed-fact-may-be-left-untyped
               nobody-asked-for-an-unprompted-mention
               a-costly-action-cost-something
               the-finder-does-not-decide
               a-dismissed-finding-does-not-come-back]})
