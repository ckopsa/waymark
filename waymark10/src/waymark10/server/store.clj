(ns waymark10.server.store
  "The storage seam: six operations and a transition append. Rows are
  maps {:id :state :version :data :shape :owner :law-revision
  :next-flip-at :created-at :updated-at}; :data is the plain-JSON
  document (keyword keys, exact decimals) — type coercion against the
  kind's schema is the engine's job at the load boundary, storage
  stays dumb.

  The transition log is one append-only table wearing four hats:
  audit trail, outbox (pg_notify rides the write transaction),
  activity feed, and the idempotency/natural-replay anchor."
  (:require [waymark10.types :as t]))

(defprotocol Storage
  (with-tx* [st f]
    "Run (f tx) inside one transaction; the invoke algorithm is one
    call of this.")
  (ensure-kind! [st rmap]
    "Create/extend the kind's table from its declaration (crude
    ensure; the migrate diff arrives with phase 2's tail).")
  (load-row [st tx kind id opts]
    "The row map, or nil. {:for-update true} takes the row lock —
    exactly one per invocation.")
  (insert-row! [st tx kind row])
  (save-row! [st tx kind row expected-version]
    "Optimistic save; throws :waymark10/version-conflict when the row
    moved.")
  (query-rows [st tx kind where opts]
    "Rows matching a {field value} equality map (phase-2 grammar;
    collections widen it in phase 7). opts: {:limit n :order-by kw}.")
  (append-transition! [st tx record]
    "Append to the log and notify the outbox channel in the same
    transaction. Returns the record with its assigned :id.")
  (transitions [st tx where opts]
    "Log rows: where {:kind … :resource-id … :since id}, newest-last.")
  (idempotency-lookup [st tx key kind]
    "→ {:status :response :media-type :request-digest} or nil.")
  (idempotency-store! [st tx key kind action digest status response media-type])
  (law-count [st tx kind revision]
    "How many rows of the kind are stamped with this law revision —
    the supersede-when-empty question (phase 5). Counts every stamped
    row, terminal included: a law lives while anything cites it.")
  (restamp-law! [st tx kind where to-revision]
    "Bulk-move a population's law stamp: rows matching the equality
    map ({field value}; :state and :law-revision address their
    columns, anything else the JSON document) get law_revision =
    to-revision. Version untouched and no transition appended —
    recorded deviation from waymark9, whose restamp logged per row:
    lifecycle restamps are maintenance, not writes. Returns the row
    count moved.")

  ;; ── phase 6: the maintainer's reads and the maintenance write ──────

  (count-matching [st tx kind conds]
    "COUNT of the kind's rows matching every cond — the maintainer's
    aggregate read. A cond is {:target :state|:id|:data, :field name,
    :cast sql-type, :op :=|:<|:<=|:>=|:>|:in, :value v | :values [vs]};
    :data values cross as strings and cast server-side.")
  (ids-matching [st tx kind conds limit]
    "Row ids matching every cond (same grammar as count-matching),
    id-ordered, LIMIT'd — the inverted dependent query and the
    backfill pager.")
  (update-data! [st tx kind id data next-flip-at]
    "The maintenance write: the document and the clock index only —
    version untouched, updated_at stamped, NO transition logged.
    Derivation maintenance is not a write (phase 6).")
  (due-flips [st tx kind now limit]
    "Rows whose next_flip_at <= now, oldest flip first, FOR UPDATE —
    the clock sweep's page.")

  ;; ── phase 7: the collection surface and the draft rows ─────────────

  (search-rows [st tx kind conds opts]
    "The collection page: rows matching every cond (the maintainer's
    grammar, widened with :op :in-any — vocab-array membership via
    JSONB containment). opts {:order-by field-kw :desc bool :limit n
    :offset n}; ordering runs over the promoted generated column
    (f_<field>), :state over its column, nil over created_at — id
    tiebreak always, so pages never overlap.")
  (facet-counts [st tx kind field conds array?]
    "Observed value → count for one faceted field under the same conds
    the rows match — a real GROUP BY. array? true unrolls a JSON array
    field (one row counts once per member).")
  (load-draft [st tx kind id action audience]
    "→ {:values … :base-version … :updated-at …} or nil.")
  (save-draft! [st tx kind id action audience values base-version]
    "Upsert the (kind, id, action, audience) draft row: values replace
    wholesale, base_version restamps, updated_at now.")
  (delete-draft! [st tx kind id action audience]
    "Discard/consume: delete the draft row; absent is a no-op.")

  ;; ── phase 9b: consumer cursors and job leases ──────────────────────

  (cursor-get [st tx consumer]
    "The named consumer's log position (a transition id), or nil when
    the consumer has never checkpointed.")
  (cursor-set! [st tx consumer position]
    "Upsert the consumer's cursor — the at-least-once checkpoint the
    webhook deliverer resumes from across restarts.")
  (claim-job-lease! [st tx job-id holder ttl-seconds]
    "Claim-or-steal the job's lease: insert, extend our own, or take
    over one whose expiry has passed — a live other holder refuses.
    → true when held for ttl-seconds from now.")
  (release-job-lease! [st tx job-id holder]
    "Drop the lease if we still hold it; absent or stolen is a no-op."))

(defn with-tx
  "Sugar: (with-tx st [tx] …)."
  [st f]
  (with-tx* st f))

(defn version-conflict [kind id expected]
  (ex-info (str "version conflict: " (name kind) " " id
                " moved past v" expected)
           {:waymark10/version-conflict true :kind kind :id id
            :expected-version expected}))

(defn definition-checked-name
  "Identifiers spliced into SQL come only from checked declarations;
  refuse anything else loudly."
  ^String [x]
  (let [s (name x)]
    (when-not (re-matches #"[a-z][a-z0-9_]*" s)
      (throw (t/definition-error (str "identifier " (pr-str s) " is not a checked snake_case token"))))
    s))
