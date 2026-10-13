(ns waymark10.data-keys-verbatim-test
  "A free-form map's keys cross the wire as written. A field whose
  schema is a map-of (or :any) holds keys a person chose — a test_env
  service named client-portal — and the get, query and invoke
  envelopes answer client-portal, never client_portal. Declared field
  names still answer snake_case. Storage is the in-memory twin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.problems :as p]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

(r/defhandler dk-rename-handler [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(def ^:private dk-ticket
  (r/resource
   {:kind :dk_ticket
    :plural "dk_tickets"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 80}]]
             [:test_env {:optional true}
              [:maybe [:map
                       [:branches [:map-of :keyword :string]]
                       [:env_after {:optional true}
                        [:maybe [:map-of :keyword :any]]]]]]]
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map [:name [:string {:min 1 :max 80}]]]
              :waives #{:edit-shape}
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "The prior value is on the audit trail."}
              :handler dk-rename-handler}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed ticket stays closed."}}}}))

(defn- route!
  [eng method uri & {:keys [body query]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri :query-string query
                       :headers {"content-type" "application/json"
                                 "x-waymark-principal" "owner"}}
                body (assoc :body (wire/write-json body))))
        text (let [b (:body resp)]
               (cond (nil? b) "" (string? b) b :else (slurp b)))]
    (assoc resp :text text
           :doc (when-not (str/blank? text) (wire/read-json text)))))

(def ^:private test-env
  {:branches {"waymark" "engine/x" "client-portal" "main"}
   :env_after {"client-portal" {"image-tag" "abc"}}})

(defn- verbatim? [doc-data]
  (and (= "main" (get-in doc-data [:test_env :branches :client-portal]))
       (= "abc" (get-in doc-data [:test_env :env_after :client-portal :image-tag]))
       (not (str/includes? (wire/write-json doc-data) "client_portal"))
       (not (str/includes? (wire/write-json doc-data) "image_tag"))))

(deftest a-free-form-map-keeps-its-keys-on-every-envelope
  (let [eng (engine/engine {:storage (memory/storage) :resources [dk-ticket]})
        created (route! eng :post "/api/dk_tickets"
                        :body {:name "hello" :test_env test-env})
        uri (str (get-in created [:doc :self]))]
    (is (= 201 (:status created)) (:text created))
    (testing "create answers the keys as written"
      (is (verbatim? (get-in created [:doc :data])) (:text created)))
    (testing "get"
      (let [r (route! eng :get uri)]
        (is (= 200 (:status r)) (:text r))
        (is (verbatim? (get-in r [:doc :data])) (:text r))
        (testing "declared field names still answer snake_case"
          (is (contains? (get-in r [:doc :data]) :test_env))
          (is (contains? (get-in r [:doc :data :test_env]) :env_after)))))
    (testing "query, with the field picked"
      (let [r (route! eng :get "/api/dk_tickets")
            _ (is (= 200 (:status r)) (:text r))
            r (route! eng :get "/api/dk_tickets" :query "fields=test_env")
            item (first (get-in r [:doc :data :items]))]
        (is (= 200 (:status r)) (:text r))
        (is (verbatim? (:fields item)) (:text r))))
    (testing "invoke"
      (let [href (str (get-in (route! eng :get uri)
                              [:doc :actions :rename :href]))
            r (route! eng :post href :body {:name "renamed"})]
        (is (= 200 (:status r)) (:text r))
        (is (= "renamed" (get-in r [:doc :data :name])))
        (is (verbatim? (get-in r [:doc :data])) (:text r))))))

(deftest wire-value-snakes-what-is-not-marked
  (is (= {"due_at" {"a_b" 1}} (p/wire-value {:due-at {:a-b 1}})))
  (is (= {"due_at" {"a-b" {"c-d" 1}}}
         (p/wire-value {:due-at (p/verbatim {:a-b (p/verbatim {:c-d 1})})})))
  (testing "a second pass keeps the mark"
    (is (= {"due_at" {"a-b" 1}}
           (p/wire-value (p/wire-value {:due-at (p/verbatim {:a-b 1})}))))))
