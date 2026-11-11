(ns waymark10.server.seats.fire
  "A fire's key, from mint to spend, carved out of
  `waymark10.server.seats` (ticket bb84d8d9, carve 3 of 4): the row a
  fire's text names, the key one fire mints and one sit spends, the
  marks a pull link puts on it while the fire waits, and the trace a
  spent key leaves on the sitting it opened. The engine's own hand and
  the two limits a key reads live here too, since these defns write
  with them.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            [waymark10.server.seats.keys :as seat-keys]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def seats-actor
  "The system actor the sweep and the router write seats as — the
  definitions `deploy` and members `registrar` precedent."
  (t/principal {:id "waymark10-seats" :type :system :display "Seats"}))

(def default-idle-seconds
  "The seat schema's own default for `sitting_idle_seconds`, for a row
  written before that field existed. One hour."
  3600)

(def sit-deadline-seconds
  "How long a fired run has to sit before its fire is missed. Ten
  minutes: a run that has not sat by then has died. The sitting a fire
  opens is born with this as its lease (`open-fire-sitting!`), so the
  lease lapses exactly when the miss is declared."
  600)

(def ^:private key-ceiling seat-keys/key-ceiling)

(def ^:private key-hash seat-keys/key-hash)

(def ^:private live-keys seat-keys/live-keys)

(def ^:private fire-key-entry seat-keys/fire-key-entry)

(def ^:private uuid-in
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(def ^:private short-id-in
  "An 8-hex word: the first group of a row id, as a person writes it."
  #"(?<![0-9A-Za-z-])[0-9a-fA-F]{8}(?![0-9A-Za-z-])")

(def ^:private short-id-words
  "How many 8-hex words of one prose `named-row` looks up."
  8)

(defn- short-id-row
  "The id of the ONE row whose id opens with the 8-hex `prefix`, or nil
  (ticket 26c7967a). The rows are those of the kind the seat walks and,
  for a seat that walks tickets, the changes, which `named-walk-row`
  reads back to their ticket. No row, or several, names nothing."
  [eng walk prefix]
  (let [st (:storage eng)
        prefix (str/lower-case prefix)
        conds [{:target :id :op :>=
                :value (str prefix "-0000-0000-0000-000000000000")}
               {:target :id :op :<=
                :value (str prefix "-ffff-ffff-ffff-ffffffffffff")}]
        ids (try
              (into []
                    (comp (filter #(get (inv/resources eng) %))
                          (mapcat (fn [kind]
                                    (store/with-tx st
                                      (fn [tx]
                                        (store/ids-matching st tx kind
                                                            conds 2)))))
                          (map str)
                          (filter #(str/starts-with? % prefix)))
                    (distinct (cond-> [(keyword walk)]
                                (= "ticket" walk) (conj :change))))
              (catch Exception _ nil))]
    (when (= 1 (count ids))
      (first ids))))

(defn named-row
  "The id of the walk row a fire's text names, or nil. A wake's text is
  the transition as JSON (`wakes/wake-text`): the kind and the row id.
  Only a row of the kind this seat WALKS counts, or, for a seat that
  walks tickets, a change, which the sit reads back to the ticket it was
  born from (`named-walk-row`). A person's prose names the first row id
  it holds (ticket 7af7d506), and the sit hands that row only when it is
  such a row. Prose with no whole id names the row of its first 8-hex
  word that opens exactly one row's id (`short-id-row`, ticket
  26c7967a), when `eng` is given to look it up. Any other text — prose
  with no id, a count wake's count — names nothing."
  ([seat-row text] (named-row nil seat-row text))
  ([eng seat-row text]
   (when-some [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
     (when-some [s (some-> text str str/trim not-empty)]
       (if (str/starts-with? s "{")
         (let [m (try (wire/read-json s) (catch Exception _ nil))
               kind (when (map? m) (str (:kind m)))]
           (when (or (= walk kind)
                     (and (= "ticket" walk) (= "change" kind)))
             (some-> (:id m) str not-empty)))
         (or (re-find uuid-in s)
             (when eng
               (some #(short-id-row eng walk %)
                     (take short-id-words
                           (distinct (re-seq short-id-in s)))))))))))

(defn hold-fire-key!
  "Mint the key ONE fire carries, and keep its hash on the seat row.
  The key comes back, for the fire text to carry. Nil comes back when
  this seat has no instructions.

  A SEAT WITH NO INSTRUCTIONS FIRES AS IT ALWAYS DID. Its Routine
  holds a key of its own, and the composed text is the person's prose
  alone (R-12.35). A key no session would read is a key not worth
  minting.

  The write is a MAINTENANCE write, `bump-counter!`'s own spelling and
  its reason: a transition for each fire would put the record of a
  credential in the log, beside the fire that is already there. The
  row is read FOR UPDATE inside the write's own transaction, so a
  restate that lands at the same moment is not lost, and two fires of
  one seat do not write over each other's key.

  The expired entries go at the same moment, and the newest keys stay,
  up to `key-ceiling`.

  `text` is the fire's own text. When it is a wake's text naming a row
  of the kind the seat walks, the entry keeps that row's id (`:row`),
  so the sit that spends the key knows which row the run was sent to
  walk (`fire-key-row`).

  `sitting` is the id of the sitting this fire opened
  (`open-fire-sitting!`, ticket 9100c7b9), or nil. The entry keeps it
  (`:sitting`) in this same write, so the sit that spends the key takes
  that sitting (`fire-key-sitting`), and `expire` ends it when nobody
  did."
  ([eng seat-row at] (hold-fire-key! eng seat-row at nil nil))
  ([eng seat-row at text] (hold-fire-key! eng seat-row at text nil))
  ([eng seat-row at text sitting]
  (when (and seat-row
             (some-> (get-in seat-row [:data :instructions]) str not-empty)
             (get (inv/resources eng) :seat))
    (let [^Instant at (or (instant-of at) ((:now-fn eng)))
          key (keys/mint)
          ttl (long (or (get-in seat-row [:data :sitting_idle_seconds])
                        default-idle-seconds))
          named (named-row eng seat-row text)
          entry (cond-> {:hash (key-hash key)
                         :expires_at (str (.plusSeconds at ttl))
                         :fired_at (str at)}
                  named (assoc :row named)
                  sitting (assoc :sitting (str sitting)))]
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :seat
                                          (str (:id seat-row))
                                          {:for-update true})]
            (let [kept (vec (take-last (dec (long key-ceiling))
                                       (live-keys row at)))]
              (store/maintain! :credential (:storage eng) tx :seat (str (:id seat-row))
                                  (assoc (:data row) :fire_keys
                                         (conj kept entry))
                                  (:next-flip-at row))
              key))))))))

(defn fire-key-held?
  "Does this seat hold an unspent, unexpired key for `key`?

  A READ, and it spends nothing. The sit asks it to learn which seat a
  key answers for, and it spends the key only after every other wall
  has passed."
  [eng seat-row key]
  (boolean (and seat-row
                (some? (fire-key-entry seat-row key ((:now-fn eng)))))))

(defn fire-key-row
  "The walk row the fire that minted `key` named, or nil (`named-row`).
  A READ, like `fire-key-held?`: the sit asks it BEFORE it spends the
  key, since the spend takes the entry off the row."
  [eng seat-row key]
  (when seat-row
    (some-> (fire-key-entry seat-row key ((:now-fn eng))) :row str not-empty)))

(defn spend-fire-key!
  "Spend the key of one firing: take its hash off the seat row, so the
  next sit that presents it is refused. → true when this call is the
  one that spent it, and nil when this key is no firing's key of this
  seat.

  ONE KEY OPENS ONE SIT, and this is where that holds. The row is read
  FOR UPDATE and written in the same transaction, so two sessions that
  present one key at the same moment do not both come away with it.
  The second reads the row the first already wrote, finds no entry,
  and is refused with the sentence of R-12.14.

  A standing key reaches here too, and it answers nil. `standing-key?`
  is what the caller asks first, and a key the seat answers for by its
  own credential is spent by nothing."
  [eng seat-row key]
  (when (and seat-row (get (inv/resources eng) :seat))
    (let [now ((:now-fn eng))]
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :seat
                                          (str (:id seat-row))
                                          {:for-update true})]
            (when-some [entry (fire-key-entry row key now)]
              (let [kept (into [] (remove #(= (str (:hash %))
                                              (str (:hash entry))))
                               (live-keys row now))]
                (store/maintain! :credential (:storage eng) tx :seat (str (:id seat-row))
                                    (assoc (:data row) :fire_keys kept)
                                    (:next-flip-at row))
                true))))))))

;; ── the key of a fire a pull link holds (ticket a25950b4) ────────────

(defn rewrite-fire-key!
  "Rewrite the entry `held` ({:seat :hash}) names on its seat row, in
  one transaction FOR UPDATE, as `spend-fire-key!` reads it: `f` of the
  entry, or nil to take it off. → true when the entry was there."
  [eng {:keys [seat hash]} f]
  (when (and seat hash (get (inv/resources eng) :seat))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :seat (str seat)
                                        {:for-update true})]
          (let [held (get-in row [:data :fire_keys])
                same? #(= (str (:hash %)) (str hash))]
            (when (some same? held)
              (store/maintain! :credential (:storage eng) tx :seat (str seat)
                                  (assoc (:data row) :fire_keys
                                         (into [] (keep #(if (same? %) (f %) %))
                                               held))
                                  (:next-flip-at row))
              true)))))))

(defn- lease-fire-sitting!
  "Lease the sitting a pull fire opened (`open-fire-sitting!`, ticket
  9f108b88) until `until`, while it is open and no session claimed it:
  a MAINTENANCE write, as its birth stamped the sit deadline. → true
  when it was written."
  [eng sitting-id ^Instant until]
  (when (and (some-> sitting-id str not-empty) until
             (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (when-some [r (store/load-row st tx :sitting (str sitting-id)
                                        {:for-update true})]
            (when (and (= :open (:state r))
                       (nil? (some-> (get-in r [:data :connector_session])
                                     str not-empty)))
              (store/maintain! :stamp st tx :sitting (str sitting-id)
                               (assoc (:data r) :expires_at (str until)) nil)
              true)))))))

(defn- end-fire-sitting!
  "End the sitting a pull fire opened when its key is revoked unspent
  (ticket 9f108b88): through `abandon`, as the engine, so it carries no
  outcome. A dropped fire is no run, and is not counted as `never_sat`.
  → true when it was ended."
  [eng sitting-id]
  (when (and (some-> sitting-id str not-empty)
             (get (inv/resources eng) :sitting))
    (try (inv/invoke! eng :sitting (str sitting-id) :abandon nil
                      {:principal seats-actor})
         true
         (catch clojure.lang.ExceptionInfo _ nil))))

(defn queue-fire-key!
  "Mark the key of a fire a pull link holds: the entry keeps the fire's
  id as `:pull_fire`, and answers until `until` at least (the link's
  keep), so the key still opens the seat when a laptop that slept takes
  the fire. The sitting the fire opened is leased to `until` too, so it
  is not missed while the fire waits (ticket 9f108b88). → true when the
  entry was there."
  [eng held fire-id ^Instant until]
  (let [sitting (volatile! nil)
        found (rewrite-fire-key! eng held
                                 (fn [e]
                                   (vreset! sitting (:sitting e))
                                   (let [was (instant-of (:expires_at e))]
                                     (assoc e
                                            :pull_fire (str fire-id)
                                            :expires_at (str (if (and was (.isAfter ^Instant was until))
                                                               was
                                                               until))))))]
    (when found (lease-fire-sitting! eng @sitting until))
    found))

(defn take-fire-key!
  "Stamp `:taken_at` on the key of a pull fire a localfire took at `at`:
  the missed-fire sweep counts from here, and the sitting the fire
  opened is leased again to `at` plus `sit-deadline-seconds` (ticket
  9f108b88). → true when the entry was there."
  [eng held ^Instant at]
  (let [sitting (volatile! nil)
        found (rewrite-fire-key! eng held
                                 (fn [e]
                                   (vreset! sitting (:sitting e))
                                   (assoc e :taken_at (str at))))]
    (when found
      (lease-fire-sitting! eng @sitting (.plusSeconds at (long sit-deadline-seconds))))
    found))

(defn revoke-fire-key!
  "Take the key of a pull fire off its seat row unspent: the fire was
  dropped, or never queued. The sitting it opened ends with it, and not
  as a run that never sat (`end-fire-sitting!`). → true when the entry
  was there."
  [eng held]
  (let [sitting (volatile! nil)
        found (rewrite-fire-key! eng held
                                 (fn [e] (vreset! sitting (:sitting e)) nil))]
    (when found (end-fire-sitting! eng @sitting))
    found))

(defn keep-fire-key!
  "Keep the trace of a spent firing's key on the sitting it opened: its
  hash, never the key (R-12.37). A MAINTENANCE write, `spend-fire-key!`'s
  own spelling and its reason. Only an OPEN sitting takes the trace, and
  it answers only while the sitting stays open, so the close, the
  tally-driven close, the abandon and the sweep all leave a key that
  answers nothing. → true when the trace was written."
  [eng sitting-id key]
  (when-some [h (key-hash key)]
    (when (and sitting-id (get (inv/resources eng) :sitting))
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :sitting
                                          (str sitting-id) {:for-update true})]
            (when (= :open (:state row))
              (store/maintain! :credential (:storage eng) tx :sitting (str sitting-id)
                                  (assoc (:data row) :fire_key_hash h) nil)
              true)))))))

(def fire-keys-schema
  "What the seat keeps of the keys its firings carried (R-12.37): one
  entry for each key the engine minted and no sit has spent yet.

  THE HASH AND NOT THE KEY. The key is on the wire one time, in the
  fire text, and the engine stores nothing a reader could present. The
  sit hashes what it was given and compares; a house whose seat rows
  leaked would leak no key.

  Worn by the row alone. Neither the create door nor the restate
  declares the field, and a closed map refuses an unknown key: that
  omission is the fence, the way the schedule fences its link."
  [:vector
   [:map
    [:hash {:x-display
            {:label "The hash of one key"
             :help "The SHA-256 of the key one fire text carried, base64url. The key itself is on the wire one time and is stored nowhere."}}
     [:string {:min 1 :max 128}]]
    [:expires_at {:x-display
                  {:label "When it stops answering"
                   :help "One sitting_idle_seconds after the fire. A key no session spent stops answering at this moment."}}
     :waymark/instant]
    ;; the instant of the fire itself, so the clock sweep can tell a
    ;; run that never sat from one that is still starting
    ;; (`wakes/sweep-missed!`). Optional: an entry held before the
    ;; field existed carries none, and the sweep leaves it to expire.
    [:fired_at {:optional true
                :x-display
                {:label "When it was fired"
                 :help "The instant of the fire that minted this key. A key still unspent some minutes after it is a run that never sat."}}
     :waymark/instant]
    ;; the walk row the fire text named, when it named one, so the sit
    ;; of a seat that runs several sittings at once hands that row and
    ;; claims it, or says another sitting holds it (`fire-key-row`)
    [:row {:optional true
           :x-display
           {:label "The row it named"
            :help "The id of the walk row this fire's text named. The sit that spends the key hands that row, unless another open sitting already holds it, of this seat or of another seat that walks the same kind."}}
     [:string {:min 1 :max 128}]]
    ;; a fire held on a pull link (ticket a25950b4): its id in the
    ;; link's queue, and when a localfire took it, since the missed-fire
    ;; sweep counts from the take and passes a fire nobody took by
    [:pull_fire {:optional true
                 :x-display
                 {:label "The pull fire it rides"
                  :help "The id of the fire a pull runner link holds for a localfire. Until one takes it, the key is not a run that never sat."}}
     [:string {:min 1 :max 128}]]
    [:taken_at {:optional true
                :x-display
                {:label "When a localfire took it"
                 :help "The instant a localfire took this pull fire. A key still unspent some minutes after it is a run that never sat."}}
     :waymark/instant]
    ;; the sitting this fire opened before any session sat in it
    ;; (ticket 9100c7b9): the sit that spends the key takes it, and
    ;; `expire` ends it as missed when nobody did
    [:sitting {:optional true
               :x-display
               {:label "The sitting it opened"
                :help "The id of the sitting this fire opened. The run's sit takes it; a fire nobody sits on ends it at the sit deadline, as missed."}}
     [:string {:min 1 :max 128}]]
    [:not_started {:optional true
                   :x-display
                   {:label "Why its run did not start"
                    :help "What a localfire said when it stopped before it knew whether the run started. The sitting the fire opened carries it when it ends."}}
     [:string {:min 1 :max 240}]]]])
