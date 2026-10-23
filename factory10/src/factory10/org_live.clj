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
  `hired_seat`. `decline-refused-hires!` retires each simulated hire
  plan whose held go_live the owner refused or let expire.

  Both passes run at boot and on a tick (`start-passes!`, ticket
  59d1cfa0), so a refused hire retires within one tick of the refusal,
  not at the next deploy. Each is idempotent: a tick with nothing to do
  writes nothing."
  (:require [factory10.mirror :as mirror]
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
  fails is left going_live, and the next pass tries again."
  [eng]
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
                (if-some [seat (mayor-seat eng org mayor)]
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
    0))

(defn decline-refused-hires!
  "One pass: every simulated hire plan whose held go_live the owner
  refused, or let expire, retired through `decline_hire`, its gap quiet
  for a week. → how many it retired."
  [eng]
  (if-some [rd (get (inv/resources eng) :org_plan)]
    (let [ended (into {}
                      (for [s ["expired" "refused"]
                            hc (rows-of eng :held_call {:state s})
                            :let [door (get-in hc [:data :door])]
                            :when (and (= "org_plan" (some-> (:kind door) name))
                                       (= "go_live" (some-> (:action door) name)))]
                        [(str (:id door)) s]))]
      (reduce (fn [n raw]
                (let [plan (inv/decode-row rd raw)
                      how (get ended (str (:id plan)))]
                  (if (and how (= "hire" (org-plan/purpose-of plan)))
                    (try
                      (inv/invoke! eng :org_plan (str (:id plan)) :decline_hire
                                   {:why (if (= "refused" how)
                                           "The owner refused the hire."
                                           "The offer expired before the owner answered.")}
                                   {:principal mirror/source-principal})
                      (inc n)
                      (catch Exception e
                        (binding [*out* *err*]
                          (println (str "waymark10 org-live: a declined hire was not retired ("
                                        (ex-message e) "); the next pass tries again")))
                        n))
                    n)))
              0
              (if (seq ended) (rows-of eng :org_plan {:state "simulated"}) [])))
    0))

;; ── the tick ────────────────────────────────────────────────────────

(def default-every-seconds
  "Five minutes between ticks."
  300)

(defn pass!
  "One tick: `mark-live-plans!`, then `decline-refused-hires!`, each
  run whatever the other did. → {:marked n :declined n}."
  [eng]
  (let [run (fn [what f]
              (try (f eng)
                   (catch Throwable e
                     (binding [*out* *err*]
                       (println (str "waymark10 org-live: the " what " pass failed ("
                                     (or (ex-message e) (class e))
                                     "); the next tick still runs")))
                     0)))]
    {:marked (run "mark-live" mark-live-plans!)
     :declined (run "decline-hire" decline-refused-hires!)}))

(defn start-passes!
  "The tick's daemon: one `pass!` every `:every-seconds`, on the one
  clock loop (`clock/every!`). The first tick is one interval
  after the start: the boot ran both passes already. A tick that throws
  is warned and the next one still runs. The wiring owns the lifecycle
  and elects the one holder per database; a suite calls `pass!`
  directly."
  [eng {:keys [every-seconds] :or {every-seconds default-every-seconds}}]
  {:stop (clock/every! {:name "factory10-org-live"
                        :period-ms (long (* 1000 every-seconds))
                        :f #(pass! eng)})})

(defn stop-passes! [{:keys [stop]}]
  (when stop (stop))
  nil)
