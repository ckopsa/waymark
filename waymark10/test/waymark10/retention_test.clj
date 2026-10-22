(ns waymark10.retention-test
  "Documents 5 (docs/spec-documents.md §4): :keep holds a kind for a
  declared period. A purge before the due date refuses in one sentence,
  the retention sweep purges a due row as the engine's retention actor
  and says so in its log line, and a row whose :from is null is kept.
  In memory, with the declaration checks beside it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.retention :as retention]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.retention :as sweep]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.work-calendar :as work-calendar]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate ZoneOffset)))

(def ^:private no-agent-alone
  (g/guard {:name :no-agent-purges-alone
            :reads [:principal :within]
            :hold true
            :explain "A patient is erased by a person; an agent's purge waits for the person's tap."
            :open "No door clears this one; the call waits for the person's tap."
            :check (fn [_row _inp ctx]
                     (if (= :agent (:type (:principal ctx))) (t/deny) (t/allow)))}))

(def ^:private patient-decl
  {:kind :patient
   :states [:active :purged]
   :initial :active
   :terminal #{:purged}
   :summary "{data.name}"
   :retain {:data true}
   :keep {:for '(years 7) :from :last_visit_on :then :purge}
   :filterable {:last_visit_on #{:eq}}
   :schema [:map
            [:name {:personal true :x-display {:label "Name"}} [:string {:min 1 :max 80}]]
            [:last_visit_on {:optional true :x-display {:label "Last visit"}}
             [:maybe :waymark/date]]]
   :actions
   {:purge {:from #{:active} :to :purged :purge true
            :waives #{:required}
            :guards [no-agent-alone]
            :safety {:idempotent true :reversible false :confirm true}
            :display {:label "Erase" :style :danger :order 9}}}})

(defn- load-quietly [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(def ^:private patient (load-quietly patient-decl))

(def ^:private clock (atom nil))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (reset! clock (Instant/parse "2026-10-22T12:00:00Z"))
    (binding [*eng* (inv/engine {:storage (memory/storage)
                                 :resources [attachments/attachment patient]
                                 :now-fn #(deref clock)})]
      (f))))

(def ^:private opts {:principal (t/principal {:id "dana" :display "Dana"})})

(defn- patient! [data]
  (:row (inv/create! *eng* :patient data opts)))

(defn- state-of [id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx] (keyword (name (:state (store/load-row st tx :patient (str id) {}))))))))

(defn- last-transition [id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx] (first (store/transitions st tx {:kind :patient :resource-id id}
                                         {:newest-first true :limit 1}))))))

(defn- refusal [id]
  (try (inv/invoke! *eng* :patient (str id) :purge nil opts)
       nil
       (catch clojure.lang.ExceptionInfo e (pr-str (ex-data e)))))

(deftest an-early-purge-is-refused
  (let [ana (patient! {:name "Ana Ruiz" :last_visit_on "2020-01-10"})]
    (testing "the purge before the due date refuses in one sentence"
      (let [why (refusal (:id ana))]
        (is (some? why))
        (is (str/includes? (str why) "Ana Ruiz's records are kept until 10 January 2027.")
            why))
      (is (= :active (state-of (:id ana)))))
    (testing "the envelope shows the date it goes"
      (is (= "2027-01-10"
             (get-in (render/envelope patient ana {:now @clock})
                     ["meta" "retained_until"]))))))

(deftest a-due-row-is-swept-and-a-null-from-is-kept
  (let [ana (patient! {:name "Ana Ruiz" :last_visit_on "2020-01-10"})
        bo (patient! {:name "Bo"})]
    (testing "before the due date the sweep moves nothing"
      (is (zero? (sweep/sweep! *eng*))))
    (reset! clock (Instant/parse "2027-02-01T12:00:00Z"))
    (testing "after it, the due row is purged and the other kept"
      (is (= 1 (sweep/sweep! *eng*)))
      (is (= :purged (state-of (:id ana))))
      (is (= :active (state-of (:id bo)))))
    (testing "the log line says whose hand and which law"
      (let [tr (last-transition (:id ana))
            actor (pr-str (:actor tr))]
        (is (str/includes? actor "waymark10-retention") actor)
        (is (str/includes? actor "purged by the 7-year retention of patient, due 10 January 2027")
            actor)))
    (testing "a null :from refuses a person's purge too, and says what it waits on"
      (is (str/includes? (str (refusal (:id bo))) "Bo's records are kept until last visit on is set.")))
    (testing "a second pass finds nothing more to do"
      (is (zero? (sweep/sweep! *eng*))))))

(deftest the-envelope-reads-today-in-the-house-zone
  (reset! clock (Instant/parse "2027-01-10T03:00:00Z"))
  (binding [*eng* (inv/engine {:storage (memory/storage)
                               :resources [attachments/attachment work-calendar/work-calendar patient]
                               :now-fn #(deref clock)})]
    (inv/create! *eng* :work_calendar
                 {:name "Elm Street Clinic" :zone "America/Denver" :default true} opts)
    (let [ana (patient! {:name "Ana Ruiz" :last_visit_on "2020-01-10"})
          env #(render/envelope patient ana {:principal (:principal opts)
                                             :now @clock
                                             :calendar (:calendar (inv/render-hooks *eng*))})]
      (testing "at 03:00 UTC on the due date it is still the 9th in Denver: the envelope, the invoke and the sweep all keep the row"
        (let [e (env)]
          (is (= "2027-01-10" (get-in e ["meta" "retained_until"])))
          (is (not (contains? (get e "actions") "purge")))
          (is (contains? (get e "unavailable") "purge")))
        (let [why (refusal (:id ana))]
          (is (str/includes? (str why) "Ana Ruiz's records are kept until 10 January 2027.") why))
        (is (zero? (sweep/sweep! *eng*)))
        (is (= :active (state-of (:id ana)))))
      (reset! clock (Instant/parse "2027-01-10T08:00:00Z"))
      (testing "past Denver's midnight the envelope offers the purge and the sweep takes it"
        (let [e (env)]
          (is (= "2027-01-10" (get-in e ["meta" "retained_until"])))
          (is (contains? (get e "actions") "purge"))
          (is (not (contains? (get e "unavailable") "purge"))))
        (is (= 1 (sweep/sweep! *eng*)))
        (is (= :purged (state-of (:id ana))))))))

(deftest a-state-dates-its-entry
  (let [keep {:for '(months 6) :from {:state :closed} :then :purge}
        at (Instant/parse "2026-03-31T23:30:00Z")]
    (is (= (LocalDate/parse "2026-09-30")
           (retention/due-on keep {:state :closed} (fn [_ _] at) ZoneOffset/UTC)))
    (is (nil? (retention/due-on keep {:state :open} (fn [_ _] nil) ZoneOffset/UTC)))
    (is (= at (retention/entered [{:to-state :open :at (Instant/parse "2026-04-02T00:00:00Z")}
                                  {:to-state :closed :at at}]
                                 :closed)))))

(defn- refused-by [decl]
  (try (load-quietly decl) nil
       (catch clojure.lang.ExceptionInfo e
         (or (:check (ex-data e)) (ex-message e)))))

(deftest the-declaration-is-judged
  (testing "the declaration as written stands"
    (is (nil? (refused-by patient-decl))))
  (testing "[keep-unfiltered]: a :from the kind does not filter by"
    (is (= :keep-unfiltered (refused-by (dissoc patient-decl :filterable)))))
  (testing "[purge-unheld]: a :then that is not a purge door"
    (is (= :purge-unheld
           (refused-by (-> patient-decl
                           (assoc-in [:keep :then] :archive)
                           (assoc-in [:actions :archive]
                                     {:from #{:active} :to :purged
                                      :safety {:idempotent true :reversible false
                                               :confirm false
                                               :one-way "An archived patient is history."}}))))))
  (testing "a :for that is no period, and a :from state the kind lacks"
    (is (str/includes? (str (refused-by (assoc-in patient-decl [:keep :for] 7))) ":for is a period"))
    (is (str/includes? (str (refused-by (assoc-in patient-decl [:keep :from] {:state :gone})))
                       "names no state")))
  (testing ":keep :forever is allowed"
    (is (nil? (refused-by (assoc patient-decl :keep :forever))))))
