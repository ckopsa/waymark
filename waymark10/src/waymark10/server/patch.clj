(ns waymark10.server.patch
  "An edit door takes a patch (ticket 5120da15). An action that declares
  `:edit {:prefill [fields]}` already says which of its input fields the
  row answers; this namespace makes that declaration the caller's
  saving too, once, in the invoke path and not per kind:

  - OMITTED MEANS UNCHANGED. A prefill field the input leaves out takes
    the row's stored value before validation, so the guards and the
    handler read a whole input, and a whole input works as ever. A
    field the row never stored stays absent, so its schema default (or
    its requiredness) judges it, as at any door.
  - LIST DELTAS. A prefill field whose schema is a list may arrive as
    {\"add\": [...], \"remove\": [...]}: remove first, by whole value,
    then add appended. A remove naming an entry the list does not hold
    refuses `patch-miss`, so a delta computed against a stale read
    never silently does nothing. One rule for every kind: no keyed or
    partial match of map entries.
  - MAPS MERGE. A prefill field whose schema is a map takes the given
    keys over the stored ones, nested maps the same way.
  - THE FENCE NAMES THE FIELDS. A caller that presents the version it
    read (If-Match, `if_version`) and is stale refuses `stale`, naming
    the prefill fields that moved since, read from the retained
    document of the write that left that version where the kind
    retains one (:retain {:data true}).

  Pure over (action, stored data, body): no storage of its own."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.problems :as p]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn prefill
  "The fields an edit door's input is about, or nil for a door that
  declares none."
  [defn']
  (seq (get-in defn' [:edit :prefill])))

(defn- canon
  "A value as a comparison reads it: keys and keyword values as names,
  so a stored entry and a typed one agree whichever reader spelled
  them."
  [v]
  (cond
    (keyword? v) (name v)
    (map? v) (into {} (map (fn [[k x]] [(if (keyword? k) (name k) (str k))
                                        (canon x)]))
                   v)
    (sequential? v) (mapv canon v)
    (set? v) (into #{} (map canon) v)
    :else v))

(defn- shape
  ":list, :map or nil: how a patch may name this input field."
  [form k]
  (let [s (schema/field-schema form k)
        head (when (vector? s) (first s))]
    (cond
      (#{:vector :sequential :set} head) :list
      (#{:map :map-of} head) :map
      :else nil)))

(defn- delta?
  [v]
  (and (map? v)
       (seq v)
       (every? #{"add" "remove"} (map name (keys v)))))

(defn- patch-miss [action field entry]
  (p/problem :patch-miss 409 "Patch miss"
             {:detail (str "remove names " (wire/write-json (canon entry))
                           ", which " (name field) " does not hold. Re-read "
                           "the row and patch against what it holds.")
              :action-attempted action
              :field (name field)
              :entry entry}))

(defn- listed [action field v]
  (cond
    (nil? v) []
    (sequential? v) (vec v)
    :else (throw (p/schema-invalid action
                                   {field ["add and remove take lists"]}))))

(defn- resolve-list
  "The stored list with a delta applied: every remove first, each by
  whole value and each refusing when absent, then the adds appended."
  [action field stored d]
  (let [pick #(or (get d %) (get d (name %)))
        kept (reduce (fn [xs entry]
                       (let [c (canon entry)]
                         (if (some #(= c (canon %)) xs)
                           (filterv #(not= c (canon %)) xs)
                           (throw (patch-miss action field entry)))))
                     (vec (or stored []))
                     (listed action field (pick :remove)))]
    (into kept (listed action field (pick :add)))))

(defn- merged [stored given]
  (if (and (map? stored) (map? given))
    (merge-with merged stored given)
    given))

(defn resolve-input
  "The whole input an edit door's patch means, against the row's stored
  (wire-shaped) data. A door with no prefill, or a body that names
  every field whole, comes back as given."
  [defn' stored body]
  (if-not (map? body)
    body
    (let [form (:input defn')
          action (:name defn')
          stored (or stored {})]
      (reduce (fn [b f]
                (let [s (get stored f)]
                  (if-not (contains? b f)
                    (cond-> b (some? s) (assoc f s))
                    (let [v (get b f)]
                      (case (shape form f)
                        :list (cond-> b
                                (delta? v) (assoc f (resolve-list action f s v)))
                        :map (cond-> b
                               (and (map? v) (map? s)) (assoc f (merged s v)))
                        b)))))
              body
              (prefill defn')))))

(defn changes
  "What an edit would change, for the person who decides a held call:
  of the `named` fields, each whose resolved value differs from the
  stored one, a list as {:added :removed} and anything else as
  {:before :after}. A field named at its stored value is no change."
  [stored resolved named]
  (into {}
        (keep (fn [f]
                (let [before (get stored f)
                      after (get resolved f)]
                  (when (not= (canon before) (canon after))
                    [f (if (and (sequential? after)
                                (or (nil? before) (sequential? before)))
                         (let [b (into #{} (map canon) before)
                               a (into #{} (map canon) after)]
                           {:added (filterv #(not (b (canon %))) after)
                            :removed (filterv #(not (a (canon %))) before)})
                         {:before before :after after})]))))
        named))

(defn read-version
  "The version an If-Match names, `W/\"kind-id-vN\"` or a bare N, or
  nil."
  [if-match]
  (some->> (some-> if-match str str/trim)
           (re-find #"^(?:.*-v)?(\d+)\"?$")
           second
           parse-long))

(defn moved
  "The prefill fields that moved between the version a caller read and
  the row as it stands (`current`, wire-shaped), or nil when that
  cannot be said. The log is newest-last and each transition advanced
  the version by one, so the write that left `read-v` is the one
  `row-v - read-v` from the end, and its retained document is what the
  caller read. A kind that retains none names no fields."
  [defn' transitions read-v row-v current]
  (let [ts (vec transitions)
        i (when (and read-v row-v (< read-v row-v))
            (- (count ts) (- row-v read-v) 1))]
    (when (and i (< -1 i (count ts)))
      (when-some [before (:after (nth ts i))]
        (vec (sort (keep (fn [f]
                           (when (not= (canon (get before f))
                                       (canon (get current f)))
                             (name f)))
                         (prefill defn'))))))))

(defn stale
  "The refusal of an edit whose caller read an older version."
  [action resource moved]
  (p/problem :stale 412 "Stale"
             {:detail (str "The row changed"
                           (when (seq moved)
                             (str " in " (str/join ", " moved)))
                           " since the version you read. Re-read it and "
                           "patch against what it holds.")
              :action-attempted action
              :resource resource
              :moved (vec moved)}))

(defn door-js
  "An edit door's advertised input: every prefill field optional, and
  prose that says so, so an agent learns the patch from the envelope
  and from waymark_schema alike."
  [js defn']
  (let [props (:properties js)
        prop (fn [f] (get props f (get props (name f))))
        fields (filter prop (prefill defn'))]
    (if (empty? fields)
      js
      (let [names (into #{} (map name) fields)
            required (filterv #(not (contains? names (name %))) (:required js))
            lists (filter #(= "array" (:type (prop %))) fields)
            prose (str "An edit: a field you leave out keeps its stored value, "
                       "so name only what changes ("
                       (str/join ", " (map name fields)) " are optional here)."
                       (when (seq lists)
                         (str " A list field (" (str/join ", " (map name lists))
                              ") also takes {\"add\": [...], \"remove\": [...]}: "
                              "remove matches whole entries and runs first, and "
                              "an entry the list does not hold refuses patch-miss."))
                       " Pass the version you read as if_version (If-Match over "
                       "HTTP) and a stale write refuses, naming what moved.")]
        (cond-> (assoc js :description (if-some [d (:description js)]
                                         (str d "\n\n" prose)
                                         prose))
          (seq required) (assoc :required required)
          (empty? required) (dissoc :required))))))
