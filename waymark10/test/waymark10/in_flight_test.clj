(ns waymark10.in-flight-test
  "The in-flight read (ticket d0407b84): every open row, with its
  waiting_on, grouped by who holds it. `entry` and `answer` are pure,
  so the grouping is judged over maps with no engine; the wire tests
  read GET /api/-/in-flight through the real handler for the grant
  scope, the filters and anonymity."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.waiting-on :as waiting-on]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── pure: one row in each holder group ───────────────────────────────────

(def ^:private job
  {:kind :job
   :domain :work
   :states [:draft :open :parked :syncing :done]
   :initial :draft
   :terminal #{:done}
   :expected-age {:draft 3600 :open 3600 :parked 3600 :syncing 3600}
   ;; the mirror lands a syncing row; no seat takes that door
   :left-by [{:in #{:syncing} :by :mirror :via :land :to :done}]
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done}
             :release {:from #{:parked} :to :open}
             :land {:from #{:syncing} :to :done}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(def ^:private seats
  [(seat "coder" {:scope [{:kind "job" :actions ["finish"]}]
                  :cadence_seconds 3600})
   (seat "mayor" {:mode "interactive"
                  :scope [{:kind "job" :actions ["groom"]}]})])

(def ^:private now (Instant/parse "2026-10-14T12:00:00Z"))

(defn- row [id state hours-ago data]
  {:id id :state state
   :updated-at (.minusSeconds ^Instant now (long (* 3600 hours-ago)))
   :data data})

(defn- entries [rows]
  (keep #(in-flight/entry job seats {:job job} %
                          {:now now :data (:data %) :summary (str "job " (:id %))})
        rows))

(def ^:private house
  [(row "draft-1" :draft 0.5 {:repo "r1"})
   (row "open-young" :open 0.5 {:repo "r1"})
   (row "open-old" :open 2 {:repo "r2"})
   (row "parked-1" :parked 3 {:repo "r2"})
   (row "sync-1" :syncing 1.5 {:repository "r1"})
   (row "done-1" :done 9 {:repo "r1"})])

(deftest each-row-is-held-by-who-its-doors-say
  (let [by-id (into {} (map (juxt :id identity)) (entries house))]
    (is (= "person" (:holder (by-id "draft-1"))) "only the mayor grooms")
    (is (= "seat:coder" (:holder (by-id "open-old"))))
    (is (= "nobody" (:holder (by-id "parked-1"))) "no seat releases")
    (is (= "source:mirror" (:holder (by-id "sync-1"))))
    (testing "a terminal row is not in flight"
      (is (nil? (by-id "done-1"))))
    (testing "age, expected age and overdue"
      (let [e (by-id "open-old")]
        (is (= 7200 (:age_seconds e)))
        (is (= 3600 (:expected_seconds e)))
        (is (= 2.0 (:overdue e)))
        (is (= "job open-old" (:summary e)))
        (is (= "work" (:domain e)))
        (is (string? (get-in e [:waiting_on :verdict])))))
    (testing "repo reads `repository` too"
      (is (= "r1" (:repo (by-id "sync-1")))))))

;; ticket 9ff24b82: the envelope's ETag is the row's version, and
;; `waiting_on` is outside it. `since` must hold still under one
;; version, so it carries the instant and never an age.
(deftest since-holds-still-under-one-version
  (let [r (row "open-old" :open 2 {})
        read-at #(waiting-on/block job seats {:job job} r {:now % :data {}})
        first-read (read-at now)
        later (read-at (.plusSeconds ^Instant now 7200))]
    (testing "since is the instant and the expected age, the same at any clock"
      (is (= (:since first-read) (:since later)))
      (is (= #{:at :expected_seconds} (set (keys (:since first-read))))))
    (testing "the verdict's age words move with the clock, which the ETag does not cover"
      (is (not= (:verdict first-read) (:verdict later))))))

;; ticket af6ea33f: a seat's cadence counts in waiting_on only when a
;; schedule fires it; nil `scheduled` is a world that read no schedules.
(deftest waiting-on-drops-the-cadence-of-an-unscheduled-seat
  (let [r (row "open-old" :open 2 {})
        coder (fn [scheduled]
                (->> (waiting-on/block job seats {:job job} r
                                       {:now now :data {} :scheduled scheduled})
                     :next
                     (some #(when (= "finish" (:door %)) %))
                     :seats
                     (some #(when (= "coder" (:seat %)) %))))]
    (testing "a seat no schedule fires holds the door without a cadence"
      (let [c (coder #{})]
        (is (some? c))
        (is (not (contains? c :cadence_seconds)))))
    (testing "a scheduled seat keeps its cadence"
      (is (= 3600 (:cadence_seconds (coder #{"coder"})))))
    (testing "a world that read no schedules counts every stated cadence"
      (is (= 3600 (:cadence_seconds (coder nil)))))))

(def ^:private ticket
  "The shapes of ticket ca68c559: a row whose `next` opens with doors
  nobody holds is held by the later door the verdict names."
  {:kind :ticket
   :states [:draft :open :in_review :blocked :stuck :done]
   :initial :draft
   :terminal #{:done}
   :expected-age {:draft 3600 :open 3600 :in_review 3600 :blocked 3600
                  :stuck 3600}
   :left-by [{:in #{:in_review} :by :mirror :via :land :to :done}
             {:in #{:blocked} :by :wait :via :unblock :to :open}]
   :actions {:groom {:from #{:draft} :to :open}
             :defer {:from #{:open} :to :draft}
             :finish {:from #{:in_review} :to :done}
             :mend {:from #{:in_review :blocked} :to :done}
             :shelve {:from #{:in_review} :to :draft}
             :turn_back {:from #{:blocked} :to :draft}
             :land {:from #{:in_review} :to :done}
             :unblock {:from #{:blocked} :to :open}
             :retry {:from #{:stuck} :to :open}}})

(def ^:private ticket-seats
  [(seat "draft-judge" {:scope [{:kind "ticket" :actions ["groom"]}]
                        :cadence_seconds 21600})
   (seat "mayor" {:mode "interactive"
                  :scope [{:kind "ticket" :actions ["groom" "defer"]}]})])

(deftest each-state-shape-is-held-by-its-verdicts-holder
  (let [es (keep #(in-flight/entry ticket ticket-seats {:ticket ticket} %
                                   {:now now :data {} :summary (:id %)})
                 [(row "review" :in_review 1 {})
                  (row "blocked" :blocked 1 {})
                  (row "draft" :draft 1 {})
                  (row "open" :open 1 {})
                  (row "stuck" :stuck 1 {})])
        by-id (into {} (map (juxt :id identity)) es)]
    (testing "next opens with doors nobody holds, the source is named"
      (is (= "nobody" (get-in (by-id "review") [:waiting_on :next 0 :held_by])))
      (is (= "source:mirror" (:holder (by-id "review"))))
      (is (= "source:wait" (:holder (by-id "blocked")))))
    (is (= "seat:draft-judge" (:holder (by-id "draft"))))
    (is (= "person" (:holder (by-id "open"))) "only the mayor defers")
    (is (= "nobody" (:holder (by-id "stuck"))) "no seat retries: an orphan")
    (testing "the holder is the verdict's own choice"
      (is (= {:type "source" :name "mirror"}
             (get-in (by-id "review") [:waiting_on :holder])))
      (is (= {:type "seat" :name "draft-judge"}
             (get-in (by-id "draft") [:waiting_on :holder])))
      (is (= {:type "person" :name "mayor"}
             (get-in (by-id "open") [:waiting_on :holder])))
      (is (= {:type "nobody"} (get-in (by-id "stuck") [:waiting_on :holder]))))
    (testing "the screen's counts follow"
      (is (= {:waiting_on_person 1 :held_by_nobody 1 :overdue 0 :total 5}
             (:counts (in-flight/answer es {})))))))

(deftest the-answer-groups-and-sorts
  (let [doc (in-flight/answer (entries house) {})]
    (testing "person, nobody, then the seats, then the sources"
      (is (= ["person" "nobody" "seat:coder" "source:mirror"]
             (mapv :holder (:groups doc)))))
    (testing "inside a group, the most overdue first"
      (let [coder (some #(when (= "seat:coder" (:holder %)) %) (:groups doc))]
        (is (= ["open-old" "open-young"] (mapv :id (:rows coder))))
        (is (= 2 (:total coder)))
        (is (= 1 (:overdue coder)))))
    (testing "the four counts"
      (is (= {:waiting_on_person 1 :held_by_nobody 1 :overdue 3 :total 5}
             (:counts doc))))))

(deftest the-answer-filters
  (let [es (entries house)]
    (is (= ["person"]
           (mapv :holder (:groups (in-flight/answer es {:holder "person"})))))
    (is (= #{"open-old" "parked-1"}
           (into #{} (comp (mapcat :rows) (map :id))
                 (:groups (in-flight/answer es {:repo "r2"})))))
    (is (= 5 (get-in (in-flight/answer es {:domain "work" :kind "job"})
                     [:counts :total])))
    (is (= 0 (get-in (in-flight/answer es {:domain "home"}) [:counts :total])))
    (is (= {:repo "r2"} (:filters (in-flight/answer es {:repo "r2"}))))))

(deftest the-facets-are-taken-before-the-filters
  (let [es (entries house)
        facets (:facets (in-flight/answer es {}))]
    (is (= (vec (sort (distinct (keep :repo es)))) (:repo facets)))
    (is (some #{"r2"} (:repo facets)))
    (is (= (vec (sort (distinct (keep :holder es)))) (:holder facets)))
    (is (= facets (:facets (in-flight/answer es {:repo "r2"}))))
    (is (= facets (:facets (in-flight/answer es {:domain "home"}))))))

(deftest a-kind-with-no-expected-age-is-not-in-flight
  (is (nil? (in-flight/open-states {:kind :memo :states [:jotted :filed]
                                    :terminal #{:filed}})))
  (is (= [:draft :open :parked :syncing] (in-flight/open-states job))))

(deftest a-resting-state-is-not-in-flight
  ;; a record kind (ticket 4d7badd8): a said verdict waits on nothing,
  ;; only the state with an expected age is owed a move
  (is (= [:open] (in-flight/open-states {:kind :verdict
                                          :states [:open :said :void]
                                          :terminal #{:void}
                                          :expected-age {:open 3600}}))))

;; ── the wire: anonymity, grant scope, ?kind= ─────────────────────────────

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(defn- kind-of [k plural states terminal ages]
  (r/resource
   (cond-> {:kind k
            :plural plural
            :states states
            :initial (first states)
            :terminal terminal
            :summary "{data.title} · {state}"
            :schema [:map [:title [:string {:min 1 :max 80}]]]
            :actions {:close {:from #{(first states)} :to (last states)
                              :safety quiet}}}
     ages (assoc :expected-age ages))))

(def ^:private flight-task
  (kind-of :flight_task "flight_tasks" [:open :closed] #{:closed} {:open 3600}))

(def ^:private flight-chore
  (kind-of :flight_chore "flight_chores" [:open :closed] #{:closed} {:open 3600}))

(def ^:private flight-memo
  ;; no expected age in any state: never in flight
  (kind-of :flight_memo "flight_memos" [:jotted :filed] #{:filed} nil))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

(defn- get-in-flight [h headers query-string]
  (let [resp (h (cond-> {:request-method :get :uri "/api/-/in-flight"
                         :headers headers}
                  query-string (assoc :query-string query-string)))]
    (assoc resp :parsed (some-> (:body resp) wire/read-json))))

(defn- flight-ids [parsed]
  (into #{}
        (comp (mapcat :rows)
              (filter #(contains? #{"flight_task" "flight_chore" "flight_memo"}
                                  (:kind %)))
              (map :id))
        (:groups parsed)))

(deftest the-in-flight-route-on-the-wire
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [flight-task flight-chore flight-memo]})
        h (engine/handler eng)
        mk (fn [k title]
             (str (get-in (inv/create! eng k {:title title} {:principal elena})
                          [:row :id])))
        t1 (mk :flight_task "t1")
        c1 (mk :flight_chore "c1")
        c2 (mk :flight_chore "c2")
        _m1 (mk :flight_memo "m1")
        gid (get-in (inv/create!
                     eng :grant
                     {:audience "spy"
                      :scope [{:kind "flight_task" :actions []}
                              {:kind "flight_chore" :ids [c1] :actions []}]}
                     {:principal elena})
                    [:row :id])
        as-elena {"x-waymark-principal" "elena"}
        as-spy {"x-waymark-principal" "spy"
                "x-waymark-actor-type" "agent"
                "x-waymark-grant" gid}]
    (inv/invoke! eng :grant gid :accept nil {:principal spy})
    (testing "anonymous is the concealment 404"
      (is (= 404 (:status (get-in-flight h {} nil)))))
    (testing "a person reads every open row; with no seat, nobody holds them"
      (let [{:keys [status parsed]} (get-in-flight h as-elena nil)]
        (is (= 200 status))
        (is (= "in_flight" (:waymark parsed)))
        (is (= #{t1 c1 c2} (flight-ids parsed)) "the memo has no expected age")
        (is (every? #(= "nobody" (:holder %))
                    (filter #(#{t1 c1 c2} (:id %))
                            (mapcat :rows (:groups parsed)))))
        (is (= (:total (:counts parsed))
               (reduce + (map :total (:groups parsed)))))
        (is (number? (:took_ms parsed)))))
    (testing "a scoped caller sees only the rows its grant admits"
      (let [{:keys [status parsed]} (get-in-flight h as-spy nil)]
        (is (= 200 status))
        (is (= #{t1 c1} (flight-ids parsed)))
        (is (every? #{"flight_task" "flight_chore"}
                    (map :kind (mapcat :rows (:groups parsed)))))))
    (testing "?kind= narrows the answer to one kind"
      (let [{:keys [parsed]} (get-in-flight h as-elena "kind=flight_chore")]
        (is (= #{c1 c2} (flight-ids parsed)))
        (is (= {:kind "flight_chore"} (:filters parsed)))))
    (testing "?holder= narrows the answer to one group"
      (let [{:keys [parsed]} (get-in-flight h as-elena "holder=person")]
        (is (= #{} (flight-ids parsed)))))))

;; ── the cap (ticket 4d7badd8) ────────────────────────────

(defn- flight-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [flight-task flight-chore flight-memo]}))

(defn- mk-flight [eng k title]
  (str (get-in (inv/create! eng k {:title title} {:principal elena}) [:row :id])))

(deftest a-capped-answer-says-so
  (let [eng (flight-engine)
        h (engine/handler eng)
        as-elena {"x-waymark-principal" "elena"}
        tasks (fn [parsed]
                (filter #(= "flight_task" (:kind %)) (mapcat :rows (:groups parsed))))]
    (doseq [n (range 3)] (mk-flight eng :flight_task (str "t" n)))
    (testing "under the page the answer is whole"
      (let [{:keys [parsed]} (get-in-flight h as-elena "kind=flight_task")]
        (is (false? (:truncated parsed)))
        (is (nil? (:capped parsed)))
        (is (= 3 (count (tasks parsed))))))
    (testing "past the page it says it truncated, with the true count"
      (binding [in-flight/*row-page* 2]
        (let [{:keys [parsed]} (get-in-flight h as-elena "kind=flight_task")]
          (is (true? (:truncated parsed)))
          (is (= [{:kind "flight_task" :state "open" :read 2 :total 3}]
                 (:capped parsed)))
          (is (= 2 (count (tasks parsed)))))))))

;; ── over MCP: waymark_in_flight (ticket 334e4dac) ────────────────────────

(defn- flight-kind [k plural domain]
  (r/resource
   {:kind k
    :plural plural
    :domain domain
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :expected-age {:open 3600}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:repo {:optional true} [:string {:min 1 :max 80}]]]
    :actions {:close {:from #{:open} :to :closed :safety quiet}}}))

(def ^:private flight-job (flight-kind :flight_job "flight_jobs" :work))
(def ^:private flight-errand (flight-kind :flight_errand "flight_errands" :home))

(defn- in-flight-tool
  "One tools/call of waymark_in_flight → {:status :error? :parsed}, the
  tool's JSON text parsed."
  [h headers args]
  (let [resp (h {:request-method :post :uri "/api/-/mcp" :headers headers
                 :body (wire/write-json
                        {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params {:name "waymark_in_flight" :arguments args}})})
        result (:result (wire/read-json (:body resp)))]
    {:status (:status resp)
     :error? (:isError result)
     :parsed (some-> (get-in result [:content 0 :text]) wire/read-json)}))

(defn- job-ids [parsed]
  (into #{}
        (comp (mapcat :rows)
              (filter #(contains? #{"flight_job" "flight_errand"} (:kind %)))
              (map :id))
        (:groups parsed)))

(deftest waymark-in-flight-over-mcp
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [flight-job flight-errand]})
        h (engine/handler eng)
        mk (fn [k data]
             (str (get-in (inv/create! eng k data {:principal elena})
                          [:row :id])))
        j1 (mk :flight_job {:title "j1" :repo "r1"})
        j2 (mk :flight_job {:title "j2" :repo "r2"})
        e1 (mk :flight_errand {:title "e1"})
        gid (get-in (inv/create!
                     eng :grant
                     {:audience "spy"
                      :scope [{:kind "flight_job" :ids [j1] :actions []}
                              {:kind "flight_errand" :actions []}]}
                     {:principal elena})
                    [:row :id])
        as-elena {"x-waymark-principal" "elena"}
        as-spy {"x-waymark-principal" "spy"
                "x-waymark-actor-type" "agent"
                "x-waymark-grant" gid}
        ids (fn [headers args]
              (let [{:keys [status error? parsed]} (in-flight-tool h headers args)]
                (is (= 200 status))
                (is (false? error?) (pr-str parsed))
                (job-ids parsed)))]
    (inv/invoke! eng :grant gid :accept nil {:principal spy})
    (testing "the tool answers the route's document"
      (let [{:keys [error? parsed]} (in-flight-tool h as-elena {})]
        (is (false? error?))
        (is (= "in_flight" (:waymark parsed)))
        (is (= #{j1 j2 e1} (job-ids parsed)))))
    (testing "a scoped grant sees only the rows it admits"
      (is (= #{j1 e1} (ids as-spy {}))))
    (testing "kind narrows"
      (is (= #{e1} (ids as-spy {:kind "flight_errand"})))
      (is (= #{j1 j2} (ids as-elena {:kind "flight_job"}))))
    (testing "domain narrows"
      (is (= #{j1} (ids as-spy {:domain "work"})))
      (is (= #{e1} (ids as-spy {:domain "home"})))
      (is (= {:domain "work"}
             (:filters (:parsed (in-flight-tool h as-spy {:domain "work"}))))))
    (testing "repo narrows, and a concealed row stays concealed under it"
      (is (= #{j2} (ids as-elena {:repo "r2"})))
      (is (= #{} (ids as-spy {:repo "r2"})))
      (is (= #{j1} (ids as-spy {:repo "r1"}))))
    (testing "holder narrows to one group"
      (is (= #{j1 e1} (ids as-spy {:holder "nobody"})))
      (is (= #{} (ids as-spy {:holder "person"}))))
    (testing "a blank filter is left out"
      (is (= #{j1 e1} (ids as-spy {:kind "  " :repo ""}))))))
