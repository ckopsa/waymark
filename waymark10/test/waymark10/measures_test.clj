(ns waymark10.measures-test
  "Measures step 1 (docs/spec-measures.md §2, §8): a kind declares named
  :measures, judged at declaration. A well-formed measure loads, hashes
  as law and is listed by its token; each malformed key is named by
  [measure-shape]; a field the sweep would read unindexed is
  [measure-unfiltered]. No database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.measures :as measures]
            [waymark10.resource :as r]))

(def ^:private safety {:idempotent false :reversible false :confirm false})

(def ^:private on-time
  {:label "Orders shipped within 3 days"
   :stat :rate
   :among "state=shipped"
   :where '(<= (data :shipped_at) (data :placed_at))
   :at :placed_at
   :over '(weeks 4)
   :tell :owner-seat})

(defn- order-decl [& {:as overrides}]
  (merge {:kind :ms_order
          :plural "ms_orders"
          :states [:placed :shipped]
          :initial :placed
          :terminal #{:shipped}
          :summary "{data.name} · {state}"
          :schema [:map
                   [:name [:string {:max 40}]]
                   [:total [:int {:min 0}]]
                   [:placed_at :waymark/instant]
                   [:shipped_at {:optional true} [:maybe :waymark/instant]]]
          :filterable {:state #{:eq :in} :placed_at #{:eq}}
          :measures {:shipped_on_time on-time}
          :actions {:ship {:from #{:placed} :to :shipped
                           :safety (assoc safety :one-way "Shipped is shipped.")
                           :display {:label "Ship"}}}}
         overrides))

(defn- with [overrides]
  (order-decl :measures {:shipped_on_time (reduce-kv (fn [m k v]
                                                       (if (= ::drop v) (dissoc m k) (assoc m k v)))
                                                     on-time overrides)}))

(defn- loads
  "nil when the declaration loads alone, else the gate's sentence."
  [decl]
  (try (r/resource decl) nil
       (catch Exception e (ex-message e))))

(defn- assembles
  "nil when the declaration's registry assembles, else the sentence."
  [decl]
  (try (ca/run-all {:kinds {:ms_order (r/resource decl)}}) nil
       (catch Exception e (ex-message e))))

(deftest a-well-formed-measure-loads
  (is (nil? (loads (order-decl))))
  (is (nil? (assembles (order-decl))))
  (testing "its defaults are written in"
    (is (= {:of :ms_order :at-least 10}
           (select-keys (get-in (r/resource (order-decl)) [:measures :shipped_on_time])
                        [:of :at-least]))))
  (testing "a count, a sum and a median load beside it"
    (let [plain (dissoc on-time :where)
          decl (order-decl :measures {:shipped_on_time on-time
                                      :placed (assoc plain :stat :count)
                                      :takings (assoc plain :stat :sum :field :total)
                                      :lead (assoc plain :stat :median
                                                   :field "duration(placed_at,shipped_at)")})]
      (is (nil? (loads decl)))
      (is (nil? (assembles decl))))))

(deftest its-token-is-kind-dot-name
  (is (= ["ms_order.shipped_on_time"] (measures/tokens (r/resource (order-decl))))))

(deftest each-malformed-key-is-named
  (doseq [[overrides says]
          [[{:goal 1} #"names :goal, not a measure key"]
           [{:label ::drop} #":label is required"]
           [{:stat :mean} #":stat :mean is not one of rate, count, sum, median, p90"]
           [{:among ""} #":among is required and not empty"]
           [{:stat :count} #":where is for :rate only"]
           [{:where ::drop} #":where is required on :rate"]
           [{:stat :sum :where ::drop} #":field is required on :stat sum"]
           [{:field :total} #":field is read by sum, median and p90 only"]
           [{:at ::drop} #":at is required"]
           [{:over ::drop} #":over is required"]
           [{:over '(fortnights 2)} #":over \(fortnights 2\) is not a period"]
           [{:over '(months 4)} #":over \(months 4\) is past \(months 3\)"]
           [{:at-least 0} #":at-least 0 is not a positive int"]]]
    (let [msg (str (loads (with overrides)))]
      (is (re-find #"ms_order \[measure-shape\] measure shipped_on_time " msg) (pr-str overrides))
      (is (re-find says msg) (pr-str overrides)))))

(deftest what-a-measure-names-is-judged-at-assembly
  (testing "an :of no kind is"
    (is (re-find #"ms_order \[measure-shape\] measure shipped_on_time :of :ms_parcel names no kind"
                 (str (assembles (with {:of :ms_parcel}))))))
  (testing "an :at that is not a time field"
    (is (re-find #"\[measure-shape\] measure shipped_on_time :at \"total\" is not a date or instant field"
                 (str (assembles (with {:at :total}))))))
  (testing "a misspelled state in :among"
    (is (re-find #"ms_order \[measure-shape\] measure shipped_on_time :among state=\"shiped\" is not a state"
                 (str (assembles (with {:among "state=shiped"}))))))
  (testing "an :among value its field's schema does not admit"
    (is (re-find #"\[measure-shape\] measure shipped_on_time :among placed_at=\"notadate\""
                 (str (assembles (with {:among "state=shipped&placed_at=notadate"}))))))
  (testing "a suffixed :among key's value its base field's schema does not admit"
    (is (re-find #"\[measure-shape\] measure shipped_on_time :among placed_at_gte=\"notadate\""
                 (str (assembles (with {:among "state=shipped&placed_at_gte=notadate"})))))
    (is (re-find #"\[measure-shape\] measure shipped_on_time :among state_ne=\"shiped\" is not a state"
                 (str (assembles (with {:among "state_ne=shiped"})))))
    (is (nil? (assembles (with {:among "state=shipped&placed_at_gte=-P3D"}))))))

(deftest a-field-no-index-serves-is-unfiltered
  (testing "the :at field"
    (is (re-find #"ms_order \[measure-unfiltered\] measure shipped_on_time reads ms_order by placed_at"
                 (str (assembles (order-decl :filterable {:state #{:eq :in}}))))))
  (testing "a field :among names"
    (is (re-find #"\[measure-unfiltered\] measure shipped_on_time reads ms_order by name"
                 (str (assembles (with {:among "state=shipped&name=x"})))))))

(defn- hash-of [decl]
  (fp/fingerprint-hash (r/fingerprint (r/resource decl))))

(deftest a-measure-is-law
  (is (= {"of" "ms_order" "stat" "rate" "among" {"state" ["shipped"]} "at" "placed_at"
          "over" ["weeks" 4] "at_least" 10 "tell" "owner-seat"}
         (dissoc (get-in (r/fingerprint (r/resource (order-decl))) ["measures" "shipped_on_time"])
                 "where")))
  (testing "the label is prose and does not hash"
    (is (= (hash-of (order-decl)) (hash-of (with {:label "Shipped in time"})))))
  (testing ":among hashes as the grammar parses it"
    (is (= (hash-of (with {:among "state=shipped,placed"}))
           (hash-of (with {:among "state=placed,shipped"})))))
  (testing "the window is law"
    (is (not= (hash-of (order-decl)) (hash-of (with {:over '(weeks 2)})))))
  (testing ":at-least 10 written or left out hashes alike"
    (is (= (hash-of (order-decl)) (hash-of (with {:at-least 10})))))
  (testing "a kind with no measures carries no facet, so no existing hash moves"
    (is (not (contains? (r/fingerprint (r/resource (order-decl :measures nil))) "measures")))))
