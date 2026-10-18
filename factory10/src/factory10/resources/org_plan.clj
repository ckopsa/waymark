(ns factory10.resources.org-plan
  "The org_plan: the row a person and the onboarding seat work on
  together to stand an org up (ticket cd22be20, M2a of epic cafa6106).

  THE BRIEF IS THE PERSON'S, THE PLAN IS THE SEAT'S. A person writes
  one paragraph, the `brief`, and names the org. The onboarding seat
  writes the org directory (`orgs/<name>`), asks the questions the
  engine's own audit raised, and opens the pull request. The person
  answers, watches the film, and takes the one tap that makes it real.

  THE MACHINE IS THE WALK. drafting → proposed (the pull request is
  up and no error finding is open) → simulated (a film of the org's
  first week is attached) → live (merged, and its mayor sits). Any of
  them may be retired, by the owner.

  `findings` IS WRITTEN BY ONE DOOR, AND IT IS NOT HERE YET. The
  findings door (M2b) writes the audit's findings onto the row; no
  door of this ticket writes them, and no create form takes them. A
  question ties to a finding by its key.

  `propose` IS REFUSED WHILE AN ERROR FINDING IS OPEN. A plan the
  audit says is broken does not go up as a pull request: the refusal
  names the finding, and the way out is to answer the question it
  raised and let the audit run again.

  `answer` AND `go_live` ARE THE OWNER'S. A question is asked of the
  person who owns the plan, so only that person answers it, or an
  agent acting for them whose answer is held for their tap. `go_live`
  is the one tap: an agent's go_live is HELD, and the owner's Allow
  runs it. The merge and the mayor's sitting behind it are M2e's.

  :nav :secondary, for change's reason: standing an org up is the
  day job's work, not the family's."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── whose plan it is ────────────────────────────────────────────────

(defn- the-person
  "The id of the person this hand is, or acts for: a person's own id,
  an agent's `:acts-for`. The engine's own hand and an agent acting
  for nobody answer nil."
  [ctx]
  (let [p (:principal ctx)]
    (some-> (case (:type p)
              :agent (:acts-for p)
              :system nil
              (:id p))
            str not-empty)))

(defn- stamp-the-owner
  "The birth's stamps: the owner is the person who filed the plan, and
  the org directory is orgs/<name> unless the filer named one."
  [row ctx]
  (cond-> row
    (some? (the-person ctx))
    (assoc-in [:data :owner] (the-person ctx))

    (str/blank? (str (get-in row [:data :org_dir])))
    (assoc-in [:data :org_dir] (str "orgs/" (get-in row [:data :name])))))

;; ── the doors' writes ───────────────────────────────────────────────

(defhandler ask-the-owner [row inp _ctx]
  ;; A question is appended, never rewritten: the answer is the
  ;; owner's door. Asking again under a key already asked is nothing,
  ;; so a retried ask is safe.
  (if (some #(= (:key inp) (:key %)) (get-in row [:data :questions]))
    row
    (update-in row [:data :questions]
               (fnil conj [])
               (select-keys inp [:key :finding_key :question]))))

(defhandler answer-the-question [row inp ctx]
  (update-in row [:data :questions]
             (fn [qs]
               (mapv #(if (= (:key inp) (:key %))
                        (assoc % :answer (:answer inp) :answered_at (:now ctx))
                        %)
                     qs))))

(defhandler put-the-pull-request-up [row inp _ctx]
  (cond-> row
    (some? (:change inp)) (assoc-in [:data :change] (:change inp))))

(defhandler attach-the-film [row inp _ctx]
  (assoc-in row [:data :film_url] (:film_url inp)))

(defhandler retire-the-plan [row inp _ctx]
  ;; A retired plan says why, because nothing departs `retired`.
  (assoc-in row [:data :retired_why] (:why inp)))

;; ── the walls ───────────────────────────────────────────────────────

(defguardfn a-person-files-the-plan
  {:reads [:principal]
   :explain "An org_plan is a person's: it is filed by a person, or by an agent acting for one, and that person owns it. A hand that is nobody's has no one to ask the questions of."
   :open "File it as a person, or as an agent acting for one."}
  [_row _inp ctx]
  (if (some? (the-person ctx)) (t/allow) (t/deny)))

(defguardfn only-the-owner-decides
  {:reads [:principal]
   :explain "This is the owner's decision: only the person who owns this plan, or an agent acting for them, may take this door."
   :open "Ask the plan's owner to take it."}
  [row _inp ctx]
  (cond
    (= :system (:type (:principal ctx))) (t/allow)
    (and (some? (the-person ctx))
         (= (the-person ctx) (str (get-in row [:data :owner])))) (t/allow)
    :else (t/deny)))

(defguardfn only-a-person-answers
  {:reads [:principal :within]
   :hold true
   :explain "An answer is the owner's word, so an agent's answer is held for the owner's tap: the call is recorded as a held_call, and the owner's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the owner's tap."}
  [row _inp ctx]
  ;; ticket's `only-a-person-reopens`, one kind over.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :answer (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn only-a-person-goes-live
  {:reads [:principal :within]
   :hold true
   :explain "Going live merges the org and seats its mayor, so it is the owner's one tap: an agent's go_live is held as a held_call, and the owner's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the owner's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :org_plan :go_live (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn the-question-was-asked
  {:reads []
   :explain "There is no question on this plan with that key, so there is nothing to answer."
   :open "Answer a question the plan carries: its key is in the plan's questions."}
  [row inp _ctx]
  (if (some #(= (:key inp) (:key %)) (get-in row [:data :questions]))
    (t/allow)
    (t/deny)))

(defguardfn no-error-finding-is-open
  {:reads []
   :vars [:finding]
   :explain "The audit found an error in this plan ({finding}), and a plan with an open error does not go up as a pull request."
   :open "Answer the question the finding raised, and let the audit run again: propose opens once no error finding is open."}
  [row _inp _ctx]
  (if-some [f (first (filter #(= "error" (str (:severity %)))
                             (get-in row [:data :findings])))]
    (t/deny {:vars {:finding (str (:key f) ": " (:sentence f))}})
    (t/allow)))

;; ── the law, written down as scenarios ──────────────────────────────

(def ^:private a-plan
  {:name "greenleaf"
   :brief "A six-person landscaping business: quotes, crew scheduling, invoicing and late payers."
   :org_dir "orgs/greenleaf"
   :owner "colton"
   :questions [{:key "q1" :finding_key "f1"
                :question "Who approves a quote over $5k?"}]})

(def ^:private an-error
  {:key "f1" :severity "error" :class "person_only" :subject "quote"
   :sentence "No one approves a quote over $5k."
   :remedy "Name who approves a quote over $5k."})

(defscenario an-open-error-keeps-the-plan-down
  "A plan the audit says is broken does not go up as a pull request."
  {:kind    :org_plan
   :attempt :propose
   :row     {:state :drafting :data (assoc a-plan :findings [an-error])}
   :input   {}
   :as      {:id "onboarding" :type :agent}
   :expect  {:refused :no-error-finding-is-open}})

(defscenario a-plan-with-no-error-goes-up
  "And with no error open, the onboarding seat puts it up."
  {:kind    :org_plan
   :attempt :propose
   :row     {:state :drafting
             :data (assoc a-plan :findings [(assoc an-error :severity "warning")])}
   :input   {}
   :as      {:id "onboarding" :type :agent}
   :expect  {:allowed true}})

(defscenario the-owner-answers
  "The owner answers the question the seat asked."
  {:kind    :org_plan
   :attempt :answer
   :row     {:state :drafting :data a-plan}
   :input   {:key "q1" :answer "I do, and over $20k my partner does too."}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-stranger-does-not-answer
  "A question is asked of the owner, and nobody else answers it."
  {:kind    :org_plan
   :attempt :answer
   :row     {:state :drafting :data a-plan}
   :input   {:key "q1" :answer "Whoever is around."}
   :as      {:id "sam" :type :person}
   :expect  {:refused :only-the-owner-decides}})

(defscenario an-agent-does-not-go-live-alone
  "Going live is the owner's one tap: an agent's go_live is refused
   here, and at the wire held for the owner."
  {:kind    :org_plan
   :attempt :go_live
   :row     {:state :simulated
             :data (assoc a-plan :film_url "https://example.com/greenleaf.mp4")}
   :as      {:id "onboarding" :type :agent}
   :expect  {:refused :only-a-person-goes-live}})

(defscenario the-owner-goes-live
  "And the owner's own tap runs it."
  {:kind    :org_plan
   :attempt :go_live
   :row     {:state :simulated
             :data (assoc a-plan :film_url "https://example.com/greenleaf.mp4")}
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

;; ── :org_plan ───────────────────────────────────────────────────────

(def ^:private finding-schema
  [:map
   [:key [:string {:min 1 :max 64}]]
   [:severity [:string {:max 32}]]
   [:class {:optional true} [:maybe [:string {:max 64}]]]
   [:subject {:optional true} [:maybe [:string {:max 200}]]]
   [:sentence {:optional true} [:maybe [:string {:max 240}]]]
   [:remedy {:optional true} [:maybe [:string {:max 240}]]]])

(def ^:private question-schema
  [:map
   [:key [:string {:min 1 :max 64}]]
   [:finding_key {:optional true} [:maybe [:string {:max 64}]]]
   [:question [:string {:min 1 :max 240}]]
   [:answer {:optional true :x-display {:widget "prose"}}
    [:maybe [:string {:max 2000}]]]
   [:answered_at {:optional true} [:maybe :waymark/instant]]])

(defresource org-plan
  {:kind :org_plan
   :plural "org_plans"
   ;; the day job's work, not the family's — see the ns docstring
   :nav :secondary
   :states [:drafting :proposed :simulated :live :retired]
   :initial :drafting
   :terminal #{:retired}
   ;; THE CRACKS LAW. At `simulated` the onboarding seat's go_live is
   ;; held for the owner's tap, so the row waits on that held call and
   ;; the engine's landing of it is what moves the row to `live`.
   :waits [{:on :held_call :awaits :held_call :in #{:simulated}
            :waiter "the onboarding seat, whose go_live waits on the owner's tap"
            :outcomes {:done :release :refused :return
                       :failed :return :expired :return}}]
   :left-by [{:in #{:simulated} :by :engine :via :go_live :to :live}]
   :expected-age {:drafting 86400 :proposed 21600 :simulated 604800}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :display {:title "{data.name}"}
   :filterable {:state #{:eq :in}
                :name #{:eq}
                :owner #{:eq}}
   :on-create stamp-the-owner
   :create-guards [a-person-files-the-plan]
   :schema
   [:map
    [:name {:x-display
            {:label "The org's name"
             :help "A short name for the org, as its directory spells it: greenleaf, or acme-landscaping."}}
     [:string {:min 1 :max 64}]]
    [:brief {:optional true
             :x-display
             {:widget "prose"
              :label "The brief"
              :help "The person's own paragraph: what the business does, and what they want the house to run."}}
     [:maybe [:string {:max 4000}]]]
    [:org_dir {:optional true
               :x-display
               {:raw true
                :label "The org's directory"
                :help "Where the org's declarations live in the repository: orgs/ and the org's name, unless the filer named another."}}
     [:maybe [:string {:max 200}]]]
    ;; WRITTEN ONLY BY THE FINDINGS DOOR (M2b), and read by propose.
    [:findings {:optional true
                :x-display
                {:label "What the audit found"
                 :help "The engine's own audit of the plan, one finding a line. An error finding keeps the plan from going up as a pull request."}}
     [:maybe [:vector {:max 200} finding-schema]]]
    [:questions {:optional true
                 :x-display
                 {:label "Questions for the owner"
                  :help "What the onboarding seat asked, each tied to the finding that raised it, and the owner's answer."}}
     [:maybe [:vector {:max 100} question-schema]]]
    [:change {:optional true :kind :change
              :x-display {:label "The pull request"}}
     [:maybe :waymark/ref]]
    [:film_url {:optional true
                :x-display
                {:raw true
                 :label "The film"
                 :help "The film of the org's simulated first week, for the owner to watch before going live."}}
     [:maybe [:string {:max 500}]]]
    [:retired_why {:optional true
                   :x-display
                   {:label "Why it was retired"
                    :help "The owner's one sentence, written by the retire door."}}
     [:maybe [:string {:max 240}]]]
    ;; stamped at birth, never input
    [:owner {:optional true :kind :member
             :x-display
             {:label "Owner"
              :help "The person who filed the plan, or the person the agent that filed it acts for. The engine writes it at birth."}}
     [:maybe :waymark/ref]]]
   :create-schema
   [:map
    [:name {:x-display {:label "The org's name"
                        :help "A short name for the org, as its directory spells it: greenleaf, or acme-landscaping."}}
     [:string {:min 1 :max 64}]]
    [:brief {:optional true
             :examples ["A six-person landscaping business. I want the house to run quotes, crew scheduling, invoicing and late payers."]
             :x-display {:widget "prose" :label "The brief"
                         :help "One paragraph in your own words: what the business does, and what you want the house to run."}}
     [:maybe [:string {:max 4000}]]]
    [:org_dir {:optional true
               :x-display {:raw true :label "The org's directory"
                           :help "Where the org's declarations will live. Leave it empty for orgs/ and the org's name."}}
     [:maybe [:string {:max 200}]]]]
   :actions
   {:ask
    {:from #{:drafting} :to :drafting
     :handler ask-the-owner
     :input [:map
             [:key {:x-display {:raw true :label "The question's key"
                                :help "A short key this question is known by on the plan; the owner's answer names it."}}
              [:string {:min 1 :max 64}]]
             [:finding_key {:optional true
                            :x-display {:raw true :label "The finding it answers"
                                        :help "The key of the audit finding that raised this question."}}
              [:maybe [:string {:max 64}]]]
             [:question {:examples ["Who approves a quote over $5k?"]
                         :x-display {:label "The question"
                                     :help "One plain question for the owner, as the finding raised it."}}
              [:string {:min 1 :max 240}]]]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The question stays on the plan, and the owner's answer is written beside it."}
     :display {:label "Ask" :order 1
               :description "Ask the owner one question the audit raised"}}

    :answer
    {:from #{:drafting} :to :drafting
     :guards [only-the-owner-decides only-a-person-answers the-question-was-asked]
     :handler answer-the-question
     :input [:map
             [:key {:x-display {:raw true :label "The question's key"
                                :help "The key of the question you are answering, as the plan's questions show it."}}
              [:string {:min 1 :max 64}]]
             [:answer {:examples ["I approve every quote over $5k myself."]
                       :x-display {:widget "prose" :label "Your answer"
                                   :help "Answer in plain words, as you would tell a new office manager."}}
              [:string {:min 1 :max 2000}]]]
     ;; :large-effort — an answer is a few plain sentences, typed once
     :waives #{:large-effort}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Answer" :order 2
               :description "Answer a question the onboarding seat asked"}}

    :propose
    {:from #{:drafting} :to :proposed
     :guards [no-error-finding-is-open]
     :handler put-the-pull-request-up
     :input [:map
             [:change {:optional true :kind :change
                       :x-display {:label "The pull request"}}
              [:maybe :waymark/ref]]]
     ;; :edit-shape — the first pull request onto a blank row is not an
     ;; edit of one
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The pull request is up for the owner to see."}
     :display {:label "Propose" :order 3
               :description "Put the plan up as a pull request"}}

    :attach_film
    {:from #{:proposed} :to :simulated
     :handler attach-the-film
     :input [:map
             [:film_url {:x-display {:raw true :label "The film"
                                     :help "Where the film of the org's simulated first week is served, for the owner to watch."}}
              [:string {:min 1 :max 500}]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The film is on the plan for the owner to watch."}
     :display {:label "Attach the film" :order 4
               :description "Attach the film of the org's simulated first week"}}

    ;; THE ONE TAP. The merge and the mayor behind it are M2e's.
    :go_live
    {:from #{:simulated} :to :live
     :guards [only-a-person-goes-live only-the-owner-decides]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The org is live: it is merged and its mayor sits. The way back is retire."}
     :display {:label "Go live" :order 5
               :description "Merge to make it real"}}

    :retire
    {:from #{:drafting :proposed :simulated :live} :to :retired
     :guards [only-the-owner-decides]
     :handler retire-the-plan
     :input [:map
             [:why {:examples ["We sold the business."]
                    :x-display {:label "Why"
                                :help "One sentence for the next reader: why this plan is let go. A retired plan takes no more doors."}}
              [:string {:min 1 :max 240}]]]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The plan is retired and takes no more doors."}
     :display {:label "Retire" :order 6
               :description "Let this plan go"}}}
   :scenarios [an-open-error-keeps-the-plan-down
               a-plan-with-no-error-goes-up
               the-owner-answers
               a-stranger-does-not-answer
               an-agent-does-not-go-live-alone
               the-owner-goes-live]})
