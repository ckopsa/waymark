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
  (idempotency-store! [st tx key kind action digest status response media-type]))

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
