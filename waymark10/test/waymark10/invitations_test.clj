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
  "The row a step acts on: one door, two plain arguments and one secret,
  a map argument that holds a map, and a secret map argument."
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
     [:room {:optional true :x-display {:label "Room"}}
      [:maybe [:string {:max 40}]]]
     [:pin {:optional true :x-display {:label "Lock code"}}
      [:maybe [:string {:max 12}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:room {:optional true :x-display {:label "Room"}}
                       [:maybe [:string {:max 40}]]]
                      [:pin {:optional true :x-secret true
                             :x-display {:label "Lock code"}}
                       [:maybe [:string {:max 12}]]]
                      [:showcase {:optional true :x-display {:label "Showcase"}}
                       [:maybe [:map
                                [:evidence {:optional true
                                            :x-display {:label "Evidence"}}
                                 [:maybe [:map
                                          [:film_url {:optional true
                                                      :x-display {:label "The film"}}
                                           [:maybe [:string {:max 500}]]]]]]]]]
                      [:vault {:optional true :x-secret true
                               :x-display {:label "Vault"}}
                       [:maybe [:map
                                [:code {:optional true
                                        :x-display {:label "Vault code"}}
                                 [:maybe [:string {:max 12}]]]]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room :pin])))
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

;; ticket 45cae4d6: a field may be a dotted path into a nested map
;; argument, as the form names that input.

(deftest a-dotted-field-walks-a-nested-map-argument
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")]
    (testing "a path that resolves"
      (let [row (invite! eng c {:field "showcase.evidence.film_url"})
            stored (row-of eng :invitation (:id row))]
        (is (= "open" (name (:state row))))
        (is (= ["showcase.evidence.film_url"] (get-in stored [:data :fields]))
            "the row stores the path as the author wrote it")))
    (testing "an inner step that names nothing"
      (let [why (refusal #(invite! eng c {:field "showcase.proof.film_url"}))]
        (is (some? why))
        (is (str/includes? (str why) "showcase.proof.film_url")
            "the refusal names the whole path")
        (is (str/includes? (str why) "not an argument")))
      (is (some? (refusal #(invite! eng c {:field "showcase.evidence.reel"})))
          "a last step the inner map does not hold")
      (is (some? (refusal #(invite! eng c {:field "title.film_url"})))
          "a step past an argument that is no map"))
    (testing "a path that crosses a secret argument"
      (let [why (refusal #(invite! eng c {:field "vault.code"}))]
        (is (some? why))
        (is (str/includes? (str why) "vault.code"))
        (is (str/includes? (str why) "secret")
            "the secret is the map the path walks through, not its leaf")))))

;; ticket f5844de1: the walk takes the map arm of an :or, as it takes
;; the one under a :maybe.

(deftest a-dotted-argument-through-an-or-with-a-map-arm-is-showable
  (let [rdef {:actions
              {:rename
               {:input [:map
                        [:title [:string {:min 1 :max 80}]]
                        [:showcase {:optional true}
                         [:or
                          [:string {:max 500}]
                          [:map
                           [:evidence {:optional true}
                            [:maybe [:or
                                     [:string {:max 500}]
                                     [:map
                                      [:film_url {:optional true}
                                       [:maybe [:string {:max 500}]]]]]]]]]]]}}}
        film "https://example.org/film"]
    (is (= {"showcase.evidence.film_url" film}
           (invitations/showable rdef "rename"
                                 {"showcase.evidence.film_url" film
                                  "showcase.evidence.reel" "x"
                                  "title.film_url" "x"}))
        "the path through the map arm stands, and a step that names nothing goes")))

;; docs/spec-walkthrough.md § 4: `fields`, with `field` the spelling for
;; a list of one. `invite!` names `field`, so a case naming `fields`
;; alone nulls it.

(deftest an-invitation-names-several-fields
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        row (invite! eng c {:field nil :fields ["room" "title"]})
        stored (row-of eng :invitation (:id row))]
    (is (= ["room" "title"] (get-in stored [:data :fields]))
        "the row stores the list in the author's reading order")
    (is (= "room" (get-in stored [:data :field]))
        "the engine stamps `field` with the first of `fields`")
    (testing "one to eight names"
      (is (some? (refusal #(invite! eng c {:field nil :fields []}))))
      (is (some? (refusal #(invite! eng c {:field nil
                                           :fields (vec (repeat 9 "title"))})))))
    (rename! eng c person "Dishes, then floors")
    (drain! eng)
    (is (= "answered" (state-of eng :invitation (:id row)))
        "the person's one transition answers it, however many fields it lit")))

(deftest field-alone-still-creates-and-reads-as-a-list-of-one
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        row (invite! eng c {})
        stored (row-of eng :invitation (:id row))]
    (is (= "title" (get-in stored [:data :field])))
    (is (= ["title"] (get-in stored [:data :fields])))))

(deftest field-and-fields-together-are-refused
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        why (refusal #(invite! eng c {:field "title" :fields ["title" "room"]}))]
    (is (some? why))
    (is (str/includes? (str why) "not both"))
    (testing "a body that names neither"
      (is (some? (refusal #(invite! eng c {:field nil})))))))

(deftest one-secret-among-the-fields-refuses-the-create
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        why (refusal #(invite! eng c {:field nil :fields ["title" "pin" "room"]}))]
    (is (some? why))
    (is (str/includes? (str why) "pin") "the refusal names the bad one")
    (is (some? (refusal #(invite! eng c {:field nil :fields ["title" "colour"]})))
        "a name the action does not take")
    (is (nil? (refusal #(invite! eng c {:field nil :fields ["title" "room"]}))))))

(deftest a-suggest-key-that-is-secret-or-no-argument-is-refused
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        why (refusal #(invite! eng c {:suggest {:title "Dishes, twice" :pin "1234"}}))]
    (is (some? why) "nobody is shown a value for a secret argument")
    (is (str/includes? (str why) "suggest"))
    (is (some? (refusal #(invite! eng c {:suggest {:colour "red"}})))
        "a key the action does not take")
    (is (nil? (refusal #(invite! eng c {:suggest {:title "Dishes, twice"
                                                  :room "Kitchen"}})))
        "a suggested argument need not be one of the lit fields")))

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

(deftest an-invitation-to-restate-a-seat-held-for-a-model-is-created
  (let [eng (fresh-engine)
        model (:row (inv/create! eng :model
                                 {:name "invited-frontier"
                                  :display "invited-frontier"
                                  :vendor "anthropic"
                                  :tier "frontier"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        scope [{:kind "model" :actions ["retire"]}]
        seat (:row (inv/create! eng :seat
                                {:name "invited-seat"
                                 :charter "Decide whether a message asks something of this house."
                                 :scope scope
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 60000
                                 :held_for [(:id model)]}
                                {:principal person}))
        why (refusal #(inv/create! eng :invitation
                                   {:subject "colton"
                                    :self (str "/api/seats/" (:id seat))
                                    :action "restate"
                                    :fields ["scope"]
                                    :suggest {:scope scope}
                                    :note "Restate the scope here."}
                                   {:principal planner
                                    :grant (grant-seeing (:id seat))}))]
    (is (= [(str (:id model))]
           (mapv str (get-in (row-of eng :seat (:id seat)) [:data :held_for])))
        "the seat is held for a model, so an empty restate would drop it")
    (is (nil? why)
        "a guard judging the restate's input is no reason: nobody has typed it yet")))

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

;; docs/spec-walkthrough.md § 3: what the invitation gains.

(deftest only-the-engine-names-a-walkthrough
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        the-engine invitations/engine-actor
        w (:id (:row (inv/create! eng :walkthrough
                                  {:subject "colton"
                                   :title "Tidying the chores"
                                   :steps [{:who "agent" :note "Watch me."}]}
                                  {:principal planner :grant (grant-seeing c)})))
        led {:walkthrough (str w) :step 1 :of 1}]
    (is (some? (refusal #(invite! eng c led)))
        "the author's own hand names no walkthrough")
    (is (some? (refusal #(invite! eng c {:step 1})))
        "nor a step alone")
    (let [row (:row (inv/create! eng :invitation
                                 (merge {:subject "colton"
                                         :self (str "/api/chores/" c)
                                         :action "rename"
                                         :field "title"
                                         :note "Pick the new title here."}
                                        led)
                                 {:principal the-engine}))
          alone (invite! eng c {})]
      (is (= "planner" (get-in row [:data :author]))
          "stamped with the walkthrough's author, not the engine's id")
      (is (= (str w) (str (get-in row [:data :walkthrough]))))
      (is (= [1 1] [(get-in row [:data :step]) (get-in row [:data :of])]))
      (is (some? (refusal #(inv/invoke! eng :invitation (str (:id alone)) :withdraw {}
                                        {:principal the-engine})))
          "the engine withdraws no invitation that stands alone")
      (inv/invoke! eng :invitation (str (:id row)) :withdraw {}
                   {:principal the-engine})
      (is (= "withdrawn" (state-of eng :invitation (:id row)))
          "the engine takes a walkthrough's step back"))))

(deftest a-transition-older-than-the-invitation-does-not-answer-it
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")]
    (rename! eng c person "Before anybody asked")
    (let [inv-row (invite! eng c {})]
      (drain! eng)
      (is (= "open" (state-of eng :invitation (:id inv-row)))
          "the rename was committed before the invitation was born")
      (rename! eng c person "After the invitation")
      (drain! eng)
      (is (= "answered" (state-of eng :invitation (:id inv-row)))))))
