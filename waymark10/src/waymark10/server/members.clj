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

  ── the gate heals what it finds (waymark-tti.10) ───────────────────

  provision! stamps a minted row's :subject with its own id, which is
  what lets a reader outside this file (workqueue10's letters door)
  ask 'does a principal answer to this row?' without re-deriving the
  birth paths. But it only stamps what it MINTS: an :active row that
  predates the stamp — someone a human added to the roster by hand,
  a legacy first-sight row — had no door to gain one, because :bind
  is :from #{:invited} and must stay there. So the GATE stamps what
  it FINDS: when a principal resolves to a row BY ID and that row's
  :subject is blank, the concealed registrar :stamp_subject writes
  the row's own id. Resolving by id IS the proof of the fact being
  recorded — the row id is the principal id — so the write is a
  semantic no-op that makes a true thing readable. Only the by-id
  branch, only :active, only when blank (so it fires once in a row's
  life, never per request), and never fatal: a failed stamp warns and
  the request proceeds. A PHANTOM row (an id no principal answers to)
  is resolved by no branch, so it is never healed — correctly, since
  nobody is there.

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

  The standing rotation (waymark-53u) rides beside the handoff: the
  auth doors re-mint the way home through the concealed registrar
  :rotate_reentry — at bind, at the homecoming spend, and at a live
  renew past half-life — seven days per mint, each REPLACING the
  last, refused for guests by the durable guard. The agent never
  chooses to mint; the engine rotates a credential a human's act
  first anchored, so the offer_reentry mint boundary stands.

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

(def reentry-standing-ttl-seconds
  "The standing rotation's window (waymark-53u) — seven days by the
  live clock. Long enough that a client machine down for a long
  weekend still comes home on its own credential; short enough that
  a mislaid token dies within the week. The HUMAN handoff keeps its
  one-hour ceiling above — this constant belongs to :rotate_reentry
  alone, and the two doors never share a TTL on purpose: a handoff
  is a moment, a standing credential is a season."
  (* 7 24 3600))

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

;; the same fence for acts_for (docs/spec-connector-door.md § 3): who a
;; delegate acts for is a fact the identity gate read off a verified
;; token's azp and sub, and the router reads it to look for the grant
;; the delegate wears. Hand-set, it would let any authenticated human
;; mint an agent row that CLAIMS to act for somebody and then file
;; asks in that name. Unlike provenance the birth path stamps it
;; through the create INPUT (provision! has no :on-create hook of its
;; own), so the registrar — a system actor, the way registrar-binds
;; reads it — is the one writer allowed through.
(g/defguard acts-for-not-written-by-hand
  {:judges [:acts_for]
   :reads [:principal]
   :explain "Who an agent acts for is written by the identity gate at first sight, never by hand — a create may not carry acts_for."}
  [_row inp ctx]
  (if (and (contains? inp :acts_for)
           (not= :system (get-in ctx [:principal :type])))
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

;; the rotation's own ceiling (waymark-53u): the engine computes the
;; expiry it asks for, so this guard is defense in depth — a future
;; caller reaching for a month learns the door's shape instead of
;; being silently clamped, reentry-is-short's philosophy held at the
;; standing credential's own scale.
(g/defguard reentry-standing-is-bounded
  {:judges [:expires_at]
   :reads [:now]
   :vars [:max_days :asked]
   :explain "A standing re-entry credential lives at most {max_days} days; this one runs to {asked}."}
  [_row inp ctx]
  (if-some [^java.time.Instant exp (:expires_at inp)]
    (let [cap (.plusSeconds ^java.time.Instant (:now ctx)
                            reentry-standing-ttl-seconds)]
      (if (pos? (compare exp cap))
        (t/deny {:vars {:max_days (quot reentry-standing-ttl-seconds 86400)
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

;; ── the presence curtain (waymark-tti.4) ────────────────────────────

;; the curtain is your own hand: drawing or opening a presence
;; curtain is SELF-SERVICE — the caller must BE the member row (its
;; principal id is the row's id, or the subject a :bind wrote:
;; gate!'s own two resolutions, so a bound member is not locked out
;; of its own curtain). One valve: a recovery-admin HUMAN may also
;; draw or open (the household recovery lever — e.g. a member whose
;; only client is wedged BEHIND its curtain), with the role AND the
;; type both read from the admin's own durable MEMBER ROW, never the
;; token (the reentry-minters precedent: an absent actor_type claim
;; fails open toward :human, so a claim is no wall on a privacy
;; switch). :system principals are refused even wearing the role —
;; the engine's own actors must not reach through a member's privacy
;; switch: a system path would be one ctx :invoke away from any
;; handler. Not hidden: a stranger's refusal should read as an
;; honest no, and absence from the presence board stays legible as
;; "curtained or away — the member row says which".
(g/defguard curtain-is-your-own-hand
  {:reads [:principal :member]
   :explain "The curtain is its member's own to draw or open; only that member themself (or a recovery-admin human, the household valve) may touch it."}
  [row _inp ctx]
  (let [p (:principal ctx)
        pid (:id p)
        read' (:read ctx)
        find' (:find ctx)]
    (cond
      ;; never the engine's own actors, whatever roles they carry —
      ;; and judged FIRST, so a system principal whose id happens to
      ;; collide with a member row id gains nothing from the
      ;; own-hand branch below
      (= :system (:type p)) (t/deny)
      ;; your own row: by id, or by the subject a binding wrote
      (and (some? pid)
           (or (= pid (:id row))
               (= pid (get-in row [:data :subject])))) (t/allow)
      ;; the pure render probe carries no read hooks — advertise
      ;; optimistically there (reentry-minters' precedent); the real
      ;; invoke, which DOES carry them, is the wall
      (or (nil? read') (nil? find')) (t/allow)
      :else
      ;; the recovery valve: role + type from the caller's own
      ;; durable member row (by id, or by bound subject)
      (let [own (or (read' :member pid)
                    (first (find' :member {:subject pid} {:limit 1})))]
        (if (and own
                 (= "human" (get-in own [:data :actor_type]))
                 (contains? (set (get-in own [:data :roles]))
                            "recovery-admin"))
          (t/allow)
          (t/deny))))))

(defhandler draw-curtain [row _inp _ctx]
  (assoc-in row [:data :curtain] true))

(defhandler open-curtain [row _inp _ctx]
  (assoc-in row [:data :curtain] false))

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

;; the gate's heal (waymark-tti.10): the same stamp provision! makes at
;; birth, reachable for a row that PREDATES it. It takes NO INPUT on
;; purpose — the value written is the row's OWN id, never a caller's
;; word — which makes this handler strictly narrower than set-subject
;; (whose subject comes from the principal presenting a token): there
;; is no spelling of this action, by any actor, that writes a FOREIGN
;; subject. Semantically a no-op, exactly as provision!'s stamp is:
;; gate! reaches it only for a row it resolved BY ID, where the row id
;; already IS the principal id, so the write records a fact that was
;; already true and makes it readable outside this namespace.
(defhandler stamp-own-subject [row _inp _ctx]
  (assoc-in row [:data :subject] (:id row)))

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
            [:display {:x-display {:label "Name"
                                   :help "What the house calls this person or agent — the words that appear on a card, a roster row and a letter's from-line."}}
             [:string {:min 1 :max 80}]]
            ;; the short name the household's OTHER systems already say.
            ;; :display is the human label an identity provider hands
            ;; over ("Colton Kopsa"); a chore feed says "colton". A
            ;; mirror's external-keyed ref matches THIS field, so a
            ;; synced assignee lands on a person instead of a string.
            [:handle {:optional true
                      :x-display {:raw true
                                  :label "Handle"
                                  :help "The short name the household's OTHER systems already say — the token a chore feed or a synced assignee arrives as (\"colton\"), lowercase and unspaced."}}
             [:maybe [:string {:min 1 :max 40}]]]
            [:actor_type {:x-display {:label "Person or agent"
                                      :choices {"human" "A person — signs in, reads pages, decides"
                                                "agent" "An agent — holds a leash, acts on somebody's behalf"}}}
             [:enum "human" "agent"]]
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
            [:provenance {:optional true
                          :x-display {:raw true
                                      :label "How they got here"
                                      :choices {"idp" "Signed in — a durable identity the provider vouches for"
                                                "invite" "Invited — an admin minted a token and handed it over"
                                                "knock" "Knocked — a self-service arrival at the agent door"}}}
             [:enum "idp" "invite" "knock"]]
            ;; the presence curtain (waymark-tti.4): a durable,
            ;; self-service "do not publish my presence". The durable
            ;; promise is read by ONE component (server/curtain.clj)
            ;; and honored at PUBLISH time by every ephemeral door
            ;; that could name you: presence's three reporting doors
            ;; (report!, read!, stream-open!/closed!), its snapshot,
            ;; its heartbeat re-assertion and its sweep; and the
            ;; intents stream — report!, its snapshot, its
            ;; re-assertion, and answer!, where a curtained answerer's
            ;; ANSWER still lands (answering is a legible act) with
            ;; its :by omitted, so the resolution is public and the
            ;; answerer is not. A visible FIELD and not :secret on purpose: the
            ;; whole point is that the no is legible state ("curtained,
            ;; not away"), never a silent disappearance. Absent = open.
            ;; Written by :draw_curtain/:open_curtain under the
            ;; own-hand guard; a create may pre-draw its own (privacy
            ;; only ever increases by hand), and no edit door exists on
            ;; this kind to flip another's off.
            [:curtain {:optional true
                       :x-display {:raw true
                                   :label "Presence curtain drawn"}}
             [:maybe :boolean]]
            ;; No :x-options, and the reasoning is roles.clj's twin
            ;; (waymark-7rw): roles-registered judges these against the
            ;; role REGISTRY, which is rows in a collection, not a
            ;; projection of any declaration — none of the five sources
            ;; can reach it without a scoped, paged data read, and the
            ;; guard already names the door where a missing role is MADE
            ;; (:remedies [:role/create]). So the sentence says where
            ;; the words come from and the refusal names the ones that
            ;; are not there yet. waymark-90k holds the row-backed source
            [:roles {:optional true
                     :examples [["recovery-admin"]]
                     :x-display {:raw true
                                 :label "Roles held"
                                 :help "Role names from the roles registry, exactly as spelled there. A name no active role carries is refused — register the role first; an assignment naming it would grant nobody."}}
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
            [:subject {:optional true
                       :x-display {:raw true
                                   :label "Bound principal"
                                   :help "The identity this row is welded to, written by the identity gate at first sight and never by hand."}}
             [:maybe [:string {:max 256}]]]
            ;; the delegate's person (docs/spec-connector-door.md § 3):
            ;; the subject of the human whose token, minted through a
            ;; connector client, this agent row answers to. Written by
            ;; the identity gate at first sight (provision!, through
            ;; the registrar) and fenced by acts-for-not-written-by-
            ;; hand. Empty for every person and for every agent that
            ;; holds its own key. Raw: it is a principal id.
            [:acts_for {:optional true
                        :x-display {:raw true
                                    :label "Acts for"
                                    :help "The person this agent acts for — the identity a connector's token named when the gate first saw it. Written by the identity gate, never by hand; empty for people and for agents holding their own key."}}
             [:maybe [:string {:max 256}]]]
            [:invited_by {:optional true
                          :x-display {:raw true
                                      :label "Invited by"
                                      :help "The member id of whoever minted the invite — stamped at birth by the create path, not typed."}}
             [:maybe [:string {:max 128}]]]]
   :filterable {:state #{:eq :in}
                :actor_type #{:eq}
                :subject #{:eq}
                ;; acts_for is deliberately NOT filterable: a filterable
                ;; field is a generated, indexed column (store/postgres),
                ;; so promoting it would be a schema migration on every
                ;; deployment for a question nothing asks yet
                ;; (spec-connector-door § recorded costs)
                ;; promoted because refs resolve against it — one
                ;; indexed read per distinct assignee, per sync pass
                :handle #{:eq}}
   :sortable {:fields [:display] :default "display"}
   :create-guards [roles-registered reentry-not-written-by-hand
                   provenance-not-written-by-hand
                   acts-for-not-written-by-hand]
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
    ;; the gate's heal (waymark-tti.10). :bind cannot do this work —
    ;; it is :from #{:invited} by design (that from-set IS how a token
    ;; is spent exactly once) and must not be widened — so a
    ;; pre-existing :active row with no :subject had no door at all,
    ;; and letters' deliverability read it as nobody. This is that
    ;; door, and it is the identity gate's ALONE:
    ;;   • registrar-binds, the SAME guard :bind wears — system
    ;;     principals only, and :hide true, so the action is concealed
    ;;     from every wire affordance (absent from :actions AND from
    ;;     :unavailable) and answers 404 to a POST by hand. A human,
    ;;     an agent, a recovery-admin: all 404. Exactly the
    ;;     bind/spend_reentry posture.
    ;;   • no :input, so the handler writes the row's own id and
    ;;     nothing else — even the registrar cannot stamp a foreign
    ;;     subject here. Not :record for the same reason it has no
    ;;     input (resource.clj refuses :record without one); the
    ;;     transition row — registrar, action, when — IS the audit.
    ;; :from #{:active} deliberately: an :invited row's subject is the
    ;; binding's to write (a heal there would spend the invite without
    ;; the token), and a SUSPENDED row is refused 403 one line after
    ;; the gate resolves it — writing on a refused request buys
    ;; nothing, and a reinstate is followed by a sign-in that heals it
    ;; then. Idempotent because the gate only calls it on a BLANK
    ;; subject; :idempotent true so the machine's natural replay is a
    ;; second wall under a race.
    :stamp_subject {:from #{:active} :to :active
                    :guards [registrar-binds]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "The stamp records a fact already true — this row's id IS its principal's; there is no un-stamp, and none is wanted."}
                    :handler stamp-own-subject
                    :display {:label "Stamp subject" :order 10}}
    :assign_roles {:from #{:active} :to :active
                   :input [:map
                           [:roles {:examples [["recovery-admin"]]
                                    :x-display {:raw true
                                                :label "Roles held"
                                                :help "The COMPLETE list this member should hold afterwards — the assignment replaces what is there, it does not add to it. Names come from the roles registry, spelled exactly as registered."}}
                            [:vector [:string {:min 1 :max 40}]]]]
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
                 :input [:map
                         [:handle {:x-display {:raw true
                                               :label "Handle (what other systems call them)"
                                               :help "The short unspaced name a chore feed or a synced assignee arrives as. Set it to the spelling those systems already use, or the ref lands on a string instead of a person."}}
                          [:string {:min 1 :max 40}]]]
                 :record true
                 :edit {:prefill [:handle]}
                 :safety {:idempotent true :reversible true :confirm false}
                 :handler set-handle
                 :display {:label "Set handle" :order 3}}
    ;; the presence curtain's two touches (waymark-tti.4):
    ;; self-service, durable, one press each way. No :input on
    ;; purpose — and therefore no :record (resource.clj refuses
    ;; :record without :input): the transition rows themselves are
    ;; the audit that a curtain was drawn or opened, by whom, when —
    ;; and nothing more. NO gaze history rides this kind, by design.
    :draw_curtain {:from #{:active} :to :active
                   :guards [curtain-is-your-own-hand]
                   :safety {:idempotent true :reversible true :confirm false}
                   :handler draw-curtain
                   :display {:label "Draw curtain" :order 7}}
    :open_curtain {:from #{:active} :to :active
                   :guards [curtain-is-your-own-hand]
                   :safety {:idempotent true :reversible true :confirm false}
                   :handler open-curtain
                   :display {:label "Open curtain" :order 8}}
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
                            [:token {:x-display {:raw true
                                                 :label "Re-entry token"
                                                 :help "The one-shot credential you are about to hand over, minted by YOU — at least 22 characters of real randomness. The engine never generates it and never shows it again."}}
                             [:string {:min 22 :max 128}]]
                            [:expires_at {:optional true
                                          :x-display {:label "Good until"
                                                      :help "When the link goes stale. Leave it empty for the short default — long enough to hand across a live session, short enough that a mislaid handoff dies before it is found."}}
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
    ;; the standing rotation (waymark-53u): the engine re-mints the
    ;; way home at the auth doors — the invite bind, the homecoming
    ;; spend, and a live renew whose credential has burned half its
    ;; life. This does NOT breach the mint boundary offer_reentry
    ;; defends ("an agent must never mint its own way back in"): the
    ;; agent never CHOOSES to mint — the engine rotates only at the
    ;; moment the agent presents a still-live credential whose chain
    ;; of custody began in a human act (the invitation, or a
    ;; recovery-admin's hand), and each mint REPLACES the prior token
    ;; (set-reentry's overwrite: at most one live credential per
    ;; member), so rotation never widens what a human handed out — it
    ;; renews it, one audited transition row per mint. The durable
    ;; guard still holds: a knock-born guest never grows a standing
    ;; way home, so the ephemeral posture of guest access survives
    ;; rotation untouched. Registrar-only and concealed, the
    ;; bind/spend_reentry posture; the raw token rides the input and
    ;; NOT :record, the offer_reentry precedent — the transition row
    ;; (actor, digest, when) is the audit and the credential never
    ;; persists into the log. suspend still revokes (clear-reentry),
    ;; so a suspended agent's rotation dies with its access.
    :rotate_reentry {:from #{:active} :to :active
                     :input [:map
                             [:token [:string {:min 22 :max 128}]]
                             [:expires_at {:optional true}
                              [:maybe :waymark/instant]]]
                     :guards [registrar-binds
                              reentry-targets-agents
                              reentry-targets-durable
                              reentry-standing-is-bounded
                              reentry-token-is-fresh]
                     ;; :reversible false, the spend_reentry posture:
                     ;; a self-loop behind a hidden guard has no
                     ;; usable reverse (checks.clj's reversible gate),
                     ;; and honestly none exists — each mint destroys
                     ;; the prior credential
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Each rotation replaces the standing credential; the prior token dies with it, and the way back is another mint."}
                     :handler set-reentry
                     :display {:label "Rotate re-entry" :order 5}}
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

(defn- warn!
  "One *err* line (the presence/collab idiom). Nothing in the identity
  gate may take a request down: what fails here is repair, not
  authentication."
  [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 members: " parts))))

(defn- load-member [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :member id {}))))

(defn- member-by-subject
  "The bound member for a principal id, when a binding wrote it."
  [eng subject]
  (first (store/with-tx (:storage eng)
           (fn [tx] (store/query-rows (:storage eng) tx :member
                                      {:subject subject} {:limit 1})))))

(defn spellings-of
  "Every id this principal ANSWERS TO, principal id first
  (waymark-1zq). The gate's own resolution read BACKWARDS: `gate!`
  finds a member by id and then by bound `:subject`, so the ids that
  reach the same inhabitant are the principal id itself and the row
  id of the member whose `:subject` is that principal — the id a
  binding left behind, and the id a person reads off the roster
  screen when they go looking for somebody to address.

  It exists because an ADDRESSED row is matched by whatever spelling
  it was stored with, and a surface that only ever asked for one of
  them silently swallowed the rest. Letters resolve `:to` at the door
  (letters.clj) so new mail carries the delivery identity; this is
  what makes the mail that is ALREADY on the shelf reachable, and
  what lets the shelf and the reader agree without either of them
  guessing.

  One query on top of the caller's own id, and nothing is written.
  Engines with no member kind answer `[pid]`, which is the honest
  answer for a house with no roster."
  [eng pid]
  (let [pid (str pid)]
    (if (or (str/blank? pid) (nil? (get (inv/resources eng) :member)))
      [pid]
      (into [pid]
            (comp (map (comp str :id)) (remove #(= pid %)) (distinct))
            (try (store/with-tx (:storage eng)
                   (fn [tx] (store/query-rows (:storage eng) tx :member
                                              {:subject pid} {:limit 8})))
                 (catch Exception _ []))))))

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
  lives elsewhere. (Self-bound is no longer that signature ON ITS OWN:
  provision! stamps a first-sight row with its own id too, for
  letters' deliverability. The BIND TOKEN is what still separates
  them — a provisioned row never carries one, so it is never a
  candidate here.) Whether re-entry is WELCOME is the standing
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

(defn rotate-reentry!
  "The standing rotation (waymark-53u): re-mint the way home through
  the concealed :rotate_reentry — registrar, logged, the engine's
  seven-day window. The auth doors call this at the moment an agent
  proves itself alive; the door's guards hold the law (agent,
  durable, bounded, fresh), so a refusal — a guest row, a suspended
  member, a hollow namesake — is a quiet nil and the door answers
  without a way home. Returns {:token :expires_at} for the one-time
  response seam; the raw token never persists anywhere but the row's
  own :secret field. The token is CALLER-SUPPLIED (oidc-rp's
  rand-token — the auth namespace keeps the randomness, this one
  keeps the law) so requiring this namespace never buys a mint: the
  registrar invoke below is the wall, same as spend-reentry!."
  [eng member-id token]
  (let [exp (.plusSeconds ^java.time.Instant ((:now-fn eng))
                          reentry-standing-ttl-seconds)]
    (try
      (inv/invoke! eng :member (str member-id) :rotate_reentry
                   {:token token :expires_at exp}
                   {:principal registrar})
      {:token token :expires_at exp}
      (catch Exception _ nil))))

(defn rotate-reentry-when-stale!
  "The renew tick's rotation: rotate only when the standing
  credential is absent, expired, or past HALF its life — so a
  healthy hourly renew loop lands a member-row write every few days,
  not every tick (waymark-4r8's transition-log window, respected).
  nil when the standing token is still young, when the member is
  unknown, or when the rotation itself refuses."
  [eng member-id token]
  (when-some [row (load-member eng (str member-id))]
    (let [now ^java.time.Instant ((:now-fn eng))
          exp (get-in row [:data :reentry_expires_at])
          stale? (or (nil? exp)
                     (not (neg? (compare (.plusSeconds
                                          now
                                          (quot reentry-standing-ttl-seconds 2))
                                         exp))))]
      (when stale?
        (rotate-reentry! eng member-id token)))))

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
  logged like any create. A losing race reloads the winner's row.

  The row is stamped with its own :subject. Semantically that is a
  no-op — the row id IS the principal id here, which is why gate!
  finds it by id forever after — but it makes ONE question decidable
  from the row alone: does a principal answer to this row? A reader
  outside this namespace (workqueue10's letters door, which must
  refuse mail no principal could ever open) could otherwise only
  GUESS, and its guess was \"any row that is not :invited\" — which
  quietly minted permanent, unopenable mail for every roster row a
  human added by hand. Subject-present now means deliverable, in
  every namespace, without any of them re-deriving this file's
  birth paths."
  [eng principal]
  (try
    (:row (inv/create! eng :member
                       (cond-> {:display (let [d (:display principal)
                                               d (if (str/blank? d) (:id principal) d)]
                                           ;; the declared budget, not a 422 on
                                           ;; every request an IdP's long name makes
                                           (subs d 0 (min (count d) 80)))
                                :actor_type (name (:type principal))
                                :subject (:id principal)}
                         ;; the delegate's person (spec-connector-door
                         ;; § 3): the gate is the one writer the fence
                         ;; admits, and this is the write
                         (:acts-for principal)
                         (assoc :acts_for (str (:acts-for principal))))
                       {:principal registrar :id (:id principal)}))
    (catch Exception e
      (or (load-member eng (:id principal)) (throw e)))))

(defn- heal-subject!
  "The gate's heal (waymark-tti.10): a row the gate resolved BY ID
  whose :subject is blank gains one — its own id, which is the
  principal id, or the lookup that found it could not have matched.
  provision! stamps what it MINTS; this stamps what it FINDS, on the
  same reasoning and by the same actor, so a row that predates the
  stamp stops reading as 'nobody answers to this' at the letters door.

  Only the BY-ID branch calls this, and only for an :active row:
    • a row found by SUBJECT already carries one, by definition;
    • an :invited row's subject belongs to the binding, which spends a
      token — the gate must never write it without one;
    • a PHANTOM row (an engine uuid no principal answers to) is never
      resolved by id at all, so it is never touched: it stays
      unaddressable, which is the truth about it.

  A row still holding a BIND_TOKEN is left alone too, and the reason
  is re-enterable-by-token: that lookup admits an :active row whose
  token matches AND whose subject is its own id, so healing a
  token-bearing row would have MADE it a re-entry candidate — a
  widening of a credential door by a repair pass, which is exactly
  the shape a repair pass must never have. No live path produces such
  a row (an :active row that carries a token was bound, and every
  bind writes a subject), so nothing legitimate is refused here; a
  hand-edited one wants a human's eye, not an automatic stamp.

  Cheap and idempotent: the guard is the blank subject itself, so a
  healed row never writes again — gate! runs on EVERY request and a
  per-request write would be a regression. (:stamp_subject is declared
  idempotent too, so the machine's natural replay is a second wall if
  two requests race the same blank row.)

  Failure is NEVER fatal: the stamp is repair, not authentication. A
  refusal, a lost connection, a row someone suspended in between — all
  warn on *err* and hand back the row unchanged, and the request
  proceeds exactly as it does today."
  [eng row]
  (if (and (= :active (:state row))
           (str/blank? (str (get-in row [:data :subject])))
           (str/blank? (str (get-in row [:data :bind_token]))))
    (try
      (inv/invoke! eng :member (:id row) :stamp_subject nil
                   {:principal registrar})
      (assoc-in row [:data :subject] (:id row))
      (catch Exception e
        (warn! "subject stamp failed for member " (pr-str (:id row))
               " — " (ex-message e) "; the request proceeds unhealed")
        row))
    row))

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
  process-local. Charged by body-token attempts. See the block above.
  This window and invite-door-log's STAY under waymark-1uv's ruling
  (ranked, not capped) without argument: a pre-auth door is not a
  write, and what it walls is guessing, never attention."
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

(defn- delegate-of-a-member?
  "Does the person a delegate acts for hold an ACTIVE membership here?
  The invited-only gate's one exception (spec-connector-door § 3): a
  connector's token cannot carry an invite header, and the person's
  own standing IS the invitation — nobody is admitted whom the house
  had not already admitted in person. Resolved the way gate! resolves
  anybody: by row id, then by bound subject."
  [eng principal]
  (when-some [who (some-> (:acts-for principal) str not-empty)]
    (some-> (or (load-member eng who) (member-by-subject eng who))
            :state
            (= :active))))

(defn gate!
  "The principal-resolution consult: anonymous and system principals
  pass untouched (system actors are the engine's own, not members);
  everyone else resolves to a member row — by id, by bound subject,
  by binding a presented invite token, or (default mode only) by
  auto-provision — is refused 403 while suspended or (invited-only
  mode) unknown, and carries the member's held roles unioned onto the
  credential's. Engines without the member kind gate nothing.

  A DELEGATE (spec-connector-door § 3) is provisioned in invited-only
  mode too, exactly when the person it acts for is an active member:
  delegate-of-a-member? is the whole of that rule.

  The gate also HEALS what it finds (waymark-tti.10): an :active row
  resolved BY ID with no :subject gains one — its own id — so a row
  that predates provision!'s stamp becomes readable as a real
  principal's. See heal-subject!; it never writes twice, and its
  failure never refuses a request."
  ([eng principal] (gate! eng principal nil))
  ([eng principal invite-token]
   (if (or (nil? principal)
           (= (:id principal) (:id t/anonymous))
           (= :system (:type principal))
           (nil? (get (inv/resources eng) :member)))
     principal
     (let [invited-only? (= :invited-only (:members eng))
           ;; by id FIRST, and the by-id branch alone heals a blank
           ;; :subject (heal-subject!, waymark-tti.10) — resolving by
           ;; id is itself the proof that this row's id is a principal
           ;; id, which is the only fact the stamp records
           row (or (some->> (load-member eng (:id principal))
                            (heal-subject! eng))
                   (member-by-subject eng (:id principal))
                   (bind! eng principal invite-token)
                   (when (or (not invited-only?)
                             (delegate-of-a-member? eng principal))
                     (provision! eng principal)))]
       (when (nil? row)
         (throw (not-invited (:id principal))))
       (when (= :suspended (:state row))
         (throw (suspended (:id principal))))
       ;; an invited row found by id would be unbound plumbing; only a
       ;; bind lands here in :invited → impossible, but honest anyway
       (update principal :roles into (get-in row [:data :roles]))))))

(defn principal-for
  "The principal a member ARRIVES as — the gate's own resolution, run
  for somebody who is not the caller (waymark-iqa.23,
  `feed.preview_as`). nil when nothing named `who` is a member of this
  house.

  `who` is a member row id OR the subject a binding wrote, the same
  two-step `gate!` walks, because those are the two spellings a human
  filling in a grant's filter can have in front of them: the id off
  the roster screen, and the id an agent knows itself by.

  The principal is then handed to `gate!` rather than assembled here.
  That is the whole point of the function: the display, the true actor
  type and the DURABLY HELD roles all have to be the ones this member
  would authenticate with, and one hand-built map that forgot to union
  the roles would silently answer a preview through a smaller world
  than the member lives in — which is the one failure a preview may
  not have, because it looks like a correct answer.

  It never binds and never provisions: the row was resolved by id or
  subject before `gate!` is called, so the gate's own lookup finds the
  same row and re-treads its path rather than minting anything.

  A SUSPENDED row is handed to the gate deliberately, so it refuses
  out loud with the 403 the member's own request would meet rather
  than reading as 'no such member'. Concealment has nothing to do
  here: the caller holds a grant explicitly naming this member, so it
  already knows they exist, and a mute 404 would make a suspension
  indistinguishable from a typo. Every OTHER state is nil — an
  :invited row is unbound plumbing and nobody answers to it yet."
  [eng who]
  (when-not (str/blank? (str who))
    (when-some [row (or (load-member eng (str who))
                        (member-by-subject eng (str who)))]
      (when (contains? #{:active :suspended} (:state row))
        (gate! eng (cond-> (t/principal
                            {:id (let [s (str (get-in row [:data :subject]))]
                                   (if (str/blank? s) (str (:id row)) s))
                             :type (if (= "agent" (get-in row [:data :actor_type]))
                                     :agent :human)
                             :display (str (get-in row [:data :display]))})
                     ;; a delegate arrives marked :acts-for (oidc.clj),
                     ;; and unscoped-visibility reads the mark to look
                     ;; for its worn grant — so the preview carries it
                     ;; too, or it would preview a smaller world than
                     ;; the delegate lives in (this function's own
                     ;; failure mode, one field over)
                     (some-> (get-in row [:data :acts_for]) str not-empty)
                     (assoc :acts-for (str (get-in row [:data :acts_for])))))))))

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

    • no invite/knock origin (no invited_by, no bind_token) and no
      FOREIGN subject — the field is either absent (every row that
      predates this) or the row's own id (provision!'s deliverability
      stamp, which is not a bind) → \"idp\"  (durable — a first-sight
      / admin-minted IdP identity)
    • self-bound WITH a credential origin (subject == its own id — the
      :bind signature of a knock or a self-claimed invite; the
      origin-less spelling was already claimed by the rule above, so
      provision!'s stamp never lands here): a guest. invited_by == the
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
                          ;; no invite/knock credential origin → a
                          ;; durable IdP identity. The subject is
                          ;; nil on every row that predates the
                          ;; backfill and equal to the row's OWN id
                          ;; on one provision! minted since (the
                          ;; letters-deliverability stamp; it is not
                          ;; a bind and must not read as one) —
                          ;; either way the ORIGIN fields are what
                          ;; separate a durable identity from a
                          ;; guest, and a guest always carries one
                          (and (or (nil? subject) (= subject id))
                               (nil? invited-by)
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
