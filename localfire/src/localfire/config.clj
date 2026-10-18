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
  "The pull link's token, from the variable `:pull :token-env` names and
  from nowhere else (R-13.2)."
  [pull]
  (some-> (System/getenv (:token-env pull)) not-empty))

(defn- normalize-pull
  "The optional `:pull` map (§13): the engine and the runner link this
  localfire asks for its fires, and the routine they run as. The
  link's token is not in it; only the name of its variable is."
  [p routine-names]
  (when-not (map? p)
    (fail! "the config's :pull must be a map."))
  (when (contains? p :token)
    (fail! "the config's :pull must not hold the link's token; name its variable in :token-env."))
  (let [url     (some-> (:engine-url p) str not-empty (str/replace #"/+$" ""))
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
    {:engine-url url
     :link-id    link
     :routine    routine
     :token-env  (or (some-> (:token-env p) str not-empty) default-pull-token-env)}))

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
  person signed in with, for a door that refuses dynamic registration."
  [o]
  (when-not (map? o)
    (fail! "the config's :mcp :oauth must be a map."))
  (let [client-id (:client-id o)
        port      (:callback-port o)]
    (when-not (and (string? client-id) (not (str/blank? client-id)))
      (fail! "the config's :mcp :oauth needs a :client-id, as a non-empty string."))
    (when-not (pos-int? port)
      (fail! "the config's :mcp :oauth needs a :callback-port, as a positive integer."))
    {:client-id client-id :callback-port (long port)}))

(defn- normalize-headers
  "The `:mcp` map's optional `:headers`: the identity a run presents at
  a door with no OIDC — X-Waymark-Principal, X-Waymark-Actor-Type,
  X-Waymark-Acts-For — written as they are into each run's mcp.json."
  [h]
  (when-not (and (map? h) (seq h))
    (fail! "the config's :mcp :headers must be a non-empty map."))
  (when-not (every? (fn [[k v]]
                      (and (or (string? k) (keyword? k))
                           (not (str/blank? (name k)))
                           (string? v) (not (str/blank? v))))
                    h)
    (fail! "the config's :mcp :headers must map each header name to a non-empty string."))
  (into {} (map (fn [[k v]] [(name k) v])) h))

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
    {:port          (long port)
     ;; a trailing slash on the public URL would double in every run
     ;; page link, and the engine writes that link onto the row
     :public-url    (str/replace public #"/+$" "")
     :place         place
     :runs-dir      runs
     :claude        (or (some-> (:claude m) str not-empty) "claude")
     ;; :oauth absent means no oauth block in the run's MCP entry;
     ;; :headers absent means no headers block. The two are one or
     ;; the other: a door signs a run in one way.
     :mcp           (do
                      (when (and (some? (:oauth mcp)) (some? (:headers mcp)))
                        (fail! "the config's :mcp takes :oauth or :headers, not both."))
                      (cond-> {:name (or (some-> (:name mcp) str not-empty) default-mcp-name)
                               :url  (str (:url mcp))}
                        (some? (:oauth mcp))   (assoc :oauth (normalize-oauth (:oauth mcp)))
                        (some? (:headers mcp)) (assoc :headers (normalize-headers (:headers mcp)))))
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
   "--claude"   :claude})

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
      :else (recur (assoc m (pull-flags f) v) more))))

(defn pull-config-map
  "The config map `localfire pull --engine <url> --link <id>` stands for:
  the EDN a localfire.edn with a `:pull` block would hold, built from
  the flags and the defaults, so `normalize` judges both alike. The
  link's token is read from LOCALFIRE_PULL_TOKEN, as the EDN path's is."
  [args]
  (let [f      (parse-pull-flags args)
        engine (some-> (:engine-url f) (str/replace #"/+$" "") not-empty)
        port   (if-let [p (:port f)]
                 (or (parse-long p)
                     (fail! (str "localfire pull's --port must be an integer, not " p ".")))
                 8112)]
    (when-not engine
      (fail! "localfire pull needs --engine, the engine's base URL."))
    (when-not (:link-id f)
      (fail! "localfire pull needs --link, the pull runner link's id."))
    {:port       port
     :public-url (str "http://127.0.0.1:" port)
     :place      (or (:place f) default-pull-place)
     :runs-dir   (or (:runs-dir f)
                     (str (System/getProperty "user.home") "/.localfire/runs"))
     :claude     (or (:claude f) "claude")
     :mcp        {:name default-mcp-name
                  :url  (or (:mcp-url f) (str engine "/api/-/mcp"))}
     :routines   {pull-routine {:model (or (:model f) "opus")}}
     :pull       {:engine-url engine
                  :link-id    (:link-id f)
                  :routine    pull-routine}}))

(defn routine
  "The routine of that name, or nil — the 404 of R-5.1."
  [cfg nm]
  (get-in cfg [:routines (str nm)]))

(defn run-url
  "The run page's URL, which is what the engine writes onto the
  schedule row as `last_run_url` (R-5.2)."
  [cfg id]
  (str (:public-url cfg) "/runs/" id))
