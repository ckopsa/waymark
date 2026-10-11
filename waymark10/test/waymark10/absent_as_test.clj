(ns waymark10.absent-as-test
  "A filterable field may declare the value an absent one filters as
  (:absent-as {field value}): field=<value> answers the rows that store
  nothing beside the rows that store it, any other value skips them,
  and the facet counts them under that value. Nothing is stored and
  nothing is backfilled, so the rule lives in the stores — proved
  against BOTH from one body of assertions."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(defn- parcel-map [absent]
  {:kind :parcel
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:max 80}]]
            [:domain {:optional true} [:maybe [:string {:max 40}]]]
            [:note {:optional true} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in}
                :domain #{:eq :in :ne}
                :note #{:contains}}
   :faceted [:domain]
   :absent-as absent
   :actions
   {:finish {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A finished parcel is history."}}}})

(def parcel (r/resource (parcel-map {:domain "factory"})))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private opts {:principal colton})

(defn- each-store
  "Run `f` against a memory engine and a Postgres engine over the same
  declaration."
  [f]
  (f :memory (inv/engine {:storage (memory/storage) :resources [parcel]}))
  (db/with-test-engine [parcel] (fn [eng] (f :postgres eng))))

(defn- titles [eng params]
  (let [env (collections/envelope eng (get (inv/resources eng) :parcel) params
                                  {:principal colton
                                   :now (java.time.Instant/now)
                                   :resources (inv/resources eng)})]
    (into #{} (map #(re-find #"^\S+" (get % "summary")))
          (get-in env ["data" "items"]))))

(deftest an-absent-value-filters-as-the-declared-one
  (each-store
   (fn [which eng]
     (inv/create! eng :parcel {:title "bare"} opts)
     (inv/create! eng :parcel {:title "stored" :domain "factory"} opts)
     (inv/create! eng :parcel {:title "other" :domain "home"} opts)
     (testing (str which ": the declared value answers the absent rows too")
       (is (= #{"bare" "stored"} (titles eng {"domain" "factory"}))))
     (testing (str which ": any other value skips them")
       (is (= #{"other"} (titles eng {"domain" "home"}))))
     (testing (str which ": a comma list naming the value takes them")
       (is (= #{"bare" "stored" "other"}
              (titles eng {"domain" "home,factory"}))))
     (testing (str which ": _ne reads the same rule")
       (is (= #{"bare" "stored"} (titles eng {"domain_ne" "home"})))
       (is (= #{"other"} (titles eng {"domain_ne" "factory"}))))
     (testing (str which ": the facet counts them under the declared value")
       (let [st (:storage eng)]
         (is (= {"factory" 2 "home" 1}
                (store/with-tx st
                  #(store/facet-counts st % :parcel :domain [] false
                                       "factory")))))))))

(deftest absent-as-on-a-field-no-eq-filter-serves-is-a-definition-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an :eq-filterable"
                        (r/resource (parcel-map {:note "none"})))
      "a substring-only field has no equality param to rewrite")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an :eq-filterable"
                        (r/resource (parcel-map {:state "open"}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an :eq-filterable"
                        (r/resource (parcel-map {:nothing "here"})))))
