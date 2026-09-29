(ns waymark10.pursue-test
  "GRAIL 1/3: pursue! reaches a goal by following refusal remedies,
  proven over the ring handler against a trimmed copy of mealplan10's
  chain — grocery_list.create → plan-is-planned → plan.finalize →
  day-is-covered → plan_day.assign_meal → meal-is-listed → meal.accept
  — plus a latch whose remedy names its own door (the cycle).

  GRAIL 3/3 at the bottom: the same chain through the MCP tool
  waymark_pursue, on memory storage, and the three walls a pursuit
  stops at rather than walks through — a confirm door, a hold guard's
  door, and a door the caller's grant does not admit."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.client :as c]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the chain's guards: cross-kind reads, so the render probe (no
;; :read) declines and the POST answers the refusal with its remedies

(g/defguard plan-is-planned
  {:reads [:plan]
   :explain "Finalize the meal plan first — the grocery list follows from it."
   :remedies [:plan/finalize]}
  [row inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :plan (or (:plan_id inp) (get-in row [:data :plan_id])))]
      (if (= "planned" (some-> plan :state name)) (t/allow) (t/deny)))
    (t/allow)))

(g/defguard day-is-covered
  {:reads [:plan_day]
   :explain "Every day needs a meal before finalizing."
   :remedies [:plan_day/assign_meal]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (if (every? #(= "planned" (some-> (read :plan_day %) :state name))
                (keep #(get-in row [:data %]) [:day_id :day2_id]))
      (t/allow)
      (t/deny))
    (t/allow)))

(g/defguard meal-is-listed
  {:judges [:meal_id] :reads [:meal]
   :explain "That meal is not on the meal list yet."
   :open "Any meal on the list."
   :remedies [:meal/accept]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:meal_id inp))]
      (if (= "on_list" (some-> meal :state name)) (t/allow) (t/deny)))
    (t/allow)))

;; pure over the row, so the render probe judges it: the refusal rides
;; the envelope's unavailable entry, remedy and all
(def latch-free
  (g/expr {:name :latch-free
           :when '(= (data :free) true)
           :explain "The latch is stuck."
           :remedies [:latch/lift]}))

;; a remedy that lands and changes nothing: the step bound's loop
(def hatch-heard
  (g/expr {:name :hatch-heard
           :when '(= (data :heard) true)
           :explain "Nobody answers the knock."
           :remedies [:hatch/knock]}))

;; cross-kind read, so the POST refuses a shut gate with both remedies:
;; nudge lands it ajar (open is retried), shove lands it open (open's
;; own effect.to, so it stands in for open)
(g/defguard gate-stuck
  {:reads [:gate]
   :explain "The gate is stuck shut."
   :remedies [:gate/nudge :gate/shove]}
  [row _inp ctx]
  (if (and (:read ctx) (= "shut" (some-> row :state name)))
    (t/deny)
    (t/allow)))

;; pure over the row: a tight gate refuses nudge, with no remedy
(def gate-loose
  (g/expr {:name :gate-loose
           :when '(= (data :loose) true)
           :explain "The gate will not budge a little."}))

(r/defhandler assign-meal-handler [row inp _ctx]
  (assoc-in row [:data :meal_id] (:meal_id inp)))

(def plan-day
  (r/resource
   {:kind :plan_day
    :states [:undecided :planned]
    :initial :undecided
    :summary "{data.label} · {state}"
    :schema [:map
             [:label [:string {:max 40}]]
             [:meal_id {:optional true} [:maybe [:string {:max 80}]]]]
    :actions
    {:assign_meal {:from #{:undecided} :to :planned
                   :input [:map [:meal_id [:string {:min 1 :max 80}]]]
                   :guards [meal-is-listed]
                   :safety fx/routine
                   :handler assign-meal-handler}
     :clear_day {:from #{:planned} :to :undecided
                 :safety fx/routine}}}))

(def plan
  (r/resource
   {:kind :plan
    :states [:draft :planned]
    :initial :draft
    :summary "Plan · {state}"
    :schema [:map
             [:day_id [:string {:max 80}]]
             [:day2_id {:optional true} [:maybe [:string {:max 80}]]]]
    :actions
    {:finalize {:from #{:draft} :to :planned
                :guards [day-is-covered]
                :safety fx/routine}
     :reopen {:from #{:planned} :to :draft
              :safety fx/routine}}}))

(def grocery-list
  (r/resource
   {:kind :grocery_list
    :states [:draft :done]
    :initial :draft
    :summary "Groceries · {state}"
    :schema [:map [:plan_id [:string {:max 80}]]]
    :create-guards [plan-is-planned]
    :actions
    {:finish {:from #{:draft} :to :done :safety fx/routine}
     :reopen {:from #{:done} :to :draft :safety fx/routine}}}))

(def latch
  (r/resource
   {:kind :latch
    :states [:shut :open]
    :initial :shut
    :summary "Latch · {state}"
    :schema [:map [:free {:optional true} [:maybe :boolean]]]
    :actions
    {:lift {:from #{:shut} :to :open
            :guards [latch-free]
            :safety fx/routine}
     :lower {:from #{:open} :to :shut :safety fx/routine}}}))

(def hatch
  (r/resource
   {:kind :hatch
    :states [:shut :open]
    :initial :shut
    :summary "Hatch · {state}"
    :schema [:map [:heard {:optional true} [:maybe :boolean]]]
    :actions
    {:swing {:from #{:shut} :to :open
             :guards [hatch-heard]
             :safety fx/routine}
     :knock {:from #{:shut} :to :shut :safety fx/routine}
     :close {:from #{:open} :to :shut :safety fx/routine}}}))

;; spelled once: the gate's doors are not declared reversible
(def one-way
  {:idempotent true :reversible false :confirm false
   :one-way "A test gate: close covers regret."})

(def gate
  (r/resource
   {:kind :gate
    :states [:shut :ajar :open]
    :initial :shut
    :summary "Gate · {state}"
    :schema [:map [:loose {:optional true} [:maybe :boolean]]]
    :actions
    {:open {:from #{:shut :ajar} :to :open
            :guards [gate-stuck]
            :safety one-way}
     :nudge {:from #{:shut} :to :ajar
             :guards [gate-loose]
             :safety one-way}
     :shove {:from #{:shut} :to :open :safety one-way}
     :close {:from #{:ajar :open} :to :shut :safety one-way}}}))

(def resources [fx/meal plan-day plan grocery-list latch hatch gate])

(def ^:dynamic *session* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table (concat (map #(store/definition-checked-name (:plural %))
                                       resources)
                                  ["definitions" "waymark10_transitions"
                                   "waymark10_idempotency" "waymark10_drafts"])]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [h (engine/handler
                 (engine/engine {:storage st :resources resources}))]
          (binding [*session* (c/connect "http://test"
                                         {:principal "priya" :handler h})]
            (f)))
        (finally (pg/close! st))))))

(defn- coll [kind]
  (c/get-doc *session* (get-in (c/index *session*) [:resources kind :href])))

(defn- make! [kind input]
  (let [res (c/create! *session* (coll kind) input)]
    (is (c/doc? res) (pr-str res))
    res))

(defn- state-of [doc] (:state (c/get-doc *session* (:self doc))))

;; the envelope carries no id field: a row's id is its self's last segment
(defn- id-of [doc] (last (str/split (str (:self doc)) #"/")))

(defn- chain!
  "A suggested meal, an undecided day, a draft plan over that day."
  []
  ;; fresh inputs each call: the session's key-store replays an
  ;; identical create, and every test needs rows of its own
  (let [n (subs (str (random-uuid)) 0 8)
        meal (make! :meal {:name (str "Tacos " n) :themes ["test"]})
        day (make! :plan_day {:label (str "Monday " n)})]
    {:meal meal :day day :plan (make! :plan {:day_id (id-of day)})}))

(defn- resolver
  "The test's :resolve: which row each remedy acts on — and, for the
  day, which meal, when one was chosen."
  [{:keys [meal day plan]} meal-id]
  (fn [door _refused]
    (case door
      "plan.finalize" {:id (id-of plan)}
      "plan_day.assign_meal" (cond-> {:id (id-of day)}
                               meal-id (assoc :input {:meal_id meal-id}))
      "meal.accept" {:id (id-of meal)}
      nil)))

(defn- pursue-list! [{:keys [plan]} opts]
  (c/pursue! *session* (coll :grocery_list) :create {:plan_id (id-of plan)} opts))

(def chain-doors
  ["meal.accept" "plan_day.assign_meal" "plan.finalize" "grocery_list.create"])

(deftest pursue-drives-the-chain-end-to-end
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))})]
    (is (c/doc? (:done res)) (pr-str res))
    (is (= "grocery_list" (:kind (:done res))))
    (is (= chain-doors (mapv :door (:writes res)))
        "deepest remedy first, then each door it unblocked")
    (is (= "on_list" (state-of meal)))
    (is (= "planned" (state-of day)))
    (is (= "planned" (state-of plan)))))

(deftest a-missing-choice-comes-back-as-blocked-on
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows nil)})
        choice (first (:blocked-on res))]
    (is (nil? (:done res)))
    (is (= 1 (count (:blocked-on res))) (pr-str res))
    (is (= "plan_day.assign_meal" (:door choice)))
    (is (= [:meal_id] (:needs choice)))
    (is (= (:self day) (:row choice)))
    (is (= ["grocery_list.create" "plan.finalize"] (mapv :door (:stack res)))
        "the stack names the doors waiting on the choice")
    (testing "a blocked rehearsal writes nothing"
      (is (= "suggested" (state-of meal)))
      (is (= "undecided" (state-of day)))
      (is (= "draft" (state-of plan))))))

(deftest without-resolve-a-cross-kind-remedy-is-a-choice
  (let [{:keys [plan] :as rows} (chain!)
        res (pursue-list! rows {})
        choice (first (:blocked-on res))]
    (is (= ["plan.finalize"] (mapv :door (:blocked-on res))) (pr-str res))
    (is (nil? (:row choice)) "nobody said which plan")
    (is (= "draft" (state-of plan)))))

(deftest the-depth-bound-stops-a-branch
  (let [{:keys [meal plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))
                                :max-depth 2})
        choice (first (:blocked-on res))]
    (is (= "plan_day.assign_meal" (:door choice)) (pr-str res))
    (is (= :depth (:reason choice)))
    (is (= "suggested" (state-of meal)))
    (is (= "draft" (state-of plan)))))

(deftest the-cycle-check-stops-a-branch
  (let [lt (make! :latch {:free false})
        res (c/pursue! *session* lt :lift nil)
        choice (first (:blocked-on res))]
    (is (= "latch.lift" (:door choice)) (pr-str res))
    (is (= :cycle (:reason choice)))
    (is (= (:self lt) (:row choice)))
    (is (= "shut" (state-of lt)))))

(deftest the-rehearsal-writes-nothing
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))
                                :dry-run true})]
    (is (:rehearsal res) (pr-str res))
    (is (= chain-doors (mapv :door (:writes res)))
        "the rehearsal reports every write it would make")
    (is (empty? (:blocked-on res)))
    (is (:first-estimate res) "a rehearsal before any write is an estimate")
    (is (= "suggested" (state-of meal)))
    (is (= "undecided" (state-of day)))
    (is (= "draft" (state-of plan)))))

;; ── re-rehearsal: a door that needs one remedy on several rows

(defn- week!
  "A suggested meal, two undecided days, a draft plan over both."
  []
  (let [n (subs (str (random-uuid)) 0 8)
        meal (make! :meal {:name (str "Soup " n) :themes ["test"]})
        d1 (make! :plan_day {:label (str "Monday " n)})
        d2 (make! :plan_day {:label (str "Tuesday " n)})]
    {:meal meal :days [d1 d2]
     :plan (make! :plan {:day_id (id-of d1) :day2_id (id-of d2)})}))

(defn- week-resolver
  "assign_meal acts on the first day still undecided, with the meal
  `meals` (day self → meal id) chose for it, if any."
  [{:keys [meal days]} meals]
  (fn [door _refused]
    (case door
      "plan_day.assign_meal"
      (when-some [d (first (filter #(= "undecided" (state-of %)) days))]
        (cond-> {:id (id-of d)}
          (get meals (:self d)) (assoc :input {:meal_id (get meals (:self d))})))
      "meal.accept" {:id (id-of meal)}
      nil)))

(deftest a-remedy-needed-twice-lands-in-one-call
  (let [{:keys [meal days plan] :as rows} (week!)
        m (id-of meal)
        res (c/pursue! *session* plan :finalize nil
                       {:resolve (week-resolver rows (zipmap (map :self days) [m m]))})]
    (is (c/doc? (:done res)) (pr-str res))
    (is (= ["meal.accept" "plan_day.assign_meal" "plan_day.assign_meal" "plan.finalize"]
           (mapv :door (:writes res))))
    (is (= (mapv :self days)
           (mapv :row (filter #(= "plan_day.assign_meal" (:door %)) (:writes res))))
        "one assign_meal per day, each found by a re-rehearsal")
    (is (= ["planned" "planned"] (mapv state-of days)))
    (is (= "planned" (state-of plan)))))

(deftest a-choice-found-mid-run-comes-back-with-the-steps-taken
  (let [{:keys [meal days plan] :as rows} (week!)
        [d1 d2] days
        res (c/pursue! *session* plan :finalize nil
                       {:resolve (week-resolver rows {(:self d1) (id-of meal)})})
        choice (first (:blocked-on res))]
    (is (nil? (:done res)) (pr-str res))
    (is (not (:rehearsal res)) "the first rehearsal reached the goal, so the run began")
    (is (= ["meal.accept" "plan_day.assign_meal"] (mapv :door (:writes res))))
    (is (= "plan_day.assign_meal" (:door choice)))
    (is (= [:meal_id] (:needs choice)))
    (is (= (:self d2) (:row choice)))
    (is (= ["plan.finalize"] (mapv :door (:stack res))))
    (is (= ["planned" "undecided"] (mapv state-of [d1 d2])))
    (is (= "draft" (state-of plan)))))

(deftest the-step-bound-stops-a-loop-that-lands-without-progress
  (let [hx (make! :hatch {:heard false})
        res (c/pursue! *session* hx :swing nil {:max-depth 2})]
    (is (= {:step-bound 8} (:stopped res)) (pr-str res))
    (is (seq (:writes res)))
    (is (every? #{"hatch.knock"} (map :door (:writes res))))
    (is (= "shut" (state-of hx)))))

;; ── an alternative that reaches the refused door's effect.to

(deftest an-alternative-landing-the-doors-state-stands-in-for-it
  (let [gx (make! :gate {:loose false})
        plan (c/pursue! *session* gx :open nil {:dry-run true})
        res (c/pursue! *session* gx :open nil {})]
    (is (= ["gate.shove"] (mapv :door (:writes plan))) (pr-str plan))
    (is (c/doc? (:done res)) (pr-str res))
    (is (= ["gate.shove"] (mapv :door (:writes res)))
        "shove landed the gate open: open is not retried")
    (is (= "open" (state-of gx)))))

(deftest an-alternative-landing-elsewhere-still-retries-the-door
  (let [gx (make! :gate {:loose true})
        plan (c/pursue! *session* gx :open nil {:dry-run true})
        res (c/pursue! *session* gx :open nil {})]
    (is (= ["gate.nudge" "gate.open"] (mapv :door (:writes plan))) (pr-str plan))
    (is (c/doc? (:done res)) (pr-str res))
    (is (= ["gate.nudge" "gate.open"] (mapv :door (:writes res)))
        "nudge landed the gate ajar, not open: open is retried")
    (is (= "open" (state-of gx)))))

;; ── GRAIL 3/3: waymark_pursue, the MCP tool ─────────────────────────────

;; cross-kind read, so the render probe declines and the POST answers
;; the refusal with its remedy: the seal's confirm door
(g/defguard seal-is-broken
  {:reads [:pt_seal]
   :explain "Break the seal first."
   :remedies [:pt_seal/break]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [seal (read :pt_seal (get-in row [:data :seal_id]))]
      (if (= "broken" (some-> seal :state name)) (t/allow) (t/deny)))
    (t/allow)))

;; a hold: the POST mints a held call instead of refusing
(g/defguard grail_hold
  {:hold true
   :reads [:pt_turnstile]
   :explain "A person taps before the turnstile turns."}
  [_row _inp ctx]
  (if (:read ctx) (t/deny) (t/allow)))

(def seal
  (r/resource
   {:kind :pt_seal
    :states [:intact :broken]
    :initial :intact
    :terminal #{:broken}
    :summary "Seal · {state}"
    :schema [:map [:label {:optional true} [:maybe [:string {:max 40}]]]]
    :actions
    {:break {:from #{:intact} :to :broken
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The seal cannot be made whole again."}}}}))

(def vault
  (r/resource
   {:kind :pt_vault
    :states [:shut :open]
    :initial :shut
    :summary "Vault · {state}"
    :schema [:map [:seal_id [:string {:max 80}]]]
    :actions
    {:open {:from #{:shut} :to :open
            :guards [seal-is-broken]
            :safety fx/routine}
     :close {:from #{:open} :to :shut :safety fx/routine}}}))

(def turnstile
  (r/resource
   {:kind :pt_turnstile
    :states [:locked :turned]
    :initial :locked
    :summary "Turnstile · {state}"
    :schema [:map [:label {:optional true} [:maybe [:string {:max 40}]]]]
    :actions
    {:turn {:from #{:locked} :to :turned
            :guards [grail_hold]
            :safety fx/routine}
     :reset {:from #{:turned} :to :locked :safety fx/routine}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- mcp-boot []
  (engine/engine {:storage (memory/storage)
                  :resources (into resources [seal vault turnstile])}))

(defn- mcp-make! [eng kind data]
  (str (:id (:row (inv/create! eng kind data {:principal elena})))))

(defn- mcp-call [eng session tool-name args]
  (mcp/call-tool eng (mcp/door eng) session tool-name args))

(defn- answer [out] (wire/read-json (get-in out [:content 0 :text])))

(defn- mcp-pursue
  ([eng args] (mcp-pursue eng {:principal elena} args))
  ([eng session args] (mcp-call eng session "waymark_pursue" args)))

(defn- mcp-state [eng kind id]
  (:state (answer (mcp-call eng {:principal elena} "waymark_get"
                            {:kind kind :id id :return "summary"}))))

(defn- mcp-chain!
  "The chain's rows on a memory engine, and the choices that name the
  row each remedy acts on."
  [eng]
  (let [meal (mcp-make! eng :meal {:name "Tacos" :themes ["test"]})
        day (mcp-make! eng :plan_day {:label "Monday"})
        plan (mcp-make! eng :plan {:day_id day})]
    {:meal meal :day day :plan plan
     :choices {"plan.finalize" {:id plan}
               "plan_day.assign_meal" {:id day :input {:meal_id meal}}
               "meal.accept" {:id meal}}}))

(defn- mcp-untouched? [eng {:keys [meal day plan]}]
  (and (= "suggested" (mcp-state eng "meal" meal))
       (= "undecided" (mcp-state eng "plan_day" day))
       (= "draft" (mcp-state eng "plan" plan))))

(deftest waymark-pursue-dry-run-answers-the-plan-and-writes-nothing
  (let [eng (mcp-boot)
        {:keys [plan choices] :as rows} (mcp-chain! eng)
        out (mcp-pursue eng {:kind "grocery_list" :action "create"
                             :input {:plan_id plan} :choices choices})
        a (answer out)]
    (is (not (:isError out)) (pr-str a))
    (is (= chain-doors (mapv :door (:plan a)))
        "deepest remedy first, then each door it unblocks")
    (is (= [] (:steps_taken a)))
    (is (nil? (:done a)))
    (is (mcp-untouched? eng rows) "dry_run is the default, and it writes nothing")))

(deftest waymark-pursue-real-run-lands-the-chain
  (let [eng (mcp-boot)
        {:keys [meal day plan choices]} (mcp-chain! eng)
        out (mcp-pursue eng {:kind "grocery_list" :action "create"
                             :input {:plan_id plan} :choices choices
                             :dry_run false})
        a (answer out)]
    (is (not (:isError out)) (pr-str a))
    (is (= "grocery_list" (:kind (:done a))))
    (is (= chain-doors (mapv :door (:steps_taken a))))
    (is (= "on_list" (mcp-state eng "meal" meal)))
    (is (= "planned" (mcp-state eng "plan_day" day)))
    (is (= "planned" (mcp-state eng "plan" plan)))))

(deftest waymark-pursue-stops-at-a-confirm-step-with-its-sentence
  (let [eng (mcp-boot)
        seal-id (mcp-make! eng :pt_seal {})
        vault-id (mcp-make! eng :pt_vault {:seal_id seal-id})
        out (mcp-pursue eng {:kind "pt_vault" :id vault-id :action "open"
                             :choices {"pt_seal.break" {:id seal-id}}
                             :dry_run false})
        a (answer out)
        choice (first (:blocked_on a))
        entry (get-in (answer (mcp-call eng {:principal elena} "waymark_get"
                                        {:kind "pt_seal" :id seal-id}))
                      [:actions :break])]
    (is (not (:isError out)) (pr-str a))
    (is (= "pt_seal.break" (:door choice)))
    (is (true? (:confirm choice)))
    (is (= (or (get-in entry [:display :description])
               (get-in entry [:display :label]))
           (:consequence choice))
        "the sentence to acknowledge, read off the row as waymark_invoke reads it")
    (is (= ["pt_vault.open"] (mapv :door (:stack a))))
    (is (= [] (:steps_taken a)))
    (testing "never acknowledged on anyone's behalf"
      (is (= "intact" (mcp-state eng "pt_seal" seal-id)))
      (is (= "shut" (mcp-state eng "pt_vault" vault-id))))))

(deftest waymark-pursue-stops-at-a-held-step-as-held
  (let [eng (mcp-boot)
        ts (mcp-make! eng :pt_turnstile {})
        agent (assoc (t/principal {:id "agent-9" :type :agent}) :acts-for "elena")
        out (mcp-pursue eng {:principal agent}
                        {:kind "pt_turnstile" :id ts :action "turn" :dry_run false})
        a (answer out)
        choice (first (:blocked_on a))]
    (is (not (:isError out)) (pr-str a))
    (is (= "pt_turnstile.turn" (:door choice)))
    (is (true? (:held choice)))
    (is (string? (:held_call choice)) "the held call a person answers")
    (is (= [] (:steps_taken a)) "a held call is not a step taken")
    (is (= "locked" (mcp-state eng "pt_turnstile" ts)))))

(deftest waymark-pursue-blocks-a-step-the-grant-does-not-admit
  (let [eng (mcp-boot)
        h (engine/handler eng)
        {:keys [plan choices] :as rows} (mcp-chain! eng)
        agent {"x-waymark-principal" "agent-7" "x-waymark-actor-type" "agent"}]
    (inv/create! eng :grant
                 {:audience "agent-7"
                  ;; everything the chain needs except meal.accept
                  :scope [{:kind "meal" :actions []}
                          {:kind "plan_day" :actions ["assign_meal"]}
                          {:kind "plan" :actions ["finalize"]}
                          {:kind "grocery_list" :actions ["create"]}]}
                 {:principal grants/approvals-actor
                  :id "grant-pursue-1"
                  :mint? true})
    (let [accepted (h {:request-method :post
                       :uri "/api/grants/grant-pursue-1/-/accept"
                       :headers (assoc agent "content-type" "application/json")
                       :body "{}"})]
      (is (= 200 (:status accepted)) (:body accepted)))
    (let [resp (h {:request-method :post :uri "/api/-/mcp"
                   :headers (assoc agent "x-waymark-grant" "grant-pursue-1")
                   :body (wire/write-json
                          {:jsonrpc "2.0" :id 1 :method "tools/call"
                           :params {:name "waymark_pursue"
                                    :arguments {:kind "grocery_list" :action "create"
                                                :input {:plan_id plan}
                                                :choices choices
                                                :dry_run false}}})})
          r (:result (wire/read-json (:body resp)))
          a (wire/read-json (get-in r [:content 0 :text]))]
      (is (= 200 (:status resp)) (:body resp))
      (is (not (:isError r)) (pr-str a))
      (is (seq (:blocked_on a)) "the pursuit comes back blocked")
      (is (nil? (:done a)))
      (is (= [] (:steps_taken a)))
      (is (mcp-untouched? eng rows) "and nothing on the chain was written"))))
