(ns waymark10.usability-test
  "The usability battery (waymark-0ee): five policies, each proved
  twice — a declaration that earns the warning and reads the fix in
  it, and a compliant declaration the battery has nothing to say
  about. The silent case is the load-bearing one: a policy that
  cannot be satisfied is a policy nobody can act on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]
            [waymark10.usability :as u]))

(defn- warns
  "The battery's sentences for one declaration, filtered to a policy."
  [rmap policy]
  (filterv #(str/starts-with? % (str "[" policy "]"))
           (u/warnings (r/resource rmap))))

;; ── a compliant kind: the silence case, and the base every other
;;    fixture bends out of shape ──────────────────────────────────────

(def compliant
  {:kind :errand
   :nav :primary
   :states [:pending :done :dropped]
   :initial :pending
   :terminal #{}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :schema [:map
            [:title {:x-display {:label "What needs doing"
                                 :help "A few words the whole house reads."}}
             [:string {:min 1 :max 60}]]
            [:size {:x-display {:label "How big"
                                :choices {"small" "A few minutes"
                                          "big" "Half an afternoon"}}}
             [:enum "small" "big"]]]
   :views [{:name :triage :kind :deck :where {:state "pending"}
            :card [:title] :right :finish :left :drop}]
   :filterable {:state #{:eq :in}}
   :actions
   {:finish {:from #{:pending} :to :done :undo :reopen
             :safety {:idempotent true :confirm false}
             :display {:label "Done"}}
    :reopen {:from #{:done} :to :pending :undo :finish
             :safety {:idempotent true :confirm false}
             :display {:label "Reopen"}}
    :drop {:from #{:pending} :to :dropped :undo :reopen_dropped
           :safety {:idempotent true :confirm false}
           :display {:label "Not today"}}
    :reopen_dropped {:from #{:dropped} :to :pending :undo :drop
                     :safety {:idempotent true :confirm false}
                     :display {:label "Back on"}}}})

(deftest a-compliant-declaration-passes-silently
  (is (= [] (u/warnings (r/resource compliant)))))

;; ── 1 · effort honesty ──────────────────────────────────────────────

(g/defguard machinery-only
  {:reads [:principal]
   :hide true
   :explain "The sync writes this; a person never does."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type])) (t/allow) (t/deny)))

(g/defguard names-a-declared-thing
  {:judges [:token]
   :vars []
   :open "The legal tokens are the registry's, one GET away."
   :explain "That is not a token this engine serves."}
  [_row _inp _ctx]
  (t/allow))

(deftest effort-honesty-warns-where-the-engine-could-have-offered-a-picker
  (let [ws (warns (assoc-in compliant [:actions :retitle]
                            {:from #{:pending} :to :pending
                             :input [:map [:token [:string {:max 60}]]]
                             :guards [names-a-declared-thing]
                             :safety {:idempotent true :reversible false :confirm false
                                      :one-way "A retitle is a retitle."}
                             :display {:label "Retitle"}})
                  "effort-honesty")]
    (is (= 1 (count ws)))
    (is (str/includes? (first ws) "action retitle field :token is free text"))
    (is (str/includes? (first ws) "names-a-declared-thing"))
    (is (str/includes? (first ws) ":enum")))

  (testing "the same field, offered as a choice, is silent"
    (is (= [] (warns (assoc-in compliant [:actions :retitle]
                               {:from #{:pending} :to :pending
                                :input [:map [:token [:enum "a" "b"]]]
                                :guards [names-a-declared-thing]
                                :safety {:idempotent true :reversible false :confirm false
                                          :one-way "A retitle is a retitle."}
                                :display {:label "Retitle"}})
                     "effort-honesty")))))

;; ── 2 · mandatory display prose ─────────────────────────────────────

(deftest display-prose-warns-about-labels-hints-and-bare-enum-tokens
  (let [ws (warns (assoc-in compliant [:actions :note]
                            {:from #{:pending} :to :pending
                             :input [:map
                                     [:body [:string {:max 200}]]
                                     [:mood [:enum "glad" "grim"]]]
                             :safety {:idempotent true :reversible false :confirm false
                                      :one-way "A note is a note."}
                             :display {:label "Note"}})
                  "display-prose")]
    (is (= 2 (count ws)))
    (is (some #(and (str/includes? % "no :x-display :label on [:body :mood]")
                    (str/includes? % "no :help sentence on the typed demand(s) [:body]"))
              ws))
    (is (some #(str/includes? % "offers the enum(s) [:mood] as bare wire tokens")
              ws)))

  (testing "a hidden field is nobody's form"
    (is (= [] (warns (assoc-in compliant [:actions :note]
                               {:from #{:pending} :to :pending
                                :input [:map [:body {:x-display {:hidden true}}
                                              [:string {:max 200}]]]
                                :safety {:idempotent true :reversible false :confirm false
                                          :one-way "A note is a note."}
                                :display {:label "Note"}})
                     "display-prose"))))

  (testing "a concealed action owes a human nothing"
    (is (= [] (warns (assoc-in compliant [:actions :note]
                               {:from #{:pending} :to :pending
                                :input [:map [:body [:string {:max 200}]]]
                                :guards [machinery-only]
                                :safety {:idempotent true :reversible false :confirm false
                                          :one-way "A note is a note."}
                                :display {:label "Note"}})
                     "display-prose")))))

;; ── 3 · composition scaffolding ─────────────────────────────────────

(def ^:private prose-field
  [:map [:story {:x-display {:widget "prose"
                             :label "The story"
                             :help "As long as it needs to be."}}
         [:string {:max 4000}]]])

(deftest composition-scaffolding-warns-at-a-standing-start
  (let [ws (warns (assoc-in compliant [:actions :tell]
                            {:from #{:pending} :to :pending
                             :input prose-field
                             :record true
                             :safety {:idempotent true :reversible false :confirm false
                                      :one-way "Told is told."}
                             :display {:label "Tell it"}})
                  "composition-scaffolding")]
    (is (= 1 (count ws)))
    (is (str/includes? (first ws) "action tell demands composition in [:story]"))
    (is (str/includes? (first ws) ":examples")))

  (testing "an examples value is enough to start from"
    (is (= [] (warns (assoc-in compliant [:actions :tell]
                               {:from #{:pending} :to :pending
                                :input [:map
                                        [:story {:x-display
                                                 {:widget "prose"
                                                  :label "The story"
                                                  :help "As long as it needs."}
                                                 :examples ["We ran out of milk again."]}
                                         [:string {:max 4000}]]]
                                :record true
                                :safety {:idempotent true :reversible false :confirm false
                                         :one-way "Told is told."}
                                :display {:label "Tell it"}})
                     "composition-scaffolding")))))

;; ── 4 · gesture duties ──────────────────────────────────────────────

(deftest gesture-duties-warns-about-long-chips-and-heavy-swipes
  (testing "a chip that will not fit under a thumb"
    (let [ws (warns (assoc-in compliant [:actions :finish :display :label]
                              "Finish it and log the minutes")
                    "gesture-duties")]
      (is (= 1 (count ws)))
      (is (str/includes? (first ws) "view :triage binds :right to finish"))
      (is (str/includes? (first ws) "a word or two"))))

  (testing "a swipe that would collect a form"
    (let [ws (warns (-> compliant
                        (assoc-in [:actions :finish :input]
                                  [:map [:minutes [:int {:min 0}]]])
                        (assoc-in [:actions :finish :edit]
                                  {:prefill []}))
                    "gesture-duties")]
      (is (= 1 (count ws)))
      (is (str/includes? (first ws) "whose demand is recall"))
      (is (str/includes? (first ws) "effort selection or less")))))

;; ── 5 · card completeness ───────────────────────────────────────────

(deftest card-completeness-warns-when-a-row-cannot-name-itself
  (let [ws (warns (dissoc compliant :label-template) "card-completeness")]
    (is (= 1 (count ws)))
    (is (str/includes? (first ws) ":primary-nav kind with no :label-template"))
    (is (str/includes? (first ws) "raw id")))

  (testing "a :name field is the label the engine defaults to"
    (is (= [] (warns (-> compliant
                         (dissoc :label-template)
                         (assoc :summary "{data.name} · {state}")
                         (assoc :schema [:map [:name [:string {:max 60}]]])
                         (dissoc :views))
                     "card-completeness"))))

  (testing "a :system kind is on nobody's nav"
    (is (= [] (warns (-> compliant (dissoc :label-template) (assoc :nav :system))
                     "card-completeness")))))
