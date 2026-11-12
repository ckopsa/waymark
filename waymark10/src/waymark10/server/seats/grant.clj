(ns waymark10.server.seats.grant
  "The seat grant a sitter wears and the sitting one fire opens (ticket
  9100c7b9): the standing grant for a seat, its mint and the retiring of
  the older ones beside it, and the reads and writes on a fire key's
  sitting.

  Moved out of `waymark10.server.seats` (ticket 10a0be5d, round eight).
  The model and the sitter these read stay there, one copy for the fire
  and the sit (`waymark10.server.mcp`)."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.leases :as leases]
            [waymark10.server.members :as members]
            ;; the seat's model and its sitter, read here and by the sit
            [waymark10.server.seats :refer [decoded-row seat-model sitter-display sitter-id]]
            [waymark10.server.seats.bill :refer [fire-entry-of]]
            [waymark10.server.seats.fire :refer [rewrite-fire-key! sit-deadline-seconds]]
            [waymark10.server.seats.inbox :refer [now-of]]
            [waymark10.server.seats.keys :refer [fire-key-entry]]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(defn standing-seat-grant
  "The grant this sitter already holds FOR THIS SEAT, or nil.
  `standing-grant-for` answers the newest that still confers or could
  — accepted first, then a still-offered one, unexpired by the live
  clock — and a grant citing a different seat is not this seat's, so
  a sitter moved between offices mints again rather than sitting in
  the old one.

  A GRANT NEAR ITS END IS NOT STANDING (ticket acc89add): one whose
  `expires_at` is less than half the seat's `standing_ttl_seconds`
  away would expire mid-run and take every power with it, so the sit
  mints afresh. The old grant is left to expire on its own clock: an
  open sitting of another run may still wear it. A seat with no ttl
  reuses as before. The near-end test is put INSIDE the pick, not after
  it: the accepted old grant is otherwise always picked over the fresh
  offered one, and every sit after it would mint again."
  [eng sitter-id seat ^Instant now]
  (let [ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        long-enough? (fn [g]
                       (leases/half-left? now (get-in g [:data :expires_at]) ttl))]
    (when-some [g (grants/standing-grant-for eng sitter-id long-enough?)]
      (when (= (str (:id seat)) (str (get-in g [:data :seat])))
        g))))

(defn- retire-seat-grants!
  "Revoke this sitter's older full grants FOR THIS SEAT that no open
  sitting wears, once `fresh` is minted beside them (ticket b72a4943):
  a near-end grant left to its own clock made every seat collect a new
  standing grant every few days. One an open sitting of another run
  still wears is left alone, and the next mint retires it. Only a grant
  `standing-seat-grant` would no longer pick (less than half its ttl
  left) is retired: a fresh one is a simultaneous sit's, minted before
  its sitting opens, and revoking it left that sit walking nothing. →
  the count revoked."
  [eng sitter-id seat fresh ^Instant now]
  (let [st (:storage eng)
        seat-id (str (:id seat))
        ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        [mine worn] (store/with-tx st
                      (fn [tx]
                        [(store/query-rows st tx :grant {:audience (str sitter-id)}
                                           {:limit 100})
                         (when (get (inv/resources eng) :sitting)
                           (into #{} (map #(str (get-in % [:data :grant])))
                                 (store/query-rows st tx :sitting
                                                   {:seat seat-id :state :open}
                                                   {:limit 100})))]))
        stale (filter #(and (not= (str (:id fresh)) (str (:id %)))
                            (contains? #{"offered" "accepted"} (name (:state %)))
                            (= seat-id (str (get-in % [:data :seat])))
                            (not (true? (get-in % [:data :substitute])))
                            (pos? ttl)
                            (not (leases/half-left? now (get-in % [:data :expires_at]) ttl))
                            (not (contains? worn (str (:id %)))))
                      mine)]
    (count
     (filter (fn [g]
               (try (inv/invoke! eng :grant (str (:id g)) :revoke {}
                                 {:principal grants/approvals-actor})
                    true
                    (catch clojure.lang.ExceptionInfo _ false)))
             stale))))

(defn mint-seat-grant!
  "The seat grant, minted the way an approved seat ask mints one
  (grants/approval-effects!, R-5.4): the approvals actor, the sitter
  as audience, the seat as a ref, no scope at all, and the seat's own
  `standing_ttl_seconds` as the expiry. The sitter's older grants for
  the seat that no open sitting wears are revoked beside it
  (`retire-seat-grants!`), so a seat holds one standing grant.

  It is left OFFERED, deliberately: accepting is the audience's own
  act (the accept guard's rule), and `worn-visibility` performs it on
  the sitter's first request the way the guest door does. The key was
  the person's consent to the seat; the acceptance is the sitter's."
  [eng sitter-id seat ^Instant now]
  (let [ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
        fresh (:row (inv/create! eng :grant
                                 (cond-> {:audience (str sitter-id)
                                          :seat (str (:id seat))
                                          :substitute false}
                                   (pos? ttl)
                                   (assoc :expires_at (str (.plusSeconds now ttl))))
                                 {:principal grants/approvals-actor}))]
    (retire-seat-grants! eng sitter-id seat fresh now)
    fresh))

(defn fire-promise
  "The id of the promise a fire's text names, or nil: a promise's wake
  is JSON that carries `promise` (R-P.10.1). Prose names none."
  [text]
  (when-some [s (some-> text str str/trim not-empty)]
    (when (str/starts-with? s "{")
      (let [m (try (wire/read-json s) (catch Exception _ nil))]
        (when (map? m) (some-> (:promise m) str not-empty))))))

(defn open-fire-sitting!
  "The sitting ONE fire opens before any session sits in it, or nil when
  this fire opens none and its sit opens the sitting as before: a seat
  with no instructions mints no key, a seat that names no model opens
  no sitting, and a seat whose sitter row does not exist yet is left to
  its first sit, which mints the sitter acting for the person behind it.

  It is born as the sit's would be: the sitter as its principal, with
  no person behind it yet, under the seat grant (`standing-seat-grant`,
  else `mint-seat-grant!`). Its lease is the sit deadline and not the
  idle limit: `expires_at` is the fire plus `sit-deadline-seconds`, so a
  fire nobody sits on lapses exactly when it is missed, and `expire`
  ends it (`missed-onto`). The sit that spends the fire's key takes it
  (`claim-sitting!` with stamps), and its claim lets the lease again.

  `text` is the fire's own. A promise's wake names its promise there,
  and the sitting keeps it as `promise` (R-P.10.1, `fire-promise`)."
  [eng seat-row at & [text]]
  (when (and seat-row
             (some-> (get-in seat-row [:data :instructions]) str not-empty)
             (get (inv/resources eng) :seat)
             (get (inv/resources eng) :sitting))
    (when-some [model (seat-model eng seat-row)]
      (let [sid (sitter-id seat-row)]
        ;; a first-ever fire mints no sitter with nobody behind it
        (when (decoded-row eng :member sid)
          (let [st (:storage eng)
                ^Instant at (or (instant-of at) (now-of eng))
                now (now-of eng)
                sitter (t/principal {:id sid :type :agent
                                     :display (sitter-display seat-row)
                                     :model (some-> (get-in model [:data :name])
                                                    str not-empty)
                                     :roles (members/held-roles eng sid)})
                grant (or (standing-seat-grant eng sid seat-row now)
                          (mint-seat-grant! eng sid seat-row now))
                row (:row (inv/create! eng :sitting
                                       {:seat (str (:id seat-row))
                                        :model (str (:id model))
                                        :grant (str (:id grant))}
                                       {:principal sitter}))
                ends (str (.plusSeconds at (long sit-deadline-seconds)))
                promise-id (fire-promise text)]
            ;; the birth stamped the idle limit; the fire's lease is the
            ;; sit deadline, and a promise's wake stamps its promise
            (store/with-tx st
              (fn [tx]
                (when-some [r (store/load-row st tx :sitting (str (:id row))
                                              {:for-update true})]
                  (store/maintain! :stamp st tx :sitting (str (:id row))
                                   (cond-> (assoc (:data r) :expires_at ends)
                                     promise-id (assoc :promise promise-id))
                                   nil))))
            (cond-> (assoc-in row [:data :expires_at] ends)
              promise-id (assoc-in [:data :promise] promise-id))))))))

(defn fire-key-sitting
  "The id of the sitting the fire that minted `key` opened, or nil
  (`open-fire-sitting!`). A READ, like `fire-key-row`: the sit asks it
  BEFORE it spends the key."
  [eng seat-row key]
  (when seat-row
    (some-> (fire-key-entry seat-row key ((:now-fn eng))) :sitting str not-empty)))

(defn fire-held-sittings
  "The ids of the sittings this seat's unspent fire keys opened. Each is
  its own fire's run's, and no other sit reuses it."
  [seat-row]
  (into #{} (keep #(some-> (:sitting %) str not-empty))
        (get-in seat-row [:data :fire_keys])))

(defn mark-not-started-fire-key!
  "Write on the key `held` ({:seat :hash}) names that its localfire
  stopped before it knew whether the run started, and why. The sitting
  the fire opened ends at the sit deadline through `expire`, which reads
  it (`missed-onto`). → true when the entry was there."
  [eng held reason]
  (let [s (or (some-> reason str not-empty) "no reason given")]
    (rewrite-fire-key! eng held
                       #(assoc % :not_started (subs s 0 (min 240 (count s)))))))

(defn drop-fire-key-of-sitting!
  "Take off the seat row the unspent key entry that names `sitting-id`
  as its fire's sitting, in one transaction FOR UPDATE, so a late sit
  with its key is refused (`wakes/missed-expired!`). → the entry, or nil."
  [eng seat-id sitting-id]
  (when (get (inv/resources eng) :seat)
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :seat (str seat-id)
                                        {:for-update true})]
          (when-some [entry (fire-entry-of row sitting-id)]
            (store/maintain! :credential (:storage eng) tx :seat (str seat-id)
                                (assoc (:data row) :fire_keys
                                       (into [] (remove #(= entry %))
                                             (get-in row [:data :fire_keys])))
                                (:next-flip-at row))
            entry))))))
