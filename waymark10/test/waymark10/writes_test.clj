(ns waymark10.writes-test
  "A door may declare `:writes`, the data keys it writes, and invoke
  refuses a move that changes any other, naming it. `:allow-after-end`
  licenses a `:to :same` door that declares `:writes` to run on a row
  whose story ended, and leaves it ended; the checks refuse the licence
  on any other door (ticket 7e60b100). Suite-local kind; the door tests
  run on real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────────────────

(def ^:private safe
  {:idempotent true :reversible false :confirm false})

(def ^:private box-map
  {:kind :wr_box
   :plural "wr_boxes"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:a {:optional true} [:maybe [:string {:max 40}]]]
            [:b {:optional true} [:maybe [:string {:max 40}]]]]
   :actions {:set_a {:from #{:open} :to :same
                     :writes #{:a}
                     :input [:map
                             [:a [:string {:min 1 :max 40}]]
                             [:b {:optional true} [:maybe [:string {:max 40}]]]]
                     :safety safe
                     :handler (fn [row inp _ctx]
                                (update row :data merge (select-keys inp [:a :b])))}
             :note {:from #{:open :closed} :to :same
                    :allow-after-end true
                    :writes #{:a}
                    :input [:map [:a [:string {:min 1 :max 40}]]]
                    :safety safe
                    :handler (fn [row inp _ctx]
                               (assoc-in row [:data :a] (:a inp)))}
             :close {:from #{:open} :to :closed
                     :safety (assoc safe :one-way "Closed is history.")}}})

(defn- load-quietly [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(def ^:private box (load-quietly box-map))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- refused-by [check m]
  (try
    (load-quietly m)
    (is false (str "expected [" (name check) "] to refuse this declaration"))
    (catch clojure.lang.ExceptionInfo e
      (is (= check (:check (ex-data e))) (ex-message e)))))

;; ── 1. the checks hold the licence to its shape ─────────────────────────────

(deftest the-licence-is-held-to-its-shape
  (testing "a licensed :to :same door with :writes loads"
    (is (some? box)))
  (testing "a licensed door that moves state is refused"
    (refused-by :allow-after-end
                (assoc-in box-map [:actions :reopen]
                          {:from #{:closed} :to :open
                           :allow-after-end true
                           :writes #{:a}
                           :safety safe})))
  (testing "a licensed door with no :writes is refused"
    (refused-by :allow-after-end
                (update-in box-map [:actions :note] dissoc :writes)))
  (testing "a licensed door with an empty :writes is refused"
    (refused-by :allow-after-end
                (assoc-in box-map [:actions :note :writes] #{})))
  (testing "without the licence, a door from a terminal state is still refused"
    (refused-by :terminal-no-exit
                (update-in box-map [:actions :note] dissoc :allow-after-end))))

(deftest writes-and-the-licence-are-law
  (let [facet (get-in (fp/fingerprint-of box) ["machine" "actions" "note"])]
    (is (= ["a"] (get facet "writes")))
    (is (true? (get facet "allow_after_end")))
    (is (nil? (get-in (fp/fingerprint-of box)
                      ["machine" "actions" "close" "writes"])))))

;; ── 2. the doors ────────────────────────────────────────────────────────────────────

(def ^:private tables
  ["wr_boxes" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(deftest writes-are-held-at-the-door
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [box]})
            new-box (fn [title]
                      (:row (inv/create! eng :wr_box {:title title}
                                         {:principal elena})))
            move (fn [id door inp]
                   (try (inv/invoke! eng :wr_box id door inp {:principal elena})
                        (catch clojure.lang.ExceptionInfo e e)))]
        (testing "a door that changes only the key it writes lands"
          (let [res (move (:id (new-box "one")) :set_a {:a "x"})]
            (is (map? res))
            (is (= "x" (get-in res [:row :data :a])))))
        (testing "a door that changes a key outside :writes is refused, naming it"
          (let [c (new-box "two")
                refused (ex-data (move (:id c) :set_a {:a "x" :b "y"}))]
            (is (= :writes (:guard refused)))
            (is (= ["b"] (get-in refused [:evidence :stray])))
            (is (re-find #"\bb\b" (str (:detail refused))))))
        (testing "a licensed door runs on a closed row and leaves it closed"
          (let [c (new-box "three")
                _ (move (:id c) :close {})
                res (move (:id c) :note {:a "late"})]
            (is (map? res))
            (is (= "late" (get-in res [:row :data :a])))
            (is (= "closed" (name (get-in res [:row :state])))))))
      (finally (pg/close! st)))))
