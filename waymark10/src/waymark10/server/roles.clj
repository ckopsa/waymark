(ns waymark10.server.roles
  "Roles: policy names are resources (waymark9 server/roles.py). A
  role's MEANING (what holding it opens) lives in guards and grants;
  the registry closes the silent-grant gap the v3 notes recorded — a
  role name nobody spelled the same way twice used to grant nobody,
  silently. Now the name must exist here, active, before a member may
  hold it; who is admin stays auditable.

  Recorded deviations and named punts:
  - One spelling per role is a create guard over ctx :find, not a
    declared :unique — v10 storage enforces no unique index yet
    (definitions carries the same punt), so the guard is the honest
    spelling and two racing creates remain the recorded window.
  - waymark9 grants consulted _active_role_named on role-held grants;
    v10 grants name a principal audience, not a role — the registry's
    v10 consumer is the member's roles field (members.clj)."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn active-role?
  "Is `role-name` a registered, active role on this engine? Reads
  through the ctx :find hook (same transaction as the write); a ctx
  without the hook declines with nil — the render probe stays
  storage-free."
  [ctx role-name]
  (when-some [find' (:find ctx)]
    (boolean (seq (find' :role {:name (str role-name) :state "active"}
                         {:limit 1})))))

(g/defguard one-spelling
  {:judges [:name]
   :reads [:role]
   :explain "A role named {name} already exists — one spelling per role, or the grant surface splits silently."
   :open "The taken spellings are the roles collection, one query away; advertising them all would enumerate the registry into every create form."}
  [_row inp ctx]
  (if (and (some? (:name inp)) (active-role? ctx (:name inp)))
    (t/deny {:vars {:name (:name inp)}})
    (t/allow)))

(defresource role
  {:kind :role
   :plural "roles"
   :states [:active :retired]
   :initial :active
   :terminal #{}                       ; retirement is reversible, deliberately
   :nav :secondary
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map
            [:name [:string {:min 1 :max 40}]]
            [:description {:optional true} [:maybe [:string {:max 240}]]]]
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [one-spelling]
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "Members can no longer be assigned this role; existing holders keep it until reassigned."}
             :display {:label "Retire" :style :danger :order 9}}
    :reactivate {:from #{:retired} :to :active
                 :safety {:idempotent true :reversible true :confirm false}
                 :display {:label "Reactivate" :order 1}}}})
