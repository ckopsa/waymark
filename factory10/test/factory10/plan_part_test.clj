(ns factory10.plan-part-test
  "The plan_part: an org_plan's tree of parts, end to end over the ring
  handler on a memory engine (ticket 7fc1415a). The owner lays out a
  goal, two branches and four leaves, agrees them in one bulk call,
  restates one and drops a branch with its children; an agent's agree
  is held for the owner's tap; a plan that is not drafting, and a
  parent from another plan, are refused.

  Parts before code (ticket f9252928): the onboarding template's scope
  reaches its own plan's parts by `org` and no other's; a new plan's
  next step counts the parts waiting and its propose waits on an agreed
  tree; a question about a part is drawn under it; a plan stored
  before `parts_required` proposes with none; and the org-live pass
  stamps `org` on a part stored without it.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-live :as org-live]
            [factory10.org-plan-walk :as walk]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-11-14T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (h (cond-> {:request-method method :uri uri :headers (or headers {})}
        body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private agent-headers
  "The onboarding seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "onboarding"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- get-row [h plural id]
  (json (req h :get (str "/api/" plural "/" id) {:headers person})))

(defn- state-of [h id] (:state (get-row h "plan_parts" id)))

(defn- filed!
  "A plan the owner filed over the wire. → its id."
  [h org]
  (let [made (req h :post "/api/org_plans"
                  {:headers person
                   :body {:name org
                          :brief "An R&D tax firm. We run each client's study and file the credit."}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- part
  "A part posted by `headers` on `plan`. → the response."
  [h headers plan body]
  (req h :post "/api/plan_parts" {:headers headers :body (assoc body :plan plan)}))

(defn- part!
  "A part the owner made on `plan`. → its id."
  [h plan body]
  (let [made (part h person plan body)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- granted!
  "The agent's ask for `scope`, the agree door unless named, approved by
  its person. → the headers it presents."
  [h & [scope]]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Ask to work the plan's parts."
                           :scope (or scope [{:kind "plan_part" :actions ["agree"]}])}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(deftest the-two-walls-are-registered-holds
  (is (holds/hold? :only-the-owner-agrees))
  (is (holds/hold? :only-the-owner-drops)))

(deftest the-owner-builds-agrees-restates-and-drops-a-tree
  (let [{:keys [h]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})
        studies (part! h plan {:title "Studies" :parent goal :order 1
                               :home "new_kind" :home_name "study"})
        filing (part! h plan {:title "Filing" :parent goal :order 2})
        leaves [(part! h plan {:title "Interviews" :parent studies})
                (part! h plan {:title "Evidence" :parent studies
                               :home "built_in" :home_name "attachment"})
                (part! h plan {:title "Form 6765" :parent filing})
                (part! h plan {:title "Amended returns" :parent filing})]
        all (into [goal studies filing] leaves)]
    (testing "a person's part is born proposed, stated, with a home"
      (let [g (get-row h "plan_parts" goal)]
        (is (= "proposed" (:state g)))
        (is (= "stated" (get-in g [:data :source])))
        (is (= "undecided" (get-in g [:data :home])))))
    (testing "the owner agrees all seven in one bulk call"
      (let [done (req h :post "/api/plan_parts/-/agree_many"
                      {:headers person :body {:ids all}})]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (= 7 (get-in (json done) [:data :succeeded])) (pr-str (json done))))
      (is (every? #(= "agreed" (state-of h %)) all)))
    (testing "restating an agreed part sends it back to proposed"
      (let [;; restate is an edit door, so it is fenced on the version read
            etag (get-in (get-row h "plan_parts" (first leaves)) [:meta :etag])
            done (req h :post (str "/api/plan_parts/" (first leaves) "/-/restate")
                      {:headers (assoc person "if-match" etag)
                       :body {:title "Client interviews"}})]
        (is (= 200 (:status done)) (pr-str (json done))))
      (let [p (get-row h "plan_parts" (first leaves))]
        (is (= "proposed" (:state p)))
        (is (= "Client interviews" (get-in p [:data :title])))
        (is (= studies (get-in p [:data :parent])))))
    (testing "dropping a branch drops its children with it"
      (let [;; drop drafts its reason, so it is fenced on the version read too
            etag (get-in (get-row h "plan_parts" studies) [:meta :etag])
            done (req h :post (str "/api/plan_parts/" studies "/-/drop")
                      {:headers (assoc person "if-match" etag)
                       :body {:reason "The client's accountant runs the studies."}})]
        (is (= 200 (:status done)) (pr-str (json done))))
      (is (= "dropped" (state-of h studies)))
      (is (= ["dropped" "dropped"] (mapv #(state-of h %) (take 2 leaves))))
      (is (= ["agreed" "agreed" "agreed" "agreed"]
             (mapv #(state-of h %) (into [goal filing] (drop 2 leaves))))
          "the other branch stands"))
    (testing "the plan counts its parts and draws the tree"
      (let [p (get-row h "org_plans" plan)
            tree (str (get-in p [:data :parts_tree]))]
        (is (= {:proposed 0 :agreed 4 :dropped 3 :total 7}
               (get-in p [:data :parts])))
        (is (str/includes? tree "- **An R&D tax firm**") tree)
        (is (str/includes? tree "  - **Studies** · new_kind `study` · dropped") tree)
        (is (str/includes? tree "    - **Form 6765**") tree)))))

(deftest an-agents-agree-is-held-for-the-owner-and-the-allow-runs-it
  (let [{:keys [h]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})
        as (granted! h)
        asked (req h :post (str "/api/plan_parts/" goal "/-/agree")
                   {:headers as :body {}})
        held (:held_call (json asked))]
    (testing "the agent's agree is not served: it waits on the owner"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (= "proposed" (state-of h goal)))
      (is (= "colton" (get-in (get-row h "held_calls" held) [:data :owner]))))
    (testing "the owner's Allow moves the part"
      (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                         {:headers person})]
        (is (= 200 (:status allowed)) (pr-str (json allowed))))
      (is (= "agreed" (state-of h goal))))))

(deftest a-part-is-refused-off-a-drafting-plan-and-under-another-plan
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")
        other (filed! h "landscaping")
        theirs (part! h other {:title "A landscaping business"})]
    (testing "a parent from another plan is refused"
      (let [no (part h person plan {:title "Mowing" :parent theirs})]
        (is (not= 201 (:status no)) (pr-str (json no)))
        (is (str/includes? (pr-str (json no)) "the-parent-is-of-this-plan")
            (pr-str (json no)))))
    (testing "a plan that is not drafting takes no part"
      (walk/proposed! eng plan the-owner)
      (is (not= "drafting" (:state (get-row h "org_plans" plan))))
      (let [no (part h person plan {:title "An R&D tax firm"})]
        (is (not= 201 (:status no)) (pr-str (json no)))
        (is (str/includes? (pr-str (json no)) "the-plan-is-drafting")
            (pr-str (json no)))))))

;; ── parts before code (ticket f9252928) ─────────────────────────────

(defn- template-entry
  "The onboarding template's plan_part scope entry, filled for `org`."
  [org]
  (let [t (edn/read-string (slurp (io/resource "seats/onboarding.edn")))]
    (->> (str/replace (pr-str (get-in t [:seat :scope])) "{{org}}" org)
         edn/read-string
         (filter #(= "plan_part" (:kind %)))
         first)))

(defn- rewritten!
  "The stored row of `kind` with `id`, its data put through `f`, as a
  row stored before a field was kept is."
  [eng kind id f]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx kind (str id) {})]
          (store/save-row! st tx kind
                           (-> (update row :data f)
                               (assoc :version (inc (long (:version row)))))
                           (:version row)))))))

(defn- restated [h headers id title]
  (req h :post (str "/api/plan_parts/" id "/-/restate")
       {:headers (assoc headers "if-match" (get-in (get-row h "plan_parts" id) [:meta :etag]))
        :body {:title title}}))

(deftest the-onboarding-seats-scope-reaches-its-own-plans-parts-and-no-other
  (let [{:keys [h]} (world)
        plan (filed! h "rd_tax")
        other (filed! h "landscaping")
        theirs (part! h other {:title "A landscaping business"})
        as (granted! h [(template-entry "rd_tax")])
        made (part h as plan {:title "An R&D tax firm"})
        ours (id-of made)]
    (is (= {:org "rd_tax"} (:filter (template-entry "rd_tax"))))
    (testing "it creates a part of its own plan, inferred and stamped with the org"
      (is (= 201 (:status made)) (pr-str (json made)))
      (is (= "rd_tax" (get-in (get-row h "plan_parts" ours) [:data :org])))
      (is (= "inferred" (get-in (get-row h "plan_parts" ours) [:data :source])))
      (is (= "landscaping" (get-in (get-row h "plan_parts" theirs) [:data :org]))))
    (testing "it restates its own plan's part"
      (let [done (restated h as ours "An R&D tax credit firm")]
        (is (= 200 (:status done)) (pr-str (json done))))
      (is (= "An R&D tax credit firm" (get-in (get-row h "plan_parts" ours) [:data :title]))))
    (testing "another plan's part is refused"
      (let [no (restated h as theirs "Mowing")]
        (is (not= 200 (:status no)) (pr-str (json no))))
      (is (= "A landscaping business" (get-in (get-row h "plan_parts" theirs) [:data :title]))))
    (testing "no caller writes the org"
      (let [made (part h person plan {:title "Filing" :org "landscaping"})]
        (is (or (not= 201 (:status made))
                (= "rd_tax" (get-in (get-row h "plan_parts" (id-of made)) [:data :org])))
            (pr-str (json made)))))))

(def ^:private an-error
  {:key "crack/study/person_only" :severity "error" :class "crack"
   :subject "study" :sentence "Only a person moves a study out of review."
   :remedy "Name who reviews a study."})

(deftest a-new-plan-shows-its-tree-before-it-goes-up
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")
        _ (walk/linked! eng plan walk/a-head)
        audit! #(inv/invoke! eng :org_plan plan :record_findings
                             {:head_sha walk/a-head :findings %} walk/the-engine)
        as (granted! h [(template-entry "rd_tax")])
        step #(get-in (get-row h "org_plans" plan) [:data :next_step])
        refusal #(try (inv/invoke! eng :org_plan plan :propose {} {:principal the-owner})
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (audit! [])
    (is (true? (get-in (get-row h "org_plans" plan) [:data :parts_required])))
    (testing "with no parts, the seat is drafting them"
      (is (= "parts/drafting" (:key (step))))
      (is (= :the-parts-are-agreed (keyword (:guard (refusal))))))
    (let [goal (id-of (part h as plan {:title "An R&D tax firm"}))
          kids [(id-of (part h as plan {:title "Studies" :parent goal}))
                (id-of (part h as plan {:title "Filing" :parent goal}))]]
      (testing "the agent's parts are inferred, and the step counts them"
        (is (every? #(= "inferred" (get-in (get-row h "plan_parts" %) [:data :source]))
                    (cons goal kids)))
        (is (= "parts/waiting" (:key (step))))
        (is (= "3 parts wait for your yes." (:sentence (step)))))
      (testing "propose waits on them"
        (is (= :the-parts-are-agreed (keyword (:guard (refusal)))))
        (is (= "drafting" (:state (get-row h "org_plans" plan)))))
      (testing "once the owner agrees them all, the step is the audit's"
        (let [done (req h :post "/api/plan_parts/-/agree_many"
                        {:headers person :body {:ids (into [goal] kids)}})]
          (is (= 200 (:status done)) (pr-str (json done))))
        (audit! [an-error])
        (is (= (:key an-error) (:key (step))))
        (is (= :no-error-finding-is-open (keyword (:guard (refusal))))))
      (testing "and propose opens once no error finding is open"
        (audit! [])
        (is (nil? (step)))
        (is (nil? (refusal)))
        (is (= "proposed" (:state (get-row h "org_plans" plan))))))))

(deftest a-question-about-a-part-is-drawn-under-it
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})
        studies (part! h plan {:title "Studies" :parent goal})]
    (inv/invoke! eng :org_plan plan :ask
                 {:key "q1" :part studies :question "Who interviews each client?"}
                 {:principal the-owner})
    (is (= studies (str (get-in (get-row h "org_plans" plan) [:data :questions 0 :part]))))
    (let [tree (str (get-in (get-row h "org_plans" plan) [:data :parts_tree]))]
      (is (str/includes? tree "  - **Studies**") tree)
      (is (str/includes? tree "    - *asked:* Who interviews each client?") tree))))

(deftest a-plan-stored-before-parts-proposes-with-none
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")]
    (rewritten! eng :org_plan plan #(dissoc % :parts_required))
    (walk/proposed! eng plan the-owner)
    (is (= "proposed" (:state (get-row h "org_plans" plan))))
    (is (= 0 (get-in (get-row h "org_plans" plan) [:data :parts :total])))))

(deftest a-part-stored-before-its-org-takes-it-from-the-pass
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})]
    (rewritten! eng :plan_part goal #(dissoc % :org))
    (is (nil? (get-in (get-row h "plan_parts" goal) [:data :org])))
    (is (= {:marked 0 :stamped 1} (org-live/pass! eng)))
    (is (= "rd_tax" (get-in (get-row h "plan_parts" goal) [:data :org])))
    (is (= {:marked 0} (org-live/pass! eng)) "a second pass writes nothing")))
