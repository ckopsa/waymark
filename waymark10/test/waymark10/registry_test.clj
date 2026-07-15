(ns waymark10.registry-test
  "Multi-kind assembly fixtures: a :project that owns :ticket (ref back
  at the parent, cascade abandon → cancel, open-tickets rollup) and a
  calendar-ish related edge over promoted date fields. The happy
  registry constructs; one minimal break per assembly check, refused by
  name. Every break is one thing changed from the valid trio."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.registry :as reg]
            [waymark10.resource :as r]))

;; ── the valid trio ──────────────────────────────────────────────────

(def project
  {:kind :project
   :states [:open :abandoned]
   :initial :open
   :terminal #{:abandoned}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:max 100}]]
            [:start_date :waymark/date]]
   :filterable {:state #{:eq :in} :start_date #{:eq :range}}
   :sortable {:fields [:start_date] :default "start_date"}
   :owns [{:kind :ticket
           :via :project_id
           :on {:abandon :cancel}
           :rollups {:open_tickets {:where {:state #{"open"}}}}}]
   :actions
   {:abandon {:from #{:open} :to :abandoned
              ;; the cascade IS a touch — the happy world advertises it
              :touches [{:kind :ticket :action :cancel :may true}]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The project and its tickets are discarded."}}}})

(def ticket
  ;; :schema entry order matters to the breaks below: index 2 is the
  ;; :project_id via entry the owns variants replace.
  {:kind :ticket
   :states [:open :done :cancelled]
   :initial :open
   :terminal #{:done :cancelled}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:max 100}]]
            [:project_id {:kind :project} :waymark/ref]
            [:due_date {:optional true} [:maybe :waymark/date]]
            [:points {:optional true} [:maybe :int]]
            [:tags [:vector [:waymark/vocab {:open true}]]]]
   :filterable {:state #{:eq :in}
                :project_id #{:eq}
                :due_date #{:eq :range}}
   :sortable {:fields [:points] :default "-points"}
   :related {:due_day {:kind :calendar_day
                       :on [[:due_date := :date]]}}
   :links [{:rel :agenda :edge :due_day}]
   :actions
   {:finish {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Finished work is history."}}
    :cancel {:from #{:open} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The ticket is discarded."}}}})

(def calendar-day
  {:kind :calendar_day
   :states [:scheduled :past]
   :initial :scheduled
   :terminal #{:past}
   :summary "{data.date} · {state}"
   :schema [:map
            [:date :waymark/date]
            [:label {:optional true} [:maybe [:string {:max 50}]]]]
   :filterable {:state #{:eq :in} :date #{:eq :range}}
   :sortable {:fields [:date] :default "date"}
   :actions
   {:pass {:from #{:scheduled} :to :past
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "Days pass on their own."}}}})

;; ── helpers ─────────────────────────────────────────────────────────

(defn- res
  "resource, with per-declaration usability warnings off the output."
  [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(defn- reg-of [& rdefs]
  (binding [*err* (java.io.StringWriter.)]
    (reg/registry (vec rdefs))))

(defn- assemble
  "The trio with optional per-kind overrides."
  [& {:keys [p t c] :or {p project t ticket c calendar-day}}]
  (reg-of (res p) (res t) (res c)))

(defn- breaks
  "Assert the assembly thunk is refused by the named check."
  [check thunk]
  (try
    (thunk)
    (is false (str "expected [" (name check) "] to refuse this assembly"))
    (catch clojure.lang.ExceptionInfo e
      (is (= check (:check (ex-data e))) (ex-message e)))))

;; ── the happy assembly ──────────────────────────────────────────────

(deftest the-happy-assembly
  (let [g (assemble)]
    (is (= [:calendar_day :project :ticket] (reg/kinds g)))
    (is (= [] (:waymark10/warnings (meta g))))
    (let [p (reg/rdef g :project)]
      (is (map? (:fingerprint p)))
      (is (string? (:fingerprint-hash p)))
      ;; the phase-5 law slots are initialized, awaiting the
      ;; definitions machinery
      (is (contains? p :current-law))
      (is (nil? (:current-law p)))
      (is (nil? (:proposed-law p)))
      (is (nil? (:piloted-law p)))
      (is (= {} (:law-ids p))))
    (is (nil? (reg/rdef g :nope)))))

;; ── registry errors ─────────────────────────────────────────────────

(deftest duplicate-kind
  (breaks :registry #(reg-of (res project) (res project))))

(deftest duplicate-plural
  (breaks :registry
          #(assemble :c (assoc calendar-day :plural "tickets"))))

;; ── refs ────────────────────────────────────────────────────────────

(deftest refs-unregistered-target
  ;; ticket's :project_id refs :project, which is absent
  (breaks :refs #(reg-of (res ticket) (res calendar-day))))

(deftest refs-naming-convention-warning
  (let [g (assemble :p (update project :schema conj
                               [:ticket_id {:optional true}
                                [:maybe [:string {:max 40}]]]))]
    (is (some #(and (str/includes? % "[refs]")
                    (str/includes? % "data.ticket_id")
                    (str/includes? % ":waymark/ref"))
              (:waymark10/warnings (meta g))))))

;; ── owns ────────────────────────────────────────────────────────────

(deftest owns-child-unregistered
  (breaks :owns #(reg-of (res project) (res calendar-day))))

(deftest owns-via-not-a-ref
  (breaks :owns
          #(assemble :t (assoc-in ticket [:schema 2]
                                  [:project_id [:string {:max 40}]]))))

(deftest owns-via-wrong-kind
  (breaks :owns
          #(assemble :t (assoc-in ticket [:schema 2]
                                  [:project_id {:kind :calendar_day}
                                   :waymark/ref]))))

(deftest owns-via-not-eq-filterable
  (breaks :owns
          #(assemble :t (update ticket :filterable dissoc :project_id))))

(deftest owns-cascade-target-takes-input
  (breaks :owns
          #(assemble :t (assoc-in ticket [:actions :cancel :input]
                                  [:map [:reason [:string {:max 100}]]]))))

(deftest owns-cascade-target-fenced
  (breaks :owns
          #(assemble :t (assoc-in ticket
                                  [:actions :cancel :safety :fence] true))))

(deftest owns-rollup-collides-with-parent-param
  (breaks :owns
          #(assemble :p (assoc-in project [:owns 0 :rollups]
                                  {:start_date {:where {:state #{"open"}}}}))))

(deftest owns-rollup-where-field-unfiltered
  ;; :points is sortable on the child, not filterable
  (breaks :owns
          #(assemble :p (assoc-in project
                                  [:owns 0 :rollups :open_tickets :where]
                                  {:points #{1}}))))

(deftest owns-rollup-sum-of-not-a-field
  (breaks :owns
          #(assemble :p (assoc-in project [:owns 0 :rollups :points_total]
                                  {:agg :sum :of :nope}))))

(deftest owns-rollup-sum-of-unpromoted
  ;; :title is a real child field but neither filterable nor sortable
  (breaks :owns
          #(assemble :p (assoc-in project [:owns 0 :rollups :points_total]
                                  {:agg :sum :of :title}))))

;; ── related ─────────────────────────────────────────────────────────

(deftest related-target-unregistered
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :kind] :nowhere))))

(deftest related-join-over-unpromoted-field
  (breaks :related
          #(assemble :t (update ticket :filterable dissoc :due_date))))

(deftest related-family-mismatch
  ;; :points (number, sortable) against :date (temporal)
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :on]
                                  [[:points := :date]]))))

(deftest related-array-join
  ;; :tags is a vocab array — auto-filterable, so promotion passes and
  ;; the array refusal is the one that fires
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :on]
                                  [[:tags := :date]]))))

(deftest related-unknown-op
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :on]
                                  [[:due_date :!= :date]]))))

(deftest related-ordered-op-needs-matching-families
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :on]
                                  [[:points :< :date]]))))

(deftest related-link-edge-strict-inequality
  ;; the edge itself is a legal ordered join; the LINK cannot compile
  ;; :< onto the public range grammar
  (breaks :related
          #(assemble :t (assoc-in ticket [:related :due_day :on]
                                  [[:due_date :< :date]]))))

(deftest related-link-edge-unknown
  (breaks :related
          #(assemble :t (assoc ticket :links [{:rel :agenda :edge :nope}]))))

;; ── derived-cycles ──────────────────────────────────────────────────

(deftest derived-cycles-cross-kind-over
  ;; assembled from an already-normalized map so the per-declaration
  ;; :derived check cannot fire first — the assembly battery keeps the
  ;; cross-kind door shut on its own
  (breaks :derived-cycles
          #(reg-of (assoc (res project)
                          :derived {:open_count {:over [:tickets_open]}})
                   (res ticket)
                   (res calendar-day))))
