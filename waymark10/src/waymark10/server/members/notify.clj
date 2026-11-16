(ns waymark10.server.members.notify
  "The presence curtain's guard and set_notify's, split out of
  waymark10.server.members so that namespace keeps room under its
  ns-size entry (ticket 1af717d2). The member resource wears these on
  :draw_curtain, :open_curtain and :set_notify."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the presence curtain (waymark-tti.4) ────────────────────────────

;; the curtain is your own hand: drawing or opening a presence
;; curtain is SELF-SERVICE — the caller must BE the member row (its
;; principal id is the row's id, or the subject a :bind wrote:
;; gate!'s own two resolutions, so a bound member is not locked out
;; of its own curtain). One valve: a recovery-admin HUMAN may also
;; draw or open (the household recovery lever — e.g. a member whose
;; only client is wedged BEHIND its curtain), with the role AND the
;; type both read from the admin's own durable MEMBER ROW, never the
;; token (the reentry-minters precedent: an absent actor_type claim
;; fails open toward :human, so a claim is no wall on a privacy
;; switch). :system principals are refused even wearing the role —
;; the engine's own actors must not reach through a member's privacy
;; switch: a system path would be one ctx :invoke away from any
;; handler. Not hidden: a stranger's refusal should read as an
;; honest no, and absence from the presence board stays legible as
;; "curtained or away — the member row says which".
(defn- own-hand
  "The own-hand verdict curtain-is-your-own-hand and
  notify-is-your-own-hand share: the member themself, or a
  recovery-admin human read from its own member row; never :system."
  [row ctx]
  (let [p (:principal ctx)
        pid (:id p)
        read' (:read ctx)
        find' (:find ctx)]
    (cond
      ;; never the engine's own actors, whatever roles they carry —
      ;; and judged FIRST, so a system principal whose id happens to
      ;; collide with a member row id gains nothing from the
      ;; own-hand branch below
      (= :system (:type p)) (t/deny)
      ;; your own row: by id, or by the subject a binding wrote
      (and (some? pid)
           (or (= pid (:id row))
               (= pid (get-in row [:data :subject])))) (t/allow)
      ;; the pure render probe carries no read hooks — advertise
      ;; optimistically there (reentry-minters' precedent); the real
      ;; invoke, which DOES carry them, is the wall
      (or (nil? read') (nil? find')) (t/allow)
      :else
      ;; the recovery valve: role + type from the caller's own
      ;; durable member row (by id, or by bound subject)
      (let [own (or (read' :member pid)
                    (first (find' :member {:subject pid} {:limit 1})))]
        (if (and own
                 (= "human" (get-in own [:data :actor_type]))
                 (contains? (set (get-in own [:data :roles]))
                            "recovery-admin"))
          (t/allow)
          (t/deny))))))

(g/defguard curtain-is-your-own-hand
  {:reads [:principal :member]
   :explain "The curtain is its member's own to draw or open; only that member themself (or a recovery-admin human, the household valve) may touch it."}
  [row _inp ctx]
  (own-hand row ctx))

;; how a member is reached is their own hand too: set_notify names the
;; chat a notice rule sends into, so a caller who could write another
;; member's :notify could redirect that member's notices to their own
;; chat. The curtain's wall, the curtain's valve.
(g/defguard notify-is-your-own-hand
  {:reads [:principal :member]
   :explain "How a member is reached is that member's own to set; only the member themself (or a recovery-admin human, the household valve) may point their notices somewhere."
   :open "The wall is about who: the member sets how they are reached, and no field of this door makes anyone else that member."}
  [row _inp ctx]
  (own-hand row ctx))

;; what set_notify stores is read by every notice rule that addresses
;; this member, and a wrong word there only shows as a silent skip. So
;; the mistake is refused where it is made: a notifier that names no
;; live notifier row, a whole notify map pasted into the input, a
;; quiet time that is no clock time (that one stays in members, beside
;; the clock-time pattern it shares with hh-mm).
(defn- retired? [row] (= "retired" (some-> (:state row) name)))

(defn- live-notifiers-named
  "The live notifiers as \"name (id)\", for a refusal to list."
  [ctx]
  (or (not-empty
       (str/join ", " (for [n ((:find ctx) :notifier {} {:limit 50})
                            :when (not (retired? n))]
                        (str (get-in n [:data :name]) " (" (:id n) ")"))))
      "none yet"))

(g/defguard notify-names-a-notifier
  {:reads [:notifier]
   :vars [:notifier :notifiers]
   :explain "{notifier} names no live notifier row; notify.notifier is a notifier's id. The notifiers: {notifiers}."
   :open "The way out is in this same form: name one of the notifiers the sentence lists, by its id. They are the notifiers collection, one query away."}
  [_row inp ctx]
  (let [nid (get-in inp [:notify :notifier])
        read' (:read ctx)]
    (if (or (nil? nid) (nil? read') (nil? (:find ctx)))
      (t/allow)
      (let [n (read' :notifier (str nid))]
        (if (and n (not (retired? n)))
          (t/allow)
          (t/deny {:vars {:notifier (str nid)
                          :notifiers (live-notifiers-named ctx)}}))))))

(g/defguard notify-input-is-the-tools-own
  {:vars [:keys]
   :explain "The input is the notifier tool's own arguments for this person, e.g. {\"chat_id\": \"42\"}, not a whole notify map; it carried {keys}."
   :open "The way out is in this same form: notify.input carries only the notifier tool's own arguments, and notify.notifier beside it names the notifier."}
  [_row inp _ctx]
  (let [in (get-in inp [:notify :input])]
    (if-some [ks (seq (filter #(contains? in %) [:notifier :input]))]
      (t/deny {:vars {:keys (str/join ", " (map name ks))}})
      (t/allow))))
