(ns waymark10.access-dev
  "The access UI-drive engine: the access and seat kinds (member,
  seat, held_call, invitation) served on a port against
  WAYMARK10_TEST_DSN, booted like waymark10.batch-a-dev. Every table
  drops at boot, and the boot seeds what the drive reads: a member, a
  seat whose sitter acts for that member, and one held seat-restate
  held_call whose owner is the member and whose caller and door author
  are the seat (held_call_test's seat and hold-door! seeds). The RP's
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
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(defn seed!
  "The member, the seat and its one held restate. → {:member :seat
  :held}, each an id string."
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
                                :why "Restate the desk's charter."})]
    {:member mid :seat sid :held (str (:id h))}))

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
