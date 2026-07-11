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
          body {:date "2025-01-06" :meal_id "m-tacos"}
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
            res (inv/invoke! *eng* :plan pid :assign_meal
                             {:meal_id "m-x"} opts)]
        (is (true? (:valid? res)))
        (is (= [] (:judged res)))
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
        (is (= [:date-in-plan] (:judged res)))
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
