(ns waymark10.server.seats.keys
  "The seat key primitives, carved out of `waymark10.server.seats`
  (ticket 7bdd1a37, carve 1 of 4): the hash a row keeps, the constant
  time compare, the live entries of a seat's fire keys, the key a
  fire's text carries, and the active seat a standing key opens. Round
  three (ticket b44dbe85) brought down the seat a token names
  (`seat-named`) and whether a key is the seat's own or its chair's
  (`standing-key?`).

  Nothing here writes. The doors that mint, spend and revoke a key
  stay in `seats`, which delegates each var below by name so every
  `seats/` caller keeps resolving."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            [waymark10.server.seats.pools :refer [chair-of]]
            [waymark10.server.store :as store])
  (:import (java.time Instant)))

(defn key-matches?
  "Does the key the caller presented hash to this row's
  `sitter_key_hash` (R-22.6)?

  `keys/matches?`, constant time over the hashes, so a caller cannot
  walk a key one character at a time off the clock. A row holding no
  key matches nothing, and neither does a blank key."
  [row key]
  (keys/matches? (get-in row [:data :sitter_key_hash]) key))

(defn seat-by-key
  "The ACTIVE seat whose `sitter_key_hash` is this key's hash, or nil.

  The compare is `keys/matches?`, `keys/equal?` over UTF-8 bytes — constant
  time in the length of the two arrays, so a caller cannot walk the
  key one character at a time off the clock. Every active seat is
  read, which is honest arithmetic here: seats are an office per kind
  of judgment and a house has a handful, `sitter_key_hash` is :secret and
  therefore may never be :filterable (resource/check-secret!: a filter
  is a value oracle over what the projection conceals), and the read
  is one query behind a door the MCP surface calls once per session.

  A blank key answers nil without touching storage: a seat that was
  never offered a key holds nil, and nil must never match nil."
  [eng key]
  (when-some [key (some-> key str not-empty)]
    (when (get (inv/resources eng) :seat)
      (store/with-tx (:storage eng)
        (fn [tx]
          (->> (store/query-rows (:storage eng) tx :seat {:state :active}
                                 {:limit 500})
               (filter #(key-matches? % key))
               first))))))

(def key-ceiling
  "How many unspent keys one seat keeps. A seat fires far fewer times
  than this inside one idle window, so the ceiling never drops a key a
  live session is about to present. It is the fence against a row that
  grows without a bound when a Routine is dark and nobody sits."
  32)

(defn key-hash
  "The SHA-256 of one key, base64url, unpadded.

  IT IS THE ONLY THING THE ROW KEEPS. The key is on the wire one time,
  in the fire text, and a seat row read by anybody at all hands over
  nothing a session could present. A blank key hashes to nil, so a row
  that holds no key matches nothing."
  [key]
  (keys/hash-b64 key))

(defn live-keys
  "The entries of this row that still answer at `now`: the keys no sit
  has spent, whose moment has not passed. An entry the engine cannot
  date is dropped, because a key it cannot expire is a key it must not
  answer."
  [row ^Instant now]
  (into []
        (filter (fn [e]
                  (when-some [^Instant at (instant-of (:expires_at e))]
                    (.isAfter at now))))
        (get-in row [:data :fire_keys])))

(defn fire-key-entry
  "The live entry of this row that answers `key` at `now`, or nil. The
  compare is `keys/matches?`, constant time, for `key-matches?`'s own
  reason."
  [row key ^Instant now]
  (first (filter #(keys/matches? (:hash %) key)
                 (live-keys row now))))

(defn fire-key-of-text
  "The seat and the key hash a fire's text carries — the seat line and
  the key under it (`schedules/fire-text`) — as {:seat :hash}, or nil
  for a text with no key. A pull link holds the text alone, so this is
  how it finds the entry its fire minted."
  [text]
  (when-some [[_ seat key] (re-find #"(?m)^Seat: (\S+) [^\n]*\nKey: (\S+)"
                                    (str text))]
    {:seat seat :hash (key-hash key)}))

(defn seat-named
  "The ACTIVE seat this token names, or nil — an id first, then the
  seat's own name (R-4 of waymark-fp62.7.23).

  The id first because a Routine handed one spells it exactly, and
  the name because that is what a person writes into a seat's
  instructions. ACTIVE only, `seat-by-key`'s own rule and its reason:
  a parked, merged or retired seat serves nothing, and a sit that
  landed in one would be a sitting nobody can spend."
  [eng named]
  (when-some [named (some-> named str str/trim not-empty)]
    (when (get (inv/resources eng) :seat)
      (store/with-tx (:storage eng)
        (fn [tx]
          (let [st (:storage eng)
                by-id (store/load-row st tx :seat named {})]
            (or (when (= :active (:state by-id)) by-id)
                (first (store/query-rows st tx :seat
                                         {:name named :state :active}
                                         {:limit 1})))))))))

(defn standing-key?
  "Is this key the seat's OWN, or its chair's?

  TWO STANDING KEYS OPEN ONE SEAT. The seat's own `sitter_key` is the
  first, and it is R-12.12 unchanged. The second is the CHAIR'S: the
  `sitter_key` of the first model in `held_for`. That second one is
  what makes one Routine for each model possible. The model's key sits
  in every seat that model is the chair of, and the name says which
  seat this firing is.

  The two are asked as one question because the sit asks them for one
  reason. A standing key is spent by nothing. A firing's key (R-12.37)
  is spent by the sit that presents it. The compare is `key-matches?`,
  constant time, for its own reason."
  [eng seat-row key]
  (boolean
   (when-some [key (some-> key str not-empty)]
     (when seat-row
       (or (key-matches? seat-row key)
           (when-some [chair (and (get (inv/resources eng) :model)
                                  (chair-of seat-row))]
             (some-> (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/load-row (:storage eng) tx :model
                                         chair {})))
                     (key-matches? key))))))))
