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
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.resources.ticket :as tk :refer [ticket]]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.server.render :as render])
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
    (is (= #{:restate :groom :block :complete :drop}
           (offers (at :draft) (ctx the-person)))
        "prioritize and defer are absent: a draft is not in the queue,
         so it has no rank there and nothing to park"))
  (testing "a seat at a draft meets everything but groom"
    (is (= #{:restate :block :complete :drop}
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

(deftest an-open-ticket-is-the-queue-and-offers-every-working-door
  (testing "a seat at an open ticket meets the doors that end or park it"
    (is (= #{:prioritize :block :defer :complete :drop}
           (offers (at :open) (ctx the-seat)))
        "restate is absent — a groomed statement is what the seat builds
         — and reopen, unblock and resume are absent — nothing ended it
         and nothing holds it"))
  (testing "a person meets the same doors and the way back to draft"
    (is (= #{:prioritize :block :defer :complete :drop :ungroom}
           (offers (at :open) (ctx the-person)))
        "what a seat may reach at all is the grant's question, not this
         kind's; ungroom is the one door here that is a person's")))

(deftest a-blocked-ticket-is-out-of-the-queue-and-waits
  (let [row (at :blocked {:blocked_by ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]})]
    (is (= #{:block :unblock} (offers row (ctx the-person)))
        "restate the blockers, or clear them — nothing else, because a
         blocked ticket is not worked and not ended")
    (let [shut (refusal row (ctx the-person) :complete)]
      (is (= :unavailable (:status shut)))
      (is (nil? (:denier shut))
          "the MACHINE refuses it, with no guard behind the refusal: a
           blocked ticket is not finished, so it is unblocked first"))))

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

;; ── the shape the walker and the import both read ───────────────────

(deftest the-declaration-says-what-the-walker-needs
  (is (= [:draft :open :blocked :deferred :done :dropped] (:states ticket)))
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
