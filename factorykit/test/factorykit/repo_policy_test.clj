(ns factorykit.repo-policy-test
  "The builder's rules (ticket 5489263b), on a small core and a small
  extension. That factory10's own repo_policy is the declaration it
  was is factory10's test, `factory10.repo-policy-kit-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [factorykit.repo-policy :as kit]
            [malli.core :as m]
            [malli.util :as mu]))

(def ^:private a-core
  {:states [:active :retired]
   :initial :active
   :terminal #{}
   :unique [[:repository]]
   :fields [[:repository [:string {:min 1}]]
            [:base {:default "main"} :string]]
   :offered [[:forge {:optional true} [:maybe :string]]]
   :actions {:restate {:from #{:active} :to :active}
             :retire {:from #{:active} :to :retired :undo :restore}}})

(def ^:private an-extension
  {:kind :repo_policy
   :plural "repo_policies"
   :policy [[:max_lines {:default 400} :int]]
   :engine [[:note {:optional true} [:maybe :string]]]
   :actions {:restate {:guards [:a-wall] :record true}
             :mark {:from #{:active} :to :active}}})

(deftest the-core-and-the-extension-are-one-declaration
  (let [built (kit/build a-core an-extension)
        stated [[:repository [:string {:min 1}]]
                [:base {:default "main"} :string]
                [:max_lines {:default 400} :int]]]
    (testing "the machine and the index are the core's"
      (is (= [:active :retired] (:states built)))
      (is (= :active (:initial built)))
      (is (= #{} (:terminal built)))
      (is (= [[:repository]] (:unique built))))
    (testing "a person states the core's fields, then the extension's"
      (is (= stated (kit/statement a-core an-extension)))
      (is (= (into [:map] stated) (:create-schema built))))
    (testing "the schema is the stated fields, then the engine's"
      (is (= (conj (into [:map] stated) [:note {:optional true} [:maybe :string]])
             (:schema built))))
    (testing "restate collects the whole statement, and opens on it"
      (is (= {:from #{:active} :to :active
              :guards [:a-wall] :record true
              :input (into [:map] stated)
              :edit {:prefill [:repository :base :max_lines]}}
             (get-in built [:actions :restate]))))
    (testing "a core door the extension is silent on is the machine alone"
      (is (= {:from #{:active} :to :retired :undo :restore}
             (get-in built [:actions :retire])))
      (is (= #{:restate :retire :mark} (set (keys (:actions built))))))
    (testing "every other key is the extension's, and the builder's own are spent"
      (is (= :repo_policy (:kind built)))
      (is (= "repo_policies" (:plural built)))
      (is (not-any? #(contains? built %)
                    [:fields :offered :takes :policy :policy-order :engine])))))

(deftest an-offered-field-is-taken-by-name
  (testing "a silent extension has none"
    (is (= [:repository :base :max_lines]
           (mapv first (kit/statement a-core an-extension)))))
  (testing "a taken one follows the core's fields"
    (is (= [:repository :base :forge :max_lines]
           (mapv first (kit/statement a-core (assoc an-extension :takes [:forge]))))))
  (testing "the core offers what it offers"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"offers no such field"
                          (kit/build a-core (assoc an-extension :takes [:colour]))))))

(deftest the-extension-says-another-field-order
  (let [built (kit/build a-core (assoc an-extension :policy-order [:max_lines :repository]))]
    (is (= [:max_lines :repository :base :note] (mapv first (rest (:schema built)))))
    (is (= [:max_lines :repository :base] (get-in built [:actions :restate :edit :prefill]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such stated field"
                        (kit/build a-core (assoc an-extension :policy-order [:note])))))

(deftest the-extension-adds-to-the-index-and-states-its-own-law
  (is (= [[:repository] [:base]]
         (:unique (kit/build a-core (assoc an-extension :unique [[:base] [:repository]])))))
  (is (not (contains? (kit/build a-core an-extension) :expected-age)))
  (is (= {:active 60}
         (:expected-age (kit/build a-core (assoc an-extension :expected-age {:active 60}))))))

(deftest what-is-the-kits-is-refused-an-extension
  (testing "the machine and the schemas"
    (doseq [k [:states :initial :terminal :schema :create-schema]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the extension names"
                            (kit/build a-core (assoc an-extension k [])))
          (str k))))
  (testing "a field of the core's, taken or not, and a stated field as an engine's"
    (doseq [extension [(assoc an-extension :policy [[:base :string]])
                       (assoc an-extension :policy [[:forge :string]])
                       (assoc an-extension :engine [[:max_lines :int]])]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"redefines"
                            (kit/build a-core extension)))))
  (testing "a core door's machine, and restate's input and prefill"
    (doseq [door [{:retire {:to :active}}
                  {:restate {:input [:map]}}
                  {:restate {:edit {:prefill []}}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the kit's to write"
                            (kit/build a-core (assoc an-extension :actions door)))))))

(deftest the-real-core-is-the-base-every-factory-shares
  (let [built (kit/build kit/core {:kind :repo_policy})]
    (is (= [:repository :branch_pattern :base :required_checks :rounds_per_change]
           (mapv first (rest (:schema built)))))
    (is (= [:forge :workspace :forge_repo :advisory_checks]
           (mapv first (:offered kit/core))))
    (is (= #{:restate :retire :restore :note_source} (set (keys (:actions built)))))
    (is (= :a-person-or-their-delegate-states-the-policy
           (:name kit/a-person-or-their-delegate-states-the-policy)))))

(deftest the-branch-pattern-has-exactly-one-star
  (let [create (:create-schema (kit/build kit/core {:kind :repo_policy}))
        policy {:repository "ckopsa/waymark" :base "main" :rounds_per_change 3}]
    (doseq [p ["waymark/*" "bench/*" "*" "pre-*-post"]]
      (is (m/validate create (assoc policy :branch_pattern p)) p))
    (doseq [p ["waymark" "a/*/*" "a b/*" "" (str (apply str (repeat 120 "a")) "*")]]
      (is (not (m/validate create (assoc policy :branch_pattern p))) p))))

;; ── a policy shaped like colton-tools' (ticket 39b0b3f7) ─────────────

(def ^:private colton-tools-shaped
  {:kind :repo_policy
   :takes [[:forge {:required true
                    :schema [:enum "bitbucket" "github"]
                    :x-display {:choices [{:value "bitbucket" :label "Bitbucket Cloud"}
                                          {:value "github" :label "GitHub (not polled; its landings open the pull request)"}]}}]
           [:workspace {:required true :schema [:string {:min 1 :max 80}]}]
           [:forge_repo {:required true :schema [:string {:min 1 :max 120}]}]]
   :fixed [:forge :workspace :forge_repo]
   :policy [[:opens_pr {:default true} :boolean]
            [:orientation {:optional true} [:maybe [:string {:max 200}]]]]
   :engine [[:source_note {:optional true} [:maybe :string]]
            [:source_noted_at {:optional true} [:maybe :string]]]})

(def ^:private a-statement
  {:repository "nav" :branch_pattern "bench/*" :base "main"
   :required_checks [] :rounds_per_change 3
   :forge "bitbucket" :workspace "colton" :forge_repo "nav"
   :opens_pr true})

(deftest a-factory-tightens-the-offered-fields-it-takes
  (let [built (kit/build kit/core colton-tools-shaped)
        create (:create-schema built)
        restate (get-in built [:actions :restate])]
    (testing "create takes the statement, and refuses a missing forge or one outside the enum"
      (is (m/validate create a-statement))
      (is (not (m/validate create (dissoc a-statement :forge))))
      (is (not (m/validate create (assoc a-statement :forge "gitlab"))))
      (is (not (m/validate create (dissoc a-statement :workspace))))
      (is (not (m/validate create (assoc a-statement :forge_repo "")))))
    (testing "the factory's words merge over the offered ones"
      (let [[_ props] (first (filter #(= :forge (first %)) (rest create)))]
        (is (not (contains? props :optional)))
        (is (= "The forge" (get-in props [:x-display :label])))
        (is (= 2 (count (get-in props [:x-display :choices]))))))
    (testing "restate does not take a fixed field"
      (is (= [:repository :branch_pattern :base :required_checks :rounds_per_change
              :opens_pr :orientation]
             (get-in restate [:edit :prefill])
             (mapv first (rest (:input restate)))))
      (is (m/validate (mu/closed-schema (:input restate))
                      (apply dissoc a-statement (:fixed colton-tools-shaped))))
      (is (not (m/validate (mu/closed-schema (:input restate)) a-statement))))
    (testing "the schema still holds the fixed fields, and :fixed is spent"
      (is (= [:repository :branch_pattern :base :required_checks :rounds_per_change
              :forge :workspace :forge_repo :opens_pr :orientation
              :source_note :source_noted_at]
             (mapv first (rest (:schema built)))))
      (is (not (contains? built :fixed))))))

(deftest a-tightening-that-widens-is-refused
  (testing "a longer max, another type, an enum value the offer refuses, a looser child"
    (doseq [entry [[:workspace {:schema [:string {:max 200}]}]
                   [:forge {:schema :int}]
                   [:forge {:schema [:enum "github" (apply str (repeat 61 "x"))]}]
                   [:advisory_checks {:schema [:vector :string]}]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"widens"
                            (kit/build kit/core {:kind :repo_policy :takes [entry]}))
          (pr-str entry))))
  (testing "an option the kit does not know"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes :required, :schema and :x-display"
                          (kit/build kit/core {:kind :repo_policy
                                               :takes [[:forge {:default "github"}]]}))))
  (testing "required alone drops :optional and the :maybe"
    (is (= [:forge {} :string]
           (last (kit/statement a-core {:takes [[:forge {:required true}]]}))))))

(deftest a-fixed-field-is-a-stated-one
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such stated field"
                        (kit/build a-core (assoc an-extension :fixed [:note])))))
