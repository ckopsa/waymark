(ns waymark10.held-band-test
  "A row some held call waits on (ticket f6808194), end to end over the
  ring handler on a memory engine: the call records its row, the row's
  live envelope lists it under `held` with its action's label, the row
  page draws the band with Allow, and the owner's Allow lands the call
  and the band clears.

  No database: dev/scratch! is the whole world."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.held-calls :as held]
            [waymark10.wire :as wire]))

(r/defhandler engage-prospect [row _inp _ctx]
  (assoc-in row [:data :engaged] true))

(r/defresource prospect
  {:kind :hb_prospect
   :plural "hb_prospects"
   :states [:qualified :engaged]
   :initial :qualified
   :terminal #{:engaged}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:engaged {:optional true} [:maybe :boolean]]]
   :actions
   {:engage {:from #{:qualified} :to :engaged
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An engaged prospect is a client."}
             :handler engage-prospect
             :display {:label "Engage"}}}})

(defn- world []
  (let [eng (dev/scratch! [prospect])]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> {:request-method method :uri path :headers (or headers {})}
          query (assoc :query-string query)
          body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- held-on!
  "A prospect, and a seat's engage of it held for colton's tap.
  → [prospect-id held-call-row]"
  [h eng]
  (let [made (req h :post "/api/hb_prospects"
                  {:headers person :body {:name "Acme Robotics"}})
        pid (id-of made)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    [pid (held/hold-door! eng {:kind :hb_prospect :action :engage :id pid
                               :body {}
                               :caller "seat:prospect-author"
                               :owner "colton"
                               :why "Acme does contract research."})]))

(defn- row-of [h pid]
  (json (req h :get (str "/api/hb_prospects/" pid) {:headers person})))

(deftest a-held-call-records-its-row
  (let [{:keys [h eng]} (world)
        [pid call] (held-on! h eng)]
    (is (= (str "hb_prospect:" pid) (get-in call [:data :row])))
    (testing "and the row field filters the held calls"
      (let [resp (req h :get (str "/api/held_calls?row=hb_prospect:" pid)
                      {:headers person})
            items (get-in (json resp) [:data :items])]
        (is (= 200 (:status resp)) (pr-str (json resp)))
        (is (= 1 (count items)))
        (is (str/ends-with? (str (:self (first items))) (str (:id call))))))))

(deftest the-rows-envelope-lists-its-held-call
  (let [{:keys [h eng]} (world)
        [pid call] (held-on! h eng)
        [entry :as held] (:held (row-of h pid))]
    (is (= 1 (count held)))
    (is (= (str (:id call)) (:id entry)))
    (is (= "engage" (:action entry)))
    (is (= "Engage" (:label entry)))
    (is (= "Acme does contract research." (:why entry)))
    (is (some? (:created_at entry)))
    (testing "a summary read does not carry it"
      (is (nil? (:held (json (req h :get (str "/api/hb_prospects/" pid "?depth=summary")
                                  {:headers person}))))))))

(deftest the-row-page-draws-the-band-with-allow
  (let [{:keys [h]} (world)
        body (:body (req h :get "/api/-/ui"))]
    (is (str/includes? body "if ((doc.held || []).length) panel.append(heldBand(doc));"))
    (is (str/includes? body "\"data-focus\": \"held\""))
    (is (str/includes? body "`Waiting on your tap: ${h.label || title(h.action || \"allow\")} ${what}`"))
    (is (str/includes? body "if (h.why) line.append(el(\"div\", {class: \"muted\"}, h.why));"))
    (is (str/includes? body "}}, \"Allow\"));"))
    (is (str/includes? body "\"Allowed by \", deciderRef(decider)"))
    (is (str/includes? body "el(\"tr\", {\"data-field\": k},"))))

(deftest allowing-it-as-the-owner-lands-the-call-and-the-band-clears
  (let [{:keys [h eng]} (world)
        [pid call] (held-on! h eng)
        resp (req h :post (str "/api/held_calls/" (:id call) "/-/allow")
                  {:headers person})]
    (is (= 200 (:status resp)) (pr-str (json resp)))
    (is (= "done" (:state (json (req h :get (str "/api/held_calls/" (:id call))
                                     {:headers person})))))
    (let [row (row-of h pid)]
      (is (= "engaged" (:state row)))
      (is (nil? (:held row)) "nothing waits on the row now"))))

(deftest an-allowed-band-names-the-decider-by-display-never-by-id
  (let [{:keys [h]} (world)
        body (:body (req h :get "/api/-/ui"))]
    (testing "the band reads the held call's decided_by, not the viewer's id"
      (is (str/includes? body "((r.body || {}).data || {}).decided_by"))
      (is (not (str/includes? body "`Allowed by ${"))))
    (testing "the decider is the member row's summary, linked, else \"a member\""
      (is (str/includes? body "function deciderRef(v) {"))
      (is (str/includes? body "const span = el(\"span\", {}, \"a member\");"))
      (is (str/includes? body "memberSummary(id) : rowSummary(kind, id)).then(s => {"))
      (is (str/includes? body "const a = resourceRef(kind, id, s);")))
    (testing "no uuid is the placeholder"
      (is (not (re-find #"function deciderRef[\s\S]{0,400}el\(\"span\", \{[^}]*title: String\(id\)" body))))))
