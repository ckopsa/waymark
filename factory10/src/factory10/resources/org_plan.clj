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

  A NEW HEAD VOIDS THE TAKE (ticket 4d9311f1). The film must be of the
  head the owner approves: a push to a `filming` plan's branch, seen by
  the findings pass or by the film pass before it lands the take, takes
  `void_take`, the engine's door alone, which clears the take and sends
  the plan back to `proposed` for the director to film the new head.

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
  The same run renders the org's first-week scene (ticket 4dbb8243),
  which the findings doors write as `first_week_scene` with the head it
  was rendered at as `first_week_scene_sha`: the director plays it only
  when that is the change's head.

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
  hire plan per gap key. The birth is the declared create door
  `open_hire`, held `:by #{:engine}` beside the ordinary create, which
  keeps `a-person-files-the-plan`; `open-hire!` is its thin caller.

  A HIRE ARRIVES AS ONE HELD CALL (ticket 7116068f, M4b). propose on a
  hire plan names `hire_seat`, the seed step factorykit.hire drafted on
  the plan's branch. An agent's go_live on it is held as the found
  plan's is, but its `why` is the offer the engine writes from the plan
  (`hire-offer`): the gap, the seat, its weekly cost and its doors in
  words, and its `shown` the seat, the cost and the gap key. The Allow
  is the same merge; then `factory10.org-live` opens the seat in the
  org's domain as the plan's owner and marks the plan live with
  `hired_seat`. A refused or expired offer retires the plan through
  `decline_hire` with `quiet_until` a week on, and open-hire! refuses
  `hire_quiet` for that gap until then.

  :nav :secondary, for change's reason: standing an org up is the
  day job's work, not the family's."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguard defguardfn defhandler defresource
                                   defscenario expr-guard refuse]]
            [waymark10.holds :as holds]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t])
  (:import (java.time Instant)))

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

(defn- purpose-backfill
  "The boot's data backfill (ticket 0fa0eb6c): a plan stored before
  `purpose` was declared founds its org, as stamp-the-owner says at
  create — so a `purpose <> hire` cond no longer misses it."
  [data]
  (if (str/blank? (str (:purpose data)))
    (assoc data :purpose "found")
    data))

;; ── the hire plan (M4a) ──────────────────────────────────────────────

(defn purpose-of
  "The plan's purpose, `found` or `hire`. Every stored plan carries one
  (the create's stamp, the boot's purpose-backfill), so it is read
  straight; a plan with none is a bug the backfill should catch."
  [plan]
  (some-> (get-in plan [:data :purpose]) name))

(def ^:private standing-hire-states
  "The states a hire plan blocks another for its gap in. A retired or
  live one does not."
  #{"drafting" "proposed" "filming" "simulated" "going_live"})

(defn hire-brief
  "The hire plan's brief: one sentence written from its gap."
  [{:keys [kind state reason row_ids oldest_since]}]
  (str (some-> kind name) " rows wait in " (some-> state name) ": " (count row_ids)
       " past their expected age, oldest since " (or oldest_since "unknown") "; "
       (if (= "pressure" (some-> reason name))
         "more than the seats on it keep up with"
         "no seat holds the door out")))

(defn- state-name [row] (some-> (:state row) name))

(defn plans-named
  "Every org_plan of the org `org`, decoded, read through the ctx
  `:find` hook — the same transaction as the write — as
  schedules/one-per-seat? does; a ctx without the hook answers nil, so
  the render probe stays storage-free."
  [ctx org]
  (when-some [find' (:find ctx)]
    (find' :org_plan {:name (str org)} {:limit 1000})))

(defn live-plan-of
  "The live found plan among `plans`, or nil."
  [plans]
  (first (filter #(and (= "live" (state-name %)) (= "found" (purpose-of %)))
                 plans)))

(defn- same-gap? [gap plan]
  (= (str (:key gap)) (str (get-in plan [:data :gap :key]))))

(def ^:private quiet-seconds
  "How long a refused hire keeps its gap quiet: a week."
  604800)

(defn- instant-of ^Instant [v]
  (cond (instance? Instant v) v
        (some? v) (try (Instant/parse (str v)) (catch Exception _ nil))))

;; the open_hire door's own law: any caller of the door — open-hire!,
;; or the live cracks pass walking it generically — is judged the same.
;; It is declarative (docs/spec-related-row-law.md §7, ticket fc360087):
;; every stored plan carries a `purpose` (the create's stamp, the boot's
;; purpose-backfill), so `not= "hire"` reads a found plan and misses
;; none. Its name stays `no_live_org`, the reason open-hire! answers.

(def the-org-is-live
  (expr-guard
   {:name :no_live_org
    :when '(exists [p :org_plan {:name (= (input :name))
                                 :state (= "live")
                                 :purpose (not= "hire")}])
    :vars {:org '(input :name)}
    :explain "No live plan founds {org}: a hire plan is opened on an org that is live."}))

;; the two code walls read the org's plans through the ctx :find hook,
;; and say `:check-tier :given`, so open_hire's scenarios need no database.

(defguardfn one-hire-per-gap
  {:name :hire_open
   :judges [:gap]
   :reads [:org_plan]
   :check-tier :given
   :vars [:gap :state]
   :explain "A hire plan for {gap} stands already, in {state}."}
  [_row inp ctx]
  (if-some [open (first (filter #(and (= "hire" (purpose-of %))
                                      (contains? standing-hire-states (state-name %))
                                      (same-gap? (:gap inp) %))
                                (plans-named ctx (:name inp))))]
    (t/deny {:vars {:gap (str (get-in inp [:gap :key])) :state (state-name open)}
             :evidence {:hire (str (:id open))}})
    (t/allow)))

(defguardfn a-turned-down-gap-is-quiet
  {:name :hire_quiet
   :judges [:gap]
   :reads [:org_plan]
   :check-tier :given
   :vars [:gap :until]
   :explain "The owner turned down a hire for {gap}: it is quiet until {until}."}
  [_row inp ctx]
  (let [^Instant now (or (instant-of (:now ctx)) (Instant/now))]
    (if-some [quiet (first (filter #(and (= "retired" (state-name %))
                                         (= "hire" (purpose-of %))
                                         (same-gap? (:gap inp) %)
                                         (some-> (instant-of (get-in % [:data :quiet_until]))
                                                 (.isAfter now)))
                                   (plans-named ctx (:name inp))))]
      (t/deny {:vars {:gap (str (get-in inp [:gap :key]))
                      :until (str (instant-of (get-in quiet [:data :quiet_until])))}
               :evidence {:hire (str (:id quiet))}})
      (t/allow))))

(def ^:private hire-refusals
  "The open_hire door's own guards, by the reason open-hire! answers."
  #{:no_live_org :hire_open :hire_quiet})

(defn open-hire!
  "The engine opens a hire plan for the org `name` on one `gap`. →
  inv/create!'s answer, or {:refused <reason> :why <sentence>}:
  `engine_only` when the hand is not the engine's, `no_live_org` when
  the org has no live found plan, `hire_open` when a hire plan for
  the same gap key stands in drafting, proposed, filming or simulated,
  and `hire_quiet` while a retired one for it is quiet (M4b). Those
  three are the open_hire door's guards, and the live plan's owner and
  directory are its on-create's: this is the door's thin caller."
  [eng {org :name gap :gap} {:keys [principal] :as opts}]
  (if-not (= :system (:type principal))
    {:refused :engine_only
     :why "Only the engine's hand opens a hire plan: it opens one for each gap its own audit finds."}
    (try
      (inv/create! eng :org_plan {:name org :gap gap} (assoc opts :action :open_hire))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [guard detail evidence]} (ex-data e)
              reason (some-> guard name keyword)]
          (if (contains? hire-refusals reason)
            (cond-> {:refused reason :why (or detail (ex-message e))}
              (:hire evidence) (assoc :hire (:hire evidence)))
            (throw e)))))))

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
   "decision" 5
   "film" 6})

(def ^:private lint-rank 7)

(def ^:private decision-order
  "The decision class's questions in the order the person meets them
  (ticket e1ed02c7): a final step, a wait, an amount."
  ["decision/unheld_final/" "decision/no_wait_limit/" "decision/money_unbounded/"])

(defn- question-rank [k]
  (or (first (keep-indexed #(when (str/starts-with? (str k) %2) %1) decision-order)) 0))

(defn walk-steps
  "The plan's open findings as the walk's steps: by class in
  walk-order, ties broken by key."
  [findings]
  (vec (sort-by (juxt #(get walk-order (str (:class %)) lint-rank)
                      #(question-rank (:key %))
                      #(str (:key %)))
                findings)))

(defn next-step
  "The walk's next step: its first open error finding, whatever its
  class, so an error comes before any warning; nil when no error
  finding is open, and the walk is done."
  [findings]
  (first (walk-steps (filter #(= "error" (str (:severity %))) findings))))

(defn- short-sha [sha] (let [s (str sha)] (subs s 0 (min 7 (count s)))))

(defn take-of?
  "Is the take `take-sha` a film of `head` (ticket 87fc7996)? The
  director may hand in a short sha — hand_in_film takes seven
  characters and up — so a take the head begins with names it."
  [take-sha head]
  (let [tk (str take-sha)
        hd (str head)]
    (and (<= 7 (count tk)) (str/starts-with? hd tk))))

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

(defn- workaround-key [w] [(:concept w) (:subject w)])

(defhandler log-the-workarounds [row inp _ctx]
  ;; Workarounds are appended, never rewritten: one already logged
  ;; under the same concept and subject is nothing, so a retried log is
  ;; safe. The concepts they name are kept beside them, once each, the
  ;; list a query filters plans by (ticket 4fdcd08a).
  (let [logged (reduce (fn [ws w]
                         (if (some #(= (workaround-key w) (workaround-key %)) ws)
                           ws
                           (conj ws (select-keys w [:concept :subject :need :instead]))))
                       (vec (get-in row [:data :workarounds]))
                       (:entries inp))]
    (-> row
        (assoc-in [:data :workarounds] logged)
        (assoc-in [:data :workarounds_concept] (vec (distinct (map :concept logged)))))))

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
    (some? (:hire_seat inp)) (assoc-in [:data :hire_seat] (:hire_seat inp))
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
  ;; The take is landed: the film pass does not judge it again. A green
  ;; take's warnings (ticket ef24488d) join the findings as a red one's
  ;; rules do, a key already on the plan replaced; a warning moves no step.
  (let [film (vec (:findings inp))
        theirs (into #{} (map :key) film)]
    (cond-> (-> row
                (assoc-in [:data :film_url] (:film_url inp))
                (assoc-in [:data :take_landed_at] (:now ctx)))
      (seq film)
      (update-in [:data :findings]
                 #(into (vec (remove (fn [f] (contains? theirs (:key f))) %)) film)))))

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
      (some? (:first_week_scene inp))
      (-> (assoc-in [:data :first_week_scene] (:first_week_scene inp))
          (assoc-in [:data :first_week_scene_sha] (:head_sha inp)))
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

(defhandler decline-the-hire [row inp ctx]
  ;; The owner said no to the offer, or let it lapse: the gap is quiet
  ;; for a week, and open-hire! reads `quiet_until`.
  (-> row
      (assoc-in [:data :retired_why] (:why inp))
      (assoc-in [:data :quiet_until] (.plusSeconds ^Instant (instant-of (:now ctx)) quiet-seconds))))

(defhandler approve-the-head [row _inp _ctx]
  ;; The owner's yes is for the head the audit last read: the house's
  ;; merge pass merges that head, or one the rig says carries from it.
  (assoc-in row [:data :approved_head] (get-in row [:data :findings_sha])))

(defhandler seat-the-mayor [row inp _ctx]
  ;; The org is live because its mayor sits: the plan names that seat.
  (cond-> row
    (some? (:mayor_seat inp)) (assoc-in [:data :mayor_seat] (:mayor_seat inp))
    (some? (:hired_seat inp)) (assoc-in [:data :hired_seat] (:hired_seat inp))))

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

(defhandler void-the-take [row inp _ctx]
  ;; A take of a head the branch has left is no film of the plan the
  ;; owner approves: it is cleared, and the moved head is a finding the
  ;; next run at that head replaces.
  (let [voided {:key "take_voided" :severity "warning" :class "film"
                :subject "film"
                :sentence (str "The branch's head moved to " (short-sha (:head_sha inp))
                               " while the film of "
                               (short-sha (get-in row [:data :take_sha]))
                               " waited to be judged, so that take was voided.")
                :remedy "Film the new head: the film must be of the head the owner approves."}
        found (conj (vec (remove #(= "take_voided" (str (:key %)))
                                 (get-in row [:data :findings])))
                    voided)]
    (-> row
        (assoc-in [:data :film_url] nil)
        (assoc-in [:data :take_film_url] nil)
        (assoc-in [:data :take_report] nil)
        (assoc-in [:data :take_sha] nil)
        (assoc-in [:data :take_landed_at] nil)
        (assoc-in [:data :findings] found)
        (assoc-in [:data :next_step] (next-step found)))))

(defhandler refilm-the-head [row inp _ctx]
  ;; A NEW HEAD IS FILMED AGAIN (ticket 87fc7996). The film the owner
  ;; would judge is of a head the change has left: it is cleared, with
  ;; any yes, and the finding names the take it leaves.
  (let [take-sha (get-in row [:data :take_sha])
        refilm {:key "take_left" :severity "warning" :class "film"
                :subject "film"
                :sentence (if (true? (:org_changed inp))
                            ;; ticket ce8a4e17: the base move changed the org
                            (str "The base move brought the change's head to "
                                 (short-sha (:head_sha inp))
                                 " and changed the org since the film of "
                                 (short-sha take-sha)
                                 ", so it must be filmed again: the plan is proposed again to film the new head.")
                            (str "The change's head moved to " (short-sha (:head_sha inp))
                                 " after the film of "
                                 (if (str/blank? (str take-sha))
                                   "an earlier head"
                                   (short-sha take-sha))
                                 ", so the plan is proposed again to film the new head."))
                :remedy "Let the director film the new head: go_live merges only the head that was filmed."}
        found (conj (vec (remove #(= "take_left" (str (:key %)))
                                 (get-in row [:data :findings])))
                    refilm)]
    (-> row
        (assoc-in [:data :film_url] nil)
        (assoc-in [:data :take_film_url] nil)
        (assoc-in [:data :take_report] nil)
        (assoc-in [:data :take_sha] nil)
        (assoc-in [:data :take_landed_at] nil)
        (assoc-in [:data :approved_head] nil)
        (assoc-in [:data :findings] found)
        (assoc-in [:data :next_step] (next-step found)))))

(defhandler carry-the-take [row inp _ctx]
  ;; A BASE MOVE THAT LEFT THE ORG ALONE (ticket ce8a4e17). The film is
  ;; of the org, and the org at the new head is the org that was filmed:
  ;; the take and the findings move to the new head, and a yes with
  ;; them, so nothing is filmed again.
  (let [head (:head_sha inp)]
    (cond-> (-> row
                (assoc-in [:data :take_sha] head)
                (assoc-in [:data :findings_sha] head))
      (some? (get-in row [:data :approved_head]))
      (assoc-in [:data :approved_head] head))))

(defhandler relink-the-change [row inp _ctx]
  ;; A CHANGE MERGED BEFORE GO_LIVE (ticket 87fc7996). The plan names
  ;; the fresh change the sit minted, and a yes for the merged head is
  ;; retired. The take stays: the new head is not it, so the findings
  ;; pass sends the plan to be filmed again once the seat pushes.
  (-> row
      (assoc-in [:data :change] (:change inp))
      (assoc-in [:data :approved_head] nil)))

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn a-person-files-the-plan
  {:reads [:principal]
   :explain "An org_plan is a person's: it is filed by a person, or by an agent acting for one, and that person owns it. A hand that is nobody's has no one to ask the questions of."
   :open "File it as a person, or as an agent acting for one."}
  [_row _inp ctx]
  (if (some? (the-person ctx)) (t/allow) (t/deny)))

;; the org's directory is orgs/<name>, so one plan not retired holds a
;; name: a second create cannot take over the first's directory (ticket
;; be518c13). It reads through the ctx :find hook, as the hire walls do.

(defguardfn one-plan-per-name
  {:judges [:name]
   :reads [:org_plan]
   :check-tier :given
   :vars [:name :plan :state]
   :explain "The org {name} is held by plan {plan}, in {state}: one plan that is not retired holds an org's name, and its directory orgs/{name}."
   :open "Work the plan that holds the name, retire it first, or file this one under another name."}
  [_row inp ctx]
  (if-some [held (first (remove #(= "retired" (state-name %))
                                (plans-named ctx (:name inp))))]
    (t/deny {:vars {:name (str (:name inp)) :plan (str (:id held)) :state (state-name held)}
             :evidence {:plan (str (:id held))}})
    (t/allow)))

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

(defguardfn the-take-is-of-the-head
  {:reads []
   :vars [:take :head]
   :explain "The film is of {take} and the pull request's head is {head}: go_live merges only the head that was filmed."
   :open "Let the findings pass send the plan back to proposed (refilm) and the director film the new head, then ask go_live again."}
  ;; Ticket 87fc7996: a new head is filmed again, no exception. Read
  ;; through the ctx hook as `the-plan-was-audited-at-its-head` reads;
  ;; a ctx with no hook allows, for the same reasons.
  [row _inp ctx]
  (if-some [read' (:read ctx)]
    (let [named (some-> (get-in row [:data :change]) str not-empty)
          head (some-> named (->> (read' :change)) (get-in [:data :head_sha]) str not-empty)
          take-sha (some-> (get-in row [:data :take_sha]) str not-empty)]
      (if (and head (take-of? take-sha head))
        (t/allow)
        (t/deny {:vars {:take (if take-sha (short-sha take-sha) "no head")
                        :head (if head (short-sha head) "none yet")}})))
    (t/allow)))

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
    ;; a hire plan's go_live is held by only-a-person-hires, with the offer
    (= "hire" (purpose-of row)) (t/allow)
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :go_live (:id row)) (t/allow)
    :else (t/deny {:vars (go-live-vars row)})))

(defn- cut [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- dollars [b]
  (or (try (.toPlainString (.stripTrailingZeros ^BigDecimal (bigdec b)))
           (catch Exception _ nil))
      "?"))

(defn- doors-said [doors]
  (if (next doors)
    (str (str/join ", " (butlast doors)) " or " (last doors))
    (first doors)))

(defn- scope-said
  "A seat's scope as verbs: each entry's doors on its kind's rows, or
  `read` for an entry that holds none."
  [scope]
  (str/join "; "
            (for [{:keys [kind actions] :as e} scope
                  :let [f (:filter e)
                        state (some-> (or (get f :state) (get f "state")) str not-empty)
                        rows (str kind " rows" (when state (str " in " state)))]]
              (if (seq actions)
                (str (doors-said (mapv str actions)) " " rows)
                (str "read " rows)))))

(defn hire-offer
  "What the owner reads in a hire plan's held go_live: `vars` for
  only-a-person-hires' sentence — the gap sentence from the brief, the
  seat, its weekly dollars and its doors in words — and `shown`, the
  seat, its cost and the gap key."
  [row]
  (let [d (:data row)
        hs (:hire_seat d)
        seat (or (some-> (:name hs) str not-empty) "unnamed")
        budget (dollars (:budget_usd_per_week hs))
        gap (-> (str (:brief d)) str/trim (str/replace #"\.+$" ""))]
    {:vars {:gap (cut (if (seq gap) (str (str/upper-case (subs gap 0 1)) (subs gap 1)) "A gap") 400)
            :hire seat
            :budget budget
            :can (cut (or (not-empty (scope-said (:scope hs))) "do nothing") 400)}
     :shown (str seat " · $" budget "/wk · " (get-in d [:gap :key]))}))

(defguardfn only-a-person-hires
  {:reads [:principal :within]
   :hold true
   :vars [:gap :hire :budget :can]
   :explain "{gap}. Add {hire} at ${budget}/week? It can {can}; it cannot take any other door, or touch any other kind."
   :open "No door clears this one. The offer waits as a held_call for the owner's tap: Allow merges the hire and opens the seat, and Refuse keeps this gap quiet for a week."}
  [row _inp ctx]
  (cond
    (not= "hire" (purpose-of row)) (t/allow)
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :go_live (:id row)) (t/allow)
    :else (let [{:keys [vars shown]} (hire-offer row)]
            (t/deny {:vars vars :evidence {:shown shown}}))))

(defguardfn a-hire-names-its-seat
  {:reads []
   :explain "A hire plan goes up with the seat it hires: propose names it as hire_seat, the seed step factorykit.hire drafted on the plan's branch."
   :open "Propose again with hire_seat: the seat's name, charter, scope and weekly budget."}
  [row inp _ctx]
  (if (and (= "hire" (purpose-of row))
           (nil? (:hire_seat inp))
           (nil? (get-in row [:data :hire_seat])))
    (t/deny)
    (t/allow)))

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

;; THE CHANGE PROPOSE NAMES IS THE PLAN'S OWN (ticket b02c9808). The
;; sit mints it `born_from` the plan; a change opened for anything else,
;; or one already closed or merged, is not this plan's pull request.
;; Declarative: the reads are derived from the `follow`, and a
;; scenario's :given rows answer them.

(defguard the-change-was-opened-for-this-plan
  (refuse "That change was not opened for this plan.")
  '(or (not (is-set (input :change)))
       (and (= (follow (input :change) :born_from) (str "org_plan:" (self)))
            (not= (follow (input :change) :state) "closed")
            (not= (follow (input :change) :state) "merged")))
  {:open "Name the change the sit opened for this plan, or name none and propose with the plan's own."
   :errors {:change "not a change opened for this plan"}})

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
  "And with no error open, the onboarding seat puts it up, with the
   change opened for it and audited at its head."
  {:kind    :org_plan
   :attempt :propose
   :given   [{:kind :change :handle :its-own :state :open
              :data {:repository "ckopsa/waymark"
                     :title "An org's pull request"
                     :born_from "org_plan:scenario:a-plan-with-no-error-goes-up"
                     :head_sha (:findings_sha a-flipped-plan)}}]
   :row     {:state :drafting
             :data (assoc a-plan :findings [(assoc an-error :severity "warning")]
                          :findings_sha (:findings_sha a-flipped-plan))}
   :input   {:change "{given/its-own}" :law_page "# The law of greenleaf"}
   :as      {:id "onboarding" :type :agent}
   :expect  {:allowed true}})

(defscenario a-plan-does-not-go-up-with-another-plans-change
  "The pull request a plan goes up with is the one opened for it: a
   change born from another plan is refused."
  {:kind    :org_plan
   :attempt :propose
   :given   [{:kind :change :handle :other :state :open
              :data {:repository "ckopsa/waymark"
                     :title "Another org's pull request"
                     :born_from "org_plan:another-plan"
                     :head_sha (:findings_sha a-flipped-plan)}}]
   :row     {:state :drafting
             :data (assoc a-plan :findings_sha (:findings_sha a-flipped-plan))}
   :input   {:change "{given/other}" :law_page "# The law of greenleaf"}
   :as      {:id "onboarding" :type :agent}
   :expect  {:refused :the-change-was-opened-for-this-plan
             :because "That change was not opened for this plan."}})

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

(def ^:private a-gap
  {:kind "invoice" :state "sent" :reason "no_holder"
   :row_ids ["i1" "i2" "i3"] :oldest_since "2026-10-11T08:00:00Z"
   :expected_seconds 604800 :key "greenleaf/invoice/sent"})

(defscenario no-hire-without-a-live-org
  "A hire plan is opened on an org that is live: with no live found
   plan, the engine's open_hire is refused."
  {:kind    :org_plan
   :attempt :open_hire
   :at      "2026-10-18T08:00:00Z"
   :input   {:name "greenleaf" :gap a-gap}
   :as      {:id "factory10-hire" :type :system}
   :expect  {:refused :no_live_org}})

(defscenario a-live-org-opens-a-hire
  "And with the org's found plan live, the engine opens the hire."
  {:kind    :org_plan
   :attempt :open_hire
   :at      "2026-10-18T08:00:00Z"
   :given   [{:kind :org_plan :state :live
              :data {:name "greenleaf" :purpose "found"}}]
   :input   {:name "greenleaf" :gap a-gap}
   :as      {:id "factory10-hire" :type :system}
   :expect  {:allowed true}})

(defscenario a-second-plan-does-not-take-a-held-name
  "An org's name is held by its plan while that plan is not retired: a
   second create with the same name is refused."
  {:kind    :org_plan
   :attempt :create
   :given   [{:kind :org_plan :state :drafting
              :data {:name "greenleaf" :purpose "found"}}]
   :input   {:name "greenleaf"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :one-plan-per-name}})

(defscenario a-retired-plan-frees-its-name
  "And once that plan is retired, the name may be filed again."
  {:kind    :org_plan
   :attempt :create
   :given   [{:kind :org_plan :state :retired
              :data {:name "greenleaf" :purpose "found"}}]
   :input   {:name "greenleaf"}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

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

(def workaround-concepts
  "The concepts a workaround stands in for, as epic 2bb0aed8 numbers
  them: 1 related-row rules, 2 calendar time and recurrence, 3 ledger
  money, 4 retention and evidence, 5 teams and outside parties, 6
  integrations, 7 goals and measures; then new and default."
  ["1" "2" "3" "4" "5" "6" "7" "new" "default"])

(def ^:private workaround-schema
  [:map
   [:concept (into [:enum] workaround-concepts)]
   [:subject [:string {:min 1 :max 200}]]
   [:need [:string {:min 1 :max 240}]]
   [:instead [:string {:min 1 :max 240}]]])

(def ^:private hire-input
  "The open_hire door's input: the org, the gap, and the live plan's
  owner and directory, which the engine copies."
  [:map
   [:name [:string {:min 1 :max 64}]]
   [:gap gap-schema]
   [:org_dir {:optional true} [:maybe [:string {:max 200}]]]
   [:owner {:optional true :kind :member} [:maybe :waymark/ref]]])

(defn- stamp-the-hire
  "The open_hire door's birth: a hire plan, its brief written from its
  gap, with the live plan's owner and directory unless the caller
  named them."
  [row ctx]
  (let [live (live-plan-of (plans-named ctx (get-in row [:data :name])))]
    (cond-> (-> row
                (assoc-in [:data :purpose] "hire")
                (assoc-in [:data :brief] (hire-brief (get-in row [:data :gap]))))
      (and live (str/blank? (str (get-in row [:data :owner]))))
      (assoc-in [:data :owner] (str (get-in live [:data :owner])))

      (and live (str/blank? (str (get-in row [:data :org_dir]))))
      (assoc-in [:data :org_dir] (get-in live [:data :org_dir])))))

(def ^:private mayor-schema
  [:map
   [:name [:string {:min 1 :max 64}]]
   [:budget_usd_per_week {:optional true} [:maybe [:string {:max 32}]]]])

(def name-pattern
  "An org's name becomes its directory (`orgs/<name>`), namespace, domain
  and seed org (ticket d4750bf9), and live-orgs boots only a snake_case
  one (waymark10.server.org, docs/orgkit.md § Naming rules): lowercase
  letters, digits and underscores, starting with a letter. A string, so
  the published schema's `pattern` spells it as written."
  "^[a-z][a-z0-9_]*$")

(defn snake-name
  "What `typed` reads as a name: lowercased, `&` dropped, every other run
  of what is not a letter or a digit one underscore, none at either end,
  `org_` before a leading digit, at most 64 long. `r&d-tax-services`
  reads as `rd_tax_services`."
  [typed]
  (let [s (-> (str/lower-case (str typed))
              (str/replace "&" "")
              (str/replace #"[^a-z0-9]+" "_")
              (str/replace #"^_+" "")
              (str/replace #"_+$" ""))
        s (if (re-find #"^[0-9]" s) (str "org_" s) s)]
    (str/replace (subs s 0 (min 64 (count s))) #"_+$" "")))

(def ^:private name-schema
  "The create form's name: the refusal suggests the snake_case of what
  was typed, so the retry is right the first time. It publishes as ONE
  string schema, not an allOf, so every form offers it as a box."
  [:and {:json-schema {:type "string" :minLength 1 :maxLength 64
                       :pattern name-pattern}}
   [:string {:min 1 :max 64}]
   [:re {:error/fn
         (fn [{:keys [value]} _]
           (str "should be lowercase letters, digits and underscores, starting with a letter, like "
                (or (not-empty (snake-name value)) "rd_tax_services")))}
    name-pattern]])

(defguardfn the-name-is-snake-case
  {:reads []
   :explain "This plan's name predates the snake_case rule (lowercase letters, digits and underscores, starting with a letter): retire it and file it again under a snake_case name."
   :open "Retire this plan and file it again under a snake_case name."}
  [row _inp _ctx]
  ;; ticket 4d243311: a name filed before d4750bf9 is read and retired,
  ;; and no other door builds an org from it
  (if (re-matches (re-pattern name-pattern) (str (get-in row [:data :name])))
    (t/allow)
    (t/deny)))

(def ^:private entry-schema
  "One entry of a hired seat's scope or wake_on: a kind, the doors on it
  and, when the entry narrows, the field it filters by."
  [:vector {:max 50}
   [:map
    [:kind {:x-display {:label "Kind"
                        :help "One kind the org serves."}}
     [:string {:min 1 :max 64}]]
    [:actions {:x-display {:label "Doors"
                           :help "The doors on that kind, by name. None is a read."}}
     [:vector {:max 50} [:string {:min 1 :max 64}]]]
    [:filter {:optional true
              :x-display {:label "Only rows where"
                          :spelled-by-hand "No form can list another kind's field names, so this pair is typed: write the field name, then the value it must equal."
                          :help "A field name, and the value it must equal: state, or a field the kind filters by."}}
     [:maybe [:map-of :keyword [:string {:min 1 :max 200}]]]]]])

(defn- log-workarounds-door
  "The log_workarounds door as a self-loop on `state`. A run learns
  workarounds after drafting too, so the door is spelled once per state
  it stays in (a v10 action declares one :to). It only appends to
  `workarounds`: no law, no head, so no refilm, findings run or void
  (ticket beb733af)."
  [state label order]
  {:from #{state} :to state
   :guards [the-name-is-snake-case]
   :handler log-the-workarounds
   :input [:map
           [:entries {:x-display {:label "The workarounds"
                                  :help "Each workaround you made. concept: 1 related-row rules, 2 calendar time and recurrence, 3 ledger money, 4 retention and evidence, 5 teams and outside parties, 6 integrations, 7 goals and measures, new or default. subject: kind.field or kind/door. need: what the org needed. instead: what you built in its place. One already logged under the same concept and subject is kept as it was."}}
            [:vector {:min 1 :max 50} workaround-schema]]]
   :safety {:idempotent true :reversible false :confirm false
            :one-way "The workarounds stay on the plan, where the mayor and the owner count them."}
   :display {:label label :order order
             :description "Log the workarounds onboarding had to make"}})

(def ^:private hire-seat-schema
  "The seat a hire plan hires, as factorykit.hire's seed step drafts it
  (M4b): what the engine opens in the org's domain once the hire merges."
  [:map
   [:name {:x-display {:label "Its name"
                       :help "The seat's name in the org: the org, the kind and a number."}}
    [:string {:min 1 :max 64}]]
   [:charter {:x-display {:widget "prose" :label "Its charter"
                          :help "What the seat does, in the words its sitter reads."}}
    [:string {:min 1 :max 4000}]]
   [:scope {:x-display {:label "Its doors"
                        :help "What the seat may walk, entry by entry."}}
    entry-schema]
   [:budget_usd_per_week {:x-display {:label "Its weekly dollars"
                                      :help "What the seat may spend in a rolling week."}}
    [:decimal {:min 0 :max 100000}]]
   [:mode {:optional true
           :x-display {:label "How it is fired"
                       :help "fired, as the seed step drafts it."}}
    [:maybe [:string {:max 32}]]]
   [:walk {:optional true
           :not-a-ref "It holds the name of the kind the seat walks, never a row id."
           :x-display {:label "The queue it walks"
                       :help "The kind the seat works through, a row at a time."}}
    [:maybe [:string {:max 64}]]]
   [:rows_per_firing {:optional true
                      :x-display {:label "Rows a firing"
                                  :help "How many rows one firing takes."}}
    [:maybe [:int {:min 1 :max 100}]]]
   [:cadence_seconds {:optional true
                      :x-display {:label "How often it wakes, in seconds"
                                  :help "The interval its schedule fires it at."}}
    [:maybe [:int {:min 300 :max 2592000}]]]
   [:standing_ttl_seconds {:optional true
                           :x-display {:label "Longest leash, in seconds"
                                       :help "The longest grant a sitter of the seat may ask for."}}
    [:maybe [:int {:min 60 :max 31536000}]]]
   [:sitting_budget_tokens {:optional true
                            :x-display {:label "Tokens a sitting"
                                        :help "What one sitting may read and write."}}
    [:maybe [:int {:min 1 :max 10000000}]]]
   [:wake_on {:optional true
              :x-display {:label "What wakes it"
                          :help "The doors on a kind that wake the seat, entry by entry."}}
    [:maybe entry-schema]]])

(def ^:private findings-input
  "What the audit's run hands every findings door: the head, its
  findings and law page, and what go_live reads (M2e): whether org.edn
  says live at that head, and the mayor the seed seats."
  [:map
   [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
               :x-display {:hidden true :raw true
                           :label "The head commit"}}
    schema/commit-sha]
   [:findings {:x-display {:hidden true
                           :label "The run's findings"}}
    [:vector {:max 200} finding-schema]]
   [:law_page {:optional true
               :x-display {:hidden true
                           :label "The run's law page"}}
    [:maybe [:string {:max 40000}]]]
   [:first_week_scene {:optional true
                       :x-display {:hidden true
                                   :label "The run's first-week scene"}}
    [:maybe [:string {:max 65536}]]]
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
             {:in #{:simulated} :by :engine :via :decline_hire :to :retired}
             {:in #{:going_live} :by :engine :via :mark_live :to :live}
             {:in #{:going_live} :by :engine :via :head_moved :to :simulated}
             {:in #{:filming} :by :engine :via :attach_film :to :simulated}
             {:in #{:filming} :by :engine :via :record_film_findings :to :drafting}
             {:in #{:filming} :by :engine :via :void_take :to :proposed}
             {:in #{:simulated} :by :engine :via :refilm :to :proposed}
             {:in #{:going_live} :by :engine :via :refilm :to :proposed}
             {:in #{:going_live} :by :engine :via :relink_change :to :simulated}]
   ;; going_live waits a day at most on the merge and the mayor's seat
   :expected-age {:drafting 86400 :proposed 21600 :filming 3600 :simulated 604800
                  :going_live 86400}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :display {:title "{data.name}"}
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :owner #{:eq}
                :purpose #{:eq}
                :workarounds_concept #{:eq :in}}
   :on-create stamp-the-owner
   :data-migrate purpose-backfill
   :create-guards [a-person-files-the-plan one-plan-per-name]
   ;; the engine's own birth of a hire plan (M4a), beside the create
   :creates [{:action :open_hire
              :input hire-input
              :by #{:engine}
              :guards [the-org-is-live one-hire-per-gap a-turned-down-gap-is-quiet]
              :on-create stamp-the-hire
              :display {:label "Open a hire plan"}}]
   :schema
   [:map
    [:name {:x-display
            {:label "The org's name"
             :help "A short name for the org, as its directory spells it: greenleaf, or acme_landscaping."}}
     ;; a stored name may predate the snake_case rule (ticket 4d243311):
     ;; create-schema judges a new one, the-name-is-snake-case the doors
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
    ;; read by the bench's held-forward pass (ticket 8288529f); none
    ;; means at_go_live, so a stored plan needs no migration
    [:base_follow {:optional true
                   :x-display
                   {:label "Keep the branch current"
                    :help "at_go_live (the default): the plan's branch is brought up to its base only from go_live on, so a filmed plan is not refilmed by every base move. always: it is brought up on every base move. when_org_dir_changes: it is brought up on every base move, and refilmed only when the move changed the org's directory."
                    :choices {"at_go_live" "From go_live on"
                              "always" "On every base move"
                              "when_org_dir_changes" "On every base move, refilmed only when the org changed"}}}
     [:maybe [:enum "at_go_live" "always" "when_org_dir_changes"]]]
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
     [:maybe schema/commit-sha]]
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
     [:maybe schema/commit-sha]]
    ;; WRITTEN ONLY BY mark_live: the seat the live org's mayor sits in (M2e-4)
    [:mayor_seat {:optional true
                  :x-display
                  {:raw true
                   :label "Its mayor's seat"
                   :help "The seat the org's mayor sits in, in the org's own domain: the engine writes it when it marks the org live."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN BY propose on a hire plan (M4b): the seat the offer names
    [:hire_seat {:optional true
                 :x-display
                 {:label "The seat it hires"
                  :help "The seat a hire plan adds, as the seed step on its branch drafts it: its name, charter, scope and weekly budget. The engine opens it when the owner allows the hire."}}
     [:maybe hire-seat-schema]]
    ;; WRITTEN ONLY BY mark_live on a hire plan: the seat it opened (M4b)
    [:hired_seat {:optional true
                  :x-display
                  {:raw true
                   :label "The seat it opened"
                   :help "The seat the engine opened in the org's domain when the hire went live."}}
     [:maybe [:string {:max 64}]]]
    ;; WRITTEN ONLY BY decline_hire: the gap is quiet until then (M4b)
    [:quiet_until {:optional true
                   :x-display
                   {:label "Quiet until"
                    :help "The owner turned this hire down, or let the offer lapse: no hire plan opens for the same gap before this time."}}
     [:maybe :waymark/instant]]
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
    ;; WRITTEN ONLY BY THE log_workarounds DOOR (ticket 4fdcd08a): read
    ;; and counted like any plan field, and not law, so the law page
    ;; leaves it out
    [:workarounds {:optional true
                   :x-display
                   {:label "Workarounds"
                    :help "What the onboarding seat had to make do with where the engine had no concept for it: the concept, the kind's field or door, what the org needed, and what was built instead."}}
     [:maybe [:vector {:max 200} workaround-schema]]]
    [:workarounds_concept {:optional true
                           :x-display
                           {:label "Workaround concepts"
                            :help "Each concept the plan's workarounds name, once: what a query finds plans by."}}
     [:vector {:max 9} [:waymark/vocab {:open true}]]]
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
    ;; WRITTEN ONLY BY THE FINDINGS DOORS: the scene the audit's run
    ;; rendered for that head, which the director plays (ticket 4dbb8243)
    [:first_week_scene {:optional true
                        :x-display
                        {:raw true
                         :label "The first-week scene"
                         :help "The script of the org's simulated first week, rendered by the audit from the org's seed: what the director plays on a clone."}}
     [:maybe [:string {:max 65536}]]]
    [:first_week_scene_sha {:optional true
                            :x-display
                            {:raw true
                             :label "Scene rendered at"
                             :help "The head commit of the org's branch the first-week scene was rendered at."}}
     [:maybe [:string {:max 64}]]]
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
     [:maybe schema/commit-sha]]
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
    ;; WRITTEN ONLY BY THE ENGINE'S open_hire door: the create form does not take it
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
                        :help "A short name for the org, as its directory spells it: lowercase letters, digits and underscores, starting with a letter, like rd_tax_services."}}
     name-schema]
    [:brief {:optional true
             :examples ["A six-person landscaping business. I want the house to run quotes, crew scheduling, invoicing and late payers."]
             :x-display {:widget "prose" :label "The brief"
                         :help "One paragraph in your own words: what the business does, and what you want the house to run."}}
     [:maybe [:string {:max 4000}]]]
    [:org_dir {:optional true
               :x-display {:raw true :label "The org's directory"
                           :help "Where the org's declarations will live. Leave it empty for orgs/ and the org's name."}}
     [:maybe [:string {:max 200}]]]
    [:base_follow {:optional true
                   :x-display {:label "Keep the branch current"
                               :help "Leave it empty for at_go_live: the branch follows its base only from go_live on. always: it follows every base move, and each move refilms the plan. when_org_dir_changes: it follows every base move, and only a move that changed the org's directory refilms the plan."
                               :choices {"at_go_live" "From go_live on"
                                         "always" "On every base move"
                                         "when_org_dir_changes" "On every base move, refilmed only when the org changed"}}}
     [:maybe [:enum "at_go_live" "always" "when_org_dir_changes"]]]]
   :actions
   {:ask
    {:from #{:drafting} :to :drafting
     :guards [the-name-is-snake-case]
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

    :log_workarounds
    (log-workarounds-door :drafting "Log workarounds" 16)

    ;; AFTER DRAFTING (ticket beb733af): a run that proposes, films and
    ;; simulates still logs what it worked around, in the state it is in.
    :log_workarounds_proposed
    (log-workarounds-door :proposed "Log workarounds while proposed" 17)

    :log_workarounds_simulated
    (log-workarounds-door :simulated "Log workarounds while simulated" 18)

    :log_workarounds_going_live
    (log-workarounds-door :going_live "Log workarounds while going live" 19)

    :propose
    {:from #{:drafting} :to :proposed
     :guards [the-name-is-snake-case no-error-finding-is-open a-hire-names-its-seat
              the-change-was-opened-for-this-plan the-plan-was-audited-at-its-head]
     :handler put-the-pull-request-up
     :input [:map
             [:change {:optional true :kind :change
                       :x-display {:label "The pull request"}}
              [:maybe :waymark/ref]]
             [:hire_seat {:optional true
                          :x-display {:label "The seat it hires"
                                      :help "For a hire plan, and required for it: the seed step factorykit.hire drafted on the plan's branch."}}
              [:maybe hire-seat-schema]]
             [:law_page {:optional true
                         :examples ["# The law of greenleaf\n\nThis is what you approve when you merge: each kind of record this org keeps, how it moves, what each move asks first, which moves wait for a person, and which seats work here and what they may spend."]
                         :x-display {:widget "prose" :label "The law"
                                     :help "The page factory10.law-text renders from the org's directory, for the person to read on the plan."}}
              [:maybe [:string {:max 40000}]]]]
     ;; :edit-shape — the first pull request onto a blank row is not an
     ;; edit of one. The named change is judged by
     ;; the-change-was-opened-for-this-plan (ticket b02c9808)
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
              schema/commit-sha]]
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
              [:string {:min 1 :max 500}]]
             [:findings {:optional true
                         :x-display {:hidden true
                                     :label "The film's warnings"}}
              [:vector {:max 20} finding-schema]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The film is on the plan for the owner to watch."}
     :display {:label "Attach the film" :order 4
               :description "Attach the film of the org's simulated first week"}}

    ;; A NEW HEAD WHILE FILMING (ticket 4d9311f1). The engine's hand
    ;; alone: the findings pass or the film pass voids a take whose head
    ;; the branch has left, and the director films the new head.
    :void_take
    {:from #{:filming} :to :proposed
     :guards [the-engine-records-the-findings]
     :handler void-the-take
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The new head"}}
              [:string {:min 7 :max 64}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The take is voided: the plan is proposed again, for the director to film its new head."}
     :display {:label "Take voided" :order 11
               :description "A new head on the branch voids the film waiting to be judged"}}

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
              schema/commit-sha]
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
     :guards [the-name-is-snake-case not-flipped the-take-is-of-the-head only-a-person-goes-live only-a-person-hires only-the-owner-decides]
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
              [:string {:min 1 :max 64}]]
             [:hired_seat {:optional true
                           :x-display {:hidden true :raw true
                                       :label "The hired seat"}}
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
              schema/commit-sha]]
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

    ;; A CHANGE MERGED BEFORE GO_LIVE (ticket 87fc7996). The sit mints a
    ;; fresh change from the base for a simulated or going_live plan
    ;; whose change is gone, and names it here; the plan is simulated,
    ;; for its seat to push :live true on the new branch.
    :relink_change
    {:from #{:simulated :going_live} :to :simulated
     :guards [the-sit-links-its-change]
     :handler relink-the-change
     :input [:map
             [:change {:kind :change
                       :x-display {:label "The pull request"}}
              :waymark/ref]]
     ;; :edit-shape — the engine names the change, nobody edits it
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The plan names its fresh change, and a yes for the merged one is retired."}
     :display {:label "Change linked again" :order 21
               :description "The sit names the fresh change it minted for a plan whose change merged"}}

    ;; A NEW HEAD IS FILMED AGAIN (ticket 87fc7996). The engine's hand
    ;; alone: the findings pass sends a simulated plan whose change's
    ;; head is not its take back to proposed, and the director films
    ;; the new head before go_live can merge it. A going_live plan whose
    ;; base move changed its org takes the same door, with `org_changed`
    ;; (ticket ce8a4e17).
    :refilm
    {:from #{:simulated :going_live} :to :proposed
     :guards [the-engine-records-the-findings]
     :handler refilm-the-head
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The new head"}}
              [:string {:min 7 :max 64}]]
             [:org_changed {:optional true
                            :x-display {:hidden true :raw true
                                        :label "The base move changed the org"}}
              :boolean]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The film is cleared: the plan is proposed again, for the director to film its new head."}
     :display {:label "Refilm" :order 13
               :description "A new head on a simulated plan's change, or a base move that changed a going_live plan's org, is filmed again before go_live"}}

    ;; A BASE MOVE THAT LEFT THE ORG ALONE (ticket ce8a4e17). The engine's
    ;; hand alone: the findings pass moves the take, the findings and the
    ;; owner's yes to the new head, and the merge waits on CI only.
    :carry_take
    {:from #{:going_live} :to :going_live
     :guards [the-engine-records-the-findings]
     :handler carry-the-take
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The new head"}}
              schema/commit-sha]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The take and the owner's yes are of the new head: the org there is the org that was filmed."}
     :display {:label "Take carried" :order 14
               :description "A base move that left the org alone keeps the film and the yes"}}

    ;; The same, for a simulated plan under base_follow
    ;; when_org_dir_changes: it stays simulated with its take.
    :carry_take_simulated
    {:from #{:simulated} :to :simulated
     :guards [the-engine-records-the-findings]
     :handler carry-the-take
     :input [:map
             [:head_sha {:examples ["5e469c6166edf6a3f28ae2e6bdb88b775025f14d"]
                         :x-display {:hidden true :raw true
                                     :label "The new head"}}
              schema/commit-sha]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The take is of the new head: the org there is the org that was filmed."}
     :display {:label "Take carried" :order 15
               :description "A base move that left the org alone keeps the film"}}

    ;; A HIRE TURNED DOWN (M4b). The owner refused the held offer, or it
    ;; expired: the engine retires the plan, and its gap is quiet a week.
    :decline_hire
    {:from #{:simulated} :to :retired
     :guards [the-engine-records-the-findings]
     :handler decline-the-hire
     :input [:map
             [:why {:x-display {:hidden true
                                :label "Why"}}
              [:string {:min 1 :max 240}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The hire plan is retired, and no hire plan opens for its gap for a week."}
     :display {:label "Hire declined" :order 13
               :description "The owner refused the offer, or let it lapse"}}

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
               a-plan-does-not-go-up-with-another-plans-change
               the-owner-answers
               a-stranger-does-not-answer
               an-agent-does-not-go-live-alone
               the-owner-goes-live
               an-unflipped-org-does-not-go-live
               no-hire-without-a-live-org
               a-live-org-opens-a-hire
               a-second-plan-does-not-take-a-held-name
               a-retired-plan-frees-its-name]})
