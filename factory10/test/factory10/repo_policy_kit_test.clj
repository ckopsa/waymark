(ns factory10.repo-policy-kit-test
  "factory10's repo_policy is composed through
  `factorykit.repo-policy/build` (ticket 5489263b), and the composition
  changed nothing: the fields, their order, the forms and the machine
  below are the ones the kind had when it was one literal map."
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.resources.repo-policy :refer [repo-policy]]
            [factorykit.repo-policy :as kit]))

(def ^:private stated
  [:repository :clone_url :branch_pattern :base :max_lines :lane_max_lines
   :opens_pr :auto_merge :merge_by :required_checks :merge_method
   :merge_wait_seconds :deploy_check :deploy_wait_seconds
   :rounds_per_change :queued_check_minutes :merge_strategy :train_size
   :groom_floor :groom_floor_settle_seconds :groom_floor_max_priority
   :formatter :deny :test :check :hosted_workflows :hosted_ok :person_paths
   :lanes :checks :orientation])

(def ^:private engines
  [:floor_noted_at :floor_count :floor_ticket :enrolled_at :note
   :source_note :forge_cursor :forge_retry :forge_pass_at :base_state
   :base_head :base_red_from :base_ticket :base_checked_at
   :deploy_waits_on :deploy_waiting_since :deployed_head :deployed_at
   :deploy_state :deploy_note :line_front :line_front_pr
   :line_front_waiting :line_waiting :line_at :line_train :lane_trains])

(deftest the-composed-policy-has-the-fields-it-had
  (testing "every field, in the order the literal map spelled them"
    (is (= (into stated engines) (mapv first (rest (:schema repo-policy))))))
  (testing "the create form and the restate door collect the stated fields"
    (is (= stated (mapv first (rest (:create-schema repo-policy)))))
    (is (= (:create-schema repo-policy) (get-in repo-policy [:actions :restate :input])))
    (is (= stated (get-in repo-policy [:actions :restate :edit :prefill]))))
  (testing "the base fields are the kit's own entries, and no offered one is taken"
    (is (every? (set (rest (:schema repo-policy))) (:fields kit/core)))
    (is (not-any? (set (map first (:offered kit/core)))
                  (map first (rest (:schema repo-policy)))))))

(deftest the-composed-policy-has-the-machine-it-had
  (is (= :active (:initial repo-policy)))
  (is (= #{:restate :retire :restore :mark_enrolled :note_source :note_base}
         (set (keys (:actions repo-policy)))))
  ;; `:undo` is not here: defresource reads it and the declaration
  ;; keeps it elsewhere. The kit's own test holds the core's undo pair.
  (is (= {:restate [#{:active} :active]
          :retire [#{:active} :retired]
          :restore [#{:retired} :active]
          :mark_enrolled [#{:active} :active]
          :note_source [#{:active} :active]
          :note_base [#{:active} :active]}
         (update-vals (:actions repo-policy) (juxt :from :to))))
  (testing "every door has its guards and its handler"
    (is (every? (comp seq :guards) (vals (:actions repo-policy))))
    (is (every? :handler (vals (:actions repo-policy)))))
  (testing "the builder's own keys are spent"
    (is (not-any? #(contains? repo-policy %) [:policy :policy-order :engine :takes]))))
