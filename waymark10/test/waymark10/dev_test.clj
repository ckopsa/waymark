(ns waymark10.dev-test
  "The scratchpad answers in the domain's own words — a FULL engine
  (definitions boot included) over the memory store, no database."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.dev :as dev]
            [waymark10.dsl :as dsl]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(r/defresource chore
  {:kind :dev_chore
   :initial :open
   :terminal #{:done :dropped}
   :summary "{data.name} · {state}"
   :display {:title "Chore: {data.name}"}
   :schema [:map [:name [:string {:min 1 :max 80}]]]
   :flow [[:open :finish :done
           {:one-way "Finishing records reality; nothing external changes."
            :display {:label "Done"}}]
          [:open :drop :dropped
           {:confirm "The chore is dropped for good."
            :display {:label "Drop" :style :danger}}]]})

(defn- scratch [] (dev/scratch! [chore]))

(deftest a-full-engine-boots-over-memory
  (let [e (scratch)]
    ;; the definitions boot ran: the kind's law is stamped current
    (is (some? (:registry e)))
    (is (pos-int? (get-in (deref (:registry e))
                          [:kinds :dev_chore :current-law])))))

(deftest the-declared-display-renders-resolved
  ;; the recorded demand, landed: top-level :display was authored but
  ;; consumed nowhere — the envelope now carries the resolved title
  (let [e (scratch)
        row (dev/create! e :dev_chore {:name "sweep"})
        doc (dev/envelope e :dev_chore (:id row))]
    (is (= "Chore: sweep" (get-in doc ["display" "title"])) (pr-str (get doc "display")))))

(deftest the-write-loop-runs-in-one-form
  (let [e (scratch)
        row (dev/create! e :dev_chore {:name "sweep"})]
    (is (= :open (:state row)))
    (is (= :available (:status (dev/why-not e :dev_chore (:id row) :finish))))
    (dev/act! e :dev_chore (:id row) :finish nil)
    (is (= :done (:state (dev/row e :dev_chore (:id row)))))
    (is (= 1 (count (dev/rows e :dev_chore))))))

(deftest why-not-answers-with-the-declarations-own-sentence
  (let [e (scratch)
        row (dev/create! e :dev_chore {:name "sweep"})
        _ (dev/act! e :dev_chore (:id row) :finish nil)
        verdict (dev/why-not e :dev_chore (:id row) :drop)]
    ;; :done is terminal — drop renders unavailable with the
    ;; out-of-state sentence, not a stack trace
    (is (= :unavailable (:status verdict)))
    (is (string? (:reason verdict)))
    (is (re-find #"(?i)open" (:reason verdict))))
  (let [e (scratch)
        row (dev/create! e :dev_chore {:name "sweep"})]
    (is (= :absent (:status (dev/why-not e :dev_chore (:id row) :launch))))))

(deftest diff-law-classifies-a-confirm-flip-as-judgment
  (let [flipped (update-in chore [:actions :finish :safety]
                           assoc :confirm true :consequence "It ends.")
        {:keys [verdict changes]} (dev/diff-law chore flipped)]
    (is (seq changes))
    (is (some #(re-find #"finish" (:path %)) changes))
    (is (contains? #{:data-law :code-or-shape} verdict)))
  (is (= :unchanged (:verdict (dev/diff-law chore chore)))))

(deftest explain-speaks-prose-not-maps
  (let [out (with-out-str (dev/explain chore))]
    (is (re-find #"dev_chore — 3 states" out))
    (is (re-find #"finish: open → done" out))
    (is (re-find #"one-way — Finishing records reality" out))
    (is (re-find #"confirm — The chore is dropped for good\." out))))

(deftest a-write-projects-its-transition-as-one-line
  (let [e (scratch)
        out (with-out-str
              (let [row (dev/create! e :dev_chore {:name "sweep"})]
                (dev/act! e :dev_chore (:id row) :finish nil)))]
    (is (re-find #"dev \(REPL\) · dev_chore create: ∅ → open · \"sweep · Open\"" out))
    (is (re-find #"dev \(REPL\) · dev_chore finish: open → done" out))))

(deftest walk-drives-the-machine-with-the-factories
  (let [e (scratch)
        row (dev/walk! e :dev_chore :done)]
    (is (= :done (:state row)) (pr-str row))))

(deftest vocab-prints-the-enforcing-vars-own-sets
  (let [out (with-out-str (dev/vocab))]
    (is (re-find #"expression ops" out))
    (is (re-find #"date-of" out))
    (is (re-find #":deviations" out))
    (is (re-find #":one-way" out))
    (is (re-find #"docs/waymark10-vocabulary\.md" out))))

;; ── why-not over a cross-resource guard (probe run 2's D6) ──────────
;; the pure render probe advertises a :reads guard optimistically;
;; why-not must verify through a dry-run before claiming :available

(g/defguard chore-is-done
  {:reads [:dev_chore]
   :explain "Finish the chore first — the reward follows the work."}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (if (= :done (:state (read :dev_chore (get-in row [:data :chore_id]))))
      (t/allow)
      (t/deny))
    (t/allow)))

(def earned-it
  (g/expr {:name :earned-it
           :when '(<= 3 (input :stars))
           :explain "Fewer than 3 stars doesn't earn the reward."}))

(r/defresource reward
  {:kind :dev_reward
   :initial :promised
   :terminal #{:given}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:chore_id {:kind :dev_chore} :waymark/ref]]
   :flow [[:promised :give :given
           {:requires [chore-is-done]
            :one-way "Giving the reward records it; nothing external changes."
            :display {:label "Give"}}]
          [:promised :grade :given
           {:input [:map [:stars [:int {:min 0 :max 5}]]]
            :requires [chore-is-done earned-it]
            :one-way "Grading gives the reward with a star count on the record."
            :display {:label "Grade"}}]]})

(deftest why-not-verifies-cross-resource-guards-through-a-dry-run
  (let [e (dev/scratch! [chore reward])
        c (dev/create! e :dev_chore {:name "sweep"})
        w (dev/create! e :dev_reward {:name "ice cream" :chore_id (:id c)})
        before (dev/why-not e :dev_reward (:id w) :give)]
    (is (= :unavailable (:status before)) (pr-str before))
    (is (= :dry-run (:via before)))
    (is (re-find #"Finish the chore first" (:reason before)))
    (dev/act! e :dev_chore (:id c) :finish nil)
    (let [after (dev/why-not e :dev_reward (:id w) :give)]
      (is (= :available (:status after)) (pr-str after))
      (is (= :dry-run (:verified after))))))

(deftest why-not-judges-input-taking-actions-through-the-partial-rehearsal
  (let [e (dev/scratch! [chore reward])
        c (dev/create! e :dev_chore {:name "sweep"})
        w (dev/create! e :dev_reward {:name "ice cream" :chore_id (:id c)})]
    ;; bodiless: the row-reading guard is answerable NOW — no
    ;; :advertised punt, the honest refusal arrives
    (let [before (dev/why-not e :dev_reward (:id w) :grade)]
      (is (= :unavailable (:status before)) (pr-str before))
      (is (re-find #"Finish the chore first" (:reason before))))
    (dev/act! e :dev_chore (:id c) :finish nil)
    ;; bodiless after: available, with the input-judging guard named
    ;; as awaiting rather than silently skipped
    (let [after (dev/why-not e :dev_reward (:id w) :grade)]
      (is (= :available (:status after)) (pr-str after))
      (is (= [:earned-it] (:awaiting after))))
    ;; a body judges the awaiting guard too — both verdicts honest
    (let [low (dev/why-not e :dev_reward (:id w) :grade {:stars 1})]
      (is (= :unavailable (:status low)) (pr-str low))
      (is (re-find #"Fewer than 3 stars" (:reason low))))
    (let [high (dev/why-not e :dev_reward (:id w) :grade {:stars 5})]
      (is (= :available (:status high)) (pr-str high))
      (is (nil? (:awaiting high))))
    ;; a body that fails the schema says so, before any guard
    (let [bad (dev/why-not e :dev_reward (:id w) :grade {:stars 9})]
      (is (= :invalid-input (:status bad)) (pr-str bad)))))

;; ── :sum facts over the memory engine (round 3: sum-matching is a
;;    protocol op, no longer a Postgres surface) ─────────────────────

(r/defresource coin
  {:kind :dev_coin
   :initial :minted
   :terminal #{:spent}
   :summary "{data.amount} · {state}"
   :schema [:map
            [:till_id {:kind :dev_till :filter #{:eq}} :waymark/ref]
            [:amount {:sort true} [:decimal {:min 0}]]]
   :flow [[:minted :spend :spent
           {:one-way "Spending records reality; nothing external changes."
            :display {:label "Spend"}}]]})

(r/defresource till
  {:kind :dev_till
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:total {:optional true
                     :derived {:sum {:owns :dev_coin :of :amount
                                     :where {:state #{"minted"}}}}}
             [:maybe :decimal]]]
   :owns [{:kind :dev_coin :via :till_id}]
   :flow [[:open :close :closed
           {:one-way "Closing records reality; nothing external changes."
            :display {:label "Close"}}]]})

(deftest sum-facts-maintain-over-the-memory-engine
  (let [e (dev/scratch! [till coin])
        t (dev/create! e :dev_till {:name "swear jar"})
        c1 (dev/create! e :dev_coin {:till_id (:id t) :amount 2.50M})
        _ (dev/create! e :dev_coin {:till_id (:id t) :amount 1.50M})]
    (is (== 4M (get-in (dev/row e :dev_till (:id t)) [:data :total])))
    (dev/act! e :dev_coin (:id c1) :spend nil)
    (is (== 1.5M (get-in (dev/row e :dev_till (:id t)) [:data :total])))))

;; ── act! and the fence (probe run 3's D8) ───────────────────────────
;; :fields' generated editors carry :edit, and an :edit implies
;; If-Match — the scratchpad supplies the live row's own etag

(r/defresource memo
  {:kind :dev_memo
   :initial :draft
   :terminal #{:sent}
   :summary "{data.title} · {state}"
   :fields {:at-create [[:title [:string {:min 1 :max 80}]]]
            :while-open [[:body (dsl/prose "Body")]]}
   :flow [[:draft :send :sent
           {:one-way "Sending records it; nothing external changes."
            :display {:label "Send"}}]]})

(deftest act-supplies-the-fence-for-generated-editors
  (let [e (dev/scratch! [memo])
        m (dev/create! e :dev_memo {:title "hi"})]
    (dev/act! e :dev_memo (:id m) :update_fields {:body "there"})
    (is (= "there" (get-in (dev/row e :dev_memo (:id m)) [:data :body])))
    ;; an explicit stale fence still refuses — the fence stays real
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)conflict"
         (dev/act! e :dev_memo (:id m) :update_fields {:body "again"}
                   {:if-match "W/\"stale\""})))))

(deftest the-ring-handler-serves-without-a-port
  (let [e (scratch)
        _ (dev/create! e :dev_chore {:name "sweep"})
        resp ((dev/handler e) {:request-method :get
                               :uri "/api/.well-known/waymark"
                               :headers {}})]
    (is (= 200 (:status resp)))))
