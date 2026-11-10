(ns waymark10.server.members.suspend
  "An agent's suspend or reinstate of a member (HC2, ticket bed1f2f6),
  split out of waymark10.server.members so that namespace keeps room
  under its ns-size entry (ticket 829a08aa). The member resource wears
  these three guards on :suspend and :reinstate.

  An agent whose scope names the door does not suspend or reinstate
  by its own hand. When its person (delegation/hold-owner: the seat's
  owner, or a delegate's person) holds the manager role, the call is
  held for that person, and the Allow runs it AS THE PERSON
  (held-calls/door-principal), who is no agent, so these guards pass
  on the replay and the person's own judge it. Otherwise it refuses."
  (:require [waymark10.guards :as g]
            ;; hold-owner: the person an agent's suspend or reinstate
            ;; waits on. delegation requires nothing that requires
            ;; members
            [waymark10.server.delegation :as delegation]
            [waymark10.server.roles :as roles]
            [waymark10.types :as t]))

(defn- agents-person
  "The person an agent's suspend or reinstate waits on, or nil for a
  caller who is no agent."
  [ctx]
  (when (= :agent (get-in ctx [:principal :type]))
    (delegation/hold-owner ctx)))

(defn- own-target?
  "Is this member the asking agent itself, or the agent's own person?"
  [row ctx]
  (let [ids (into #{} (keep #(some-> % str not-empty))
                  [(:id row) (get-in row [:data :subject])])]
    (boolean (some ids (keep #(some-> % str not-empty)
                             [(get-in ctx [:principal :id])
                              (agents-person ctx)])))))

(defn- managers-agent?
  "Does a manager person stand behind this agent?"
  [ctx]
  (when-some [owner (agents-person ctx)]
    (roles/manager? (assoc ctx :principal (t/principal {:id owner :display owner})))))

(defn- suspend-shown
  "What a held suspend or reinstate says to the person, in plain words:
  whom it moves, and what changes for them."
  [row]
  (let [who (or (some-> (get-in row [:data :display]) str not-empty) (str (:id row)))]
    (if (= "suspended" (some-> (:state row) name))
      (str "Reinstate " who ": they can sign in again.")
      (str "Suspend " who ": they can no longer sign in, and every request they make is refused until reinstated."))))

(g/defguard agents-never-suspend-their-own
  {:reads [:principal :now :grant :approval_request]
   :open "No door clears this one: a suspend or reinstate of the agent itself, or of its own person, is never an agent's."
   :explain "An agent never suspends or reinstates its own person, or the member that is the agent itself."}
  [row _inp ctx]
  (if (and (= :agent (get-in ctx [:principal :type])) (own-target? row ctx))
    (t/deny)
    (t/allow)))

(g/defguard agents-suspend-only-for-a-manager
  {:reads [:principal :now :grant :approval_request :member :domain]
   :open "An agent's suspend or reinstate waits on its person, and only a person who holds the manager role can allow it; that person suspends or reinstates directly."
   :explain "An agent suspends or reinstates a member only for a person who holds the manager role, and no such person stands behind you."}
  [_row _inp ctx]
  (if (and (= :agent (get-in ctx [:principal :type])) (not (managers-agent? ctx)))
    (t/deny)
    (t/allow)))

(g/defguard an-agents-suspend-waits-on-its-person
  {:hold :agents
   :reads [:principal :now :grant :approval_request :member :domain]
   :vars [:shown]
   :open "No door clears this one. The call waits as a held_call for the agent's person, and the person's Allow runs it as the person, judged as the person's own suspend or reinstate."
   :explain "Held for your person's tap. {shown} The call is recorded as a held_call, and their Allow runs it as them."}
  [row _inp ctx]
  (if (and (managers-agent? ctx) (not (own-target? row ctx)))
    (let [shown (suspend-shown row)]
      (t/deny {:vars {:shown shown} :evidence {:shown shown}}))
    (t/allow)))
