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

(def default-allowed-tools
  "What a run may reach when the config names nothing: the engine's own
  MCP door, and the one echo the Routine prompt asks for."
  ["mcp__waymark__*" "Bash(echo *)"])

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
    {:port          (long port)
     ;; a trailing slash on the public URL would double in every run
     ;; page link, and the engine writes that link onto the row
     :public-url    (str/replace public #"/+$" "")
     :place         place
     :runs-dir      runs
     :claude        (or (some-> (:claude m) str not-empty) "claude")
     :mcp           {:name (or (some-> (:name mcp) str not-empty) "waymark")
                     :url  (str (:url mcp))}
     :allowed-tools (vec (or (seq (map str (:allowed-tools m)))
                             default-allowed-tools))
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

(defn routine
  "The routine of that name, or nil — the 404 of R-5.1."
  [cfg nm]
  (get-in cfg [:routines (str nm)]))

(defn run-url
  "The run page's URL, which is what the engine writes onto the
  schedule row as `last_run_url` (R-5.2)."
  [cfg id]
  (str (:public-url cfg) "/runs/" id))
