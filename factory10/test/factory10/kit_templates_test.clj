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
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.roles :as roles]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private as-person
  {:principal (t/principal {:id "colton" :display "Colton Kopsa"})})

(def ^:private values
  "What fills each hole the templates name."
  {:ticket_repo "ckopsa/waymark"
   :repos "ckopsa/waymark"
   :domain "factory"})

(def ^:private journal
  "A stand-in for the house's journal kind (workqueue10), which the
  mayor's scope names and factory10 does not serve (ticket bad6628c)."
  (r/resource
   {:kind :journal
    :plural "journals"
    :states [:written :struck]
    :initial :written
    :terminal #{:struck}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 60}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:strike {:from #{:written} :to :struck
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Striking is acknowledged."}
              :display {:label "Strike" :order 1
                        :description "Strike this entry"}}}}))

(defn- world []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (conj (vec (main/resources)) journal)})]
    (roles/ensure-factory-roles! eng)
    eng))

(defn- template [path]
  (edn/read-string (slurp (io/resource path))))

(defn- fill
  "`x` with each hole the template's params name filled from `values`."
  [{:keys [template]} x]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") (get values k)))
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
        ;; in_domain is the seat kind's computed field, which its create
        ;; door refuses; ticket 65928b96 takes it out of the templates
        seat (:row (inv/create! eng :seat (dissoc (fill t (:seat t)) :in_domain)
                                as-person))]
    (is (= "mayor" (get-in seat [:data :name])))))
