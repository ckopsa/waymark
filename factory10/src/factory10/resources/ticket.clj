(ns factory10.resources.ticket
  "The ticket: one ask of the software factory, as a row a person or
  a seat writes (docs/spec-ticket.md).

  WHY A KIND OF ITS OWN. The household's `task` (workqueue10) is a
  mirror over the family's authorities, and its lifecycle is the
  authority's word. The day job's work is not that: an ask here has a
  PARENT (the epic it is one piece of), BLOCKERS (the asks that must
  land first), a TYPE, and a body a seat reads before it builds. Put
  those on the family's queue and the family's feed would card the
  day job's structure, which `change` already refuses to do. So the
  factory keeps its own ask, beside its own `change` and `ci_run`.

  THE MACHINE IS SIX STATES. A ticket is born `draft`, and `groom` —
  a person's door — moves it to `open`, which is the queue. `blocked`
  waits on other tickets, `deferred` waits on a date; neither is an
  ending. `done` and `dropped` are the two endings, and both come back
  through `reopen` to `draft`, so neither is a tomb and a ticket that
  ended wrongly is groomed again before a seat sees it. THERE IS NO
  `in_progress`: a ticket is in progress when a `change` born from it
  is open, which is a fact the engine already holds and not a status a
  model sets and forgets.

  IN REVIEW IS THE SEVENTH, AND ONLY ITS CHANGE MOVES IT (ticket
  2e869934). A code seat's submit moves the ticket its change was born
  from `open -> in_review`: the work is out for review and the ticket
  is out of every walk. It is not done. The change's red or conflicted
  head, a close without a merge, a stall and a person's unstick send it
  back through `return` to `open`, so the seat that wrote the change
  wakes and walks it again with its change and feedback; the merge ends
  it through `land` with the pull request as its sentence, from any
  state that has not ended (ticket 3ec37f66). All three
  doors are the change's, inside its own transaction
  (`only-its-change-moves-it`), and no hand at the wire takes them.

  GROOMED IS A STATE, AND A SEAT CANNOT REACH IT. The owner's ruling,
  2026-09-26: a task is groomed before it is picked up. So the birth
  lands in `draft`, where nothing walks, and `groom` is walled for a
  model alone (repo_policy's shape: a person, the engine, or a
  delegate acting for a person). A seat that could groom could fill
  its own queue; a seat that cannot builds only what a person read
  and stood behind. `ungroom` sends a ticket back, and `restate`
  serves `draft` alone: a groomed statement is what the seat builds,
  so changing it is ungrooming it.

  READY IS THE DEFAULT FILTER, NOT A STATE. The collection a walker
  opens is `state=open`, and a draft, blocked or deferred ticket is
  out of it by construction. So the code seat walks `ticket`, one row
  at a time, and never reads a ticket it cannot work (spec-seat.md
  R-12.9).

  AN EPIC IS A TICKET WITH CHILDREN. No `epic` type: a parent stays
  `open` while its children are worked, and `children-are-finished`
  keeps a parent from completing over an open child. The tree is the
  `parent` ref, and the `children` link walks it back down.

  THE ENDINGS ASK FOR A SENTENCE. `complete` and `drop` take
  `close_reason`: what was done, or why it was let go. The record the
  next reader has is that sentence, so the door will not open on an
  empty one (ci_run's remedy, one kind over).

  REOPEN IS A PERSON'S DOOR. A reopen is a correction of an ending,
  and a seat that could reopen tickets could refill its own queue.
  `only-a-person-reopens` stops every agent hand; it is not
  grantable. An agent's reopen is HELD for its person's tap (the
  guard declares `:hold true`), and the person's Allow replays it; an
  agent with no person behind it is refused. The engine's own hand
  passes, for the same reason a merge completes a ticket with it.

  :nav :secondary, for change's reason: an ask of the software
  factory is the day job's work, not the family's."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.holds :as holds]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── what the row keeps ──────────────────────────────────────────────

(def detail-chars
  "The ceiling on a ticket's body. The beads this kind replaces ran to
  seven thousand characters of design in one row, and a seat reads the
  whole body before it builds — so the cap is above what the record
  holds and well under what a context can carry."
  20000)

(def ^:private unfinished
  "The states a child may NOT be in when its parent ends."
  #{:open :in_review :blocked :deferred})

(def ^:private ended
  "The states a blocker or a parent may NOT be in when it is named."
  #{:done :dropped})

(defn- state-of [row]
  (some-> (:state row) name keyword))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler restate-the-ticket [row inp _ctx]
  ;; The whole statement again, as repo_policy's restate: every field
  ;; the door collects lands, and the machine keeps the row where it
  ;; stands.
  (update row :data merge inp))

(defhandler rank-the-ticket [row inp _ctx]
  (assoc-in row [:data :priority] (:priority inp)))

(defhandler state-the-blockers [row inp _ctx]
  ;; The list is REPLACED, not appended. The door prefills the blockers
  ;; that stand, so a person states the whole set again — two spellings
  ;; of one list would be two lists.
  ;;
  ;; The state it was blocked FROM is kept, so the last blocker's ending
  ;; returns a draft to `draft` and not to the queue. A restatement from
  ;; `blocked` keeps what the first block wrote.
  (let [from (state-of row)]
    (cond-> (assoc-in row [:data :blocked_by] (vec (:blocked_by inp)))
      (#{:draft :open} from) (assoc-in [:data :blocked_from] (name from)))))

(defhandler clear-the-blockers [row _inp _ctx]
  ;; The transition log keeps who blocked what; the row says what
  ;; holds NOW, and an unblocked ticket is blocked by nothing.
  (-> row
      (assoc-in [:data :blocked_by] [])
      (assoc-in [:data :blocked_from] nil)))

(defhandler defer-the-ticket [row inp _ctx]
  (assoc-in row [:data :defer_until] (:defer_until inp)))

(defhandler resume-the-ticket [row _inp _ctx]
  (assoc-in row [:data :defer_until] nil))

(defn- still-waits-on
  "The blockers `waiter` still waits on once `ending` ends: the ending
  ticket leaves the list, and so does any other blocker that has ended
  or is gone — a list that named one would keep the ticket blocked by
  nothing, and the `block` door refuses to restate it."
  [waiter ending read']
  (into []
        (remove (fn [id]
                  (or (= ending (str id))
                      (let [b (read' :ticket (str id))]
                        (or (nil? b) (contains? ended (state-of b)))))))
        (get-in waiter [:data :blocked_by])))

(defn- release-the-waiters!
  "THE LAST BLOCKER'S ENDING UNBLOCKS (the loop that needs no person).
  Every blocked ticket whose `blocked_by` names the ticket that is
  ending is re-judged in the same transaction: with blockers left, the
  `block` door restates the shorter list; with none, the ticket goes
  back where it was blocked from — `unblock` into the queue, the same
  transition a person's tap makes, so a seat's wake on `unblock` fires
  as it always has, or `return_to_draft` for a draft nobody groomed.

  BEST-EFFORT, as the change's merge is: a waiter that refuses is said
  in the log, and the ending stands. A probe or a rehearsal carries no
  pen, and releases nothing."
  [row ctx]
  (let [find' (:find ctx)
        read' (:read ctx)
        invoke' (:invoke ctx)
        ending (str (:id row))]
    (when (and find' read' invoke')
      (doseq [waiter (find' :ticket {:state "blocked"} {:limit 500})
              :when (some #(= ending (str %))
                          (get-in waiter [:data :blocked_by]))
              :let [left (still-waits-on waiter ending read')
                    ;; `block` is fenced (its :edit implies it), so the
                    ;; version read here is named as an honest client
                    ;; would name it (worksheet/apply-invocations!)
                    fence {:if-match (inv/etag :ticket (:id waiter)
                                               (:version waiter))}]]
        (try
          (cond
            (seq left)
            (invoke' :ticket (:id waiter) :block {:blocked_by left} fence)
            (= "draft" (get-in waiter [:data :blocked_from]))
            (invoke' :ticket (:id waiter) :return_to_draft nil fence)
            :else
            (invoke' :ticket (:id waiter) :unblock nil fence))
          (catch Exception e
            (binding [*out* *err*]
              (println "factory10 ticket ending: the ticket" (:id waiter)
                       "was not released -" (ex-message e)))))))))

(defhandler close-the-ticket [row inp ctx]
  ;; One handler for both endings. The machine says which ending; the
  ;; handler writes the sentence, and releases what waited on it.
  (release-the-waiters! row ctx)
  (assoc-in row [:data :close_reason] (:close_reason inp)))

(defhandler reopen-the-ticket [row _inp _ctx]
  ;; The sentence that closed it stays in the log and leaves the row:
  ;; a reopened ticket reads as open work, not as work with a reason
  ;; it ended.
  (assoc-in row [:data :close_reason] nil))

;; ── the walls ───────────────────────────────────────────────────────
;;
;; Three read other tickets and are CONFORMANCE-tier: each declares
;; `:reads [:ticket]` and asks the ctx hook for the rows. None of them
;; stands on `reopen`, so that door's scenarios stay check-tier. A ctx with no
;; hook — the render probe — is answered with an ALLOW, which is the
;; framework's own posture (change's bench walls, one kind over): the
;; envelope advertises optimistically and the door judges again with a
;; real hook behind it. Their law is proved in factory10.ticket-test
;; over a fake hook. The fourth reads :principal and :within, both of
;; which the check tier answers, and its scenarios below are check-tier.

(defguardfn the-parent-is-open-at-birth
  {:judges [:parent]
   :reads [:ticket]
   :vars [:parent]
   :remedies [:ticket/reopen]
   :explain "The parent {parent} has ended, and a ticket born under an ended parent is work nobody will find. Reopen the parent first, or leave the parent empty."}
  [_row inp ctx]
  (let [read' (:read ctx)
        parent (some-> (:parent inp) str not-empty)]
    (if (or (nil? read') (nil? parent))
      (t/allow)
      (let [p (read' :ticket parent)]
        (cond
          (nil? p)
          (t/deny {:vars {:parent parent}
                   :errors {:parent ["ticket not found"]}})
          (contains? ended (state-of p))
          (t/deny {:vars {:parent parent}})
          :else (t/allow))))))

(defguardfn children-are-finished
  {:reads [:ticket]
   :vars [:count]
   :remedies [:ticket/complete :ticket/drop]
   :explain "{count} of this ticket's children are not finished. A parent ends after its children: complete or drop each one first, and then this door opens."}
  [row _inp ctx]
  (if-some [find' (:find ctx)]
    (let [children (find' :ticket {:parent (str (:id row))} {:limit 500})
          waiting (count (filter (comp unfinished state-of) children))]
      (if (pos? waiting)
        (t/deny {:vars {:count waiting}})
        (t/allow)))
    (t/allow)))

(defguardfn the-blockers-are-open-and-not-itself
  {:judges [:blocked_by]
   :reads [:ticket]
   :vars [:which]
   :remedies [:ticket/reopen]
   ;; :open is the closure acknowledgment (checks/check-closure): the
   ;; blockers are a list of refs, and no published constraint can say
   ;; which tickets are still open — the collection can, one GET away.
   :open "The blockers are tickets, and the open ones are the tickets collection under its default filter, one query away; no form can recite which of them have ended."
   :explain "A ticket waits on open work: {which}. Name blockers that are still open, and never the ticket itself."}
  [row inp ctx]
  (let [read' (:read ctx)
        self (str (:id row))
        named (map str (:blocked_by inp))]
    (cond
      (some #(= self %) named)
      (t/deny {:vars {:which "this ticket names itself"}
               :errors {:blocked_by ["a ticket cannot block itself"]}})
      (nil? read') (t/allow)
      :else
      (let [problem (some (fn [id]
                            (let [b (read' :ticket id)]
                              (cond
                                (nil? b) (str id " is not a ticket")
                                (contains? ended (state-of b))
                                (str id " has ended")
                                :else nil)))
                          named)]
        (if problem
          (t/deny {:vars {:which problem}
                   :errors {:blocked_by [problem]}})
          (t/allow))))))

(defguardfn a-person-or-their-delegate-grooms
  {:reads [:principal]
   :open "No door here changes this verdict. Grooming is a person's reading of an ask — that it is stated well enough to build as written — and a model alone does not stand behind its own statement. A seat that wants a ticket groomed says so where an agent may, and a person taps."
   :explain "A ticket is groomed by a person, or by a delegate acting for one under a grant the person approved. A seat that could groom could fill its own queue with asks nobody read."}
  [_row _inp ctx]
  ;; repo_policy's `a-person-or-their-delegate-states-the-policy`, one
  ;; kind over: the wall is against a model ALONE, and a delegate that
  ;; names whom it acts for is the person's hand.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (and (= :agent type) (str/blank? (str acts-for)))
      (t/deny)
      (t/allow))))

(defguardfn only-a-person-reopens
  {:reads [:principal :within]
   :hold true
   :explain "A reopen is the person's correction of an ending, so an agent's reopen is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written. A seat that could reopen tickets alone could refill its own queue."
   :open "No door clears this one. The call waits as a held_call for the person's tap, and a grant that opened it would let a seat write its own queue."}
  [row _inp ctx]
  ;; ci_run's `only-a-person-reclassifies`, one kind over: every hand
  ;; but an agent's passes, the engine's own actor included. An
  ;; agent's reopen is HELD (waymark10.holds): the router records it
  ;; for its person, and the one agent call this admits is the
  ;; engine's replay of the held call that person allowed. The held
  ;; row is read only when `:within` names one, which no scenario and
  ;; no wire request does, so the check tier's answer is the door's.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :ticket :reopen (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn only-an-ending-returns-a-ticket-to-draft
  {:reads [:within]
   :open "No door clears this one. A blocked draft goes back to draft when the last ticket it waits on ends, and the engine moves it then; a person who wants it sooner unblocks it into the queue, or states its blockers again."
   :explain "A blocked ticket returns to draft only when the last ticket it waits on is completed or dropped: the ending moves it, in the same transaction, and no hand does."}
  [_row _inp ctx]
  ;; `:within`, vocabulary § 6: the door opens for this kind's own
  ;; endings and for nobody's hand. The wire, the render probe and
  ;; every rehearsal answer nil, so it renders refused, which is true.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :ticket kind) (contains? #{:complete :drop :land :mend} action))
      (t/allow)
      (t/deny))))

(defguardfn only-its-change-moves-it
  {:reads [:within]
   :open "No door clears this one. A ticket goes out for review when its change is submitted, and comes back when that change goes red, closes, stalls or is unstuck; the change moves it then, and a person who wants it sooner works the change."
   :explain "A ticket under review is moved by the change it was built in and by no hand: the change's submit sends it out, its red head, close, stall or unstick sends it back, and its merge ends it."}
  [_row _inp ctx]
  ;; `only-an-ending-returns-a-ticket-to-draft`'s shape, one kind over:
  ;; the door opens inside a `change` door's own transaction and for
  ;; nobody's hand. The wire, the render probe and every rehearsal
  ;; answer nil, so it renders refused, which is true.
  (if (= :change (:kind (:within ctx)))
    (t/allow)
    (t/deny)))

(defguardfn only-the-base-pass-writes-this
  {:reads [:principal :within]
   :hide true
   :explain "The GitHub source's base pass writes a red base's heads and ends its ticket when the base is green. A person and a model read it."}
  ;; repo_policy's `the-engine-notes-the-source`, one kind over (ticket
  ;; ade81ae9): the engine's system hand alone, and a hidden door
  ;; answers 404 and says nothing. It opens only `:within` the base
  ;; pass (forge's `base-opts`), so the render probe, which carries no
  ;; `:within`, offers the engine nothing on a ticket under review.
  [_row _inp ctx]
  (if (and (= :system (:type (:principal ctx)))
           (= :repo_policy (:kind (:within ctx))))
    (t/allow)
    (t/deny)))

(defhandler note-a-red-head [row inp _ctx]
  ;; One more red head of the base this ticket was opened for, kept to
  ;; the last fifty.
  (update-in row [:data :red_heads]
             (fn [heads]
               (vec (take-last 50 (distinct (conj (vec heads)
                                                  (:red_head inp))))))))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Check-tier: no :given rows, and the one guard on the attempted door
;; reads :principal and nothing else, so `make check-factory` judges
;; them with no database.

(def ^:private a-done-ticket
  {:title "The code seat walks ticket rows"
   :detail "Switch the walk from task to ticket."
   :type "feature"
   :priority 1
   :repo "ckopsa/waymark"
   :blocked_by []
   :close_reason "Merged: github:ckopsa/waymark#41."})

(defscenario a-seat-does-not-reopen-a-ticket
  "A reopen is the person's correction of an ending. The hand that
   could refill its own queue is refused, and the refusal says where
   to say so instead."
  {:kind    :ticket
   :attempt :reopen
   :row     {:state :done :data a-done-ticket}
   :as      {:id "code-seat" :type :agent}
   :expect  {:refused :only-a-person-reopens
             :because "person's correction"}})

(defscenario the-person-reopens-a-ticket
  "And the door is really there for the person whose work it is — one
   tap, no grant and no ceremony."
  {:kind    :ticket
   :attempt :reopen
   :row     {:state :done :data a-done-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(def ^:private a-draft-ticket
  (dissoc a-done-ticket :close_reason))

(defscenario a-seat-does-not-groom-a-ticket
  "Grooming is a person's reading that the ask is stated well enough
   to build as written. A seat alone is refused, so the queue holds
   only what a person stood behind."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft :data a-draft-ticket}
   :as      {:id "code-seat" :type :agent}
   :expect  {:refused :a-person-or-their-delegate-grooms
             :because "groomed by a person"}})

(defscenario the-person-grooms-a-ticket
  "And the door is really there for the person — one tap."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft :data a-draft-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-finished-ticket-is-not-put-back-by-a-side-door
  "The machine refuses it with no guard behind the refusal: a done
   ticket has no unblock door, and the only way to move it is a
   person's reopen."
  {:kind    :ticket
   :attempt :unblock
   :row     {:state :done :data a-done-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:refused :out-of-state
             :because "Done"}})

(defscenario a-person-does-not-return-a-blocked-ticket-to-draft
  "The way back to draft from blocked is the last blocker's ending,
   made by the engine inside that ending. A hand at the wire is
   refused, and the refusal says what moves it instead."
  {:kind    :ticket
   :attempt :return_to_draft
   :row     {:state :blocked
             :data (assoc a-draft-ticket
                          :blocked_by ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]
                          :blocked_from "draft")}
   :as      {:id "colton" :type :person}
   :expect  {:refused :only-an-ending-returns-a-ticket-to-draft
             :because "last ticket it waits on"}})

(defscenario a-person-does-not-take-a-ticket-out-of-review
  "A ticket under review comes back when its change says so — red,
   closed, stalled or unstuck — and no hand at the wire moves it. The
   refusal says what moves it instead."
  {:kind    :ticket
   :attempt :return
   :row     {:state :in_review :data a-draft-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:refused :only-its-change-moves-it
             :because "moved by the change"}})

;; ── the fields, spelled once and read by three doors ────────────────

(def ^:private type-choices
  {"bug" "Something is wrong and should be fixed"
   "feature" "Something new, or something that should work differently"
   "task" "One piece of work, on the way to something larger"
   "chore" "Upkeep — nothing a person would notice, and it needs doing"})

(def ^:private stated-fields
  "What a person or a seat STATES about a ticket: the create door and
  the restate door collect the same four, because a restatement is
  the whole statement again."
  [[:title {:examples ["The code seat walks ticket rows instead of task rows"]
            :x-display
            {:label "What needs doing"
             :help "One line, the way you would say it out loud. It is the pull request's title when a seat builds it, so say the outcome and not the diagnosis."}}
    [:string {:min 1 :max 200}]]
   [:detail {:optional true
             :examples ["Switch `walk` on the code seat from task to ticket. The change born from the walk row keeps `ticket:<id>` in born_from, and the merge completes the ticket."]
             :x-display
             {:widget "prose"
              :teaser true
              :label "The how, and what done looks like"
              :help "Everything the builder needs and nothing they can read off the repository: the design, the acceptance, the files it touches, the traps. A seat reads this before it reads a line of code."}}
    [:maybe [:string {:max 20000}]]]
   [:type {:default "task"
           :x-display
           {:label "What kind of ask"
            :choices type-choices}}
    (into [:enum] (sort (keys type-choices)))]
   [:repo {:optional true
           :examples ["ckopsa/waymark"]
           :x-display
           {:raw true
            :label "The repository"
            :help "The repository this ask is built in, as GitHub spells it. A seat's bench filter names the same one, so a ticket for another repository never reaches its worktree."}}
    [:maybe [:string {:max 140}]]]])

(def ^:private birth-fields
  "What a birth may say beyond the statement: where it sits in the
  tree, what surfaced it, and how urgent it is."
  [[:priority {:default 2
               :examples [2]
               :x-display
               {:label "Priority (0 first, 4 last)"
                :help "The queue's own order. A walker takes the lowest number first, so 0 is the ask the house wants next and 4 is the one it can wait for."}}
    [:int {:min 0 :max 4}]]
   [:parent {:optional true :kind :ticket
             :x-display
             {:label "Part of"
              :help "The larger ask this one is a piece of. A parent stays open while its children are worked and ends after them."}}
    [:maybe :waymark/ref]]
   [:found_in {:optional true :kind :ticket
               :x-display
               {:label "Found while working on"
                :help "The ticket whose work surfaced this one, when there is one. It is how the record says where an ask came from."}}
    [:maybe :waymark/ref]]
   [:bead_id {:optional true
              :examples ["waymark-fp62.8"]
              :x-display
              {:raw true
               :label "Its id in beads, if it had one"
               :help "The id this ask carried in the beads tracker this kind replaced. Empty for a ticket born here."}}
    [:maybe [:string {:max 60}]]]])

(def ^:private engine-fields
  "What the DOORS write and no birth does: the blockers, the date, and
  the sentence an ending carries."
  [[:blocked_by {:default []
                 :kind :ticket
                 :x-display
                 {:label "Waits on"
                  :help "The tickets that must end before this one is worked. While any of them is open this ticket is blocked and out of the queue."}}
    [:vector :waymark/ref]]
   [:blocked_from {:optional true
                   :x-display
                   {:label "Blocked while"
                    :help "Where this ticket stood when it was blocked. When the last ticket it waits on ends it goes back there: a groomed ticket to the queue, a draft to draft."}}
    [:maybe [:enum "draft" "open"]]]
   [:defer_until {:optional true
                  :examples ["2026-11-19"]
                  :x-display
                  {:label "Deferred until"
                   :help "The day this ask comes back into the queue. Empty unless the ticket is deferred."}}
    [:maybe :waymark/date]]
   [:close_reason {:optional true
                   :examples ["Merged: github:ckopsa/waymark#41."]
                   :x-display
                   {:widget "prose"
                    :label "How it ended"
                    :help "One sentence: what was done, or why it was let go. It is what the next reader has."}}
    [:maybe [:string {:max 480}]]]
   ;; ticket ade81ae9: the heads of a red base, written by the base pass
   ;; on the one ticket it opened for that repository
   [:red_heads {:optional true
                :examples [["1f0c2d3e4a5b60718293a4b5c6d7e8f901234567: gate"]]
                :x-display
                {:raw true
                 :label "Red heads of the base"
                 :help "Each head of the base branch that went red while this ticket was open, with its red checks. Empty for a ticket the engine did not open."}}
    [:maybe [:vector [:string {:max 400}]]]]])

(def ^:private close-input
  [:map
   [:close_reason
    {:examples ["Merged: github:ckopsa/waymark#41."]
     :x-display
     {:widget "prose"
      :label "How it ended"
      :help "One sentence for the next reader: what was done, or why this is let go. Say the outcome — merged, superseded by, no longer wanted because — and not the diagnosis."}}
    [:string {:min 1 :max 480}]]])

;; ── :ticket — one ask of the factory ────────────────────────────────

(defresource ticket
  {:kind :ticket
   :plural "tickets"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   :states [:draft :open :in_review :blocked :deferred :done :dropped]
   :initial :draft
   ;; NO TOMB. Both endings come back through `reopen`, a person's
   ;; door, to `draft`: a ticket ended wrongly is groomed again.
   :terminal #{}
   ;; `:over` names the two endings apart: a done ticket is the deed
   ;; this kind is graded by, a dropped one is work the house let go.
   ;; `draft`, `blocked` and `deferred` are in neither list — they are
   ;; waiting, not over, and their doors stay open.
   :over {:accomplished #{:done} :let-go #{:dropped}}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :display {:title "{data.title}"}
   :filterable {:state #{:eq :in}
                :type #{:eq :in}
                :priority #{:eq :range}
                :parent #{:eq :set}
                :found_in #{:eq}
                :repo #{:eq}
                :bead_id #{:eq :set}}
   ;; THE QUEUE IS THE COLLECTION UNDER ITS DEFAULT FILTER: a walker
   ;; opens /api/tickets and gets the work that is READY — groomed,
   ;; not blocked, not deferred — lowest priority number first.
   :default-filters {:state "open"}
   :sortable {:fields [:priority :created_at] :default "priority"}
   :schema (into [:map] (concat stated-fields birth-fields engine-fields))
   ;; THE BIRTH IS THE STATEMENT AND WHERE IT SITS. The blockers, the
   ;; date and the ending are on no birth: a ticket is born a draft,
   ;; and the doors below are how it becomes ready and stops being.
   :create-schema (into [:map] (concat stated-fields birth-fields))
   :create-guards [the-parent-is-open-at-birth]
   :actions
   {:restate
    {:from #{:draft} :to :draft
     :input (into [:map] stated-fields)
     :handler restate-the-ticket
     :record true
     :edit {:prefill [:title :detail :type :repo]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restate" :order 2
               :description "Say what needs doing again, whole"}}

    ;; GROOMING IS THE PERSON'S TAP. A draft becomes the queue's when
    ;; a person read it and stands behind it as written. The way back
    ;; is `ungroom`, so a statement that needs work leaves the queue
    ;; before it changes.
    :groom
    {:from #{:draft} :to :open
     :guards [a-person-or-their-delegate-grooms]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Groom" :style :primary :order 1
               :description "It is stated well enough to build as written — into the queue"}}

    :ungroom
    {:from #{:open} :to :draft
     :guards [a-person-or-their-delegate-grooms]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Back to draft" :order 9
               :description "Out of the queue — the statement needs work before a seat builds it"}}

    :prioritize
    {:from #{:open} :to :open
     :input [:map
             [:priority {:examples [1]
                         :x-display
                         {:label "Priority (0 first, 4 last)"
                          :help "The queue's own order: 0 is what the house wants next."}}
              [:int {:min 0 :max 4}]]]
     :handler rank-the-ticket
     :record true
     :edit {:prefill [:priority]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Prioritize" :order 3
               :description "Move this ask up or down the queue"}}

    ;; THE BLOCKERS, STATED WHOLE. From `draft` or `open` the door
    ;; blocks; from `blocked` it restates the set. One door, because
    ;; all three land in `blocked`. A person's `unblock` lands in `open`;
    ;; the last blocker's ENDING returns the ticket where it was blocked
    ;; from (`blocked_from`), so a draft nobody groomed goes back to
    ;; `draft`. Two ways back to two states is why this door is one-way
    ;; and not reversible.
    :block
    {:from #{:draft :open :blocked} :to :blocked
     :input [:map
             [:blocked_by {:kind :ticket
                           :x-display
                           {:label "Waits on"
                            :help "Every ticket that must end before this one is worked. State the whole set: this replaces the list, it does not add to it."}}
              [:vector {:min 1 :max 50} :waymark/ref]]]
     :guards [the-blockers-are-open-and-not-itself]
     :handler state-the-blockers
     :edit {:prefill [:blocked_by]}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This ticket leaves the queue until the tickets it waits on end. When the last of them ends it goes back where it stood — the queue, or draft for a draft — and a person's unblock lands it in the queue sooner."}
     :display {:label "Blocked by" :order 4
               :description "Wait on other tickets — this one leaves the queue until they end"}}

    :unblock
    {:from #{:blocked} :to :open
     :handler clear-the-blockers
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Unblock" :style :primary :order 1
               :description "Back into the queue — nothing holds this one now"}}

    ;; THE ENGINE'S WAY BACK FOR A DRAFT. The last blocker's ending
    ;; opens it (release-the-waiters!), and no hand at the wire does:
    ;; a draft was never groomed, so its blockers ending does not put
    ;; it in the queue.
    :return_to_draft
    {:from #{:blocked} :to :draft
     :guards [only-an-ending-returns-a-ticket-to-draft]
     :handler clear-the-blockers
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Return to draft" :order 10
               :description "The last ticket it waited on ended — back to draft, to be groomed"}}

    :defer
    {:from #{:open} :to :deferred
     :input [:map
             [:defer_until {:examples ["2026-11-19"]
                            :x-display
                            {:label "Until"
                             :help "The day this ask comes back into the queue."}}
              :waymark/date]]
     :handler defer-the-ticket
     :edit {:prefill [:defer_until]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Defer" :order 5
               :description "Not now — take it out of the queue until a day"}}

    :resume
    {:from #{:deferred} :to :open
     :handler resume-the-ticket
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :style :primary :order 1
               :description "Back into the queue now"}}

    ;; THE TWO ENDINGS, from `draft` or `open`. A draft may already be
    ;; done, or not wanted, before anyone groomed it. A blocked or
    ;; deferred ticket is not finished; unblock or resume it, and then
    ;; end it. Both are one-way: `reopen` lands in `draft` and never in
    ;; `open`, because an ending that was wrong is an ask to read again.
    :complete
    {:from #{:draft :open} :to :done
     :input close-input
     :guards [children-are-finished]
     :handler close-the-ticket
     ;; the sentence is composed, so it is drafted (change's `stall`):
     ;; a mis-click must not discard what was typed
     :edit {:draft {:shared true :live true}}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the ending on the record, with its sentence, and a ticket that waited only on this one goes back where it was blocked from. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Complete" :style :primary :order 6
               :description "The work is done — say what was done"}}

    :drop
    {:from #{:draft :open} :to :dropped
     :input close-input
     :guards [children-are-finished]
     :handler close-the-ticket
     :edit {:draft {:shared true :live true}}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the ending on the record, with its sentence, and a ticket that waited only on this one goes back where it was blocked from. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Drop" :style :danger :order 7
               :description "Let this go — say why"}}

    ;; ── THE CHANGE'S THREE DOORS (ticket 2e869934) ───────────────────
    ;; Walked by the change born from this ticket, inside its own
    ;; door, and by no hand: see `only-its-change-moves-it`. None is
    ;; fenced, because the change names no version it read.
    :review
    {:from #{:open} :to :in_review
     :guards [only-its-change-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The change built for this ticket was submitted, so the ticket leaves the queue while its pull request is reviewed. Its change sends it back if the checks go red, and its merge ends it."}
     :display {:label "Out for review" :order 11
               :description "Its change was submitted — out of the queue while the pull request is reviewed"}}

    ;; one door back for every way a review ends without a merge, so a
    ;; seat's wake_on hears one action: `return`, not `resume` —
    ;; `resume` is a person's door out of `deferred`, and a wake on it
    ;; would read a person's not-now as the checks' red
    :return
    {:from #{:in_review} :to :open
     :guards [only-its-change-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Its change went red, closed without a merge, stalled or was put back to work, so the ticket is in the queue again and the seat that wrote the change walks it next. The change's next submit sends it out for review again."}
     :display {:label "Back from review" :order 12
               :description "Its change went red, closed, stalled or was put back to work — into the queue again"}}

    ;; a stall's way back (ticket 6bdaf6fe): the seat said it cannot
    ;; build this as written, so the ticket leaves the queue for draft,
    ;; where a person reads the stall and grooms it again — and a queue
    ;; of tickets beside stuck changes no longer wakes the seat
    :shelve
    {:from #{:open :in_review} :to :draft
     :guards [only-its-change-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The seat stalled the change built for this ticket, so the ticket leaves the queue for draft. A person's groom puts it back, and the next sit puts its change back to work."}
     :display {:label "Stalled" :order 17
               :description "Its change stalled — back to draft, to be groomed again"}}

    ;; the merge's ending, from every state that has not ended (ticket
    ;; 3ec37f66): `in_review` most days, `open` for a change a person
    ;; merged while it was red, and `draft`, `blocked` or `deferred`
    ;; for a ticket a stall, a groomer or a person moved while its pull
    ;; request waited green. GitHub merged it whatever the queue says.
    ;; Not `complete`: that door is a hand's, and fenced by its draft.
    :land
    {:from #{:draft :open :in_review :blocked :deferred} :to :done
     :input close-input
     :guards [only-its-change-moves-it children-are-finished]
     :handler close-the-ticket
     ;; change's `stick`, one kind over: only the engine walks this
     ;; door and nobody composes the sentence in a box, and an `:edit`
     ;; would fence a door the change reaches with no version in hand
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Its pull request merged, and that is the ending on the record, with the pull request as its sentence. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Merged" :order 13
               :description "Its pull request merged — the work is done"}}

    ;; ── THE BASE PASS'S THREE DOORS (ticket ade81ae9) ──────────────────
    ;; Hidden, and the engine's hand alone. A later red head of the base
    ;; is written on the one open ticket (a self-loop, spelled once for
    ;; each state it serves), and a green base ends it with `mend`,
    ;; whether or not its own change merged.
    :note_red
    {:from #{:open} :to :open
     :guards [only-the-base-pass-writes-this]
     :handler note-a-red-head
     :input [:map
             [:red_head {:x-display {:hidden true :raw true
                                     :label "The red head"}}
              [:string {:min 1 :max 400}]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Red head noted" :order 14
               :description "The base went red again on a new head"}}

    :note_red_in_review
    {:from #{:in_review} :to :in_review
     :guards [only-the-base-pass-writes-this]
     :handler note-a-red-head
     :input [:map
             [:red_head {:x-display {:hidden true :raw true
                                     :label "The red head"}}
              [:string {:min 1 :max 400}]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Red head noted" :order 15
               :description "The base went red again on a new head"}}

    :mend
    {:from #{:draft :open :in_review :blocked :deferred} :to :done
     :input close-input
     :guards [only-the-base-pass-writes-this]
     :handler close-the-ticket
     ;; `land`'s reasons: only the engine walks it, nobody composes the
     ;; sentence in a box, and it reaches the row with no version in hand
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The base branch is green again, and that is the ending on the record. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Base green again" :order 16
               :description "The base branch this ticket was opened for is green again"}}

    :reopen
    {:from #{:done :dropped} :to :draft
     :guards [only-a-person-reopens]
     :handler reopen-the-ticket
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 8
               :description "It ended wrongly — back to draft, to be groomed again"}}}
   :links [{:rel "parent" :kind :ticket
            :href "/api/tickets/{data.parent}"
            :summary "The larger ask this one is a piece of"}
           {:rel "children" :kind :ticket
            :href "/api/tickets?parent={id}&state="
            :summary "The pieces of this ask, in every state"}
           {:rel "found_in" :kind :ticket
            :href "/api/tickets/{data.found_in}"
            :summary "The ticket whose work surfaced this one"}]
   :deviations
   ["`restate` serves `draft` alone and `prioritize` serves `open` alone. A v10 action declares one `:to`, so a self-loop that served every waiting state would be several doors with one handler (change's `observe`/`observe_submitted`, the recorded precedent). A groomed statement is what the seat builds, so changing it is `ungroom` and then `restate`; a blocked or deferred ticket is ranked when it returns to the queue, which is where its rank matters."
    "`complete`, `drop` and `block` are one-way, not reversible. Each leaves from more than one state and its reverse lands in one (`reopen` in `draft`, `unblock` in `open`), and checks/check-reversible asks a reversible door for a way back to each `:from`. The way back is real in every case, and the `:one-way` sentence names it."
    "`reopen` does not read the parent. A child reopened under an ended parent leaves that parent done over open work, and a person reopens the parent next; the birth door refuses the same shape (`the-parent-is-open-at-birth`). A guard on `reopen` that read the parent would take that door's scenarios out of the check tier, and the person-wall on it is the law this kind is graded by."]
   :scenarios [a-seat-does-not-groom-a-ticket
               the-person-grooms-a-ticket
               a-seat-does-not-reopen-a-ticket
               the-person-reopens-a-ticket
               a-finished-ticket-is-not-put-back-by-a-side-door
               a-person-does-not-return-a-blocked-ticket-to-draft
               a-person-does-not-take-a-ticket-out-of-review]})
