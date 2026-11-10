(ns waymark10.server.seats.owners
  "A seat's scope against its owner's, carved out of
  `waymark10.server.seats` (ticket 6aad331d): `scope-inside-the-owners`,
  which judges the entries a write adds — a `*` entry as the kinds it
  reads — and `a-restate-adds-no-copy`, the warning that keeps a scope
  from accreting exact copies of an entry it already holds.

  This file requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.roles :as roles]
            [waymark10.types :as t]))

(defn- entry-shape [e]
  [(str (:kind e)) (into #{} (map str) (:actions e))])

(defn opening-person
  "The person whose own hand opens a seat no delegating seat authors:
  a person in person, or the one a delegate acts for. nil for any other
  hand — a bare agent, the engine's own actors."
  [ctx]
  (let [{:keys [type id]} (:principal ctx)]
    (case type
      :human (some-> id str not-empty)
      :agent (delegation/owner-of ctx)
      nil)))

(defn- seat-owner
  "The principal whose effective scope bounds this seat write, or nil."
  [row ctx]
  (if (some? (:id row))
    (when-some [o (some-> (get-in row [:data :owner]) str not-empty)]
      (t/principal {:id o :display o}))
    (or (when-some [author (delegation/authoring-seat ctx :seat nil)]
          (when-some [o (or (delegation/owner-of ctx)
                            (some-> (get-in author [:data :owner]) str not-empty))]
            (t/principal {:id o :display o})))
        (when-some [o (opening-person ctx)]
          (t/principal {:id o :display o}))
        (:principal ctx))))

(g/defguard scope-inside-the-owners
  {:judges [:scope]
   :reads [:principal :member :domain :seat :grant :held_call :within :services]
   :vars [:owner :entries]
   :open "The owner's effective scope is every door this engine serves but the ones a manager holds; a manager's is the whole engine."
   :explain "A seat's scope is never wider than its owner's own, and {owner} may not walk {entries}. Drop those entries, or ask a manager to open the seat."}
  [row inp ctx]
  (let [before (into #{} (map entry-shape) (get-in row [:data :scope]))
        ;; an added `*` is judged as the kinds it reads, one by one
        rdefs (some-> (:rdefs ctx) (apply []))
        added (->> (:scope inp)
                   (remove #(str/includes? (str (:kind %)) "."))
                   (remove #(contains? before (entry-shape %)))
                   (grants/expand-every-kind rdefs))]
    (if (or (empty? added) (nil? (:rdefs ctx)))
      (t/allow)
      (if-some [owner (seat-owner row ctx)]
        (let [eff (roles/effective-scope-in ctx owner)
              past (remove (fn [e] (some #(delegation/entry-fits? e %) eff))
                           added)]
          (if (seq past)
            (t/deny {:vars {:owner (str (:id owner))
                            :entries (str/join "; " (map delegation/entry-text past))}})
            (t/allow)))
        (t/allow)))))

(defn restate-copies
  "The entries a restate of `row` to `inp` holds more than once and more
  often than the scope held them before: the copies it would add."
  [row inp]
  (let [before (frequencies (get-in row [:data :scope]))]
    (keep (fn [[e n]] (when (and (> n 1) (> n (get before e 0))) e))
          (frequencies (:scope inp)))))

(g/defguard a-restate-adds-no-copy
  {:severity :warning
   :judges [:scope]
   :vars [:entries]
   :open "Two equal entries grant what one does (ticket 6aad331d); the refusal names the copy. Acknowledge the warning by its name to keep it."
   :explain "This restate adds an entry the scope already holds, exactly: {entries}. Drop the copy, or acknowledge this warning to keep it."}
  [row inp _ctx]
  (if-some [copies (seq (restate-copies row inp))]
    (t/deny {:vars {:entries (str/join "; " (map delegation/entry-text copies))}})
    (t/allow)))
