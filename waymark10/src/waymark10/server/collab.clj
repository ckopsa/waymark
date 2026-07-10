(ns waymark10.server.collab
  "Live collab (phase 9b): waymark-relay over http-kit websockets at
  GET /api/{plural}/{id}/-/{action}/draft/collab, for :edit actions
  whose :draft is {:shared true :live true}. Clients join a per-draft
  room; frames:

    client → server   {\"type\": \"set\", \"field\", \"value\"}
    server → others   {\"type\": \"update\", \"field\", \"value\",
                       \"rev\", \"author\"}
    client → server   {\"type\": \"sync\"}
    server → sender   {\"type\": \"sync\", \"values\", \"rev\"}
    server → sender   {\"type\": \"error\", \"errors\": {field: [msgs]}}

  Every accepted set is the SAME write a draft PUT lands — partial
  validation against the action's input schema (unknown fields and
  type errors answer an error frame, values may be incomplete
  mid-edit), then the shared draft row persists through the drafts
  storage in the set's own transaction. The room's rev is a per-draft
  atom, incremented per accepted set and stamped on the broadcast.

  Merge discipline, scoped honestly to per-field last-writer-wins:
  server-ordered (the room lock serializes sets), no OT/CRDT, and —
  unlike waymark9's relay/2 — no base_rev staleness rejection, no
  saved/reject acks (sync is the read-back), no presence frames, no
  affordance gate or regate (the resource and a live shared draft
  policy are the door; what the action may DO stays the invoke's
  question), no cross-worker bus (rooms are process-local), and no
  closed frame when the draft is consumed — the next set answers an
  error and the client re-syncs. Each is a recorded punt, not a
  forgotten one.

  Rooms clean up on last disconnect; the rev resets with the room —
  LWW ordering only matters among live participants, and a rejoined
  room starts fresh over the persisted values."
  (:require [org.httpkit.server :as http]
            [waymark10.schema :as schema]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── rooms ───────────────────────────────────────────────────────────

(defn- room-of [eng key]
  (get (swap! (:collab-rooms eng) update key
              #(or % {:clients (atom {})
                      :rev (atom 0)
                      :lock (Object.)}))
       key))

(defn- leave! [eng key room ch]
  (let [clients (swap! (:clients room) dissoc ch)]
    (when (empty? clients)
      (swap! (:collab-rooms eng)
             (fn [rooms]
               ;; only reap the room we joined — a racing rejoin may
               ;; have re-minted the key
               (if (identical? (get rooms key) room)
                 (dissoc rooms key)
                 rooms))))))

(defn- send-frame! [ch frame]
  (http/send! ch (wire/write-json frame)))

(defn- broadcast!
  "Every room member but the sender."
  [room frame sender]
  (doseq [ch (keys @(:clients room))
          :when (not (identical? ch sender))]
    (send-frame! ch frame)))

(defn- author-of [principal]
  {:id (:id principal)
   :type (name (:type principal :human))
   :display (or (:display principal) (:id principal))})

;; ── the door ────────────────────────────────────────────────────────

(defn- live-defn
  "The action's normalized definition when its draft policy is
  {:shared true :live true}; 404 otherwise — a collab route for an
  unlive draft does not exist."
  [rdef action-name]
  (or (when-some [a (get-in rdef [:actions action-name])]
        (when (and (get-in a [:edit :draft :shared])
                   (get-in a [:edit :draft :live]))
          (assoc a :name action-name)))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str (name (:kind rdef))
                                      " has no live shared draft on "
                                      (name action-name) ".")}))))

;; ── frames ──────────────────────────────────────────────────────────

(defn- error! [ch errors]
  (send-frame! ch {:type "error" :errors (p/wire-value errors)}))

(defn- load-shared-draft [eng kind id action-name]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-draft (:storage eng) tx kind id
                               action-name "shared"))))

(defn- persist-set!
  "The set's write: merge the field into the shared draft's stored
  values and upsert, base_version = the row's current version — the
  same row a draft PUT writes. → the merged values, or nil when the
  resource is gone (consumed rows outlive their drafts, vanished rows
  do not)."
  [eng rdef action-name id field value]
  (store/with-tx (:storage eng)
    (fn [tx]
      (when-some [raw (store/load-row (:storage eng) tx (:kind rdef) id {})]
        (let [draft (store/load-draft (:storage eng) tx (:kind rdef) id
                                      action-name "shared")
              values (assoc (or (:values draft) {}) field value)]
          (store/save-draft! (:storage eng) tx (:kind rdef) id
                             action-name "shared" values (:version raw))
          values)))))

(defn- handle-set!
  [eng rdef defn' id room ch msg principal]
  (let [field-str (:field msg)
        field (when (string? field-str) (keyword field-str))
        known (set (schema/entry-keys (:input defn')))]
    (cond
      (nil? field)
      (error! ch {:_root ["expected {\"type\": \"set\", \"field\", \"value\"}"]})

      (not (contains? known field))
      (error! ch {field ["unknown field"]})

      :else
      (let [value (:value msg)
            decoded (schema/decode (:input defn') {field value})]
        (if-some [errors (schema/partial-closed-errors (:input defn') decoded)]
          (error! ch errors)
          ;; the room lock serializes sets — server-ordered LWW
          (locking (:lock room)
            (if (nil? (persist-set! eng rdef (:name defn') id field value))
              (error! ch {:_root [(str "no " (name (:kind rdef)) " " id)]})
              (let [rev (swap! (:rev room) inc)]
                (broadcast! room {:type "update"
                                  :field field-str
                                  :value value
                                  :rev rev
                                  :author (author-of principal)}
                            ch)))))))))

(defn- handle-sync!
  [eng rdef defn' id room ch]
  (let [draft (load-shared-draft eng (:kind rdef) id (:name defn'))]
    (send-frame! ch {:type "sync"
                     :values (p/wire-value (or (:values draft) {}))
                     :rev @(:rev room)})))

(defn- handle! [eng rdef defn' id room ch raw principal]
  (let [msg (when (string? raw)
              (try (wire/read-json raw) (catch Exception _ ::bad)))]
    (cond
      (or (= ::bad msg) (not (map? msg)))
      (error! ch {:_root ["invalid JSON"]})

      (= "set" (:type msg))
      (handle-set! eng rdef defn' id room ch msg principal)

      (= "sync" (:type msg))
      (handle-sync! eng rdef defn' id room ch)

      :else
      (error! ch {:_root ["expected type \"set\" or \"sync\""]}))))

;; ── the route ───────────────────────────────────────────────────────

(defn join
  "Upgrade one request into a room membership for the draft's
  lifetime. The resource must exist and the action must declare a
  live shared draft — refusals throw problems BEFORE the upgrade, so
  the handshake answers them as plain HTTP."
  [eng rdef action-name id principal req]
  (let [defn' (live-defn rdef action-name)
        _ (or (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx (:kind rdef) id {})))
              (throw (p/not-found (:kind rdef) id)))
        key [(:kind rdef) id (:name defn')]
        room (room-of eng key)]
    (http/as-channel
     req
     {:on-open (fn [ch]
                 (swap! (:clients room) assoc ch (author-of principal)))
      :on-receive (fn [ch raw]
                    (handle! eng rdef defn' id room ch raw principal))
      :on-close (fn [ch _status]
                  (leave! eng key room ch))})))
