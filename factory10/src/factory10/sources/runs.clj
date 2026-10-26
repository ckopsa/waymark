(ns factory10.sources.runs
  "A forge's workflow runs on one head, and which of them died without a
  verdict (ticket 22f91244). The forge pass re-runs such a run, and the
  `pull_request` kind reads its checks as pending instead of red
  (ticket 0fac9efc), so both call the functions here. This namespace
  requires none of the factory's own, so either may require it."
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(defprotocol ForgeRerun
  "A run that died without a verdict, asked to run again (ticket
  22f91244). A protocol of its own, so a source that does not implement
  it re-runs nothing and the pass goes on as before."
  (forge-runs [s repository head-sha]
    "→ [{:run_id :status :conclusion :jobs [{:name :conclusion :steps
    [{:name :conclusion} …]} …]} …], the latest run of each workflow on
    one head. `:jobs` is read only for a run that finished and did not
    pass. Throws when the forge does not answer.")
  (forge-rerun! [s repository run-id]
    "Re-run the failed jobs of one run. Throws when the forge refuses."))

(def interrupted-conclusions
  "The job conclusions that end a job without a verdict. A JOB is not a
  CHECK, so this set is not the kit's table's (ticket 4a7b5ebc): the
  table reads a timed out check as red, which is what sends the pass
  here, and here the question is whose fault the red run is. A job the
  forge timed out is a runner that froze, so it is a rerun. `stale` and
  `action_required` are a check's conclusions and no job's."
  #{"cancelled" "timed_out"})

(def ^:private setup-step
  "The steps that run before any test: the runner's own set-up, the
  checkout, the tool set-up, the containers and the services, and the
  clean-up after. A failure in one of these is the runner's, not the
  code's."
  #"(?i)\s*(set ?up|checkout|run actions/(checkout|setup-|cache)|initialize containers|create local container network|start(ing)? |pull |post |complete job|stop containers).*")

(defn- setup-step? [step]
  (boolean (re-matches setup-step (str (:name step)))))

(defn job-verdict
  "One job of a finished run: :red when a step past set-up failed,
  :interrupted when it was cancelled, timed out, or failed with no step
  past set-up failing, and nil when it passed."
  [job]
  (let [c (str (:conclusion job))]
    (cond
      (contains? interrupted-conclusions c) :interrupted
      (= "failure" c) (if (some #(and (= "failure" (str (:conclusion %)))
                                      (not (setup-step? %)))
                                (:steps job))
                        :red
                        :interrupted)
      :else nil)))

(defn- job-instant [job k]
  (try (Instant/parse (str (get job k))) (catch Exception _ nil)))

(defn aggregator?
  "A red job that only reports the others (the `gate` of tests.yml,
  ticket 2f8a03cd): it began after every interrupted job had ended, so
  its own red step read their result and ran no test. It is known by
  the run's own times and by no name. A red test job beside fail-fast
  siblings began before they were cancelled, so it is no aggregator;
  neither is a job whose times the forge did not say."
  [job interrupted]
  (let [began (job-instant job :started_at)
        ended (map #(job-instant % :completed_at) interrupted)]
    (boolean (and began
                  (seq ended)
                  (every? some? ended)
                  (not-any? #(.isAfter ^Instant % began) ended)))))

(defn interrupted-run?
  "A finished run with at least one interrupted job and no red one. A
  run with a failing test step is red, not interrupted — and so is a
  fail-fast matrix, whose siblings a red job cancelled. A red
  aggregator is not counted: it is red because the others died."
  [run]
  (let [jobs (vec (:jobs run))
        verdicts (mapv job-verdict jobs)
        of (fn [v] (keep-indexed #(when (= v (nth verdicts %1)) %2) jobs))
        interrupted (of :interrupted)]
    (boolean (and (= "completed" (str (:status run)))
                  (seq interrupted)
                  (every? #(aggregator? % interrupted) (of :red))))))

(defn interrupted-runs
  "The ids of the runs that red checks `names` died in, as strings, when
  every one of them is a job of an interrupted run and of no other run;
  else nil. A name no run's job carries (a rejected workflow, a commit
  status) is red, and so is the whole verdict."
  [names runs]
  (let [holding (fn [n] (filter (fn [run] (some #(= n (str (:name %))) (:jobs run)))
                                runs))
        held (map holding names)]
    (when (and (seq names)
               (every? #(and (seq %) (every? interrupted-run? %)) held))
      (vec (distinct (map #(str (:run_id %)) (apply concat held)))))))
