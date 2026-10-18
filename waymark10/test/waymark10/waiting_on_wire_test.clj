(ns waymark10.waiting-on-wire-test
  "The `waiting_on` block on the wire (ticket daa37bce): what
  factory10.waiting-on-test judges as the pure `block`, read here
  through a booted engine — the real row handler, its lean
  `?depth=summary` read, and waymark_get with `return: summary`.

  What these tests hold:

  • a row read carries the block, and so does waymark_get's summary,
    which projects the same envelope; the lean depth=summary read does
    not (router/with-waiting-on: it rides the full row read alone);
  • `since.at` and `since.by` are the transition that ENTERED the
    state, read from the log, not a later self-loop;
  • a hold field the caller's grant hides is in neither `held_by` nor
    the verdict;
  • an engine that serves no seat kind serves the envelope without it.

  Memory storage, the real routes through the in-process door, no
  database — the mcp_return_test arrangement."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.conformance :as conf]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(defhandler jot [row inp _ctx]
  (assoc-in row [:data :note] (:note inp)))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private errand
  "A ticket-shaped kind: a door in, a self-loop that writes without
  moving, a door out a seat holds, and one hold field."
  (r/resource
   {:kind :wo_errand
    :plural "wo_errands"
    :states [:draft :open :done]
    :initial :draft
    :terminal #{:done}
    :expected-age {:open 3600}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:note {:optional true} [:maybe [:string {:max 200}]]]
             [:held_reason {:optional true} [:maybe [:string {:max 200}]]]]
    :actions {:groom {:from #{:draft} :to :open :safety quiet}
              :jot {:from #{:open} :to :open
                    :input [:map [:note [:string {:max 200}]]]
                    :safety {:idempotent true :reversible true :confirm false}
                    :handler jot}
              :finish {:from #{:open} :to :done :safety quiet}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private opts {:principal elena})

(defn- boot []
  (engine/engine {:storage (memory/storage) :resources [errand]}))

(defn- seat! [eng]
  (inv/create! eng :seat
               {:name "wo-coder"
                :charter "Finish the errands the house grooms."
                :scope [{:kind "wo_errand" :actions ["finish"]}]
                :standing_ttl_seconds 604800
                :cadence_seconds 3600
                :budget_usd_per_week 5M
                :sitting_budget_tokens 60000}
               opts))

(defn- open-errand!
  "An errand groomed open, then jotted on in place: its log's newest
  transition is the self-loop, the one before it the groom."
  [eng]
  (let [id (str (:id (:row (inv/create! eng :wo_errand
                                        {:title "fetch the part"
                                         :held_reason "the vendor"}
                                        opts))))]
    (inv/invoke! eng :wo_errand id :groom nil opts)
    (Thread/sleep 10)
    (inv/invoke! eng :wo_errand id :jot {:note "called twice"} opts)
    id))

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :wo_errand :resource-id id}
                         {:newest-first true}))))

(defn- by-action [log action]
  (some #(when (= action (some-> (:action %) name)) %) log))

(defn- route-get [eng uri query-string]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method :get :uri uri
                       :headers {"x-waymark-principal" "elena"}}
                query-string (assoc :query-string query-string)))]
    (assoc resp :parsed (some-> (:body resp) wire/read-json))))

(defn- tool
  ([eng tool-name args] (tool eng {:principal elena} tool-name args))
  ([eng session tool-name args]
   (some-> (mcp/call-tool eng (mcp/door eng) session tool-name args)
           (get-in [:content 0 :text])
           wire/read-json)))

;; ── the row read carries it ─────────────────────────────────────────

(deftest a-row-read-carries-what-it-waits-on
  (let [eng (boot)
        _ (seat! eng)
        id (open-errand! eng)
        log (log-of eng id)
        groomed (by-action log "groom")
        jotted (by-action log "jot")
        uri (str "/api/wo_errands/" id)
        {:keys [status parsed headers]} (route-get eng uri nil)
        w (:waiting_on parsed)]
    (is (= 200 status))
    (is (map? w) "the full row read carries the block")

    (testing "since is the transition that entered the state"
      (is (some? groomed))
      (is (some? jotted))
      (is (= "groom" (get-in w [:since :by])) "not the later self-loop")
      (is (= (str (:at groomed)) (get-in w [:since :at])))
      (is (not= (str (:at jotted)) (get-in w [:since :at])))
      (is (= 3600 (get-in w [:since :expected_seconds]))))

    (testing "next names the door out and the seat that holds it"
      (is (= "open" (:state w)))
      (let [finish (some #(when (= "finish" (:door %)) %) (:next w))]
        (is (= "seat" (:held_by finish)))
        (is (some #{"wo-coder"} (map :seat (:seats finish))))))

    (testing "the hold field it can see is named"
      (is (= {:held_reason "the vendor"} (:held_by w)))
      (is (str/includes? (:verdict w) "held_reason")))

    (testing "the envelope still meets the conformance walk's shape"
      (is (empty? (conf/envelope-violations
                   parsed {:kind :wo_errand :state :open
                           :etag-header (get headers "ETag")
                           :law? true}))))

    (testing "the lean depth=summary read does not carry it"
      (let [{:keys [status parsed]} (route-get eng uri "depth=summary")]
        (is (= 200 status))
        (is (= "open" (:state parsed)))
        (is (not (contains? parsed :waiting_on)))))

    (testing "waymark_get's summary carries the same block"
      (let [s (tool eng "waymark_get" {:kind "wo_errand" :id id :return "summary"})]
        (is (= id (:id s)))
        (is (= (dissoc w :verdict) (dissoc (:waiting_on s) :verdict)))
        (is (= "groom" (get-in s [:waiting_on :since :by])))
        (is (= (str (:at groomed)) (get-in s [:waiting_on :since :at])))))))

;; ── concealment is inherited ────────────────────────────────────────

(deftest a-hidden-hold-field-is-not-named
  (let [eng (boot)
        _ (seat! eng)
        id (open-errand! eng)
        ;; the grant's own closure shapes, held_reason redacted
        narrow {:kind? (constantly true)
                :row? (constantly true)
                :action? (constantly true)
                :arg? (constantly true)
                :field? (fn [_kind f] (not= "held_reason" (name f)))}
        session {:principal elena :visibility narrow}]
    (doseq [args [{:kind "wo_errand" :id id}
                  {:kind "wo_errand" :id id :return "summary"}]]
      (testing (str "waymark_get " (or (:return args) "envelope"))
        (let [d (tool eng session "waymark_get" args)
              w (:waiting_on d)]
          (is (not (contains? (:data d) :held_reason)) "the data conceals it")
          (is (map? w) "the block still rides")
          (is (= "groom" (get-in w [:since :by])))
          (is (not (contains? w :held_by)) "held_by names no hidden field")
          (is (not (str/includes? (:verdict w) "held_reason"))
              "nor does the verdict")
          (is (not (str/includes? (:verdict w) "the vendor"))))))))

;; ── no seat kind, no block ──────────────────────────────────────────

(deftest an-engine-with-no-seat-kind-serves-no-block
  (let [eng (boot)
        id (open-errand! eng)]
    ;; the seat is core's and always enrolled, so the engine is booted
    ;; whole and then made to serve no seat kind
    (swap! (:registry eng) update :kinds dissoc :seat)
    (is (not (contains? (inv/resources eng) :seat)))
    (let [{:keys [status parsed]} (route-get eng (str "/api/wo_errands/" id) nil)]
      (is (= 200 status))
      (is (= "open" (:state parsed)))
      (is (contains? parsed :actions) "the envelope is served whole")
      (is (not (contains? parsed :waiting_on))))))
