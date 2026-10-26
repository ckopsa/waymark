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
  a factory's handler on each ending door calls it.

  THE CHANGE LINK IS THE CORE'S (step 3b, ticket b220009b). The kit
  holds WHEN an ask follows its change — submitted, back to work or
  closed unmerged, merged, and the ask's own ending — and the factory's
  extension names WHICH door each moment walks, under `:link`
  (`link-defaults` for a factory that names none). The review and
  return doors' machine and their wall, `only-its-change-moves-it`,
  are the core's.

  THE LAW IS THE CORE'S (step 3c, ticket 2b85a37e): what an ask waits
  on, the exits no seat takes at an ask door, and how long an ask may
  rest in each working state (`law`), so a factory built on the kit
  reads real cracks from its first read. A factory's extension adds to
  it or overrides it under the same keys.

  AN ASK BACK IN THE QUEUE PUTS ITS STUCK CHANGE BACK TO WORK, or does
  not go (`back-to-work!`, ticket 4b2740db): a factory's handler on
  groom, unblock and resume calls it."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.types :as t]))

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

;; ── the change link (step 3b) ───────────────────────────────────────

(def link-defaults
  "The link of a factory that names none of its own: the change kind,
  the door each moment walks, the input the ending's close takes, and
  the words of the wall."
  {:change-kind :change
   :review-door :review
   :return-door :return
   :merged-door :complete
   :close-change-door :close_without_pr
   :close-change-input (fn [sentence] {:why sentence})
   ;; back to work (ticket 4b2740db): the doors a groom, unblock or
   ;; resume walks on a stuck change, and the ones a refusal names
   :rework-door :unstick
   :rework-submitted-door :unstick_submitted
   :unstick-door :unstick
   :unstick-submitted-door :unstick_submitted
   :rejoin-door :rejoin_review
   :words {:open "No door clears this one. An ask goes out for review when its change is submitted, and comes back to the queue when that change goes back to work or closes unmerged. A person who wants it sooner works the change."
           :explain "An ask under review is moved by the change it was built in and by no hand: the change's submit sends it out, its return sends it back, and its merge ends it."}})

(defn link
  "The change link of an ask of `ask-kind`: `link-defaults` with what
  the factory `named` over them."
  [ask-kind named]
  (merge link-defaults named {:ask-kind ask-kind}))

(defn walked-by-its-change-kind?
  "Is this door walked by a subscription the change kind `kind` itself
  declares (ticket 35c1e826)? The walk's principal carries the
  subscription's id (door_receivers' `walker`), and that row is
  `managed_by` \"kind\" with `managed_kind` `kind`. A person's hand, an
  agent's, and any subscription a person or an agent wrote, is not."
  [kind ctx]
  (boolean
   (when-some [sid (some-> (:subscription (:principal ctx)) str not-empty)]
     (when-some [read' (:read ctx)]
       (when-some [sub (read' :subscription sid)]
         (and (= "kind" (some-> (get-in sub [:data :managed_by]) name))
              (= (name kind) (some-> (get-in sub [:data :managed_kind]) name))))))))

(defn only-its-change-moves-it
  "The wall on the link's doors: the door opens inside a door of the
  link's change kind, or for a subscription that kind declares
  (`walked-by-its-change-kind?`), and for nobody's hand. The wire, the
  render probe and every rehearsal carry neither, so it renders
  refused, which is true. Its form names the change kind, so a
  factory's wall is the one it wrote by hand. The one row it reads is
  the subscription its principal names, which is the principal's own
  hand and no law of another kind, so it stays a check-tier wall: a
  scenario's ctx has no hook, and it refuses there."
  [link]
  (let [kind (:change-kind link)]
    (g/guard
     (merge {:name :only-its-change-moves-it :reads [:within :principal]}
            (:words link)
            {:check (with-meta
                      (fn [_row _inp ctx]
                        (if (or (= kind (:kind (:within ctx)))
                                (walked-by-its-change-kind? kind ctx))
                          (t/allow)
                          (t/deny)))
                      {:waymark10/form
                       (list 'fn '[_row _inp ctx]
                             (list 'if (list 'or
                                             (list '= kind '(:kind (:within ctx)))
                                             (list 'walked-by-its-change-kind? kind 'ctx))
                                   '(t/allow)
                                   '(t/deny)))})}))))

(defn- link-doors
  "The core's part of the link's doors, under the names the link gives."
  [link]
  (let [wall [(only-its-change-moves-it link)]]
    {(:review-door link) {:from #{:open} :to :in_review :guards wall}
     (:return-door link) {:from #{:in_review} :to :open :guards wall}}))

;; ── the law (step 3c) ───────────────────────────────────────────────

(def expected-age
  "HOW LONG AN ASK MAY REST, in seconds: open twelve hours, in review
  six, blocked seven days. The live cracks pass files an aged note for
  an ask past its state's age."
  {:open 43200 :in_review 21600 :blocked 604800})

(def unaged
  "The states the core gives no age: `draft` waits on a groom,
  `deferred` on its `defer_until`, and `done` and `dropped` are over."
  #{:draft :deferred :done :dropped})

(defn law
  "The core's law for an ask whose link is `link`.

  - `:waits`: a blocked ask waits on the asks its `blocked_by` names,
    and either ending releases it (`release-the-waiters!`).
  - `:left-by`: the exits no seat takes at an ask door, so the cracks
    audit does not report a working state as one nobody leaves. The
    change's submit walks the review door, the forge's mirror walks the
    merged door, and the last blocker's ending walks `unblock` or
    `return_to_draft`.
  - `:expected-age`: `expected-age`."
  [link]
  {:waits [{:on :blocked_by :awaits (:ask-kind link) :in #{:blocked}
            :outcomes {:done :release :dropped :release}}]
   :left-by [{:in #{:open} :by {:kind (:change-kind link) :action :submit}
              :via (:review-door link) :to :in_review}
             {:in #{:in_review} :by :mirror :via (:merged-door link) :to :done}
             {:in #{:blocked} :by :wait :via :unblock :to :open}
             {:in #{:blocked} :by :wait :via :return_to_draft :to :draft}]
   :expected-age expected-age})

(defn- entries-over
  "The core's `entries` with the factory's `own` over them: an own entry
  whose `k` a core entry has takes its place, and the rest follow."
  [k entries own]
  (let [own-by (into {} (map (juxt k identity)) own)
        cores (set (map k entries))]
    (into (mapv #(get own-by (k %) %) entries)
          (remove #(contains? cores (k %)) own))))

(defn- law-over
  "The core's `law` with the extension's over it: a wait by its field
  (`:on`), an exit by its door (`:via`), an age by its state."
  [law extension]
  {:waits (entries-over :on (:waits law) (:waits extension))
   :left-by (entries-over :via (:left-by law) (:left-by extension))
   :expected-age (merge (:expected-age law) (:expected-age extension))})

(defn its-changes
  "Every change born from `ask` that stands in one of `states`. The
  adoption writes the forge's id over `change_id`, so a change with a
  pull request is found by its repository and read by its `born_from`."
  [link ask states find']
  (let [kind (:change-kind link)
        born (str (name (:ask-kind link)) ":" (:id ask))
        repo (some-> (get-in ask [:data :repo]) str not-empty)]
    (vals (into {}
                (comp (filter #(= born (str (get-in % [:data :born_from]))))
                      (map (juxt :id identity)))
                (mapcat (fn [state]
                          (concat
                           (find' kind {:state state :change_id born}
                                  {:limit 50})
                           (when repo
                             (find' kind {:state state :repository repo}
                                    {:limit 200}))))
                        states)))))

(defn- its-ask
  "[id row] of the ask `change` was born from, or nil: a change born
  from another kind, or from nothing, has no ask here."
  [link change ctx]
  (let [born (str (get-in change [:data :born_from]))
        prefix (str (name (:ask-kind link)) ":")]
    (when (str/starts-with? born prefix)
      (when-some [id (not-empty (subs born (count prefix)))]
        (when-some [read' (:read ctx)]
          (when-some [ask (read' (:ask-kind link) id)]
            [id ask]))))))

(defn move-its-ask!
  "Walk `door` on the ask `change` was born from, when the ask stands in
  one of `from`.

  BEST-EFFORT, as the merge's completion is: a change born from no ask
  moves nothing, an ask that stands elsewhere is left where it is, and
  a door that refuses is said in the log — the change's own move
  stands. A rehearsal carries no pen, and moves nothing."
  [link change from door input ctx]
  (let [[id ask] (its-ask link change ctx)]
    (when (and ask
               (contains? from (some-> (:state ask) name keyword))
               (:invoke ctx))
      (try
        ((:invoke ctx) (:ask-kind link) id door input)
        (catch Exception e
          (binding [*out* *err*]
            (println "factorykit.ask link: the" (name (:ask-kind link)) id
                     "did not" (name door) "-" (ex-message e))))))))

(defn submitted!
  "The change is submitted: its ask, in the queue, goes out for review."
  [link change ctx]
  (move-its-ask! link change #{:open} (:review-door link) nil ctx))

(defn returned!
  "The change went back to work, or closed unmerged: its ask, under
  review, comes back to the queue."
  [link change ctx]
  (move-its-ask! link change #{:in_review} (:return-door link) nil ctx))

(def ^:private unended
  #{:draft :open :in_review :blocked :deferred})

(defn- refuse-the-move!
  "The ask's own `action` refused, because the change's door refused to
  put `change` back to work. The remedy is the change's `unstick`, on
  that change."
  [link ask action change unstick e]
  (let [door (keyword (name (:change-kind link)) (name unstick))]
    (throw (p/guard-refused
            action (:state ask)
            (str "Its " (name (:change-kind link)) " " (:id change)
                 " is stuck, and this " (name action)
                 " could not put it back to work: " (ex-message e))
            {:guard :its-change-stays-stuck
             :remedies [door]
             :resolved-remedies [{:door door :id (str (:id change))}]
             :open (str "Unstick the " (name (:change-kind link))
                        " first, or ask a person who may, then "
                        (name action) " again.")}
            {:kind (:ask-kind link) :id (str (:id ask))}))))

(defn back-to-work!
  "A GROOM, UNBLOCK OR RESUME PUTS THE ASK'S STUCK CHANGE BACK TO WORK
  (tickets 9ace68fb, 4363c63b, 4b2740db). The sit hands no ask whose
  change is stuck, so a move that left the change stuck would leave the
  ask idle in the queue. Every stuck change born from `ask` walks, in
  the same transaction, the link's `:rework-door` when it has no pull
  request and its `:rework-submitted-door` when it has one.

  NOT BEST-EFFORT: a change that refuses refuses the ask's own `action`,
  naming the change's unstick door (`:unstick-door`, or
  `:unstick-submitted-door` with a pull request) on that change. An ask
  never reaches the queue beside a change that is still stuck. A probe
  or a rehearsal carries no pen, and moves nothing.

  THE ASK FOLLOWS A PULL REQUEST BACK UNDER REVIEW (ticket 7e01dbe5):
  the move lands the ask in `open`, beside a change back under review,
  so one `:rejoin-door` is queued through ctx :follow-up and the engine
  walks it after this write commits. A follow-up that refuses is said in
  the log, and the ask stays open."
  [link ask action ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)
        follow' (:follow-up ctx)]
    (when (and find' invoke')
      (let [under-review?
            (reduce
             (fn [under change]
               (let [pr? (some? (get-in change [:data :number]))
                     [door unstick] (if pr?
                                      [(:rework-submitted-door link)
                                       (:unstick-submitted-door link)]
                                      [(:rework-door link) (:unstick-door link)])]
                 (try
                   (invoke' (:change-kind link) (:id change) door nil)
                   (catch Exception e
                     (refuse-the-move! link ask action change unstick e)))
                 (or under pr?)))
             false
             (its-changes link ask ["stuck"] find'))]
        (when (and follow' under-review?)
          (follow' {:kind (:ask-kind link) :id (:id ask)
                    :action (:rejoin-door link)}))))))

(defn merged!
  "The change merged: its ask, wherever it stands short of an ending,
  ends with `input`."
  [link change input ctx]
  (move-its-ask! link change unended (:merged-door link) input ctx))

(def ^:private unmerged
  "The states of a change its ask's ending closes. `submitted` is not
  one: its pull request is the forge's to end."
  ["open" "stuck" "failing"])

(defn close-its-changes!
  "THE ASK THAT ENDS TAKES ITS PR-LESS CHANGES WITH IT, in the same
  transaction: each change born from `ask`, open, stuck or failing with
  no pull request, walks the link's close door with `sentence`. A
  change `leave?` answers true for is left — the one whose merge IS
  this ending.

  BEST-EFFORT, as `release-the-waiters!` is: a change that refuses is
  said in the log, and the ending stands."
  [link ask sentence leave? ctx]
  (let [find' (:find ctx)
        invoke' (:invoke ctx)]
    (when (and find' invoke')
      (doseq [change (its-changes link ask unmerged find')
              :when (and (nil? (get-in change [:data :number]))
                         (not (leave? change)))]
        (try
          (invoke' (:change-kind link) (str (:id change))
                   (:close-change-door link)
                   ((:close-change-input link) sentence))
          (catch Exception e
            (binding [*out* *err*]
              (println "factorykit.ask ending: the change" (:id change)
                       "was not closed -" (ex-message e)))))))))

;; ── the builder ─────────────────────────────────────────────────────

(def ^:private machine
  "The keys of the machine, which are the core's."
  [:states :initial :terminal])

(defn- refuse! [why data]
  (throw (ex-info (str "factorykit.ask/build: " why) data)))

(declare ^:private build*)

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
  - The link's review and return doors are core doors too, under the
    names the extension's `:link` gives them (`link`): their machine
    and their wall are the kit's. `:link` itself leaves the map.
  - The law (`law`) is the core's, and the extension's `:waits`,
    `:left-by` and `:expected-age` over it (`law-over`).
  - Every other key is the extension's."
  [core extension]
  (let [link (link (:kind extension) (:link extension))
        core (update core :actions merge (link-doors link))
        extension (-> (dissoc extension :link)
                      (merge (law-over (law link) extension)))]
    (build* core extension)))

(defn- build* [core extension]
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
