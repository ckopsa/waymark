(ns waymark10.server.amendment-revert
  "An amendment's revert (docs/spec-amendment.md §14 step 9; R-A.6.1-6.4).

  `revert` puts back exactly `before` through the target's own restate,
  overlaid on the target's current values as adopt builds its input, and
  moves the amendment to `reverted`: one transition on each row. For
  each patched field it reads the target now: a field still holding
  `after` is restored, a field already holding `before` is left as it
  is, and a field holding anything else was changed by another hand and
  is left and named under `revert.conflicts`. When nothing is left to
  restore, revert writes the amendment's move and no restate.

  The owner taps it at any time in `trial` or `settled`; the engine's
  `revert!` is keyed `amendment:<id>:revert`, so it runs once. It acts
  under the owner's principal, as adopt does: a seat's restate admits a
  person only (`a-person`), so the engine's own hand cannot write it. When the
  restore meets a `:hold` guard the engine writes nothing on the target,
  proposes one held call to the owner (\"revert as written\" or \"keep
  it\") and leaves the amendment in `trial`. A `walk_filter` amendment
  (R-A.10.2) is never restored by the engine, armed or not: `revert!`
  proposes that held call every time. This namespace does not require
  `amendments`, which declares these doors on its kind."
  (:require [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defhandler]]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(defn- seat?
  "A seat's own principal: an agent that acts for no person."
  [p]
  (and (= :agent (:type p)) (nil? (some-> (:acts-for p) str not-empty))))

(g/defguard the-owner-or-the-engine-reverts
  {:reads [:principal]
   :explain "Only the amendment's owner reverts or keeps it, by their own tap, or the engine on a `worse` verdict in `mode revert` (R-A.6.1)."
   :open "The member named as the amendment's owner taps it."}
  [row _inp ctx]
  (let [p (:principal ctx)
        owner (some-> (fget (:data row) :owner) text not-empty)]
    (if (or (= :system (:type p))
            (and owner (not (seat? p))
                 (= owner (some-> (or (:acts-for p) (:id p)) str))))
      (t/allow)
      (t/deny))))

;; ── a seat's walk filter (R-A.10.2) ──────────────────────────────────

(defn walk-entry-at
  "The index in seat `data`'s scope of the entry a `walk_filter` patches:
  the one on its walked kind that already filters (a kind carries one
  filtered entry), else the first on that kind; nil when there is none."
  [data]
  (when-some [walk (some-> (fget data :walk) text not-empty)]
    (let [scope (vec (fget data :scope))
          on (filterv #(= walk (text (fget (scope %) :kind))) (range (count scope)))]
      (or (first (filter #(seq (fget (scope %) :filter)) on)) (first on)))))

(defn walk-filter-of
  "The `walk_filter` seat `data` carries: that entry's filter, or nil."
  [data]
  (when-some [i (walk-entry-at data)]
    (not-empty (fget (nth (vec (fget data :scope)) i) :filter))))

(defn as-read
  "`data` with its `walk_filter` beside it, the field no row stores, as
  adopt's `before` and `after` and revert's plan read it."
  [data]
  (assoc data :walk_filter (walk-filter-of data)))

(defn restating
  "`patch` as the target's restate takes it: a `walk_filter` becomes the
  scope with that entry's filter set, or dropped for nil, on the walk the
  patch leaves. Every other field passes as it is."
  [data patch]
  (if-not (contains? patch :walk_filter)
    patch
    (let [f (:walk_filter patch)
          others (dissoc patch :walk_filter)
          d (merge data others)
          i (walk-entry-at d)]
      (cond-> others
        i (assoc :scope (update (vec (fget d :scope)) i
                                #(cond-> (dissoc % :filter "filter") (seq f) (assoc :filter f))))))))

(defn plan
  "R-A.6.2-6.3: what revert does with each patched field, given the
  amendment's `before` and `after` and the target's `current` data.
  → {:restore {field before-value} :conflicts [field] :unchanged [field]}."
  [before after current]
  (reduce (fn [acc k]
            (let [f (text k)
                  now (fget current f)]
              (cond
                (= now (fget before f)) (update acc :unchanged conj f)
                (= now (fget after f)) (assoc-in acc [:restore (keyword f)] (fget before f))
                :else (update acc :conflicts conj f))))
          {:restore {} :conflicts [] :unchanged []}
          (sort (map text (keys after)))))

(def ^:private acknowledged
  "The warnings the replay already showed the owner, as adopt takes them."
  #{:opens-no-crack :a-restate-says-what-it-drops})

(defhandler revert-amendment
  [row inp ctx]
  ;; R-A.6.2: the target's own door, under this principal, in this
  ;; transaction; its refusal is thrown through and nothing moves
  (let [data (:data row)
        changes (fget data :changes)
        kind (keyword (text (fget changes :kind)))
        id (text (fget changes :id))
        target ((:read ctx) kind id)
        {:keys [restore conflicts unchanged]} (plan (fget data :before) (fget data :after) (as-read (:data target)))]
    (when (seq restore)
      (let [whole (into {}
                        (keep (fn [f] (let [v (fget (:data target) f)]
                                        (when (some? v) [(keyword (text f)) v]))))
                        (fget (fget (fget data :replay) :target) :input))]
        ((:invoke ctx) kind id (keyword (text (fget changes :door))) (merge whole (restating (:data target) restore))
         {:acknowledged acknowledged
          :if-match (inv/etag kind id (:version target))})))
    (update row :data
            #(cond-> (assoc % :revert {:restored (sort (map name (keys restore)))
                                       :conflicts conflicts
                                       :unchanged unchanged})
               (some? (:trial inp)) (assoc :trial (:trial inp))
               (some? (:verdict inp)) (assoc :verdict (:verdict inp))))))

(defhandler keep-amendment
  ;; R-A.6.4's "keep it": the change stays as adopted
  [row _inp _ctx]
  row)

(def ^:private report-schema [:maybe [:map-of :keyword :any]])

(def revert-door
  {:from #{:trial :settled} :to :reverted
   :input [:map
           [:trial {:optional true} report-schema]
           [:verdict {:optional true}
            [:maybe [:enum "better" "same" "worse" "quiet" "confounded" "unjudged"]]]]
   :guards [the-owner-or-the-engine-reverts]
   :touches [{:kind :seat :action :restate} {:kind :promise :action :restate}]
   :safety {:idempotent false :reversible false :confirm false
            :one-way "The target's own restate puts back each field still holding `after`; a field another hand changed is left and named."}
   :handler revert-amendment
   :display {:label "Revert" :style :danger :order 4}})

(def keep-door
  {:from #{:trial} :to :settled
   :guards [the-owner-or-the-engine-reverts]
   :safety {:idempotent false :reversible false :confirm false
            :one-way "The change stays as adopted, and the amendment settles."}
   :handler keep-amendment
   :display {:label "Keep it" :order 5}})

;; ── the engine's revert (R-A.6.1, R-A.6.4) ───────────────────────────

(defn- waiting-hold
  "The held call already proposed for amendment `id`'s revert, or nil."
  [eng id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (first (filter #(let [door (fget (:data %) :door)]
                          (and (= "amendment" (text (fget door :kind)))
                               (= (str id) (text (fget door :id)))))
                       (store/search-rows st tx :held_call
                                          [{:target :state :op :in :values ["held"]}]
                                          {:order-by nil :desc nil :limit 1000})))))))

(defn- hold!
  [eng id owner refusal]
  (held/hold-door! eng {:kind :amendment :action :revert :id (str id) :body {}
                        :caller owner
                        :owner owner
                        :why (str "Reverting this amendment is held: " (:detail refusal))
                        :choices [{:door :revert :label "Revert as written"}
                                  {:door :keep :label "Keep it"}]}))

(defn- walk-filter-amendment? [row]
  (boolean (some #(= "walk_filter" (text %))
                 (keys (fget (fget (:data row) :changes) :patch)))))

(defn revert!
  "The engine's revert of amendment `id` with `input` (its `trial` and
  `verdict`), under its owner and keyed so it runs once. A restore a
  `:hold` guard holds writes nothing and answers {:held <held_call>},
  proposed once however often the sweep asks. A `walk_filter` amendment
  answers the same held call without trying: only the owner's tap
  narrows or widens what a seat sees back."
  ([eng id] (revert! eng id nil))
  ([eng id input]
   (let [row ((:read (inv/render-hooks eng)) :amendment (str id))
         owner (or (some-> (fget (:data row) :owner) text not-empty)
                   (throw (ex-info (str "amendment " id " names no owner to revert it under") {:id id})))]
     (if (and (walk-filter-amendment? row)
              (#{"trial" "settled"} (text (:state row))))
       {:held (or (waiting-hold eng id)
                  (hold! eng id owner {:detail "it changes a seat's walk filter, so only its owner's tap puts it back (R-A.10.2)."}))}
     (try
       (inv/invoke! eng :amendment (str id) :revert input
                    {:principal (t/principal {:id owner :type :human :display owner})
                     :idempotency-key (str "amendment:" id ":revert")})
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (and (= :guard-refused (:waymark10/problem d))
                    (holds/hold? (:guard d))
                    (contains? (inv/resources eng) :held_call))
             {:held (or (waiting-hold eng id) (hold! eng id owner d))}
             (throw e)))))))))
