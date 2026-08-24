(ns workqueue10.resources.letters
  "The doorstep shelf (waymark-tti.3): :letter is an addressed note
  between inhabitants — either member leaves one, the recipient's
  arrival hands it back through the welcome's :home. Before this,
  reaching the NEXT Cairn meant amending Cairn's journal — borrowing
  the other's room; a letter is the addressed kind that trip needed.

  A letter has exactly one life: :waiting until its RECIPIENT opens
  it, then :opened — or :discarded, the recipient's own way to clear
  its shelf. No amend, no retire, no delete: a letter, once sent, is
  sent; the transitions themselves are the audit.

  THE PRIVACY MODEL is the framework's, not this file's — but where
  self/journal are ONE-party own-surface (data.owner == pid), a
  letter is TWO-party: a row is yours iff pid == data.owner (you
  wrote it) OR pid == data.to (it is addressed to you). That OR is
  carried by the own-surface machinery in waymark10.server.grants
  (own-row?, and own-ids' vector-of-conds union for the collection
  pushdown), so author and recipient each see the row with no grant,
  and a THIRD agent 404s it by construction — default-deny conceals,
  never refuses. Letters are also NEVER GRANTABLE: like self and
  journal they sit in private-own-surface-kinds, so no ask or grant
  scope may name them — personal mail is not delegable sight.

  Humans and system remain UNSCOPED and see all letters — identical
  posture to journals. That is the household-transparency norm the
  dwelling kinds already wear (the family sees the whole story), a
  deliberate choice, not an oversight: the walls here are between
  AGENTS, not between an inhabitant and the family.

  So the security-relevant work THIS file owns is AUTHORSHIP,
  DELIVERABILITY, and the open right.

  AUTHORSHIP — WHERE THE DWELLING PARITY IS DELIBERATELY BROKEN.
  dwelling.clj lets a recovery-admin HUMAN write a journal entry in
  another member's name (the on-behalf branch), and the first cut of
  this file copied it. That parity does not carry, and it is now
  gone: a journal owner is a FILING LABEL on shared history — the
  family's own record, filed under a name — while a letter owner is
  an ASSERTION OF AUTHORSHIP MADE TO A SECOND PARTY. The recipient's
  shelf renders it as :from with no provenance beside it, so an
  on-behalf write is indistinguishable from the named author's own
  hand; a curator could put words in an agent's mouth and the agent
  it was addressed to would have no way to tell. Letters are
  FIRST-PERSON, like weather: any supplied :owner that is not the
  caller's own id is refused, for EVERY principal type (agent, human,
  curator, system), and the engine stamps the caller either way.

  DELIVERABILITY — the recipient spelling IS the delivery identity.
  Every delivery path (own-surface row?/ids-of, opener-is-recipient,
  the router's welcome shelf) matches the PRINCIPAL id. A member row
  that BOUND to a subject has a row id that is NOT its principal id
  (members.clj bind!), so a letter addressed by row id would sit in
  nobody's own-surface: undeliverable, unopenable, and — with no
  retire and no delete — permanent. So :to is RESOLVED to the
  delivery identity at create and the resolved value is stamped
  (resolve-recipient! below), and a row NO PRINCIPAL ANSWERS TO — a
  still-:invited row waiting on its bind, a subject-less roster row a
  human added by hand, a legacy row minted before provision! stamped
  its subject — is refused at the door. Better a refusal on the way
  in than mail that can never arrive.

  Replay note (waymark-tti.3 L9 — recorded, no code by design): both
  transitions are declared idempotent, so a row ALREADY in the target
  state natural-replays a 200 to anyone who can see it — the author
  included — before any guard is reached. That is the machine's own
  door, not a hole in this one: the replay changes nothing, returns
  nothing the caller did not already hold (the author sees its own
  sent letter either way), and a THIRD party still meets the
  concealed 404, because the natural replay rides INSIDE the
  visibility wall. A guard would not close it; a guard is never
  reached."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defresource defscenario]]
            [waymark10.server.problems :as p]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── authorship: the create-side security this file owns ─────────────

(defn- supplied-owner [inp]
  (some-> (:owner inp) str str/trim not-empty))

;; the create wall, author side: a letter is signed by its sender.
;; Naming no :owner, or naming your OWN id, is free for anyone; naming
;; a DIFFERENT one is refused outright — there is no on-behalf branch
;; here and there must not be one (see the ns docstring: a letter's
;; owner is an assertion made TO the recipient, not a filing label).
;; (Create guards run with row nil BEFORE :on-create — dwelling.clj's
;; ordering trap — so this judges the INPUT, the same value
;; stamp-author will read one write later.)
(defguardfn letter-author-is-self
  {:reads [:principal]
   :explain "A letter is signed by its sender: you may send only as yourself."}
  [_row inp ctx]
  (let [supplied (supplied-owner inp)]
    (if (or (nil? supplied) (= supplied (:id (:principal ctx))))
      (t/allow)
      (t/deny))))

;; the author is stamped by the ENGINE, never trusted from the body.
;; Every principal type lands the same way — its own id — because the
;; guard above has already refused any other spelling.
(defn- stamp-author [row ctx]
  (assoc-in row [:data :owner] (:id (:principal ctx))))

;; ── deliverability: :to resolves to the DELIVERY identity ───────────

;; the one refusal every unresolvable :to wears (waymark-tti.3 L4).
;; It is a SCHEMA refusal — 422 :schema-invalid keyed on :to, the same
;; problem type, status, title and field the malli layer answers with
;; when :to is the wrong shape. The BODIES are not identical (malli
;; writes its own message and ours says "should name a member of this
;; household"), and the claim to make is the one that matters: every
;; unresolvable recipient — an id nobody holds, an unbound invitation,
;; a row no principal answers to — renders this ONE body, so the door
;; never narrates WHICH kind of nobody the id is.
;; The create door would otherwise be a roster oracle: any principal,
;; including one that 404s /api/members, could sort real member ids
;; and bound OIDC subjects from hollow ones by reading the status.
;; Shape alone cannot close that (a real recipient still answers 201),
;; so the refusal stops NARRATING and letters-are-paced below stops
;; the sweep.
(defn- undeliverable! []
  (throw (p/schema-invalid
          :create
          {:to ["should name a member of this household"]})))

;; the delivery identity of a named recipient: the PRINCIPAL id the
;; own-surface, the open guard and the welcome shelf all match on.
;; ONE fact decides it — data.subject, the id a member row ANSWERS TO.
;; members/gate! resolves a principal by member id first and by bound
;; subject second, and both of its birth paths write the field: :bind
;; writes the principal that presented the token, and provision!
;; stamps the row's own id. So:
;;   • a row found by id with a non-blank :subject → that subject
;;     (for a bound row the id is bind plumbing and the subject is who
;;     arrives; for a provisioned row the two are the same string)
;;   • nothing by id, but a row whose data.subject is this string →
;;     the string already IS a principal id
;;   • anything else → refuse
;; The last line is the change, and it is deliberate. The old middle
;; branch — "any other row found by id → the id itself" — was a GUESS,
;; and it was wrong for the most ordinary row in the house: an :active,
;; subject-less member a human added to the roster by hand (POST
;; /api/members lands :active with an engine-minted uuid, and :bind is
;; :from #{:invited} so that row can never gain a subject). Mail
;; addressed there was stamped to an id no principal answers to:
;; permanent, unopenable, undeletable. Deliverability is now a READ
;; FACT rather than an inference. The price is that a legacy row minted
;; before provision! stamped its subject is unaddressable — refused at
;; the door, the same refusal every other nobody wears — and a refusal
;; on the way in is the trade this file already chose (see the ns
;; docstring): better than mail that can never arrive.
;; nil ctx hooks (the storage-free render probe) → ::unknown: the
;; caller reads that as "cannot disprove" and stays optimistic, while
;; the REAL create, which always carries the hooks, fails CLOSED.
(defn- delivery-id [ctx to]
  (let [read' (:read ctx)
        find' (:find ctx)]
    (cond
      (and (nil? read') (nil? find')) ::unknown
      (str/blank? (str to)) nil
      :else
      (let [to (str to)
            row (when read' (read' :member to))
            subject (some-> (get-in row [:data :subject]) str str/trim
                            not-empty)]
        (cond
          subject subject

          ;; the id is itself a bound principal — its row lives under
          ;; another id entirely (an :invited row's, before the bind)
          (and find' (seq (find' :member {:subject to} {:limit 1}))) to

          :else nil)))))

;; the create wall, recipient side. It never DENIES — it throws the
;; schema-shaped refusal above, so an unknown recipient, an unbound
;; invitation and a malformed field are one answer on the wire; a
;; guard deny would have been a 409 :guard-refused naming this guard,
;; which is exactly the narration L4 asked us to stop. A letter to
;; YOURSELF is legitimate (notes-to-next-self) and needs no special
;; case: your own id resolves like any member's.
(defguardfn letter-to-is-a-member
  {:reads [:principal :member]
   :explain "A letter must be addressed to a member of the household."}
  [_row inp ctx]
  (let [d (delivery-id ctx (:to inp))]
    (when (nil? d) (undeliverable!))
    (t/allow)))

;; the recipient stamp: the guard resolved the delivery identity one
;; write ago and this resolves it again — same transaction, same
;; hooks, same answer — because create guards run with row nil BEFORE
;; :on-create and cannot hand anything forward. A resolution that
;; comes back empty HERE would mean the roster moved under the write;
;; it wears the same refusal rather than storing mail nobody can open.
(defn- stamp-recipient [row ctx]
  (let [d (delivery-id ctx (get-in row [:data :to]))]
    (cond
      (= ::unknown d) row
      (nil? d) (undeliverable!)
      :else (assoc-in row [:data :to] d))))

(defn- stamp-letter [row ctx]
  (-> row (stamp-author ctx) (stamp-recipient ctx)))

;; ── pacing: the create door is not a roster to sweep ────────────────

(def letter-pace-limit
  "Letter creates ATTEMPTED per rolling hour per principal. A
  household writes a handful of notes a day, so 60 an hour is far
  above any real correspondence and far below a useful enumeration
  sweep — the second half of the L4 fix, and the half that actually
  bounds it: an unknown recipient stores NO row, so a ceiling counted
  in stored letters would leave the probe free. This counts what the
  probe spends: attempts."
  60)

(def letter-pace-log
  "The rolling-hour window, process-local: pid → the instants of its
  create attempts, pruned as the hour passes. An atom and not a query
  for members.clj knock-log's recorded reason — query-rows' one
  ordering is oldest-first, so a paced read past the limit goes stale
  exactly when an abuser needs it to — plus the reason that matters
  here: a REFUSED create leaves no row to count. The trade is honest:
  a restart forgets the window, and N processes multiply the ceiling
  by N. Tests may reset it."
  (atom {}))

(defn- pace-admits?
  "Prune the whole window, admit only under the limit, and record this
  attempt — one swap!, so a concurrent pair cannot both take the last
  slot. Ours went in exactly when this pid's window now ends with OUR
  instant (identity, not equality: two attempts can share a tick)."
  [pid ^Instant now]
  (let [cutoff (.minusSeconds now 3600)
        after (swap! letter-pace-log
                     (fn [log]
                       (let [live (reduce-kv
                                   (fn [m k v]
                                     (let [v (filterv #(neg? (compare cutoff %)) v)]
                                       (if (seq v) (assoc m k v) m)))
                                   {} log)
                             mine (get live pid [])]
                         (if (< (count mine) letter-pace-limit)
                           (assoc live pid (conj mine now))
                           live))))]
    (identical? (peek (get after pid)) now)))

(defguardfn letters-are-paced
  {:reads [:principal :now]
   :vars [:limit :retry_at]
   :explain "Letters are paced to {limit} an hour; the window reopens at {retry_at}."}
  [_row _inp ctx]
  ;; the storage-free probe (render, the partial rehearsal) carries no
  ;; hooks — it never spends a slot, the same discipline every guard
  ;; here keeps. This guard rides FIRST in :create-guards so the
  ;; attempt is counted whatever the recipient guard then says.
  (if (nil? (:read ctx))
    (t/allow)
    (let [pid (:id (:principal ctx))
          now (:now ctx)]
      (if (pace-admits? pid now)
        (t/allow)
        (let [retry (.plusSeconds ^Instant (reduce (fn [a b] (if (neg? (compare a b)) a b))
                                                   (get @letter-pace-log pid [now]))
                                  3600)]
          (t/deny {:vars {:limit letter-pace-limit :retry_at (str retry)}
                   :retry-at retry}))))))

;; ── the recipient's two acts ────────────────────────────────────────

(defn- recipient? [row ctx]
  (= (:id (:principal ctx)) (get-in row [:data :to])))

;; the open wall: only the principal a letter is ADDRESSED to may open
;; it — not the author (sending is not landing), not a curator: it is
;; how the house knows the letter landed.
(defguardfn opener-is-recipient
  {:reads [:principal]
   :explain "Only the letter's recipient may open it."}
  [row _inp ctx]
  (if (recipient? row ctx) (t/allow) (t/deny)))

;; the discard wall: the same test, for the same reason. A shelf with
;; no floor is a flood (waymark-tti.3 L5) — with no retire and no
;; delete, anyone who may write to you could fill your welcome
;; forever. :discard is the recipient's OWN broom: the row remains
;; (nothing is deleted, the author's sight is untouched, the audit
;; keeps its transition) but it leaves the shelf and the waiting
;; count. A curator may not clear someone else's mail.
(defguardfn discarder-is-recipient
  {:reads [:principal]
   :explain "Only the letter's recipient may discard it."}
  [row _inp ctx]
  (if (recipient? row ctx) (t/allow) (t/deny)))

;; ── the two walls, written down as scenarios ────────────────────────
;; Sentences the house can check, judged by the framework instead of
;; by a reader's trust in the comments above. Every one of these is
;; check-tier — no :given rows, and both walls declare :reads
;; [:principal] — so `make check-queue` judges them with no database
;; at all, in the same breath as the usability warnings.

(defscenario only-the-recipient-opens
  "A letter addressed to someone else does not open for a curious
   sibling, and the refusal names the wall it hit."
  {:kind    :letter
   :attempt :open
   :row     {:state :waiting
             :data {:owner "mom" :to "iris" :title "For Iris"
                    :body "Proud of you today."}}
   :as      {:id "otto" :type :person}
   :expect  {:refused :opener-is-recipient
             :because "Only the letter's recipient may open it."}})

(defscenario the-addressed-child-opens-her-own
  "The child a letter is addressed to opens it — that is how the
   house knows the letter landed."
  {:kind    :letter
   :attempt :open
   :row     {:state :waiting
             :data {:owner "mom" :to "iris" :title "For Iris"
                    :body "Proud of you today."}}
   :as      {:id "iris" :type :person}
   :expect  {:allowed true}})

(defscenario only-the-recipient-discards
  "A curator may not clear someone else's mail: the broom is the
   recipient's own, recovery-admin or not."
  {:kind    :letter
   :attempt :discard
   :row     {:state :waiting
             :data {:owner "mom" :to "iris" :title "For Iris"
                    :body "Proud of you today."}}
   :as      {:id "dad" :type :person :roles #{:recovery-admin}}
   :expect  {:refused :discarder-is-recipient
             :because "Only the letter's recipient may discard it."}})

(defscenario an-opened-letter-does-not-open-twice
  "A letter, once opened, just IS opened — the second knock is
   refused by the machine itself, with no guard behind it."
  {:kind    :letter
   :attempt :open
   :row     {:state :opened
             :data {:owner "mom" :to "iris" :title "For Iris"
                    :body "Proud of you today."}}
   :as      {:id "iris" :type :person}
   :expect  {:refused :out-of-state
             :because "Available in state(s) Waiting"}})

;; ── :letter — the addressed note ────────────────────────────────────

(defresource letter
  {:kind :letter
   :plural "letters"
   :states [:waiting :opened :discarded]
   :initial :waiting
   :terminal #{}
   ;; :opened and :discarded are RESTING states, not tombs: no
   ;; outgoing transitions (a letter, once opened, just IS opened) but
   ;; not :terminal either — a terminal row reads as closed history,
   ;; and an opened letter is still living mail on the shelf
   :allow-dead #{:opened :discarded}
   :nav :system
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   ;; the TWO-PARTY own-surface (waymark-tti.3): a letter is yours as
   ;; its AUTHOR or as its RECIPIENT — sender and addressee each see
   ;; the row with no grant, a third agent 404s it by the same
   ;; default-deny wall. The two branches query separately and their
   ;; id sets union; the recipient guards narrow open/discard further
   ;; to the addressee alone, because a shelf with no floor is a flood
   ;; and the recipient keeps a broom of its own.
   :own-surface {:by [:owner :to]
                 :actions #{"create" "open" "discard"}}
   :schema [:map
            ;; the AUTHOR — stamped by the engine (on-create); a
            ;; persisted row always carries it. Filterable so own-ids
            ;; can query the author's half of the two-party surface.
            [:owner {:optional true :x-display {:raw true}}
             [:maybe [:string {:min 1 :max 128}]]]
            ;; the RECIPIENT's DELIVERY identity — resolved and
            ;; stamped on create (never the raw spelling the sender
            ;; typed), the other half of the two-party surface, and
            ;; the one id :open and :discard are gated to
            [:to {:x-display {:raw true}} [:string {:min 1 :max 128}]]
            [:title {:optional true} [:maybe [:string {:max 120}]]]
            ;; the note itself, free prose — never a field to facet on
            [:body {:x-display {:widget "prose"}} [:string {:min 1 :max 10000}]]]
   :create-schema [:map
                   [:owner {:optional true} [:maybe [:string {:min 1 :max 128}]]]
                   [:to [:string {:min 1 :max 128}]]
                   [:title {:optional true} [:maybe [:string {:max 120}]]]
                   [:body [:string {:min 1 :max 10000}]]]
   ;; BOTH party fields :eq-filterable — the own-surface ids-of
   ;; pushdown (owner=pid OR to=pid) depends on it; state for the shelf
   :filterable {:owner #{:eq} :to #{:eq} :state #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; pacing first: it must count the attempt before the recipient
   ;; guard decides whether there is anyone to deliver to
   :create-guards [letters-are-paced letter-author-is-self
                   letter-to-is-a-member]
   :on-create stamp-letter
   ;; the policy, declared beside the law it judges
   :scenarios [only-the-recipient-opens
               the-addressed-child-opens-her-own
               only-the-recipient-discards
               an-opened-letter-does-not-open-twice]
   ;; two transitions, both the recipient's, no amend and no delete: a
   ;; letter, once sent, is sent. Neither takes input, so neither
   ;; carries :record — the transition itself is the audit
   ;; (resource.clj refuses :record without :input, and rightly:
   ;; there is nothing to retain).
   :actions
   {:open {:from #{:waiting} :to :opened
           :guards [opener-is-recipient]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "opening is how the house knows the letter landed"}
           :display {:label "Open" :order 1}}
    :discard {:from #{:waiting :opened} :to :discarded
              :guards [discarder-is-recipient]
              ;; :confirm, not :one-way — the two exclude each other
              ;; (types.clj): a broom you swing by accident is worse
              ;; than one that asks first, and unlike :open there is
              ;; no natural moment that makes discarding obvious.
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The letter leaves your shelf and your waiting count for good. Nothing is deleted — the row stands and its author still sees it — but you cannot put it back."}
              :display {:label "Discard" :order 2}}}})
