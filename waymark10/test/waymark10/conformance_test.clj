(ns waymark10.conformance-test
  "Phase-4a conformance: the factory-level obligations over the
  fixture kinds — every framework promise the walker can reach
  without the HTTP envelope (that layer is phase 4b). The suite is
  generic: it knows Waymark, not meal plans; the fixtures enroll and
  the walk does the rest. Needs the waymark10_test database;
  WAYMARK10_TEST_DSN overrides the default local DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.types :as t]))

;; ── suite-local kinds ───────────────────────────────────────────────
;; chore: the fixtures declare only idempotent actions, so the
;; non-idempotent half of safety-truth needs one non-idempotent door.
;; locked: an always-denying guard, so the honest-skip and
;; unavailable-truth shapes have a row that can never move.

(r/defhandler chore-tick [row _inp _ctx]
  (update-in row [:data :ticks] (fnil inc 0)))

(def chore
  (r/resource
   {:kind :chore
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:ticks {:optional true} [:maybe :int]]]
    :actions
    {:tick {:from #{:open} :to :open
            :safety {:idempotent false :reversible true :confirm false}
            :handler chore-tick}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed chore is history."}}}}))

(def never-opens
  (g/guard {:name :never-opens
            :explain "This door never opens."
            :check (fn [_ _ _] (t/deny))}))

(def locked
  (r/resource
   {:kind :locked
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 40}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :guards [never-opens]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A conformance fixture's door."}}}}))

;; vetted: a create-guarded kind whose :on-create counts its calls, so
;; the create dry-run's tiers (design §23) are observable — the guard
;; tier judges, the hook must NOT fire, and nothing is minted.

(def on-create-calls (atom 0))

(def refuse-evil
  (g/guard {:name :refuse-evil
            :judges [:title]
            :check (fn [_row inp _ctx]
                     (if (= "evil" (:title inp)) (t/deny) (t/allow)))
            :explain "Evil titles refuse at the door."}))

(def sponsor-known
  (g/guard {:name :sponsor-known
            :severity :warning
            :judges [:sponsor]
            :check (fn [_row inp _ctx]
                     (if (= "evil corp" (:sponsor inp)) (t/deny) (t/allow)))
            :explain "That sponsor has a history."}))

(def vetted
  (r/resource
   {:kind :vetted
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:sponsor {:optional true} [:maybe [:string {:max 60}]]]
             [:vetted_at {:optional true} [:maybe [:string {:max 40}]]]]
    :create-guards [refuse-evil sponsor-known]
    :on-create (fn [row _ctx]
                 (swap! on-create-calls inc)
                 (assoc-in row [:data :vetted_at] "birth"))
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Vetting ends once."}}}}))

;; ── enrollment: what the fixtures must register ─────────────────────
;; plan: generation alone can't promise a walkable week — begin needs
;; a started start_date and finalize needs every day covered — so the
;; create example supplies a past, covered week. assign_meal needs NO
;; example: date-in-plan's acceptance set feeds :date and :meal_id is
;; an unconstrained ref string.

(fac/example-input! :plan :create
  {:start_date "2025-01-06" :weeks 1
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (db/with-test-engine [fx/meal fx/plan chore locked vetted]
      (fn [eng] (binding [*eng* eng] (f))))))

(def fixture-kinds [:meal :plan])

(defn- rdef [kind] (get-in *eng* [:resources kind]))

(defn- action-of [kind aname]
  (assoc (get-in (rdef kind) [:actions aname]) :name aname))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (or (fac/problem-data e) (throw e)))))

(defn- reload [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/load-row (:storage *eng*) tx kind id {}))))

(defn- transition-count [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (count (store/transitions (:storage *eng*) tx
                                       {:kind kind :resource-id id} {})))))

;; ── 1. every state reachable without a hand-written factory ─────────

(deftest every-state-reachable
  (doseq [kind fixture-kinds
          state (:states (rdef kind))]
    (testing (str (name kind) " → " (name state))
      (let [out (fac/walk-to-state *eng* kind state {:seed 7})]
        (is (not (:skip out))
            (str "fixture kinds must be fully walkable; skipped: "
                 (get-in out [:skip :reason])))
        (when-not (:skip out)
          (is (= state (:state out)))))))
  (testing "a blocked path is an honest skip naming the action and the fix"
    (let [out (fac/walk-to-state *eng* :locked :done {:seed 7})]
      (is (= :done (get-in out [:skip :state])))
      (is (re-find #"finish" (get-in out [:skip :reason])))
      (is (re-find #"example-input!" (get-in out [:skip :reason]))))))

;; ── 2. transition truth: state, version, log, actor ─────────────────

(deftest transition-truth
  (doseq [kind fixture-kinds
          state (:states (rdef kind))]
    (let [base (fac/walk-to-state *eng* kind state {:seed 13})]
      (when-not (:skip base)
        (doseq [action (fac/available-actions (rdef kind) base (fac/probe-ctx *eng*))]
          (testing (str (name kind) "." (name state) " → " (name (:name action)))
            (let [row (fac/walk-to-state *eng* kind state {:seed 17})
                  ctx (fac/probe-ctx *eng*)
                  body (fac/synthesize-input *eng* (rdef kind) action row ctx {:seed 19})
                  res (fac/walker-invoke! *eng* kind row action body)
                  row' (:row res)
                  rec (:transition res)]
              (is (nil? (:replayed? res)))
              (is (= (:to action) (:state row')))
              (is (= (inc (:version row)) (:version row')))
              (is (= (:name action) (:action rec)))
              (is (= (:state row) (:from-state rec)))
              (is (= (:to action) (:to-state rec)))
              (is (= "walker" (get-in rec [:actor :id]))
                  "the log names the acting principal"))))))))

;; ── 3. unavailable truth: advertisement = enforcement ───────────────

(defn- assert-advertised-refusals
  "Every action probe hard-denies (and does not hide) on this row:
  invoking anyway is a 409 whose :detail EQUALS the probed reason."
  [kind row]
  (let [ctx (fac/probe-ctx *eng*)
        denied (for [action (machine/transitions-from (rdef kind) (:state row))
                     :let [denial (fac/probe-denial action row ctx)]
                     :when (and denial (not (:hide denial)))]
                 [action denial])]
    (is (seq denied) "the staging produced at least one advertised refusal")
    (doseq [[action denial] denied]
      (testing (str (name kind) "/" (name (:name action)))
        (let [body (fac/synthesize-input *eng* (rdef kind) action row ctx {:seed 23})
              p (problem-of #(fac/walker-invoke! *eng* kind row action body))]
          (is (= 409 (:status p)))
          (is (= :guard-refused (:waymark10/problem p)))
          (is (= (:reason denial) (:detail p))
              "advertisement and enforcement produce the same sentence"))))))

(deftest unavailable-truth
  (testing "an uncovered draft plan says why finalize refuses"
    (let [{:keys [row]} (fac/create-example
                         *eng* :plan
                         {:seed 29 :overrides {:days [{:date "2025-01-06"}]}})]
      (assert-advertised-refusals :plan row)))
  (testing "a future planned week says why begin refuses"
    (let [{:keys [row]} (fac/create-example
                         *eng* :plan
                         {:seed 31 :overrides {:start_date "2099-01-04"
                                               :days [{:date "2099-01-04"
                                                       :eating_out true}]}})
          res (fac/walker-invoke! *eng* :plan row (action-of :plan :finalize) nil)]
      (assert-advertised-refusals :plan (:row res))))
  (testing "the locked door"
    (let [{:keys [row]} (fac/create-example *eng* :locked {:seed 37})]
      (assert-advertised-refusals :locked row))))

;; ── 4. safety truth: replay discipline ──────────────────────────────

(deftest safety-truth
  (testing "idempotent, no input: the double-invoke is a natural replay"
    (let [{:keys [row]} (fac/create-example *eng* :meal {:seed 41})
          accept (action-of :meal :accept)
          one (fac/walker-invoke! *eng* :meal row accept nil)
          two (fac/walker-invoke! *eng* :meal (:row one) accept nil)]
      (is (nil? (:replayed? one)))
      (is (= :natural (:replayed? two)))
      (is (= (get-in one [:row :version]) (get-in two [:row :version]))
          "version advanced at most once past the first")))
  (testing "idempotent with input: an identical body replays"
    (let [row (fac/walk-to-state *eng* :plan :draft {:seed 43})
          assign (action-of :plan :assign_meal)
          ;; the ref wall (waymark-fp62.4.1) resolves meal_id at the
          ;; door, so the replayed body names a meal that stands
          meal (:row (fac/create-example *eng* :meal {:seed 44}))
          body {:date "2025-01-06" :meal_id (:id meal)}
          one (fac/walker-invoke! *eng* :plan row assign body)
          two (fac/walker-invoke! *eng* :plan (:row one) assign body)]
      (is (nil? (:replayed? one)))
      (is (= :natural (:replayed? two)))
      (is (= (get-in one [:row :version]) (get-in two [:row :version])))))
  (testing "non-idempotent without a key is 428"
    (let [{:keys [row]} (fac/create-example *eng* :chore {:seed 47})
          p (problem-of #(inv/invoke! *eng* :chore (:id row) :tick nil
                                      {:principal (fac/walker-principal)}))]
      (is (= 428 (:status p)))
      (is (= :idempotency-key-required (:waymark10/problem p)))))
  (testing "same key + same body replays; the key on another action refuses"
    (let [{:keys [row]} (fac/create-example *eng* :chore {:seed 53})
          opts {:principal (fac/walker-principal)
                :idempotency-key "conformance-key-1"}
          one (inv/invoke! *eng* :chore (:id row) :tick nil opts)
          two (inv/invoke! *eng* :chore (:id row) :tick nil opts)
          p (problem-of #(inv/invoke! *eng* :chore (:id row) :close nil opts))]
      (is (map? (:row one)))
      (is (= :idempotency (:replayed? two)))
      (is (= (:version (:row one)) (:version (reload :chore (:id row))))
          "the replay executed nothing")
      (is (= 409 (:status p)))
      (is (= :idempotency-key-reuse (:waymark10/problem p))))))

;; ── 5. the input contract ───────────────────────────────────────────

(deftest input-contract
  (let [row (fac/walk-to-state *eng* :plan :draft {:seed 59})
        pid (:id row)
        opts {:principal (fac/walker-principal)}]
    (testing "a schema-invalid body is 422 with field-keyed errors"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "not-a-date" :meal_id "m"} opts))]
        (is (= 422 (:status p)))
        (is (contains? (:errors p) :date))))
    (testing "an unknown field refuses, never silently drops"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "2025-01-06" :meal_id "m" :evil 1}
                                        opts))]
        (is (= 422 (:status p)))
        (is (= ["disallowed key"] (get-in p [:errors :evil])))))
    (testing "dry-run never changes the version or appends transitions"
      (let [v-before (:version (reload :plan pid))
            t-before (transition-count :plan pid)
            res (inv/invoke! *eng* :plan pid :finalize nil
                             (assoc opts :dry-run true))]
        (is (true? (:valid? res)))
        (is (= v-before (:version (reload :plan pid))))
        (is (= t-before (transition-count :plan pid)))))
    (testing "dry-run needs no idempotency key and leaves no trace"
      (let [{crow :row} (fac/create-example *eng* :chore {:seed 61})
            t-before (transition-count :chore (:id crow))
            res (inv/invoke! *eng* :chore (:id crow) :tick nil
                             (assoc opts :dry-run true))]
        (is (true? (:valid? res)))
        (is (= t-before (transition-count :chore (:id crow))))))
    (testing "dry-run neither consumes nor records a key it was handed"
      (let [{crow :row} (fac/create-example *eng* :chore {:seed 62})
            keyed (assoc opts :idempotency-key "dry-then-real-1")
            dry (inv/invoke! *eng* :chore (:id crow) :tick nil
                             (assoc keyed :dry-run true))
            first-real (inv/invoke! *eng* :chore (:id crow) :tick nil keyed)
            replay (inv/invoke! *eng* :chore (:id crow) :tick nil keyed)]
        (is (true? (:valid? dry)))
        (is (nil? (:replayed? first-real))
            "the rehearsal recorded nothing — the first real invoke executes")
        (is (= :idempotency (:replayed? replay))
            "the real execution's key then replays as ever")))))

;; ── 5b. the dry-run rehearsals (design §23) ─────────────────────────
;; The create tiers (waymark9 _create_entry's), and the partial mode's
;; obligations: silence on unprovided fields, provided fields judged
;; exactly as ever, guard leaves judged the moment their fields arrive.

(defn- row-count [kind]
  (store/with-tx (:storage *eng*)
    (fn [tx] (count (store/query-rows (:storage *eng*) tx kind {}
                                      {:limit 1000})))))

(deftest create-dry-run-tiers
  (let [opts {:principal (fac/walker-principal) :dry-run true}]
    (testing "tier one — no create guards: schema validation IS the answer"
      (let [n (row-count :chore)
            res (inv/create! *eng* :chore {:title "Rehearsed"} opts)]
        (is (= {:valid? true} res))
        (is (= n (row-count :chore)) "nothing minted"))
      (let [p (problem-of #(inv/create! *eng* :chore {:title ""} opts))]
        (is (= 422 (:status p)) "the schema tier still refuses honestly")))
    (testing "tier two — declared create guards judged as the real path,
              warnings riding the body, on-create never firing"
      (let [fired @on-create-calls
            n (row-count :vetted)
            ok (inv/create! *eng* :vetted {:title "Fine" :sponsor "acme"} opts)
            warned (inv/create! *eng* :vetted
                                {:title "Fine" :sponsor "evil corp"} opts)
            p (problem-of #(inv/create! *eng* :vetted {:title "evil"} opts))]
        (is (true? (:valid? ok)))
        (is (nil? (:warnings ok)))
        (is (true? (:valid? warned)))
        (is (= [:sponsor-known] (mapv :name (:warnings warned)))
            "the pending warning rides the body as data")
        (is (= 409 (:status p)))
        (is (= :guard-refused (:waymark10/problem p)))
        (is (= n (row-count :vetted)) "nothing minted")
        (is (= fired @on-create-calls) "on-create never fired")))
    (testing "an acknowledged warning passes the rehearsal too"
      (let [res (inv/create! *eng* :vetted {:title "Fine" :sponsor "evil corp"}
                             (assoc opts :acknowledged #{:sponsor-known}))]
        (is (true? (:valid? res)))
        (is (nil? (:warnings res)))))
    (testing "a create dry-run neither demands nor records a key"
      (let [res (inv/create! *eng* :chore {:title "Keyed rehearsal"}
                             (assoc opts :idempotency-key "create-dry-1"))
            real (inv/create! *eng* :chore {:title "Keyed rehearsal"}
                              {:principal (fac/walker-principal)
                               :idempotency-key "create-dry-1"})]
        (is (true? (:valid? res)))
        (is (nil? (:replayed? real)) "the real create executed fresh")
        (is (map? (:row real)))))))

(deftest partial-dry-run-judges-when-answerable
  (let [row (fac/walk-to-state *eng* :plan :draft {:seed 89})
        pid (:id row)
        opts {:principal (fac/walker-principal) :dry-run :partial}]
    (testing "silence on unprovided fields is the obligation"
      (let [v-before (:version (reload :plan pid))
            t-before (transition-count :plan pid)
            meal (:row (fac/create-example *eng* :meal {:seed 109}))
            res (inv/invoke! *eng* :plan pid :assign_meal
                             {:meal_id (:id meal)} opts)]
        (is (true? (:valid? res)))
        (is (= [:names-a-row-that-stands] (:judged res))
            "the ref wall reads beyond the clock, so the rehearsal judges it")
        (is (= [:date-in-plan] (:awaiting res))
            "the date leaf waits — named, never failed")
        (is (= v-before (:version (reload :plan pid))) "nothing moved")
        (is (= t-before (transition-count :plan pid)))))
    (testing "a provided field's errors refuse exactly as ever, keyed
              only by what the caller provided"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "not-a-date"} opts))]
        (is (= 422 (:status p)))
        (is (= [:date] (vec (keys (:errors p)))))))
    (testing "a fully covered guard leaf is judged now"
      (let [p (problem-of #(inv/invoke! *eng* :plan pid :assign_meal
                                        {:date "2099-12-25"} opts))]
        (is (= 409 (:status p)))
        (is (= :guard-refused (:waymark10/problem p)))
        (is (= :date-in-plan (:guard p))))
      (let [res (inv/invoke! *eng* :plan pid :assign_meal
                             {:date "2025-01-06"} opts)]
        (is (true? (:valid? res)))
        (is (= [:names-a-row-that-stands :date-in-plan] (:judged res)))
        (is (= [] (:awaiting res)))))
    (testing "the partial create rehearsal shares the discipline"
      (let [res (inv/create! *eng* :vetted {:sponsor "acme"}
                             (assoc opts :dry-run :partial))]
        (is (true? (:valid? res)))
        (is (= [:sponsor-known] (:judged res)))
        (is (= [:refuse-evil] (:awaiting res))
            "the title leaf waits for its field"))
      (let [p (problem-of #(inv/create! *eng* :vetted {:title "evil"}
                                        (assoc opts :dry-run :partial)))]
        (is (= 409 (:status p))
            "a covered create leaf refuses the moment it can")))))

;; ── 6. the schema-guard gap (the fuzz) ──────────────────────────────

(deftest schema-guard-gap
  (doseq [kind fixture-kinds
          action (machine/actions-seq (rdef kind))
          leaf (mapcat g/iter-leaves (:guards action))
          :when (and (:accepts leaf)
                     (not (:relation leaf))
                     (= 1 (count (:judges leaf))))]
    (let [field (first (:judges leaf))
          state (first (sort (:from action)))
          row (fac/walk-to-state *eng* kind state {:seed 67})
          ctx (fac/probe-ctx *eng*)
          admitted (g/admitted leaf row ctx)
          admits? (fn [v] (let [s (str v)]
                            (boolean (some #(= s (str %)) admitted))))
          base (fac/synthesize-input *eng* (rdef kind) action row ctx {:seed 71})
          field-form (schema/field-schema (:input action) field)
          encode #(schema/encode field-form %)
          outside (->> (fac/sample field-form {:seed 73 :size 20})
                       (remove admits?)
                       distinct)]
      (testing (str (name kind) "/" (name (:name action)) " judges " field)
        (is (seq admitted) "the staging left the acceptance set inhabited")
        (is (seq outside)
            "generation found schema-valid values outside the admitted set")
        (doseq [v (take 4 outside)]
          (let [p (problem-of #(fac/walker-invoke! *eng* kind row action
                                                   (assoc base field (encode v))))]
            (is (= 409 (:status p)))
            (is (= :guard-refused (:waymark10/problem p)))
            (is (= (:name leaf) (:guard p))
                "the schema-valid stranger is refused by the acceptance guard")))
        (doseq [v admitted]
          (let [p (problem-of #(fac/walker-invoke! *eng* kind row action
                                                   (assoc base field (encode v))))]
            (is (or (nil? p) (not= (:name leaf) (:guard p)))
                "an admitted member is never refused by its own guard")))))))

;; ── wave one, remedies ──────────────────────────────────────────────
;;
;; R-4 of waymark-fp62.2.1: every refusal that leaves the engine
;; carries a way out — :remedies, the :kind/action tokens of the doors
;; that change the verdict, or :open, the sentence that admits no door
;; does. The walk is over every guard on every fixture kind.
;;
;; The predicates are resolved rather than required: this namespace's
;; ns form is shared with another wave-one section, and this section
;; appends only. Hoist them into the :require when the wave lands.

(def ^:private dead-end?
  @(requiring-resolve 'waymark10.checks/dead-end?))

(def ^:private guard-sites
  @(requiring-resolve 'waymark10.checks/guard-sites))

(def ^:private suite-dead-ends
  "The fixture guards that refuse with no way out, on purpose.

  Each one exists to prove a DIFFERENT obligation — that an
  advertisement equals its enforcement, that a create guard judges in
  a rehearsal, that a blocked walk is an honest skip — and none of
  them stands for law a household wrote. They are named here rather
  than waived in the framework's list because the framework's list is
  a debt somebody owes and these are not debts. The set is pinned:
  a new fixture guard that refuses with nothing to do next shows up
  as a failure here, and is either given a way out or added on
  purpose."
  #{[:plan :assign_meal :date-in-plan]
    [:plan :begin :plan-started]
    [:locked :finish :never-opens]
    [:vetted :create :refuse-evil]})

(defn- all-guard-sites []
  (mapcat guard-sites (vals (:resources *eng*))))

(defn- site-key [{:keys [kind door guard]}] [kind door (:name guard)])

(deftest every-refusing-guard-names-a-way-out
  (let [dead (into #{} (comp (filter (comp dead-end? :guard)) (map site-key))
                   (all-guard-sites))]
    (is (= suite-dead-ends dead)
        (str "a guard that refuses in words and names neither :remedies "
             "nor :open leaves the caller nothing to do next"))))

(defn- denier-of
  "The declared guard behind a refusal: the site whose guard bears the
  name the problem body reports."
  [kind door gname]
  (:guard (first (filter #(= [kind door gname] (site-key %))
                         (all-guard-sites)))))

(defn- assert-way-out
  "The refusal body says exactly what the guard declared. This is R-4
  read as an equality rather than a presence: a body that DROPPED a
  declared remedy is the failure the obligation exists to catch, and
  a guard that declares nothing is one the set above already names."
  [p kind door]
  (is (= :guard-refused (:waymark10/problem p)))
  (let [d (denier-of kind door (:guard p))]
    (is (some? d) (str "the body names a guard this kind declares: " (:guard p)))
    (is (= (not-empty (vec (:remedies d))) (not-empty (vec (:remedies p))))
        "every remedy the guard declares reaches the body")
    (is (= (:open d) (:open p))
        "and so does the sentence that says no door clears it")
    (is (or (not (dead-end? d))
            (contains? suite-dead-ends [kind door (:guard p)]))
        "a refusal with no way out is one the suite declared on purpose")))

(deftest a-refusal-body-carries-the-way-out
  (testing "a fact gate hands the caller the door that supplies the fact"
    (let [{:keys [row]} (fac/create-example
                         *eng* :plan
                         {:seed 101 :overrides {:days [{:date "2025-01-06"}]}})
          p (problem-of #(fac/walker-invoke! *eng* :plan row
                                             (action-of :plan :finalize) nil))]
      (is (= 409 (:status p)))
      (is (= [:plan/assign_meal] (vec (:remedies p)))
          "the require gate's declared remedy rides the 409")
      (assert-way-out p :plan :finalize)))
  (testing "a create guard's refusal answers the same way"
    (let [p (problem-of #(inv/create! *eng* :vetted {:title "evil"}
                                      {:principal (fac/walker-principal)}))]
      (is (= 409 (:status p)))
      (assert-way-out p :vetted :create)))
  (testing "an always-shut door"
    (let [{:keys [row]} (fac/create-example *eng* :locked {:seed 103})
          p (problem-of #(fac/walker-invoke! *eng* :locked row
                                             (action-of :locked :finish) nil))]
      (is (= 409 (:status p)))
      (assert-way-out p :locked :finish))))

;; ── wave one, charter ──
;;
;; THE CHARTER MUST BE TRUE (waymark-fp62.4.1). An envelope that
;; advertises a door makes a promise. One false promise makes an agent
;; verify every other one, and verification is fuel spent on distrust.
;; A person finds a false promise by reading sessions; these three
;; obligations are the gate finding it instead.
;;
;; The kinds below are suite-local and DELIBERATELY NOT ENROLLED:
;; both walls are pure functions of a declaration and a row, so they
;; are judged here with no database at all, beside the enrolled walk
;; that judges the same walls through the real door.

(def ^:private errand
  "A kind whose two endings are STATES and not terminal — choreplan's
  own shape. `reopen` and `unskip` are the ways back; `note` continues
  the work from an ending, which is exactly the door the ending shuts."
  (r/resource
   {:kind :cf_errand :plural "cf_errands"
    :states [:due :done :skipped] :initial :due
    :terminal #{}
    :over {:accomplished #{:done} :let-go #{:skipped}}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 40}]]]
    :actions {:complete {:from #{:due} :to :done
                         :safety {:idempotent true :reversible false
                                  :confirm false :one-way "Done is done."}}
              :skip {:from #{:due} :to :skipped
                     :safety {:idempotent true :reversible false
                              :confirm false :one-way "Let go."}}
              :reopen {:from #{:done} :to :due
                       :safety {:idempotent true :reversible false
                                :confirm false :one-way "Back to the pile."}}
              :unskip {:from #{:skipped} :to :due
                       :safety {:idempotent true :reversible false
                                :confirm false :one-way "Back to the pile."}}
              :note {:from #{:due :done} :to :done
                     :safety {:idempotent true :reversible false
                              :confirm false :one-way "A note is kept."}}}}))

(def ^:private queued
  "A MIRROR-shaped kind: nothing is terminal and the lifecycle is a
  FIELD, so the machine can show no way back and the kind names one."
  (r/resource
   {:kind :cf_queued :plural "cf_queueds"
    :states [:fresh] :initial :fresh :terminal #{}
    :over {:field :status
           :accomplished #{"finished"} :let-go #{"abandoned"}
           :ways-back #{:start}}
    :summary "{data.title} · {data.status}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:status {:optional true}
              [:maybe [:enum "queued" "active" "finished" "abandoned"]]]]
    :actions {:start {:from #{:fresh} :to :fresh
                      :safety {:idempotent true :reversible false
                               :confirm false :one-way "Started."}}
              :finish {:from #{:fresh} :to :fresh
                       :safety {:idempotent true :reversible false
                                :confirm false :one-way "Finished."}}
              :abandon {:from #{:fresh} :to :fresh
                        :safety {:idempotent true :reversible false
                                 :confirm false :one-way "Let go."}}}}))

(def ^:private roster
  "A kind whose one door carries BOTH ref shapes: one id and a list of
  them. The list is what R-4 is about — one unresolved item refuses
  the whole door, and the sentence names the item's position."
  (r/resource
   {:kind :cf_roster :plural "cf_rosters"
    :states [:open] :initial :open :terminal #{}
    :summary "{data.title}"
    :schema [:map [:title [:string {:min 1 :max 40}]]]
    :actions {:seat {:from #{:open} :to :open
                     :input [:map
                             [:meal_id {:kind :meal} :waymark/ref]
                             [:meal_ids {:kind :meal}
                              [:vector :waymark/ref]]]
                     :safety {:idempotent true :reversible false
                              :confirm false :one-way "Seated."}}}}))

(defn- action-map [rdef aname]
  (assoc (get-in rdef [:actions aname]) :name aname))

(def ^:private wall-names #{:the-work-is-over :names-a-row-that-stands})

(defn- wall-verdict
  "The first framework wall's verdict on one door, judged exactly as
  render probes it and invoke runs it: through `g/walled-guards`. The
  walls ride behind the kind's own guards, so the first WALL is what
  this reads, not the first guard."
  [rdef aname row inp ctx]
  (let [defn' (action-map rdef aname)
        guards (g/walled-guards rdef defn' row)]
    (if-some [wall (first (filter #(wall-names (:name %)) guards))]
      (let [[v d] (g/evaluate wall row inp ctx)]
        {:guard (:name d)
         :denied (t/deny? v)
         :reason (g/render-reason d v row)})
      ;; no wall and no declared guard: the door is simply open
      {:guard nil :denied false :reason ""})))

;; ── 7. advertised-door truth ────────────────────────────────────────
;;
;; R-1 and R-2: every door an envelope advertises must RUN, or refuse
;; with a NAMED guard. A door advertised and then refused because of
;; the row's state is the defect — the machine's own wrong-state
;; refusal must never be the answer to something the probe offered.

(deftest advertised-door-truth
  (doseq [kind fixture-kinds
          state (sort (machine/reachable-states (rdef kind)))]
    (let [row (fac/walk-to-state *eng* kind state {:seed 83})]
      (when-not (:skip row)
        (let [ctx (fac/probe-ctx *eng*)]
          (doseq [action (fac/available-actions (rdef kind) row ctx)]
            (testing (str (name kind) "." (name state) " advertises "
                          (name (:name action)))
              (let [body (fac/synthesize-input *eng* (rdef kind) action row ctx
                                               {:seed 89})]
                (when (or body (nil? (:input action)))
                  ;; the rehearsal wears the client's own manners — the
                  ;; fence when the door declares one, a key when the
                  ;; door is not idempotent (fac/walker-invoke!'s)
                  (let [opts {:principal (fac/walker-principal)
                              :dry-run true
                              :idempotency-key
                              (when-not (get-in action [:safety :idempotent])
                                (str (random-uuid)))
                              :if-match
                              (when (get-in action [:safety :fence])
                                (inv/etag kind (:id row) (:version row)))}
                        p (problem-of
                           #(inv/invoke! *eng* kind (:id row) (:name action)
                                         body opts))]
                    (is (not= :wrong-state (:waymark10/problem p))
                        "an advertised door is never answered by the machine's state rule")
                    (when p
                      (is (= :guard-refused (:waymark10/problem p))
                          "an advertised door that refuses refuses as a guard")
                      (is (some? (:guard p))
                          "and the refusal names the guard"))))))))))))

;; ── 8. an ended row advertises only the ways back ───────────────────

(deftest the-ending-shuts-the-doors
  (testing "a state-declared ending: the machine shows its own ways back"
    (is (= #{:reopen :unskip} (machine/ways-back errand)))
    (let [done {:id "e1" :state :done :version 1 :data {:title "Post the parcel"}}]
      (is (true? (machine/work-over? errand done)))
      (is (true? (machine/accomplished? errand done)))
      (let [shut (wall-verdict errand :note done nil {})]
        (is (true? (:denied shut)))
        (is (= :the-work-is-over (:guard shut)))
        (is (re-find #"over" (:reason shut))
            "the refusal says the work is over, in the kind's own word"))
      (let [back (wall-verdict errand :reopen done nil {})]
        (is (not= :the-work-is-over (:guard back))
            "the way back is not shut by the ending it undoes"))))

  (testing "a field-declared ending: the kind names the way back"
    (is (= #{:start} (machine/ways-back queued)))
    (let [gone {:id "q1" :state :fresh :version 1
                :data {:title "12 Angry Men" :status "abandoned"}}
          live {:id "q2" :state :fresh :version 1
                :data {:title "12 Angry Men" :status "queued"}}]
      (is (true? (machine/work-over? queued gone)))
      (is (false? (machine/accomplished? queued gone))
          "let go is over without being a deed")
      (is (false? (machine/work-over? queued live)))
      (doseq [door [:finish :abandon]]
        (let [shut (wall-verdict queued door gone nil {})]
          (is (true? (:denied shut))
              (str (name door) " is shut on a row that is over"))
          (is (= :the-work-is-over (:guard shut)))))
      (is (not= :the-work-is-over (:guard (wall-verdict queued :start gone nil {})))
          "the declared way back stays open")
      (doseq [door [:start :finish :abandon]]
        (is (not= :the-work-is-over
                  (:guard (wall-verdict queued door live nil {})))
            (str (name door) " is untouched while the work is not over")))))

  (testing "a kind that declares no :over has no ending to shut a door with"
    (let [row {:id "m1" :state :suggested :version 1
               :data {:name "Tacos" :themes []}}]
      (is (empty? (filter #(= :the-work-is-over (:name %))
                          (g/walled-guards fx/meal (action-map fx/meal :accept)
                                           row))))))

  (testing "an exception that names no door refuses at the def site"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource
                  {:kind :cf_typo :plural "cf_typos"
                   :states [:fresh] :initial :fresh :terminal #{}
                   :over {:field :status :accomplished #{"finished"}
                          :let-go #{} :ways-back #{:resume}}
                   :summary "{data.title}"
                   :schema [:map [:title [:string {:min 1 :max 40}]]]
                   :actions {:finish {:from #{:fresh} :to :fresh
                                      :safety {:idempotent true
                                               :reversible false
                                               :confirm false
                                               :one-way "Finished."}}}})))))

;; ── 9. dangling-ref truth ───────────────────────────────────────────
;;
;; R-3 and R-4: a door that carries a ref resolves it. An id that names
;; no row refuses; an id of the WRONG KIND refuses by the same read,
;; because the row is not there under the kind the field names. The
;; sentence names the field, and a list names the position.

(deftest dangling-ref-truth
  (testing "the declaration knows which of its fields name rows"
    (is (= [{:field :meal_id :kind :meal :listed false}
            {:field :meal_ids :kind :meal :listed true}]
           (schema/ref-fields (:input (action-map roster :seat)))))
    (is (= [{:field :meal_id :kind :meal :listed false}]
           (:ref-fields (action-map fx/plan :assign_meal)))))

  (let [row {:id "r1" :state :open :version 1 :data {:title "Sunday"}}
        held #{"m-real" "m-also"}
        ctx {:rdef-of {:meal fx/meal}
             :read (fn [kind id]
                     (when (and (= :meal kind) (held (str id)))
                       {:id (str id) :state :suggested}))}
        verdict (fn [inp] (wall-verdict roster :seat row inp ctx))]

    (testing "one ref, invented"
      (let [v (verdict {:meal_id "m-nope" :meal_ids ["m-real"]})]
        (is (true? (:denied v)))
        (is (= :names-a-row-that-stands (:guard v)))
        (is (re-find #"meal_id" (:reason v)) "the sentence names the field")
        (is (re-find #"meal" (:reason v)) "and the kind it expected")))

    (testing "one ref, a row of the wrong kind"
      ;; the read is per KIND, so an id that stands elsewhere does not
      ;; stand here — one rule, both defects
      (let [v (verdict {:meal_id "p-real" :meal_ids ["m-real"]})]
        (is (true? (:denied v)))
        (is (= :names-a-row-that-stands (:guard v)))
        (is (re-find #"meal_id" (:reason v)))))

    (testing "a list refuses on one item, and names its position"
      (let [v (verdict {:meal_id "m-real"
                        :meal_ids ["m-real" "m-nope" "m-also"]})]
        (is (true? (:denied v)))
        (is (= :names-a-row-that-stands (:guard v)))
        (is (re-find #"meal_ids\[1\]" (:reason v)))))

    (testing "every ref standing is no refusal at all"
      (let [v (verdict {:meal_id "m-real" :meal_ids ["m-real" "m-also"]})]
        (is (false? (:denied v)))))

    (testing "an absent optional ref is nothing to resolve"
      (let [v (verdict {:meal_id "m-real"})]
        (is (false? (:denied v)))))

    (testing "no read in scope advertises optimistically"
      (let [v (wall-verdict roster :seat row {:meal_id "m-nope"}
                            {:rdef-of {:meal fx/meal}})]
        (is (false? (:denied v))))))

  (testing "through the real door: an invented ref refuses by name"
    (let [row (fac/walk-to-state *eng* :plan :draft {:seed 97})
          p (problem-of
             #(inv/invoke! *eng* :plan (:id row) :assign_meal
                           {:date "2025-01-06"
                            :meal_id (str (random-uuid))}
                           {:principal (fac/walker-principal)}))]
      (is (= 409 (:status p)))
      (is (= :guard-refused (:waymark10/problem p)))
      (is (= :names-a-row-that-stands (:guard p)))
      (is (re-find #"meal_id" (str (:detail p))))))

  (testing "through the real door: an id of the wrong kind refuses too"
    (let [row (fac/walk-to-state *eng* :plan :draft {:seed 101})
          p (problem-of
             #(inv/invoke! *eng* :plan (:id row) :assign_meal
                           {:date "2025-01-06" :meal_id (:id row)}
                           {:principal (fac/walker-principal)}))]
      (is (= 409 (:status p)))
      (is (= :names-a-row-that-stands (:guard p)))
      (is (re-find #"meal_id" (str (:detail p))))))

  (testing "through the real door: a ref that stands is admitted"
    (let [row (fac/walk-to-state *eng* :plan :draft {:seed 103})
          meal (:row (fac/create-example *eng* :meal {:seed 107}))
          res (inv/invoke! *eng* :plan (:id row) :assign_meal
                           {:date "2025-01-06" :meal_id (:id meal)}
                           {:principal (fac/walker-principal)})]
      (is (= :draft (get-in res [:row :state])))
      (is (= (:id meal)
             (some :meal_id (get-in res [:row :data :days])))))))
