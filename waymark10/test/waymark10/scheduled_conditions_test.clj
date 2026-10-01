(ns waymark10.scheduled-conditions-test
  "The `conditions` validity rule (docs/spec-scheduled-actions.md, child
  5, R-2.3): the collection filter's grammar over one row. Memory
  storage, the real engine and a clock the test holds. The clock that
  starts a run is child 2's, so a test claims a row and runs it by hand."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(r/defhandler revise [row inp _ctx]
  (update row :data merge inp))

(def ^:private errand
  "The row a scheduled call acts on, with one field for each operator of
  R-2.3's table."
  (r/resource
   {:kind :errand
    :plural "errands"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:priority {:x-display {:label "Priority"}} [:int {:min 1 :max 5}]]
     [:due {:x-display {:label "Due"}} :waymark/date]
     [:note {:optional true :x-display {:label "Note"}}
      [:maybe [:string {:min 1 :max 40}]]]]
    :filterable {:state #{:eq :in}
                 :title #{:contains}
                 :priority #{:eq :in :ne :range}
                 :due #{:after :before}
                 :note #{:set}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}
     ;; an edit: the fields move and the state does not
     :revise {:from #{:open} :to :open
              :input [:map
                      [:title {:optional true :x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:priority {:optional true :x-display {:label "Priority"}}
                       [:int {:min 1 :max 5}]]
                      [:due {:optional true :x-display {:label "Due"}} :waymark/date]
                      [:note {:optional true :x-display {:label "Note"}}
                       [:maybe [:string {:min 1 :max 40}]]]]
              :handler revise
              :edit {:fence false
                     :unfenced-reason "The fields are written as sent, and a test has one writer."}
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Revise" :order 2}}}}))

(def ^:private noon (Instant/parse "2026-10-01T12:00:00Z"))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private planner (t/principal {:id "planner" :type :agent :display "Planner"}))

(defn- member! [eng id actor-type]
  (inv/create! eng :member
               {:display (str id) :actor_type actor-type}
               {:principal scheduled/engine-actor :id id}))

(defn- fresh-engine
  "An engine whose clock is the atom's instant, with the scheduler's
  member row: the run builds its principal from it."
  ([] (fresh-engine (atom noon)))
  ([clock]
   (let [eng (engine/engine {:storage (memory/storage)
                             :resources [errand]
                             :now-fn (fn [] @clock)})]
     (member! eng "colton" "human")
     eng)))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- errand-state [eng e] (some-> (row-of eng :errand e) :state name))

(defn- why-of [eng id]
  (get-in (row-of eng :scheduled_action id) [:data :outcome_why]))

(defn- errand!
  "An open errand: priority 2, due on the tenth, and no note."
  [eng]
  (:id (:row (inv/create! eng :errand
                          {:title "Dishes" :priority 2 :due "2026-10-10"}
                          {:principal person}))))

(defn- revise! [eng e body]
  (inv/invoke! eng :errand (str e) :revise body {:principal person}))

(defn- under
  "The body of a call that runs only while `conditions` hold."
  [conditions]
  {:validity "conditions" :conditions conditions})

(defn- schedule!
  "Schedule `finish` on an errand for 08:30 the next morning in Denver,
  with `extra` laid over the body: the row's id."
  ([eng e extra] (schedule! eng e extra {:principal person}))
  ([eng e extra opts]
   (:id (:row (inv/create! eng :scheduled_action
                           (merge {:target {:kind "errand" :action "finish" :id (str e)}
                                   :run_at "2026-10-02T08:30:00-06:00"}
                                  extra)
                           opts)))))

(defn- refused
  "Everything a refusal carries, as text, or nil when the call went
  through."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (str (ex-message e) " " (pr-str (ex-data e))))))

(defn- run!
  "Claim the row and run it, as the clock will: the ending's name."
  [eng id]
  (when (scheduled/start! eng id)
    (some-> (scheduled/run! eng id) name)))

;; ── the grammar, one case for each operator (R-2.3) ──────────────────

(def ^:private operators
  "The operator, a condition the new errand meets, an edit after which
  it does not, and what the skip then says."
  [["equals" {:priority "2"} {:priority 1}
    "priority is 1, and the condition was priority=2."]
   ["any of" {:priority "2,3"} {:priority 1}
    "priority is 1, and the condition was priority=2,3."]
   ["_ne" {:priority_ne "1"} {:priority 1}
    "priority is 1, and the condition was priority_ne=1."]
   ["_gte" {:priority_gte "2"} {:priority 1}
    "priority is 1, and the condition was priority_gte=2."]
   ["_lte" {:priority_lte "2"} {:priority 3}
    "priority is 3, and the condition was priority_lte=2."]
   ["_after" {:due_after "2026-10-05"} {:due "2026-10-02"}
    "due is 2026-10-02, and the condition was due_after=2026-10-05."]
   ["_before" {:due_before "2026-10-20"} {:due "2026-10-25"}
    "due is 2026-10-25, and the condition was due_before=2026-10-20."]
   ["_set" {:note_set "false"} {:note "Soak first"}
    "note is Soak first, and the condition was note_set=false."]
   ["_contains" {:title_contains "dish"} {:title "Pans"}
    "title is Pans, and the condition was title_contains=dish."]])

(deftest each-operator-runs-while-it-holds-and-skips-when-it-does-not
  (let [eng (fresh-engine)]
    (doseq [[label conditions edit sentence] operators]
      (testing label
        (testing "a condition that holds at the time runs"
          (let [e (errand! eng)
                id (schedule! eng e (under conditions))]
            (is (= "done" (run! eng id)))
            (is (= "done" (errand-state eng e)))))
        (testing "a condition that held at scheduling and fails at the time skips"
          (let [e (errand! eng)
                id (schedule! eng e (under conditions))]
            (revise! eng e edit)
            (is (= "skipped" (run! eng id)))
            (is (= "open" (errand-state eng e)))
            (is (= (str "Not run: " sentence) (why-of eng id)))))))))

(deftest state-is-a-condition-of-every-kind
  (let [eng (fresh-engine)]
    (testing "any of the machine's states"
      (let [e (errand! eng)
            id (schedule! eng e (under {:state "open,done"}))]
        (is (= "done" (run! eng id)))))
    (testing "a state the row is not in skips, between the state and the door"
      (let [e (errand! eng)
            id (schedule! eng e (under {:state "done"}))]
        (is (= "skipped" (run! eng id)))
        (is (= "Not run: state is open, and the condition was state=done."
               (why-of eng id)))))
    (testing "a state the machine does not have is refused in the collection's words"
      (let [e (errand! eng)]
        (is (str/includes? (refused #(schedule! eng e (under {:state "closed"})))
                           "is not a state"))))))

(deftest entries-are-anded-and-the-first-that-fails-is-named
  (let [eng (fresh-engine)
        both {:priority_gte "2" :title_contains "dish"}]
    (testing "both hold"
      (let [e (errand! eng)
            id (schedule! eng e (under both))]
        (is (= "done" (run! eng id)))))
    (testing "one fails"
      (let [e (errand! eng)
            id (schedule! eng e (under both))]
        (revise! eng e {:title "Pans"})
        (is (= "skipped" (run! eng id)))
        (is (= "Not run: title is Pans, and the condition was title_contains=dish."
               (why-of eng id)))))))

;; ── the refusals at scheduling ────────────────────────────────────────

(deftest the-refusal-is-the-collections-own
  (let [eng (fresh-engine)
        e (errand! eng)]
    (testing "a parameter the kind does not have"
      (is (str/includes? (refused #(schedule! eng e (under {:colour "red"})))
                         "unknown query parameter")))
    (testing "an operator the field does not declare"
      (is (str/includes? (refused #(schedule! eng e (under {:title "Dishes"})))
                         "unknown query parameter"))
      (is (str/includes? (refused #(schedule! eng e (under {:note_contains "soak"})))
                         "unknown query parameter")))
    (testing "a value the field cannot hold"
      (is (some? (refused #(schedule! eng e (under {:priority "high"}))))))
    (testing "a condition with no value"
      (is (some? (refused #(schedule! eng e (under {:priority ""}))))))))

(deftest conditions-and-their-rule-are-named-together
  (let [eng (fresh-engine)
        e (errand! eng)]
    (testing "conditions under another rule"
      (is (str/includes? (refused #(schedule! eng e {:conditions {:priority "2"}}))
                         "validity: conditions"))
      (is (some? (refused #(schedule! eng e {:validity "strict"
                                             :conditions {:priority "2"}})))))
    (testing "the rule with no conditions"
      (is (str/includes? (refused #(schedule! eng e {:validity "conditions"}))
                         "at least one condition"))
      (is (some? (refused #(schedule! eng e (under {}))))))))

(deftest a-create-takes-no-conditions
  (let [eng (fresh-engine)
        e (errand! eng)
        create {:target {:kind "errand" :action "create"}
                :input {:title "Laundry" :priority 2 :due "2026-10-10"}}]
    (is (nil? (refused #(schedule! eng e create)))
        "the same create under `state` is stored")
    (is (str/includes? (refused #(schedule! eng e (merge create (under {:priority "2"}))))
                       "A create has no row to read"))))

;; ── the grant ─────────────────────────────────────────────────────────

(deftest a-field-outside-the-grant-is-refused-as-unknown
  (let [eng (fresh-engine)
        e (errand! eng)
        ;; the guard's-eye view of a leash that hides `priority`
        worn {:principal planner
              :grant {:id "grant-planner"
                      :action? (fn [_ _] true)
                      :row? (fn [_ _] true)
                      :plain? (fn [_ field] (not= "priority" (name field)))}}]
    (testing "the same sentence an unknown filter draws, at scheduling"
      (is (str/includes? (refused #(schedule! eng e (under {:priority "2"}) worn))
                         "Unknown parameter(s): priority")))
    (testing "a field the grant shows is a condition"
      (is (some? (schedule! eng e (under {:title_contains "dish"}) worn))))))

(deftest a-grant-that-no-longer-admits-the-field-skips
  (let [clock (atom noon)
        eng (fresh-engine clock)
        _ (member! eng "planner" "agent")
        gid (str (:id (:row (inv/create! eng :grant
                                         {:audience "planner"
                                          :scope [{:kind "errand" :actions ["finish"]}]
                                          :expires_at "2026-10-02T12:00:00Z"}
                                         {:principal person}))))
        _ (inv/invoke! eng :grant gid :accept {} {:principal planner})
        worn (fn [] {:principal planner
                     :grant (:grant (grants/visibility eng gid planner))})]
    (testing "a grant that still shows the field runs"
      (let [e (errand! eng)
            id (schedule! eng e (under {:priority "2"}) (worn))]
        (is (= gid (get-in (row-of eng :scheduled_action id) [:data :grant])))
        (is (= "done" (run! eng id)))))
    (testing "a grant that ended before the time skips, and shows no value"
      (let [e (errand! eng)
            id (schedule! eng e (under {:priority "2"}) (worn))]
        (reset! clock (Instant/parse "2026-10-02T14:30:00Z"))
        (is (= "skipped" (run! eng id)))
        (is (= "open" (errand-state eng e)))
        (is (= "Not run: the condition priority=2 names a field this grant no longer admits."
               (why-of eng id)))))))
