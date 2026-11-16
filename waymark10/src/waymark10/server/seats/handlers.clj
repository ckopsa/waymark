(ns waymark10.server.seats.handlers
  "The seat's door handlers and its on-create, split out of
  waymark10.server.seats so that namespace keeps room under its ns-size
  entry (ticket 1af717d2). The seat resource names each by the name it
  had there."
  (:require [waymark10.resource :refer [defhandler]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.keys :as keys]
            [waymark10.server.seats.owners :refer [opening-person]]))

(set! *warn-on-reflection* true)

(defhandler unpark-seat [row _inp ctx]
  ;; R-2: the seat's state IS the input of the `seat_not_active` wall,
  ;; and `unpark` is the hand that moves it. The other three lines
  ;; stand — a park does not spend fuel and does not change the model
  ;; list — and the door that reads them judges them.
  ;;
  ;; INVARIANT 4: an authored seat's FIRST unpark is its person's
  ;; approval, and the row says who gave it and when. From then on its
  ;; author restates it within the ceiling with no new tap.
  (let [first-approval? (and (some-> (get-in row [:data :authored_by]) str
                                     not-empty)
                             (nil? (get-in row [:data :approved_by])))
        approver (when first-approval?
                   (or (some-> (delegation/allowed-hold ctx :seat (:id row))
                               (get-in [:data :decided_by]) str not-empty)
                       (str (get-in ctx [:principal :id]))))]
    (cond-> row
      (= "seat_not_active" (str (get-in row [:data :halt :reason])))
      (update :data dissoc :halt)
      first-approval?
      (update :data assoc :approved_by approver :approved_at (:now ctx)))))

(defhandler hand-seat-to [row inp ctx]
  ;; INVARIANT 3 and 4: the person's hand writes the author, and the
  ;; same tap IS the approval — the seat is live already, so there is
  ;; no first unpark to carry it. The person is the one who decided a
  ;; held call when this replays one, else the hand at the door.
  (let [person (or (some-> (delegation/allowed-hold ctx :seat (:id row))
                           (get-in [:data :decided_by]) str not-empty)
                   (delegation/owner-of ctx)
                   (str (get-in ctx [:principal :id])))]
    (update row :data assoc
            :authored_by (str (:author inp))
            :owner person
            :approved_by person
            :approved_at (:now ctx))))

(defhandler take-seat-back [row _inp _ctx]
  ;; hand_to undone: the author and the approval go, and the author's
  ;; restates are held again under invariant 4.
  (update row :data dissoc :authored_by :approved_by :approved_at))

(defn seat-born
  "The seat's on-create (invariant 3 and 4 of server/delegation): a
  seat whose author is a DELEGATING seat is born parked, and carries
  `authored_by` and `owner` from its first moment. A seat a person
  opens — in person, or through a delegate that acts for them — is
  born live and carries that person as `owner`, so its later restates
  are judged against them (`scope-inside-the-owners`). Any other hand
  writes no owner.

  The author is read off the held call when this birth replays one,
  so a sitter whose grant lapsed between the ask and the tap still
  writes the author the person approved."
  [row ctx]
  (if-some [author (delegation/authoring-seat ctx :seat nil)]
    (let [owner (or (delegation/owner-of ctx)
                    (some-> (get-in author [:data :owner]) str not-empty))]
      (-> row
          (assoc :state :parked)
          (update :data assoc :authored_by (str (:id author)))
          (cond-> owner (assoc-in [:data :owner] owner))))
    (if-some [person (opening-person ctx)]
      (assoc-in row [:data :owner] person)
      row)))

(defhandler write-stale [row inp _ctx]
  (assoc-in row [:data :stale] (:stale inp)))

(defhandler drop-lapsed [row inp _ctx]
  (assoc-in row [:data :scope] (:scope inp)))

(defhandler write-halt [row inp ctx]
  (assoc-in row [:data :halt]
            (cond-> {:reason (:reason inp) :since (:now ctx)}
              (:detail inp) (assoc :detail (:detail inp)))))

(defhandler clear-halt-mark [row _inp _ctx]
  (update row :data dissoc :halt))

;; R-12.12: a new offer REPLACES the old one — at most one live sitter
;; key per seat, members' set-reentry overwrite exactly. The key the
;; person pasted into yesterday's Routine dies the moment a fresh one
;; lands. The row keeps the key's hash alone (R-22.6), as it keeps the
;; fire key's and the feed token's: no read can answer the key again.
(defhandler set-sitter-key [row inp _ctx]
  (-> row
      (update :data dissoc :sitter_key)
      (assoc-in [:data :sitter_key_hash] (keys/hash-b64 (:key inp)))))

(defhandler clear-sitter-key [row _inp _ctx]
  (update row :data dissoc :sitter_key :sitter_key_hash))

;; R-12.19: a fire moves nothing on the seat. The row is returned as
;; it stands, and the transition IS the record — `:record true` puts
;; the text in the log's inputs, the ledger counts the move, and the
;; schedules consumer hears it and starts the run.
(defhandler fire-seat [row _inp _ctx]
  row)

(defn- larger
  "The larger of two comparables, either of which may be absent."
  [a b]
  (cond (nil? a) b (nil? b) a (neg? (compare a b)) b :else a))

(defhandler absorb-fold [row inp _ctx]
  (-> row
      (assoc-in [:data :scope] (:scope inp))
      (assoc-in [:data :standing_ttl_seconds] (:standing_ttl_seconds inp))
      (assoc-in [:data :budget_usd_per_week] (:budget_usd_per_week inp))))

(defhandler follow-successor [row inp _ctx]
  (assoc-in row [:data :judgment] (:judgment inp)))

(defhandler merge-seat [row inp ctx]
  ;; § 6, in order: fold the two scopes per kind (waymark-ycp's
  ;; merge — one entry per kind, never appended), keep the LARGER
  ;; ttl and budget, and write
  ;; merged_into. The fold lands on `into` through its OWN door, so
  ;; the four scope guards judge it there (R-6.1) and `into`'s history
  ;; says its authority grew, by whose hand, in the same transaction
  ;; as the close. Nothing revokes and nothing mints (R-6.2): a grant
  ;; citing a merged seat scopes to nothing by R-5.2 and dies on its
  ;; own clock, and each moved sitter files a bootstrap ask for `into`.
  (let [into-id (str (:into inp))
        mine (:data row)
        target (when (:read ctx) ((:read ctx) :seat into-id))
        theirs (:data target)]
    (when (and target (:invoke ctx))
      ((:invoke ctx) :seat into-id :absorb
       {:scope (grants/merge-scope (:scope theirs) (:scope mine))
        :standing_ttl_seconds (max (long (or (:standing_ttl_seconds theirs) 0))
                                   (long (or (:standing_ttl_seconds mine) 0)))
        :budget_usd_per_week (larger (:budget_usd_per_week theirs)
                                     (:budget_usd_per_week mine))}))
    (assoc-in row [:data :merged_into] into-id)))
