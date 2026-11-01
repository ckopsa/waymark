(ns waymark10.server.waiting-on
  "What ONE row waits on (ticket 86af9d88): the read-only `waiting_on`
  block of a row's envelope. The cracks audit says which STATES work can
  stop in; this says, for the row in hand, who moves it next.

  since     when the row entered its state and by which door, read from
            its log (the last write when the log does not say), and the
            state's expected age
            (`live-cracks/expected-age`) when it has one — or, where
            that age is a map, its `due` (`live-cracks/deadline` over
            the :calendar hook) and no nominal seconds
  next      every way out of the state (`cracks/row-doors`): the door,
            the active seats that hold it for THIS row, what wakes each
            of them, and `held_by`: seat, person, source or nobody
  held_by   the row's own hold fields that are set (`hold-fields`)
  lease     the row's live lease, when it has one (`leases-in`, leases
            6): who holds it now, beside `holder`, who should move it
  verdict   one line for a person

  It is computed from the kind's law and the seat rows and names no
  kind. `block` is pure over (declaration, seat rows, row), so a test
  judges it with no engine. `for-row` reads the seats and the row's log
  and never throws: a row is served without the block before it is not
  served.

  The hold fields and the values the verdict names are read from the
  data the caller was SHOWN, so the block says nothing of a field the
  caller's grant hides.

  The block is OUTSIDE what the envelope's ETag covers (ticket
  9ff24b82): the ETag is the row's version, and the verdict's age words
  and the seats `next` names move without a write. `since` serves the
  instant and the expected age, never an age, so it holds still under
  one version; the reader subtracts."
  (:require [clojure.string :as str]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.invoke :as inv]
            [waymark10.server.leases :as leases]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant OffsetDateTime)
           (java.util Date)))

(set! *warn-on-reflection* true)

(def hold-fields
  "The fields that say a row is held, by NAME: a kind that carries one
  of them set says why its row does not move."
  ["blocked_by" "merge_waits" "held_reason" "line_why" "shelved_because"])

(def ^:private person-holds
  "The hold fields whose value `person` says a row is kept for a
  person's tap, by NAME, each with the field that says why (ticket
  6644eeeb): a change whose touched paths its repository keeps for a
  person carries line_why person and its line_reason."
  {"line_why" "line_reason"})

(def ^:private seat-page
  "The most active seats one read takes."
  500)

(def ^:private log-page
  "The most transitions, newest first, read to find when the row entered
  its state. A row that has taken more self-loops than this since says
  its last write."
  50)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 waiting on: " parts))))

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- seat-name [seat]
  (or (some-> (get-in seat [:data :name]) str not-empty) (str (:id seat))))

(defn- fget
  "A map's value for a key, whichever way the map spells the key."
  [m k]
  (when (map? m)
    (let [k (keyword (text k))]
      (if (contains? m k) (get m k) (get m (name k))))))

(defn- inst
  "An instant, however the row spells it. Unparsable is nil."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (instance? Date v) (.toInstant ^Date v)
    (instance? OffsetDateTime v) (.toInstant ^OffsetDateTime v)
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn- span
  "Seconds as a person says them: under 2 min, 12 min, 3 h, 2 d. No
  finer: two reads of one row a moment apart answer the same words."
  [seconds]
  (let [s (long seconds)]
    (cond
      (< s 120) "under 2 min"
      (< s 7200) (str (quot s 60) " min")
      (< s 172800) (str (quot s 3600) " h")
      :else (str (quot s 86400) " d"))))

(defn- set-value?
  "Does a hold field say something? Not nil, false, blank or empty."
  [v]
  (cond
    (nil? v) false
    (false? v) false
    (string? v) (not (str/blank? v))
    (coll? v) (boolean (seq v))
    :else true))

(defn- held-by
  "The hold fields this data carries set, as {field value}."
  [data]
  (into (sorted-map)
        (for [f hold-fields
              :let [v (fget data f)]
              :when (set-value? v)]
          [f v])))

(defn- kept-for
  "Why this data says its row is kept for a person's tap, or nil."
  [data]
  (some (fn [[f why]]
          (when (= "person" (text (fget data f)))
            (let [r (fget data why)]
              (when (set-value? r) (text r)))))
        person-holds))

(defn- entered
  "The transition that put the row into `state`, from its log newest
  first: the first one that arrived there from another state, with only
  self-loops after it. nil when the log read does not reach it."
  [log state]
  (let [s (text state)]
    (some (fn [t]
            (when (not= s (some-> (:from-state t) text)) t))
          (take-while #(= s (some-> (:to-state %) text)) log))))

(defn- some-of
  "The first three names, and how many more."
  [names]
  (let [[shown more] (split-at 3 names)]
    (str (str/join ", " shown)
         (when (seq more) (str " and " (count more) " more")))))

(defn- holders
  "The seats the doors name, each one time in the order met, with every
  door it holds."
  [doors]
  (let [pairs (for [d doors, s (:seats d)] [(:door d) s])
        holds (reduce (fn [acc [door s]]
                        (update acc (:seat s) (fnil conj []) door))
                      {} pairs)]
    (for [n (distinct (map (comp :seat second) pairs))]
      (assoc (some (fn [[_ s]] (when (= n (:seat s)) s)) pairs)
             :holds (get holds n)))))

(defn- choose
  "Who holds the row, as the verdict names it (ticket ca68c559): a seat
  a model sits in first, a woken one before one on a cadence; then a
  source; then a person; then nobody. Not the first door of `next`:
  many of those are held by nobody while a later one is held.

  A seat counts only when its walk would hand it this row and a wake or
  its cadence brings it to the row (ticket af41e6bf): `walking` is the
  names of the seats whose walk admits the row, or that name no walk to
  narrow them. `:strays` says a seat a
  model sits in holds a door out and none of them walks the row."
  [doors walking]
  (let [hs (holders doors)
        models (remove :person hs)
        walkers (filter #(and (contains? walking (:seat %))
                              (or (seq (:woken_by %)) (:cadence_seconds %)))
                        models)]
    {:pick (or (first (filter (comp seq :woken_by) walkers))
               (first walkers))
     :strays (boolean (and (seq models) (empty? walkers)))
     :person (first (filter :person hs))
     :source (first (filter #(= "source" (:held_by %)) doors))}))

(defn debtor
  "Who owes a promise's remedy (docs/spec-promise.md R-P.5.1): the
  holder `choose` picks among the seats that hold `door` only (or any of
  them, when `door` is a list: one debtor for a remedy of several doors), read off
  a `block`'s :next — a seat a model sits in first, then a person; nil
  when nobody holds it. Every seat that holds the door counts as
  walking: the promise names the door, so it names the row. A model
  seat no wake or cadence brings to the row still owes it before a
  person does: the promise's wake is what brings it."
  [block door]
  (let [names (into #{} (map text) (if (sequential? door) door [door]))
        doors (filter #(contains? names (text (:door %))) (:next block))
        {:keys [pick person]} (choose doors (into #{} (comp (mapcat :seats) (map :seat)) doors))]
    (or pick (first (remove :person (holders doors))) person)))

(defn- addressee-of
  "The member or team a held call's door is addressed to ((hold … :by),
  spec-teams §5), as the block's holder {type name id}, or nil: name is
  the call's display name, id the member or team id it is addressed to."
  [data]
  (let [door (fget data :door)
        n (some-> (fget door :for_name) text not-empty)
        m (some-> (fget door :for_member) text not-empty)
        t (some-> (fget door :for_team) text not-empty)]
    (cond
      m {:type "member" :name (or n m) :id m}
      t {:type "team" :name (or n t) :id t})))

(defn- owner-of
  "The member the row waits on in `state` by the kind's :holder-field
  ({state field}, docs/spec-amendment.md R-A.2.6): that field's value,
  as the block's holder {type name}, or nil."
  [rdef state data]
  (when-some [f (get (:holder-field rdef) (keyword state))]
    (when-some [m (some-> (fget data f) text not-empty)]
      {:type "member" :name m})))

(defn- holder-of
  "The choice as the block carries it: {type name}, type one of seat,
  source, person or nobody; nil for a state a row rests in. An
  addressed held call names its member or team instead (addressee-of)."
  [doors walking]
  (when doors
    (let [{:keys [pick person source]} (choose doors walking)]
      (cond
        pick {:type "seat" :name (:seat pick)}
        source {:type "source" :name (:source source)}
        person {:type "person" :name (:seat person)}
        :else {:type "nobody"}))))

(defn- verdict
  "The one line, naming the holder `choose` picks."
  [kind state doors walking for-words age-words held kept]
  (let [{:keys [pick person source strays]} (choose doors walking)
        holds (if strays "walks this row to" "holds")]
    (str
     (cond
       (nil? doors)
       (str "nothing: " state " is where a " kind " row rests")

       pick
       (str "waiting on " (:seat pick) " (holds " (some-of (:holds pick)) ", "
            (cond
              (seq (:woken_by pick)) (str "woken by " (str/join ", " (:woken_by pick)))
              (:cadence_seconds pick) (str "on its " (span (:cadence_seconds pick))
                                           " cadence")
              :else "nothing wakes it")
            ")")

       source
       (str "waiting on the " (:source source) " (" (:door source) ")")

       person
       (str "a person: no seat a model sits in " holds " a door out of " state
            for-words "; " (:seat person) " holds " (some-of (:holds person)))

       :else
       (str "nobody: no active seat " holds " a door out of " state for-words))
     (when doors age-words)
     (when (seq held) (str "; held by " (str/join ", " (keys held))))
     (when kept (str "; waits on a person's tap: " kept)))))

(defn- unmet-words
  "The first item not ok of a :checklist computed field whose states
  hold the row's (ticket f1c6a494), as `; waits on <item>: <says>`, or
  nil. Read from the data the caller was shown, like the rest."
  [rdef state data]
  (first
   (for [[f c] (sort-by key (:computed rdef))
         :when (contains? (set (map text (:checklist c))) state)
         :let [item (first (remove #(true? (fget % :ok)) (fget data f)))]
         :when item]
     (str "; waits on " (text (fget item :item))
          (when-some [s (some-> (fget item :says) text not-empty)]
            (str ": " s))))))

(defn- walk-led?
  "Does this seat name a walk or a judgment that says which rows it is
  handed?"
  [seat]
  (boolean (or (some-> (get-in seat [:data :walk]) text not-empty)
               (some-> (get-in seat [:data :judgment]) str not-empty)
               ;; a promise walk says it too: the breaches it keeps
               (true? (get-in seat [:data :walk_promises])))))

(defn block
  "The `waiting_on` block of one row. `seat-rows` is the ACTIVE seats,
  as stored; `by-kind` is every kind the engine serves, by name. `now`
  is the instant of the read, `entered` the transition that put the row
  into its state when the log said (`:at`, `:action`), `data` the
  row's data as the caller is shown it, and `tickets` and `judgments`
  what `walk-reads` read for it, and `calendar` the :calendar hook a
  map age reads, and `promised` the default promises that stand
  (`live-cracks/default-promises`): one's `within` is the expected
  age (R-P.8.3). `scheduled` is the ids of the seats a schedule fires:
  a seat outside it keeps no cadence here (ticket af6ea33f), and nil
  counts every stated cadence."
  [rdef seat-rows by-kind row {:keys [now entered data tickets judgments calendar scheduled leases promised breaches]}]
  (let [lease (get leases [(text (:kind rdef)) (str (:id row))])
        seat-rows (cracks/fired-cadences seat-rows scheduled)
        kind (text (:kind rdef))
        state (text (:state row))
        doors (some->> (cracks/row-doors rdef seat-rows by-kind row) vec)
        ^Instant at (or (inst (:at entered)) (inst (:updated-at row)))
        ^Instant now (inst now)
        secs (when (and at now)
               (max 0 (.getSeconds (Duration/between at now))))
        dated (map? (first (live-cracks/age-entry rdef state)))
        within (:within (get promised (live-cracks/default-key kind state)))
        expected (when (and doors (not dated))
                   (if (number? within) (long within) (live-cracks/expected-age rdef state)))
        ^Instant due (when (and doors dated at now)
                       (live-cracks/deadline (first (live-cracks/age-entry rdef state))
                                             row at now calendar))
        named (for [f (cracks/walked-fields rdef seat-rows)
                    :let [v (fget data f)]
                    :when (set-value? v)]
                (str f " " (if (coll? v) (str/join ", " (map text v)) (text v))))
        for-words (when (seq named) (str " for " (str/join ", " named)))
        age-words (when secs
                    (str ", " (span secs)
                         (cond
                           due (str (if (.isBefore due now) ", past its deadline " ", due ")
                                    due)
                           expected (str " of " (span expected)))))
        held (held-by data)
        kept (kept-for data)
        reads {:tickets tickets :judgments judgments :breaches breaches}
        ;; a seat that names no walk and no judgment is narrowed by
        ;; neither, so its cadence alone may bring it to any row
        walking (into #{}
                      (comp (filter #(or (not (walk-led? %))
                                         (live-cracks/walk-admits? by-kind reads % rdef row)))
                            (map seat-name))
                      seat-rows)
        addressed (when doors (or (addressee-of data) (owner-of rdef state data)))
        holder (or addressed (holder-of doors walking))]
    (cond-> {:state state
             :since (cond-> {}
                      at (assoc :at (str at))
                      (:action entered) (assoc :by (text (:action entered)))
                      expected (assoc :expected_seconds expected)
                      due (assoc :due (str due)))
             :next (or doors [])
             :verdict (str (if addressed
                             (str "waiting on " (:name addressed) age-words)
                             (verdict kind state doors walking for-words age-words held kept))
                           (unmet-words rdef state data))}
      holder (assoc :holder holder)
      (seq held) (assoc :held_by held)
      lease (assoc :lease lease)
      kept (assoc :note (str "waits on a person's tap: " kept)))))

(def ^:private lease-page
  "The most held leases one read of many rows takes."
  2000)

(defn- lease-end
  "The end a lease is read on: its own `expires_at`, or, for one that
  follows its holder, the holding sitting's (docs/spec-leases.md §5)."
  [st tx lease]
  (or (some-> (get-in lease [:data :expires_at]) str not-empty)
      (when (= "sitting" (str (get-in lease [:data :holder_kind])))
        (some-> (store/load-row st tx :sitting (str (get-in lease [:data :holder_id])) {})
                (get-in [:data :expires_at]) str not-empty))))

(defn leases-in
  "The live lease on each of the rows `ids` names, read in `tx` (leases
  6, docs/spec-leases.md §5): {[kind id] → {:lease :holder :seat
  :sitting :expires_at :half_left}}. `half_left` is `leases/half-left?`
  on `expires_at` over the lease's seat's length
  (`seats/lease-seconds`), the seat read from `seat-rows`. An engine
  that serves no lease answers {}."
  [eng tx seat-rows ids]
  (let [st (:storage eng)
        resources (inv/resources eng)
        wanted (into #{} (keep #(some-> % str not-empty)) ids)]
    (if (or (empty? wanted) (nil? (get resources :lease)))
      {}
      (let [now ((:now-fn eng))
            ctx {:now now
                 :rdef-of #(some->> % str not-empty keyword (get resources))
                 :read (fn [kind id] (store/load-row st tx (keyword (name kind)) (str id) {}))}
            held (store/query-rows st tx :lease
                                   (cond-> {:state :held}
                                     (= 1 (count wanted)) (assoc :subject_id (first wanted)))
                                   {:limit lease-page})
            seat-of (into {} (map (juxt #(str (:id %)) identity)) seat-rows)]
        (into {}
              (for [l held
                    :let [{:keys [subject_kind subject_id holder_kind holder_id seat]} (:data l)]
                    :when (and (contains? wanted (str subject_id)) (leases/live? ctx l))
                    :let [end (lease-end st tx l)]]
                [[(str subject_kind) (str subject_id)]
                 (cond-> {:lease (str (:id l))
                          :holder (str holder_kind ":" holder_id)
                          :seat (some-> seat str not-empty)
                          :expires_at end
                          :half_left (leases/half-left?
                                      now end
                                      (seats/lease-seconds (get seat-of (str seat))))}
                   (= "sitting" (str holder_kind)) (assoc :sitting (str holder_id)))]))))))

(defn- born-ticket-id
  "The id of the ticket a row was born from (`born_from` ticket:<id>),
  or nil."
  [row]
  (let [born (str (fget (:data row) :born_from))]
    (when (str/starts-with? born "ticket:")
      (subs born (count "ticket:")))))

(defn walk-reads
  "What `block` reads, in `tx`, to tell whose walk hands it a row:
  {:tickets id → row} for the tickets these rows were born from,
  {:judgments id → row} for the judgments these seats sit for, and
  :scheduled, the ids of the seats a schedule fires
  (`cracks/scheduled-in`), and :leases, the rows' live leases
  (`leases-in`), and :promised, the default promises that stand
  (`live-cracks/default-promises`), and :breaches, the open breaches of
  the enforced promises a promise walk is handed
  (`live-cracks/promise-breaches`)."
  [eng tx seat-rows rows]
  (let [st (:storage eng)
        resources (inv/resources eng)
        load (fn [kind ids]
               (if-some [rdef (get resources kind)]
                 (into {}
                       (keep (fn [id]
                               (when-some [r (store/load-row st tx kind id {})]
                                 [id (inv/decode-row rdef r)])))
                       (distinct ids))
                 {}))]
    {:tickets (load :ticket (keep born-ticket-id rows))
     :judgments (load :judgment
                      (keep #(some-> (get-in % [:data :judgment]) str not-empty)
                            seat-rows))
     :scheduled (cracks/scheduled-in eng tx)
     :leases (leases-in eng tx seat-rows (map :id rows))
     :promised (live-cracks/default-promises eng tx)
     :breaches (live-cracks/promise-breaches eng tx)}))

(defn for-row
  "The block for a row the engine serves, or nil for a read that
  failed. The seat kind is core's and always enrolled, so every engine
  serves one. `data` is the row's data as the envelope projected it
  for the caller."
  [eng rdef row data]
  (try
    (let [st (:storage eng)
          resources (inv/resources eng)
          [seat-rows log reads]
          (store/with-tx st
            (fn [tx]
              (let [seat-rows (cracks/fired-in eng tx
                                               (store/query-rows st tx :seat {:state :active}
                                                                 {:limit seat-page}))]
                [seat-rows
                 (store/transitions st tx
                                    {:kind (:kind rdef)
                                     :resource-id (str (:id row))}
                                    {:limit log-page :newest-first true})
                 (walk-reads eng tx seat-rows [row])])))]
      (block rdef seat-rows resources row
             (assoc reads
                    :now ((:now-fn eng))
                    :entered (entered log (:state row))
                    :data data
                    :calendar (:calendar (inv/render-hooks eng)))))
    (catch Exception e
      (warn! (text (:kind rdef)) " " (:id row) ": " (ex-message e))
      nil)))
