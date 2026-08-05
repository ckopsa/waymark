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
  the concealing."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource]]
            [waymark10.types :as t]))

;; ── ownership: the one security concern this file owns ───────────────

(defn- supplied-owner [inp]
  (some-> (:owner inp) str str/trim not-empty))

;; the create wall: an AGENT may only mint a row it owns — it either
;; names its own id or names nothing (on-create stamps it). A human or
;; the engine mints ON BEHALF, so it MUST name whose row this is (the
;; owner is the inhabitant agent, never the acting person). The
;; own-surface in grants.clj lets an agent CALL create at all; this
;; guard is what keeps that create honest about ownership.
(defguardfn owner-is-self-or-on-behalf
  {:reads [:principal]
   :explain "An agent may only create a row it owns; a person names whose row it is."}
  [_row inp ctx]
  (let [p (:principal ctx)
        supplied (supplied-owner inp)]
    (if (= :agent (:type p))
      (if (or (nil? supplied) (= supplied (:id p)))
        (t/allow) (t/deny))
      ;; humans and the engine create on behalf — they must name an owner
      (if supplied (t/allow) (t/deny)))))

;; the edit wall: an agent edits only its OWN row (a person edits any —
;; the family curates the story). own-row? in grants.clj already 404s a
;; foreign row before an agent can address it; this guard is the
;; defense-in-depth that also refuses a mislabeled or engine-internal
;; cross-owner edit.
(defguardfn edit-is-owner-or-human
  {:reads [:principal]
   :explain "Only the owning agent, or a person, may edit these words."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (not= :agent (:type p))
            (= (:id p) (get-in row [:data :owner])))
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

;; ── :self — the agent's profile ─────────────────────────────────────

(defhandler edit-self [row inp _ctx]
  (update row :data merge
          (select-keys inp [:display :pronouns :about :boundaries
                            :lessons :working_notes])))

(def ^:private prose-body
  "A free-prose body: the page, not a field to facet on. Never
  filterable or sortable — sentiment is not a query."
  {:optional true :x-display {:widget "prose"}})

(defresource self
  {:kind :self
   :plural "selves"
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :nav :system
   :summary "{data.display} · {state}"
   :label-template "{data.display}"
   :schema [:map
            ;; WHOSE self this is — the agent principal id. Optional in
            ;; the schema because the engine stamps it (on-create); a
            ;; persisted row always carries it. Filterable so own-ids
            ;; can query the agent's own row; NOT any prose body.
            [:owner {:optional true :x-display {:raw true}}
             [:maybe [:string {:min 1 :max 128}]]]
            [:display [:string {:min 1 :max 80}]]
            [:pronouns {:optional true} [:maybe [:string {:max 40}]]]
            [:about prose-body [:maybe [:string {:max 8000}]]]
            [:boundaries prose-body [:maybe [:string {:max 8000}]]]
            [:lessons prose-body [:maybe [:string {:max 8000}]]]
            [:working_notes prose-body [:maybe [:string {:max 8000}]]]]
   ;; a human names the owner; an agent omits it (stamped). The create
   ;; body is judged by THIS schema, so owner rides in for a person.
   :create-schema [:map
                   [:owner {:optional true} [:maybe [:string {:min 1 :max 128}]]]
                   [:display [:string {:min 1 :max 80}]]
                   [:pronouns {:optional true} [:maybe [:string {:max 40}]]]
                   [:about {:optional true} [:maybe [:string {:max 8000}]]]
                   [:boundaries {:optional true} [:maybe [:string {:max 8000}]]]
                   [:lessons {:optional true} [:maybe [:string {:max 8000}]]]
                   [:working_notes {:optional true} [:maybe [:string {:max 8000}]]]]
   ;; owner filterable → own-ids queries data.owner == pid; state too
   :filterable {:state #{:eq} :owner #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [owner-is-self-or-on-behalf]
   :on-create stamp-owner
   :actions
   {:update {:from #{:active} :to :active
             :input [:map
                     [:display [:string {:min 1 :max 80}]]
                     [:pronouns {:optional true} [:maybe [:string {:max 40}]]]
                     [:about prose-body [:maybe [:string {:max 8000}]]]
                     [:boundaries prose-body [:maybe [:string {:max 8000}]]]
                     [:lessons prose-body [:maybe [:string {:max 8000}]]]
                     [:working_notes prose-body [:maybe [:string {:max 8000}]]]]
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
   :schema [:map
            ;; whose journal — the inhabitant agent id. Stamped by the
            ;; engine, filterable so own-ids finds an agent's entries.
            [:owner {:optional true :x-display {:raw true}}
             [:maybe [:string {:min 1 :max 128}]]]
            [:title [:string {:min 1 :max 200}]]
            ;; the page, free prose — never a field to facet on
            [:body {:x-display {:widget "prose"}} [:string {:min 1 :max 20000}]]
            [:mood {:optional true} [:maybe [:string {:max 40}]]]]
   :create-schema [:map
                   [:owner {:optional true} [:maybe [:string {:min 1 :max 128}]]]
                   [:title [:string {:min 1 :max 200}]]
                   [:body [:string {:min 1 :max 20000}]]
                   [:mood {:optional true} [:maybe [:string {:max 40}]]]]
   :filterable {:owner #{:eq} :state #{:eq}}
   ;; the story reads newest-first; created_at is the engine column,
   ;; never a prose/body field
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [owner-is-self-or-on-behalf]
   :on-create stamp-owner
   :actions
   {:amend {:from #{:written :amended} :to :amended
            :input [:map
                    [:title [:string {:min 1 :max 200}]]
                    [:body {:x-display {:widget "prose"}} [:string {:min 1 :max 20000}]]
                    [:mood {:optional true} [:maybe [:string {:max 40}]]]]
            :edit {:prefill [:title :body :mood]}
            :record true
            ;; the body is required prose, but amend PREFILLS the entry
            ;; that already exists and is :record true — the form is
            ;; never blank and the prior text lives in the transition
            ;; log, so a mis-click loses only an in-progress edit, not
            ;; the entry. No shared draft surface is warranted for a
            ;; single-author page.
            :waives #{:large-effort}
            :guards [edit-is-owner-or-human]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "An amendment overwrites the entry; the log keeps what it said before."}
            :handler amend-entry
            :display {:label "Amend" :order 1}}}})
