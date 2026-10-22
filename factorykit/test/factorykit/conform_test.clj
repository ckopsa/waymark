(ns factorykit.conform-test
  "Org conformance (ticket 86f96963): the example org passes clean, and
  a fixture org with a state no seat leaves, a guard with no sentence
  and a seat scope naming an unknown kind earns exactly those three
  findings, keyed the same on every run. No database, no network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [factorykit.conform :as conform])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest the-example-org-conforms
  (is (= [] (conform/conform "../orgs/example"))))

(deftest the-orgkit-template-conforms-as-a-new-org
  (let [src (io/file "../orgkit/template")
        dir (io/file (str (Files/createTempDirectory "orgkit" (make-array FileAttribute 0)))
                     "myorg")]
    (doseq [^File f (file-seq src)
            :when (.isFile f)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath f))))]]
      (io/make-parents to)
      (io/copy f to))
    (is (= [] (filterv #(= :error (:severity %)) (conform/conform (str dir)))))))

(defn- example-with-seat
  "A copy of orgs/example whose seed holds the seat `entry` beside its
  own, as the onboarding seat writes it. → its dir."
  [entry]
  (let [src (io/file "../orgs/example")
        dir (io/file (str (Files/createTempDirectory "org-seat" (make-array FileAttribute 0)))
                     "example")
        seed-file (io/file dir "resources" "example" "seed.edn")]
    (doseq [^File f (file-seq src)
            :when (.isFile f)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath f))))]]
      (io/make-parents to)
      (io/copy f to))
    (spit seed-file
          (pr-str (-> (edn/read-string (slurp seed-file))
                      (update :domains (partial mapv #(assoc % :ref :example)))
                      (update :seats conj entry))))
    (str dir)))

(defn- example-with-mayor
  "orgs/example with the org-mayor template, filled with `kinds`, in
  its seed. → its dir."
  [kinds]
  (example-with-seat {:template "org-mayor"
                      :params {:org "example" :domain "example"
                               :domain_id [:ref :example] :owner "owner"
                               :budget_usd_per_week 8M :kinds kinds :wakes []}}))

(deftest the-example-org-conforms-with-a-hired-seat-in-the-seed
  (is (= [] (conform/conform
             (example-with-seat {:template "org-seat"
                                 :params {:org "example" :domain "example"
                                          :domain_id [:ref :example] :owner "owner"
                                          :budget_usd_per_week 5M
                                          :gap_kind "request" :gap_state "new"
                                          :kinds [{:kind "request" :actions ["accept"]}]
                                          :charter "You accept each new request whose detail says what the work is."}})))))

(deftest the-example-org-conforms-with-its-mayor-in-the-seed
  (is (= [] (conform/conform (example-with-mayor [{:kind "request" :actions []}
                                                  {:kind "work_order" :actions []}]))))
  (testing "and a mayor naming a kind the org does not serve earns its finding"
    (is (= ["scope/unknown-kind/example-mayor/ticket"]
           (mapv :key (conform/conform (example-with-mayor [{:kind "ticket" :actions []}])))))))

(deftest the-live-report-reads-the-flag-and-the-mayor
  (is (= {:live_flag false} (conform/live-report "../orgs/example")))
  (is (= {:live_flag false
          :mayor {:name "example-mayor" :budget_usd_per_week "8"}}
         (conform/live-report (example-with-mayor [{:kind "request" :actions []}])))))

(deftest a-broken-org-earns-exactly-its-three-findings
  (let [fs (conform/conform "fixtures/orgs/broken")]
    (is (= ["crack/no_exit/gizmo/stuck"
            "sentence/gizmo/start/broken/unsaid"
            "scope/unknown-kind/clerk/gadget"]
           (mapv :key fs)))
    (is (every? #(= :error (:severity %)) fs))
    (is (every? #(and (:class %) (:subject %) (seq (:sentence %)) (seq (:remedy %))) fs))
    (testing "the keys are stable across runs"
      (is (= (mapv :key fs)
             (mapv :key (conform/conform "fixtures/orgs/broken")))))))

(deftest a-seat-above-its-owners-ceiling-earns-one-finding
  (is (= ["scope/outside-owner/clerk/gizmo/finish"]
         (mapv :key (conform/conform "fixtures/orgs/overreach")))))

(deftest an-org-whose-name-is-not-snake-case-earns-a-finding
  (let [dir (io/file (str (Files/createTempDirectory "orgs" (make-array FileAttribute 0)))
                     "bad-name")
        _ (.mkdirs dir)
        [f] (conform/conform (str dir))]
    (is (= "org_name_not_snake_case/bad-name" (:key f)))
    (is (= :error (:severity f)))
    (is (re-find #"bad-name" (:sentence f))))
  (is (not-any? #(= :org_name_not_snake_case (:class %))
                (conform/conform "fixtures/orgs/unmarked"))))

(deftest an-org-with-no-manifest-earns-a-finding-naming-it
  (let [[f] (conform/conform "fixtures/orgs/unmarked")]
    (is (= "manifest/missing/unmarked" (:key f)))
    (is (re-find #"orgs/unmarked/org\.edn" (:sentence f)))))

;; ── decision (ticket e1ed02c7) ───────────────────────────────────────

(def ^:private bill
  {:kind :bill :states [:open :paid] :terminal #{:paid}
   :actions {:pay {:from #{:open} :to :paid
                   :input [:map [:amount_cents :int]]
                   :guards []
                   :safety {:idempotent true :reversible false :confirm false}}}})

(def ^:private clerk [{:name "clerk" :scope [{:kind "bill" :actions ["pay"]}]}])

(def ^:private rosa {:cast {:owner {:id "owner" :display "Rosa Delgado"}}})

(defn- decisions
  ([r] (decisions r clerk))
  ([r seats] (mapv :key (#'conform/decision-findings [r] seats rosa))))

(deftest the-audit-asks-the-person's-three-questions
  (let [fs (#'conform/decision-findings [bill] clerk rosa)]
    (is (= ["decision/unheld_final/bill"
            "decision/no_wait_limit/bill/open"
            "decision/money_unbounded/bill/pay"]
           (mapv :key fs)))
    (is (every? #(= :error (:severity %)) fs))
    (is (= ["Should the AI pay a bill on its own, or wait for Rosa Delgado's tap?"
            "How long may a bill stay open before someone is told, and who?"
            "Above what amount should pay on a bill wait for you?"]
           (mapv :sentence fs)))))

(deftest a-hold-or-the-person's-answer-clears-each-question
  (let [held {:name :owner-taps :hold true :explain "Rosa taps each payment."}
        capped {:name :under-cap :explain "A payment above $500 waits."
                :when '(<= :amount_cents 50000)}
        answered (assoc-in bill [:actions :pay :decided] "The clerk pays any bill under $500 on its own.")]
    (testing "a :hold guard clears the final step and the amount"
      (is (= ["decision/no_wait_limit/bill/open"]
             (decisions (assoc-in bill [:actions :pay :guards] [held])))))
    (testing "so does the person's answer, written on the door"
      (is (= ["decision/no_wait_limit/bill/open"] (decisions answered))))
    (testing "a guard reading the amount bounds it; the final step still asks"
      (is (= ["decision/unheld_final/bill" "decision/no_wait_limit/bill/open"]
             (decisions (assoc-in bill [:actions :pay :guards] [capped])))))
    (testing "an expected age, or the person's answer for the state, clears the wait"
      (is (= [] (decisions (assoc answered :expected-age {:open 604800}))))
      (is (= [] (decisions (assoc answered :decided {:open "A bill waits a week; then Rosa is told."})))))
    (testing "a door no model seat holds asks nothing"
      (is (= ["decision/no_wait_limit/bill/open"] (decisions bill [])))
      (is (= ["decision/no_wait_limit/bill/open"]
             (decisions bill [(assoc (first clerk) :person true)]))))))

(deftest a-decision-that-says-a-person-taps-needs-a-hold
  (let [says (assoc-in bill [:actions :pay :decided] "Paying a bill happens only on The Principal's tap.")
        held {:name :owner-taps :hold true :explain "The Principal taps each payment."}]
    (testing "with no hold, the audit finds the contradiction"
      (let [fs (#'conform/decision-findings [says] clerk rosa)]
        (is (= ["decision/decided_says_held/bill/pay" "decision/no_wait_limit/bill/open"]
               (mapv :key fs)))
        (is (= :error (:severity (first fs))))
        (is (re-find #"Add a :hold guard" (:remedy (first fs))))))
    (testing "the same door with a :hold guard does not"
      (is (= ["decision/no_wait_limit/bill/open"]
             (decisions (assoc-in says [:actions :pay :guards] [held])))))
    (testing ":decided-held :none with a reason keeps the sentence as narrative"
      (is (= ["decision/no_wait_limit/bill/open"]
             (decisions (assoc-in says [:actions :pay :decided-held] {:none "The tap is on the bank's side."})))))
    (testing "a door no model seat holds asks nothing"
      (is (= ["decision/no_wait_limit/bill/open"] (decisions says []))))))

(def ^:private prospect
  {:kind :prospect :states [:qualified :engaged] :terminal #{:engaged}
   :expected-age {:qualified 604800}
   :schema [:map
            [:company [:string {:min 1}]]
            [:letter_signed_on {:optional true} [:maybe [:string {:max 10}]]]]
   :actions {:engage {:from #{:qualified} :to :engaged
                      :guards [{:name :letter-signed :explain "Record the day they signed."
                                :when '(present? :letter_signed_on)}]
                      :safety {:idempotent true :reversible true :confirm false}}}})

(deftest a-field-only-a-door-knows-asks-when-it-is-known
  (testing "an optional field the editor fills and a guard reads off the row"
    (let [[f :as fs] (#'conform/decision-findings [prospect] clerk rosa)]
      (is (= ["decision/door_time_input/prospect/engage/letter_signed_on"] (mapv :key fs)))
      (is (= "Is letter signed on something you only know when engage happens?" (:sentence f)))
      (is (= :error (:severity f)))))
  (testing "asked on the door as input, it is not"
    (is (= [] (decisions (-> prospect
                             (assoc-in [:actions :engage :input] [:map [:letter_signed_on :string]])
                             (assoc-in [:actions :engage :guards 0 :when] '(<= (input :letter_signed_on) (today))))))))
  (testing "nor is a required create field, nor the person's answer on the door"
    (is (= [] (decisions (assoc prospect :schema [:map [:letter_signed_on :string]]))))
    (is (= [] (decisions (assoc-in prospect [:actions :engage :decided] "The day is known when the row is written.")))))
  (testing "the R&D tax org's prospect and study, as they stood, are the cases"
    (let [ks (set (map :key (conform/conform "fixtures/orgs/door_time_inputs")))]
      (is (every? ks ["decision/door_time_input/prospect/engage/letter_signed_on"
                      "decision/door_time_input/study/records_in/records_complete_on"
                      "decision/door_time_input/study/records_in/records_confirmed_by"])
          (pr-str (sort ks))))))

(deftest a-cast-scope-naming-what-the-engine-does-not-serve-earns-its-findings
  (is (= ["scope/unknown-action/cast/boss/gizmo/begin"
          "scope/unknown-kind/cast/boss/gadget"]
         (mapv :key (conform/conform "fixtures/orgs/miscast")))))

;; ── world (ticket a4149376) ──────────────────────────────────────

(defn- example-with
  "A copy of orgs/example whose seed is `(f seed)`, beside the manifest
  `manifest`. → its dir."
  [manifest f]
  (let [src (io/file "../orgs/example")
        dir (io/file (str (Files/createTempDirectory "org-world" (make-array FileAttribute 0)))
                     "example")
        seed-file (io/file dir "resources" "example" "seed.edn")]
    (doseq [^File f (file-seq src)
            :when (.isFile f)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath f))))]]
      (io/make-parents to)
      (io/copy f to))
    (spit seed-file (pr-str (f (edn/read-string (slurp seed-file)))))
    (spit (io/file dir "org.edn") (pr-str manifest))
    (str dir)))

(defn- example-without-rehearsal
  "A copy of orgs/example whose seed holds no :rehearsal."
  [manifest]
  (example-with manifest #(dissoc % :rehearsal)))

(deftest an-org-with-no-outside-world-earns-its-finding
  (let [fs (conform/conform (example-without-rehearsal {:name "example" :live false}))]
    (is (= ["no-outside-world/example"] (mapv :key fs)))
    (is (= "Nobody outside the business starts work in the first week, so the film has nothing to show."
           (:sentence (first fs)))))
  (testing "an org that only works inside waives it with :world :none"
    (is (= [] (conform/conform (example-without-rehearsal {:name "example" :live false :world :none}))))))

;; ── the rehearsal, judged as a clone loads it (ticket 290faa7a) ───

(deftest a-world-seat-missing-a-param-earns-its-finding
  (is (= ["seed/holes/world"]
         (mapv :key (conform/conform
                     (example-with {:name "example" :live false}
                                   #(update-in % [:rehearsal :seats 0 :params] dissoc :brief)))))))

(deftest the-dress-rehearsal-org-seeds-a-clone-that-boots
  (is (= [] (filterv #(= :seed (:class %)) (conform/conform "../orgs/dress_rehearsal")))))

(defn- brief-of [n]
  (apply str (take n (cycle "A long brief about the business. "))))

(deftest a-world-seat-with-a-long-brief-fits-the-seat-schema
  (is (= [] (conform/conform
             (example-with {:name "example" :live false}
                           #(assoc-in % [:rehearsal :seats 0 :params :brief] (brief-of 1000)))))))

(deftest a-world-seat-breaking-the-seat-schema-earns-its-finding
  (let [fs (conform/conform
            (example-with {:name "example" :live false}
                          #(assoc-in % [:rehearsal :seats 0 :params :brief] (brief-of 2400))))]
    (is (= ["seed/schema/world/instructions"] (mapv :key fs)))
    (is (re-find #"instructions .*should be at most 2000 characters; this one is \d+"
                 (:sentence (first fs))))))

(deftest a-rehearsal-cast-member-scoping-an-unknown-kind-earns-its-finding
  (is (= ["scope/unknown-kind/cast/bob/gadget"]
         (mapv :key (conform/conform
                     (example-with {:name "example" :live false}
                                   #(assoc-in % [:rehearsal :cast :bob]
                                              {:id "bob" :display "Bob Lin" :type :human
                                               :scope [{:kind "gadget" :actions ["create"]}]})))))))

;; ── the rehearsal's integrations (ticket 524685a0) ─────────────────

(def ^:private parcel
  {:kind :parcel :states [:packed :labelled]
   :actions {:buy_label {:from #{:packed} :to :labelled
                         :effect {:power "postage__create_label"}}}})

(def ^:private shop-week
  {:days [{:day 1 :calls [{:as :world :event :payments :body {:type "payment_intent.succeeded"}}
                          {:as :mayor :kind "parcel" :action "buy_label" :row "box"}]}]})

(deftest a-week-event-or-effect-the-rehearsal-cannot-play-earns-its-finding
  (let [fs (#'conform/rehearsal-findings "shop" [parcel] {} shop-week)]
    (is (= ["rehearsal/event-secret/shop/payments"
            "rehearsal/effect-fake/shop/postage__create_label"]
           (mapv :key fs)))
    (is (every? #(= :error (:severity %)) fs))
    (is (= "The week posts a payments event, but the rehearsal gives the clone no secret to sign it with; add a secret step reading [:param :signing_secret_payments]."
           (:sentence (first fs)))))
  (testing "a secret step and a fake for the tool clear both"
    (is (= [] (#'conform/rehearsal-findings
               "shop" [parcel]
               {:rehearsal {:steps [{:as :owner :kind :secret :ref :pay_key
                                     :create {:name "payments-signing" :value [:param :signing_secret_payments]}}]
                            :effects {"postage__create_label" {:status "done" :result {"object_id" "L1"}}}}}
               shop-week))))
  (testing "the week fixture's rehearsal earns neither"
    (is (= [] (filterv #(= :rehearsal (:class %)) (conform/conform "../orgs/week_fixture"))))))
