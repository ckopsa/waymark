(ns workqueue10.sources.gate-chat
  "The half tgram and messa share: how a thread source reaches Gate,
  how it reads a rig's answer, how it mints an etag, and how a name
  it has never seen becomes a row on the roster.

  HOW THE SOURCES REACH GATE (docs/spec-threads.md § 'How the sources
  reach Gate'): through `gate-proxy/rpc-of` — the engine's OWN Gate
  client and the seam it exposes — built once at wiring time in main
  and handed to both sources, so the thread confluence holds one MCP
  session, reused across passes and re-initialized by that client
  when Gate says it expired. No second transport is opened here.

  AND PAST `invoke-for`, deliberately. That door judges a CALLER's
  grant; a sync pass has no caller — no principal, no request, no
  X-Waymark-Grant — the mirror runs on the engine's own clock exactly
  as gtasks runs on a refresh token and flickr on LAN reach. Handing
  the pass a synthetic grant so a check could pass would be
  honouring nothing (gate_proxy's own sentence, about refusing a
  filtered grant). The leash lands where it belongs instead: the
  engine holds Gate's reach, the ROWS are grant-projected like any
  kind, and the mirror can never widen anybody's sight past what a
  thread row shows — because the translation drops the body before a
  document exists.

  WHAT COMES BACK. Every Gate tool answers MCP's tool-call shape: one
  JSON object per row as a separate `content` text part, AND the same
  rows as `structuredContent.result`. `rows` reads the structured
  array first — it is the rig's own structure, where the parts are
  its rendering — and falls back to parsing the parts when a rig
  answers without one.

  ETAGS are content hashes of the TRANSLATED document with the
  namespace's translation revision composed on: neither rig mints a
  version of its own, and a content hash can see the authority moving
  while the revision is what sees US moving (the flickr/gtasks
  spelling).

  THE OBSERVED BIRTH. A participant name the roster does not hold is
  written down as a `person` born `observed` — person.clj's own law:
  an agent may write down somebody it found in this house's record,
  the row says `observed` wherever it is cited, and only a person's
  tap makes it the house's. It is narrow on purpose (`person-name?`):
  a name mints a row only when it LOOKS like a person's name, so the
  roster does not fill with shortcodes and payroll robots. And it is
  best-effort — a birth that fails costs a ref its resolution and
  never the pass."
  (:require [clojure.string :as str]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(def default-limit
  "How many conversations a listing asks for. Generous on purpose:
  neither rig takes a `since=` (docs/spec-threads.md fork (e)), so the
  listing IS the window, and a window that always overlaps what the
  last pass saw is how a thread that moved is never missed. Cheap —
  one call, forty rows."
  40)

(defn rpc
  "The engine's Gate caller for the thread sources: `gate-proxy/rpc-of`
  over an optional url (unset = the deployment default the proxy
  names). Call it ONCE and share the result — the session is the
  connection reuse."
  [{:keys [url]}]
  (gate/rpc-of {:gate (cond-> {} (not (str/blank? (str url)))
                              (assoc :url (str url)))}))

(defn- parse-part [s]
  (try (wire/read-json s) (catch Exception _ nil)))

(defn rows
  "One tool answer → the seq of row maps it carries.
  `structuredContent.result` when the rig speaks one, else the
  `content` text parts parsed individually. An `isError` answer
  throws — the rig refused, which is unreachable for this pass and
  costs this source's rows a beat and nothing else."
  [answer]
  (when (:isError answer)
    (throw (ex-info (str "the rig refused: "
                         (->> (:content answer) (keep :text) (str/join " ")
                              (take 300) (apply str)))
                    {})))
  (let [structured (get-in answer [:structuredContent :result])]
    (if (sequential? structured)
      (vec structured)
      (into [] (comp (keep :text) (keep parse-part) (filter map?))
            (:content answer)))))

(defn call
  "One read at Gate: (rpc tool args) → the rows. `why` rides every
  call because Gate's approver reads it, and a read tool takes it
  optionally — saying it anyway is how the household can tell, in
  Gate's own log, which of its threads waymark asked about and what
  for."
  [rpc-fn tool args]
  (rows (rpc-fn "tools/call"
                {:name tool
                 :arguments (assoc args :__why
                                   (str "waymark: mirroring the thread list "
                                        "(titles, times and names — never "
                                        "what was said)"))})))

(defn content-etag
  "The translated document IS the version, with the source's
  translation revision composed on — neither rig mints one."
  [doc rev]
  (str (wire/sha256-hex (pr-str (into (sorted-map) doc))) "|" rev))

;; ── names ───────────────────────────────────────────────────────────

(def ^:private name-pattern
  ;; letters, marks, spaces and the punctuation a name actually
  ;; carries — and NO digits, which is the whole filter: "41646",
  ;; "(743) 222-5699" and "(304) 482-6884" are addresses the carrier
  ;; assigned, not people, and "Bros. 🧠" is a group's title rather
  ;; than anybody's name (an emoji is a symbol, not a letter).
  #"\p{L}[\p{L}\p{M}'’.\-\s]{1,79}")

(defn person-name?
  "Does this look like a person's name — the test a participant must
  pass before it mints a roster row? A name that fails still lands in
  :participant_names whole; it simply names nobody."
  [nm]
  (let [s (str/trim (str nm))]
    (boolean (and (>= (count s) 2) (re-matches name-pattern s)))))

(def ^:private observed-relation
  "The relation an observed birth carries. Free words, and honest
  about how little is known: this house has a conversation with them,
  which is all the rig said. A person's tap is what replaces it."
  "somebody this house exchanges messages with")

;; ── the scriptable twin ─────────────────────────────────────────────
;;
;; The fake is an in-memory GATE, not an in-memory source: it stands
;; behind the same (method params) seam the real client rides, so a
;; test exercises the real listing read, the real structured/parts
;; fallback, the real bot and kind filters and the real translation,
;; and only the socket is missing (flickr's fake, one layer up).

(defn fake-rpc
  "A Gate caller over a scriptable state atom:
  {:answers {tool [row …]} :down bool :structured? bool
   :calls [{:tool … :arguments …}]}.
  :structured? false makes it answer the CONTENT-PARTS shape instead
  of structuredContent, which is the fallback path `rows` keeps for
  a rig that answers without one."
  [state]
  (fn [method params]
    (when-not (= "tools/call" method)
      (throw (ex-info (str "the fake gate speaks no " method) {})))
    (swap! state update :calls (fnil conj [])
           {:tool (:name params) :arguments (:arguments params)})
    (when (:down @state)
      (throw (ex-info "Gate unreachable" {})))
    (let [rows (get-in @state [:answers (str (:name params))] [])]
      (if (false? (:structured? @state))
        {:isError false
         :content (mapv (fn [r] {:type "text" :text (wire/write-json r)}) rows)}
        {:isError false
         :content (mapv (fn [r] {:type "text" :text (wire/write-json r)}) rows)
         :structuredContent {:result (vec rows)}}))))

(defn fake-state
  "A fresh scriptable Gate: seed it with answer! / down! and read the
  calls back with calls."
  []
  (atom {:answers {} :down false :calls []}))

(defn answer!
  "Script one tool's listing — the rig's OWN shape, never a canonical
  document: the whole point of this twin is that the real translation
  and the real filters run."
  [state tool rows]
  (swap! state assoc-in [:answers (str tool)] (vec rows)))

(defn down! [state down?] (swap! state assoc :down (boolean down?)))

(defn parts-only!
  "Make the fake answer without structuredContent — the fallback path."
  [state]
  (swap! state assoc :structured? false))

(defn calls
  "Every call the source made, oldest first."
  [state]
  (:calls @state))

(defn roster-birth-fn
  "The observed birth's engine half, over the engine that stores the
  rows (flickr/engine-audience-fn's late-bound pattern): a name → the
  person row's id, minting one `observed` when the roster holds none.
  Best-effort in every direction — before the engine exists, or on
  any read or write failure, it simply has no opinion and the ref
  stays a renderable gap that the next pass heals.

  The read is one indexed lookup on person's :name (:eq-filterable
  for exactly this), so the common case — everybody already known —
  costs one query per participant and no writes at all."
  [{:keys [engine-ref]}]
  (fn [nm]
    (let [nm (str/trim (str nm))]
      (when (and (person-name? nm) (some-> engine-ref deref))
        (let [eng @engine-ref]
          (try
            (or (store/with-tx (:storage eng)
                  (fn [tx]
                    (some-> (first (store/query-rows (:storage eng) tx :person
                                                     {:name nm} {:limit 1}))
                            :id str)))
                (some-> (:row (inv/create!
                               eng :person
                               {:name nm :relation observed-relation}
                               {:principal (t/principal
                                            {:id "workqueue10-threads"
                                             :type :system
                                             :display "The thread mirror"})}))
                        :id str))
            (catch Exception _ nil)))))))
