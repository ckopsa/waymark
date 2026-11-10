(ns waymark10.server.seats.inbox
  "A seat's inbox, its keys, the feed tokens and the seat's place,
  carved out of `waymark10.server.seats` (ticket bbf88202, carve 4):
  what an inbox hears, the key a sitting's inbox door reads, the
  read-only token a feed answers, and the tar the mayor's one line
  fetches. The seat's mode and the sitting's lease reads live here too,
  since these defns read them.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.keys :as keys]
            ;; a lease's half-life rule (docs/spec-leases.md §2)
            [waymark10.server.leases :as leases]
            [waymark10.server.seats.fire :as fire]
            [waymark10.server.seats.keys :as seat-keys]
            [waymark10.server.store :as store]
            ;; a cue note's {data.field} holes, filled as a :summary
            ;; template's are (ticket 79b29c3e)
            [waymark10.summary :as summary]
            [waymark10.wire :as wire])
  (:import (java.io ByteArrayOutputStream File)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private seats-actor fire/seats-actor)

(def ^:private default-idle-seconds fire/default-idle-seconds)

(def ^:private key-hash seat-keys/key-hash)

;; ── the seat's mode (R-10.8) ────────────────────────────────────────

(def interactive-mode "interactive")

(defn interactive-seat?
  "Is this seat one a person sits in (R-10.8)? Reads a STORED row as
  happily as a decoded one — an enum crosses the wire as its own
  string either way — so the schedules consumer, the wake consumer
  and the `fire` door all ask the question the same way. A row
  written before the field existed is `fired`, which is what it was."
  [seat-row]
  (= interactive-mode (some-> (get-in seat-row [:data :mode]) str)))

;; ── the sitting's lease (docs/spec-leases.md §2, §3) ────────────────

(defn ->instant [v]
  (cond (instance? java.time.Instant v) v
        (inst? v) (.toInstant ^java.util.Date v)
        (some-> v str not-empty) (java.time.Instant/parse (str v))))

(defn now-of
  "The engine's own clock, so a test that moves the clock moves the
  lease too."
  [eng]
  ((or (:now-fn eng) #(java.time.Instant/now))))

(defn lease-live?
  "Is this sitting's lease live at `now`? `open` means a live lease
  (docs/spec-leases.md §3): state `open` and `expires_at` ahead. Between
  its `expires_at` and the sweep's pass a sitting is still in state
  `open`, and nothing counts it then: not the seat's slots, not the
  damper, not the credentials it was issued. A row born before the
  lease, with no `expires_at`, is live while it is open; the boot's
  `backfill-expiries!` gives it one."
  [row now]
  (and (= "open" (some-> (:state row) name))
       (let [exp (->instant (get-in row [:data :expires_at]))
             at (->instant now)]
         (or (nil? exp) (nil? at) (.isBefore ^java.time.Instant at exp)))))

(defn lease-seconds
  "A sitting's lease length (§3): its seat's `sitting_idle_seconds`, in
  both modes and with no cap. It is not the old claim silence, which
  as a lease would end a fired run quiet through a long build. A seat
  that cannot be read, or names none, is the schema's default."
  [seat-row]
  (long (or (get-in seat-row [:data :sitting_idle_seconds])
            default-idle-seconds)))

(defn renewal-due?
  "Does this sitting's lease want renewing at `now` (§2)? Live, and less
  than half its length ahead: `leases/half-left?`, the rule a grant's
  standing shares. A lapsed lease is not renewable, and an ended
  sitting never renews."
  [row now seat-row]
  (boolean
   (and (lease-live? row now)
        (some? (get-in row [:data :expires_at]))
        (not (leases/half-left? (->instant now) (get-in row [:data :expires_at])
                                (lease-seconds seat-row))))))

;; ── the inbox's key ─────────────────────────────────────────────────

(def default-inbox
  "What an INTERACTIVE seat that states no `inbox` hears (epic
  3ad250ef): every move of the kinds a person at a seat works with. It
  is derived where the inbox is judged and never stored, and the door
  still narrows it to the kinds the seat's scope reads."
  {:only {:ticket [] :change [] :held_call [] :approval_request []
          :seat [] :sitting []}})

(defn own-held-calls?
  "Does this inbox say `held_call: own`: hear the held calls its seat
  filed itself, and no other seat's?"
  [inbox]
  (= "own" (some-> (:held_call inbox) name)))

(defn inbox-of
  "The inbox this seat's sittings pull: the one it states, else
  `default-inbox` for an interactive seat, else nil. A fired seat has
  an inbox only when it states one.

  An inbox that states `cues` and no `only` keeps the default `only`,
  so an interactive seat adds cues without restating every kind it
  hears. An inbox that says `held_call: own` hears held_call, every
  action of it, whether or not its `only` names the kind."
  [seat-row]
  (let [stated (get-in seat-row [:data :inbox])
        default (when (interactive-seat? seat-row) default-inbox)
        inbox (cond
                (nil? stated) default
                (some? (:only stated)) stated
                :else (merge default (dissoc stated :only)))]
    (cond-> inbox
      (own-held-calls? inbox) (update-in [:only :held_call] #(or % [])))))

(defn cue-note
  "The words a cue attaches to an event of `action` it matches: its
  stated `note`, else a default sentence built from the entry, so a
  cue spelled as a wake entry still tells the person (ticket
  1f3aa7cd). A cue that names actions says by which one the row
  arrived; one with no actions says only that it moved.

  A stated note may name the row that moved: its {data.field},
  {state} and {id} holes fill from `row` as a :summary template's do,
  and a field the row lacks, or a row that is gone, says an em-dash
  (ticket 79b29c3e)."
  ([cue action] (cue-note cue action nil))
  ([cue action row]
   (or (some-> (:note cue) str not-empty (summary/render row))
       (if (seq (:actions cue))
         (str "A " (:kind cue) " arrived by " action ": it may wait on your tap.")
         (str "A " (:kind cue) " moved: it may wait on your tap.")))))

(defn cue-note-holes?
  "Whether a cue's stated note names a field of the row that moved, so
  matching it reads that row (ticket 79b29c3e)."
  [cue]
  (boolean (some->> (:note cue) str (re-find #"\{[A-Za-z0-9_.]+\}"))))

(def inbox-keys-max
  "How many inbox keys of one sitting answer at one time. A same-session
  sit past it drops the oldest."
  12)

(defn- inbox-key-hashes
  "The hashes of the inbox keys this sitting's data holds, newest
  first."
  [data]
  (->> (cons (:inbox_key_hash data) (:inbox_keys_earlier data))
       (keep #(some-> % str not-empty))
       vec))

(defn issue-inbox-key!
  "Mint a fresh inbox key for this sitting, keep its hash on the
  sitting, and answer the key. → the key, or nil when the seat has no
  inbox (`inbox-of`) or the sitting is no longer open.

  `transcripts/issue-key!`'s shape and its reason: each sit mints a new
  key, the row keeps the hash alone, and the write is a maintenance
  write, so the record of a credential is not in the log. Every key
  dies with the sitting, because `inbox-sitting-by-key` reads open
  sittings only.

  A SIT OF THE SAME HARNESS SESSION STOPS NO KEY, `with-feed-token`'s
  way: when `harness-session` is the one stamped on the sitting, the
  keys before this one are kept beside it (the newest `inbox-keys-max`
  in all), so a stream that is using one is not cut. Any other sit of
  the sitting replaces them: the old keys stop answering."
  ([eng seat-row sitting-row] (issue-inbox-key! eng seat-row sitting-row nil))
  ([eng seat-row sitting-row harness-session]
   (when (and seat-row sitting-row
              (some? (inbox-of seat-row))
              (get (inv/resources eng) :sitting))
     (let [key (keys/mint)
           harness (some-> harness-session str str/trim not-empty)]
       (store/with-tx (:storage eng)
         (fn [tx]
           (when-some [row (store/load-row (:storage eng) tx :sitting
                                           (str (:id sitting-row)) {:for-update true})]
             (when (= :open (:state row))
               (let [data (:data row)
                     same? (and harness
                                (= harness (some-> (:harness_session data) str not-empty)))]
                 (store/maintain! :credential (:storage eng) tx :sitting (str (:id row))
                                     (assoc data
                                            :inbox_key_hash (key-hash key)
                                            :inbox_keys_earlier
                                            (if same?
                                              (vec (take (dec (long inbox-keys-max))
                                                         (inbox-key-hashes data)))
                                              []))
                                     (:next-flip-at row)))
               key))))))))

(defn inbox-url
  "The absolute address of the inbox door, from the origin the sit
  arrived under."
  [origin]
  (str (str/replace (str origin) #"/+$" "") "/api/-/sittings/inbox"))

(def inbox-key-header
  "The request header the inbox door reads its key from, and the only
  place it reads it: a key in the query string would land in access logs."
  "Waymark-Inbox-Key")

(def inbox-wait-max
  "The longest `wait`, in seconds: under the idle timeout of the
  proxies a cloud session reaches the engine through."
  25)

(def inbox-how
  "The sentence the sit answers beside the inbox url and key, so a
  session need not guess how to present the key or how the door pages."
  (str "GET the url with the key in the " inbox-key-header " header. "
       "`?after=now` answers the newest event id in the Waymark-Inbox-After "
       "response header; then `?after=<id>&wait=<seconds, up to " inbox-wait-max ">` "
       "long-polls newline-delimited JSON events after it, and every answer's "
       "Waymark-Inbox-After is the next `after`. A 401 means the sitting closed: sit again."))

(def inbox-client
  "The client that reads the inbox door all day, named in the sit answer."
  "seat/inbox-feed.sh in ckopsa/waymark")

;; ── the seat's place (docs/spec-first-run.md R-5.6) ─────────────────
;;
;; The mayor's one line is `curl … | tar x`: the door mints a key,
;; offers it, and answers this tar. The key is written into the tar's
;; CLAUDE.md and nowhere else — `offer_key` keeps its hash alone and
;; records no input.

(def place-origin
  "Where the mayor's one line fetches its place from: the self-hosted
  engine's own address (SELFHOST.md)."
  "http://127.0.0.1:8014")

(def place-feed-paths
  "Where the engine reads `seat/inbox-feed.sh` from: the repository
  root, or a module directory beside it — the image starts in
  workqueue10/, the suites in waymark10/."
  ["seat/inbox-feed.sh" "../seat/inbox-feed.sh"])

(defn place-feed
  "The bytes of `inbox-feed.sh`, or nil when no path above holds it."
  ^bytes []
  (some (fn [^String p]
          (let [f (File. p)]
            (when (.isFile f) (Files/readAllBytes (.toPath f)))))
        place-feed-paths))

(defn place-claude-md
  "The place's CLAUDE.md: the sit instructions, with the key."
  ^String [seat-name seat-id k]
  (str "# The seat's place\n\n"
       "This is the place of the seat `" seat-name "` (" seat-id "). "
       "The key below is written here and nowhere else: the engine keeps "
       "its hash alone, and a new place replaces it.\n\n"
       "Sit first. Call waymark_sit once, with\n\n"
       "    key:  " k "\n"
       "    seat: " seat-name "\n\n"
       "and pass your harness's session id as `session` when you have one. "
       "Then do what the sit answers.\n\n"
       "`inbox-feed.sh` beside this file reads the seat's inbox: "
       "`./inbox-feed.sh <inbox url> <inbox key>`, with the url and the key "
       "the sit answers (docs/seat-inbox-feed.md).\n\n"
       "Keep this file out of every repository: whoever reads the key sits "
       "as this seat.\n"))

(defn place-mcp-json
  "The place's project .mcp.json: the engine's MCP door at
  place-origin, as Claude Code, an agent acting for `owner` — the
  solo identity router's solo-admits takes — so Claude Code opened in
  the folder has the Waymark tools with no `claude mcp add`. A
  person's headers would be refused at the interactive seat's sit."
  ^String [owner]
  (str (wire/write-json
        {"mcpServers"
         {"Waymark" {"type" "http"
                     "url" (str place-origin "/api/-/mcp")
                     "headers" {"X-Waymark-Principal" "claude-code"
                                "X-Waymark-Actor-Type" "agent"
                                "X-Waymark-Acts-For" (str owner)}}}})
       "\n"))

(defn- tar-octal
  "A ustar number field: `width` - 1 octal digits and a NUL."
  ^bytes [n width]
  (.getBytes (str (format (str "%0" (dec (long width)) "o") (long n)) "\u0000")
             StandardCharsets/US_ASCII))

(defn- tar-entry!
  "One ustar member onto `out`: its header block, then its body padded
  to the next 512 bytes."
  [^ByteArrayOutputStream out ^String path mode ^bytes body mtime]
  (let [h (byte-array 512)
        put! (fn [at ^bytes b] (System/arraycopy b 0 h (int at) (alength b)))
        ascii (fn [^String s] (.getBytes s StandardCharsets/US_ASCII))]
    (put! 0 (ascii path))
    (put! 100 (tar-octal mode 8))
    (put! 108 (tar-octal 0 8))
    (put! 116 (tar-octal 0 8))
    (put! 124 (tar-octal (alength body) 12))
    (put! 136 (tar-octal mtime 12))
    ;; the checksum is summed with its own field as eight spaces
    (put! 148 (ascii "        "))
    (put! 156 (ascii "0"))
    (put! 257 (ascii "ustar\u000000"))
    (put! 148 (ascii (format "%06o\u0000 "
                             (reduce + (map #(bit-and (long %) 0xff) h)))))
    (.write out h)
    (.write out body)
    (.write out (byte-array (mod (- 512 (mod (alength body) 512)) 512)))))

(defn place-tar
  "The seat's place as a tar: CLAUDE.md with the key, .mcp.json naming
  the engine as `owner`, and inbox-feed.sh, executable."
  ^bytes [seat-name seat-id k owner ^bytes feed ^Instant now]
  (let [out (ByteArrayOutputStream.)
        mtime (.getEpochSecond now)]
    (tar-entry! out "CLAUDE.md" 0644
                (.getBytes (place-claude-md seat-name seat-id k) StandardCharsets/UTF_8)
                mtime)
    (tar-entry! out ".mcp.json" 0644
                (.getBytes (place-mcp-json owner) StandardCharsets/UTF_8)
                mtime)
    (tar-entry! out "inbox-feed.sh" 0755 feed mtime)
    ;; the end of the archive: two zero blocks
    (.write out (byte-array 1024))
    (.toByteArray out)))

(def ^:private inbox-sitting-page
  "The most open sittings the inbox door reads for a key: every seat's
  open sittings, which are a handful while the sweep keeps up."
  500)

(defn inbox-sitting-by-key
  "The open sitting this inbox key belongs to, raw, or nil for a bad
  key, a key a later sit replaced, or a sitting whose lease is no
  longer live (docs/spec-leases.md §3). A key
  a same-session sit kept (`issue-inbox-key!`) finds it as the newest
  does.

  THE KEY IS FOUND BY READING THE OPEN SITTINGS, for
  `transcripts/transcript-by-key`'s reason: `inbox_key_hash` is
  :secret, and a :secret field may never be :filterable."
  [eng key]
  (when-some [wanted (key-hash key)]
    (when (get (inv/resources eng) :sitting)
      (->> (store/with-tx (:storage eng)
             (fn [tx]
               (store/query-rows (:storage eng) tx :sitting {:state :open}
                                 {:limit inbox-sitting-page})))
           (filter (fn [r]
                     (and (lease-live? r (now-of eng))
                          (some #(keys/equal? wanted %)
                                (inbox-key-hashes (:data r))))))
           first))))

(defn keep-inbox-lease!
  "The inbox door's half of the half-life rule (docs/spec-leases.md §2):
  a sitting read through its inbox key whose lease has less than half
  its length left is renewed by its own `renew` door, as the engine —
  the renewal `with-renewal` makes for a counted call. So a session that
  only listens is not swept under its reader; one that stops reading
  still lapses. → the sitting, or nil when the renewal is refused (the
  sitting ended meanwhile)."
  [eng sitting seat-row]
  (if-not (renewal-due? sitting (now-of eng) seat-row)
    sitting
    (try (inv/invoke! eng :sitting (str (:id sitting)) :renew nil
                      {:principal seats-actor})
         sitting
         (catch clojure.lang.ExceptionInfo _ nil))))

(def feed-token-seconds
  "How long a feed token answers while its sitting stays open: 35
  minutes. A sitting that runs longer is answered the next one by the
  key check door (`renew-feed-token!`), or sits again for a fresh one."
  2100)

(def feed-tokens-max
  "How many feed tokens of one sitting answer at one time. A sit past
  it drops the oldest; the key check door past it renews nothing."
  12)

(defn- live-feed-tokens
  "The feed tokens this sitting's data holds that have not reached
  their end, as `{:hash :expires_at}`, newest first."
  [data ^Instant now]
  (->> (cons {:hash (:feed_token_hash data)
              :expires_at (:feed_token_expires_at data)}
             (:feed_tokens_earlier data))
       (filter (fn [{:keys [hash expires_at]}]
                 (and (some-> hash str not-empty)
                      (when-some [^Instant until (->instant expires_at)]
                        (.isBefore now until)))))
       (mapv #(select-keys % [:hash :expires_at]))))

(defn- feed-token-held
  "The live feed token of this sitting's data whose hash is `wanted`,
  compared in constant time, or nil."
  [data wanted now]
  (->> (live-feed-tokens data now)
       (filter (fn [{:keys [hash]}]
                 (keys/equal? wanted hash)))
       first))

(defn- with-feed-token
  "This sitting's data with `token` as its newest feed token. The
  tokens before it that have not ended are kept beside it, so a token
  in use answers until its own end: the newest `feed-tokens-max` in
  all."
  [data token expires now]
  (assoc data
         :feed_token_hash (key-hash token)
         :feed_token_expires_at expires
         :feed_tokens_earlier (vec (take (dec (long feed-tokens-max))
                                         (live-feed-tokens data now)))))

(defn- feed-sitting-by-token
  "The sitting with a live lease that holds a live feed token of this
  hash, raw, or nil: a token stops when its lease does
  (docs/spec-leases.md §3)."
  [eng tx wanted now]
  (->> (store/query-rows (:storage eng) tx :sitting
                         {:state :open}
                         {:limit inbox-sitting-page})
       (filter #(and (lease-live? % now)
                     (feed-token-held (:data %) wanted now)))
       first))

(defn issue-feed-token!
  "Mint a fresh feed token for this sitting, keep its hash and its end
  on the sitting, and answer `{:url :token :expires_at :note}`. → nil
  when the seat declares no https `feed_url` or the sitting is no
  longer open.

  EACH SIT MINTS A NEW TOKEN AND STOPS NONE. A sit that reuses an open
  sitting leaves the tokens before it answering until their own ends
  (`with-feed-token`), so a stream that is using one is not cut. The
  row keeps the hashes alone, so a sit cannot answer an earlier token
  again. The write is a maintenance write, `issue-inbox-key!`'s way,
  so the record of a credential is not in the log. Every token dies
  with the sitting, because `feed-seat-by-token` reads open sittings
  only, and at its `expires_at` when the sitting outlives it.

  IT IS NOT A KEY. Only the key check door reads `feed_token_hash`, so
  the token sits in no seat, closes no sitting and pulls no inbox."
  [eng seat-row sitting-row]
  (when-some [url (some-> seat-row (get-in [:data :feed_url]) str not-empty)]
    (when (and sitting-row
               (str/starts-with? url "https://")
               (get (inv/resources eng) :sitting))
      (let [token (keys/mint)
            ^Instant now ((:now-fn eng))
            expires (str (.plusSeconds now (long feed-token-seconds)))]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [row (store/load-row (:storage eng) tx :sitting
                                            (str (:id sitting-row)) {:for-update true})]
              (when (= :open (:state row))
                (store/maintain! :credential (:storage eng) tx :sitting (str (:id row))
                                    (with-feed-token (:data row) token expires now)
                                    (:next-flip-at row))
                {:url url
                 :token token
                 :expires_at expires
                 :note (str "A read-only feed token for " url ", valid until "
                            expires " or the close of this sitting, safe to "
                            "send as `Authorization: Bearer` to that URL only.")}))))))))

(defn feed-seat-by-token
  "The ACTIVE seat whose open sitting was answered this feed token, raw,
  or nil for a bad token, a token past its end, or a sitting that has
  ended. A token a later sit or a renewal followed answers until its
  own end (`live-feed-tokens`).

  THE TOKEN IS FOUND BY READING THE OPEN SITTINGS, for
  `inbox-sitting-by-key`'s reason: `feed_token_hash` is :secret, and a
  :secret field may never be :filterable."
  [eng token]
  (when-some [wanted (key-hash token)]
    (when (and (get (inv/resources eng) :sitting)
               (get (inv/resources eng) :seat))
      (let [^Instant now ((:now-fn eng))]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [sitting (feed-sitting-by-token eng tx wanted now)]
              (when-some [seat (some->> (get-in sitting [:data :seat]) str not-empty
                                        (#(store/load-row (:storage eng) tx :seat % {})))]
                (when (= :active (:state seat))
                  seat)))))))))

(defn renew-feed-token!
  "The next feed token for the open sitting that holds `token`, as
  `{:token :expires_at}`, when `token` is live and has less than half
  its life left. → nil for every other token, for a seat that is not
  active, and for a sitting that already holds `feed-tokens-max` live
  tokens.

  THE OLD TOKEN IS NOT STOPPED. It answers until its own end, so the
  stream that holds it has no gap between the two (`with-feed-token`).
  The write is `issue-feed-token!`'s maintenance write. An ask that
  repeats past the half mints again, because the row keeps hashes and
  cannot answer the same next token twice; `feed-tokens-max` bounds it."
  [eng token]
  (when-some [wanted (key-hash token)]
    (when (and (get (inv/resources eng) :sitting)
               (get (inv/resources eng) :seat))
      (let [^Instant now ((:now-fn eng))
            half (quot (long feed-token-seconds) 2)]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [found (feed-sitting-by-token eng tx wanted now)]
              (when-some [row (store/load-row (:storage eng) tx :sitting
                                              (str (:id found)) {:for-update true})]
                (let [^Instant until (some-> (feed-token-held (:data row) wanted now)
                                             :expires_at
                                             ->instant)
                      seat (some->> (get-in row [:data :seat]) str not-empty
                                    (#(store/load-row (:storage eng) tx :seat % {})))]
                  (when (and (= :open (:state row))
                             until
                             (.isBefore until (.plusSeconds now half))
                             (= :active (:state seat))
                             (< (count (live-feed-tokens (:data row) now))
                                (long feed-tokens-max)))
                    (let [next-token (keys/mint)
                          expires (str (.plusSeconds now (long feed-token-seconds)))]
                      (store/maintain! :credential (:storage eng) tx :sitting (str (:id row))
                                          (with-feed-token (:data row) next-token expires now)
                                          (:next-flip-at row))
                      {:token next-token :expires_at expires})))))))))))
