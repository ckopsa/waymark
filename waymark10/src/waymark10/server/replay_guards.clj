(ns waymark10.server.replay-guards
  "Guard replay for an amendment (docs/spec-amendment.md §14 step 6;
  R-A.3.3's fourth bullet): each logged move in the window through a
  door whose guards read a patched field, judged again by those guards
  as the change would leave them. Pure reads: nothing here writes,
  moves or wakes.

  A move is judged only where what its guards need was kept: its input
  on the log (`:record`), or no guard of the door grading input at
  all. Its row is the document before the move — the previous move's
  `:after` where the kind retains data, today's row otherwise
  (R-A.3.4), counted under `current_data:<kind>`.

  The log holds moves that happened, so replay can say a move would
  now be refused and never that a move once refused would now pass.
  `refusals` in `blind` says so, with the moves that could not be
  judged because their input was not retained as its moments."
  (:require [waymark10.guards :as g]
            [waymark10.server.decision :as decision]
            [waymark10.server.measure :as measure]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(defn- before? [^Instant a ^Instant b] (.isBefore a b))

(defn- read-window
  "The kind's in-window log rows, oldest first, at most `cap`:
  {:recs :n :capped stop-at}."
  [st kind {:keys [from until]} cap]
  (let [recs (store/with-tx st
               (fn [tx]
                 (store/transitions st tx {:kind kind}
                                    {:newest-first true :limit (inc cap)})))
        fresh (take-while #(not (before? (:at %) from)) recs)
        over? (and (= (count fresh) (count recs)) (> (count fresh) cap))
        kept (vec (if over? (take cap fresh) fresh))]
    (cond-> {:recs (->> kept (filter #(before? (:at %) until)) reverse vec)
             :n (count kept)}
      over? (assoc :capped (or (some-> (peek kept) :at) until)))))

(defn- touches? [patched guard]
  (or (nil? patched)
      (boolean (some (set patched) (concat (:reads guard) (:judges guard))))))

(defn- replayed-guards
  "door keyword → the guards of it the patch touches, for the doors that
  have any."
  [doors patched]
  (into {}
        (keep (fn [[door guards]]
                (when-some [gs (seq (filter #(touches? patched %) guards))]
                  [(keyword (name door)) (vec gs)])))
        doors))

(defn- needs-input? [guards] (boolean (some :needs-input guards)))

(defn- todays-data
  "id-string → the row's data as it stands now, for these ids."
  [st kind ids]
  (if (empty? ids)
    {}
    (into {}
          (map (fn [raw] [(str (:id raw)) (:data raw)]))
          (store/with-tx st
            (fn [tx]
              (store/search-rows st tx kind [{:target :id :op :in :values (vec ids)}]
                                 {:order-by nil :desc nil :limit (count ids)}))))))

(defn- judge
  "The names of the guards that refuse this move now, in order; nil
  when every one allows. A warning-severity deny is not a refusal."
  [guards row inp ctx]
  (not-empty
   (into []
         (keep (fn [guard]
                 (let [[v _] (g/evaluate guard row inp ctx)]
                   (when (and (t/deny? v) (= :refuse (:severity guard :refuse)))
                     (name (:name guard))))))
         guards)))

(defn- befores
  "The document before each move, in log order: the previous `:after`
  of the same row where the kind retains data, else nil."
  [retains? recs]
  (first
   (reduce (fn [[acc last-after] rec]
             (let [id (str (:resource-id rec))]
               [(conj acc (get last-after id))
                (cond-> last-after
                  (and retains? (some? (:after rec)))
                  (assoc id (:after rec)))]))
           [[] {}] recs)))

(defn- verdict
  "One move judged: {:refused names-or-nil :current-data bool}, or
  {:unretained true} when its guards cannot be run over what was kept.
  A guard that throws over the reconstructed row leaves the move
  unjudged rather than counted."
  [guards rec before today]
  (let [inp (:inputs rec)
        id (str (:resource-id rec))]
    (if (and (nil? inp) (needs-input? guards))
      {:unretained true}
      (try
        {:current-data (nil? before)
         :refused (judge guards
                         {:id id
                          :state (:from-state rec)
                          :data (if (some? before) before (get today id))}
                         inp
                         {:now (:at rec) :actor (:actor rec)})}
        (catch Exception _ {:unretained true})))))

(defn replay
  "The moves on kind `rdef` over window `w` that the changed guards
  would now refuse. opts:
    :doors    {door [guard ...]}, each door's guards under the new value
    :patched  the field names the change touches; nil judges every
              guard named in :doors
    :cap      defaults to measure/read-cap
  -> {:window w :rows_read n :judged n
      :refused [{:kind :id :at :action :guards [names]}]
      :counts {action n} :blind [{:token :moments}]}."
  [st rdef w {:keys [doors patched cap]}]
  (let [kind (:kind rdef)
        by-door (replayed-guards doors patched)
        {:keys [recs n capped]} (read-window st kind w (or cap measure/read-cap))
        recs (filterv #(contains? by-door (keyword (name (:action %)))) recs)
        before (befores (decision/retains-data? rdef) recs)
        today (todays-data st kind
                           (into #{} (keep-indexed
                                      (fn [i rec]
                                        (when (nil? (nth before i))
                                          (str (:resource-id rec)))))
                                 recs))
        verdicts (mapv (fn [rec b]
                         (verdict (get by-door (keyword (name (:action rec))))
                                  rec b today))
                       recs before)
        judged (remove :unretained verdicts)
        refused (into []
                      (keep (fn [[rec v]]
                              (when-some [names (:refused v)]
                                {:kind (name kind)
                                 :id (str (:resource-id rec))
                                 :at (:at rec)
                                 :action (name (:action rec))
                                 :guards names})))
                      (map vector recs verdicts))
        current (count (filter :current-data judged))]
    {:window w
     :rows_read n
     :judged (count judged)
     :refused refused
     :counts (frequencies (map :action refused))
     :blind (cond-> []
              (pos? current)
              (conj {:token (str "current_data:" (name kind)) :moments current})
              (seq recs)
              (conj {:token "refusals"
                     :moments (count (filter :unretained verdicts))})
              capped
              (conj {:token "capped" :moments 1 :at capped}))}))
