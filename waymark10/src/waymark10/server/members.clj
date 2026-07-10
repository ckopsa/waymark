(ns waymark10.server.members
  "Members: identity is a resource (waymark9 server/members.py, scoped
  to phase 9a's deliverable). One member row per external principal —
  id IS the principal id — with display, actor type, held roles, and
  the active/suspended machine. The admin console is the members
  collection; suspension and its lifting are audited transitions.

  THE BOUNDARY, documented: guards stay the only AUTHORIZATION
  concept. The suspension gate here is authentication-adjacent — it
  decides whether the request carries a live identity at all, before
  any resource is addressed — so it lives in the router's identity
  middleware and refuses with a 403 problem, never with a guard
  verdict. What a live member may DO stays the guards' question.

  Recorded deviations from waymark9's member (each a sentence):
  - waymark9's invite → first-login-bind flow (invited state, email,
    OIDC subject binding) is unported: v10 auto-provisions on first
    sight — the closest honest port of 'membership appears through
    the engine, logged' without the invitation machinery. Invited-only
    membership is a named punt.
  - Roles ride the member (member→roles, waymark9's shape); the role
    registry (roles.clj) validates every assignment — waymark9's
    roles_registered_at_invite, moved to assign_roles because there
    is no invite.
  - System principals are not members: they are the engine's own
    actors (deploy, cascade, bytes), never provisioned, never gated."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def registrar
  "The system actor that provisions members on first sight."
  (t/principal {:id "waymark10-members" :type :system
                :display "Member registrar"}))

;; ── the resource ────────────────────────────────────────────────────

(g/defguard roles-registered
  {:judges [:roles]
   :reads [:role]
   :explain "No active role named {roles} — register the role first; an assignment naming it would grant nobody."
   :open "The active roles are the roles collection, one query away; the registry judges each token at submit."
   :remedies [:role/create]}
  [_row inp ctx]
  (if-some [unknown (when (:find ctx)
                      (seq (sort (remove #(roles/active-role? ctx %)
                                         (:roles inp)))))]
    (t/deny {:vars {:roles (str/join ", " unknown)}})
    (t/allow)))

(defhandler set-roles [row inp _ctx]
  (assoc-in row [:data :roles] (vec (distinct (:roles inp)))))

(defresource member
  {:kind :member
   :plural "members"
   :states [:active :suspended]
   :initial :active
   :terminal #{}                     ; suspension is reversible, deliberately
   :nav :secondary
   :summary "{data.display} · {state}"
   :label-template "{data.display}"
   :schema [:map
            [:display [:string {:min 1 :max 80}]]
            [:actor_type [:enum "human" "agent"]]
            [:roles {:optional true}
             [:vector [:string {:min 1 :max 40}]]]]
   :filterable {:state #{:eq :in}
                :actor_type #{:eq}}
   :sortable {:fields [:display] :default "display"}
   :create-guards [roles-registered]
   :actions
   {:assign_roles {:from #{:active} :to :active
                   :input [:map [:roles [:vector [:string {:min 1 :max 40}]]]]
                   :record true
                   :edit {:prefill [:roles]}   ; the fence rides along
                   :guards [roles-registered]
                   :safety {:idempotent true :reversible true :confirm false}
                   :handler set-roles
                   :display {:label "Assign roles" :order 2}}
    :suspend {:from #{:active} :to :suspended
              :safety {:idempotent true :reversible true :confirm true
                       :consequence "Every request this member makes is refused until reinstated; their held roles stop acting."}
              :display {:label "Suspend" :style :danger :order 9}}
    :reinstate {:from #{:suspended} :to :active
                :safety {:idempotent true :reversible true :confirm false}
                :display {:label "Reinstate" :order 1}}}})

;; ── the identity gate (router middleware's consult) ─────────────────

(defn suspended
  "The 403 problem a suspended member's request answers with."
  [id]
  (p/problem :member-suspended 403 "Membership suspended"
             {:detail (str "Member " (pr-str id) " is suspended; every "
                           "request is refused until a reinstate. Ask an "
                           "administrator.")}))

(defn- load-member [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :member id {}))))

(defn- provision!
  "First sight: mint the member through the engine — system actor,
  logged like any create. A losing race reloads the winner's row."
  [eng principal]
  (try
    (:row (inv/create! eng :member
                       {:display (let [d (:display principal)
                                       d (if (str/blank? d) (:id principal) d)]
                                   ;; the declared budget, not a 422 on
                                   ;; every request an IdP's long name makes
                                   (subs d 0 (min (count d) 80)))
                        :actor_type (name (:type principal))}
                       {:principal registrar :id (:id principal)}))
    (catch Exception e
      (or (load-member eng (:id principal)) (throw e)))))

(defn gate!
  "The principal-resolution consult: anonymous and system principals
  pass untouched (system actors are the engine's own, not members);
  everyone else is provisioned on first sight, refused 403 while
  suspended, and carries the member's held roles unioned onto the
  credential's. Engines without the member kind gate nothing."
  [eng principal]
  (if (or (nil? principal)
          (= (:id principal) (:id t/anonymous))
          (= :system (:type principal))
          (nil? (get (inv/resources eng) :member)))
    principal
    (let [row (or (load-member eng (:id principal))
                  (provision! eng principal))]
      (when (= :suspended (:state row))
        (throw (suspended (:id principal))))
      (update principal :roles into (get-in row [:data :roles])))))
