(ns waymark10.seat-grants-test
  "The seat GRANT, end to end over the ring handler on a memory engine
  with a held clock (docs/spec-seat.md § 5, § 7.7, § 10.6): the ask's
  three shapes, the mint, the resolve with its three walls, the halt
  the router writes at one and clears past it, and the two counters a
  sitting keeps.

  The acceptance cases of § 16 answered here: 3 (the mint, and the ask
  that names two things), 4 (a request sees the seat's scope, and the
  next request sees a restate with no new grant), 6 (park and unpark),
  9 (one full sitter, unlimited substitutes), 10 (the seat's own
  ceiling beside the 24-hour one), 11 (the model list at the ask and at
  the request), 15 (the week's fuel, and the sitting eight days old
  that does not count), 18 (the counters) and 24 (the halt, written
  once and lifted).

  Case 5's other half — the substitute's bar on self, journal and
  letter — lives in workqueue10.substitute-test, because the three
  kinds it bars are that household's.

  No database: dev/scratch! is the whole world, and the clock is an
  atom, which is what makes a sitting eight days old cost one line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the household a seat sits in ────────────────────────────────────

(r/defresource pantry
  {:kind :seat_pantry
   :states [:open :done]
   :plural "seat_pantries"
   :initial :open
   :terminal #{:done}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]]})

(r/defresource ledger
  {:kind :seat_ledger_line
   :states [:open :done]
   :plural "seat_ledger_lines"
   :initial :open
   :terminal #{:done}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]]})

;; a door that exists to be refused: the 409 a sitting's `refusals`
;; counts is a GUARD refusal, and every other 409 this engine serves
;; (a wrong state, a reused key) is either a natural replay here or a
;; fixture of its own — one honest refusal is what the counter needs
(g/defguard sealed-for-good
  {:explain "This door exists to refuse: the vault does not open."}
  [_row _inp _ctx]
  (t/deny))

(r/defresource vault
  {:kind :seat_vault
   :plural "seat_vaults"
   :states [:open :sealed]
   :initial :open
   :terminal #{:sealed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :actions {:seal {:from #{:open} :to :sealed
                    :guards [sealed-for-good]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Sealing is for good."}
                    :display {:label "Seal"}}}})

;; ── the world ───────────────────────────────────────────────────────

(def ^:private t0 (Instant/parse "2026-09-17T08:00:00Z"))

(defn- world []
  (let [clock (atom t0)
        eng (dev/scratch! [pantry ledger vault] {:now-fn (fn [] @clock)})]
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

(def ^:private human {"x-waymark-principal" "colton"})

(defn- sitter
  "An agent's dev headers — its id, the leash it presents, and the
  model its session declares (router/dev-principal reads all three)."
  [id & [{:keys [grant model]}]]
  (cond-> {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
    grant (assoc "x-waymark-grant" grant)
    model (assoc "x-waymark-model" model)))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

;; ── the shapes a test starts from ───────────────────────────────────

(def ^:private clerk-scope
  [{:kind "seat_pantry" :actions ["create" "finish"]}
   {:kind "seat_vault" :actions ["create" "seal"]}])

(defn- seat-body [nm extra]
  (merge {:name nm
          :charter "Decide whether a message asks something of this house."
          :scope clerk-scope
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5
          :sitting_budget_tokens 60000}
         extra))

(defn- open-seat!
  "→ the seat's id: an envelope names itself by :self, never by a
  field, so every id in this file is read off the address."
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
  "A restate carries the fence its :edit implies, so the caller hands
  over the etag an honest client would have read off the row."
  [h seat-id extra]
  (req h :post (str "/api/seats/" seat-id "/-/restate")
       {:headers (assoc human "if-match" (etag-of h (str "/api/seats/" seat-id) human))
        :body (merge {:charter "Decide whether a message asks something of this house."
                      :scope clerk-scope
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5
                      :sitting_budget_tokens 60000}
                     extra)}))

(defn- ask!
  "One approval_request, filed by the agent that will hold the grant."
  [h who body]
  (req h :post "/api/approval_requests" {:headers who :body body}))

(defn- approve!
  "The person's verdict — four-eyes holds, so never the requester's
  own hand. → the response, so a refused approve is readable."
  ([h ask-resp] (approve! h ask-resp human))
  ([h ask-resp as]
   (req h :post (str "/api/approval_requests/" (id-of ask-resp) "/-/approve")
        {:headers as})))

(defn- sit!
  "The whole bootstrap: an agent asks to sit in a seat, a person
  approves, and the minted grant's id comes back."
  [h who nm extra]
  (let [asked (ask! h who (merge {:task "Walk the queue this seat owns."
                                  :seat nm}
                                 extra))
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (approve! h asked)]
    (assert (= 200 (:status approved)) (pr-str (json approved)))
    (get-in (json approved) [:data :grant_id])))

;; ── case 3 · the mint, and the ask that names two things ────────────

(deftest a-seat-ask-mints-a-grant-that-cites-the-seat-and-holds-no-scope
  (let [{:keys [h]} (world)
        seat (open-seat! h "clerk-mint")]
    (testing "an ask naming BOTH a seat and a scope is refused at the door"
      (let [both (ask! h (sitter "ari-mint")
                       {:task "Two things at once."
                        :seat "clerk-mint"
                        :scope [{:kind "seat_pantry" :actions ["create"]}]})]
        (is (= 409 (:status both)))
        (is (= "ask-names-one-thing" (:guard (json both))))
        (is (str/includes? (str (:detail (json both))) "never both"))))

    (testing "an ask naming a seat that is not open is refused, naming it"
      (let [gone (ask! h (sitter "ari-mint")
                       {:task "Sit in an office nobody opened."
                        :seat "no-such-clerk"})]
        (is (= 409 (:status gone)))
        (is (= "the-seat-is-open" (:guard (json gone))))
        (is (str/includes? (str (:detail (json gone))) "no-such-clerk"))))

    (testing "the seat ask mints {audience, seat, substitute, expires_at}"
      (let [gid (sit! h (sitter "ari-mint") "clerk-mint" {})
            g (json (req h :get (str "/api/grants/" gid) {:headers human}))]
        (is (= "accepted" (:state g)) "accepted at the mint: the ask was the consent")
        (is (= "ari-mint" (get-in g [:data :audience])))
        (is (= seat (get-in g [:data :seat]))
            "the ask spelled the NAME; the mint wrote the ref")
        (is (false? (get-in g [:data :substitute])))
        (is (empty? (get-in g [:data :scope]))
            "and no scope at all — the seat's row is the authority")
        (is (= (str "/api/seats/" seat)
               (get-in g [:links :seat :href]))
            "the grant points at the office it sits in")))

    (testing "a SUBSTITUTE ask mints the flag"
      (let [gid (sit! h (sitter "vera-mint") "clerk-mint" {:substitute true})
            g (json (req h :get (str "/api/grants/" gid) {:headers human}))]
        (is (true? (get-in g [:data :substitute])))
        (is (= seat (get-in g [:data :seat])))))))

;; ── case 4 · the seat's scope, resolved at each request ─────────────

(deftest a-sitter-sees-the-seats-scope-and-a-restate-moves-it
  (let [{:keys [h]} (world)
        seat (open-seat! h "clerk-scope")
        gid (sit! h (sitter "ari-scope") "clerk-scope" {})
        as (sitter "ari-scope" {:grant gid})]
    (testing "the sitter sees exactly the seat's scope"
      (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as}))))
      (is (= 200 (:status (req h :get "/api/seat_vaults" {:headers as}))))
      (is (= 404 (:status (req h :get "/api/seat_ledger_lines" {:headers as})))
          "a kind the seat does not open is not there"))

    (testing "and reads the seat row the grant cites, with no scope entry (R-4.9)"
      (let [row (req h :get (str "/api/seats/" seat) {:headers as})]
        (is (= 200 (:status row)))
        (is (= "clerk-scope" (get-in (json row) [:data :name])))
        (is (not (contains? (:actions (json row)) :park))
            "read-only: the sitter is offered no door on its own office")
        (is (not (contains? (:actions (json row)) :restate))))
      (let [other (open-seat! h "clerk-elsewhere")]
        (is (= 404 (:status (req h :get (str "/api/seats/" other)
                                 {:headers as})))
            "one row, not the collection: another office is not there")))

    (testing "a restate moves every live grant with it — no new grant, nothing copied"
      (let [restated (restate! h seat
                               {:scope [{:kind "seat_ledger_line"
                                         :actions ["create"]}]})]
        (is (= 200 (:status restated)) (pr-str (json restated))))
      (is (= 200 (:status (req h :get "/api/seat_ledger_lines" {:headers as})))
          "the next request sees the new scope")
      (is (= 404 (:status (req h :get "/api/seat_pantries" {:headers as})))
          "…and not the old one")
      (let [g (json (req h :get (str "/api/grants/" gid) {:headers human}))]
        (is (= "accepted" (:state g)))
        (is (empty? (get-in g [:data :scope]))
            "the grant itself never moved: there is nothing on it to move")))))

;; ── case 6 · park and unpark, with no grant moved ───────────────────

(deftest park-makes-the-sitter-see-nothing-and-unpark-gives-it-back
  (let [{:keys [h eng]} (world)
        seat (open-seat! h "clerk-park")
        gid (sit! h (sitter "ari-park") "clerk-park" {})
        as (sitter "ari-park" {:grant gid})]
    (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as}))))

    (testing "parked: the seat serves nothing"
      (is (= 200 (:status (req h :post (str "/api/seats/" seat "/-/park")
                               {:headers human}))))
      (is (= 404 (:status (req h :get "/api/seat_pantries" {:headers as}))))
      (is (= 404 (:status (req h :get (str "/api/seats/" seat)
                               {:headers as})))
          "including the seat row itself — nothing means nothing")
      (testing "and a PARKED seat records no halt: the person chose this"
        (is (nil? (get-in (json (req h :get (str "/api/seats/" seat)
                                     {:headers human}))
                          [:data :halt]))
            "a halt on a parked seat would tell its approver what they did")
        (is (empty? (filter #(= :mark_halted (:action %))
                            (log-of eng :seat seat))))))

    (testing "unparked: the same grant serves again"
      (is (= 200 (:status (req h :post (str "/api/seats/" seat "/-/unpark")
                               {:headers human}))))
      (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as}))))
      (let [g (json (req h :get (str "/api/grants/" gid) {:headers human}))]
        (is (= "accepted" (:state g)) "no grant moved in either direction")
        (is (= #{:create :accept}
               (into #{} (map :action) (log-of eng :grant gid))))))))

;; ── case 9 · one full sitter, and substitutes without limit ─────────

(deftest a-seat-takes-one-full-sitter-and-any-number-of-substitutes
  (let [{:keys [h]} (world)
        _ (open-seat! h "clerk-one")
        _ (sit! h (sitter "ari-one") "clerk-one" {})]
    (testing "a second FULL grant is refused at approve, naming who holds it"
      (let [asked (ask! h (sitter "bo-one") {:task "Sit here too."
                                             :seat "clerk-one"})
            _ (is (= 201 (:status asked)) "the ask itself is fine — the wall is the verdict")
            refused (approve! h asked)]
        (is (= 409 (:status refused)))
        (is (= "seat-has-one-sitter" (:guard (json refused))))
        (is (str/includes? (str (:detail (json refused))) "ari-one"))
        (is (str/includes? (str (:detail (json refused))) "clerk-one"))))

    (testing "a SUBSTITUTE is not limited"
      (let [gid (sit! h (sitter "vera-one") "clerk-one" {:substitute true})]
        (is (some? gid)))
      (let [gid (sit! h (sitter "wren-one") "clerk-one" {:substitute true})]
        (is (some? gid))))))

;; ── case 10 · the seat's own ceiling, and the 24-hour one ───────────

(deftest a-seat-ask-runs-to-the-seats-ceiling-and-a-scope-ask-to-a-day
  (let [{:keys [h]} (world)]
    (open-seat! h "clerk-leash" {:standing_ttl_seconds 604800})
    (testing "a seat ask may run to the seat's standing leash"
      (let [ok (ask! h (sitter "ari-leash")
                     {:task "A week at the office."
                      :seat "clerk-leash"
                      :expires_at "2026-09-23T08:00:00Z"})]
        (is (= 201 (:status ok)) (pr-str (json ok)))))
    (testing "…and not past it, and the refusal says whose ceiling it is"
      (let [over (ask! h (sitter "bo-leash")
                       {:task "A fortnight at the office."
                        :seat "clerk-leash"
                        :expires_at "2026-10-01T08:00:00Z"})]
        (is (= 409 (:status over)))
        (is (= "asks-are-short" (:guard (json over))))
        (is (str/includes? (str (:detail (json over))) "168 hours"))
        (is (str/includes? (str (:detail (json over))) "clerk-leash"))))
    (testing "a SCOPE ask keeps the 24-hour ceiling"
      (let [over (ask! h (sitter "cy-leash")
                       {:task "A week of pantry."
                        :scope [{:kind "seat_pantry" :actions ["create"]}]
                        :expires_at "2026-09-23T08:00:00Z"})]
        (is (= 409 (:status over)))
        (is (str/includes? (str (:detail (json over))) "at most 24 hours"))))))

;; ── case 11 · the model list, at the ask and at every request ───────

(deftest a-seat-held-for-one-model-refuses-another-at-the-ask-and-at-the-door
  (let [{:keys [h eng]} (world)
        held (add-model! h "held-model")
        _ (add-model! h "other-model")
        seat (open-seat! h "clerk-model" {:held_for [held]})]

    (testing "the ask from a session declaring another model is refused,
              and the refusal names the list"
      (let [wrong (ask! h (sitter "ari-model" {:model "other-model"})
                        {:task "Sit on the wrong rung." :seat "clerk-model"})]
        (is (= 409 (:status wrong)))
        (is (= "model-may-sit" (:guard (json wrong))))
        (is (str/includes? (str (:detail (json wrong))) "held-model")
            "the list, by the identifier a harness declares")
        (is (str/includes? (str (:detail (json wrong))) "other-model"))))

    (testing "the ask from a session declaring the held model is served"
      (let [gid (sit! h (sitter "ari-model" {:model "held-model"}) "clerk-model" {})
            as (sitter "ari-model" {:grant gid :model "held-model"})]
        (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as}))))

        (testing "and a renew that changes the model makes the NEXT request
                  see nothing — the same grant, a different session"
          (let [drifted (sitter "ari-model" {:grant gid :model "other-model"})]
            (is (= 404 (:status (req h :get "/api/seat_pantries"
                                     {:headers drifted}))))
            (is (= "model_not_held"
                   (get-in (json (req h :get (str "/api/seats/" seat)
                                      {:headers human}))
                           [:data :halt :reason]))
                "the wall reached a person (R-7.7)"))
          (testing "…and the held model's next request lifts it again"
            (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as}))))
            (is (nil? (get-in (json (req h :get (str "/api/seats/" seat)
                                         {:headers human}))
                              [:data :halt])))))))))

;; ── case 24 · the halt: written once, lifted once ───────────────────

(deftest the-first-request-at-a-wall-writes-the-halt-and-the-first-past-it-clears
  (let [{:keys [h eng]} (world)
        held (add-model! h "wall-held")
        _ (add-model! h "wall-other")
        seat (open-seat! h "clerk-wall" {:held_for [held]})
        gid (sit! h (sitter "ari-wall" {:model "wall-held"}) "clerk-wall" {})
        walled (sitter "ari-wall" {:grant gid :model "wall-other"})
        passing (sitter "ari-wall" {:grant gid :model "wall-held"})
        halts (fn [] (filter #(= :mark_halted (:action %))
                             (log-of eng :seat seat)))
        clears (fn [] (filter #(= :clear_halt (:action %))
                              (log-of eng :seat seat)))]
    (testing "the first request at the wall writes the halt, with the reason"
      (req h :get "/api/seat_pantries" {:headers walled})
      (let [row (json (req h :get (str "/api/seats/" seat) {:headers human}))]
        (is (= "model_not_held" (get-in row [:data :halt :reason])))
        (is (some? (get-in row [:data :halt :since])))
        (is (str/includes? (str (get-in row [:data :halt :detail])) "wall-other")
            "the detail says what the session declared"))
      (is (= 1 (count (halts))))
      (is (= "system" (get-in (first (halts)) [:actor :type]))
          "the engine's own hand, logged"))

    (testing "a second request at the SAME wall writes nothing more"
      (req h :get "/api/seat_pantries" {:headers walled})
      (req h :get "/api/seat_vaults" {:headers walled})
      (is (= 1 (count (halts)))
          "an alert that shouted once per request would be an alert nobody reads"))

    (testing "the first request that passes clears it"
      (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers passing}))))
      (is (nil? (get-in (json (req h :get (str "/api/seats/" seat)
                                   {:headers human}))
                        [:data :halt])))
      (is (= 1 (count (clears))))
      (testing "and a second passing request writes nothing either"
        (req h :get "/api/seat_pantries" {:headers passing})
        (is (= 1 (count (clears))))))))

;; ── case 15 · the week's fuel ───────────────────────────────────────

(defn- sitting!
  "A closed sitting for this seat, costed at the model's prices — the
  shape a week's spend is summed from."
  [h who seat-id model-id gid tokens]
  (let [made (req h :post "/api/sittings"
                  {:headers who
                   :body {:seat seat-id :model model-id :grant gid}})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        sid (id-of made)
        closed (req h :post (str "/api/sittings/" sid "/-/close")
                    {:headers who
                     :body {:input_tokens tokens :output_tokens 0
                            :cache_read_tokens 0 :cache_write_tokens 0
                            :turns 1 :note "A wake, for the ledger."}})]
    (assert (= 200 (:status closed)) (pr-str (json closed)))
    sid))

(deftest a-seat-that-has-spent-its-week-serves-nothing
  (let [{:keys [h clock eng]} (world)
        ;; five dollars a million input tokens, so one million tokens
        ;; is exactly the seat's weekly budget
        model (add-model! h "fuel-model" {:price_input_per_mtok 5})
        seat (open-seat! h "clerk-fuel" {:budget_usd_per_week 5})
        gid (sit! h (sitter "ari-fuel") "clerk-fuel" {:expires_at "2026-09-23T08:00:00Z"})
        as (sitter "ari-fuel" {:grant gid})]

    (testing "a sitting closed EIGHT DAYS ago does not count against the week"
      (reset! clock (.minusSeconds t0 (* 8 86400)))
      (sitting! h as seat model gid 1000000)
      (reset! clock t0)
      (is (= 200 (:status (req h :get "/api/seat_pantries" {:headers as})))
          "the window rolled past it"))

    (testing "a sitting inside the window that reaches the budget walls the seat"
      (sitting! h as seat model gid 1000000)
      (is (= 404 (:status (req h :get "/api/seat_pantries" {:headers as}))))
      (let [row (json (req h :get (str "/api/seats/" seat) {:headers human}))]
        (is (= "budget_reached" (get-in row [:data :halt :reason])))
        (is (str/includes? (str (get-in row [:data :halt :detail])) "5")
            "the detail says what was spent against what")))

    (testing "and the wall lifts on its own as the window rolls"
      ;; eight days on, the sitting that spent the week is outside it.
      ;; The leash is not — it asked for six days — so the sitter asks
      ;; again, which is the ordinary way back and costs one tap
      (reset! clock (.plusSeconds t0 (* 8 86400)))
      (let [fresh (sit! h (sitter "ari-fuel") "clerk-fuel" {})]
        (is (= 200 (:status (req h :get "/api/seat_pantries"
                                 {:headers (sitter "ari-fuel" {:grant fresh})})))
            "nothing was spent in THIS week")
        (is (nil? (get-in (json (req h :get (str "/api/seats/" seat)
                                     {:headers human}))
                          [:data :halt]))
            "and the halt lifted itself on the first request that passed")))))

;; ── case 18 · the two counters ──────────────────────────────────────

(deftest a-sitting-counts-the-transitions-and-the-refusals-under-its-grant
  (let [{:keys [h eng]} (world)
        model (add-model! h "count-model")
        seat (open-seat! h "clerk-count")
        gid (sit! h (sitter "ari-count") "clerk-count" {})
        as (sitter "ari-count" {:grant gid})
        ;; a SECOND leash for the same agent, over the same kind —
        ;; what a transition under another grant must not touch
        other (let [asked (ask! h (sitter "ari-count")
                                {:task "A pantry of my own."
                                 :scope [{:kind "seat_pantry"
                                          :actions ["create" "finish"]}]})]
                (get-in (json (approve! h asked)) [:data :grant_id]))
        made (req h :post "/api/sittings"
                  {:headers as :body {:seat seat :model model
                                      :grant gid}})
        sid (id-of made)
        counts (fn []
                 (let [d (:data (json (req h :get (str "/api/sittings/" sid)
                                           {:headers human})))]
                   [(:transitions d) (:refusals d)]))]
    (is (= 201 (:status made)) (pr-str (json made)))
    (is (= [0 0] (counts)) "a sitting does not count its own birth")

    (testing "a committed transition under the grant counts one"
      (let [row (req h :post "/api/seat_pantries"
                     {:headers as :body {:name "flour"}})]
        (is (= 201 (:status row)))
        (is (= [1 0] (counts)) "the birth of a row IS a committed transition")
        (is (= 200 (:status (req h :post (str "/api/seat_pantries/" (id-of row)
                                              "/-/finish")
                                 {:headers as}))))
        (is (= [2 0] (counts)))))

    (testing "a 409 under the grant counts one refusal"
      (let [v (req h :post "/api/seat_vaults" {:headers as :body {:name "safe"}})
            _ (is (= 201 (:status v)))
            refused (req h :post (str "/api/seat_vaults/" (id-of v) "/-/seal")
                         {:headers as})]
        (is (= 409 (:status refused)))
        (is (= [3 1] (counts))
            "the create counted a transition; the seal counted a refusal")))

    (testing "a transition under ANOTHER grant adds nothing"
      (let [row (req h :post "/api/seat_pantries"
                     {:headers (sitter "ari-count" {:grant other})
                      :body {:name "sugar"}})]
        (is (= 201 (:status row)))
        (is (= [3 1] (counts)))))

    (testing "and a request with no open sitting counts nothing"
      (is (= 200 (:status (req h :post (str "/api/sittings/" sid "/-/close")
                               {:headers as
                                :body {:input_tokens 10 :output_tokens 10
                                       :cache_read_tokens 0
                                       :cache_write_tokens 0
                                       :turns 1 :note "Done."}}))))
      (req h :post "/api/seat_pantries" {:headers as :body {:name "salt"}})
      (req h :post "/api/seat_vaults" {:headers as :body {:name "chest"}})
      (is (= [3 1] (counts)) "the counts are frozen at the close"))))
