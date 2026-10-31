(ns waymark10.server.promises
  "Promises (docs/spec-promise.md §14 steps 2 and 3): what must hold, by
  when, and who owes it. Standing is the state, moved only by the
  engine's hand (`risk`, `break`, `keep`) on every transition of the
  `about` kind (`hear!`) and on the live cracks clock (`sweep!`). A
  break writes its debtor; in `enforced` mode it fires a model seat
  once, and a breach only a person can fix reaches the owner as one
  held call. A promise that names a seat waits `proposed` for its accept.
  Opted into like `goal`, through an app's resources vector."
  (:require [clojure.string :as str]
            [waymark10.age :as age]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.goals :as goals]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.measure :as measure]
            [waymark10.server.members :as members]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]
            [waymark10.server.waiting-on :as waiting-on]
            [waymark10.server.wakes :as wakes]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 promises: " parts))))

(defn- text [v] (if (keyword? v) (name v) (some-> v str)))

(defn- fget [m k]
  (when (map? m)
    (let [v (get m (keyword (text k)))]
      (if (some? v) v (get m (text k))))))

(defn- inst ^Instant [v]
  (cond (instance? Instant v) v
        (instance? java.util.Date v) (.toInstant ^java.util.Date v)
        (some? v) (try (Instant/parse (str v)) (catch Exception _ nil))))

(def engine-actor
  (t/principal {:id "waymark10-promises" :type :system :display "Promises"}))

(def sweep-seconds 900)

(def ^:private waits-for #{:damped :throttled :spoken_for})

(g/defguard a-promise-writer
  {:reads [:principal :member :domain]
   :explain "A promise is the house owner's to make: this door wants the factory domain's owner, or a seat whose grant names `promise`."
   :open "The factory domain's owner is set by restating that domain; a seat is given `promise` on its grant."}
  [_row _inp ctx]
  (if (goals/goal-writer? ctx) (t/allow) (t/deny)))

(g/defguard the-engine-judges-it
  {:reads [:principal]
   :hide true
   :explain "A promise's standing is moved by the engine's own check; no hand at the wire marks one at risk, broken or kept — fix the row instead."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx))) (t/allow) (t/deny)))

(defn- seat-ids-of
  "The seats this principal sits in: its live grants' seats, and the
  seat a sitter's own id names."
  [ctx]
  (let [sid (members/seat-id (get-in ctx [:principal :id]))]
    (cond-> (delegation/cited-seats ctx)
      sid (conj sid))))

(g/defguard the-named-debtor-answers
  {:reads [:principal :grant :seat]
   :explain "Only the seat this promise names as its debtor accepts or declines it: a seat cannot be made to owe what it never took."
   :open "The owner names another seat by restating the promise's debtor."}
  [row _inp ctx]
  (let [debtor (some-> (fget (:data row) :debtor) text not-empty)
        read' (:read ctx)
        named? (fn [sid] (= debtor (some-> (read' :seat sid) :data (fget :name) text)))]
    (if (and debtor read' (some named? (seat-ids-of ctx))) (t/allow) (t/deny))))

(g/defguard the-seats-person-approves
  {:reads [:principal :held_call :within]
   :hold true
   :explain "A seat takes on a promise under its person's approval (R-P.2.6): this accept waits as a held_call for their tap."
   :open "No door clears this one. The person's Allow replays the accept as the seat."}
  [row _inp ctx]
  (if (holds/approved-hold? ctx :promise :accept (:id row)) (t/allow) (t/deny)))

(def ^:private filter-schema
  [:maybe [:map-of :keyword [:string {:max 200}]]])

(def ^:private name-field
  [:name {:x-display {:label "Promise" :help "A short sentence a person reads: merged content is live within 2 h."}}
   [:string {:min 3 :max 200}]])

(def ^:private holds-field
  [:holds {:x-display {:label "Holds" :help "The check, {query {kind, filter, expect}}: expect none (no row may match), some, or count_lte with count_lte n. A filter value {id} is the about row's id."}}
   [:map
    [:query [:map
             [:kind [:string {:min 1 :max 80}]]
             [:filter {:optional true} filter-schema]
             [:expect [:enum "none" "some" "count_lte"]]
             [:count_lte {:optional true} [:maybe [:int {:min 0}]]]]]]])

(def ^:private by-field
  [:by {:x-display {:label "By" :help "{after <action>, within <age>}: a deadline from the row's last <action>; or {window <age>}: the check must pass at every sweep. An age is seconds, {business_days n} or {until <form>}."}}
   [:map
    [:after {:optional true} [:maybe [:string {:max 80}]]]
    [:within {:optional true} :any]
    [:window {:optional true} :any]]])

(def ^:private remedy-field
  [:remedy {:x-display {:label "Remedy" :help "{kind, door}: the door that fixes a break, on the about row, or on the row its field `via` names."}}
   [:map
    [:kind [:string {:min 1 :max 80}]]
    [:door [:string {:min 1 :max 80}]]
    [:via {:optional true} [:maybe [:string {:max 80}]]]]])

(def ^:private debtor-field
  [:debtor {:optional true
            :x-display {:label "Debtor" :help "A seat's name. Left out, the debtor is computed per break from who holds the remedy door."}}
   [:maybe [:string {:max 120}]]])

(def ^:private mark-schema
  [:maybe [:vector [:map [:id :string] [:at :string]]]])

(def ^:private breach-schema
  [:map
   [:id :string]
   [:since :string]
   [:cause [:string {:max 480}]]
   [:debtor {:optional true} [:maybe :string]]
   [:person {:optional true} [:maybe :boolean]]
   [:transition {:optional true} [:maybe :string]]
   [:woken {:optional true} [:maybe :boolean]]
   [:woken_at {:optional true} [:maybe :string]]
   [:escalated {:optional true} [:maybe :boolean]]])

(def ^:private move-input
  [:map
   [:row {:optional true} [:maybe [:string {:max 120}]]]
   [:cause {:optional true} [:maybe [:string {:max 480}]]]
   [:debtor {:optional true} [:maybe [:string {:max 120}]]]
   [:at :string]])

(defn- optional [[k props s]]
  [k (assoc props :optional true) s])

(defn- without [marks id]
  (vec (remove #(= id (text (fget % :id))) marks)))

(defn- recent [xs ^Instant now]
  (filterv #(some-> (inst %) (.plusSeconds 86400) (.isAfter now)) (map text xs)))

(defhandler restate-promise
  [row inp _ctx]
  ;; a changed debtor owes nothing until it accepts (R-P.2.4)
  (let [was (some-> (fget (:data row) :debtor) text not-empty)
        named (if (contains? inp :debtor) (some-> (:debtor inp) text not-empty) was)
        row (update row :data merge (select-keys inp [:name :holds :by :remedy :debtor]))]
    (cond
      (and named (not= named was))
      (-> row (assoc :state :proposed) (update :data assoc :breaches [] :passes [] :confirms []))
      (and (nil? named) (= "proposed" (text (:state row)))) (assoc row :state :kept)
      :else row)))

(defhandler accept-promise [row _inp _ctx] (assoc row :state :kept))

(defhandler decline-promise
  [row inp _ctx]
  (-> row (assoc :state :retired) (assoc-in [:data :declined] (:why inp))))

(defhandler enforce-promise [row _inp _ctx] (assoc-in row [:data :mode] "enforced"))

(defhandler watch-promise [row _inp _ctx] (assoc-in row [:data :mode] "watch"))

(defhandler break-promise
  [row inp _ctx]
  (let [id (text (:row inp))]
    (update row :data
            (fn [d]
              (-> d
                  (assoc :breaches (conj (without (fget d :breaches) id)
                                         (cond-> {:id id :since (:at inp)
                                                  :cause (or (:cause inp) "")}
                                           (:debtor inp) (assoc :debtor (:debtor inp)))))
                  (assoc :confirms (without (fget d :confirms) id)))))))

(defhandler keep-promise
  [row inp _ctx]
  (let [d (:data row)
        id (text (:row inp))
        left (if id (without (fget d :breaches) id) (vec (fget d :breaches)))
        to (if (seq left) :broken :kept)]
    (-> row
        (assoc :state to)
        (update :data assoc
                :breaches left
                :passes (if id (without (fget d :passes) id) (vec (fget d :passes)))
                :kept_at (cond-> (recent (fget d :kept_at) (inst (:at inp)))
                           (= :kept to) (conj (:at inp)))))))

(def ^:private standing #{:kept :at_risk :broken})

(def ^:private open-states (conj standing :proposed))

(defn- born-promise
  "A named debtor owes nothing until it accepts (R-P.2.6), so such a
  promise is born `proposed`; its owner is the one who made it unless
  it names one."
  [row ctx]
  (let [d (:data row)
        p (:principal ctx)
        owner (or (some-> (fget d :owner) text not-empty)
                  (when (not= :system (:type p))
                    (some-> (or (:acts-for p) (:id p)) str not-empty)))]
    (cond-> row
      owner (assoc-in [:data :owner] owner)
      (some-> (fget d :debtor) text not-empty) (assoc :state :proposed))))

(def ^:private engine-safety
  {:idempotent false :reversible false :confirm false
   :one-way "Standing is the engine's reading; its next check moves it again."})

(defresource promise
  {:kind :promise
   :plural "promises"
   :nav :secondary
   :states [:proposed :kept :at_risk :broken :retired]
   :initial :kept
   :terminal #{:retired}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map
            name-field
            [:about {:x-display {:label "About" :help "{kind, id} for one row, or {kind, filter} for every row the filter admits."}}
             [:map
              [:kind [:string {:min 1 :max 80}]]
              [:id {:optional true :x-ref {:kind-from :kind}} [:maybe [:string {:max 120}]]]
              [:filter {:optional true} filter-schema]]]
            holds-field
            by-field
            remedy-field
            debtor-field
            [:mode {:optional true
                    :x-display {:label "Mode" :help "watch checks and moves but wakes nobody; enforced wakes the debtor. Born watch."}}
             [:maybe [:enum "watch" "enforced"]]]
            [:owner {:optional true
                     :x-ref {:principal true}
                     :x-display {:label "Owner" :help "The member the promise is for."}}
             [:maybe [:string {:max 120}]]]
            [:breaches {:optional true} [:maybe [:vector breach-schema]]]
            [:passes {:optional true} mark-schema]
            [:confirms {:optional true} mark-schema]
            [:kept_at {:optional true} [:maybe [:vector :string]]]
            [:flapping {:optional true} [:maybe :boolean]]
            [:declined {:optional true} [:maybe [:string {:max 480}]]]
            [:last_checked {:optional true} [:maybe :string]]
            [:last_error {:optional true} [:maybe [:string {:max 480}]]]]
   :filterable {:state #{:eq :in}}
   :create-guards [a-promise-writer]
   :on-create born-promise
   :actions
   {:restate {:from open-states :to open-states
              :input [:map (optional name-field) (optional holds-field) (optional by-field)
                      (optional remedy-field) debtor-field]
              :record true
              :guards [a-promise-writer]
              :safety {:idempotent true :reversible true :confirm false}
              :handler restate-promise
              :display {:label "Restate" :style :primary :order 1}}
    :enforce {:from open-states :to :same
              :guards [a-promise-writer]
              :safety {:idempotent true :reversible true :confirm false}
              :handler enforce-promise
              :display {:label "Enforce" :order 2}}
    :watch {:from open-states :to :same
            :guards [a-promise-writer]
            :safety {:idempotent true :reversible true :confirm false}
            :handler watch-promise
            :display {:label "Watch" :order 3}}
    :risk {:from #{:kept} :to :at_risk
           :input move-input :record true
           :guards [the-engine-judges-it]
           :safety engine-safety
           :display {:label "At risk" :order 5}}
    :break {:from standing :to :broken
            :input move-input :record true
            :guards [the-engine-judges-it]
            :safety engine-safety
            :handler break-promise
            :display {:label "Break" :order 6}}
    :keep {:from #{:at_risk :broken} :to #{:kept :broken}
           :input move-input :record true
           :guards [the-engine-judges-it]
           :safety engine-safety
           :handler keep-promise
           :display {:label "Keep" :order 7}}
    :accept {:from #{:proposed} :to :kept
             :guards [the-named-debtor-answers the-seats-person-approves]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An accepted promise is owed; the owner restates or retires it."}
             :handler accept-promise
             :display {:label "Accept" :style :primary :order 4}}
    :decline {:from #{:proposed} :to :retired
              :input [:map [:why {:x-display {:label "Why not" :help "One sentence for the owner: why this seat will not owe it."}}
                            [:string {:min 1 :max 480}]]]
              :record true
              :guards [the-named-debtor-answers]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined promise is retired; the owner makes a new one."}
              :handler decline-promise
              :display {:label "Decline" :style :danger :order 8}}
    :retire {:from open-states :to :retired
             :guards [a-promise-writer]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired promise stays in the log; a new one is one create away."}
             :display {:label "Retire" :style :danger :order 9}}}})

(defn- serves? [eng kind] (contains? (inv/resources eng) kind))

(defn serving? [eng] (serves? eng :promise))

(defn- rdef-of [eng kind] (get (inv/resources eng) (keyword (text kind))))

(defn- decoded [rdef raw]
  (assoc (inv/decode-row rdef raw) :kind (:kind rdef)))

(defn- load-row [eng kind id]
  (when-some [rdef (rdef-of eng kind)]
    (let [st (:storage eng)]
      (some->> (store/with-tx st (fn [tx] (store/load-row st tx (:kind rdef) (str id) {})))
               (decoded rdef)))))

(defn row-of [eng id] (load-row eng :promise id))

(defn- search
  "The raw rows a `filter` admits, `{id}` bound; over read-cap throws."
  [eng rdef filter id]
  (let [f (into {} (map (fn [[k v]] [(text k) (if (= "{id}" (text v)) (str id) (text v))]))
                filter)
        {:keys [conds]} (collections/parse-query rdef (dissoc f "id") {:defaults? false})
        conds (cond-> (vec conds)
                (get f "id") (conj {:target :id :op :in :values [(get f "id")]}))
        st (:storage eng)
        raws (store/with-tx st
               (fn [tx]
                 (store/search-rows st tx (:kind rdef) conds
                                    {:order-by nil :desc nil :limit (inc measure/read-cap)})))]
    (when (< measure/read-cap (count raws))
      (throw (ex-info (str "over " measure/read-cap " rows match; narrow the filter") {})))
    raws))

(defn- about-rows [eng about]
  (try
    (let [rdef (rdef-of eng (fget about :kind))]
      (cond
        (nil? rdef) {:error (str "about names `" (text (fget about :kind)) "`, a kind this engine does not serve")}
        (fget about :id) {:rows (keep identity [(load-row eng (:kind rdef) (fget about :id))])}
        :else {:rows (mapv #(decoded rdef %) (search eng rdef (fget about :filter) nil))}))
    (catch Exception e {:error (or (ex-message e) (str e))})))

(defn- check
  "The query check over one about row (R-P.3.2): [:pass|:fail|:error cause]."
  [eng holds row]
  (try
    (let [q (fget holds :query)
          rdef (rdef-of eng (fget q :kind))]
      (if (nil? rdef)
        [:error (str "the query names `" (text (fget q :kind)) "`, a kind this engine does not serve")]
        (let [n (count (search eng rdef (fget q :filter) (:id row)))
              expect (text (fget q :expect))
              lte (long (or (fget q :count_lte) 0))
              ok (case expect "none" (zero? n) "some" (pos? n) (<= n lte))]
          (if ok
            [:pass nil]
            [:fail (str (text (fget q :kind)) " expected "
                        (case expect "none" "no row" "some" "a row" (str "at most " lte))
                        " and read " n)]))))
    (catch Exception e [:error (or (ex-message e) (str e))])))

(defn- entered-at [eng row action]
  (let [st (:storage eng)]
    (some #(when (= action (text (:action %))) (inst (:at %)))
          (store/with-tx st
            (fn [tx]
              (store/transitions st tx {:kind (:kind row) :resource-id (str (:id row))}
                                 {:limit 200 :newest-first true}))))))

(defn- age-entry [v]
  (if (map? v)
    (into {} (map (fn [[k x]] [(keyword (str/replace (text k) "_" "-")) x])) v)
    v))

(defn- due
  "{:due? :risky?} under `by` (R-P.4.2); a window is always due."
  [eng by row ^Instant now]
  (if (some? (fget by :window))
    {:due? true}
    (let [^Instant start (some->> (fget by :after) text (entered-at eng row))
          within (age-entry (fget by :within))
          ^Instant dl (when start
               (if (number? within)
                 (.plusSeconds start (long within))
                 (age/deadline within row start now (:calendar (inv/render-hooks eng)))))]
      (if (nil? dl)
        {:due? false}
        (let [half (.dividedBy (Duration/between start dl) 2)]
          {:due? (not (.isBefore now dl))
           :risky? (not (.isBefore now (.plus start half)))})))))

(defn- span-seconds [by]
  (let [w (fget by :within)] (if (number? w) (long w) sweep-seconds)))

(defn- stamp!
  "Merge `m` into the promise's data, with no transition."
  [eng pid m]
  (when-some [p (row-of eng pid)]
    (let [st (:storage eng)
          rdef (rdef-of eng :promise)]
      (store/with-tx st
        (fn [tx]
          (store/maintain! :stamp st tx :promise pid
                           (schema/encode (:schema rdef) (merge (:data p) m))
                           nil))))))

(defn- breach-stamp! [eng pid id m]
  (when-some [p (row-of eng pid)]
    (stamp! eng pid {:breaches (mapv #(if (= id (text (fget % :id))) (merge % m) %)
                                     (fget (:data p) :breaches))})))

(defn- move! [eng pid action input]
  (inv/invoke! eng :promise pid action input
               {:principal engine-actor
                :idempotency-key (str "promise:" pid ":" (name action) ":"
                                      (:row input) ":" (:at input))}))

(defn- newest-transition [eng pid]
  (let [st (:storage eng)]
    (some-> (store/with-tx st
              (fn [tx]
                (store/transitions st tx {:kind :promise :resource-id pid}
                                   {:limit 1 :newest-first true})))
            first :id str)))

(defn- remedy-row
  "The row `remedy` acts on for this about row: itself, or the row its
  `via` field names."
  [eng d row]
  (let [remedy (fget d :remedy)
        via (some-> (fget remedy :via) text not-empty)]
    (if via (load-row eng (fget remedy :kind) (fget (:data row) via)) row)))

(defn- debtor-of [eng d row]
  (try
    (let [remedy (fget d :remedy)
          target (remedy-row eng d row)
          rdef (when target (rdef-of eng (:kind target)))
          pick (when rdef
                 (waiting-on/debtor (waiting-on/for-row eng rdef target (:data target))
                                    (fget remedy :door)))]
      (when pick {:name (text (:seat pick)) :person? (boolean (:person pick))}))
    (catch Exception e
      (warn! "no debtor for " (:id row) " — " (ex-message e))
      nil)))

(defn- seat-id-of [eng seat-name]
  (let [st (:storage eng)]
    (some #(when (= seat-name (text (fget (:data %) :name))) (str (:id %)))
          (store/with-tx st
            (fn [tx] (store/query-rows st tx :seat {:state :active} {:limit 500}))))))

(defn- wake!
  "Fire the debtor seat as the engine (R-P.5.2): :fired, :held or :refused."
  [eng p b ^Instant now]
  (let [d (:data p)
        about (fget d :about)
        id (text (fget b :id))
        seat-id (seat-id-of eng (text (fget b :debtor)))
        state (some-> (load-row eng (fget about :kind) id) :state text)
        txt (wire/write-json {:kind (text (fget about :kind)) :id id :action "break"
                              :from state :to state :promise (str (:id p))
                              :cause (text (fget b :cause))
                              :door (text (fget (fget d :remedy) :door))})
        held (when seat-id (wakes/wake-held eng seat-id txt now))]
    (cond
      (nil? seat-id) (do (warn! "no active seat " (fget b :debtor) "; promise " (:id p) " wakes nobody")
                         :refused)
      (contains? waits-for held) :held
      (some? held) (do (warn! "seat " (fget b :debtor) " is " (name held) "; promise " (:id p) " wakes nobody")
                       :refused)
      :else (try
              (inv/invoke! eng :seat seat-id :fire {:text txt}
                           {:principal schedules/system-actor
                            :idempotency-key (str "promise:" (:id p) ":" id ":" (text (fget b :transition)))})
              :fired
              (catch Exception e
                (warn! "seat " (fget b :debtor) " would not be woken on promise " (:id p) " — " (ex-message e))
                :refused)))))

(defn- walks?
  "Could `person` walk `door` on `row` now? Rehearsed as them."
  [eng person row door]
  (try
    (inv/invoke! eng (:kind row) (str (:id row)) (keyword door) {}
                 {:principal (t/principal {:id person :display person}) :dry-run true})
    true
    (catch Exception _ false)))

(defn- escalate!
  "Propose ONE held call to the promise's owner for this breach
  (R-P.5.3): the remedy door when the owner holds it, retire, and watch."
  [eng p b]
  (let [d (:data p)
        pid (str (:id p))
        id (text (fget b :id))
        owner (some-> (fget d :owner) text not-empty)
        door (text (fget (fget d :remedy) :door))
        about (load-row eng (fget (fget d :about) :kind) id)
        target (when about (remedy-row eng d about))
        fix (when (and owner target (walks? eng owner target door))
              [{:kind (:kind target) :id (str (:id target)) :door door
                :label (str "Fix it: " door)}])]
    (if (nil? owner)
      (warn! "promise " pid " names no owner; its breach on " id " reaches nobody")
      (try
        (held-calls/offer!
         eng {:person owner
              :choices (into (vec fix)
                             [{:kind :promise :id pid :door :retire :label "Retire this promise"}
                              {:kind :promise :id pid :door :watch :label "Switch to watch"}])
              :source (str "promise:" pid)
              :key (str id ":" (text (fget b :transition)))
              :why (str "\"" (text (fget d :name)) "\" is broken on " id
                        (some->> (some-> (fget b :cause) text not-empty) (str ": "))
                        ". Only you can move it.")})
        (catch Exception e
          (warn! "promise " pid " could not escalate " id " — " (ex-message e)))))
    (breach-stamp! eng pid id {:escalated true})))

(defn- wake-due!
  "Settle an enforced breach (R-P.5.2, R-P.5.3, R-P.4.3): wake its
  model-seat debtor once; escalate to the owner, once, when a person or
  nobody holds the remedy, when the promise flaps, or when the woken
  seat left it standing one `by` span."
  [eng pid id ^Instant now]
  (let [p (row-of eng pid)
        d (:data p)
        b (some #(when (= id (text (fget % :id))) %) (fget d :breaches))
        ^Instant woken (some-> (fget b :woken_at) inst)]
    (when (and b (= "enforced" (text (fget d :mode))) (not (fget b :escalated)))
      (cond
        (or (fget d :flapping) (fget b :person) (nil? (fget b :debtor)))
        (escalate! eng p b)

        (not (fget b :woken))
        (when (not= :held (wake! eng p b now))
          (breach-stamp! eng pid id {:woken true :woken_at (str now)}))

        (and woken (not (.isAfter (.plusSeconds woken (span-seconds (fget d :by))) now)))
        (escalate! eng p b)))))

(defn- enter!
  "A row failing past `by` enters `breaches` (R-P.4.3, R-P.4.4)."
  [eng pid {:keys [row cause]} ^Instant now]
  (let [p (row-of eng pid)
        d (:data p)
        id (str (:id row))
        kept (recent (fget d :kept_at) now)
        ^Instant last-keep (some-> (peek kept) inst)
        confirms (vec (fget d :confirms))]
    (if (and (= "kept" (text (:state p))) last-keep
             (.isBefore now (.plusSeconds last-keep (span-seconds (fget d :by))))
             (not-any? #(= id (text (fget % :id))) confirms))
      (stamp! eng pid {:confirms (conj confirms {:id id :at (str now)})})
      (let [named (some-> (fget d :debtor) text not-empty)
            debtor (if named {:name named :person? false} (debtor-of eng d row))]
        (move! eng pid :break (cond-> {:row id :cause cause :at (str now)}
                                debtor (assoc :debtor (:name debtor))))
        (stamp! eng pid {:flapping (<= 3 (count kept))})
        (breach-stamp! eng pid id {:transition (newest-transition eng pid)
                                   :person (boolean (:person? debtor))})
        (wake-due! eng pid id now)))))

(defn- pass!
  "A breached row leaves on its second pass a sweep apart (R-P.4.3)."
  [eng pid id ^Instant now]
  (let [passes (vec (fget (:data (row-of eng pid)) :passes))
        ^Instant first-at (some #(when (= id (text (fget % :id))) (inst (fget % :at))) passes)]
    (cond
      (nil? first-at) (stamp! eng pid {:passes (conj passes {:id id :at (str now)})})
      (<= sweep-seconds (.getSeconds (Duration/between first-at now)))
      (move! eng pid :keep {:row id :at (str now)}))))

(defn evaluate!
  "Check one promise at the engine's now and move its standing."
  [eng pid]
  (let [^Instant now ((:now-fn eng))
        at (str now)
        p (row-of eng pid)]
    (when (and p (standing (keyword (text (:state p)))))
      (let [d (:data p)
            {:keys [rows error]} (about-rows eng (fget d :about))]
        (if error
          (stamp! eng pid {:last_error error :last_checked at})
          (let [results (mapv (fn [row]
                                (let [[v cause] (check eng (fget d :holds) row)]
                                  (merge {:row row :verdict v :cause cause}
                                         (when (= :fail v) (due eng (fget d :by) row now)))))
                              rows)
                by-id (into {} (map (juxt #(str (:id (:row %))) identity)) results)
                failing (into (sorted-set) (keep #(when (and (= :fail (:verdict %)) (:due? %))
                                                    (str (:id (:row %)))))
                              results)
                breached (into (sorted-set) (map #(text (fget % :id))) (fget d :breaches))
                errors (keep #(when (= :error (:verdict %)) (:cause %)) results)]
            (doseq [id failing :when (not (breached id))]
              (enter! eng pid (by-id id) now))
            (doseq [id breached]
              (cond
                (failing id) (do (stamp! eng pid {:passes (without (fget (:data (row-of eng pid)) :passes) id)})
                                 (wake-due! eng pid id now))
                (= :error (:verdict (by-id id))) nil
                :else (pass! eng pid id now)))
            (let [p' (row-of eng pid)
                  risky (first (filter #(and (= :fail (:verdict %)) (:risky? %) (not (:due? %))) results))]
              (cond
                (and (= "kept" (text (:state p'))) risky)
                (move! eng pid :risk {:row (str (:id (:row risky))) :cause (:cause risky) :at at})

                (and (= "at_risk" (text (:state p'))) (empty? (fget (:data p') :breaches))
                     (every? #(= :pass (:verdict %)) results))
                (move! eng pid :keep {:at at})))
            (stamp! eng pid {:last_checked at :last_error (first errors)})))))))

(defn- standing-ids [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (mapv (juxt #(str (:id %)) #(text (fget (fget (:data %) :about) :kind)))
              (store/search-rows st tx :promise
                                 [{:target :state :op :in :values ["kept" "at_risk" "broken"]}]
                                 {:order-by nil :desc nil :limit 1000}))))))

(defn- evaluate-quietly! [eng pid]
  (try (evaluate! eng pid) (catch Exception e (warn! "promise " pid ": " (ex-message e)))))

(defn sweep! [eng]
  (when (serving? eng)
    (try (doseq [[pid _] (standing-ids eng)] (evaluate-quietly! eng pid))
         (catch Exception e (warn! "the sweep failed — " (ex-message e)))))
  nil)

(defn hear! [eng t]
  (when (and (serving? eng) (not= :promise (:kind t)))
    (try (doseq [[pid kind] (standing-ids eng) :when (= kind (text (:kind t)))]
           (evaluate-quietly! eng pid))
         (catch Exception e (warn! "transition " (:id t) " could not be heard — " (ex-message e)))))
  nil)

(def consumer-name :promises)

(defn consumer-fn [eng] (fn [t] (hear! eng t)))

(defn start! [eng opts]
  (when (serving? eng)
    (consumers/register-consumer! eng consumer-name (consumer-fn eng)
                                  (select-keys opts [:dispatcher :poll-ms]))))

(defn stop! [consumer]
  (some-> consumer consumers/stop-consumer!)
  nil)
