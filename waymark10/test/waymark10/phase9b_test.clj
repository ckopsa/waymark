(ns waymark10.phase9b-test
  "Phase-9b acceptance, part 4: surfaces and the OpenAPI overlay. A
  declared surface composes the anchor's full envelope with its
  edge-resolved members (related joins and owns) and the evaluated
  attention flags; a declaration citing an undeclared edge refuses at
  assembly; /api/openapi.json derives the real paths and input
  schemas with the problem responses referenced once. Suite-local
  kinds over the ring handler; real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.registry :as registry]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.surface :as surface]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the kinds ───────────────────────────────────────────────────────

(r/defhandler rename-proj [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(def ^:private proj
  (r/resource
   {:kind :proj
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 60}]]
             [:tag {:optional true} [:maybe [:string {:min 1 :max 20}]]]
             [:hot {:optional true} [:maybe :boolean]]]
    :filterable {:tag #{:eq} :hot #{:eq}}
    :related {:cards {:kind :card :on [[:tag := :tag]]}}
    :owns [{:kind :card :via :proj_id}]
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map [:name [:string {:min 1 :max 60}]]]
              :edit {:draft {} :prefill [:name]}
              :safety {:idempotent true :reversible true :confirm false}
              :handler rename-proj
              :display {:label "Rename"}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}
              :display {:label "Finish"}}}}))

(def ^:private card
  (r/resource
   {:kind :card
    :states [:fresh :filed]
    :initial :fresh
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:tag {:optional true} [:maybe [:string {:min 1 :max 20}]]]
             [:proj_id {:optional true :kind :proj} [:maybe :waymark/ref]]]
    :filterable {:tag #{:eq} :proj_id #{:eq}}
    :actions
    {:file {:from #{:fresh} :to :filed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Filed is filed."}}}}))

(def ^:private board
  {:name :proj-board
   :anchor :proj
   :members [{:name :matching :kind :card :related :cards}
             {:name :owned :owns :card}]
   :showcase [:finish]
   :attention {:hot true}})

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["projs" "cards" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [proj card]
                                       :surfaces [board]}))]
          (f))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (*h* (cond-> {:request-method method :uri uri
                 :headers {"x-waymark-principal" "priya"}}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

;; ── 1. the composed decision screen ─────────────────────────────────

(deftest surface-composes-the-screen
  (let [pid (id-of (req :post "/api/projs"
                        {:name "kitchen" :tag "red" :hot true}))
        _ (req :post "/api/cards" {:title "same tag" :tag "red"})
        _ (req :post "/api/cards" {:title "owned child" :proj_id pid})
        _ (req :post "/api/cards" {:title "unrelated" :tag "blue"})
        resp (req :get (str "/api/surfaces/proj-board/" pid))
        b (json resp)]
    (is (= 200 (:status resp)))
    (is (= "application/waymark+json" (get-in resp [:headers "Content-Type"])))
    (is (= "surface" (:kind b)))
    (is (= (str "/api/surfaces/proj-board/" pid) (:self b)))
    (is (= "proj-board" (:name b)))
    (is (= ["finish"] (:showcase b)))
    (testing "the anchor is the FULL envelope"
      (is (= "kitchen" (get-in b [:anchor :data :name])))
      (is (contains? (get-in b [:anchor :actions]) :finish))
      (is (= (str "/api/projs/" pid) (get-in b [:anchor :self]))))
    (testing "members resolve through the declared edges"
      (let [matching (get-in b [:members :matching :items])
            owned (get-in b [:members :owned :items])]
        (is (= 1 (count matching)))
        (is (str/starts-with? (:summary (first matching)) "same tag"))
        (is (not (contains? (first matching) :data))
            "member items are envelope-minus-data")
        (is (= 1 (count owned)))
        (is (str/starts-with? (:summary (first owned)) "owned child"))))
    (testing "attention evaluates the stored facts"
      (is (= {:hot true} (:attention b))))
    (testing "a cool anchor flags false"
      (let [pid2 (id-of (req :post "/api/projs" {:name "garage"}))
            b2 (json (req :get (str "/api/surfaces/proj-board/" pid2)))]
        (is (= {:hot false} (:attention b2)))
        (is (= [] (get-in b2 [:members :matching :items]))
            "a nil join value relates to nothing")))))

(deftest surface-doors
  (testing "well-known lists the declared surfaces"
    (let [b (json (req :get "/api/.well-known/waymark"))]
      (is (= "/api/surfaces/proj-board/{anchor-id}"
             (get-in b [:surfaces :proj-board :href])))))
  (testing "an unknown surface or anchor does not exist"
    (is (= 404 (:status (req :get "/api/surfaces/nope/x"))))
    (is (= 404 (:status (req :get "/api/surfaces/proj-board/nope")))))
  (testing "a declaration citing an undeclared edge refuses at assembly"
    (let [reg (registry/registry [proj card])]
      (is (thrown-with-msg?
           Exception #"cites related edge"
           (surface/assemble reg [{:name :bad :anchor :proj
                                   :members [{:name :x :related :nope}]}])))
      (is (thrown-with-msg?
           Exception #"showcase names unknown action"
           (surface/assemble reg [{:name :bad :anchor :proj
                                   :showcase [:zap]}])))
      (is (thrown-with-msg?
           Exception #"attention field"
           (surface/assemble reg [{:name :bad :anchor :proj
                                   :attention {:nope true}}]))))))

;; ── 2. the OpenAPI overlay ──────────────────────────────────────────

(deftest openapi-derives-the-surface
  (let [resp (req :get "/api/openapi.json")
        doc (json resp)
        path #(get-in doc [:paths (keyword %)])]
    (is (= 200 (:status resp)))
    (is (= "3.1.0" (:openapi doc)))
    (testing "collection, create and get carry the real declarations"
      (is (some? (:get (path "/api/projs"))))
      (is (some #(= "tag" (:name %))
                (get-in (path "/api/projs") [:get :parameters]))
          "the query parameters come from the filter grammar")
      (is (= ["name"]
             (get-in (path "/api/projs")
                     [:post :requestBody :content
                      (keyword "application/json") :schema :required])))
      (is (some? (:get (path "/api/projs/{id}")))))
    (testing "actions carry their input schemas and descriptions"
      (let [op (:post (path "/api/projs/{id}/-/rename"))]
        (is (= "Rename" (:summary op)))
        (is (str/includes? (:description op) "open → open"))
        (is (str/includes? (:description op) "idempotent=true"))
        (is (contains? (get-in op [:requestBody :content
                                   (keyword "application/json")
                                   :schema :properties])
                       :name))))
    (testing "the draft sub-resource documents beside its action"
      (is (some? (:put (path "/api/projs/{id}/-/rename/draft"))))
      (is (some? (:delete (path "/api/projs/{id}/-/rename/draft")))))
    (testing "problem responses are referenced once"
      (is (contains? (get-in doc [:components :responses]) :refused))
      (is (= "#/components/responses/refused"
             (get-in (:post (path "/api/projs/{id}/-/finish"))
                     [:responses :409 :$ref]))))
    (testing "the engine kinds document themselves too"
      (is (some? (path "/api/jobs/{id}/-/cancel")))
      (is (some? (path "/api/subscriptions/{id}/-/pause"))))))
