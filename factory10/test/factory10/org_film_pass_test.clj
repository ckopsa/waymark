(ns factory10.org-film-pass-test
  "The director hands in the film; the engine judges it (ticket
  e552de65): the director's hand_in_film keeps the take and moves the
  plan to filming, and its hand takes neither attach_film nor
  record_film_findings; the forge's film pass lands a green take to
  simulated and a red one to drafting with its findings, once each.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.sources.forge :as forge]
            [waymark10.dev :as dev]
            [waymark10.server.film-rules :as film-rules]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge
  (t/principal {:id "factory10-source" :type :system
                :display "factory10-source"}))

(def ^:private a-director
  (t/principal {:id "director" :type :agent :display "director"}))

(def ^:private director-headers
  "The director seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "director"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private rules
  [{:name "chrome-leaks" :metric "chrome_leaks" :op "=" :threshold 0
    :scope "film" :severity "fail" :origin "craft"
    :why "No /api path, uuid or raw key shows on screen."}])

(def ^:private green-take "{\"film\": {\"chrome_leaks\": 0, \"dead_air_s\": 0.4}}")
(def ^:private red-take "{\"film\": {\"chrome_leaks\": 2, \"dead_air_s\": 0.4}}")

(def ^:private head "5e469c6166edf6a3f28ae2e6bdb88b775025f14d")

(defn- proposed!
  "A plan its owner filed over the wire and put up. → its id."
  [h eng org]
  (let [made (req h :post "/api/org_plans"
                  {:headers person :body {:name org}})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        id (id-of made)]
    (walk/proposed! eng id the-owner)
    id))

(defn- director!
  "The director's ask for its one door, approved by its person. → the
  headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers director-headers
                    :body {:task "Hand in the film of a proposed plan."
                           :scope [{:kind "org_plan" :actions ["hand_in_film"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc director-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(defn- film-of [id] (str "https://films.example.test/" id ".mp4"))

(deftest the-director-hands-in-and-the-engine-lands-each-take-once
  (let [{:keys [h eng]} (world)
        as (director! h)
        green (proposed! h eng "greenleaf")
        red (proposed! h eng "acme")
        plan #(json (req h :get (str "/api/org_plans/" %) {:headers person}))
        state #(name (:state (plan %)))
        hand-in! #(req h :post (str "/api/org_plans/" %1 "/-/hand_in_film")
                       {:headers as
                        :body {:film_url (film-of %1) :take_report %2
                               :head_sha head}})
        pass! #(forge/film-pass! eng {} (fn [& _] nil)
                                 (fn [_ tk] (film-rules/judge rules tk)))]
    (testing "a proposed plan with no take lands nothing"
      (is (nil? (:films (pass!))))
      (is (= "proposed" (state green))))
    (testing "the director's hand-in keeps the take and decides nothing"
      (let [done (hand-in! green green-take)]
        (is (= 200 (:status done)) (pr-str (json done))))
      (is (= 200 (:status (hand-in! red red-take))))
      (let [data (:data (plan green))]
        (is (= "filming" (state green)))
        (is (= "filming" (state red)))
        (is (= (film-of green) (:take_film_url data)))
        (is (= green-take (:take_report data)))
        (is (= head (:take_sha data)))
        (is (nil? (:film_url data)) "no film is attached until it is judged")))
    (testing "the director's hand takes neither of the engine's doors"
      (doseq [[door body] [[:attach_film {:film_url (film-of green)}]
                           [:record_film_findings
                            {:head_sha head
                             :findings [{:key "film/chrome-leaks" :severity "error"}]}]]]
        (let [no (req h :post (str "/api/org_plans/" green "/-/" (name door))
                      {:headers as :body body})]
          (is (not= 200 (:status no)) (name door)))
        (try (inv/invoke! eng :org_plan green door body {:principal a-director})
             (catch clojure.lang.ExceptionInfo _ nil))
        (is (= "filming" (state green)) (name door))))
    (testing "the pass lands a green take to simulated and a red one to drafting"
      (is (= 2 (:films (pass!))))
      (is (= "simulated" (state green)))
      (is (= (film-of green) (get-in (plan green) [:data :film_url])))
      (is (= "drafting" (state red)))
      (is (= ["film/chrome-leaks"]
             (mapv :key (get-in (plan red) [:data :findings]))))
      (is (= head (get-in (plan red) [:data :findings_sha])))
      (is (nil? (get-in (plan red) [:data :film_url]))))
    (testing "a landed take is not judged again"
      (inv/invoke! eng :org_plan red :record_findings
                   {:head_sha head :findings []} {:principal the-forge})
      (inv/invoke! eng :org_plan red :propose {} {:principal the-owner})
      (is (= "proposed" (state red)))
      (is (nil? (:films (pass!))))
      (is (= "proposed" (state red))))
    (testing "a new take is judged anew"
      (is (= 200 (:status (hand-in! red green-take))))
      (is (= 1 (:films (pass!))))
      (is (= "simulated" (state red))))))

(def ^:private stopped-take
  "{\"film\": {\"chrome_leaks\": 0}, \"shots\": [{\"id\": \"s1\", \"ok\": true}, {\"id\": \"s2\", \"ok\": false, \"why\": \"missing shot.caption\"}, {\"id\": \"s3\"}]}")

(def ^:private passed-take
  "{\"film\": {\"chrome_leaks\": 0}, \"shots\": [{\"id\": \"s1\", \"ok\": true}, {\"id\": \"s2\", \"ok\": true}]}")

(deftest a-take-stopped-on-a-failed-shot-is-voided-not-judged
  (let [{:keys [h eng]} (world)
        as (director! h)
        stopped (proposed! h eng "stopped")
        passed (proposed! h eng "passed")
        plan #(json (req h :get (str "/api/org_plans/" %) {:headers person}))
        state #(name (:state (plan %)))
        hand-in! #(req h :post (str "/api/org_plans/" %1 "/-/hand_in_film")
                       {:headers as
                        :body {:film_url (film-of %1) :take_report %2
                               :head_sha head}})
        pass! #(forge/film-pass! eng {} (fn [& _] nil)
                                 (fn [_ tk] (film-rules/judge rules tk)))]
    (is (= 200 (:status (hand-in! stopped stopped-take))))
    (is (= 200 (:status (hand-in! passed passed-take))))
    (let [census (pass!)]
      (testing "a take with a failed shot voids the take and records take_stopped"
        (is (= 1 (:voided census)))
        (is (= "proposed" (state stopped)) "not drafting: propose is not shut")
        (let [data (:data (plan stopped))
              f (first (filter #(= "take_stopped" (:key %)) (:findings data)))]
          (is (= "warning" (:severity f)))
          (is (str/includes? (:sentence f) "s2"))
          (is (str/includes? (:sentence f) "missing shot.caption"))
          (is (not-any? #(= "error" (:severity %)) (:findings data)))
          (testing "with take_sha cleared, so the same head may be filmed again"
            (is (nil? (:take_sha data)))
            (is (nil? (:take_report data))))))
      (testing "a take whose shots all passed attaches"
        (is (= 1 (:films census)))
        (is (= "simulated" (state passed)))))
    (testing "the voided plan's head is filmed again"
      (is (= 200 (:status (hand-in! stopped passed-take))))
      (is (= 1 (:films (pass!))))
      (is (= "simulated" (state stopped))))))
