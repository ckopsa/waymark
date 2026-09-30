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
            [waymark10.schema :as sch]
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
    (is (= #{:restate :groom :block :complete :drop :merge_after_draft}
           (offers (at :draft) (ctx the-person)))
        "prioritize and defer are absent: a draft is not in the queue,
         so it has no rank there and nothing to park"))
  (testing "a seat at a draft meets everything but groom"
    (is (= #{:restate :block :complete :drop :merge_after_draft}
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
    (is (= #{:prioritize :block :defer :complete :drop :merge_after}
           (offers (at :open) (ctx the-seat)))
        "restate is absent — a groomed statement is what the seat builds
         — and reopen, unblock and resume are absent — nothing ended it
         and nothing holds it"))
  (testing "a person meets the same doors and the way back to draft"
    (is (= #{:prioritize :block :defer :complete :drop :ungroom :merge_after}
           (offers (at :open) (ctx the-person)))
        "what a seat may reach at all is the grant's question, not this
         kind's; ungroom is the one door here that is a person's")))

(deftest a-blocked-ticket-is-out-of-the-queue-and-waits
  (let [row (at :blocked {:blocked_by ["01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"]})]
    (is (= #{:block :unblock :merge_after_blocked} (offers row (ctx the-person)))
        "restate the blockers, clear them, or state what it merges after
         — nothing else, because a blocked ticket is not worked and not
         ended")
    (let [shut (refusal row (ctx the-person) :complete)]
      (is (= :unavailable (:status shut)))
      (is (nil? (:denier shut))
          "the MACHINE refuses it, with no guard behind the refusal: a
           blocked ticket is not finished, so it is unblocked first"))))

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
