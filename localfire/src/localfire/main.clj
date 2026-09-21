(ns localfire.main
  "`clojure -M:serve` (R-4.1).

  It does four things and then blocks: refuse to start without a
  token, read the config, name the runs that were in flight at the
  last stop, and stand the server up. The two refusals are exit 2 with
  one sentence on the error stream, because a server that came up
  without its token and answered 401 to every fire would put `broken`
  on every schedule row in the house."
  (:require [clojure.string :as str]
            [localfire.config :as config]
            [localfire.runs :as runs]
            [localfire.server :as server])
  (:gen-class))

(defn- die! [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 2))

(defn -main [& args]
  (let [token (config/token)]
    (when (str/blank? token)
      (die! "localfire: LOCALFIRE_TOKEN is empty, and the server reads its bearer from nowhere else."))
    (let [path (config/config-path args)
          cfg  (try (config/load-config path)
                    (catch clojure.lang.ExceptionInfo e
                      (die! (str "localfire: " (ex-message e)))))]
      ;; R-5.6, before the first fire of this life
      (let [lost (runs/mark-lost! (:runs-dir cfg))
            st   (server/start! {:config cfg :token token})]
        (println (str "localfire listening on " (:port st)
                      " public-url=" (:public-url cfg)
                      " routines=" (str/join "," (sort (keys (:routines cfg))))
                      " lost=" lost))
        @(promise)))))
