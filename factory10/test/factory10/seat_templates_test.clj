(ns factory10.seat-templates-test
  "Every factorykit seat template that sits in a domain, filled and
  created through the seat kind's create door against a real domain
  row, reads its computed `in_domain` back as that domain's name
  (ticket f7d7c8a3): the template writes `:domain`, the ref, and not
  `:in_domain`.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [example.org :as example]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [waymark10.dev :as dev]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seed :as seed]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(def ^:private names ["draft-judge" "ops-seat" "sitting-judge" "mayor"])

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(defn- req [h method uri body]
  (h (cond-> {:request-method method :uri uri :headers person}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- edn-resource [path]
  (edn/read-string (slurp (io/resource path))))

(defn- fill
  "The template's `part` with each hole its params name filled from
  `values`."
  [t part values]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") (get values k)))
           (pr-str (get t part))
           (keys (get-in t [:template :params])))))

(defn- made! [h plural body]
  (let [made (req h :post (str "/api/" plural) body)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- judgment!
  "The judgment template `jn`, created and promoted. → its id."
  [eng h jn]
  (let [id (made! h "judgments"
                  (fill (edn-resource (str "judgments/" jn ".edn")) :judgment
                        {:ticket_repo "ckopsa/waymark"}))]
    (inv/invoke! eng :judgment id :promote {} {:principal a-person})
    id))

(defn- powers!
  "Each power token the templates' scopes name, registered, so the
  scope names real kinds as it does on the house."
  [eng templates]
  (doseq [token (into (sorted-set)
                      (comp (mapcat #(get-in % [:seat :scope]))
                            (map :kind)
                            (filter #(str/includes? % ".")))
                      templates)]
    (inv/create! eng :capability
                 {:token token
                  :description (str token " through Gate.")
                  :enforced_by "gate-mcp"}
                 {:principal a-person})))

(deftest every-domain-template-creates-a-seat-whose-in-domain-is-its-domain
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        domain-id (made! h "domains" {:name "landscaping"
                                      :charter "Run the quotes and the crews."
                                      :budget_usd_per_week 1000})]
    (powers! eng (map #(edn-resource (str "seats/" % ".edn")) names))
    (doseq [n names
            :let [t (edn-resource (str "seats/" n ".edn"))
                  jn (get-in t [:template :judgment_template])
                  ;; short: draft-judge's instructions sit at the cap
                  values {:repos "o/r"
                          :restart_jobs "clone-mcp"
                          :judgment (when jn (judgment! eng h jn))
                          :domain "landscaping"
                          :domain_id domain-id}
                  ;; the mayor's journal is workqueue10's kind, which
                  ;; this engine does not assemble
                  seat (update (fill t :seat values) :scope
                               (partial filterv #(not= "journal" (:kind %))))
                  made (req h :post "/api/seats" seat)]]
      (testing n
        (is (= 201 (:status made)) (pr-str (json made)))
        (let [row (json (req h :get (str "/api/seats/" (id-of made)) nil))]
          (is (= domain-id (get-in row [:data :domain])))
          (is (= "landscaping" (get-in row [:data :in_domain]))))))))

(deftest the-onboarding-seat-reaches-every-door-its-walk-needs-and-nothing-else
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        t (edn-resource "seats/onboarding.edn")
        seat (fill t :seat {:org "rd_tax_services"})
        vocab (into {} (map (fn [[k rd]] [(name k) (inv/action-names rd)]))
                    (inv/resources eng))
        kinds (remove #(str/includes? (:kind %) ".") (:scope seat))
        powers (filter #(str/includes? (:kind %) ".") (:scope seat))]
    (powers! eng [t])
    (testing "it fills and creates through the seat door"
      (let [made (req h :post "/api/seats" seat)]
        (is (= 201 (:status made)) (pr-str (json made)))))
    (testing "its walk and every door its scope names are ones the engine serves"
      (is (contains? vocab (:walk seat)))
      (doseq [{:keys [kind actions]} kinds
              a actions]
        (is (contains? (get vocab kind) a) (str kind "." a))))
    (testing "it holds the walk's doors and no other"
      (is (= {"org_plan" #{"create" "ask" "log_workarounds"
                           "propose" "go_live" "retire"}
              "change" #{"submit"}}
             (into {} (map (juxt :kind (comp set :actions))) kinds)))
      (is (= {:state "drafting,proposed,simulated,going_live" :name "rd_tax_services"}
             (:filter (first (filter #(= "org_plan" (:kind %)) kinds))))
          "the walk is its own org's drafting, proposed, simulated and going_live plans: propose leaves one, go_live's Allow the next, a merged change is minted again on the last (ticket 87fc7996), and a proposed plan still logs its workarounds (ticket beb733af)"))
    (testing "its bench reaches the org's directory in ckopsa/waymark, and no live power"
      (is (seq powers))
      (doseq [{:keys [kind filter]} powers]
        (is (str/starts-with? kind "bench.") kind)
        (is (= "ckopsa/waymark" (:repo filter)) kind)
        (when (#{"bench.find" "bench.read"} kind)
          (is (= "orgs/rd_tax_services/**,orgkit/**,orgs/example/**,factorykit/*/org-*.edn,factorykit/*/world.edn,docs/orgkit.md,docs/spec-related-row-law.md,waymark10/*/dsl.clj,CLAUDE.md"
                 (:path filter))
              kind))
        (when (= "bench.edit" kind)
          (is (= "orgs/rd_tax_services/**" (:path filter)) kind)))
      (is (every? (set (map :kind powers)) ["bench.feedback" "bench.pull"])
          "it reads red CI and follows main"))
    (testing "its instructions name only bench tools its scope holds, and stay short"
      (let [held (set (map :kind powers))]
        (doseq [[_ tool] (re-seq #"bench__(\w+)" (str (:charter seat) (:instructions seat)))]
          (is (contains? held (str "bench." tool)) tool)))
      (is (< (count (:charter seat)) 1200))
      (is (<= (count (:charter seat)) 1150) "filled for rd_tax_services, it leaves room under the cap")
      (is (< (count (:instructions seat)) 2000)))))

(def ^:private agent-headers
  "The onboarding seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "onboarding"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(defn- req-as [h method uri headers body]
  (h (cond-> {:request-method method :uri uri :headers headers}
       body (assoc :body (wire/write-json body)))))

(defn- served? [resp] (contains? #{200 201 202} (:status resp)))

(deftest the-onboarding-seats-grant-asks-go-live-and-it-comes-back-held
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})
        h (dev/handler eng)
        entry (first (filter #(= "org_plan" (:kind %))
                             (:scope (fill (edn-resource "seats/onboarding.edn") :seat
                                           {:org "greenleaf"}))))
        asked (req-as h :post "/api/approval_requests" agent-headers
                      {:task "Walk greenleaf's plan." :scope [entry]})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked) "/-/approve") nil)
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))
        as (assoc agent-headers "x-waymark-grant" (get-in (json approved) [:data :grant_id]))
        id (made! h "org_plans" {:name "greenleaf" :brief "A six-person landscaping business."})
        state #(:state (json (req h :get (str "/api/org_plans/" id) nil)))
        door #(req-as h :post (str "/api/org_plans/" id "/-/" %1) as %2)]
    (testing "it files its person's first plan with create"
      (let [filed (req-as h :post "/api/org_plans" as
                          {:name "bluefern" :brief "A four-person bakery."})]
        (is (= 201 (:status filed)) (pr-str (json filed)))))
    (testing "it cannot record findings"
      (is (not (served? (door "record_findings" {:findings []}))))
      (is (= "drafting" (state))))
    (walk/proposed! eng id a-person)
    (testing "it cannot attach the film"
      (is (not (served? (door "attach_film" {:film_url "https://example.com/g.mp4"}))))
      (is (= "proposed" (state))))
    (inv/invoke! eng :org_plan id :hand_in_film
                 {:film_url "https://example.com/g.mp4" :take_report "{}" :head_sha walk/a-head}
                 {:principal a-person})
    (inv/invoke! eng :org_plan id :attach_film {:film_url "https://example.com/g.mp4"}
                 walk/the-engine)
    (walk/flipped! eng id walk/a-head)
    (testing "it cannot mark the plan live"
      (is (not (served? (door "mark_live" {}))))
      (is (= "simulated" (state))))
    (testing "its go_live on a simulated plan comes back held, not done"
      (let [held (door "go_live" {})]
        (is (= 202 (:status held)) (pr-str (json held)))
        (is (true? (:held (json held))))
        (is (= "simulated" (state)))))))

(deftest the-org-mayor-fills-for-orgs-example-and-keeps-to-the-org
  (let [eng (dev/scratch! (-> (vec (main/resources)) (into (example/resources)) (conj caps/capability))
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        domain-id (made! h "domains" {:name "example"
                                      :charter "Example Landscaping"
                                      :budget_usd_per_week 1000})
        org-kinds (sort (map (comp name :kind) (example/resources)))
        t (edn-resource "seats/org-mayor.edn")
        seat (seed/filled (:seat t)
                          {:org "example" :domain "example" :domain_id domain-id
                           :owner "owner" :budget_usd_per_week 8M
                           :kinds (mapv (fn [k] {:kind k :actions []}) org-kinds)
                           :wakes []}
                          (:template t))]
    (testing "its :cues are optional, the wakes when left out, and a cue may carry its own note"
      (let [wake {:kind "request" :actions ["create"]}
            cue (assoc wake :note "A request came in.")
            given {:org "example" :domain "example" :domain_id domain-id
                   :owner "owner" :budget_usd_per_week 8M :kinds [] :wakes [wake]}]
        (is (= {:cues [wake]} (:inbox (seed/filled (:seat t) given (:template t)))))
        (is (= {:cues [cue]}
               (:inbox (seed/filled (:seat t) (assoc given :cues [cue]) (:template t)))))))
    (testing "it fills with no hole left, its budget a decimal"
      (is (nil? (re-find #"\{\{" (pr-str seat))))
      (is (= 8M (:budget_usd_per_week seat)))
      (is (= "example-mayor" (:name seat))))
    (testing "it creates through the seat door, in the org's domain"
      (let [made (req h :post "/api/seats" seat)]
        (is (= 201 (:status made)) (pr-str (json made)))
        (is (= "example" (get-in (json (req h :get (str "/api/seats/" (id-of made)) nil))
                                 [:data :in_domain])))))
    (testing "its scope reads the org's kinds, its domain's seats and the held calls, and holds no door"
      (is (= (into #{"seat" "held_call"} org-kinds) (set (map :kind (:scope seat)))))
      (is (every? (comp empty? :actions) (:scope seat)))
      (is (= {:domain domain-id}
             (:filter (first (filter #(= "seat" (:kind %)) (:scope seat)))))))
    (testing "given :repos it is born holding ticket on them and create on the house's; without, no ticket door"
      (let [given {:org "example" :domain "example" :domain_id domain-id
                   :owner "owner" :budget_usd_per_week 8M :kinds [] :wakes []}
            born (seed/filled (:seat t) (assoc given :repos ["ckopsa/example"]) (:template t))
            tickets (fn [scope] (into #{} (comp (filter #(= "ticket" (str (:kind %))))
                                                (map #(select-keys % [:actions :filter])))
                                      scope))
            both #{{:actions ["create" "groom" "prioritize" "restate" "drop"]
                    :filter {:repo "ckopsa/example"}}
                   {:actions ["create"] :filter {:repo "ckopsa/waymark"}}}]
        (is (= both (tickets (:scope born))))
        (is (empty? (tickets (:scope seat))))
        (let [made (req h :post "/api/seats" (assoc born :name "example-mayor-repos"))]
          (is (= 201 (:status made)) (pr-str (json made)))
          (is (= both (tickets (get-in (json (req h :get (str "/api/seats/" (id-of made)) nil))
                                       [:data :scope])))))))))

(deftest the-org-seat-fills-for-orgs-example-and-holds-only-the-hired-doors
  (let [eng (dev/scratch! (-> (vec (main/resources)) (into (example/resources)) (conj caps/capability))
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        domain-id (made! h "domains" {:name "example"
                                      :charter "Example Landscaping"
                                      :budget_usd_per_week 1000})
        t (edn-resource "seats/org-seat.edn")
        given {:org "example" :domain "example" :domain_id domain-id
               :owner "owner" :budget_usd_per_week 5M
               :gap_kind "request" :gap_state "new"
               :kinds [{:kind "request" :actions ["accept"]}]
               :charter "You accept each new request whose detail says what the work is."}
        seat (seed/filled (:seat t) given)]
    (testing "it fills with no hole left, its budget a decimal"
      (is (nil? (re-find #"\{\{" (pr-str seat))))
      (is (= 5M (:budget_usd_per_week seat)))
      (is (= "example-request-new" (:name seat))))
    (testing "it creates through the seat door, in the org's domain"
      (let [made (req h :post "/api/seats" seat)]
        (is (= 201 (:status made)) (pr-str (json made)))
        (is (= "example" (get-in (json (req h :get (str "/api/seats/" (id-of made)) nil))
                                 [:data :in_domain])))))
    (testing "its scope is exactly the hired kinds, and it wakes on the gap's state"
      (is (= [{:kind "request" :actions ["accept"]}] (:scope seat)))
      (is (= [{:kind "request" :actions [] :filter {:state "new"} :at_least 1}]
             (seed/filled (:wakes t) given))))))

(deftest the-director-seat-hands-in-the-film-and-takes-no-engine-door
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        t (edn-resource "seats/director.edn")
        seat (fill t :seat {})
        vocab (into {} (map (fn [[k rd]] [(name k) (inv/action-names rd)]))
                    (inv/resources eng))
        kinds (remove #(str/includes? (:kind %) ".") (:scope seat))]
    (powers! eng [{:seat seat}])
    (testing "it fills and creates through the seat door"
      (let [made (req h :post "/api/seats" seat)]
        (is (= 201 (:status made)) (pr-str (json made)))))
    (testing "its walk and every door its scope names are ones the engine serves"
      (is (contains? vocab (:walk seat)))
      (doseq [{:keys [kind actions]} kinds
              a actions]
        (is (contains? (get vocab kind) a) (str kind "." a))))
    (testing "on a plan it holds the hand-in and neither of the engine's doors"
      (is (= {"org_plan" #{"hand_in_film"} "change" #{}}
             (into {} (map (juxt :kind (comp set :actions))) kinds)))
      (is (= {:state "proposed"}
             (:filter (first (filter #(= "org_plan" (:kind %)) kinds))))
          "the walk is the proposed plans"))
    (testing "it wakes on every door that leaves a plan proposed: propose, refilm and void_take (ticket 82a8c2b6)"
      (is (= [{:kind "org_plan" :actions ["propose" "refilm" "void_take"]}]
             (fill t :wakes {}))))
    (testing "its powers are the clone door's six: it calls the clone to write the week, and sends the outside world's events"
      (is (= #{"clone.up" "clone.call" "clone.perform" "clone.film" "clone.event" "clone.down"}
             (into #{} (comp (map :kind) (filter #(str/includes? % ".")))
                   (:scope seat)))))
    (testing "its instructions name only the tools its scope holds"
      (let [named (into #{} (map (fn [[_ k a]] (str k "." a)))
                        (re-seq #"\b([a-z]+)__([a-z_]+)" (:instructions seat)))]
        (is (= #{"clone.up" "clone.call" "clone.perform" "clone.film" "clone.event" "clone.down"} named))))
    (testing "it plays the org's week.edn and improvises no week (ticket 4c70e261)"
      (is (str/includes? (:instructions seat) "week.edn"))
      (is (str/includes? (:instructions seat) "week_sha"))
      (is (not (str/includes? (:instructions seat) "1 to 3"))))))
