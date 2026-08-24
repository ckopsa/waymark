(ns waymark10.checks-test
  "One deliberately-broken declaration per check, refused by name, plus
  the green acceptance of the real fixtures. Every break is minimal:
  one thing changed from a valid base."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

;; ── the valid base and helpers ──────────────────────────────────────

(def base
  {:kind :thing
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:max 100}]]]
   :actions {:close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Closing is cheap."}}}})

(def close-action (get-in base [:actions :close]))

(defn- with-action [m aname a] (assoc-in m [:actions aname] a))

(defn- load-quietly
  "resource, with usability warnings kept off the test output."
  [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(defn- warnings-of [m] (:waymark10/warnings (meta (load-quietly m))))

(defn- breaks
  "Assert the declaration is refused by the named check."
  [check m]
  (try
    (load-quietly m)
    (is false (str "expected [" (name check) "] to refuse this declaration"))
    (catch clojure.lang.ExceptionInfo e
      (is (= check (:check (ex-data e))) (ex-message e)))))

(defn- warns [substr m]
  (let [ws (warnings-of m)]
    (is (some #(str/includes? % substr) ws)
        (str "expected a warning containing " (pr-str substr) " in " (pr-str ws)))))

;; guards the breaks lean on
(def judge-x
  (g/guard {:name :judge-x :judges [:x]
            :explain "{x} is not right."
            :check (fn [_ _ _] (t/allow))}))

(def judge-name
  (g/guard {:name :judge-name :judges [:name]
            :explain "{name} is not allowed."
            :check (fn [_ _ _] (t/allow))}))

(def mystery
  (g/guard {:name :mystery :judges [:name]
            :explain "{mystery} happened."
            :check (fn [_ _ _] (t/allow))}))

(def key-listed
  (g/guard {:name :key-listed :judges [:key]
            :accepts (fn [row] (mapv :key (get-in row [:data :parts])))
            :explain "{key} is not a part."}))

;; ── the base is green ───────────────────────────────────────────────

(deftest the-base-declaration-is-green
  (is (= [] (warnings-of base))))

;; ── one break per check, refused by name ────────────────────────────

(deftest tokens
  (breaks :tokens (assoc base :initial :nope)))

(deftest reachability
  (breaks :reachability (update base :states conj :limbo)))

(deftest terminal-no-exit
  (breaks :terminal-no-exit
          (with-action base :reopen
            {:from #{:closed} :to :open
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Reopening is cheap."}})))

(deftest reversible
  (breaks :reversible
          (with-action base :close
            (assoc close-action
                   :safety {:idempotent true :reversible true :confirm false}))))

(deftest one-way
  ;; the door must open from a non-initial state: leaving the initial
  ;; state closes on nothing, and self-loops re-do themselves
  (breaks :one-way
          (-> base
              (assoc :states [:open :mid :closed])
              (assoc :actions
                     {:advance {:from #{:open} :to :mid
                                :safety {:idempotent true :reversible false
                                         :confirm false
                                         :one-way "Advancing is cheap."}}
                      :finish {:from #{:mid} :to :closed
                               :safety {:idempotent true :reversible false
                                        :confirm false}}}))))

(deftest guard-declarations
  (breaks :guard-declarations
          (with-action base :close (assoc close-action :guards [judge-x]))))

(deftest guard-templates
  (breaks :guard-templates
          (with-action base :close
            (assoc close-action
                   :input [:map [:name [:string {:max 100}]]]
                   :guards [mystery]))))

(deftest create-guards
  (breaks :create-guards (assoc base :create-guards [judge-x])))

(deftest closure
  (breaks :closure
          (with-action base :close
            (assoc close-action
                   :input [:map [:name :string]]
                   :guards [judge-name]))))

(deftest handler-signatures
  (breaks :handler-signatures
          (with-action base :close (assoc close-action :handler "not-a-fn"))))

(deftest summary-template
  (breaks :summary-template (assoc base :summary "{bogus} thing")))

(deftest waive-tokens
  (breaks :waive-tokens
          (with-action base :close (assoc close-action :waives #{:speed}))))

(deftest place
  (breaks :place
          (with-action base :close
            (assoc close-action
                   :place :parts
                   :input [:map [:key [:string {:max 20}]]]))))

(deftest edit
  (breaks :edit
          (with-action base :close
            (assoc close-action
                   :edit {:prefill [:nope]}
                   :input [:map [:name [:string {:max 100}]]]))))

(deftest faceted
  (breaks :faceted (assoc base :faceted [:name])))

(deftest oneof
  (breaks :oneof (assoc base :one-of {:naming {:arms {:a [:name] :b [:name]}}})))

;; ── :views — the deck-rule battery ──────────────────────────────────
;; A valid triage world: pending cards, each gesture reversible via
;; its :undo pair, each gesture draining the deck. Every refusal below
;; is this base with exactly one thing broken.

(def deck-base
  {:kind :ticket
   :states [:pending :approved :flagged]
   :initial :pending
   :terminal #{}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 100}]]]
   :filterable {:state #{:eq :in}}
   :flow [[:pending :approve :approved {:undo :unapprove
                                        :display {:label "Approve"}}]
          [:approved :unapprove :pending {:undo :approve}]
          [:pending :flag :flagged {:undo :unflag
                                    :display {:label "Flag"}}]
          [:flagged :unflag :pending {:undo :flag}]]
   :views [{:name :triage :kind :deck :where {:state "pending"}
            :right :approve :left :flag
            :card [:title] :display {:label "Triage"}}
           {:name :review :kind :feed :where {:state "pending"}
            :display {:label "Review"}}]})

(defn- with-views [views] (assoc deck-base :views views))

(def triage-view (first (:views deck-base)))

(deftest a-deck-and-feed-declaration-is-green
  (is (= [] (warnings-of deck-base))))

(deftest views-refuse-a-duplicate-name
  (breaks :views (with-views [triage-view (assoc triage-view :kind :feed
                                                 :right nil :left nil)]))
  ;; and the honest spelling of the same collision
  (breaks :views (with-views [(assoc triage-view :name :same)
                              {:name :same :kind :feed}])))

(deftest views-refuse-a-nameless-view
  (breaks :views (with-views [(dissoc triage-view :name)])))

(deftest a-deck-gesture-must-name-a-declared-action
  (breaks :views (with-views [(assoc triage-view :right :bless)])))

(deftest a-deck-gesture-must-be-reversible
  ;; :discard has no :undo — a swipe with no honest reverse refuses
  (breaks :views
          (-> deck-base
              (assoc-in [:actions :discard]
                        {:from #{:pending} :to :flagged
                         :safety {:idempotent true :reversible false
                                  :confirm false
                                  :one-way "Discarding is acknowledged."}})
              (assoc :views [(assoc triage-view :left :discard)]))))

(deftest a-deck-requires-a-where
  (breaks :views (with-views [(dissoc triage-view :where)])))

(deftest a-deck-where-must-constrain-state
  (breaks :views
          (-> deck-base
              (assoc :schema [:map [:title [:string {:min 1 :max 100}]]
                              [:owner {:filter #{:eq}} [:string {:max 60}]]])
              (assoc :views [(assoc triage-view :where {:owner "ana"})]))))

(deftest a-deck-gesture-must-depart-from-every-where-state
  ;; the deck shows pending AND flagged, but :approve only departs
  ;; from pending — a card the gesture refuses
  (breaks :views
          (with-views [(assoc triage-view
                              :where {:state #{:pending :flagged}}
                              :left :unflag)])))

(deftest a-deck-gesture-must-leave-the-where-states
  ;; :touch is honestly reversible (its own undo) but lands back in
  ;; pending — the queue would never drain
  (breaks :views
          (-> deck-base
              (assoc-in [:actions :touch]
                        {:from #{:pending} :to :pending :undo :touch
                         :safety {:idempotent true :reversible true
                                  :confirm false}})
              (assoc :views [(assoc triage-view :right :touch)]))))

(deftest a-view-card-names-schema-fields
  (breaks :views (with-views [(assoc triage-view :card [:title :priority])])))

(deftest a-view-where-is-an-expressible-filter
  ;; :title declares no filter ops — a view's where is an ordinary
  ;; filter the caller could have typed
  (breaks :views (with-views [{:name :mine :kind :feed
                               :where {:title "x"}}]))
  ;; and a state value that is not a state
  (breaks :views (with-views [(assoc triage-view
                                     :where {:state "nonexistent"})])))

(deftest a-deck-requires-both-gestures
  (breaks :views (with-views [(dissoc triage-view :left)])))

(deftest unique
  (breaks :unique (assoc base :unique [:name])))

(deftest links
  (breaks :links (assoc base :links [{:rel :self :badge :nope}])))

(deftest derived
  (breaks :derived
          (assoc base :derived {:score {:over [:nope] :expr '(var :nope)}})))

(deftest unless
  (breaks :unless
          (with-action base :close (assoc close-action :unless :nonexistent))))

(deftest renames
  (testing "a rename whose target reaches no declared state"
    (breaks :renames (assoc base :renames {:states {:draft :nowhere}})))
  (testing "a still-declared token cannot be renamed"
    (breaks :renames (assoc base :renames {:states {:open :closed}})))
  (testing "a chain that cycles reaches nothing"
    (breaks :renames (assoc base :renames {:states {:a :b, :b :a}})))
  (testing "an action rename to no declared action"
    (breaks :renames (assoc base :renames {:actions {:shut :nothing}})))
  (testing "an unknown rename surface"
    (breaks :renames (assoc base :renames {:columns {:a :b}})))
  (testing "the chain resolves through retired intermediates"
    (is (some? (load-quietly
                (assoc base :renames {:states {:begun :started
                                               :started :open}
                                      :actions {:shut :close}}))))))

(deftest require-check
  (testing "a fact nobody derives"
    (breaks :require
            (with-action base :close
              (assoc close-action :guards [(g/require :blessed)]))))
  (testing "a non-bool derivation is not a gate"
    (breaks :require
            (-> base
                (assoc :schema [:map [:name [:string {:max 100}]] [:score :int]]
                       :derived {:score {:over [:name]
                                         :expr '(is-set (var :name))}})
                (with-action :close
                  (assoc close-action :guards [(g/require :score)]))))))

;; ── the warnings ────────────────────────────────────────────────────

(deftest handler-without-form-metadata-warns
  (warns "no stateable identity — declare it with defhandler"
         (with-action base :close
           (assoc close-action :handler (fn [row _ _] row)))))

(deftest edit-shaped-warns
  (warns "edit-shaped"
         (with-action base :touch
           {:from #{:open} :to :open
            :input [:map [:name [:string {:max 100}]]]
            :safety {:idempotent true :reversible false :confirm false}})))

(defn- touching
  "base with a touch action whose only input is the given field form —
  the minimal edit-shaped candidate."
  [field]
  (with-action base :touch
    {:from #{:open} :to :open
     :input (into [:map] [field])
     :safety {:idempotent true :reversible false :confirm false}}))

(defn- does-not-warn [substr m]
  (let [ws (warnings-of m)]
    (is (not-any? #(str/includes? % substr) ws)
        (str "expected NO warning containing " (pr-str substr)
             " in " (pr-str ws)))))

(deftest edit-shaped-warns-on-near-mirrors
  ;; data.name is [:string {:max 100}]. Each of these is the same field
  ;; wearing one ordinary bit of drift; strict = called them all
  ;; strangers and the nudge went silent (waymark-01f).
  (testing "an added :x-display label is presentation, not a new field"
    (warns "edit-shaped"
           (touching [:name {:x-display {:label "Name"}} [:string {:max 100}]])))
  (testing "a tightened constraint is the same field, narrower"
    (warns "edit-shaped" (touching [:name [:string {:max 20}]])))
  (testing "a :maybe wrapper is the same field, nullable"
    (warns "edit-shaped" (touching [:name [:maybe [:string {:max 100}]]])))
  (testing "an :optional wrapper is the same field, skippable"
    (warns "edit-shaped" (touching [:name {:optional true} [:string {:max 100}]])))
  (testing "all of it at once"
    (warns "edit-shaped"
           (touching [:name {:optional true :x-display {:label "Name"}}
                      [:maybe [:string {:max 20}]]]))))

(deftest edit-shaped-stays-quiet-on-strangers
  (testing "a field name the data schema never declares"
    (does-not-warn "edit-shaped" (touching [:note [:string {:max 100}]])))
  (testing "the same name over a different base type"
    (does-not-warn "edit-shaped" (touching [:name :int])))
  (testing "the same name and leaf, but a list where the document holds one"
    (does-not-warn "edit-shaped" (touching [:name [:vector [:string {:max 100}]]]))))

(deftest edit-shape-is-waivable
  ;; the looser rule sees name and shape, never emptiness: a door that
  ;; welds a FIRST value onto a blank field reads identically to one
  ;; rewriting an existing one (members/bind is the live case). The
  ;; waiver is how a declaration says which it is.
  (let [mirror [:name {:x-display {:label "Name"}} [:string {:max 100}]]]
    (testing "the near-mirror warns on its own"
      (warns "edit-shaped" (touching mirror)))
    (testing "and goes quiet when the action waives :edit-shape"
      (does-not-warn "edit-shaped"
                     (with-action base :touch
                       {:from #{:open} :to :open
                        :input (into [:map] [mirror])
                        :waives #{:edit-shape}
                        :safety {:idempotent true :reversible false
                                 :confirm false}})))
    (testing "and :edit-shape is a known token, not a typo the gate rejects"
      (does-not-warn "waives unknown"
                     (with-action base :touch
                       {:from #{:open} :to :open
                        :input (into [:map] [mirror])
                        :waives #{:edit-shape}
                        :safety {:idempotent true :reversible false
                                 :confirm false}})))))

(deftest prose-required-warns
  (warns "demands composition with no draft"
         (with-action base :annotate
           {:from #{:open} :to :open
            :input [:map [:body {:x-display {:widget "prose"}}
                          [:string {:max 8000}]]]
            :safety {:idempotent true :reversible false :confirm false}})))

(deftest altitude-warns
  (warns "re-asks the user to identify an item of data.parts"
         (-> base
             (assoc :schema [:map
                             [:name [:string {:max 100}]]
                             [:parts [:vector [:map [:key [:string {:max 20}]]]]]])
             (with-action :tag
               {:from #{:open} :to :open
                :input [:map [:key [:string {:max 20}]]]
                :guards [key-listed]
                :safety {:idempotent true :reversible false :confirm false}}))))

(deftest long-text-warns
  (warns "data.bio is a text field with no shape"
         (assoc base :schema [:map
                              [:name [:string {:max 100}]]
                              [:bio :string]])))

;; ── the runtime-vocabulary spelling (waymark-8sg) ───────────────────
;; A typo in :x-options is otherwise SILENT — the projection omits the
;; annotation, the picker never appears, and the field goes back to
;; being the blank rectangle the whole spelling exists to end. So the
;; check refuses all three ways of misspelling it.

(deftest options-refuse-a-source-this-engine-cannot-fetch
  (testing "an unknown :from"
    (breaks :options
            (assoc base :schema [:map
                                 [:name {:x-options {:from :everything}}
                                  [:string {:max 100}]]])))

  (testing "a relative source with nothing to be relative to"
    (breaks :options
            (assoc base :schema [:map
                                 [:name {:x-options {:from :fields}}
                                  [:string {:max 100}]]])))

  (testing ":of naming a field this form does not declare"
    (breaks :options
            (assoc base :schema [:map
                                 [:name {:x-options {:from :fields :of :nowhere}}
                                  [:string {:max 100}]]])))

  (testing "a composition grammar nobody speaks"
    (breaks :options
            (assoc base :schema [:map
                                 [:target [:string {:max 60}]]
                                 [:name {:x-options {:from :filters :of :target
                                                     :composes :sql}}
                                  [:string {:max 100}]]])))

  (testing "the spelling as it is meant to be written loads clean"
    (is (= [] (warnings-of
               (assoc base :schema
                      [:map
                       [:target {:x-options {:from :kinds}
                                 :x-display {:label "Target"
                                             :help "One of this engine's kinds."}}
                        [:string {:max 60}]]
                       [:name {:x-options {:from :fields :of :target :each true}
                               :x-display {:label "Fields"
                                           :help "Which of the target's fields."}}
                        [:string {:max 100}]]]))))))

;; ── the acceptance: the real fixtures are green ─────────────────────

(deftest the-fixtures-load-with-zero-warnings
  (is (= :meal (:kind fx/meal)))
  (is (= [] (:waymark10/warnings (meta fx/meal))))
  (is (= :plan (:kind fx/plan)))
  (is (= [] (:waymark10/warnings (meta fx/plan)))))
