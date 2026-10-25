(ns waymark10.held-choice-test
  "A held call that offers several doors on one row (ticket 50351c4c),
  end to end over the ring handler on a memory engine: the call shows
  every choice, the person's allow names the one walked, an allow that
  names none refuses, a row that moves on withdraws the call, and a
  call of one door is as it was.

  No database: dev/scratch! is the whole world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.expiry :as expiry]
            [waymark10.server.held-calls :as held]
            [waymark10.wire :as wire]))

(r/defhandler as-it-is [row _inp _ctx] row)

(r/defresource change
  {:kind :hc_change
   :plural "hc_changes"
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

(defn- made! [h]
  (let [made (req h :post "/api/hc_changes"
                  {:headers person :body {:title "Fold the sweep"}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- offered!
  "A change, and the mayor's three doors on it held for colton's tap.
  → [change-id held-call-row]"
  [h eng]
  (let [cid (made! h)]
    [cid (held/hold-door! eng {:kind :hc_change :id cid
                               :choices [{:door :merge :label "Merge it"}
                                         {:door :park :label "Park it for later"}
                                         {:door :close :label "Close it"}]
                               :proposed 1
                               :caller "seat:mayor"
                               :owner "colton"
                               :why "The checks are green, and the owner asked to wait."})]))

(defn- call-of [h id]
  (json (req h :get (str "/api/held_calls/" id) {:headers person})))

(defn- state-of [h cid]
  (:state (json (req h :get (str "/api/hc_changes/" cid) {:headers person}))))

(deftest a-call-with-three-choices-shows-all-three
  (let [{:keys [h eng]} (world)
        [_ call] (offered! h eng)
        doc (call-of h (:id call))]
    (is (= ["merge" "park" "close"] (mapv :door (get-in doc [:data :choices]))))
    (is (= ["Merge it" "Park it for later" "Close it"]
           (mapv :label (get-in doc [:data :choices]))))
    (is (= 1 (get-in doc [:data :proposed])))
    (is (= "The checks are green, and the owner asked to wait." (get-in doc [:data :why])))
    (is (= "open" (get-in doc [:data :raised_in])) "the row's state at birth")))

(deftest the-allow-walks-exactly-the-chosen-door
  (let [{:keys [h eng]} (world)
        [cid call] (offered! h eng)
        resp (req h :post (str "/api/held_calls/" (:id call) "/-/allow")
                  {:headers person :body {:choice 1}})]
    (is (= 200 (:status resp)) (pr-str (json resp)))
    (let [doc (call-of h (:id call))]
      (is (= "done" (:state doc)) (pr-str (get-in doc [:data :reason])))
      (is (= 1 (get-in doc [:data :chosen]))))
    (is (= "parked" (state-of h cid)) "the second choice, and no other")))

(deftest an-allow-without-a-choice-refuses-naming-the-field
  (let [{:keys [h eng]} (world)
        [cid call] (offered! h eng)
        resp (req h :post (str "/api/held_calls/" (:id call) "/-/allow")
                  {:headers person})]
    (is (<= 400 (:status resp) 499) (pr-str (json resp)))
    (is (str/includes? (pr-str (json resp)) "choice"))
    (is (= "held" (:state (call-of h (:id call)))))
    (is (= "open" (state-of h cid)) "no door was walked")))

(deftest a-row-that-moves-on-withdraws-the-call
  (let [{:keys [h eng]} (world)
        [cid call] (offered! h eng)
        moved (req h :post (str "/api/hc_changes/" cid "/-/park") {:headers person})]
    (is (= 200 (:status moved)) (pr-str (json moved)))
    (expiry/sweep! eng {:kinds #{:held_call}})
    (let [doc (call-of h (:id call))]
      (is (= "withdrawn" (:state doc)))
      (is (str/includes? (str (get-in doc [:data :reason])) "open")
          "the caller's row says why"))
    (testing "a row still in raised_in keeps its call"
      (let [[_ other] (offered! h eng)]
        (expiry/sweep! eng {:kinds #{:held_call}})
        (is (= "held" (:state (call-of h (:id other)))))))))

(deftest a-call-of-one-door-is-as-it-was
  (let [{:keys [h eng]} (world)
        cid (made! h)
        call (held/hold-door! eng {:kind :hc_change :action :merge :id cid :body {}
                                   :caller "seat:mayor" :owner "colton"
                                   :why "The checks are green."})]
    (is (nil? (get-in call [:data :raised_in])))
    (is (nil? (get-in call [:data :choices])))
    (testing "a sweep after the row moved does not withdraw it"
      (let [other-cid (made! h)
            other (held/hold-door! eng {:kind :hc_change :action :close :id other-cid
                                        :body {} :caller "seat:mayor" :owner "colton"
                                        :why "Let it go."})]
        (req h :post (str "/api/hc_changes/" other-cid "/-/park") {:headers person})
        (expiry/sweep! eng {:kinds #{:held_call}})
        (is (= "held" (:state (call-of h (:id other)))))))
    (let [resp (req h :post (str "/api/held_calls/" (:id call) "/-/allow")
                    {:headers person})]
      (is (= 200 (:status resp)) (pr-str (json resp)))
      (is (= "done" (:state (call-of h (:id call)))))
      (is (nil? (get-in (call-of h (:id call)) [:data :chosen])))
      (is (= "merged" (state-of h cid))))))
