(ns waymark10.access-dev
  "The access UI-drive engine: the access and seat kinds (member,
  seat, held_call, invitation) served on a port against
  WAYMARK10_TEST_DSN, booted like waymark10.batch-a-dev. Every table
  drops at boot, and the boot seeds what the drive reads: a member, a
  seat whose sitter acts for that member, and one held seat-restate
  held_call whose owner is the member and whose caller and door author
  are the seat (held_call_test's seat and hold-door! seeds), and one
  sealed walk the drive replays. The RP's
  session doors are composed (no identity provider, require-auth off,
  so the dev headers still speak): the drive signs a guest in through
  /auth/guest and reads the UI with a session cookie alone.

    WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_ui_test?user=ckopsa \\
    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx \\
      -e \"(do ((requiring-resolve 'waymark10.access-dev/start!) 8124) nil) @(promise)\""
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.walks :as walks]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(defn seed!
  "The member, the seat, its one held restate, and one sealed walk of
  three frames by the seat's sitter (a move to the seat, a ui frame
  with the restate dialog open, a transition) for the drive's replay.
  → {:member :seat :held :walk}, each an id string."
  [eng]
  (let [member (:row (inv/create! eng :member
                                  {:display "Jack Tester" :actor_type "human"}
                                  {:principal colton}))
        mid (str (:id member))
        seat (:row (inv/create! eng :seat
                                {:name "mail-desk"
                                 :charter "Answer the household's mail."
                                 :scope [{:kind "capability" :actions []}]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal colton}))
        sid (str (:id seat))
        sitter (str "seat:" sid)
        _ (members/ensure-sitter! eng sitter "mail-desk" mid)
        h (held/hold-door! eng {:kind "seat" :action "restate" :id sid
                                :body {:charter "Answer the household's mail, briefly."}
                                :caller sitter :author sitter :owner mid
                                :why "Restate the desk's charter."})
        walk (:row (inv/create! eng :walk
                                {:followed sitter
                                 :title "Restating the desk's charter"}
                                {:principal colton}))
        wid (str (:id walk))
        self (str "/api/seats/" sid)
        by {:id sitter :type "agent" :display "mail-desk"}
        state (or (some-> (:state seat) name) "open")
        ;; a pause before each frame: the replay has a gap to keep
        frame! (fn [type body]
                 (Thread/sleep 300)
                 (walks/record-frame! eng wid nil {:type type :body body}))]
    (frame! "move" {:principal by :self self :event "move" :source "ui"})
    (frame! "ui" {:principal by :self self :event "ui"
                  :ui {:dialog {:self self :action "restate"}
                       :fields {:charter "Answer the household's mail, briefly."}
                       :collection nil
                       :focus nil}})
    (frame! "transition" {:kind "seat" :self self :action "restate"
                          :from state :to state
                          :at (str (Instant/now))
                          :summary "mail-desk, restated"
                          :actor by})
    (inv/invoke! eng :walk wid :seal {} {:principal colton})
    {:member mid :seat sid :held (str (:id h)) :walk wid}))

(defn start! [port]
  (let [st (pg/storage db/dsn)]
    (store/with-tx st
      (fn [tx]
        (doseq [{t :tablename} (jdbc/execute!
                                tx ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"]
                                {:builder-fn rs/as-unqualified-lower-maps})]
          (jdbc/execute! tx [(str "DROP TABLE IF EXISTS \"" t "\" CASCADE")]))))
    (let [eng (engine/engine {:storage st
                              :resources [caps/capability]
                              :auto-migrate true
                              :oidc {:issuer "https://idp.test/realms/access-dev"
                                     :audience "access-dev"
                                     :jwks {:keys []}
                                     :rp {:client-id "access-dev"
                                          :client-secret "access-dev"
                                          :app-url (str "http://localhost:" port)
                                          :session-secret "a-32-byte-session-secret-access!"}}})
          ids (seed! eng)]
      (engine/start! eng port {:wrap-handler (rp/wrap-handler eng)})
      (println (str "access engine: http://localhost:" port "/api/-/ui"))
      (println (str "seeded: " (pr-str ids)))
      eng)))
