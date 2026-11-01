(ns waymark10.server.amendments
  "Amendments (docs/spec-amendment.md §14 step 2): a row that wraps one
  seat or promise restate. R-A.2.1-2.7 and R-A.10.2: the fields, the
  states and their doors, the lane a patch may name
  (`an-amendment-stays-in-its-lane`), and the engine's own walls on
  `replay` and `settle`.

  Step 7 (R-A.4.1-4.3, R-A.2.7): `replay!` is the engine's replay. It
  reads the target, runs steps 3-5 over the window under the row as it
  stands and as the patch leaves it, and writes the report through the
  engine-only `replay` door, with the target's version, a hash of each
  of its fields and the restate's whole-input fields. `adopt` is the
  owner's tap: it refuses a target that moved since (naming the fields
  that did), then invokes the target's own restate under the owner's
  principal in the same transaction, the row's current values overlaid
  with the patch, and keeps `before` and `after`. A guard that refuses
  that restate refuses the adopt with its own sentence. `revert` and
  `keep` are step 9's doors (waymark10.server.amendment-revert). Opted
  into like `promise`, through an app's resources vector."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.amendment-revert :as amendment-revert]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.replay :as replay]
            [waymark10.server.replay-guards :as replay-guards]
            [waymark10.server.replay-promises :as replay-promises]
            [waymark10.server.replay-walks :as replay-walks]
            [waymark10.server.store :as store]
            [waymark10.server.waiting-on :as waiting-on]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(def engine-actor
  (t/principal {:id "waymark10-amendments" :type :system :display "Amendments"}))

(def lane
  "R-A.10.2: the fields a patch may name, by the target's kind."
  {"seat" #{"charter" "instructions" "walk" "walk_promises" "judgment"
            "rows_per_firing" "cadence_seconds"}
   "promise" #{"name" "holds" "by" "remedy"}})

(defn- seat?
  "A seat's own principal: an agent that acts for no person."
  [p]
  (and (= :agent (:type p)) (nil? (some-> (:acts-for p) str not-empty))))

(defn- filer-of [p]
  (if (= :system (:type p)) "engine" (some-> (or (:acts-for p) (:id p)) str not-empty)))

(g/defguard an-amendment-writer
  {:reads [:principal :member :domain]
   :explain "An amendment is the house owner's to file: this door wants the factory domain's owner, or a seat whose grant names `amendment`."
   :open "The factory domain's owner is set by restating that domain; a seat is given `amendment` on its grant."}
  [_row _inp ctx]
  (if (goals/goal-writer? ctx) (t/allow) (t/deny)))

(g/defguard the-filer-or-the-owner
  {:reads [:principal :member :domain]
   :explain "Only the one who filed this amendment, or the house owner, restates or withdraws it."
   :open "The owner files a new amendment of their own."}
  [row _inp ctx]
  (let [p (:principal ctx)
        filer (some-> (fget (:data row) :filed_by) text not-empty)]
    (if (or (and filer (= filer (filer-of p)))
            (and (not (seat? p)) (not= :system (:type p)) (goals/goal-writer? ctx)))
      (t/allow)
      (t/deny))))

(g/defguard the-engine-moves-it
  {:reads [:principal]
   :hide true
   :explain "An amendment's replay and its verdict are the engine's own reading; no seat replays its own change or judges its own trial (R-A.2.5)."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx))) (t/allow) (t/deny)))

(g/defguard a-seats-amendment-answers-a-promise
  {:judges [:answers]
   :reads [:principal]
   :explain "A seat files an amendment against the promises it breaks: name at least one in `answers`, as {promise, breach}."
   :open "Name the promise and the id of its break transition; a person's amendment may name none."}
  [row inp ctx]
  (let [answers (if (contains? inp :answers) (:answers inp) (fget (:data row) :answers))]
    (if (or (not (seat? (:principal ctx))) (seq answers)) (t/allow) (t/deny))))

(defn- out-of-lane
  "Why `changes` leaves the lane (R-A.10.2), or nil. `read'` reads the
  target when the ctx carries it."
  [changes read']
  (let [kind (some-> (fget changes :kind) text)
        id (some-> (fget changes :id) text)
        door (some-> (fget changes :door) text)
        patch (fget changes :patch)
        fields (sort (map text (keys patch)))
        admits (get lane kind)]
    (cond
      (nil? admits)
      (str "a `" kind "` row is outside it: an amendment restates one seat or one promise"
           " — use the " kind " kind's own doors yourself")

      (not= "restate" door)
      (str "the door `" door "` is not `restate`: no seat is created, parked, unparked,"
           " merged or retired by an amendment — use the raw door " kind "." door " yourself")

      (empty? fields)
      "its patch names no field"

      :else
      (or (some (fn [f]
                  (when-not (admits f)
                    (str "`" f "` is outside it on a " kind
                         " — change it on the raw door " kind ".restate yourself")))
                fields)
          (when read'
            (let [target (try (read' (keyword kind) id) (catch Exception _ nil))
                  was (fget (:data target) :cadence_seconds)
                  now (fget patch :cadence_seconds)]
              (cond
                (nil? target) (str "it names no " kind " `" id "`")
                (and (= "seat" kind) (some? now) (number? was)
                     (not (and (number? now) (> now was))))
                (str "`cadence_seconds` may only lengthen, and it is " was
                     " now — shorten it on the raw door seat.restate yourself"))))))))

(g/defguard an-amendment-stays-in-its-lane
  {:judges [:changes]
   :reads [:seat :promise]
   :vars [:problem]
   :explain "An amendment changes only what its lane admits: {problem}."
   :open "Patch a seat's charter, instructions, walk, walk_promises, judgment, rows_per_firing or a longer cadence_seconds, or a promise's name, holds, by or remedy; anything else is the person's, on the target's own door."}
  [_row inp ctx]
  (if-some [changes (:changes inp)]
    (if-some [problem (out-of-lane changes (:read ctx))]
      (t/deny {:vars {:problem problem}})
      (t/allow))
    (t/allow)))

(g/defguard the-owner-adopts
  {:reads [:principal]
   :explain "Only the amendment's owner adopts it, by their own tap; a seat never adopts a change, its own or another's (R-A.2.6)."
   :open "The member named as the amendment's owner taps adopt."}
  [row _inp ctx]
  (let [p (:principal ctx)
        owner (some-> (fget (:data row) :owner) text not-empty)]
    (if (and owner (not (seat? p)) (not= :system (:type p)) (= owner (filer-of p)))
      (t/allow)
      (t/deny))))

(defn target-of
  "The amendment's target as {:kind :id :door :patch}, the patch keyed
  by keyword."
  [row]
  (let [changes (fget (:data row) :changes)]
    {:kind (keyword (text (fget changes :kind)))
     :id (text (fget changes :id))
     :door (keyword (text (fget changes :door)))
     :patch (into {} (map (fn [[k v]] [(keyword (text k)) v])) (fget changes :patch))}))

(defn- seen-of
  "A hash of each of the row's fields, so a later read can name the
  ones that moved without the report keeping the row whole."
  [data]
  (into {} (map (fn [[k v]] [(keyword (text k)) (hash v)])) data))

(defn- moved
  "The fields of `data` whose hash is not the one `seen` kept, sorted."
  [seen data]
  (let [now (seen-of data)
        then (into {} (map (fn [[k v]] [(keyword (text k)) v])) seen)]
    (sort (keep (fn [k] (when (not= (get then k (hash nil)) (get now k (hash nil))) (name k)))
                (distinct (concat (keys then) (keys now)))))))

(g/defguard the-target-has-not-moved
  {:reads [:seat :promise]
   :vars [:fields]
   :explain "The target changed after the replay read it ({fields}): an adopt applies only what was replayed (R-A.2.7)."
   :open "Restate the amendment; the engine replays it against the target as it now stands."}
  [row _inp ctx]
  (let [seen (fget (fget (:data row) :replay) :target)
        {:keys [kind id]} (target-of row)
        target (when (:read ctx) (try ((:read ctx) kind id) (catch Exception _ nil)))]
    (cond
      (nil? seen) (t/deny {:vars {:fields "no replay read it"}})
      (nil? target) (t/deny {:vars {:fields "it is gone"}})
      (= (text (fget seen :version)) (text (:version target))) (t/allow)
      :else (t/deny {:vars {:fields (let [fs (moved (fget seen :seen) (:data target))]
                                      (if (seq fs)
                                        (str "`" (str/join "`, `" fs) "` moved")
                                        "its state moved"))}}))))

(def ^:private title-field
  [:title {:x-display {:label "Amendment" :help "A sentence a person reads: meal-planner walks next week's plan."}}
   [:string {:min 3 :max 200}]])

(def ^:private answers-field
  [:answers {:optional true
             :x-display {:label "Answers" :help "[{promise, breach}]: each promise id and the id of its break transition. A seat's amendment names at least one; a person's may name none."}}
   [:maybe [:vector {:max 20}
            [:map
             [:promise [:string {:min 1 :max 120}]]
             [:breach {:optional true} [:maybe [:string {:max 120}]]]]]]])

(def ^:private changes-field
  [:changes {:x-display {:label "Changes" :help "{kind, id, door, patch}: one seat or promise, its restate door, and the fields it changes by name."}}
   [:map
    [:kind [:string {:min 1 :max 80}]]
    [:id {:x-ref {:kind-from :kind}} [:string {:min 1 :max 120}]]
    [:door [:string {:min 1 :max 80}]]
    [:patch [:map-of :keyword :any]]]])

(def ^:private report-schema [:maybe [:map-of :keyword :any]])

(defn- optional [[k props s]]
  [k (assoc props :optional true) s])

(def ^:private engine-fields
  [:before :after :replay :trial :verdict :revert :mode :filed_by :key :withdrawn_because])

(defn- born-amendment
  "The engine writes what the filer may not: `filed_by` is the create's
  principal, the owner is the filer unless it names one, and every
  engine field starts empty."
  [row ctx]
  (let [p (:principal ctx)
        owner (or (some-> (fget (:data row) :owner) text not-empty)
                  (when (not= :system (:type p)) (filer-of p)))]
    (-> row
        (update :data #(apply dissoc % (concat engine-fields (map name engine-fields))))
        (assoc-in [:data :filed_by] (filer-of p))
        (cond-> owner (assoc-in [:data :owner] owner)))))

(defhandler restate-amendment
  [row inp _ctx]
  (update row :data #(-> %
                         (merge (select-keys inp [:title :answers :changes]))
                         (dissoc "replay")
                         (assoc :replay nil))))

(defhandler replay-amendment
  [row inp _ctx]
  (assoc-in row [:data :replay] (:report inp)))

(def ^:private replay-acknowledged
  "R-A.4.2: the warnings the replay already showed the owner."
  #{:opens-no-crack})

(defhandler adopt-amendment
  [row _inp ctx]
  ;; R-A.4.1: the target's own door, under this (the owner's) principal,
  ;; in this transaction. Its refusal is thrown through, so the adopt
  ;; refuses with the door's own sentence and nothing moves.
  (let [{:keys [kind id door patch]} (target-of row)
        target ((:read ctx) kind id)
        data (:data target)
        whole (into {}
                    (keep (fn [f] (let [v (fget data f)] (when (some? v) [(keyword (text f)) v]))))
                    (fget (fget (fget (:data row) :replay) :target) :input))
        res ((:invoke ctx) kind id door (merge whole patch)
             {:acknowledged replay-acknowledged
              :if-match (inv/etag kind id (:version target))})
        written (:data (or (:row res) ((:read ctx) kind id)))]
    ;; R-A.4.3: `after` is what the restate wrote, read back here
    (update row :data assoc
            :before (into {} (map (fn [k] [k (fget data k)])) (keys patch))
            :after (into {} (map (fn [k] [k (fget written k)])) (keys patch)))))

;; ── the engine's replay (R-A.3) ──────────────────────────────────────

(def ^:private walk-fields #{"walk" "walk_promises" "judgment"})
(def ^:private model-fields #{"charter" "instructions"})
(def ^:private check-fields #{"holds" "by" "remedy"})

(defn- door-fields
  "The fields a door's whole input states: its edit prefill, else the
  keys of its input map."
  [rdef door]
  (let [a (get-in rdef [:actions door])
        s (:input a)]
    (vec (or (some->> (get-in a [:edit :prefill]) (map text))
             (when (and (vector? s) (= :map (first s)))
               (keep #(when (vector? %) (text (first %))) (rest s)))))))

(defn- walked-kinds
  "The declarations of the kinds either side of the seat walks."
  [eng seat after]
  (into [] (comp (keep #(some-> (get-in % [:data :walk]) text not-empty keyword))
                 (distinct)
                 (keep (inv/resources eng)))
        [seat after]))

(def ^:private read-tokens
  "`walk-reads`' maps, each named by the kind it reads as it stands now."
  [[:tickets "current_data:ticket"]
   [:judgments "current_data:judgment"]
   [:breaches "current_data:promise"]])

(defn- walk-part
  "R-A.3.3's first two bullets: the walk and holder replay of every kind
  either side of the seat walks, beside the other active seats. The
  tickets, judgments and breaches `walk-admits?` reads are today's
  (`waiting-on/walk-reads`), and `blind` names each that was read."
  [eng hooks w seat after]
  (let [by-kind (inv/resources eng)
        rdefs (walked-kinds eng seat after)
        others (into [] (remove #(= (str (:id %)) (str (:id seat))))
                     ((:find hooks) :seat {:state "active"} {:limit 500}))
        history (replay/history (:storage eng) rdefs w)
        reads (store/with-tx (:storage eng)
                #(waiting-on/walk-reads eng % (into [seat after] others)
                                        (for [[_ r] (:rows history) m (:timeline r)]
                                          {:data (or (:data m) {})})))
        runs (mapv #(replay-walks/replay {:rdef % :by-kind by-kind :history history
                                          :seats others :before seat :after after
                                          :reads reads :scheduled (:scheduled reads)})
                   rdefs)
        tagged (fn [k] (into [] (mapcat (fn [r] (map #(assoc % :kind (:kind r)) (get r k)))) runs))]
    {:rows_read (:rows_read history)
     :walk {:added (tagged :adds) :dropped (tagged :drops)}
     :holders {:moved (tagged :holders)
               :to_person (into [] (mapcat :to_person) runs)
               :to_nobody (into [] (mapcat :to_nobody) runs)}
     :blind (into (vec (:blind history))
                  (keep (fn [[k token]]
                          (when-some [xs (not-empty (get reads k))]
                            {:token token :moments (count xs)})))
                  read-tokens)}))

(defn- promise-part
  "R-A.3.3's third bullet: the promise's query check under each side,
  over its `about` kind."
  [eng hooks w promise after]
  (let [about (some-> (fget (fget after :about) :kind) text keyword)
        rdef (get (inv/resources eng) about)
        history (replay/history (:storage eng) (if rdef [rdef] []) w)
        r (replay-promises/replay {:history history :before (:data promise) :after after
                                   :window w :calendar (:calendar hooks)})
        pair (fn [k] [(get-in r [:before k]) (get-in r [:after k])])]
    {:rows_read (:rows_read history)
     :promises {(keyword (str (:id promise))) {:breaks (pair :breaks) :keeps (pair :keeps)}}
     :blind (:blind r)}))

(defn- merge-parts [a b]
  (cond (nil? a) b
        (nil? b) a
        :else (-> (merge a b)
                  (assoc :rows_read (+ (or (:rows_read a) 0) (or (:rows_read b) 0))
                         :promises (merge (:promises a) (:promises b))
                         :blind (into (vec (:blind a)) (:blind b))))))

(defn- answers-part
  "R-A.3.3's third bullet for a seat change: every promise in the
  amendment's `answers` whose `about` is a kind the seat walks either
  side, its query check re-run as it stands."
  [eng hooks w amendment seat after]
  (when (contains? (inv/resources eng) :promise)
    (let [walked (into #{} (map #(text (:kind %))) (walked-kinds eng seat after))
          pids (into [] (comp (keep #(some-> (fget % :promise) text not-empty)) (distinct))
                     (fget (:data amendment) :answers))]
      (reduce merge-parts nil
              (for [pid pids
                    :let [p ((:read hooks) :promise pid)]
                    :when (and p (walked (some-> (fget (fget (:data p) :about) :kind) text)))]
                (promise-part eng hooks w p (:data p)))))))

(defn- guard-part
  "R-A.3.3's fourth bullet: each logged move on the target's kind through
  a door whose guards read a patched field, judged again
  (`replay-guards/replay`)."
  [eng w rdef fields]
  (let [doors (into {}
                    (keep (fn [[door a]]
                            (when-some [gs (seq (filter map? (:guards a)))]
                              [door (vec gs)])))
                    (:actions rdef))]
    (replay-guards/replay (:storage eng) rdef w {:doors doors :patched (mapv keyword fields)})))

(defn- report
  "The replay report (R-A.3.7), with the target as the replay read it:
  the stale check of R-A.2.7 and the adopt's whole input stand on it."
  [w fields part guards target rdef door]
  (walk/postwalk
   #(if (instance? Instant %) (str %) %)
   {:window w
    :rows_read (+ (or (:rows_read part) 0) (or (:rows_read guards) 0))
    :walk (or (:walk part) {:added [] :dropped []})
    :holders (or (:holders part) {:moved [] :to_person [] :to_nobody []})
    :promises (or (:promises part) {})
    :refusals {:would_refuse (count (:refused guards))}
    :blind (let [gb (mapv #(select-keys % [:token :moments]) (:blind guards))]
             (cond-> (into (vec (:blind part)) gb)
               (some model-fields fields) (conj {:token "model_judgment" :moments 1})
               (not-any? #(= "refusals" (:token %)) gb) (conj {:token "refusals" :moments 0})))
    :target {:kind (text (:kind rdef)) :id (str (:id target))
             :version (text (:version target))
             :seen (seen-of (:data target))
             :input (door-fields rdef door)}}))

(defn replay!
  "The engine's replay of amendment `id` (R-A.3): the target as it
  stands and as the patch would leave it, over a window of `days`
  (default 14) ending now, written through the `replay` door."
  ([eng id] (replay! eng id nil))
  ([eng id {:keys [days]}]
   (let [hooks (inv/render-hooks eng)
         amendment ((:read hooks) :amendment id)
         {:keys [kind door patch] tid :id} (target-of amendment)
         rdef (get (inv/resources eng) kind)
         target (when rdef ((:read hooks) kind tid))
         _ (when-not target
             (throw (ex-info (str "amendment " id " names no " (text kind) " `" tid "`")
                             {:id id})))
         fields (set (map text (keys patch)))
         after (update target :data merge patch)
         w (replay/window ((:now-fn eng)) days)
         part (case (text kind)
                "seat" (merge-parts
                        (when (some walk-fields fields) (walk-part eng hooks w target after))
                        (answers-part eng hooks w amendment target after))
                "promise" (when (some check-fields fields)
                            (promise-part eng hooks w target (:data after)))
                nil)
         guards (guard-part eng w rdef fields)]
     (inv/invoke! eng :amendment id :replay
                  {:report (report w fields part guards target rdef door)}
                  {:principal engine-actor
                   :idempotency-key (str "amendment-replay:" id ":" (:version amendment))}))))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 amendments: " parts))))

(defn- draft-ids [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (mapv #(str (:id %))
              (store/search-rows st tx :amendment
                                 [{:target :state :op :in :values ["draft"]}]
                                 {:order-by nil :desc nil :limit 1000}))))))

(defn sweep!
  "`replay!` every amendment in `draft`, so a filed or restated one
  reaches `replayed` and its owner's adopt door. Rides the live cracks
  clock beside `promises/sweep!`; one that will not replay is warned
  and left in `draft` for the next pass."
  [eng]
  (when (contains? (inv/resources eng) :amendment)
    (try (doseq [id (draft-ids eng)]
           (try (replay! eng id)
                (catch Exception e (warn! "amendment " id ": " (ex-message e)))))
         (catch Exception e (warn! "the sweep failed — " (ex-message e)))))
  nil)

(defhandler withdraw-amendment
  [row inp _ctx]
  (assoc-in row [:data :withdrawn_because] (:why inp)))

(defhandler settle-amendment
  ;; R-A.5.5: the trial's readings and verdict, written by the engine
  ;; (waymark10.server.amendment-trial) as it settles the row
  [row inp _ctx]
  (update row :data assoc :trial (:trial inp) :verdict (:verdict inp)))

(def ^:private engine-safety
  {:idempotent false :reversible false :confirm false
   :one-way "The engine's reading; a restate sends the amendment back to be read again."})

(defresource amendment
  {:kind :amendment
   :plural "amendments"
   :nav :secondary
   :states [:draft :replayed :trial :settled :reverted :withdrawn]
   :initial :draft
   :terminal #{:reverted :withdrawn}
   ;; reached by `adopt` (step 7) and left by `revert` (step 9)
   :allow-dead #{:trial :settled :reverted}
   :retain {:data true}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :schema [:map
            title-field
            answers-field
            changes-field
            [:owner {:optional true
                     :x-ref {:principal true}
                     :x-display {:label "Owner" :help "The member who adopts: the house owner unless the filer names another."}}
             [:maybe [:string {:max 120}]]]
            [:before {:optional true} report-schema]
            [:after {:optional true} report-schema]
            [:replay {:optional true
                      :x-display {:label "Replay" :help "The engine's replay report (§3): what the last weeks would have been under the change, and what it was blind to."}}
             report-schema]
            [:trial {:optional true} report-schema]
            [:revert {:optional true
                      :x-display {:label "Revert" :help "What the revert restored, left as it was, and left because another hand changed it (R-A.6.3)."}}
             report-schema]
            [:verdict {:optional true}
             [:maybe [:enum "better" "same" "worse" "quiet" "confounded" "unjudged"]]]
            [:mode {:optional true} [:maybe [:enum "watch" "revert"]]]
            [:filed_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 120}]]]
            [:key {:optional true} [:maybe [:string {:max 200}]]]
            [:withdrawn_because {:optional true} [:maybe [:string {:max 480}]]]]
   :filterable {:state #{:eq :in}}
   :create-guards [an-amendment-writer an-amendment-stays-in-its-lane
                   a-seats-amendment-answers-a-promise]
   :on-create born-amendment
   :actions
   {:restate {:from #{:draft :replayed} :to :draft
              :input [:map (optional title-field) answers-field (optional changes-field)]
              :record true
              :guards [the-filer-or-the-owner an-amendment-stays-in-its-lane
                       a-seats-amendment-answers-a-promise]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A restate clears the replay; the engine replays the row as it now stands."}
              :handler restate-amendment
              :display {:label "Restate" :style :primary :order 1}}
    :replay {:from #{:draft} :to :replayed
             :input [:map [:report report-schema]]
             :guards [the-engine-moves-it]
             :safety engine-safety
             :handler replay-amendment
             :display {:label "Replay" :order 2}}
    :adopt {:from #{:replayed} :to :trial
            :guards [the-owner-adopts the-target-has-not-moved]
            :touches [{:kind :seat :action :restate} {:kind :promise :action :restate}]
            :safety {:idempotent false :reversible false :confirm false
                     :one-way "The target's own restate writes the change; `before` keeps what it overwrote."}
            :handler adopt-amendment
            :display {:label "Adopt" :style :primary :order 3}}
    :settle {:from #{:trial} :to :settled
             :input [:map [:trial report-schema]
                     [:verdict [:enum "better" "same" "worse" "quiet" "confounded" "unjudged"]]]
             :guards [the-engine-moves-it]
             :safety engine-safety
             :handler settle-amendment
             :display {:label "Settle" :order 3}}
    :revert amendment-revert/revert-door
    :keep amendment-revert/keep-door
    :withdraw {:from #{:draft :replayed} :to :withdrawn
               :input [:map [:why {:x-display {:label "Why" :help "One sentence for the owner: why this amendment is let go."}}
                             [:string {:min 1 :max 480}]]]
               :record true
               :guards [the-filer-or-the-owner]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A withdrawn amendment stays in the log; a new one is one create away."}
               :handler withdraw-amendment
               :display {:label "Withdraw" :style :danger :order 9}}}})
