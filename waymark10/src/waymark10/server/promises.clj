(ns waymark10.server.promises
  "Promises (docs/spec-promise.md §14 steps 2 and 3): what must hold, by
  when, and who owes it. Standing is the state, moved only by the
  engine's hand (`risk`, `break`, `keep`) on every transition of the
  `about` kind (`hear!`) and on the live cracks clock (`sweep!`). A
  break writes its debtor; in `enforced` mode it fires a model seat
  once, and a breach only a person can fix reaches the owner as one
  held call. A promise that names a seat waits `proposed` for its accept.
  Opted into like `goal`, through an app's resources vector. A row
  merged from one that carries `outcomes` makes each a promise; a broken
  one walks its remedy door on that row, and a kept one retires (§7)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.age :as age]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.goals :as goals]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.measure :as measure]
            [waymark10.server.members :as members]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
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

(g/defguard one-remedy-door
  {:judges [:remedy]
   :reads []
   :explain "A remedy names `door` (one door) or `doors` (a list of them): exactly one of the two."
   :open "Name the remedy's door as `door`, or its several doors as `doors`, and not both."}
  [_row inp _ctx]
  (let [r (:remedy inp)]
    (if (or (nil? r)
            (not= (boolean (some-> (fget r :door) text not-empty)) (boolean (seq (fget r :doors)))))
      (t/allow)
      (t/deny))))

(def ^:private filter-schema
  [:maybe [:map-of :keyword [:string {:max 200}]]])

(def ^:private name-field
  [:name {:x-display {:label "Promise" :help "A short sentence a person reads: merged content is live within 2 h."}}
   [:string {:min 3 :max 200}]])

(def ^:private probe-schema
  [:map
   [:power [:string {:min 1 :max 120}]]
   [:arguments {:optional true} [:maybe [:map-of :keyword :any]]]
   [:expect [:map
             [:path [:string {:min 1 :max 200}]]
             [:eq {:optional true} :any]
             [:neq {:optional true} :any]
             [:lte {:optional true} :any]
             [:gte {:optional true} :any]
             [:exists {:optional true} [:maybe :boolean]]]]
   [:every [:int {:min 900}]]])

(def ^:private holds-field
  [:holds {:x-display {:label "Holds" :help "The check, {query {kind, filter, expect}}: expect none (no row may match), some, or count_lte with count_lte n. A filter value {id} is the about row's id. With `health` naming a seat health rule, the query reads the about seat's recent sittings and counts one when they break that rule. Or {probe {power, arguments, expect, every}}: one power call every `every` seconds (at least 900), its answer read at expect's path with eq, neq, lte, gte or exists. Or {judgment {id, expect}}: the newest said verdict of that judgment about the row passes when its word is in expect; no verdict yet passes until `by` and fails after."}}
   [:map
    [:query {:optional true}
     [:map
      [:kind [:string {:min 1 :max 80}]]
      [:filter {:optional true} filter-schema]
      [:expect [:enum "none" "some" "count_lte"]]
      [:count_lte {:optional true} [:maybe [:int {:min 0}]]]
      [:health {:optional true} [:maybe [:string {:min 1 :max 80}]]]]]
    [:probe {:optional true} probe-schema]
    [:judgment {:optional true}
     [:map
      [:id [:string {:min 1 :max 128}]]
      [:expect [:vector {:min 1 :max 20} [:string {:min 1 :max 40}]]]]]]])

(def ^:private by-field
  [:by {:x-display {:label "By" :help "{after <action>, within <age>}: a deadline from the row's last <action> (or any of several, comma-separated); or {window <age>}: the check must pass at every sweep. An age is seconds, {business_days n} or {until <form>}."}}
   [:map
    [:after {:optional true} [:maybe [:string {:max 400}]]]
    [:within {:optional true} :any]
    [:window {:optional true} :any]]])

(def ^:private remedy-field
  [:remedy {:x-display {:label "Remedy" :help "{kind, door} or {kind, doors}: the door (or any of the doors) that fixes a break, on the about row, or on the row its field `via` names."}}
   [:map
    [:kind [:string {:min 1 :max 80}]]
    [:door {:optional true} [:string {:min 1 :max 80}]]
    [:doors {:optional true} [:vector {:min 1 :max 20} [:string {:min 1 :max 80}]]]
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

(defn- breach-seconds
  "The seconds the cleared breaches stood, each from its `since` to
  `at`, summed: what a keep writes for promise.breach_seconds (R-P.9.1)."
  [cleared at]
  (if-some [^Instant end (inst at)]
    (reduce + 0 (keep #(when-some [^Instant s (some-> (fget % :since) text inst)]
                         (max 0 (- (.getEpochSecond end) (.getEpochSecond s))))
                      cleared))
    0))

(defhandler keep-promise
  [row inp _ctx]
  (let [d (:data row)
        id (text (:row inp))
        all (vec (fget d :breaches))
        left (if id (without all id) all)
        cleared (if id (filterv #(= id (text (fget % :id))) all) all)
        to (if (seq left) :broken :kept)]
    (-> row
        (assoc :state to)
        (update :data assoc
                :breach_seconds (breach-seconds cleared (:at inp))
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
            [:last_error {:optional true} [:maybe [:string {:max 480}]]]
            [:probe_errors {:optional true} [:maybe [:int {:min 0}]]]
            [:breach_seconds {:optional true
                              :x-display {:label "Breach seconds" :help "Written by each keep: the seconds since the breaks it cleared (R-P.9.1)."}}
             [:maybe [:int {:min 0}]]]
            [:default_of {:optional true
                          :x-display {:label "Default for" :help "kind.state: the expected age this default promise was written from (R-P.8.1)."}}
             [:maybe [:string {:max 200}]]]
            [:outcome_of {:optional true
                          :x-display {:label "Outcome of" :help "kind:id: the row whose `outcomes` this promise was made from on a merge (R-P.7.2). A break walks the remedy on it; a span kept retires this."}}
             [:maybe [:string {:max 200}]]]]
   :filterable {:state #{:eq :in}}
   ;; R-P.9.1: read from the log — an evaluation that moves nothing is
   ;; not logged, so there is no share of evaluations to read
   :measures {:breaks {:label "Promise breaks" :stat :count
                       :among "state=proposed,kept,at_risk,broken,retired"
                       :at "transition:break" :over '(weeks 1) :at-least 1}
              :keeps {:label "Promise keeps" :stat :count
                      :among "state=proposed,kept,at_risk,broken,retired"
                      :at "transition:keep" :over '(weeks 1) :at-least 1}
              :breach_seconds {:label "Seconds promises stood broken" :stat :sum
                               :field "breach_seconds"
                               :among "state=proposed,kept,at_risk,broken,retired"
                               :at "transition:keep" :over '(weeks 1) :at-least 1}}
   :create-guards [a-promise-writer one-remedy-door]
   :on-create born-promise
   :actions
   {:restate {:from open-states :to open-states
              :input [:map (optional name-field) (optional holds-field) (optional by-field)
                      (optional remedy-field) debtor-field]
              :record true
              :guards [a-promise-writer one-remedy-door]
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
  "The query check over one about row (R-P.3.2): [:pass|:fail|:error cause].
  `by` and `now` give a seat health query its window."
  [eng holds row & [by now]]
  (try
    (let [q (fget holds :query)
          rdef (rdef-of eng (fget q :kind))]
      (if (nil? rdef)
        [:error (str "the query names `" (text (fget q :kind)) "`, a kind this engine does not serve")]
        (let [rule (some-> (fget q :health) text not-empty)
              ;; a seat health query (R-P.11.3): `health-breach` judges
              ;; the seat's sittings inside `by.window`, and a broken
              ;; rule is one row
              broke (when rule
                      (seats/rule-breach eng (:id row) rule
                                         (fget by :window) now))
              n (if rule
                  (if broke 1 0)
                  (count (search eng rdef (fget q :filter) (:id row))))
              expect (text (fget q :expect))
              lte (long (or (fget q :count_lte) 0))
              ok (case expect "none" (zero? n) "some" (pos? n) (<= n lte))]
          (cond
            ok [:pass nil]
            broke [:fail (str "the seat's sittings break " broke)]
            :else
            [:fail (str (text (fget q :kind)) " expected "
                        (case expect "none" "no row" "some" "a row" (str "at most " lte))
                        " and read " n)]))))
    (catch Exception e [:error (or (ex-message e) (str e))])))

(def ^:private seat-sittings
  "How many of a seat's newest sittings a judgment check about the seat
  reads verdicts on."
  50)

(defn- judged-subjects
  "What a judgment check about `row` reads verdicts on: [kind ids]. The
  row itself; or, for a seat, its newest sittings, so a sitting judge's
  findings on that seat stand as one breach keyed by the seat (R-P.11.4)."
  [eng row]
  (if (and (= "seat" (text (:kind row))) (serves? eng :sitting))
    (let [st (:storage eng)]
      ["sitting"
       (mapv #(str (:id %))
             (store/with-tx st
               (fn [tx]
                 (store/search-rows st tx :sitting
                                    [{:target :data :field :seat :cast "text" :op := :value (str (:id row))}]
                                    {:order-by :created_at :desc true :limit seat-sittings}))))])
    [(text (:kind row)) [(str (:id row))]]))

(defn- judged
  "The judgment check over one about row (R-P.3.4): the newest `said`
  verdict of that judgment about the row — about a seat, about any of
  its newest sittings — read and never run. [:pass nil], [:fail-now
  cause] for a word outside `expect`, [:unjudged cause] for no verdict
  yet, or [:error cause]."
  [eng holds row]
  (try
    (let [j (fget holds :judgment)
          jid (text (fget j :id))
          expect (into #{} (map text) (fget j :expect))
          rdef (rdef-of eng :verdict)]
      (if (nil? rdef)
        [:error "a judgment check reads verdicts, and this engine serves none"]
        (let [[skind ids] (judged-subjects eng row)
              {:keys [conds]} (collections/parse-query rdef {"judgment" jid
                                                              "subject_kind" skind}
                                                       {:defaults? false})
              st (:storage eng)
              said (when (seq ids)
                     (first (store/with-tx st
                              (fn [tx]
                                (store/search-rows st tx (:kind rdef)
                                                   (conj (vec conds)
                                                         {:target :data :field :subject_id :cast "text" :op :in :values ids}
                                                         {:target :state :op :in :values ["said"]})
                                                   {:order-by :created_at :desc true :limit 1})))))
              word (some-> (fget (:data said) :verdict) text)]
          (cond
            (nil? said) [:unjudged (str "judgment " jid " has said no verdict yet")]
            (contains? expect word) [:pass nil]
            :else [:fail-now (str "judgment " jid " said " word ", and only "
                                  (str/join ", " (sort expect)) " passes")]))))
    (catch Exception e [:error (or (ex-message e) (str e))])))

(defn- clip [s n]
  (let [s (str s)] (if (< (long n) (count s)) (subs s 0 n) s)))

(defn- owner-visibility
  "The visibility the owner's standing grant confers, or nil: a probe
  is the owner's call, worn, never the engine's."
  [eng owner]
  (when-some [g (some->> owner not-empty (grants/standing-grant-for eng))]
    (when (= "accepted" (text (:state g)))
      (grants/visibility eng (str (:id g)) (t/principal {:id owner})))))

(defn- answer-of
  "A power's answer as data: its structured content, or its first text
  part read as JSON. Unreadable throws."
  [res]
  (let [sc (:structuredContent res)]
    (if (map? sc)
      sc
      (some->> (:content res) (some #(when (= "text" (text (:type %))) (:text %)))
               str wire/read-json))))

(defn- at-path
  "The value at a JSON path such as `$.items[0].at`."
  [v path]
  (reduce (fn [x seg]
            (if (re-matches #"\d+" seg)
              (when (sequential? x) (nth (vec x) (Long/parseLong ^String seg) nil))
              (fget x seg)))
          v
          (remove str/blank? (str/split (str/replace (str path) #"^\$" "") #"[.\[\]]+"))))

(defn- same? [a b]
  (if (and (number? a) (number? b)) (== a b) (= (text a) (text b))))

(defn- expected
  "`expect`'s comparison over the value read: [ok? words]."
  [expect v]
  (let [n? #(and (number? v) (number? %))]
    (or (some (fn [[k f words]]
                (let [x (fget expect k)]
                  (when (some? x) [(boolean (f x)) (str words " " (pr-str x))])))
              [[:eq #(same? v %) "equal to"]
               [:neq #(not (same? v %)) "not equal to"]
               [:lte #(and (n? %) (<= v %)) "at most"]
               [:gte #(and (n? %) (>= v %)) "at least"]
               [:exists #(= (true? %) (some? v)) "present:"]])
        [(some? v) "present"])))

(defn- probe
  "The probe check (R-P.3.3): one call through the power door under the
  owner's grant, `why` the promise's name, its answer read at `expect`'s
  path. A refused grant, a server down, a held call or an unreadable
  answer is an error, not a breach. The call carries the promise's id
  (R-P.10.1). [:pass|:fail|:error cause]."
  [eng pid d]
  (let [pr (fget (fget d :holds) :probe)
        power (text (fget pr :power))
        expect (fget pr :expect)
        path (text (fget expect :path))
        args (into {} (map (fn [[k v]] [(keyword (text k)) v])) (fget pr :arguments))]
    (try
      (let [res (gate/invoke-for eng (owner-visibility eng (text (fget d :owner))) power
                                 (assoc args :why (text (fget d :name)))
                                 {:promise (str pid)})]
        (cond
          (true? (get-in res [:structuredContent :held]))
          [:error (str power " waits on a person's tap, and a probe asks nobody")]

          (true? (:isError res)) [:error (str power " answered an error")]

          :else
          (let [v (at-path (answer-of res) path)
                [ok words] (expected expect v)]
            (if ok
              [:pass nil]
              [:fail (clip (str path " expected " words " and read " (pr-str v)) 400)]))))
      (catch Exception e [:error (clip (or (ex-message e) (str e)) 480)]))))

(defn- probe-due?
  "A probe is called again only once `every` has passed since `last_checked`."
  [d ^Instant now]
  (let [every (long (or (fget (fget (fget d :holds) :probe) :every) sweep-seconds))
        ^Instant last (inst (fget d :last_checked))]
    (or (nil? last) (not (.isBefore now (.plusSeconds last every))))))

(def ^:private probe-error-limit 3)

(defn- file-probe-draft!
  "The draft ticket a probe's third error in a row files against its
  promise (R-P.3.1), keyed by the promise and the error's first line so a
  broken probe is reported once. Best effort: a refusal is a warning."
  [eng pid d cause]
  (when (serves? eng :ticket)
    (let [st (:storage eng)
          line (first (str/split-lines (str cause)))
          k (str "promise-probe:" pid ":" line)]
      (when-not (store/with-tx st (fn [tx] (store/idempotency-lookup st tx k :ticket)))
        (try
          (inv/create! eng :ticket
                       {:title (clip (str "A promise's probe keeps erroring: " (text (fget d :name))) 200)
                        :detail (str line
                                     "\n\nPromise: " pid
                                     "\nPower: " (text (fget (fget (fget d :holds) :probe) :power)))
                        :repo "ckopsa/waymark"
                        :type "bug"
                        :priority 2}
                       {:principal schedules/system-actor
                        :idempotency-key k})
          (catch Exception e
            (warn! "promise " pid ": the probe's draft would not file — " (ex-message e))))))))

(defn- probe-streak!
  "The probe's errors in a row, reset by a pass or a fail; the third files
  the draft."
  [eng pid d [v cause]]
  (if (= :error v)
    (let [n (inc (long (or (fget d :probe_errors) 0)))]
      (when (= probe-error-limit n) (file-probe-draft! eng pid d cause))
      {:probe_errors n :last_error (clip cause 480)})
    {:probe_errors 0}))

(defn- entered-at
  "When the row last took one of `after`'s doors (comma-separated)."
  [eng row after]
  (let [st (:storage eng)
        actions (into #{} (map str/trim) (str/split after #","))]
    (some #(when (contains? actions (text (:action %))) (inst (:at %)))
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
    (if via
      ;; an address (`ticket:<id>`, as `born_from` holds) names its row
      (let [k (text (fget remedy :kind))
            v (some-> (fget (:data row) via) text)
            v (if (and v (str/starts-with? v (str k ":"))) (subs v (inc (count k))) v)]
        (load-row eng k v))
      row)))

(defn- remedy-doors
  "The doors `remedy` names: its one `door`, or its `doors`."
  [remedy]
  (if-some [door (some-> (fget remedy :door) text not-empty)]
    [door]
    (mapv text (fget remedy :doors))))

(defn- debtor-of [eng d row]
  (try
    (let [remedy (fget d :remedy)
          target (remedy-row eng d row)
          rdef (when target (rdef-of eng (:kind target)))
          pick (when rdef
                 (waiting-on/debtor (waiting-on/for-row eng rdef target (:data target))
                                    (remedy-doors remedy)))]
      (when pick {:name (text (:seat pick)) :person? (boolean (:person pick))}))
    (catch Exception e
      (warn! "no debtor for " (:id row) " — " (ex-message e))
      nil)))

(defn- seat-id-of [eng seat-name]
  (let [st (:storage eng)]
    (some #(when (= seat-name (text (fget (:data %) :name))) (str (:id %)))
          (store/with-tx st
            (fn [tx] (store/query-rows st tx :seat {:state :active} {:limit 500}))))))

(defn- held-doors
  "Of the remedy's doors, the ones `seat` holds on `row`'s remedy row
  now; every one of them when that cannot be read."
  [eng d row seat]
  (let [ds (remedy-doors (fget d :remedy))]
    (try
      (let [target (when row (remedy-row eng d row))
            rdef (when target (rdef-of eng (:kind target)))
            nxt (when rdef (:next (waiting-on/for-row eng rdef target (:data target))))
            held (into #{} (comp (filter (fn [n] (some #(= seat (text (:seat %))) (:seats n))))
                                 (map (comp text :door)))
                       nxt)]
        (or (not-empty (filterv held ds)) ds))
      (catch Exception _ ds))))

(defn- wake!
  "Fire the debtor seat as the engine (R-P.5.2): :fired, :held or :refused.
  The fire text names the remedy's `door`, or the `doors` the debtor holds."
  [eng p b ^Instant now]
  (let [d (:data p)
        about (fget d :about)
        id (text (fget b :id))
        seat-id (seat-id-of eng (text (fget b :debtor)))
        row (load-row eng (fget about :kind) id)
        state (some-> row :state text)
        door (some-> (fget (fget d :remedy) :door) text not-empty)
        txt (wire/write-json (merge {:kind (text (fget about :kind)) :id id :action "break"
                                     :from state :to state :promise (str (:id p))
                                     :cause (text (fget b :cause))}
                                    (if door
                                      {:door door}
                                      {:doors (held-doors eng d row (text (fget b :debtor)))})))
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
  (R-P.5.3): each remedy door the owner holds, retire, and watch."
  [eng p b]
  (let [d (:data p)
        pid (str (:id p))
        id (text (fget b :id))
        owner (some-> (fget d :owner) text not-empty)
        about (load-row eng (fget (fget d :about) :kind) id)
        target (when about (remedy-row eng d about))
        fix (when (and owner target)
              (for [door (remedy-doors (fget d :remedy))
                    :when (walks? eng owner target door)]
                {:kind (:kind target) :id (str (:id target)) :door door
                 :label (str "Fix it: " door)}))]
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

(defn- reopen-outcome!
  "A broken outcome walks its remedy door on the row it was made from,
  as the engine, the failed check as the move's why, and retires
  (R-P.7.3): the groomers restate that row with what reality showed. A
  walk refused leaves it broken, the refusal its `last_error`."
  [eng pid row cause]
  (let [d (:data (row-of eng pid))]
    (when (some-> (fget d :outcome_of) text not-empty)
      (try
        (let [target (remedy-row eng d row)
              door (first (remedy-doors (fget d :remedy)))]
          (when-not (and target door)
            (throw (ex-info (str "no " (fget d :outcome_of) " to walk " door " on") {})))
          (inv/invoke! eng (:kind target) (str (:id target)) (keyword door) {}
                       {:principal (assoc engine-actor :why
                                          (clip (str "Its outcome \"" (text (fget d :name))
                                                     "\" broke: " cause)
                                                280))})
          (inv/invoke! eng :promise pid :retire nil {:principal engine-actor}))
        (catch Exception e
          (stamp! eng pid {:last_error (clip (or (ex-message e) (str e)) 480)}))))))

(defn- retire-proved!
  "An outcome kept for its `by` span plus one sweep retires itself, and
  the outcome is proved (R-P.7.3)."
  [eng pid ^Instant now]
  (let [p (row-of eng pid)
        d (:data p)
        about (fget d :about)]
    (when (and (some-> (fget d :outcome_of) text not-empty)
               (= "kept" (text (:state p)))
               (empty? (fget d :breaches)))
      (when-some [row (some->> (fget about :id) (load-row eng (fget about :kind)))]
        (when (:due? (due eng (fget d :by) row (.minusSeconds now sweep-seconds)))
          (inv/invoke! eng :promise pid :retire nil {:principal engine-actor}))))))

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
        (wake-due! eng pid id now)
        (reopen-outcome! eng pid row cause)))))

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
  "Check one promise at the engine's now and move its standing. A probe
  promise is checked only once `every` has passed since `last_checked`,
  and its one call answers for every about row."
  [eng pid]
  (let [^Instant now ((:now-fn eng))
        at (str now)
        p (row-of eng pid)]
    (when (and p (standing (keyword (text (:state p)))))
      (let [d (:data p)
            probe? (some? (fget (fget d :holds) :probe))
            {:keys [rows error]} (when (or (not probe?) (probe-due? d now))
                                   (about-rows eng (fget d :about)))]
        (cond
          error (stamp! eng pid {:last_error error :last_checked at})
          (nil? rows) nil
          :else
          (let [verdict (when probe? (probe eng pid d))
                judged? (some? (fget (fget d :holds) :judgment))
                results (mapv (fn [row]
                                (let [[v cause] (or verdict
                                                    (if judged?
                                                      (judged eng (fget d :holds) row)
                                                      (check eng (fget d :holds) row
                                                             (fget d :by) now)))
                                      late (when (#{:fail :unjudged} v) (due eng (fget d :by) row now))]
                                  (case v
                                    ;; the standing verdict is the answer, whatever `by` says
                                    :fail-now {:row row :verdict :fail :cause cause :due? true}
                                    ;; no verdict yet: a pass until `by`, a fail after (R-P.3.4)
                                    :unjudged (if (:due? late)
                                                {:row row :verdict :fail :cause cause :due? true}
                                                {:row row :verdict :pass :cause nil})
                                    (merge {:row row :verdict v :cause cause}
                                           (when (= :fail v) late)))))
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
            (stamp! eng pid (cond-> {:last_checked at :last_error (first errors)}
                              probe? (merge (probe-streak! eng pid d verdict))))))
        (retire-proved! eng pid now)))))

(defn- standing-ids [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (mapv (juxt #(str (:id %)) #(text (fget (fget (:data %) :about) :kind))
                    #(some? (fget (fget (:data %) :holds) :probe))
                    #(some? (fget (fget (:data %) :holds) :judgment)))
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

(def ^:private outcome-link
  "The field a merged row names the row it was built for in, `kind:id`."
  "born_from")

(def ^:private outcome-door
  "The door a broken outcome walks on that row: the ticket's own
  `reopen`, done or dropped → draft (decision, Oct 31)."
  "reopen")

(defn- born-from [eng row]
  (let [[k id] (some-> (fget (:data row) outcome-link) text (str/split #":" 2))]
    (when (and (not-empty k) (not-empty id))
      (load-row eng k id))))

(defn- outcome-promise
  "One outcome draft {name, holds, by {within}} as a promise about the
  merged row, `by` after the merge, its remedy the asker's reopen."
  [t asker draft]
  (let [draft (walk/keywordize-keys draft)
        k (text (:kind asker))
        aid (str (:id asker))]
    {:name (or (some-> (:name draft) text not-empty) (str "An outcome of " k " " aid))
     :about {:kind (text (:kind t)) :id (str (:resource-id t))}
     :holds (:holds draft)
     :by {:after (text (:action t)) :within (get-in draft [:by :within])}
     :remedy {:kind k :door outcome-door :via outcome-link}
     :outcome_of (str k ":" aid)}))

(defn- outcomes!
  "On `merge`, each outcome the merged row's `born_from` row carries
  becomes a promise in `kept` (R-P.7.1, R-P.7.2), once per merged row."
  [eng t]
  (when (= "merge" (text (:action t)))
    (when-some [asker (some->> (load-row eng (:kind t) (:resource-id t)) (born-from eng))]
      (doseq [[i draft] (map-indexed vector (fget (:data asker) :outcomes))]
        (try
          (inv/create! eng :promise (outcome-promise t asker draft)
                       {:principal engine-actor
                        :idempotency-key (str "outcome:" (:resource-id t) ":" i)})
          (catch Exception e
            (warn! "outcome " i " of " (:id asker) " made no promise — " (ex-message e))))))))

(defn- seat-promise-stands?
  "Does a standing promise already hold judgment `jid` about `seat-id`?"
  [eng jid seat-id]
  (let [st (:storage eng)]
    (boolean
     (some #(let [d (:data %)]
              (and (= seat-id (text (fget (fget d :about) :id)))
                   (= jid (text (fget (fget (fget d :holds) :judgment) :id)))))
           (store/with-tx st
             (fn [tx]
               (store/search-rows st tx :promise
                                  [{:target :state :op :in :values ["proposed" "kept" "at_risk" "broken"]}]
                                  {:order-by nil :desc nil :limit 1000})))))))

(defn- seat-promise!
  "A sitting judge's finding whose word its judgment files a ticket on
  writes, when none stands, one watched promise about the sitting's
  seat that holds the judgment's other words (R-P.11.4). Every later
  finding on that seat is the same breach, keyed by the seat, and a pass
  recovers it under the usual damping; `judgments` files no ticket for
  it. Best effort: a refusal is a warning."
  [eng t]
  (try
    (when (and (= "verdict" (text (:kind t))) (= "judge" (text (:action t))) (serves? eng :seat))
      (when-some [vd (:data (load-row eng :verdict (:resource-id t)))]
        (let [j (some->> (fget vd :judgment) text not-empty (load-row eng :judgment))
              listed (into #{} (map text) (fget (:data j) :files_ticket_on))
              expect (into [] (comp (map #(text (fget % :name))) (remove listed))
                           (fget (:data j) :verdicts))
              seat-id (when (= "sitting" (text (fget vd :subject_kind)))
                        (some-> (some->> (fget vd :subject_id) text not-empty (load-row eng :sitting))
                                :data (fget :seat) text not-empty))
              jid (str (:id j))]
          (when (and seat-id (seq expect)
                     (nil? (some-> (fget vd :corrects) text not-empty))
                     (contains? listed (text (fget vd :verdict)))
                     (not (seat-promise-stands? eng jid seat-id)))
            (inv/create! eng :promise
                         {:name (clip (str (or (some-> (load-row eng :seat seat-id) :data (fget :name) text not-empty)
                                               seat-id)
                                           " sits as \"" (text (fget (:data j) :name)) "\" asks")
                                      200)
                          :about {:kind "seat" :id seat-id}
                          :holds {:judgment {:id jid :expect expect}}
                          :by {:window sweep-seconds}
                          :remedy {:kind "seat" :door "restate"}}
                         {:principal engine-actor})))))
    (catch Exception e
      (warn! "verdict " (:resource-id t) " made no seat promise — " (ex-message e)))))

(defn hear! [eng t]
  (when (and (serving? eng) (not= :promise (:kind t)))
    ;; a probe touches a wire, so a transition never runs one (R-P.3.3)
    (try (outcomes! eng t)
         (seat-promise! eng t)
         (let [k (text (:kind t))
               ;; a verdict said or overruled is heard by the judgment
               ;; promises about its subject's kind (R-P.3.4)
               judged-kind (when (= "verdict" k)
                             (some-> (load-row eng :verdict (:resource-id t))
                                     :data (fget :subject_kind) text))]
           (doseq [[pid kind probe? judged?] (standing-ids eng)
                   :when (and (not probe?)
                              (or (= kind k)
                                  (and judged? (= kind judged-kind))
                                  ;; a seat's promise reads its sittings' verdicts (R-P.11.4)
                                  (and judged? (= "seat" kind) (= "sitting" judged-kind))))]
             (evaluate-quietly! eng pid)))
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
