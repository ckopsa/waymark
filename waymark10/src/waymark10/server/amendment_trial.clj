(ns waymark10.server.amendment-trial
  "An adopted amendment's trial and verdict (docs/spec-amendment.md §14
  step 8; R-A.5.1-5.5). At `adopted_at + T` the sweep compares the
  answered promises' breaks and breach seconds over two matched windows,
  writes `trial` and `verdict`, and moves the amendment by `settle`.

  T is the longest `:over` of the measures that judge: one week for the
  promise measures. The after window is [adopted_at, adopted_at + T);
  the before window is the T that ends at `adopted_at`, in the governing
  work calendar's zone. One that overlaps another trial on the same
  target moves back to end where that trial began, at most 30 days
  back. Breach seconds come from the promises' own moves clipped to each
  window, so an unkept breach counts to the window's end; the declared
  `breach_seconds` measure sums at `keep` and is not read. A `worse`
  verdict in `mode revert` reverts (R-A.6.1); every other verdict settles."
  (:require [clojure.walk :as walk]
            [waymark10.server.amendment-revert :as amendment-revert]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.invoke :as inv]
            [waymark10.server.measure :as measure]
            [waymark10.server.replay :as replay]
            [waymark10.server.store :as store]
            [waymark10.server.work-calendar :as work-calendar])
  (:import (java.time Duration Instant ZoneId ZoneOffset)))

(set! *warn-on-reflection* true)

(def trial-days
  "R-A.5.1's T: the promise measures' `:over`, one week."
  7)

(def max-back-days
  "How far back an overlapping trial may push the before window."
  30)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(defn- before? [^Instant a ^Instant b] (.isBefore a b))

(defn- shift ^Instant [^Instant t ^ZoneId zone days]
  (.toInstant (.plusDays (.atZone t zone) (long days))))

(defn- seconds [^Instant a ^Instant b] (.getSeconds (Duration/between a b)))

(defn- overlaps? [a b]
  (and (before? (:from a) (:until b)) (before? (:from b) (:until a))))

(defn- in? [{:keys [from until]} ^Instant t]
  (and (not (before? t from)) (before? t until)))

;; ── the matched windows (R-A.5.1) ────────────────────────────────────

(defn windows
  "{:before w :after w}, each {:from :until}, for a trial adopted at
  `adopted-at` in `zone`; `others` are the other trials on the same
  target as windows. {:unjudged reason} when no clean before window
  lies within 30 days."
  [^Instant adopted-at ^ZoneId zone others]
  (let [after {:from adopted-at :until (shift adopted-at zone trial-days)}
        floor (shift adopted-at zone (- max-back-days))]
    (loop [end adopted-at]
      (let [w {:from (shift end zone (- trial-days)) :until end}]
        (if (before? (:from w) floor)
          {:unjudged "no clean window before"}
          (if-some [hit (first (sort-by :from (filter #(overlaps? w %) others)))]
            (recur (:from hit))
            {:before w :after after}))))))

;; ── what judges (R-A.5.2) ────────────────────────────────────────────

(defn reading
  "One promise over window `w`: {:breaks :keeps :breach_seconds}.
  `opening` is its state when w opens, `moves` its {:at :action :to} in
  time order; those outside w are not read. A breach still standing at
  w's end counts to the end."
  [opening moves {:keys [from until] :as w}]
  (let [inside (filterv #(in? w (:at %)) moves)
        count-of (fn [a] (count (filter #(= a (text (:action %))) inside)))
        [since secs] (reduce (fn [[since secs] m]
                               (let [broken? (= "broken" (text (:to m)))]
                                 (cond
                                   (and broken? (nil? since)) [(:at m) secs]
                                   (and (not broken?) since) [nil (+ secs (seconds since (:at m)))]
                                   :else [since secs])))
                             [(when (= "broken" (text opening)) from) 0]
                             inside)]
    {:breaks (count-of "break")
     :keeps (count-of "keep")
     :breach_seconds (+ secs (if since (seconds since until) 0))}))

(defn- answered [row]
  (into [] (comp (keep #(some-> (fget % :promise) text not-empty)) (distinct))
        (fget (:data row) :answers)))

(defn- promise-facts
  "Each answered promise's readings in both windows, or {:unjudged
  reason} when one cannot be read."
  [eng ids {:keys [before after]}]
  (let [rdef (get (inv/resources eng) :promise)
        h (when rdef
            (replay/history (:storage eng) [rdef] {:from (:from before) :until (:until after)}))
        read' (:read (inv/render-hooks eng))]
    (cond
      (nil? rdef) {:unjudged "the engine serves no promise"}
      (some #(= "capped" (:token %)) (:blind h)) {:unjudged "the log read was over the cap"}
      :else
      (reduce
       (fn [acc pid]
         (let [row (get-in h [:rows ["promise" pid]])
               now-state (some-> (read' :promise pid) :state text)
               moves (:timeline row)
               open-before (if row (:opening row) now-state)
               open-after (if row (replay/state-at row (:from after)) now-state)
               states (cons open-after (keep #(when (in? after (:at %)) (:to %)) moves))]
           (cond
             (and (nil? row) (nil? now-state))
             (reduced {:unjudged (str "promise " pid " is not found")})
             (some #{"proposed" "retired"} (map text states))
             (reduced {:unjudged (str "promise " pid " was retired or proposed during the trial")})
             :else
             (assoc-in acc [:promises (keyword pid)]
                       {:before (reading open-before moves before)
                        :after (reading open-after moves after)}))))
       {:promises {}} ids))))

(defn- log-of
  "`kind`'s log newest first, at most read-cap rows."
  [st kind]
  (store/with-tx st
    #(store/transitions st % {:kind kind} {:newest-first true :limit measure/read-cap})))

(defn- confounded?
  "R-A.6.3: a move by the target's door after the adopt, inside the after
  window, that leaves a patched field other than the adopt wrote it."
  [st row {:keys [after]} ^Instant adopted-at]
  (let [{:keys [kind id door patch]} (amendments/target-of row)
        wrote (fget (:data row) :after)]
    (boolean
     (some (fn [r]
             (and (= id (str (:resource-id r)))
                  (= (name door) (text (:action r)))
                  (before? adopted-at (:at r))
                  (in? after (:at r))
                  (let [a (:after r)]
                    (or (nil? a) (some #(not= (fget wrote %) (fget a %)) (keys patch))))))
           (log-of st kind)))))

;; ── the verdict (R-A.5.3-5.4) ────────────────────────────────────────

(defn- total [promises side]
  (reduce (fn [m r] (merge-with + m (select-keys (get r side) [:breaks :keeps :breach_seconds])))
          {:breaks 0 :keeps 0 :breach_seconds 0}
          (vals promises)))

(defn- compare-windows
  "R-A.5.3. Equal breaks and no breach seconds before reads `same`, not
  `better`: zero is at most 0.75 × zero, but it is no improvement."
  [b a]
  (let [bs (:breach_seconds b) as (:breach_seconds a)]
    (cond
      (> (:breaks a) (:breaks b)) "worse"
      (< (:breaks a) (:breaks b)) "better"
      (> as (* 1.25 bs)) "worse"
      (and (pos? bs) (<= as (* 0.75 bs))) "better"
      :else "same")))

(defn decide
  "[verdict reason] over the trial's facts: {:unjudged reason},
  {:confounded true} or {:promises {id {:before r :after r}}}."
  [{:keys [unjudged confounded promises]}]
  (let [b (total promises :before)
        a (total promises :after)]
    (cond
      unjudged ["unjudged" unjudged]
      confounded ["confounded" "another hand restated a patched field during the trial"]
      (zero? (+ (:breaks b) (:keeps b) (:breaks a) (:keeps a))) ["quiet" nil]
      :else [(compare-windows b a) nil])))

(defn judge
  "The trial of amendment `row` adopted at `adopted-at` in `zone`, beside
  `others` (the other trials on its target, as windows):
  {:trial :verdict}."
  [eng row ^Instant adopted-at zone others]
  (let [ids (answered row)
        ws (windows adopted-at zone others)
        facts (cond
                (empty? ids) {:unjudged "answers no promise"}
                (:unjudged ws) ws
                :else (let [f (promise-facts eng ids ws)]
                        (cond-> f
                          (and (not (:unjudged f))
                               (confounded? (:storage eng) row ws adopted-at))
                          (assoc :confounded true))))
        [verdict reason] (decide facts)]
    {:verdict verdict
     :trial (walk/postwalk
             #(if (instance? Instant %) (str %) %)
             (cond-> {:adopted_at adopted-at
                      :before_window (:before ws)
                      :after_window (:after ws)
                      :readings {:before (total (:promises facts) :before)
                                 :after (total (:promises facts) :after)
                                 :promises (or (:promises facts) {})}}
               reason (assoc :reason reason)))}))

;; ── settle (R-A.5.5) ─────────────────────────────────────────────────

(defn- zone-of ^ZoneId [eng]
  (let [c (work-calendar/governing {:calendar (:calendar (inv/render-hooks eng))})]
    (or (when (map? c) (:zone c)) ZoneOffset/UTC)))

(defn- adopts
  "Every logged adopt, newest first: {:id :at :target [kind id]}."
  [st]
  (keep (fn [r]
          (when (= "adopt" (text (:action r)))
            (let [{:keys [kind id]} (amendments/target-of {:data (:after r)})]
              {:id (str (:resource-id r)) :at (:at r) :target [kind id]})))
        (log-of st :amendment)))

(defn settle!
  "Judge amendment `id` and move it by `settle` once its after window has
  ended; nil while the trial still runs."
  [eng id]
  (let [row ((:read (inv/render-hooks eng)) :amendment id)
        zone (zone-of eng)
        all (adopts (:storage eng))
        ^Instant at (:at (first (filter #(= (str id) (:id %)) all)))
        target (let [t (amendments/target-of row)] [(:kind t) (:id t)])
        others (into []
                     (comp (filter #(and (= target (:target %)) (not= (str id) (:id %))))
                           (map (fn [t] {:from (:at t) :until (shift (:at t) zone trial-days)})))
                     all)]
    (when (and at (not (before? ((:now-fn eng)) (shift at zone trial-days))))
      (let [{:keys [trial verdict]} (judge eng row at zone others)]
        (if (and (= "worse" verdict) (= "revert" (some-> (fget (:data row) :mode) str)))
          (amendment-revert/revert! eng id {:trial trial :verdict verdict})
          (inv/invoke! eng :amendment id :settle {:trial trial :verdict verdict}
                       {:principal amendments/engine-actor
                        :idempotency-key (str "amendment:" id ":settle")}))))))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 amendment trial: " parts))))

(defn- trial-ids [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (mapv #(str (:id %))
              (store/search-rows st tx :amendment
                                 [{:target :state :op :in :values ["trial"]}]
                                 {:order-by nil :desc nil :limit 1000}))))))

(defn sweep!
  "`settle!` every amendment in `trial`. Rides the live cracks clock
  beside `amendments/sweep!`; one that will not settle is warned and
  left in `trial` for the next pass."
  [eng]
  (when (contains? (inv/resources eng) :amendment)
    (try (doseq [id (trial-ids eng)]
           (try (settle! eng id)
                (catch Exception e (warn! "amendment " id ": " (ex-message e)))))
         (catch Exception e (warn! "the sweep failed — " (ex-message e)))))
  nil)
