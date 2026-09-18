(ns waymark10.halt-lift-test
  "The halt line, and the doors that lift it (waymark-fp62.7.13,
  docs/spec-seat.md R-7.7 and R-12.20).

  R-7.7 gives the line one writer and one lifter: the router writes it
  when a sitter's request meets a wall, and clears it when a later
  request passes. A FIRED seat makes no request of its own. Its wake
  comes through the `fire` door, and that door reads the line — so a
  wall that lifted left the seat stopped until a person started the
  Routine by hand.

  The line is a record of a wall. It is not a lock. This suite proves
  the three doors that lift it, one deftest per acceptance line:

  1. a restate that raises the budget lifts `budget_reached` on the
     row it answers, and a fire then goes out;
  2. a week that rolled lifts it at the fire door itself — the door
     sums the week as the wall does — and the line is gone afterwards;
  3. a restate that names a new model lifts `model_not_held`;
  4. a restate that changes nothing leaves the line, and the fire is
     refused with the wall's sentence, which names the restate;
  5. a week that is still spent refuses the fire, and the line stands;
  6. a sitter's request still writes the line and still clears it —
     the router's own path is untouched.

  No database: dev/scratch! is the whole world, the clock is an atom
  (which is what makes a sitting eight days old cost one line), and
  the fake scheduler and the fake fire endpoint stand at the
  provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the household a seat sits in ────────────────────────────────────

(r/defresource pantry
  {:kind :halt_pantry
   :states [:open :done]
   :plural "halt_pantries"
   :initial :open
   :terminal #{:done}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]]})

;; ── the world ───────────────────────────────────────────────────────

(def ^:private t0 (Instant/parse "2026-09-17T08:00:00Z"))

(defn- world
  "A booted engine with the clock held, the fake scheduler in the
  adapter slot and the fake fire endpoint at the provider — the
  fire-door fixture of fire-door-test, over memory."
  []
  (let [clock (atom t0)
        fake (sch/fake-scheduler)
        fire (sch/fake-fire)
        eng (assoc (dev/scratch! [pantry] {:now-fn (fn [] @clock)})
                   :schedule-adapters {:claude_routine fake}
                   :fire-adapter fire)]
    {:clock clock :eng eng :fire fire :h (dev/handler eng)}))

;; ── the wire ────────────────────────────────────────────────────────

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> {:request-method method :uri path :headers (or headers {})}
          query (assoc :query-string query)
          body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private human {"x-waymark-principal" "colton"})

(def ^:private colton
  "The same person, off the wire: the fire door is not idempotent, so
  a fire carries a key, and `inv/invoke!` is where a test hands one
  over."
  (t/principal {:id "colton" :type :human :display "Colton"}))

(defn- sitter
  "An agent's dev headers — its id, the leash it presents, and the
  model its session declares."
  [id & [{:keys [grant model]}]]
  (cond-> {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
    grant (assoc "x-waymark-grant" grant)
    model (assoc "x-waymark-model" model)))

;; ── what a person pastes ────────────────────────────────────────────

(def ^:private a-fire-url
  "https://api.anthropic.com/v1/claude_code/routines/trig_01FAKE/fire")

(def ^:private a-fire-token "rk-test-0123456789abcdef")

;; ── writers ─────────────────────────────────────────────────────────

(def ^:private a-scope [{:kind "halt_pantry" :actions ["create" "finish"]}])
(def ^:private a-charter
  "Decide whether a message asks something of this house.")

(defn- seat-body [nm extra]
  (merge {:name nm
          :charter a-charter
          :scope a-scope
          :substitute_drop []
          :held_for []
          :substitute_for []
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5
          :sitting_budget_tokens 60000
          :rows_per_firing 20}
         extra))

(defn- open-seat!
  ([h nm] (open-seat! h nm {}))
  ([h nm extra]
   (let [made (req h :post "/api/seats" {:headers human :body (seat-body nm extra)})]
     (assert (= 201 (:status made)) (pr-str (json made)))
     (id-of made))))

(defn- add-model!
  ([h nm] (add-model! h nm {}))
  ([h nm prices]
   (let [made (req h :post "/api/models"
                   {:headers human
                    :body (merge {:name nm :display nm :vendor "anthropic"
                                  :tier "strong"
                                  :price_input_per_mtok 3
                                  :price_output_per_mtok 15
                                  :price_cache_read_per_mtok 0.3
                                  :price_cache_write_per_mtok 3.75}
                                 prices)})]
     (assert (= 201 (:status made)) (pr-str (json made)))
     (id-of made))))

(defn- etag-of [h uri headers]
  (get-in (json (req h :get uri {:headers headers})) [:meta :etag]))

(defn- restate!
  "A restate states the office whole and carries the fence its :edit
  implies, so the caller hands over the etag an honest client reads
  off the row first. → the response, so a refused restate is readable."
  [h seat-id extra]
  (req h :post (str "/api/seats/" seat-id "/-/restate")
       {:headers (assoc human "if-match"
                        (etag-of h (str "/api/seats/" seat-id) human))
        :body (merge {:charter a-charter
                      :scope a-scope
                      :substitute_drop []
                      :held_for []
                      :substitute_for []
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5
                      :sitting_budget_tokens 60000
                      :rows_per_firing 20}
                     extra)}))

(defn- sit!
  "The whole bootstrap: an agent asks to sit in a seat, a person
  approves, and the minted grant's id comes back."
  [h who nm]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers who
                    :body {:task "Walk the queue this seat owns." :seat nm}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers human})]
    (assert (= 200 (:status approved)) (pr-str (json approved)))
    (get-in (json approved) [:data :grant_id])))

(defn- sitting!
  "A closed sitting for this seat, costed at the model's prices — the
  shape a week's spend is summed from."
  [h who seat-id model-id gid tokens]
  (let [made (req h :post "/api/sittings"
                  {:headers who
                   :body {:seat seat-id :model model-id :grant gid}})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        closed (req h :post (str "/api/sittings/" (id-of made) "/-/close")
                    {:headers who
                     :body {:input_tokens tokens :output_tokens 0
                            :cache_read_tokens 0 :cache_write_tokens 0
                            :turns 1 :note "A wake, for the ledger."}})]
    (assert (= 200 (:status closed)) (pr-str (json closed)))
    (id-of made)))

;; ── readers ─────────────────────────────────────────────────────────

(defn- seat-row [eng seat-id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :seat (str seat-id) {}))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- clears [eng seat-id]
  (filter #(= :clear_halt (:action %)) (log-of eng :seat seat-id)))

(defn- halts [eng seat-id]
  (filter #(= :mark_halted (:action %)) (log-of eng :seat seat-id)))

;; ── the hands at the fire door ──────────────────────────────────────

(defn- drain!
  "One synchronous drain under a named cursor, seeded before the
  writes it is about — schedules-test's discipline."
  [eng cname]
  (consumers/drain-consumer! eng cname (sch/consumer-fn eng)))

(defn- link-fire!
  "The seat's schedule, minted by the drain and linked by a person:
  the fire door's own precondition (R-12.18)."
  [eng h cname seat-id]
  (drain! eng cname)
  (let [sched (sch/schedule-for-seat eng seat-id)
        _ (assert (some? sched) "the drain mints the seat's schedule")
        linked (req h :post (str "/api/schedules/" (:id sched) "/-/link")
                    {:headers human
                     :body {:fire_url a-fire-url :token a-fire-token}})]
    (assert (= 200 (:status linked)) (pr-str (json linked)))
    (:id sched)))

(defn- fire!
  "A person's fire. The door is not idempotent, so every call carries
  its own key — the MCP door's posture, minted fresh here as it is
  there."
  [eng seat-id]
  (inv/invoke! eng :seat (str seat-id) :fire {:text "Look at this now."}
               {:principal colton
                :idempotency-key (str "halt-lift:" (random-uuid))}))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

;; ── 1 · a restate that raises the budget lifts the line ─────────────

(deftest a-restate-that-raises-the-budget-lifts-the-line-and-the-fire-goes-out
  (let [{:keys [eng h fire]} (world)
        cn :halt-lift-raise
        _ (drain! eng cn)
        seat (open-seat! h "clerk-raise")
        _ (link-fire! eng h cn seat)]
    (seats/seat-halt! eng seat "budget_reached"
                      (str "The week's fuel is spent: 5.00 of 5.00 over"
                           " clerk-raise's sittings of the last seven days."))

    (testing "the halt line is gone on the row the restate answers"
      (let [answered (restate! h seat {:budget_usd_per_week 20})]
        (is (= 200 (:status answered)) (pr-str (json answered)))
        (is (nil? (get-in (json answered) [:data :halt]))
            "the person is handed back a row with no line on it"))
      (is (nil? (get-in (seat-row eng seat) [:data :halt]))
          "and the stored row says the same"))

    (testing "the lift is the handler's, in the restate's own transaction"
      (is (empty? (clears eng seat))
          "no second door, and no second write: the restate IS the lift"))

    (testing "and a fire then goes out"
      (is (some? (fire! eng seat)))
      (drain! eng cn)
      (is (= 1 (count (sch/fires fire))))
      (is (= a-fire-url (:fire-url (last (sch/fires fire))))))))

;; ── 2 · the window rolls, and the door sees it ──────────────────────

(deftest a-week-that-rolled-lifts-the-line-at-the-fire-door
  (let [{:keys [eng h clock fire]} (world)
        cn :halt-lift-window
        _ (drain! eng cn)
        ;; five dollars a million input tokens, so one million tokens
        ;; is exactly the seat's week
        model (add-model! h "window-model" {:price_input_per_mtok 5})
        seat (open-seat! h "clerk-window" {:held_for [model]})
        _ (link-fire! eng h cn seat)
        gid (sit! h (sitter "ari-window" {:model "window-model"}) "clerk-window")
        as (sitter "ari-window" {:grant gid :model "window-model"})]

    (testing "a sitting that spends the week walls the seat, and the line is written"
      (sitting! h as seat model gid 1000000)
      (is (= 404 (:status (req h :get "/api/halt_pantries" {:headers as}))))
      (is (= "budget_reached" (get-in (seat-row eng seat) [:data :halt :reason]))))

    (testing "eight days on the sitting is outside the window, and the fire goes out"
      (reset! clock (.plusSeconds t0 (* 8 86400)))
      (is (some? (fire! eng seat))
          "the door sums the week itself rather than reading the line")
      (drain! eng cn)
      (is (= 1 (count (sch/fires fire)))
          "and the provider was asked for one run"))

    (testing "and the line is gone afterwards, through the door that lifts it"
      (is (nil? (get-in (seat-row eng seat) [:data :halt])))
      (is (= 1 (count (clears eng seat))))
      (is (= "system" (get-in (first (clears eng seat)) [:actor :type]))
          "the engine's own hand, logged — the audit shows the lift"))))

;; ── 3 · a restate that names a new model ────────────────────────────

(deftest a-restate-that-names-a-new-model-lifts-the-model-line
  (let [{:keys [eng h fire]} (world)
        cn :halt-lift-step
        _ (drain! eng cn)
        first-rung (add-model! h "step-first-model")
        next-rung (add-model! h "step-next-model")
        seat (open-seat! h "clerk-step" {:held_for [first-rung]})
        _ (link-fire! eng h cn seat)]
    (seats/seat-halt! eng seat "model_not_held"
                      (str "This session declares step-next-model and"
                           " clerk-step is held for 1 model(s) as its full"
                           " sitter; a session outside the list sees nothing."))

    (testing "the step carries its note, and the line goes with the step"
      (let [answered (restate! h seat
                               {:held_for [next-rung]
                                :note (str "Up one rung: five sittings read,"
                                           " and the three refusals were all"
                                           " law this seat had to learn.")})]
        (is (= 200 (:status answered)) (pr-str (json answered)))
        (is (nil? (get-in (json answered) [:data :halt]))))
      (is (nil? (get-in (seat-row eng seat) [:data :halt]))))

    (testing "and a fire then goes out"
      (is (some? (fire! eng seat)))
      (drain! eng cn)
      (is (= 1 (count (sch/fires fire)))))))

;; ── 4 · a restate that changes nothing ──────────────────────────────

(deftest a-restate-that-moves-no-wall-leaves-the-model-line-standing
  (let [{:keys [eng h fire]} (world)
        cn :halt-lift-same
        _ (drain! eng cn)
        rung (add-model! h "same-model")
        seat (open-seat! h "clerk-same" {:held_for [rung]})
        _ (link-fire! eng h cn seat)]
    (seats/seat-halt! eng seat "model_not_held"
                      (str "This session declares nothing and clerk-same is"
                           " held for 1 model(s) as its full sitter; a"
                           " session outside the list sees nothing."))

    (testing "a restate that states the same model again keeps the line"
      (let [answered (restate! h seat {:held_for [rung]})]
        (is (= 200 (:status answered)) (pr-str (json answered)))
        (is (= "model_not_held" (get-in (json answered) [:data :halt :reason]))))
      (is (= "model_not_held" (get-in (seat-row eng seat) [:data :halt :reason]))))

    (testing "and the fire is refused with the wall's sentence, which names the restate"
      (let [p (refusal #(fire! eng seat))]
        (is (= :not-halted (:guard p)))
        (is (str/includes? (str (:detail p)) "is held for")
            "the wall's own words, as the router wrote them")
        (is (str/includes? (str (:detail p)) "Restate the seat")
            "and the door out, named"))
      (drain! eng cn)
      (is (empty? (sch/fires fire))
          "the provider was told nothing at all"))))

;; ── 5 · a week that is still spent ──────────────────────────────────

(deftest a-week-that-is-still-spent-refuses-the-fire-and-the-line-stands
  (let [{:keys [eng h fire]} (world)
        cn :halt-lift-spent
        _ (drain! eng cn)
        model (add-model! h "spent-model" {:price_input_per_mtok 5})
        seat (open-seat! h "clerk-spent" {:held_for [model]})
        _ (link-fire! eng h cn seat)
        gid (sit! h (sitter "ari-spent" {:model "spent-model"}) "clerk-spent")
        as (sitter "ari-spent" {:grant gid :model "spent-model"})]

    (testing "the seat spends its week, and the first request at the wall writes the line"
      (sitting! h as seat model gid 1000000)
      (is (= 404 (:status (req h :get "/api/halt_pantries" {:headers as}))))
      (is (= 1 (count (halts eng seat)))))

    (testing "the fire is refused: the door sums the week and the wall still holds"
      (let [p (refusal #(fire! eng seat))]
        (is (= :not-halted (:guard p)))
        (is (str/includes? (str (:detail p)) "The week's fuel is spent"))
        (is (str/includes? (str (:detail p)) "window rolls")
            "and the sentence says what lifts it"))
      (is (= "budget_reached" (get-in (seat-row eng seat) [:data :halt :reason]))
          "the line stands")
      (is (empty? (clears eng seat)))
      (drain! eng cn)
      (is (empty? (sch/fires fire))
          "a fire is fuel, and the provider heard nothing"))))

;; ── 6 · the router's own path, untouched ────────────────────────────

(deftest a-sitters-request-still-writes-the-line-and-still-clears-it
  (let [{:keys [eng h]} (world)
        held (add-model! h "router-held")
        _ (add-model! h "router-other")
        seat (open-seat! h "clerk-router" {:held_for [held]})
        gid (sit! h (sitter "ari-router" {:model "router-held"}) "clerk-router")
        walled (sitter "ari-router" {:grant gid :model "router-other"})
        passing (sitter "ari-router" {:grant gid :model "router-held"})]

    (testing "the first request at the wall writes the halt, by the engine's hand"
      (is (= 404 (:status (req h :get "/api/halt_pantries" {:headers walled}))))
      (is (= "model_not_held" (get-in (seat-row eng seat) [:data :halt :reason])))
      (is (= 1 (count (halts eng seat))))
      (is (= "system" (get-in (first (halts eng seat)) [:actor :type]))))

    (testing "and the first request that passes clears it, once"
      (is (= 200 (:status (req h :get "/api/halt_pantries" {:headers passing}))))
      (is (nil? (get-in (seat-row eng seat) [:data :halt])))
      (is (= 1 (count (clears eng seat))))
      (req h :get "/api/halt_pantries" {:headers passing})
      (is (= 1 (count (clears eng seat)))
          "a seat with no line is not written again"))))
