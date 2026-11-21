(ns factory10.onboarding-sit-answer-test
  "What the onboarding seat's sit answer tells a fresh agent (ticket
  c2b8885c): its plan's brief rides the walk row, plan_part's create
  door is named under the plan, each bench tool's arguments are one
  line, and the charter speaks in the doors' own words.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp.bench :as mcp-bench]))

(defn- seat
  "The template's seat, filled for greenleaf."
  []
  (let [t (edn/read-string (slurp (io/resource "seats/onboarding.edn")))]
    (edn/read-string (-> (pr-str (:seat t))
                         (str/replace "{{org}}" "greenleaf")
                         (str/replace "{{repo}}" "ckopsa/waymark")))))

(defn- engine []
  (dev/scratch! (vec (main/resources)) {}))

(deftest the-plans-brief-rides-its-walk-row
  (is (= [:brief] (mcp/sit-prose-fields (get (inv/resources (engine)) :org_plan)))))

(deftest plan-parts-create-door-is-named-under-the-plan
  (is (= ["plan_part"] (mcp/create-under-kinds (engine) {:data (seat)} "org_plan"))))

(deftest a-tools-input-line-names-its-arguments
  (is (= "path, old, new; optional: create"
         (mcp-bench/input-line {:type "object"
                                :properties {:path {} :old {} :new {} :create {}}
                                :required ["path" "old" "new"]})))
  (is (= "path; optional: limit, offset"
         (mcp-bench/input-line {"properties" {"path" {} "offset" {} "limit" {}}
                                "required" ["path"]})))
  (is (= "nothing required" (mcp-bench/input-line {})))
  (testing "an enum's values ride beside its name (ticket 09a46ba9)"
    (is (= "repo; optional: mode (tree|glob|grep|diff)"
           (mcp-bench/input-line {:properties {:repo {} :mode {:enum ["tree" "glob" "grep" "diff"]}}
                                  :required ["repo"]})))))

(deftest the-instructions-say-where-the-kit-is-read
  ;; ticket 09a46ba9: a house's orgs repository holds no kit
  (let [{:keys [instructions scope]} (seat)
        finds (some #(when (= "bench.find" (:kind %)) %) scope)]
    (is (str/includes? instructions "in ckopsa/waymark on the branch reference_note names"))
    (is (some #{"orgs"} (str/split (get-in finds [:filter :path]) #","))
        "a tree of orgs/ is the natural first look")))

(deftest the-charter-says-the-doors-own-words
  (let [{:keys [charter instructions]} (seat)]
    (testing "the door is submit, never push"
      (is (not (re-find #"(?i)push" charter)))
      (is (not (re-find #"(?i)push" instructions))))
    (testing "it names no file the seat may not read"
      (is (not (str/includes? charter "docs/orientation.md"))))
    (testing "it says how parts are added and who moves after propose"
      (doseq [w ["create_under" "leave them undecided" "in the conversation"
                 "next pass" "invoke go_live" "held for the person's tap"
                 "already has an org-mayor"]]
        (is (str/includes? charter w) w)))))
