(ns factory10.org-plan-findings-test
  "The org_plan's findings as data (ticket 5d62058e, M2b): two audit
  runs over a fixture org land on its plan through record_findings.
  The first records three findings, the second (after a fix) records
  two; the finding that is gone closes and its question is resolved,
  and the keys of the unchanged findings do not move.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
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

(defn- plan-of [h id]
  (:data (json (req h :get (str "/api/org_plans/" id) {:headers person}))))

(defn- filed! [h]
  (let [made (req h :post "/api/org_plans"
                  {:headers person
                   :body {:name "greenleaf"
                          :brief "A six-person landscaping business."}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

;; What `clojure -M:conform --json orgs/greenleaf` answers for the
;; fixture org, before and after the fix: conform's own keys.
(def ^:private quote-approver
  {:key "crack/quote/person_only" :severity "error" :class "crack"
   :subject "quote" :sentence "No one approves a quote over $5k."
   :remedy "Name who approves a quote over $5k."})

(def ^:private late-payer
  {:key "crack/invoice/no_waker" :severity "error" :class "crack"
   :subject "invoice" :sentence "No seat is woken when an invoice is 30 days late."
   :remedy "Name who chases a late invoice."})

(def ^:private unexplained
  {:key "sentence/crew/double-booked" :severity "error" :class "sentence"
   :subject "crew" :sentence "The guard double-booked does not explain itself."
   :remedy "Give the guard an :explain."})

(defn- recorded! [eng id sha findings]
  (inv/invoke! eng :org_plan id :record_findings
               {:head_sha sha :findings findings}
               {:principal the-forge}))

(deftest two-runs-land-on-the-plan
  (let [{:keys [h eng]} (world)
        id (filed! h)]
    (recorded! eng id "aaaaaaa1" [quote-approver late-payer unexplained])
    (inv/invoke! eng :org_plan id :ask
                 {:key "q1" :finding_key (:key quote-approver)
                  :question "Who approves a quote over $5k?"}
                 {:principal the-owner})
    (inv/invoke! eng :org_plan id :ask
                 {:key "q2" :finding_key (:key unexplained)
                  :question "What happens when a crew is double-booked?"}
                 {:principal the-owner})
    (let [first-run (plan-of h id)]
      (testing "the first run records three findings at its head"
        (is (= 3 (count (:findings first-run))))
        (is (= "aaaaaaa1" (:findings_sha first-run)))
        (is (not-any? :resolved_at (:questions first-run))))
      (recorded! eng id "bbbbbbb2" [quote-approver late-payer])
      (let [second-run (plan-of h id)
            by-key (into {} (map (juxt :key identity)) (:questions second-run))]
        (testing "the second run records two, at its own head"
          (is (= 2 (count (:findings second-run))))
          (is (= "bbbbbbb2" (:findings_sha second-run))))
        (testing "the keys of the unchanged findings do not move"
          (is (= (take 2 (map :key (:findings first-run)))
                 (map :key (:findings second-run)))))
        (testing "the finding that is gone closes: its question is resolved"
          (is (some? (:resolved_at (by-key "q2"))))
          (is (nil? (:resolved_at (by-key "q1")))))))))

(def ^:private seat-outside
  {:key "scope/outside-owner/crew-seat/quote/approve" :severity "error"
   :class "scope" :subject "seat crew-seat"
   :sentence "The seat crew-seat holds quote.approve, which its owner does not."
   :remedy "Narrow the seat's scope to its owner's, or widen the owner's first."})

(deftest the-walk-takes-the-findings-in-a-fixed-order
  (let [{:keys [h eng]} (world)
        id (filed! h)
        _ (walk/linked! eng id "ccccccc1")
        step #(:key (:next_step (plan-of h id)))
        propose #(:status (req h :post (str "/api/org_plans/" id "/-/propose")
                               {:headers person :body {}}))]
    (recorded! eng id "aaaaaaa1"
               [seat-outside unexplained quote-approver late-payer])
    (testing "three classes: cracks before sentences before seats, ties by key"
      (is (= [(:key late-payer) (:key quote-approver)
              (:key unexplained) (:key seat-outside)]
             (map :key (org-plan/walk-steps (:findings (plan-of h id))))))
      (is (= (:key late-payer) (step))))
    (testing "the step stays until a later run clears its finding"
      (recorded! eng id "aaaaaaa2"
                 [seat-outside unexplained quote-approver late-payer])
      (is (= (:key late-payer) (step)))
      (recorded! eng id "bbbbbbb1" [seat-outside unexplained quote-approver])
      (is (= (:key quote-approver) (step)))
      (recorded! eng id "bbbbbbb2" [seat-outside unexplained])
      (is (= (:key unexplained) (step)))
      (recorded! eng id "bbbbbbb3" [seat-outside])
      (is (= (:key seat-outside) (step))))
    (testing "the walk is not done while a finding is open"
      (is (not= 200 (propose))))
    (testing "the walk ends when the findings are empty, and propose opens"
      (recorded! eng id "ccccccc1" [])
      (is (nil? (:next_step (plan-of h id))))
      (is (= 200 (propose))))))

(def ^:private red-film
  {:key "film/invoice/late-payer-chased" :severity "error" :class "film"
   :subject "invoice" :sentence "In the film no one chased the late invoice."
   :remedy "Name who chases a late invoice."})

(deftest a-red-film-reopens-a-proposed-plan
  (let [{:keys [h eng]} (world)
        id (filed! h)
        change (walk/linked! eng id "ccccccc1")
        state #(:state (json (req h :get (str "/api/org_plans/" id)
                                  {:headers person})))
        propose #(:status (req h :post (str "/api/org_plans/" id "/-/propose")
                               {:headers person :body {}}))
        filmed! #(do (inv/invoke! eng :org_plan id :hand_in_film
                                  {:film_url "https://films.example.test/greenleaf.mp4"
                                   :take_report "{}" :head_sha "ddddddd1"}
                                  {:principal the-forge})
                     (inv/invoke! eng :org_plan id :record_film_findings
                                  {:head_sha "ddddddd1" :findings [red-film]}
                                  {:principal the-forge}))
        warning (assoc unexplained :severity "warning")]
    (recorded! eng id "ccccccc1" [warning])
    (is (= 200 (propose)))
    (is (= "proposed" (name (state))))
    (testing "a person does not record the film's findings"
      (let [no (req h :post (str "/api/org_plans/" id "/-/record_film_findings")
                    {:headers person
                     :body {:head_sha "ddddddd1" :findings [red-film]}})]
        (is (not= 200 (:status no)) (pr-str (json no)))
        (is (= "proposed" (name (state))))))
    (testing "the film's red rules land as findings, and the plan drafts again"
      (filmed!)
      (let [plan (plan-of h id)]
        (is (= "drafting" (name (state))))
        (is (= #{(:key warning) (:key red-film)}
               (into #{} (map :key) (:findings plan))))
        (is (= "ddddddd1" (:findings_sha plan)))
        (is (some? (:next_step plan)) "the walk reopens on the film")))
    (testing "propose is shut until the finding clears"
      (is (not= 200 (propose)))
      (inv/invoke! eng :change change :observe {:head_sha "ddddddd2"} walk/the-engine)
      (recorded! eng id "ddddddd2" [warning])
      (is (nil? (:next_step (plan-of h id))))
      (is (= 200 (propose))))))

(deftest a-landed-take-replaces-the-earlier-takes-film-findings
  ;; ticket d7619f04
  (let [{:keys [h eng]} (world)
        id (filed! h)
        _ (walk/linked! eng id "ccccccc1")
        hand-in! #(inv/invoke! eng :org_plan id :hand_in_film
                               {:film_url "https://films.example.test/greenleaf.mp4"
                                :take_report "{}" :head_sha "ddddddd1"}
                               {:principal the-forge})
        stopped {:key "take_stopped" :severity "warning" :class "film"
                 :subject "film"
                 :sentence "The take stopped at shot 41 of 41, so it was voided, not judged."
                 :remedy "Film the plan again."}
        warning (assoc unexplained :severity "warning")]
    (recorded! eng id "ccccccc1" [warning])
    (is (= 200 (:status (req h :post (str "/api/org_plans/" id "/-/propose")
                             {:headers person :body {}}))))
    (hand-in!)
    (inv/invoke! eng :org_plan id :void_take
                 {:head_sha "ddddddd1" :finding stopped}
                 {:principal the-forge})
    (is (= #{(:key warning) "take_stopped"}
           (into #{} (map :key) (:findings (plan-of h id)))))
    (hand-in!)
    (inv/invoke! eng :org_plan id :record_film_findings
                 {:head_sha "ddddddd1" :findings [red-film]}
                 {:principal the-forge})
    (let [found (:findings (plan-of h id))]
      (testing "only the landed take's miss is among the film findings"
        (is (= [(:key red-film)]
               (map :key (filter #(= "film" (name (:class %))) found)))))
      (testing "a finding of another class stays"
        (is (some #(= (:key warning) (:key %)) found))))))

(deftest a-film-error-walks-after-the-audit-errors-and-before-warnings
  (let [lint {:key "lint/crew/label" :severity "warning" :class "lint"}
        seats (assoc seat-outside :severity "warning")
        kinds {:key "resource/quote/no-check" :severity "error"
               :class "resource"}]
    (testing "the film's error comes before every warning"
      (is (= (:key red-film)
             (:key (org-plan/next-step [lint seats red-film])))))
    (testing "an audit error comes before the film's"
      (is (= [(:key kinds) (:key red-film)]
             (->> (org-plan/walk-steps [lint seats red-film kinds])
                  (filter #(= "error" (:severity %)))
                  (map :key))))
      (is (= (:key kinds)
             (:key (org-plan/next-step [lint seats red-film kinds])))))
    (testing "a film finding ranks ahead of lint"
      (is (= [(:key red-film) (:key lint)]
             (map :key (org-plan/walk-steps
                        [lint (assoc red-film :severity "warning")])))))))

(deftest only-the-engine-records-findings
  (let [{:keys [h]} (world)
        id (filed! h)
        no (req h :post (str "/api/org_plans/" id "/-/record_findings")
                {:headers person
                 :body {:head_sha "aaaaaaa1" :findings [quote-approver]}})]
    (is (not= 200 (:status no)) (pr-str (json no)))
    (is (empty? (:findings (plan-of h id))))))

(defn- entry-schema
  "The schema of entry `k` in a :map form."
  [form k]
  (some #(when (and (vector? %) (= k (first %))) (last %)) form))

(deftest every-head-the-plan-takes-is-lower-case-hex
  ;; ticket 9c8711ac
  (let [doors [:record_findings :carry_take :hand_in_film :record_film_findings :head_moved]
        heads (concat (for [d doors]
                        [d (entry-schema (get-in org-plan/org-plan [:actions d :input]) :head_sha)])
                      (for [f [:findings_sha :approved_head :take_sha]]
                        [f (entry-schema (:schema org-plan/org-plan) f)]))]
    (doseq [[at s] heads]
      (is (some? s) (name at))
      (is (schema/validate s "5e469c6166edf6a3f28ae2e6bdb88b775025f14d") (name at))
      (doseq [bad ["5E469C6166EDF6A3F28AE2E6BDB88B775025F14D" "5e469c6z" "head-1b"]]
        (is (not (schema/validate s bad)) (str (name at) " " bad))))))

;; ticket d4750bf9: the name becomes a directory, a namespace and a
;; domain, and live-orgs boots only a snake_case one.
(defn- file-named [h org]
  (req h :post "/api/org_plans" {:headers person :body {:name org}}))

(deftest a-name-is-snake-case
  (let [{:keys [h]} (world)]
    (doseq [typed ["r&d-tax-services" "rd-tax-services"]]
      (testing (str typed " is refused, naming the field and its snake_case")
        (let [no (file-named h typed)
              said (pr-str (json no))]
          (is (= 422 (:status no)) said)
          (is (re-find #"\bname\b" said) said)
          (is (str/includes? said "rd_tax_services") said))))
    (testing "rd_tax_services is accepted"
      (is (= 201 (:status (file-named h "rd_tax_services")))))
    (testing "capitals, spaces, slashes and a leading digit or underscore are refused"
      (doseq [bad ["Greenleaf" "green leaf" "orgs/greenleaf" "_greenleaf" "9lives"]]
        (is (= 422 (:status (file-named h bad))) bad)))
    (testing "the projected create schema shows the pattern"
      (let [doc (json (req h :get "/api/org_plans" {:headers person}))]
        (is (some #(and (map? %) (= org-plan/name-pattern (:pattern %)))
                  (tree-seq coll? seq doc)))))))

(deftest the-snake-case-of-what-was-typed
  (is (= "rd_tax_services" (org-plan/snake-name "r&d-tax-services")))
  (is (= "rd_tax_services" (org-plan/snake-name "rd-tax-services")))
  (is (= "acme_landscaping" (org-plan/snake-name "  Acme Landscaping!")))
  (is (= "a_b" (org-plan/snake-name "a//b")))
  (is (= "org_9lives" (org-plan/snake-name "9lives"))))

;; ticket 4d243311: a plan filed before the rule keeps its name, is read
;; and retired, and takes no other door.
(defn- stored-name!
  "The plan stores `nm`, as a filing before d4750bf9 left it."
  [eng id nm]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :org_plan id {})]
          (store/save-row! st tx :org_plan
                           (-> (assoc-in row [:data :name] nm)
                               (assoc :version (inc (long (:version row)))))
                           (:version row)))))))

(deftest a-name-before-the-rule-is-read-and-retired
  (let [{:keys [h eng]} (world)
        id (id-of (file-named h "rd_tax_services"))
        _ (stored-name! eng id "rd-tax-services")
        row-of #(json (req h :get (str "/api/org_plans/" id) {:headers person}))]
    (testing "it is read"
      (let [got (req h :get (str "/api/org_plans/" id) {:headers person})]
        (is (= 200 (:status got)) (pr-str (json got)))
        (is (= "rd-tax-services" (get-in (json got) [:data :name])))))
    (testing "propose refuses, naming the rule and the way out"
      (let [no (req h :post (str "/api/org_plans/" id "/-/propose")
                    {:headers person :body {}})
            said (pr-str (json no))]
        (is (not= 200 (:status no)) said)
        (is (str/includes? said "snake_case rule") said)
        (is (str/includes? said "retire it and file it again") said)
        (is (= "drafting" (:state (row-of))))))
    (testing "retire moves it to retired"
      (let [ok (req h :post (str "/api/org_plans/" id "/-/retire")
                    {:headers person :body {:why "Filed before the snake_case rule."}})]
        (is (= 200 (:status ok)) (pr-str (json ok)))
        (is (= "retired" (:state (row-of))))))))
