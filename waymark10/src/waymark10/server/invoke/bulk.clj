(ns waymark10.server.invoke.bulk
  "The bulk door's body readers, carved out of waymark10.server.invoke:
  the three body shapes (`ids`, `items`, `where`) read into ONE item
  list, the `on_error` mode (continue, stop, atomic), a rehearsed
  item's verdict and the marker an over-threshold call defers with.
  `invoke/bulk!` runs the items; nothing here reads a store, a
  collection or a grant."
  (:require [clojure.string :as str]
            [waymark10.server.problems :as p]))

(def ^:private on-error-modes
  "The bulk door's `on_error` vocabulary: continue is the partial-
  success report, atomic is one transaction that any refusal rolls
  back, stop halts at the first refusal and reports what ran and
  what did not."
  #{"continue" "stop" "atomic"})

(defn on-error-mode
  "The mode a bulk call runs under: the declaration's (atomic when
  `:bulk {:atomic true}`, else continue) unless the body asks — and
  the body may only TIGHTEN. A declared-atomic action is a promise
  the collection made; a caller asking it to continue past a refusal
  is asking the declaration to be somebody else's."
  [action-name spec body]
  (let [given (:on_error body)
        declared (if (:atomic spec) "atomic" "continue")]
    (cond
      (nil? given) declared
      (not (contains? on-error-modes given))
      (throw (p/schema-invalid action-name
                               {:on_error ["one of continue, stop, atomic"]}))
      (and (:atomic spec) (not= "atomic" given))
      (throw (p/schema-invalid
              action-name
              {:on_error [(str (name action-name)
                               " is declared atomic; on_error can only be atomic")]}))
      :else given)))

(defn- item-errors
  "One `items` entry's contract violations, sentences prefixed with
  its index — id a non-blank string, input a map when present,
  acknowledge an array of guard names when present, nothing else."
  [i it]
  (let [at (str "item " i ": ")]
    (cond-> []
      (not (and (string? (:id it)) (not (str/blank? (:id it)))))
      (conj (str at "id must be a non-blank string"))
      (and (contains? it :input) (some? (:input it)) (not (map? (:input it))))
      (conj (str at "input must be an object"))
      (and (contains? it :acknowledge) (some? (:acknowledge it))
           (not (and (vector? (:acknowledge it))
                     (every? string? (:acknowledge it)))))
      (conj (str at "acknowledge must be an array of guard names"))
      (seq (dissoc it :id :input :acknowledge))
      (conj (str at "unexpected field "
                 (str/join ", " (map name (keys (dissoc it :id :input :acknowledge)))))))))

(defn bulk-items
  "Both bulk body shapes as ONE item list — {:id :input :acknowledged}
  per row, validated (every violation is the 422 an input field
  draws, keyed on the shape's own field).

  `{:ids […] …input}` is one input over N rows, the phase-7 shape:
  every item carries the same input and the call-level acknowledged
  set (the Waymark-Acknowledge header — one input, one reading, one
  acknowledgement).

  `{:items [{:id :input? :acknowledge?}]}` is N rows each with its
  own input (waymark-pywy.4). Acknowledgement is PER ITEM here, and
  the header is refused rather than spread: a blanket acknowledgement
  over rows whose warnings the caller has not read one by one is a
  guard-override device, and the door will not be one."
  [action-name body acknowledged]
  (let [body (or body {})
        ids (:ids body)
        items (:items body)]
    (cond
      (and (contains? body :ids) (contains? body :items))
      (throw (p/schema-invalid action-name
                               {:items ["one of ids or items, not both"]}))

      (contains? body :items)
      (do
        (when-not (and (vector? items) (seq items) (every? map? items))
          (throw (p/schema-invalid
                  action-name
                  {:items ["required, non-empty array of {id, input?, acknowledge?} objects"]})))
        (when-some [extras (seq (dissoc body :items :on_error))]
          (throw (p/schema-invalid
                  action-name
                  (into {} (map (fn [[k _]] [k ["unexpected field — each item carries its own input"]]))
                        extras))))
        (when-some [errors (seq (into [] (comp (map-indexed item-errors) cat) items))]
          (throw (p/schema-invalid action-name {:items (vec errors)})))
        (when (seq acknowledged)
          (throw (p/problem
                  :acknowledge-per-item 422 "Acknowledge is per item"
                  {:detail (str "The items shape acknowledges per item: name "
                                "each item's warned guards in its own "
                                "`acknowledge` array. A Waymark-Acknowledge "
                                "header would spread one reading over every "
                                "row, and no row was read that way.")
                   :action-attempted action-name
                   :acknowledge {:field "items[].acknowledge"
                                 :names (mapv name acknowledged)}})))
        (mapv (fn [it]
                {:id (:id it)
                 :input (not-empty (:input it))
                 :acknowledged (into #{} (map keyword) (:acknowledge it))})
              items))

      :else
      (do
        (when-not (and (vector? ids) (seq ids) (every? string? ids))
          (throw (p/schema-invalid action-name
                                   {:ids ["required, non-empty array of ids"]})))
        (let [input (not-empty (dissoc body :ids :on_error))]
          (mapv (fn [id] {:id id :input input :acknowledged acknowledged})
                ids))))))

(defn where-items
  "The third bulk body shape (rain day C): `{:where {param value}
  …input}` — one input over every row the collection's own filter
  matches, acknowledged at call level as `ids` is. `resolve` is the
  router's seam (this namespace reads no collection): given the where
  map it answers the matched ids, in the collection's order, read under
  the caller's grant. They are resolved HERE, before anything runs or
  defers, so a job runs a fixed list. An empty match is an empty list,
  and the report says nothing ran."
  [action-name body acknowledged resolve]
  (let [where (:where body)]
    (cond
      (or (contains? body :ids) (contains? body :items))
      (throw (p/schema-invalid action-name
                               {:where ["one of ids, items or where, not two"]}))

      (not (and (map? where) (seq where)))
      (throw (p/schema-invalid
              action-name
              {:where ["required, a non-empty object of the collection's query parameters"]}))

      (nil? resolve)
      (throw (p/schema-invalid action-name
                               {:where ["this door takes ids or items"]}))

      :else
      (let [input (not-empty (dissoc body :where :on_error))]
        (mapv (fn [id] {:id id :input input :acknowledged acknowledged})
              (resolve where))))))

(defn item-verdict
  "One bulk item's rehearsal verdict — self-keyed, the guard's own
  sentence on a refusal, and on an ok the `would`: the state the row
  would leave and reach and the fields the input names. The
  rehearsal never fires the handler (§23's iron rule), so what a
  handler would write is exactly what it does not claim to know."
  [href id res]
  (cond-> {:self (href id) :verdict "ok"}
    (:warnings res) (assoc :warnings (:warnings res))
    (:to res) (assoc :would (cond-> {:from (name (:from res))
                                     :to (if (set? (:to res))
                                           (vec (sort (map name (:to res))))
                                           (name (:to res)))}
                              (seq (:fields res)) (assoc :fields (:fields res))))))

(defn deferred-marker
  "What an over-threshold call hands the job resource: the phase-9b
  marker ({:kind :action :ids :input}) — and, for the items shape,
  each row's own input and acknowledged names, index-aligned with
  `:ids`, so the worker's `bulk-item!` runs exactly the call that was
  deferred. The call's idempotency key rides too (waymark-pywy.5): a
  deferred call keeps no whole-call record, but its items are stamped
  with the key exactly as a synchronous call's are, so the log reads
  the same whichever side of the threshold a call fell."
  [kind action-name body items idempotency-key]
  (cond-> (if (contains? body :items)
            {:kind kind
             :action action-name
             :ids (mapv :id items)
             :inputs (mapv #(or (:input %) {}) items)
             :acknowledged (mapv #(mapv name (:acknowledged %)) items)}
            {:kind kind
             :action action-name
             :ids (mapv :id items)
             :input (:input (first items))})
    idempotency-key (assoc :idempotency-key idempotency-key)))
