(ns waymark10.invitations-test
  "The invitation kind (docs/spec-guided-follow.md § 3): an agent
  hands one step to a person, and the person's own transition answers
  it. Memory storage and the real engine; the resolution consumer is
  drained by hand, so every case is deterministic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private chore
  "The row a step acts on: one door, one plain argument and one secret."
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
     [:pin {:optional true :x-display {:label "Lock code"}}
      [:maybe [:string {:max 12}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:pin {:optional true :x-secret true
                             :x-display {:label "Lock code"}}
                       [:maybe [:string {:max 12}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :pin])))
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Rename" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 2}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))
(def ^:private planner (t/principal {:id "planner" :type :agent}))

(defn- drain! [eng]
  (consumers/drain-consumer! eng invitations/consumer-name
                             (invitations/consumer-fn eng)))

(defn- fresh-engine []
  (doto (engine/engine {:storage (memory/storage) :resources [chore]})
    ;; seed the cursor before any write, so every later one is heard
    (drain!)))

(defn- row-of [eng k id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) k)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx k (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng k id] (some-> (row-of eng k id) :state name))

(defn- chore! [eng title]
  (:id (:row (inv/create! eng :chore {:title title} {:principal person}))))

(defn- grant-seeing
  "The guard's-eye view of a grant whose scope sees one chore."
  [visible]
  {:id "grant-planner"
   :action? (fn [_kind _action] true)
   :row? (fn [_kind id] (= (str id) (str visible)))})

(defn- invite!
  ([eng chore-id extra] (invite! eng chore-id extra (grant-seeing chore-id)))
  ([eng chore-id extra grant]
   (:row (inv/create! eng :invitation
                      (merge {:subject "colton"
                              :self (str "/api/chores/" chore-id)
                              :action "rename"
                              :field "title"
                              :note "Pick the new title here."}
                             extra)
                      {:principal planner :grant grant}))))

(defn- refusal
  "The refusal's sentence, or nil when the call went through."
  [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(defn- rename! [eng id principal title]
  (inv/invoke! eng :chore (str id) :rename {:title title}
               {:principal principal}))

(deftest the-persons-own-transition-answers-the-invitation
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        inv-row (invite! eng c {})]
    (is (= "open" (name (:state inv-row))))
    (is (= "planner" (get-in inv-row [:data :author]))
        "the engine stamps the author from the principal")
    (is (some? (get-in inv-row [:data :expires_at])))
    (rename! eng c person "Dishes, then floors")
    (drain! eng)
    (let [after (row-of eng :invitation (:id inv-row))]
      (is (= "answered" (name (:state after))))
      (is (not (str/blank? (str (get-in after [:data :answered_by]))))
          "answered_by names the person's transition"))))

(deftest an-author-cannot-invite-onto-a-row-it-cannot-see
  (let [eng (fresh-engine)
        seen (chore! eng "Dishes")
        hidden (chore! eng "Laundry")]
    (testing "a row outside the author's grant"
      (is (some? (refusal #(invite! eng hidden {} (grant-seeing seen))))))
    (testing "an agent with no grant at all"
      (is (some? (refusal #(invite! eng seen {} nil)))))
    (testing "a door the kind does not have"
      (is (some? (refusal #(invite! eng seen {:action "burn"})))))
    (testing "the row it can see"
      (is (nil? (refusal #(invite! eng seen {})))))))

(deftest a-secret-field-cannot-be-invited
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")]
    (is (some? (refusal #(invite! eng c {:field "pin"}))))
    (is (some? (refusal #(invite! eng c {:field "colour"})))
        "a field the action does not take")
    (is (nil? (refusal #(invite! eng c {:field "title"}))))))

(deftest another-actors-transition-does-not-answer-it
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        inv-row (invite! eng c {})]
    (rename! eng c other "Iris did it")
    (drain! eng)
    (is (= "open" (state-of eng :invitation (:id inv-row))))))

(deftest an-open-invitation-expires
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        past (invite! eng c {:expires_at (str (.minusSeconds (Instant/now) 60))})
        later (invite! eng c {})]
    (is (= 1 (invitations/sweep-expired! eng)))
    (is (= "expired" (state-of eng :invitation (:id past))))
    (is (= "open" (state-of eng :invitation (:id later))))))

(deftest suggest-is-never-submitted-by-the-engine
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        inv-row (invite! eng c {:suggest {:title "Suggested title"}})]
    (drain! eng)
    (is (= "Dishes" (get-in (row-of eng :chore c) [:data :title]))
        "creating and hearing the invitation writes nothing to the row")
    (rename! eng c person "My own title")
    (drain! eng)
    (is (= "My own title" (get-in (row-of eng :chore c) [:data :title])))
    (is (= "answered" (state-of eng :invitation (:id inv-row))))))

(deftest an-invitation-to-a-door-the-row-cannot-take-now-is-refused
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")]
    (inv/invoke! eng :chore (str c) :finish {} {:principal person})
    (let [why (refusal #(invite! eng c {}))]
      (is (some? why) "a done chore offers no rename")
      (is (str/includes? (str why) "Done")
          "the refusal quotes the row's own unavailable reason"))
    (inv/invoke! eng :chore (str c) :reopen {} {:principal person})
    (is (nil? (refusal #(invite! eng c {}))) "reopened, it takes the door again")))

(deftest the-summary-names-the-subject
  (let [eng (fresh-engine)
        m (:row (inv/create! eng :member {:display "Colton Kopsa" :actor_type "human"}
                             {:principal invitations/engine-actor}))
        c (chore! eng "Dishes")
        row (invite! eng c {:subject (str (:id m))})
        line (summary/render (get-in (inv/resources eng) [:invitation :summary]) row)]
    (is (= "Colton Kopsa" (get-in row [:data :subject_name])))
    (is (str/includes? line "Colton Kopsa"))
    (is (not (str/includes? line (str (:id m)))) "never the raw id")))
