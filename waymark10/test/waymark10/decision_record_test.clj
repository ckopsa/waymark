(ns waymark10.decision-record-test
  "The decision record (spec-decision-record.md, waymark-442.5): a
  committed transition remembers why it was ALLOWED.

  The suite proves the two halves separately, because they fail
  separately:

  THE DERIVED HALF costs no storage and is retroactive — hand
  decision/basis a kind, an action and a law revision and it answers
  with the guards that judged, whether or not anything was ever
  written. Nothing in those tests touches the log.

  THE WRITTEN HALF is the evidence, and it is proved against BOTH
  stores from one body of assertions. The memory twin is the WEAKER
  witness here (spec §the plumbing): it conjes the record verbatim,
  so a field Postgres silently drops would pass against memory and
  only the real INSERT can catch it. Running the same assertions
  twice is the whole point of the shape below — `each-store` is not
  ceremony.

  The Postgres half needs the waymark10_test database; the memory
  half needs nothing, which is why it runs even when the other is
  skipped."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.decision :as decision]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

;; the ordinary case: a verdict as data, whose :vars name the values
;; the refusal sentence would have interpolated. Those same values are
;; the record — the spec's second thesis, made testable
(def ^:private stock-remains
  (g/expr {:name :stock-remains
           :when '(< 0 (data :stock))
           :explain "Only {left} left in the cupboard."
           :vars {:left '(data :stock)
                  :shelf '(data :shelf)}}))

;; the disclosure lock's subject: a :vars form reading a {:secret true}
;; field. It must evaluate to nil at WRITE time — subtraction, not
;; projection
(def ^:private combination-known
  (g/expr {:name :combination-known
           :when '(is-set (data :shelf))
           :explain "The cupboard combination is {code}."
           :vars {:code '(data :combination)}}))

;; the closure boundary: a code guard records its name and its
;; declared :reads, and nothing else. Same line fingerprint.clj draws
(g/defguard shelf-is-reachable
  {:reads [:principal]
   :explain "That shelf is out of reach."}
  [_row _inp _ctx]
  (t/allow))

;; the acknowledge protocol's half of the record
(def ^:private nothing-spilled
  (g/expr {:name :nothing-spilled
           :severity :warning
           :when '(not (data :spilled))
           :explain "Something spilled in there."
           :vars {:where '(data :shelf)}}))

(def ^:private cupboard
  (r/resource
   {:kind :cupboard
    :plural "cupboards"
    :states [:stocked :emptied]
    :initial :stocked
    :terminal #{:emptied}
    :summary "{data.shelf} · {state}"
    :retain {:judgment true}
    :schema [:map
             [:shelf [:string {:max 40}]]
             [:stock {:optional true} [:maybe :int]]
             [:spilled {:optional true} [:maybe :boolean]]
             [:combination {:optional true :secret true}
              [:maybe [:string {:max 40}]]]]
    :actions
    {:take {:from #{:stocked} :to :stocked
            :guards [stock-remains combination-known shelf-is-reachable
                     nothing-spilled]
            :safety {:idempotent true :reversible true :confirm false}}
     :empty {:from #{:stocked} :to :emptied
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An emptied cupboard is history."}}}}))

;; the control: the same shape, retention never declared. Its log must
;; carry no judgment at all — the default-off posture, proved rather
;; than assumed
(def ^:private drawer
  (r/resource
   {:kind :drawer
    :plural "drawers"
    :states [:stocked :emptied]
    :initial :stocked
    :terminal #{:emptied}
    :summary "{data.shelf} · {state}"
    :schema [:map
             [:shelf [:string {:max 40}]]
             [:stock {:optional true} [:maybe :int]]]
    :actions
    {:take {:from #{:stocked} :to :stocked
            :guards [stock-remains]
            :safety {:idempotent true :reversible true :confirm false}}
     :empty {:from #{:stocked} :to :emptied
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An emptied drawer is history."}}}}))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private opts {:principal colton})

(defn- each-store
  "Run `f` against a memory engine and a Postgres engine over the same
  declarations — the parity harness this feature's plumbing demands."
  [f]
  (f :memory (inv/engine {:storage (memory/storage)
                          :resources [cupboard drawer]}))
  (db/with-test-engine [cupboard drawer] (fn [eng] (f :postgres eng))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id id} {}))))

;; ── the derived half: no storage, and retroactive ───────────────────

(deftest the-basis-is-derived
  (testing "the guards that judged, in the order they judged"
    (let [b (decision/basis cupboard :take 1)]
      (is (= [:stock-remains :combination-known :shelf-is-reachable
              :nothing-spilled]
             (mapv :name (:guards b))))
      (is (= [:expr :expr :code :expr] (mapv :form (:guards b))))
      (is (= [:refuse :refuse :refuse :warning]
             (mapv :severity (:guards b))))))

  (testing "the resident declaration IS the law when no revision overlays"
    (is (= :resident (:law (decision/basis cupboard :take 1))))
    (is (= :resident (:law (decision/basis cupboard :take nil)))))

  (testing "an action with no guards answers an empty vector, not nil"
    (is (= [] (:guards (decision/basis cupboard :empty 1)))))

  (testing "the create door's basis is the kind's create guards"
    (is (= [] (:guards (decision/basis cupboard :create 1)))))

  (testing "a restamp judges nothing, and says so"
    (is (= {:action :adopt :revision 3 :law :engine :guards []}
           (decision/basis cupboard :adopt 3))))

  (testing "an action this kind never declared has no basis"
    (is (nil? (decision/basis cupboard :juggle 1))))

  (testing "retention is not the basis's business — the derived half is
            free for every kind, recorded or not"
    (is (= [:stock-remains] (mapv :name (:guards (decision/basis drawer
                                                                 :take 1)))))
    (is (false? (decision/retains? drawer)))
    (is (true? (decision/retains? cupboard)))))

;; ── the written half, against both stores ───────────────────────────

(deftest the-record-names-its-guards-and-verdict
  (each-store
   (fn [store-name eng]
     (let [where (str "(" (name store-name) ")")
           {:keys [row]} (inv/create! eng :cupboard
                                      {:shelf "the tall one" :stock 3
                                       :combination "17-4-22"}
                                      opts)
           id (:id row)
           _ (inv/invoke! eng :cupboard id :take nil opts)
           t' (last (log-of eng :cupboard id))
           j (:judgment t')
           by-name (into {} (map (fn [g] [(:name g) g])) (:guards j))]

       (testing (str where " the record rides the committed transition")
         (is (some? j) "a retained kind records at its own write")
         (is (= (:law-revision t') (:revision j))
             "the record names the law that judged, not today's"))

       (testing (str where " every guard, in the order it judged")
         (is (= ["stock-remains" "combination-known" "shelf-is-reachable"
                 "nothing-spilled"]
                (mapv (comp name :name) (:guards j)))))

       (testing (str where " every guard carries its verdict")
         (is (= ["allow" "allow" "allow" "allow"]
                (mapv :verdict (:guards j)))))

       (testing (str where " an expression guard records its declared :vars")
         (is (= {:left 3 :shelf "the tall one"}
                (:read (by-name "stock-remains")))
             "the refusal sentence the guard did not have to give")
         (is (= {:left ["stock"] :shelf ["shelf"]}
                (:read_fields (by-name "stock-remains")))
             "each value names the fields it read, so a reader can project"))

       (testing (str where " a code guard records nothing but its name")
         (is (= {:name "shelf-is-reachable" :verdict "allow"
                 :reads ["principal"] :opaque true}
                (by-name "shelf-is-reachable"))))

       (testing (str where " a SECRET field is never captured")
         (is (= {:code nil} (:read (by-name "combination-known")))
             "subtracted at write time, not projected at read time")
         (is (not (re-find #"17-4-22"
                           (pr-str (by-name "combination-known"))))))

       (testing (str where " the derived basis agrees with the stored names")
         (is (= (mapv (comp name :name)
                      (:guards (decision/basis cupboard (:action t')
                                               (:law-revision t'))))
                (mapv (comp name :name) (:guards j)))))

       (testing (str where " the birth is judged too")
         (is (some? (:judgment (first (log-of eng :cupboard id))))))))))

(deftest an-acknowledged-warning-is-recorded-as-overridden
  (each-store
   (fn [store-name eng]
     (let [where (str "(" (name store-name) ")")
           {:keys [row]} (inv/create! eng :cupboard
                                      {:shelf "under the sink" :stock 2
                                       :spilled true}
                                      opts)
           id (:id row)
           _ (inv/invoke! eng :cupboard id :take nil
                          (assoc opts :acknowledged #{:nothing-spilled}))
           j (:judgment (last (log-of eng :cupboard id)))
           entry (first (filter #(= "nothing-spilled" (:name %))
                                (:guards j)))]
       (testing (str where " an overridden warning is not an allow")
         (is (= "acknowledged" (:verdict entry))
             "the one verdict that was overridden rather than earned")
         (is (= ["nothing-spilled"] (:acknowledged j))))
       (testing (str where " it still records what it read")
         (is (= {:where "under the sink"} (:read entry))))))))

(deftest retention-is-the-only-door
  (each-store
   (fn [store-name eng]
     (let [where (str "(" (name store-name) ")")
           {:keys [row]} (inv/create! eng :drawer
                                      {:shelf "the cutlery one" :stock 4}
                                      opts)
           id (:id row)]
       (inv/invoke! eng :drawer id :take nil opts)
       (testing (str where " a kind that declares no retention records nothing")
         (is (every? (comp nil? :judgment) (log-of eng :drawer id))
             "default off, grown by declaration — never an empty object"))))))

(deftest a-rehearsal-records-nothing
  (each-store
   (fn [store-name eng]
     (let [where (str "(" (name store-name) ")")
           {:keys [row]} (inv/create! eng :cupboard
                                      {:shelf "the dry goods" :stock 1}
                                      opts)
           id (:id row)
           before (count (log-of eng :cupboard id))]
       (inv/invoke! eng :cupboard id :take nil (assoc opts :dry-run true))
       (testing (str where " §23 holds: a dry run neither demands, "
                     "consumes, nor RECORDS")
         (is (= before (count (log-of eng :cupboard id)))))))))

(deftest the-conformance-obligation-holds
  (each-store
   (fn [store-name eng]
     (let [where (str "(" (name store-name) ")")
           {:keys [row]} (inv/create! eng :cupboard
                                      {:shelf "the corner one" :stock 9}
                                      opts)]
       (inv/invoke! eng :cupboard (:id row) :take nil opts)
       (inv/create! eng :drawer {:shelf "the odds and ends" :stock 2} opts)
       (let [{:keys [violations covered]} (conf/decision-record-violations eng)]
         (testing (str where " the obligation is green and has teeth")
           (is (= [] violations))
           (is (pos? covered)
               "an obligation that read no records proves nothing")))))))

;; ── the read lock ───────────────────────────────────────────────────

(deftest evidence-rides-the-grant
  (let [j {:revision 1
           :guards [{:name "stock-remains" :verdict "allow"
                     :reads []
                     :read {:left 3 :shelf "the tall one"}
                     :read_fields {:left ["stock"] :shelf ["shelf"]}}
                    {:name "shelf-is-reachable" :verdict "allow"
                     :reads ["principal"] :opaque true}]}]
    (testing "a system read (nil visibility) projects nothing away"
      (is (= j (decision/project j nil))))

    (testing "a value whose field the grant conceals is WITHHELD, not refused"
      (let [g' (first (:guards (decision/project j #{"shelf"})))]
        (is (= {:shelf "the tall one"} (:read g'))
            "the field this grant admits still reads")
        (is (= [:left] (:withheld g'))
            "the var's NAME is law and stays; only the VALUE is projected")
        (is (not (re-find #"\b3\b" (pr-str (:read g')))))))

    (testing "an opaque guard has nothing to withhold"
      (is (= (second (:guards j))
             (second (:guards (decision/project j #{}))))))))
