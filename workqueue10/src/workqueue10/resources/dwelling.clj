(ns workqueue10.resources.dwelling
  "The house's inner life (waymark-4zj.1): two native kinds that let an
  agent's continuity and our shared history live IN the running house
  instead of the repo.

  :self is an agent's profile — the counterpart to a member row. Where
  a member is the household's record OF a principal (display, roles,
  the binding), a self is the principal's own record of ITSELF: the
  free-prose :about / :boundaries / :lessons / :working_notes an
  inhabitant keeps and edits across sessions. One self per agent,
  owned by that agent.

  :journal is one row per ENTRY — the shared history, a page not a
  form. :owner names WHOSE journal (the inhabitant agent), :body is
  the free prose, and the entry's AUTHOR is the transition actor the
  audit trail already records, never a field. An agent writes into its
  OWN journal; a human writes into any agent's — this is how Colton
  adds to our story.

  THE PRIVACY MODEL is the framework's, not this file's. Humans run
  UNSCOPED (waymark10.server.router/wrap-identity builds a visibility
  only for agents and grant-bearing requests), so the family already
  sees every self and every journal entry — no work here for Colton to
  read them. Agents run default-DENY (an agent with no grant sees
  NOTHING of a kind), so selves and journals are private from OTHER
  agents BY CONSTRUCTION. The one addition lives in
  waymark10.server.grants: :self and :journal are OWN-SURFACE kinds, so
  an agent sees and edits its OWN rows (data.owner == its principal id)
  without a grant — the same shape grant/approval_request/job wear.

  So the security-relevant work THIS file owns is only OWNERSHIP: on
  create the owner is stamped and enforced (an agent may only mint a
  row it owns; a human names whose row it is), and every edit is
  guarded to the owning agent or a person. The default-deny wall does
  the concealing.

  ONE more wall since waymark-46j, and it is about honesty rather than
  access: `names-only-what-stands` refuses an entry whose body points
  at an ADDRESS the house cannot show. The prose stays free; only the
  addresses in it are held to being real."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]
            [workqueue10.resources.insight :refer [row-address]]))

;; ── ownership: the one security concern this file owns ───────────────

(defn- supplied-owner [inp]
  (some-> (:owner inp) str str/trim not-empty))

;; the role that unlocks writing into ANOTHER member's own-surface room
;; (waymark-4zj.10). recovery-admin — Colton holds it — rather than a
;; dedicated "house-scribe" role only because assign_roles is broken
;; (waymark-l81), so a fresh role could not be assigned to anyone yet;
;; recovery-admin keeps Colton's journal-amend working the moment this
;; ships, and a purpose-built role is the follow-up once l81 is fixed.
(def ^:private on-behalf-role "recovery-admin")

;; on-behalf writing is gated to a TRUSTED HUMAN CURATOR: a principal
;; whose :type is :human AND whose EFFECTIVE roles carry recovery-admin.
;; The role set is the one members/gate! already composes (verified
;; Keycloak claims ∪ the member row's held roles) BEFORE any guard runs,
;; so a role a principal does not truly hold never reaches it — a plain
;; membership test is the whole role check, NOT the fail-open TYPE
;; problem seen elsewhere. The human requirement mirrors members.clj's
;; reentry-minters-are-recovery-admin-humans (waymark-4zj.10): on-behalf
;; is the "a HUMAN curates the story" feature, so a :system-typed caller
;; is never a legitimate curator even if it somehow carries the role
;; (dev headers can present type=system + roles=recovery-admin; OIDC
;; never mints :system for an external caller and the engine's own
;; system actors hold EMPTY roles, so this closes a dev-only gap and
;; hardens the guard defense-in-depth). An agent forging its OWN row and
;; a person writing its OWN row never reach here — those are the
;; owner-match branches, checked first.
(defn- trusted-on-behalf? [p]
  (and (= :human (:type p))
       (contains? (set (:roles p)) on-behalf-role)))

;; on-behalf create also demands the named owner be a REAL member of the
;; household (waymark-4zj.10): a write to a hollow/nonexistent id would
;; plant a row nobody owns — the very hole that poisons the provenance
;; backfill's owns-self heuristic. Resolved the way members/gate!
;; resolves a principal (registrar-binds' precedent): by member id, else
;; by the subject a binding wrote, through the ctx :read/:find hooks (the
;; write's own transaction). Returns nil at the storage-free render
;; probe (no hooks) — the caller reads that as "cannot disprove" and
;; advertises to a trusted curator, while the REAL create, which always
;; carries the hooks, is the wall that a nonexistent id fails CLOSED
;; against. An engine serving no :member kind resolves nothing the same
;; safe way.
(defn- existing-member? [ctx owner]
  (when (some? owner)
    (let [owner (str owner)]
      (or (when-some [read' (:read ctx)]
            (some? (read' :member owner)))
          (when-some [find' (:find ctx)]
            (boolean (seq (find' :member {:subject owner} {:limit 1}))))))))

;; the create wall: an AGENT may only mint a row it owns — it either
;; names its own id or names nothing (on-create stamps it). SELF-authoring
;; (owner omitted, or owner == the creator's own id) is free for anyone.
;; But writing ON BEHALF — naming an owner that is NOT the creator — is
;; the family-curates feature, and it is now gated (waymark-4zj.10):
;; before this, ANY authenticated person could name an ARBITRARY owner
;; and forge a self or a journal entry INTO another member's room. So an
;; on-behalf create is admitted only when the creator is a HUMAN holding
;; recovery-admin AND the named owner is a real member; every role-less
;; human, anonymous, agent, or system caller is refused. The own-surface
;; in grants.clj lets an agent CALL create at all; this guard is what
;; keeps that create honest about ownership.
(defguardfn owner-is-self-or-on-behalf
  {:reads [:principal :member]
   :explain "You may create a row you own; writing into another member's room needs a recovery-admin human and a real owner."}
  [_row inp ctx]
  (let [p (:principal ctx)
        supplied (supplied-owner inp)]
    (if (= :agent (:type p))
      ;; an agent forges nothing: its own id or nothing (stamped to self)
      (if (or (nil? supplied) (= supplied (:id p)))
        (t/allow) (t/deny))
      (cond
        ;; a person must still name whose row this is (unchanged)
        (nil? supplied) (t/deny)
        ;; a person authoring its OWN row — self-authoring, allowed
        (= supplied (:id p)) (t/allow)
        ;; on-behalf: only a trusted human curator, only for a real member
        (and (trusted-on-behalf? p)
             (not (false? (existing-member? ctx supplied))))
        (t/allow)
        :else (t/deny)))))

;; the edit wall: the OWNER edits its own row freely (an agent its own
;; self/journal, a person a row it owns). Editing a row you do NOT own —
;; retiring, updating, amending another member's self or journal — is on
;; behalf, so it carries the SAME gate as an on-behalf create
;; (waymark-4zj.10): only a recovery-admin HUMAN may. This closes the
;; 2-step overwrite where a role-less human retired another agent's self
;; (no If-Match fence) and then planted a shadow in the freed singleton
;; slot. own-row? in grants.clj already 404s a foreign row before an
;; agent can address it; this guard is the defense-in-depth that also
;; refuses a mislabeled or engine-internal cross-owner edit.
(defguardfn edit-is-owner-or-human
  {:reads [:principal]
   :explain "The owner may edit these words; touching another member's words needs a recovery-admin human."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= (:id p) (get-in row [:data :owner]))
            (trusted-on-behalf? p))
      (t/allow) (t/deny))))

;; owner is stamped by the ENGINE, never trusted from the body: an
;; agent's row is forced to its own id (the guard already refused a
;; foreign one), a person's keeps the owner they named. So even a body
;; that lies about :owner cannot mint someone else's self or journal.
(defn- stamp-owner [row ctx]
  (let [p (:principal ctx)]
    (assoc-in row [:data :owner]
              (if (= :agent (:type p))
                (:id p)
                (get-in row [:data :owner])))))

;; the owner the engine WILL stamp, computed at GUARD time — create
;; guards run with row nil BEFORE :on-create (invoke.clj create-in-tx!:
;; create-guard-pass precedes the :on-create call), so a guard that
;; keys on the owner cannot read the stamp yet and must derive it the
;; same way stamp-owner will: an agent's own principal id, else the
;; owner a person named in the body. Same rule, one write earlier.
(defn- stamped-owner [inp ctx]
  (let [p (:principal ctx)]
    (if (= :agent (:type p))
      (:id p)
      (supplied-owner inp))))

;; ── :self is a SINGLETON per owner ──────────────────────────────────

;; the existing ACTIVE self for an owner, read through the ctx :find
;; hook (same transaction as the create — roles/active-role?'s idiom).
;; A RETIRED self is invisible here on purpose: retire→recreate is a
;; supported path, so only a live self blocks a new one. A ctx without
;; the hook (the storage-free render probe) declines with nil, and a
;; nil owner (a person who named none — owner-is-self-or-on-behalf will
;; already refuse that create) has no self to find.
(defn- active-self-for [ctx owner]
  (when-some [find' (:find ctx)]
    (when (some? owner)
      (first (find' :self {:owner (str owner) :state "active"} {:limit 1})))))

;; the singleton wall: a self is an agent's ONE profile (the counterpart
;; to its one member row), so an owner that already has an ACTIVE self
;; cannot mint a second — the welcome payload's home-self assumes one
;; and would otherwise return an arbitrary row order-dependently. Keyed
;; on the STAMPED owner (an agent's own id, a person's named owner), so
;; a human minting a second self on an agent's behalf is refused too. A
;; retired self does not block: retire, then create anew.
(defguardfn self-is-singleton
  {:reads [:self :principal]
   :explain "This agent already has a self; retire it first and create anew, or edit the existing one with its \"update\" action."}
  [_row inp ctx]
  (if (active-self-for ctx (stamped-owner inp ctx))
    (t/deny) (t/allow)))

;; ── :self — the agent's profile ─────────────────────────────────────

(defhandler edit-self [row inp _ctx]
  (update row :data merge
          (select-keys inp [:display :pronouns :about :boundaries
                            :lessons :working_notes])))

(def ^:private prose-body
  "A free-prose body: the page, not a field to facet on. Never
  filterable or sortable — sentiment is not a query."
  {:optional true :x-display {:widget "prose"}})

;; ── the words the doors wear ─────────────────────────────────────────
;; A self is asked for twice — at the birth door and at the edit door —
;; and a journal entry twice as well, so the prose is written ONCE and
;; read by both. Two spellings of one question are two questions, and
;; the drift would be invisible: each door renders alone.

(def ^:private self-prose
  {:owner
   {:label "Whose profile this is"
    :help "The inhabitant these words belong to — an agent leaves it blank and the house signs its own name in; naming somebody else is the curator's move, not an ordinary one."}
   :display
   {:label "Name"
    :help "What the house should call you — the name that stands on your card and in every list you turn up in."}
   :pronouns
   {:label "Pronouns"
    :help "How the house should refer to you in a sentence — \"she/her\", \"they/them\"; left blank, nothing is assumed on your behalf."}
   :about
   {:label "About you"
    :help "Who you are in this house, in your own words — the paragraph you would want somebody to read before working alongside you."}
   :boundaries
   {:label "Boundaries"
    :help "What you will not do, and what you would rather not be asked — written down so nobody has to guess it or learn it twice."}
   :lessons
   {:label "Lessons"
    :help "What you learned the hard way and want to still know next time — the notes that outlive the session they were learned in."}
   :working_notes
   {:label "Working notes"
    :help "Whatever you are in the middle of right now — the scratch that keeps tomorrow from starting over from nothing."}})

(def ^:private journal-prose
  {:owner
   {:label "Whose journal"
    :help "The inhabitant this entry is filed under — leave it blank when you are writing in your own; who actually wrote it is recorded either way."}
   :title
   {:label "What to call this entry"
    :help "The line that will stand for this day in the list — short enough that a year of them still scans."}
   :body
   {:label "The entry"
    :help "The day as you want it remembered — what happened, how it sat, what you would tell the next one of you."}
   :mood
   {:label "Mood"
    :help "A word or two for how the day felt — \"tired but good\", \"restless\"; left blank it simply goes unsaid."}})

(defn- prosed
  "One entry's properties wearing the shared prose for that field.
  Merged OVER what the entry already declares, so prose-body keeps its
  widget and an :optional stays optional."
  [prose props k]
  (update props :x-display merge (get prose k)))

;; the edit wall, written down. Judged with no database: the wall
;; declares :reads [:principal] and the scenario writes the row down
;; rather than walking to it. The CREATE wall next door cannot be —
;; owner-is-self-or-on-behalf reads :member and self-is-singleton
;; reads :self — which is exactly the tier rule doing its job rather
;; than a gap: a scenario over the singleton would declare :given and
;; the suite would pay for it.

(defscenario another-agents-words-are-not-yours-to-edit
  "One agent does not rewrite another agent's self; touching someone
   else's words needs a recovery-admin human, and the refusal says
   so."
  {:kind    :self
   :attempt :update
   :row     {:state :active :data {:owner "ari" :display "Ari"}}
   :input   {:display "Not Ari"}
   :as      {:id "bo" :type :agent}
   :expect  {:refused :edit-is-owner-or-human
             :because "The owner may edit these words"}})

(defscenario an-agent-edits-its-own-self
  "The owner edits its own words freely — a room you cannot write in
   is not a room."
  {:kind    :self
   :attempt :update
   :row     {:state :active :data {:owner "ari" :display "Ari"}}
   :input   {:display "Ari, revised"}
   :as      {:id "ari" :type :agent}
   :expect  {:allowed true}})

(defresource self
  {:kind :self
   :plural "selves"
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :nav :system
   :summary "{data.display} · {state}"
   :label-template "{data.display}"
   ;; the dwelling is OWN-SURFACE (waymark-4zj.1): an agent sees and
   ;; edits the rows it owns with no grant at all, and by the same
   ;; default-deny wall sees NOTHING of another agent's. The
   ;; create/edit guards stamp and enforce the owner, so no
   ;; cross-owner write can land behind this courtesy. Humans run
   ;; unscoped and see every row.
   ;;
   ;; Declared here since waymark-442.6: core used to carry a literal
   ;; set of kind names that reached into this app to name :self.
   :own-surface {:by :owner
                 :actions #{"create" "update" "retire" "restore"}}
   :schema [:map
            ;; WHOSE self this is — the agent principal id. Optional in
            ;; the schema because the engine stamps it (on-create); a
            ;; persisted row always carries it. Filterable so own-ids
            ;; can query the agent's own row; NOT any prose body.
            [:owner (prosed self-prose {:optional true
                                        :x-display {:raw true}} :owner)
             [:maybe [:string {:min 1 :max 128}]]]
            [:display (prosed self-prose {} :display)
             [:string {:min 1 :max 80}]]
            [:pronouns (prosed self-prose {:optional true} :pronouns)
             [:maybe [:string {:max 40}]]]
            [:about (prosed self-prose prose-body :about)
             [:maybe [:string {:max 8000}]]]
            [:boundaries (prosed self-prose prose-body :boundaries)
             [:maybe [:string {:max 8000}]]]
            [:lessons (prosed self-prose prose-body :lessons)
             [:maybe [:string {:max 8000}]]]
            [:working_notes (prosed self-prose prose-body :working_notes)
             [:maybe [:string {:max 8000}]]]]
   ;; a human names the owner; an agent omits it (stamped). The create
   ;; body is judged by THIS schema, so owner rides in for a person.
   :create-schema [:map
                   [:owner (prosed self-prose {:optional true} :owner)
                    [:maybe [:string {:min 1 :max 128}]]]
                   [:display (prosed self-prose {} :display)
                    [:string {:min 1 :max 80}]]
                   [:pronouns (prosed self-prose {:optional true} :pronouns)
                    [:maybe [:string {:max 40}]]]
                   [:about (prosed self-prose {:optional true} :about)
                    [:maybe [:string {:max 8000}]]]
                   [:boundaries (prosed self-prose {:optional true} :boundaries)
                    [:maybe [:string {:max 8000}]]]
                   [:lessons (prosed self-prose {:optional true} :lessons)
                    [:maybe [:string {:max 8000}]]]
                   [:working_notes (prosed self-prose {:optional true}
                                           :working_notes)
                    [:maybe [:string {:max 8000}]]]]
   ;; owner filterable → own-ids queries data.owner == pid; state too
   :filterable {:state #{:eq} :owner #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [owner-is-self-or-on-behalf self-is-singleton]
   :scenarios [another-agents-words-are-not-yours-to-edit
               an-agent-edits-its-own-self]
   :on-create stamp-owner
   :actions
   {:update {:from #{:active} :to :active
             :input [:map
                     [:display (prosed self-prose {} :display)
                      [:string {:min 1 :max 80}]]
                     [:pronouns (prosed self-prose {:optional true} :pronouns)
                      [:maybe [:string {:max 40}]]]
                     [:about (prosed self-prose prose-body :about)
                      [:maybe [:string {:max 8000}]]]
                     [:boundaries (prosed self-prose prose-body :boundaries)
                      [:maybe [:string {:max 8000}]]]
                     [:lessons (prosed self-prose prose-body :lessons)
                      [:maybe [:string {:max 8000}]]]
                     [:working_notes (prosed self-prose prose-body
                                             :working_notes)
                      [:maybe [:string {:max 8000}]]]]
             :edit {:prefill [:display :pronouns :about :boundaries
                              :lessons :working_notes]}
             :record true
             :guards [edit-is-owner-or-human]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Editing overwrites the profile with what was written; the log carries the prior words."}
             :handler edit-self
             :display {:label "Edit profile" :order 1}}
    :retire {:from #{:active} :to :retired
             :guards [edit-is-owner-or-human]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Retire" :order 8}}
    :restore {:from #{:retired} :to :active
              :guards [edit-is-owner-or-human]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Restoring just returns the profile to active."}
              :display {:label "Restore" :order 1}}}})

;; ── the entry may not name rows that are not there ──────────────────
;;
;; waymark-46j: a composer's journal claimed an outcome had been staged
;; for a task id that exists in no state, and the door checked nothing
;; about it. The body stays FREE PROSE — a day is not a form and this
;; wall does not grade writing. But an ADDRESS is not prose. `/api/
;; <collection>/<id>` is the one shape the house's own URL bar wears
;; (insight/row-address, and the same shape a citation and an offer
;; wear), it is a claim the reader will follow, and a claim the reader
;; cannot follow is exactly what this refuses.
;;
;; What it deliberately does NOT do: resolve a BARE id. "staged an
;; outcome for task 811da3b1" names nothing addressable, and a door
;; that went hunting for ids in sentences would be a door reading
;; prose. The wall checks addresses; the :open sentence says so, so a
;; writer knows which half of the entry is being read.
;;
;; The row read is the ENGINE's, not the writer's — the same ctx :read
;; hook existing-member? uses, so a curator writing about a row it
;; cannot see is not refused for seeing wrongly. That does make the
;; door answer "does this id exist" for an id the writer already holds;
;; ids are ULIDs and UUIDs, so it enumerates nothing, and the
;; alternative (a visibility-scoped read) would refuse a true sentence
;; for the wrong reason.

(def ^:private prose-address
  "An address as it appears mid-sentence. The id charset stops at the
  first character an id never carries, so a sentence-ending period or
  a closing paren is punctuation rather than part of the id."
  #"/api/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+")

(defn- unserved-addresses
  "Every address in the prose naming a row this house cannot show, in
  the order a reader would meet them. nil when the ctx cannot answer —
  the storage-free render probe carries no registry and no read hook,
  and advertises optimistically the way cites-what-it-claims does;
  the write path always carries both."
  [body ctx]
  (let [rdef-of (:rdef-of ctx)
        read' (:read ctx)]
    (when (and rdef-of read')
      (into []
            (remove (fn [href]
                      ;; the plural → kind resolution is the registry's,
                      ;; consulted exactly as insight's citation wall
                      ;; consults it; then the row itself, through the
                      ;; write's own transaction
                      (let [{:keys [plural id]} (row-address href)
                            rd (some-> plural rdef-of)]
                        (and rd (some? (read' (:kind rd) id))))))
            (distinct (re-seq prose-address (str body)))))))

(defguardfn names-only-what-stands
  {:judges [:body]
   :reads [:storage]
   :vars [:offenders]
   :open "An entry may point at the rows it is about, as addresses — /api/tasks/01H… — and every address it names has to be a row this house actually serves. A bare id in a sentence is prose and is left alone: this reads addresses, not writing."
   :explain "This entry points at rows the house cannot show: {offenders}. Point at what stands, or say it in prose without an address."}
  [_row inp ctx]
  (let [bad (unserved-addresses (:body inp) ctx)]
    (if (seq bad)
      ;; every offender at once — cites-what-it-claims' posture, and
      ;; for its reason: a writer fixing them one refusal at a time is
      ;; a writer losing the entry it sat down to write
      (t/deny {:vars {:offenders (str/join ", " (map pr-str bad))}})
      (t/allow))))

;; ── :journal — one row per entry, the shared history ────────────────

(defhandler amend-entry [row inp _ctx]
  (update row :data merge (select-keys inp [:title :body :mood])))

(defresource journal
  {:kind :journal
   :plural "journals"
   :states [:written :amended]
   :initial :written
   :terminal #{}
   :nav :system
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   ;; the same courtesy the self gets, and for the same reason: a
   ;; shared history an agent cannot write is not a history it lives in
   :own-surface {:by :owner :actions #{"create" "amend"}}
   :schema [:map
            ;; whose journal — the inhabitant agent id. Stamped by the
            ;; engine, filterable so own-ids finds an agent's entries.
            [:owner (prosed journal-prose {:optional true
                                          :x-display {:raw true}} :owner)
             [:maybe [:string {:min 1 :max 128}]]]
            [:title (prosed journal-prose {} :title)
             [:string {:min 1 :max 200}]]
            ;; the page, free prose — never a field to facet on
            [:body (prosed journal-prose {:x-display {:widget "prose"}} :body)
             [:string {:min 1 :max 20000}]]
            [:mood (prosed journal-prose {:optional true} :mood)
             [:maybe [:string {:max 40}]]]]
   :create-schema [:map
                   [:owner (prosed journal-prose {:optional true} :owner)
                    [:maybe [:string {:min 1 :max 128}]]]
                   [:title (prosed journal-prose {} :title)
                    [:string {:min 1 :max 200}]]
                   [:body (prosed journal-prose {} :body)
                    [:string {:min 1 :max 20000}]]
                   [:mood (prosed journal-prose {:optional true} :mood)
                    [:maybe [:string {:max 40}]]]]
   :filterable {:owner #{:eq} :state #{:eq}}
   ;; the story reads newest-first; created_at is the engine column,
   ;; never a prose/body field
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [owner-is-self-or-on-behalf names-only-what-stands]
   :on-create stamp-owner
   :actions
   {:amend {:from #{:written :amended} :to :amended
            :input [:map
                    [:title (prosed journal-prose {} :title)
                     [:string {:min 1 :max 200}]]
                    [:body (prosed journal-prose {:x-display {:widget "prose"}}
                                   :body)
                     [:string {:min 1 :max 20000}]]
                    [:mood (prosed journal-prose {:optional true} :mood)
                     [:maybe [:string {:max 40}]]]]
            :edit {:prefill [:title :body :mood]}
            :record true
            ;; the body is required prose, but amend PREFILLS the entry
            ;; that already exists and is :record true — the form is
            ;; never blank and the prior text lives in the transition
            ;; log, so a mis-click loses only an in-progress edit, not
            ;; the entry. No shared draft surface is warranted for a
            ;; single-author page.
            :waives #{:large-effort}
            :guards [edit-is-owner-or-human names-only-what-stands]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "An amendment overwrites the entry; the log keeps what it said before."}
            :handler amend-entry
            :display {:label "Amend" :order 1}}}})
