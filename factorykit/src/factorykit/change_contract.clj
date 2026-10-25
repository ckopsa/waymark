(ns factorykit.change-contract
  "THE CHANGE CONTRACT (epic 459ed2eb, step 2d). `factorykit.change`
  builds a factory's change kind from the core; this suite says what
  that kind must then do in the factory's own engine, and a factory
  runs it against its own change.

  `(failures new-hand)` runs every clause and answers the ones the
  change broke, each as `{:clause :says}`; an empty answer conforms.
  `new-hand` is a function of no argument that answers a hand on a
  fresh engine, so each clause starts from nothing. The suite requires
  no engine and no factory namespace: a hand is the factory's engine
  closed into plain functions.

  | key | what it is |
  |---|---|
  | `:declaration` | the change kind the engine serves |
  | `:now` | the `Instant` the engine's clock reads |
  | `:indexes?` | true when the store holds the declared indexes; a memory store holds none, and the second mint is then not tried |
  | `:repository` | a repository the factory builds in |
  | `:mint!` | `(fn [fields])` → the row the create door minted, by the source's hand |
  | `:row` | `(fn [id])` → the row as it stands |
  | `:walk!` | `(fn [hand id door input])` → the row after the door; `hand` is `:source`, `:person` or `:model` (a model acting for nobody), and a refusal throws |
  | `:ask!` | `(fn [])` → the `born_from` address of a new ask in the queue |
  | `:unqueue!` | `(fn [ask])` takes the ask out of the queue |
  | `:ask-state` | `(fn [ask])` → the ask's state, a keyword |
  | `:sweep!` | `(fn [now])` runs the factory's sweep as of `now` |
  | `:regroom!` | `(fn [ask row])` puts the ask back in the queue and does what the factory does when a seat sits down to it |
  | `:pull-request!` | `(fn [])` → the id of a new `pull_request` row the engine holds |"
  (:require [clojure.string :as str])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def states
  "Every factory's change has these six states."
  #{:open :submitted :failing :stuck :merged :closed})

(def terminal
  "And this one tomb: a closed change comes back through `reopen`."
  #{:merged})

(defn- word [x] (some-> x name keyword))

(defn- state-of [row] (word (:state row)))

(defn- refused?
  "True when `f` throws: the door refused the call."
  [f]
  (try (f) false (catch Exception _ true)))

(defn- fresh-id [what] (str "contract:" what ":" (random-uuid)))

(defn- minted!
  "A change with no pull request, born from `born-from`, by the
  source's hand."
  [{:keys [mint! repository]} born-from]
  (mint! (cond-> {:change_id (fresh-id "change")
                  :repository repository
                  :title "A change the contract minted"
                  :head_branch (str "bench/contract-" (random-uuid))}
           born-from (assoc :born_from born-from))))

;; ── the clauses ─────────────────────────────────────────────────────

(defn- the-machine [{:keys [declaration]}]
  (let [ours (set (map word (:states declaration)))
        tomb (set (map word (:terminal declaration)))]
    (when-not (and (= states ours) (= terminal tomb))
      (str "its states are " (pr-str (sort ours)) " and its tomb "
           (pr-str (sort tomb)) "; the core's are " (pr-str (sort states))
           " and " (pr-str (sort terminal))))))

(defn- the-pr-less-doors [{:keys [declaration]}]
  (let [{:keys [close_without_pr reopen]} (:actions declaration)]
    (cond
      (nil? close_without_pr) "it has no close_without_pr door"
      (not= :closed (word (:to close_without_pr)))
      "close_without_pr does not land in closed"
      (nil? reopen) "it has no reopen door"
      (not (contains? (set (map word (:from reopen))) :closed))
      "reopen does not leave closed"
      (not= :open (word (:to reopen))) "reopen does not land in open")))

(defn- one-row-per-change-id [{:keys [declaration mint! indexes?] :as hand}]
  (let [row (minted! hand ((:ask! hand)))
        again #(mint! (assoc (select-keys (:data row) [:change_id :repository :title])
                             :head_branch (str "bench/contract-" (random-uuid))))]
    (cond
      (not (some #(= [:change_id] (mapv word %)) (:unique declaration)))
      "its index does not hold one row for each change_id"
      (and indexes? (not (refused? again)))
      "a second change with the same change_id was minted")))

(defn- born-from-stands [{:keys [walk!] :as hand}]
  (let [ask ((:ask! hand))
        row (minted! hand ask)
        pr (fresh-id "pull")
        _ (walk! :source (:id row) :adopt {:change_id pr :number 7})
        adopted ((:row hand) (:id row))]
    (cond
      (not= ask (get-in row [:data :born_from]))
      "the minted change does not keep its born_from"
      (not= pr (get-in adopted [:data :change_id]))
      "the source's adopt did not write the pull request's id"
      (not= ask (get-in adopted [:data :born_from]))
      "the adoption lost born_from")))

(defn- the-source-writes [{:keys [walk!] :as hand}]
  (let [id (:id (minted! hand ((:ask! hand))))
        tries {:observe {:title "Retitled by the wrong hand"}
               :adopt {:change_id (fresh-id "pull") :number 8}
               :link {:pull_request ((:pull-request! hand))}}
        walked (vec (for [who [:person :model]
                          [door input] tries
                          :when (not (refused? #(walk! who id door input)))]
                      (str (name who) " walked " (name door))))]
    (cond
      (seq walked) (str "the wall let " (str/join " and " walked) " through")
      (refused? #(walk! :source id :observe {:title "Retitled by the source"}))
      "the source itself was refused observe")))

(defn- a-change-links-one-pull-request [{:keys [declaration walk! indexes?] :as hand}]
  ;; spec-change §3, R-C.4 (ticket 56c97770)
  (let [id (:id (minted! hand ((:ask! hand))))
        other (:id (minted! hand ((:ask! hand))))
        pr ((:pull-request! hand))
        _ (walk! :source id :link {:pull_request pr})]
    (cond
      (not (some #(= [:pull_request] (mapv word %)) (:unique declaration)))
      "its index does not hold one change for each pull_request"
      (not= pr (get-in ((:row hand) id) [:data :pull_request]))
      "the source's link did not write the pull request's row"
      (and indexes? (not (refused? #(walk! :source other :link {:pull_request pr}))))
      "a second change was linked to a pull request another change holds")))

(defn- an-unqueued-ask-is-swept [{:keys [sweep! ask-state] :as hand}]
  (let [ask ((:ask! hand))
        row (minted! hand ask)
        _ ((:unqueue! hand) ask)
        held (ask-state ask)
        ^Instant now (:now hand)
        state-now #(state-of ((:row hand) (:id row)))]
    (sweep! (.plus now (Duration/ofHours 1)))
    (if (not= :open (state-now))
      "a change whose ask left the queue an hour ago was swept"
      (do (sweep! (.plus now (Duration/ofDays 2)))
          (cond
            (not= :closed (state-now))
            "a change whose ask left the queue two days ago was not swept"
            (not= held (ask-state ask))
            (str "the sweep moved the ask from " (some-> held name) " to "
                 (some-> (ask-state ask) name)))))))

(defn- a-regroomed-ask-reopens-its-change [{:keys [walk!] :as hand}]
  (let [ask ((:ask! hand))
        id (:id (minted! hand ask))
        _ ((:unqueue! hand) ask)
        _ ((:sweep! hand) (.plus ^Instant (:now hand) (Duration/ofDays 2)))
        row #((:row hand) id)]
    (cond
      (not= :closed (state-of (row)))
      "the sweep did not close the change, so there is nothing to reopen"
      (not (refused? #(walk! :person id :reopen {})))
      "a person walked reopen, which is the source's door"
      :else
      (do ((:regroom! hand) ask (row))
          (cond
            (not= :open (state-of (row)))
            (str "the change stands " (some-> (state-of (row)) name)
                 " after its ask was groomed again")
            (not (zero? (long (or (get-in (row) [:data :rounds]) 0))))
            "the reopened change did not start its rounds from zero")))))

(def clauses
  "The contract, clause by clause: each judge takes a hand and answers
  nil when the change keeps the clause, or a sentence when it does not."
  [[:the-machine the-machine]
   [:the-pr-less-doors the-pr-less-doors]
   [:one-row-per-change-id one-row-per-change-id]
   [:born-from-stands born-from-stands]
   [:the-source-writes the-source-writes]
   [:a-change-links-one-pull-request a-change-links-one-pull-request]
   [:an-unqueued-ask-is-swept an-unqueued-ask-is-swept]
   [:a-regroomed-ask-reopens-its-change a-regroomed-ask-reopens-its-change]])

(defn failures
  "Every clause, each on a fresh hand from `new-hand`. → the clauses the
  change broke, `{:clause :says}`; `[]` conforms. A judge that throws
  is a broken clause, and says what it threw."
  [new-hand]
  (into []
        (keep (fn [[clause judge]]
                (when-some [says (try (judge (new-hand))
                                      (catch Exception e
                                        (str "it threw: " (ex-message e))))]
                  {:clause clause :says says})))
        clauses))
