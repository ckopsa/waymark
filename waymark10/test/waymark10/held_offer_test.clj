(ns waymark10.held-offer-test
  "The engine's own offer (ticket d98f3f38, R-P.5.3): one held call to a
  named person, with choices on more than one row. The allow walks the
  chosen door on its own row as the person, a door the person could not
  walk is refused at create, one (source, key) proposes once, and no
  seat reaches the create.

  No database: dev/scratch! is the whole world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.expiry :as expiry]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(r/defhandler as-it-is [row _inp _ctx] row)

(r/defresource change
  {:kind :ho_change
   :plural "ho_changes"
   :states [:open :parked :merged :closed]
   :initial :open
   :terminal #{:merged :closed}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:merge {:from #{:open} :to :merged
            :handler as-it-is
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A merged change is in."}
            :display {:label "Merge"}}
    :park {:from #{:open} :to :parked
           :handler as-it-is
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A parked change waits for later."}
           :display {:label "Park"}}
    :close {:from #{:open :parked} :to :closed
            :handler as-it-is
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A closed change is let go."}
            :display {:label "Close"}}}})

(defn- world []
  (let [eng (dev/scratch! [change])]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (h (cond-> {:request-method method :uri uri :headers (or headers {})}
        body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- made! [h title]
  (let [made (req h :post "/api/ho_changes" {:headers person :body {:title title}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- state-of [h cid]
  (:state (json (req h :get (str "/api/ho_changes/" cid) {:headers person}))))

(defn- held-count [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (count (store/query-rows st tx :held_call {} {:limit 50}))))))

(defn- offer [about promise]
  {:person "colton"
   :source "promise:p1" :key "breach-1"
   :why "The promise is breached, and only you can fix it."
   :choices [{:kind :ho_change :id about :door :merge :label "Fix it"}
             {:kind :ho_change :id promise :door :park :label "Watch it"}
             {:kind :ho_change :id promise :door :close :label "Retire it"}]})

(deftest the-engine-offers-three-choices-on-two-rows
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")
        promise (made! h "Answer within a day")
        call (held/offer! eng (offer about promise))
        doc (json (req h :get (str "/api/held_calls/" (:id call)) {:headers person}))]
    (is (= "held" (:state doc)) (pr-str doc))
    (is (= [["ho_change" about "merge"] ["ho_change" promise "park"]
            ["ho_change" promise "close"]]
           (mapv (juxt :kind :id :door) (get-in doc [:data :choices]))))
    (is (str/includes? (str (get-in doc [:data :choices 0 :summary])) "Fold the sweep")
        "each choice shows its row's summary")
    (is (str/includes? (str (get-in doc [:data :choices 1 :summary])) "Answer within a day"))))

(deftest allowing-choice-two-walks-that-door-alone-as-the-person
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")
        promise (made! h "Answer within a day")
        call (held/offer! eng (offer about promise))
        resp (req h :post (str "/api/held_calls/" (:id call) "/-/allow")
                  {:headers person :body {:choice 2}})]
    (is (= 200 (:status resp)) (pr-str (json resp)))
    (is (= "done" (:state (json (req h :get (str "/api/held_calls/" (:id call))
                                     {:headers person})))))
    (is (= "closed" (state-of h promise)) "the third choice, on its own row")
    (is (= "open" (state-of h about)) "and nothing else")))

(deftest a-choice-the-person-cannot-walk-is-refused-at-create
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")
        promise (made! h "Answer within a day")
        _ (req h :post (str "/api/ho_changes/" about "/-/close") {:headers person})]
    (is (thrown? clojure.lang.ExceptionInfo (held/offer! eng (offer about promise))))
    (is (= 0 (held-count eng)) "no dead button is offered")))

(deftest a-call-is-withdrawn-when-any-of-its-rows-moves-on
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")
        promise (made! h "Answer within a day")
        call (held/offer! eng (offer about promise))
        doc (json (req h :get (str "/api/held_calls/" (:id call)) {:headers person}))]
    (is (= ["open" "open" "open"] (mapv :raised_in (get-in doc [:data :choices])))
        "each choice keeps its own row's state at birth")
    (expiry/sweep! eng {:kinds #{:held_call}})
    (is (= "held" (:state (json (req h :get (str "/api/held_calls/" (:id call))
                                     {:headers person}))))
        "no row moved, so the call stands")
    (req h :post (str "/api/ho_changes/" promise "/-/close") {:headers person})
    (expiry/sweep! eng {:kinds #{:held_call}})
    (is (= "withdrawn" (:state (json (req h :get (str "/api/held_calls/" (:id call))
                                          {:headers person}))))
        "the promise row left open while the about row stood")
    (is (= "open" (state-of h about)) "and no door was walked")))

(deftest a-second-offer-with-the-same-key-is-a-no-op
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")
        promise (made! h "Answer within a day")]
    (held/offer! eng (offer about promise))
    (held/offer! eng (offer about promise))
    (is (= 1 (held-count eng)))))

(deftest a-seat-cannot-call-the-engine-create
  (let [{:keys [h eng]} (world)
        about (made! h "Fold the sweep")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/create! eng :held_call
                              {:tool "ho_change.merge" :why "Let me."
                               :caller "waymark10-held-calls" :forward {}
                               :door {:kind "ho_change" :action "merge" :id about
                                      :for_member "colton"}
                               :choices [{:kind "ho_change" :id about :door "merge"}]
                               :owner "colton"}
                              {:principal (t/principal {:id "seat:mayor" :type :agent
                                                        :display "seat:mayor"})})))
    (is (= 0 (held-count eng)))))
