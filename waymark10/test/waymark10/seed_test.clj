(ns waymark10.seed-test
  "The seed loader (docs/spec-demo-clones.md § 1, § 5 item 1), over the
  in-memory twin and a toy kind: a seed loads through the doors and
  leaves a history, a refused step ends the boot, a working engine is
  refused, a seeded engine is not seeded twice, and a relative date is
  counted from the boot.

  The demo seed itself is loaded where its own kinds are, in
  workqueue10.demo-seed-test."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.resource :as r]
            [waymark10.server.collections :as coll]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)))

(def ^:private chore
  "The toy kind: one door out of `open` and one back."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:due {:optional true :x-display {:label "Due"}}
      [:maybe :waymark/date]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

;; A fixture of its own: this cast is the toy seed's, not the demo
;; seed's, so its displays do not follow the demo cast's names.
(def ^:private toy
  {:seed "toy"
   :version 1
   :cast {:ada {:id "ada" :display "Ada Example" :type :human}
          :plan {:id "plan" :display "Planner" :type :agent :acts-for :ada}}
   :steps [{:as :ada :kind :chore :create {:title "Water the ferns"} :ref :c1}
           {:as :plan :kind :chore :on :c1 :action "finish"}]})

(defn- engine
  ([] (engine {}))
  ([opts] (dev/scratch! [chore] (merge {:name "demo-test"} opts))))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- refused
  "The refusal a call threw, or nil when it went through."
  [f]
  (try (f) nil
       (catch ExceptionInfo e e)))

(deftest a-seed-loads-through-the-doors-and-leaves-a-history
  (let [eng (engine)
        result (seed/load! eng toy {})
        id (get-in result [:refs :c1 :id])]
    (is (true? (:seeded result)))
    (is (= "done" (name (:state (dev/row eng :chore id)))))
    (testing "each move is in the log, made by the member the step names"
      (let [log (moves eng :chore id)]
        (is (= ["ada" "plan"] (mapv #(get-in % [:actor :id]) log)))
        (is (= "finish" (name (:action (last log)))))))
    (testing "the cast are member rows, born through the member door"
      (is (= "Ada Example" (get-in (dev/row eng :member "ada") [:data :display])))
      (is (= "agent" (get-in (dev/row eng :member "plan") [:data :actor_type])))
      (is (= "ada" (get-in (dev/row eng :member "plan") [:data :acts_for])))
      (is (= 1 (count (moves eng :member "ada")))))))

(deftest a-seeds-teams-are-written-after-their-roles-and-cast
  (let [eng (engine)
        crewed (assoc toy
                      :cast (assoc (:cast toy) :parcel {:id "parcel" :display "Parcelco"
                                                        :type :outside :roles ["carrier"]})
                      :teams {:crew-b {:members [:ada :parcel]}
                              :therapists {:name "Therapists" :members [:ada] :roles ["pt"]}})
        _ (seed/load! eng crewed {})
        teams (into {} (map (juxt #(get-in % [:data :name]) identity)) (dev/rows eng :team))]
    (testing "each team names the cast's member ids"
      (is (= ["ada" "parcel"] (get-in teams ["crew-b" :data :members])))
      (is (= ["ada"] (get-in teams ["Therapists" :data :members]))))
    (testing "the role a team confers is registered before the team is written"
      (is (= ["pt"] (get-in teams ["Therapists" :data :roles])))
      (is (some #(= "pt" (get-in % [:data :name])) (dev/rows eng :role))))
    (testing "an outside cast member is a member row of actor type outside"
      (is (= "outside" (get-in (dev/row eng :member "parcel") [:data :actor_type]))))
    (testing "a second load writes no team twice"
      (seed/load! eng crewed {})
      (is (= 2 (count (dev/rows eng :team)))))))

(deftest a-step-the-law-refuses-ends-the-boot
  (let [eng (engine)
        ;; the schema refuses a chore with no title
        bad (update toy :steps conj
                    {:as :ada :kind :chore :create {:title ""}})
        e (refused #(seed/load! eng bad {}))]
    (is (some? e) "the load throws, so the boot ends")
    (is (true? (:waymark10/seed-refused (ex-data e))))
    (is (= 3 (:step (ex-data e))) "and it names the step")))

(deftest a-seed-refuses-an-engine-not-named-demo
  (let [eng (engine {:name "work"})
        e (refused #(seed/load! eng toy {}))]
    (is (some? e))
    (is (re-find #"demo-" (str (ex-message e))))
    (is (nil? (dev/row eng :member "ada")) "and nothing was written")
    (is (empty? (dev/rows eng :chore)))))

(deftest a-seed-refuses-an-engine-with-an-idp
  (let [eng (assoc (engine) :oidc {:issuer "https://idp.example"})
        e (refused #(seed/load! eng toy {}))]
    (is (some? e))
    (is (re-find #"identity provider" (str (ex-message e))))
    (is (nil? (dev/row eng :member "ada")) "and nothing was written")))

(deftest a-seeded-engine-is-not-seeded-twice
  (let [eng (engine)
        once (seed/load! eng toy {})
        twice (seed/load! eng toy {})]
    (is (true? (:seeded once)))
    (is (false? (:seeded twice)))
    (is (string? (:skipped twice)) "and it says so")
    (is (= 1 (count (dev/rows eng :chore))) "no second row")))

(deftest a-seed-that-failed-half-way-is-refused-on-a-restart
  (let [eng (engine)
        ;; the schema refuses a chore with no title
        bad (update toy :steps conj
                    {:as :ada :kind :chore :create {:title ""}})
        first-e (refused #(seed/load! eng bad {}))
        again (try (seed/load! eng bad {})
                   (catch ExceptionInfo e e))]
    (is (= 3 (:step (ex-data first-e))) "the last step is refused")
    (is (some? (dev/row eng :member "ada")) "and the cast is already written")
    (is (instance? ExceptionInfo again) "the second load refuses; it never answers skipped")
    (is (true? (:waymark10/seed-refused (ex-data again))))
    (is (re-find #"half-way" (str (ex-message again))))
    (is (= 1 (count (dev/rows eng :chore))) "and no step is walked again")
    (testing "a whole seed is refused there too, and is not skipped"
      (is (some? (refused #(seed/load! eng toy {})))))))

(deftest a-relative-date-is-counted-from-the-boot
  (let [eng (engine)
        dated (assoc toy :steps
                     [{:as :ada :kind :chore :ref :later
                       :create {:title "Later" :due [:days 3]}}
                      {:as :ada :kind :chore :ref :earlier
                       :create {:title "Earlier" :due [:days -2]}}])
        result (seed/load! eng dated
                           {:now (Instant/parse "2026-10-01T23:30:00Z")})
        due (fn [k]
              (str (get-in (dev/row eng :chore (get-in result [:refs k :id]))
                           [:data :due])))]
    (is (= "2026-10-04" (due :later)))
    (is (= "2026-09-29" (due :earlier)))))

(deftest a-relative-date-counts-from-the-day-one-the-clone-was-given
  ;; ticket bbf1f253: the scene's day_one is the clone's day one, though
  ;; the clone is stood up days later
  (let [week (assoc toy :steps
                    [{:as :ada :kind :chore :ref :first
                      :create {:title "Day 1" :due [:days 0]}}
                     {:as :ada :kind :chore :ref :last
                      :create {:title "Day 7" :due [:days 6]}}])
        later (Instant/parse "2026-10-23T09:00:00Z")
        dues (fn [opts]
               (let [eng (engine)
                     result (seed/load! eng week (assoc opts :now later))]
                 (mapv #(str (get-in (dev/row eng :chore (get-in result [:refs % :id]))
                                     [:data :due]))
                       [:first :last])))]
    (testing "with a day one, [:days n] counts from it, not from the boot"
      (is (= ["2026-10-19" "2026-10-25"] (dues {:day-one "2026-10-19"}))))
    (testing "without one, from the boot, as before"
      (is (= ["2026-10-23" "2026-10-29"] (dues {})))
      (is (= ["2026-10-23" "2026-10-29"] (dues {:day-one ""}))))
    (testing "one that is not a date refuses the seed"
      (is (some? (refused #(seed/load! (engine) week {:day-one "next tuesday"})))))))

;; ── a live seed ─────────────────────────────────────────────────────

;; Its values are the boot's params, and its second step fills a seat
;; template in the factory kit's shape: test/seats/seed-test-seat.edn,
;; since the kit itself is not on this classpath.
(def ^:private live
  (assoc toy
         :seed "live"
         :live-ok true
         :steps [{:as :ada :kind :chore :create {:title [:param :chore]} :ref :c1}
                 {:as :ada :template "seed-test-seat"
                  :params {:repo [:param :repo]} :ref :s1}]))

(def ^:private params {"chore" "Water the ferns" "repo" "acme/garden"})

(deftest a-live-seed-loads-on-a-fresh-engine-named-waymark
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng live {:params params})]
    (is (true? (:seeded once)))
    (is (= "Water the ferns"
           (get-in (dev/row eng :chore (get-in once [:refs :c1 :id])) [:data :title]))
        "a [:param k] value is the boot's param")
    (testing "a template step creates the seat with its holes filled"
      (is (= :seat (get-in once [:refs :s1 :kind])))
      (is (= "Keep acme/garden tidy."
             (get-in (dev/row eng :seat (get-in once [:refs :s1 :id])) [:data :charter]))))
    (testing "a second boot skips it, by the marker"
      (let [twice (seed/load! eng live {:params params})]
        (is (false? (:seeded twice)))
        (is (= 1 (count (dev/rows eng :chore))) "no second row")))))

(deftest a-seeded-engine-gains-only-what-the-seed-gained
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng live {:params params})
        counts (fn [] (into {} (map (juxt identity #(count (dev/rows eng %))))
                            [:member :seat :chore]))
        before (counts)]
    (testing "a second load of the same seed adds nothing"
      (let [again (seed/load! eng live {:params params})]
        (is (false? (:seeded again)))
        (is (= {:members 0 :steps 0} (:added again)))
        (is (= before (counts)))))
    (testing "a seed with one more seat and one more cast member adds just those two"
      (let [grown (-> live
                      (assoc-in [:cast :bea] {:id "bea" :display "Bea Example" :type :human})
                      (update :steps conj
                              {:as :ada :kind :seat :ref :s2
                               :create {:name "seed-test-seat-2"
                                        :charter "Keep acme/garden tidier."
                                        :scope [{:kind "model" :actions ["retire"]}]
                                        :standing_ttl_seconds 604800
                                        :cadence_seconds 3600
                                        :budget_usd_per_week 5M
                                        :sitting_budget_tokens 60000}}))
            more (seed/load! eng grown {:params params})]
        (is (false? (:seeded more)))
        (is (= {:members 1 :steps 1} (:added more)))
        (is (= (-> before (update :member inc) (update :seat inc)) (counts)))
        (is (some? (dev/row eng :member "bea")))
        (is (= "seed-test-seat-2"
               (get-in (dev/row eng :seat (get-in more [:refs :s2 :id])) [:data :name])))
        (testing "and a load of the grown seed again adds nothing"
          (let [again (seed/load! eng grown {:params params})]
            (is (= {:members 0 :steps 0} (:added again)))
            (is (= (-> before (update :member inc) (update :seat inc)) (counts)))))))))

(def ^:private porch
  "Two steps that name no row: an unnamed create, and an invoke on it."
  [{:as :ada :kind :chore :create {:title "Sweep the porch"} :ref :c2}
   {:as :plan :kind :chore :on :c2 :action "finish"}])

(defn- strip-walked!
  "The marker as an engine seeded before keys were kept holds it."
  [eng seed-name]
  (let [st (:storage eng)
        id (str "seed-" seed-name)]
    (store/with-tx st
      (fn [tx]
        (let [raw (store/load-row st tx :member id {:for-update true})]
          (store/update-data! st tx :member id (dissoc (:data raw) :walked_steps)
                              (:next-flip-at raw)))))))

(deftest a-seeded-engine-walks-a-gained-step-that-names-no-row-once
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng live {:params params})
        grown (update live :steps into porch)
        more (seed/load! eng grown {:params params})
        c2 (get-in more [:refs :c2 :id])]
    (is (= {:members 0 :steps 2} (:added more)))
    (is (= 2 (count (dev/rows eng :chore))))
    (is (= "done" (name (:state (dev/row eng :chore c2)))) "the invoke was walked")
    (testing "and the next load walks neither again"
      (let [again (seed/load! eng grown {:params params})]
        (is (= {:members 0 :steps 0} (:added again)))
        (is (= 2 (count (dev/rows eng :chore))))))))

(deftest an-old-marker-backfills-its-keys-and-walks-nothing
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng live {:params params})
        _ (strip-walked! eng "live")
        grown (update live :steps into porch)
        back (seed/load! eng grown {:params params})]
    (is (= {:members 0 :steps 0} (:added back)))
    (is (= 1 (count (dev/rows eng :chore))) "no step ran twice, and none ran at all")
    (testing "and the keys it took stay walked"
      (let [again (seed/load! eng grown {:params params})]
        (is (= {:members 0 :steps 0} (:added again)))
        (is (= 1 (count (dev/rows eng :chore))))))))

(defn- seat-step [charter]
  {:as :ada :kind :seat :ref :s2
   :create {:name "seed-test-seat-2"
            :charter charter
            :scope [{:kind "model" :actions ["retire"]}]
            :standing_ttl_seconds 604800
            :cadence_seconds 3600
            :budget_usd_per_week 5M
            :sitting_budget_tokens 60000}})

(deftest an-edited-named-step-makes-no-second-row
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng (update live :steps conj (seat-step "Keep acme/garden tidier."))
                      {:params params})
        seats (count (dev/rows eng :seat))
        edited (seed/load! eng (update live :steps conj (seat-step "Keep acme/garden tidiest."))
                           {:params params})]
    (is (= {:members 0 :steps 0} (:added edited)))
    (is (= seats (count (dev/rows eng :seat))) "the named row is found, not made again")
    (is (some? (get-in edited [:refs :s2 :id])))))

(def ^:private model-body
  {:vendor "anthropic" :tier "strong"
   :price_input_per_mtok 3M
   :price_output_per_mtok 15M
   :price_cache_read_per_mtok 0.3M
   :price_cache_write_per_mtok 3.75M})

(deftest a-seeded-seat-is-held-for-the-house-model-unless-it-names-its-own
  ;; ticket 7e177f8d: `made!` holds a seat that names no `held_for` for
  ;; the house's active model, and a step's own `:with` is kept
  (let [eng (engine {:name "waymark"})
        held (assoc toy
                    :seed "held"
                    :live-ok true
                    :steps [{:as :ada :kind :model :ref :house
                             :create (assoc model-body :name "claude-house-5"
                                            :display "House 5")}
                            (seat-step "Keep acme/garden tidier.")
                            {:as :ada :kind :model :ref :own
                             :create (assoc model-body :name "claude-own-5"
                                            :display "Own 5")}
                            {:as :ada :template "seed-test-seat"
                             :params {:repo "acme/garden"}
                             :with {:held_for [[:ref :own]]} :ref :s1}])
        once (seed/load! eng held {})
        held-for (fn [ref] (mapv str (get-in (dev/row eng :seat (get-in once [:refs ref :id]))
                                             [:data :held_for])))]
    (is (true? (:seeded once)))
    (testing "a seat that names no model is held for the house's"
      (is (= [(str (get-in once [:refs :house :id]))] (held-for :s2))))
    (testing "a step that names its own held_for keeps it"
      (is (= [(str (get-in once [:refs :own :id]))] (held-for :s1))))))

(deftest org-boot-says-what-it-added
  (let [line (org/boot-line "acme" {:seed "acme" :seeded false
                                   :added {:members 0 :steps 1}
                                   :skipped "only what the seed gained was added"})]
    (is (re-find #":added \{:members 0, :steps 1\}" line))
    (is (re-find #"only what the seed gained" line))))

(deftest a-gained-step-may-point-at-an-unnamed-step-the-load-skipped
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng live {:params params})
        grown (update live :steps conj
                      {:as :ada :kind :seat :ref :s3
                       :create {:name "seed-test-seat-3"
                                :charter [:ref :c1]
                                :scope [{:kind "model" :actions ["retire"]}]
                                :standing_ttl_seconds 604800
                                :cadence_seconds 3600
                                :budget_usd_per_week 5M
                                :sitting_budget_tokens 60000}})
        more (seed/load! eng grown {:params params})]
    (is (false? (:seeded more)))
    (is (= {:members 0 :steps 1} (:added more)))
    (is (= (get-in once [:refs :c1]) (get-in more [:refs :c1]))
        "the unnamed chore the load skipped is still bound")
    (is (= (get-in once [:refs :c1 :id])
           (get-in (dev/row eng :seat (get-in more [:refs :s3 :id])) [:data :charter]))
        "and the gained seat points at it")))

(def ^:private granted
  "The live seed with a grant step: a step that makes no named row."
  (update live :steps conj
          {:grant {:audience [:cast :plan]
                   :scope [{:kind "chore" :actions ["finish"]}]}
           :ref :g1}))

(def ^:private pointing
  "A gained step that points at the grant an earlier load made."
  {:as :ada :kind :chore :create {:title [:ref :g1]} :ref :c9})

(defn- kept-refs [eng seed-name]
  (into {} (map (fn [[k v]] [(keyword (name k)) (str v)]))
        (get-in (dev/row eng :member (str "seed-" seed-name)) [:data :walked_refs])))

(defn- strip-refs!
  "The marker as an engine seeded before refs were kept holds it."
  [eng seed-name]
  (let [st (:storage eng)
        id (str "seed-" seed-name)]
    (store/with-tx st
      (fn [tx]
        (let [raw (store/load-row st tx :member id {:for-update true})]
          (store/update-data! st tx :member id (dissoc (:data raw) :walked_refs)
                              (:next-flip-at raw)))))))

(deftest walking-a-step-records-its-ref
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng granted {:params params})]
    (is (= (get-in once [:refs :g1 :id]) (:g1 (kept-refs eng "live"))))
    (is (= (get-in once [:refs :c1 :id]) (:c1 (kept-refs eng "live"))))
    (testing "and a gained step's ref is recorded as it is walked"
      (let [more (seed/load! eng (update granted :steps conj pointing) {:params params})]
        (is (= (get-in more [:refs :c9 :id]) (:c9 (kept-refs eng "live"))))))))

(deftest a-gained-step-may-point-at-a-grant-an-earlier-load-made
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng granted {:params params})
        more (seed/load! eng (update granted :steps conj pointing) {:params params})]
    (is (= {:members 0 :steps 1} (:added more)))
    (is (= (get-in once [:refs :g1]) (get-in more [:refs :g1]))
        "the skipped grant step is bound from the marker")
    (is (= (get-in once [:refs :g1 :id])
           (get-in (dev/row eng :chore (get-in more [:refs :c9 :id])) [:data :title]))
        "and the gained chore points at that same grant")))

(deftest a-gained-step-pointing-at-a-ref-walked-before-refs-were-kept-refuses
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng granted {:params params})
        _ (strip-refs! eng "live")
        chores (count (dev/rows eng :chore))
        e (refused #(seed/load! eng (update granted :steps conj pointing) {:params params}))]
    (is (some? e))
    (is (re-find #"`:g1`.*walked before the seed's marker kept refs"
                 (str (ex-message e))))
    (is (= chores (count (dev/rows eng :chore))) "and nothing was made")))

(deftest a-live-seed-loads-on-an-engine-with-an-idp
  (let [eng (assoc (engine {:name "waymark"}) :oidc {:issuer "https://idp.example"})
        once (seed/load! eng live {:params params})]
    (is (true? (:seeded once)))
    (testing "and a later boot skips it, by the marker"
      (let [twice (seed/load! eng live {:params params})]
        (is (false? (:seeded twice)))
        (is (= 1 (count (dev/rows eng :chore))) "no second row")))))

(deftest a-live-seed-refuses-an-engine-that-has-members
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng live {:params params})
        other (assoc live :seed "other"
                     :steps [{:as :ada :kind :chore :create {:title "Sweep"}}])
        e (refused #(seed/load! eng other {}))]
    (is (some? e))
    (is (re-find #"already has members" (str (ex-message e))))
    (is (= 1 (count (dev/rows eng :chore))) "and nothing was written")))

(defn- org-seed [nm]
  {:seed nm :version 1 :live-ok true
   :cast {:owner {:id "lee" :display "Lee Example" :type :human}}
   :steps [{:as :owner :kind :chore :create {:title (str "Open " nm)}}]})

(deftest an-orgs-live-seed-loads-on-a-working-engine
  (let [eng (engine {:name "waymark"})
        _ (seed/load! eng live {:params params})
        members (fn [] (count (dev/rows eng :member)))]
    (testing "alpha's seed loads with :org beside the house's members"
      (is (true? (:seeded (seed/load! eng (org-seed "alpha") {:org "alpha"}))))
      (is (some? (dev/row eng :member "lee"))))
    (testing "gamma's seed, naming the same owner, loads and reuses that member"
      (let [before (members)]
        (is (true? (:seeded (seed/load! eng (org-seed "gamma") {:org "gamma"}))))
        (is (= (inc before) (members)) "only gamma's marker is new")
        (is (= 3 (count (dev/rows eng :chore))))))
    (testing "a plain live seed is still refused"
      (let [e (refused #(seed/load! eng (org-seed "plain") {}))]
        (is (re-find #"already has members" (str (ex-message e))))))
    (testing "gamma's seed under another org's name is refused"
      (let [e (refused #(seed/load! eng (org-seed "gamma") {:org "alpha"}))]
        (is (= "org_mismatch" (:reason (ex-data e))))
        (is (= 3 (count (dev/rows eng :chore))) "and nothing was written")))))

(deftest an-orgs-marker-records-its-org-in-a-field-of-its-own
  (let [eng (engine {:name "waymark"})
        nm (apply str (repeat 50 "s"))
        org-name (apply str (repeat 50 "o"))]
    (testing "a seed and org whose names come to 100 characters load"
      (is (true? (:seeded (seed/load! eng (org-seed nm) {:org org-name}))))
      (let [marker (dev/row eng :member (str "seed-" nm))]
        (is (= org-name (get-in marker [:data :seed_org])))
        (is (= (str "Seed `" nm "`") (get-in marker [:data :display])))))
    (testing "and another org's name is refused by the field"
      (let [e (refused #(seed/load! eng (org-seed nm) {:org "other"}))]
        (is (= "org_mismatch" (:reason (ex-data e))))))))

(deftest seed-org-is-written-by-the-engine-alone
  (let [eng (engine {:name "waymark"})
        ada (t/principal {:id "ada" :type :human :display "Ada Example"})
        body {:display "Ada Example" :actor_type "human" :subject "ada"}]
    (testing ":system's seed load still writes seed_org"
      (is (true? (:seeded (seed/load! eng (org-seed "sys") {:org "sys"}))))
      (is (= "sys" (get-in (dev/row eng :member "seed-sys") [:data :seed_org]))))
    (testing "a hand-written member create carrying seed_org is refused, naming the field"
      (let [e (refused #(inv/create! eng :member (assoc body :seed_org "sys")
                                     {:principal ada :id "ada"}))]
        (is (= :schema-invalid (:waymark10/problem (ex-data e))))
        (is (contains? (:errors (ex-data e)) :seed_org))
        (is (nil? (dev/row eng :member "ada")) "and nothing was written")))
    (testing "the same create without it passes"
      (is (nil? (refused #(inv/create! eng :member body {:principal ada :id "ada"}))))
      (is (some? (dev/row eng :member "ada"))))
    (testing "the member create door's projected input schema does not list seed_org"
      (let [props (get-in (coll/create-affordance (get (inv/resources eng) :member) nil)
                          [:input :properties])]
        (is (contains? props :display))
        (is (not (contains? props :seed_org)))))))

(deftest an-org-name-longer-than-the-marker-holds-is-refused-before-any-step
  (let [eng (engine {:name "waymark"})
        too-long (apply str (repeat 65 "o"))
        e (refused #(seed/load! eng (org-seed "long") {:org too-long}))]
    (is (= "org_too_long" (:reason (ex-data e))))
    (is (re-find #"65 characters" (str (ex-message e))))
    (is (empty? (dev/rows eng :chore)) "and no step was walked")
    (is (nil? (dev/row eng :member "seed-long")) "and no marker was written")
    (testing "a name of exactly 64 characters loads"
      (let [at-cap (apply str (repeat 64 "o"))]
        (is (true? (:seeded (seed/load! eng (org-seed "long") {:org at-cap}))))))))

(deftest an-old-marker-with-its-org-in-the-display-is-read-and-rewritten
  (let [eng (engine {:name "waymark"})
        st (:storage eng)
        _ (seed/load! eng (org-seed "old") {:org "old"})
        _ (store/with-tx st
            (fn [tx]
              (let [raw (store/load-row st tx :member "seed-old" {:for-update true})]
                (store/update-data! st tx :member "seed-old"
                                    (-> (:data raw)
                                        (dissoc :seed_org)
                                        (assoc :display "Seed `old` for org `old`"))
                                    (:next-flip-at raw)))))]
    (is (nil? (get-in (dev/row eng :member "seed-old") [:data :seed_org])))
    (is (false? (:seeded (seed/load! eng (org-seed "old") {:org "old"}))))
    (is (= "old" (get-in (dev/row eng :member "seed-old") [:data :seed_org]))
        "the org read from the display is written to the field")
    (let [e (refused #(seed/load! eng (org-seed "old") {:org "new"}))]
      (is (= "org_mismatch" (:reason (ex-data e)))))))

(defn- marker-data
  "The marker's data as the store holds it, the loader's own fields
  with it."
  [eng seed-name]
  (let [st (:storage eng)]
    (:data (store/with-tx st
             (fn [tx] (store/load-row st tx :member (str "seed-" seed-name) {}))))))

(deftest an-org-load-that-failed-half-way-resumes
  (let [eng (engine {:name "waymark"})
        half {:seed "half" :version 1 :live-ok true
              :cast {:owner {:id "lee" :display "Lee Example" :type :human}}
              :steps [{:as :owner :kind :chore :create {:title "One"} :ref :c1}
                      {:as :owner :kind :chore :create {:title "Two"} :ref :c2}
                      {:as :owner :kind :chore :create {:title [:param :chore]} :ref :c3}
                      {:as :owner :kind :chore :create {:title "Four"} :ref :c4}]}]
    (is (some? (refused #(seed/load! eng half {:org "half"})))
        "the third step names a param the boot does not give")
    (is (= 2 (count (dev/rows eng :chore))))
    (is (true? (:loading (marker-data eng "half")))
        "the marker, written first, still says loading")
    (let [out (java.io.StringWriter.)
          again (binding [*out* out]
                  (seed/load! eng half {:org "half" :params {"chore" "Three"}}))]
      (is (true? (:seeded again)))
      (is (true? (:resumed again)))
      (is (re-find #"resumed half-seeded org half" (str out)))
      (is (= 4 (count (dev/rows eng :chore))) "the two made before are not made again")
      (is (= "half" (get-in (dev/row eng :member "seed-half") [:data :seed_org]))))))

(deftest an-org-load-records-each-step-and-resumes-after-the-last-it-walked
  (let [eng (engine {:name "waymark"})
        steps [{:as :owner :kind :chore :create {:title "One"} :ref :c1}
               {:as :owner :kind :chore :on :c1 :action "finish"}
               {:as :owner :kind :chore :create {:title [:param :chore]} :ref :c3}
               {:as :owner :kind :chore :create {:title "Four"} :ref :c4}
               {:as :owner :kind :chore :create {:title "Five"} :ref :c5}]
        rec {:seed "rec" :version 1 :live-ok true
             :cast {:owner {:id "lee" :display "Lee Example" :type :human}}
             :steps steps}
        key-of #'seed/step-key]
    (testing "a load that fails at step 3 leaves a marker loading, with steps 1-2 walked"
      (let [e (refused #(seed/load! eng rec {:org "rec"}))]
        (is (= 3 (:step (ex-data e)))))
      (let [data (marker-data eng "rec")]
        (is (true? (:loading data)))
        (is (= (set (map key-of (take 2 steps))) (set (:walked_steps data))))))
    (testing "the next load walks steps 3-5 only and ends loading false"
      (let [again (binding [*out* (java.io.StringWriter.)]
                    (seed/load! eng rec {:org "rec" :params {"chore" "Three"}}))
            c1 (get-in again [:refs :c1 :id])
            data (marker-data eng "rec")]
        (is (true? (:resumed again)))
        (is (= 4 (count (dev/rows eng :chore))))
        (is (= "done" (name (:state (dev/row eng :chore c1)))))
        (is (= 1 (count (filter #(= "finish" (name (:action %))) (moves eng :chore c1))))
            "the action step is not walked twice")
        (is (false? (:loading data)))
        (is (= (set (map key-of steps)) (set (:walked_steps data))))))))

(deftest a-live-seed-refuses-a-param-the-boot-does-not-give
  (let [eng (engine {:name "waymark"})
        e (refused #(seed/load! eng live {:params {"chore" "Water the ferns"}}))]
    (is (re-find #"repo" (str (ex-message (or (ex-cause e) e)))))))

(def ^:private owned
  "A live seed whose one cast member is made from the boot's params."
  (assoc live
         :seed "owned"
         :cast {:owner {:id [:param :owner] :display [:param :owner_display]
                        :type :human}}
         :steps [{:as :owner :kind :chore :create {:title [:param :chore]} :ref :c1}]))

(deftest a-live-seed-takes-a-cast-members-id-and-display-from-the-params
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng owned {:params (assoc params "owner" "lee"
                                                   "owner_display" "Lee Example")})]
    (is (true? (:seeded once)))
    (is (= "Lee Example" (get-in (dev/row eng :member "lee") [:data :display]))
        "the member is born with the param's id and display")
    (is (some? (dev/row eng :chore (get-in once [:refs :c1 :id])))
        "and walks its steps as that member")))

(deftest a-live-seed-refuses-a-cast-param-the-boot-does-not-give
  (let [eng (engine {:name "waymark"})
        e (refused #(seed/load! eng owned {:params params}))]
    (is (re-find #"owner" (str (ex-message e))))
    (is (empty? (dev/rows eng :chore)) "and nothing was written")))

(deftest the-params-read-as-json-or-edn
  (is (= {:repo "acme/garden"} (seed/read-params "{\"repo\": \"acme/garden\"}")))
  (is (= {:repo "acme/garden"} (seed/read-params "{:repo \"acme/garden\"}")))
  (is (= {} (seed/read-params nil)))
  (is (some? (refused #(seed/read-params "[1 2]")))))

(def ^:private pull-step
  "An unnamed step a later one points at, as the factory seed's `:pull`
  runner_link is."
  {:as :ada :kind :chore :create {:title "Pull the weeds"} :ref :pull})

(def ^:private pulled
  "The seed grown since: a step pointing at `[:ref :pull]`, and one that
  needs nothing."
  {:seed "pull" :version 1 :cast (:cast toy)
   :steps [pull-step
           {:as :ada :kind :chore :create {:title [:ref :pull]} :ref :link}
           {:as :ada :kind :chore :create {:title "Rake the leaves"}}]})

(defn- seeded-before-step-ids!
  "An engine as one seeded before `step-id` holds it: `n` pull chores
  under random ids, and the `:pull` step's key walked."
  [eng n]
  (seed/load! eng {:seed "pull" :version 1 :cast (:cast toy)
                   :steps (vec (repeat n (dissoc pull-step :ref)))}
              {})
  (#'seed/walk-recorded! eng {:seed "pull"} [(#'seed/step-key pull-step)]))

(defn- bound-refs [eng seed-name]
  (let [st (:storage eng)]
    (into {} (map (fn [[k v]] [(name k) (str v)]))
          (get-in (store/with-tx st
                    (fn [tx] (store/load-row st tx :member (str "seed-" seed-name) {})))
                  [:data :bound_refs]))))

(deftest a-ref-seeded-before-step-ids-is-bound-by-its-body-once
  (let [eng (engine)
        _ (seeded-before-step-ids! eng 1)
        pull (str (:id (first (dev/rows eng :chore))))
        more (seed/load! eng pulled {})]
    (is (= {:members 0 :steps 2} (:added more)))
    (is (= pull (get-in more [:refs :pull :id])) "the ref is bound by the body")
    (is (= pull (get-in (dev/row eng :chore (get-in more [:refs :link :id])) [:data :title]))
        "and the gained step points at it")
    (is (= {"pull" pull} (bound-refs eng "pull")) "and the marker keeps the binding")
    (testing "and the next load reads bound_refs and does not match again"
      (seed/load! eng {:seed "extra" :version 1
                       :cast {:bea {:id "bea" :display "Bea Example" :type :human}}
                       :steps [{:as :bea :kind :chore :create {:title "Pull the weeds"}}]}
                  {})
      (let [again (seed/load! eng pulled {})]
        (is (= {:members 0 :steps 0} (:added again)))
        (is (= pull (get-in again [:refs :pull :id])))))))

(deftest a-ref-two-rows-match-skips-the-steps-that-need-it
  (let [eng (engine)
        _ (seeded-before-step-ids! eng 2)
        err (java.io.StringWriter.)
        more (binding [*err* err] (seed/load! eng pulled {}))]
    (is (= {:members 0 :steps 1} (:added more)) "the rest of the seed still loads")
    (is (nil? (get-in more [:refs :pull])))
    (is (nil? (get-in more [:refs :link])) "the step that needs it is skipped")
    (is (= 3 (count (dev/rows eng :chore))))
    (is (re-find #"seed `pull` binds no row to `:pull`: 2 rows" (str err)))
    (is (= {} (bound-refs eng "pull")))))

;; ── a seat's wakes (E-5.4) ──────────────────────────────────────────

(def ^:private woken
  (assoc toy
         :seed "woken"
         :live-ok true
         :steps [{:as :ada :kind :seat :ref :w1
                  :create {:name "seed-test-walker"
                           :mode "fired"
                           :walk "chore"
                           :charter "Finish each open chore."
                           :scope [{:kind "chore" :actions ["finish"] :filter {:state "open"}}]
                           :standing_ttl_seconds 604800
                           :cadence_seconds 3600
                           :budget_usd_per_week 5M
                           :sitting_budget_tokens 60000}
                  :wakes [{:kind "chore" :actions ["reopen"] :filter {:title "Water the ferns"}}]}]))

(defn- fire-subs
  "The door subscriptions on the seat's fire door."
  [eng seat-id]
  (filterv #(and (= "door" (some-> (get-in % [:data :receiver :type]) name))
                 (= seat-id (str (get-in % [:data :receiver :id]))))
           (dev/rows eng :subscription)))

(deftest a-seat's-wakes-boot-as-its-owner's-door-subscriptions
  (let [eng (engine {:name "waymark"})
        once (seed/load! eng woken {})
        id (str (get-in once [:refs :w1 :id]))
        active #(filterv (fn [s] (= "active" (name (:state s)))) (fire-subs eng id))]
    (testing "the wakes written are its one active door subscription, its owner's own"
      (is (= 1 (count (active))))
      (let [d (:data (first (active)))]
        (is (= ["chore"] (map name (:kinds d))))
        (is (= ["reopen"] (map name (:actions d))))
        (is (= {"title" "Water the ferns"} (some-> (:filter d) (update-keys name))))
        (is (= "fire" (some-> (get-in d [:receiver :action]) name)))
        (is (= "owner" (some-> (:managed_by d) name)))))
    (testing "no managed row is left beside them"
      (is (not-any? #(= "seat" (some-> (get-in % [:data :managed_by]) name)) (fire-subs eng id))))
    (testing "a second boot adds none"
      (let [n (count (fire-subs eng id))]
        (seed/load! eng woken {})
        (is (= n (count (fire-subs eng id))))
        (is (= 1 (count (active))))))))
