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

  Batch F restores waymark9's job lifecycle pieces:
  - :queued is back: a job is born :queued and the worker's claim
    STARTS it (queued → running, the hidden :start action) — a queued
    job is honest about nobody working it yet.
  - Job artifacts: the worker persists the final per-item report on
    the job row's data (:report — action, kind, totals, the refusal
    list with a refused/failed class per entry) before completing, so
    the completed envelope carries the whole outcome, not just the
    running progress.
  - The orphan sweep: sweep-orphans! re-queues :running jobs whose
    lease is absent or expired (no live claimant — the worker died
    between renewals); start-orphan-sweeper! elects ONE sweeper across
    processes via coherence/start-role! (the advisory-lock election),
    the same discipline as the webhook deliverer. The lease steal
    still resumes too — the sweep just makes the orphan VISIBLE as
    queued instead of leaving it wearing a dead worker's :running.

  Recorded deviations from waymark9's jobs.py, each a sentence:
  - Items run under a principal RECONSTRUCTED from the job's
    requested_by (id, type, display) — held roles are not carried, so
    a role-reading guard judges the reconstruction, not the original
    credential (waymark9 had the same property).
  - A deferred call skips whole-call idempotency (the job row is its
    record); each item still natural-replays like any invoke."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource]]
            [waymark10.schema :as schema]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

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
   :states [:queued :running :completed :cancelled]
   :initial :queued
   :terminal #{:completed :cancelled}
   :nav :system
   :summary "Deferred {data.action} on {data.kind} · {state}"
   :schema [:map
            [:action [:string {:min 1 :max 64}]]
            ;; showcased: the kind filter stands above the jobs table
            ;; as a select of observed kinds (the facet counts ride
            ;; the options) — the queue is usually read one kind at a
            ;; time
            [:kind {:x-display {:showcase true}} [:string {:min 1 :max 64}]]
            [:ids [:vector [:string {:min 1}]]]
            ;; the per-item action input, wire-shaped, verbatim
            [:input {:optional true} :any]
            [:requested_by :any]
            [:progress [:map
                        [:done :int]
                        [:total :int]
                        [:refusals [:vector :any]]]]
            ;; the job artifact (batch F): the final per-item report,
            ;; persisted by the worker just before :complete fires
            [:report {:optional true} :any]]
   :filterable {:state #{:eq :in}
                :kind #{:eq}}
   :faceted [:kind]
   :create-guards [worker-only]
   :actions
   {:start {:from #{:queued} :to :running
            :guards [worker-only-hidden]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "The claim made visible: a worker holds this job's lease."}
            :display {:label "Start" :order 9}}
    :cancel {:from #{:queued :running} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "Items not yet processed stay untouched; items already processed stay done."}
             :display {:label "Cancel job" :style :danger :order 9}}
    :requeue {:from #{:running} :to :queued
              :guards [worker-only-hidden]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "The orphan sweep's record: the claimant died, and the next claim starts the job again."}
              :display {:label "Re-queue" :order 9}}
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

(defn- persist-data!
  "The maintenance write: the job document updates in place — no
  version bump, no transition (the items log on their own rows)."
  [eng job data]
  (let [rdef (get (inv/resources eng) :job)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :job (:id job)
                            (schema/encode (:schema rdef) data) nil)))))

(defn- persist-progress! [eng job progress]
  (persist-data! eng job (assoc (:data job) :progress progress)))

(defn- report-of
  "The job artifact from the final progress: totals plus the refusal
  list, refused/failed told apart by the per-entry :class the worker
  stamped."
  [job]
  (let [{:keys [action kind progress]} (:data job)
        {:keys [total refusals]} progress
        classes (frequencies (map :class refusals))]
    {:action action
     :kind kind
     :total total
     :succeeded (- total (count refusals))
     :refused (get classes "refused" 0)
     :failed (get classes "failed" 0)
     :refusals refusals}))

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
  "One batch of a claimed job: reload the row (a cancel landed between
  batches stops here), start a queued job (queued → running — the
  claim made visible), run up to batch-size items through bulk-item!,
  persist progress, renew the lease. → the job's state after the
  batch (:running means more items remain), :gone when the row
  vanished, :lost when the lease was stolen mid-run. On the last
  batch the artifact (:report) persists before :complete fires."
  [eng job-id {:keys [batch-size holder lease-seconds]
               :or {batch-size 10 lease-seconds 60}}]
  (let [job (load-job eng job-id)]
    (cond
      (nil? job) :gone
      (contains? #{:completed :cancelled} (:state job)) (:state job)
      (and holder (not (claim! eng job-id holder lease-seconds))) :lost
      :else
      (let [job (if (= :queued (:state job))
                  (:row (inv/invoke! eng :job job-id :start nil
                                     {:principal worker-actor
                                      :correlation-id job-id}))
                  job)
            {:keys [action kind ids input progress]} (:data job)
            {:keys [done total]} progress
            target-kind (keyword kind)
            action-name (keyword action)
            plural (:plural (get (inv/resources eng) target-kind))
            principal (requesting-principal job)
            batch (subvec (vec ids) done (min total (+ done batch-size)))]
        (if (empty? batch)
          (do ;; the artifact: the final per-item report on the row's
              ;; data, in the same document the completed envelope reads
              (persist-data! eng job (assoc (:data job)
                                            :report (p/wire-value (report-of job))))
              (inv/invoke! eng :job job-id :complete nil
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
                                         :reason (inv/problem-reason e)
                                         :class "refused"})
                         (do (warn! "job " job-id " item " id " failed: "
                                    (ex-message e))
                             (conj refusals
                                   {:self (str "/api/" plural "/" id)
                                    :reason "Internal error while processing this item."
                                    :class "failed"}))))))
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
  "One worker pass: claim-or-steal every queued and running job and
  drive each to its end (a queued claim starts it; a running claim is
  the steal-on-expiry resume). → the number of jobs this pass
  advanced."
  [eng {:keys [holder lease-seconds] :or {lease-seconds 60} :as opts}]
  (let [holder (or holder (str "worker-" (random-uuid)))
        opts (assoc opts :holder holder :lease-seconds lease-seconds)
        running (store/with-tx (:storage eng)
                  (fn [tx]
                    (into (store/query-rows (:storage eng) tx :job
                                            {:state :queued} {:limit 50})
                          (store/query-rows (:storage eng) tx :job
                                            {:state :running} {:limit 50}))))]
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

;; ── the orphan sweep (batch F) ──────────────────────────────────────

(defn sweep-orphans!
  "Re-queue every :running job with no live claimant — its lease row
  absent or expired (the worker died between renewals). The re-queue
  is an ordinary logged transition (system actor), so the outage is
  in the audit trail; the stale lease row is dropped so the next
  claim is an insert, not a steal. → the number of jobs re-queued."
  [eng]
  (let [st (:storage eng)
        now ^Instant ((:now-fn eng))
        running (store/with-tx st
                  (fn [tx] (store/query-rows st tx :job
                                             {:state :running} {:limit 200})))]
    (reduce
     (fn [n job]
       (let [lease (store/with-tx st #(store/job-lease st % (:id job)))]
         (if (and lease (.isAfter ^Instant (:expires-at lease) now))
           n
           (do (when lease
                 (store/with-tx st
                   #(store/release-job-lease! st % (:id job) (:holder lease))))
               (try
                 (inv/invoke! eng :job (:id job) :requeue nil
                              {:principal worker-actor
                               :correlation-id (:id job)})
                 (inc n)
                 (catch Exception e
                   (warn! "orphan re-queue of job " (:id job) " failed: "
                          (ex-message e))
                   n))))))
     0
     running)))

(defn start-orphan-sweeper!
  "The orphan sweep as an ELECTED singleton (coherence/start-role!'s
  advisory-lock election, role :jobs-orphan-sweeper): one process per
  database sweeps every :interval-ms (default 30s); a crashed holder's
  lock session dies and a peer takes over within one retry interval.
  Returns the role handle; stop with coherence/stop-role!."
  [eng {:keys [interval-ms retry-ms] :or {interval-ms 30000}}]
  (coherence/start-role!
   (:storage eng) :jobs-orphan-sweeper
   {:retry-ms (or retry-ms 5000)
    :start-fn
    (fn []
      (let [stop (CountDownLatch. 1)
            t (Thread. ^Runnable
                       (fn []
                         (loop []
                           (when-not (.await stop (long interval-ms)
                                             TimeUnit/MILLISECONDS)
                             (try (sweep-orphans! eng)
                                  (catch Exception e
                                    (warn! "orphan sweep failed: "
                                           (ex-message e))))
                             (recur))))
                       "waymark10-jobs-orphans")]
        (doto ^Thread t (.setDaemon true) (.start))
        {:thread t :stop stop}))
    :stop-fn (fn [{:keys [^CountDownLatch stop]}]
               (some-> stop .countDown))}))
