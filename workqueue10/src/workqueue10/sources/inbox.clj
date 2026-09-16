(ns workqueue10.sources.inbox
  "The inbox queue, filled with no tokens (docs/spec-seat.md § 13.8,
  'The queue fills with no tokens'): one Gate listing per beat, one
  `inbox_item` row per message id nobody has minted yet, and NOTHING
  a model had to read to decide it. The seat's economy rests on this
  namespace being dumb — a queue a frontier session pays to build is
  a queue nobody can afford hourly.

  THE EMAIL TWIN OF tgram. The same half both thread sources share
  (`workqueue10.sources.gate-chat`): the engine's own Gate client,
  reused across passes, the structured/parts fallback `rows` keeps,
  and the same deliberate route PAST `invoke-for` — a sync pass has
  no caller, no principal and no grant to judge, so the leash lands
  where it belongs (the engine holds Gate's reach; the ROWS are
  grant-projected like any kind). Read gate_chat's header for the
  whole argument; it is not restated here.

  ONE TOOL, ONE CALL A PASS: `emila__inbox` under the `email.read`
  power (waymark10.server.gate-proxy/tool-capability). The tool name,
  the window and the folder are configurable, because emila's listing
  is the one wire in this confluence that is NOT pinned anywhere in
  this repo — see `message->doc` for the shape this namespace assumes
  and how little a rig that spells a key differently costs.

  HEADERS ONLY, and this is the line the kind exists to draw. Four
  fields are read: the message's id at the rig, its subject, its
  sender and when it arrived. A body, a snippet, a preview, a
  folder — whatever else the listing carries is read by nothing here
  and can never reach a row. The model opens the message itself,
  through the `email.read` power at research time, and the house pays
  those tokens once per message it actually decided to look at.

  NEWSLETTERS AND RECEIPTS DO NOT MINT. A message carrying a
  List-Unsubscribe header is a broadcast, not a request, and week
  one's nine corrections are what put that rule here rather than in a
  charter sentence every firing pre-loads (§ 13.8's table). The count
  is logged every pass, because a filter nobody counts is a filter
  nobody can correct.

  IDEMPOTENT ACROSS PASSES, twice over: the wiring's `:exists?` (or a
  `:known-ids` set) is the durable answer — the engine's own rows,
  which outlive this process — and `source` keeps the ids it minted
  in an atom beside it, so a pass whose engine read fails still
  cannot mint a message twice in one process. The listing is a
  WINDOW, deliberately wider than one beat needs, so the same message
  is offered pass after pass and the dedupe is the whole design.

  AN UNREACHABLE GATE THROWS. The pass is the thread sources'
  posture: the rows already stored keep serving, the beat is lost,
  and the next one tries again. Nothing here degrades into minting
  half a queue."
  (:require [clojure.string :as str]
            [workqueue10.sources.gate-chat :as gc])
  (:import (java.time Instant OffsetDateTime ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def default-tool
  "Gate's inbox listing. Configurable because this one wire is not
  pinned in this repo: `emila__list_messages` is the same power's
  other listing, and a deployment whose rig prefers it says so
  without a deploy."
  "emila__inbox")

(def default-limit
  "How many headers a listing asks for. The listing IS the window
  (tgram's sentence, same reason): the rig takes no `since=`, so a
  window that always overlaps what the last pass saw is how a message
  that arrived between beats is never missed. The dedupe is what
  makes the overlap free."
  40)

(def default-every-seconds
  "How often the pass runs when the wiring names no cadence. Fifteen
  minutes: the inbox is a queue a person reads hourly at best, and a
  listing costs one call."
  900)

(def why
  "What Gate's own log records about this pass. A read tool takes
  `__why` optionally; saying it anyway is how the household can tell,
  at Gate, that waymark asked for headers and not for mail."
  (str "waymark: listing the inbox headers (ids, subjects, senders "
       "and times — never a body)"))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "workqueue10 inbox: " parts))))

;; ── the wire ────────────────────────────────────────────────────────

(defn call
  "One listing read at Gate → the seq of entry maps. `gc/rows` does
  the reading (structured first, the content parts as the fallback, a
  refusal thrown), and the `__why` is this source's own — gc/call's
  is the thread mirror's sentence and would be a lie here."
  [rpc-fn tool args]
  (gc/rows (rpc-fn "tools/call"
                   {:name tool :arguments (assoc args :__why why)})))

;; ── the translation ─────────────────────────────────────────────────

(defn- present
  "The first key in ks the entry answers with something worth having:
  no nil, no blank string, no false."
  [m ks]
  (some (fn [k]
          (let [v (get m k)]
            (when (and (some? v) (not (false? v))
                       (or (not (string? v)) (not (str/blank? v))))
              v)))
        ks))

(defn- party-string
  "An address as a person can read it: a rig that answers a map gets
  \"Name <addr>\", one that answers a string gets its string, one
  that answers a list gets its first entry (the sender is one)."
  [v]
  (cond
    (map? v) (let [nm (str/trim (str (or (:name v) (:display_name v)
                                         (:displayName v) "")))
                   addr (str/trim (str (or (:email v) (:address v)
                                           (:addr v) "")))]
               (cond (and (seq nm) (seq addr)) (str nm " <" addr ">")
                     (seq nm) nm
                     :else addr))
    (sequential? v) (some-> (first v) party-string)
    :else (str/trim (str v))))

(defn- try-parse [f]
  (try (f) (catch Exception _ nil)))

(defn instant-string
  "Whatever the rig calls a time → the canonical RFC 3339 instant the
  schema decodes, or nil when nothing here can read it. Canonical
  rather than passed through, so a rig that changes its spelling does
  not change what a row says (tgram's rule).

  Five spellings are read: an RFC 3339 instant, the space-separated
  \"2026-09-15 14:02:11+00:00\" both chat rigs speak, an offset
  datetime, mail's own RFC 1123 (\"Tue, 15 Sep 2026 08:02:11 -0600\"),
  and epoch seconds or milliseconds as a number or a digit string."
  [v]
  (cond
    (nil? v) nil
    (instance? Instant v) (str v)
    (number? v) (let [n (long v)]
                  (str (if (> n 99999999999)
                         (Instant/ofEpochMilli n)
                         (Instant/ofEpochSecond n))))
    :else
    (let [s (str/trim (str v))]
      (when-not (str/blank? s)
        (let [iso (str/replace s #"^(\d{4}-\d{2}-\d{2}) " "$1T")]
          (or (try-parse #(str (Instant/parse iso)))
              (try-parse #(str (.toInstant (OffsetDateTime/parse iso))))
              (try-parse #(str (.toInstant
                                (ZonedDateTime/parse
                                 s DateTimeFormatter/RFC_1123_DATE_TIME))))
              (when (re-matches #"\d{9,}" s)
                (instant-string (parse-long s)))))))))

(def ^:private id-keys
  [:message_id :messageId :message-id :id :uid :msg_id :msgId])

(def ^:private subject-keys [:subject :title :headline])

(def ^:private sender-keys
  [:sender :from :from_address :fromAddress :from_name :fromName
   :from_email :address])

(def ^:private received-keys
  [:received_at :receivedAt :date :received :sent_at :timestamp :time
   :internal_date :internalDate :datetime])

(defn message-id
  "The mail's id at the rig — the one field a message cannot be
  minted without, because it is what makes a second pass free."
  [msg]
  (some-> (present msg id-keys) str str/trim not-empty))

(defn- flagged?
  "Is this header value a value at all? A false, a nil or a blank is
  a header the rig mentioned and did not carry."
  [v]
  (and (some? v) (not (false? v)) (not (str/blank? (str v)))))

(defn- header-name? [k]
  (= "listunsubscribe" (str/replace (str/lower-case (str k)) #"[^a-z]" "")))

(defn list-unsubscribe?
  "Does this message carry a List-Unsubscribe header — the broadcast
  signal that keeps newsletters and receipts out of the queue?

  Read from wherever the rig puts headers: a top-level
  `list_unsubscribe` / `List-Unsubscribe` field (a string or a plain
  boolean), a `:headers` MAP keyed by header name, or a `:headers`
  LIST of {name, value} pairs — or of raw header lines. Punctuation
  and case are normalized away, so `List-Unsubscribe`,
  `list_unsubscribe` and `listUnsubscribe` are one header."
  [msg]
  (let [hdrs (:headers msg)]
    (boolean
     (or (some (fn [[k v]] (and (header-name? k) (flagged? v))) msg)
         (cond
           (map? hdrs)
           (some (fn [[k v]] (and (header-name? k) (flagged? v))) hdrs)

           (sequential? hdrs)
           (some (fn [h]
                   (if (map? h)
                     (and (header-name? (or (:name h) (:key h) (:header h)))
                          (flagged? (or (:value h) (:v h) true)))
                     (header-name? (first (str/split (str h) #":" 2)))))
                 hdrs)

           :else nil)))))

(defn message->doc
  "One listing entry → the `inbox_item` document: four fields, and
  the fourth is a time rather than a word anybody wrote.

  THE ASSUMED SHAPE. Gate is external and emila's listing is pinned
  nowhere in this repo, so every field is read from the most likely
  keys and a rig that spells one differently costs that FIELD, never
  the pass:

      {:id \"18f3c…\" :subject \"Registration closes Friday\"
       :from {:name \"Ada Park\" :email \"ada@school.org\"}
       :date \"2026-09-15T14:02:11Z\"
       :headers {:List-Unsubscribe \"<mailto:…>\"} :body \"…\"}

  - message_id ← :message_id / :messageId / :id / :uid / :msg_id
  - subject    ← :subject / :title
  - sender     ← :sender / :from / :from_address / :from_name; a map
                 spells \"Name <addr>\", a list gives its first entry
  - received_at ← :received_at / :date / :received / :timestamp /
                 :internal_date, in any spelling `instant-string`
                 reads; `fallback` (the pass's clock) when the rig
                 says nothing a clock can read, because a queue that
                 drops the mail it cannot date is worse than one that
                 stamps it on arrival.

  A missing subject or sender becomes a plain placeholder rather than
  a refused mint — the message is still the person's to triage, and a
  blank is not more honest than \"(no subject)\".

  AND WHAT IS NOT HERE: everything else the entry carries. No body,
  no snippet, no preview, no flags, no folder. The body is the
  model's to fetch through the `email.read` power at research time;
  it never becomes a row, in a shorter coat or otherwise."
  [msg fallback]
  {:message_id (message-id msg)
   :subject (or (some-> (present msg subject-keys) str str/trim not-empty)
                "(no subject)")
   :sender (or (some-> (present msg sender-keys) party-string not-empty)
               "(unknown sender)")
   :received_at (or (instant-string (present msg received-keys)) fallback)})

;; ── the pass ────────────────────────────────────────────────────────

(defn- known-set [known-ids]
  (cond
    (set? known-ids) known-ids
    (fn? known-ids) (set (known-ids))
    (coll? known-ids) (set known-ids)
    :else #{}))

(defn pass!
  "One pass: list the inbox headers at Gate, mint one row per message
  id nobody holds yet, and report what happened.

  config (what `source` holds, and what the wiring fills):
  :rpc-fn    the shared Gate caller (gate-chat/rpc builds it once)
  :tool      the listing tool (default `emila__inbox`)
  :limit     the window (default 40); :none sends no limit
             argument at all, for a rig that takes none
  :folder    an optional folder argument the rig may take
  :mint!     doc → the created row — the engine's create of ONE
             `inbox_item`, injected so the translation is testable
             without an engine and the wiring can adapt when the
             kind's create door lands
  :exists?   message id → truthy when the engine already holds it
  :known-ids a set (or a 0-arg fn answering one) — the same question
             asked once instead of per message
  :minted    the atom of ids this process minted (source's)
  :now-fn    the clock (→ Instant), for the undatable message
  :log-fn    where the one-line report goes

  → {:listed :minted :unsubscribed :known :unreadable :refused}.
  THROWS when Gate is unreachable or refuses: the stored rows keep
  serving and the next beat tries again. A single row the engine
  refuses is counted and skipped, never allowed to stop the rest of
  the pass — and, not being recorded as minted, it is offered again
  next beat."
  [{:keys [rpc-fn tool limit folder mint! exists? known-ids minted
           now-fn log-fn]}]
  (let [tool (or tool default-tool)
        now-fn (or now-fn #(Instant/now))
        log-fn (or log-fn warn!)
        minted (or minted (atom #{}))
        fallback (str (now-fn))
        known (known-set known-ids)
        held? (fn [id] (or (contains? known id)
                           (contains? @minted id)
                           (boolean (when exists? (exists? id)))))
        limit (if (nil? limit) default-limit limit)
        msgs (call rpc-fn tool (cond-> {}
                                 (number? limit) (assoc :limit limit)
                                 folder (assoc :folder folder)))
        report
        (reduce
         (fn [rep msg]
           (let [rep (update rep :listed inc)
                 id (message-id msg)]
             (cond
               (nil? id) (update rep :unreadable inc)
               (list-unsubscribe? msg) (update rep :unsubscribed inc)
               (held? id) (update rep :known inc)
               :else
               (try
                 (mint! (message->doc msg fallback))
                 (swap! minted conj id)
                 (update rep :minted inc)
                 (catch Exception e
                   (log-fn "message " id " was refused a row: "
                           (ex-message e))
                   (update rep :refused inc))))))
         {:listed 0 :minted 0 :unsubscribed 0 :known 0 :unreadable 0
          :refused 0}
         msgs)]
    (log-fn (:listed report) " listed, " (:minted report) " minted, "
            (:unsubscribed report) " skipped for list-unsubscribe, "
            (:known report) " already queued"
            (when (pos? (long (:unreadable report)))
              (str ", " (:unreadable report) " with no id"))
            (when (pos? (long (:refused report)))
              (str ", " (:refused report) " refused")))
    report))

(defn source
  "The real boundary over Gate: the pass config, plus the ids this
  process has minted. Hold it and hand it to `pass!` — the atom is
  what makes a second pass over the same listing free even when the
  engine read behind :exists? is having a bad day."
  [config]
  (assoc config :minted (atom #{})))

(defn fake-source
  "The inbox in memory: the REAL source over a scriptable Gate
  (gate-chat's twin, standing at the transport), so the listing read,
  the structured/parts fallback, the unsubscribe filter and the
  translation all run and only the socket is missing. Script it with
  gate-chat/answer! — rig-shaped entries, never canonical documents —
  and gate-chat/down!."
  ([] (fake-source (gc/fake-state)))
  ([state] (fake-source state {}))
  ([state opts] (source (assoc opts :rpc-fn (gc/fake-rpc state)))))

;; ── the cadence ─────────────────────────────────────────────────────

(defn start-passes!
  "The inbox daemon: one `pass!` now and every :every-seconds after,
  on a daemon thread (the jobs worker's shape). A beat that throws —
  Gate unreachable, the rig refusing — is warned and the NEXT beat
  still runs; nothing in a pass may kill the loop. main's start!
  owns the lifecycle and elects the one holder per database; tests
  call pass! directly."
  [src {:keys [every-seconds] :or {every-seconds default-every-seconds}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (try (pass! src)
                            (catch Exception e
                              (warn! "pass failed (" (ex-message e)
                                     "); the stored rows keep serving and "
                                     "the next beat still runs")))
                       (when-not (.await stop (long (* 1000 every-seconds))
                                         TimeUnit/MILLISECONDS)
                         (recur))))
                   "workqueue10-inbox")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-passes! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
