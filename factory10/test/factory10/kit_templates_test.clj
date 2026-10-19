(ns factory10.kit-templates-test
  "The factory kit's judgment templates and its mayor seat template, put
  through the doors they are written for (ticket c1448bba). factorykit's
  seats_test checks them against a copy of the kinds' limits; here each
  is filled, created with its kind's own create door and, for a
  judgment, promoted — so a drift in the judgment or seat kind refuses
  a template here and not first on a fresh engine. In memory.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private as-person
  {:principal (t/principal {:id "colton" :display "Colton Kopsa"})})

(def ^:private values
  "What fills each hole the templates name."
  {:ticket_repo "ckopsa/waymark"
   :repos "ckopsa/waymark"
   :domain "factory"})

(defn- world [& [opts]]
  (let [eng (engine/engine (merge {:storage (memory/storage)
                                   :resources (main/resources)}
                                  opts))]
    (roles/ensure-factory-roles! eng)
    eng))

(defn- refused
  "The problem a call was refused with, or nil when it ran."
  [call]
  (try (call) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- template [path]
  (edn/read-string (slurp (io/resource path))))

(defn- fill
  "`x` with each hole the template's params name filled from `values`,
  and from `more` over them."
  [{:keys [template]} x & [more]]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") (get (merge values more) k)))
           (pr-str x)
           (keys (:params template)))))

(deftest every-judgment-template-is-created-and-promoted
  (doseq [n ["draft-ticket" "sitting"]
          :let [t (template (str "judgments/" n ".edn"))]]
    (testing n
      (let [eng (world)
            made (:row (inv/create! eng :judgment (fill t (:judgment t)) as-person))
            up (:row (inv/invoke! eng :judgment (str (:id made)) :promote {} as-person))]
        (is (= "draft" (name (:state made))) "the create door takes it")
        (is (= "promoted" (name (:state up))) "the promote guards pass it")))))

(deftest the-mayor-template-is-created-through-the-seat-door
  (let [t (template "seats/mayor.edn")
        eng (world)
        domain (:row (inv/create! eng :domain {:name "factory"
                                               :charter "The engine's own work."
                                               :budget_usd_per_week 40M}
                                  as-person))
        seat (:row (inv/create! eng :seat
                                (fill t (:seat t) {:domain_id (str (:id domain))})
                                as-person))]
    (is (= "mayor" (get-in seat [:data :name])))
    (is (= (str (:id domain)) (str (get-in seat [:data :domain]))))))

(defn- a-domain [eng]
  (:row (inv/create! eng :domain {:name "factory"
                                  :charter "The engine's own work."
                                  :budget_usd_per_week 40M}
                     as-person)))

(def ^:private world-values
  {:brief "A bakery that takes cake orders."
   :kinds "ticket"
   :cast "Ann, a customer"
   :day_one "2026-10-19"})

(defn- world-seat
  "The world template's seat, filled from `given` as a seed fills it."
  [given]
  (let [t (template "seats/world.edn")]
    (seed/filled (:seat t) given (:template t))))

(deftest only-a-clone-seats-the-world-template
  (let [body (fn [eng] (world-seat (assoc world-values :domain_id
                                          (str (:id (a-domain eng))))))]
    (testing "an engine that is not a clone refuses it"
      (let [eng (world {:clone? false})]
        (is (not (engine/clone? eng)))
        (is (str/includes? (pr-str (refused #(inv/create! eng :seat (body eng) as-person)))
                           "cast-only-on-a-clone"))))
    (testing "a clone takes it"
      (let [eng (world {:clone? true})
            seat (:row (inv/create! eng :seat (body eng) as-person))]
        (is (engine/clone? eng))
        (is (true? (get-in seat [:data :acts_as_cast])))))))

(defn- kinds-of [seat k]
  (set (map (comp name :kind) (get-in seat [:data k]))))

(deftest the-world-template-scopes-create-on-every-outside-kind
  (testing "two outside kinds, comma-separated, each get a create entry"
    (let [eng (world {:clone? true})
          body (world-seat (assoc world-values :kinds "ticket, judgment"
                                  :domain_id (str (:id (a-domain eng)))))
          seat (:row (inv/create! eng :seat body as-person))]
      (is (= #{"ticket" "judgment"} (kinds-of seat :scope)))
      (is (every? #(= ["create"] (mapv name (:actions %))) (get-in seat [:data :scope])))
      (is (= #{"ticket" "judgment"} (kinds-of seat :delivers)))))
  (testing "a seed passing :kind alone still fills, as a one-element :kinds"
    (let [eng (world {:clone? true})
          body (world-seat (-> world-values (dissoc :kinds)
                               (assoc :kind "ticket" :domain_id (str (:id (a-domain eng))))))
          seat (:row (inv/create! eng :seat body as-person))]
      (is (empty? (re-seq #"\{\{[^}]*\}\}" (pr-str body))) "no hole is left")
      (is (= #{"ticket"} (kinds-of seat :scope))))))

(deftest the-world-charter-fixes-the-weeks-arc
  (let [charter (:charter (world-seat (assoc world-values :domain_id "d")))]
    (doseq [day ["day 2" "day 5" "day 7"]]
      (is (str/includes? charter day) (str "the charter names " day)))
    (is (<= (count charter) 1200) "the charter's cap")))

(defn- restate!
  "A seat's restate with the etag an honest client would have read off
  the row, since the door carries the fence an :edit implies."
  [eng id body]
  (let [row (some->> (store/with-tx (:storage eng)
                       (fn [tx] (store/load-row (:storage eng) tx :seat (str id) {})))
                     (inv/decode-row (get (inv/resources eng) :seat)))]
    (inv/invoke! eng :seat (str id) :restate body
                 (assoc as-person :if-match (inv/etag :seat (str id) (:version row))))))

(deftest only-a-clone-restates-a-seat-into-the-cast
  (let [t (template "seats/mayor.edn")
        seat-on (fn [eng]
                  (let [inp (fill t (:seat t) {:domain_id (str (:id (a-domain eng)))})]
                    [inp (:row (inv/create! eng :seat inp as-person))]))]
    (testing "a seat without the flag is made and restated anywhere"
      (let [eng (world {:clone? false})
            [inp seat] (seat-on eng)]
        (is (nil? (refused #(restate! eng (:id seat) (dissoc inp :name)))))))
    (testing "off a clone, a restate turning acts_as_cast on is refused"
      (let [eng (world {:clone? false})
            [inp seat] (seat-on eng)]
        (is (str/includes? (pr-str (refused #(restate! eng (:id seat)
                                                       (-> inp (dissoc :name) (assoc :acts_as_cast true)))))
                           "cast-only-on-a-clone"))))
    (testing "on a clone it passes"
      (let [eng (world {:clone? true})
            [inp seat] (seat-on eng)
            up (:row (restate! eng (:id seat)
                               (-> inp (dissoc :name) (assoc :acts_as_cast true))))]
        (is (true? (get-in up [:data :acts_as_cast])))))))
