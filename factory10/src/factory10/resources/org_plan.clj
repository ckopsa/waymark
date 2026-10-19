(ns factory10.resources.org-plan
  "The org_plan: the row a person and the onboarding seat work on
  together to stand an org up (ticket cd22be20, M2a of epic cafa6106).

  THE BRIEF IS THE PERSON'S, THE PLAN IS THE SEAT'S. A person writes
  one paragraph, the `brief`, and names the org. The onboarding seat
  writes the org directory (`orgs/<name>`), asks the questions the
  engine's own audit raised, and opens the pull request. The person
  answers, watches the film, and takes the one tap that makes it real.

  THE MACHINE IS THE WALK. drafting → proposed (the pull request is
  up and no error finding is open) → simulated (a film of the org's
  first week is attached) → going_live (the owner allowed the merge of
  one head) → live (merged, and its mayor sits). Any of them may be
  retired, by the owner.

  `findings` IS WRITTEN BY ONE DOOR, `record_findings` (ticket
  5d62058e, M2b). After each push to the org's branch the audit's run
  replaces the plan's findings with its own, keyed to the head SHA,
  and only the engine's hand takes that door; no create form takes
  them. A proposed plan takes the same run through its twin,
  `record_findings_proposed` (ticket c4893567), so the plan a person
  approves is the plan as it stands. A question ties to a finding by its key, and a finding that is
  gone from the next run closes: its question is marked resolved.

  `propose` IS REFUSED WHILE AN ERROR FINDING IS OPEN. A plan the
  audit says is broken does not go up as a pull request: the refusal
  names the finding, and the way out is to answer the question it
  raised and let the audit run again.

  A RED FILM REOPENS THE PLAN (ticket d52abbf2, for M3d). A film's red
  rules land on a `filming` plan as findings through
  `record_film_findings`, the engine's hand on the judge's answer, and
  move it back to `drafting`: they join the audit's findings, replacing
  any with the same key, so the walk reopens on them and propose stays
  shut until a later run no longer finds them.

  THE DIRECTOR HANDS IN THE FILM; THE ENGINE JUDGES IT (ticket
  e552de65). The director seat films a proposed plan's head on a clone
  and hands in the film and its take report through `hand_in_film`,
  which keeps the take, moves the plan to `filming` and decides
  nothing. The forge's film pass,
  the engine's hand, judges the take against the film rules once and
  lands it through `attach_film` or `record_film_findings`; both are
  the engine's doors alone.

  THE WALK IS THE FINDINGS, IN A FIXED ORDER (ticket f64c9ce1, M2c).
  The onboarding walk is a computed `next_step` on the plan, not a
  `walkthrough` row: a walkthrough's steps are invitations a person or
  seat answers, and a finding's step closes only when the audit's next
  run no longer finds it, never because a hand says so. So
  `record_findings` writes `next_step`, the first open finding in
  `walk-order` (ties broken by key), and nil once no error finding is
  open: the walk is done, and propose opens.

  THE LAW PAGE RIDES THE PROPOSAL (ticket 36c70e59). The org's
  directory lives on the pull request's branch, not on the factory's
  classpath, so the factory cannot render it. The onboarding seat runs
  `factory10.law-text` on its bench and hands the page to `propose`,
  which writes it onto the plan as `law_page`: the person reads the
  law in the plan's own document. The audit's run renders it too, in
  the org-findings artifact beside the findings (ticket 4ab34d18), and
  `record_findings` writes it for that head, so the page always matches
  the latest head; a propose without one keeps the recorded page.

  `answer` AND `go_live` ARE THE OWNER'S. A question is asked of the
  person who owns the plan, so only that person answers it, or an
  agent acting for them whose answer is held for their tap. `go_live`
  is the one tap: an agent's go_live is HELD, and the owner's Allow
  runs it.

  THE ONE TAP IS THE MERGE'S YES (ticket c8e131fb, M2e). The flip
  happens on the branch before the tap: the onboarding seat sets
  `:live true` in the org's org.edn, and the audit's run reports it as
  `live_flag`, with the `mayor` the seed seats, for that head. go_live
  is refused `not-flipped` until the latest head says live; its held
  call names the org, the mayor and the budget; and the Allow writes
  `approved_head` and moves the plan to going_live. The house's merge
  pass takes that yes as the person's for that head (factory10.bench),
  and a moved head sends the plan back to simulated through
  `head_moved`. `mark_live` is the engine's step to live: the pass in
  `factory10.org-live` takes it once the house serves the org's domain
  and its mayor's seat is active there, and writes `mayor_seat`.

  A PLAN CAN BE A HIRE PLAN (ticket 78f11fa7, M4a). `purpose` is
  `found` (the plan that stands an org up; a plan born before the field
  reads as found) or `hire`: a plan the engine opens on a live org for
  one `gap` its own audit found, kind rows waiting in a state that no
  seat holds or that the seats on it do not keep up with. `open-hire!`
  is that birth, the engine's hand alone: it copies the live plan's
  owner and directory, writes the brief from the gap, and opens one
  hire plan per gap key. It is a function and not a declared door
  because a kind has one create verb; the ordinary create keeps
  `a-person-files-the-plan`, and the engine's birth passes it by.

  :nav :secondary, for change's reason: standing an org up is the
  day job's work, not the family's."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.holds :as holds]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── whose plan it is ────────────────────────────────────────────────

(defn- the-person
  "The id of the person this hand is, or acts for: a person's own id,
  an agent's `:acts-for`. The engine's own hand and an agent acting
  for nobody answer nil."
  [ctx]
  (let [p (:principal ctx)]
    (some-> (case (:type p)
              :agent (:acts-for p)
              :system nil
              (:id p))
            str not-empty)))

(defn- stamp-the-owner
  "The birth's stamps: the owner is the person who filed the plan, the
  org directory is orgs/<name> unless the filer named one, and a plan
  born with no purpose founds its org."
  [row ctx]
  (cond-> row
    (some? (the-person ctx))
    (assoc-in [:data :owner] (the-person ctx))

    (str/blank? (str (get-in row [:data :org_dir])))
    (assoc-in [:data :org_dir] (str "orgs/" (get-in row [:data :name])))

    (str/blank? (str (get-in row [:data :purpose])))
    (assoc-in [:data :purpose] "found")))

;; ── the hire plan (M4a) ──────────────────────────────────────────────

(defn purpose-of
  "The plan's purpose, `found` or `hire`. A plan born before the field
  was declared has none, and reads as found."
  [plan]
  (if (= "hire" (some-> (get-in plan [:data :purpose]) name)) "hire" "found"))

(def ^:private standing-hire-states
  "The states a hire plan blocks another for its gap in. A retired or
  live one does not."
  #{"drafting" "proposed" "filming" "simulated" "going_live"})

(defn hire-brief
  "The hire plan's brief: one sentence written from its gap."
  [{:keys [kind state reason row_ids oldest_since]}]
  (str kind " rows wait in " state ": " (count row_ids)
       " past their expected age, oldest since " (or oldest_since "unknown") "; "
       (if (= "pressure" (some-> reason name))
         "more than the seats on it keep up with"
         "no seat holds the door out")))

(defn- state-name [row] (some-> (:state row) name))

(defn- plans-of
  "Every org_plan of the org `org` in a state that is not retired,
  decoded."
  [eng org]
  (let [st (:storage eng)
        rd (get (inv/resources eng) :org_plan)]
    (into []
          (comp (map #(inv/decode-row rd %))
                (filter #(= org (str (get-in % [:data :name])))))
          (store/with-tx st
            (fn [tx]
              (mapcat #(store/query-rows st tx :org_plan {:state %} {:limit 1000})
                      ["live" "drafting" "proposed" "filming" "simulated" "going_live"]))))))

(defn open-hire!
  "The engine opens a hire plan for the org `name` on one `gap`. →
  inv/create!'s answer, or {:refused <reason> :why <sentence>}:
  `engine_only` when the hand is not the engine's, `no_live_org` when
  the org has no live found plan, and `hire_open` when a hire plan for
  the same gap key stands in drafting, proposed, filming or simulated. The plan
  is born in drafting with the live plan's owner and directory, and
  the brief written from the gap."
  [eng {org :name gap :gap} {:keys [principal] :as opts}]
  (if-not (= :system (:type principal))
    {:refused :engine_only
     :why "Only the engine's hand opens a hire plan: it opens one for each gap its own audit finds."}
    (let [plans (plans-of eng org)
          live (first (filter #(and (= "live" (state-name %))
                                    (= "found" (purpose-of %)))
                              plans))
          open (first (filter #(and (= "hire" (purpose-of %))
                                    (contains? standing-hire-states (state-name %))
                                    (= (str (:key gap))
                                       (str (get-in % [:data :gap :key]))))
                              plans))]
      (cond
        (nil? live)
        {:refused :no_live_org
         :why (str "No live plan founds " org ": a hire plan is opened on an org that is live.")}

        (some? open)
        {:refused :hire_open
         :hire (str (:id open))
         :why (str "A hire plan for " (:key gap) " stands already, in " (state-name open) ".")}

        :else
        (inv/create! eng :org_plan
                     {:name org
                      :purpose "hire"
                      :org_dir (get-in live [:data :org_dir])
                      :owner (str (get-in live [:data :owner]))
                      :gap gap
                      :brief (hire-brief gap)}
                     (assoc opts :mint? true))))))

;; ── the findings walk (M2c) ─────────────────────────────────────────

(def walk-order
  "The rank of each audit class on the onboarding walk: the kinds
  (do they check), then states and doors, who holds each door (cracks),
  rules and held doors (sentences), the seats (scope, budget), a film's
  red rules (film), and lint last. A film is judged on a plan the audit
  already passed, so its rule comes after the audit's classes, but no
  lint nit outranks it (ticket 23f82fff). The others are
  factorykit.conform's classes; a class not named here is lint."
  {"load" 0 "resource" 0 "registry" 0
   "boot" 1
   "crack" 2
   "sentence" 3
   "seed" 4 "scope" 4 "budget" 4
   "film" 5})

(def ^:private lint-rank 6)

(defn walk-steps
  "The plan's open findings as the walk's steps: by class in
  walk-order, ties broken by key."
  [findings]
  (vec (sort-by (juxt #(get walk-order (str (:class %)) lint-rank)
                      #(str (:key %)))
                findings)))

(defn next-step
  "The walk's next step: its first open error finding, whatever its
  class, so an error comes before any warning; nil when no error
  finding is open, and the walk is done."
  [findings]
  (first (walk-steps (filter #(= "error" (str (:severity %))) findings))))

(defn- short-sha [sha] (let [s (str sha)] (subs s 0 (min 7 (count s)))))

;; ── the doors' writes ───────────────────────────────────────────────

(defhandler ask-the-owner [row inp _ctx]
  ;; A question is appended, never rewritten: the answer is the
  ;; owner's door. Asking again under a key already asked is nothing,
  ;; so a retried ask is safe.
  (if (some #(= (:key inp) (:key %)) (get-in row [:data :questions]))
    row
    (update-in row [:data :questions]
               (fnil conj [])
               (select-keys inp [:key :finding_key :question]))))

(defhandler answer-the-question [row inp ctx]
  (update-in row [:data :questions]
             (fn [qs]
               (mapv #(if (= (:key inp) (:key %))
                        (assoc % :answer (:answer inp) :answered_at (:now ctx))
                        %)
                     qs))))

(defhandler put-the-pull-request-up [row inp _ctx]
  (cond-> row
    (some? (:change inp)) (assoc-in [:data :change] (:change inp))
    (some? (:law_page inp)) (assoc-in [:data :law_page] (:law_page inp))))

(defhandler link-the-change [row inp _ctx]
  ;; The sit's own hand (ticket db8b35bb): the change it minted for this
  ;; plan is the plan's, so the findings pass and the go_live hold find it.
  (assoc-in row [:data :change] (:change inp)))

(defhandler hand-in-the-film [row inp _ctx]
  ;; The director's take, kept for the engine's film pass to judge. It
  ;; decides nothing; a new take is judged anew.
  (-> row
      (assoc-in [:data :take_film_url] (:film_url inp))
      (assoc-in [:data :take_report] (:take_report inp))
      (assoc-in [:data :take_sha] (:head_sha inp))
      (assoc-in [:data :take_landed_at] nil)))

(defhandler attach-the-film [row inp ctx]
  ;; The take is landed: the film pass does not judge it again.
  (-> row
      (assoc-in [:data :film_url] (:film_url inp))
      (assoc-in [:data :take_landed_at] (:now ctx))))

(defhandler record-the-findings [row inp ctx]
  ;; The run's findings replace the plan's. The audit's keys are the
  ;; same on every run, so an unchanged finding keeps its key; a
  ;; question whose finding is gone is resolved, once. The walk's next
  ;; step moves only here, so a step closes when its finding clears.
  ;; The run also says whether org.edn is live at its head and who the
  ;; mayor is (M2e): a run that says nothing is not flipped.
  (let [found (vec (:findings inp))
        open (into #{} (map :key) found)
        closed? #(and (some? (:finding_key %))
                      (not (contains? open (:finding_key %)))
                      (nil? (:resolved_at %)))]
    (cond-> (-> row
                (assoc-in [:data :findings] found)
                (assoc-in [:data :findings_sha] (:head_sha inp))
                (assoc-in [:data :live_flag] (true? (:live_flag inp)))
                (assoc-in [:data :mayor] (:mayor inp))
                (assoc-in [:data :next_step] (next-step found)))
      (some? (:law_page inp))
      (assoc-in [:data :law_page] (:law_page inp))
      (seq (get-in row [:data :questions]))
      (update-in [:data :questions]
                 (fn [qs]
                   (mapv #(if (closed? %) (assoc % :resolved_at (:now ctx)) %)
                         qs))))))

(defhandler record-the-film-findings [row inp ctx]
  ;; A red film's rules join the audit's findings, a key the film shares
  ;; with one already on the plan replacing it, and the walk's next step
  ;; moves to them. The next audit run replaces them all, as ever.
  (let [film (vec (:findings inp))
        theirs (into #{} (map :key) film)
        found (into (vec (remove #(contains? theirs (:key %))
                                 (get-in row [:data :findings])))
                    film)]
    (-> row
        (assoc-in [:data :findings] found)
        (assoc-in [:data :findings_sha] (:head_sha inp))
        (assoc-in [:data :next_step] (next-step found))
        (assoc-in [:data :take_landed_at] (:now ctx)))))

(defhandler retire-the-plan [row inp _ctx]
  ;; A retired plan says why, because nothing departs `retired`.
  (assoc-in row [:data :retired_why] (:why inp)))

(defhandler approve-the-head [row _inp _ctx]
  ;; The owner's yes is for the head the audit last read: the house's
  ;; merge pass merges that head, or one the rig says carries from it.
  (assoc-in row [:data :approved_head] (get-in row [:data :findings_sha])))

(defhandler seat-the-mayor [row inp _ctx]
  ;; The org is live because its mayor sits: the plan names that seat.
  (cond-> row
    (some? (:mayor_seat inp)) (assoc-in [:data :mayor_seat] (:mayor_seat inp))))

(defhandler the-head-moved [row inp _ctx]
  ;; The yes is retired, and the moved head is a finding the next run
  ;; at that head replaces.
  (let [moved {:key "head_moved" :severity "error" :class "merge"
               :subject "pull request"
               :sentence (str "The pull request's head moved to " (short-sha (:head_sha inp))
                              " after the owner allowed "
                              (short-sha (get-in row [:data :approved_head]))
                              ", so it was not merged.")
               :remedy "Let the audit read the new head, then ask go_live again: the owner's yes names the head it merges."}
        found (conj (vec (remove #(= "head_moved" (str (:key %)))
                                 (get-in row [:data :findings])))
                    moved)]
    (-> row
        (assoc-in [:data :approved_head] nil)
        (assoc-in [:data :findings] found)
        (assoc-in [:data :next_step] (next-step found)))))

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn a-person-files-the-plan
  {:reads [:principal]
   :explain "An org_plan is a person's: it is filed by a person, or by an agent acting for one, and that person owns it. A hand that is nobody's has no one to ask the questions of."
   :open "File it as a person, or as an agent acting for one."}
  [_row _inp ctx]
  (if (some? (the-person ctx)) (t/allow) (t/deny)))

(defguardfn only-the-owner-decides
  {:reads [:principal]
   :explain "This is the owner's decision: only the person who owns this plan, or an agent acting for them, may take this door."
   :open "Ask the plan's owner to take it."}
  [row _inp ctx]
  (cond
    (= :system (:type (:principal ctx))) (t/allow)
    (and (some? (the-person ctx))
         (= (the-person ctx) (str (get-in row [:data :owner])))) (t/allow)
    :else (t/deny)))

(defguardfn only-a-person-answers
  {:reads [:principal :within]
   :hold true
   :explain "An answer is the owner's word, so an agent's answer is held for the owner's tap: the call is recorded as a held_call, and the owner's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the owner's tap."}
  [row _inp ctx]
  ;; ticket's `only-a-person-reopens`, one kind over.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :answer (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn not-flipped
  {:reads []
   :explain "The org's branch does not say it is live: org.edn's :live is not true at the head the audit last read, so there is nothing to merge yet."
   :open "Set :live true in the org's org.edn on the plan's branch, push, and let the audit run again."}
  [row _inp _ctx]
  (if (true? (get-in row [:data :live_flag])) (t/allow) (t/deny)))

(defn- go-live-vars
  "What the owner reads in go_live's held call: the org, its mayor and
  the mayor's weekly budget, and the head the Allow merges."
  [row]
  (let [d (:data row)]
    {:org (str (:name d))
     :mayor (or (some-> (get-in d [:mayor :name]) str not-empty) "unnamed")
     :budget (or (some-> (get-in d [:mayor :budget_usd_per_week]) str not-empty) "?")
     :head (short-sha (:findings_sha d))}))

(defguardfn only-a-person-goes-live
  {:reads [:principal :within]
   :hold true
   :vars [:org :mayor :budget :head]
   :explain "Make {org} live? Its mayor {mayor} will sit at ${budget}/week. Allow merges the org's pull request at {head}."
   :open "No door clears this one. The call waits as a held_call for the owner's tap, and the owner's Allow runs it exactly as written."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :go_live (:id row)) (t/allow)
    :else (t/deny {:vars (go-live-vars row)})))

(defguardfn the-engine-records-the-findings
  {:reads [:principal]
   :hide true
   :explain "The audit's run writes the findings. A person and a model read them."}
  ;; repo_policy's `the-engine-notes-the-source`, one kind over: the
  ;; forge pass is the only hand that walks this door, and a hidden
  ;; door answers 404 and says nothing.
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(defguardfn the-question-was-asked
  {:reads []
   :explain "There is no question on this plan with that key, so there is nothing to answer."
   :open "Answer a question the plan carries: its key is in the plan's questions."}
  [row inp _ctx]
  (if (some #(= (:key inp) (:key %)) (get-in row [:data :questions]))
    (t/allow)
    (t/deny)))

(defguardfn no-error-finding-is-open
  {:reads []
   :vars [:finding]
   :explain "The audit found an error in this plan ({finding}), and a plan with an open error does not go up as a pull request."
   :open "Answer the question the finding raised, and let the audit run again: propose opens once no error finding is open."}
  [row _inp _ctx]
  (if-some [f (first (filter #(= "error" (str (:severity %)))
                             (get-in row [:data :findings])))]
    (t/deny {:vars {:finding (str (:key f) ": " (:sentence f))}})
    (t/allow)))

(defguardfn the-plan-was-audited-at-its-head
  {:reads []
   :vars [:head]
   :explain "The audit has not recorded its findings at the head of this plan's pull request ({head}), so what would go up is not what was audited."
   :open "Push, and wait for the audit's findings on this head before proposing."}
  ;; Ticket db8b35bb. The change is the one propose names, or the plan's
  ;; own, read through the ctx hook. A ctx with no hook — the scenarios
  ;; and the render probe — allows, bench/policy-of's posture: the door
  ;; judges again with a real hook behind it. That is the check tier's
  ;; answer too, so the declared reads stay empty and org_plan's
  ;; scenarios stay check-tier, as ticket.clj's
  ;; `the-receiving-mayor-answers-an-ask` does.
  [row inp ctx]
  (if-some [read' (:read ctx)]
    (let [named (some-> (or (:change inp) (get-in row [:data :change])) str not-empty)
          head (some-> named (->> (read' :change)) (get-in [:data :head_sha]) str not-empty)]
      (if (and head (= head (str (get-in row [:data :findings_sha]))))
        (t/allow)
        (t/deny {:vars {:head (if head (short-sha head) "none yet")}})))
    (t/allow)))

(def ^:private gone-change-states
  "The change states whose pull request is out of play."
  #{"closed" "merged"})

(defguardfn the-sit-links-its-change
  {:reads [:principal :change]
   :hide true
   :explain "The sit names the change it minted for this plan, and only when the plan names none or names one that is closed."}
  ;; The engine's hand alone (ticket db8b35bb), as the findings are: a
  ;; plan whose change is still in play keeps it.
  [row inp ctx]
  (let [named (some-> (get-in row [:data :change]) str not-empty)]
    (cond
      (not= :system (:type (:principal ctx))) (t/deny)
      (nil? named) (t/allow)
      (= named (str (:change inp))) (t/deny)
      :else (if-some [read' (:read ctx)]
              (if (contains? gone-change-states
                             (some-> (read' :change named) :state name))
                (t/allow)
                (t/deny))
              (t/allow)))))

;; ── the law, written down as scenarios ──────────────────────────────

(def ^:private a-plan
  {:name "greenleaf"
   :brief "A six-person landscaping business: quotes, crew scheduling, invoicing and late payers."
   :org_dir "orgs/greenleaf"
   :owner "colton"
   :questions [{:key "q1" :finding_key "f1"
                :question "Who approves a quote over $5k?"}]})

(def ^:private an-error
  {:key "f1" :severity "error" :class "person_only" :subject "quote"
   :sentence "No one approves a quote over $5k."
   :remedy "Name who approves a quote over $5k."})

(def ^:private a-flipped-plan
  (assoc a-plan :film_url "https://example.com/greenleaf.mp4"
         :findings_sha "5e469c6166edf6a3f28ae2e6bdb88b775025f14d"
         :live_flag true
         :mayor {:name "greenleaf-mayor" :budget_usd_per_week "8"}))

(defscenario an-open-error-keeps-the-plan-down
  "A plan the audit says is broken does not go up as a pull request."
  {:kind    :org_plan
   :attempt :propose
   :row     {:state :drafting :data (assoc a-plan :findings [an-error])}
   :input   {}
   :as      {:id "onboarding" :type :agent}
   :expect  {:refused :no-error-finding-is-open}})

(defscenario a-plan-with-no-error-goes-up
  "And with no error open, the onboarding seat puts it up."
  {:kind    :org_plan
   :attempt :propose
   :row     {:state :drafting
             :data (assoc a-plan :findings [(assoc an-error :severity "warning")])}
   :input   {:law_page "# The law of greenleaf"}
   :as      {:id "onboarding" :type :agent}
   :expect  {:allowed true}})

(defscenario the-owner-answers
  "The owner answers the question the seat asked."
  {:kind    :org_plan
   :attempt :answer
   :row     {:state :drafting :data a-plan}
   :input   {:key "q1" :answer "I do, and over $20k my partner does too."}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-stranger-does-not-answer
  "A question is asked of the owner, and nobody else answers it."
  {:kind    :org_plan
   :attempt :answer
   :row     {:state :drafting :data a-plan}
   :input   {:key "q1" :answer "Whoever is around."}
   :as      {:id "sam" :type :person}
   :expect  {:refused :only-the-owner-decides}})

(defscenario an-agent-does-not-go-live-alone
  "Going live is the owner's one tap: an agent's go_live is refused
   here, and at the wire held for the owner."
  {:kind    :org_plan
   :attempt :go_live
   :row     {:state :simulated
             :data a-flipped-plan}
   :as      {:id "onboarding" :type :agent}
   :expect  {:refused :only-a-person-goes-live}})

(defscenario the-owner-goes-live
  "And the owner's own tap runs it."
  {:kind    :org_plan
   :attempt :go_live
   :row     {:state :simulated
             :data a-flipped-plan}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario an-unflipped-org-does-not-go-live
  "Nothing goes live until the org's branch says so: org.edn's :live is
   true at the head the audit last read."
  {:kind    :org_plan
   :attempt :go_live
   :row     {:state :simulated :data (assoc a-flipped-plan :live_flag false)}
   :as      {:id "colton" :type :person}
   :expect  {:refused :not-flipped}})

;; ── :org_plan ───────────────────────────────────────────────────────

(def ^:private finding-schema
  [:map
   [:key [:string {:min 1 :max 64}]]
   [:severity [:string {:max 32}]]
   [:class {:optional true} [:maybe [:string {:max 64}]]]
   [:subject {:optional true} [:maybe [:string {:max 200}]]]
   [:sentence {:optional true} [:maybe [:string {:max 240}]]]
   [:remedy {:optional true} [:maybe [:string {:max 240}]]]])

(def ^:private question-schema
  [:map
   [:key [:string {:min 1 :max 64}]]
   [:finding_key {:optional true} [:maybe [:string {:max 64}]]]
   [:question [:string {:min 1 :max 240}]]
   [:answer {:optional true :x-display {:widget "prose"}}
    [:maybe [:string {:max 2000}]]]
   [:answered_at {:optional true} [:maybe :waymark/instant]]
   [:resolved_at {:optional true} [:maybe :waymark/instant]]])

(def ^:private gap-schema
  [:map
   [:kind [:string {:min 1 :max 64}]]
   [:state [:string {:min 1 :max 64}]]
   [:reason [:enum "no_holder" "pressure"]]
   [:row_ids {:optional true} [:maybe [:vector {:max 20} [:string {:max 64}]]]]
   [:oldest_since {:optional true} [:maybe :waymark/instant]]
   [:expected_seconds {:optional true} [:maybe [:int {:min 0}]]]
   [:key [:string {:min 1 :max 200}]]])

(def ^:private mayor-schema
  [:map
   [:name [:string {:min 1 :max 64}]]
   [:budget_usd_per_week {:optional true} [:maybe [:string {:max 32}]]]])

(def ^:private findings-input
  "What the audit's run hands every findings door: the head, its
  findings and law page, and what go_live reads (M2e): whether org.edn
  says live at that head, and the mayor the seed seats."
  [:map
   [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
               :x-display {:hidden true :raw true
                           :label "The head commit"}}
    [:string {:min 7 :max 64}]]
   [:findings {:x-display {:hidden true
                           :label "The run's findings"}}
    [:vector {:max 200} finding-schema]]
   [:law_page {:optional true
               :x-display {:hidden true
                           :label "The run's law page"}}
    [:maybe [:string {:max 40000}]]]
   [:live_flag {:optional true
                :x-display {:hidden true
                            :label "The branch says live"}}
    [:maybe :boolean]]
   [:mayor {:optional true
            :x-display {:hidden true
                        :label "The seed's mayor"}}
    [:maybe mayor-schema]]])

(defresource org-plan
  {:kind :org_plan
   :plural "org_plans"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   :states [:drafting :proposed :filming :simulated :going_live :live :retired]
   :initial :drafting
   :terminal #{:retired}
   ;; THE CRACKS LAW. At `simulated` the onboarding seat's go_live is
   ;; held for the owner's tap, so the row waits on that held call and
   ;; the engine's landing of it is what moves the row to `live`.
   :waits [{:on :held_call :awaits :held_call :in #{:simulated}
            :waiter "the onboarding seat, whose go_live waits on the owner's tap"
            :outcomes {:done :release :refused :return
                       :failed :return :expired :return}}]
   ;; At `filming` the forge's film pass holds the next move: it judges
   ;; the director's take and lands it (ticket e552de65).
   :left-by [{:in #{:simulated} :by :engine :via :go_live :to :going_live}
             {:in #{:going_live} :by :engine :via :mark_live :to :live}
             {:in #{:going_live} :by :engine :via :head_moved :to :simulated}
             {:in #{:filming} :by :engine :via :attach_film :to :simulated}
             {:in #{:filming} :by :engine :via :record_film_findings :to :drafting}]
   ;; going_live waits a day at most on the merge and the mayor's seat
   :expected-age {:drafting 86400 :proposed 21600 :filming 3600 :simulated 604800
                  :going_live 86400}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :display {:title "{data.name}"}
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :owner #{:eq}
                :purpose #{:eq}}
   :on-create stamp-the-owner
   :create-guards [a-person-files-the-plan]
   :schema
   [:map
    [:name {:x-display
            {:label "The org's name"
             :help "A short name for the org, as its directory spells it: greenleaf, or acme-landscaping."}}
     [:string {:min 1 :max 64}]]
    [:brief {:optional true
             :x-display
             {:widget "prose"
              :label "The brief"
              :help "The person's own paragraph: what the business does, and what they want the house to run."}}
     [:maybe [:string {:max 4000}]]]
    [:org_dir {:optional true
               :x-display
               {:raw true
                :label "The org's directory"
                :help "Where the org's declarations live in the repository: orgs/ and the org's name, unless the filer named another."}}
     [:maybe [:string {:max 200}]]]
    ;; WRITTEN ONLY BY THE FINDINGS DOOR (M2b), and read by propose.
    [:findings {:optional true
                :x-display
                {:label "What the audit found"
                 :help "The engine's own audit of the plan, one finding a line. An error finding keeps the plan from going up as a pull request."}}
     [:maybe [:vector {:max 200} finding-schema]]]
    [:findings_sha {:optional true
                    :x-display
                    {:raw true
                     :label "Audited at"
                     :help "The head commit of the org's branch the findings were found on."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN ONLY BY THE FINDINGS DOORS, for that head (M2e)
    [:live_flag {:optional true
                 :x-display
                 {:label "The branch says live"
                  :help "Whether the org's org.edn says :live true at the head the audit last read. go_live waits until it does."}}
     [:maybe :boolean]]
    [:mayor {:optional true
             :x-display
             {:label "The mayor"
              :help "The mayor the org's seed seats, and its weekly budget, as the audit last read them."}}
     [:maybe mayor-schema]]
    ;; WRITTEN ONLY BY go_live: the head the owner's yes merges
    [:approved_head {:optional true
                     :x-display
                     {:raw true
                      :label "Allowed at"
                      :help "The head of the org's branch the owner's go_live allowed: the house merges the pull request at that head, and no other."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN ONLY BY mark_live: the seat the live org's mayor sits in (M2e-4)
    [:mayor_seat {:optional true
                  :x-display
                  {:raw true
                   :label "Its mayor's seat"
                   :help "The seat the org's mayor sits in, in the org's own domain: the engine writes it when it marks the org live."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN ONLY BY THE FINDINGS DOOR: the walk's step (M2c)
    [:next_step {:optional true
                 :x-display
                 {:label "The next step"
                  :help "The open finding the onboarding walk takes next, in its fixed order. Empty once no error finding is open: the walk is done and the plan can go up."}}
     [:maybe finding-schema]]
    [:questions {:optional true
                 :x-display
                 {:label "Questions for the owner"
                  :help "What the onboarding seat asked, each tied to the finding that raised it, and the owner's answer."}}
     [:maybe [:vector {:max 100} question-schema]]]
    [:change {:optional true :kind :change
              :x-display {:label "The pull request"}}
     [:maybe :waymark/ref]]
    ;; WRITTEN BY propose and the findings doors: the page
    ;; factory10.law-text renders
    [:law_page {:optional true
                :x-display
                {:widget "prose"
                 :label "The law"
                 :help "The org's law in sentences, as the pull request carries it: what the person approves when it merges."}}
     [:maybe [:string {:max 40000}]]]
    [:film_url {:optional true
                :x-display
                {:raw true
                 :label "The film"
                 :help "The film of the org's simulated first week, for the owner to watch before going live."}}
     [:maybe [:string {:max 500}]]]
    ;; WRITTEN BY hand_in_film: the director's take, which the engine's
    ;; film pass judges once (ticket e552de65)
    [:take_film_url {:optional true
                     :x-display
                     {:raw true
                      :label "The film handed in"
                      :help "The film the director handed in, before the film rules judged it."}}
     [:maybe [:string {:max 500}]]]
    [:take_report {:optional true
                   :x-display
                   {:raw true
                    :label "The take report"
                    :help "The report of the film handed in, as JSON: what the film rules judge."}}
     [:maybe [:string {:max 40000}]]]
    [:take_sha {:optional true
                :x-display
                {:raw true
                 :label "Filmed at"
                 :help "The head commit of the org's branch the film handed in was made at."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN BY attach_film and record_film_findings: the take is landed
    [:take_landed_at {:optional true
                      :x-display
                      {:label "The take landed"
                       :help "When the engine judged the film handed in and landed it. Empty while it waits to be judged."}}
     [:maybe :waymark/instant]]
    [:retired_why {:optional true
                   :x-display
                   {:label "Why it was retired"
                    :help "The owner's one sentence, written by the retire door."}}
     [:maybe [:string {:max 240}]]]
    ;; stamped at birth: found, unless the engine opened it to hire (M4a)
    [:purpose {:optional true
               :x-display
               {:label "Purpose"
                :help "found: the plan stands the org up. hire: the engine opened it on a live org, for one gap its audit found. A plan with none founds its org."}}
     [:maybe [:enum "found" "hire"]]]
    ;; WRITTEN ONLY BY THE ENGINE'S open-hire!: no create form takes it
    [:gap {:optional true
           :x-display
           {:label "The gap"
            :help "What the hire plan is for: the kind's rows waiting in a state past their expected age, and whether no seat holds the door out or the seats on it do not keep up."}}
     [:maybe gap-schema]]
    ;; stamped at birth, never input
    [:owner {:optional true :kind :member
             :x-display
             {:label "Owner"
              :help "The person who filed the plan, or the person the agent that filed it acts for. The engine writes it at birth."}}
     [:maybe :waymark/ref]]]
   :create-schema
   [:map
    [:name {:x-display {:label "The org's name"
                        :help "A short name for the org, as its directory spells it: greenleaf, or acme-landscaping."}}
     [:string {:min 1 :max 64}]]
    [:brief {:optional true
             :examples ["A six-person landscaping business. I want the house to run quotes, crew scheduling, invoicing and late payers."]
             :x-display {:widget "prose" :label "The brief"
                         :help "One paragraph in your own words: what the business does, and what you want the house to run."}}
     [:maybe [:string {:max 4000}]]]
    [:org_dir {:optional true
               :x-display {:raw true :label "The org's directory"
                           :help "Where the org's declarations will live. Leave it empty for orgs/ and the org's name."}}
     [:maybe [:string {:max 200}]]]]
   :actions
   {:ask
    {:from #{:drafting} :to :drafting
     :handler ask-the-owner
     :input [:map
             [:key {:x-display {:raw true :label "The question's key"
                                :help "A short key this question is known by on the plan; the owner's answer names it."}}
              [:string {:min 1 :max 64}]]
             [:finding_key {:optional true
                            :x-display {:raw true :label "The finding it answers"
                                        :help "The key of the audit finding that raised this question."}}
              [:maybe [:string {:max 64}]]]
             [:question {:examples ["Who approves a quote over $5k?"]
                         :x-display {:label "The question"
                                     :help "One plain question for the owner, as the finding raised it."}}
              [:string {:min 1 :max 240}]]]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The question stays on the plan, and the owner's answer is written beside it."}
     :display {:label "Ask" :order 1
               :description "Ask the owner one question the audit raised"}}

    :answer
    {:from #{:drafting} :to :drafting
     :guards [only-the-owner-decides only-a-person-answers the-question-was-asked]
     :handler answer-the-question
     :input [:map
             [:key {:x-display {:raw true :label "The question's key"
                                :help "The key of the question you are answering, as the plan's questions show it."}}
              [:string {:min 1 :max 64}]]
             [:answer {:examples ["I approve every quote over $5k myself."]
                       :x-display {:widget "prose" :label "Your answer"
                                   :help "Answer in plain words, as you would tell a new office manager."}}
              [:string {:min 1 :max 2000}]]]
     ;; :large-effort — an answer is a few plain sentences, typed once
     :waives #{:large-effort}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Answer" :order 2
               :description "Answer a question the onboarding seat asked"}}

    :propose
    {:from #{:drafting} :to :proposed
     :guards [no-error-finding-is-open the-plan-was-audited-at-its-head]
     :handler put-the-pull-request-up
     :input [:map
             [:change {:optional true :kind :change
                       :x-display {:label "The pull request"}}
              [:maybe :waymark/ref]]
             [:law_page {:optional true
                         :examples ["# The law of greenleaf\n\nThis is what you approve when you merge: each kind of record this org keeps, how it moves, what each move asks first, which moves wait for a person, and which seats work here and what they may spend."]
                         :x-display {:widget "prose" :label "The law"
                                     :help "The page factory10.law-text renders from the org's directory, for the person to read on the plan."}}
              [:maybe [:string {:max 40000}]]]]
     ;; :edit-shape — the first pull request onto a blank row is not an
     ;; edit of one
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The pull request is up for the owner to see."}
     :display {:label "Propose" :order 3
               :description "Put the plan up as a pull request"}}

    ;; THE DIRECTOR'S HAND-IN (ticket e552de65). It keeps the take and
    ;; decides nothing: the plan waits in `filming` for the engine's film
    ;; pass to judge it and land it.
    :hand_in_film
    {:from #{:proposed} :to :filming
     :handler hand-in-the-film
     :input [:map
             [:film_url {:x-display {:raw true :label "The film"
                                     :help "Where the film of the org's simulated first week is served."}}
              [:string {:min 1 :max 500}]]
             [:take_report {:examples ["{\"film\": {\"chrome_leaks\": 0, \"dead_air_s\": 0.4}}"]
                            :x-display {:raw true :label "The take report"
                                        :help "The film's take report, as JSON, as the tool that filmed it wrote it: the film rules judge it."}}
              [:string {:min 2 :max 40000}]]
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:raw true :label "The filmed commit"
                                     :help "The head commit of the org's branch the film was made at."}}
              [:string {:min 7 :max 64}]]]
     ;; :edit-shape — a take replaces the last one, nobody edits it
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The take is the engine's to judge: the plan waits in filming until the film pass lands it."}
     :display {:label "Hand in the film" :order 10
               :description "Hand in the film of the plan's head and its take report, for the engine to judge"}}

    ;; THE ENGINE'S HAND ALONE (ticket e552de65): the film pass lands a
    ;; green take here, as record_film_findings takes a red one.
    :attach_film
    {:from #{:filming} :to :simulated
     :guards [the-engine-records-the-findings]
     :handler attach-the-film
     :input [:map
             [:film_url {:x-display {:raw true :label "The film"
                                     :help "Where the film of the org's simulated first week is served, for the owner to watch."}}
              [:string {:min 1 :max 500}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The film is on the plan for the owner to watch."}
     :display {:label "Attach the film" :order 4
               :description "Attach the film of the org's simulated first week"}}

    ;; THE AUDIT'S OWN DOOR (M2b). Hidden, and the engine's hand alone,
    ;; for repo_policy's note_source's reasons. An empty run clears the
    ;; findings. While drafting: the findings are what keep propose shut.
    :record_findings
    {:from #{:drafting} :to :drafting
     :guards [the-engine-records-the-findings]
     :handler record-the-findings
     :input findings-input
     ;; :edit-shape — a run replaces the findings, nobody edits them
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Findings recorded" :order 7
               :description "The audit's run writes what it found on the org's branch"}}

    ;; AFTER PROPOSE (ticket c4893567). A push to a proposed plan's
    ;; branch still lands its findings, so the plan a person approves is
    ;; the plan as it stands. The same run, the same hand, the same state.
    :record_findings_proposed
    {:from #{:proposed} :to :proposed
     :guards [the-engine-records-the-findings]
     :handler record-the-findings
     :input findings-input
     ;; :edit-shape — a run replaces the findings, nobody edits them
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Findings recorded while proposed" :order 9
               :description "The audit's run writes what it found on a proposed plan's branch"}}

    ;; AFTER THE FILM (M2e). The onboarding seat's flip of org.edn is a
    ;; push to a simulated plan's branch: its run lands here, and says
    ;; whether the head go_live would merge is live.
    :record_findings_simulated
    {:from #{:simulated} :to :simulated
     :guards [the-engine-records-the-findings]
     :handler record-the-findings
     :input findings-input
     ;; :edit-shape — a run replaces the findings, nobody edits them
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Findings recorded while simulated" :order 12
               :description "The audit's run writes what it found on a simulated plan's branch"}}

    ;; A RED FILM (ticket d52abbf2). The film is judged while the plan is
    ;; up; a red rule is a finding, and the plan goes back to drafting
    ;; with it. The engine's hand alone, as record_findings is.
    :record_film_findings
    {:from #{:filming} :to :drafting
     :guards [the-engine-records-the-findings]
     :handler record-the-film-findings
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The filmed commit"}}
              [:string {:min 7 :max 64}]]
             [:findings {:x-display {:hidden true
                                     :label "The film's red rules"}}
              [:vector {:min 1 :max 200} finding-schema]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The plan is back in drafting, with the film's red rules among its findings."}
     :display {:label "Film findings recorded" :order 8
               :description "The film's red rules reopen the plan to drafting"}}

    ;; THE ONE TAP (M2e). The owner's yes is the merge's: the house
    ;; merges the plan's pull request at the approved head.
    :go_live
    {:from #{:simulated} :to :going_live
     :guards [not-flipped only-a-person-goes-live only-the-owner-decides]
     :handler approve-the-head
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The owner's yes merges the org's pull request at the audited head, and the org goes live. The way back is retire."}
     :display {:label "Go live" :order 5
               :description "Merge to make it real"}}

    ;; THE ENGINE'S STEP TO LIVE, once the org's domain and its mayor's
    ;; seat stand (M2e-4's pass takes it). The engine's hand alone.
    :mark_live
    {:from #{:going_live} :to :live
     :guards [the-engine-records-the-findings]
     :handler seat-the-mayor
     :input [:map
             [:mayor_seat {:optional true
                           :x-display {:hidden true :raw true
                                       :label "The mayor's seat"}}
              [:string {:min 1 :max 64}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The org is live: it is merged and its mayor sits. The way back is retire."}
     :display {:label "Live" :order 10
               :description "The engine marks the merged org live"}}

    ;; A HEAD THE OWNER DID NOT ALLOW. The house's merge pass does not
    ;; merge it: the yes is retired, and the plan is simulated again.
    :head_moved
    {:from #{:going_live} :to :simulated
     :guards [the-engine-records-the-findings]
     :handler the-head-moved
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The moved head"}}
              [:string {:min 7 :max 64}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The owner's yes is retired: the plan is simulated again, with the moved head among its findings."}
     :display {:label "Head moved" :order 11
               :description "The pull request's head moved after the owner's yes"}}

    ;; THE SIT'S LINK (ticket db8b35bb). The onboarding seat's sit mints
    ;; the plan's change; this door writes it onto the plan, so the
    ;; findings pass reads its head and the merge line holds it.
    :link_change
    {:from #{:drafting} :to :drafting
     :guards [the-sit-links-its-change]
     :handler link-the-change
     :input [:map
             [:change {:kind :change
                       :x-display {:label "The pull request"}}
              :waymark/ref]]
     ;; :edit-shape — the engine names the change, nobody edits it
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Change linked" :order 20
               :description "The sit names the change it minted for this plan"}}

    :retire
    {:from #{:drafting :proposed :filming :simulated :going_live :live} :to :retired
     :guards [only-the-owner-decides]
     :handler retire-the-plan
     :input [:map
             [:why {:examples ["We sold the business."]
                    :x-display {:label "Why"
                                :help "One sentence for the next reader: why this plan is let go. A retired plan takes no more doors."}}
              [:string {:min 1 :max 240}]]]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The plan is retired and takes no more doors."}
     :display {:label "Retire" :order 6
               :description "Let this plan go"}}}
   :scenarios [an-open-error-keeps-the-plan-down
               a-plan-with-no-error-goes-up
               the-owner-answers
               a-stranger-does-not-answer
               an-agent-does-not-go-live-alone
               the-owner-goes-live
               an-unflipped-org-does-not-go-live]})
