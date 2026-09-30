(ns waymark10.delegation-test
  "Delegated seat authorship (server/delegation), end to end over the
  ring handler on a memory engine: a MAYOR seat whose person wrote it
  a ceiling, its sitter opening and tuning seats inside it, and every
  call it may not make alone waiting on the person's tap as a held
  call that the person's Allow replays.

  No database: dev/scratch! is the whole world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the household the seats work in ─────────────────────────────────

(r/defresource ticket
  {:kind :dl_ticket
   :plural "dl_tickets"
   :states [:open :done :dropped]
   :initial :open
   :terminal #{:done :dropped}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:repo {:optional true} [:maybe [:string {:max 40}]]]]
   :filterable {:state #{:eq :in} :repo #{:eq}}
   :default-filters {:state "open"}
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]
          [:open :drop :dropped
           {:one-way "Dropping records reality; nothing external changes."
            :display {:label "Drop"}}]]})

(r/defhandler rename-gadget [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(r/defhandler poke-gadget [row _inp _ctx]
  (update-in row [:data :pokes] (fnil inc 0)))

(r/defresource gadget
  ;; a FENCED door with no prefill list: the strict fence stays
  {:kind :dl_gadget
   :plural "dl_gadgets"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:pokes {:optional true} [:maybe :int]]]
   :actions
   {:rename {:from #{:open} :to :open
             :input [:map [:name [:string {:min 1 :max 80}]]]
             :safety {:idempotent true :reversible true :confirm false
                      :fence true}
             :handler rename-gadget}
    :poke {:from #{:open} :to :open
           :safety {:idempotent true :reversible true :confirm false}
           :handler poke-gadget}
    :close {:from #{:open} :to :closed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A closed gadget is history."}}}})

;; ── the world ───────────────────────────────────────────────────────

(def ^:private t0 (Instant/parse "2026-09-23T08:00:00Z"))

(defn- world []
  (let [clock (atom t0)
        eng (dev/scratch! [ticket gadget] {:now-fn (fn [] @clock)})]
    {:clock clock :eng eng :h (dev/handler eng)}))

(defn- fire-world
  "The world with the fake scheduler and the fake fire endpoint at the
  provider, so a seat can be linked and fired (halt-lift-test's
  fixture)."
  []
  (let [clock (atom t0)
        eng (assoc (dev/scratch! [ticket gadget] {:now-fn (fn [] @clock)})
                   :schedule-adapters {:claude_routine (sch/fake-scheduler)}
                   :fire-adapter (sch/fake-fire))]
    {:clock clock :eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> {:request-method method :uri path :headers (or headers {})}
          query (assoc :query-string query)
          body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- as-sitter
  "The mayor's sitter: the seat's own member id, an agent, acting for
  its person, presenting the grant it sits under."
  [sitter-id grant]
  {"x-waymark-principal" sitter-id
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"
   "x-waymark-grant" grant})

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(declare get-row)

(defn- money
  "A decimal as the envelope spells it, however that is."
  [v]
  (bigdec (str (if (map? v) (:dec v) v))))

(defn- budget-of [h seat]
  (money (get-in (get-row h "seats" seat person) [:data :budget_usd_per_week])))

(defn- get-row [h kind-plural id headers]
  (json (req h :get (str "/api/" kind-plural "/" id) {:headers headers})))

(defn- etag-of [h uri headers]
  (get-in (json (req h :get uri {:headers headers})) [:meta :etag]))

;; ── the shapes ──────────────────────────────────────────────────────

(def ^:private mayor-scope
  "What the mayor's OWN sitter may touch: seats and judgments, and no
  ticket at all — the ceiling below is not this."
  [{:kind "seat" :actions ["create" "restate" "park" "unpark" "retire" "merge"
                           "hand_to" "take_back"]}
   {:kind "judgment" :actions ["create" "revise" "promote" "supersede"]}])

(def ^:private ceiling
  {:scope [{:kind "dl_ticket" :actions ["create" "finish"] :filter {:repo "bench"}}
           {:kind "verdict" :actions ["judge"]}]
   :budget_usd_per_week 5
   :sitting_budget_tokens 100000})

(defn- body [nm extra]
  (merge {:name nm
          :charter "Decide which ticket on the bench is ready."
          :scope [{:kind "dl_ticket" :actions ["create" "finish"]
                   :filter {:repo "bench"}}]
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 2
          :sitting_budget_tokens 60000}
         extra))

(defn- restate-body [extra]
  (dissoc (body "ignored" extra) :name))

(defn- open-mayor!
  "The person opens the mayor with its ceiling, and its sitter sits in
  it through an ask the person approves. → {:mayor id :as headers}."
  [h]
  (let [made (req h :post "/api/seats"
                  {:headers person
                   :body (body "mayor" {:scope mayor-scope :delegates ceiling})})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        mayor (id-of made)
        sitter (str "seat:" mayor)
        asked (req h :post "/api/approval_requests"
                   {:headers {"x-waymark-principal" sitter
                              "x-waymark-actor-type" "agent"
                              "x-waymark-acts-for" "colton"}
                    :body {:task "Keep the house's seats." :seat "mayor"}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))
        grant (get-in (json approved) [:data :grant_id])]
    {:mayor mayor :sitter sitter :as (as-sitter sitter grant)}))

(defn- author!
  "The mayor's sitter opens a seat. → the response."
  [h as nm extra]
  (req h :post "/api/seats" {:headers as :body (body nm extra)}))

(defn- restate-as! [h who seat extra]
  (req h :post (str "/api/seats/" seat "/-/restate")
       {:headers (assoc who "if-match" (etag-of h (str "/api/seats/" seat) who))
        :body (restate-body extra)}))

(defn- allow! [h held-id]
  (req h :post (str "/api/held_calls/" held-id "/-/allow") {:headers person}))

;; ── the holds are declared, not listed ──────────────────────────────

(deftest the-four-delegation-walls-are-registered-holds
  (doseq [g [:authors-within-the-ceiling :the-persons-lever
             :promotes-under-a-parked-child :the-persons-judgment]]
    (is (delegation/hold-guard? g) (str (name g) " holds"))
    (is (contains? (delegation/hold-guards) g)))
  (is (not (delegation/hold-guard? :not-a-sitter))
      "every other refusal stays the 409 it always was"))

;; ── invariant 1 · a seat never restates itself ──────────────────────

(deftest a-mayor-does-not-restate-its-own-seat
  (let [{:keys [h eng]} (world)
        {:keys [mayor as]} (open-mayor! h)
        asked (restate-as! h as mayor {:scope mayor-scope :delegates ceiling
                                       :budget_usd_per_week 50})]
    (testing "the self-restate is not served: it is held for the person"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (str/includes? (str (:why (json asked))) "Invariant 1")
          "and the sentence says which invariant")
      (is (== 2 (budget-of h mayor))
          "the mayor's own budget stays the person's")
      (is (= [:create] (mapv :action (log-of eng :seat mayor)))
          "nothing moved on the mayor's row"))))

;; ── invariant 2 · the ceiling, entry by entry ───────────────────────

(deftest a-child-over-the-ceiling-is-held-and-the-sentence-names-the-entry
  (let [{:keys [h eng]} (world)
        {:keys [as]} (open-mayor! h)
        over (author! h as "bench-wide"
                      {:scope [{:kind "dl_ticket"
                                :actions ["create" "finish" "drop"]
                                :filter {:repo "bench"}}]})
        why (str (:why (json over)))]
    (is (= 202 (:status over)) (pr-str (json over)))
    (is (str/includes? why "Invariant 2"))
    (is (str/includes? why "{kind dl_ticket, actions [create, finish, drop], filter repo=bench}")
        "the entry that would have admitted it, spelled as an ask would spell it")
    (is (str/includes? why "the nearest ceiling entry is {kind dl_ticket, actions [create, finish], filter repo=bench}"))
    (is (nil? (store/with-tx (:storage eng)
                (fn [tx] (first (store/query-rows (:storage eng) tx :seat
                                                  {:name "bench-wide"} {})))))
        "no seat was written")

    (testing "a filter the child drops is a widening too"
      (let [unfiltered (author! h as "bench-any"
                                {:scope [{:kind "dl_ticket" :actions ["finish"]}]})]
        (is (= 202 (:status unfiltered)))
        (is (str/includes? (str (:why (json unfiltered))) "repo=bench"))))

    (testing "and so is a budget past the cap"
      (let [rich (author! h as "bench-rich" {:budget_usd_per_week 50})]
        (is (= 202 (:status rich)))
        (is (str/includes? (str (:why (json rich)))
                           "A ceiling budget_usd_per_week of 50 would have admitted it"))))))

;; ── invariants 3 and 4 · born parked, authored, owned ───────────────

(deftest a-child-within-the-ceiling-is-born-parked-and-authored
  (let [{:keys [h]} (world)
        {:keys [mayor as]} (open-mayor! h)
        made (author! h as "bench-clerk" {})
        child (id-of made)
        row (get-row h "seats" child person)]
    (is (= 201 (:status made)) (pr-str (json made)))
    (is (= "parked" (:state row)) "born parked: nothing it can do yet")
    (is (= mayor (get-in row [:data :authored_by])))
    (is (= "colton" (get-in row [:data :owner])))
    (is (nil? (get-in row [:data :approved_by])))
    (testing "the ceiling is not the mayor's scope"
      (is (= [{:kind "dl_ticket" :actions ["create" "finish"]
               :filter {:repo "bench"}}]
             (get-in row [:data :scope]))
          "the child holds ticket doors the mayor itself does not"))
    (testing "and it waits on the person: the unpark door is theirs to take"
      (let [env (json (req h :get (str "/api/seats/" child) {:headers person}))]
        (is (contains? (:actions env) :unpark)
            (pr-str (keys (:actions env))))))))

(deftest a-sitter-does-not-unpark-its-own-child-and-the-person-does
  (let [{:keys [h eng]} (world)
        {:keys [as sitter]} (open-mayor! h)
        child (id-of (author! h as "bench-clerk" {}))]
    (testing "the author's unpark is not served: it waits on the person"
      (let [asked (req h :post (str "/api/seats/" child "/-/unpark") {:headers as})]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (str/includes? (str (:why (json asked))) "Invariant 4"))
        (is (= "parked" (:state (get-row h "seats" child person))))
        (testing "and the author cannot allow its own held call"
          (let [self-allow (req h :post (str "/api/held_calls/"
                                             (:held_call (json asked)) "/-/allow")
                                {:headers as})]
            (is (not= 200 (:status self-allow)))
            (is (= "parked" (:state (get-row h "seats" child person))))))))
    (testing "the person's unpark is the approval"
      (let [done (req h :post (str "/api/seats/" child "/-/unpark") {:headers person})
            row (get-row h "seats" child person)]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (= "active" (:state row)))
        (is (= "colton" (get-in row [:data :approved_by])))
        (is (some? (get-in row [:data :approved_at])))
        (is (not= sitter (get-in row [:data :approved_by])))))
    (is (= [:create :unpark] (mapv :action (log-of eng :seat child))))))

(deftest the-persons-allow-replays-the-held-call-as-the-author
  (let [{:keys [h eng]} (world)
        {:keys [as sitter]} (open-mayor! h)
        child (id-of (author! h as "bench-clerk" {}))
        asked (req h :post (str "/api/seats/" child "/-/unpark") {:headers as})
        held (:held_call (json asked))]
    (is (= 200 (:status (allow! h held))))
    (let [row (get-row h "seats" child person)
          call (get-row h "held_calls" held person)]
      (is (= "active" (:state row)) "the tap unparked it")
      (is (= "colton" (get-in row [:data :approved_by]))
          "and the approval is the person's, not the author's")
      (is (= "done" (:state call)))
      (is (= sitter (get-in (last (log-of eng :seat child)) [:actor :id]))
          "the unpark is in the seat's history as the author's move"))))

;; ── after the approval · restates within the ceiling ────────────────

(deftest an-author-restates-its-approved-child-within-the-ceiling
  (let [{:keys [h eng]} (world)
        {:keys [as sitter]} (open-mayor! h)
        child (id-of (author! h as "bench-clerk" {}))
        _ (req h :post (str "/api/seats/" child "/-/unpark") {:headers person})]
    (testing "within the ceiling: served, no tap"
      (let [done (restate-as! h as child {:budget_usd_per_week 4
                                          :charter "Decide which bench ticket is ready now."})]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (== 4 (budget-of h child)))
        (let [last-move (last (log-of eng :seat child))]
          (is (= :restate (:action last-move)))
          (is (= sitter (get-in last-move [:actor :id]))
              "and it is a normal transition in the child's history"))))
    (testing "past the ceiling: held, and the Allow replays it"
      (let [asked (restate-as! h as child {:budget_usd_per_week 9})
            held (:held_call (json asked))]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (== 4 (budget-of h child)))
        (is (= 200 (:status (allow! h held))))
        (is (== 9 (budget-of h child)))
        (is (= "done" (:state (get-row h "held_calls" held person))))))))

;; ── the held fence is about the edit, not the version alone ──────────

(def ^:private colton
  "The person, off the wire: the fire door is not idempotent, so a
  fire carries a key, and `inv/invoke!` is where a test hands one over."
  (t/principal {:id "colton" :type :human :display "Colton"}))

(defn- link-fire!
  "The seat's schedule, minted by the drain and linked by the person:
  the fire door's own precondition (halt-lift-test's `link-fire!`)."
  [eng h cname seat]
  (consumers/drain-consumer! eng cname (sch/consumer-fn eng))
  (let [sched (sch/schedule-for-seat eng seat)
        _ (assert (some? sched) "the drain mints the seat's schedule")
        linked (req h :post (str "/api/schedules/" (:id sched) "/-/link")
                    {:headers person
                     :body {:fire_url "https://api.anthropic.com/v1/claude_code/routines/trig_01FAKE/fire"
                            :token "rk-test-0123456789abcdef"}})]
    (assert (= 200 (:status linked)) (pr-str (json linked)))))

(deftest a-held-restate-survives-a-fire-that-moved-none-of-its-fields
  (let [{:keys [h eng]} (fire-world)
        cn :delegation-held-fire
        _ (consumers/drain-consumer! eng cn (sch/consumer-fn eng))
        {:keys [as]} (open-mayor! h)
        child (id-of (author! h as "bench-clerk" {}))
        _ (req h :post (str "/api/seats/" child "/-/unpark") {:headers person})
        _ (link-fire! eng h cn child)
        asked (restate-as! h as child {:budget_usd_per_week 9})
        held-id (:held_call (json asked))]
    (is (= 202 (:status asked)) (pr-str (json asked)))
    (inv/invoke! eng :seat (str child) :fire {:text "Look at the bench now."}
                 {:principal colton
                  :idempotency-key (str "held-fire:" (random-uuid))})
    (is (= :fire (:action (last (log-of eng :seat child))))
        "the fire moved the row between the hold and the tap")
    (is (= 200 (:status (allow! h held-id))))
    (is (= "done" (:state (get-row h "held_calls" held-id person)))
        (pr-str (get-in (get-row h "held_calls" held-id person) [:data :reason])))
    (is (== 9 (budget-of h child)) "the restate landed")))

(deftest a-held-restate-fails-naming-the-field-the-person-moved
  (let [{:keys [h]} (world)
        {:keys [as]} (open-mayor! h)
        child (id-of (author! h as "bench-clerk" {}))
        _ (req h :post (str "/api/seats/" child "/-/unpark") {:headers person})
        asked (restate-as! h as child {:budget_usd_per_week 9})
        held-id (:held_call (json asked))
        own (restate-as! h person child
                         {:charter "Decide which bench ticket is ready this week."})]
    (is (= 202 (:status asked)) (pr-str (json asked)))
    (is (= 200 (:status own)) (pr-str (json own)))
    (is (= 200 (:status (allow! h held-id))))
    (let [call (get-row h "held_calls" held-id person)]
      (is (= "failed" (:state call)))
      (is (str/includes? (str (get-in call [:data :reason])) "charter")
          (pr-str (get-in call [:data :reason]))))
    (is (== 2 (budget-of h child)) "the stale restate did not land")))

(deftest a-held-call-on-a-door-with-no-prefill-fails-on-any-version-move
  (let [{:keys [h eng]} (world)
        made (req h :post "/api/dl_gadgets" {:headers person :body {:name "sprocket"}})
        gid (id-of made)
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        call (held/hold-door! eng {:kind :dl_gadget :action :rename :id gid
                                   :body {:name "cog"}
                                   :caller "seat:gadget-author"
                                   :owner "colton"
                                   :if-match (etag-of h (str "/api/dl_gadgets/" gid) person)
                                   :why "Rename it."})]
    (is (nil? (get-in call [:data :door :prefill_digests]))
        "a door with no prefill list keeps no digests")
    (is (= 200 (:status (req h :post (str "/api/dl_gadgets/" gid "/-/poke")
                             {:headers person}))))
    (is (= 200 (:status (allow! h (:id call)))))
    (is (= "failed" (:state (get-row h "held_calls" (:id call) person))))
    (is (= "sprocket" (get-in (get-row h "dl_gadgets" gid person) [:data :name])))))

(deftest a-held-self-restate-from-a-patch-replays-on-allow-and-stays-small
  (let [{:keys [h eng]} (fire-world)
        cn :delegation-held-self-patch
        _ (consumers/drain-consumer! eng cn (sch/consumer-fn eng))
        {:keys [mayor as]} (open-mayor! h)
        muted {:kind "notifier" :actions []}
        own (restate-as! h person mayor {:scope (conj mayor-scope muted)
                                         :delegates ceiling})
        _ (assert (= 200 (:status own)) (pr-str (json own)))
        _ (link-fire! eng h cn mayor)
        asked (req h :post (str "/api/seats/" mayor "/-/restate")
                   {:headers (assoc as "if-match" (etag-of h (str "/api/seats/" mayor) as))
                    :body {:patch true
                           :scope {:remove [muted]
                                   :add [{:kind "notifier" :actions ["create"]}]}}})
        held-id (:held_call (json asked))]
    (is (= 202 (:status asked)) (pr-str (json asked)))
    (testing "the held call names only scope, keeps the body once, and is small"
      ;; the STORED row is what #549 keeps small; the envelope also
      ;; carries the computed `call` (#558), so it is not measured
      (let [data (:data (store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx :held_call
                                                   (str held-id) {}))))
            size (count (.getBytes ^String (wire/write-json data) "UTF-8"))]
        (is (= ["scope"] (mapv name (keys (:changes data)))))
        (is (nil? (:input data)))
        (is (< size 1024) (str size " bytes: " (wire/write-json data)))))
    (inv/invoke! eng :seat (str mayor) :fire {:text "Look at the seats now."}
                 {:principal colton
                  :idempotency-key (str "held-fire:" (random-uuid))})
    (is (= :fire (:action (last (log-of eng :seat mayor))))
        "the fire moved the row between the hold and the tap")
    (is (= 200 (:status (allow! h held-id))))
    (let [call (get-row h "held_calls" held-id person)
          scope (get-in (get-row h "seats" mayor person) [:data :scope])
          shape (fn [xs] (mapv #(select-keys % [:kind :actions]) xs))]
      (is (= "done" (:state call)) (pr-str (get-in call [:data :reason])))
      (is (= (shape (conj mayor-scope {:kind "notifier" :actions ["create"]}))
             (shape scope))
          "the replay landed, and only the notifier entry changed"))))

(deftest a-patch-takes-a-list-delta-inside-a-map-field
  (let [{:keys [h]} (world)
        {:keys [mayor]} (open-mayor! h)
        uri (str "/api/seats/" mayor)
        delegates #(get-in (get-row h "seats" mayor person) [:data :delegates])
        patch! (fn [d]
                 (req h :post (str uri "/-/restate")
                      {:headers (assoc person "if-match" (etag-of h uri person))
                       :body {:patch true :delegates d}}))
        shape (fn [xs] (mapv #(select-keys % [:kind :actions]) xs))
        e {:kind "judgment" :actions ["revise"]}
        before (delegates)
        added (patch! {:scope {:add [e]}})
        after (delegates)]
    (is (= 200 (:status added)) (pr-str (json added)))
    (testing "exactly e is appended to delegates.scope"
      (is (= (conj (shape (:scope before)) e) (shape (:scope after)))))
    (testing "every other delegates key is unchanged"
      (is (= (dissoc before :scope) (dissoc after :scope))))
    (testing "a nested remove of an absent entry refuses patch-miss"
      (let [missed (patch! {:scope {:remove [{:kind "nope" :actions []}]}})]
        (is (= 409 (:status missed)) (pr-str (json missed)))
        (is (str/includes? (pr-str (json missed)) "delegates.scope"))
        (is (= after (delegates)) "and nothing moved")))))

(deftest an-author-does-not-restate-a-seat-it-did-not-author
  (let [{:keys [h eng]} (world)
        {:keys [as]} (open-mayor! h)
        other (id-of (req h :post "/api/seats"
                          {:headers person :body (body "persons-own" {})}))
        asked (restate-as! h as other {:budget_usd_per_week 1})]
    (is (= 202 (:status asked)) (pr-str (json asked)))
    (is (str/includes? (str (:why (json asked))) "was not authored by mayor"))
    (is (== 2 (budget-of h other)))
    (is (= [:create] (mapv :action (log-of eng :seat other))))))

(deftest a-sitter-of-a-seat-that-delegates-nothing-is-still-refused
  (let [{:keys [h]} (world)
        _ (req h :post "/api/seats"
               {:headers person :body (body "plain" {:scope mayor-scope})})
        asked (req h :post "/api/approval_requests"
                   {:headers {"x-waymark-principal" "plain-sitter"
                              "x-waymark-actor-type" "agent"
                              "x-waymark-acts-for" "colton"}
                    :body {:task "Sit." :seat "plain"}})
        grant (get-in (json (req h :post (str "/api/approval_requests/"
                                              (id-of asked) "/-/approve")
                                 {:headers person}))
                      [:data :grant_id])
        made (author! h (as-sitter "plain-sitter" grant) "sideways" {})]
    (is (= 409 (:status made)))
    (is (= "not-a-sitter" (:guard (json made))))))

;; ── invariant 5 · judgments ─────────────────────────────────────────

(def ^:private a-judgment
  {:name "Is the bench ticket ready"
   :subject_kind "dl_ticket"
   :queue {:state "open"}
   :verdicts [{:name "ready" :sentence "It can go."}
              {:name "not_yet" :sentence "It waits."}]})

(deftest a-judgment-is-promoted-only-under-a-parked-child
  (let [{:keys [h]} (world)
        {:keys [as]} (open-mayor! h)
        j (id-of (req h :post "/api/judgments" {:headers as :body a-judgment}))]
    (testing "no parked child cites it: the promotion waits on the person"
      (let [asked (req h :post (str "/api/judgments/" j "/-/promote") {:headers as})]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (str/includes? (str (:why (json asked))) "Invariant 5"))
        (is (= "draft" (:state (get-row h "judgments" j person))))))
    (testing "a parked child that cites the draft lets the author promote it"
      (let [made (author! h as "bench-judge"
                          {:walk "dl_ticket" :judgment j
                           :scope [{:kind "dl_ticket" :actions ["create" "finish"]
                                    :filter {:repo "bench"}}
                                   {:kind "verdict" :actions ["judge"]}]})
            child (id-of made)]
        (is (= 201 (:status made)) (pr-str (json made)))
        (is (= "parked" (:state (get-row h "seats" child person))))
        (let [done (req h :post (str "/api/judgments/" j "/-/promote") {:headers as})]
          (is (= 200 (:status done)) (pr-str (json done)))
          (is (= "promoted" (:state (get-row h "judgments" j person)))))))))

;; ── the token ceiling, ignored on purpose ───────────────────────────

(deftest a-person-may-tell-a-seat-to-ignore-its-token-ceiling
  (let [{:keys [h]} (world)
        made (req h :post "/api/seats"
                  {:headers person
                   :body (body "long-sitter" {:ignore_sitting_budget true})})]
    (is (= 201 (:status made)) (pr-str (json made)))
    (is (true? (get-in (get-row h "seats" (id-of made) person)
                       [:data :ignore_sitting_budget]))))
  (testing "an author may not lift a child's ceiling on its own"
    (let [{:keys [h]} (world)
          {:keys [as]} (open-mayor! h)
          asked (author! h as "bench-long" {:ignore_sitting_budget true})]
      (is (= 202 (:status asked)))
      (is (str/includes? (str (:why (json asked))) "ignore_sitting_budget")))))

;; ── the person's hand-off · hand_to and take_back ──────────────────

(defn- hand-to! [h who seat author]
  (req h :post (str "/api/seats/" seat "/-/hand_to")
       {:headers who :body {:author author}}))

(defn- persons-seat! [h nm extra]
  (let [made (req h :post "/api/seats" {:headers person :body (body nm extra)})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(deftest a-person-hands-a-seat-to-the-mayor-and-takes-it-back
  (let [{:keys [h]} (world)
        {:keys [as mayor]} (open-mayor! h)
        seat (persons-seat! h "code-seat" {})]
    (testing "before the hand-off the mayor's restate is held"
      (let [asked (restate-as! h as seat {:budget_usd_per_week 3})]
        (is (= 202 (:status asked)) (pr-str (json asked)))))
    (testing "the person hands it over, and the tap is the approval"
      (let [done (hand-to! h person seat mayor)
            data (:data (get-row h "seats" seat person))]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (= mayor (:authored_by data)))
        (is (= "colton" (:owner data)))
        (is (= "colton" (:approved_by data)))
        (is (some? (:approved_at data)))))
    (testing "within the ceiling the mayor's restate goes through"
      (let [done (restate-as! h as seat {:budget_usd_per_week 4})]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (== 4 (budget-of h seat)))))
    (testing "past the ceiling it is still held"
      (let [asked (restate-as! h as seat {:budget_usd_per_week 9})]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (== 4 (budget-of h seat)))))
    (testing "take_back makes the mayor's restate held again"
      (let [done (req h :post (str "/api/seats/" seat "/-/take_back")
                      {:headers person})
            data (:data (get-row h "seats" seat person))]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (nil? (:authored_by data)))
        (is (nil? (:approved_by data)))
        (let [asked (restate-as! h as seat {:budget_usd_per_week 3})]
          (is (= 202 (:status asked)) (pr-str (json asked)))
          (is (str/includes? (str (:why (json asked))) "was not authored by mayor"))
          (is (== 4 (budget-of h seat))))))))

(deftest the-mayor-does-not-hand-a-seat-to-itself
  (let [{:keys [h]} (world)
        {:keys [as mayor]} (open-mayor! h)
        seat (persons-seat! h "code-seat" {})
        asked (hand-to! h as seat mayor)]
    (is (= 202 (:status asked)) (pr-str (json asked)))
    (is (str/includes? (str (:why (json asked))) "Invariant 4"))
    (is (nil? (get-in (get-row h "seats" seat person) [:data :authored_by])))
    (testing "and take_back from the mayor is held too"
      (let [_ (hand-to! h person seat mayor)
            asked (req h :post (str "/api/seats/" seat "/-/take_back")
                       {:headers as})]
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (= mayor (get-in (get-row h "seats" seat person)
                             [:data :authored_by])))))))

(deftest hand-to-refuses-what-the-author-could-not-have-authored
  (let [{:keys [h]} (world)
        {:keys [mayor]} (open-mayor! h)]
    (testing "a seat that does not delegate is no author"
      (let [plain (persons-seat! h "plain" {})
            seat (persons-seat! h "code-seat" {})
            refused (hand-to! h person seat plain)]
        (is (= 409 (:status refused)) (pr-str (json refused)))
        (is (str/includes? (pr-str (json refused)) "carries no ceiling"))))
    (testing "a scope past the ceiling names the entry"
      (let [seat (persons-seat! h "wide"
                                {:scope [{:kind "dl_ticket"
                                          :actions ["create" "finish" "drop"]
                                          :filter {:repo "bench"}}]})
            refused (hand-to! h person seat mayor)]
        (is (= 409 (:status refused)) (pr-str (json refused)))
        (is (str/includes? (pr-str (json refused)) "past the ceiling"))
        (is (str/includes? (pr-str (json refused)) "drop"))
        (is (nil? (get-in (get-row h "seats" seat person)
                          [:data :authored_by])))))
    (testing "a parked seat is told to unpark first"
      (let [seat (persons-seat! h "resting" {})
            _ (req h :post (str "/api/seats/" seat "/-/park") {:headers person})
            refused (hand-to! h person seat mayor)]
        (is (= 409 (:status refused)) (pr-str (json refused)))
        (is (str/includes? (pr-str (json refused)) "Unpark it first"))))))
