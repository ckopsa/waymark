(ns waymark10.move-why-test
  "Ticket 1d8f5f1c: every move may carry the hand's own one sentence of
  why. The invoke takes it (`?why=` on the wire, `:waymark10/why` from
  waymark_invoke), the log entry keeps it on its actor, the row's
  history shows it, a held call keeps it, and one over 280 characters
  is refused with the move."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(def ^:private chore
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private rosa {"x-waymark-principal" "rosa"})

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers extra]}]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> (merge {:request-method method :uri path :headers (or headers {})}
                       extra)
          query (assoc :query-string query)
          body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- world []
  (let [eng (dev/scratch! [chore] {})]
    {:eng eng :h (dev/handler eng)}))

(deftest a-move-with-a-why-keeps-it-on-its-log-entry-and-in-history
  (let [{:keys [eng h]} (world)
        made (req h :post "/api/chores?why=Rosa%20asked%20for%20it."
                  {:headers rosa :body {:title "Trim the hedge"}})
        id (id-of made)
        done (req h :post (str "/api/chores/" id "/-/finish?why=The%20hedge%20is%20trimmed.")
                  {:headers rosa})
        log (log-of eng :chore id)]
    (is (= 201 (:status made)) (pr-str (json made)))
    (is (= 200 (:status done)) (pr-str (json done)))
    (testing "the log entry keeps it beside the actor, a create too"
      (is (= ["Rosa asked for it." "The hedge is trimmed."]
             (mapv #(get-in % [:actor :why]) log))))
    (testing "the row's history shows it beside each move"
      (let [doc (json (req h :get (str "/api/chores/" id "/-/history") {:headers rosa}))
            by (into {} (map (juxt #(str (:action %)) :why))
                     (get-in doc [:data :transitions]))]
        (is (= "The hedge is trimmed." (get by "finish")))
        (is (= "Rosa asked for it." (get by "create")))))))

(deftest waymark-invoke-s-why-rides-the-request
  (let [{:keys [eng h]} (world)
        id (id-of (req h :post "/api/chores" {:headers rosa :body {:title "Sweep"}}))
        done (req h :post (str "/api/chores/" id "/-/finish")
                  {:headers rosa :extra {:waymark10/why "Swept before the guests came."}})]
    (is (= 200 (:status done)) (pr-str (json done)))
    (is (= "Swept before the guests came."
           (get-in (last (log-of eng :chore id)) [:actor :why])))))

(deftest a-move-without-a-why-writes-none
  (let [{:keys [eng h]} (world)
        id (id-of (req h :post "/api/chores" {:headers rosa :body {:title "Dust"}}))]
    (is (not (contains? (:actor (first (log-of eng :chore id))) :why)))))

(deftest a-why-over-280-characters-is-refused
  (let [{:keys [eng h]} (world)
        id (id-of (req h :post "/api/chores" {:headers rosa :body {:title "Mop"}}))
        long-why (apply str (repeat 281 "a"))
        refused (req h :post (str "/api/chores/" id "/-/finish?why=" long-why)
                     {:headers rosa})]
    (is (= 422 (:status refused)) (pr-str (json refused)))
    (is (= [:create] (mapv :action (log-of eng :chore id)))
        "and the move with it")
    (testing "280 is still one sentence"
      (is (= 200 (:status (req h :post (str "/api/chores/" id "/-/finish?why="
                                            (apply str (repeat 280 "a")))
                               {:headers rosa})))))))

;; ── a held call keeps the caller's why ──────────────────────────────────

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private mayor-scope
  [{:kind "seat" :actions ["create" "restate"]}])

(def ^:private ceiling
  {:scope [{:kind "chore" :actions ["create" "finish"]}]
   :budget_usd_per_week 5
   :sitting_budget_tokens 100000})

(defn- seat-body [nm extra]
  (merge {:name nm
          :charter "Keep the house's chores."
          :scope [{:kind "chore" :actions ["create" "finish"]}]
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 2
          :sitting_budget_tokens 60000}
         extra))

(deftest a-held-call-keeps-the-callers-why
  (let [{:keys [eng h]} (world)
        made (req h :post "/api/seats"
                  {:headers person
                   :body (seat-body "mayor" {:scope mayor-scope :delegates ceiling})})
        _ (is (= 201 (:status made)) (pr-str (json made)))
        mayor (id-of made)
        sitter (str "seat:" mayor)
        agent {"x-waymark-principal" sitter
               "x-waymark-actor-type" "agent"
               "x-waymark-acts-for" "colton"}
        asked (req h :post "/api/approval_requests"
                   {:headers agent :body {:task "Keep the house." :seat "mayor"}})
        approved (req h :post (str "/api/approval_requests/" (id-of asked) "/-/approve")
                      {:headers person})
        as (assoc agent "x-waymark-grant" (get-in (json approved) [:data :grant_id]))
        etag (get-in (json (req h :get (str "/api/seats/" mayor) {:headers as}))
                     [:meta :etag])
        held (req h :post (str "/api/seats/" mayor
                               "/-/restate?why=Colton%20wants%20a%20bigger%20budget.")
                  {:headers (assoc as "if-match" etag)
                   :body (dissoc (seat-body "x" {:scope mayor-scope :delegates ceiling
                                                 :budget_usd_per_week 50})
                                 :name)})]
    (is (= 202 (:status held)) (pr-str (json held)))
    (is (= "Colton wants a bigger budget."
           (get-in (dev/row eng :held_call (:held_call (json held))) [:data :why])))))
