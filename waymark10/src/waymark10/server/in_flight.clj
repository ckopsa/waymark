(ns waymark10.server.in-flight
  "GET /api/-/in-flight (ticket d0407b84): every open row the caller's
  grant admits, with its `waiting_on`, grouped by who holds it. The
  answer to \"where are we at with X?\" in one read, and the opening
  page of every house: it names no kind.

  A row is IN FLIGHT when it is in a state that is not terminal and has
  an expected age (`live-cracks/expected-age`: the kind's own
  `:expected-age` or the default for the state's name). A state with
  none is one a record rests in — a verdict `said`, a journal
  `written` — and is left out, as the cracks audit leaves it out: no
  one owes it a move (ticket 4d7badd8). A row whose verdict says it
  rests (`waiting-on/block` says `nothing`) is left out too.

  Per row: kind, id, summary, state, its `waiting_on` as the row read
  gives it, and

  holder            `seat:<name>`, `source:<name>`, `person` or
                    `nobody`: the holder the verdict names, as
                    `waiting_on.holder` carries it (ticket ca68c559)
  age_seconds       since the row's last write, as the live cracks pass
                    reads it, so what this calls overdue is what that
                    pass calls aged
  expected_seconds  the state's expected age, or nil
  due, late         where that age is a map (a date or business days):
                    the deadline `waiting_on.since.due` names, and
                    whether it has passed
  overdue           age / expected — age / the span to `due` for a
                    dated age — or nil with neither

  Groups run `person`, `nobody`, then each seat, then each source;
  inside a group the most overdue first.

  THE CAP. One read takes at most `*row-page*` rows of a state. When a
  state holds more, the answer says `truncated: true` and names that
  state under `capped` with its true count, so a capped number is never
  read as the total.

  ONE PASS. The active seats and the rows of every open state are read
  in one transaction, and each row's block is `waiting-on/block`, which
  is pure; no row's log is read, so `since.by` is absent here where the
  row read carries it. `answer` is pure over the entries, so a test
  judges the grouping with no engine."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.members :as members]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.waiting-on :as waiting-on])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def ^:private seat-page
  "The most active seats one read takes, as `waiting-on` takes them."
  500)

(def ^:dynamic *row-page*
  "The most rows of one state one read takes. Dynamic so a test reaches
  the cap with a few rows."
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
  not terminal and has an expected age; nil when there is none. A
  state with no expected age is one its rows rest in."
  [rdef]
  (let [terminal (into #{} (map text) (:terminal rdef))
        states (filterv #(and (not (contains? terminal (text %)))
                              (live-cracks/expected-age rdef %))
                        (:states rdef))]
    (when (seq states) states)))

(defn entry
  "One row in flight, or nil for a row in a state it rests in. `data` is
  the row's data as the caller may read it, `summary` its line."
  [rdef seat-rows by-kind row {:keys [now data summary tickets judgments calendar scheduled]}]
  (let [w (waiting-on/block rdef seat-rows by-kind row
                            {:now now :data data :calendar calendar
                             :tickets tickets :judgments judgments
                             :scheduled scheduled})]
    (when-not (str/starts-with? (str (:verdict w)) "nothing:")
      (let [at (inst (get-in w [:since :at]))
            now (inst now)
            age (when (and at now)
                  (max 0 (.getSeconds (Duration/between at now))))
            expected (get-in w [:since :expected_seconds])
            due (inst (get-in w [:since :due]))
            window (if due
                     (when at (.getSeconds (Duration/between at due)))
                     expected)
            ratio (when (and age (number? window) (pos? window))
                    (/ (Math/round (* 100.0 (/ (double age) window))) 100.0))
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
          repo (assoc :repo repo)
          due (assoc :due (str due)
                     :late (boolean (and now (not (.isBefore now due))))))))))

(defn- overdue? [e]
  (if (contains? e :late)
    (boolean (:late e))
    (boolean (and (:age_seconds e) (number? (:expected_seconds e))
                  (> (:age_seconds e) (:expected_seconds e))))))

(defn- group-rank [h]
  [(cond (= "person" h) 0
         (= "nobody" h) 1
         (members/seat-id h) 2
         :else 3)
   h])

(defn answer
  "The document over the entries: filtered, grouped by holder, each
  group most overdue first, with totals per group and four counts,
  and the facets: each filter's values over the entries before any
  filter, so a filtered screen still offers every choice."
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
     :facets (into {} (map (fn [k] [k (vec (sort (into #{} (comp (keep k) (map text))
                                                       entries)))]))
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
        calendar (:calendar (inv/render-hooks eng))
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
        page *row-page*
        [seat-rows pages]
        (store/with-tx st
          (fn [tx]
            [(vec (store/query-rows st tx :seat {:state :active}
                                    {:limit seat-page}))
             ;; one row past the page tells a capped state from a full one
             (vec (for [[k rdef states] kinds
                        s states
                        :let [state (text s)
                              raws (vec (store/query-rows st tx k {:state (keyword state)}
                                                          {:limit (inc page)}))
                              over? (> (count raws) page)]]
                    {:rdef rdef
                     :raws (if over? (subvec raws 0 page) raws)
                     :capped (when over?
                               {:kind (text k) :state state :read page
                                :total (store/count-matching
                                        st tx k [{:target :state :op := :value state}])})}))]))
        admits? (fn [rdef row]
                  (or (nil? vis)
                      (boolean (some-> (:whole-kind? vis) (apply [(:kind rdef)])))
                      (boolean ((:row? vis) (:kind rdef) (str (:id row))))))
        rows (vec (for [{:keys [rdef raws]} pages
                        raw raws
                        :let [row (inv/decode-row rdef raw)]
                        :when (admits? rdef row)]
                    [rdef row]))
        ;; whose walk hands each row its holder (ticket af41e6bf)
        reads (store/with-tx st
                (fn [tx] (waiting-on/walk-reads eng tx seat-rows (map second rows))))
        entries (for [[rdef row] rows
                      :let [e (entry rdef seat-rows resources row
                                     (assoc reads
                                            :now now
                                            :calendar calendar
                                            :data (shown-data rdef row vis)
                                            :summary (render/target-summary rdef row vis)))]
                      :when e]
                  e)
        capped (into [] (keep :capped) pages)]
    (cond-> (assoc (answer entries filters)
                   :truncated (boolean (seq capped))
                   :at (str (inst now))
                   :took_ms (quot (- (System/nanoTime) started) 1000000))
      (seq capped) (assoc :capped capped))))
