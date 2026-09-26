(ns waymark10.server.mcp-servers
  "An MCP server as a row (docs/spec-mcp-servers.md, bead
  waymark-fp62.10): the `mcp_server` kind, the client the engine holds
  for each live row, and the resolution of a prefixed tool name to
  its row.

  THE ROW IS THE POLICY. A row names a server, says how to reach it
  (http url or stdio command), mirrors what the server offers
  (`tools`, from tools/list, with a hash), and holds `powers`: a list
  of entries {power, tools, approval, shown, constraints}. A power is
  the dotted token a grant names (email.read). `tools` are names or
  globs on this server. `approval` is what a call must pass before it
  goes out: `none`, `why` (one sentence of reason, and the call runs
  at once) or `person` (the sentence, and the call waits for a
  person's tap, in waymark10.server.held-calls). `why true` is the older
  spelling of `approval why` and still reads as one (`approval-of`).
  `shown` names the two or three input fields a held call's own line
  carries. `constraints` names the tool input fields a grant's filter may narrow
  this power by, and an entry naming none admits no filter at all
  (waymark-fp62.6.3.5). A tool that no entry names does not exist
  through the power door, whatever the server offers (R-5). This list
  replaced the static tool→token map that gate_proxy.clj used to
  hold.

  THE ROW IS ALSO THE VOCABULARY (waymark-fp62.10.4). A dotted token
  a non-retired row's powers name is a real token: `scope-names-real-
  kinds` reads `tokens-of-rows` first and the capability registry only
  for a token no row names. `sweep-capabilities!` retires, once at
  boot, the registry rows a server's powers have taken over.

  THE NAME IS THE PREFIX (R-2). A tool `read` on the server `emila`
  is `emila__read` to every caller. `resolve-tool` splits the name at
  the first `__`, finds the row that wears the prefix, and answers
  the bare name the server knows. One row, named `gate` with
  `passthrough` true, is the bridge of Gate's deprecation (R-13): its
  tools already wear their prefixes, so the engine adds nothing and
  matches the full name against its powers.

  AND THE TOKEN IS A NAME TOO (waymark-fp62.6.3.12). A power token
  that admits exactly ONE tool is a second spelling of that tool:
  `tool-name-of` answers `bench__read` for `bench.read`, and the
  power door resolves every name through it before it judges the
  grant. A token that admits two tools resolves to neither.

  ONE CLIENT PER ROW (R-3). `client-for` keeps one client per row id
  in a registry, keyed by the row's transport fields, so a restate
  that moves the url builds a new client and closes the old one.
  Each client has its own lock and its own timeout (R-12), so one
  hung server never stops another's calls. The engine's seam is
  (:services eng) :mcp-servers — {:client-fn (fn [row] client|nil),
  :gate-rpc, :call-timeout-ms, :discover-ms} — read from the ctx by
  the handlers and from the engine by everything else.

  DARK (R-8). `call!` marks a row dark when the client says the
  failure is fatal (an http wire failure; a stdio process that died
  three times in one window). A dark row answers every power with a
  503 that names `mark_live` as the remedy. A person's `mark_live`
  runs a discover first and refuses when the server does not answer.
  A row created against a server that does not answer is born dark,
  with the reason in `last_error`, because a birth is the one place
  the engine cannot invoke `mark_dark` on a row that does not exist
  yet.

  A SECRET NEVER LANDS (R-9). `auth_env` names an environment
  variable; the http client reads it at call time. A value that looks
  like a secret (a space, a colon, more than 64 characters) refuses
  at create and at restate.

  THE CADENCE. `start-discover-sweeper!` re-reads every live row's
  tools/list on an interval and walks the `discover` door only when
  the hash moved, so an unchanged list costs no transition. The hash
  it saw rides the door as its input (`discover-input`): the door
  mirrors what the server answers, and a door whose input never
  changes is a door invoke's natural replay can answer with the last
  pass's outcome."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.util.concurrent CountDownLatch TimeUnit)
           (java.util.regex Pattern)))

(set! *warn-on-reflection* true)

;; ── the engine's own hand ───────────────────────────────────────────

(def engine-actor
  "The system actor the boot seed, the sweeper and the dark flip
  write as."
  (t/principal {:id "waymark10-mcp-servers" :type :system
                :display "MCP servers"}))

(def default-gate-url
  "Where Gate answers on the household LAN. It is the seed of the gate
  row's url in `ensure-gate-row!` and nothing else reads it."
  "http://192.168.1.40:8100/mcp/")

(def default-discover-ms
  "How often the sweeper re-reads every live server's tool list, and
  the window a stdio client counts its deaths in."
  900000)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 mcp-servers: " parts))))

(defn- brief [s]
  (let [s (str s)]
    (if (< 500 (count s)) (subs s 0 500) s)))

;; ── the seam ────────────────────────────────────────────────────────

(defn seam-of
  "The engine's mcp-servers seam: (:services eng) :mcp-servers."
  [eng]
  (get-in eng [:services :mcp-servers]))

(defn- seam-of-ctx [ctx]
  (get-in ctx [:services :mcp-servers]))

;; ── the client registry ─────────────────────────────────────────────

(defonce ^:private clients
  ;; row id → {:key config :client fn :lock Object}
  (atom {}))

(defonce ^:private build-lock (Object.))

(defn- config-key [row]
  (let [d (:data row)]
    [(str (:transport d)) (:url d) (:command d) (vec (:args d))
     (:auth_env d) (boolean (:passthrough d))]))

(defn- headers-fn
  "The http client's extra headers, read from the environment on
  every call (R-9). nil when the row names no variable."
  [env-name]
  (when-not (str/blank? (str env-name))
    (fn []
      (let [v (System/getenv (str env-name))]
        (when-not (str/blank? (str v))
          {"Authorization" (str v)})))))

(defn- build-client [seam row]
  (let [d (:data row)
        timeout (long (or (:call-timeout-ms seam) 30000))
        seeded (or (when-some [f (:client-fn seam)] (f row))
                   (when (and (= "gate" (str (:name d))) (:gate-rpc seam))
                     (:gate-rpc seam)))
        raw (cond
              seeded seeded

              (= "stdio" (str (:transport d)))
              (client/stdio-client
               {:command (:command d) :args (:args d)
                :timeout-ms timeout
                :window-ms (long (or (:discover-ms seam) default-discover-ms))})

              :else
              (client/http-client (str (:url d))
                                  {:headers-fn (headers-fn (:auth_env d))
                                   :timeout-ms timeout}))]
    (client/with-timeout raw timeout)))

(defn client-for
  "The row's client entry {:client :lock}, built on first use and
  rebuilt when the row's transport fields moved."
  [seam row]
  (let [id (str (:id row))
        k (config-key row)]
    (locking build-lock
      (let [e (get @clients id)]
        (if (and e (= k (:key e)))
          e
          (let [fresh {:key k :client (build-client seam row) :lock (Object.)}]
            (when e (client/close! (:client e)))
            (swap! clients assoc id fresh)
            fresh))))))

(defn drop-client!
  "Close and forget a row's client."
  [row-id]
  (locking build-lock
    (when-some [e (get @clients (str row-id))]
      (client/close! (:client e))
      (swap! clients dissoc (str row-id)))
    nil))

(defn- wire!
  "One call on a row's client, under the row's lock. Throws the
  client's problem."
  [seam row method params]
  (let [{:keys [client lock]} (client-for seam row)]
    (locking lock (client method params))))

(defn- fatal?
  "Did the row's client call its last failure fatal?"
  [seam row]
  (client/dead? (:client (client-for seam row))))

;; ── the rows ────────────────────────────────────────────────────────

(defn- rdef-of [eng] (get (inv/resources eng) :mcp_server))

(defn rows
  "Every mcp_server row that is not retired, decoded."
  [eng]
  (if-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (->> (store/with-tx st
             (fn [tx] (store/query-rows st tx :mcp_server {} {:limit 1000})))
           (map #(inv/decode-row rd %))
           (remove #(= :retired (:state %)))
           vec))
    []))

(defn row-by-name
  "The row named `nm`, in any state, or nil."
  [eng nm]
  (when-some [rd (rdef-of eng)]
    (let [st (:storage eng)]
      (some->> (store/with-tx st
                 (fn [tx] (first (store/query-rows st tx :mcp_server
                                                   {:name (str nm)}
                                                   {:limit 1}))))
               (inv/decode-row rd)))))

;; ── resolution ──────────────────────────────────────────────────────

(defn split-name
  "A prefixed tool name → [prefix bare], or nil when it wears none."
  [tool]
  (let [s (str tool)
        i (.indexOf s "__")]
    (when (pos? i)
      [(subs s 0 i) (subs s (+ i 2))])))

(defn glob-matches?
  "Does `pattern` (a name, or a glob with `*`) match `tool`?"
  [pattern tool]
  (let [parts (str/split (str pattern) #"\*" -1)
        re (re-pattern (str "^" (str/join ".*" (map #(Pattern/quote %) parts))
                            "$"))]
    (boolean (re-matches re (str tool)))))

(defn entry-for
  "The first powers entry of `row` whose tools name `bare`, or nil."
  [row bare]
  (some (fn [e]
          (when (some #(glob-matches? % bare) (:tools e)) e))
        (get-in row [:data :powers])))

;; ── the approval a powers entry demands (waymark-fp62.10.2) ─────────

(def approvals
  "What an entry may demand before a call goes out, weakest first.

  `none` forwards at once. `why` demands one sentence of reason and
  forwards at once, which is what `why true` has always meant. `person`
  demands the same sentence and holds the call until a person allows
  it (R-14 of docs/spec-mcp-servers.md)."
  ["none" "why" "person"])

(defn approval-of
  "The approval this entry demands, as a keyword: `:none`, `:why` or
  `:person`.

  THE ENGINE READS BOTH SPELLINGS AND WRITES THE NEW ONE. `approval`
  is the field a person states now. `why true` is the older spelling
  of `approval why`, and a row written before this leg still carries
  it, so an entry that names no `approval` and says `why true` reads
  as `:why`. An entry that names both is judged at the write door
  (`entry-approval-agrees?`), so the two can never disagree here."
  [entry]
  (let [a (str/lower-case (str (:approval entry)))]
    (cond
      (= "person" a) :person
      (= "why" a) :why
      (= "none" a) :none
      (true? (:why entry)) :why
      :else :none)))

(defn why-demanded?
  "Must a call on this entry carry one sentence of reason? Both
  `why` and `person` demand it: a person who must tap reads the
  sentence first, so an approval that held the call and had nothing
  to show would be a notice with no words on it."
  [entry]
  (not= :none (approval-of entry)))

(defn person-approval?
  "Does this entry hold the call until a person allows it?"
  [entry]
  (= :person (approval-of entry)))

(defn shown-fields
  "The input fields this entry marks as `shown` (R-8): the two or
  three the person reads on the held call's own line. Names only, in
  the order the entry wrote them."
  [entry]
  (into [] (comp (map str) (remove str/blank?)) (:shown entry)))

(defn- passthrough? [row]
  (true? (get-in row [:data :passthrough])))

(defn resolve-tool
  "A prefixed tool name → {:row :bare :entry :token :why}, or nil
  when no row answers to it. The row wearing the prefix wins. When
  none does, a passthrough row answers the full name: the one whose
  powers name it, else the first."
  [eng tool]
  (let [tool (str tool)
        rs (rows eng)
        by-prefix (when-some [[prefix bare] (split-name tool)]
                    (some (fn [row]
                            (when (and (= prefix (str (get-in row [:data :name])))
                                       (not (passthrough? row)))
                              {:row row :bare bare}))
                          rs))
        through (when (nil? by-prefix)
                  (let [pts (filter passthrough? rs)]
                    (when-some [row (or (some #(when (entry-for % tool) %) pts)
                                        (first pts))]
                      {:row row :bare tool})))
        hit (or by-prefix through)]
    (when hit
      (let [entry (entry-for (:row hit) (:bare hit))]
        (assoc hit
               :entry entry
               :token (some-> (:power entry) str)
               ;; `:why` is the DEMAND for a sentence and not the
               ;; entry's old boolean: an entry that says
               ;; `approval person` demands one too
               :why (boolean (and entry (why-demanded? entry)))
               :approval (if entry (approval-of entry) :none))))))

(defn capability-of
  "The power token a prefixed tool name is bound to, or nil."
  [eng tool]
  (:token (resolve-tool eng tool)))

(defn why-required?
  "Must a call on this tool carry a `why`?"
  [eng tool]
  (boolean (:why (resolve-tool eng tool))))

(defn prefixed-name
  "The name a caller uses for `bare` on `row`."
  [row bare]
  (if (passthrough? row)
    (str bare)
    (str (get-in row [:data :name]) "__" bare)))

(defn token-tools
  "Every tool a power TOKEN admits, as a caller spells them:
  `bench.read` → [\"bench__read\"]. The entries of every row that is
  not retired, whose `power` is this token, their tool names carried through
  `prefixed-name`, in order and without repeats.

  A GLOB IS LEFT OUT. `list_*` names no one tool, so a token whose
  entry carries one cannot be read as a tool name at all: the names
  here are the ones a caller could have typed."
  [eng token]
  (let [tk (str token)]
    (if (str/blank? tk)
      []
      (vec (distinct
            (for [row (rows eng)
                  e (get-in row [:data :powers])
                  :when (= tk (str (:power e)))
                  t (:tools e)
                  :let [nm (str t)]
                  :when (and (not (str/blank? nm))
                             (not (str/includes? nm "*")))]
              (prefixed-name row nm)))))))

(defn token-tool
  "The ONE tool a power token admits, or nil when it admits none or
  more than one. THE RULE IS SPELLED HERE ONCE: a token that admits
  exactly one tool may be read as the name of that tool
  (`tool-name-of`), and the sit names that tool for each bench power a
  seat holds (R-12.29). A token that admits two tools names neither."
  [eng token]
  (let [ts (token-tools eng token)]
    (when (= 1 (count ts)) (first ts))))

(defn tool-name-of
  "THE NAME THIS DOOR RESOLVES, for a caller that typed either
  spelling (waymark-fp62.6.3.12).

  A name that is a tool is itself. A name that is not a tool, but is
  a power TOKEN a live row names and that token admits exactly ONE
  tool, is that tool: `bench.read` → `bench__read`. Every other
  name is itself, and the door refuses it as it always did — a
  token with two tools names neither, and a name no row answers to is
  no name here.

  It is TEXT AND ROWS only. The grant, the filter and the why are
  judged after it, on the tool it answers."
  [eng nm]
  (let [nm (str nm)]
    (if (:entry (resolve-tool eng nm))
      nm
      (or (token-tool eng nm) nm))))

(defn offered
  "Every mirrored tool of every LIVE row, with its resolution:
  [{:name :bare :row :entry :token :why :description :input-schema} …]."
  [eng]
  (vec
   (for [row (rows eng)
         :when (= :live (:state row))
         tool (get-in row [:data :tools])
         :let [bare (str (:name tool))
               entry (entry-for row bare)]]
     {:name (prefixed-name row bare)
      :bare bare
      :row row
      :entry entry
      :token (some-> (:power entry) str)
      :why (boolean (and entry (why-demanded? entry)))
      :approval (if entry (approval-of entry) :none)
      :description (str (:description tool))
      :input-schema (:input_schema tool)})))

(defn tokens-of-rows
  "Every power token this page of rows' powers name, sorted. It takes
  ROWS and not the engine because the guard that judges a scope holds
  neither — it reads its page through the ctx `:find` hook, the same
  way it reads the registry — and a guard that re-collected the
  tokens would be a second definition of the vocabulary, right on the
  day it was written and wrong on the next."
  [rows]
  (vec (sort (distinct
              (for [row rows
                    e (get-in row [:data :powers])
                    :let [tk (:power e)]
                    :when (not (str/blank? (str tk)))]
                (str tk))))))

(defn constraints-of-rows
  "The fields a grant's FILTER may name for each power token this page
  of rows carries → {token #{field …}} (waymark-fp62.6.3.5).

  It takes ROWS for `tokens-of-rows`' reason verbatim: the guard that
  judges a filtered scope entry holds no engine and reads its page
  through the ctx `:find` hook, and a guard that re-collected the
  fields would be a second definition of the constraint.

  A token every entry names with no `constraints` maps to the EMPTY
  set, which is not the same as absent: the empty set says a server
  names this power and admits no filter on it, and absent says no
  server names it at all (the capability registry's own powers —
  `schedule.write` — whose enforcement point is this engine and none
  of this door's business). Two entries naming one token
  union their fields, because the token is what a grant names and a
  token cannot admit a field on Tuesdays only."
  [rows]
  (reduce (fn [m [tk fields]] (update m tk (fnil into #{}) fields))
          {}
          (for [row rows
                e (get-in row [:data :powers])
                :let [tk (str (:power e))]
                :when (not (str/blank? tk))]
            [tk (into #{} (comp (map str) (remove str/blank?))
                      (:constraints e))])))

(defn power-tokens
  "Every power token any row's powers name, sorted."
  [eng]
  (tokens-of-rows (rows eng)))

(defn power-constraints
  "Every power token that admits a filter, with the fields it admits,
  sorted → {token [field …]}. A token that admits none is ABSENT
  rather than empty: discover's `doors.ask.constraints` and
  `waymark_powers` publish this map, and an agent reading a token
  there knows it may narrow the ask — a token missing from it is a
  token to ask for whole."
  [eng]
  (into (sorted-map)
        (keep (fn [[tk fields]] (when (seq fields) [tk (vec (sort fields))])))
        (constraints-of-rows (rows eng))))

;; ── the registry beside the rows (waymark-fp62.10.4) ────────────────
;;
;; Since this leg the powers list is the FIRST vocabulary: a dotted
;; token a non-retired row names is a real token because the row says
;; so. The capability registry answers for the tokens no server names
;; — this engine's own powers, today schedule.write —
;; and that is the whole of what is left to it.

(defn- capability-rows
  "Every `capability` row, undecoded: the registry is flat data (a
  token, a state, a pointer at whoever enforces), so the decode a
  door's ctx would do buys nothing here. An engine that serves no
  such kind has no registry, which is not an error — it is an engine
  whose whole vocabulary is its rows."
  [eng]
  (if (contains? (inv/resources eng) :capability)
    (let [st (:storage eng)]
      (vec (store/with-tx st
             (fn [tx] (store/query-rows st tx :capability {} {:limit 1000})))))
    []))

(defn registered-tokens
  "Every ACTIVE capability row's token, sorted."
  [eng]
  (vec (sort (distinct (for [row (capability-rows eng)
                             :when (= :active (:state row))
                             :let [tk (get-in row [:data :token])]
                             :when (not (str/blank? (str tk)))]
                         (str tk))))))

(defn nameable-tokens
  "Every dotted token a scope may name: the servers' powers, and the
  standing capability rows beside them. What discover's
  doors.ask.powers lists — an agent composing an ask reads ONE list,
  because a vocabulary spelled in two places is two vocabularies."
  [eng]
  (vec (sort (distinct (concat (power-tokens eng) (registered-tokens eng))))))

;; ── the mirror ──────────────────────────────────────────────────────

(defn tools-hash [tools]
  (wire/sha256-hex (wire/write-json tools)))

(defn- mirror-entry [tool]
  {:name (str (:name tool))
   :description (str (or (:description tool) ""))
   :input_schema (or (:inputSchema tool) {:type "object" :properties {}})})

(defn fetch-tools!
  "tools/list on the row's client → {:tools […] :hash s}, the tools
  sorted by name. Throws the client's problem."
  [seam row]
  (let [answer (wire! seam row "tools/list" {})
        tools (->> (:tools answer) (map mirror-entry) (sort-by :name) vec)]
    {:tools tools :hash (tools-hash tools)}))

(defn- mirror [row {:keys [tools hash]} now]
  (update row :data assoc
          :tools tools
          :tools_hash hash
          :discovered_at now
          :last_error nil))

;; ── calls past the grant ────────────────────────────────────────────

(defn dark-problem [row]
  (p/problem :mcp-server-dark 503 "Server dark"
             {:detail (str "The server " (get-in row [:data :name])
                           " is dark"
                           (when-some [e (get-in row [:data :last_error])]
                             (str " (" e ")"))
                           ". No power reaches it until a person marks"
                           " it live.")
              :remedies [(str "POST /api/mcp_servers/" (:id row)
                              "/-/mark_live — the engine discovers the"
                              " server's tools first, and refuses when"
                              " the server does not answer.")]}))

(defn- darken!
  "The engine's mark_dark, best-effort, and the client closed."
  [eng row detail]
  (try
    (inv/invoke! eng :mcp_server (str (:id row)) :mark_dark
                 {:error (brief detail)} {:principal engine-actor})
    (catch Exception e
      (warn! "mark_dark for " (get-in row [:data :name]) " did not land ("
             (ex-message e) ")")))
  (drop-client! (:id row)))

(defn call!
  "One tools/call by prefixed name, past the grant — the engine's own
  hand. → the CallToolResult. Refuses 404 when no row answers to the
  name, 503 when the row is dark. A failure the client calls fatal
  marks the row dark and rethrows."
  [eng tool args]
  (let [{:keys [row bare] :as hit} (resolve-tool eng tool)]
    (when (nil? hit)
      (throw (p/not-found "power" (str tool))))
    (when (= :dark (:state row))
      (throw (dark-problem row)))
    (let [seam (seam-of eng)]
      (try
        (wire! seam row "tools/call" {:name bare :arguments (or args {})})
        (catch Exception e
          (when (and (p/problem? e) (fatal? seam row))
            (darken! eng row (ex-message e)))
          (throw e))))))

(defn engine-of
  "The engine behind a dispatcher, a delay, an engine-ref or the
  engine itself; nil when there is none yet."
  [x]
  (cond
    (nil? x) nil
    (map? x) x
    (instance? clojure.lang.IDeref x) (engine-of @x)
    (ifn? x) (engine-of (::engine (meta x)))
    :else nil))

(defn rpc-of
  "The engine's dispatcher: (fn [method params]) in the shape the
  sources and the surfaces have always held. tools/list answers the
  mirrored tools of every live row, prefixed. tools/call resolves the
  name to a row and forwards through its client. `eng-or-ref` may be
  the engine or something that derefs to it, read on every call, so
  a source built before the boot holds a dispatcher that works after
  it."
  [eng-or-ref]
  (with-meta
    (fn dispatch [method params]
      (let [eng (engine-of eng-or-ref)]
        (when (nil? eng)
          (throw (client/unreachable "the engine is not started yet.")))
        (case (str method)
          "tools/list"
          {:tools (mapv (fn [o] {:name (:name o)
                                 :description (:description o)
                                 :inputSchema (:input-schema o)})
                        (offered eng))}

          "tools/call"
          (call! eng (str (:name params)) (:arguments params))

          (throw (client/unreachable
                  (str "this engine speaks no " method " to a server."))))))
    {::engine eng-or-ref}))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard a-person-or-the-engine
  {:reads [:principal]
   :open "No door opens a server to an agent: the person who runs this instance adds a server, and the engine's own boot seeds the gate row."
   :explain "A server is a person's row: a person creates it, restates it, marks it live and retires it, in person or through a tool the person is signed in to. An agent does not add a server to the instance it sits in."}
  [_row _inp ctx]
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (= :system type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(g/defguard the-engines-own-hand
  {:reads [:principal]
   :hide true
   :explain "The engine marks a server dark when a call fails on the wire; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(def name-pattern
  "A prefix: lowercase letters, digits and single underscores, and
  never a double underscore, because `__` is where a caller's name
  splits."
  #"[a-z][a-z0-9]*(_[a-z0-9]+)*")

(g/defguard one-server-spelling
  {:judges [:name]
   :reads [:mcp_server]
   :open "The names on record are the mcp_servers collection, one query away; the spelling rule is the prefix's own: lowercase letters, digits and single underscores."
   :explain "The name {name} is not one this instance can use. A name is lowercase letters, digits and single underscores, and no double underscore, because every tool of this server is called <name>__<tool>; and one name is on record once. Restate the row that holds it, or restore it if it was retired."}
  [_row inp ctx]
  (let [nm (str (:name inp))]
    (cond
      (not (re-matches name-pattern nm))
      (t/deny {:vars {:name nm}})

      (if-some [find' (:find ctx)]
        (seq (find' :mcp_server {:name nm} {:limit 1}))
        false)
      (t/deny {:vars {:name nm}})

      :else (t/allow))))

(defn looks-like-a-value?
  "R-9: a space, a colon, or more than 64 characters is a value, not
  the name of a variable."
  [s]
  (let [s (str s)]
    (or (str/includes? s " ")
        (str/includes? s ":")
        (< 64 (count s)))))

;; It reads auth_env from the input and judges no field's closure: the
;; rule is about what the text IS (a name, never a value), which no
;; enum or picker can offer.
(g/defguard auth-env-is-a-name
  {:open "No door takes a secret: the row names the variable, and the deployment sets it on the engine's host."
   :explain "auth_env names an environment variable, never the value: no space, no colon, at most 64 characters. Put the header value in a variable on the engine's host and name the variable here."}
  [_row inp _ctx]
  (if (and (some? (:auth_env inp)) (looks-like-a-value? (:auth_env inp)))
    (t/deny)
    (t/allow)))

(defn- address-fits? [transport url command]
  (case (str transport)
    "http" (not (str/blank? (str url)))
    "stdio" (not (str/blank? (str command)))
    false))

;; The pair (url, command) is judged against the transport, which no
;; one field's closure can advertise, so the create guard judges the
;; transport and the restate guard judges the pair against the row.
(g/defguard transport-has-its-address
  {:judges [:transport]
   :open "Each transport has one address field: url for http, command for stdio."
   :explain "An http server names its url and a stdio server names its command. Give the field the transport needs."}
  [_row inp _ctx]
  (if (address-fits? (:transport inp) (:url inp) (:command inp))
    (t/allow)
    (t/deny)))

(g/defguard address-fits-the-transport
  {:open "A restate keeps the transport the row was created with: url for http, command for stdio."
   :explain "This server keeps its transport: an http server keeps a url and a stdio server keeps a command. A restate may not empty the field the transport needs."}
  [row inp _ctx]
  (let [d (:data row)
        url (if (contains? inp :url) (:url inp) (:url d))
        command (if (contains? inp :command) (:command inp) (:command d))]
    (if (address-fits? (:transport d) url command)
      (t/allow)
      (t/deny))))

(g/defguard passthrough-is-the-gates
  {:judges [:passthrough]
   :open "passthrough exists for the one row named gate and for nothing else; every other server's tools wear the server's name."
   :explain "Only the server named gate may carry passthrough: its tools already wear their prefixes. Every other server wears its own name in front of every tool, so leave passthrough off."}
  [_row inp _ctx]
  (if (and (true? (:passthrough inp)) (not= "gate" (str (:name inp))))
    (t/deny)
    (t/allow)))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler discover-server [row _inp ctx]
  (mirror row (fetch-tools! (get-in ctx [:services :mcp-servers]) row)
          (:now ctx)))

(def restatable
  "The fields a restate states again (R-8)."
  [:url :command :args :auth_env :powers :note])

(defhandler restate-server [row inp ctx]
  (let [row (reduce (fn [r f]
                      (if (contains? inp f)
                        (assoc-in r [:data f] (get inp f))
                        r))
                    row restatable)]
    (mirror row (fetch-tools! (get-in ctx [:services :mcp-servers]) row)
            (:now ctx))))

(defhandler mark-dark-server [row inp _ctx]
  (update row :data assoc :last_error (:error inp)))

(defn- born
  "The create's discover (R-4). A server that does not answer makes a
  row born dark with the reason on it."
  [row ctx]
  (try
    (mirror row (fetch-tools! (seam-of-ctx ctx) row) (:now ctx))
    (catch Exception e
      (-> row
          (assoc :state :dark)
          (update :data assoc :last_error (brief (ex-message e)))))))

;; ── scenarios ───────────────────────────────────────────────────────

(def ^:private a-live-server
  {:name "bench" :transport "stdio" :command "python3"
   :args ["-m" "bench" "--stdio"]
   :powers [{:power "bench.read" :tools ["find" "read" "status"] :why false
             :constraints ["repo" "path"]}]})

(defscenario an-agent-does-not-retire-a-server
  "An agent that could retire a server could take a power off the
   instance it sits in."
  {:kind    :mcp_server
   :attempt :retire
   :row     {:state :live :data a-live-server}
   :as      {:id "clerk" :type :agent}
   :expect  {:refused :a-person-or-the-engine
             :because "a person's row"}})

(defscenario the-person-retires-the-server
  "And the person whose instance it is retires it in one tap."
  {:kind    :mcp_server
   :attempt :retire
   :row     {:state :live :data a-live-server}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

(defscenario an-agent-does-not-mark-a-server-live
  "A dark server stays dark until a person says so."
  {:kind    :mcp_server
   :attempt :mark_live
   :row     {:state :dark :data a-live-server}
   :as      {:id "clerk" :type :agent}
   :expect  {:refused :a-person-or-the-engine
             :because "a person's row"}})

(defscenario the-person-marks-the-server-live
  "The person's tap opens the door; the handler then discovers."
  {:kind    :mcp_server
   :attempt :mark_live
   :row     {:state :dark :data a-live-server}
   :as      {:id "colton" :type :human}
   :expect  {:allowed true}})

(defscenario an-agent-does-not-restate-a-server
  "The powers list is the policy, and an agent does not write policy."
  {:kind    :mcp_server
   :attempt :restate
   :row     {:state :live :data a-live-server}
   :input   {:powers []}
   :as      {:id "clerk" :type :agent}
   :expect  {:refused :a-person-or-the-engine
             :because "a person's row"}})

(defscenario a-restate-does-not-carry-a-secret-as-auth-env
  "R-9: the row names the variable and never holds the value."
  {:kind    :mcp_server
   :attempt :restate
   :row     {:state :live :data a-live-server}
   :input   {:auth_env "Bearer abc123"}
   :as      {:id "colton" :type :human}
   :expect  {:refused :auth-env-is-a-name
             :because "never the value"}})

(defscenario a-create-does-not-carry-a-secret-as-auth-env
  "The same wall at birth."
  {:kind    :mcp_server
   :attempt :create
   :input   {:name "emila" :transport "http" :url "http://emila:8000/mcp/"
             :auth_env "Bearer abc123"}
   :as      {:id "colton" :type :human}
   :expect  {:refused :auth-env-is-a-name
             :because "never the value"}})

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private power-entry-fields
  [:map
   [:power {:examples ["email.read"]
            :x-display {:raw true
                        :label "Power"
                        :help "The dotted token a grant names, such as email.read."}}
    [:string {:min 1 :max 60}]]
   [:tools {:examples [["read" "search"]]
            :x-display {:label "Tools"
                        :help "Tool names on this server, or globs with * — read, search, list_*."}}
    [:vector [:string {:min 1 :max 120}]]]
   [:why {:optional true
          :x-display {:label "A why is required"
                      :help "The older spelling of an approval: true means the same as approval why. State approval instead; this field stays here because rows written before it exist."}}
    [:maybe :boolean]]
   ;; waymark-fp62.10.2: what a call on this power must pass before it
   ;; goes out. `none` forwards at once, `why` demands one sentence,
   ;; and `person` demands the sentence and HOLDS the call in a
   ;; held_call row until a person taps allow (R-14).
   [:approval {:optional true
               :examples ["person"]
               :x-display {:label "Approval"
                           :choices {"none" "none: the call goes out at once"
                                     "why" "why: the call carries one sentence of reason and goes out at once"
                                     "person" "person: the call carries the sentence and waits for a person to allow it"}
                           :help "What a call on this power must pass before the engine makes it. person holds the call: the engine mints a held_call row, answers the caller that it is waiting, and forwards only after a person allows it."}}
    [:maybe (into [:enum] approvals)]]
   ;; R-8: the row IS the notice, so the person's line names the two
   ;; or three input fields that say what the call would do. A send's
   ;; recipient and text; a payment's amount. The list is on the
   ;; ENTRY, because only the policy knows which fields of which tool
   ;; carry the consequence.
   [:shown {:optional true
            :examples [["to" "text"]]
            :x-display {:label "Fields shown to the approver"
                        :help "The two or three tool input fields the held call's own line names, such as to and text for a send. A person reads this line and decides. Keep it short."}}
    [:maybe [:vector {:max 3} [:string {:min 1 :max 60}]]]]
   ;; waymark-fp62.6.3.5: the row says which SENTENCES a grant may
   ;; narrow this power with. The power door interprets a filter only
   ;; on a field named here, so a server that has not taught the door
   ;; how to hold itself to a field cannot be granted narrowly by
   ;; accident — an entry listing nothing admits no filtered grant at
   ;; all, and the ask refuses before a person ever taps it.
   [:constraints {:optional true
                  :examples [["repo" "path"]]
                  :x-display {:label "Constraints"
                              :help "The tool input fields a grant's filter may name for this power — repo, path. Leave it empty and no grant may filter this power at all."}}
    [:maybe [:vector [:string {:min 1 :max 60}]]]]])

(defn entry-approval-agrees?
  "R-14's one rule a `:map` cannot say: the two spellings of the same
  demand must not contradict each other.

  `why true` beside `approval none` is a policy that says both `ask
  for a sentence` and `ask for nothing`. `why false` beside `approval
  why` or `approval person` is the same contradiction the other way
  round. Either field alone is fine, and `why true` beside `approval
  why` or `approval person` agrees, because both of those demand the
  sentence. A non-map answers true: the map beside this one has
  already refused it, and one wrong entry owes a person one
  sentence."
  [e]
  (if-not (map? e)
    true
    (let [a (str/lower-case (str (:approval e)))]
      (cond
        (str/blank? a) true
        (true? (:why e)) (not= "none" a)
        (false? (:why e)) (= "none" a)
        :else true))))

(def ^:private power-entry
  "One `powers` entry: the fields above, and the rule that `why` and
  `approval` say the same thing.

  The rule is malli's, not a guard's, so it lands where a person's
  eyes are, which is the entry's own place in the 422, and it lands at the
  create door and the restate door without either of them repeating
  it. The `:json-schema` property is the MAP's own, for the seat's
  `wake_on` reason verbatim: a client draws a list of maps as rows
  only when the items projection carries `properties`, and an `:and`
  projects to `allOf`, which carries none."
  [:and
   {:json-schema (schema/json-schema power-entry-fields)}
   power-entry-fields
   [:fn {:error/message "An entry says why or approval, and the two must agree: why true is approval why, so it cannot sit beside approval none."}
    #'entry-approval-agrees?]])

(def ^:private tool-entry
  [:map
   [:name [:string {:min 1 :max 120}]]
   [:description {:optional true :x-display {:raw true}}
    [:maybe [:string {:max 4000}]]]
   [:input_schema {:optional true} [:maybe [:map-of :keyword :any]]]])

(def ^:private note-example
  "The prose field's starting text: a blank box is the heaviest demand
  the vocabulary has, and every other prose field in this engine hands
  the caller a sentence to write over (seats' charter is the pattern)."
  (str "The household's mail rig, on the LAN. The token lives in "
       "EMILA_TOKEN on the engine's host. Ask Colton before you move "
       "its address."))

(def ^:private person-fields
  [[:name {:examples ["emila"]
           :x-display {:raw true
                       :label "Name"
                       :help "The prefix every tool of this server wears: a tool read on emila is emila__read to every caller. Lowercase letters, digits and single underscores."}}
    [:string {:min 1 :max 40}]]
   [:transport {:x-display {:label "Transport"
                            :choices {"http" "HTTP — the engine posts to the server's url"
                                      "stdio" "stdio — the engine starts the command and holds its pipes"}}}
    [:enum "http" "stdio"]]
   [:url {:optional true :examples ["http://192.168.1.40:8100/mcp/"]
          :x-display {:raw true
                      :label "URL"
                      :help "Where an http server answers MCP."}}
    [:maybe [:string {:max 400}]]]
   [:command {:optional true :examples ["python3"]
              :x-display {:raw true
                          :label "Command"
                          :help "What a stdio server is started with."}}
    [:maybe [:string {:max 400}]]]
   [:args {:optional true :examples [["-m" "bench" "--stdio" "--config" "bench.json"]]
           :x-display {:label "Arguments"
                       :help "The command's arguments, one each."}}
    [:maybe [:vector [:string {:max 400}]]]]
   [:auth_env {:optional true :examples ["EMILA_TOKEN"]
               :x-display {:raw true
                           :label "Auth variable"
                           :help "The NAME of an environment variable on the engine's host that holds the Authorization header value. Never the value."}}
    [:maybe [:string {:max 64}]]]
   [:passthrough {:optional true
                  :x-display {:label "Passthrough"
                              :help "True only on the row named gate: its tools already wear their prefixes, and the engine adds nothing in front."}}
    [:maybe :boolean]]
   [:powers {:optional true
             :examples [[{:power "email.read" :tools ["read" "search"] :why false}]]
             :x-display {:label "Powers"
                         :help "The policy: which tools each power token admits, and what a call must pass before it goes out: nothing, one sentence of reason, or a person's tap. A tool that no entry names does not exist through the power door."}}
    [:maybe [:vector power-entry]]]
   [:note {:optional true
           :examples [note-example]
           :x-display {:widget "prose"
                       :label "Note"
                       :help "Anything the next person should know about this server."}}
    [:maybe [:string {:max 500}]]]])

(def ^:private engine-fields
  [[:tools {:optional true
            :x-display {:label "Tools offered"
                        :help "What the server answered to tools/list, mirrored by discover."}}
    [:maybe [:vector tool-entry]]]
   [:tools_hash {:optional true :x-display {:raw true :label "Tools hash"}}
    [:maybe [:string {:max 64}]]]
   [:discovered_at {:optional true :x-display {:label "Discovered at"}}
    [:maybe :waymark/instant]]
   [:last_error {:optional true :x-display {:raw true :label "Last error"}}
    [:maybe [:string {:max 500}]]]])

(def ^:private discover-input
  "What the CADENCE saw: the hash of the tool list the engine read
  before it walked this door. The engine's own field — hidden from
  every form, because a person taps Discover and the handler reads
  the server either way — and it is here for one reason. Invoke's
  natural replay compares the LATEST transition's input digest, so a
  discover after a discover with the same (empty) input answers as a
  replay and the handler never runs; the list that moved in between
  would then stay on the row for good. The hash moves whenever the
  list moves, so the digest does too, and the mirror lands."
  [:map
   [:seen_hash {:optional true
                :x-display {:hidden true :label "Hash seen"}}
    [:maybe [:string {:max 64}]]]])

(def ^:private restate-input
  [:map
   [:url {:optional true
          :x-display {:raw true :label "URL"
                      :help "Where an http server answers MCP. The engine discovers the server at this address before the restate lands."}}
    [:maybe [:string {:max 400}]]]
   [:command {:optional true
              :x-display {:raw true :label "Command"
                          :help "What a stdio server is started with. A changed command starts a new process."}}
    [:maybe [:string {:max 400}]]]
   [:args {:optional true
           :x-display {:label "Arguments"
                       :help "The command's arguments, one each."}}
    [:maybe [:vector [:string {:max 400}]]]]
   [:auth_env {:optional true
               :x-display {:raw true :label "Auth variable"
                           :help "The NAME of the environment variable, never the value."}}
    [:maybe [:string {:max 64}]]]
   [:powers {:optional true
             :x-display {:label "Powers"
                         :help "The whole policy, stated again: which tools each power token admits, and what a call must pass before it goes out."}}
    [:maybe [:vector power-entry]]]
   [:note {:optional true
           :examples [note-example]
           :x-display {:widget "prose" :label "Note"
                       :help "Anything the next person should know about this server."}}
    [:maybe [:string {:max 500}]]]])

(defresource mcp-server
  {:kind :mcp_server
   :plural "mcp_servers"
   :states [:live :dark :retired]
   :initial :live
   :terminal #{}
   :nav :system
   :summary "{data.name} · {data.transport} · {state}"
   :label-template "{data.name}"
   :schema (into [:map] (concat person-fields engine-fields))
   :create-schema (into [:map] person-fields)
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :transport #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :unique [[:name]]
   :create-guards [a-person-or-the-engine
                   one-server-spelling
                   transport-has-its-address
                   auth-env-is-a-name
                   passthrough-is-the-gates]
   :on-create born
   :actions
   {:restate
    {:from #{:live :dark} :to :live
     :input restate-input
     :guards [a-person-or-the-engine
              auth-env-is-a-name
              address-fits-the-transport]
     :record true
     :edit {:prefill [:url :command :args :auth_env :powers :note]}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A restate runs a discover: the row lands live with what the server answered, and a server that does not answer refuses the restate."}
     :handler restate-server
     :display {:label "Restate" :style :primary :order 1
               :description "State the address, the auth variable, the powers or the note again; the engine discovers the server before it lands"}}
    :discover
    {:from #{:live} :to :live
     :input discover-input
     :guards [a-person-or-the-engine]
     :safety {:idempotent true :reversible true :confirm false}
     :handler discover-server
     :display {:label "Discover" :order 2
               :description "Read the server's tool list again and mirror it onto the row"}}
    :mark_dark
    {:from #{:live} :to :dark
     :input [:map [:error {:optional true :x-display {:raw true}}
                   [:maybe [:string {:max 500}]]]]
     :guards [the-engines-own-hand]
     :edit {:fence false
            :unfenced-reason "The engine's own health flip after a call failed on the wire — no read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A person's mark_live is the way back, and it proves the server answers first."}
     :handler mark-dark-server
     :display {:label "Went dark"}}
    :mark_live
    {:from #{:dark} :to :live
     :guards [a-person-or-the-engine]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The engine discovers the server first; a server that does not answer refuses the door and the row stays dark."}
     :handler discover-server
     :display {:label "Mark live" :style :primary :order 1
               :description "Discover the server's tools and open it to powers again"}}
    :retire
    {:from #{:live :dark} :to :retired
     :guards [a-person-or-the-engine]
     :safety {:idempotent true :reversible false :confirm true
              :consequence "No power reaches this server and its client is closed; restore puts it back dark, and mark_live proves it answers."}
     :display {:label "Retire" :style :danger :order 9}}
    :restore
    {:from #{:retired} :to :dark
     :guards [a-person-or-the-engine]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "A restored server comes back dark: mark_live discovers it before any power reaches it."}
     :display {:label "Restore" :order 1}}}
   :scenarios [an-agent-does-not-retire-a-server
               the-person-retires-the-server
               an-agent-does-not-mark-a-server-live
               the-person-marks-the-server-live
               an-agent-does-not-restate-a-server
               a-restate-does-not-carry-a-secret-as-auth-env
               a-create-does-not-carry-a-secret-as-auth-env]
   :deviations
   ["R-4 says the engine calls discover at create. A create cannot walk a door on a row that does not exist yet, so the discover runs in the create's own hook: a server that answers is born live with its tools, and one that does not is born dark with the reason in last_error."
    "R-5 says the engine writes a required why to the transition log. A power call is not a transition on any row, and R-8 names no door for it, so the why is demanded, forwarded to a passthrough server as __why, and not written to the log."
    "R-8 lists restate beside mark_live. A restate runs a discover too, so a dark row a person fixes by restating its address lands live, and a restate the server does not answer is refused."
    "R-4 names no input on discover. The door takes one, `seen_hash`, hidden and optional: the cadence says which list it saw. Invoke's natural replay compares the latest transition's input digest, so an inputless discover after an inputless discover replays the first one's outcome and the list that moved in between never lands on the row."]})

;; ── the cadence ─────────────────────────────────────────────────────

(defn sweep-discover!
  "One pass: every live row's tools/list, walked through the discover
  door only when the hash moved. A failure the client calls fatal
  marks the row dark."
  [eng]
  (doseq [row (rows eng)
          :when (= :live (:state row))]
    (let [seam (seam-of eng)]
      (try
        (let [{:keys [hash]} (fetch-tools! seam row)]
          (when (not= hash (get-in row [:data :tools_hash]))
            ;; the hash the pass SAW rides the door as its input, so
            ;; two passes over two different lists are two calls and
            ;; never one replayed one (see `discover-input`)
            (inv/invoke! eng :mcp_server (str (:id row)) :discover
                         {:seen_hash hash} {:principal engine-actor})))
        (catch Exception e
          (if (and (p/problem? e) (fatal? seam row))
            (darken! eng row (ex-message e))
            (warn! "discover of " (get-in row [:data :name]) " failed ("
                   (ex-message e) ")")))))))

(defn start-discover-sweeper!
  "The cadence (R-4): every `:interval-ms`, `sweep-discover!`. The
  first pass is one interval after the start, so a boot writes
  nothing. Returns the handle `stop-discover-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms default-discover-ms}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-discover! eng)
                              (catch Exception e
                                (warn! "the discover pass failed ("
                                       (ex-message e) ")")))
                         (recur))))
                   "waymark10-mcp-discover")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-discover-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

;; ── the bridge ──────────────────────────────────────────────────────

(def gate-seed-powers
  "The static tool→token map gate_proxy.clj used to hold, restated as
  R-5 entries: the seed of the gate row's powers and nothing else.
  gsd__* is deliberately absent: waymark owns tasks and the calendar
  natively. A mutation Gate approves carries why."
  [{:power "email.read"
    :tools ["emila__inbox" "emila__list_messages" "emila__search"
            "emila__read" "emila__read_batch" "emila__download_attachment"
            "emila__summary" "emila__folders"]
    :why false}
   {:power "email.move" :tools ["emila__move" "emila__move_from_sender"]
    :why true}
   {:power "email.send" :tools ["emila__send"] :why true}
   {:power "telegram.read"
    :tools ["tgram__get_messages" "tgram__list_chats" "tgram__search_messages"
            "tgram__search_all_chats"]
    :why false}
   {:power "telegram.send" :tools ["tgram__send_message"] :why true}
   {:power "messages.read"
    :tools ["messa__threads" "messa__read_messages" "messa__reset"]
    :why false}
   {:power "notes.read" :tools ["keep__list_notes" "keep__search" "keep__read"]
    :why false}
   {:power "ynab.read"
    :tools ["ynab__accounts" "ynab__transactions" "ynab__budget_month"
            "ynab__categories"]
    :why false}
   {:power "ynab.write"
    :tools ["ynab__update_transaction" "ynab__split_transaction"
            "ynab__bulk_approve" "ynab__create_transaction"]
    :why true}
   {:power "amazon.read"
    :tools ["amzn__orders" "amzn__search" "amzn__product_details"
            "amzn__view_cart" "amzn__reset"]
    :why false}
   {:power "amazon.cart" :tools ["amzn__add_to_cart"] :why true}
   {:power "costco.read"
    :tools ["costco__receipts" "costco__receipt" "costco__captured"
            "costco__login" "costco__reset"]
    :why false}])

(defn ensure-gate-row!
  "The bridge of Gate's deprecation (R-13, § 3 step 1): one row named
  gate, passthrough, at Gate's url, with the powers the static map
  used to hold. Created when absent, in any state never overwritten.
  → the row. `url` defaults to the household LAN address."
  ([eng] (ensure-gate-row! eng nil))
  ([eng {:keys [url]}]
   (or (row-by-name eng "gate")
       (:row (inv/create! eng :mcp_server
                          {:name "gate"
                           :transport "http"
                           :url (str (or (not-empty (str url)) default-gate-url))
                           :passthrough true
                           :powers gate-seed-powers
                           :note (str "The bridge of Gate's deprecation: its "
                                      "tools already wear their prefixes. "
                                      "Move each rig to its own row, then "
                                      "retire this one.")}
                          {:principal engine-actor})))))

;; ── the one vocabulary (waymark-fp62.10.4) ──────────────────────────

(def gate-enforcer
  "The needle the sweep looks for in a capability row's `enforced_by`.
  The old boot seed wrote \"gate-mcp (192.168.1.40:8100)\"; the sweep
  matches the NAME and not the address, because an address moves and
  the enforcer behind it does not."
  "gate-mcp")

(defn superseded-capabilities
  "The capability rows a server's powers have taken over: active, the
  token named by some row's powers, and `enforced_by` naming Gate.
  Pure over the two lists, so the sweep and the test that proves it
  weigh the same judgment.

  A row whose `enforced_by` does NOT name Gate stands, and that is the
  registry's remaining job: `schedule.write` is this engine's own
  power, no server enforces it, and a scope must still be able to
  name it."
  [caps tokens]
  (let [tokens (set tokens)]
    (filterv (fn [row]
               (and (= :active (:state row))
                    (contains? tokens (str (get-in row [:data :token])))
                    (str/includes? (str/lower-case
                                    (str (get-in row [:data :enforced_by])))
                                   gate-enforcer)))
             caps)))

(defn sweep-capabilities!
  "One pass, at boot after the rows are seeded: every capability row a
  server's powers now carry is retired. Gate's tokens were registered
  twice once this leg made the powers list the vocabulary — a row
  saying `telegram.send` exists and a row named gate whose powers say
  the same — and the second copy is the one that can rot.

  The retirement walks the capability kind's OWN `retire` door under
  the engine's principal, never a store write: the registry is a
  resource, and a person reading its history should find a transition
  with an actor on it rather than a row that changed by itself.

  Idempotent: a second pass finds those rows retired and writes
  nothing. → the tokens it retired, sorted."
  [eng]
  (let [tokens (power-tokens eng)
        retired (atom [])]
    (doseq [row (superseded-capabilities (capability-rows eng) tokens)]
      (let [token (str (get-in row [:data :token]))]
        (try
          (inv/invoke! eng :capability (str (:id row)) :retire {}
                       {:principal engine-actor})
          (swap! retired conj token)
          (catch Exception e
            (warn! "the capability " token " did not retire ("
                   (ex-message e) ")")))))
    (vec (sort @retired))))
