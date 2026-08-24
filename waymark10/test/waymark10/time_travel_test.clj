(ns waymark10.time-travel-test
  "Time travel tiers 1-2 (waymark-442.4, docs/spec-time-travel.md and
  its 2026-08-23 amendments).

  This suite boots TWICE over one storage, the law sweep's own shape
  and for the opposite reason. The sweep needs two laws in hand at the
  same instant; time travel needs one law that is GONE — a revision
  the engine has promoted past, whose rows have all restamped, whose
  `:judgment-laws` entry `sweep!` therefore dissoc'd. That is exactly
  the revision the amendment says the slot deliberately cannot give,
  and the only way to reach it is the on-demand loader.

  The severity of one guard moves between the boots, name and position
  held fixed. That choice is not decoration: `judgment/rebuild-guards`
  substitutes positionally AND by name, so a renamed guard would fall
  back to the resident one and prove nothing. Severity is a field the
  stored tree really carries, so a transition stamped revision 1
  answering `warning` while the resident code says `refuse` is proof —
  the only proof available at this altitude — that the law of the day
  served."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.definitions :as definitions]
            [waymark10.server.engine :as engine]
            [waymark10.server.history :as history]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(defn- ledger
  "One kind, two laws. `severity` is what moves; `:retain {:judgment
  true}` makes the written half of the record available so the two
  answers — derived basis, stored evidence — can be told apart on the
  same document."
  [severity]
  (let [enough (g/expr {:name :balance-is-positive
                        :severity severity
                        :when '(< 0 (data :balance))
                        :explain "Nothing left: {left}."
                        :vars {:left '(data :balance)
                               :note '(data :note)}})]
    (r/resource
     {:kind :tt_ledger
      :plural "tt_ledgers"
      :states [:open :closed]
      :initial :open
      :terminal #{:closed}
      :summary "{data.name} · {state}"
      :retain {:judgment true}
      :schema [:map
               [:name [:string {:min 1 :max 40}]]
               [:balance :int]
               [:note {:optional true} [:maybe [:string {:max 60}]]]]
      :actions
      {:touch {:from #{:open} :to :open
               :guards [enough]
               :safety {:idempotent true :reversible true :confirm false}}
       :close {:from #{:open} :to :closed
               :guards [enough]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Closed is history."}}}})))

;; the control: the same shape, retention never declared
(def ^:private daybook
  (r/resource
   {:kind :tt_daybook
    :plural "tt_daybooks"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions
    {:close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed is history."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private opts {:principal elena})

(defn- boot [st resources]
  (engine/engine {:storage st :resources resources :deploy-mode :promote}))

(defn- get-json
  "One GET at the real door, as Elena — or as whoever the headers name."
  [eng uri & [qs headers]]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method :get :uri uri
                       :headers (or headers {"x-waymark-principal" "elena"})}
                qs (assoc :query-string qs)))]
    [(:status resp) (some-> (:body resp) wire/read-json) (:headers resp)]))

(defn- by-action [doc a]
  (first (filter #(= a (str (:action %))) (get-in doc [:data :transitions]))))

;; ── tier 1: the log, read ───────────────────────────────────────────

(deftest the-history-route-serves-the-log
  (let [st (memory/storage)
        eng (boot st [(ledger :warning) daybook])
        row (:row (inv/create! eng :tt_ledger
                               {:name "the blue one" :balance 4
                                :note "kept in the drawer"} opts))
        id (:id row)
        _ (inv/invoke! eng :tt_ledger id :touch nil opts)
        _ (inv/invoke! eng :tt_ledger id :close nil opts)
        [status doc] (get-json eng (str "/api/tt_ledgers/" id "/-/history"))]

    (testing "the row's transitions, newest first, as a first-class read"
      (is (= 200 status))
      (is (= "history" (:kind doc)))
      (is (= (str "/api/tt_ledgers/" id "/-/history") (:self doc)))
      (is (= ["close" "touch" "create"]
             (mapv #(str (:action %)) (get-in doc [:data :transitions]))))
      (is (= 3 (get-in doc [:data :scanned])))
      (is (false? (get-in doc [:data :truncated]))))

    (testing "every transition carries what moved and who moved it"
      (let [t (by-action doc "close")]
        (is (= "open" (str (:from t))))
        (is (= "closed" (str (:to t))))
        (is (= "elena" (get-in t [:actor :id])))
        ;; the summary is not reconstructed — invoke rendered it at
        ;; write time and the log kept the sentence
        (is (= "the blue one · Closed" (:summary t)))))

    (testing "the honesty clauses are met before the evidence"
      (is (seq (get-in doc [:data :notes])))
      (is (some #(re-find #"tier 3" (str %)) (get-in doc [:data :notes]))))

    (testing "a row this engine never held has no history"
      (is (= 404 (first (get-json eng "/api/tt_ledgers/nope/-/history")))))

    (testing "limit bounds the page and the notes say the page is bounded"
      (let [[_ page] (get-json eng (str "/api/tt_ledgers/" id "/-/history")
                               "limit=1")]
        (is (= 1 (count (get-in page [:data :transitions]))))
        (is (true? (get-in page [:data :truncated])))
        (is (some #(re-find #"cap" (str %)) (get-in page [:data :notes])))))

    (testing "a limit that is not a positive integer is a 422, never a guess"
      (is (= 422 (first (get-json eng (str "/api/tt_ledgers/" id "/-/history")
                                  "limit=0")))))))

;; ── the two answers, told apart ─────────────────────────────────────

(deftest evidence-says-which-answer-it-is-giving
  (let [st (memory/storage)
        eng (boot st [(ledger :warning) daybook])
        led (:id (:row (inv/create! eng :tt_ledger
                                    {:name "kept" :balance 2} opts)))
        day (:id (:row (inv/create! eng :tt_daybook {:name "plain"} opts)))
        _ (inv/invoke! eng :tt_ledger led :touch nil opts)
        [_ ldoc] (get-json eng (str "/api/tt_ledgers/" led "/-/history"))
        [_ ddoc] (get-json eng (str "/api/tt_daybooks/" day "/-/history"))]

    (testing "a retaining kind answers with the guards' own reading"
      (let [t (by-action ldoc "touch")]
        (is (= "recorded" (str (:evidence t))))
        (is (= {:left 2 :note nil} (get-in t [:judgment :guards 0 :read])))))

    (testing "a kind that declared no retention says so rather than
              answering with an empty object"
      (let [t (by-action ddoc "create")]
        (is (= "not_retained" (str (:evidence t))))
        (is (nil? (:judgment t)))
        ;; …and the derived half is still there, which is the whole
        ;; point of it being derived
        (is (some? (:basis t)))))

    (testing "the notes name the retention posture the kind actually took"
      (is (some #(re-find #":retain" (str %)) (get-in ddoc [:data :notes])))
      (is (not-any? #(re-find #":retain" (str %))
                    (get-in ldoc [:data :notes]))))))

;; ── tier 2: the law of the day, from a revision nobody serves ───────

(deftest an-old-revision-is-judged-by-its-own-law
  (let [st (memory/storage)
        eng1 (boot st [(ledger :warning) daybook])
        id (:id (:row (inv/create! eng1 :tt_ledger
                                   {:name "the blue one" :balance 4} opts)))
        _ (inv/invoke! eng1 :tt_ledger id :touch nil opts)
        rev1 (definitions/current-law eng1 :tt_ledger)

        ;; the second boot IS the new law: same guard, same position,
        ;; same name, one field moved. The row restamps to it
        ;; (:adoption :immediate), which is what empties revision 1 and
        ;; makes sweep! drop its :judgment-laws entry — the premise
        eng2 (boot st [(ledger :refuse) daybook])
        rev2 (definitions/current-law eng2 :tt_ledger)
        ;; a DIFFERENT action, because `touch` twice with the same
        ;; input is invoke/natural-replay and writes no transition at
        ;; all — the same guard judges both
        _ (inv/invoke! eng2 :tt_ledger id :close nil opts)
        rdef2 (get (inv/resources eng2) :tt_ledger)
        [_ doc] (get-json eng2 (str "/api/tt_ledgers/" id "/-/history"))
        olds (filter #(= rev1 (:law_revision %))
                     (get-in doc [:data :transitions]))
        news (filter #(= rev2 (:law_revision %))
                     (get-in doc [:data :transitions]))]

    (testing "the promote really did move the law"
      (is (= 1 rev1))
      (is (= 2 rev2)))

    (testing "revision 1 is exactly the revision :judgment-laws cannot give"
      (is (nil? (get (:judgment-laws rdef2) rev1))
          (str "the whole premise: the rows restamped, sweep! superseded the"
               " old law and dropped its entry — an arbitrary historical"
               " revision is NOT in hand"))
      (is (some? (get-in rdef2 [:law-ids rev1]))
          "…but the definition row's id still is, and that is the handle"))

    (testing "the old transitions are judged by the old law"
      (is (seq olds))
      (let [t (first (filter #(= "touch" (str (:action %))) olds))]
        (is (= "stored" (str (get-in t [:basis :law]))))
        (is (= "warning" (str (get-in t [:basis :guards 0 :severity])))
            "the stored tree's severity, not the resident declaration's"))
      ;; a create's basis is :resident whatever the revision — the
      ;; create path has never had an :actions entry for the overlay
      ;; to resolve through. waymark-2fq, inherited from .5 and named
      ;; here rather than papered over
      (is (= "resident"
             (str (get-in (first (filter #(= "create" (str (:action %))) olds))
                          [:basis :law])))))

    (testing "…and the new one by the new law, on the same document"
      (is (seq news))
      (let [t (first (filter #(= "close" (str (:action %))) news))]
        (is (= "stored" (str (get-in t [:basis :law]))))
        (is (= "refuse" (str (get-in t [:basis :guards 0 :severity]))))))

    (testing "the loader caches what it read, on the registry rather than
              the rdef — install! throws the rdef's own cache away"
      (is (some? (get-in @(:registry eng2) [:law-fingerprints :tt_ledger rev1])))
      (is (= (get-in @(:registry eng2) [:law-fingerprints :tt_ledger rev1])
             (definitions/stored-fingerprint eng2 :tt_ledger rev1))))

    (testing "a revision this engine has no definition row for is named as
              unrecoverable, never as resident"
      (let [[rd' source] (history/law-of eng2 rdef2 998)]
        (is (= :unrecoverable source))
        (is (= rdef2 rd')))
      (let [[_ source] (history/law-of eng2 rdef2 nil)]
        (is (= :resident source) "the pre-law horizon, as judgment.clj serves it")))))

;; ── the disclosure lock ─────────────────────────────────────────────

(deftest evidence-rides-the-grant
  (let [st (memory/storage)
        eng (boot st [(ledger :warning) daybook])
        rdef (get (inv/resources eng) :tt_ledger)
        id (:id (:row (inv/create! eng :tt_ledger
                                   {:name "kept" :balance 2
                                    :note "in the drawer"} opts)))
        _ (inv/invoke! eng :tt_ledger id :touch nil opts)
        ;; the grant's own closure shape: (fn [kind field] → bool)
        narrow {:field? (fn [_kind f] (not= "note" (name f)))}
        whole (history/row-history eng rdef id {} nil)
        scoped (history/row-history eng rdef id {} narrow)
        guard-of (fn [doc]
                   (->> (get-in doc [:data :transitions])
                        (filter #(= :touch (:action %)))
                        first :judgment :guards first))]

    (testing "the system door sees the row whole"
      (is (= {:left 2 :note "in the drawer"} (:read (guard-of whole))))
      (is (nil? (:withheld (guard-of whole)))))

    (testing "a value whose form read a concealed field is WITHHELD and named"
      (is (= {:left 2} (:read (guard-of scoped))))
      (is (= [:note] (:withheld (guard-of scoped))))
      (is (some #(re-find #"withheld" (str %)) (get-in scoped [:data :notes]))))

    (testing "the var NAMES stay — a guard's :vars are law, not data"
      (is (= "balance-is-positive" (str (:name (guard-of scoped))))))

    (testing "a row the grant conceals has no history at all"
      (let [resp ((engine/handler eng)
                  {:request-method :get
                   :uri (str "/api/tt_ledgers/" id "/-/history")
                   :headers {"x-waymark-principal" "elena"}
                   :waymark10/visibility {:kind? (constantly true)
                                          :row? (constantly false)}})]
        (is (= 404 (:status resp)))))))

;; ── tier 1: as-of ───────────────────────────────────────────────────

(deftest as-of-answers-from-the-log
  (let [st (memory/storage)
        eng (boot st [(ledger :warning) daybook])
        id (:id (:row (inv/create! eng :tt_ledger
                                   {:name "the blue one" :balance 4} opts)))
        mid (do (Thread/sleep 5) (java.time.Instant/now))
        _ (Thread/sleep 5)
        _ (inv/invoke! eng :tt_ledger id :close nil opts)
        [status doc headers] (get-json eng (str "/api/tt_ledgers/" id)
                                       (str "as-of=" mid))]

    (testing "the state, the law and the summary as they stood"
      (is (= 200 status))
      (is (= "as_of" (:kind doc)))
      (is (true? (get-in doc [:data :existed])))
      (is (= "open" (str (get-in doc [:data :state]))))
      (is (= 1 (get-in doc [:data :law_revision])))
      (is (= "the blue one · Open" (get-in doc [:data :summary]))))

    (testing "…and why it was there, judged by the law of that day"
      (is (= "create" (str (get-in doc [:data :put_there_by :action]))))
      (is (some? (get-in doc [:data :put_there_by :basis]))))

    (testing "the X-As-Of marker, so nothing mistakes it for live"
      (is (= (str mid) (get headers "X-As-Of"))))

    (testing "it is NOT an envelope: no data, no actions, no ETag"
      (is (nil? (:data (:data doc))))
      (is (nil? (:actions doc)))
      (is (nil? (get headers "ETag"))))

    (testing "before the row existed is an answer, not an absence"
      (let [[_ before] (get-json eng (str "/api/tt_ledgers/" id)
                                 "as-of=2020-01-01T00:00:00Z")]
        (is (false? (get-in before [:data :existed])))
        (is (nil? (get-in before [:data :state])))))

    (testing "an unparseable instant is a 422 that names the spelling —
              never a silent fall back to now"
      (is (= 422 (first (get-json eng (str "/api/tt_ledgers/" id)
                                  "as-of=last tuesday")))))

    (testing "the collection answers the rows that existed then, in the
              states they held"
      (let [[cs cdoc] (get-json eng "/api/tt_ledgers" (str "as-of=" mid))]
        (is (= 200 cs))
        (is (= "as_of_collection" (:kind cdoc)))
        (is (true? (get-in cdoc [:data :complete])))
        (is (= [{:id id :state "open"}]
               (mapv #(hash-map :id (:id %) :state (str (:state %)))
                     (get-in cdoc [:data :items]))))))

    (testing "an as-of collection takes no filters — the log carries state,
              not data, so a data filter could not be answered"
      (is (= 422 (first (get-json eng "/api/tt_ledgers"
                                  (str "as-of=" mid "&name=the%20blue%20one"))))))

    (testing "the live read is untouched"
      (let [[_ env] (get-json eng (str "/api/tt_ledgers/" id))]
        (is (= "closed" (str (:state env))))))))

;; ── waymark_history rides the route (waymark-zp5) ───────────────────

(deftest the-mcp-tool-is-a-pass-through
  (let [st (memory/storage)
        eng (boot st [(ledger :warning) daybook])
        id (:id (:row (inv/create! eng :tt_ledger
                                   {:name "kept" :balance 2} opts)))
        _ (inv/invoke! eng :tt_ledger id :touch nil opts)
        out (mcp/call-tool eng (mcp/door eng) {:principal elena}
                           "waymark_history" {:kind "tt_ledger" :id id})
        doc (wire/read-json (get-in out [:content 0 :text]))]

    (testing "the tool answers with the ROUTE's document, projection and all"
      (is (not (:isError out)))
      (is (= "history" (:kind doc)))
      (is (= (str "/api/tt_ledgers/" id "/-/history") (:self doc)))
      (is (seq (get-in doc [:data :notes])))
      (is (some? (:basis (by-action doc "touch"))))
      (is (some? (:judgment (by-action doc "touch")))))

    (testing "a row the tool's caller cannot see 404s at the route, not here"
      (let [gone (mcp/call-tool eng (mcp/door eng) {:principal elena}
                                "waymark_history"
                                {:kind "tt_ledger" :id "nope"})]
        (is (true? (:isError gone)))))))
