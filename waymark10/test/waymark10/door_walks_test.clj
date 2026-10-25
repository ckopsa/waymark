(ns waymark10.door-walks-test
  "A door receiver that walks a named door on the row a field or a match
  of the moved row names, and skips a row that already moved
  (docs/spec-change.md §4.3, R-C.11 and R-C.13; docs/spec-events.md
  E-3.3). Real Postgres."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private walk-source
  (r/resource
   {:kind :walk_source :plural "walk_sources" :summary "{data.title} · {state}"
    :states [:open :shipped] :initial :open :terminal #{:shipped}
    :schema [:map
             [:title {:examples ["Ship the parcel"]
                      :x-display {:label "What it is" :help "One line naming the work."}}
              [:string {:min 1 :max 80}]]
             [:target {:optional true
                       :examples ["walk_target:0b6f1c1e-0000-4000-8000-000000000000"]
                       :x-display {:label "The row it names"
                                   :help "The id or the address of the row a walk moves."}}
              [:maybe [:string {:min 1 :max 120}]]]]
    :filterable {:state #{:eq :in} :title #{:eq}} :default-filters {:state "open"}
    :actions {:ship {:from #{:open} :to :shipped :display {:label "Ship" :order 1}
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Shipped is shipped."}}}}))

(def ^:private walk-target
  (r/resource
   {:kind :walk_target :plural "walk_targets" :summary "{data.title} · {state}"
    :states [:waiting :done :cancelled] :initial :waiting :terminal #{:done :cancelled}
    :schema [:map
             [:title {:examples ["Receive the parcel"]
                      :x-display {:label "What it is" :help "One line naming the work."}}
              [:string {:min 1 :max 80}]]
             [:source {:optional true
                       :examples ["0b6f1c1e-0000-4000-8000-000000000000"]
                       :x-display {:label "Its source" :help "The source row this one waits on."}}
              [:maybe [:string {:min 1 :max 64}]]]
             [:sources {:optional true
                        :examples [["0b6f1c1e-0000-4000-8000-000000000000"]]
                        :x-display {:label "Its sources" :help "The source rows this one waits on."}}
              [:maybe [:vector [:string {:min 1 :max 64}]]]]]
    :filterable {:state #{:eq :in}} :default-filters {:state "waiting"}
    :actions {:finish {:from #{:waiting} :to :done :display {:label "Finish" :order 1}
                       :input [:map [:note {:optional true
                                            :x-display {:label "Note" :help "A short word on how it ended."}}
                                     [:maybe [:string {:min 1 :max 12}]]]]
                       :record true
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Done is done."}}
              :cancel {:from #{:waiting} :to :cancelled :display {:label "Cancel" :order 2}
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Cancelled is cancelled."}}}}))

(def ^:private tables
  ["walk_sources" "walk_targets" "schedules" "seats" "models" "sittings" "definitions"
   "members" "roles" "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "invitations" "walkthroughs" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_drafts"])

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st (fn [tx] (doseq [table tables]
                                     (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources [walk-source walk-target]})]
          (f))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(defn- raw [kind id]
  (store/with-tx (:storage *eng*) #(store/load-row (:storage *eng*) % kind (str id) {})))

(defn- state-of [kind id] (some-> (:state (raw kind id)) name))

(defn- moves [kind id action]
  (filterv #(= action (keyword (name (:action %))))
           (store/with-tx (:storage *eng*)
             #(store/transitions (:storage *eng*) % {:kind kind :resource-id (str id)} {}))))

(defn- actor [m k] (let [a (:actor m)] (when (map? a) (str (get a k)))))

(defn- source! [data] (:id (:row (inv/create! *eng* :walk_source data {:principal elena}))))
(defn- target! [data] (:id (:row (inv/create! *eng* :walk_target data {:principal elena}))))
(defn- ship! [id] (inv/invoke! *eng* :walk_source (str id) :ship nil {:principal elena}))

(defn- walker!
  "A subscription on one source's ship that walks walk_target.finish:
  the title filter keeps each test's own subscription to its own source."
  [title receiver]
  (:row (inv/create! *eng* :subscription
                     {:kinds ["walk_source"] :actions ["ship"] :filter {:title title}
                      :receiver (merge {:type "door" :kind "walk_target" :door "finish"}
                                       receiver)}
                     {:principal elena})))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (if (:waymark10/problem (ex-data e)) (ex-data e) (throw e)))))

(deftest a-walk-reaches-the-row-a-field-names
  (let [target (target! {:title "parcel"})
        sub (walker! "walk-path" {:id_from "target" :input {:note "title"}})
        source (source! {:title "walk-path" :target (str "walk_target:" target)})]
    (ship! source)
    (doors/drain! *eng*)
    (is (= "done" (state-of :walk_target target)))
    (testing "the log shows both moves, the second naming the subscription"
      (is (= 1 (count (moves :walk_source source :ship))))
      (let [ms (moves :walk_target target :finish)
            m (first ms)]
        (is (= 1 (count ms)))
        (is (= (str (:id sub)) (actor m :subscription)))
        (is (= "elena" (actor m :id)) "the walk is its owner's hand")
        (is (= "walk-path" (some-> (or (get-in m [:inputs :note]) (get-in m [:inputs "note"])) str))
            "the input maps from the moved row")))
    (testing "an address of another kind names no row"
      (let [sub2 (walker! "walk-elsewhere" {:id_from "target"})
            other (target! {:title "elsewhere"})
            source2 (source! {:title "walk-elsewhere" :target (str "ticket:" other)})]
        (ship! source2)
        (doors/drain! *eng*)
        (is (= "waiting" (state-of :walk_target other)))
        (is (= 1 (get-in (raw :subscription (:id sub2)) [:data :skipped_count])))))))

(deftest a-walk-reaches-each-row-a-match-names
  (testing "a match on a field naming the moved row"
    (let [source (source! {:title "walk-match"})
          target (target! {:title "matched" :source (str source)})
          other (target! {:title "unmatched" :source "someone-else"})
          sub (walker! "walk-match" {:id_from "source=(self)"})]
      (ship! source)
      (doors/drain! *eng*)
      (is (= "done" (state-of :walk_target target)))
      (is (= "waiting" (state-of :walk_target other)))
      (is (= (str (:id sub)) (actor (first (moves :walk_target target :finish)) :subscription)))))
  (testing "a match on a list holding the moved row walks each row it names"
    (let [source (source! {:title "walk-array"})
          a (target! {:title "first" :sources [(str source) "x"]})
          b (target! {:title "second" :sources ["y" (str source)]})
          c (target! {:title "third" :sources ["z"]})
          sub (walker! "walk-array" {:id_from "sources=(contains (self))"})]
      (ship! source)
      (doors/drain! *eng*)
      (is (= ["done" "done" "waiting"] (mapv #(state-of :walk_target %) [a b c])))
      (doseq [id [a b]]
        (is (= (str (:id sub)) (actor (first (moves :walk_target id :finish)) :subscription)))))))

(deftest no-row-and-a-row-already-moved-are-skips
  (testing "no row named is a skip"
    (let [sub (walker! "walk-none" {:id_from "target"})
          source (source! {:title "walk-none"})]
      (ship! source)
      (doors/drain! *eng*)
      (let [s (raw :subscription (:id sub))]
        (is (= 1 (get-in s [:data :skipped_count])))
        (is (nil? (get-in s [:data :last_refusal]))))))
  (testing "a row already moved out of the door's states (wrong_state) is a skip"
    (let [target (target! {:title "early"})
          _ (inv/invoke! *eng* :walk_target (str target) :cancel nil {:principal elena})
          sub (walker! "walk-moved" {:id_from "target"})
          source (source! {:title "walk-moved" :target (str target)})]
      (ship! source)
      (doors/drain! *eng*)
      (let [s (raw :subscription (:id sub))]
        (is (= 1 (get-in s [:data :skipped_count])))
        (is (nil? (get-in s [:data :last_refusal])))
        (is (= "cancelled" (state-of :walk_target target)))
        (is (empty? (moves :walk_target target :finish))))))
  (testing "a row the same door already moved writes nothing again, and nothing is refused"
    (let [target (target! {:title "done early"})
          _ (inv/invoke! *eng* :walk_target (str target) :finish nil {:principal elena})
          sub (walker! "walk-again" {:id_from "target"})
          source (source! {:title "walk-again" :target (str target)})]
      (ship! source)
      (doors/drain! *eng*)
      (let [s (raw :subscription (:id sub))]
        (is (nil? (get-in s [:data :last_refusal])))
        (is (= 1 (count (moves :walk_target target :finish))))))))

(deftest any-other-refusal-is-kept-on-the-subscription
  (let [target (target! {:title "kept"})
        sub (walker! "walk-refused-at-length" {:id_from "target" :input {:note "title"}})
        source (source! {:title "walk-refused-at-length" :target (str target)})]
    (ship! source)
    (doors/drain! *eng*)
    (is (= "waiting" (state-of :walk_target target)) "the door's own schema refused the note")
    (let [s (raw :subscription (:id sub))]
      (is (string? (get-in s [:data :last_refusal])))
      (is (some? (get-in s [:data :last_refused_at])))
      (is (nil? (get-in s [:data :skipped_count])) "a kept refusal is not a skip"))))

(deftest a-walk-receiver-names-its-door-and-id-from
  (testing "the guard refuses a walk without its door, its id_from, or with a fixed id"
    (is (some? (refusal #(walker! "walk-bad" {:door nil :id_from "target"}))))
    (is (some? (refusal #(walker! "walk-bad" {:id_from nil}))))
    (is (some? (refusal #(walker! "walk-bad" {:id_from "target" :id "0b6f1c1e"}))))
    (is (some? (refusal #(walker! "walk-bad" {:id_from "source=(someone)"})))))
  (testing "and admits one that names both"
    (is (nil? (refusal #(walker! "walk-good" {:id_from "merge_after=(contains (self))"}))))))
