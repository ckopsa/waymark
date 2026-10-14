(ns factorykit.ask-contract
  "THE ASK CONTRACT (ticket 50fd9aba), as `factorykit.change-contract`
  is the change's. `factorykit.ask` builds a factory's ask kind from the
  core; this suite says what that kind must then do in the factory's
  own engine, and a factory runs it against its own ask.

  `(failures new-hand)` runs every clause and answers the ones the ask
  broke, each as `{:clause :says}`; an empty answer conforms.
  `new-hand` is a function of no argument that answers a hand on a
  fresh engine, so each clause starts from nothing. The suite requires
  no engine and no factory namespace: a hand is the factory's engine
  closed into plain functions.

  | key | what it is |
  |---|---|
  | `:declaration` | the ask kind the engine serves |
  | `:link` | the factory's link (`factorykit.ask/link`); the review and return doors are `review` and `return` when it names none |
  | `:mint!` | `(fn [])` → a new ask in draft, at a priority the queue takes, by a person's hand |
  | `:row` | `(fn [id])` → the row as it stands |
  | `:walk!` | `(fn [hand id door input])` → the row after the door; `hand` is `:person` or `:model` (a model acting for nobody), and a refusal throws |
  | `:end!` | `(fn [id])` ends the ask through one of the factory's ending doors, as a person ends it |"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def states
  "Every factory's ask has these seven states."
  #{:draft :open :in_review :blocked :deferred :done :dropped})

(def ^:private ended #{:done :dropped})

(def ^:private wall :only-its-change-moves-it)

(defn- word [x] (some-> x name keyword))

(defn- state-of [row] (word (:state row)))

(defn- refused?
  "True when `f` throws: the door refused the call."
  [f]
  (try (f) false (catch Exception _ true)))

;; ── the clauses ─────────────────────────────────────────────────────

(defn- the-machine [{:keys [declaration]}]
  (let [ours (set (map word (:states declaration)))
        tomb (set (map word (:terminal declaration)))]
    (cond
      (not= states ours)
      (str "its states are " (pr-str (sort ours)) "; the core's are "
           (pr-str (sort states)))
      (seq tomb)
      (str "its tomb is " (pr-str (sort tomb)) ", and the core has none")
      (not= :draft (word (:initial declaration)))
      (str "it starts in " (some-> (:initial declaration) name)
           ", and the core starts in draft"))))

(defn- groom-lands-in-the-queue [{:keys [walk! row mint!]}]
  (let [backlog (:id (mint!))]
    (cond
      (not (refused? #(walk! :person backlog :groom {:priority 4})))
      "a groom at priority 4 put the ask in the queue"
      (not= :draft (state-of (row backlog)))
      "the refused groom moved the ask"
      :else
      (first
       (for [p [0 1 2 3]
             :let [id (:id (mint!))
                   _ (walk! :person id :groom {:priority p})
                   after (row id)
                   at (get-in after [:data :priority])]
             :when (or (not= :open (state-of after))
                       (not (and (number? at) (== p at))))]
         (str "a groom at priority " p " left the ask "
              (some-> (state-of after) name) " at priority " (pr-str at)))))))

(defn- an-ending-releases-what-it-blocked [{:keys [walk! row mint! end!]}]
  (let [one (str (:id (mint!)))
        two (str (:id (mint!)))
        waiter (:id (mint!))
        _ (walk! :person waiter :groom {})
        _ (walk! :person waiter :block {:blocked_by [one two]})
        held (state-of (row waiter))]
    (if (not= :blocked held)
      (str "the block left the ask " (some-> held name))
      (do (end! one)
          (cond
            (not (ended (state-of (row one))))
            "the ending door did not end the blocker"
            (not= :blocked (state-of (row waiter)))
            (str "the ask stands " (some-> (state-of (row waiter)) name)
                 " with one of its two blockers still working")
            (not= [two] (mapv str (get-in (row waiter) [:data :blocked_by])))
            (str "the first ending left it waiting on "
                 (pr-str (get-in (row waiter) [:data :blocked_by])))
            :else
            (do (end! two)
                (when (not= :open (state-of (row waiter)))
                  (str "the ask stands " (some-> (state-of (row waiter)) name)
                       " after its last blocker ended, and not open"))))))))

(defn- the-link-doors-open-only-in-the-change
  [{:keys [declaration walk! row mint!] :as hand}]
  (let [{:keys [review-door return-door]}
        (merge {:review-door :review :return-door :return} (:link hand))
        walled? (fn [door]
                  (some #(= wall (word (:name %)))
                        (get-in declaration [:actions door :guards])))
        id (:id (mint!))
        _ (walk! :person id :groom {})
        walked (vec (for [who [:person :model]
                          :when (not (refused? #(walk! who id review-door {})))]
                      (name who)))]
    (cond
      (not (walled? review-door))
      (str "its " (name review-door) " door has no " (name wall) " wall")
      (not (walled? return-door))
      (str "its " (name return-door) " door has no " (name wall) " wall")
      (seq walked)
      (str "a " (str/join " and a " walked) " walked " (name review-door)
           " by hand")
      (not= :open (state-of (row id)))
      (str "the refused " (name review-door) " moved the ask"))))

(def clauses
  "The contract, clause by clause: each judge takes a hand and answers
  nil when the ask keeps the clause, or a sentence when it does not."
  [[:the-machine the-machine]
   [:groom-lands-in-the-queue groom-lands-in-the-queue]
   [:an-ending-releases-what-it-blocked an-ending-releases-what-it-blocked]
   [:the-link-doors-open-only-in-the-change the-link-doors-open-only-in-the-change]])

(defn failures
  "Every clause, each on a fresh hand from `new-hand`. → the clauses the
  ask broke, `{:clause :says}`; `[]` conforms. A judge that throws is a
  broken clause, and says what it threw."
  [new-hand]
  (into []
        (keep (fn [[clause judge]]
                (when-some [says (try (judge (new-hand))
                                      (catch Exception e
                                        (str "it threw: " (ex-message e))))]
                  {:clause clause :says says})))
        clauses))
