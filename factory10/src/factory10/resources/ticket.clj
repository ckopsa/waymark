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
  head, a close without a merge and a person's unstick send it back
  through `return` to `open`, so the seat that wrote the change wakes
  and walks it again with its change and feedback; a stall sends it
  through `shelve` to `draft`, where a person grooms it again (ticket
  6bdaf6fe); the merge ends it through `land` with the pull request as
  its sentence, from any state that has not ended (ticket 3ec37f66).
  All four doors are the change's, inside its own transaction
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

  AN EPIC NAMES ITS SHOWCASE (ticket cbf84f80). `showcase` is the
  scene that makes a person want the work: a film for what a person
  sees, a before/after text for work that makes the agent better. A
  ticket whose title starts `[epic]` is not groomed without one
  (`an-epic-names-its-showcase`), and a child shows its parent's scene
  read-only in `parent_scene`, so plumbing shows which scene it serves.

  AN EPIC ENDS ON ITS EVIDENCE (ticket 056ac769). An epic is done when
  its scene can be watched or read, not when its last pull request
  merges. `showcase.evidence` holds a `film_url` or a `scene_ref`, and
  `complete` refuses an epic whose evidence does not match its format
  (`an-epic-shows-its-evidence`). `drop` asks for none.

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
            [factorykit.ask :as ask]
            [waymark10.dsl :refer [defguard defguardfn defhandler defresource
                                   defscenario refuse]]
            [waymark10.holds :as holds]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.invoke :as inv]
            [waymark10.server.roles :as roles]
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

;; ── its change link (step 3b, ticket b220009b) ──────────────────────

(def ^:private named-link
  "The doors this ticket names for the kit's change link: the kit says
  when the ticket follows its change, this says which door. The merge
  ends it through `land`, this kind's own door, and its ending closes a
  PR-less change through change `supersede`, which writes
  `superseded_by`."
  {:review-door :review
   :return-door :return
   :merged-door :land
   :close-change-door :supersede
   :close-change-input (fn [sentence] {:superseded_by sentence})
   :rework-door :rework
   :rework-submitted-door :rework_submitted
   :words {:open "No door clears this one. A ticket goes out for review when its change is submitted, and comes back to the queue when that change goes red, closes or is unstuck; a stall sends it to draft instead, to be groomed again. The change moves it then, and a person who wants it sooner works the change."
           :explain "A ticket under review is moved by the change it was built in and by no hand: the change's submit sends it out, its red head, close or unstick sends it back to the queue, its stall sends it to draft, and its merge ends it."}})

(def link
  "The ticket's change link, which the change kind walks."
  (ask/link :ticket named-link))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler restate-the-ticket [row inp _ctx]
  ;; The whole statement again, as repo_policy's restate: every field
  ;; the door collects lands, and the machine keeps the row where it
  ;; stands.
  (update row :data merge inp))

(declare close-the-parents-stuck-changes!)

(defhandler reparent-the-ticket [row inp ctx]
  ;; the new parent is not built (ticket 898caffd)
  (close-the-parents-stuck-changes! (:parent inp) ctx)
  ;; nil clears it: the ticket is a piece of nothing
  (assoc-in row [:data :parent] (:parent inp)))

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
  ;;
  ;; `then` says where a DRAFT goes instead (ticket cca6b000): `open`
  ;; writes `blocked_from` as a groomed ticket's block does, so the last
  ;; blocker's ending takes `unblock` and the seat wakes. A ticket that
  ;; stood in the queue stays bound for it whatever `then` says; the way
  ;; out of the queue is `ungroom`.
  (let [from (state-of row)
        was (get-in row [:data :blocked_from])
        then (some-> (:then inp) name)
        stood (cond
                (= :open from) "open"
                (= :draft from) (or then "draft")
                (= "open" was) "open"
                :else (or then was))]
    ;; `then` is kept beside it, so a restatement that names only the
    ;; blockers prefills what the first block said
    (cond-> (assoc-in row [:data :blocked_by] (vec (:blocked_by inp)))
      stood (assoc-in [:data :blocked_from] stood)
      stood (assoc-in [:data :then] stood))))

(defhandler state-the-merge-order [row inp _ctx]
  ;; `block`'s rule, one field over: the list is REPLACED, and an empty
  ;; one says the change merges when it is green.
  (assoc-in row [:data :merge_after] (vec (:merge_after inp))))

(defn- changes-born-from
  "Every change born from this ticket that stands in one of `states`.
  The adoption writes GitHub's id over `change_id`, so a change with a
  pull request is found by its repository and read by its `born_from`."
  [row states find']
  (ask/its-changes link row states find'))

(defn- put-its-change-back-to-work!
  "The kit's (`factorykit.ask/back-to-work!`, ticket 4b2740db): every
  stuck change born from this ticket walks `rework`, or
  `rework_submitted` with a pull request (tickets 9ace68fb, 4363c63b),
  and the ticket follows a pull request back under review through
  `rejoin_review` (ticket 7e01dbe5). A change that refuses refuses the
  ticket's `action`, naming the change's unstick door. A follow-up
  that refuses leaves the ticket open, where the sit leaves it out
  (ticket 6ca380da)."
  [row action ctx]
  (ask/back-to-work! link row action ctx))

(defhandler groom-the-ticket [row inp ctx]
  ;; a priority given lands with the groom, in the one transition; none
  ;; given keeps the stored one (ticket f7420404)
  (put-its-change-back-to-work! row :groom ctx)
  (cond-> row
    (some? (:priority inp)) (assoc-in [:data :priority] (:priority inp))))

(defhandler clear-the-blockers [row _inp _ctx]
  ;; The transition log keeps who blocked what; the row says what
  ;; holds NOW, and an unblocked ticket is blocked by nothing.
  (-> row
      (assoc-in [:data :blocked_by] [])
      (assoc-in [:data :blocked_from] nil)
      (assoc-in [:data :then] nil)))

(defhandler unblock-the-ticket [row inp ctx]
  ;; `clear-the-blockers`, and the ticket is in the queue again
  (put-its-change-back-to-work! row :unblock ctx)
  (clear-the-blockers row inp ctx))

(defhandler defer-the-ticket [row inp _ctx]
  (assoc-in row [:data :defer_until] (:defer_until inp)))

(defhandler resume-the-ticket [row _inp ctx]
  (put-its-change-back-to-work! row :resume ctx)
  (assoc-in row [:data :defer_until] nil))

(defn- release-the-waiters!
  "The kit's (`factorykit.ask/release-the-waiters!`, ticket 718f997f),
  for this kind."
  [row ctx]
  (ask/release-the-waiters! :ticket row ctx))

(defn- finish-the-parent!
  "THE LAST CHILD'S ENDING ENDS A PARENT ALREADY MERGED (ticket
  499bcd72). A parent's pull request that merged while a child was open
  could not `land` it, so the merge wrote `merged_change` on it and left
  it in review. When the child that is ending is the last unfinished
  one, the parent walks `finish` in the same transaction, with the pull
  request as its sentence. A parent with no merged change is left alone.

  BEST-EFFORT, as `release-the-waiters!` is: a refusal is said in the
  log, and the child's ending stands."
  [row ctx]
  (let [find' (:find ctx)
        read' (:read ctx)
        invoke' (:invoke ctx)
        ending (str (:id row))
        parent (some-> (get-in row [:data :parent]) str not-empty)]
    (when (and find' read' invoke' parent)
      (when-some [p (read' :ticket parent)]
        (let [merged (some-> (get-in p [:data :merged_change]) str not-empty)]
          (when (and merged
                     (= :in_review (state-of p))
                     ;; the ending child is not written yet, so it is
                     ;; left out by its id
                     (not-any? (fn [c]
                                 (and (not= ending (str (:id c)))
                                      (contains? unfinished (state-of c))))
                               (find' :ticket {:parent parent} {:limit 500})))
            (try
              (invoke' :ticket parent :finish
                       {:close_reason (str "Merged: " merged "; children done.")})
              (catch Exception e
                (binding [*out* *err*]
                  (println "factory10 ticket ending: the parent" parent
                           "was not finished -" (ex-message e)))))))))))

(defn- clip
  "`s`, cut to the 500 characters the change's doors take."
  [s]
  (let [s (str s)] (subs s 0 (min 500 (count s)))))

(defn- merged-address
  "The pull request a merge's sentence names — `Merged: <address>.` or
  `Merged: <address>; children done.` — else nil."
  [close-reason]
  (some-> (re-find #"^Merged: (\S+)" (str close-reason))
          second
          (str/replace #"[.;]+$" "")
          not-empty))

(defn ending-sentence
  "What a change its ticket's ending closed names as `superseded_by`
  (ticket 458d65c5): the pull request that merged, when a merge ended
  the ticket, as the merge's own close of a duplicate names it; else
  the ending and the sentence the ticket ended with."
  [ending close-reason]
  (or (merged-address close-reason)
      (clip (str "ticket " (name ending) ": " close-reason))))

(defn- address-of
  "The pull request's url, else its id: what a merge's sentence names."
  [change]
  (or (not-empty (str (get-in change [:data :url])))
      (not-empty (str (get-in change [:data :change_id])))))

(defn- close-its-unmerged-changes!
  "A TICKET THAT ENDS TAKES ITS UNMERGED CHANGES WITH IT (ticket
  458d65c5). A change left open, stuck or failing beside an ended
  ticket was a queue nobody would clear, and one of them was picked up
  and merged after its ticket was dropped. So every such change born
  from the ticket with no pull request walks, in the same transaction,
  the link's close door — `supersede` to `closed` — naming the ending
  (`factorykit.ask/close-its-changes!`). A change with a pull
  request is GitHub's to end (`no-pull-request-to-close`): it is left,
  and a submitted one's `adoption_note` says its ticket ended. The
  change whose merge IS this ending is the one the sentence names, and
  is left for its merge.

  BEST-EFFORT, as `release-the-waiters!` is: a change that refuses is
  said in the log, and the ending stands."
  [row ending inp ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)
        reason (str (:close_reason inp))
        merged (merged-address reason)
        sentence (ending-sentence ending reason)]
    (when (and find' invoke')
      (doseq [change (changes-born-from row ["submitted"] find')
              :when (and (not= merged (address-of change))
                         (some? (get-in change [:data :number])))]
        (try
          (invoke' :change (str (:id change)) :note_adoption
                   (cond-> {:adoption_note
                            (clip (str "Its ticket ended (" sentence
                                       "); close this pull request at GitHub."))}
                     (get-in change [:data :unadopted_since])
                     (assoc :unadopted_since
                            (str (get-in change [:data :unadopted_since])))))
          (catch Exception e
            (binding [*out* *err*]
              (println "factory10 ticket ending: the change" (:id change)
                       "was not noted -" (ex-message e)))))))
    (ask/close-its-changes! link row sentence
                            #(= merged (address-of %)) ctx)))

(defn- close-the-parents-stuck-changes!
  "A PARENT IS NOT BUILT (ticket 898caffd). A ticket that takes a child
  ends after its children, so a stuck change born from it before it had
  one is work nobody will finish: it walks the link's close door, in
  the same transaction, with a sentence that says why, and leaves the
  stuck list. Only a stuck change is closed; a live one is its seat's.

  BEST-EFFORT, as the ending's close is."
  [parent-id ctx]
  (let [read' (:read ctx)]
    (when-some [parent (when (and read' (some? parent-id))
                         (try (read' :ticket (str parent-id))
                              (catch Exception _ nil)))]
      (ask/close-its-changes!
       link parent
       "Its ticket has children now: a parent is not built, it ends after them."
       #(not= "stuck" (some-> (:state %) name))
       ctx))))

;; ── a drop is the decline (ticket 775b6427) ─────────────────────────

(def ^:private default-domain
  "The domain of a ticket, and of a seat, that stores none."
  "factory")

(defn- turn-the-asker-back!
  "A ticket asked of another domain names the ticket that waits for it
  in `needed_by`. When it is dropped, that ticket goes to `draft`
  through `turn_back`, with the decline as its `shelved_because`, so
  the mayor that asked plans again. It runs before
  `release-the-waiters!`, which would put the asker in the queue.

  BEST-EFFORT, as the release is. An asker under review, deferred or
  ended is left where it stands."
  [row inp ctx]
  (let [read' (:read ctx)
        invoke' (:invoke ctx)
        asker (when read'
                (some->> (get-in row [:data :needed_by]) str not-empty
                         (read' :ticket)))]
    (when (and invoke' asker
               (contains? #{:draft :open :blocked} (state-of asker)))
      (let [why (str "declined by "
                     (or (some-> (get-in row [:data :domain]) str not-empty)
                         default-domain)
                     ": " (:close_reason inp))]
        (try
          (invoke' :ticket (:id asker) :turn_back
                   {:shelved_because (subs why 0 (min 480 (count why)))}
                   {:if-match (inv/etag :ticket (:id asker) (:version asker))})
          (catch Exception e
            (binding [*out* *err*]
              (println "factory10 ticket drop: the ticket" (:id asker)
                       "was not turned back -" (ex-message e)))))))))

(defhandler close-the-ticket [row inp ctx]
  ;; One handler for every ending but the drop (`drop-the-ticket`). The
  ;; machine says which ending; the handler writes the sentence,
  ;; releases what waited on it, and closes its unmerged changes.
  (release-the-waiters! row ctx)
  (finish-the-parent! row ctx)
  (close-its-unmerged-changes! row :done inp ctx)
  (assoc-in row [:data :close_reason] (:close_reason inp)))

(defhandler drop-the-ticket [row inp ctx]
  ;; `close-the-ticket`, with its changes naming the drop; the ticket
  ;; that asked for this one is turned back first
  (turn-the-asker-back! row inp ctx)
  (release-the-waiters! row ctx)
  (finish-the-parent! row ctx)
  (close-its-unmerged-changes! row :dropped inp ctx)
  (assoc-in row [:data :close_reason] (:close_reason inp)))

(defhandler reopen-the-ticket [row _inp _ctx]
  ;; The sentence that closed it stays in the log and leaves the row:
  ;; a reopened ticket reads as open work, not as work with a reason
  ;; it ended.
  (assoc-in row [:data :close_reason] nil))

;; ── the walls ───────────────────────────────────────────────────────
;;
;; The parent's wall and the children's are DECLARATIVE
;; (docs/spec-related-row-law.md §7): a `follow` and a `none`, whose
;; reads the engine derives and a scenario's :given rows answer. A ctx
;; with no hook — the render probe — leaves the verdict unknown, which
;; is pending and so advertised: the envelope advertises optimistically
;; and the door judges again with a real hook behind it. The walls that
;; walk the parent chain and the waits read other tickets in code and
;; declare `:reads [:ticket]`; the two on `reparent` say `:check-tier
;; :given`, so that door's scenarios need no database. None of them
;; stands on `reopen`, so that door's scenarios stay check-tier. Their
;; law is proved in factory10.ticket-test over a fake hook. A wall that
;; reads only :principal and :within is check-tier, and so are its
;; scenarios below.

(defguard the-parent-is-open-at-birth
  (refuse "The parent {parent} has ended, and a ticket born under an ended parent is work nobody will find. Reopen the parent first, or leave the parent empty.")
  '(or (not (is-set (input :parent)))
       (and (is-set (follow (input :parent) :id))
            (not= (follow (input :parent) :state) "done")
            (not= (follow (input :parent) :state) "dropped")))
  {:remedies [:ticket/reopen]
   :errors {:parent "not an open ticket"}})

;; THE REFUSAL NAMES ONE CHILD (ticket 20ee6b97): the oldest unfinished
;; child by `created_at`, as evidence `c`, and both remedies bind its
;; id. A bare remedy on the ticket's own kind falls back to the refused
;; row, so a pursuit of a parent's `complete` tried the parent again and
;; met nothing but cycles. One child is enough: a re-plan after each
;; move finds the next. The count is over every child, as it was; the
;; child named is one the CALLER can see — the engine binds evidence
;; among the rows the grant on the ctx admits, and a ctx with no grant
;; sees them all — so a refusal never names a row its reader could not
;; open, and `:hidden` is the sentence that says so. The states are
;; `unfinished`, spelled as the law's words.

(defguard children-are-finished
  (refuse "{count} of this ticket's children are not finished. The oldest is \"{c.title}\". A parent ends after its children: complete or drop each one first, and then this door opens.")
  '(none [c :ticket {:parent (= (self))
                     :state (in "open" "in_review" "blocked" "deferred")}])
  {:remedies [{:door :ticket/complete :id '(evidence :c)}
              {:door :ticket/drop :id '(evidence :c)}]
   :vars {:count '(count [n :ticket {:parent (= (self))
                                     :state (in "open" "in_review" "blocked" "deferred")}])}
   :hidden {:c "They are outside what you can see."}})

;; A PARENT IS NOT BUILT (ticket 898caffd): a ticket with a child in any
;; state ends after its children, so it never enters the queue. The way
;; is `block`, on the children.

(defguard a-parent-is-not-groomed
  (refuse "This ticket has pieces under it; block it on them instead, so it ends after them.")
  '(none [c :ticket {:parent (= (self))}])
  {:remedies [:ticket/block]})

;; NO DEPENDENCY MAY MAKE A CYCLE (ticket d069bc3b). A ticket waits on
;; two kinds of other ticket: its blockers, to be worked, and its
;; `merge_after`, to merge. Either edge can close a loop with the other
;; — A blocked by B while B merges after A holds both for ever — so one
;; walk follows the union of both from the tickets a write names, and
;; the write is refused when the walk comes back to the ticket written.
;; An ended ticket waits on nothing, so the walk does not go through it.

(def ^:private walk-limit
  "The most tickets one cycle walk reads."
  500)

(defn- waits-of
  "The ids ticket `t` waits on, to be worked or to merge."
  [t]
  (when-not (contains? ended (state-of t))
    (map str (concat (get-in t [:data :blocked_by])
                     (get-in t [:data :merge_after])))))

(defn cycle-path
  "The path by which the tickets `named` lead back to `self`, through
  every ticket's blockers and `merge_after`, as ids from `self` to
  `self` — or nil when none does. Breadth first, so the path is a
  shortest one. `read'` is the ctx hook."
  [self named read']
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                     (map (fn [id] [self (str id)]))
                     (distinct named))
         seen #{}]
    (when-some [path (peek queue)]
      (let [id (peek path)]
        (cond
          (= self id) path
          (contains? seen id) (recur (pop queue) seen)
          :else
          (let [t (when (< (count seen) walk-limit) (read' :ticket id))]
            (recur (into (pop queue) (map #(conj path %)) (waits-of t))
                   (conj seen id))))))))

(defn- cycle-text [path]
  (str "a cycle: " (str/join " → " path)))

;; NO TICKET WAITS ON ITS OWN ANCESTOR (ticket 59b4912d). A parent ends
;; after its children (`children-are-finished`), so a child blocked by
;; its parent, or by any ticket above it, waits for ever, and holds the
;; parent with it. What such a child wants is its parent's MERGE, and
;; `merge_after` says that.

(defn- lineage
  "`parent` and every ticket above it, nearest first, as ids — up the
  parent chain until a ticket with no parent, a ticket already seen or
  `walk-limit`. With no `read'` hook it is `parent` alone."
  [parent read']
  (loop [id (some-> parent str not-empty)
         out []]
    (if (or (nil? id) (some #{id} out) (>= (count out) walk-limit))
      out
      (recur (when read'
               (some-> (read' :ticket id) (get-in [:data :parent]) str not-empty))
             (conj out id)))))

(defn- ancestor-text [id parent]
  (str id (if (= id (some-> parent str)) " is this ticket's parent" " is an ancestor of this ticket")
       ", and a parent ends only after its children, so the wait could never end;"
       " to wait for its change to merge, use merge_after instead"))

(defguardfn the-merge-order-makes-no-cycle
  {:judges [:merge_after]
   :reads [:ticket]
   :vars [:which]
   ;; the closure acknowledgment, the blockers' own: which tickets wait
   ;; on this one is the collection's to say, not a form's
   :open "The tickets named are any tickets, and which of them wait on this one is read from their rows at the write; no form can recite it."
   :explain "A ticket cannot wait to merge on {which}. Name tickets that exist and that do not wait, by what blocks them or what they merge after, on this one."}
  [row inp ctx]
  (let [read' (:read ctx)
        self (some-> (:id row) str)
        named (map str (:merge_after inp))]
    (cond
      (and self (some #(= self %) named))
      (t/deny {:vars {:which "itself"}
               :errors {:merge_after ["a ticket cannot merge after itself"]}})
      (nil? read') (t/allow)
      :else
      (let [problem (or (some #(when (nil? (read' :ticket %))
                                 (str % ", which is not a ticket"))
                              named)
                        (some-> (when self (cycle-path self named read'))
                                cycle-text))]
        (if problem
          (t/deny {:vars {:which problem}
                   :errors {:merge_after [problem]}})
          (t/allow))))))

(defguardfn the-blockers-are-open-and-not-itself
  {:judges [:blocked_by]
   :reads [:ticket]
   :vars [:which]
   :remedies [:ticket/reopen]
   ;; :open is the closure acknowledgment (checks/check-closure): the
   ;; blockers are a list of refs, and no published constraint can say
   ;; which tickets are still open — the collection can, one GET away.
   :open "The blockers are tickets, and the open ones are the tickets collection under its default filter, one query away; no form can recite which of them have ended."
   :explain "A ticket waits on open work: {which}. Name blockers that are still open, never the ticket itself nor its parent or any ticket above it, and none that waits on this one."}
  [row inp ctx]
  (let [read' (:read ctx)
        self (str (:id row))
        named (map str (:blocked_by inp))
        parent (get-in row [:data :parent])
        above (set (lineage parent read'))]
    (cond
      (some #(= self %) named)
      (t/deny {:vars {:which "this ticket names itself"}
               :errors {:blocked_by ["a ticket cannot block itself"]}})
      (some above named)
      (let [problem (ancestor-text (some above named) parent)]
        (t/deny {:vars {:which problem}
                 :errors {:blocked_by [problem]}}))
      (nil? read') (t/allow)
      :else
      (let [problem (some (fn [id]
                            (let [b (read' :ticket id)]
                              (cond
                                (nil? b) (str id " is not a ticket")
                                (contains? ended (state-of b))
                                (str id " has ended")
                                :else nil)))
                          named)
            problem (or problem
                        (some-> (cycle-path self named read') cycle-text))]
        (if problem
          (t/deny {:vars {:which problem}
                   :errors {:blocked_by [problem]}})
          (t/allow))))))

(defguardfn the-parent-is-not-waited-on
  {:judges [:parent]
   :reads [:ticket]
   :check-tier :given
   :vars [:which]
   :stays-code "It walks the ticket tree up the parent chain to any depth, which a one-hop follow cannot express."
   ;; the merge-order guard's acknowledgment, one field over: which
   ;; tickets sit above the parent is read from their rows at the write
   :open "The parent's own ancestors are read from their rows at the write, up the parent chain; no form can recite them. Name a parent this ticket does not wait on."
   :explain "A ticket cannot wait on its own parent: {which}. Leave the parent empty, or state the blockers again without it first."}
  [row inp ctx]
  ;; the block door's ancestor wall, from the other side: a parent set
  ;; on a ticket that already waits on it, or on a ticket above it
  (let [parent (some-> (:parent inp) str not-empty)
        waits (set (map str (concat (get-in row [:data :blocked_by])
                                    (:blocked_by inp))))
        hit (when parent (some waits (lineage parent (:read ctx))))]
    (if hit
      (let [problem (ancestor-text hit parent)]
        (t/deny {:vars {:which problem}
                 :errors {:parent [problem]}}))
      (t/allow))))

;; NO TICKET IS ITS OWN ANCESTOR (ticket 03658863). A birth cannot make
;; that loop, because nothing is under a ticket that is not yet born; a
;; re-parenting can, by naming the ticket itself or one below it.

(defguardfn the-parent-is-not-below-it
  {:judges [:parent]
   :reads [:ticket]
   :check-tier :given
   :vars [:which]
   :stays-code "It walks the ticket tree up the parent chain to any depth, which a one-hop follow cannot express."
   ;; the-parent-is-not-waited-on's acknowledgment: the chain above the
   ;; parent named is read from the rows at the write
   :open "The tickets above the parent named are read from their rows at the write, up the parent chain; no form can recite them. Name a parent that is not under this ticket."
   :explain "A ticket cannot be a piece of itself: {which}. Name a parent that is not this ticket nor any ticket under it, or leave the parent empty."}
  [row inp ctx]
  (let [self (some-> (:id row) str)
        parent (some-> (:parent inp) str not-empty)
        problem (cond
                  (or (nil? self) (nil? parent)) nil
                  (= self parent) "this ticket names itself"
                  (some #{self} (lineage parent (:read ctx)))
                  (str parent " is under this ticket, so this ticket would be its own ancestor"))]
    (if problem
      (t/deny {:vars {:which problem}
               :errors {:parent [problem]}})
      (t/allow))))

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

(defguardfn a-model-says-why-it-ungrooms
  ;; ticket 1a7a763f: a seat sent a ticket back three times and said
  ;; nothing. The reason is the input's to give, so the render probe,
  ;; which carries none, keeps the door offered; a person's hand may
  ;; leave it out, since a person ungrooming from the board knows why.
  {:reads [:principal]
   :needs-input true
   :remedies [{:door :ticket/ungroom :fields [:shelved_because]}]
   :explain "A model's ungroom names its reason in `shelved_because`: what blocks this ticket as written, and what would unblock it. The draft-judge and the mayor read it before they restate and groom it again; a ticket sent back with none sits with nothing to act on."}
  [_row inp ctx]
  (if (and (= :agent (:type (:principal ctx)))
           (str/blank? (str (:shelved_because inp))))
    (t/deny)
    (t/allow)))

;; AN EPIC NAMES ITS SHOWCASE (ticket cbf84f80). The guard reads the
;; row and nothing else, so groom's scenarios stay check-tier: the epic
;; it knows is the one whose title says `[epic]`. A parent that is an
;; epic by its children alone is not judged here; reading the children
;; would declare `:reads [:ticket]` and take this door out of that tier.

(defn- epic? [row]
  (-> (get-in row [:data :title]) str str/triml str/lower-case
      (str/starts-with? "[epic]")))

(defn- scene-of [row]
  (let [showcase (get-in row [:data :showcase])]
    (some-> (or (:scene showcase) (get showcase "scene")) str not-empty)))

(defguardfn an-epic-names-its-showcase
  {:reads []
   :remedies [:ticket/restate]
   :explain "This ticket is an epic, and an epic is judged by its showcase. Restate it with a showcase: the scene, filmed or told, that makes a person want this."}
  [row _inp _ctx]
  (if (and (epic? row) (nil? (scene-of row)))
    (t/deny)
    (t/allow)))

;; P4 IS THE BACKLOG (ticket f7420404). The owner's rule, 2026-09-30:
;; only P0-P3 work is built, and a seat walks every open ticket whatever
;; its priority. So no door lands a ticket in the queue at 4. The
;; ceiling is fixed here, house-wide, and both guards read the row and
;; the input alone, so groom's scenarios stay check-tier. repo_policy's
;; `groom_floor_max_priority` is the floor's own count and is not read.

(def ^:private queue-max-priority ask/queue-max-priority)

(defn- backlog? [priority]
  (and (some? priority) (> (long priority) queue-max-priority)))

(defguardfn a-groomed-ticket-lands-above-the-backlog
  ;; the priority is the input's to give, so the render probe, which
  ;; carries none, keeps the door offered on a P4 draft
  {:reads []
   :needs-input true
   :vars [:lands_at]
   :remedies [{:door :ticket/groom :fields [:priority]}]
   :explain "This ticket would enter the queue at priority {lands_at}, and the queue takes 0 to 3: priority 4 is the backlog, which no seat builds. Groom it with a priority of 0 to 3 when it earns one, or leave it in draft."}
  [row inp _ctx]
  (let [lands-at (or (:priority inp) (get-in row [:data :priority]))]
    (if (backlog? lands-at)
      (t/deny {:vars {:lands_at lands-at}})
      (t/allow))))

(defguardfn a-draft-queued-ahead-lands-above-the-backlog
  ;; `a-person-or-their-delegate-queues-a-draft`'s reading of the one
  ;; input that grooms: `then: open` on a ticket that did not stand in
  ;; the queue. Every other block passes.
  {:reads []
   :vars [:lands_at]
   :remedies [{:door :ticket/block :fields [:then]}]
   :explain "A block that says then: open puts this draft in the queue when its blockers end, and it stands at priority {lands_at}. The queue takes 0 to 3: priority 4 is the backlog, which no seat builds. Block it with then: draft, or raise it first: groom it with a priority of 0 to 3, and block it from the queue."}
  [row inp _ctx]
  (let [queued? (or (= :open (state-of row))
                    (= "open" (get-in row [:data :blocked_from])))
        stands-at (get-in row [:data :priority])]
    (if (and (= "open" (some-> (:then inp) name))
             (not queued?)
             (backlog? stands-at))
      (t/deny {:vars {:lands_at stands-at}})
      (t/allow))))

;; AN EPIC ENDS ON ITS EVIDENCE (ticket 056ac769). `complete` asks the
;; showcase for the evidence its format names: a film's `film_url`, or a
;; text's `scene_ref` to a row that exists. The ref is spelled `kind:id`,
;; as change's `born_from` is, and it may name any kind, so the guard
;; declares `:storage`. A ctx with no hook — the render probe — answers a
;; stated ref with an ALLOW, as the walls above do. `drop` does not
;; carry the guard: an epic let go shows nothing.

(defn- showcase-part [m k]
  (or (get m k) (get m (name k))))

(defn- evidence-of [row k]
  (some-> (get-in row [:data :showcase])
          (showcase-part :evidence)
          (showcase-part k)
          str str/trim not-empty))

(defn- told-scene
  "The row a `scene_ref` names, or nil when it is not spelled `kind:id`
  or no such row is there."
  [scene-ref read']
  (let [scene-ref (str scene-ref)
        colon (str/index-of scene-ref ":")]
    (when (and colon (pos? (long colon)))
      (when-some [id (not-empty (subs scene-ref (inc (long colon))))]
        (try
          (read' (keyword (subs scene-ref 0 (long colon))) id)
          (catch Exception _ nil))))))

(defn- missing-evidence
  "What an epic's showcase still owes before it ends: `:missing`, the
  sentence that says so, and `:field`, the one input that answers it.
  nil for an epic that owes nothing and for a ticket that is no epic."
  [row ctx]
  (let [fmt (some-> (get-in row [:data :showcase]) (showcase-part :format) name)
        film (evidence-of row :film_url)
        scene-ref (evidence-of row :scene_ref)
        read' (:read ctx)
        missing (cond
                  (not (epic? row)) nil
                  (= "film" fmt)
                  (when-not (some-> film (str/starts-with? "https://"))
                    "Its showcase is a film, so its evidence needs `film_url`: the https link where the film plays.")
                  (= "text" fmt)
                  (cond
                    (nil? scene-ref)
                    "Its showcase is a text, so its evidence needs `scene_ref`: the row whose text is the told scene, written kind:id."
                    (and read' (nil? (told-scene scene-ref read')))
                    "Its `scene_ref` names no row that exists: write it kind:id, for the journal or other row whose text is the told scene.")
                  :else
                  "It has no showcase yet: state the scene, and with it the evidence, a `film_url` for a film or a `scene_ref` for a text.")
        ;; the one input the refusal is missing, as the remedy's form names it
        field (case fmt
                "film" "showcase.evidence.film_url"
                "text" "showcase.evidence.scene_ref"
                "showcase")]
    (when missing
      {:missing missing :field field})))

(defn- evidence-or-deny [row ctx]
  (if-some [{:keys [missing field]} (missing-evidence row ctx)]
    (t/deny {:vars {:missing missing} :evidence {:missing_field field}})
    (t/allow)))

;; THE REMEDY IS AFFORDED WHERE THE EPIC STANDS (ticket ce2e4008). A
;; guard's remedies are declared once and do not read the row's state,
;; and `restate` is a draft's door. So the one law is two guards on
;; `complete`, one for each state it leaves from: a draft is refused
;; toward `restate`, and an open epic toward `show_evidence`. Each
;; allows in the other's state, and a pursuit meets one door it can take.

(defguardfn a-draft-epic-shows-its-evidence
  {:reads [:storage]
   :vars [:missing]
   :evidence [:missing_field]
   :remedies [{:door :ticket/restate :fields ['(evidence :missing_field)]}]
   :explain "This ticket is an epic, and an epic is done when its scene can be watched or read. {missing} Restate it with that in its showcase, and then this door opens."}
  [row _inp ctx]
  (if (= :draft (state-of row))
    (evidence-or-deny row ctx)
    (t/allow)))

(defguardfn an-epic-shows-its-evidence
  {:reads [:storage]
   :vars [:missing]
   :evidence [:missing_field]
   :remedies [{:door :ticket/show_evidence :fields ['(evidence :missing_field)]}]
   :explain "This ticket is an epic, and an epic is done when its scene can be watched or read. {missing} Show the evidence with that in its showcase, and then this door opens."}
  [row _inp ctx]
  (if (= :draft (state-of row))
    (t/allow)
    (evidence-or-deny row ctx)))

(defguardfn only-an-epic-shows-evidence
  ;; not hidden: the door is its own way back (checks/check-reversible),
  ;; and a hidden guard would leave a reversible self-loop with none
  {:reads []
   :open "No door changes this where the ticket stands: an epic is a ticket whose title says so, and a groomed title is restated from draft."
   :explain "Only an epic shows evidence: a ticket whose title starts `[epic]`. Every other ticket ends without it."}
  [row _inp _ctx]
  (if (epic? row)
    (t/allow)
    (t/deny)))

(defhandler show-the-evidence [row inp _ctx]
  ;; The scene and its format are the groomed statement, so they stay
  ;; as stored and the evidence alone lands. An open epic that never
  ;; stated a showcase (one groomed before the groom asked) states it
  ;; here, whole.
  (let [shown (:showcase inp)
        stored (get-in row [:data :showcase])]
    (cond
      (nil? shown) row
      (nil? stored) (assoc-in row [:data :showcase] shown)
      :else (assoc-in row [:data :showcase]
                      (assoc shown
                             :format (showcase-part stored :format)
                             :scene (showcase-part stored :scene))))))

(defn- parent-scene
  "The `parent_scene` computed field: the scene of the ticket this one
  is a piece of, or nil when it has no parent or the parent names none."
  [row ctx]
  (when-some [read' (:read ctx)]
    (when-some [parent (some-> (get-in row [:data :parent]) str not-empty)]
      (some-> (read' :ticket parent) scene-of))))

(defguardfn a-person-or-their-delegate-queues-a-draft
  {:reads [:principal]
   :open "No door here changes this verdict. A block that says then: open grooms the draft when its blockers end, and grooming is a person's reading of an ask. A seat blocks the draft without then, and a person grooms it."
   :explain "A draft blocked with then: open reaches the queue when its blockers end, so it is blocked that way by a person, or by a delegate acting for one under a grant the person approved. A seat that could say it could fill its own queue with asks nobody read."}
  [row inp ctx]
  ;; `a-person-or-their-delegate-grooms`, on the one input that grooms:
  ;; `then: open` on a ticket that did not stand in the queue. Every
  ;; other block passes, the engine's restatement included.
  (let [{:keys [type acts-for]} (:principal ctx)
        queued? (or (= :open (state-of row))
                    (= "open" (get-in row [:data :blocked_from])))]
    (if (and (= "open" (some-> (:then inp) name))
             (not queued?)
             (= :agent type)
             (str/blank? (str acts-for)))
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
    (if (and (= :ticket kind) (contains? #{:complete :drop :land :mend :finish} action))
      (t/allow)
      (t/deny))))

;; ── one domain asks another (ticket 775b6427) ───────────────────────

(defguardfn only-a-decline-turns-the-asker-back
  {:reads [:within]
   :open "No door clears this one. A ticket is turned back when the ticket it asked another domain for is dropped, and the engine moves it then; a person who wants it out of the queue sends it back to draft."
   :explain "A ticket is turned back to draft only when the ticket it asked another domain for is dropped: the drop moves it, in the same transaction, and no hand does."}
  [_row _inp ctx]
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :ticket kind) (= :drop action))
      (t/allow)
      (t/deny))))

(defhandler note-the-decline [row inp ctx]
  ;; `clear-the-blockers`, and the decline is kept where the mayor that
  ;; asked reads it before it plans again
  (-> (clear-the-blockers row inp ctx)
      (assoc-in [:data :shelved_because] (:shelved_because inp))))

(defn- domain-named
  "The domain row of this name, or nil. A ctx with no `:find` hook
  answers nil."
  [ctx domain]
  (when-some [find' (:find ctx)]
    (first (find' :domain {:name domain} {:limit 1}))))

(declare answer-an-ask)

(defguardfn the-receiving-mayor-answers-an-ask
  {:reads [:principal]
   :vars [:domain :asker :mayor]
   :open "No door changes who the caller is: ask the mayor of the domain this ticket was asked of, or the owner, to answer it."
   :explain "{asker} asked {domain} for this ticket, so {domain} answers it: it is groomed, ranked and dropped by the owner or by the mayor of {domain}, which is {mayor}. A domain that could groom what it asks for would fill another domain's queue."}
  [row _inp ctx]
  (answer-an-ask row ctx true))

(defn- a-services-ticket?
  "Is this ticket a service's (epic aff24e84, piece 5): in `domain`, the
  domain of a seat that `serves` any, and in the repository the
  `bench.edit` entry of that seat's scope filters by. A ctx with no
  `:find` hook answers false."
  [ctx row domain]
  (let [repo (some-> (get-in row [:data :repo]) str not-empty)
        find' (:find ctx)
        read' (:read ctx)
        in (fn [seat]
             (if-some [id (some-> (get-in seat [:data :domain]) str not-empty)]
               (some-> (when read' (read' :domain id))
                       (get-in [:data :name]) str)
               default-domain))
        edits? (fn [e]
                 (let [f (:filter e)]
                   (and (= "bench.edit" (some-> (:kind e) name))
                        (= repo (some-> (or (:repo f) (get f "repo")) str)))))]
    (boolean
     (and repo find'
          (some #(and (= domain (in %))
                      (some edits? (get-in % [:data :scope])))
                (find' :seat {:serves "any"} {:limit 100}))))))

(defn- answer-an-ask
  "The verdict of `the-receiving-mayor-answers-an-ask`. With
  `requester-too?` the mayor of the domain that asked is allowed as
  well, on a service's ticket; without it only the receiving side is."
  [row ctx requester-too?]
  ;; domains' `the-mayors-or-a-persons-move`, on a ticket one domain
  ;; asked of another. Every other ticket passes, and so does a ctx
  ;; with no hook, which declines to guess. It reads the domain and
  ;; the caller's grants through that hook and declares neither, as
  ;; `only-a-person-reopens` reads its held call: see :deviations.
  (let [domain (or (some-> (get-in row [:data :domain]) str not-empty)
                   default-domain)
        asker (some-> (get-in row [:data :requested_by]) str not-empty)]
    (if (or (nil? asker) (= asker domain) (nil? (:find ctx)))
      (t/allow)
      (let [{:keys [type acts-for]} (:principal ctx)
            cited (delegation/cited-seats ctx)
            mayor (some-> (domain-named ctx domain)
                          (get-in [:data :mayor]) str not-empty)
            said (or (when-some [read' (when mayor (:read ctx))]
                       (some-> (read' :seat mayor)
                               (get-in [:data :name]) str not-empty))
                     mayor
                     "no seat yet")
            refuse #(t/deny {:vars {:domain domain :asker asker :mayor said}})]
        (cond
          (and mayor (contains? cited mayor)) (t/allow)
          ;; a service works at its requester's priority: the mayor of
          ;; the domain that asked grooms and ranks the ticket
          (and requester-too?
               (a-services-ticket? ctx row domain)
               (some->> (domain-named ctx asker) :data :mayor str not-empty
                        (contains? cited)))
          (t/allow)
          ;; a sitter of any other seat
          (seq cited) (refuse)
          ;; a person, a tool a person is signed in to, or the engine
          (or (not= :agent type) (some? (not-empty (str acts-for)))) (t/allow)
          :else (refuse))))))

(defguardfn only-the-receiving-mayor-declines
  {:reads [:principal]
   :vars [:domain :asker :mayor]
   :open "No door changes who the caller is: ask the mayor of the domain this ticket was asked of, or the owner, to drop it."
   :explain "{asker} asked {domain} for this ticket, and the decline is the answer of {domain}: it is dropped by the owner or by the mayor of {domain}, which is {mayor}. The domain that asked grooms and ranks a service's ticket, and it does not drop one."}
  [row _inp ctx]
  ;; `the-receiving-mayor-answers-an-ask` with no exception for the
  ;; requester of a service's ticket (epic aff24e84, piece 5)
  (answer-an-ask row ctx false))

(def only-its-change-moves-it
  "The kit's wall on the link's doors (`factorykit.ask`), in this
  ticket's words: the door opens inside a `change` door's own
  transaction, or for a subscription the `change` kind declares, and for
  nobody's hand. `shelve` and `land` wear it as well as `review` and
  `return`."
  (ask/only-its-change-moves-it link))

;; `only-its-change-moves-it` cannot judge `rejoin_review`: the engine
;; walks it as a ctx :follow-up, after the write that queued it
;; committed, and a follow-up carries no `:within` (ticket 7e01dbe5).
;; The wall is the FACT instead, read from the committed rows, so a hand
;; that takes the door moves the ticket only where its change already
;; is. A ctx with no hook, the render probe, is refused (`:pending
;; :deny`): the door is advertised only where a submitted change was
;; found.

(defguard its-change-is-under-review
  (refuse "A ticket follows its change out for review, and no change born from this ticket is submitted. It stays in the queue for the seat that builds it.")
  '(exists [c :change {:born_from (= (str "ticket:" (self)))
                       :state (= "submitted")}])
  {:open "No door clears this one. A ticket goes out for review behind a change of its own that is submitted: the change's submit sends it, and so does a groom, unblock or resume that puts its stuck pull request back under review."
   :pending :deny})

(defguardfn only-a-childs-ending-finishes-it
  {:reads [:within]
   :open "No door clears this one. A ticket whose pull request merged while a child was open ends when its last child is completed or dropped; the engine moves it then, and a person who wants it sooner ends the children."
   :explain "A ticket in review whose pull request already merged ends only when its last child ends: that ending moves it, in the same transaction, and no hand does."}
  [row _inp ctx]
  ;; `only-an-ending-returns-a-ticket-to-draft`'s shape (ticket
  ;; 499bcd72), and only on a ticket its change's merge wrote on. The
  ;; wire, the render probe and every rehearsal answer nil, so it
  ;; renders refused, which is true.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :ticket kind)
             (contains? #{:complete :drop :land :mend :finish} action)
             (some-> (get-in row [:data :merged_change]) str not-empty))
      (t/allow)
      (t/deny))))

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

(defhandler land-the-merge [row inp ctx]
  ;; THE MERGE'S ONE DOOR, WITH TWO LANDINGS (ticket 35c1e826). Its
  ;; change's `merge` subscription walks it with the pull request's
  ;; address, or its id when the mirror read none. With every child
  ;; ended the ticket closes as any ending does, the address as its
  ;; sentence, and lands `done`. Over a child that has not ended, the
  ;; merge is written on it and it lands `in_review`, where the last
  ;; child's ending walks `finish` (`finish-the-parent!`, ticket
  ;; 499bcd72). A ctx with no hook reads no child, and closes it.
  (let [merged (or (some-> (:merged_change inp) str not-empty)
                   (some-> (:merged_change_id inp) str not-empty))
        id (str (:id row))
        waiting (when-some [find' (:find ctx)]
                  (some #(contains? unfinished (state-of %))
                        (find' :ticket {:parent id} {:limit 500})))]
    (if waiting
      (-> row
          (assoc :state :in_review)
          (assoc-in [:data :merged_change] merged))
      (-> (close-the-ticket row
                            (cond-> inp
                              (and merged (str/blank? (str (:close_reason inp))))
                              (assoc :close_reason (str "Merged: " merged ".")))
                            ctx)
          (assoc :state :done)))))

(defhandler note-the-shelving [row inp _ctx]
  ;; the stall's sentence, kept where whoever grooms next reads it
  ;; (ticket b6c8ea04); a shelve with none leaves the last one standing
  (if-some [why (some-> (:shelved_because inp) str not-empty)]
    (assoc-in row [:data :shelved_because] why)
    row))

(defhandler note-a-red-head [row inp _ctx]
  ;; One more red head of the base this ticket was opened for, kept to
  ;; the last fifty.
  (update-in row [:data :red_heads]
             (fn [heads]
               (vec (take-last 50 (distinct (conj (vec heads)
                                                  (:red_head inp))))))))

(defhandler note-a-green-base [row inp _ctx]
  ;; The base went green while this ticket's change held work (ticket
  ;; d5000a1c): the sentence is written, and the ticket is not ended.
  (assoc-in row [:data :green_note] (str (:green_note inp))))

;; ── a ticket moves into another domain (ticket 66d080b0) ────────────

(def ^:private move-doors
  "The door that writes a ticket's domain, by the state the ticket stands
  in: the hand's `move_domain` where a hand shapes the tree, and the
  hidden `move_domain_carried` where the ticket is carried with its
  parent. An ended ticket has none (`the-work-is-over` shuts every door
  on it), and keeps the domain it ended in."
  {:open :move_domain
   :draft :move_domain
   :blocked :move_domain
   :in_review :move_domain_carried
   :deferred :move_domain_carried})

(def ^:private change-states
  "The states a change born from it takes the domain in, through its one
  `take_domain` door (ticket bc6a8732)."
  #{:open :submitted :failing :stuck})

(defn- inside-a-move?
  "Whether this write was opened inside a ticket's move into a domain."
  [ctx]
  (let [{:keys [kind action]} (:within ctx)]
    (and (= :ticket kind)
         (contains? (set (vals move-doors)) action))))

(defn- move-the-tickets-under!
  "Every child of `id` walks its own move, which walks its children in
  turn. An ended child takes no door, so it is left as it is and the
  tickets under it are walked from here."
  [id domain ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)]
    (doseq [child (find' :ticket {:parent id} {:limit 500})]
      (if-some [door (move-doors (state-of child))]
        ;; the hand's doors are fenced (their :edit implies it), so the
        ;; version read here is named, as `release-the-waiters!` names it
        (invoke' :ticket (str (:id child)) door {:domain domain}
                 {:if-match (inv/etag :ticket (:id child) (:version child))})
        (move-the-tickets-under! (str (:id child)) domain ctx)))))

(defhandler move-the-domain [row inp ctx]
  ;; The ticket, every ticket under it and each of their open changes,
  ;; in the one transaction. NOT best-effort, as the endings' cascades
  ;; are: a row that refuses refuses the whole move, so a tree is never
  ;; left in two domains. A probe or a rehearsal carries no pen, and
  ;; moves the one row.
  (let [domain (:domain inp)
        find' (:find ctx)
        invoke' (:invoke ctx)]
    (when (and find' invoke')
      (doseq [change (changes-born-from row (map name change-states) find')]
        (invoke' :change (str (:id change)) :take_domain {:domain domain}))
      (move-the-tickets-under! (str (:id row)) domain ctx))
    (assoc-in row [:data :domain] domain)))

(defguardfn the-domain-stands
  {:judges [:domain]
   :reads [:domain]
   :vars [:which]
   :open "The domains are read from their rows at the write; no form can recite them. Name a domain that is active."
   :explain "A ticket moves into a domain that is here and not retired: {which}. Name an active domain."}
  [_row inp ctx]
  (let [named (some-> (:domain inp) str not-empty)
        find' (:find ctx)]
    (if (or (nil? named) (nil? find')
            (seq (find' :domain {:name named :state "active"} {:limit 1})))
      (t/allow)
      (let [problem (str "no active domain is named " named)]
        (t/deny {:vars {:which problem}
                 :errors {:domain [problem]}})))))

(defguardfn the-domains-mayor-or-a-person-moves-it
  {:reads [:principal :now :grant :domain :within]
   :open "No door changes who the caller is: ask the mayor of the domain this ticket is in, or a person, to make the move."
   :explain "A ticket is moved out of a domain by the sitter of that domain's mayor seat, or by a person. Ask the mayor of the domain this ticket is in, or a person, to make the move."}
  [row _inp ctx]
  ;; domains' `the-mayors-or-a-persons-move`, read from the ticket: the
  ;; domain is the one the row stores, or factory. A child's door opens
  ;; inside its parent's move, whoever's hand made that one.
  (let [{:keys [type acts-for]} (:principal ctx)
        cited (delegation/cited-seats ctx)
        named (or (some-> (get-in row [:data :domain]) str not-empty) "factory")
        mayor (when-some [find' (:find ctx)]
                (some-> (first (find' :domain {:name named} {:limit 1}))
                        (get-in [:data :mayor]) str not-empty))]
    (cond
      (inside-a-move? ctx) (t/allow)
      (and mayor (contains? cited mayor)) (t/allow)
      ;; a sitter of any other seat
      (seq cited) (t/deny)
      ;; a person, a tool a person is signed in to, or the engine
      (or (not= :agent type) (some? (not-empty (str acts-for)))) (t/allow)
      :else (t/deny))))

(defguardfn only-a-parents-move-carries-it
  {:reads [:within]
   :hide true
   :explain "A ticket under review or deferred changes domain with the ticket above it, inside that ticket's move. A person and a model read it."}
  [_row _inp ctx]
  (if (inside-a-move? ctx) (t/allow) (t/deny)))

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

(def ^:private a-draft-epic
  (assoc a-draft-ticket :title "[epic] Every epic names its scene"))

(defscenario an-epic-is-not-groomed-without-its-showcase
  "An epic is judged and ranked by the scene that makes a person want
   it. Without one even the person's groom is refused, and the refusal
   names the door that states it."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft :data a-draft-epic}
   :as      {:id "colton" :type :person}
   :expect  {:refused :an-epic-names-its-showcase
             :because "Restate it with a showcase"}})

(defscenario an-epic-with-its-showcase-is-groomed
  "And with the scene stated the door opens."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft
             :data (assoc a-draft-epic :showcase
                          {:format "film"
                           :scene "You open a proposal and cannot tell why anyone wants it. Then the scene plays, and you can."})}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(def ^:private a-backlog-draft
  (assoc a-draft-ticket :priority 4))

(defscenario a-backlog-ticket-is-not-groomed-where-it-stands
  "Only priority 0 to 3 is built, and a seat walks every open ticket.
   So a draft at 4 is refused the queue even at the person's tap, and
   the refusal says to groom it with a priority."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft :data a-backlog-draft}
   :input   {}
   :as      {:id "colton" :type :person}
   :expect  {:refused :a-groomed-ticket-lands-above-the-backlog
             :because "priority 4 is the backlog"}})

(defscenario a-backlog-ticket-is-groomed-with-a-priority
  "And the groom that raises it lands it, in the one transition."
  {:kind    :ticket
   :attempt :groom
   :row     {:state :draft :data a-backlog-draft}
   :input   {:priority 3}
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

;; Check-tier with :given rows: the walls on `reparent`, `rejoin_review`
;; and `drop` are declarative, or code that says `:check-tier :given`,
;; or read :principal only, so the scenario's own rows answer them. The
;; check tier gives the row the id `scenario:<name>`, which is how a
;; :given row names it as its parent or the ticket it was born from.

(defscenario a-ticket-is-not-moved-under-an-ended-parent
  "A ticket under an ended parent is work nobody will find. The door
   refuses it, and names the reopen that would stand the parent up
   again."
  {:kind    :ticket
   :attempt :reparent
   :given   [{:kind :ticket :handle :ended :state :done :data a-done-ticket}]
   :row     {:state :open :data a-draft-ticket}
   :input   {:parent "{given/ended}"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :the-parent-is-open-at-birth
             :because "has ended"
             :remedies [:ticket/reopen]}})

(defscenario a-ticket-is-moved-under-an-open-parent
  "And under a parent that stands, the move is one tap."
  {:kind    :ticket
   :attempt :reparent
   :given   [{:kind :ticket :handle :standing :state :open :data a-draft-ticket}]
   :row     {:state :open :data a-draft-ticket}
   :input   {:parent "{given/standing}"}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-parent-is-not-dropped-over-an-open-child
  "A parent ends after its children. The refusal counts the ones not
   finished and names the oldest, so its reader knows which to end
   first."
  {:kind    :ticket
   :attempt :drop
   :given   [{:kind :ticket :handle :child :state :open
              :data (assoc a-draft-ticket
                           :title "The open child"
                           :parent "scenario:a-parent-is-not-dropped-over-an-open-child")}]
   :row     {:state :open :data a-draft-ticket}
   :input   {:close_reason "Not wanted any more."}
   :as      {:id "colton" :type :person}
   :expect  {:refused :children-are-finished
             :because "1 of this ticket's children are not finished. The oldest is \"The open child\"."}})

(defscenario a-parent-is-dropped-once-its-children-have-ended
  "And once every child has ended, the parent ends too."
  {:kind    :ticket
   :attempt :drop
   :given   [{:kind :ticket :handle :child :state :done
              :data (assoc a-done-ticket
                           :parent "scenario:a-parent-is-dropped-once-its-children-have-ended")}]
   :row     {:state :open :data a-draft-ticket}
   :input   {:close_reason "Not wanted any more."}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-ticket-with-a-child-is-not-groomed
  "A parent is not built: it ends after its children. Its groom is
   refused, and the refusal names the block that waits on them."
  {:kind    :ticket
   :attempt :groom
   :given   [{:kind :ticket :handle :child :state :done
              :data (assoc a-done-ticket
                           :parent "scenario:a-ticket-with-a-child-is-not-groomed")}]
   :row     {:state :draft :data a-draft-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:refused :a-parent-is-not-groomed
             :because "This ticket has pieces under it"
             :remedies [:ticket/block]}})

(defscenario a-submitted-change-takes-its-ticket-back-out
  "A ticket follows its change: when the change born from it is
   submitted, the ticket goes back out for review with it."
  {:kind    :ticket
   :attempt :rejoin_review
   :given   [{:kind :change :handle :its-change :state :submitted
              :data {:repository "ckopsa/waymark"
                     :title "The code seat walks ticket rows"
                     :born_from "ticket:scenario:a-submitted-change-takes-its-ticket-back-out"}}]
   :row     {:state :open :data a-draft-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-ticket-stays-in-the-queue-while-its-change-is-unsubmitted
  "A change still being built is not under review, so the ticket stays
   in the queue for the seat that builds it."
  {:kind    :ticket
   :attempt :rejoin_review
   :given   [{:kind :change :handle :its-change :state :open
              :data {:repository "ckopsa/waymark"
                     :title "The code seat walks ticket rows"
                     :born_from "ticket:scenario:a-ticket-stays-in-the-queue-while-its-change-is-unsubmitted"}}]
   :row     {:state :open :data a-draft-ticket}
   :as      {:id "colton" :type :person}
   :expect  {:refused :its-change-is-under-review
             :because "no change born from this ticket is submitted"}})

;; ── the fields, spelled once and read by three doors ────────────────

(def ^:private type-choices
  {"bug" "Something is wrong and should be fixed"
   "feature" "Something new, or something that should work differently"
   "task" "One piece of work, on the way to something larger"
   "chore" "Upkeep — nothing a person would notice, and it needs doing"})

(def ^:private then-choices
  {"draft" "Back to draft, to be groomed"
   "open" "Into the queue, as groomed"})

(def ^:private showcase-format-choices
  {"film" "A short film of what a person sees"
   "text" "A before/after conversation, for work that makes the agent better"})

(def ^:private stated-fields
  "What a person or a seat STATES about a ticket: the create door and
  the restate door collect the same six, because a restatement is
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
   ;; ticket cbf84f80: the scene an epic is judged and ranked by
   [:showcase {:optional true
               :x-display
               {:label "Showcase"
                :help "The scene that makes a person want this, the way a product keynote shows it: the friction you know, then the moment it disappears. film for what a person sees, text for a before/after conversation when the work makes the agent better."}}
    [:maybe [:map
             [:format {:x-display
                       {:label "Film or text"
                        :choices showcase-format-choices}}
              (into [:enum] (sort (keys showcase-format-choices)))]
             [:scene {:x-display
                      {:widget "prose"
                       :label "The scene"
                       :help "The friction a person knows, then the moment it disappears. It is at most 1200 characters."}}
              [:string {:min 1 :max 1200}]]
             ;; ticket 056ac769: what `complete` asks an epic for
             [:evidence {:optional true
                         :x-display
                         {:label "Evidence"
                          :help "What shows the scene happened. An epic is not completed without it: a film needs the film's link, and a text needs the row that tells the scene."}}
              [:maybe [:map
                       [:film_url {:optional true
                                   :x-display
                                   {:label "The film"
                                    :help "The https link where the film plays, such as a clone film's public address."}}
                        [:maybe [:string {:max 500}]]]
                       [:scene_ref {:optional true
                                    :x-display
                                    {:label "The told scene"
                                     :help "The journal or other row whose text is the scene, told from real records. Write it kind:id."}}
                        [:maybe [:string {:max 200}]]]
                       [:note {:optional true
                               :x-display
                               {:label "A note"
                                :help "One line about the evidence, when it needs one."}}
                        [:maybe [:string {:max 480}]]]]]]]]]
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
    [:maybe [:string {:max 140}]]]
   ;; ticket d069bc3b: holds the MERGE, not the build — see bench.clj
   [:merge_after {:optional true :kind :ticket
                  :x-display
                  {:label "Merges after"
                   :help "The tickets, in any repository, that must be done before this one's change merges. A seat builds it meanwhile; the house holds its green pull request until every one of them is done or dropped."}}
    [:maybe [:vector {:max 50} :waymark/ref]]]])

(def ^:private birth-fields
  "What a birth may say beyond the statement: where it sits in the
  tree, what surfaced it, and how urgent it is."
  [[:priority {:default 2
               :examples [2]
               :x-display
               {:label "Priority (0 first, 4 last)"
                :help "The queue's own order. A walker takes the lowest number first, so 0 is the ask the house wants next and 4 is the one it can wait for. A fired seat's ticket lands at 4; the groomer raises it."}}
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
   ;; ticket 775b6427: the ticket in the asking domain that waits for
   ;; this one, which a drop of this one turns back to draft
   [:needed_by {:optional true :kind :ticket
                :x-display
                {:label "Needed by"
                 :help "The ticket that waits for this one, when a mayor asks another domain for work. If this ticket is dropped, that one goes back to draft with the reason, so the mayor that asked plans again."}}
    [:maybe :waymark/ref]]
   ;; the domain's NAME and not its id: the factory domain's id is
   ;; minted at boot on each database, so no declaration can spell it,
   ;; and `:absent-as` needs a value it can spell (ticket 20fab5f9)
   [:domain {:optional true
             :not-a-ref "The name of a domain, which no restate changes: a word, not a row id."
             :examples ["factory"]
             :x-display
             {:raw true
              :label "Domain that does the work"
              :help "The name of the domain this ask belongs to. Leave it empty and the birth writes it: a ticket with a parent takes its parent's domain, and any other takes the domain of the seat that filed it. A mayor names another domain to ask it for work. A move into another domain changes it, here and on every ticket under this one. A ticket that stores none is in factory."}}
    [:maybe [:string {:max 64}]]]
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
   ;; ticket cca6b000: what the `block` door said, kept for its restate
   [:then {:optional true
           :x-display
           {:label "When its blockers end"
            :choices then-choices
            :help "Where this ticket goes when the last ticket it waits on ends. Empty unless the ticket is blocked."}}
    [:maybe (into [:enum] (sort (keys then-choices)))]]
   [:defer_until {:optional true
                  :examples ["2026-11-19"]
                  :x-display
                  {:label "Deferred until"
                   :help "The day this ask comes back into the queue. Empty unless the ticket is deferred."}}
    [:maybe :waymark/date]]
   [:close_reason {:optional true
                   :examples ["Done: written, checked and sent to the person who asked."]
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
    [:maybe [:vector [:string {:max 400}]]]]
   ;; ticket d5000a1c: written by the base pass when the base went green
   ;; while this ticket's change held work, in place of the ending
   [:green_note {:optional true
                 :examples ["main is green again at 1f0c2d3e4a5b60718293a4b5c6d7e8f901234567 on its own. The change on bench/1f0c holds work that is not submitted, so this ticket was left open."]
                 :x-display
                 {:raw true
                  :label "Green on its own"
                  :help "What the base pass wrote when the base went green while this ticket's change held work that was not submitted. The ticket was left open, so its groomers decide whether that work still ships."}}
    [:maybe [:string {:max 480}]]]
   ;; ticket d069bc3b: written by the house merge pass, as the change's
   ;; place in the line is, and cleared when nothing holds the merge
   [:merge_waits {:optional true
                  :examples ["waits on 01HZQ7Y7F2R3W4V5X6Y7Z8A9B1 (open) to merge"]
                  :x-display
                  {:widget "prose"
                   :label "Waits to merge on"
                   :help "Why this ticket's green pull request is not merging: the tickets it merges after that are not done yet, each with its state. Empty when nothing holds it."}}
    [:maybe [:string {:max 500}]]]
   ;; ticket 499bcd72: written by its change's merge while a child was
   ;; still open, and read by the last child's ending
   [:merged_change {:optional true
                    :x-display
                    {:raw true
                     :label "Merged before its children"
                     :help "The pull request that merged while a child of this ticket was still open. The ticket ends when its last child does. Empty for every other ticket."}}
    [:maybe [:string {:max 400}]]]
   ;; ticket b6c8ea04: written by its change's stall, through `shelve`
   [:shelved_because {:optional true
                      :x-display
                      {:widget "prose"
                       :label "Why its change was stalled"
                       :help "The sentence the seat gave when it stalled the change built for this ticket and sent it back to draft. Read it before grooming again: a groom that does not answer it sends the seat back to the same wall."}}
    [:maybe [:string {:max 480}]]]
   ;; ticket b0ec4d47: written by the birth when a fired seat filed the
   ;; ticket, beside the 4 the birth stamped over what it asked
   [:asked_priority {:optional true
                     :x-display
                     {:label "Priority the seat asked for"
                      :help "The priority a fired seat named when it filed this ticket. Its birth lands at 4 whatever it asked, so a groomer reads the seat's own judgment here and raises the ticket when it earns it. A ticket a mayor asked of another domain keeps the mayor's suggestion here in the same way. Empty for any other ticket."}}
    [:maybe [:int {:min 0 :max 4}]]]
   ;; ticket 775b6427: written by the birth when a seat names a domain
   ;; other than its own, and read by the groom, rank and drop doors
   [:requested_by {:optional true
                   :not-a-ref "The name of a domain, which no restate changes: a word, not a row id."
                   :examples ["household"]
                   :x-display
                   {:raw true
                    :label "Domain that asked"
                    :help "The name of the domain whose mayor asked another domain for this work. Only the receiving domain's mayor, or the owner, grooms, ranks or drops such a ticket, except that the asking domain's mayor grooms and ranks a ticket of a seat that serves any domain. Empty for a ticket filed in its own domain."}}
    [:maybe [:string {:max 64}]]]
   ;; epic 46831b3c, R-3.4: written by the birth, never input, and
   ;; read by a requester's sight (grants/requester-visibility)
   [:filed_by {:optional true :kind :member
               :x-display
               {:label "Person who filed it"
                :help "The member id of the person who filed this ticket, or of the person the tool that filed it acts for. The engine writes it at birth. Empty for a ticket a seat or the engine filed, and for one filed before it was kept."}}
    [:maybe :waymark/ref]]])

;; ── a fired seat's ticket lands at the back (ticket b0ec4d47) ───────

(def ^:private fired-seat-priority
  "Where a fired seat's ticket lands, whatever it asked: the owner's
  ruling, 2026-09-30. A seat files follow-ups after each merge or
  stall, and the queue drains only if they wait for a groomer."
  4)

(defn- filed-by-a-fired-seat?
  "Whether this birth's hand is a seat in a FIRED sitting: an open
  sitting under the grant the request wore, of the fired mode. A
  person, an interactive seat and the engine's own hand hold no fired
  sitting, and a ctx with no `:find` hook declines to guess. A sitting
  written before `mode` existed reads fired, which every seat was."
  [ctx]
  (when-some [gid (some-> (get-in ctx [:grant :id]) str not-empty)]
    (when-some [find' (:find ctx)]
      (some #(= "fired" (str (or (get-in % [:data :mode]) "fired")))
            (find' :sitting {:grant gid :state :open}
                   {:limit 50 :newest-first true})))))

(defn- land-a-fired-seats-ticket-at-four
  "The birth's stamp: a fired seat's ticket keeps the priority it asked
  in `asked_priority` and lands at `fired-seat-priority`. Every other
  birth is left as it was asked."
  [row ctx]
  (if (filed-by-a-fired-seat? ctx)
    (-> row
        (assoc-in [:data :asked_priority] (get-in row [:data :priority]))
        (assoc-in [:data :priority] fired-seat-priority))
    row))

;; ── a ticket carries the domain that wants the work (ticket 20fab5f9) ─

(defn- seat-domain-name
  "The name of the domain the filing seat STORES, or nil: the grant the
  request wore cites a seat, and the seat names a domain row. A person,
  a seat with no stored domain and a ctx with no `:read` hook answer
  nil, and a ticket that stores nothing filters as factory."
  [ctx]
  (when-some [read' (:read ctx)]
    (when-some [gid (some-> (get-in ctx [:grant :id]) str not-empty)]
      (when-some [seat-id (some-> (read' :grant gid)
                                  (get-in [:data :seat]) str not-empty)]
        (when-some [domain-id (some-> (read' :seat seat-id)
                                      (get-in [:data :domain]) str not-empty)]
          (some-> (read' :domain domain-id)
                  (get-in [:data :name]) str not-empty))))))

(defn- filing-seat
  "The seat row the request's grant cites, or nil: a person, the engine
  and a ctx with no `:read` hook file with no seat."
  [ctx]
  (when-some [read' (:read ctx)]
    (when-some [gid (some-> (get-in ctx [:grant :id]) str not-empty)]
      (when-some [seat-id (some-> (read' :grant gid)
                                  (get-in [:data :seat]) str not-empty)]
        (read' :seat seat-id)))))

(defn- home-of
  "The domain ROW a seat is in: the one it stores, or factory's for a
  seat that stores none."
  [seat ctx]
  (if-some [domain-id (some-> (get-in seat [:data :domain]) str not-empty)]
    ((:read ctx) :domain domain-id)
    (domain-named ctx default-domain)))

(defguardfn the-domain-is-one-we-have
  {:judges [:domain]
   :reads [:domain]
   :vars [:domain]
   :open "No door here makes a domain. Name one that exists, or leave the domain empty and the ticket takes the filing seat's."
   :explain "No domain is named {domain}, and a ticket asked of a domain nobody runs is work no mayor will find. Name a domain that exists, or leave the domain empty."}
  [_row inp ctx]
  (let [stated (some-> (:domain inp) str not-empty)]
    (if (or (nil? stated) (nil? (:find ctx)) (some? (domain-named ctx stated)))
      (t/allow)
      (t/deny {:vars {:domain stated}
               :errors {:domain ["domain not found"]}}))))

(defguardfn only-a-mayor-asks-another-domain
  {:judges [:domain]
   ;; it reads the filing seat's row as well, which no declaration in
   ;; this module names (spec R-6)
   :reads [:grant :domain]
   :vars [:domain :own]
   :open "No door changes who the caller is. Leave the domain empty to file the ticket in your own domain, or tell your domain's mayor what you need of the other one."
   :explain "Domains ask each other for work through their mayors, and this seat is in {own} without being its mayor, so it does not file a ticket in {domain}. Leave the domain empty, or tell the mayor of {own} what you need of {domain}."}
  [_row inp ctx]
  ;; a person, the engine and a seat naming its own domain pass
  (let [stated (some-> (:domain inp) str not-empty)
        seat (when stated (filing-seat ctx))]
    (if (or (nil? seat) (nil? (:find ctx)))
      (t/allow)
      (let [home (home-of seat ctx)
            own (or (some-> home (get-in [:data :name]) str not-empty)
                    default-domain)
            mayor (some-> home (get-in [:data :mayor]) str not-empty)]
        (if (or (= stated own) (= mayor (str (:id seat))))
          (t/allow)
          (t/deny {:vars {:domain stated :own own}}))))))

;; ── a seat does not file one failure twice (ticket 535d86ed) ─────────

(def ^:private failure-key-patterns
  "What names a failing check in a ticket's words: the ui-drive step
  after `FAILED: `, a `timed out waiting for <what>` phrase, and the
  Clojure test of a `FAIL in (<name>)` line."
  [#"FAILED:\s*([^\n`\"]+)"
   #"(timed out waiting for [^\n`\".;,]+)"
   #"FAIL in \(([^)\s]+)\)"])

(defn- failure-keys
  "The failing checks a title and a detail name, as the strings another
  ticket would hold. A key shorter than six characters names nothing."
  [text]
  (into #{}
        (comp (mapcat #(re-seq % (str text)))
              (map #(str/replace (str/trim (second %)) #"[\s.,;:')]+$" ""))
              (filter #(<= 6 (count %))))
        failure-key-patterns))

(defn- tickets-naming
  "The draft, open, in-review and blocked tickets of `repo` whose title
  or detail holds one of `named`. A seat's follow-up is born a draft and
  stays one until it is groomed, so drafts are read too (ticket
  901e3c37). A bounded read of each state, with no index: the queue is
  hundreds of rows, not thousands."
  [named repo ctx]
  (when-some [find' (:find ctx)]
    (for [state ["draft" "open" "in_review" "blocked"]
          row (find' :ticket (cond-> {:state state} repo (assoc :repo repo))
                     {:limit 200})
          :let [words (str (get-in row [:data :title]) "\n"
                           (get-in row [:data :detail]))]
          :when (some #(str/includes? words %) named)]
      row)))

(defguardfn no-open-ticket-names-this-failure
  {:severity :warning
   :judges [:title :detail]
   :reads [:principal :ticket]
   :vars [:which]
   :open "The tickets that already name a failure are read from the queue at the write; no form can recite them. Acknowledge the warning by its name to file the ticket anyway."
   :explain "A ticket in the queue already names this failing check: {which}. Restate that ticket with your run instead of filing a second one, or acknowledge this warning to file anyway."}
  [_row inp ctx]
  ;; a person's create is not slowed, and neither is the engine's
  (let [named (when (= :agent (:type (:principal ctx)))
                (failure-keys (str (:title inp) "\n" (:detail inp))))
        hits (when (seq named)
               (take 5 (tickets-naming named (some-> (:repo inp) str not-empty) ctx)))]
    (if (seq hits)
      (t/deny {:vars {:which (str/join "; "
                                       (map #(str (:id %) " \"" (get-in % [:data :title]) "\"")
                                            hits))}})
      (t/allow))))

(defn- take-the-domain
  "The birth's other stamp. A ticket that names a domain keeps it; one
  with a parent takes what its parent stores, and any other takes the
  filing seat's domain. Nothing is written when there is none to take.

  A SEAT THAT NAMES ANOTHER DOMAIN IS ASKING IT (ticket 775b6427). The
  ticket stores the asking domain in `requested_by`, and its priority
  is the receiving mayor's to set: what was asked moves to
  `asked_priority` and the ticket lands at `fired-seat-priority`."
  [row ctx]
  (let [stated (some-> (get-in row [:data :domain]) str not-empty)
        parent (some-> (get-in row [:data :parent]) str not-empty)
        domain (cond
                 stated stated
                 parent (when-some [read' (:read ctx)]
                          (some-> (read' :ticket parent)
                                  (get-in [:data :domain]) str not-empty))
                 :else (seat-domain-name ctx))
        asker (when (and stated (filing-seat ctx))
                (or (seat-domain-name ctx) default-domain))]
    (cond-> row
      domain (assoc-in [:data :domain] domain)
      (and asker (not= asker stated))
      (-> (assoc-in [:data :requested_by] asker)
          (update :data #(if (some? (:asked_priority %))
                           %
                           (assoc % :asked_priority (:priority %)
                                  :priority fired-seat-priority)))))))

;; ── who filed it, and a requester's ask (epic 46831b3c, R-3.4) ───────

(defn- filing-person
  "The member row of the person who files: a person's own row, or the
  row of the person a delegate acts for. A seat, the engine's own hand
  and a ctx with no read hooks answer nil."
  [ctx]
  (let [p (:principal ctx)
        who (some-> (case (:type p)
                      :agent (:acts-for p)
                      :system nil
                      (:id p))
                    str not-empty)
        read' (:read ctx)
        find' (:find ctx)]
    (when (and who read' find')
      (or (read' :member who)
          (first (find' :member {:subject who} {:limit 1}))))))

(defn- stamp-who-filed
  "The birth's third stamp. `filed_by` is the member id of the person
  who filed; `requested_by` keeps naming the asking domain. A person
  who holds `requester` files into the backlog: the ticket is born a
  draft as every ticket is, and lands at `fired-seat-priority` with
  what they asked kept in `asked_priority`."
  [row ctx]
  (if-some [m (filing-person ctx)]
    (cond-> (assoc-in row [:data :filed_by] (str (:id m)))
      (contains? (set (get-in m [:data :roles])) roles/requester)
      (-> (update :data #(if (some? (:asked_priority %))
                           %
                           (assoc % :asked_priority (:priority %))))
          (assoc-in [:data :priority] fired-seat-priority)))
    row))

(defn- stamp-the-birth
  [row ctx]
  ;; its parent is not built now (ticket 898caffd)
  (close-the-parents-stuck-changes! (get-in row [:data :parent]) ctx)
  (-> row
      (land-a-fired-seats-ticket-at-four ctx)
      (take-the-domain ctx)
      (stamp-who-filed ctx)))

(def ^:private close-input
  [:map
   [:close_reason
    {:examples ["Done: written, checked and sent to the person who asked."]
     :x-display
     {:widget "prose"
      :label "How it ended"
      :help "One sentence for the next reader: what was done, or why this is let go. Say the outcome — merged, superseded by, no longer wanted because — and not the diagnosis. It is at most 480 characters."}}
    [:string {:min 1 :max 480}]]])

(def ^:private merge-after-input
  [:map
   [:merge_after {:kind :ticket
                  :x-display
                  {:label "Merges after"
                   :help "Every ticket, in any repository, that must be done before this one's change merges. State the whole set: this replaces the list, and an empty one lets it merge when it is green."}}
    [:vector {:max 50} :waymark/ref]]])

(def ^:private merge-after-safety
  {:idempotent true :reversible true :confirm false})

(def ^:private merge-after-description
  "Name the tickets that must be done before this one's change merges")

(def ^:private reparent-input
  [:map
   [:parent {:kind :ticket
             :x-display
             {:label "Part of"
              :help "The larger ask this one is a piece of. It must be open, and not a ticket this one waits on or one under this one. Leave it empty and this ticket is a piece of nothing."}}
    [:maybe :waymark/ref]]])

(def ^:private reparent-guards
  [the-parent-is-open-at-birth the-parent-is-not-waited-on
   the-parent-is-not-below-it])

(def ^:private reparent-safety
  {:idempotent true :reversible true :confirm false})

(def ^:private reparent-description
  "Move this ask under another one, or out from under its parent")

(def ^:private move-domain-input
  [:map
   [:domain {:not-a-ref "The name of a domain: a word, not a row id."
             :examples ["household"]
             :x-display
             {:raw true
              :label "Domain"
              :help "The name of the active domain this ask moves into. Every ticket under this one, and each of their open changes, moves with it."}}
    [:string {:min 1 :max 64}]]])

(def ^:private carried-domain-input
  [:map
   [:domain {:not-a-ref "The name of a domain: a word, not a row id."
             :x-display {:hidden true :raw true :label "The domain"}}
    [:string {:min 1 :max 64}]]])

(def ^:private move-domain-guards
  [the-domain-stands the-domains-mayor-or-a-person-moves-it])

(def ^:private move-domain-safety
  {:idempotent true :reversible true :confirm false})

(def ^:private move-domain-description
  "Move this ask, and every ask under it, into another domain")

(def ^:private carried-domain-description
  "The ticket above it moved into another domain, and this one went with it")

;; ── :ticket — one ask of the factory ────────────────────────────────

(def ^:private factory10s-own
  "Everything of the ticket kind that is not `factorykit.ask/core`: the
  core holds the seven states, no tomb, and the queue doors' machine
  and groom's input (ticket 718f997f). Every door here names its own
  guards, handler, safety and words."
  {:kind :ticket
   :plural "tickets"
   ;; the doors the kit's change link walks (`named-link`)
   :link named-link
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   ;; `:over` names the two endings apart: a done ticket is the deed
   ;; this kind is graded by, a dropped one is work the house let go.
   ;; `draft`, `blocked` and `deferred` are in neither list — they are
   ;; waiting, not over, and their doors stay open.
   :over {:accomplished #{:done} :let-go #{:dropped}}
   ;; THE LAW IS THE KIT'S (`factorykit.ask/law`, ticket 2b85a37e):
   ;; the `blocked_by` wait, the exits no seat takes at a ticket door
   ;; (`review` and `land` by the link's names, `unblock` and
   ;; `return_to_draft` by a blocker's ending) and the expected ages.
   ;; This ticket adds its own wait: a `merge_after` ticket releases
   ;; as a blocker does (ticket 97697177): bench/merge-holds holds the
   ;; green change for neither a done ticket nor a dropped one, so the
   ;; next merge pass lets it go.
   :waits [{:on :merge_after :awaits :ticket :in #{:in_review}
            :restated-by [:merge_after]
            :outcomes {:done :release :dropped :release}}]
   ;; THE FOLDED DOORS (ticket 45a8d06c): each state-copied door is now
   ;; one `:to :same` door, and its old names still answer through here
   :aliases {:restate_blocked :restate
             :merge_after_draft :merge_after
             :merge_after_in_review :merge_after
             :merge_after_blocked :merge_after
             :reparent_draft :reparent
             :reparent_blocked :reparent
             :move_domain_draft :move_domain
             :move_domain_blocked :move_domain
             :move_domain_in_review :move_domain_carried
             :move_domain_deferred :move_domain_carried
             :note_red_in_review :note_red
             :note_green_in_review :note_green
             :note_green_draft :note_green
             :note_green_blocked :note_green
             :note_green_deferred :note_green}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :display {:title "{data.title}"}
   :filterable {:state #{:eq :in}
                :type #{:eq :in}
                :priority #{:eq :range}
                :parent #{:eq :set}
                ;; ticket cfcfcdd7: showcase_set=true is the tickets that name a
                ;; scene, which a dashboard slot lists as the epics
                :showcase #{:set}
                :found_in #{:eq}
                :repo #{:eq :in}
                :domain #{:eq :in}
                ;; ticket ab77e635: a seat's inbox cue names the domain
                ;; that asked, and a query may too
                :requested_by #{:eq}
                ;; epic 46831b3c: a requester's sight is this filter
                :filed_by #{:eq}
                :bead_id #{:eq :set}}
   ;; a ticket born before domains stores none, and is factory's
   :absent-as {:domain "factory"}
   ;; THE QUEUE IS THE COLLECTION UNDER ITS DEFAULT FILTER: a walker
   ;; opens /api/tickets and gets the work that is READY — groomed,
   ;; not blocked, not deferred — lowest priority number first, and the
   ;; older of two tickets at one priority before the newer (ticket
   ;; c944fd8c: an id is random, and broke that tie in no order).
   :default-filters {:state "open"}
   :sortable {:fields [:priority :created_at]
              :default ["priority" "created_at"]}
   :schema (into [:map] (concat stated-fields birth-fields engine-fields))
   ;; THE BIRTH IS THE STATEMENT AND WHERE IT SITS. The blockers, the
   ;; date and the ending are on no birth: a ticket is born a draft,
   ;; and the doors below are how it becomes ready and stops being.
   :create-schema (into [:map] (concat stated-fields birth-fields))
   :create-guards [the-parent-is-open-at-birth the-merge-order-makes-no-cycle
                   the-domain-is-one-we-have only-a-mayor-asks-another-domain
                   no-open-ticket-names-this-failure
                   the-parent-is-not-waited-on]
   ;; a fired seat's follow-up lands at 4 and a groomer raises it; a
   ;; person or an interactive seat is born at what it named
   ;; and every birth takes its domain (ticket 20fab5f9)
   :on-create stamp-the-birth
   ;; ticket cbf84f80: a child shows the scene its parent names
   :computed {:parent_scene
              {:schema [:maybe :string]
               :x-display
               {:widget "prose"
                :label "Part of the scene"
                :help "The showcase of the ticket this one is a piece of, read from the parent each time. It says which scene this work serves. Empty when there is no parent or the parent names no showcase."}
               ;; it reads the parent: with no :read it is absent
               :reads? true
               :fn parent-scene}}
   :actions
   ;; THE EVERYDAY DOORS (ticket f66dd175). Eight doors are marked
   ;; `:everyday` in their display: restate, groom, block, unblock,
   ;; defer, resume, complete and drop. They are the ones a person's
   ;; hand walks, and a row's bar keeps them in view while they are
   ;; shut. The others are the engine's or a change's, or a rarer
   ;; statement, and a shut one of those is drawn under 'More doors'.
   ;; A blocked ticket is restated where it stands too (ticket
   ;; 470abe2a): the way round was `unblock`, which lands in `open` and
   ;; wakes a seat on work whose blockers have not ended. `blocked_by`
   ;; and `blocked_from` are not stated fields, so the handler leaves
   ;; them as they stand.
   {:restate
    {:from #{:draft :blocked} :to :same
     :input (into [:map] stated-fields)
     :guards [the-merge-order-makes-no-cycle]
     :handler restate-the-ticket
     :record true
     :edit {:prefill [:title :detail :showcase :type :repo :merge_after]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restate" :order 2 :everyday true
               :description "Say what needs doing again, whole"}}

    ;; AN OPEN EPIC TAKES ITS EVIDENCE WHERE IT STANDS (ticket
    ;; ce2e4008). An epic is open while its children are worked, and
    ;; the film exists only when they are done. The way round was
    ;; `ungroom`, `restate` and `complete` from draft, by hand. This
    ;; self-loop writes `showcase.evidence` and leaves the scene as
    ;; groomed. A ticket that is no epic is refused it.
    :show_evidence
    {:from #{:open} :to :open
     :input (into [:map] (filter #(= :showcase (first %))) stated-fields)
     :guards [only-an-epic-shows-evidence]
     :handler show-the-evidence
     :record true
     :edit {:prefill [:showcase]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Show the evidence" :order 31
               :description "Attach the film's link or the told scene — the scene stays as groomed"}}

    ;; GROOMING IS THE PERSON'S TAP. A draft becomes the queue's when
    ;; a person read it and stands behind it as written. The way back
    ;; is `ungroom`, so a statement that needs work leaves the queue
    ;; before it changes. The groom may say the priority the ticket
    ;; lands at, and it never lands at 4 (ticket f7420404).
    :groom
    {:guards [a-person-or-their-delegate-grooms an-epic-names-its-showcase
              a-parent-is-not-groomed
              a-groomed-ticket-lands-above-the-backlog
              the-receiving-mayor-answers-an-ask]
     :handler groom-the-ticket
     ;; not an edit door: a groom is one tap and is fenced on no version,
     ;; and a prefilled 4 would be refused by the door's own schema
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Groom" :style :primary :order 1 :everyday true
               :description "It is stated well enough to build as written — into the queue"}}

    :ungroom
    {:guards [a-person-or-their-delegate-grooms a-model-says-why-it-ungrooms]
     :handler note-the-shelving
     ;; the log keeps the reason, as shelve's does (ticket 1a7a763f)
     :record true
     :input [:map
             [:shelved_because {:optional true
                                :examples ["The detail names no door to change; it builds once it says which one."]
                                :x-display
                                {:widget "prose"
                                 :label "Why it goes back to draft"
                                 :help "One sentence for whoever grooms it next: what blocks this ticket as written, and what would unblock it. A model's ungroom must say it; a person's may leave it out. It is at most 480 characters."}}
              [:maybe [:string {:min 1 :max 480}]]]]
     ;; one tap, fenced on no version, as groom is
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Back to draft" :order 9
               :description "Out of the queue — the statement needs work before a seat builds it"}}

    :prioritize
    {:input [:map
             [:priority {:examples [1]
                         :x-display
                         {:label "Priority (0 first, 4 last)"
                          :help "The queue's own order: 0 is what the house wants next."}}
              [:int {:min 0 :max 4}]]]
     :guards [the-receiving-mayor-answers-an-ask]
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
    ;; and not reversible. `then: open` on a draft is the groom said
    ;; ahead of time (ticket cca6b000): the ending queues it.
    :block
    {:input [:map
             [:blocked_by {:kind :ticket
                           :x-display
                           {:label "Waits on"
                            :help "Every ticket that must end before this one is worked. State the whole set: this replaces the list, it does not add to it."}}
              [:vector {:min 1 :max 50} :waymark/ref]]
             [:then {:optional true
                     :x-display
                     {:label "When they end"
                      :choices then-choices
                      :help "Where a draft goes when the last ticket it waits on ends: open puts it in the queue, as groomed; draft, or nothing, returns it to draft. A ticket blocked from the queue goes back to the queue either way. A draft at priority 4 is the backlog and is not sent to the queue from here."}}
              [:maybe (into [:enum] (sort (keys then-choices)))]]]
     :guards [the-blockers-are-open-and-not-itself
              a-person-or-their-delegate-queues-a-draft
              a-draft-queued-ahead-lands-above-the-backlog]
     :handler state-the-blockers
     :edit {:prefill [:blocked_by :then]}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This ticket leaves the queue until the tickets it waits on end. When the last of them ends it goes back where it stood — the queue, or draft for a draft — and a person's unblock lands it in the queue sooner."}
     :display {:label "Blocked by" :order 4 :everyday true
               :description "Wait on other tickets — this one leaves the queue until they end"}}

    ;; THE MERGE ORDER, STATED WHOLE (ticket d069bc3b). It holds the
    ;; merge and never the build, so it is open wherever a change can
    ;; be waiting — after the pull request exists too. One self-loop
    ;; over every state a change can wait in.
    :merge_after
    {:from #{:draft :open :in_review :blocked} :to :same
     :input merge-after-input
     :guards [the-merge-order-makes-no-cycle]
     :handler state-the-merge-order
     :edit {:prefill [:merge_after]}
     :safety merge-after-safety
     :display {:label "Merges after" :order 17
               :description merge-after-description}}

    ;; THE PARENT, AFTER BIRTH (ticket 03658863). The birth's walls on
    ;; `parent` stand here too, and one more: no ticket goes under
    ;; itself. It is its own door and not a field of `restate`, because
    ;; a draft holds no blockers and `the-parent-is-not-waited-on`
    ;; would have nothing to judge there. One self-loop over the states
    ;; a hand shapes the tree in. `:record` keeps the old parent beside
    ;; the new one.
    :reparent
    {:from #{:draft :open :blocked} :to :same
     :input reparent-input
     :guards reparent-guards
     :handler reparent-the-ticket
     :record true
     :edit {:prefill [:parent]}
     :safety reparent-safety
     :display {:label "Part of" :order 23
               :description reparent-description}}

    ;; THE DOMAIN, AFTER BIRTH (ticket 66d080b0). The mayor of the domain
    ;; the ticket is in, or a person, moves it into another, and every
    ;; ticket under it and their open changes go with it in the one
    ;; transaction. One self-loop over the states a hand shapes the tree
    ;; in, as `reparent`. `:record` keeps the old domain beside the new
    ;; one.
    :move_domain
    {:from #{:draft :open :blocked} :to :same
     :input move-domain-input
     :guards move-domain-guards
     :handler move-the-domain
     :record true
     :edit {:prefill [:domain]}
     :safety move-domain-safety
     :display {:label "Move to a domain" :order 26
               :description move-domain-description}}

    ;; A ticket under review or deferred goes with the ticket above it,
    ;; and by no hand: hidden, and open only inside that ticket's move.
    ;; Its own door, not `move_domain`'s: the input, the guard, the
    ;; safety and the waiver all differ (the deviations say why).
    :move_domain_carried
    {:from #{:in_review :deferred} :to :same
     :input carried-domain-input
     :guards [only-a-parents-move-carries-it]
     :handler move-the-domain
     ;; the engine writes it with no version in hand: an `:edit` would
     ;; fence it
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Moved with its parent" :order 29
               :description carried-domain-description}}

    :unblock
    {:handler unblock-the-ticket
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Unblock" :style :primary :order 1 :everyday true
               :description "Back into the queue — nothing holds this one now"}}

    ;; THE ENGINE'S WAY BACK FOR A DRAFT. The last blocker's ending
    ;; opens it (release-the-waiters!), and no hand at the wire does:
    ;; a draft was never groomed, so its blockers ending does not put
    ;; it in the queue.
    :return_to_draft
    {:guards [only-an-ending-returns-a-ticket-to-draft]
     :handler clear-the-blockers
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Return to draft" :order 10
               :description "The last ticket it waited on ended — back to draft, to be groomed"}}

    ;; THE DECLINE'S WAY BACK (ticket 775b6427). The drop of a ticket
    ;; that names this one in `needed_by` walks it, and no hand does:
    ;; the other domain said no, so the asker leaves the queue, or its
    ;; wait, for draft with the reason. No new state.
    :turn_back
    {:from #{:draft :open :blocked} :to :draft
     :guards [only-a-decline-turns-the-asker-back]
     :handler note-the-decline
     ;; the log keeps the reason, so the live cracks pass can read two
     ;; shelvings for one reason (ticket 26e51658)
     :record true
     :input [:map
             [:shelved_because {:x-display {:hidden true
                                            :label "Why it was declined"}}
              [:string {:min 1 :max 480}]]]
     ;; the engine writes the decline's sentence, with no version in hand
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The domain this ticket asked for work dropped that work, so this ticket goes to draft with the reason and waits on nothing. A person's groom puts it back in the queue once its mayor has planned again."}
     :display {:label "Declined" :order 18
               :description "The domain it asked said no — back to draft, to be planned again"}}

    :defer
    {:input [:map
             [:defer_until {:examples ["2026-11-19"]
                            :x-display
                            {:label "Until"
                             :help "The day this ask comes back into the queue."}}
              :waymark/date]]
     :handler defer-the-ticket
     :edit {:prefill [:defer_until]}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Defer" :order 5 :everyday true
               :description "Not now — take it out of the queue until a day"}}

    :resume
    {:handler resume-the-ticket
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :style :primary :order 1 :everyday true
               :description "Back into the queue now"}}

    ;; THE TWO ENDINGS, from `draft` or `open`. A draft may already be
    ;; done, or not wanted, before anyone groomed it. A blocked or
    ;; deferred ticket is not finished; unblock or resume it, and then
    ;; end it. Both are one-way: `reopen` lands in `draft` and never in
    ;; `open`, because an ending that was wrong is an ask to read again.
    :complete
    {:from #{:draft :open} :to :done
     :input close-input
     :guards [children-are-finished a-draft-epic-shows-its-evidence
              an-epic-shows-its-evidence]
     :handler close-the-ticket
     ;; the sentence is composed, so it is drafted (change's `stall`):
     ;; a mis-click must not discard what was typed
     :edit {:draft {:shared true :live true}}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the ending on the record, with its sentence, and a ticket that waited only on this one goes back where it was blocked from. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Complete" :style :primary :order 6 :everyday true
               :description "The work is done — say what was done"}}

    :drop
    {:input close-input
     ;; a drop of a ticket another domain asked for is the decline
     :guards [children-are-finished the-receiving-mayor-answers-an-ask
              only-the-receiving-mayor-declines]
     :handler drop-the-ticket
     :edit {:draft {:shared true :live true}}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "This is the ending on the record, with its sentence, and a ticket that waited only on this one goes back where it was blocked from. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Drop" :style :danger :order 7 :everyday true
               :description "Let this go — say why"}}

    ;; ── THE CHANGE'S THREE DOORS (ticket 2e869934) ───────────────────
    ;; Walked by the change born from this ticket, inside its own
    ;; door, and by no hand: see `only-its-change-moves-it`. None is
    ;; fenced, because the change names no version it read. `review`
    ;; and `return` are the kit's link doors (ticket b220009b): their
    ;; machine and their wall are the core's, the rest is this kind's.
    :review
    {:safety {:idempotent true :reversible false :confirm false
              :one-way "The change built for this ticket was submitted, so the ticket leaves the queue while its pull request is reviewed. Its change sends it back if the checks go red, and its merge ends it."}
     :display {:label "Out for review" :order 11
               :description "Its change was submitted — out of the queue while the pull request is reviewed"}}

    ;; one door back for every way a review ends without a merge, so a
    ;; seat's subscriptions hear one action: `return`, not `resume` —
    ;; `resume` is a person's door out of `deferred`, and a wake on it
    ;; would read a person's not-now as the checks' red
    :return
    {:safety {:idempotent true :reversible false :confirm false
              :one-way "Its change went red, closed without a merge or was put back to work, so the ticket is in the queue again and the seat that wrote the change walks it next. The change's next submit sends it out for review again."}
     :display {:label "Back from review" :order 12
               :description "Its change went red, closed or was put back to work — into the queue again"}}

    ;; a stall's way back (ticket 6bdaf6fe): the seat said it cannot
    ;; build this as written, so the ticket leaves the queue for draft,
    ;; where a person reads the stall and grooms it again — and a queue
    ;; of tickets beside stuck changes no longer wakes the seat
    :shelve
    {:guards [only-its-change-moves-it]
     :handler note-the-shelving
     ;; the log keeps the reason, so the live cracks pass can read two
     ;; shelvings for one reason (ticket 26e51658)
     :record true
     :input [:map
             [:shelved_because {:optional true
                                :x-display {:hidden true
                                            :label "Why its change was stalled"}}
              [:maybe [:string {:max 480}]]]]
     ;; the engine writes the stall's sentence, with no version in hand
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The seat stalled the change built for this ticket, so the ticket leaves the queue for draft. A person's groom puts it back, and puts its change back to work in the same move; a change with a pull request goes back under review at the next sit."}
     :display {:label "Stalled" :order 17
               :description "Its change stalled — back to draft, to be groomed again"}}

    ;; THE GROOM'S FOLLOW-UP (ticket 7e01dbe5). A groom, unblock or
    ;; resume that puts a stuck pull request back under review lands
    ;; the ticket in `open`, its declared landing, beside a submitted
    ;; change. Its handler queues this door through ctx :follow-up, and
    ;; the engine walks it once that write commits. A follow-up carries
    ;; no `:within`, so `review` would refuse it; this door is walled on
    ;; the fact instead (`its-change-is-under-review`).
    :rejoin_review
    {:from #{:open} :to :in_review
     :guards [its-change-is-under-review]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A change built for this ticket is submitted, so the ticket leaves the queue while its pull request is reviewed. Its change sends it back if the checks go red, and its merge ends it."}
     :display {:label "Out for review again" :order 26
               :description "Its pull request is back under review — out of the queue with it"}}

    ;; the merge's ending, from every state that has not ended (ticket
    ;; 3ec37f66): `in_review` most days, `open` for a change a person
    ;; merged while it was red, and `draft`, `blocked` or `deferred`
    ;; for a ticket a stall, a groomer or a person moved while its pull
    ;; request waited green. GitHub merged it whatever the queue says.
    ;; Not `complete`: that door is a hand's, and fenced by its draft.
    ;; Two landings (ticket 35c1e826): `done`, or `in_review` over a
    ;; child that has not ended (`land-the-merge`).
    :land
    {:from #{:draft :open :in_review :blocked :deferred} :to #{:done :in_review}
     :input [:map
             [:close_reason {:optional true
                             :x-display {:hidden true
                                         :label "How it ended"}}
              [:maybe [:string {:min 1 :max 480}]]]
             [:merged_change {:optional true
                              :x-display {:hidden true :raw true
                                          :label "The merged pull request"}}
              [:maybe [:string {:min 1 :max 400}]]]
             [:merged_change_id {:optional true
                                 :x-display {:hidden true :raw true
                                             :label "The merged change's id"}}
              [:maybe [:string {:min 1 :max 400}]]]]
     :guards [only-its-change-moves-it]
     :handler land-the-merge
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
    ;; is written on the one open ticket (one self-loop over the states
    ;; it serves), and a green base ends it with `mend`,
    ;; whether or not its own change merged.
    :note_red
    {:from #{:open :in_review} :to :same
     :guards [only-the-base-pass-writes-this]
     :handler note-a-red-head
     :input [:map
             [:red_head {:x-display {:hidden true :raw true
                                     :label "The red head"}}
              [:string {:min 1 :max 400}]]]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Red head noted" :order 14
               :description "The base went red again on a new head"}}

    ;; ── GREEN ON ITS OWN, WITH WORK ON THE BENCH (ticket d5000a1c) ───
    ;; `mend` ends the ticket, and the ending closes its open change.
    ;; A change that holds work is not closed: the base pass walks this
    ;; door in place of `mend`, and the ticket stays where it is. A seat
    ;; that stalled its change sent the ticket to draft, and a person
    ;; may have blocked or deferred it since (ticket e666bd6a): one
    ;; self-loop over every state it serves. A ticket under
    ;; review is served too (ticket 1188f3ed): `work-kept` reads every
    ;; change born from the ticket, and one with no pull request may
    ;; hold work beside the submitted one.
    :note_green
    {:from #{:draft :open :in_review :blocked :deferred} :to :same
     :guards [only-the-base-pass-writes-this]
     :handler note-a-green-base
     :input [:map
             [:green_note {:x-display {:hidden true :raw true
                                       :label "Green on its own"}}
              [:string {:min 1 :max 480}]]]
     ;; `note_merge`'s reason: the engine writes a first value onto a
     ;; blank field, with no version in hand
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Green on its own" :order 17
               :description "The base went green while this ticket's change held work"}}

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

    ;; ── A MERGE BEFORE THE CHILDREN (ticket 499bcd72) ───────────────
    ;; `land` over an open child writes the pull request on the ticket
    ;; and leaves it in review, and the last child's ending walks
    ;; `finish` (`finish-the-parent!`). It is not a hand's door.
    :finish
    {:from #{:in_review} :to :done
     :input close-input
     :guards [only-a-childs-ending-finishes-it]
     :handler close-the-ticket
     ;; `land`'s reasons: only the engine walks it, nobody composes the
     ;; sentence in a box, and it reaches the row with no version in hand
     :waives #{:edit-shape :large-effort}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Its pull request merged earlier and its last child ended, and that is the ending on the record. The way back is a person's reopen, which lands the ticket in draft to be groomed again."}
     :display {:label "Merged, children done" :order 22
               :description "Its pull request merged before its children ended, and the last one has"}}

    :reopen
    {:guards [only-a-person-reopens]
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
   ["`restate` serves `draft` and `blocked`, one `:to :same` door, and `prioritize` serves `open` alone. A groomed statement is what the seat builds, so changing it is `ungroom` and then `restate`. A blocked ticket is restated where it stands: the way round was `unblock`, which lands in `open` and wakes a seat before the blockers end. A deferred ticket is restated when it returns; a blocked or deferred ticket is ranked when it returns to the queue, which is where its rank matters."
    "`show_evidence` (open → open) is the one door that writes a stated field on a groomed ticket. It serves an epic alone and lands `showcase.evidence` alone: the handler keeps the stored scene and format, so what a seat builds from does not move. The law that asks for the evidence is two guards on `complete`, `a-draft-epic-shows-its-evidence` and `an-epic-shows-its-evidence`, because a guard's remedies do not read the row's state and each refusal must name a door afforded where the epic stands."
    "`complete`, `drop` and `block` are one-way, not reversible. Each leaves from more than one state and its reverse lands in one (`reopen` in `draft`, `unblock` in `open`), and checks/check-reversible asks a reversible door for a way back to each `:from`. The way back is real in every case, and the `:one-way` sentence names it."
    "`merge_after` is one `:to :same` door over each state a change can wait in (`draft`, `open`, `in_review`, `blocked`). On a ticket under review it is the one door a hand may take, because it holds the merge and moves no state."
    "`reparent` is one `:to :same` door over the states a hand shapes the tree in (`draft`, `open`, `blocked`). It is not a field of `restate`: a draft holds no blockers (`return_to_draft` clears them), and so `the-parent-is-not-waited-on` could never refuse on that door. A ticket under review or deferred is not re-parented; it is when it returns."
    "A domain move is two `:to :same` doors, not one. `move_domain` is a hand's, over the states a hand shapes the tree in (`draft`, `open`, `blocked`, as `reparent`), fenced by its `:edit`. `move_domain_carried` serves `in_review` and `deferred`: hidden, open only inside the move of a ticket above, irreversible, and waiving `:edit-shape` because the engine's carried move writes with no version in hand. One door would either fence the carried move or drop the hand's guards and edit. So a ticket under review or deferred goes with its parent, and is moved alone when it returns. An ended ticket takes no door and keeps the domain it ended in; the tickets under it are still walked."
    "`reopen` does not read the parent. A child reopened under an ended parent leaves that parent done over open work, and a person reopens the parent next; the birth door refuses the same shape (`the-parent-is-open-at-birth`). A guard on `reopen` that read the parent would take that door's scenarios out of the check tier, and the person-wall on it is the law this kind is graded by."
    "`the-receiving-mayor-answers-an-ask`, on `groom`, `prioritize` and `drop`, declares `:reads [:principal]` and reads more, and so does `only-the-receiving-mayor-declines` on `drop`: the receiving domain's row and the caller's grants, through the ctx hook, for a ticket one domain asked of another. With no hook it allows, which is the check tier's answer for every guard that reaches past its `:reads`. Declaring those kinds would take `groom`'s scenarios out of the check tier, and no declaration in this module names the seat kind. Its law is proved in factory10.ticket-test over a fake hook."]
   :scenarios [a-seat-does-not-groom-a-ticket
               the-person-grooms-a-ticket
               an-epic-is-not-groomed-without-its-showcase
               an-epic-with-its-showcase-is-groomed
               a-seat-does-not-reopen-a-ticket
               the-person-reopens-a-ticket
               a-finished-ticket-is-not-put-back-by-a-side-door
               a-person-does-not-return-a-blocked-ticket-to-draft
               a-person-does-not-take-a-ticket-out-of-review]})

(defresource ticket (ask/build ask/core factory10s-own))
