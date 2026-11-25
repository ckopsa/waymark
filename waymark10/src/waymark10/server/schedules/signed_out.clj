(ns waymark10.server.schedules.signed-out
  "A localfire whose credential check fails (spec-local-fire.md R-4.6)
  answers a fire 429 with `signed_out` in its body, the name of the door
  it is signed out of (ticket c56021e7). Read here, the schedule's row
  says the runner is signed out, not that the Routine has no free run.")

(defn door
  "The door a 429's `body` says its runner is signed out of, or nil."
  [body]
  (some->> body str (re-find #"\"signed_out\"\s*:\s*\"([^\"]{1,80})\"") second))

(defn note
  "The row's sentence for a runner signed out of `door`, naming the host
  of `fire-url`."
  [fire-url door]
  (let [host (try (some-> fire-url str not-empty java.net.URI. .getAuthority)
                  (catch Exception _ nil))]
    (str "The runner on " (or host "its host") " is signed out of " door
         "; a person signs it in again.")))
