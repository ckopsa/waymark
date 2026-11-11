(ns waymark10.server.seats.holds
  "The rows a seat's sittings hold, carved out of
  `waymark10.server.seats` (ticket 806dbaec, round two): the leases an
  open sitting holds and the grace a closed one keeps, the rows an open
  sitting of another seat holds, the tickets a walk leaves out because
  their change is stuck or submitted or they are a parent, the row a
  fire's text names, the claim that leases a sit's rows, and the counts
  and stamps a wake reads off a seat's open sittings. The leaf helpers
  these defns read moved down with them: the two page sizes,
  `leased-row-ids`, `lease-lapsed?` and `halt-mark-of`.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.machine :as machine]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.leases :as leases]
            [waymark10.server.seats.fire :refer [seats-actor named-row]]
            [waymark10.server.seats.inbox :refer [->instant now-of lease-live?
                                                  lease-seconds]]
            [waymark10.server.seats.wakes :refer [walk-filter]]
            [waymark10.server.store :as store])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def open-sitting-page
  "The most open sittings one seat is read for at a close. A seat
  holds one open sitting in the ordinary case and a handful when runs
  overlap; anything past this is a seat whose sweep is overdue, and
  the honest fix is the sweep, not a longer page."
  50)

(def lease-record-page
  "The most lease rows one read of what sittings were handed takes."
  500)

(defn leased-row-ids
  "The subject ids of the sittings' leases `where` names, held or ended,
  distinct: what those sittings were handed. `find'` is (kind where
  opts) → rows."
  [find' where]
  (into [] (comp (keep #(some-> (get-in % [:data :subject_id]) str not-empty))
                 (distinct))
        (find' :lease (assoc where :holder_kind "sitting")
               {:limit lease-record-page})))

(defn lease-lapsed?
  "Is this sitting still `open` with its `expires_at` strictly past at
  `now` (docs/spec-leases.md §3)? It holds nothing, and is expired."
  [row now]
  (boolean
   (and (= "open" (some-> (:state row) name))
        (let [exp (->instant (get-in row [:data :expires_at]))
              at (->instant now)]
          (and exp at (.isBefore ^java.time.Instant exp at))))))

(defn- halt-mark-of
  "The sit's `halted` block, string-keyed as it answers it, in
  `halt-mark`'s spelling. → a map, or nil."
  [halted]
  (when (seq halted)
    (into {} (keep (fn [[k v]] (when (some? v) [(keyword k) v]))) halted)))

(def release-grace-default
  "The grace, in seconds, a seat that names no `release_grace_seconds`
  gives the rows of a sitting that closed."
  120)

(defn- walked-rdef
  "The rdef of the kind this seat walks, or nil."
  [eng seat-row]
  (some->> (get-in seat-row [:data :walk]) str not-empty keyword
           (get (inv/resources eng))))

;; ── the leases a sitting holds (docs/spec-leases.md §1, §3) ─────────
;; The claim acquires one lease per walk row it hands, held by the
;; sitting, and its ending releases them: what a sitting holds is read
;; from its leases alone. A sitting of a seat that walks no kind has no
;; subject kind to lease, and holds nothing.

(defn- leases-served? [eng]
  (some? (get (inv/resources eng) :lease)))

(defn- lease-ahead?
  "Is the lease's own end still ahead of `now`? A lease with none
  follows its holder, and is ahead while the holder is."
  [lease now]
  (let [exp (instant-of (get-in lease [:data :expires_at]))
        now (instant-of now)]
    (or (nil? exp) (nil? now) (.isAfter ^Instant exp ^Instant now))))

(defn- held-leases
  "The `held` leases `where` names, as stored."
  [st tx where]
  (store/query-rows st tx :lease (assoc where :state :held)
                    {:limit open-sitting-page}))

(defn- leases-of
  "The `held` leases the sitting `s` holds whose own end is not past at
  `now`, or nil when no lease was ever written for it. → a vector of
  stored rows, or nil."
  [eng st tx s now]
  (when (leases-served? eng)
    (let [mine {:holder_kind "sitting" :holder_id (str (:id s))}
          held (held-leases st tx mine)]
      (cond (seq held) (filterv #(lease-ahead? % now) held)
            (seq (store/query-rows st tx :lease mine {:limit 1})) []
            :else nil))))

(defn- held-rows
  "The walk row ids the OPEN sitting `s` holds at `now`: its leases'
  subjects (`leases-of`). → a vector of ids."
  [eng st tx s now]
  (into [] (keep #(some-> (get-in % [:data :subject_id]) str not-empty))
        (leases-of eng st tx s now)))

(defn held-row-ids
  "The walk row ids the sitting `sitting-id` holds now, through its live
  leases (`held-rows`). → a vector of ids."
  [eng sitting-id]
  (if (and sitting-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (if-some [s (store/load-row st tx :sitting (str sitting-id) {})]
            (held-rows eng st tx s ((:now-fn eng)))
            []))))
    []))

(defn handed-row-ids
  "The walk row ids the sitting `sitting-id` was handed: the subjects of
  its leases, held or ended — the record a closed sitting leaves
  (`leased-row-ids`). → a vector of ids."
  [eng sitting-id]
  (if (and sitting-id (leases-served? eng))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (leased-row-ids #(store/query-rows st tx %1 %2 %3)
                          {:holder_id (str sitting-id)}))))
    []))

(defn handed-by
  "The ids of the sittings whose leases held the walk row `row-id`, held
  or ended: the sittings it was handed to. → a set of ids."
  [eng row-id]
  (if (and row-id (leases-served? eng))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (into #{} (keep #(some-> (get-in % [:data :holder_id]) str not-empty))
                (store/query-rows st tx :lease
                                  {:holder_kind "sitting" :subject_id (str row-id)}
                                  {:limit lease-record-page})))))
    #{}))

(defn- graced-of
  "What the CLOSED sitting `s` still holds at `now` through its grace,
  as [id until] pairs: each lease its close released whose grace is
  still ahead (`leases-of`). → a vector."
  [eng st tx s _grace now]
  (into [] (keep (fn [l]
                   (let [until (instant-of (get-in l [:data :expires_at]))
                         id (some-> (get-in l [:data :subject_id]) str not-empty)]
                     (when (and until id) [id until]))))
        (leases-of eng st tx s now)))

(defn- graced-holds
  "The [id until] pairs the sittings of this seat CLOSED within their
  grace still hold (`graced-of`), but the sitting `sitting-id` names
  and a row whose work is over (`machine/work-over?` under the walked
  kind's `rdef`), which was finished, not released. → a vector."
  [eng st tx seat-row rdef sitting-id now]
  (let [grace (long (or (get-in seat-row [:data :release_grace_seconds])
                        release-grace-default))
        now (instant-of now)
        walk (some-> (get-in seat-row [:data :walk]) str not-empty keyword)
        over? (fn [id]
                (when (and rdef walk)
                  (when-some [r (store/load-row st tx walk id {})]
                    (machine/work-over? rdef (inv/decode-row rdef r)))))]
    (if-not (and seat-row now)
      []
      (into []
            (comp (remove #(= (str sitting-id) (str (:id %))))
                  (mapcat #(graced-of eng st tx % grace now))
                  (remove (comp over? first)))
            (store/query-rows st tx :sitting
                              {:seat (str (:id seat-row)) :state :closed}
                              {:limit open-sitting-page
                               :newest-first true})))))

(defn- graced-rows
  "The walk rows a sitting of this seat CLOSED within the seat's
  `release_grace_seconds` still holds (ticket f6c8d5ce): its close
  released its leases with that grace. A close can land while the run
  that sat is still mid-call — its hook fired early, or its last answer
  is in flight — and a fire a minute later must not take its ticket out
  from under it. An abandoned sitting released with no grace and holds
  nothing; the sitting `sitting-id` names is left out, as
  `claimed-rows` leaves it (`graced-holds`). → a set of ids."
  [eng st tx seat-row rdef sitting-id now]
  (into #{} (map first) (graced-holds eng st tx seat-row rdef sitting-id now)))

(defn grace-lifts-at
  "The moment the first of `ids` that a CLOSED sitting of this seat
  still holds through its grace (`graced-rows`) is handed on: that
  sitting's `ended_at` plus the seat's `release_grace_seconds`. A wake
  that finds its walk empty only because its row rests there is
  deferred to this moment, not spent on a run that sits and finds
  nothing (ticket 1a4038bf). → an Instant after `now`, or nil when
  none of `ids` is held by a grace."
  [eng seat-row ids now]
  (let [ids (into #{} (keep #(some-> % str not-empty)) ids)
        now (instant-of now)
        st (:storage eng)]
    (when (and seat-row now (seq ids)
               (get (inv/resources eng) :sitting))
      (store/with-tx st
        (fn [tx]
          (->> (graced-holds eng st tx seat-row (walked-rdef eng seat-row) nil now)
               (keep (fn [[id until]] (when (contains? ids id) until)))
               (filter #(.isAfter ^Instant % ^Instant now))
               sort
               first))))))

(defn- still-in-its-walk
  "Of `ids`, the rows still in the walk of the seat `other`: under its
  judgment's `queue` for a seat that says one, and its walk's scope
  entry filter otherwise, both under the kind's defaults
  (`wakes/walk-query`'s choice, read through `collections/parse-query`
  as the sit's page is). A row that seat's own door moved out of its
  walk is no longer its work (ticket 2885ad6e). A filter that cannot
  be read leaves every id in. → a set of ids."
  [eng st tx other ids]
  (let [rdef (walked-rdef eng other)
        jid (some-> (get-in other [:data :judgment]) str not-empty)
        judgment (when (and jid (get (inv/resources eng) :judgment))
                   (store/load-row st tx :judgment jid {}))
        f (if judgment
            (get-in judgment [:data :queue])
            (walk-filter other))]
    (if-not (and rdef (seq ids))
      (set ids)
      (try
        (let [params (into {} (map (fn [[k v]] [(name k) (str v)])) f)
              conds (vec (:conds (collections/parse-query rdef params)))]
          (into #{}
                (filter (fn [id]
                          (seq (store/ids-matching
                                st tx (:kind rdef)
                                (conj conds
                                      {:target :id :op :>= :value id}
                                      {:target :id :op :<= :value id})
                                1))))
                ids))
        (catch Exception _ (set ids))))))

(defn- holder-lapses-at
  "When the open sitting `s` of the seat `holder` stops holding its
  rows against ANOTHER seat (ticket cbe4d495): its `last_call_at` plus
  that seat's `release_grace_seconds`. A run whose hook never came
  leaves its sitting open until the idle sweep, and a seat woken for
  its row must not wait that long on nothing. The sitting stays open;
  only its hold over the other seat lapses. → an Instant, or nil when
  the sitting made no call or the grace is not positive: that hold
  lasts while the sitting is open."
  [s holder]
  (let [grace (long (or (get-in holder [:data :release_grace_seconds])
                        release-grace-default))]
    (when (pos? grace)
      (some-> (instant-of (get-in s [:data :last_call_at]))
              (.plusSeconds grace)))))

(defn- other-seats-holds
  "The open sittings of OTHER seats that walk this seat's kind and still
  hold rows of it against this seat at `now`, each as [sitting held-ids
  lapses-at] (`other-seats-rows` reads the ids, `elsewhere-lifts-at`
  the moments). A sitting past its `holder-lapses-at` holds nothing
  here. → a vector."
  [eng st tx seat-row now]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)
        seat-id (str (:id seat-row))
        seat-of (memoize (fn [id] (store/load-row st tx :seat id {})))
        now (instant-of now)]
    (if-not walk
      []
      (into []
            (comp (remove #(= seat-id (str (get-in % [:data :seat]))))
                  (remove #(lease-lapsed? % now))
                  (keep
                   (fn [s]
                     (when-some [other (some-> (get-in s [:data :seat])
                                               str not-empty seat-of)]
                       (when (= walk (some-> (get-in other [:data :walk]) str))
                         (let [lapses (holder-lapses-at s other)]
                           (when-not (and now lapses
                                          (not (.isAfter ^Instant lapses
                                                         ^Instant now)))
                             (let [ids (still-in-its-walk
                                        eng st tx other
                                        (held-rows eng st tx s now))]
                               (when (seq ids) [s ids lapses])))))))))
            (store/query-rows st tx :sitting
                              {:state :open}
                              {:limit open-sitting-page
                               :newest-first true})))))

(defn- other-seats-rows
  "The walk row ids the open sittings of OTHER seats that walk this
  seat's kind were handed (ticket 40d8f04c). Two seats over one queue
  are two offices on one row: without this each sit hands the row the
  other already works, and both runs edit one branch. The hold is the
  open sitting's alone - a closed or swept sitting of another seat
  holds nothing, and the grace stays the seat's own.

  The hold lasts while the row is in that other seat's walk
  (`still-in-its-walk`). A seat that grooms drafts walks the kind the
  seat that builds them walks: its sitting was handed the draft, and
  its groom moved the row into the builder's walk while that sitting
  was still open. Held past that move, the row was counted out of the
  builder's walk, and the groom's wake read the walk as empty and was
  dropped (ticket 2885ad6e).

  The hold also lapses one `release_grace_seconds` of the holding seat
  after its sitting's last call (`holder-lapses-at`, ticket cbe4d495):
  a sitting whose run ended without its hook stays open until the idle
  sweep, and held the row from every other seat all that while. → a
  set of ids."
  [eng st tx seat-row]
  (into #{} (mapcat second)
        (other-seats-holds eng st tx seat-row ((:now-fn eng)))))

(defn elsewhere-lifts-at
  "The moment the first of `ids` that an open sitting of ANOTHER seat
  holds (`other-seats-holds`) is let go: that sitting's last call plus
  its seat's `release_grace_seconds` (ticket cbe4d495). A wake that
  finds its walk empty only because another seat's run holds its rows
  is deferred to this moment and asked again then, not spent: a close
  before it ends the hold sooner, and a holder still calling moves the
  moment on. → an Instant after `now`, or nil when no such hold has an
  end."
  [eng seat-row ids now]
  (let [ids (into #{} (keep #(some-> % str not-empty)) ids)
        st (:storage eng)]
    (when (and seat-row (seq ids) (get (inv/resources eng) :sitting))
      (store/with-tx st
        (fn [tx]
          (->> (other-seats-holds eng st tx seat-row now)
               (keep (fn [[_ held lapses]]
                       (when (some ids held) lapses)))
               sort
               first))))))

(defn other-seats-row-ids
  "The part of `claimed-rows` an open sitting of ANOTHER seat holds
  (`other-seats-rows`), so a walk they emptied can say so. → a set of
  ids."
  [eng seat-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (other-seats-rows eng st tx
                            (store/load-row st tx :seat (str seat-id) {})))))
    #{}))

(defn claimed-rows
  "The walk row ids the OTHER open sittings of this seat were handed,
  and the ones the open sittings of another seat over the same kind
  were (`other-seats-rows`):
  the rows a second run of the seat must not walk again. A fire and a
  wake that land together start two runs, and without this both sits
  answer the same first row, so both work one branch and the next row
  waits. The claim ends with the sitting — an abandoned or expired
  sitting holds nothing, nor does an open one whose lease ran out (the
  claim expires it), and a closed one holds its rows only through the
  seat's grace (`graced-rows`) — and the sitting `sitting-id`
  names is left out, so a re-sit is handed its own rows again. → a
  set of ids."
  [eng seat-id sitting-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (into (let [seat-row (store/load-row st tx :seat (str seat-id) {})]
                  (into (graced-rows eng st tx seat-row (walked-rdef eng seat-row)
                                     sitting-id ((:now-fn eng)))
                        (other-seats-rows eng st tx seat-row)))
                (comp (remove #(= (str sitting-id) (str (:id %))))
                      (remove #(lease-lapsed? % ((:now-fn eng))))
                      (mapcat #(held-rows eng st tx % ((:now-fn eng)))))
                (store/query-rows st tx :sitting
                                  {:seat (str seat-id) :state :open}
                                  {:limit open-sitting-page
                                   :newest-first true})))))
    #{}))

(defn holding-sittings
  "The ids of this seat's open sittings that hold any of `row-ids`
  (`held-rows`), newest first: the runs a sit that found every
  row held names, so the stop it is told to make is seen to be right
  (ticket 471d69dd). → a vector of ids."
  [eng seat-id row-ids]
  (let [wanted (into #{} (keep #(some-> % str not-empty)) row-ids)]
    (if (and seat-id (seq wanted) (get (inv/resources eng) :sitting))
      (let [st (:storage eng)]
        (store/with-tx st
          (fn [tx]
            (into []
                  (comp (filter (fn [s]
                                  (some #(contains? wanted %)
                                        (held-rows eng st tx s ((:now-fn eng))))))
                        (map #(str (:id %))))
                  (store/query-rows st tx :sitting
                                    {:seat (str seat-id) :state :open}
                                    {:limit open-sitting-page
                                     :newest-first true})))))
      [])))

(defn graced-row-ids
  "The part of `claimed-rows` a CLOSED sitting of this seat holds
  through the seat's release grace (`graced-rows`), the sitting
  `sitting-id` left out, so a walk the grace emptied can say so
  (ticket ae64b57c). → a set of ids."
  [eng seat-id sitting-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (let [seat-row (store/load-row st tx :seat (str seat-id) {})]
            (graced-rows eng st tx seat-row (walked-rdef eng seat-row)
                         sitting-id ((:now-fn eng)))))))
    #{}))

;; ── the rows a sit leaves out of its walk ───────────────────────────

(def ^:private stuck-scan-limit
  "How many stuck changes one walk reads to leave their tickets out. A
  stuck change waits for a person, and a house that works holds few."
  200)

(def ^:private live-change-scan-limit
  "How many change rows one lookup of a ticket's live change reads."
  20)

(def ^:private groomed-walk-prefix
  "What `born_from` starts with for a change built for a ticket."
  "ticket:")

(defn- latest-transition
  "The newest transition of one row through `action`, or nil."
  [eng kind id action]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (some (fn [tr] (when (= action (some-> (:action tr) name)) tr))
              (store/transitions st tx {:kind kind :resource-id (str id)}
                                 {:newest-first true}))))))

(defn groom-after-stall
  "The ticket's newest `groom` when it is newer than the change's newest
  `stall`, or nil: a person has read the stall and stands behind the
  ticket again. A change that was never stalled, or a ticket not groomed
  since, answers nil."
  [eng change ticket-id]
  (let [groom (latest-transition eng :ticket ticket-id "groom")
        stall (latest-transition eng :change (:id change) "stall")]
    (when (and groom stall (> (long (:id groom)) (long (:id stall))))
      groom)))

(def ^:private queueing-doors
  "The ticket's doors that put its ask in the queue again: a person's
  groom, unblock, resume or reopen, or the engine's `return` when
  GitHub closed the pull request unmerged."
  #{"groom" "unblock" "resume" "return" "reopen"})

(def ^:private ending-doors
  "The change's doors that end it unmerged."
  #{"close" "close_without_pr" "supersede"})

(defn- latest-transition-of
  "The newest transition of one row through any of `actions`, or nil."
  [eng kind id actions]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (some (fn [tr] (when (contains? actions (some-> (:action tr) name)) tr))
              (store/transitions st tx {:kind kind :resource-id (str id)}
                                 {:newest-first true}))))))

(defn ask-after-close
  "The ticket's newest groom, unblock, resume, return or reopen when it
  is newer than the change's newest close, close_without_pr or
  supersede, or nil (ticket 7e12f1e8): the ask was put in the queue
  again after the change ended. A ticket never queued answers nil; a
  change that ended by none of those doors answers the ask alone."
  [eng change ticket-id]
  (let [ask (latest-transition-of eng :ticket ticket-id queueing-doors)
        end (latest-transition-of eng :change (:id change) ending-doors)]
    (when (and ask (or (nil? end) (> (long (:id ask)) (long (:id end)))))
      ask)))

(defn- parent-walk-reasons
  "The open tickets that have a child in any state (ticket 898caffd): a
  parent is not built, it ends after its children, so a ticket walk
  leaves it out as it leaves a stuck one out. → {ticket-id reason}."
  [eng]
  (if-some [tdef (get (inv/resources eng) :ticket)]
    (let [st (:storage eng)
          tickets (fn [where limit]
                    (try
                      (->> (store/with-tx st
                             (fn [tx]
                               (hooks/hook :factory-tickets eng tx where limit)))
                           (map #(inv/decode-row tdef %)))
                      (catch Exception _ nil)))]
      (into {}
            (keep (fn [ticket]
                    (let [id (str (:id ticket))]
                      (when (seq (tickets {:parent id} 1))
                        [id "it has children: a parent is not built, it ends after them"]))))
            (tickets {:state "open"} stuck-scan-limit)))
    {}))

(defn stuck-walk-reasons
  "The tickets in a ticket walk whose change is stuck and
  waits for a person (ticket 6bdaf6fe): a `stuck` change born from the
  ticket, no live change beside it, and no groom since its stall. The
  walk leaves them out as it leaves a claimed row out, so a queue of
  only such tickets answers an empty walk and no wake spends a sitting
  on saying it is stuck. A groom after the stall puts the ticket back,
  and the sit then unsticks its change. A stuck change is one whatever
  state it was stuck from: `open`, `submitted` or `failing` (ticket
  60c2ec22).

  A ticket whose change is `submitted` is left out too (ticket
  60c2ec22): its round is in review and its landing may still run, so
  a second run of the seat has nothing to build on it, and a submit
  there only finds a clean worktree.

  Only a change in the ticket's own repository counts, when the ticket
  names one (ticket 0eba219c, as 80a8e60b for the named walk): each
  candidate ticket's repo is loaded once, within the scan limit.

  An open ticket with a child is left out too (`parent-walk-reasons`,
  ticket 898caffd): a parent is not built.

  → {ticket-id reason}: each withheld ticket with the sentence the sit
  answers for it (ticket 87c928e9), so a seat handed an empty walk
  can say which row was held back and why. Empty for any other walk
  and for an engine that serves no change."
  [eng walk]
  (if-some [rdef (when (= "ticket" (str walk))
                   (get (inv/resources eng) :change))]
    (let [st (:storage eng)
          changes (fn [where limit]
                    (->> (store/with-tx st
                           (fn [tx]
                             (hooks/hook :factory-changes eng tx where limit)))
                         (map #(inv/decode-row rdef %))))
          tdef (get (inv/resources eng) :ticket)
          repo-of (memoize
                   (fn [ticket-id]
                     (when tdef
                       (try
                         (some-> (some->> (store/with-tx st
                                            (fn [tx]
                                              (hooks/hook :factory-ticket eng tx
                                                          ticket-id)))
                                          (inv/decode-row tdef))
                                 (get-in [:data :repo]) str not-empty)
                         (catch Exception _ nil)))))
          ours? (fn [ticket-id change]
                  (let [repo (repo-of ticket-id)]
                    (or (nil? repo)
                        (= repo (some-> (get-in change [:data :repository])
                                        str)))))
          live? (fn [born ticket-id]
                  (some #(and (contains? #{:open :submitted :failing}
                                         (some-> (:state %) name keyword))
                              (ours? ticket-id %))
                        (changes {:born_from born} live-change-scan-limit)))
          ticket-of (fn [change]
                      (let [born (str (get-in change [:data :born_from]))]
                        (when (str/starts-with? born groomed-walk-prefix)
                          (not-empty (subs born (count groomed-walk-prefix))))))]
      (merge
       (parent-walk-reasons eng)
       (into (into {} (keep (fn [change]
                             (when-some [ticket-id (ticket-of change)]
                               (when (ours? ticket-id change)
                                 [ticket-id (str "change " (:id change)
                                                 " is submitted and in review")]))))
                  (changes {:state "submitted"} stuck-scan-limit))
            (keep (fn [change]
                    (let [born (str (get-in change [:data :born_from]))]
                      (when (str/starts-with? born groomed-walk-prefix)
                        (when-some [ticket-id (not-empty
                                               (subs born (count groomed-walk-prefix)))]
                          (when (and (ours? ticket-id change)
                                     (not (live? born ticket-id))
                                     (nil? (groom-after-stall eng change
                                                              ticket-id)))
                            [ticket-id (str "change " (:id change)
                                            " is stuck and waits for a person")]))))))
            (changes {:state "stuck"} stuck-scan-limit))))
    {}))

(defn stuck-walk-rows
  "The ids of `stuck-walk-reasons`, as a set."
  [eng walk]
  (set (keys (stuck-walk-reasons eng walk))))

(defn named-walk-row
  "The walk row a fire's text named, as the sit reads it (ticket
  7af7d506): the id itself when it is a row of the kind the seat walks,
  and for a ticket walk the ticket a named CHANGE was born from, so a
  fire that names either hands the ticket and the change beside it. Nil
  for an id that is neither."
  [eng walk id]
  (when-some [id (some-> id str not-empty)]
    (let [walk (str walk)
          row-of (fn [kind]
                   (when-some [rdef (get (inv/resources eng) kind)]
                     (try
                       (some->> (store/with-tx (:storage eng)
                                  (fn [tx]
                                    (store/load-row (:storage eng) tx kind id
                                                    {})))
                                (inv/decode-row rdef))
                       (catch Exception _ nil))))]
      (cond
        (and (seq walk) (row-of (keyword walk))) id

        (= "ticket" walk)
        (when-some [change (row-of :change)]
          (let [born (str (get-in change [:data :born_from]))]
            (when (str/starts-with? born groomed-walk-prefix)
              (not-empty (subs born (count groomed-walk-prefix))))))))))

(defn failing-change-mark
  "The mark the walk row of a ticket carries when the fire named a
  FAILING change born from it (ticket c91b3af9): the seat's own red
  round, handed back for it to fix. → {\"why\" \"failing_change\"
  \"change\" id \"pr\" number \"head\" sha} when `id` is a change in
  `failing` whose ticket is `in_review`, and the seat wrote the change
  (its `author` is the seat's name) or holds `complete` on tickets of
  the ticket's repository. Nil otherwise, and the walk is as it was."
  [eng seat-row id]
  (when (= "ticket" (str (get-in seat-row [:data :walk])))
    (let [row-of (fn [kind rid]
                   (when-some [rdef (get (inv/resources eng) kind)]
                     (try
                       (some->> (store/with-tx (:storage eng)
                                  (fn [tx]
                                    (store/load-row (:storage eng) tx kind
                                                    (str rid) {})))
                                (inv/decode-row rdef))
                       (catch Exception _ nil))))
          change (some->> id str not-empty (row-of :change))
          born (str (get-in change [:data :born_from]))
          ticket-id (when (str/starts-with? born groomed-walk-prefix)
                      (not-empty (subs born (count groomed-walk-prefix))))
          ticket (when (and ticket-id
                            (= "failing" (some-> (:state change) name)))
                   (row-of :ticket ticket-id))
          repo (some-> (get-in ticket [:data :repo]) str not-empty)
          seat-name (some-> (get-in seat-row [:data :name]) str not-empty)
          wrote? (and seat-name
                      (= seat-name (str (get-in change [:data :author]))))
          completes? (some (fn [entry]
                             (and (= "ticket" (some-> (:kind entry) name))
                                  (some #(= "complete" (name %)) (:actions entry))
                                  (let [r (some-> (get-in entry [:filter :repo]) str)]
                                    (or (nil? r) (= r repo)))))
                           (get-in seat-row [:data :scope]))
          number (get-in change [:data :number])
          head (some-> (get-in change [:data :head_sha]) str not-empty)]
      (when (and ticket
                 (= "in_review" (some-> (:state ticket) name))
                 (or wrote? completes?))
        (cond-> {"why" "failing_change" "change" (str (:id change))}
          (some? number) (assoc "pr" number)
          head (assoc "head" head))))))

(defn fire-deferred-until
  "When a fire whose `text` names a walk row (`named-row`, read back to
  its ticket by `named-walk-row`) must wait: the moment that row's
  release grace lifts (`grace-lifts-at`), when only a CLOSED sitting's
  grace holds it (ticket afb445d4). A run fired now would sit, be told
  the row is held, and stop. A row an OPEN sitting of the seat holds is
  the sit's to say, as it was, and a free row fires at once. → an
  Instant after `now`, or nil."
  [eng seat-row text now]
  (when-some [named (some->> (named-row eng seat-row text)
                             (named-walk-row eng (get-in seat-row [:data :walk])))]
    (let [st (:storage eng)
          open (store/with-tx st
                 (fn [tx]
                   (into #{}
                         (mapcat #(held-rows eng st tx % now))
                         (store/query-rows st tx :sitting
                                           {:seat (str (:id seat-row)) :state :open}
                                           {:limit open-sitting-page
                                            :newest-first true}))))]
      (when-not (contains? open named)
        (grace-lifts-at eng seat-row [named] now)))))

(defn fire-names
  "The walk row a fire's `text` names, as the sit reads it (`named-row`,
  read back to its ticket by `named-walk-row`), or nil when it names
  none."
  [eng seat-row text]
  (some->> (named-row eng seat-row text)
           (named-walk-row eng (get-in seat-row [:data :walk]))))

(defn open-walked-rows
  "The walk row ids the OPEN sittings of this seat hold, as a set.
  `claimed-rows` less the rows a closed sitting's grace holds: those
  are `fire-deferred-until`'s to say."
  [eng seat-id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (into #{}
              (mapcat #(held-rows eng st tx % ((:now-fn eng))))
              (store/query-rows st tx :sitting
                                {:seat (str seat-id) :state :open}
                                {:limit open-sitting-page
                                 :newest-first true}))))))

(defn sitting-down-count
  "How many OPEN sittings of this seat have sat and hold no row YET:
  no lease, and neither stamp a finished sit leaves on a sitting
  it handed nothing (`stamp-walked-nothing!`'s `walked_nothing` and
  `halted`). A sitting is born before its sit claims its rows
  (`claim-rows-atomically!`), so between the two its run is no longer a
  fire on its way and its row is not yet held: a wake that counted free
  rows then read that row free and fired a second run for it (ticket
  c822d9e4)."
  [eng seat-id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (count
         (remove #(or (seq (store/query-rows st tx :lease
                                             {:holder_kind "sitting"
                                              :holder_id (str (:id %))}
                                             {:limit 1}))
                      (true? (get-in % [:data :walked_nothing]))
                      (some? (get-in % [:data :halted])))
                 (store/query-rows st tx :sitting
                                   {:seat (str seat-id) :state :open}
                                   {:limit open-sitting-page
                                    :newest-first true})))))))

(defn- ticket-ended?
  "Has the ticket `id` ended, `done` or `dropped`? False for a ticket
  the store does not hold."
  [eng id]
  (boolean
   (when-some [rdef (get (inv/resources eng) :ticket)]
     (let [st (:storage eng)]
       (try
         (some->> (store/with-tx st
                    (fn [tx] (hooks/hook :factory-ticket eng tx (str id))))
                  (inv/decode-row rdef)
                  :state name keyword
                  (contains? #{:done :dropped}))
         (catch Exception _ false))))))

(defn named-beside-a-live-change?
  "Does a live change — open, submitted, failing or stuck — stand beside
  the ticket a fire named? Such a ticket is walked whatever its own
  state (ticket 7af7d506): a seat fired on a ticket in review is handed
  it and its change, and a ticket whose change merged or closed is not.
  A ticket that ended is not either (ticket 458d65c5): its ending
  closed its changes, and a leftover is no work to hand. False for any
  other walk."
  [eng walk id]
  (boolean
   (when-some [rdef (when (and (= "ticket" (str walk))
                               (not (ticket-ended? eng id)))
                      (get (inv/resources eng) :change))]
     (let [st (:storage eng)]
       (some #(contains? #{:open :submitted :failing :stuck}
                         (some-> (:state %) name keyword))
             (map #(inv/decode-row rdef %)
                  (store/with-tx st
                    (fn [tx]
                      (hooks/hook :factory-changes eng tx
                                  {:born_from (str groomed-walk-prefix id)}
                                  live-change-scan-limit)))))))))

(defn named-beside-a-submitted-change?
  "Does the ticket a fire named stand in one of `states`, a set of state
  names, while a change born from it is `submitted`? Only a change in
  the ticket's own repository counts, when the ticket names one (ticket
  80a8e60b). False for any other walk."
  [eng walk id states]
  (boolean
   (when-some [rdef (when (= "ticket" (str walk))
                      (get (inv/resources eng) :change))]
     (when-some [tdef (get (inv/resources eng) :ticket)]
       (let [st (:storage eng)
             ticket (try
                      (some->> (store/with-tx st
                                 (fn [tx]
                                   (hooks/hook :factory-ticket eng tx (str id))))
                               (inv/decode-row tdef))
                      (catch Exception _ nil))]
         (when (contains? states (some-> (:state ticket) name))
           (some (let [repo (some-> (get-in ticket [:data :repo]) str not-empty)]
                   #(and (= "submitted" (some-> (:state %) name))
                         (or (nil? repo)
                             (= repo (some-> (get-in % [:data :repository]) str)))))
                 (map #(inv/decode-row rdef %)
                      (store/with-tx st
                        (fn [tx]
                          (hooks/hook :factory-changes eng tx
                                      {:born_from (str groomed-walk-prefix id)}
                                      live-change-scan-limit)))))))))))

(defn named-open-beside-a-submitted-change?
  "Is the ticket a fire named `open` while a change born from it is
  `submitted` (ticket 6ca380da)? The round is in the house's hands, so
  the named walk withholds it as the plain walk does (ticket 60c2ec22).
  A groom, unblock or resume that puts a stuck pull request back under
  review sends its ticket out for review after it (ticket 7e01dbe5); a
  ticket whose follow-up was refused, or whose change was submitted by
  another path, is the one left open here. A ticket in review is still
  handed by name (ticket 7af7d506)."
  [eng walk id]
  (named-beside-a-submitted-change? eng walk id #{"open"}))

(defn unwalkable-rows
  "The walk row ids a sit of this seat would not hand now: the rows
  another open sitting holds (`claimed-rows`) and the tickets whose
  change is stuck (`stuck-walk-rows`). The sit subtracts them from its
  page and the wakes from their count, so a wake never fires a run
  whose sit walks nothing (ticket e031e479). `sitting-id` is the
  sitting whose own rows stay walkable, or nil. → a set of ids."
  [eng seat-row sitting-id]
  (into (claimed-rows eng (:id seat-row) sitting-id)
        (stuck-walk-rows eng (get-in seat-row [:data :walk]))))

(defn- lease-ctx
  "What `leases/live?` reads, over this transaction."
  [eng st tx]
  {:now ((:now-fn eng))
   :rdef-of #(some->> % str not-empty keyword (get (inv/resources eng)))
   :read (fn [kind id] (store/load-row st tx (keyword (name kind)) (str id) {}))})

(defn- lease-rows!
  "Hold the `subjects`, each [kind id] (`claim-subjects`), for the open
  sitting `sitting-id` of the seat `seat-id`, inside `tx`, through the lease's
  own doors under the leases actor (docs/spec-leases.md §3). The held
  leases on those rows and the sitting's own are locked FOR UPDATE in
  the order of their ids — the last tier of the lock order seat,
  subjects, sittings, leases. Every lease another holder has on one of
  them is expired, `taken_over` while it is still live: the claim
  judged the row free, so its hold had lapsed or its holder has ended.
  The sitting's leases on rows outside `ids` are released with no
  grace, and one `acquire` is made per row it does not hold yet. The
  writes ride `sink`, for `inv/after-commit!`."
  [eng st tx subjects seat-id sitting-id sink]
  (let [lctx (lease-ctx eng st tx)
        opts {:principal leases/leaser :sink sink}
        subjects (distinct subjects)
        want (into #{} (map second) subjects)
        me (str sitting-id)
        mine? #(and (= "sitting" (str (get-in % [:data :holder_kind])))
                    (= me (str (get-in % [:data :holder_id]))))
        mine (held-leases st tx {:holder_kind "sitting" :holder_id me})
        theirs (into [] (comp (mapcat (fn [[k id]]
                                        (held-leases st tx {:subject_kind k
                                                            :subject_id id})))
                              (remove mine?))
                     subjects)
        subject #(str (get-in % [:data :subject_id]))]
    (doseq [l (sort-by #(str (:id %)) (concat mine theirs))]
      (store/load-row st tx :lease (str (:id l)) {:for-update true}))
    (doseq [l theirs]
      (inv/invoke-in! eng tx :lease (:id l) :expire
                      (if (leases/live? lctx l) {:reason "taken_over"} {})
                      opts))
    (doseq [l mine
            :when (and (not (want (subject l))) (leases/live? lctx l))]
      (inv/invoke-in! eng tx :lease (:id l) :release {:grace_seconds 0} opts))
    (let [kept (into #{} (comp (filter #(leases/live? lctx %)) (map subject)) mine)]
      (doseq [[k id] subjects :when (not (kept id))]
        (inv/create-in! eng tx :lease
                        {:holder_kind "sitting" :holder_id me
                         :subject_kind k :subject_id id
                         :seat (some-> seat-id str)}
                        opts)))))

(defn- claim-subjects
  "The rows a claim asked for, each as [kind id]: a bare id is a row of
  the kind the seat walks, and a map names its own `:kind` and `:id` —
  a breach row the walk joined (R-P.6.1), of any kind. A row of a kind
  this engine does not serve is left out. → a vector."
  [eng seat-row rows]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
    (into [] (keep (fn [r]
                     (let [[k id] (if (map? r) [(:kind r) (:id r)] [walk r])
                           k (some-> k str not-empty)
                           id (some-> id str not-empty)]
                       (when (and k id (get (inv/resources eng) (keyword k)))
                         [k id]))))
          rows)))

(defn- claim-ids
  "The ids of the rows a claim asked for, bare or named with their kind."
  [rows]
  (into [] (keep #(some-> (if (map? %) (:id %) %) str not-empty)) rows))

(defn- lock-subjects!
  "The rows asked for (`claim-subjects`), each FOR UPDATE under its own
  kind, in the order of their ids. → those [kind id], or nil when none
  is of a kind this engine serves."
  [eng st tx seat-row rows]
  (let [subjects (claim-subjects eng seat-row rows)]
    (doseq [[k id] (sort-by second subjects)]
      (store/load-row st tx (keyword k) id {:for-update true}))
    (not-empty subjects)))

(defn claim-rows!
  "Lease the walk rows this sit handed to the sitting it opened, so
  `claimed-rows` keeps them from a second open sitting of the seat: a
  lease per row (`lease-rows!`), for a seat that walks a kind. Only an
  OPEN sitting takes the claim, and it reads no
  other claim — `claim-rows-atomically!` does. It locks as that does:
  the seat, the rows, the sitting, the leases. → true when it was
  written."
  [eng sitting-id row-ids]
  (let [ids (claim-ids row-ids)
        st (:storage eng)
        sink (atom [])]
    (when (and sitting-id (seq ids) (get (inv/resources eng) :sitting))
      (let [res (store/with-tx st
                  (fn [tx]
                    (let [seat-id (some-> (store/load-row st tx :sitting (str sitting-id) {})
                                          (get-in [:data :seat]) str not-empty)
                          seat-row (when seat-id
                                     (store/load-row st tx :seat seat-id {:for-update true}))
                          subjects (lock-subjects! eng st tx seat-row row-ids)]
                      (when-some [row (store/load-row st tx :sitting
                                                      (str sitting-id) {:for-update true})]
                        (when (= :open (:state row))
                          (when (and subjects (leases-served? eng))
                            (lease-rows! eng st tx subjects seat-id sitting-id sink))
                          true)))))]
        (inv/after-commit! eng @sink)
        res))))

(defn- stale-holders
  "Step 3 of the claim (docs/spec-leases.md §1): the lapsed open
  sittings but `sitting-id` holding one of `ids`, read without a lock.
  → their ids."
  [eng st tx subjects sitting-id others ids now]
  (let [want (set ids)
        holder #(store/load-row st tx :sitting (str %) {})
        leased (when (and subjects (leases-served? eng))
                 (into [] (comp (mapcat (fn [[k id]]
                                          (held-leases st tx {:subject_kind k
                                                              :subject_id id})))
                                (filter #(= "sitting" (str (get-in % [:data :holder_kind]))))
                                (keep #(some-> (get-in % [:data :holder_id]) holder)))
                       subjects))]
    (into [] (comp (filter #(lease-lapsed? % now))
                   (map #(str (:id %)))
                   (remove #{(str sitting-id)})
                   (distinct))
          (concat leased
                  (filter #(some want (held-rows eng st tx % now)) others)))))

(defn- expire-stale-holders!
  "Step 4 of the claim (docs/spec-leases.md §1): the `stale` holders and
  the claiming sitting, locked FOR UPDATE by id and read again. One
  renewed or ended-in-grace since keeps its rows of `ids`, and nothing
  is written; else each still past is expired. → the ids kept."
  [eng st tx sitting-id ids now sink stale]
  (let [want (set ids)
        me (str sitting-id)
        locked (into [] (keep #(store/load-row st tx :sitting % {:for-update true}))
                     (sort (distinct (conj (vec stale) me))))
        theirs (remove #(= me (str (:id %))) locked)
        holds (fn [s]
                (if (= "open" (some-> (:state s) name))
                  (held-rows eng st tx s now)
                  (keep #(some-> (get-in % [:data :subject_id]) str)
                        (leases-of eng st tx s now))))
        kept (into #{} (comp (remove #(lease-lapsed? % now))
                             (mapcat holds)
                             (filter want))
                   theirs)]
    (when (empty? kept)
      (doseq [s theirs :when (lease-lapsed? s now)]
        (inv/invoke-in! eng tx :sitting (:id s) :expire nil
                        {:principal seats-actor :sink sink})))
    kept))

(defn claim-rows-atomically!
  "`claim-rows!`, with the read of the other sittings' claims IN THE
  SAME TRANSACTION as the write. The seat row is read FOR UPDATE first,
  so every claim of one seat runs one at a time: two sits of the seat
  at the same instant cannot both find a row free and both write it.

  Two seats over one kind lock two seat rows, so the rows asked for are
  read FOR UPDATE as well, in the order of their ids: the sits of two
  seats that ask for one row run one at a time too, and the second
  reads the first one's claim (`other-seats-rows`, ticket 40d8f04c).
  Then the claiming sitting, then the leases (`lease-rows!`): the lock
  order seat, subjects, sittings, leases (docs/spec-leases.md §1).

  The claim is all or nothing. When a row asked for is already held by
  another open sitting, nothing is written, and the answer
  says which rows are held, so the sit reads its walk again past them.
  A lapsed holder is expired (`expire-stale-holders!`).
  Otherwise the sitting leases every row it was handed, a lease the
  claim judged lapsed or ended is expired.
  A row asked for is a bare id of the walked kind, or {:kind :id} for a
  breach row of another kind the walk joined (R-P.6.1): each is locked
  and leased under its own kind (`claim-subjects`).
  → {:claimed? bool :taken #{ids other open sittings hold}}."
  [eng seat-id sitting-id row-ids]
  (let [ids (claim-ids row-ids)
        st (:storage eng)
        sink (atom [])]
    (if-not (and seat-id sitting-id (get (inv/resources eng) :sitting))
      {:claimed? true :taken #{}}
      (let [res (store/with-tx st
                  (fn [tx]
                    (let [seat-row (store/load-row st tx :seat (str seat-id) {:for-update true})
                          subjects (lock-subjects! eng st tx seat-row row-ids)
                          now ((:now-fn eng))
                          open (store/query-rows st tx :sitting
                                                 {:seat (str seat-id) :state :open}
                                                 {:limit open-sitting-page
                                                  :newest-first true})
                          others (remove #(= (str sitting-id) (str (:id %))) open)
                          taken (into (into (graced-rows eng st tx seat-row
                                                         (walked-rdef eng seat-row)
                                                         sitting-id now)
                                            (other-seats-rows eng st tx seat-row))
                                      (comp (remove #(lease-lapsed? % now))
                                            (mapcat #(held-rows eng st tx % now)))
                                      others)
                          mine (first (filter #(= (str sitting-id) (str (:id %))) open))]
                      (cond
                        (some taken ids) {:claimed? false :taken taken}

                        (and mine (seq ids))
                        (let [kept (expire-stale-holders!
                                    eng st tx sitting-id ids now sink
                                    (stale-holders eng st tx subjects sitting-id others ids now))]
                          (if (seq kept)
                            {:claimed? false :taken (into taken kept)}
                            (do (when (and subjects (leases-served? eng))
                                  (lease-rows! eng st tx subjects seat-id sitting-id sink))
                                {:claimed? true :taken taken})))

                        :else {:claimed? true :taken taken}))))]
        (inv/after-commit! eng @sink)
        res))))

(defn stamp-walked-nothing!
  "Stamp the sitting whose sit was let walk and handed it NO rows — an
  empty queue, a queue whose every row another open sitting or a stuck
  change holds — so seat health counts a wake that had nothing to do
  rather than reading an absent lease, which a sitting the claim never
  reached carries too. A re-sit that IS handed a row takes
  the stamp back off. A MAINTENANCE write, `claim-rows!`'s spelling:
  only an OPEN sitting takes it, and only a change is written.

  `why` rides beside the stamp as `walked_nothing_why`: the rule that
  left each row out. `halted` is the sit's own `halted` block, and a
  halted sit is stamped with IT and not `walked_nothing` — it was not
  let walk, and a reader must not take the wall for a walk bug (ticket
  ae64b57c). → true when it was written."
  ([eng sitting-id nothing?]
   (stamp-walked-nothing! eng sitting-id nothing? nil))
  ([eng sitting-id nothing? {:keys [why halted]}]
   (when (and sitting-id (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (when-some [row (store/load-row (:storage eng) tx :sitting
                                         (str sitting-id) {:for-update true})]
           (let [mark (cond
                        (seq halted) {:halted (halt-mark-of halted)}
                        nothing? (cond-> {:walked_nothing true}
                                   why (assoc :walked_nothing_why why))
                        :else {})
                 data (merge (dissoc (:data row) :walked_nothing
                                     :walked_nothing_why :halted)
                             mark)]
             (when (and (= :open (:state row)) (not= data (:data row)))
               (store/maintain! :stamp (:storage eng) tx :sitting (str sitting-id)
                                   data nil)
               true))))))))

(defn stamp-halted-schedule!
  "The seat's schedule, told the wall its sit found (ticket ae64b57c):
  `halted` beside `last_halted_wake`, so the schedule says which wall
  held the seat and when it lifts, and not only that a wake waits. A
  sit clear of the wall takes it off. A MAINTENANCE write, as
  `stamp-walked-nothing!`. → true when it was written."
  [eng seat-id halted]
  (when (and seat-id (get (inv/resources eng) :schedule))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (when-some [row (first (store/query-rows st tx :schedule
                                                   {:seat (str seat-id)}
                                                   {:limit 1}))]
            (let [data (if (seq halted)
                         (assoc (:data row) :halted (halt-mark-of halted))
                         (dissoc (:data row) :halted))]
              (when (not= data (:data row))
                (store/maintain! :stamp st tx :schedule (:id row) data
                                    (:next-flip-at row))
                true))))))))

(defn open-sitting-count
  "How many sittings of this seat are OPEN now: the runs the seat's
  `max_open_sittings` is judged against (`wakes/wake-seat!`). Open
  means a live lease, not state `open` alone (docs/spec-leases.md §3)."
  [eng seat-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [now (now-of eng)]
      (count (filter #(lease-live? % now)
                     (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/query-rows (:storage eng) tx :sitting
                                           {:seat (str seat-id) :state :open}
                                           {:limit open-sitting-page}))))))
    0))

(defn busy-sitting-count
  "How many OPEN sittings of this seat hold a slot: every open one but
  those the sit stamped `walked_nothing` (`stamp-walked-nothing!`). A
  sit that found every row held, or an empty queue, has no work, and
  its run is ending; the sitting stays open only so the Stop hook can
  close it with the run's turns and tokens. Counted busy, it kept a
  free slot from a row that came free meanwhile (ticket cc2f8e7f)."
  [eng seat-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [now (now-of eng)]
      (count (remove #(or (true? (get-in % [:data :walked_nothing]))
                          ;; a lapsed lease holds no slot (§3)
                          (not (lease-live? % now)))
                     (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/query-rows (:storage eng) tx :sitting
                                           {:seat (str seat-id) :state :open}
                                           {:limit open-sitting-page}))))))
    0))

;; ── which sitting is open, and what the earlier ones were handed ─────

(declare open-sittings-for-grant)

(defn open-sitting-for-grant
  "The open sitting a request under `grant-id` is counted against, or
  nil. ONE query, by the promoted `grant` column and state — the
  router runs it on every write and every refusal, so it must stay one
  lookup (R-10.6). A request with no open sitting counts nothing,
  which is what nil means here. Open means a live lease
  (docs/spec-leases.md §3), so a connector back after its lease ran
  out is sat afresh, not handed a sitting the sweep is about to end:
  the newest live one of `open-sittings-for-grant`'s page."
  [eng grant-id]
  (when (and grant-id (get (inv/resources eng) :sitting))
    (first (open-sittings-for-grant eng grant-id))))

(defn open-sittings-for-grant
  "Every open sitting under `grant-id`, newest first, one page of them:
  `open-sitting-for-grant`'s query read past its first row. Every
  sitting of a seat shares the seat's grant, so a sit that must find
  THIS run's sitting among overlapping runs reads them all
  (mcp/reusable-sitting). With `lapsed?` the page keeps the open rows
  whose lease ran out and the sweep has not ended yet: the ones a
  re-sit of THIS run may re-let (`claim-sitting!`, ticket e2b55a0c)."
  ([eng grant-id] (open-sittings-for-grant eng grant-id false))
  ([eng grant-id lapsed?]
   (if (and grant-id (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (filterv #(or lapsed? (lease-live? % (now-of eng)))
                  (store/query-rows (:storage eng) tx :sitting
                                    {:grant (str grant-id) :state :open}
                                    {:limit open-sitting-page :newest-first true}))))
     [])))

(defn open-sitting-for-seat
  "The open sitting a SESSION-END REPORT belongs to (R-12.17), or nil.

  `open-sitting-for-grant`'s shape, one field over and one reading
  past it. The hook at the end of a run presents the seat's key and
  knows nothing about which grant the firing wore, so the lookup is by
  the promoted `seat` column and state — one query, newest first.

  WHICH of the seat's open sittings is the reading. `harness_session`
  is `:maybe`, so it can never be a promoted column and never a
  filter (the kind's own recorded deviation about `model` is the same
  wall); the pairing is therefore done in code over one page:

    1. the newest open sitting stamped with the reported session — the
       run that is ending, named by its own id;
    2. failing that, the newest open sitting carrying NO stamp — a
       wake nobody's hook will ever name, which is the one an unnamed
       report is most likely about;
    3. failing that, the newest open sitting.

  A report with no session id starts at 2, which is why a stamped
  sitting is not closed by somebody else's report while its own run
  is still going."
  ([eng seat-id] (open-sitting-for-seat eng seat-id nil))
  ([eng seat-id harness-session]
   (when (and seat-id (get (inv/resources eng) :sitting))
     (let [now (now-of eng)
           ;; open means a live lease (docs/spec-leases.md §3)
           rows (filterv #(lease-live? % now)
                         (store/with-tx (:storage eng)
                           (fn [tx]
                             (store/query-rows (:storage eng) tx :sitting
                                               {:seat (str seat-id) :state :open}
                                               {:limit open-sitting-page
                                                :newest-first true}))))
           stamp-of #(some-> (get-in % [:data :harness_session]) str not-empty)
           wanted (some-> harness-session str not-empty)]
       (or (when wanted (first (filter #(= wanted (stamp-of %)) rows)))
           (first (remove stamp-of rows))
           (first rows))))))

(defn earlier-sittings
  "The documents of the sittings in `rows` — one seat's newest page —
  that started before the sitting `id`, whose document is `data`."
  [rows id data]
  (let [^java.time.Instant mine (->instant (:started_at data))]
    (into []
          (comp (remove #(= (str id) (str (:id %))))
                (filter (fn [r]
                          (when-some [^java.time.Instant theirs
                                      (->instant (get-in r [:data :started_at]))]
                            (and mine (.isBefore theirs mine)))))
                (map #(assoc (:data %) :id (:id %))))
          rows)))

(defn with-handed
  "`data`, the document of the sitting `id`, and `earlier`
  (`earlier-sittings`), each given `:handed` for `rewalked?`: the rows
  this sitting's leases held, and of those the ones each earlier
  sitting held a lease on too. A closed sitting's ended leases are the
  record of what it was handed. With no `find'` nothing was.
  → [data earlier]."
  [find' id data earlier]
  (let [mine (if find' (leased-row-ids find' {:holder_id (str id)}) [])
        holders (into {}
                      (map (fn [row]
                             [row (into #{}
                                        (keep #(some-> (get-in % [:data :holder_id]) str))
                                        (find' :lease {:holder_kind "sitting" :subject_id row}
                                               {:limit lease-record-page}))]))
                      mine)]
    [(assoc data :handed mine)
     (mapv (fn [e]
             (let [sid (str (:id e))]
               (assoc e :handed (filterv #(contains? (get holders %) sid) mine))))
           earlier)]))

(defn release-leases
  "Let go of every live `held` lease the sitting `row` holds, through
  the lease's own `release` in this write's transaction, under the
  leases actor, with `grace` seconds: the lease stays held until the
  grace runs out, and a grace of 0 frees its row at once. A ctx with
  no inner-write hook, or an engine that serves no lease, lets go of
  nothing. → the row, unchanged."
  [row ctx grace]
  (when-some [invoke' (:invoke ctx)]
    (when-some [find' (:find ctx)]
      (doseq [l (find' :lease {:holder_kind "sitting" :holder_id (str (:id row))
                               :state "held"}
                       {:limit 50})
              :when (leases/live? ctx l)]
        (invoke' :lease (:id l) :release
                 {:grace_seconds (min 86400 (max 0 (long grace)))}
                 {:as leases/leaser}))))
  row)

;; ── the walked kind's rows outside the grant (seat health 4) ─────────

(def ^:private hidden-scan-limit
  "The most rows of the walked kind one count reads. A count that
  reaches it says that many or more."
  200)

(defn- hidden-str
  "A state or a field's value as a filter spells it."
  [v]
  (if (keyword? v) (name v) (str v)))

(defn hidden-waiting
  "The rows of the kind this seat walks that wait where its grant does
  not reach: in the states its walk reads — the filter's own, or the
  kind's default, either of which may name several with commas, or
  any state that is not terminal — and outside the
  other fields of its scope entry's filter (`walk-filter`). Read
  through the store and not through the seat's grant. A seat whose
  entry filters by no field hides none, and a judgment seat walks the
  judgment's queue and is not counted. Best effort.
  → {:count :state :plural :fields}, or nil when none waits."
  [eng tx seat-row]
  (try
    (let [kind (some-> (get-in seat-row [:data :walk]) str not-empty keyword)
          rdef (get (inv/resources eng) kind)
          flt (into {}
                    (map (fn [[f v]] [(name f) (hidden-str v)]))
                    (walk-filter seat-row))
          fields (dissoc flt "state")]
      (when (and rdef (seq fields)
                 (nil? (some-> (get-in seat-row [:data :judgment]) str not-empty)))
        (let [state (or (not-empty (get flt "state"))
                        (some-> (get-in rdef [:default-filters :state])
                                hidden-str not-empty))
              states (when state
                       (into []
                             (comp (map str/trim) (remove str/blank?))
                             (str/split state #",")))
              terminal (into #{} (map hidden-str) (:terminal rdef))
              waits? (fn [row]
                       (or (some? state)
                           (not (contains? terminal (hidden-str (:state row))))))
              admitted? (fn [row]
                          (every? (fn [[f v]]
                                    (= v (some-> (get-in row [:data (keyword f)])
                                                 hidden-str)))
                                  fields))
              rows-in (fn [where]
                        (store/query-rows (:storage eng) tx kind where
                                          {:limit hidden-scan-limit}))
              n (count (filter #(and (waits? %) (not (admitted? %)))
                               (if (seq states)
                                 (mapcat #(rows-in {:state (keyword %)}) states)
                                 (rows-in {}))))]
          (when (pos? n)
            {:count n
             :state (some->> (seq states) (str/join " or "))
             :plural (:plural rdef)
             :fields (vec (sort (keys fields)))}))))
    (catch Exception _ nil)))

;; ── a sitting's lease end, and the backfill that gave one to each ────

(defn lease-end
  "`from` plus the lease length of `seat-row`: a lease's end at birth
  (`started_at`) and at each renewal (now). nil with no clock."
  [from seat-row]
  (when-some [^java.time.Instant at (->instant from)]
    (str (.plusSeconds at (lease-seconds seat-row)))))

(defn seat-of-sitting
  "The seat row of this sitting, read inside `tx`, or nil."
  [eng tx row]
  (some->> (get-in row [:data :seat]) str not-empty
           (#(store/load-row (:storage eng) tx :seat % {}))))

(defn sitting-transitions
  "The transitions made under a sitting (ticket 39b2c934): the log rows
  whose actor carries the sitting's grant and whose `at` falls in its
  window, `started_at` to `ended_at` (to now, while it is open). The
  log has no sitting column; the grant and the window are the link.
  → a vector, oldest first, or nil for an unknown sitting."
  ([eng sitting-id] (sitting-transitions eng sitting-id {}))
  ([eng sitting-id opts]
   (store/with-tx (:storage eng)
     (fn [tx]
       (when-some [row (store/load-row (:storage eng) tx :sitting
                                       (str sitting-id) {})]
         (let [data (:data row)]
           (when-some [grant (some-> (:grant data) str not-empty)]
             (store/transitions-under-grant
              (:storage eng) tx grant
              (->instant (:started_at data))
              (->instant (:ended_at data))
              opts))))))))

(defn backfill-expiries!
  "Leases build ticket 3a's backfill (docs/spec-leases.md §3): every
  open sitting born before the lease, with no `expires_at`, is given
  one, the latest of its `started_at`, `last_call_at` and `tallied_at`
  plus its seat's `sitting_idle_seconds`, so the sittings open across
  the deploy keep working and the counts that now read a live lease
  find them. A stamped row is never stamped again, so a second pass
  writes nothing. A MAINTENANCE write, `backfill-domain-ids!`'s
  spelling. → the number stamped."
  [eng]
  (if (get (inv/resources eng) :sitting)
    (let [st (:storage eng)
          due (store/with-tx st
                (fn [tx]
                  (->> (store/query-rows st tx :sitting {:state :open} {:all true})
                       (remove #(some? (get-in % [:data :expires_at])))
                       (mapv (comp str :id)))))]
      (count
       (filterv
        (fn [id]
          (store/with-tx st
            (fn [tx]
              (when-some [row (store/load-row st tx :sitting id
                                              {:for-update true})]
                (let [data (:data row)
                      seen (->> [:started_at :last_call_at :tallied_at]
                                (keep #(->instant (get data %)))
                                sort
                                last)]
                  (when (and (= :open (:state row))
                             (nil? (:expires_at data))
                             seen)
                    (store/update-data! st tx :sitting id
                                        (assoc data :expires_at
                                               (lease-end seen (seat-of-sitting eng tx row)))
                                        nil)
                    true))))))
        due)))
    0))
