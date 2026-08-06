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

  ── re-entry: the homecoming credential (waymark-4zj.8) ─────────────

  An ACTIVE agent member whose durable credential is unreachable can
  be handed a one-shot way back in: a recovery-admin HUMAN mints
  :offer_reentry (the token is minter-supplied — the bind_token
  precedent, and the row render conceals :secret fields, so the
  engine could never answer with a minted one), short-lived by guard.
  The /auth/agent door resolves it (reentrant-by-token), nulls it
  through the concealed registrar :spend_reentry — one shot under
  race because invoke.clj loads the row FOR UPDATE (invoke.clj ~892):
  a Postgres row lock, so a two-POST race's loser BLOCKS, re-reads the
  emptied row, and the spend's compare-and-set refuses (save-row!'s
  optimistic version check is only a memory-store backstop, never the
  wall on a lock-holding backend) — and mints a session naming the
  member's STABLE id; a fresh member is never minted. Fields separate
  from :bind_token, deliberately: invite semantics stay welded to
  :invited, and a re-entry mint can never masquerade as an unclaimed
  invite. The token rides the POST BODY only (never a query string,
  where it would land in access and proxy logs), is machine-minted at
  128 bits by the Access panel (never a human's typing), and the door
  is paced (reentry-door-paced!, a rolling-hour window kept separate
  from the invite door's own — N1) so the uniform 404 can't be guessed
  against a short window.

  Recorded deviations and named punts (each a sentence):
  - the invite carries no email and the outbox sends nothing —
    waymark9 bound by verified email claim; v10 binds by the token
    the admin hands over out of band.
  - :bind_token and :reentry_token are :secret (waymark-kyg): the
    punt that let a credential render raw to an unscoped members
    reader is closed — the row stays visible, the credentials never
    leave the engine in any projection.
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

;; the credential boundary (waymark-du2): assigning roles is how a
;; principal's authority GROWS, so the ASSIGNMENT itself must be
;; authorized — roles-registered only judges the role NAMES, never the
;; caller, so without this guard any authenticated human runs unscoped
;; and could POST assign_roles {:roles ["recovery-admin"]} onto its own
;; member row and self-escalate. Authority is the engine's own (a
;; :system write — the registrar's bootstrap, an engine-internal
;; invoke) OR a principal that ALREADY holds "recovery-admin" (gate!
;; unions the member's held roles, and the IdP's token roles claim
;; onto them, so a real admin authenticates WITH the role). The FIRST
;; admin therefore comes from the identity provider, never from a bare
;; assign_roles — which is what makes recovery-admin a real credential
;; boundary (the reconsent door depends on it). The exact
;; system-OR-recovery-admin shape the connections panel's revoke lever
;; draws (workqueue10.connections/revoke-is-recovery-admins). Not
;; hidden: an admin SHOULD see and use the action, and a non-admin
;; earns an honest refusal rather than a vanished door.
(g/defguard assign-is-recovery-admins
  {:reads [:principal]
   :explain "Assigning roles is the recovery-admin's authority (and the engine's own); a member cannot grant itself roles it does not already hold."}
  [_row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (contains? (set (:roles p)) "recovery-admin"))
      (t/allow)
      (t/deny))))

;; ── the re-entry mint (waymark-4zj.8) ───────────────────────────────

(def reentry-default-ttl-seconds
  "A minted re-entry link lives fifteen minutes unless the minter
  says less — long enough to hand across a live session, short
  enough that a mislaid handoff goes stale before it is found."
  (* 15 60))

(def reentry-max-ttl-seconds
  "The ceiling on a minted expiry — one hour by the live clock. The
  guard refuses longer; it never silently clamps, because a minter
  who asked for a day should learn the door's shape, not discover an
  hour later that the engine quietly disagreed."
  3600)

;; the second credential boundary on this kind (the assign_roles
;; lesson, applied): a re-entry token IS a session for its member, so
;; minting one must be narrower than holding one. HUMAN and the role,
;; both asserted — an agent holding recovery-admin must never mint
;; its own way back in (self-escalation, day one), and the engine's
;; own actors have no hand here either: a system path would be one
;; ctx :invoke away from any handler, which is exactly the reach a
;; credential mint must not have.
;;
;; BOTH facts come from the minter's own durable member ROW, never the
;; token (waymark-4zj.8.2 R1). The adversarial review's HIGH: the
;; token's :actor_type claim is OPTIONAL and principal-of defaults an
;; absent one to :human (oidc.clj) — a fail-open toward privilege on a
;; mint boundary, so an AGENT whose actor_type mapper is dropped, but
;; whose token still claims recovery-admin, resolved :human and minted
;; its own way back in. The engine's own record knows better: the row
;; carries the actor's true kind (provision! stamped it) and its
;; durably-assigned roles. A single source of truth, read from the
;; row gate! already loaded this principal against — a token that
;; cannot be matched to a HUMAN member row carrying recovery-admin
;; cannot mint, whatever it claims. (Stricter than assign_roles's
;; row-OR-claim on purpose: minting a credential is the most sensitive
;; act on this kind, so the role must be durably assigned, not merely
;; asserted this session.)
(g/defguard reentry-minters-are-recovery-admin-humans
  {:reads [:principal :member]
   :explain "Re-entry is minted by a recovery-admin human, in person; neither an agent nor the engine's own actors can mint a way back in."}
  [_row _inp ctx]
  (let [read' (:read ctx)
        find' (:find ctx)
        p (:principal ctx)]
    ;; the pure render probe carries no read hooks — advertise
    ;; optimistically there (roles-registered's precedent); the real
    ;; invoke, which DOES carry them, is the wall
    (if (or (nil? read') (nil? find'))
      (t/allow)
      (let [row (or (read' :member (:id p))
                    (first (find' :member {:subject (:id p)} {:limit 1})))]
        (if (and row
                 (= "human" (get-in row [:data :actor_type]))
                 (contains? (set (get-in row [:data :roles]))
                            "recovery-admin"))
          (t/allow)
          (t/deny))))))

;; the write fence (waymark-4zj.8.2 R2, the :subject precedent made
;; real): reentry_token/reentry_expires_at are schema fields, so a
;; plain create — which lands :active unless it carries a bind_token —
;; would otherwise stamp a LIVE credential with no mint guard, no
;; type check, no TTL cap and no audit (the review's HIGH: any
;; authenticated human POSTed a member row carrying reentry_token +
;; recovery-admin + a 365-day expiry → an invisible backdoor, since
;; the field is :secret). The ONLY writer is set-reentry, under
;; :offer_reentry's guards; a create OR edit that carries either field
;; by hand is refused. (Action inputs are closed maps, so no OTHER
;; action's :input can smuggle these; this guard closes the create
;; door the same way.)
(g/defguard reentry-not-written-by-hand
  {:judges [:reentry_token :reentry_expires_at]
   :explain "The re-entry credential is written by :offer_reentry alone, never by hand — a create or edit may not carry reentry_token or reentry_expires_at."}
  [_row inp _ctx]
  (if (or (contains? inp :reentry_token)
          (contains? inp :reentry_expires_at))
    (t/deny)
    (t/allow)))

(g/defguard reentry-targets-agents
  {:explain "Re-entry is the agent door's credential; a human re-enters through the identity provider, never through a minted token."}
  [row _inp _ctx]
  (if (= "agent" (get-in row [:data :actor_type]))
    (t/allow)
    (t/deny)))

;; ── provenance: the honest identity key (waymark-4zj.9.1) ────────────

;; the write fence (the :subject / reentry_token precedent, made real
;; for provenance): provenance is a schema field, so a plain create or
;; an edit could otherwise STAMP a durable identity onto a guest — and
;; the offer_reentry durable guard rests on it, so a hand-set "idp"
;; would forge a way home. The ONLY writer is :on-create's birth-path
;; landing (provision!→idp, an invite create→invite, knock!→knock);
;; a create OR edit carrying provenance by hand is refused. (Action
;; inputs are closed maps, so no other action's :input can smuggle it;
;; this closes the create/edit door the same way.)
(g/defguard provenance-not-written-by-hand
  {:judges [:provenance]
   :explain "Provenance is written by the birth path alone (first-sight, invite, or knock), never by hand — a create or edit may not carry provenance."}
  [_row inp _ctx]
  (if (contains? inp :provenance)
    (t/deny)
    (t/allow)))

;; the homecoming durable guard (waymark-4zj.8.3, closed): offer_reentry
;; had no durable-self guard, so a recovery-admin could mint a way home
;; onto a HOLLOW namesake — a knock-born guest row that owns no self, no
;; story (the live acceptance failure: the mint bound to an empty
;; duplicate 'Cairn'). Re-entry is offered ONLY to a durable identity,
;; and durable == provenance "idp" — the sole test. That label is
;; UNFORGEABLE: the birth path writes it alone, never a hand. We do NOT
;; check "owns an active :self": a :self is forgeable (waymark-4zj.10 —
;; a human can plant one naming any owner id), so it must never be a
;; security signal, and owns-self is dropped here as exactly that. The
;; guard FAILS CLOSED — a row carrying no provenance (one that predates
;; the backfill) is refused until the backfill labels it "idp", which
;; makes the backfill a REQUIRED deploy companion. A self-less "idp"
;; row is admitted deliberately (GAP A, resolved): it is your own
;; durable identity with an empty journal, not a foreign namesake —
;; safe to come home to, and the mint still needs a recovery-admin. The
;; verdict is a pure function of the stored label, so the render probe
;; and the real invoke read the same field: the probe can only
;; advertise what the invoke would mint — no probe path opens a door.
(g/defguard reentry-targets-durable
  {:explain "Re-entry is offered only to a durable identity — an IdP-backed member (provenance \"idp\"), the sole unforgeable durable signal."}
  [row _inp _ctx]
  (if (= "idp" (get-in row [:data :provenance]))
    (t/allow)    ; durable: the IdP vouches for it
    (t/deny)))   ; guest (invite/knock) or unlabelled — fail closed

(g/defguard reentry-is-short
  {:judges [:expires_at]
   :reads [:now]
   :vars [:max_minutes :asked]
   :explain "A re-entry link is short — at most {max_minutes} minutes; this one runs to {asked}. Mint closer to the moment of use."}
  [_row inp ctx]
  (if-some [^java.time.Instant exp (:expires_at inp)]
    (let [cap (.plusSeconds ^java.time.Instant (:now ctx)
                            reentry-max-ttl-seconds)]
      (if (pos? (compare exp cap))
        (t/deny {:vars {:max_minutes (quot reentry-max-ttl-seconds 60)
                        :asked (str exp)}})
        (t/allow)))
    (t/allow)))

;; the spend's own wall, a COMPARE-AND-SET (waymark-4zj.8.2 R4): spend
;; ONLY the exact token the door resolved, and only if the row still
;; carries it. Two properties ride this one guard, both under the
;; :for-update lock invoke.clj holds when it runs:
;;   • one-shot under race — the loser of a two-POST race re-loads a
;;     row the winner already emptied (reentry_token nil) and refuses;
;;   • revocation cannot be defeated (the review's TOCTOU MEDIUM) — a
;;     remint to a FRESH token between the door's lookup and its spend
;;     leaves the row carrying the new token, so a stale in-flight
;;     token no longer equals it and spends nothing. Without the
;;     compare, the stale holder's spend nulled whatever token was
;;     there, destroying the fresh credential a revoke had just
;;     minted.
;; Hidden: the wire never reaches this action; a race's loser (or a
;; stale token) deserves the door's uniform 404, not a narrated verdict.
;; :needs-input false ON PURPOSE: this guard must also run on the
;; concealment PROBE (nil input, invoke.clj ~920) so that a two-POST
;; race's loser — which re-reads the winner's emptied row — is 404'd
;; there, BEFORE step-8 natural-replay could replay the winner's spend
;; (both racers carry the same token, so the same input digest). On
;; the probe it conceals only when NO credential stands; a standing
;; one defers its exact match to the real spend, so a valid spend is
;; never 404'd before it runs.
(g/defguard reentry-token-matches
  {:judges [:token]
   :hide true
   :needs-input false
   :explain "No re-entry credential stands on this member."}
  [row inp _ctx]
  (let [standing (get-in row [:data :reentry_token])]
    (cond
      (nil? inp) (if (some? standing) (t/allow) (t/deny))
      (and (some? standing) (= standing (:token inp))) (t/allow)
      :else (t/deny))))

;; the mint's uniqueness wall (waymark-4zj.8.2 R6c): the header's
;; recorded "token uniqueness is unenforced" punt is tolerable for an
;; INVITE (two invites sharing a token bind whichever the query
;; returns first), but not for a one-shot re-entry credential — the
;; review showed one token minted onto two rows opening TWO sessions
;; as two identities, "exactly one session per credential" made false.
;; Refuse at mint a token already live on ANOTHER member row. (Checked
;; before the handler writes, so the target row does not yet carry it;
;; excluded by id regardless. reentry_token is :secret, not
;; :filterable — this scans data->>'reentry_token', the same read
;; reentrant-by-token does; a mint is rare, so the scan is cheap.)
(g/defguard reentry-token-is-fresh
  {:judges [:token]
   :reads [:member]
   :explain "That re-entry token is already live on another member — generate a fresh one."}
  [row inp ctx]
  (let [find' (:find ctx)
        tok (:token inp)]
    (if (and find' (some? tok))
      (if (seq (remove #(= (:id %) (:id row))
                       (find' :member {:reentry_token tok} {:limit 2})))
        (t/deny)
        (t/allow))
      (t/allow))))

(defhandler set-reentry [row inp ctx]
  ;; re-minting overwrites: at most ONE live re-entry credential per
  ;; member — the prior token dies the moment a fresh one lands
  (-> row
      (assoc-in [:data :reentry_token] (:token inp))
      (assoc-in [:data :reentry_expires_at]
                (or (:expires_at inp)
                    (.plusSeconds ^java.time.Instant (:now ctx)
                                  reentry-default-ttl-seconds)))))

(defhandler clear-reentry [row _inp _ctx]
  (update row :data dissoc :reentry_token :reentry_expires_at))

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
            ;; the honest identity key (waymark-4zj.9.1): where this row
            ;; was BORN — "idp" (Bearer/IdP first-sight, durable),
            ;; "invite" (an admin's invite token), "knock" (a
            ;; self-service /agentInvite). Written by the birth path
            ;; alone, in :on-create, NEVER by hand — the
            ;; provenance-not-written-by-hand guard closes the create/edit
            ;; door the :subject precedent closes. NOT :secret: it is the
            ;; VISIBLE differentiator the roster reads (durable vs guest),
            ;; so it renders raw. :optional for backfill tolerance — every
            ;; NEW row gets one, the 48 existing rows are classified by
            ;; the read-only provenance-backfill-proposal below and set by
            ;; hand. Durable? ≡ provenance == "idp".
            [:provenance {:optional true :x-display {:raw true}}
             [:enum "idp" "invite" "knock"]]
            [:roles {:optional true}
             [:vector [:string {:min 1 :max 40}]]]
            ;; the invite's credential: presented once (X-Waymark-Invite)
            ;; by the principal the row will bind to
            [:bind_token {:optional true :secret true}
             [:maybe [:string {:min 8 :max 128}]]]
            ;; the homecoming credential (waymark-4zj.8): a one-shot
            ;; re-entry token for an ACTIVE agent member, minted by
            ;; :offer_reentry, spent by the /auth/agent door. :min 22
            ;; is the entropy floor (~128 bits base64url) — the minter
            ;; supplies it, never the engine
            [:reentry_token {:optional true :secret true}
             [:maybe [:string {:min 22 :max 128}]]]
            ;; :secret too (waymark-4zj.8.2 R7): the raw expiry rendered
            ;; to any members reader was a live-credential beacon — it
            ;; announced which member holds a re-entry token and its
            ;; exact death time, a targeting signal beside the sealed
            ;; token. The door reads it off the decoded row (concealment
            ;; is a render-layer disposition, not a storage one); no
            ;; reader needs the instant
            [:reentry_expires_at {:optional true :secret true}
             [:maybe :waymark/instant]]
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
   :create-guards [roles-registered reentry-not-written-by-hand
                   provenance-not-written-by-hand]
   ;; a token-bearing create is an INVITE: born :invited, the inviter
   ;; recorded (the definitions born-:proposed precedent — the create
   ;; transition logs the landing state honestly). :on-create is also
   ;; the ONE place provenance is stamped (waymark-4zj.9.1), keyed on
   ;; the SAME signals it already reads — a token means a guest, and the
   ;; registrar as inviter distinguishes a self-service knock from an
   ;; admin's invite; a token-less create is a first-sight/admin-minted
   ;; DURABLE identity ("idp"). knock! creates through the registrar, so
   ;; a knock row lands "knock" and KEEPS it across its later bind
   ;; (bind/bind-agent! do not touch provenance).
   :on-create (fn [row ctx]
                (let [tok (get-in row [:data :bind_token])
                      inviter (get-in ctx [:principal :id])
                      provenance (cond
                                   (and tok (= inviter (:id registrar))) "knock"
                                   tok "invite"
                                   :else "idp")
                      row (assoc-in row [:data :provenance] provenance)]
                  (if tok
                    (-> row
                        (assoc :state :invited)
                        (assoc-in [:data :invited_by] inviter))
                    row)))
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
                   ;; two questions, both answered: WHO may assign
                   ;; (system or a recovery-admin) and WHAT names are
                   ;; assignable (registered roles only)
                   :guards [assign-is-recovery-admins roles-registered]
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
    ;; the homecoming mint (waymark-4zj.8). The token is
    ;; MINTER-SUPPLIED, the bind_token precedent: the row render
    ;; conceals :secret fields, so the engine could never hand a
    ;; minted one back without a one-time response seam this door
    ;; does not need. NOT :record — a recorded action persists its
    ;; RAW inputs into the transition log (invoke.clj), and this
    ;; input IS the credential; the transition row (actor, one-way
    ;; digest, summary) is still the audit that a link was minted,
    ;; by whom, when. Not hidden: an admin should see and use the
    ;; door, and a non-admin earns an honest refusal.
    :offer_reentry {:from #{:active} :to :active
                    :input [:map
                            [:token [:string {:min 22 :max 128}]]
                            [:expires_at {:optional true}
                             [:maybe :waymark/instant]]]
                    :guards [reentry-minters-are-recovery-admin-humans
                             reentry-targets-agents
                             reentry-targets-durable
                             reentry-is-short
                             reentry-token-is-fresh]
                    :safety {:idempotent true :reversible true :confirm false}
                    :handler set-reentry
                    :display {:label "Offer re-entry" :order 4}}
    ;; the door's spend (the :bind precedent): registrar only,
    ;; concealed; carries the exact token the door resolved
    ;; (waymark-4zj.8.2 R4). One shot under race because invoke.clj
    ;; loads the row FOR UPDATE (~892), so the loser BLOCKS, re-reads
    ;; the emptied row, and reentry-token-matches refuses; the same
    ;; compare defeats a stale token trying to spend a freshly-reminted
    ;; credential. NOT :record — the token would otherwise persist raw
    ;; into the transition log; the transition entry (actor, one-way
    ;; digest, summary) IS the audit that the link was spent.
    :spend_reentry {:from #{:active} :to :active
                    :input [:map [:token [:string {:min 22 :max 128}]]]
                    :guards [registrar-binds reentry-token-matches]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Spending nulls the credential; the way back in again is a fresh mint."}
                    :handler clear-reentry
                    :display {:label "Spend re-entry" :order 6}}
    ;; suspension revokes the credential too (waymark-4zj.8.2 R8): a
    ;; suspend that only HID the member (its old behaviour) left the
    ;; re-entry token live on the row, so a suspend/reinstate cycle
    ;; resurrected the same way back in. Nulling it here makes suspend
    ;; a real credential reset — a reinstated member re-enters through
    ;; a fresh mint, not a token that outlived its suspension.
    :suspend {:from #{:active} :to :suspended
              :safety {:idempotent true :reversible true :confirm true
                       :consequence "Every request this member makes is refused until reinstated; their held roles stop acting, and any standing re-entry credential is revoked."}
              :handler clear-reentry
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

(defn re-enterable-by-token
  "The guest door's return path (the magic link, durable for its
  window): the ACTIVE member a presented token names — but only when
  the row bound to ITSELF, the credential-less door's own signature;
  a header-bound agent's spent token re-admits nobody, its principal
  lives elsewhere. Whether re-entry is WELCOME is the standing
  grant's question, not this one's. nil is the same 404 as a spent
  link."
  [eng token]
  (when-not (str/blank? (str token))
    (let [row (first (store/with-tx (:storage eng)
                       (fn [tx] (store/query-rows (:storage eng) tx :member
                                                  {:bind_token token}
                                                  {:limit 1}))))]
      (when (and row (= :active (:state row))
                 (= (:id row) (get-in row [:data :subject])))
        row))))

(defn- reentry-row-by-token
  "The decoded :member row carrying this EXACT re-entry token, whatever
  its state or expiry — nil when no row does. Decodes because the
  expiry compare needs its instant back (standing-grant-for's recorded
  shape: the store hands strings)."
  [eng token]
  (when-not (str/blank? (str token))
    (let [rdef (get (inv/resources eng) :member)]
      (some->> (store/with-tx (:storage eng)
                 (fn [tx] (store/query-rows (:storage eng) tx :member
                                            {:reentry_token token}
                                            {:limit 1})))
               first
               (inv/decode-row rdef)))))

(defn- reentry-live?
  "Is this row's re-entry credential ADMISSIBLE at the door: an ACTIVE,
  AGENT member (waymark-4zj.8.2 R3 — the agents-only rule holds at the
  door, not only at the mint, defence in depth against any row that
  acquired a token by some other path) whose window is still open by
  the live clock ((:now-fn eng), the same clock grants enforce with)."
  [eng row]
  (let [exp (get-in row [:data :reentry_expires_at])]
    (boolean
     (and row
          (= :active (:state row))
          (= "agent" (get-in row [:data :actor_type]))
          exp
          (neg? (compare ((:now-fn eng)) exp))))))

(defn reentrant-by-token
  "The homecoming door's lookup (waymark-4zj.8): the ACTIVE agent
  member a presented re-entry token names, while the token's window is
  open by the live clock. nil otherwise — unknown, spent, expired,
  suspended and human-actor all collapse to the door's one 404, and
  nothing here spends. A pure lookup; the door's spend (and the lazy
  sweep of dead tokens) lives in spend-reentry!."
  [eng token]
  (when-some [row (reentry-row-by-token eng token)]
    (when (reentry-live? eng row) row)))

(defn spend-reentry!
  "The /auth/agent door's re-entry resolution. On a LIVE credential:
  null it through the concealed :spend_reentry (registrar, logged,
  carrying the exact token so the spend is a compare-and-set), and
  hand back the still-ACTIVE row the session will name — the member's
  STABLE id, never a fresh row. nil when the token names nobody, or
  when the row it names cannot open the door.

  One shot under race: invoke.clj loads the row FOR UPDATE (~892), so
  a two-POST race's loser BLOCKS, re-reads the emptied row, and
  reentry-token-matches refuses — exactly one session per credential,
  however the race falls.

  R9 (lazy sweep): a token that names an EXPIRED but still-:active row
  is dead by the clock — null it here (best-effort, the same
  compare-and-set spend) so it does not linger raw in rows and nightly
  backups. This nulls, it never mints a session: the door still
  answers its uniform 404, and an expired token still 'does not
  spend' in the sense the spec meant — no way back in is opened."
  [eng token]
  (when-some [row (reentry-row-by-token eng token)]
    (if (reentry-live? eng row)
      (try
        (inv/invoke! eng :member (:id row) :spend_reentry {:token token}
                     {:principal registrar})
        (load-member eng (:id row))
        (catch Exception _ nil))
      (let [exp (get-in row [:data :reentry_expires_at])]
        (when (and (= :active (:state row)) exp
                   (not (neg? (compare ((:now-fn eng)) exp))))
          (try
            (inv/invoke! eng :member (:id row) :spend_reentry {:token token}
                         {:principal registrar})
            (catch Exception _ nil)))
        nil))))

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

;; ── the /auth/agent door's pacing (waymark-4zj.8.2 R6b; N1 fix) ──────
;;
;; The door is ANONYMOUS and pre-auth — an agent arrives holding only a
;; link — so the window must be a wall a flood cannot turn into a
;; lockout of everyone else. Two failures shaped this design:
;;
;;   • DECOUPLED buckets (N1). One process-global window (default 60/hr)
;;     shared by BOTH flows and charged before EITHER branch meant an
;;     anonymous flood of 60 garbage tokens 429'd everyone — legitimate
;;     invite onboarding AND re-entry homecoming alike. This is the
;;     Keycloak-unreachable FALLBACK door, the one time it most needs to
;;     stay open, so the two flows get SEPARATE windows: exhausting one
;;     can never starve the other. A re-entry guessing flood (body
;;     tokens) burns only the re-entry bucket; invite onboarding (a
;;     query ?invite=) burns only the invite bucket.
;;
;;   • GLOBAL, not per-source keyed — deliberately, and stated so it is
;;     not mistaken for an oversight. No trustworthy per-source key
;;     stands at this pre-auth door: every engine sits behind the
;;     reverse proxy that fronts the household's apps, so http-kit's
;;     :remote-addr collapses to the proxy's own address (one value for
;;     the whole world — a global bucket wearing a disguise), and
;;     X-Forwarded-For is client-supplied and is NOT confirmed anywhere
;;     this repo can see to be set-and-sanitized by that proxy. Keying
;;     on a spoofable header would give FALSE assurance — an abuser
;;     rotates the value per request and the cap never bites — so it is
;;     refused (the brief's rule). Keying by target member is
;;     impossible too: the member is known only AFTER the token lookup,
;;     and the pace must run BEFORE it so the window leaks nothing about
;;     which tokens exist.
;;
;; So each flow gets one GENEROUS global ceiling: high enough that
;; legitimate use never approaches it (re-entry is a rare emergency;
;; invite onboarding rarer still — a handful an hour at the very most),
;; low enough that a crude high-rate flood trips it within a second and
;; the log stays bounded. 128-bit machine-minted tokens are the real
;; wall against guessing; this pacing is defense-in-depth beside them.
;; A restart forgets the window (honest, as knock-log is). Tests reset
;; the atoms.

(def reentry-door-log
  "The re-entry (homecoming) flow's rolling-hour window on /auth/agent,
  process-local. Charged by body-token attempts. See the block above."
  (atom []))

(def invite-door-log
  "The invite-bind (onboarding) flow's rolling-hour window on
  /auth/agent, process-local. Charged by query ?invite= attempts.
  Separate from reentry-door-log so neither flow can starve the other."
  (atom []))

(defn- door-swap!
  "The atomic prune-check-record swap knock-paced! builds, factored so
  the two /auth/agent buckets share one implementation. Records this
  attempt only if the live window is under the limit; returns true when
  admitted, false when full. Every attempt that lands is recorded
  regardless of the token's fate, so the pace itself leaks nothing
  about which tokens exist. The check and the record are one swap, so a
  concurrent flood cannot both pass the last slot."
  [log-atom ^long limit ^java.time.Instant now]
  (let [cutoff (.minusSeconds now 3600)
        after (swap! log-atom
                     (fn [log]
                       (let [live (filterv #(neg? (compare cutoff %)) log)]
                         (if (< (count live) limit) (conj live now) live))))]
    (identical? (peek after) now)))

(defn reentry-door-paced!
  "Pace a re-entry (homecoming) attempt in its own window. Returns true
  when admitted, false when the bucket is full (the caller answers
  429). Generous global default 600/hour — see the block above."
  [eng ^java.time.Instant now]
  (door-swap! reentry-door-log
              (long (get-in eng [:services :reentry-door-hourly] 600))
              now))

(defn invite-door-paced!
  "Pace an invite-bind (onboarding) attempt in its own window, separate
  from re-entry (N1). Returns true when admitted, false when full.
  Generous global default 600/hour — see the block above."
  [eng ^java.time.Instant now]
  (door-swap! invite-door-log
              (long (get-in eng [:services :invite-door-hourly] 600))
              now))

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

;; ── the provenance backfill (waymark-4zj.9.1, READ-ONLY) ─────────────
;;
;; The 48 rows that predate the provenance field need one classified,
;; once. This is a REQUIRED deploy companion, not an optional cleanup:
;; the offer_reentry durable guard fails CLOSED on a row with no
;; provenance, so until this is applied EVERY real durable row is
;; refused a way home. It is the migrate dry-run posture: it READS every
;; member, PROPOSES a provenance per row, and writes NOTHING —
;; Cairn/Colton apply the result by hand against prod after reading it.
;; It never mutates any database, so it is safe to run against a live
;; engine.

(defn provenance-backfill-proposal
  "READ-ONLY. Classify every existing member row for the one-time
  provenance backfill. Returns a vector of
    {:id :display :actor_type :state :provenance :because {…}}
  one per member. NEVER writes — the caller applies the proposal by
  hand. The classifier reads the UNFORGEABLE subject/origin signals the
  birth paths leave. It does NOT use \"owns a :self\": a :self is
  forgeable (waymark-4zj.10 — a human can plant one on any owner id), so
  a planted self would poison the classifier and mis-propose a hollow
  guest as durable. The signals instead:

    • never bound to a subject, and no invite/knock origin (no
      invited_by, no bind_token) → \"idp\"  (durable — a first-sight /
      admin-minted IdP identity)
    • self-bound (subject == its own id — the :bind signature of a
      knock or a self-claimed invite): a guest. invited_by == the
      registrar tells a self-service knock from an admin's invite; where
      the origin is post-hoc indistinguishable, the guest defaults to
      \"invite\" (noted in :because — a reviewer can promote it).
    • a self-service knock still unclaimed (registrar as inviter, not
      yet bound) → \"knock\"
    • the rest — an admin's invite, bound to a foreign subject or still
      unclaimed → \"invite\"

  Where a row already carries provenance (a new row born after this
  change), the PROPOSAL is still recomputed from signals so a reviewer
  can spot any drift; it does not read the stored value."
  [eng]
  (let [st (:storage eng)
        members (store/with-tx st
                  (fn [tx] (store/query-rows st tx :member {} {:limit 100000})))]
    (mapv
     (fn [row]
       (let [id (:id row)
             d (:data row)
             subject (:subject d)
             invited-by (:invited_by d)
             bind-token? (some? (:bind_token d))
             self-invited? (= invited-by (:id registrar))
             subject-is-self? (boolean (and subject (= subject id)))
             provenance (cond
                          ;; never bound, no invite/knock credential
                          ;; origin → a durable IdP identity
                          (and (nil? subject) (nil? invited-by)
                               (not bind-token?)) "idp"
                          ;; self-bound (subject == its own id): a guest;
                          ;; the registrar inviter tells knock from
                          ;; invite, else default the guest to "invite"
                          subject-is-self? (if self-invited? "knock" "invite")
                          ;; a self-service knock still unclaimed
                          self-invited? "knock"
                          ;; the rest — an admin's invite
                          :else "invite")]
         {:id id
          :display (:display d)
          :actor_type (:actor_type d)
          :state (:state row)
          :provenance provenance
          :because {:subject subject
                    :subject-is-self? subject-is-self?
                    :self-invited? self-invited?
                    :invited_by invited-by
                    :bind_token? bind-token?}}))
     members)))

(defn print-provenance-backfill-proposal
  "Pretty-print provenance-backfill-proposal for a human to review
  before applying by hand. READ-ONLY: it prints, it never writes.
  Returns the proposal vector."
  [eng]
  (let [rows (provenance-backfill-proposal eng)]
    (println (str "provenance backfill proposal — " (count rows)
                  " member rows (READ-ONLY; nothing written)"))
    (doseq [{:keys [id display actor_type state provenance because]} rows]
      (println (format "  %-6s  %-38s  %-6s  %-9s  %-28s  %s"
                       provenance id (str actor_type) (name state)
                       (pr-str (str display)) (pr-str because))))
    rows))
