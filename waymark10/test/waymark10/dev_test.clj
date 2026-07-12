(ns waymark10.dev-test
  "The scratchpad answers in the domain's own words — a FULL engine
  (definitions boot included) over the memory store, no database."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.dev :as dev]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(r/defresource chore
  {:kind :dev_chore
   :initial :open
   :terminal #{:done :dropped}
   :summary "{data.name} · {state}"
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
            :display {:label "Give"}}]]})

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

(deftest the-ring-handler-serves-without-a-port
  (let [e (scratch)
        _ (dev/create! e :dev_chore {:name "sweep"})
        resp ((dev/handler e) {:request-method :get
                               :uri "/api/.well-known/waymark"
                               :headers {}})]
    (is (= 200 (:status resp)))))
