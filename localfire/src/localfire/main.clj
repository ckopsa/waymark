(ns localfire.main
  "`clojure -M:serve [config.edn]` (R-4.1), or `clojure -M:serve pull
  --engine <url> --link <id>` with no file (§13).

  It does four things and then blocks: read the config, refuse to start
  without a token, name the runs that were in flight at the last stop,
  and stand the server up, with the pull loop beside it when the config
  has `:pull` (§13). The refusals are exit 2 with one sentence on the
  error stream, because a server that came up without its token and
  answered 401 to every fire would put `broken` on every schedule row
  in the house. A localfire that only pulls needs no push token."
  (:require [clojure.string :as str]
            [localfire.config :as config]
            [localfire.people :as people]
            [localfire.pull :as pull]
            [localfire.runs :as runs]
            [localfire.server :as server])
  (:gen-class))

(defn- die! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 2))

(defn- load-config
  "`localfire pull --engine <url> --link <id>` builds its config from
  the flags (§13); anything else names an EDN file."
  [args]
  (if (= "pull" (first args))
    (config/normalize (config/pull-config-map (rest args)))
    (config/load-config (config/config-path args))))

(defn -main [& args]
  (let [cfg        (try (load-config args)
                        (catch clojure.lang.ExceptionInfo e
                          (die! (str "localfire: " (ex-message e)))))
        token      (config/token)
        pull-cfg   (:pull cfg)
        pull-token (some-> pull-cfg config/pull-token)]
    (when (and (str/blank? token) (nil? pull-cfg))
      (die! "localfire: LOCALFIRE_TOKEN is empty, and the server reads its bearer from nowhere else."))
    ;; R-13.2: the link's token is read from its variable and nowhere else
    (when (and pull-cfg (str/blank? pull-token))
      (die! (str "localfire: the config has :pull, and " (:token-env pull-cfg)
                 " is empty; the link's token is read from nowhere else.")))
    ;; R-12.2: a sign-in with nowhere safe to keep the token is no start
    (when (and (:oidc cfg) (nil? (people/env-key)))
      (die! "localfire: the config has :oidc, and LOCALFIRE_TOKEN_KEY is not a base64 AES key of 32 bytes."))
    ;; R-5.6, before the first fire of this life
    (let [lost (runs/mark-lost! (:runs-dir cfg))
          st   (server/start! {:config cfg :token token :check? true
                               :sign-in {:key    (people/env-key)
                                         :secret (people/env-secret)}})]
      (when pull-cfg
        (pull/start! st pull-cfg pull-token))
      ;; R-12.12: a loop for each person who stored a pull link
      (when (and (:people st) (:person-pull-url cfg))
        (pull/start-people! st (:person-pull-url cfg)))
      (println (str "localfire listening on " (:port st)
                    " public-url=" (:public-url cfg)
                    " routines=" (str/join "," (sort (keys (:routines cfg))))
                    " pull=" (or (:link-id pull-cfg) "off")
                    " lost=" lost
                    " credential=" (:ok @(:credential st))))
      @(promise))))
