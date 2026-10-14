(ns waymark10.server.in-flight
  "GET /api/-/in-flight (ticket d0407b84): every open row the caller's
  grant admits, with its `waiting_on`, grouped by who holds it. The
  answer to \"where are we at with X?\" in one read, and the opening
  page of every house: it names no kind.

  A row is IN FLIGHT when its kind has an expected age for some state
  (`live-cracks/expected-age`: the kind's own `:expected-age` or the
  default for the state's name) and the row is in a state that is not
  terminal. A state a row rests in (`waiting-on/block` says `nothing`)
  is left out: no one owes it a move.

  Per row: kind, id, summary, state, its `waiting_on` as the row read
  gives it, and

  holder            `seat:<name>`, `source:<name>`, `person` or
                    `nobody`: the holder the verdict names, as
                    `waiting_on.holder` carries it (ticket ca68c559)
  age_seconds       since the row's last write, as the live cracks pass
                    reads it, so what this calls overdue is what that
                    pass calls aged
  expected_seconds  the state's expected age, or nil
  overdue           age / expected, or nil with no expected age

  Groups run `person`, `nobody`, then each seat, then each source;
  inside a group the most overdue first.

  ONE PASS. The active seats and the rows of every open state are read
  in one transaction, and each row's block is `waiting-on/block`, which
  is pure; no row's log is read, so `since.by` is absent here where the
  row read carries it. `answer` is pure over the entries, so a test
  judges the grouping with no engine."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.waiting-on :as waiting-on])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def ^:private seat-page
  "The most active seats one read takes, as `waiting-on` takes them."
  500)

(def ^:private row-page
  "The most rows of one state one read takes."
  1000)

(def filter-keys
  "The filters the read takes, by query parameter name."
  [:domain :repo :kind :holder])

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- inst ^Instant [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn holder
  "Who holds a row: the holder its `waiting_on` block's verdict names
  (`waiting_on.holder`), as a group token. nobody only when the verdict
  finds no seat, source or person, as the cracks audit does."
  [w]
  (let [{:keys [type name]} (:holder w)]
    (case type
      "seat" (str "seat:" name)
      "source" (str "source:" name)
      "person" "person"
      "nobody")))

(defn open-states
  "The states of a kind whose rows are in flight: every state that is
  not terminal, when some such state has an expected age; else none."
  [rdef]
  (let [terminal (into #{} (map text) (:terminal rdef))
        states (remove #(contains? terminal (text %)) (:states rdef))]
    (when (some #(live-cracks/expected-age rdef %) states)
      (vec states))))

(defn entry
  "One row in flight, or nil for a row in a state it rests in. `data` is
  the row's data as the caller may read it, `summary` its line."
  [rdef seat-rows by-kind row {:keys [now data summary]}]
  (let [w (waiting-on/block rdef seat-rows by-kind row {:now now :data data})]
    (when-not (str/starts-with? (str (:verdict w)) "nothing:")
      (let [at (inst (get-in w [:since :at]))
            now (inst now)
            age (when (and at now)
                  (max 0 (.getSeconds (Duration/between at now))))
            expected (get-in w [:since :expected_seconds])
            ratio (when (and age (number? expected) (pos? expected))
                    (/ (Math/round (* 100.0 (/ (double age) expected))) 100.0))
            repo (some-> (or (get data :repo) (get data :repository)
                             (get data "repo") (get data "repository"))
                         str not-empty)]
        (cond-> {:kind (text (:kind rdef))
                 :id (str (:id row))
                 :summary summary
                 :state (text (:state row))
                 :holder (holder w)
                 :age_seconds age
                 :expected_seconds expected
                 :overdue ratio
                 :waiting_on w}
          (:domain rdef) (assoc :domain (text (:domain rdef)))
          repo (assoc :repo repo))))))

(defn- overdue? [e]
  (boolean (and (:age_seconds e) (number? (:expected_seconds e))
                (> (:age_seconds e) (:expected_seconds e)))))

(defn- group-rank [h]
  [(cond (= "person" h) 0
         (= "nobody" h) 1
         (str/starts-with? h "seat:") 2
         :else 3)
   h])

(defn answer
  "The document over the entries: filtered, grouped by holder, each
  group most overdue first, with totals per group and four counts."
  [entries filters]
  (let [want (fn [k e]
               (let [v (get filters k)]
                 (or (nil? v) (= v (get e k)))))
        shown (filter #(every? (fn [k] (want k %)) filter-keys) entries)
        order (fn [e] [(- (or (:overdue e) -1.0)) (- (or (:age_seconds e) 0))
                       (:kind e) (:id e)])
        groups (for [[h es] (sort-by (comp group-rank key) (group-by :holder shown))
                     :let [es (vec (sort-by order es))]]
                 {:holder h
                  :total (count es)
                  :overdue (count (filter overdue? es))
                  :rows es})
        held (fn [h] (count (filter #(= h (:holder %)) shown)))]
    {:waymark "in_flight"
     :filters (into {} (keep (fn [k] (when-some [v (get filters k)] [k v])))
                    filter-keys)
     :counts {:waiting_on_person (held "person")
              :held_by_nobody (held "nobody")
              :overdue (count (filter overdue? shown))
              :total (count shown)}
     :groups (vec groups)}))

(defn- shown-data
  "The row's data as `vis` may read it: no secret field, no field the
  grant hides, and no field it admits only as a token."
  [rdef row vis]
  (let [kind (:kind rdef)
        secret (schema/secret-fields (:schema rdef))
        field? (:field? vis)
        hashed? (:hashed? vis)]
    (into {}
          (remove (fn [[k _]]
                    (or (contains? secret k)
                        (and field? (not (field? kind k)))
                        (and hashed? (hashed? kind k)))))
          (:data row))))

(defn read-all
  "The in-flight document as one caller reads it. `vis` nil is a person
  unscoped; otherwise a kind the grant does not admit is not read and a
  row it does not admit is not answered. `filters` is {:domain :repo
  :kind :holder} → string."
  [eng vis filters]
  (let [started (System/nanoTime)
        st (:storage eng)
        resources (inv/resources eng)
        now ((:now-fn eng))
        kind? (fn [k] (or (nil? vis) ((:kind? vis) k)))
        kinds (for [[k rdef] resources
                    :when (and (kind? k)
                               (or (nil? (:kind filters))
                                   (= (:kind filters) (text k)))
                               (or (nil? (:domain filters))
                                   (= (:domain filters) (some-> (:domain rdef) text))))
                    :let [states (open-states rdef)]
                    :when (seq states)]
                [k rdef states])
        [seat-rows rows]
        (store/with-tx st
          (fn [tx]
            [(if (contains? resources :seat)
               (vec (store/query-rows st tx :seat {:state :active}
                                      {:limit seat-page}))
               [])
             (vec (for [[k rdef states] kinds
                        s states
                        raw (store/query-rows st tx k {:state (keyword (text s))}
                                              {:limit row-page})]
                    [rdef raw]))]))
        admits? (fn [rdef row]
                  (or (nil? vis)
                      (boolean (some-> (:whole-kind? vis) (apply [(:kind rdef)])))
                      (boolean ((:row? vis) (:kind rdef) (str (:id row))))))
        entries (keep (fn [[rdef raw]]
                        (let [row (inv/decode-row rdef raw)]
                          (when (admits? rdef row)
                            (entry rdef seat-rows resources row
                                   {:now now
                                    :data (shown-data rdef row vis)
                                    :summary (render/target-summary rdef row vis)}))))
                      rows)]
    (assoc (answer entries filters)
           :at (str (inst now))
           :took_ms (quot (- (System/nanoTime) started) 1000000))))
