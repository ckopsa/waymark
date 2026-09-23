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
            [waymark10.server.store :as store]
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

;; ── the world ───────────────────────────────────────────────────────

(def ^:private t0 (Instant/parse "2026-09-23T08:00:00Z"))

(defn- world []
  (let [clock (atom t0)
        eng (dev/scratch! [ticket] {:now-fn (fn [] @clock)})]
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
  [{:kind "seat" :actions ["create" "restate" "park" "unpark" "retire" "merge"]}
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
    (testing "and it waits in the person's feed"
      (let [feed (json (req h :get "/api/-/feed" {:headers person}))]
        (is (some #(= (str "decide/seat/" child) (str (:card_id %)))
                  (:cards feed))
            (pr-str (mapv :card_id (:cards feed))))))))

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
