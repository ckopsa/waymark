(ns localfire.config
  "The one EDN file the server reads (R-4.2), and the one variable it
  reads the bearer token from (R-4.3).

  The split is the point. The file holds the port, the public URL, the
  place, the runs directory and the routines — all of it dull enough
  to commit or to hand a person — and the token is never in it,
  because a config file is the thing that gets copied into a ticket. A
  bad file is a refusal to start with one sentence, not a server that
  answers half the routes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [localfire.prompt :as prompt]))

(def default-mcp-name
  "The MCP server's name, the cloud connector's own, so a run's tools
  are `mcp__Waymark__…` as the seats' instructions spell them."
  "Waymark")

(def default-allowed-tools
  "What a run may reach when the config names nothing: the engine's own
  MCP door and nothing else. The built-in tools are off (R-5.4)."
  ["mcp__Waymark__*"])

(defn- fail! [msg]
  (throw (ex-info msg {:localfire/config true})))

(defn config-path
  "The path of the config file (R-4.2): the first argument, or
  LOCALFIRE_CONFIG, or `localfire.edn` beside the working directory."
  [args]
  (or (some-> (first args) str not-empty)
      (some-> (System/getenv "LOCALFIRE_CONFIG") not-empty)
      "localfire.edn"))

(defn token
  "The bearer, from LOCALFIRE_TOKEN and from nowhere else (R-4.3)."
  []
  (some-> (System/getenv "LOCALFIRE_TOKEN") not-empty))

(defn- routine-name
  "Routine names are strings on the wire, whatever EDN spelled them."
  [k]
  (if (keyword? k) (name k) (str k)))

(def default-pull-token-env
  "The variable the pull link's token is read from when `:pull` names
  none (R-13.2)."
  "LOCALFIRE_PULL_TOKEN")

(defn pull-token
  "The pull link's token (R-13.2): from the file `:pull :token-file`
  names when it names one, read again on each call so a rotated token is
  taken up, and otherwise from the variable `:token-env` names; from
  nowhere else. nil when there is none."
  [pull]
  (if-let [f (:token-file pull)]
    (try (some-> (slurp f) str/trim not-empty)
         (catch java.io.IOException _ nil))
    (some-> (System/getenv (:token-env pull)) not-empty)))

(defn pull-token-source
  "Where `pull-token` reads the token, for a message. Never the token."
  [pull]
  (if-let [f (:token-file pull)] (str "the file " f) (:token-env pull)))

(declare ^:private normalize-mcp)

(defn- normalize-pull
  "The optional `:pull` map (§13): the engine and the runner link this
  localfire asks for its fires, and the routine they run as. The
  link's token is not in it; only the name of its variable is."
  [p routine-names]
  (when-not (map? p)
    (fail! "the config's :pull must be a map."))
  (when (contains? p :token)
    (fail! "the config's :pull must not hold the link's token; name its variable in :token-env or its file in :token-file."))
  (let [url     (some-> (:engine-url p) str not-empty (str/replace #"/+$" ""))
        file    (some-> (:token-file p) str not-empty)
        link    (some-> (:link-id p) str not-empty)
        routine (some-> (:routine p) routine-name not-empty)]
    (when-not url
      (fail! "the config's :pull needs an :engine-url, the engine's base URL."))
    (when-not link
      (fail! "the config's :pull needs a :link-id, the pull runner link's id."))
    (when-not routine
      (fail! "the config's :pull needs a :routine, the routine its fires run as."))
    (when-not (contains? routine-names routine)
      (fail! (str "the config's :pull :routine " routine " is not under :routines.")))
    (when (and (some? (:mcp p)) (not (map? (:mcp p))))
      (fail! "the config's :pull :mcp must be a map, the pull engine's MCP door."))
    (cond-> {:engine-url url
             :link-id    link
             :routine    routine
             :token-env  (or (some-> (:token-env p) str not-empty) default-pull-token-env)}
      ;; the file wins over the variable when both are named
      file (assoc :token-file file)
      ;; the door a pulled fire's run reaches; absent, the top-level :mcp
      ;; its :url defaults to the pull engine's own door
      (some? (:mcp p))
      (assoc :mcp (normalize-mcp
                   (update (:mcp p) :url
                           #(or (some-> % str not-empty) (str url "/api/-/mcp")))
                   ":pull :mcp"))
      ;; LP3: where the pull journal lives; the state dir's file without it
      (some-> (:journal p) str not-empty) (assoc :journal (str (:journal p))))))

(defn- normalize-routine [nm r]
  (when-not (map? r)
    (fail! (str "the routine " nm " must be a map in the config.")))
  (let [model (some-> (:model r) str not-empty)]
    (when-not model
      (fail! (str "the routine " nm " needs a :model in the config.")))
    {:name             nm
     :model            model
     :max-concurrent   (long (or (:max-concurrent r) 1))
     :max-run-seconds  (long (or (:max-run-seconds r) 3600))
     :prompt           (or (some-> (:prompt r) str not-empty)
                           prompt/routine-prompt)}))

(defn- normalize-oauth
  "The `:mcp` map's optional `:oauth` (R-8.2): the fixed client a
  person signed in with, for a door that refuses dynamic registration.
  `where` names the `:mcp` map, for the message."
  [where o]
  (when-not (map? o)
    (fail! (str "the config's " where " :oauth must be a map.")))
  (let [client-id (:client-id o)
        port      (:callback-port o)]
    (when-not (and (string? client-id) (not (str/blank? client-id)))
      (fail! (str "the config's " where " :oauth needs a :client-id, as a non-empty string.")))
    (when-not (pos-int? port)
      (fail! (str "the config's " where " :oauth needs a :callback-port, as a positive integer.")))
    {:client-id client-id :callback-port (long port)}))

(defn- normalize-headers
  "The `:mcp` map's optional `:headers`: the identity a run presents at
  a door with no OIDC — X-Waymark-Principal, X-Waymark-Actor-Type,
  X-Waymark-Acts-For — written as they are into each run's mcp.json."
  [where h]
  (when-not (and (map? h) (seq h))
    (fail! (str "the config's " where " :headers must be a non-empty map.")))
  (when-not (every? (fn [[k v]]
                      (and (or (string? k) (keyword? k))
                           (not (str/blank? (name k)))
                           (string? v) (not (str/blank? v))))
                    h)
    (fail! (str "the config's " where " :headers must map each header name to a non-empty string.")))
  (into {} (map (fn [[k v]] [(name k) v])) h))

(defn- normalize-header-files
  "The `:mcp` map's optional `:header-files`: each header name to the
  file that holds its value, read every time a run's mcp.json is
  written, so a value written later — the owner a claim keeps — is
  taken up with no restart."
  [where h]
  (when-not (and (map? h) (seq h)
                 (every? (fn [[k v]]
                           (and (or (string? k) (keyword? k))
                                (not (str/blank? (name k)))
                                (string? v) (not (str/blank? v))))
                         h))
    (fail! (str "the config's " where " :header-files must map each header name to a file path.")))
  (into {} (map (fn [[k v]] [(name k) v])) h))

(defn- normalize-mcp
  "One `:mcp` map — the top-level door, or the `:pull` map's own —
  with its name filled in. `:oauth` absent means no oauth block in the
  run's MCP entry; `:headers` absent means no headers block. The two are
  one or the other: a door signs a run in one way."
  [mcp where]
  (when (and (some? (:oauth mcp))
             (or (some? (:headers mcp)) (some? (:header-files mcp))))
    (fail! (str "the config's " where " takes :oauth or :headers, not both.")))
  (cond-> {:name (or (some-> (:name mcp) str not-empty) default-mcp-name)
           :url  (str (:url mcp))}
    (some? (:oauth mcp))   (assoc :oauth (normalize-oauth where (:oauth mcp)))
    (some? (:headers mcp)) (assoc :headers (normalize-headers where (:headers mcp)))
    (some? (:header-files mcp))
    (assoc :header-files (normalize-header-files where (:header-files mcp)))))

(defn- normalize-oidc
  "The optional `:oidc` map (§12): the Keycloak realm a person signs in
  through, and where the sealed tokens live. No secret is in it."
  [o]
  (when-not (map? o)
    (fail! "the config's :oidc must be a map."))
  (let [issuer (some-> (:issuer o) str not-empty (str/replace #"/+$" ""))
        tokens (some-> (:tokens-dir o) str not-empty)
        engine (some-> (:engine-url o) str not-empty (str/replace #"/+$" ""))]
    (when-not issuer
      (fail! "the config's :oidc needs an :issuer, the realm's URL."))
    (when-not tokens
      (fail! "the config's :oidc needs a :tokens-dir, where the sealed tokens live."))
    (cond-> {:issuer        issuer
             :client-id     (or (some-> (:client-id o) str not-empty) "localfire-claude")
             :authorize-url (or (some-> (:authorize-url o) str not-empty)
                                (str issuer "/protocol/openid-connect/auth"))
             :token-url     (or (some-> (:token-url o) str not-empty)
                                (str issuer "/protocol/openid-connect/token"))
             :tokens-dir    tokens}
      ;; where /me registers the person's runner link (ticket eae4b907)
      engine (assoc :engine-url engine))))

(defn- normalize-solo
  "The optional `:solo` map (first-run R-5.5): R-12.2's token page for
  the one person at this machine, with no sign-in, and where its sealed
  token lives. The config's routines run under that token."
  [s]
  (when-not (map? s)
    (fail! "the config's :solo must be a map."))
  (let [tokens (some-> (:tokens-dir s) str not-empty)]
    (when-not tokens
      (fail! "the config's :solo needs a :tokens-dir, where the sealed token lives."))
    {:tokens-dir tokens}))

(defn normalize
  "The config as the server uses it: every default filled in, every
  name a string, every routine complete. It throws ex-info with one
  sentence for anything it cannot make sense of."
  [m]
  (when-not (map? m)
    (fail! "the config file must hold one EDN map."))
  (let [port    (:port m)
        public  (some-> (:public-url m) str not-empty)
        place   (some-> (:place m) str not-empty)
        runs    (some-> (:runs-dir m) str not-empty)
        mcp     (:mcp m)
        rs      (:routines m)]
    (when-not (integer? port)
      (fail! "the config needs a :port, as an integer."))
    (when-not public
      (fail! "the config needs a :public-url, the URL a person reaches this server at."))
    (when-not place
      (fail! "the config needs a :place, the directory each run's place is copied from."))
    (when-not (.isDirectory (io/file place))
      (fail! (str "the :place " place " is not a directory on this machine.")))
    (when-not runs
      (fail! "the config needs a :runs-dir, where run records live."))
    (when-not (and (map? mcp) (some-> (:url mcp) str not-empty))
      (fail! "the config needs an :mcp map with a :url, the engine's MCP door."))
    (when-not (and (map? rs) (seq rs))
      (fail! "the config needs at least one routine under :routines."))
    (when-not (or (nil? (:check-seconds m)) (pos-int? (:check-seconds m)))
      (fail! "the config's :check-seconds must be a positive integer."))
    (when-not (or (nil? (:hook-via m))
                  (and (sequential? (:hook-via m))
                       (every? #(and (string? %) (not (str/blank? %))) (:hook-via m))))
      (fail! "the config's :hook-via must be a list of non-empty strings."))
    (when-not (or (nil? (:claude-home m))
                  (and (string? (:claude-home m)) (not (str/blank? (:claude-home m)))))
      (fail! "the config's :claude-home must be a non-empty string."))
    (when (and (some? (:oidc m)) (some? (:solo m)))
      (fail! "the config takes :oidc or :solo, not both: a solo localfire signs nobody in."))
    {:port          (long port)
     ;; a trailing slash on the public URL would double in every run
     ;; page link, and the engine writes that link onto the row
     :public-url    (str/replace public #"/+$" "")
     :place         place
     :runs-dir      runs
     :claude        (or (some-> (:claude m) str not-empty) "claude")
     :mcp           (normalize-mcp mcp ":mcp")
     :allowed-tools (vec (or (seq (map str (:allowed-tools m)))
                             default-allowed-tools))
     ;; R-4.6: how often the credential is probed
     :check-seconds (long (or (:check-seconds m) 600))
     ;; R-5.6: the close runs the hook where the runs run. A :claude
     ;; wrapper that moves a run into a container moves its transcript
     ;; there too, so the hook goes through the same door (:hook-via)
     ;; and looks under that side's HOME (:claude-home). Absent, the
     ;; hook runs here, under this process's HOME.
     :hook-via      (vec (:hook-via m))
     :claude-home   (:claude-home m)
     ;; §12: nil without :oidc, and then no sign-in, as before
     :oidc          (some-> (:oidc m) normalize-oidc)
     ;; first-run R-5.5: nil without :solo; with it, one person's token
     ;; page, no sign-in, and the routines run under that token
     :solo          (some-> (:solo m) normalize-solo)
     ;; §12: what a person's routine runs as, with :oidc only
     :person-routine (when (:oidc m)
                       (normalize-routine "person" (or (:person-routine m) {:model "opus"})))
     ;; R-12.12: the engine a person's pull link is polled at, with
     ;; :oidc only; the :pull map's engine when the config names none
     :person-pull-url (when (:oidc m)
                        (some-> (or (some-> (:person-pull-url m) str not-empty)
                                    (some-> (:pull m) :engine-url str not-empty))
                                (str/replace #"/+$" "")))
     ;; §13: nil without :pull, and then localfire only takes pushes
     :pull          (some-> (:pull m)
                            (normalize-pull (set (map routine-name (keys rs)))))
     :routines      (into {}
                          (map (fn [[k v]]
                                 (let [nm (routine-name k)]
                                   [nm (normalize-routine nm v)])))
                          rs)}))

(defn load-config
  "Read and validate the config at `path`. Throws ex-info with one
  sentence, which `main` puts on the error stream before exit 2."
  [path]
  (let [f (io/file path)]
    (when-not (.isFile f)
      (fail! (str "no config file at " (.getPath f) ".")))
    (normalize
     (try (edn/read-string (slurp f))
          (catch Exception e
            (fail! (str "the config at " (.getPath f) " is not readable EDN: "
                        (ex-message e))))))))

;; ── `localfire pull` (§13): a config built from flags ────────────────

(def pull-flags
  "The flags `localfire pull` takes, each to the key it fills. Only
  --engine and --link are required."
  {"--engine"   :engine-url
   "--link"     :link-id
   "--place"    :place
   "--runs-dir" :runs-dir
   "--port"     :port
   "--model"    :model
   "--mcp-url"  :mcp-url
   "--claude"   :claude
   "--token-file" :token-file
   "--solo-tokens" :solo-tokens
   "--mcp-header" :mcp-header
   "--mcp-header-file" :mcp-header-file})

(def ^:private repeated-pull-flags
  "The pull flags that may be given more than once, each kept in order."
  #{:mcp-header :mcp-header-file})

(def default-pull-place
  "The seat place `localfire pull` copies when --place names none: the
  one the image bakes in (Dockerfile.localfire)."
  "/srv/waymark-seat")

(def pull-routine
  "The one routine `localfire pull` serves, and its fires run as."
  "pull")

(defn- parse-pull-flags [args]
  (loop [m {} [f v & more :as as] (seq args)]
    (cond
      (empty? as) m
      (not (contains? pull-flags f))
      (fail! (str "localfire pull does not take " f "; it takes "
                  (str/join ", " (sort (keys pull-flags))) "."))
      (or (nil? v) (str/starts-with? v "--"))
      (fail! (str "localfire pull's " f " needs a value."))
      :else (let [k (pull-flags f)]
              (recur (if (repeated-pull-flags k)
                       (update m k (fnil conj []) v)
                       (assoc m k v))
                     more)))))

(defn- header-flag
  "One --mcp-header or --mcp-header-file value, `Name: value`, as its
  [name value] pair."
  [flag s]
  (let [[_ k v] (re-matches #"(?s)([^:]*):(.*)" s)
        k       (some-> k str/trim)
        v       (some-> v str/trim)]
    (when (or (str/blank? k) (str/blank? v))
      (fail! (str "localfire pull's " flag " takes 'Name: value', not " s ".")))
    [k v]))

(defn- header-flags [flag vs]
  (into {} (map #(header-flag flag %)) vs))

(defn pull-config-map
  "The config map `localfire pull --engine <url> --link <id>` stands for:
  the EDN a localfire.edn with a `:pull` block would hold, built from
  the flags and the defaults, so `normalize` judges both alike. The
  link's token is read from the file --token-file names, or the one
  LOCALFIRE_PULL_TOKEN_FILE names (first-run R-4.3), or else from
  LOCALFIRE_PULL_TOKEN, as the EDN path's is. --solo-tokens makes it
  solo (first-run R-5.5): its fires run under the one Claude token its
  page keeps there, and it pulls nothing until there is one. Each
  --mcp-header `Name: value` is a header every run's mcp.json carries;
  each --mcp-header-file `Name: path` is one whose value the file at
  path holds, read as each run starts. With neither, the pull gets its
  own door at the same URL, which presents the link's token as
  `run-mcp` has it (ticket 5dacde8e)."
  [args]
  (let [f      (parse-pull-flags args)
        engine (some-> (:engine-url f) (str/replace #"/+$" "") not-empty)
        port   (if-let [p (:port f)]
                 (or (parse-long p)
                     (fail! (str "localfire pull's --port must be an integer, not " p ".")))
                 8112)
        token-file (or (:token-file f)
                       (some-> (System/getenv "LOCALFIRE_PULL_TOKEN_FILE") not-empty))
        door       {:name default-mcp-name
                    :url  (or (:mcp-url f) (str engine "/api/-/mcp"))}
        signed-in? (or (:mcp-header f) (:mcp-header-file f))]
    (when-not engine
      (fail! "localfire pull needs --engine, the engine's base URL."))
    (when-not (:link-id f)
      (fail! "localfire pull needs --link, the pull runner link's id."))
    (cond->
     {:port       port
     :public-url (str "http://127.0.0.1:" port)
     :place      (or (:place f) default-pull-place)
     :runs-dir   (or (:runs-dir f)
                     (str (System/getProperty "user.home") "/.localfire/runs"))
     :claude     (or (:claude f) "claude")
     :mcp        (cond-> door
                   (:mcp-header f)
                   (assoc :headers (header-flags "--mcp-header" (:mcp-header f)))
                   (:mcp-header-file f)
                   (assoc :header-files (header-flags "--mcp-header-file" (:mcp-header-file f))))
     :routines   {pull-routine {:model (or (:model f) "opus")}}
     :pull       (cond-> {:engine-url engine
                          :link-id    (:link-id f)
                          :routine    pull-routine}
                   token-file (assoc :token-file token-file)
                   (not signed-in?) (assoc :mcp door))}
      (:solo-tokens f) (assoc :solo {:tokens-dir (:solo-tokens f)}))))

(defn routine
  "The routine of that name, or nil — the 404 of R-5.1."
  [cfg nm]
  (get-in cfg [:routines (str nm)]))

(def runner-token-header
  "The header a pulled run presents its pull link's token in: the one
  the engine's pull doors read."
  "Waymark-Runner-Token")

(defn run-mcp
  "The door a run writes into its mcp.json: a pulled fire's is the
  `:pull` map's own `:mcp` when it names one, and every other run's is
  the top-level `:mcp`. The credential probe stays on the top-level.
  A pull door with no `:oauth`, `:headers` or `:header-files` of its
  own presents the pull link's token in `runner-token-header`, read
  now from where the pull reads it (ticket 9ef6e515)."
  [cfg pulled?]
  (let [door (when pulled? (get-in cfg [:pull :mcp]))
        token (when (and door (not-any? door [:oauth :headers :header-files]))
                (pull-token (:pull cfg)))]
    (cond
      (nil? door) (:mcp cfg)
      token (assoc door :headers {runner-token-header token})
      :else door)))

(defn run-allowed-tools
  "What a run on `door` may reach: the config's `:allowed-tools`, each
  one that names the top-level door by its `mcp__<name>__` prefix named
  for `door` instead, so a pull door of another `:name` is the one the
  run reaches. On the top-level door it is `:allowed-tools` as it is."
  [cfg door]
  (let [from (str "mcp__" (get-in cfg [:mcp :name]) "__")
        to   (str "mcp__" (:name door) "__")]
    (mapv (fn [t]
            (let [t (str t)]
              (if (str/starts-with? t from) (str to (subs t (count from))) t)))
          (:allowed-tools cfg))))

(defn run-url
  "The run page's URL, which is what the engine writes onto the
  schedule row as `last_run_url` (R-5.2)."
  [cfg id]
  (str (:public-url cfg) "/runs/" id))
