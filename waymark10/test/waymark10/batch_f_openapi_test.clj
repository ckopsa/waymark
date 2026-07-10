(ns waymark10.batch-f-openapi-test
  "Batch F, deliverable 6: the OpenAPI overlay grows response schemas
  (envelope, collection, problem, bulk_report as components.schemas,
  referenced per route), securitySchemes (the OIDC bearer and the dev
  header), and the surfaces routes. Real Postgres
  (WAYMARK10_TEST_DSN); the document derives per request off the ring
  handler."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private item
  (r/resource
   {:kind :f_item
    :plural "f_items"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 60}]]
             [:hot {:optional true} [:maybe :boolean]]]
    :actions
    {:finish {:from #{:open} :to :done
              :bulk {:max-items 50 :defer-over 20}
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}
              :display {:label "Finish"}}}}))

(def ^:private board
  {:name :f-item-board
   :anchor :f_item
   :showcase [:finish]
   :attention {:hot true}})

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["f_items" "definitions" "members" "roles" "grants"
                           "attachments" "subscriptions" "jobs"
                           "waymark10_transitions" "waymark10_idempotency"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [item]
                                       :surfaces [board]}))]
          (f))
        (finally (pg/close! st))))))

(defn- doc []
  (wire/read-json
   (:body (*h* {:request-method :get :uri "/api/openapi.json"
                :headers {"x-waymark-principal" "priya"}}))))

(deftest response-schemas-are-components
  (let [d (doc)
        schemas (get-in d [:components :schemas])]
    (testing "the four shared shapes exist once"
      (is (= #{:envelope :collection :problem :bulk_report}
             (set (keys schemas))))
      (is (= ["kind" "self" "state" "summary"]
             (get-in schemas [:envelope :required])))
      (is (= {(keyword "$ref") "#/components/schemas/envelope"}
             (get-in schemas [:collection :properties :data :properties
                              :items :items]))
          "collection items reference the envelope"))
    (testing "the routes reference them, never restate them"
      (let [path #(get-in d [:paths (keyword %)])]
        (is (= "#/components/schemas/envelope"
               (get-in (path "/api/f_items/{id}")
                       [:get :responses :200 :content
                        (keyword "application/waymark+json")
                        :schema (keyword "$ref")])))
        (is (= "#/components/schemas/collection"
               (get-in (path "/api/f_items")
                       [:get :responses :200 :content
                        (keyword "application/waymark+json")
                        :schema (keyword "$ref")])))
        (is (= "#/components/schemas/envelope"
               (get-in (path "/api/f_items")
                       [:post :responses :201 :content
                        (keyword "application/waymark+json")
                        :schema (keyword "$ref")])))
        (testing "bulk answers the report; deferred answers the job envelope"
          (let [rs (get-in (path "/api/f_items/-/finish") [:post :responses])]
            (is (= "#/components/schemas/bulk_report"
                   (get-in rs [:200 :content
                               (keyword "application/waymark+json")
                               :schema (keyword "$ref")])))
            (is (= "#/components/schemas/envelope"
                   (get-in rs [:202 :content
                               (keyword "application/waymark+json")
                               :schema (keyword "$ref")])))))))
    (testing "problem responses stay referenced once AND carry the shape"
      (is (= "#/components/schemas/problem"
             (get-in d [:components :responses :refused :content
                        (keyword "application/problem+json")
                        :schema (keyword "$ref")]))))))

(deftest security-schemes-name-both-doors
  (let [d (doc)
        schemes (get-in d [:components :securitySchemes])]
    (is (= "bearer" (get-in schemes [:bearer :scheme])))
    (is (= "http" (get-in schemes [:bearer :type])))
    (is (= "X-Waymark-Principal" (get-in schemes [:devHeader :name])))
    (is (= "apiKey" (get-in schemes [:devHeader :type])))
    (testing "the document declares them as alternatives"
      (is (= [{:bearer []} {:devHeader []}] (:security d))))))

(deftest surfaces-routes-are-documented
  (let [d (doc)
        op (get-in d [:paths (keyword "/api/surfaces/f-item-board/{id}") :get])]
    (is (some? op) "one documented path per declared surface")
    (is (= ["surfaces"] (:tags op)))
    (is (= "id" (get-in op [:parameters 0 :name])))
    (is (some? (get-in op [:responses :200])))
    (is (= "#/components/responses/not_found"
           (get-in op [:responses :404 (keyword "$ref")])))))
