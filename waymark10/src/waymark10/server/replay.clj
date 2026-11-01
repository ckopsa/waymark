(ns waymark10.server.replay
  "History reconstruction for an amendment's replay
  (docs/spec-amendment.md §14 step 3; R-A.3.2, 3.4 and 3.6): each
  row's state timeline over a window, read from the transition log,
  and the `blind` tokens that say what the read could not see. Pure
  reads: nothing here writes, moves or wakes.

  The window (R-A.3.2) is 14 days ending at the replay unless the
  filer names 1 to 30. A kind whose log starts after the window opens
  is cut there and names `before_record:<kind>`.

  States come from the `from` and `to` of each transition, which every
  kind logs. Fields (R-A.3.4) come from the transition's `:after` where
  the kind retains data, and from the row as it stands now otherwise;
  each moment read that way counts under `current_data:<kind>`.

  The cap (R-A.3.6) is measure/read-cap log rows across every kind
  read. Past it the read stops at the oldest moment it reached, the
  timelines cover only [stopped, until), and `capped` names that
  moment — never a part answered as the whole."
  (:require [waymark10.server.decision :as decision]
            [waymark10.server.measure :as measure]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def default-days 14)

(defn window
  "{:from :until} — `days` (default 14, 1 to 30) ending at `until`.
  Throws on a count outside 1 to 30 rather than reading a window the
  filer did not name."
  ([until] (window until nil))
  ([^Instant until days]
   (let [d (or days default-days)]
     (when-not (and (integer? d) (<= 1 d 30))
       (throw (ex-info (str "a replay window is 1 to 30 days, not " d)
                       {:days d})))
     {:from (.minus until (Duration/ofDays (long d))) :until until})))

(def ^:dynamic *tx*
  "The transaction to read in when the caller already holds one (a
  guard judging a write, R-A.8.3); nil opens one for each read."
  nil)

(defn- in-tx [st f]
  (if-some [tx *tx*] (f tx) (store/with-tx st f)))

(defn- before? [^Instant a ^Instant b] (.isBefore a b))

(defn- read-kind
  "The kind's log newest-first, at most `budget` rows at or after
  `from` (one more is read to tell a full window from a cut one):
  {:recs [in-window, oldest first] :n read-counted :capped stop-at
  :before-record oldest-at}."
  [st kind {:keys [from until]} budget]
  (let [recs (in-tx st
               (fn [tx]
                 (store/transitions st tx {:kind kind}
                                    {:newest-first true :limit (inc budget)})))
        fresh (take-while #(not (before? (:at %) from)) recs)
        crossed? (< (count fresh) (count recs))
        over? (and (not crossed?) (> (count fresh) budget))
        kept (vec (if over? (take budget fresh) fresh))
        oldest (some-> (peek kept) :at)]
    (cond-> {:recs (->> kept
                        (remove #(not (before? (:at %) until)))
                        reverse
                        vec)
             :n (count kept)}
      over? (assoc :capped (or oldest until))
      (and (not crossed?) (not over?)) (assoc :before-record (or oldest until)))))

(defn- todays-rows
  "id-string → the row as it stands now, for these ids — one read."
  [st kind ids]
  (if (empty? ids)
    {}
    (into {}
          (map (fn [raw] [(str (:id raw)) raw]))
          (in-tx st
            (fn [tx]
              (store/search-rows st tx kind [{:target :id :op :in :values (vec ids)}]
                                 {:order-by nil :desc nil :limit (count ids)}))))))

(defn- moment
  "One transition as a timeline entry; `today` is the row now, read
  when the log keeps no after-image of this move."
  [rec today]
  (let [after (:after rec)]
    {:at (:at rec)
     :action (some-> (:action rec) name)
     :from (some-> (:from-state rec) name)
     :to (some-> (:to-state rec) name)
     :data (if (some? after) after (:data today))
     :current-data (nil? after)}))

(defn- kind-history
  "The timelines of one kind's rows from its in-window log rows."
  [st rdef recs]
  (let [kind (:kind rdef)
        retains? (decision/retains-data? rdef)
        need (into #{} (comp (filter #(or (not retains?) (nil? (:after %))))
                             (map #(str (:resource-id %))))
                   recs)
        today (todays-rows st kind need)
        moments (mapv #(moment % (get today (str (:resource-id %)))) recs)
        rows (reduce (fn [acc [rec m]]
                       (update acc (str (:resource-id rec))
                               (fnil conj []) (dissoc m :current-data)))
                     {} (map vector recs moments))]
    {:rows (into {}
                 (map (fn [[id tl]]
                        [[(name kind) id]
                         {:kind (name kind) :id id
                          :opening (:from (first tl))
                          :timeline tl}]))
                 rows)
     :current-data (count (filter :current-data moments))}))

(defn- blind-of [kind {:keys [current-data before-record]}]
  (cond-> []
    (pos? current-data)
    (conj {:token (str "current_data:" (name kind)) :moments current-data})
    before-record
    (conj {:token (str "before_record:" (name kind)) :moments 1
           :at before-record})))

(defn history
  "Every logged row of the kinds `rdefs` over window `w`:
  {:window w :rows_read n :rows {[kind id] {:kind :id :opening
  :timeline [{:at :action :from :to :data}]}} :blind [{:token :moments}]}.
  :opening is the row's state when the window (or the read) opened.
  `cap` defaults to measure/read-cap, and is spent kind by kind in the
  order given. A capped read carries {:token \"capped\" :moments 1 :at
  stopped :unread [kind]}: the kind that met the cap covers only
  [stopped, until), and the kinds after it are unread and named, never
  reported empty as if whole."
  ([st rdefs w] (history st rdefs w nil))
  ([st rdefs w cap]
   (let [cap (or cap measure/read-cap)
         reads (loop [[rdef & more :as left] (seq rdefs), budget cap, acc []]
                 (cond
                   (empty? left) acc
                   (zero? budget)
                   (recur more 0 (conj acc [rdef {:recs [] :n 0 :unread true}]))
                   :else
                   (let [r (read-kind st (:kind rdef) w budget)]
                     (recur more (- budget (:n r)) (conj acc [rdef r])))))
         stopped (some (comp :capped second) reads)
         unread (into [] (comp (filter (comp :unread second))
                               (map (comp name :kind first)))
                      reads)
         parts (mapv (fn [[rdef r]] [rdef r (kind-history st rdef (:recs r))])
                     reads)]
     {:window w
      :rows_read (reduce + 0 (map (comp :n second) reads))
      :rows (into {} (mapcat (fn [[_ _ h]] (:rows h))) parts)
      :blind (cond-> (into []
                           (mapcat (fn [[rdef r h]]
                                     (blind-of (:kind rdef)
                                               {:current-data (:current-data h)
                                                :before-record (:before-record r)})))
                           parts)
               (or stopped (seq unread))
               (conj (cond-> {:token "capped" :moments 1 :at stopped}
                       (seq unread) (assoc :unread unread))))})))

(defn state-at
  "A reconstructed row's state at instant t: the `to` of its last move
  before or at t, else its opening state."
  [row ^Instant t]
  (reduce (fn [s m] (if (.isAfter ^Instant (:at m) t) (reduced s) (:to m)))
          (:opening row) (:timeline row)))
