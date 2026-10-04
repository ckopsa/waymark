(ns factory10.ticket-test
  "The ticket, judged with no database at all (docs/spec-ticket.md).

  WHAT THIS FILE OWNS is the claim the kind is FOR: that READY is the
  envelope's doing and not the charter's. Every assertion is about an
  ENVELOPE — which doors a row offers the hand in front of it — read
  through `render/action-availability`, tree_test's arrangement and
  for its reason. The three walls that read other tickets are judged
  over a FAKE hook: a ctx whose `:read` and `:find` answer from a map,
  which is the shape the engine's own probe ctx has and the suite's
  factories build (waymark10.test.factories/probe-ctx).

  The declaration gate — the usability battery, the remedies census
  and the check-tier scenarios — is tree_test's, and it iterates
  factory10.main/resources, so this kind is under it already.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.resources.change :refer [change]]
            [factory10.resources.ticket :as tk :refer [ticket]]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.dashboard :as dash]
            [waymark10.schema :as sch]
            [waymark10.server.collections :as collections]
            [waymark10.server.render :as render]
            [waymark10.server.seats :as seats]
            [waymark10.server.store.memory :as memory])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-09-26T14:00:00Z"))

(def ^:private a-ticket
  {:title "The code seat walks ticket rows"
   :detail "Switch the walk from task to ticket."
   :type "feature"
   :priority 1
   :repo "ckopsa/waymark"
   :blocked_by []})

(defn- at
  "One ticket row, in the state and with the document named."
  ([state] (at state {} "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"))
  ([state extra] (at state extra "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"))
  ([state extra id]
   {:kind :ticket :id id :state state :data (merge a-ticket extra)}))

(def ^:private the-seat {:id "code-seat" :type :agent :roles #{}})
(def ^:private the-person {:id "colton" :type :person :roles #{}})
(def ^:private the-engine {:id "waymark10" :type :system :roles #{}})

(defn- ctx
  "A probe ctx, with a fake store behind `:read` and `:find` when rows
  are given: `:read` answers by id, `:find` answers the rows whose
  data matches every pair of the where map."
  ([principal] (ctx principal nil))
  ([principal rows]
   (cond-> {:principal principal :now now :mode :probe}
     rows (assoc :read (fn [_kind id] (get rows (str id)))
                 :find (fn [_kind where _opts]
                         (filterv (fn [r]
                                    (every? (fn [[k v]]
                                              (= (str v) (str (get-in r [:data k]))))
                                            where))
                                  (vals rows)))))))

(defn- offers
  "The doors this row would advertise to this hand."
  [row c]
  (into (sorted-set)
        (keep (fn [a]
                (when (= :available
                         (:status (render/action-availability
                                   ticket (:name a) row c)))
                  (:name a))))
        (machine/actions-seq ticket)))

(defn- refusal [row c action]
  (render/action-availability ticket action row c))

;; ── the queue is the envelope's doing ───────────────────────────────

(deftest a-draft-is-groomed-by-a-person-and-never-by-a-seat
  (testing "a person at a draft meets groom, and the doors that shape it"
    (is (= #{:restate :groom :block :complete :drop :merge_after_draft
             :reparent_draft :move_domain_draft}
           (offers (at :draft) (ctx the-person)))
        "prioritize and defer are absent: a draft is not in the queue,
         so it has no rank there and nothing to park"))
  (testing "a seat at a draft meets everything but groom"
    (is (= #{:restate :block :complete :drop :merge_after_draft :reparent_draft}
           (offers (at :draft) (ctx the-seat)))
        "a seat that could groom could fill its own queue")
    (let [shut (refusal (at :draft) (ctx the-seat) :groom)]
      (is (= :unavailable (:status shut)))
      (is (= :a-person-or-their-delegate-grooms (:name (:denier shut))))))
  (testing "a delegate acting for a person is the person's hand"
    (is (= :available
           (:status (refusal (at :draft)
                             (ctx (assoc the-seat :acts-for "colton"))
                             :groom)))))
  (testing "and the engine's own hand grooms too"
    (is (= :available (:status (refusal (at :draft) (ctx the-engine) :groom))))))

;; ── an epic names its showcase (ticket cbf84f80) ────────────────────

(def ^:private a-showcase
  {:format "film"
   :scene "You open a proposal and cannot tell why anyone wants it. Then the scene plays, and you can."})

(deftest an-epic-is-groomed-with-its-showcase-and-not-without
  (let [epic (at :draft {:title "[epic] Every epic names its scene"})]
    (testing "an epic without a showcase refuses groom, and says the way"
      (let [shut (refusal epic (ctx the-person) :groom)]
        (is (= :unavailable (:status shut)))
        (is (= :an-epic-names-its-showcase (:name (:denier shut))))
        (is (re-find #"Restate it with a showcase" (str (:reason shut))))))
    (testing "with one it grooms"
      (is (= :available
             (:status (refusal (assoc-in epic [:data :showcase] a-showcase)
                               (ctx the-person) :groom)))))
    (testing "a plain ticket grooms without one"
      (is (= :available
             (:status (refusal (at :draft) (ctx the-person) :groom)))))
    (testing "the field is on the birth and on the restate"
      (is (contains? (into #{} (map first) (rest (:create-schema ticket)))
                     :showcase))
      (is (contains? (into #{} (map first)
                           (rest (get-in ticket [:actions :restate :input])))
                     :showcase)))))

;; ── the tickets that name a scene (ticket cfcfcdd7) ─────────────────

(deftest showcase-set-filters-to-the-tickets-with-a-showcase
  (let [rows [(at :open {:title "[epic] Every epic names its scene"
                         :showcase a-showcase} "E")
              (at :done {:showcase a-showcase} "D")
              (at :open {:parent "E"} "C")
              (at :draft {:showcase nil} "N")]
        ids (fn [params]
              (let [{:keys [conds]} (collections/parse-query ticket params)]
                (into #{}
                      (comp (filter (fn [row]
                                      (every? #(@#'memory/cond-matches? row %)
                                              conds)))
                            (map :id))
                      rows)))]
    (testing "true answers exactly the tickets with a showcase"
      (is (= #{"E" "D"} (ids {"showcase_set" "true" "state" ""}))))
    (testing "false answers the others, a child of an epic among them"
      (is (= #{"C" "N"} (ids {"showcase_set" "false" "state" ""}))))
    (testing "it composes with the states a panel of epics shows"
      (is (= #{"E"} (ids {"showcase_set" "true"
                         "state" "draft,open,blocked"}))))))

(deftest a-dashboard-slot-lists-the-tickets-with-a-showcase
  (let [problems (fn [where]
                   (dash/slot-problems (fn [_kind] ticket) (fn [_kind _id] nil)
                                       {:target "ticket" :where where}))]
    (is (= [] (problems "showcase_set=true&state=draft,open,blocked")))
    (testing "a presence filter is true or false"
      (is (re-find #"is not true or false"
                   (str (first (problems "showcase_set=maybe"))))))
    (testing "a field with no presence filter is still refused"
      (is (re-find #"not an :eq/:in-filterable field"
                   (str (first (problems "detail_set=true"))))))))

(deftest a-child-envelope-shows-its-parents-scene
  (let [parent (at :open {:title "[epic] Every epic names its scene"
                          :showcase a-showcase} "P")
        child (at :draft {:parent "P"} "C")
        rows {"P" parent "C" child}
        env (fn [row]
              (render/envelope ticket row
                               {:principal the-person :now now
                                :read (fn [_kind id] (get rows (str id)))}))]
    (is (= (:scene a-showcase) (get-in (env child) ["data" "parent_scene"]))
        "plumbing shows which scene it serves")
    (is (nil? (get-in (env parent) ["data" "parent_scene"]))
        "a ticket with no parent is part of no scene")
    (is (= "{data.title} · {state}" (:summary ticket))
        "and the summary line is unchanged")))

;; ── an epic ends on its evidence (ticket 056ac769) ──────────────────

(deftest an-epic-completes-with-its-evidence-and-not-without
  (let [epic (fn [showcase]
               (at :open {:title "[epic] Every epic names its scene"
                          :showcase showcase} "E"))
        told {:kind :journal :id "J" :state :written
              :data {:text "Before, the seat asked twice. After, it asked once."}}
        c (ctx the-person {"J" told})
        text-scene (assoc a-showcase :format "text")
        complete (fn [showcase] (refusal (epic showcase) c :complete))]
    (testing "an epic without evidence refuses complete, and says what to attach"
      (let [shut (complete a-showcase)]
        (is (= :unavailable (:status shut)))
        (is (= :an-epic-shows-its-evidence (:name (:denier shut))))
        (is (re-find #"film_url" (str (:reason shut))))))
    (testing "a film epic with its film link completes"
      (is (= :available
             (:status (complete (assoc a-showcase :evidence
                                       {:film_url "https://films.example/inbox"}))))))
    (testing "a film epic with only a scene_ref refuses"
      (let [shut (complete (assoc a-showcase :evidence {:scene_ref "journal:J"}))]
        (is (= :unavailable (:status shut)))
        (is (= :an-epic-shows-its-evidence (:name (:denier shut))))))
    (testing "a text epic completes on a scene_ref that names a row"
      (is (= :available
             (:status (complete (assoc text-scene :evidence
                                       {:scene_ref "journal:J"}))))))
    (testing "and refuses one that names no row"
      (let [shut (complete (assoc text-scene :evidence {:scene_ref "journal:nope"}))]
        (is (= :unavailable (:status shut)))
        (is (re-find #"names no row" (str (:reason shut))))))
    (testing "drop needs no evidence"
      (is (= :available (:status (refusal (epic a-showcase) c :drop)))))
    (testing "a plain ticket completes without a showcase"
      (is (= :available (:status (refusal (at :open) c :complete)))))))

(deftest an-open-ticket-is-the-queue-and-offers-every-working-door
  (testing "a seat at an open ticket meets the doors that end or park it"
    (is (= #{:prioritize :block :defer :complete :drop :merge_after :reparent}
           (offers (at :open) (ctx the-seat)))
        "restate is absent — a groomed statement is what the seat builds
         — and reopen, unblock and resume are absent — nothing ended it
         and nothing holds it"))
  (testing "a person meets the same doors and the way back to draft"
    (is (= #{:prioritize :block :defer :complete :drop :ungroom :merge_after
             :reparent :move_domain}
           (offers (at :open) (ctx the-person)))
        "what a seat may reach at all is the grant's question, not this
         kind's; ungroom is the one door here that is a person's")))

(deftest a-blocked-ticket-is-out-of-the-queue-and-waits
  (let [row (at :blocked {:blocked_by ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]})]
    (is (= #{:block :unblock :merge_after_blocked :reparent_blocked
             :restate_blocked :move_domain_blocked}
           (offers row (ctx the-person)))
        "restate the blockers, clear them, state what it merges after,
         what it is a piece of or what it asks — nothing else, because
         a blocked ticket is not worked and not ended")
    (let [shut (refusal row (ctx the-person) :complete)]
      (is (= :unavailable (:status shut)))
      (is (nil? (:denier shut))
          "the MACHINE refuses it, with no guard behind the refusal: a
           blocked ticket is not finished, so it is unblocked first"))))

(deftest a-blocked-ticket-is-restated-and-stays-blocked
  ;; ticket 470abe2a: the way round was `unblock`, which lands in `open`
  ;; and wakes a seat on a ticket whose blockers have not ended
  (let [row (at :blocked {:blocked_by ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]
                          :blocked_from "draft"})
        door (get (:actions ticket) :restate_blocked)
        draft-door (get (:actions ticket) :restate)
        said {:title "The code seat walks ticket rows"
              :detail "Switch the walk from task to ticket, and say why."
              :type "feature"
              :repo "ckopsa/waymark"}
        after ((:handler door) row said (ctx the-person))]
    (testing "a self-loop: the statement moves, the state does not"
      (is (= #{:blocked} (:from door)))
      (is (= :blocked (:to door)))
      (is (= #{:draft} (:from draft-door))
          "the draft door is as it was; an open ticket is ungroomed first"))
    (testing "it is the draft door, one state over"
      (is (= (:input draft-door) (:input door)))
      (is (= (:guards draft-door) (:guards door)))
      (is (= (:edit draft-door) (:edit door)))
      (is (true? (:record door))
          "the transition keeps the old statement beside the new one"))
    (testing "the new statement lands and the blockers stand"
      (is (= (:detail said) (get-in after [:data :detail])))
      (is (= ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"] (get-in after [:data :blocked_by])))
      (is (= "draft" (get-in after [:data :blocked_from]))
          "the last blocker's ending still returns it where it stood"))))

(deftest a-ticket-in-review-offers-no-hand-a-door-that-moves-it
  (doseq [c [(ctx the-person) (ctx the-seat) (ctx the-engine)]]
    (is (= #{:merge_after_in_review} (offers (at :in_review) c))
        "its change moves it back or ends it, from inside its own door;
         the wire meets only the merge order, which moves no state"))
  (let [shut (refusal (at :in_review) (ctx the-person) :return)]
    (is (= :only-its-change-moves-it (:name (:denier shut))))))

(deftest a-deferred-ticket-waits-on-its-day
  (is (= #{:resume} (offers (at :deferred {:defer_until "2026-11-19"})
                            (ctx the-person)))
      "one door back into the queue, and nothing that would end or
       rank a ticket the house said not-now about"))

(deftest the-two-endings-offer-reopen-to-a-person-and-nothing-to-a-seat
  (doseq [state [:done :dropped]]
    (let [row (at state {:close_reason "Merged: github:ckopsa/waymark#41."})]
      (testing (str (name state) " is a person's to reverse, into draft")
        (is (= #{:reopen} (offers row (ctx the-person))))
        (is (= :draft (:to (get (:actions ticket) :reopen)))
            "an ending that was wrong is an ask to read again")
        (is (= #{:reopen} (offers row (ctx the-engine)))
            "the engine's own hand passes too — the merge completes a
             ticket with it, and a person's undo of that is the same
             hand's to serve")
        (is (empty? (offers row (ctx the-seat)))
            "a seat that could reopen tickets could refill its own
             queue"))
      (testing "and the refusal says what it is and where to say so"
        (let [shut (refusal row (ctx the-seat) :reopen)]
          (is (= :unavailable (:status shut)))
          (is (= :only-a-person-reopens (:name (:denier shut))))
          (is (re-find #"person's correction" (str (:reason shut)))))))))

;; ── the three walls that read other tickets ─────────────────────────

(deftest a-parent-ends-after-its-children
  (let [parent (at :open {} "P")
        rows {"P" parent
              "C1" (at :done {:parent "P" :close_reason "done"} "C1")
              "C2" (at :blocked {:parent "P" :blocked_by ["C1"]} "C2")
              "C3" (at :open {:parent "P"} "C3")}]
    (testing "two children are not finished, and the door says so"
      (let [shut (refusal parent (ctx the-person rows) :complete)]
        (is (= :unavailable (:status shut)))
        (is (= :children-are-finished (:name (:denier shut))))
        (is (re-find #"2 of this ticket's children" (str (:reason shut)))
            "a blocked child is unfinished; a done one is not counted"))
      (is (= :unavailable
             (:status (refusal parent (ctx the-person rows) :drop)))
          "dropping a parent over open children is the same refusal"))
    (testing "with every child ended, the parent ends"
      (let [rows (-> rows
                     (assoc-in ["C2" :state] :dropped)
                     (assoc-in ["C3" :state] :done))]
        (is (= :available
               (:status (refusal parent (ctx the-person rows) :complete))))))
    (testing "and the probe with no hook advertises optimistically"
      (is (= :available (:status (refusal parent (ctx the-person) :complete)))
          "the render probe carries no store; the door judges again
           with a real one behind it"))))

(deftest a-ticket-waits-on-open-work-and-never-on-itself
  (let [row (at :open {} "T")
        rows {"T" row
              "B-open" (at :open {} "B-open")
              "B-done" (at :done {:close_reason "done"} "B-done")}]
    ;; action-availability probes with no input, so the guard's input
    ;; branch is judged directly here, the way the door will judge it.
    (let [guard (first (:guards (get (:actions ticket) :block)))
          judge (fn [named rows]
                  (let [[v _] (g/evaluate
                               guard row {:blocked_by named}
                               (ctx the-person rows))]
                    (:verdict v)))]
      (is (= :allow (judge ["B-open"] rows))
          "an open blocker is what the door is for")
      (is (= :deny (judge ["T"] rows))
          "a ticket cannot block itself")
      (is (= :deny (judge ["B-done"] rows))
          "a blocker that has ended holds nothing")
      (is (= :deny (judge ["nobody"] rows))
          "a blocker that is not a ticket is refused by name")
      (is (= :allow (judge ["B-done"] nil))
          "the probe with no hook declines to guess"))))

(deftest no-dependency-makes-a-cycle
  ;; ticket d069bc3b: one walk over blocked_by and merge_after together
  (let [row (at :open {} "T")
        merge-guard (first (:guards (get (:actions ticket) :merge_after)))
        block-guard (first (:guards (get (:actions ticket) :block)))
        judge (fn [guard inp rows]
                (first (g/evaluate guard row inp (ctx the-person rows))))
        base {"T" row "A" (at :open {} "A") "B" (at :open {} "B")}]
    (testing "a write that makes no cycle passes"
      (is (= :allow (:verdict (judge merge-guard {:merge_after ["A"]} base))))
      (is (= :allow (:verdict (judge block-guard {:blocked_by ["A"]} base)))))
    (testing "a direct cycle is refused with its path"
      (let [rows (assoc base "A" (at :open {:merge_after ["T"]} "A"))
            v (judge merge-guard {:merge_after ["A"]} rows)]
        (is (= :deny (:verdict v)))
        (is (re-find #"T → A → T" (pr-str v)))))
    (testing "a three-long cycle is refused with its path"
      (let [rows (-> base
                     (assoc "A" (at :open {:merge_after ["B"]} "A"))
                     (assoc "B" (at :open {:merge_after ["T"]} "B")))
            v (judge merge-guard {:merge_after ["A"]} rows)]
        (is (= :deny (:verdict v)))
        (is (re-find #"T → A → B → T" (pr-str v)))))
    (testing "a cycle mixed across the two fields is refused, either way"
      (let [rows (assoc base "A" (at :blocked {:blocked_by ["T"]} "A"))
            v (judge merge-guard {:merge_after ["A"]} rows)]
        (is (= :deny (:verdict v)))
        (is (re-find #"T → A → T" (pr-str v))))
      (let [rows (assoc base "B" (at :open {:merge_after ["T"]} "B"))
            v (judge block-guard {:blocked_by ["B"]} rows)]
        (is (= :deny (:verdict v)))
        (is (re-find #"T → B → T" (pr-str v)))))
    (testing "an ended ticket waits on nothing, so it closes no cycle"
      (let [rows (assoc base "A" (at :done {:merge_after ["T"]
                                             :close_reason "done"} "A"))]
        (is (= :allow (:verdict (judge merge-guard {:merge_after ["A"]} rows))))))
    (testing "itself, and a ticket that is not one, are refused"
      (is (= :deny (:verdict (judge merge-guard {:merge_after ["T"]} base))))
      (is (= :deny (:verdict (judge merge-guard {:merge_after ["nobody"]} base)))))
    (testing "restate and the birth judge the same field"
      (is (some #{merge-guard} (:guards (get (:actions ticket) :restate))))
      (is (some #{merge-guard} (:create-guards ticket))))))

(deftest a-ticket-never-waits-on-its-parent-or-any-ancestor
  ;; ticket 59b4912d: a parent ends after its children, so the wait never would
  (let [rows {"G" (at :open {} "G")
              "P" (at :open {:parent "G"} "P")
              "C" (at :open {:parent "P"} "C")
              "U" (at :open {} "U")}
        block-guard (first (:guards (get (:actions ticket) :block)))
        judge (fn [named]
                (first (g/evaluate block-guard (get rows "C") {:blocked_by named}
                                   (ctx the-person rows))))]
    (testing "blocking a child on its parent refuses, and names merge_after"
      (let [v (judge ["P"])]
        (is (= :deny (:verdict v)))
        (is (re-find #"P is this ticket's parent" (pr-str v)))
        (is (re-find #"merge_after" (pr-str v)))))
    (testing "blocking on a grandparent refuses"
      (let [v (judge ["U" "G"])]
        (is (= :deny (:verdict v)))
        (is (re-find #"G is an ancestor of this ticket" (pr-str v)))))
    (testing "blocking on an unrelated open ticket still works"
      (is (= :allow (:verdict (judge ["U"])))))
    (testing "setting a parent the ticket already waits on refuses"
      (let [guard (last (:create-guards ticket))
            row (at :blocked {:blocked_by ["G"]} "C")
            judge-parent (fn [parent]
                           (:verdict (first (g/evaluate guard row {:parent parent}
                                                        (ctx the-person rows)))))]
        (is (= :deny (judge-parent "G")) "the parent it waits on")
        (is (= :deny (judge-parent "P")) "a parent below the ticket it waits on")
        (is (= :allow (judge-parent "U")))
        (is (= :allow (judge-parent nil)))))))

(deftest a-ticket-is-re-parented-under-the-parent-walls
  ;; ticket 03658863: `parent` was a birth field alone, so its walls
  ;; never judged a write at the wire
  (let [rows {"G" (at :open {} "G")
              "P" (at :open {:parent "G"} "P")
              "C" (at :blocked {:parent "P" :blocked_by ["B"]} "C")
              "K" (at :open {:parent "C"} "K")
              "B" (at :open {} "B")
              "BK" (at :open {:parent "B"} "BK")
              "U" (at :open {} "U")
              "E" (at :done {:close_reason "done"} "E")}
        row (get rows "C")
        door (get (:actions ticket) :reparent_blocked)
        c (ctx the-person rows)
        ;; the door's guards in its order, as the door will judge them:
        ;; the first that denies, by name, with what it said
        judge (fn [parent]
                (or (some (fn [guard]
                            (let [[v _] (g/evaluate guard row {:parent parent} c)]
                              (when (= :deny (:verdict v))
                                {:verdict :deny
                                 :guard (:name guard)
                                 :said (pr-str v)})))
                          (:guards door))
                    {:verdict :allow}))
        moved (fn [parent]
                (get-in ((:handler door) row {:parent parent} c) [:data :parent]))]
    (testing "each waiting state has the door, with the same walls"
      (doseq [[action state] {:reparent :open
                              :reparent_draft :draft
                              :reparent_blocked :blocked}]
        (let [a (get (:actions ticket) action)]
          (is (= #{state} (:from a)))
          (is (= state (:to a)) "a self-loop: the parent moves, the state does not")
          (is (= (:guards door) (:guards a)))
          (is (true? (:record a))
              "the transition keeps the old parent beside the new one"))))
    (testing "re-parenting to an open ticket works"
      (is (= :allow (:verdict (judge "U"))))
      (is (= "U" (moved "U"))))
    (testing "to a ticket this one is blocked by, or one under it, refuses"
      (let [v (judge "B")]
        (is (= :deny (:verdict v)))
        (is (= :the-parent-is-not-waited-on (:guard v)))
        (is (re-find #"B is this ticket's parent" (:said v))))
      (let [v (judge "BK")]
        (is (= :the-parent-is-not-waited-on (:guard v)))
        (is (re-find #"B is an ancestor of this ticket" (:said v)))))
    (testing "to one of its own descendants, or to itself, refuses"
      (let [v (judge "K")]
        (is (= :deny (:verdict v)))
        (is (= :the-parent-is-not-below-it (:guard v)))
        (is (re-find #"K is under this ticket" (:said v))))
      (let [v (judge "C")]
        (is (= :the-parent-is-not-below-it (:guard v)))
        (is (re-find #"this ticket names itself" (:said v)))))
    (testing "to a ticket that has ended refuses, as a birth under it does"
      (is (= :the-parent-is-open-at-birth (:guard (judge "E")))))
    (testing "clearing the parent works"
      (is (= :allow (:verdict (judge nil))))
      (is (nil? (moved nil))))))

(deftest a-child-is-born-under-an-open-parent
  (let [rows {"P-open" (at :open {} "P-open")
              "P-done" (at :done {:close_reason "done"} "P-done")}
        birth (first (:create-guards ticket))
        judge-birth (fn [parent]
                      (let [[v _] (g/evaluate
                                   birth nil {:parent parent}
                                   (ctx the-person rows))]
                        (:verdict v)))]
    (is (= :allow (judge-birth nil)) "no parent is the ordinary birth")
    (is (= :allow (judge-birth "P-open")))
    (is (= :deny (judge-birth "P-done"))
        "a ticket born under an ended parent is work nobody will find")
    (is (= :deny (judge-birth "nobody")))
    (is (= :allow (let [[v _] (g/evaluate birth nil {:parent "P-done"}
                                          (ctx the-person))]
                    (:verdict v)))
        "the probe with no hook declines to guess")))

(deftest a-birth-under-an-invented-parent-refuses-at-the-door
  ;; waymark-fp62.4.1 acceptance: the framework's ref wall, not the
  ;; ticket's own guard, names the field and the kind it expected
  (let [rows {"P-open" (at :open {} "P-open")}
        wall (g/names-a-row-that-stands (:create-ref-fields ticket))
        c (assoc (ctx the-person rows)
                 :rdef-of (fn [k] (when (= :ticket k) ticket)))
        judge (fn [inp]
                (let [[v d] (g/evaluate wall nil inp c)]
                  {:verdict (:verdict v) :reason (g/render-reason d v nil)}))]
    (is (some #(= :parent (:field %)) (:create-ref-fields ticket))
        "parent is a ref the create door carries")
    (is (= :allow (:verdict (judge {:parent "P-open"}))))
    (let [{:keys [verdict reason]} (judge {:parent "invented-01"})]
      (is (= :deny verdict))
      (is (re-find #"parent" reason) "the sentence names the field")
      (is (re-find #"ticket" reason) "and the kind it expected"))))

;; ── a fired seat's ticket lands at 4 (ticket b0ec4d47) ─────────────────

(def ^:private the-sittings
  [{:kind :sitting :id "S-fired" :state :open
    :data {:grant "G-code-seat" :mode "fired"}}
   {:kind :sitting :id "S-mayor" :state :open
    :data {:grant "G-mayor" :mode "interactive"}}])

(defn- born
  "The row the birth hook makes of a create at `priority`, by `principal`
  wearing `grant-id` (nil for none), over a fake store of `the-sittings`."
  [principal grant-id priority]
  ((:on-create ticket)
   (at :draft {:priority priority})
   (cond-> (assoc (ctx principal)
                  :find (fn [kind where _opts]
                          (if (= :sitting kind)
                            (filterv #(= (str (:grant where))
                                         (get-in % [:data :grant]))
                                     the-sittings)
                            [])))
     grant-id (assoc :grant {:id grant-id}))))

(deftest a-fired-seats-ticket-lands-at-four
  (testing "a fired seat's create at 1 lands at 4 and keeps what it asked"
    (let [{:keys [data]} (born the-seat "G-code-seat" 1)]
      (is (= 4 (:priority data)))
      (is (= 1 (:asked_priority data)))))
  (testing "a person's create at 2 lands at 2"
    (let [{:keys [data]} (born the-person nil 2)]
      (is (= 2 (:priority data)))
      (is (nil? (:asked_priority data)))))
  (testing "mayor's interactive create at 2 lands at 2"
    (let [{:keys [data]} (born {:id "mayor" :type :agent :roles #{}} "G-mayor" 2)]
      (is (= 2 (:priority data)))
      (is (nil? (:asked_priority data))))))

;; ── a ticket and a change carry the domain (ticket 20fab5f9) ──────────

(def ^:private the-house
  {"G-house" {:kind :grant :id "G-house" :data {:seat "S-house"}}
   "G-plain" {:kind :grant :id "G-plain" :data {:seat "S-plain"}}
   "S-house" {:kind :seat :id "S-house"
              :data {:name "house-seat" :domain "D-house"}}
   "S-plain" {:kind :seat :id "S-plain" :data {:name "plain-seat"}}
   "D-house" {:kind :domain :id "D-house" :data {:name "household"}}
   "P-infra" (at :open {:domain "infra"} "P-infra")
   "P-plain" (at :open {} "P-plain")})

(defn- born-in
  "The domain the birth hook stamps on a create with `extra`, by a hand
  wearing `grant-id` (nil for none), over the fake store `the-house`."
  [grant-id extra]
  (get-in ((:on-create ticket)
           (at :draft extra "NEW")
           (cond-> (ctx the-seat the-house)
             grant-id (assoc :grant {:id grant-id})))
          [:data :domain]))

(deftest a-ticket-is-born-in-a-domain
  (testing "a ticket with a parent takes its parent's domain"
    (is (= "infra" (born-in nil {:parent "P-infra"})))
    (is (= "infra" (born-in "G-house" {:parent "P-infra"}))
        "and not the filing seat's"))
  (testing "a parent that stores none leaves its child with none"
    (is (nil? (born-in "G-house" {:parent "P-plain"}))))
  (testing "a ticket with no parent takes the filing seat's domain name"
    (is (= "household" (born-in "G-house" {}))))
  (testing "a seat that stores no domain, and a hand with no seat, stamp none"
    (is (nil? (born-in "G-plain" {})))
    (is (nil? (born-in nil {})))))

(deftest a-change-takes-its-tickets-domain
  (let [born (fn [data]
               (get-in ((:on-create change)
                        {:kind :change :id "CH" :state :open :data data}
                        (ctx the-engine the-house))
                       [:data :domain]))]
    (is (= "infra" (born {:born_from "ticket:P-infra"})))
    (testing "a ticket that stores none leaves the change with none"
      (is (nil? (born {:born_from "ticket:P-plain"}))))
    (testing "and so does a change the forge minted"
      (is (nil? (born {:change_id "github:ckopsa/waymark#1"}))))))

(deftest a-row-that-stores-no-domain-filters-as-factory
  (doseq [[rdef kind] [[ticket :ticket] [change :change]]]
    (let [rows [{:kind kind :id "OLD" :state :open :data {}}
                {:kind kind :id "HOME" :state :open :data {:domain "household"}}]
          ids (fn [params]
                (let [{:keys [conds]} (collections/parse-query rdef params)]
                  (into #{}
                        (comp (filter (fn [row]
                                        (every? #(@#'memory/cond-matches? row %)
                                                conds)))
                              (map :id))
                        rows)))]
      (testing (name kind)
        (is (= #{"OLD"} (ids {"domain" "factory" "state" ""})))
        (is (= #{"HOME"} (ids {"domain" "household" "state" ""})))))))

(deftest a-dashboard-slot-filters-by-domain
  (is (= [] (dash/slot-problems (fn [_kind] ticket) (fn [_kind _id] nil)
                                {:target "ticket" :where "domain=factory"})))
  (is (= [] (dash/slot-problems (fn [_kind] change) (fn [_kind _id] nil)
                                {:target "change" :where "domain=factory"}))))

;; ── the shape the walker and the import both read ───────────────────

(deftest the-declaration-says-what-the-walker-needs
  (is (= [:draft :open :in_review :blocked :deferred :done :dropped] (:states ticket)))
  (is (= :draft (:initial ticket))
      "born a draft: nothing walks it until a person grooms it")
  (is (= #{} (:terminal ticket)) "no tomb: reopen is a person's door")
  (is (= #{:done} (get-in ticket [:over :accomplished])))
  (is (= #{:dropped} (get-in ticket [:over :let-go])))
  (is (= {:state "open"} (:default-filters ticket))
      "READY is the collection under its default filter — a draft,
       blocked or deferred ticket is out of it by construction, so the
       code seat walks this kind and never reads a ticket it cannot
       work")
  (is (= "priority" (get-in ticket [:sortable :default]))
      "lowest number first, which is the queue's own order")
  (let [form (into #{} (map first) (rest (:create-schema ticket)))]
    (is (not (contains? form :blocked_by))
        "a ticket is born a draft; block is a door, not a birth")
    (is (not (contains? form :close_reason)))
    (is (not (contains? form :defer_until)))
    (is (contains? form :bead_id)
        "the import keeps the id each ask carried in beads"))
  (is (= 20000 tk/detail-chars)))

;; ── the change's `why` says its limit (ticket e527f233) ─────────────
;; A seat walking a ticket learns the 480 characters before it spends
;; a refused submit or stall on them: the help names the limit, and an
;; over-long sentence refuses with its own length and the limit both.

(defn- why-entry
  "The `why` entry of a change action's input form: [:why props schema]."
  [action]
  (some #(when (and (vector? %) (= :why (first %))) %)
        (get-in change [:actions action :input])))

(deftest the-why-help-names-the-limit
  (doseq [action [:submit :stall]]
    (testing (name action)
      (let [[_ props schema] (why-entry action)]
        (is (str/includes? (get-in props [:x-display :help]) "480"))
        (is (= 480 (get-in schema [1 :max])) "the limit itself is unchanged")))))

(deftest an-over-long-why-says-both-numbers
  (doseq [action [:submit :stall]]
    (testing (name action)
      (let [form (get-in change [:actions action :input])
            said (first (:why (sch/closed-errors form {:why (apply str (repeat 512 "x"))})))]
        (is (string? said))
        (is (str/includes? said "480"))
        (is (str/includes? said "512"))
        (is (nil? (sch/closed-errors form {:why (apply str (repeat 480 "x"))})))))))

;; ── every one-sentence door says its limit (ticket 7a4baff9) ────────
;; The limit a door's help names is the one its schema declares and the
;; one the door enforces: the last length it takes, and one more refused.

(defn- the-limit-said
  "The number of characters a help sentence names as its limit."
  [help]
  (some-> (re-find #"at most (\d+) characters" help) second parse-long))

(deftest a-one-sentence-door-declares-the-limit-it-enforces
  (doseq [[kind resource action field]
          [["change" change :submit :why]
           ["change" change :stall :why]
           ["change" change :close_without_pr :why]
           ["ticket" ticket :complete :close_reason]
           ["ticket" ticket :drop :close_reason]]]
    (testing (str kind "." (name action))
      (let [form (get-in resource [:actions action :input])
            [_ props schema] (some #(when (and (vector? %) (= field (first %))) %) form)
            declared (get-in schema [1 :max])
            sentence #(apply str (repeat % "x"))]
        (is (= 480 declared))
        (is (= declared (the-limit-said (get-in props [:x-display :help])))
            "the help says the limit the schema declares")
        (is (nil? (sch/closed-errors form {field (sentence declared)}))
            "a sentence of the declared length is taken")
        (is (seq (get (sch/closed-errors form {field (sentence (inc declared))}) field))
            "one character more is refused")))))

;; ── one domain asks another (ticket 775b6427) ─────────────────────────

(defn- sat [seat domain]
  {(str "G-" seat) {:kind :grant :id (str "G-" seat) :state :accepted
                    :data {:seat (str "S-" seat) :audience seat}}
   (str "S-" seat) {:kind :seat :id (str "S-" seat)
                    :data {:name seat :domain domain}}})

(def ^:private two-domains
  (merge (sat "house-mayor" "D-house")
         (sat "house-hand" "D-house")
         (sat "infra-mayor" "D-infra")
         {"D-house" {:kind :domain :id "D-house"
                     :data {:name "household" :mayor "S-house-mayor"}}
          "D-infra" {:kind :domain :id "D-infra"
                     :data {:name "infra" :mayor "S-infra-mayor"}}
          "ASK" (at :blocked {:domain "household" :blocked_by ["NEW"]
                              :blocked_from "open"}
                     "ASK")}))

(defn- sitter
  "The delegate that sits in `seat`, over the fake store `two-domains`."
  [seat]
  (assoc (ctx {:id seat :type :agent :roles #{} :acts-for "colton"} two-domains)
         :grant {:id (str "G-" seat)}))

(def ^:private an-ask
  {:domain "infra" :requested_by "household" :needed_by "ASK"})

(deftest a-mayor-drafts-into-another-domain
  (testing "the draft carries who asked, what waits for it and the priority asked"
    (let [{:keys [state data]} ((:on-create ticket)
                                (at :draft {:domain "infra" :needed_by "ASK"} "NEW")
                                (sitter "house-mayor"))]
      (is (= :draft state))
      (is (= "infra" (:domain data)))
      (is (= "household" (:requested_by data)))
      (is (= "ASK" (:needed_by data)))
      (is (= 1 (:asked_priority data)))
      (is (= 4 (:priority data)) "the receiving mayor sets the priority")))
  (testing "a mayor naming its own domain asks nobody"
    (let [{:keys [data]} ((:on-create ticket)
                          (at :draft {:domain "household"} "NEW")
                          (sitter "house-mayor"))]
      (is (nil? (:requested_by data)))
      (is (= 1 (:priority data)))))
  (let [[_ _ known mayors] (:create-guards ticket)
        allowed? (fn [guard seat inp]
                   (= :allow (:verdict (first (g/evaluate guard nil inp (sitter seat))))))]
    (testing "only a mayor names another domain"
      (is (allowed? mayors "house-mayor" {:domain "infra"}))
      (is (not (allowed? mayors "house-hand" {:domain "infra"})))
      (is (allowed? mayors "house-hand" {:domain "household"}))
      (is (allowed? mayors "house-hand" {})))
    (testing "and the domain it names is one that exists"
      (is (allowed? known "house-mayor" {:domain "infra"}))
      (is (not (allowed? known "house-mayor" {:domain "nowhere"}))))))

(deftest only-the-receiving-mayor-answers-an-ask
  (let [draft (at :draft an-ask "NEW")
        queued (at :open an-ask "NEW")
        wall :the-receiving-mayor-answers-an-ask]
    (testing "the mayor that asked does not groom, rank or drop it"
      (doseq [[row action] [[draft :groom] [draft :drop] [queued :prioritize]]]
        (let [shut (refusal row (sitter "house-mayor") action)]
          (is (= :unavailable (:status shut)) (name action))
          (is (= wall (:name (:denier shut))) (name action)))))
    (testing "and the refusal names the receiving mayor"
      (let [guard (last (:guards (get (:actions ticket) :groom)))
            [v d] (g/evaluate guard draft nil (sitter "house-mayor"))]
        (is (str/includes? (g/render-reason d v nil) "infra-mayor"))))
    (testing "the receiving mayor does, and so does the owner"
      (doseq [[row action] [[draft :groom] [draft :drop] [queued :prioritize]]
              c [(sitter "infra-mayor") (ctx the-person two-domains)]]
        (is (= :available (:status (refusal row c action))) (name action))))
    (testing "a ticket nobody asked across domains is groomed as before"
      (is (= :available
             (:status (refusal (at :draft {:domain "household"} "NEW")
                               (sitter "house-mayor") :groom)))))))

(deftest a-decline-turns-the-asker-back
  (let [calls (atom [])
        c (assoc (ctx the-person two-domains)
                 :invoke (fn [& args] (swap! calls conj (vec args)) nil))
        _ ((:handler (get (:actions ticket) :drop))
           (at :open an-ask "NEW") {:close_reason "Infra runs no music server."} c)
        [kind id _ inp] (first (filter #(= :turn_back (nth % 2)) @calls))
        door (get (:actions ticket) :turn_back)
        asker (get two-domains "ASK")]
    (testing "the drop walks the waiting ticket's turn_back with the reason"
      (is (= [:ticket "ASK"] [kind id]))
      (is (= "declined by infra: Infra runs no music server."
             (:shelved_because inp))))
    (testing "which lands in draft, waiting on nothing, with the reason kept"
      (let [after ((:handler door) asker inp c)]
        (is (= :draft (:to door)))
        (is (= [] (get-in after [:data :blocked_by])))
        (is (= (:shelved_because inp) (get-in after [:data :shelved_because])))))
    (testing "and no hand takes that door"
      (is (= :only-a-decline-turns-the-asker-back
             (:name (:denier (refusal asker (ctx the-person) :turn_back)))))
      (is (= :available
             (:status (refusal asker
                               (assoc (ctx the-engine)
                                      :within {:kind :ticket :action :drop})
                               :turn_back)))))))

;; ── a service serves every domain (ticket 92eb577f) ───────────────────

(def ^:private infra-repo "ckopsa/home-infrastructure")

(def ^:private a-service
  (merge two-domains
         (sat "factory-mayor" nil)
         {"D-factory" {:kind :domain :id "D-factory"
                       :data {:name "factory" :mayor "S-factory-mayor"}}
          "S-infra-seat" {:kind :seat :id "S-infra-seat"
                          :data {:name "infra-seat" :serves "any" :walk "ticket"
                                 :scope [{:kind "bench.edit" :actions []
                                          :filter {:repo infra-repo}}]}}
          "S-code-seat" {:kind :seat :id "S-code-seat"
                         :data {:name "code-seat" :walk "ticket"
                                :scope [{:kind "bench.edit" :actions []
                                         :filter {:repo "ckopsa/waymark"}}]}}}))

(defn- served
  "The delegate that sits in `seat`, over the fake store `a-service`."
  [seat]
  (assoc (ctx {:id seat :type :agent :roles #{} :acts-for "colton"} a-service)
         :grant {:id (str "G-" seat)}))

(def ^:private a-served-ask
  {:domain "factory" :requested_by "household" :needed_by "ASK"
   :repo infra-repo})

(deftest the-requesting-mayor-grooms-and-ranks-a-services-ticket
  (let [draft (at :draft a-served-ask "NEW")
        queued (at :open a-served-ask "NEW")]
    (testing "the household mayor grooms and ranks what it asked a service for"
      (is (= :available (:status (refusal draft (served "house-mayor") :groom))))
      (is (= :available
             (:status (refusal queued (served "house-mayor") :prioritize)))))
    (testing "and does not drop it: the decline is the receiving domain's"
      (let [shut (refusal draft (served "house-mayor") :drop)]
        (is (= :unavailable (:status shut)))
        (is (= :only-the-receiving-mayor-declines (:name (:denier shut))))))
    (testing "the receiving mayor still answers it, the drop included"
      (doseq [[row action] [[draft :groom] [draft :drop] [queued :prioritize]]]
        (is (= :available (:status (refusal row (served "factory-mayor") action)))
            (name action))))
    (testing "a hand of the asking domain that is not its mayor does not"
      (is (= :unavailable
             (:status (refusal draft (served "house-hand") :groom)))))
    (testing "the same groom is refused on a ticket of a seat that serves its own domain"
      (let [shut (refusal (at :draft (assoc a-served-ask :repo "ckopsa/waymark") "NEW")
                          (served "house-mayor") :groom)]
        (is (= :unavailable (:status shut)))
        (is (= :the-receiving-mayor-answers-an-ask (:name (:denier shut))))))))

(deftest a-services-spend-shows-by-the-domain-that-asked
  (let [sitting (fn [id started cost walked]
                  {id {:kind :sitting :id id :state :closed
                       :data {:seat "S-infra-seat" :started_at started
                              :cost_usd cost :walked_rows walked}}})
        rows (merge a-service
                    {"T-asked" (at :open a-served-ask "T-asked")
                     "T-own" (at :open {:repo infra-repo} "T-own")}
                    (sitting "SIT-1" "2026-09-25T10:00:00Z" 3M ["T-asked"])
                    (sitting "SIT-2" "2026-09-24T10:00:00Z" 2M ["T-own"])
                    (sitting "SIT-3" "2026-09-23T10:00:00Z" 1M [])
                    (sitting "SIT-old" "2026-09-01T10:00:00Z" 50M ["T-asked"]))
        {read' :read find' :find} (ctx the-person rows)
        spend (seats/spend-by-domain read' find' (get rows "S-infra-seat") now)]
    (is (= #{:household :factory} (set (keys spend))))
    (is (== 3 (:household spend))
        "a requested ticket's sitting is the requester's cost")
    (is (== 3 (:factory spend))
        "a ticket nobody asked for, and a sitting that walked nothing, are factory's")))
