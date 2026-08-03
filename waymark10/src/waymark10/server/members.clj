(ns waymark10.server.members
  "Members: identity is a resource (waymark9 server/members.py, phase
  9a's deliverable widened by batch B). One member row per external
  principal — an auto-provisioned member's id IS the principal id —
  with display, actor type, held roles, and the machine. The admin
  console is the members collection; suspension and its lifting are
  audited transitions.

  THE BOUNDARY, documented: guards stay the only AUTHORIZATION
  concept. The gates here are authentication-adjacent — they decide
  whether the request carries a live identity at all, before any
  resource is addressed — so they live in the router's identity
  middleware and refuse with 403 problems, never with guard verdicts.
  What a live member may DO stays the guards' question.

  ── batch B: invite → bind ──────────────────────────────────────────

  waymark9's invited state comes home, resized to v10's shape. An
  admin's create carrying a :bind_token is born :invited (an
  on-create landing, the definitions born-:proposed precedent) with
  the inviter recorded; the row's id is its own (a uuid), because the
  principal it will bind to is unknown until the binding. The FIRST
  authenticated principal that presents the token (the
  X-Waymark-Invite header — any resolver, dev headers or OIDC bearer
  alike) binds: the concealed :bind transition (registrar system
  actor, logged, input recorded) writes the principal id into
  :subject and lands :active. Thereafter the gate finds the member by
  id OR by bound subject; the token is spent because :bind fires only
  from :invited — a second binder finds no invited row and is
  refused. Binding works under either membership mode; what the mode
  decides is the FALLBACK for an unknown principal:

    {:members :invited-only}   → refused, one 403 problem (membership
                                 is the admin's to extend)
    absent (the default)       → auto-provisioned on first sight, as
                                 before (a logged system-actor create)

  Recorded deviations and named punts (each a sentence):
  - the invite carries no email and the outbox sends nothing —
    waymark9 bound by verified email claim; v10 binds by the token
    the admin hands over out of band.
  - :bind_token renders like any field to an unscoped members-collection
    reader — waymark9's SECRET_FIELDS owner-gating is unported (v10
    has no member-level field modes; a named punt beside role
    uniqueness under race).
  - token uniqueness is unenforced under race (two invites sharing a
    token bind whichever the query returns first) — the role-create
    guard's sibling, recorded not fixed.
  - :handle uniqueness is unenforced too, and its consequence is
    quieter: two members sharing a handle send every ref that matches
    it to whichever row the query returns first. The same sibling,
    recorded the same way.
  - membership mode is per engine ({:members :invited-only} in the
    engine map); per-kind modes are unscoped.
  - waymark9's unbind (reset the binding, keep membership) is
    unported: v10's remedy is suspend, or a fresh invite.
  - roles ride the member (member→roles, waymark9's shape); the role
    registry (roles.clj) validates every assignment AND every invite
    (waymark9's roles_registered_at_invite, restored by the create
    guard now that invites exist).
  - system principals are not members: they are the engine's own
    actors (deploy, cascade, bytes, registrar), never provisioned,
    never gated."
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
  "The system actor that provisions and binds members."
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

(g/defguard registrar-binds
  {:reads [:principal]
   :hide true
   :explain "The binding is written by the identity gate at first sight, never by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(defhandler set-roles [row inp _ctx]
  (assoc-in row [:data :roles] (vec (distinct (:roles inp)))))

(defhandler set-subject [row inp _ctx]
  (assoc-in row [:data :subject] (:subject inp)))

(defhandler set-handle [row inp _ctx]
  (assoc-in row [:data :handle] (:handle inp)))

(defresource member
  {:kind :member
   :plural "members"
   :states [:invited :active :suspended]
   :initial :active
   ;; :invited is entered by the on-create landing (a token-bearing
   ;; invite), never by transition — the definitions :proposed precedent
   :allow-dead #{:invited}
   :terminal #{}                     ; suspension is reversible, deliberately
   :nav :system
   :summary "{data.display} · {state}"
   :label-template "{data.display}"
   :schema [:map
            [:display [:string {:min 1 :max 80}]]
            ;; the short name the household's OTHER systems already say.
            ;; :display is the human label an identity provider hands
            ;; over ("Colton Kopsa"); a chore feed says "colton". A
            ;; mirror's external-keyed ref matches THIS field, so a
            ;; synced assignee lands on a person instead of a string.
            [:handle {:optional true}
             [:maybe [:string {:min 1 :max 40}]]]
            [:actor_type [:enum "human" "agent"]]
            [:roles {:optional true}
             [:vector [:string {:min 1 :max 40}]]]
            ;; the invite's credential: presented once (X-Waymark-Invite)
            ;; by the principal the row will bind to
            [:bind_token {:optional true}
             [:maybe [:string {:min 8 :max 128}]]]
            ;; the bound principal id — written by :bind, never by hand
            [:subject {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 256}]]]
            [:invited_by {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 128}]]]]
   :filterable {:state #{:eq :in}
                :actor_type #{:eq}
                :subject #{:eq}
                ;; promoted because refs resolve against it — one
                ;; indexed read per distinct assignee, per sync pass
                :handle #{:eq}}
   :sortable {:fields [:display] :default "display"}
   :create-guards [roles-registered]
   ;; a token-bearing create is an INVITE: born :invited, the inviter
   ;; recorded (the definitions born-:proposed precedent — the create
   ;; transition logs the landing state honestly)
   :on-create (fn [row ctx]
                (if (get-in row [:data :bind_token])
                  (-> row
                      (assoc :state :invited)
                      (assoc-in [:data :invited_by]
                                (get-in ctx [:principal :id])))
                  row))
   :actions
   {:bind {:from #{:invited} :to :active
           :input [:map [:subject [:string {:min 1 :max 256}]]]
           :record true
           ;; :subject names a data field, but an :invited row has none
           ;; — the invite is unclaimed by definition, and the value
           ;; comes from the principal presenting the token, never from
           ;; the row. Prefilling it would offer back a blank at best
           ;; and a stale identity at worst (waymark-01f)
           :waives #{:edit-shape}
           :guards [registrar-binds]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "Binding welds the invitation to the principal that presented its token; a different account is a fresh invite."}
           :handler set-subject
           :display {:label "Bind" :order 5}}
    :assign_roles {:from #{:active} :to :active
                   :input [:map [:roles [:vector [:string {:min 1 :max 40}]]]]
                   :record true
                   :edit {:prefill [:roles]}   ; the fence rides along
                   :guards [roles-registered]
                   :safety {:idempotent true :reversible true :confirm false}
                   :handler set-roles
                   :display {:label "Assign roles" :order 2}}
    ;; the handle is unset at every birth — an auto-provisioned member
    ;; is named by its identity provider, which has never heard of the
    ;; chore board. Without this door a synced assignee could only ever
    ;; resolve for members born of an invite, so the door exists.
    :set_handle {:from #{:active} :to :active
                 :input [:map [:handle {:x-display {:label "Handle (what other systems call them)"}}
                               [:string {:min 1 :max 40}]]]
                 :record true
                 :edit {:prefill [:handle]}
                 :safety {:idempotent true :reversible true :confirm false}
                 :handler set-handle
                 :display {:label "Set handle" :order 3}}
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

(defn not-invited
  "The 403 problem an unknown principal answers with on an
  invited-only engine."
  [id]
  (p/problem :membership-invited 403 "Membership is invited"
             {:detail (str "No membership for " (pr-str id) "; membership "
                           "here is invited — ask an administrator for an "
                           "invitation.")}))

(defn- load-member [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :member id {}))))

(defn- member-by-subject
  "The bound member for a principal id, when a binding wrote it."
  [eng subject]
  (first (store/with-tx (:storage eng)
           (fn [tx] (store/query-rows (:storage eng) tx :member
                                      {:subject subject} {:limit 1})))))

(defn invited-by-token
  "The still-INVITED member row a presented token names, nil when the
  token matches nothing invited — the bind! lookup, public for the
  welcome document (GET /api/-/welcome), which teaches the invited
  agent its protocol without spending the token."
  [eng token]
  (when-not (str/blank? (str token))
    (let [candidate (first (store/with-tx (:storage eng)
                             (fn [tx] (store/query-rows
                                       (:storage eng) tx :member
                                       {:bind_token token} {:limit 1}))))]
      (when (and candidate (= :invited (:state candidate)))
        candidate))))

(defn bind-agent!
  "The /auth/agent door's bind (waymark10.server.oidc-rp): an agent
  holding ONLY the invite link has no principal yet, so the invited
  row binds to ITS OWN id — the row's identity becomes the principal
  the engine-minted session names, and gate! thereafter finds it by
  id like any member. Returns the now-active row; nil when the token
  names nothing invited — a spent link binds nobody twice, and the
  door's 404 says nothing more."
  [eng token]
  (when-some [candidate (invited-by-token eng token)]
    (try
      (inv/invoke! eng :member (:id candidate) :bind
                   {:subject (:id candidate)}
                   {:principal registrar})
      (load-member eng (:id candidate))
      (catch Exception _ nil))))

(defn- bind!
  "First sight of a presented invite token: the matching INVITED row
  binds to this principal through the concealed :bind transition
  (registrar, logged). nil when the token matches nothing invited —
  a spent token binds nobody twice; a race's loser refuses (the
  from-state is gone) and lands here as nil too."
  [eng principal token]
  (when-not (str/blank? (str token))
    (let [candidate (invited-by-token eng token)]
      (when candidate
        (try
          (inv/invoke! eng :member (:id candidate) :bind
                       {:subject (:id principal)}
                       {:principal registrar})
          (load-member eng (:id candidate))
          (catch Exception _ nil))))))

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

(def knock-shelf-cap
  "Unclaimed self-requested invitations that may stand at once — the
  standing wall: it persists across restarts because it IS the rows."
  12)

(def knock-log
  "The knock door's rolling-hour window, process-local: instants of
  every mint, pruned as the hour passes. An atom and not a query
  because query-rows' one ordering is oldest-first — a paced read
  past the limit would go stale exactly when an abuser needs it to
  (the grants pace guard's recorded punt, not repeated here). The
  cost is honesty about the trade: a restart forgets the window (the
  shelf cap above survives it). Tests may reset it."
  (atom []))

(defn- knock-paced!
  "Prune the window, refuse (429) when full, record this knock. The
  window is what the unclaimed-shelf cap cannot be: a wall an abuser
  cannot drain by BINDING each invite as it is minted — bound rows
  leave the shelf, but the mint stays in the hour."
  [eng ^java.time.Instant now]
  (let [limit (long (get-in eng [:services :agent-knock-hourly] 12))
        cutoff (.minusSeconds now 3600)
        ;; one swap: prune, and append only under the limit — the
        ;; check and the record are atomic, so a concurrent pair
        ;; cannot both pass through the last slot. Ours went in
        ;; exactly when the new value ends with OUR instant (swap!
        ;; returns the state our fn produced; identity, not equality,
        ;; because two knocks can share a millisecond).
        after (swap! knock-log
                     (fn [log]
                       (let [live (filterv #(neg? (compare cutoff %)) log)]
                         (if (< (count live) limit) (conj live now) live))))]
    (when-not (identical? (peek after) now)
      (throw (p/problem :agent-invite-pace 429 "The door is paced"
                        {:detail (str "Self-requested invitations are paced to "
                                      limit " an hour; knock again when the "
                                      "window reopens.")})))))

(defn knock!
  "The self-service half of the invite loop (the /agentInvite door):
  an agent with no credential and no standing invitation names itself
  and the door mints the SAME invite the Access panel mints — a
  token-bearing member create, born :invited — except the inviter
  recorded is the registrar, which marks the row as self-invited
  (the Access panel badges it: a knocker chose its own name, so an
  ask wearing a familiar display must still say nobody here minted
  it). Two walls pace the anonymous: the standing shelf (at most
  knock-shelf-cap unclaimed :invited rows — inert by design, an
  agent principal without a grant sees only the bootstrap surface)
  and the rolling hour (knock-paced!, which binding cannot drain).
  Returns the new row; the caller renders the welcome link (the
  agent's half) and the follow link (the human's half)."
  [eng {:keys [display handle]}]
  (when (nil? (get (inv/resources eng) :member))
    (throw (p/problem :not-found 404 "Not found"
                      {:detail "This engine keeps no members."})))
  (when (str/blank? (str display))
    (throw (p/problem :agent-invite-nameless 422 "Name yourself"
                      {:detail (str "POST {\"display\": \"your name\"} — the "
                                    "invitation needs a name to greet you by.")})))
  (let [open (store/with-tx (:storage eng)
               (fn [tx] (store/query-rows (:storage eng) tx :member
                                          {:invited_by (:id registrar)
                                           :state :invited}
                                          {:limit (inc knock-shelf-cap)})))]
    (when (>= (count open) knock-shelf-cap)
      (throw (p/problem :agent-invite-pace 429 "Too many open invitations"
                        {:detail (str knock-shelf-cap " self-requested "
                                      "invitations already stand unclaimed; "
                                      "the door pauses until one binds or an "
                                      "administrator clears the shelf.")}))))
  (knock-paced! eng (java.time.Instant/now))
  (:row (inv/create! eng :member
                     (cond-> {:display (subs (str display)
                                             0 (min (count (str display)) 80))
                              :actor_type "agent"
                              :bind_token (str (random-uuid))}
                       (not (str/blank? (str handle)))
                       (assoc :handle handle))
                     {:principal registrar})))

(defn gate!
  "The principal-resolution consult: anonymous and system principals
  pass untouched (system actors are the engine's own, not members);
  everyone else resolves to a member row — by id, by bound subject,
  by binding a presented invite token, or (default mode only) by
  auto-provision — is refused 403 while suspended or (invited-only
  mode) unknown, and carries the member's held roles unioned onto the
  credential's. Engines without the member kind gate nothing."
  ([eng principal] (gate! eng principal nil))
  ([eng principal invite-token]
   (if (or (nil? principal)
           (= (:id principal) (:id t/anonymous))
           (= :system (:type principal))
           (nil? (get (inv/resources eng) :member)))
     principal
     (let [invited-only? (= :invited-only (:members eng))
           row (or (load-member eng (:id principal))
                   (member-by-subject eng (:id principal))
                   (bind! eng principal invite-token)
                   (when-not invited-only? (provision! eng principal)))]
       (when (nil? row)
         (throw (not-invited (:id principal))))
       (when (= :suspended (:state row))
         (throw (suspended (:id principal))))
       ;; an invited row found by id would be unbound plumbing; only a
       ;; bind lands here in :invited → impossible, but honest anyway
       (update principal :roles into (get-in row [:data :roles]))))))
