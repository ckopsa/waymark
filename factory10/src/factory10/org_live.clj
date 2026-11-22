(ns factory10.org-live
  "A going_live org_plan reads live once the house serves its org
  (ticket 25c1520b, M2e-4 of epic cafa6106). The merge put the org's
  directory on main, the deploy's boot loaded it (`waymark10.server.org`),
  and this pass, run after that boot as the engine's own hand, walks
  each going_live plan whose org is now served: a domain named the
  plan's `name`, and an active seat in that domain named the plan's
  `mayor`. It takes the plan's `mark_live` with that seat's id, which
  the plan keeps as `mayor_seat`.

  Until then it does nothing: going_live's expected age makes a stuck
  deploy a crack for the mayor. A plan already live is not going_live,
  so a second pass changes nothing.

  A HIRE PLAN (ticket 7116068f, M4b) goes live otherwise: once its
  pull request merged (a plan that names no change is taken as merged),
  the pass opens its `hire_seat` in the org's domain as the plan's
  owner, unless that seat sits there already, and marks it live with
  `hired_seat`. A hire plan whose held go_live the owner refused or let
  expire is retired by org_plan's subscription on held_call, not here
  (ticket 829427d1).

  A CHANGE PLAN's org is live already, so its mayor sits before the
  change is served (ticket d565d21d): it is marked live only once its
  pull request merged and no repository's policy still waits on that
  merge's deploy (`change-served?`), and the engine running the pass
  booted from a commit that holds that merge (`boot-holds?`, ticket
  3032c0a8): the image stamps its commit, so a merge outside the house
  merge pass, which writes no deploy wait, goes live only once a boot
  holds it.

  The pass runs at boot and on a tick (`start-passes!`, ticket
  59d1cfa0), so a plan reads live within one tick of its deploy. It is
  idempotent: a tick with nothing to do writes nothing.

  The same tick is plan_part's migration of `org` (ticket f9252928):
  a part stored before it takes its plan's name (`stamp-part-orgs!`)."
  (:require [clojure.string :as str]
            [factory10.mirror :as mirror]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.server.clock :as clock]
            [waymark10.server.domains :as domains]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(defn- rows-of [eng kind where]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 1000})))
    []))

(defn mayor-seat
  "The id of the active seat named `mayor` in the domain named `org`,
  or nil while the house serves no such domain or no such seat."
  [eng org mayor]
  (when (and (seq org) (seq mayor))
    (when-some [domain (domains/domain-named eng org)]
      (some->> (rows-of eng :seat {:name mayor})
               (filter #(and (= "active" (name (:state %)))
                             (= (str (:id domain))
                                (some-> (get-in % [:data :domain]) str))))
               first :id str))))

(defn- merged?
  "Did the plan's pull request merge? A plan that names no change is
  taken as merged."
  [eng plan]
  (if-some [cid (some-> (get-in plan [:data :change]) str not-empty)]
    (= "merged" (some-> (store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx :change cid {})))
                        :state name))
    true))

(defn- deploy-waits-on?
  "Does an active repo_policy still wait on the deploy of the change
  `cid`? The merge pass writes a house merge on `deploy_waits_on`, as
  \"change ticket number\", before its change reads merged, and the
  forge pass takes it off once the deploy check is green on a head that
  holds the merge (`factory10.bench/note-deploy!`)."
  [eng cid]
  (boolean
   (some (fn [policy]
           (some #(= cid (first (str/split (str %) #" ")))
                 (get-in policy [:data :deploy_waits_on])))
         (rows-of eng :repo_policy {:state "active"}))))

(defn boot
  "The commit this engine booted from and the repository it is a commit
  of, as the image stamps them (`WAYMARK10_COMMIT`,
  `WAYMARK10_REPOSITORY`, ticket 3032c0a8), or nil when it stamped
  none: a laptop's engine, a suite's."
  []
  (let [commit (some-> (System/getenv "WAYMARK10_COMMIT") str/trim not-empty)
        repo (some-> (System/getenv "WAYMARK10_REPOSITORY") str/trim not-empty)]
    (when (and commit repo) {:commit commit :repository repo})))

(defn boot-holds?
  "Does the engine's `boot` ({:commit :repository}) hold the merge of
  `change`, a change row? (ticket 3032c0a8) `covers?` is (fn [repository
  number sha]) → whether that pull request's merge is `sha` or an
  ancestor of it. There is nothing to judge, and it is true, when the
  boot is unknown, when the change is of another repository or names no
  pull request, or when there is no forge to ask. A forge that does not
  answer is false: the next pass asks again."
  [boot covers? change]
  (let [number (get-in change [:data :number])
        repo (some-> (get-in change [:data :repository]) str not-empty)]
    (if (and (:commit boot) covers? number (= repo (:repository boot)))
      (try (boolean (covers? repo number (:commit boot)))
           (catch Exception e
             (binding [*out* *err*]
               (println (str "waymark10 org-live: " repo "#" number
                             " was not judged against the boot commit ("
                             (ex-message e) "); the next pass asks again")))
             false))
      true)))

(defn- change-served?
  "Is a change plan's change served? Its pull request merged
  (`merged?`), the deploy that holds the merge is no longer waited on
  (`deploy-waits-on?`), and this engine booted from a commit that holds
  it (`boot-holds?`)."
  [eng plan {:keys [boot covers?]}]
  (let [cid (some-> (get-in plan [:data :change]) str not-empty)]
    (and (merged? eng plan)
         (not (some->> cid (deploy-waits-on? eng)))
         (or (nil? cid)
             (boot-holds? boot covers?
                          (store/with-tx (:storage eng)
                            (fn [tx] (store/load-row (:storage eng) tx :change cid {}))))))))

(defn- hire-seat!
  "The seat a merged hire plan opens: the active seat named its
  `hire_seat` in the org's domain, opened there as the plan's owner when
  none sits. → its id, or nil while the plan waits."
  [eng plan org]
  (let [hs (get-in plan [:data :hire_seat])
        owner (some-> (get-in plan [:data :owner]) str not-empty)
        domain (when (seq org) (domains/domain-named eng org))]
    (when (and (map? hs) owner domain (merged? eng plan))
      (or (mayor-seat eng org (some-> (:name hs) str))
          (some-> (inv/create! eng :seat
                               (-> (into {} (remove (comp nil? val)) hs)
                                   (update :budget_usd_per_week bigdec)
                                   (assoc :domain (str (:id domain))))
                               {:principal (t/principal {:id owner :type :human :display owner})})
                  :row :id str)))))

(defn mark-live-plans!
  "One pass: every going_live org_plan whose org the house now serves,
  with its mayor's seat, marked live through `mark_live`; a hire plan
  with the seat it hires. → how many it marked. A plan whose step
  fails is left going_live, and the next pass tries again. `opts`
  carries `change-served?`'s :boot (`boot` unless given) and :covers?."
  ([eng] (mark-live-plans! eng {}))
  ([eng opts]
  (let [opts (merge {:boot (boot)} opts)]
  (if-some [rd (get (inv/resources eng) :org_plan)]
    (reduce (fn [n raw]
              (let [plan (inv/decode-row rd raw)
                    org (some-> (get-in plan [:data :name]) str not-empty)
                    mayor (some-> (get-in plan [:data :mayor :name]) str not-empty)]
                (if (= "hire" (org-plan/purpose-of plan))
                  (try
                    (if-some [seat (hire-seat! eng plan org)]
                      (do (inv/invoke! eng :org_plan (str (:id plan)) :mark_live
                                       {:hired_seat seat}
                                       {:principal mirror/source-principal})
                          (inc n))
                      n)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "waymark10 org-live: the hire for " org " did not go live ("
                                      (ex-message e) "); the next pass tries again")))
                      n))
                (if-some [seat (when (or (not= "change" (org-plan/purpose-of plan))
                                         (change-served? eng plan opts))
                                 (mayor-seat eng org mayor))]
                  (try
                    (inv/invoke! eng :org_plan (str (:id plan)) :mark_live
                                 {:mayor_seat seat}
                                 {:principal mirror/source-principal})
                    (inc n)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "waymark10 org-live: " org " did not go live ("
                                      (ex-message e) "); the next pass tries again")))
                      n))
                  n))))
            0
            (rows-of eng :org_plan {:state "going_live"}))
    0))))

(defn stamp-part-orgs!
  "One pass of plan_part's migration (ticket f9252928): each part stored
  without an `org` takes its plan's name through the engine's
  `stamp_org`. A part born since is stamped at birth, so once the stored
  ones are done the pass writes nothing. → how many it stamped."
  [eng]
  (if-some [rd (get (inv/resources eng) :plan_part)]
    (let [org-of (memoize
                  (fn [pid]
                    (some-> (store/with-tx (:storage eng)
                              (fn [tx] (store/load-row (:storage eng) tx :org_plan pid {})))
                            (get-in [:data :name]) str not-empty)))]
      (reduce (fn [n raw]
                (let [part (inv/decode-row rd raw)
                      org (some-> (get-in part [:data :plan]) str not-empty org-of)]
                  (if (or (nil? org) (some? (get-in part [:data :org])))
                    n
                    (try
                      (inv/invoke! eng :plan_part (str (:id part)) :stamp_org {:org org}
                                   {:principal mirror/source-principal})
                      (inc n)
                      (catch Exception e
                        (binding [*out* *err*]
                          (println (str "waymark10 org-live: part " (:id part) " took no org ("
                                        (ex-message e) "); the next pass tries again")))
                        n)))))
              0
              (rows-of eng :plan_part {})))
    0))

;; ── the tick ────────────────────────────────────────────────────────

(def default-every-seconds
  "Five minutes between ticks."
  300)

(defn pass!
  "One tick: `mark-live-plans!` (with `opts`), then `stamp-part-orgs!`.
  → {:marked n}, and :stamped when a part took its org."
  ([eng] (pass! eng {}))
  ([eng opts]
  (let [run (fn [what f]
              (try (f eng)
                   (catch Throwable e
                     (binding [*out* *err*]
                       (println (str "waymark10 org-live: the " what " pass failed ("
                                     (or (ex-message e) (class e))
                                     "); the next tick still runs")))
                     0)))]
    (let [marked (run "mark-live" #(mark-live-plans! % opts))
          stamped (run "part-org" stamp-part-orgs!)]
      (cond-> {:marked marked}
        (pos? stamped) (assoc :stamped stamped))))))

(defn start-passes!
  "The tick's daemon: one `pass!` every `:every-seconds`, on the one
  clock loop (`clock/every!`). The first tick is one interval
  after the start: the boot ran the pass already. A tick that throws
  is warned and the next one still runs. The wiring owns the lifecycle
  and elects the one holder per database; a suite calls `pass!`
  directly. :boot and :covers? ride to `mark-live-plans!`."
  [eng {:keys [every-seconds] :or {every-seconds default-every-seconds} :as opts}]
  {:stop (clock/every! {:name "factory10-org-live"
                        :period-ms (long (* 1000 every-seconds))
                        :f #(pass! eng (select-keys opts [:boot :covers?]))})})

(defn stop-passes! [{:keys [stop]}]
  (when stop (stop))
  nil)
