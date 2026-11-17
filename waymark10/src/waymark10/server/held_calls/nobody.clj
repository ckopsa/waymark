(ns waymark10.server.held-calls.nobody
  "Who could allow a held call, asked at its mint (ticket 0e1fe0d3).
  On Marco a held power call showed its owner no Allow and no reason:
  allowing needs the `approver` role, no member held it, and the role
  row did not exist. `sentence` says so, naming the role and the doors
  that fix it, and the mint keeps it on the row and in the answer."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def ^:private page
  ;; the most members or teams one mint reads; a house has a handful
  1000)

(defn- rows
  "The stored rows of `kind` matching `where`, or nil when the engine
  serves no such kind."
  [eng kind where]
  (when (contains? (inv/resources eng) kind)
    (let [st (:storage eng)]
      (store/with-tx st (fn [tx] (store/query-rows st tx kind where {:limit page}))))))

(defn- holds?
  "Does a row's `roles` name `role`?"
  [role row]
  (some #(= role (str %)) (get-in row [:data :roles])))

(defn- held?
  "Does an active person hold `role`: on their own member row, or
  conferred by an active team that names them (teams/holds-role?'s
  reading)? An agent's member row is no person's."
  [eng role]
  (let [people (remove #(= "agent" (str (get-in % [:data :actor_type])))
                       (rows eng :member {:state "active"}))
        ids (set (map #(str (:id %)) people))]
    (boolean
     (or (some (partial holds? role) people)
         (some #(and (holds? role %)
                     (some (comp ids str) (get-in % [:data :members])))
               (rows eng :team {:state "active"}))))))

(defn- spelled [names]
  (str/join " or " (map #(str "`" % "`") names)))

(defn sentence
  "nil when an active person holds `role` (kept \"x or y\" for a set),
  or on an engine that serves no members; else the one sentence that
  says nobody can allow the call, which role it needs, and who fixes it
  with which door: role.create when no active `role` row registers it,
  and member.assign_roles, which a recovery-admin holds."
  [eng role]
  (let [names (vec (remove str/blank? (str/split (str role) #" or ")))]
    (when (and (seq names)
               (contains? (inv/resources eng) :member)
               (not-any? #(held? eng %) names))
      (let [missing (remove #(seq (rows eng :role {:name % :state "active"})) names)]
        (str "Nobody can allow this call yet: allowing it needs the "
             (spelled names) " role, and no active person holds it. "
             (when (seq missing)
               (str "No role row registers " (spelled missing)
                    ", so a person who may create roles registers it first (role.create). "))
             "Then a recovery-admin gives it to a person (member.assign_roles).")))))
