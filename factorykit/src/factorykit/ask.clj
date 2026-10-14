(ns factorykit.ask
  "The ask's shared core, and the builder a factory composes its own
  ticket kind through (epic 459ed2eb, step 3a).

  A FACTORY'S ASK IS THE CORE AND ITS OWN EXTENSION. The core is what
  every factory's queue has: the seven states and no tomb, and the
  machine of the queue doors — groom, ungroom, return_to_draft,
  prioritize, block, unblock, defer, resume, shelve, drop and reopen.
  The extension is a defresource map of everything else. `build`
  answers the map `waymark10.dsl/defresource` takes.

  THE CORE DOORS CARRY THE MACHINE, and groom its input: a groom may
  say the priority the ask lands at, 0 to 3, and never 4, which is the
  backlog no seat builds. The guards, the handlers, the safety and the
  words are the factory's, as repo-policy's are, because who grooms
  and what a groom wakes are the factory's own.

  AN ASK THAT ENDS RELEASES WHAT IT BLOCKS (`release-the-waiters!`):
  a factory's handler on each ending door calls it."
  (:require [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

;; ── the base ────────────────────────────────────────────────────────

(def queue-max-priority
  "The last priority the queue takes. 4 is the backlog: no door lands
  an ask in the queue there (ticket f7420404)."
  3)

(def core
  "What every factory's ask has: the machine, and groom's input."
  {:states [:draft :open :in_review :blocked :deferred :done :dropped]
   :initial :draft
   ;; NO TOMB. Both endings come back through `reopen` to `draft`.
   :terminal #{}
   :actions
   {:groom
    {:from #{:draft} :to :open
     :input [:map
             [:priority {:optional true
                         :examples [2]
                         :x-display
                         {:label "Priority (0 first, 3 last)"
                          :help "The priority this ticket enters the queue at. Leave it empty to keep the one it has. Priority 4 is the backlog and never enters the queue: a ticket that stands at 4 is groomed with a priority of 0 to 3, or stays in draft."}}
              [:maybe [:int {:min 0 :max queue-max-priority}]]]]}
    :ungroom {:from #{:open} :to :draft}
    :prioritize {:from #{:open} :to :open}
    :block {:from #{:draft :open :blocked} :to :blocked}
    :unblock {:from #{:blocked} :to :open}
    :return_to_draft {:from #{:blocked} :to :draft}
    :defer {:from #{:open} :to :deferred}
    :resume {:from #{:deferred} :to :open}
    :shelve {:from #{:open :in_review} :to :draft}
    :drop {:from #{:draft :open} :to :dropped}
    :reopen {:from #{:done :dropped} :to :draft}}})

;; ── an ending releases its waiters ──────────────────────────────────

(def ^:private ended
  "The states of an ask that has ended."
  #{:done :dropped})

(defn- still-waits-on
  "The blockers `waiter` still waits on once `ending` ends: the ending
  ask leaves the list, and so does any other blocker that has ended or
  is gone — a list that named one would keep the ask blocked by
  nothing, and the `block` door refuses to restate it."
  [kind waiter ending read']
  (into []
        (remove (fn [id]
                  (or (= ending (str id))
                      (let [b (read' kind (str id))]
                        (or (nil? b)
                            (contains? ended (some-> (:state b) name keyword)))))))
        (get-in waiter [:data :blocked_by])))

(defn release-the-waiters!
  "THE LAST BLOCKER'S ENDING UNBLOCKS (the loop that needs no person).
  Every blocked ask of `kind` whose `blocked_by` names the ask that is
  ending is re-judged in the same transaction: with blockers left, the
  `block` door restates the shorter list; with none, the ask goes back
  where it was blocked from — `unblock` into the queue, the same
  transition a person's tap makes, so a seat's wake on `unblock` fires
  as it always has, or `return_to_draft` for a draft nobody groomed.

  BEST-EFFORT, as the change's merge is: a waiter that refuses is said
  in the log, and the ending stands. A probe or a rehearsal carries no
  pen, and releases nothing."
  [kind row ctx]
  (let [find' (:find ctx)
        read' (:read ctx)
        invoke' (:invoke ctx)
        ending (str (:id row))]
    (when (and find' read' invoke')
      (doseq [waiter (find' kind {:state "blocked"} {:limit 500})
              :when (some #(= ending (str %))
                          (get-in waiter [:data :blocked_by]))
              :let [left (still-waits-on kind waiter ending read')
                    ;; `block` is fenced (its :edit implies it), so the
                    ;; version read here is named as an honest client
                    ;; would name it (worksheet/apply-invocations!)
                    fence {:if-match (inv/etag kind (:id waiter)
                                               (:version waiter))}]]
        (try
          (cond
            (seq left)
            (invoke' kind (:id waiter) :block {:blocked_by left} fence)
            (= "draft" (get-in waiter [:data :blocked_from]))
            (invoke' kind (:id waiter) :return_to_draft nil fence)
            :else
            (invoke' kind (:id waiter) :unblock nil fence))
          (catch Exception e
            (binding [*out* *err*]
              (println "factorykit.ask ending: the" (name kind) (:id waiter)
                       "was not released -" (ex-message e)))))))))

;; ── the builder ─────────────────────────────────────────────────────

(def ^:private machine
  "The keys of the machine, which are the core's."
  [:states :initial :terminal])

(defn- refuse! [why data]
  (throw (ex-info (str "factorykit.ask/build: " why) data)))

(defn build
  "The defresource map of a factory's ask: `core` and the factory's own
  `extension`, which is a defresource map without the core's part.

  - `:states`, `:initial` and `:terminal` are the core's. An extension
    that names one is refused.
  - The doors are the core's `:actions` and the extension's. A core
    door is the core's part and the extension's door of that name over
    it: the guards, the handler, the safety and the words. An
    extension that names a part of a core door the core wrote — its
    machine, or groom's input — is refused.
  - Every other key is the extension's."
  [core extension]
  (when-some [named (seq (filter #(contains? extension %) machine))]
    (refuse! (str "the extension names " (pr-str (vec named))
                  ", and the machine is the core's.")
             {:named (vec named)}))
  (doseq [[door shape] (:actions core)
          :let [named (filter (set (keys shape))
                              (keys (get-in extension [:actions door])))]
          :when (seq named)]
    (refuse! (str "the extension's " (name door) " door names "
                  (pr-str (vec named))
                  ", and that part of a core door is the kit's to write.")
             {:door door :named (vec named)}))
  (-> extension
      (merge (select-keys core machine))
      (assoc :actions (merge-with merge (:actions core) (:actions extension)))))
