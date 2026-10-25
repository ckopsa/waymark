(ns waymark10.server.expiry
  "How a row ENDS BY THE CLOCK, said once for every kind. A kind
  declares `:expires {:field :expires_at :door :expire}` — and `:in
  #{states}` when only some of the states its door leaves are the
  clock's — and `sweep!` walks every row of every such kind that is
  not terminal and whose field is before now through that door, as
  the engine, a batch of rows at a time.

  A row the sweep cannot date is left alone: a row it cannot expire is
  a row it must not expire by guessing. A door that refuses (the row
  moved since it was read) is a warning, never a throw, and the next
  pass reads the row as it stands then. The kind's own guards still
  judge every walk, so the sweep can do nothing the door would not."
  (:require [waymark10.server.clock :as clock]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def engine-actor
  "The system actor that walks every expiring kind's door."
  (t/principal {:id "waymark10-expiry" :type :system
                :display "Expiry"}))

(def default-period-ms
  "How often the sweep runs: every five minutes."
  300000)

(def ^:private batch
  "How many rows one read takes. A pass reads page after page, so no
  due row waits on the batch."
  500)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 expiry: " parts))))

(defn instant-of
  "An instant, however the row spells it: a stored string, or an
  Instant already. Unparsable is nil, which reads as never."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn expiring
  "Every served kind that declares `:expires`, as {kind {:field :door
  :states}}. :states is the declaration's `:in` when it spells one,
  else every state the door leaves, and never a terminal one."
  [eng]
  (into {}
        (for [[kind rdef] (inv/resources eng)
              :let [{:keys [field door in]} (:expires rdef)
                    from (get-in rdef [:actions door :from])]
              :when (and field door (coll? from))]
          [kind {:field field
                 :door door
                 :states (vec (remove (set (:terminal rdef)) (or in from)))
                 :withdraw (:withdraw (:expires rdef))}])))

(defn- rows-in
  "Every row of `kind` in `state`, read a batch at a time."
  [eng kind state]
  (let [st (:storage eng)]
    (loop [offset 0 acc []]
      (let [page (store/with-tx st
                   (fn [tx]
                     (vec (store/query-rows st tx kind {:state state}
                                            {:limit batch :offset offset}))))
            acc (into acc page)]
        (if (< (count page) batch)
          acc
          (recur (+ offset batch) acc))))))

(defn- due-ids
  "The ids of `kind`'s rows whose field is before `now`."
  [eng kind {:keys [field states]} ^Instant now]
  (let [rdef (get (inv/resources eng) kind)]
    (vec (for [state states
               raw (rows-in eng kind state)
               :let [row (inv/decode-row rdef raw)
                     at (instant-of (get-in row [:data field]))]
               :when (and at (.isBefore at now))]
           (str (:id row))))))

(defn- moved-ids
  "The ids of `kind`'s rows the world moved past, by the kind's own
  test: `:withdraw {:door :in #{states} :moved? (fn [eng row])}` inside
  `:expires` (held_call, ticket 50351c4c). A kind that spells none has
  none."
  [eng kind {:keys [in moved?]}]
  (if-not (and (ifn? moved?) (seq in))
    []
    (let [rdef (get (inv/resources eng) kind)]
      (vec (for [state in
                 raw (rows-in eng kind state)
                 :let [row (inv/decode-row rdef raw)]
                 :when (moved? eng row)]
             (str (:id row)))))))

(defn- walk!
  "Walk one row through its kind's expiry door, best effort."
  [eng kind id door]
  (try
    (inv/invoke! eng kind id door {} {:principal engine-actor})
    (catch Exception e
      (warn! (name kind) " " id " could not be moved by " (name door)
             " (" (ex-message e) ")")
      nil)))

(defn sweep!
  "One pass: every expiring kind's due rows, each walked through its
  kind's own door. → how many moved. `:kinds`, a set, narrows the pass
  to those kinds."
  ([eng] (sweep! eng {}))
  ([eng {:keys [kinds]}]
   (let [^Instant now ((:now-fn eng))]
     (reduce-kv
      (fn [n kind {:keys [door withdraw] :as spec}]
        (if (and kinds (not (contains? kinds kind)))
          n
          ;; the rows the world moved past first, so the clock's pass
          ;; reads them ended and does not walk them twice
          (let [n (reduce (fn [m id] (if (walk! eng kind id (:door withdraw)) (inc m) m))
                          n
                          (moved-ids eng kind withdraw))]
            (reduce (fn [m id] (if (walk! eng kind id door) (inc m) m))
                    n
                    (due-ids eng kind spec now)))))
      0
      (expiring eng)))))

(defn start!
  "Every `:period-ms`, `sweep!`. The first pass is one period after the
  start, so a boot writes nothing. Returns the handle `stop!` takes."
  [eng {:keys [period-ms] :or {period-ms default-period-ms}}]
  {:stop (clock/every! {:name "waymark10-expiry"
                        :period-ms period-ms
                        :f #(sweep! eng)})})

(defn stop! [{:keys [stop]}]
  (when stop (stop))
  nil)
