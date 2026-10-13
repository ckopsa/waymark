(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds. `audit` is pure over declarations and seat
  rows, so the world there is two maps and no engine. The wire tests
  at the end (ticket 2ffa0f85) read GET /api/-/cracks through the real
  handler, and the same document on waymark_schema's `cracks`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:private task
  {:kind :task
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :actions {:groom {:from #{:draft} :to :open}
             :finish {:from #{:open} :to :done
                      :guards [{:name :task/needs-reopen
                                :explain "It was shelved."
                                :remedies [:task/reopen]}]}
             :reopen {:from #{:done} :to :open}
             :note {:from #{:open} :to :open}}})

(defn- seat [name data]
  {:id name :state :active :data (assoc data :name name)})

(defn- of [cracks crack state]
  (filterv #(and (= crack (:crack %)) (= state (:state %))) cracks))

(deftest a-state-no-seat-leaves-is-no-exit
  (let [groomer (seat "groomer" {:scope [{:kind "task" :actions ["groom"]}]
                                 :cadence_seconds 3600})
        cracks (cracks/audit [task] [groomer])]
    (testing "open has finish, and no seat holds it"
      (let [[c & more] (of cracks "no_exit" "open")]
        (is (nil? more))
        (is (= "task" (:kind c)))
        (is (= [{:seat "groomer" :holds []}] (:seats_considered c)))
        (is (re-find #"finish" (:what_would_close_it c)))
        (is (not (re-find #"note" (:what_would_close_it c)))
            "an action that lands where it left is not a way out")))
    (testing "draft is left by the groomer, and done is terminal"
      (is (empty? (of cracks "no_exit" "draft")))
      (is (empty? (filter #(= "done" (:state %)) cracks))))
    (testing "a scope filter that shuts the state out holds nothing there"
      (let [shut (seat "shut" {:scope [{:kind "task" :actions ["finish"]
                                        :filter {:state "draft"}}]})]
        (is (seq (of (cracks/audit [task] [groomer shut]) "no_exit" "open")))))
    (testing "with no seats every non-terminal state is a crack"
      (is (= #{"draft" "open"}
             (into #{} (comp (filter #(= "no_exit" (:crack %))) (map :state))
                   (cracks/audit [task] [])))))))

(deftest a-seat-nothing-wakes-is-unwoken
  (let [scope [{:kind "task" :actions ["groom" "finish" "reopen"]}]
        idle (seat "builder" {:scope scope})
        cracks (cracks/audit [task] [idle])]
    (testing "it can leave open, and has no wake_on, walk or cadence"
      (let [[c] (of cracks "unwoken" "open")]
        (is (some? c))
        (is (= [{:seat "builder" :holds ["finish"] :wakes []}]
               (:seats_considered c)))
        (is (re-find #"groom" (:what_would_close_it c))))
      (is (empty? (of cracks "no_exit" "open"))))
    (testing "a wake_on entry on a transition into the state clears it"
      (let [woken (seat "builder" {:scope scope
                                   :wake_on [{:kind "task" :actions ["groom"]}]})]
        (is (empty? (of (cracks/audit [task] [woken]) "unwoken" "open")))))
    (testing "a wake_on entry on a transition that lands elsewhere does not"
      (let [elsewhere (seat "builder" {:scope scope
                                       :wake_on [{:kind "task" :actions ["finish"]}]})]
        (is (seq (of (cracks/audit [task] [elsewhere]) "unwoken" "open")))))
    (testing "a wake_on filter narrower than the scope filter does not"
      (let [narrow (seat "builder" {:scope scope
                                    :wake_on [{:kind "task" :actions ["groom"]
                                               :filter {:repo "a"}}]})]
        (is (seq (of (cracks/audit [task] [narrow]) "unwoken" "open")))))
    (testing "create wakes a seat to the initial state"
      (let [born (seat "builder" {:scope scope
                                  :wake_on [{:kind "task" :actions ["create"]}]})
            cracks (cracks/audit [task] [born])]
        (is (empty? (of cracks "unwoken" "draft")))
        (is (seq (of cracks "unwoken" "open")))))
    (testing "a walk whose filter admits the state clears it"
      (let [walker (seat "builder" {:scope [{:kind "task"
                                             :actions ["groom" "finish"]
                                             :filter {:state "open"}}]
                                    :walk "task"})
            cracks (cracks/audit [task] [walker])]
        (is (empty? (of cracks "unwoken" "open")))))
    (testing "a cadence clears it"
      (let [timed (seat "builder" {:scope scope :cadence_seconds 3600})]
        (is (empty? (filter #(= "unwoken" (:crack %))
                            (cracks/audit [task] [timed]))))))))

(deftest a-remedy-no-seat-holds-is-remedy-unheld
  (let [finisher (seat "finisher" {:scope [{:kind "task" :actions ["groom" "finish"]}]
                                   :cadence_seconds 3600})
        cracks (cracks/audit [task] [finisher])]
    (testing "finish refuses naming task/reopen, and no seat holds reopen"
      (let [[c & more] (of cracks "remedy_unheld" "open")]
        (is (nil? more))
        (is (= "finish" (:door c)))
        (is (= "task/reopen" (:remedy c)))
        (is (= [{:seat "finisher" :holds []}] (:seats_considered c)))))
    (testing "a seat that holds the remedy clears it"
      (let [reopener (seat "reopener" {:scope [{:kind "task" :actions ["reopen"]}]})]
        (is (empty? (filter #(= "remedy_unheld" (:crack %))
                            (cracks/audit [task] [finisher reopener]))))))))

;; ── the wire: anonymity, projection, ?kind= ─────────────────────────

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private crack-task
  ;; granted whole to the agent
  (r/resource
   {:kind :crack_task
    :plural "crack_tasks"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions {:finish {:from #{:open} :to :closed :safety quiet}}}))

(def ^:private crack-chore
  ;; granted by ids only: not seen whole, so absent
  (r/resource
   {:kind :crack_chore
    :plural "crack_chores"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions {:archive {:from #{:open} :to :done :safety quiet}}}))

(def ^:private crack-memo
  ;; never granted to the agent
  (r/resource
   {:kind :crack_memo
    :plural "crack_memos"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions {:file {:from #{:open} :to :filed :safety quiet}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [crack-task crack-chore crack-memo]}))

(defn- get-cracks [h headers query-string]
  (let [resp (h (cond-> {:request-method :get :uri "/api/-/cracks"
                         :headers headers}
                  query-string (assoc :query-string query-string)))]
    (assoc resp :parsed (some-> (:body resp) wire/read-json))))

(defn- kinds-of [parsed]
  (into #{} (map :kind) (:cracks parsed)))

(deftest the-cracks-route-on-the-wire
  (let [eng (fresh-engine)
        h (engine/handler eng)
        c1 (get-in (inv/create! eng :crack_chore {:title "ids-narrowed"}
                                {:principal elena})
                   [:row :id])
        gid (get-in (inv/create!
                     eng :grant
                     {:audience "spy"
                      :scope [{:kind "crack_task" :actions []}
                              {:kind "crack_chore" :ids [c1] :actions []}]}
                     {:principal elena})
                    [:row :id])
        as-spy {"x-waymark-principal" "spy"
                "x-waymark-actor-type" "agent"
                "x-waymark-grant" gid}]
    (inv/invoke! eng :grant gid :accept nil {:principal spy})
    (testing "anonymous is the concealment 404"
      (is (= 404 (:status (get-cracks h {} nil)))))
    (testing "a person reads every kind; with no seat, each open state is a crack"
      (let [{:keys [status parsed]} (get-cracks
                                     h {"x-waymark-principal" "elena"} nil)]
        (is (= 200 status))
        (is (= "cracks" (:waymark parsed)))
        (is (= (:total parsed) (count (:cracks parsed))))
        (is (every? (kinds-of parsed) ["crack_task" "crack_chore" "crack_memo"]))))
    (testing "a scoped caller: the whole-granted kind present; the
              ids-narrowed and the ungranted byte-level absent"
      (let [{:keys [status body parsed]} (get-cracks h as-spy nil)]
        (is (= 200 status))
        (is (contains? (kinds-of parsed) "crack_task"))
        (is (not (str/includes? body "crack_chore")))
        (is (not (str/includes? body "crack_memo")))))
    (testing "?kind= narrows the answer to one kind"
      (let [{:keys [status parsed]} (get-cracks
                                     h {"x-waymark-principal" "elena"}
                                     "kind=crack_task")]
        (is (= 200 status))
        (is (= 1 (:total parsed)))
        (is (= {:no_exit 1} (:by_crack parsed)))
        (is (= [["crack_task" "open" "no_exit"]]
               (mapv (juxt :kind :state :crack) (:cracks parsed))))))
    (testing "?kind= does not widen a scoped caller's projection"
      (let [{:keys [status parsed]} (get-cracks h as-spy "kind=crack_memo")]
        (is (= 200 status))
        (is (= 0 (:total parsed)))
        (is (= [] (:cracks parsed)))))))

(deftest waymark-schema-carries-the-kinds-cracks
  (let [eng (fresh-engine)
        out (mcp/call-tool eng (mcp/door eng) {:principal elena}
                           "waymark_schema" {:kind "crack_task"})
        doc (wire/read-json (get-in out [:content 0 :text]))
        route (:parsed (get-cracks (engine/handler eng)
                                   {"x-waymark-principal" "elena"}
                                   "kind=crack_task"))]
    (is (not (:isError out)))
    (is (= 1 (get-in doc [:cracks :total])))
    (is (= (dissoc route :waymark) (:cracks doc))
        "the tool's field is the route's answer for this kind")))
