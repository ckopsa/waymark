(ns waymark10.server.transcripts
  "The transcript of a sitting: what the model read, said and called
  while it sat (docs/spec-transcript.md).

  THE SITTING RECORDS WHAT A WAKE COST; THIS RECORDS WHAT IT DID. The
  owner's ruling of 2026-09-27: a seat that audits other seats needs
  to read their runs, and the provider gives a cloud session no way to
  read another run's events. So the harness's own transcript comes
  here, line by line, and each line is a row a grant can query.

  ── the two kinds ───────────────────────────────────────────────────

  `transcript` is one row per sitting. The sit gives birth to it and
  mints its key (R-3.6, R-4.1); the upload door appends to it; the
  sweep seals it and, after the seat's `transcript_days`, purges its
  lines (R-9). `transcript_entry` is one row per line of one file of
  the harness's transcript, written by the door with the engine's own
  hand and no transition of its own (R-5.7, `inv/insert-quiet!`).

  ── the key, and why it is in the model's hands ─────────────────────

  The hook has no credential in the cloud: the environment must hold
  none, because every Routine's run uses it. The hook already reads
  the sitting's id out of the sit's answer in the transcript, so the
  sit answers a second thing beside it: an address and a key that can
  add lines to THIS transcript and do nothing else (R-4.2). The row
  keeps the key's hash alone.

  The key is therefore in the model's context too. The design does
  not stop a model posting lines of its own; it makes that visible.
  Every line is chained (R-5.3), the door only appends (R-5.4), and a
  line that does not match what the engine holds breaks the chain and
  writes the `gap` (R-5.5). The proxy-held credential that would keep
  the key from the model is a recorded punt (§ 10, § 13).

  Recorded deviations (each a sentence):

  - THE KINDS LIVE HERE, NOT IN seats.clj. R-3.1 names seats.clj.
    That file already holds three kinds and one law about money; the
    transcript is a second law, about bytes, with its own door, its
    own redaction and its own sweep, and held_calls.clj is the
    precedent for a core kind in a namespace of its own.
  - THE TRANSCRIPT IS BORN AT THE SIT, NOT WITH THE SITTING. R-3.6
    asks for the birth in the sitting's own commit. A sitting is also
    born by the leash keeper and by hand, and neither has a hook that
    could ever send a line, so a transcript born there would be a row
    that can only ever say `No transcript was received.` The sit is
    where a key is answered, and the key is the only way a line
    arrives; the transcript is born there, in the call that opens or
    reuses the sitting, and R-3.6's point (an absent record reads
    differently from a lost one) holds for every sitting that could
    have had one.
  - THE KEY IS FOUND BY READING THE OPEN TRANSCRIPTS. `key_hash` is
    :secret, and a :secret field may never be :filterable
    (resource/check-secret!). `seats/seat-by-key` reads every active
    seat for the same reason; the open transcripts are the open
    sittings plus the ones inside their grace, which is a handful.
  - `tool` AND `tool_use_id` ARE EMPTY STRINGS, NOT NULLS, on a line
    that has none. A `:maybe` field promotes no column, and an audit
    that cannot filter by tool is the audit this kind exists for.
  - `n`, the line's place among ALL the transcript's lines, is a field
    R-3.3 does not name. The default sort is by `n`, because a
    collection sorts by one field and `file` then `seq` is two; `n`
    is the order the lines arrived in, which is the order they were
    written in within each file."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64)))

(set! *warn-on-reflection* true)

;; ── the numbers of the spec ─────────────────────────────────────────

(def text-cap
  "R-3.3: the readable text of one line, the cap spec-seat.md holds
  for a message."
  4000)

(def summary-cap
  "R-3.5: the text the summary projection carries."
  300)

(def default-line-max-bytes
  "R-3.3: `transcript_line_max_bytes`, the most of one line kept in
  `raw`."
  65536)

(def post-max-bytes
  "R-5.2: the most lines one post may carry, before compression."
  (* 4 1024 1024))

(def default-max-bytes
  "R-5.6: `transcript_max_bytes`. Past it, lines keep their derived
  fields and no `raw`."
  (* 32 1024 1024))

(def default-grace-seconds
  "R-9.1: `transcript_grace_seconds`, how long after its sitting ends
  a transcript still takes lines — the last Stop of a fired run comes
  after the close."
  600)

(def default-days
  "R-9.4: the seat's `transcript_days` when the row predates it."
  30)

(defn- opt
  "An engine option under :services :transcripts, or the default."
  [eng k default]
  (get-in eng [:services :transcripts k] default))

(def zero-chain
  "R-5.3: the chain before the first line."
  (apply str (repeat 64 "0")))

(def ^:private file-pattern
  "R-3.3: `main`, or `agent-{id}` for a subagent."
  #"main|agent-[A-Za-z0-9_-]{1,64}")

;; ── the chain (R-5.3) ───────────────────────────────────────────────

(defn- hex ^String [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [x b] (.append sb (format "%02x" (bit-and (int x) 0xff))))
    (.toString sb)))

(defn chain-next
  "The chain to a line: SHA-256 over the chain before it, one newline,
  and the line, as UTF-8, in lower-case hex. The hook computes the
  same thing, which is the whole point of it."
  ^String [^String prior ^String line]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str prior "\n" line) StandardCharsets/UTF_8))))

(defn- utf8-length ^long [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))

;; ── the key (R-4) ───────────────────────────────────────────────────

(defonce ^:private ^SecureRandom key-random (SecureRandom.))

(defn- mint-key
  "128 bits of randomness, base64url, unpadded: the shape of every
  other key this engine mints (seats/mint-key)."
  []
  (let [b (byte-array 16)]
    (.nextBytes key-random b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- hash-matches?
  "Constant-time compare of a key's hash with a held one, for
  `seats/key-matches?`'s reason."
  [held key]
  (boolean
   (when-some [wanted (seats/key-hash key)]
     (when-some [held (some-> held str not-empty)]
       (MessageDigest/isEqual (.getBytes ^String wanted StandardCharsets/UTF_8)
                              (.getBytes ^String held StandardCharsets/UTF_8))))))

;; ── redaction (R-7.4) ───────────────────────────────────────────────

(def patterns
  "The `pattern` class of R-7.1, which the engine applies a second
  time. Each is plain ASCII with no JSON-special character in what it
  matches, so a replacement inside a JSON line keeps the line valid."
  [#"gh[pos]_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"sk-ant-[A-Za-z0-9_-]{16,}"
   #"xox[bp]-[A-Za-z0-9-]{10,}"
   #"AKIA[0-9A-Z]{16}"
   #"Bearer [A-Za-z0-9._~+/=-]{16,}"
   #"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"])

(def ^:private key-shape
  "A base64url string of 22 characters, standing alone: the shape of
  every key this engine mints."
  #"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{22}(?![A-Za-z0-9_-])")

(defn- replace-counting
  "Replace every match of `re` in `s` with `with`; → [s' n]."
  [^String s re ^String with]
  (let [n (count (re-seq re s))]
    (if (zero? n)
      [s 0]
      [(str/replace s re (str/re-quote-replacement with)) n])))

(defn redact-line
  "The engine's own pass over one line the hook already redacted.
  `secrets` is {:hashes #{…} :raw #{…}}: the hashes of the keys that
  still answer (the seat's live fire keys, this transcript's key) and
  the standing keys held raw (the seat's and its chair's). → [line n],
  n the redactions this pass made. A count above zero is a hook that
  missed something, and `engine_redactions` shows it (R-7.4)."
  [^String line {:keys [hashes raw]}]
  (let [[line n1] (reduce (fn [[s n] re]
                            (let [[s' k] (replace-counting s re "[redacted:pattern]")]
                              [s' (+ (long n) (long k))]))
                          [line 0] patterns)
        [line n2] (reduce (fn [[^String s n] ^String k]
                            (if (and (not (str/blank? k)) (str/includes? s k))
                              [(str/replace s k "[redacted:seat-key]") (inc (long n))]
                              [s n]))
                          [line 0] raw)
        n3 (volatile! 0)
        line (if (seq hashes)
               (str/replace line key-shape
                            (fn [token]
                              (if (contains? hashes (seats/key-hash token))
                                (do (vswap! n3 inc) "[redacted:seat-key]")
                                token)))
               line)]
    [line (+ (long n1) (long n2) (long @n3))]))

;; ── what one line says (R-3.4) ──────────────────────────────────────

(defn- cap [^String s n]
  (if (> (count s) (long n)) (subs s 0 (long n)) s))

(defn- block-text
  "A tool result's content, which is a string or a list of blocks."
  [content]
  (cond
    (string? content) content
    (sequential? content) (str/join "\n" (keep #(when (map? %) (:text %)) content))
    :else ""))

(def ^:private usage-keys
  {:input_tokens :input_tokens
   :output_tokens :output_tokens
   :cache_read_input_tokens :cache_read_tokens
   :cache_creation_input_tokens :cache_write_tokens})

(defn derive-line
  "The fields the ENGINE reads off a line (R-3.4): the type, the tool
  and its pairing id, the error mark, the usage and the readable text.
  The hook sends lines only, and nothing here trusts a field it could
  have written. A line that is not JSON is still a line: its type is
  `unparsed` and its text is the line itself."
  [^String line]
  (let [doc (try (wire/read-json line) (catch Exception _ ::unparsed))]
    (if-not (map? doc)
      {:type "unparsed" :tool "" :tool_use_id "" :is_error false
       :text (cap line text-cap)}
      (let [msg (when (map? (:message doc)) (:message doc))
            content (:content msg)
            blocks (if (sequential? content) (filter map? content) [])
            use (first (filter #(= "tool_use" (:type %)) blocks))
            result (first (filter #(= "tool_result" (:type %)) blocks))
            texts (keep #(when (= "text" (:type %)) (:text %)) blocks)
            usage (when (map? (:usage msg))
                    (not-empty
                     (into {} (keep (fn [[from to]]
                                      (let [v (get (:usage msg) from)]
                                        (when (integer? v) [to (long v)]))))
                           usage-keys)))
            text (cond
                   (string? content) content
                   use (str (:name use) " " (wire/write-json (or (:input use) {})))
                   result (block-text (:content result))
                   (seq texts) (str/join "\n" texts)
                   (string? (:summary doc)) (:summary doc)
                   (string? (:content doc)) (:content doc)
                   :else "")]
        (cond-> {:type (or (some-> (:type doc) str not-empty) "unknown")
                 :tool (or (some-> (:name use) str) "")
                 :tool_use_id (or (some-> (or (:id use) (:tool_use_id result)) str) "")
                 :is_error (true? (:is_error result))
                 :text (cap (str text) text-cap)
                 ::result? (some? result)}
          usage (assoc :usage usage)
          (string? (:timestamp doc)) (assoc ::at (:timestamp doc)))))))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard the-engine-writes-it
  {:reads [:principal]
   :hide true
   :explain "The sit gives birth to a transcript, the upload door writes its lines and the sweep seals it; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard not-an-agent
  {:reads [:principal]
   :explain "A transcript's lines are deleted by a person or by the sweep. No grant can hold this door, so a seat cannot erase the record of its own sittings."}
  [_row _inp ctx]
  (if (= :agent (:type (:principal ctx)))
    (t/deny)
    (t/allow)))

(def purge-consequence
  "R-9.5's sentence, which the confirm echoes."
  "Every line of this transcript is deleted. The counts and the chain stay.")

;; ── :transcript ─────────────────────────────────────────────────────

(def ^:private file-entry
  [:map
   [:name {:x-display {:raw true :label "File"
                       :help "`main`, or `agent-{id}` for a subagent."}}
    [:string {:min 1 :max 80}]]
   [:lines {:x-display {:label "Lines held"}} [:int {:min 0}]]
   [:bytes {:x-display {:label "Bytes held"}} [:int {:min 0}]]
   [:chain {:x-display {:raw true :label "Chain to the last line"
                        :help "The SHA-256 chain to this file's last held line. The hook computes the same value; a different one is a different history."}}
    [:string {:min 64 :max 64}]]
   [:diverged {:optional true
               :x-display {:label "Diverged"
                           :help "True when a post did not match the lines this file already held. The file takes no more lines."}}
    [:maybe :boolean]]])

(def ^:private mode-values ["fired" "interactive"])

(defresource transcript
  {:kind :transcript
   :plural "transcripts"
   :states [:open :sealed :purged]
   :initial :open
   :terminal #{:purged}
   :nav :system
   :summary "Transcript of {data.sitting} · {data.lines} lines · {state}"
   :schema
   [:map
    [:sitting {:kind :sitting
               :x-display {:label "The sitting"
                           :help "The sitting this transcript records. One transcript for each sitting that sat with a key."}}
     :waymark/ref]
    [:seat {:kind :seat
            :x-display {:label "The seat"
                        :help "Copied from the sitting at birth, so a grant can filter by it."}}
     :waymark/ref]
    [:model {:kind :model
             :x-display {:label "The model"
                         :help "Copied from the sitting at birth."}}
     :waymark/ref]
    [:mode {:x-display {:label "How it was sat"
                        :help "Copied from the sitting at birth: a fired run, or a person's own sitting."}}
     (into [:enum] mode-values)]
    [:harness_session {:optional true
                       :x-display {:raw true :label "The harness session"
                                   :help "The harness's own id for the session, from the first upload."}}
     [:maybe [:string {:max 128}]]]
    [:run_session {:optional true
                   :x-display {:raw true :label "The provider's run"
                               :help "The provider's id for the run, from the first upload: the id the Routines surface lists for each firing."}}
     [:maybe [:string {:max 128}]]]
    [:files {:default []
             :x-display {:label "Files"
                         :help "One entry for each file of the harness's transcript: its line count, its bytes and the chain to its last line."}}
     [:vector file-entry]]
    [:lines {:default 0
             :x-display {:label "Lines held" :help "Over all files."}}
     [:int {:min 0}]]
    [:bytes {:default 0
             :x-display {:label "Bytes held" :help "Over all files, as the hook sent them."}}
     [:int {:min 0}]]
    [:uploads {:default 0
               :x-display {:label "Accepted posts"}}
     [:int {:min 0}]]
    [:redactions {:default {}
                  :x-display {:raw true :label "Redactions by the hook"
                              :help "How many secrets the hook replaced, by class, before it sent the lines."}}
     [:map-of :keyword [:int {:min 0}]]]
    [:engine_redactions {:default 0
                         :x-display {:label "Redactions the hook missed"
                                     :help "Secrets the engine found and replaced after the hook. Above zero is a defect in the hook."}}
     [:int {:min 0}]]
    [:first_at {:optional true :x-display {:label "First post"}}
     [:maybe :waymark/instant]]
    [:last_at {:optional true :x-display {:label "Last post"}}
     [:maybe :waymark/instant]]
    [:sealed_at {:optional true
                 :x-display {:label "Sealed"
                             :help "When the sweep sealed it. A sealed transcript takes no line."}}
     [:maybe :waymark/instant]]
    [:gap {:optional true
           :x-display {:label "What is missing"
                       :help "One sentence when the record is not whole: nothing received, a file that diverged, raw lines not kept past the cap, or no post after the sitting ended."}}
     [:maybe [:string {:max 240}]]]
    [:key_hash {:optional true :secret true
                :x-display {:hidden true
                            :label "The key's hash"
                            :spelled-by-hand "The SHA-256 of the live upload key. The sit writes it and the seal drops it; the engine never shows a key."}}
     [:maybe [:string {:max 64}]]]]
   :create-schema
   [:map
    [:sitting {:kind :sitting :x-display {:label "The sitting"}} :waymark/ref]
    [:seat {:kind :seat :x-display {:label "The seat"}} :waymark/ref]
    [:model {:kind :model :x-display {:label "The model"}} :waymark/ref]
    [:mode {:x-display {:label "How it was sat"}} (into [:enum] mode-values)]]
   :create-guards [the-engine-writes-it]
   :on-create
   (fn [row _ctx]
     (update row :data merge {:files [] :lines 0 :bytes 0 :uploads 0
                              :redactions {} :engine_redactions 0}))
   :filterable {:state #{:eq :in}
                :sitting #{:eq}
                :seat #{:eq}
                :model #{:eq}
                :mode #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :links [{:rel "sitting" :kind :sitting
            :href "/api/sittings/{data.sitting}"
            :summary "The sitting this transcript records"}
           {:rel "seat" :kind :seat
            :href "/api/seats/{data.seat}"
            :summary "The seat that sat"}]
   :actions
   {:seal
    {:from #{:open} :to :sealed
     :guards [the-engine-writes-it]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The transcript takes no more lines and its key stops answering. What it holds stays until the purge."}
     :display {:label "Seal" :order 9}}
    :purge
    {:from #{:sealed} :to :purged
     :guards [not-an-agent]
     :safety {:idempotent true :reversible false :confirm true
              :consequence purge-consequence}
     :display {:label "Delete the lines" :style :danger :order 1
               :description "Delete every line of this transcript now, before its seat's days are up"}}}
   :deviations
   ["R-3.1 names seats.clj; the kinds live in server/transcripts.clj, held_calls.clj's precedent for a core kind with its own door and sweep."
    "R-3.6 asks for the birth in the sitting's commit; the transcript is born at the sit, because a key is the only way a line arrives and the sit is where the key is answered. A sitting born by the leash keeper or by hand has no hook that could send a line."
    "The purge deletes the entries with `store/delete-rows!`, a maintenance delete with no transition per line; the `purge` transition on this row is the record of it."]})

;; ── :transcript_entry ───────────────────────────────────────────────

(defresource transcript-entry
  {:kind :transcript_entry
   :plural "transcript_entries"
   :states [:held]
   :initial :held
   :terminal #{:held}
   :nav :system
   :summary "{data.file}:{data.seq} · {data.type} {data.tool}"
   :schema
   [:map
    [:transcript {:kind :transcript
                  :x-display {:raw true :label "The transcript"}}
     :waymark/ref]
    [:sitting {:kind :sitting
               :x-display {:raw true :label "The sitting"
                           :help "Copied, so an audit filters one sitting's lines in one query."}}
     :waymark/ref]
    [:seat {:kind :seat
            :x-display {:raw true :label "The seat"
                        :help "Copied, so a grant can be narrowed to one seat's lines."}}
     :waymark/ref]
    [:file {:x-display {:raw true :label "File"
                        :help "`main`, or `agent-{id}` for a subagent."}}
     [:string {:min 1 :max 80}]]
    [:seq {:x-display {:label "Line in its file" :help "From 0."}}
     [:int {:min 0}]]
    [:n {:x-display {:label "Line in the transcript"
                     :help "The order the line arrived in, over every file. The default sort."}}
     [:int {:min 0}]]
    [:at {:x-display {:label "When"
                      :help "The line's own timestamp, or the moment it arrived when it carries none."}}
     :waymark/instant]
    [:type {:x-display {:raw true :label "Type"
                        :help "The line's own type: user, assistant, system, and others."}}
     [:string {:min 1 :max 60}]]
    [:tool {:x-display {:raw true :label "Tool"
                        :help "The tool a tool_use called, and the tool a tool_result answers. Empty on a line with no tool."}}
     [:string {:max 200}]]
    [:tool_use_id {:x-display {:raw true :label "Pairing id"
                               :help "The id that pairs a tool_use with its tool_result. Empty on a line with no tool."}}
     [:string {:max 200}]]
    [:is_error {:x-display {:label "An error"
                            :help "True for a tool_result the harness marked as an error."}}
     :boolean]
    [:usage {:optional true
             :x-display {:raw true :label "Tokens"
                         :help "The four token counts of an assistant line, as the harness reported them."}}
     [:maybe [:map-of :keyword [:int {:min 0}]]]]
    [:text {:x-display {:widget "prose" :label "What it says"
                        :help "The message, the tool's input or the tool's answer, cut to 4,000 characters."}}
     [:string {:max 4000}]]
    [:raw {:optional true
           :x-display {:raw true :label "The line"
                       :help "The whole line after redaction. Absent past the transcript's byte cap."}}
     [:maybe [:string {:max 65536}]]]
    [:raw_truncated {:optional true
                     :x-display {:label "Bytes cut from the line"}}
     [:maybe [:int {:min 0}]]]
    [:chain {:x-display {:raw true :label "Chain"
                         :help "The SHA-256 chain to this line."}}
     [:string {:min 64 :max 64}]]]
   :create-guards [the-engine-writes-it]
   :filterable {:transcript #{:eq}
                :sitting #{:eq}
                :seat #{:eq}
                :file #{:eq}
                :type #{:eq :in}
                :tool #{:eq :in}
                :tool_use_id #{:eq}
                :is_error #{:eq}
                :at #{:after :before :range}}
   :sortable {:fields [:n :seq :at] :default "n"}
   :links [{:rel "transcript" :kind :transcript
            :href "/api/transcripts/{data.transcript}"
            :summary "The transcript this line belongs to"}
           {:rel "sitting" :kind :sitting
            :href "/api/sittings/{data.sitting}"
            :summary "The sitting that wrote it"}]
   :deviations
   ["R-5.7: each line is inserted by the engine's quiet birth door (`inv/insert-quiet!`) with no transition of its own; the post that carried it is the write, and a transition per line would put the transcript in the log a second time."
    "`tool` and `tool_use_id` are empty strings rather than null on a line with no tool, because a `:maybe` field promotes no column and an audit must be able to filter by tool."
    "`n` is not in R-3.3. It is the line's place among all the transcript's lines and the default sort, because a collection sorts by one field."]})

;; ── reads ───────────────────────────────────────────────────────────

(defn- serves? [eng] (contains? (inv/resources eng) :transcript))

(defn- load-raw [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn transcript-for-sitting
  "The transcript of this sitting, raw, or nil."
  [eng sitting-id]
  (when (and sitting-id (serves? eng))
    (store/with-tx (:storage eng)
      (fn [tx]
        (first (store/query-rows (:storage eng) tx :transcript
                                 {:sitting (str sitting-id)} {:limit 1}))))))

(defn- keeps?
  "R-3.7: does this seat keep this sitting's transcript?"
  [seat-row mode]
  (case (str (or (get-in seat-row [:data :keep_transcripts]) "fired"))
    "none" false
    "all" true
    (= "fired" (str mode))))

;; ── the sit's half (R-3.6, R-4) ─────────────────────────────────────

(defn issue-key!
  "Give birth to the sitting's transcript when it has none, mint a
  fresh key, keep its hash, and answer the key. → the key, or nil when
  the seat keeps no transcript of this sitting, the transcript is no
  longer open, or this engine serves no transcripts.

  EACH SIT MINTS A NEW KEY AND THE OLD ONE STOPS ANSWERING (R-4.1).
  The hook reads the LAST sit's answer, so the key it holds is always
  the live one. The hash is a maintenance write, the fire key's own
  spelling (seats/hold-fire-key!): a transition for each key would put
  the record of a credential in the log."
  [eng seat-row sitting-row]
  (when (and (serves? eng) seat-row sitting-row)
    (let [mode (str (or (get-in sitting-row [:data :mode]) "fired"))]
      (when (keeps? seat-row mode)
        (let [existing (transcript-for-sitting eng (:id sitting-row))
              row (or existing
                      (:row (inv/create!
                             eng :transcript
                             {:sitting (str (:id sitting-row))
                              :seat (str (:id seat-row))
                              :model (str (get-in sitting-row [:data :model]))
                              :mode (if (= "interactive" mode) "interactive" "fired")}
                             {:principal seats/seats-actor})))
              key (mint-key)]
          (store/with-tx (:storage eng)
            (fn [tx]
              (when-some [held (store/load-row (:storage eng) tx :transcript
                                               (str (:id row)) {:for-update true})]
                (when (= :open (:state held))
                  (store/update-data! (:storage eng) tx :transcript (str (:id held))
                                      (assoc (:data held) :key_hash (seats/key-hash key))
                                      (:next-flip-at held))
                  key)))))))))

(defn upload-url
  "R-4.2: the absolute address of the door, from the origin the sit
  arrived under."
  [origin]
  (str (str/replace (str origin) #"/+$" "") "/api/-/sittings/transcript"))

;; ── the door (R-5) ──────────────────────────────────────────────────

(def no-transcript
  "UNIFORM (R-5.5), for R-12.14's reason: a wrong key, an absent key
  and a dropped key all read the same."
  "No transcript answers this key.")

(defn transcript-by-key
  "The open transcript this key answers, raw, or nil."
  [eng key]
  (when-some [key (some-> key str not-empty)]
    (when (serves? eng)
      (store/with-tx (:storage eng)
        (fn [tx]
          (->> (store/query-rows (:storage eng) tx :transcript {:state :open}
                                 {:limit 500})
               (filter #(hash-matches? (get-in % [:data :key_hash]) key))
               first))))))

(defn- invalid! [field detail]
  (throw (p/problem :invalid-params 422 "Invalid parameters"
                    {:detail (str (name field) " " detail)})))

(defn judge-body
  "R-5.2, judged before a row is read, in the close door's order: a
  malformed body is wrong whatever the key holds."
  [body]
  (when-not (map? body)
    (invalid! :body "must be a JSON object carrying file, from, prior and lines."))
  (let [{:keys [file from prior lines redactions harness_session run_session]} body]
    (when-not (and (string? file) (re-matches file-pattern file))
      (invalid! :file "must be `main` or `agent-{id}`."))
    (when-not (and (int? from) (not (neg? (long from))))
      (invalid! :from "must be a whole number of zero or more: the seq of the first line in this post."))
    (when-not (and (string? prior) (re-matches #"[0-9a-f]{64}" prior))
      (invalid! :prior "must be 64 lower-case hex characters: the chain before `from`."))
    (when-not (and (sequential? lines) (every? string? lines))
      (invalid! :lines "must be a list of strings, one for each line."))
    (when (> (reduce + 0 (map #(utf8-length %) lines)) (long post-max-bytes))
      (throw (p/problem :too-large 413 "Too large"
                        {:detail (str "One post carries at most " post-max-bytes
                                      " bytes of lines. Send the file in more than one post.")})))
    (when (some? redactions)
      (when-not (and (map? redactions)
                     (every? #(and (int? %) (not (neg? (long %)))) (vals redactions)))
        (invalid! :redactions "must map each class to a whole number.")))
    (doseq [[f v] [[:harness_session harness_session] [:run_session run_session]]]
      (when (some? v)
        (when-not (and (string? v) (<= (count v) 128))
          (invalid! f "must be a string of at most 128 characters."))))
    (when-some [extra (seq (sort (map name (keys (dissoc body :file :from :prior :lines
                                                         :redactions :harness_session
                                                         :run_session)))))]
      (invalid! (first extra) "is not a field of this post."))
    body))

(defn- secrets-of
  "What the engine's second pass looks for in this transcript's lines
  (R-7.4): the hashes of the keys that answer now, and the standing
  keys held raw."
  [eng transcript-raw]
  (let [seat (load-raw eng :seat (get-in transcript-raw [:data :seat]))
        chair (when-some [c (some-> seat seats/chair-of)]
                (when (contains? (inv/resources eng) :model)
                  (load-raw eng :model c)))]
    {:hashes (into #{}
                   (remove nil?)
                   (concat [(get-in transcript-raw [:data :key_hash])]
                           (map :hash (get-in seat [:data :fire_keys]))))
     :raw (into #{}
                (keep #(some-> (get-in % [:data :sitter_key]) str not-empty))
                [seat chair])}))

(defn- find-entry [st tx transcript-id file seq']
  (first (store/query-rows st tx :transcript_entry
                           {:transcript (str transcript-id) :file file :seq seq'}
                           {:limit 1})))

(defn- tool-of-use
  "The tool a tool_result answers, from an earlier post's tool_use."
  [st tx transcript-id use-id]
  (when-not (str/blank? use-id)
    (some-> (first (store/query-rows st tx :transcript_entry
                                     {:transcript (str transcript-id)
                                      :tool_use_id use-id}
                                     {:limit 5}))
            (get-in [:data :tool])
            str not-empty)))

(defn- at-of [s ^Instant now]
  (or (when s (try (Instant/parse (str s)) (catch Exception _ nil))) now))

(defn- diverged-sentence [file n]
  (str "File `" file "` diverged at line " n "."))

(defn- write-gap
  "The first gap stays: it is the one that says where the record broke."
  [data sentence]
  (if (some-> (:gap data) str not-empty) data (assoc data :gap sentence)))

(defn append!
  "The door's work, after the key and the body (R-5.4 to R-5.7). →
  {:outcome :ok|:behind|:diverged|:sealed, …}. The caller turns the
  outcome into the answer; a divergence is WRITTEN (the gap, the
  file's mark) in its own commit before the caller refuses, which is
  why this returns rather than throws."
  [eng transcript-id body]
  (let [st (:storage eng)
        ^Instant now ((:now-fn eng))
        line-max (long (opt eng :line-max-bytes default-line-max-bytes))
        max-bytes (long (opt eng :max-bytes default-max-bytes))
        {:keys [file from prior lines redactions harness_session run_session]} body
        from (long from)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :transcript (str transcript-id) {:for-update true})
              data (:data row)
              files (vec (:files data))
              idx (first (keep-indexed (fn [i f] (when (= file (:name f)) i)) files))
              fentry (if idx (nth files idx) {:name file :lines 0 :bytes 0 :chain zero-chain})
              held (long (:lines fentry))
              held-chain (str (:chain fentry))
              mark-diverged!
              (fn [n]
                (let [fentry' (assoc fentry :diverged true)
                      files' (if idx (assoc files idx fentry') (conj files fentry'))]
                  (store/update-data! st tx :transcript (str transcript-id)
                                      (-> data
                                          (assoc :files files')
                                          (write-gap (diverged-sentence file n)))
                                      (:next-flip-at row))
                  {:outcome :diverged :file file :line n :held held :chain held-chain}))]
          (cond
            (not= :open (:state row)) {:outcome :sealed}
            (true? (:diverged fentry)) {:outcome :diverged :file file :line held
                                        :held held :chain held-chain}
            (> from held) {:outcome :behind :file file :held held :chain held-chain}
            :else
            (let [prior-held (cond
                               (zero? from) zero-chain
                               (= from held) held-chain
                               :else (some-> (find-entry st tx transcript-id file (dec from))
                                             (get-in [:data :chain]) str))]
              (if (not= prior prior-held)
                (mark-diverged! from)
                ;; walk the post: the overlap must reproduce the held
                ;; chain, and what follows it is new
                (let [chains (vec (rest (reductions chain-next prior lines)))
                      overlap (min (count lines) (- held from))]
                  (if (and (pos? overlap)
                           (not= (nth chains (dec overlap))
                                 (if (= (+ from overlap) held)
                                   held-chain
                                   (some-> (find-entry st tx transcript-id file
                                                       (+ from (dec overlap)))
                                           (get-in [:data :chain]) str))))
                    ;; the overlap's last line does not reproduce the
                    ;; chain the engine holds at that line, so some
                    ;; line in between is different. A rare path, so
                    ;; it reads line by line to name the FIRST one.
                    (mark-diverged!
                     (or (first (keep (fn [i]
                                        (let [held-at (some-> (find-entry st tx transcript-id file
                                                                          (+ from i))
                                                              (get-in [:data :chain]) str)]
                                          (when (not= held-at (nth chains i))
                                            (+ from i))))
                                      (range overlap)))
                         (+ from (dec overlap))))
                    (let [fresh (drop overlap (map vector lines chains))
                          secrets (secrets-of eng row)
                          seat-id (str (get-in data [:seat]))
                          sitting-id (str (get-in data [:sitting]))
                          base-n (long (:lines data))
                          uses (volatile! {})
                          total (volatile! (long (:bytes data)))
                          engine-n (volatile! 0)
                          over? (volatile! false)
                          written
                          (doall
                           (map-indexed
                            (fn [i [^String line chain]]
                              (let [seq' (+ held i)
                                    bytes (utf8-length line)
                                    [clean k] (redact-line line secrets)
                                    _ (vswap! engine-n + (long k))
                                    d (derive-line clean)
                                    _ (when-not (str/blank? (:tool d))
                                        (vswap! uses assoc (:tool_use_id d) (:tool d)))
                                    tool (if (and (::result? d) (str/blank? (:tool d)))
                                           (or (get @uses (:tool_use_id d))
                                               (tool-of-use st tx transcript-id (:tool_use_id d))
                                               "")
                                           (:tool d))
                                    keep-raw? (<= (+ (long @total) bytes) max-bytes)
                                    _ (when-not keep-raw? (vreset! over? true))
                                    _ (vswap! total + bytes)
                                    ^bytes raw-bytes (.getBytes ^String clean StandardCharsets/UTF_8)
                                    cut (max 0 (- (alength raw-bytes) line-max))
                                    raw (when keep-raw?
                                          (if (pos? cut)
                                            (String. raw-bytes 0 (int line-max) StandardCharsets/UTF_8)
                                            clean))]
                                (inv/insert-quiet!
                                 eng tx :transcript_entry
                                 (cond-> {:transcript (str transcript-id)
                                          :sitting sitting-id
                                          :seat seat-id
                                          :file file
                                          :seq seq'
                                          :n (+ base-n i)
                                          :at (at-of (::at d) now)
                                          :type (:type d)
                                          :tool tool
                                          :tool_use_id (:tool_use_id d)
                                          :is_error (:is_error d)
                                          :text (:text d)
                                          :chain chain}
                                   (:usage d) (assoc :usage (:usage d))
                                   raw (assoc :raw raw)
                                   (and raw (pos? cut)) (assoc :raw_truncated cut))
                                 {:principal seats/seats-actor})
                                bytes))
                            fresh))
                          added (count written)
                          added-bytes (reduce + 0 written)
                          new-chain (if (pos? added) (second (last fresh)) held-chain)
                          fentry' (assoc fentry
                                         :lines (+ held added)
                                         :bytes (+ (long (:bytes fentry)) (long added-bytes))
                                         :chain new-chain)
                          files' (if idx (assoc files idx fentry') (conj files fentry'))
                          data' (cond-> (assoc data
                                               :files files'
                                               :lines (+ base-n added)
                                               :bytes (+ (long (:bytes data)) (long added-bytes))
                                               :uploads (inc (long (or (:uploads data) 0)))
                                               :engine_redactions (+ (long (or (:engine_redactions data) 0))
                                                                     (long @engine-n))
                                               :redactions (if (= from held)
                                                             ;; a resend that overlaps what is held
                                                             ;; carries counts for lines already
                                                             ;; counted, so only a post that starts
                                                             ;; at the held line adds its own
                                                             (merge-with + (or (:redactions data) {})
                                                                         (into {} (map (fn [[k v]] [(keyword (name k)) (long v)]))
                                                                               redactions))
                                                             (or (:redactions data) {}))
                                               :last_at (str now))
                                  (nil? (:first_at data)) (assoc :first_at (str now))
                                  (and harness_session (nil? (:harness_session data)))
                                  (assoc :harness_session harness_session)
                                  (and run_session (nil? (:run_session data)))
                                  (assoc :run_session run_session)
                                  @over? (write-gap (str "Raw lines were not kept past "
                                                         (quot max-bytes (* 1024 1024)) " MiB.")))]
                      (store/update-data! st tx :transcript (str transcript-id) data'
                                          (:next-flip-at row))
                      {:outcome :ok
                       :file file
                       :held (:lines fentry')
                       :chain new-chain
                       :lines (:lines data')
                       :bytes (:bytes data')})))))))))))

(defn upload!
  "The whole door: key, body, row. → the 200 document, or throws the
  refusal (R-5.5)."
  [eng key body]
  (let [row (or (transcript-by-key eng key)
                (throw (p/problem :not-found 404 "Not found" {:detail no-transcript})))
        body (judge-body body)
        out (append! eng (:id row) body)
        tid (str (:id row))
        sitting (str (get-in row [:data :sitting]))]
    (case (:outcome out)
      :ok {:waymark "10"
           :kind "transcript_upload"
           :transcript tid
           :sitting sitting
           :file (:file out)
           :held (:held out)
           :chain (:chain out)
           :lines (:lines out)
           :bytes (:bytes out)}
      :sealed (throw (p/problem :sealed 409 "Sealed"
                                {:detail (str "The transcript of sitting `" sitting
                                              "` is sealed.")}))
      :behind (throw (p/problem :not-held 409 "Lines missing"
                                {:detail (str "File `" (:file out) "` holds " (:held out)
                                              " lines. Send again from line " (:held out) ".")
                                 :held (:held out)
                                 :chain (:chain out)}))
      :diverged (throw (p/problem :diverged 409 "Diverged"
                                  {:detail (diverged-sentence (:file out) (:line out))
                                   :held (:held out)
                                   :chain (:chain out)})))))

;; ── the sweep (R-9) ─────────────────────────────────────────────────

(def ^:private sweep-page 500)

(defn- rows-in [eng kind where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit sweep-page}))))

(defn- older-than? [v ^Instant now ^long seconds]
  (when-some [^Instant at (at-of v nil)]
    (not (.isAfter (.plusSeconds at seconds) now))))

(defn- ended-at
  "When this transcript's sitting ended, or nil while it is open."
  [eng transcript-raw]
  (let [s (load-raw eng :sitting (get-in transcript-raw [:data :sitting]))]
    (cond
      (nil? s) (get-in transcript-raw [:data :last_at])
      (= :open (:state s)) nil
      :else (or (get-in s [:data :ended_at]) (:updated-at s)))))

(defn seal!
  "Seal one transcript (R-9.1, R-9.3): drop the key, stamp the moment,
  write the gap when the record is not whole, then walk `seal` as the
  engine."
  [eng transcript-raw ended]
  (let [st (:storage eng)
        ^Instant now ((:now-fn eng))
        id (str (:id transcript-raw))]
    (store/with-tx st
      (fn [tx]
        (when-some [row (store/load-row st tx :transcript id {:for-update true})]
          (let [d (:data row)
                last-at (at-of (:last_at d) nil)
                ended (at-of ended nil)
                gap (cond
                      (zero? (long (or (:lines d) 0))) "No transcript was received."
                      (and last-at ended (.isBefore ^Instant last-at ^Instant ended))
                      "The last post came before the sitting ended.")]
            (store/update-data! st tx :transcript id
                                (cond-> (-> d (dissoc :key_hash) (assoc :sealed_at (str now)))
                                  gap (write-gap gap))
                                (:next-flip-at row))))))
    (inv/invoke! eng :transcript id :seal {} {:principal seats/seats-actor})))

(defn purge-lines!
  "Delete every line of one transcript. → how many."
  [eng transcript-id]
  (let [st (:storage eng)]
    (loop [n 0]
      (let [ids (store/with-tx st
                  (fn [tx]
                    (store/ids-matching st tx :transcript_entry
                                        [{:target :data :field :transcript
                                          :cast "text" :op :=
                                          :value (str transcript-id)}]
                                        5000)))]
        (if (empty? ids)
          n
          (recur (+ n (long (store/with-tx st
                              (fn [tx] (store/delete-rows! st tx :transcript_entry ids)))))))))))

(defn sweep!
  "The transcripts' half of the seat pass. → {:sealed n :purged n}."
  [eng]
  (if-not (and (serves? eng) (contains? (inv/resources eng) :sitting))
    {:sealed 0 :purged 0}
    (let [^Instant now ((:now-fn eng))
          grace (long (opt eng :grace-seconds default-grace-seconds))
          sealed (reduce
                  (fn [n row]
                    (let [ended (ended-at eng row)]
                      (if (and ended (older-than? ended now grace))
                        (do (seal! eng row ended) (inc (long n)))
                        n)))
                  0 (rows-in eng :transcript {:state :open}))
          purged (reduce
                  (fn [n row]
                    (let [seat (load-raw eng :seat (get-in row [:data :seat]))
                          days (long (or (get-in seat [:data :transcript_days]) default-days))]
                      (if (older-than? (get-in row [:data :sealed_at]) now (* days 86400))
                        (do (purge-lines! eng (:id row))
                            (inv/invoke! eng :transcript (str (:id row)) :purge {}
                                         {:principal seats/seats-actor})
                            (inc (long n)))
                        n)))
                  0 (rows-in eng :transcript {:state :sealed}))]
      {:sealed sealed :purged purged})))

(defn after-purge!
  "THE WIRE-BOUNDARY EFFECT of a person's `purge` (R-9.5), called by
  the router after every committed invoke, beside
  `held/after-allow!`: the row moved to `purged`, and the lines go
  now, outside the transition's own transaction, because a purge of a
  long transcript is thousands of deletes. The sweep calls
  `purge-lines!` itself before its own purge. Every other write passes
  through untouched, and a replay deletes nothing twice."
  [eng rdef action out]
  (when (and (= :transcript (:kind rdef))
             (= :purge action)
             (map? out)
             (:transition out)
             (nil? (:replayed? out)))
    (purge-lines! eng (:id (:row out))))
  out)
