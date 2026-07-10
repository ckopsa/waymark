(ns waymark10.batch-a-dev
  "The batch-A UI-drive engine: the FRAMEWORK fixtures (meal, plan)
  plus the link trio, served on an ephemeral port against
  WAYMARK10_TEST_DSN — never mealplan10. Tables drop at boot so every
  drive starts from bytes it seeds itself (the drive seeds through
  the API, like any client).

    WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_ui_test?user=ckopsa \\
    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx \\
      -e \"((requiring-resolve 'waymark10.batch-a-dev/start!) 8123) @(promise)\""
  (:require [next.jdbc :as jdbc]
            [waymark10.batch-a-fixtures :as bafx]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]))

(defn start! [port]
  (let [st (pg/storage db/dsn)]
    (store/with-tx st
      (fn [tx]
        (doseq [t ["meals" "plans" "ba_projects" "ba_tickets" "ba_days"
                   "definitions" "waymark10_transitions"
                   "waymark10_idempotency" "waymark10_drafts"]]
          (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " t " CASCADE")]))))
    (let [eng (engine/engine {:storage st
                              :resources [fx/meal fx/plan bafx/ba-project
                                          bafx/ba-ticket bafx/ba-day]})]
      (engine/start! eng port)
      (println (str "batch-a engine: http://localhost:" port "/api/-/ui"))
      eng)))
