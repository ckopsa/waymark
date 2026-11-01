(ns waymark10.server.replay-promises
  "Promise replay for an amendment (docs/spec-amendment.md §14 step 5;
  the third bullet of R-A.3.3), over the timelines `replay/history`
  reconstructs. Pure: nothing here reads the store, writes, moves or
  wakes.

  A promise's `query` check is re-run under its data as it stands
  (`before`) and as the change would leave it (`after`), at the times
  the engine ran it: each transition of the `about` kind (`hear!`) and
  each sweep tick (`promises/sweep-seconds`) in the window, against the
  rows as the history leaves them then. Each side counts the breaks and
  keeps it would have logged, judged as `promises/evaluate!` judges
  them: a row failing past `by` breaks, and a breached row keeps on its
  second pass a sweep apart. A filter here is equality, a
  comma-separated value any of, and `{id}` the about row's id.

  A `probe` check reads the world at its moment and a `judgment` check
  is a model's verdict (R-A.3.5), so neither is re-run: that side
  answers no count, and `blind` names `probes` or `judgment_checks`
  with the moments it touched. A seat health query reads sittings no
  history here holds, and answers no count with `skipped` `health`."
  (:require [clojure.string :as str]
            [waymark10.age :as age]
            [waymark10.server.promises :as promises])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(defn- before? [^Instant a ^Instant b] (.isBefore a b))

(defn- rows-of
  "The reconstructed rows of one kind, [id row] by id."
  [history kind]
  (sort-by first
           (keep (fn [[[k id] r]] (when (= kind k) [id r])) (:rows history))))

(defn- row-at
  "The row as its timeline leaves it at `t`: its newest moment at or
  before `t`, else its opening state with its first moment's data; nil
  for a row born after `t`. `past` is the moments up to `t`."
  [id {:keys [opening timeline]} ^Instant t]
  (let [past (take-while #(not (before? t (:at %))) timeline)]
    (cond
      (seq past) (let [m (last past)]
                   {:id id :state (:to m) :data (or (:data m) {}) :past past})
      opening {:id id :state opening :data (or (:data (first timeline)) {}) :past ()})))

(defn- field-of [row k]
  (case k
    "id" (:id row)
    "state" (:state row)
    (text (fget (:data row) k))))

(defn- matches?
  "Whether `row` meets every entry of a promise filter, `{id}` bound to
  `id`."
  [flt row id]
  (every? (fn [[k v]]
            (let [v (text v)
                  v (if (= "{id}" v) (str id) v)]
              (contains? (into #{} (map str/trim) (str/split v #","))
                         (field-of row (text k)))))
          flt))

(defn- rows-at [history kind ^Instant t]
  (keep (fn [[id r]] (row-at id r t)) (rows-of history kind)))

(defn- about-at
  "The about rows at `t`: the one `about.id` names, or those its filter
  admits."
  [history about ^Instant t]
  (let [id (some-> (fget about :id) text)]
    (filterv #(if id (= id (:id %)) (matches? (fget about :filter) % nil))
             (rows-at history (text (fget about :kind)) t))))

(defn- check
  "The query check over one about row at `t`: [:pass|:fail cause]."
  [history q row ^Instant t]
  (let [n (count (filter #(matches? (fget q :filter) % (:id row))
                         (rows-at history (text (fget q :kind)) t)))
        expect (text (fget q :expect))
        lte (long (or (fget q :count_lte) 0))]
    (if (case expect "none" (zero? n) "some" (pos? n) (<= n lte))
      [:pass nil]
      [:fail (str (text (fget q :kind)) " expected "
                  (case expect "none" "no row" "some" "a row" (str "at most " lte))
                  " and read " n)])))

(defn- age-entry [v]
  (if (map? v)
    (into {} (map (fn [[k x]] [(keyword (str/replace (text k) "_" "-")) x])) v)
    v))

(defn- due?
  "Whether a failing row is past `by` at `t` (R-P.4.2): a window always
  is; an `after` deadline counts from the row's last such door in the
  window."
  [by row ^Instant t calendar]
  (if (some? (fget by :window))
    true
    (let [actions (some->> (fget by :after) text (#(str/split % #",")) (into #{} (map str/trim)))
          ^Instant start (some #(when (contains? actions (:action %)) (:at %))
                               (reverse (:past row)))
          within (age-entry (fget by :within))
          ^Instant dl (when start
                        (if (number? within)
                          (.plusSeconds start (long within))
                          (age/deadline within row start t calendar)))]
      (boolean (and dl (not (before? t dl)))))))

(defn- pass
  "A breached row's pass at `t`: the first is noted, and one a sweep
  after it keeps."
  [acc id ^Instant t]
  (let [^Instant first-at (get-in acc [:passes id])]
    (cond
      (nil? first-at) (assoc-in acc [:passes id] t)
      (<= promises/sweep-seconds (.getSeconds (Duration/between first-at t)))
      (-> acc
          (update :breached disj id)
          (update :passes dissoc id)
          (update :log conj {:event "keep" :row id :at t}))
      :else acc)))

(defn- tick
  "One run of the check at `t`, as `evaluate!` moves the standing."
  [history p calendar acc ^Instant t]
  (let [q (fget (fget p :holds) :query)
        results (mapv (fn [row]
                        (let [[v cause] (check history q row t)]
                          {:id (:id row) :cause cause
                           :due (and (= :fail v) (due? (fget p :by) row t calendar))}))
                      (about-at history (fget p :about) t))
        failing (into #{} (keep #(when (:due %) (:id %))) results)
        breached (:breached acc)
        acc (reduce (fn [a {:keys [id cause]}]
                      (-> a
                          (update :breached conj id)
                          (update :log conj {:event "break" :row id :at t :cause cause})))
                    acc
                    (filter #(and (:due %) (not (breached (:id %)))) results))]
    (reduce (fn [a id] (if (failing id) (update a :passes dissoc id) (pass a id t)))
            acc (sort breached))))

(defn times
  "The moments the engine ran a promise about `kind` in `window`, oldest
  first: each sweep tick, and each transition of that kind unless
  `heard?` is false (a probe is never run by a transition)."
  [history kind {:keys [^Instant from ^Instant until]} heard?]
  (let [heard (when heard?
                (for [[_ r] (rows-of history kind) m (:timeline r)] (:at m)))
        swept (when (and from until)
                (take-while #(before? % until)
                            (iterate #(.plusSeconds ^Instant % (long promises/sweep-seconds)) from)))]
    (vec (sort (distinct (concat heard swept))))))

(defn- blind-token [holds]
  (cond (some? (fget holds :probe)) "probes"
        (some? (fget holds :judgment)) "judgment_checks"))

(defn- side
  "What one side's promise would have logged over the window."
  [history p window calendar]
  (let [holds (fget p :holds)
        blind (blind-token holds)
        ts (times history (text (fget (fget p :about) :kind)) window (not= "probes" blind))]
    (cond
      blind {:moments (count ts) :blind blind}
      (some-> (fget (fget holds :query) :health) text not-empty)
      {:moments (count ts) :skipped "health"}
      :else
      (let [{:keys [log]} (reduce (partial tick history p calendar)
                                  {:breached #{} :passes {} :log []} ts)]
        {:moments (count ts)
         :breaks (count (filter #(= "break" (:event %)) log))
         :keeps (count (filter #(= "keep" (:event %)) log))
         :log log}))))

(defn replay
  "What a change to one promise would do to its query check over
  `history` (`replay/history`'s answer): {:kind :before :after :blind}.
  `before` and `after` are the promise's data under each side (`about`,
  `holds`, `by`); `window` is the history's {:from :until} and
  `calendar` the :calendar hook a dated `by` needs. Each side is
  {:moments :breaks :keeps :log}, `log` each {:event :row :at :cause};
  a probe or judgment side is {:moments} alone, its token in `blind`
  beside the history's own."
  [{:keys [history before after window calendar]}]
  (let [sides [(side history before window calendar)
               (side history after window calendar)]
        blind (into (vec (:blind history))
                    (keep (fn [token]
                            (when-some [hit (seq (filter #(= token (:blind %)) sides))]
                              {:token token :moments (apply max (map :moments hit))})))
                    ["probes" "judgment_checks"])]
    {:kind (text (fget (fget after :about) :kind))
     :before (dissoc (first sides) :blind)
     :after (dissoc (second sides) :blind)
     :blind blind}))
