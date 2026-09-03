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
    between renewals); start-orphan-sweeper! is the loop, and the
    module's lifecycle hook carries `:elected :jobs-orphan-sweeper`
    so ONE process per database runs it (waymark-db9.4 — the
    election used to be wired in here, by name). The lease steal
    still resumes too — the sweep just makes the orphan VISIBLE as
    queued instead of leaving it wearing a dead worker's :running.

  Recorded deviations from waymark9's jobs.py, each a sentence:
  - Items run under a principal RECONSTRUCTED from the job's
    requested_by (id, type, display) — held roles are not carried, so
    a role-reading guard judges the reconstruction, not the original
    credential (waymark9 had the same property).
  - A deferred call skips whole-call idempotency (the job row is its
    record); each item still natural-replays like any invoke.

  MIRROR SYNC JOBS (the manual trigger): a job whose :action is one
  of sync-actions (\"resync\"/\"discover\") carries a KIND-level
  mirror pass, not a deferred bulk call — minted by the trigger door
  (waymark10.server.mirror/request-sync!) and serviced by the mirror
  discovery daemon, the lease-elected singleton that owns every sync
  pass and the adapter census. The bulk worker SKIPS them (run-once!
  — bulk-item! could not run one: there are no ids, and the sync
  doors are system-only). The orphan sweep does NOT skip them: a
  daemon that dies mid-pass leaves its job re-queued for the next
  lease holder, the same visibility the bulk jobs get."
  (:require [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.seams :as seams]
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

(def sync-actions
  "The job actions that carry a mirror sync request — a kind-level
  pass, not a per-item bulk call. The bulk worker never claims one;
  the mirror discovery daemon services them (see the ns docstring
  and waymark10.server.mirror/service-sync-jobs!)."
  #{"resync" "discover"})

(defn sync-job?
  "Is this job row (raw or decoded — :action is a plain string either
  way) a mirror sync request rather than a deferred bulk call? The
  action name alone would collide with an app's own bulk action
  spelled \"resync\" — a sync job also carries NO ids (a deferred
  bulk call always carries at least its threshold's worth), so the
  pair discriminates."
  [job]
  (and (contains? sync-actions (get-in job [:data :action]))
       (empty? (get-in job [:data :ids]))))

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

(declare enqueue!)

(def ^:private deferral-door
  "The door core's bulk grammar walks through when a call exceeds its
  declared :defer-over (waymark-db9.7). It stamps the rdef below, so
  the ONE thing core needs from this module rides the kind the module
  ENROLLED — the same enrollment that makes the 202's envelope
  renderable. Before this, router.clj required this namespace by name
  for a single call, which is what kept `waymark10.server.jobs` on
  the classpath of an engine assembled without the jobs module."
  (reify seams/Deferring
    (defer! [_ eng deferred principal] (enqueue! eng deferred principal))))

(def job
  (seams/with-deferral
   (r/resource
    {:kind :job
   :plural "jobs"
   :states [:queued :running :completed :cancelled]
   :initial :queued
   :terminal #{:completed :cancelled}
   :nav :system
   :summary "Deferred {data.action} on {data.kind} · {state}"
   ;; the job the principal asked for is the principal's to watch: the
   ;; sync trigger's 202 hands the requester a job, and the record of
   ;; who asked IS the sight — a leash that may mint a pass may watch
   ;; it run. requested_by rides as an OBJECT ({:id :type :display},
   ;; enqueue!'s pattern), so the branch is a PATH out of cond-sql's
   ;; top-level reach and its window filters in memory — the recorded
   ;; seam: the orphan sweep keeps the table small, and a deployment
   ;; that outgrows the window promotes the id to its own field
   :own-surface {:by [[:requested_by :id]]}
   :schema [:map
            [:action {:x-display
                      {:label "The action to run on each row"
                       :help "One action name from the target kind's own vocabulary — complete, retire, mark_stored. Every row in the batch takes the same door."}}
             [:string {:min 1 :max 64}]]
            ;; showcased: the kind filter stands above the jobs table
            ;; as a select of observed kinds (the facet counts ride
            ;; the options) — the queue is usually read one kind at a
            ;; time
            [:kind {:x-display
                    {:showcase true
                     :label "The kind whose rows these are"
                     :help "One resource kind's own name — a job never spans two."}}
             [:string {:min 1 :max 64}]]
            [:ids {:x-display
                   {:label "The rows to work through"
                    :help "The row ids, in the order the worker should take them; refusals are recorded per row and never stop the rest."}}
             [:vector [:string {:min 1}]]]
            ;; the per-item action input, wire-shaped, verbatim
            [:input {:optional true
                     :x-display
                     {:label "The input every row gets"}}
             :any]
            ;; the items shape deferred (waymark-pywy.4): each row's
            ;; own input and acknowledged guard names, index-aligned
            ;; with ids — absent for a one-input call
            [:inputs {:optional true
                      :x-display
                      {:label "Each row's own input"
                       :help "Index-aligned with the ids: the input each row gets when the call gave every row its own."}}
             [:maybe [:vector :any]]]
            [:acknowledged {:optional true
                            :x-display
                            {:label "Each row's acknowledged warnings"
                             :help "Index-aligned with the ids: the guard names each item acknowledged when it was queued."}}
             [:maybe [:vector [:vector :string]]]]
            [:requested_by {:x-display {:label "Who asked for it"}}
             :any]
            [:progress {:x-display
                        {:label "How far along"
                         :help "Written by the worker as it goes — done, total, and the rows that refused with their reasons."}}
             [:map
              [:done :int]
              [:total :int]
              [:refusals [:vector :any]]]]
            ;; the job artifact (batch F): the final per-item report,
            ;; persisted by the worker just before :complete fires
            [:report {:optional true
                      :x-display {:label "The finished report"}}
             :any]]
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
   deferral-door))

;; ── minting (the deferral door) ─────────────────────────────────────

(defn enqueue!
  "One deferred bulk call becomes one job row: created through the
  ordinary engine path (system actor — jobs are never wire-created),
  the requesting principal recorded in data. → the create! result.

  Reached from core through `deferral-door` above, never by name:
  the document shape and the worker actor are this module's, and a
  copy of either in the router would be exactly the drift this
  framework exists to make impossible."
  [eng {:keys [kind action ids input inputs acknowledged]} principal]
  (inv/create! eng :job
               (cond-> {:action (name action)
                        :kind (name kind)
                        :ids ids
                        :requested_by {:id (:id principal)
                                       :type (name (:type principal :human))
                                       :display (:display principal)}
                        :progress {:done 0 :total (count ids) :refusals []}}
                 input (assoc :input input)
                 inputs (assoc :inputs inputs)
                 acknowledged (assoc :acknowledged acknowledged))
               {:principal worker-actor}))

;; ── execution ───────────────────────────────────────────────────────

(defn load-job
  "The decoded job row by id, nil when gone. Public for the mirror
  daemon's sync-job service pass; the bulk worker's own callers."
  [eng id]
  (let [rdef (get (inv/resources eng) :job)
        raw (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :job id {})))]
    (when raw (inv/decode-row rdef raw))))

(defn- requesting-principal [job]
  (let [rb (get-in job [:data :requested_by])]
    (t/principal {:id (or (:id rb) "anonymous")
                  :type (keyword (or (:type rb) "human"))
                  :display (:display rb)})))

(defn persist-data!
  "The maintenance write: the job document updates in place — no
  version bump, no transition (the items log on their own rows).
  Public for the mirror daemon's report persist, the same artifact
  discipline the bulk worker follows."
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
            {:keys [action kind ids input inputs acknowledged progress]} (:data job)
            {:keys [done total]} progress
            target-kind (keyword kind)
            action-name (keyword action)
            plural (:plural (get (inv/resources eng) target-kind))
            principal (requesting-principal job)
            ;; the items shape (waymark-pywy.4): each row's own
            ;; input and acknowledged names ride index-aligned with
            ;; the ids; a one-input call has neither
            input-at (fn [i] (if inputs (not-empty (nth inputs i nil)) input))
            acknowledged-at (fn [i] (into #{} (map keyword)
                                          (when acknowledged (nth acknowledged i nil))))
            end (min total (+ done batch-size))
            batch (map vector (range done end) (subvec (vec ids) done end))]
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
                 (fn [refusals [i id]]
                   (try
                     (inv/bulk-item! eng target-kind action-name id (input-at i)
                                     {:principal principal
                                      :acknowledged (acknowledged-at i)
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
                                            {:state :running} {:limit 50}))))
        ;; sync jobs are the mirror daemon's — bulk-item! could not
        ;; run one (no ids, and the sync doors are system-only)
        running (remove sync-job? running)]
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
  "The orphan sweep's loop: every :interval-ms (default 30s),
  sweep-orphans! re-queues the running jobs no live worker claims.
  Returns the handle stop-orphan-sweeper! takes.

  ONE process per database should run this, and that is no longer
  decided here: the jobs module's lifecycle hook carries `:elected
  :jobs-orphan-sweeper` and the engine elects the holder through the
  storage (waymark10.modules, store/elect-role!). Election used to be
  baked into this function, which meant a plain start — the shape a
  test wants — did not exist."
  [eng {:keys [interval-ms] :or {interval-ms 30000}}]
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

(defn stop-orphan-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
