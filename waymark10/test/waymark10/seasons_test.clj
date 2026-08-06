(ns waymark10.seasons-test
  "Seasons acceptance (waymark-tti.2): the transition log read as a
  shape. Memory store where the meaning lives — bucketing follows
  the UTC ISO week, created/completed/other follows the DECLARATION
  (terminal landings and the conventional closing names, never a
  hardcoded per-kind list), system actors are excluded by default,
  and the projection seam: a scoped caller gets whole-granted kinds
  only, ids-narrowed and ungranted kinds byte-level absent from
  weeks and aging alike. Anonymous is the concealment 404; weeks
  clamps 1..12. The last test drives the Postgres SQL path (the
  aggregate + the ix_wm10_t_at index) against the waymark10_test
  database; WAYMARK10_TEST_DSN overrides. The presence curtain's two
  member touches are excluded as person-rhythm (waymark-tti.4) — the
  same 'reads the work, never the people' line that drops system
  beats."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.seasons :as seasons]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private task
  ;; :finish lands terminal under an unconventional name — the
  ;; declaration, not the name, must classify it :completed
  (r/resource
   {:kind :season_task
    :plural "season_tasks"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:finish {:from #{:open} :to :closed :safety quiet}
     :poke {:from #{:open} :to :open
            :safety {:idempotent true :reversible true :confirm false}}}}))

(def ^:private chore
  ;; :complete lands NON-terminal (a mirror-style loop) — the
  ;; conventional name must still classify it :completed; :archive
  ;; lands terminal under a name no list would carry
  (r/resource
   {:kind :season_chore
    :plural "season_chores"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:complete {:from #{:open} :to :open :safety quiet}
     :archive {:from #{:open} :to :done :safety quiet}}}))

(def ^:private memo
  ;; never granted to the agent — the byte-level-absence control
  (r/resource
   {:kind :season_memo
    :plural "season_memos"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:file {:from #{:open} :to :filed :safety quiet}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private sweeper (t/principal {:id "sweeper" :type :system
                                     :display "Sweeper"}))
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [task chore memo]}))

;; the memory twin stamps :at/:created-at itself; rhythm tests reach
;; into its atom to move history where the scenario needs it — the
;; same the presence tests do with the registry's :local
(defn- backdate-transition! [st pred ^Instant at]
  (swap! (:state st) update :transitions
         (fn [ts] (mapv #(if (pred %) (assoc % :at at) %) ts))))

(defn- backdate-row! [st kind id ^Instant at]
  (swap! (:state st) assoc-in [:tables kind id :created-at] at))

(defn- create! [eng kind title principal]
  (get-in (inv/create! eng kind {:title title} {:principal principal})
          [:row :id]))

;; ── bucketing and classification ────────────────────────────────────

(deftest weekly-buckets-follow-the-declaration
  (let [eng (fresh-engine)
        st (:storage eng)
        now (Instant/now)
        ;; mid-previous-week: unambiguously the bucket before now's
        prev-week (.minus (store/utc-week-start now) 3 ChronoUnit/DAYS)
        t1 (create! eng :season_task "old" elena)
        t2 (create! eng :season_task "done" elena)
        t3 (create! eng :season_task "poked" elena)
        c1 (create! eng :season_chore "looped" elena)
        c2 (create! eng :season_chore "archived" elena)]
    (backdate-transition! st #(= t1 (:resource-id %)) prev-week)
    (inv/invoke! eng :season_task t2 :finish nil {:principal elena})
    (inv/invoke! eng :season_task t3 :poke nil {:principal elena})
    (inv/invoke! eng :season_chore c1 :complete nil {:principal elena})
    (inv/invoke! eng :season_chore c2 :archive nil {:principal elena})
    (let [rep (seasons/report eng nil {:weeks 4 :include-system? false})
          wks (:weeks rep)
          prev (nth wks 2)
          cur (last wks)]
      (testing "the window is exactly `weeks` labeled buckets, oldest first"
        (is (= 4 (count wks)))
        (is (every? #(re-matches #"\d{4}-W\d{2}" (:week %)) wks))
        (is (= 4 (get-in rep [:window :weeks])))
        (is (= {} (:kinds (first wks))) "an empty week rides as an empty bucket"))
      (testing "a backdated create lands in ITS week, not the current one"
        (is (= {:created 1 :completed 0 :other 0}
               (get-in prev [:kinds :season_task]))))
      (testing "terminal landing completes whatever the action's name"
        (is (= {:created 2 :completed 1 :other 1}
               (get-in cur [:kinds :season_task]))))
      (testing "the conventional closing name completes even off-terminal;
                so does an unconventional terminal landing"
        (is (= {:created 2 :completed 2 :other 0}
               (get-in cur [:kinds :season_chore])))))))

;; ── the system-actor exclusion ──────────────────────────────────────

(deftest system-beats-are-excluded-by-default
  (let [eng (fresh-engine)]
    (create! eng :season_chore "human" elena)
    (create! eng :season_chore "beat-1" sweeper)
    (create! eng :season_chore "beat-2" sweeper)
    (let [cur #(get-in (last (:weeks %)) [:kinds :season_chore :created])]
      (is (= 1 (cur (seasons/report eng nil {:weeks 4 :include-system? false})))
          "mirror-style system beats never dominate the counts")
      (is (= 3 (cur (seasons/report eng nil {:weeks 4 :include-system? true})))
          "include_system invites them back in"))))

;; ── the curtain's touches are person-rhythm, never work ─────────────

(deftest curtain-touches-never-become-a-bar
  ;; waymark-tti.4: when someone stepped behind their presence curtain
  ;; and back out is a PERSON's rhythm. The member row's transitions
  ;; keep it (deliberate posture — the no stays legible); a weekly
  ;; shape of it would be the gaze history this surface refuses.
  (let [eng (fresh-engine)]
    (inv/create! eng :member {:display "Elena" :actor_type "human"}
                 {:principal members/registrar :id "elena"})
    (inv/invoke! eng :member "elena" :draw_curtain nil {:principal elena})
    (inv/invoke! eng :member "elena" :open_curtain nil {:principal elena})
    (let [cur (last (:weeks (seasons/report eng nil {:weeks 4
                                                     :include-system? false})))]
      (is (nil? (get-in cur [:kinds :member]))
          "two curtain touches by a human, and the member kind has no bucket")
      (testing "the exclusion is those two actions, not the kind: an
                ordinary member act still counts"
        ;; set_handle rides the member kind's write fence — the etag
        ;; is v3 here (create + the two curtain touches)
        (inv/invoke! eng :member "elena" :set_handle {:handle "elena-k"}
                     {:principal members/registrar
                      :if-match (inv/etag :member "elena" 3)})
        (let [cur (last (:weeks (seasons/report eng nil
                                                {:weeks 4
                                                 :include-system? true})))]
          (is (= {:created 1 :completed 0 :other 1}
                 (get-in cur [:kinds :member]))
              "the registrar's create and the set_handle — and neither
               curtain touch"))))))

;; ── aging: current rows, the declaration's reading of open ──────────

(deftest aging-counts-old-non-terminal-rows
  (let [eng (fresh-engine)
        st (:storage eng)
        now (Instant/now)
        t1 (create! eng :season_task "aging" elena)
        t2 (create! eng :season_task "closed-old" elena)
        _ (create! eng :season_task "fresh" elena)]
    (backdate-row! st :season_task t1 (.minus now 20 ChronoUnit/DAYS))
    (backdate-row! st :season_task t2 (.minus now 40 ChronoUnit/DAYS))
    (inv/invoke! eng :season_task t2 :finish nil {:principal elena})
    (let [rep (seasons/report eng nil {:weeks 4 :include-system? false})
          entry (first (filter #(= "season_task" (:kind %)) (:aging rep)))]
      (is (= 1 (:open_older_than_14d entry))
          "a terminal row never ages, however old; a fresh one is not yet aging")
      (is (= 20 (:oldest_days entry)))
      (is (not-any? #(= "season_chore" (:kind %)) (:aging rep))
          "a kind with nothing aging is absent, not zero"))))

;; ── the wire: projection, anonymity, clamping ───────────────────────

(defn- get-seasons [h headers query-string]
  (let [resp (h (cond-> {:request-method :get :uri "/api/-/seasons"
                         :headers headers}
                  query-string (assoc :query-string query-string)))]
    (assoc resp :parsed (some-> (:body resp) wire/read-json))))

(deftest the-projection-seam-on-the-wire
  (let [eng (fresh-engine)
        st (:storage eng)
        h (engine/handler eng)
        now (Instant/now)
        t1 (create! eng :season_task "granted whole" elena)
        c1 (create! eng :season_chore "ids-narrowed" elena)
        m1 (create! eng :season_memo "ungranted" elena)
        gid (get-in (inv/create!
                     eng :grant
                     {:audience "spy"
                      :scope [{:kind "season_task" :actions []}
                              {:kind "season_chore" :ids [c1] :actions []}]}
                     {:principal elena})
                    [:row :id])]
    (inv/invoke! eng :grant gid :accept nil {:principal spy})
    ;; every kind carries an aging row, so absence is projection,
    ;; never emptiness
    (doseq [[kind id] [[:season_task t1] [:season_chore c1] [:season_memo m1]]]
      (backdate-row! st kind id (.minus now 30 ChronoUnit/DAYS)))
    (testing "an unscoped human sees every kind"
      (let [{:keys [status parsed]} (get-seasons
                                     h {"x-waymark-principal" "elena"} nil)
            aged (set (map :kind (:aging parsed)))]
        (is (= 200 status))
        (is (contains? aged "season_task"))
        (is (contains? aged "season_chore"))
        (is (contains? aged "season_memo"))))
    (testing "a scoped caller: whole-granted present; ids-narrowed and
              ungranted byte-level absent from weeks AND aging"
      (let [{:keys [status body parsed]}
            (get-seasons h {"x-waymark-principal" "spy"
                            "x-waymark-actor-type" "agent"
                            "x-waymark-grant" gid} nil)]
        (is (= 200 status))
        (is (some #(contains? (:kinds %) :season_task) (:weeks parsed)))
        (is (= ["season_task"] (mapv :kind (:aging parsed))))
        (is (not (str/includes? body "season_chore")))
        (is (not (str/includes? body "season_memo")))))
    (testing "an ungranted agent (the bootstrap surface) sees no kind"
      (let [{:keys [status parsed]}
            (get-seasons h {"x-waymark-principal" "spy"
                            "x-waymark-actor-type" "agent"} nil)]
        (is (= 200 status))
        (is (every? #(= {} (:kinds %)) (:weeks parsed)))
        (is (= [] (:aging parsed)))))
    (testing "anonymous is the concealment 404"
      (is (= 404 (:status (get-seasons h {} nil)))))))

(deftest weeks-clamp-on-the-wire
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (is (= 1 (seasons/clamp-weeks "0")))
    (is (= 12 (seasons/clamp-weeks "99")))
    (is (= 4 (seasons/clamp-weeks nil)))
    (is (= 4 (seasons/clamp-weeks "june")))
    (doseq [[qs n] [["weeks=0" 1] ["weeks=99" 12] [nil 4]]]
      (let [{:keys [parsed]} (get-seasons
                              h {"x-waymark-principal" "elena"} qs)]
        (is (= n (get-in parsed [:window :weeks])))
        (is (= n (count (:weeks parsed))))))))

;; ── the Postgres SQL path (the shared test database; run serially) ──

(def ^:private dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(deftest transition-stats-over-postgres
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["season_tasks" "waymark10_transitions"
                         "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (inv/engine {:storage st :resources [task]})
            now (Instant/now)
            prev-week (.minus (store/utc-week-start now) 3 ChronoUnit/DAYS)
            since (.minus (store/utc-week-start now) 21 ChronoUnit/DAYS)
            t1 (create! eng :season_task "old" elena)
            _ (create! eng :season_task "current" elena)
            _ (create! eng :season_task "beat" sweeper)]
        (store/with-tx st
          (fn [tx]
            (jdbc/execute! tx ["UPDATE waymark10_transitions SET at = ? WHERE resource_id = ?"
                               (java.sql.Timestamp/from prev-week) t1])))
        (testing "the aggregate buckets by the shared UTC week truncation"
          (let [rows (store/with-tx st
                       #(store/transition-stats st % since false))]
            (is (= [{:week-start (store/utc-week-start prev-week)
                     :kind "season_task" :action "create"
                     :actor-type "human" :n 1}
                    {:week-start (store/utc-week-start now)
                     :kind "season_task" :action "create"
                     :actor-type "human" :n 1}]
                   rows)
                "the system actor's row is filtered in the SQL itself")))
        (testing "include-system? widens the same query"
          (let [rows (store/with-tx st
                       #(store/transition-stats st % since true))]
            (is (= 3 (reduce + (map :n rows))))
            (is (some #(= "system" (:actor-type %)) rows))))
        (testing "the at-index exists and the DDL stays idempotent"
          (store/with-tx st
            (fn [tx]
              (doseq [ddl pg/prerequisites]
                (jdbc/execute! tx [ddl]))
              (is (seq (jdbc/execute!
                        tx ["SELECT indexname FROM pg_indexes WHERE tablename = 'waymark10_transitions' AND indexname = 'ix_wm10_t_at'"])))))))
      (finally (pg/close! st)))))
