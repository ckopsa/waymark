(ns waymark10.server.jobs
  "Deferred jobs (phase 9b): the phase-7 :defer-over punt closes. A
  bulk call exceeding its declared threshold no longer refuses — the
  router mints a :job (an ordinary engine-served resource: progress in
  data, a cancel action, transitions in the same log as everything
  else) and answers 202 with the job envelope and its Location. A
  worker claims the job through waymark10_job_leases — claim-or-steal
  on expiry, so a died worker's job is picked up by the next one, the
  steal IS the resume — and executes the per-item invokes through
  inv/bulk-item!, the SAME code path a synchronous bulk item runs.

  Progress is a maintenance write (store/update-data!): batchwise, no
  version bump, no transition — one transition per item would drown
  the log twice (each item already logs on its own row). The logged
  job transitions are its create, its completion (system actor) and
  its cancel; cancel takes effect between batches — the worker reloads
  the row's state before every batch and stops when it is no longer
  running.

  Recorded deviations from waymark9's jobs.py, each a sentence:
  - queued is dropped: a v10 job is born :running (the worker's claim
    is the lease, not a state) and done is spelled :completed.
  - Items run under a principal RECONSTRUCTED from the job's
    requested_by (id, type, display) — held roles are not carried, so
    a role-reading guard judges the reconstruction, not the original
    credential (waymark9 had the same property).
  - Job artifacts (the per-dataset sub-status of service jobs, design
    E6) and the orphan sweep are unported — the lease steal already
    resumes a dead worker's job instead of cancelling it.
  - A deferred call skips whole-call idempotency (the job row is its
    record); each item still natural-replays like any invoke."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 jobs: " parts))))

(def worker-actor
  "The system actor that mints, advances and completes deferred jobs."
  (t/principal {:id "waymark10-jobs" :type :system :display "Job worker"}))

;; ── the resource ────────────────────────────────────────────────────

(defn- system? [ctx]
  (= :system (get-in ctx [:principal :type])))

(def ^:private worker-only
  (g/guard {:name :worker-writes-the-job
            :explain "Jobs are minted by a deferred bulk call and finished by the worker, never over the wire."
            :reads [:principal]
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(def ^:private worker-only-hidden
  (g/guard {:name :worker-writes-the-job
            :explain "Jobs are minted by a deferred bulk call and finished by the worker, never over the wire."
            :reads [:principal]
            :hide true
            :check (fn [_ _ ctx] (if (system? ctx) (t/allow) (t/deny)))}))

(defresource job
  {:kind :job
   :plural "jobs"
   :states [:running :completed :cancelled]
   :initial :running
   :terminal #{:completed :cancelled}
   :nav :secondary
   :summary "Deferred {data.action} on {data.kind} · {state}"
   :schema [:map
            [:action [:string {:min 1 :max 64}]]
            [:kind [:string {:min 1 :max 64}]]
            [:ids [:vector [:string {:min 1}]]]
            ;; the per-item action input, wire-shaped, verbatim
            [:input {:optional true} :any]
            [:requested_by :any]
            [:progress [:map
                        [:done :int]
                        [:total :int]
                        [:refusals [:vector :any]]]]]
   :filterable {:state #{:eq :in}
                :kind #{:eq}}
   :create-guards [worker-only]
   :actions
   {:cancel {:from #{:running} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "Items not yet processed stay untouched; items already processed stay done."}
             :display {:label "Cancel job" :style :danger :order 9}}
    :complete {:from #{:running} :to :completed
               :guards [worker-only-hidden]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "The worker's bookkeeping: the items are already done when this fires."}
               :display {:label "Complete" :order 9}}}})

;; ── minting (the router's defer seam) ───────────────────────────────

(defn enqueue!
  "One deferred bulk call becomes one job row: created through the
  ordinary engine path (system actor — jobs are never wire-created),
  the requesting principal recorded in data. → the create! result."
  [eng {:keys [kind action ids input]} principal]
  (inv/create! eng :job
               (cond-> {:action (name action)
                        :kind (name kind)
                        :ids ids
                        :requested_by {:id (:id principal)
                                       :type (name (:type principal :human))
                                       :display (:display principal)}
                        :progress {:done 0 :total (count ids) :refusals []}}
                 input (assoc :input input))
               {:principal worker-actor}))

;; ── execution ───────────────────────────────────────────────────────

(defn- load-job [eng id]
  (let [rdef (get (inv/resources eng) :job)
        raw (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :job id {})))]
    (when raw (inv/decode-row rdef raw))))

(defn- requesting-principal [job]
  (let [rb (get-in job [:data :requested_by])]
    (t/principal {:id (or (:id rb) "anonymous")
                  :type (keyword (or (:type rb) "human"))
                  :display (:display rb)})))

(defn- persist-progress!
  "The maintenance write: progress updates in place — no version bump,
  no transition (the items log on their own rows)."
  [eng job progress]
  (let [rdef (get (inv/resources eng) :job)
        data (assoc (:data job) :progress progress)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :job (:id job)
                            (schema/encode (:schema rdef) data) nil)))))

(defn claim!
  "Claim-or-steal the job's lease for lease-seconds; re-claiming our
  own lease extends it (the between-batches renewal)."
  [eng job-id holder lease-seconds]
  (store/with-tx (:storage eng)
    #(store/claim-job-lease! (:storage eng) % job-id holder lease-seconds)))

(defn release! [eng job-id holder]
  (store/with-tx (:storage eng)
    #(store/release-job-lease! (:storage eng) % job-id holder)))

(defn run-batch!
  "One batch of a claimed running job: reload the row (a cancel landed
  between batches stops here), run up to batch-size items through
  bulk-item!, persist progress, renew the lease. → the job's state
  after the batch (:running means more items remain), :gone when the
  row vanished, :lost when the lease was stolen mid-run."
  [eng job-id {:keys [batch-size holder lease-seconds]
               :or {batch-size 10 lease-seconds 60}}]
  (let [job (load-job eng job-id)]
    (cond
      (nil? job) :gone
      (not= :running (:state job)) (:state job)
      (and holder (not (claim! eng job-id holder lease-seconds))) :lost
      :else
      (let [{:keys [action kind ids input progress]} (:data job)
            {:keys [done total]} progress
            target-kind (keyword kind)
            action-name (keyword action)
            plural (:plural (get (inv/resources eng) target-kind))
            principal (requesting-principal job)
            batch (subvec (vec ids) done (min total (+ done batch-size)))]
        (if (empty? batch)
          (do (inv/invoke! eng :job job-id :complete nil
                           {:principal worker-actor
                            :correlation-id job-id})
              :completed)
          (let [refusals
                (reduce
                 (fn [refusals id]
                   (try
                     (inv/bulk-item! eng target-kind action-name id input
                                     {:principal principal
                                      :correlation-id job-id})
                     refusals
                     (catch Exception e
                       (if (inv/refusal? e)
                         (conj refusals {:self (str "/api/" plural "/" id)
                                         :reason (inv/problem-reason e)})
                         (do (warn! "job " job-id " item " id " failed: "
                                    (ex-message e))
                             (conj refusals
                                   {:self (str "/api/" plural "/" id)
                                    :reason "Internal error while processing this item."}))))))
                 []
                 batch)]
            (persist-progress! eng job
                               (-> progress
                                   (update :done + (count batch))
                                   (update :refusals into
                                           (mapv p/wire-value refusals))))
            :running))))))

(defn run-job!
  "Drive one claimed job to its end (or its cancel): batches until the
  state moves off :running."
  [eng job-id opts]
  (loop []
    (let [s (run-batch! eng job-id opts)]
      (if (= :running s) (recur) s))))

(defn run-once!
  "One worker pass: claim-or-steal every running job and drive each to
  its end. → the number of jobs this pass advanced."
  [eng {:keys [holder lease-seconds] :or {lease-seconds 60} :as opts}]
  (let [holder (or holder (str "worker-" (random-uuid)))
        opts (assoc opts :holder holder :lease-seconds lease-seconds)
        running (store/with-tx (:storage eng)
                  (fn [tx] (store/query-rows (:storage eng) tx :job
                                             {:state :running} {:limit 50})))]
    (reduce
     (fn [n job]
       (if (claim! eng (:id job) holder lease-seconds)
         (do (try
               (run-job! eng (:id job) opts)
               (catch Exception e
                 (warn! "job " (:id job) " aborted: " (ex-message e)))
               (finally (release! eng (:id job) holder)))
             (inc n))
         n))
     0
     running)))

;; ── the worker lifecycle (engine start!/stop!) ──────────────────────

(defn start-worker!
  "The jobs daemon: run-once! every poll-ms (default 1s) on a daemon
  thread with a stable holder identity. Engine start! owns the
  lifecycle; tests call run-once!/run-batch! directly."
  [eng {:keys [poll-ms] :or {poll-ms 1000} :as opts}]
  (let [stop (CountDownLatch. 1)
        holder (str "worker-" (random-uuid))
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long poll-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (run-once! eng (assoc opts :holder holder))
                              (catch Exception e
                                (warn! "worker pass failed: " (ex-message e))))
                         (recur))))
                   "waymark10-jobs")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop :holder holder}))

(defn stop-worker! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
