(ns waymark10.cracks-test
  "The cracks audit (ticket 8b52a6e6): a state no active seat can
  leave, a state seats can leave and nothing wakes them to, and a
  remedy no seat holds. Only a wake_on entry wakes (ticket cd402fb6):
  a state reached by cadence alone, or left by a person alone, is its
  own crack. Then (ticket 53e55720) a state only a person can leave
  with nothing telling them, and a row value no seat's walk admits.
  `audit` is pure over declarations, seat rows and the rows it is
  handed, so the world there is maps and no engine. The wire tests
  (ticket 2ffa0f85) read GET /api/-/cracks through the real
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
    (testing "a walk alone does not clear it: the wake_on it defaults to does"
      (let [walked [{:kind "task"
                     :actions ["groom" "finish"]
                     :filter {:state "open"}}]
            walker (seat "builder" {:scope walked :walk "task"})
            deaf (seat "builder" {:scope walked :walk "task"
                                  :wake_on [{:kind "task" :actions ["finish"]}]})]
        (is (empty? (of (cracks/audit [task] [walker]) "unwoken" "open"))
            "a wake_on nobody wrote is every action of the walk, under its filter")
        (is (= [{:seat "builder" :holds ["finish"] :wakes []}]
               (:seats_considered
                (first (of (cracks/audit [task] [deaf]) "unwoken" "open"))))
            "a wake_on that lands elsewhere, and the walk says nothing")))
    (testing "a cadence is not a wake: the state is cadence_only, not unwoken"
      (let [timed (seat "builder" {:scope scope :cadence_seconds 3600})
            cracks (cracks/audit [task] [timed])]
        (is (empty? (filter #(= "unwoken" (:crack %)) cracks)))
        (is (seq (of cracks "cadence_only" "open")))))))

(deftest what-no-wake-on-covers-is-graded
  (let [scope [{:kind "task" :actions ["groom" "finish" "reopen"]}]
        daily (seat "daily" {:scope scope :cadence_seconds 86400})
        hourly (seat "hourly" {:scope scope :cadence_seconds 3600})
        mayor (seat "mayor" {:scope scope :mode "interactive"})
        idle (seat "idle" {:scope scope})
        woken (seat "woken" {:scope scope
                             :wake_on [{:kind "task" :actions ["groom"]}]})
        at-open (fn [seats]
                  (into []
                        (comp (filter #(= "open" (:state %)))
                              (remove #(= "remedy_unheld" (:crack %)))
                              (map :crack))
                        (cracks/audit [task] seats)))]
    (testing "reached by cadence alone: cadence_only, with the shortest"
      (let [[c & more] (of (cracks/audit [task] [daily hourly mayor idle])
                           "cadence_only" "open")]
        (is (nil? more))
        (is (= 3600 (:shortest_cadence_seconds c)))
        (is (= [["daily" ["cadence"]] ["hourly" ["cadence"]]
                ["mayor" ["interactive"]] ["idle" []]]
               (map (juxt :seat :wakes) (:seats_considered c))))
        (is (re-find #"3600 seconds" (:what_would_close_it c)))
        (is (re-find #"groom" (:what_would_close_it c))))
      (is (= ["cadence_only"] (at-open [daily hourly mayor idle]))))
    (testing "left by a person alone: person_only"
      (let [[c] (of (cracks/audit [task] [mayor idle]) "person_only" "open")]
        (is (some? c))
        (is (re-find #"finish" (:what_would_close_it c))))
      (is (= ["person_only"] (at-open [mayor idle])))
      (is (= ["person_only"] (at-open [mayor]))))
    (testing "neither a wake_on, a cadence nor a person: unwoken"
      (is (= ["unwoken"] (at-open [idle]))))
    (testing "one leaver with a wake_on makes the state clean"
      (is (= [] (at-open [daily mayor idle woken]))))))

;; ticket d71d2805: `patch` ends two ways, and the one that is no tomb
;; is left by `reopen` alone.
(def ^:private patch
  {:kind :patch
   :states [:open :failing :merged :closed]
   :initial :open
   :terminal #{:merged}
   :over {:accomplished #{:merged} :let-go #{:closed}}
   :actions {:fail {:from #{:open} :to :failing}
             :rework {:from #{:failing} :to :open}
             :land {:from #{:open} :to :merged}
             :close {:from #{:open :failing} :to :closed}
             :reopen {:from #{:closed} :to :open}}})

(deftest wakes-that-each-name-one-value-cover-a-state-together
  (let [builder (fn [name repo]
                  (seat name (cond-> {:scope [{:kind "patch" :actions ["rework" "close"]}]
                                      :cadence_seconds 3600}
                               repo (assoc :wake_on [{:kind "patch" :actions ["fail"]
                                                      :filter {:repository repo}}]))))
        a (builder "a" "ckopsa/a")
        b (builder "b" "ckopsa/b")
        world {:rows {:patch [{:id "1" :state :open :data {:repository "ckopsa/a"}}
                              {:id "2" :state :failing :data {:repository "ckopsa/b"}}
                              {:id "3" :state :closed :data {:repository "ckopsa/gone"}}]}}
        failing (fn [seats world]
                  (filterv #(= "failing" (:state %)) (cracks/audit [patch] seats world)))]
    (testing "a wake for each repository a live row carries: failing is clean"
      (is (= [] (failing [a b] world))
          "the closed row rests, and its repository asks for no wake"))
    (testing "drop one wake, and the crack names the repository nobody wakes for"
      (let [[c & more] (failing [a (builder "b" nil)] world)]
        (is (nil? more))
        (is (= "cadence_only" (:crack c)))
        (is (= [{:field "repository" :value "ckopsa/b" :rows 1}] (:unwoken_values c)))
        (is (= [["a" [{"repository" ["ckopsa/a"]}]] ["b" nil]]
               (map (juxt :seat :wakes_for) (:seats_considered c))))
        (is (re-find #"repository ckopsa/b" (:what_would_close_it c)))))
    (testing "with no rows read, a narrowed wake covers nothing"
      (let [[c & more] (failing [a b] nil)]
        (is (nil? more))
        (is (= "cadence_only" (:crack c)))
        (is (nil? (:unwoken_values c)))))))

(deftest an-ending-only-a-way-back-leaves-rests
  (testing "closed is declared over and left by reopen alone: no crack, with no seat at all"
    (is (= #{"open" "failing"}
           (into #{} (map :state) (cracks/audit [patch] [])))))
  (testing "an ending with another way out is still judged"
    (let [leaky (assoc-in patch [:actions :archive] {:from #{:closed} :to :merged})]
      (is (seq (of (cracks/audit [leaky] []) "no_exit" "closed"))))))

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

;; ticket 42259e4b: the seats the audit read, in the fixture's shape.
(deftest the-seat-export-prints-what-the-audit-reads
  (let [b (seat "b-seat" {:walk "task" :cadence_seconds 3600 :charter "Not this."
                          :scope [{:kind "task" :actions ["finish"]
                                   :filter {:state "open"}}]
                          :wake_on [{:kind "task" :actions ["groom"]}]})
        a (seat "a-seat" {:mode "interactive" :sitter_key "secret"
                          :scope [{:kind "task" :actions ["groom"]}]})
        doc (cracks/seat-export [b a])]
    (is (= "cracks_seats" (:waymark doc)))
    (testing "in the order of their names, each with the fields it carries"
      (is (= [{:name "a-seat" :mode "interactive"
               :scope [{:kind "task" :actions ["groom"]}]}
              {:name "b-seat" :walk "task" :cadence_seconds 3600
               :scope [{:kind "task" :actions ["finish"] :filter {:state "open"}}]
               :wake_on [{:kind "task" :actions ["groom"]}]}]
             (:seats doc))))
    (testing "the audit over the exported seats is the audit over the rows"
      (is (= (cracks/audit [task] [a b])
             (cracks/audit [task] (mapv #(seat (:name %) (dissoc % :name))
                                        (:seats doc))))))))

(deftest the-seat-export-route-on-the-wire
  (let [eng (fresh-engine)
        h (engine/handler eng)
        gid (get-in (inv/create!
                     eng :grant
                     {:audience "spy"
                      :scope [{:kind "crack_task" :actions []}]}
                     {:principal elena})
                    [:row :id])
        get-seats (fn [headers]
                    (let [resp (h {:request-method :get :uri "/api/-/cracks/seats"
                                   :headers headers})]
                      (assoc resp :parsed (some-> (:body resp) wire/read-json))))]
    (inv/invoke! eng :grant gid :accept nil {:principal spy})
    (testing "anonymous is the concealment 404"
      (is (= 404 (:status (get-seats {})))))
    (testing "a caller whose grant does not see the seat kind whole is too"
      (is (= 404 (:status (get-seats {"x-waymark-principal" "spy"
                                      "x-waymark-actor-type" "agent"
                                      "x-waymark-grant" gid})))))
    (testing "a person reads the active seats; this house has none"
      (let [{:keys [status parsed]} (get-seats {"x-waymark-principal" "elena"})]
        (is (= 200 status))
        (is (= {:waymark "cracks_seats" :seats []} parsed))))))

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

(def ^:private order
  {:kind :order
   :states [:cart :placed :void]
   :initial :cart
   :terminal #{:void}
   :actions {:place {:from #{:cart} :to :placed}
             :void {:from #{:placed} :to :void :safety {:confirm true}}}})

(deftest an-exit-only-a-person-takes-with-nothing-telling-them
  (let [clerk (seat "clerk" {:scope [{:kind "order" :actions ["place" "void"]}]
                             :cadence_seconds 3600})
        silent (fn [seats world]
                 (of (cracks/audit [order] seats world) "silent_person_exit" "placed"))]
    (testing "placed has one exit, a confirm door, and no cue"
      (let [cracks (cracks/audit [order] [clerk])
            [c & more] (of cracks "silent_person_exit" "placed")]
        (is (nil? more))
        (is (= "order" (:kind c)))
        (is (= [{:action "void" :held_by ["confirm"]}] (:exits c)))
        (is (re-find #"place" (:what_would_close_it c)))
        (is (empty? (of cracks "silent_person_exit" "cart"))
            "cart is left by a door a seat takes alone")))
    (testing "a guard declared :hold true holds the exit the same way"
      (let [held (assoc-in order [:actions :void]
                           {:from #{:placed} :to :void
                            :guards [{:name :order/over-ceiling
                                      :explain "It is past the ceiling."
                                      :hold true}]})
            [c] (of (cracks/audit [held] [clerk]) "silent_person_exit" "placed")]
        (is (= [{:action "void" :held_by ["hold"]}] (:exits c)))))
    (testing "a guard declared :hold :agents holds only an agent, so the door stays the seat's"
      (let [agents (assoc-in order [:actions :void]
                             {:from #{:placed} :to :void
                              :guards [{:name :order/an-agents-void-waits
                                        :explain "Held for your person's tap."
                                        :hold :agents}]})]
        (is (empty? (of (cracks/audit [agents] [clerk]) "silent_person_exit" "placed")))))
    (testing "one exit a seat takes alone clears it"
      (let [open (assoc-in order [:actions :ship] {:from #{:placed} :to :void})]
        (is (empty? (of (cracks/audit [open] [clerk]) "silent_person_exit" "placed")))))
    (testing "a notice rule on the arrival clears it"
      (is (empty? (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                          :when {:to_state "placed"}}}]})))
      (is (empty? (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                          :when {:action "place"}}}]}))))
    (testing "a rule on another state, another kind, or a time does not"
      (is (seq (silent [clerk] {:notice-rules [{:data {:kind "order"
                                                       :when {:to_state "void"}}}
                                               {:data {:kind "task"
                                                       :when {:to_state "placed"}}}
                                               {:data {:kind "order"
                                                       :at {:field "due"}}}]}))))
    (testing "a subscription that names the kind, or none, clears it"
      (is (empty? (silent [clerk] {:subscriptions [{:data {:kinds ["order"]}}]})))
      (is (empty? (silent [clerk] {:subscriptions [{:data {}}]})))
      (is (seq (silent [clerk] {:subscriptions [{:data {:kinds ["task"]}}]}))))
    (testing "a cue in the inbox of a seat a person sits in clears it"
      (let [inbox {:cues [{:kind "order" :actions ["place"] :note "An order came."}]}
            desk (seat "desk" {:mode "interactive" :inbox inbox})
            fired (seat "fired" {:inbox inbox})]
        (is (empty? (silent [clerk desk] nil)))
        (is (seq (silent [clerk fired] nil))
            "a fired seat's inbox tells a model, not the person")))
    (testing "a mayor that cues its inbox on its wakes, with no note, clears it"
      (let [wakes [{:kind "order" :actions ["place"]}]
            mayor (seat "mayor" {:mode "interactive" :wake_on wakes
                                 :inbox {:cues wakes}})]
        (is (empty? (silent [clerk mayor] nil)))))))

(def ^:private draft-ticket
  {:kind :ticket
   :states [:draft :open :done]
   :initial :draft
   :terminal #{:done}
   :actions {:groom {:from #{:draft} :to :open}
             :complete {:from #{:open} :to :done}}})

(deftest a-value-no-walk-admits-is-unwalked
  (let [walker (fn [name fm]
                 (seat name {:scope [{:kind "ticket" :actions ["complete"] :filter fm}]
                             :walk "ticket"}))
        builder (walker "builder" {:repo "ckopsa/waymark" :state "open"})
        rows {:ticket [{:id "1" :state :open :data {:repo "ckopsa/waymark"}}
                       {:id "2" :state :open :data {:repo "ckopsa/waymark-bench"}}
                       {:id "3" :state :done :data {:repo "ckopsa/gone"}}]}
        unwalked (fn [seats]
                   (filterv #(= "unwalked_value" (:crack %))
                            (cracks/audit [draft-ticket] seats {:rows rows})))]
    (testing "an open ticket in a repo no seat's filter admits"
      (let [[c & more] (unwalked [builder])]
        (is (nil? more) "the admitted repo and the terminal row are no crack")
        (is (= "ticket" (:kind c)))
        (is (= "repo" (:field c)))
        (is (= "ckopsa/waymark-bench" (:value c)))
        (is (= 1 (:rows c)))
        (is (= ["open"] (:states c)))
        (is (= [{:seat "builder" :admits ["ckopsa/waymark"]}] (:seats_considered c)))
        (is (re-find #"ckopsa/waymark-bench" (:what_would_close_it c)))))
    (testing "a second seat whose walk admits the repo clears it"
      (is (empty? (unwalked [builder (walker "bench" {:repo "ckopsa/waymark-bench"})]))))
    (testing "a walk that does not filter on the field admits every value"
      (is (empty? (unwalked [builder (walker "any" {:state "open"})]))))
    (testing "with no rows read there is nothing to report"
      (is (empty? (filter #(= "unwalked_value" (:crack %))
                          (cracks/audit [draft-ticket] [builder])))))))

;; ticket 5cb126ab: a seat that walks the drafts of every repo hid the
;; open row no builder is handed. The kind is said here, beside the test.
(deftest a-walk-shut-to-the-state-hides-no-value
  (let [ticket {:kind :ticket
                :states [:draft :open :done]
                :initial :draft
                :terminal #{:done}
                :actions {:groom {:from #{:draft} :to :open}
                          :complete {:from #{:open} :to :done}}}
        builder (seat "builder" {:scope [{:kind "ticket" :actions ["complete"]
                                          :filter {:repo "ckopsa/waymark" :state "open"}}]
                                 :walk "ticket"})
        groomer (seat "groomer" {:scope [{:kind "ticket" :actions ["groom"]
                                          :filter {:state "draft"}}]
                                 :walk "ticket"})
        rows {:ticket [{:id "1" :state :open :data {:repo "ckopsa/waymark"}}
                       {:id "2" :state :open :data {:repo "ckopsa/messa"}}
                       {:id "3" :state :draft :data {:repo "ckopsa/messa"}}]}
        unwalked (fn [seats]
                   (filterv #(= "unwalked_value" (:crack %))
                            (cracks/audit [ticket] seats {:rows rows})))]
    (testing "an open ticket in a repo no seat that walks open admits"
      (let [[c & more] (unwalked [builder groomer])]
        (is (nil? more) "the draft is walked by the groomer, whatever its repo")
        (is (= "repo" (:field c)))
        (is (= "ckopsa/messa" (:value c)))
        (is (= 1 (:rows c)))
        (is (= ["open"] (:states c)))
        (is (= [{:seat "builder" :admits ["ckopsa/waymark"]}] (:seats_considered c)))))
    (testing "a seat woken for the row, that can move it, clears it"
      (is (empty? (unwalked [builder groomer
                             (seat "any" {:scope [{:kind "ticket" :actions ["complete"]}]
                                          :wake_on [{:kind "ticket" :actions ["groom"]}]})]))))
    (testing "a wake whose filter shuts the repo out does not"
      (is (seq (unwalked [builder groomer
                          (seat "b" {:scope [{:kind "ticket" :actions ["complete"]}]
                                     :wake_on [{:kind "ticket" :actions ["groom"]
                                                :filter {:repo "ckopsa/b"}}]})]))))))

;; ticket 8186d41a: the mayor holds doors out of open in every repo, and
;; a person's sitting is no wake. It hid the open row no builder is
;; handed, by its walk and by the wake_on a walk seat is given.
(deftest a-seat-a-person-sits-in-hides-no-value
  (let [ticket {:kind :ticket
                :states [:draft :open :in_review :done]
                :initial :draft
                :terminal #{:done}
                :left-by [{:in #{:open} :by {:kind :change :action :submit}
                           :via :review :to :in_review}]
                :actions {:groom {:from #{:draft} :to :open}
                          :ungroom {:from #{:open} :to :draft}
                          :review {:from #{:open} :to :in_review}
                          :land {:from #{:in_review} :to :done}}}
        change {:kind :change
                :states [:open :submitted]
                :initial :open
                :terminal #{:submitted}
                :actions {:submit {:from #{:open} :to :submitted}}}
        builder (seat "builder" {:scope [{:kind "ticket" :actions ["ungroom"]
                                          :filter {:repo "ckopsa/waymark" :state "open"}}
                                         {:kind "change" :actions ["submit"]}]
                                 :walk "ticket"})
        mayor (fn [fm]
                (seat "mayor" {:mode "interactive" :cadence_seconds 86400
                               :scope [(cond-> {:kind "ticket" :actions ["groom" "ungroom"]}
                                         fm (assoc :filter fm))
                                       {:kind "change" :actions ["submit"]}]
                               :walk "ticket"}))
        rows {:ticket [{:id "1" :state :open :data {:repo "ckopsa/waymark"}}
                       {:id "2" :state :open :data {:repo "ckopsa/messa"}}]}
        unwalked (fn [seats]
                   (filterv #(= "unwalked_value" (:crack %))
                            (cracks/audit [ticket change] seats {:rows rows})))]
    (testing "an interactive mayor with every repo in scope clears nothing"
      (doseq [fm [nil {:state "draft,open"}]]
        (let [[c & more] (unwalked [builder (mayor fm)])]
          (is (nil? more))
          (is (= "repo" (:field c)))
          (is (= "ckopsa/messa" (:value c)))
          (is (= 1 (:rows c)))
          (is (= ["open"] (:states c)))
          (is (= [{:seat "builder" :admits ["ckopsa/waymark"]}] (:seats_considered c))))))
    (testing "a wake_on entry the mayor wrote is a wake"
      (is (empty? (unwalked [builder
                             (assoc-in (mayor nil) [:data :wake_on]
                                       [{:kind "ticket" :actions ["groom"]}])]))))
    (testing "a woken seat that holds only an administrative exit moves no work"
      (is (seq (unwalked [builder
                          (seat "clerk" {:scope [{:kind "ticket" :actions ["ungroom"]}]
                                         :wake_on [{:kind "ticket" :actions ["groom"]}]})]))))
    (testing "a woken seat that holds the door the kind declares clears it"
      (is (empty? (unwalked [builder
                             (seat "any" {:scope [{:kind "change" :actions ["submit"]}]
                                          :wake_on [{:kind "ticket" :actions ["groom"]}]})]))))))

;; ticket af58f25f: a seat that walks the open tickets of every repo and
;; holds only drop hid the open row no builder is handed. The seats are
;; written in the export's shape after the house's, by hand: the
;; generated fixture is not in the repository yet.
(deftest a-walk-that-cannot-take-the-declared-exit-hides-no-value
  (let [ticket {:kind :ticket
                :states [:draft :open :in_review :done :dropped]
                :initial :draft
                :terminal #{:done :dropped}
                :left-by [{:in #{:open} :by {:kind :change :action :submit}
                           :via :review :to :in_review}]
                :actions {:groom {:from #{:draft} :to :open}
                          :ungroom {:from #{:open} :to :draft}
                          :drop {:from #{:draft :open} :to :dropped}
                          :review {:from #{:open} :to :in_review}
                          :land {:from #{:in_review} :to :done}}}
        change {:kind :change
                :states [:open :submitted]
                :initial :open
                :terminal #{:submitted}
                :actions {:submit {:from #{:open} :to :submitted}}}
        builder (seat "code-seat" {:scope [{:kind "ticket" :actions ["ungroom"]
                                            :filter {:repo "ckopsa/waymark" :state "open"}}
                                           {:kind "change" :actions ["submit"]}]
                                   :walk "ticket"
                                   :wake_on [{:kind "ticket" :actions ["groom"]
                                              :filter {:repo "ckopsa/waymark"}}]})
        judge (seat "draft-judge" {:scope [{:kind "ticket" :actions ["drop"]}]
                                   :walk "ticket"
                                   :wake_on [{:kind "ticket" :actions ["create" "ungroom"]
                                              :filter {:state "draft"}}]})
        director (seat "director" {:scope [{:kind "ticket" :actions ["drop"]
                                            :filter {:state "draft,open"}}]
                                   :walk "ticket"})
        rows {:ticket [{:id "1" :state :open :data {:repo "ckopsa/waymark"}}
                       {:id "2" :state :open :data {:repo "ckopsa/messa"}}
                       {:id "3" :state :draft :data {:repo "ckopsa/messa"}}]}
        unwalked (fn [seats]
                   (filterv #(= "unwalked_value" (:crack %))
                            (cracks/audit [ticket change] seats {:rows rows})))]
    (testing "the open ticket of a repo no builder walks is reported"
      (doseq [seats [[builder judge] [builder director] [builder judge director]]]
        (let [[c & more] (unwalked seats)]
          (is (nil? more) "the draft is walked, whatever its repo")
          (is (= "repo" (:field c)))
          (is (= "ckopsa/messa" (:value c)))
          (is (= 1 (:rows c)))
          (is (= ["open"] (:states c)))
          (is (= [{:seat "code-seat" :admits ["ckopsa/waymark"]}] (:seats_considered c))))))
    (testing "a walk of every repo by a seat that holds the declared door clears it"
      (is (empty? (unwalked [builder judge
                             (seat "any" {:scope [{:kind "ticket" :actions ["ungroom"]
                                                   :filter {:state "open"}}
                                                  {:kind "change" :actions ["submit"]}]
                                          :walk "ticket"})]))))))

;; ticket d9edac4e: a kind says what its rows wait on. `job` waits the
;; two ways a ticket does: a blocker's ending releases it whichever way
;; it ends, and a merge_after job that is dropped does nothing.
(def ^:private job
  {:kind :job
   :states [:open :blocked :in_review :done :dropped]
   :initial :open
   :terminal #{:done :dropped}
   :waits [{:on :blocked_by :awaits :job :in #{:blocked}
            :outcomes {:done :release :dropped :release}}
           {:on :merge_after :awaits :job :in #{:in_review}
            :restated-by [:merge_after_in_review]
            :outcomes {:done :release :dropped :nothing}}]
   :actions {:block {:from #{:open} :to :blocked}
             :unblock {:from #{:blocked} :to :open}
             :submit {:from #{:open} :to :in_review}
             :merge_after_in_review {:from #{:in_review} :to :in_review}
             :land {:from #{:in_review} :to :done}
             :drop {:from #{:open :blocked :in_review} :to :dropped}}})

(defn- dead-waits [seats]
  (filterv #(= "dead_wait" (:crack %)) (cracks/audit [job] seats)))

(deftest an-outcome-that-does-nothing-with-no-seat-woken-is-a-dead-wait
  (testing "merge_after on a dropped job is a dead wait"
    (let [[c & more] (dead-waits [])]
      (is (nil? more) "done releases, and so it is no crack")
      (is (= {:kind "job" :state "in_review" :on "merge_after"
              :awaits "job" :outcome "dropped" :seats_considered []}
             (select-keys c [:kind :state :on :awaits :outcome :seats_considered])))
      (is (re-find #"merge_after_in_review" (:what_would_close_it c)))
      (is (re-find #"naming one of: drop" (:what_would_close_it c)))))
  (testing "blocked_by on a dropped job is none: the drop releases it"
    (is (empty? (filter #(= "blocked_by" (:on %)) (dead-waits [])))))
  (let [scope [{:kind "job" :actions ["merge_after_in_review"]}]]
    (testing "a seat that holds the door and is not woken leaves it dead"
      (let [[c] (dead-waits [(seat "mayor" {:scope scope})])]
        (is (= [{:seat "mayor" :holds ["merge_after_in_review"] :wakes []}]
               (:seats_considered c)))))
    (testing "a wake on the drop, or a cadence, brings it to act"
      (is (empty? (dead-waits [(seat "mayor" {:scope scope
                                              :wake_on [{:kind "job" :actions ["drop"]}]})])))
      (is (empty? (dead-waits [(seat "mayor" {:scope scope :cadence_seconds 3600})]))))
    (testing "a wake on another ending does not"
      (is (seq (dead-waits [(seat "mayor" {:scope scope
                                           :wake_on [{:kind "job" :actions ["land"]}]})]))))
    (testing "a seat woken by the drop that holds no door on the waiter does not"
      (is (seq (dead-waits [(seat "watcher" {:scope [{:kind "job" :actions ["unblock"]}]
                                             :wake_on [{:kind "job" :actions ["drop"]}]})]))))))

(deftest a-wait-of-no-row-is-cleared-by-the-wake-alone
  (let [hold {:kind :hold
              :states [:held :done :refused]
              :initial :held
              :terminal #{:done :refused}
              :waits [{:on :hold :awaits :hold :waiter "the caller"
                       :outcomes {:done :release :refused :nothing}}]
              :actions {:allow {:from #{:held} :to :done}
                        :refuse {:from #{:held} :to :refused}}}
        dead (fn [seats]
               (filterv #(= "dead_wait" (:crack %)) (cracks/audit [hold] seats)))]
    (testing "the caller holds no door, so the crack names no state"
      (let [[c & more] (dead (vector (seat "timed" {:cadence_seconds 60})))]
        (is (nil? more))
        (is (nil? (:state c)))
        (is (= "refused" (:outcome c)))))
    (testing "a seat woken by the refusal clears it"
      (is (empty? (dead [(seat "caller" {:wake_on [{:kind "hold" :actions ["refuse"]}]})]))))))

;; ticket 071c563c: a kind says which of its states another kind's door,
;; or a source with no seat, moves a row out of.
(def ^:private ticket
  {:kind :ticket
   :states [:open :in_review :blocked :done]
   :initial :open
   :terminal #{:done}
   :waits [{:on :blocked_by :awaits :ticket :in #{:blocked}
            :outcomes {:done :release}}]
   :left-by [{:in #{:open} :by {:kind :change :action :submit}
              :via :review :to :in_review}
             {:in #{:in_review} :by :mirror :via :land :to :done}
             {:in #{:blocked} :by :wait :via :unblock :to :open}]
   :actions {:block {:from #{:open} :to :blocked}
             :unblock {:from #{:blocked} :to :open}
             :review {:from #{:open} :to :in_review}
             :land {:from #{:in_review} :to :done}}})

(def ^:private change
  {:kind :change
   :states [:open :submitted]
   :initial :open
   :terminal #{:submitted}
   :actions {:submit {:from #{:open} :to :submitted}}})

(defn- ticket-cracks [ticket seats]
  (filterv #(= "ticket" (:kind %)) (cracks/audit [ticket change] seats)))

(deftest a-state-another-kinds-door-leaves-is-no-crack
  (let [scope [{:kind "change" :actions ["submit"]}]
        code (seat "code" {:scope scope :cadence_seconds 3600})]
    (testing "the code seat holds change.submit and no ticket door"
      (let [cracks (ticket-cracks ticket [code])]
        (is (empty? (of cracks "no_exit" "open")))
        (is (empty? (of cracks "unwoken" "open")))))
    (testing "with nothing to wake it, it is unwoken as a direct holder is"
      (let [[c] (of (ticket-cracks ticket [(seat "code" {:scope scope})])
                    "unwoken" "open")]
        (is (= [{:seat "code" :holds ["change/submit"] :wakes []}]
               (:seats_considered c)))))
    (testing "a wake_on entry on the ticket wakes it, whatever its filter"
      (let [woken (seat "code" {:scope scope
                                :wake_on [{:kind "ticket" :actions ["create"]
                                           :filter {:repo "a"}}]})]
        (is (empty? (of (ticket-cracks ticket [woken]) "unwoken" "open")))))
    (testing "no seat holds either door, and the crack names both"
      (let [[c] (of (ticket-cracks ticket []) "no_exit" "open")]
        (is (re-find #"review" (:what_would_close_it c)))
        (is (re-find #"change/submit" (:what_would_close_it c)))))
    (testing "removing the declaration brings the crack back"
      (is (seq (of (ticket-cracks (dissoc ticket :left-by) [code])
                   "no_exit" "open"))))))

;; ticket 4b0460cc: a kind says which of its states another kind's door
;; brings a row to, and a wake on that door is a wake to the state.
(def ^:private born
  (assoc change :reached-by [{:in #{:open} :by {:kind :ticket :action :land}}]))

(deftest a-wake-on-the-door-that-brings-a-row-wakes-its-seat
  (let [code (fn [wake]
               (seat "code" {:scope [{:kind "change" :actions ["submit"]
                                      :filter {:repository "a"}}]
                             :cadence_seconds 3600
                             :wake_on wake}))
        open (fn [rdef wake]
               (into []
                     (comp (filter #(and (= "change" (:kind %)) (= "open" (:state %))))
                           (map :crack))
                     (cracks/audit [ticket rdef] [(code wake)])))]
    (testing "a wake on the ticket's door wakes it, whatever its filter"
      (is (= [] (open born [{:kind "ticket" :actions ["land"]
                             :filter {:state "done" :repo "a"}}]))))
    (testing "a wake on another door of the ticket does not"
      (is (= ["cadence_only"] (open born [{:kind "ticket" :actions ["block"]}]))))
    (testing "removing the declaration brings the crack back"
      (is (= ["cadence_only"] (open change [{:kind "ticket" :actions ["land"]}]))))))

(deftest a-state-a-source-leaves-needs-no-seat
  (testing "the mirror's land leaves in_review, and a wait's ending blocked"
    (is (= #{"open"}
           (into #{} (map :state) (ticket-cracks ticket [])))))
  (testing "removing the declaration brings both back"
    (is (= #{"open" "in_review" "blocked"}
           (into #{} (comp (filter #(= "no_exit" (:crack %))) (map :state))
                 (ticket-cracks (dissoc ticket :left-by) []))))))

(deftest a-measure-telling-no-active-seat-is-a-crack
  (let [order {:kind :order
               :states [:placed :shipped]
               :initial :placed
               :terminal #{:shipped}
               :actions {:ship {:from #{:placed} :to :shipped}}
               :measures {:shipped_on_time {:label "Shipped on time"
                                            :tell "owner-seat"}}}
        owner (seat "owner-seat" {:scope [{:kind "order" :actions ["ship"]}]})
        told (fn [rdef seats]
               (filterv #(= "measure-tell-unseated" (:crack %))
                        (cracks/audit [rdef] seats)))]
    (testing "a :tell naming an active seat is no crack"
      (is (empty? (told order [owner]))))
    (testing "a :tell naming a seat that is not active is"
      (let [[c & more] (told order [])]
        (is (nil? more))
        (is (= {:kind "order" :measure "shipped_on_time" :seat "owner-seat"}
               (select-keys c [:kind :measure :seat])))
        (is (= "order.shipped_on_time tells `owner-seat`, which is not an active seat; a miss would tell nobody."
               (:what_would_close_it c)))))
    (testing "a measure with no :tell is [measure-untold]'s, not this crack"
      (is (empty? (told (update-in order [:measures :shipped_on_time] dissoc :tell) []))))
    (testing "retiring the seat it tells opens the crack at the seat write"
      (is (= ["measure-tell-unseated"]
             (into [] (comp (filter :measure) (map :crack))
                   (cracks/opened [order] [owner] [])))))))
