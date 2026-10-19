(ns waymark10.create-confirm-test
  "A create door that declares safety.confirm (ticket 13e3b323): the
  declaration's :create-safety names a consequence filled from the
  create's input, the collection's create entry advertises it, and both
  the HTTP birth door and waymark_invoke's create path refuse until the
  caller echoes the sentence that input fills.

  Memory storage, the real handler, a suite-local kind: no database,
  no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.confirm :as confirm]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

(def ^:private template
  "This files {name} and starts its onboarding seat, onboarding-{name}, at ${budget}/week.")

(def ^:private filing
  (r/resource
   {:kind :filing
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:budget :int]]
    :create-safety {:confirm true :consequence template}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}}}))

(def ^:private body {:name "meals" :budget 40})

(def ^:private sentence
  "This files meals and starts its onboarding seat, onboarding-meals, at $40/week.")

(defn- fresh []
  (engine/handler (engine/engine {:storage (memory/storage)
                                  :resources [filing]})))

(def ^:private headers {"x-waymark-principal" "colton"})

(defn- json [resp] (wire/read-json (:body resp)))

(defn- post [h hdrs]
  (h {:request-method :post :uri "/api/filings"
      :headers (merge headers hdrs)
      :body (wire/write-json body)}))

(defn- invoke [h args]
  (let [resp (h {:request-method :post :uri "/api/-/mcp" :headers headers
                 :body (wire/write-json {:jsonrpc "2.0" :id 1 :method "tools/call"
                                         :params {:name "waymark_invoke"
                                                  :arguments args}})})
        result (:result (json resp))
        text (get-in result [:content 0 :text])]
    (is (= 200 (:status resp)) (:body resp))
    {:refused? (boolean (:isError result))
     :value (try (wire/read-json text) (catch Exception _ nil))}))

(deftest the-sentence-is-filled-from-the-input
  (is (= sentence (confirm/create-consequence filing body)))
  (testing "a hole the input does not answer stays as written"
    (is (str/includes? (confirm/create-consequence filing {:name "meals"})
                       "${budget}/week")))
  (testing "a fn consequence is handed the input"
    (is (= "files meals"
           (confirm/create-consequence
            {:create-safety {:confirm true
                             :consequence #(str "files " (:name %))}}
            body))))
  (testing "a kind that declares nothing is no confirm create"
    (is (false? (confirm/create-confirm? {:kind :x})))))

(deftest the-collection-advertises-the-gate
  (let [h (fresh)
        doc (json (h {:request-method :get :uri "/api/filings" :headers headers}))
        safety (get-in doc [:actions :create :safety])]
    (is (true? (:confirm safety)) (pr-str safety))
    (is (= template (:consequence safety)))))

(deftest the-http-door-refuses-until-echoed
  (let [h (fresh)]
    (testing "no echo"
      (let [resp (post h {})]
        (is (= 409 (:status resp)) (:body resp))
        (is (= sentence (:consequence (json resp))))))
    (testing "the template, unfilled, is not the sentence"
      (is (= 409 (:status (post h {"waymark-confirm" template})))))
    (testing "the filled sentence"
      (is (= 201 (:status (post h {"waymark-confirm" sentence})))))))

(deftest the-mcp-create-refuses-until-acknowledged
  (let [h (fresh)]
    (testing "no acknowledge: refused, naming the filled sentence"
      (let [{:keys [refused? value]} (invoke h {:kind "filing" :action "create"
                                                :input body})]
        (is refused?)
        (is (= sentence (:consequence value)) (pr-str value))))
    (testing "a wrong acknowledge is refused"
      (is (:refused? (invoke h {:kind "filing" :action "create" :input body
                                :acknowledge template}))))
    (testing "the sentence echoed: the row is made"
      (let [{:keys [refused? value]} (invoke h {:kind "filing" :action "create"
                                                :input body
                                                :acknowledge sentence})]
        (is (not refused?) (pr-str value))
        (is (= "open" (str (:state value))) (pr-str value))))))
