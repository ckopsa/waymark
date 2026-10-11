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
            [waymark10.dev :as dev]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
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

(defn start-held-call!
  "The invitation drive's engine: a memory engine (dev/scratch!, no
  database) serving the meal fixture beside core's invitation kind.
  Nothing is seeded here: the drive opens its meal and invitations
  through the API.

    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx \\
      -e \"(do ((requiring-resolve 'waymark10.batch-a-dev/start-held-call!) 8124) nil) @(promise)\""
  [port]
  (let [eng (dev/scratch! [fx/meal])]
    (engine/start! eng port)
    (println (str "held-call engine: http://localhost:" port "/api/-/ui"))
    eng))

(def ^:private later-ticket
  "The later drive's row: a ticket a person grooms, with one field a
  condition can read."
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:draft :open]
    :initial :draft
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:priority {:x-display {:label "Priority"}} [:int {:min 0 :max 4}]]]
    :filterable {:state #{:eq :in}
                 :priority #{:eq :in :ne :range}}
    :actions
    {:groom {:from #{:draft} :to :open
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Groom" :order 1}}
     :ungroom {:from #{:open} :to :draft
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Ungroom" :order 2}}}}))

(defn start-later!
  "The later drive's engine: a memory engine (dev/scratch!, no database)
  serving a ticket beside core's scheduled_action kind
  (docs/spec-scheduled-actions.md R-7.3). Nothing is seeded here: the
  drive writes its ticket through the API.

    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx -e \\
      \"(do ((requiring-resolve 'waymark10.batch-a-dev/start-later!) 8125) nil) @(promise)\""
  [port]
  (let [eng (dev/scratch! [later-ticket])]
    (engine/start! eng port)
    (println (str "later engine: http://localhost:" port "/api/-/ui"))
    eng))
