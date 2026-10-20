(ns waymark10.exists-test
  "exists / none / count-rows (docs/spec-related-row-law.md §3, build
  step 3): a guard asks other rows of a kind through a where its own
  scope fills, the row itself left out, and a refusal names the oldest
  match the caller may see — {b.field} in the sentence, (evidence :b)
  in a remedy. Over a fixture pair of kinds: a crew and its bookings."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.declare :refer [defguard refuse]]
            [waymark10.expr :as e]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────────────────

(defguard the-crew-is-free
  (refuse "That crew is already at {b.customer_name} from {b.starts_at} to {b.ends_at}.")
  '(none [b :ex_booking {:crew      (= (input :crew))
                         :starts_at (< (input :ends_at))
                         :ends_at   (> (input :starts_at))
                         :state     (in "scheduled")}])
  {:remedies [{:door :ex_booking/reschedule :id '(evidence :b)}]})

(defguard the-crew-has-room
  (refuse "That crew already holds two bookings.")
  '(< (count [o :ex_booking {:crew (= (input :crew)) :state (in "scheduled")}]) 2)
  {:remedies [:ex_booking/finish]})

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Booked is booked."})

(def ^:private slot
  [:map
   [:crew [:string {:max 40}]]
   [:starts_at :waymark/instant]
   [:ends_at :waymark/instant]])

(def ^:private crew-map
  {:kind :ex_crew
   :plural "ex_crews"
   :states [:active :resting]
   :initial :active
   :terminal #{:resting}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 40}]]]
   :actions {:rest {:from #{:active} :to :resting :safety safety}}})

(defn- booking-map [guards]
  {:kind :ex_booking
   :plural "ex_bookings"
   :states [:draft :scheduled :done]
   :initial :draft
   :terminal #{:done}
   :summary "{data.customer_name} · {state}"
   :schema [:map
            [:customer_name [:string {:min 1 :max 80}]]
            [:crew {:optional true} [:maybe [:string {:max 40}]]]
            [:starts_at {:optional true} [:maybe :waymark/instant]]
            [:ends_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in} :crew #{:eq} :starts_at #{:eq} :ends_at #{:eq}}
   :actions {:schedule {:from #{:draft} :to :scheduled :input slot
                        :guards (vec (:schedule guards)) :safety safety}
             :reschedule {:from #{:scheduled} :to :scheduled :input slot
                          :guards (vec (:reschedule guards)) :safety safety}
             :finish {:from #{:scheduled} :to :done :safety safety}}})

(def ^:private world
  (delay {:crew (r/resource crew-map)
          :booking (r/resource (booking-map {:schedule [the-crew-is-free the-crew-has-room]
                                             :reschedule [the-crew-is-free]}))}))

(defn- guard-on [door n]
  (nth (get-in (:booking @world) [:actions door :guards]) n))

(defn- judge [door n row inp ctx]
  (g/evaluate (guard-on door n) row inp ctx))

;; b0 sorts first by id, but b1 is the older booking
(def ^:private bookings
  [{:id "b1" :kind :ex_booking :state :scheduled :created_at "2026-10-01T09:00:00Z"
    :data {:customer_name "the Parks' yard" :crew "elm"
           :starts_at "2026-10-21T08:00:00Z" :ends_at "2026-10-21T12:00:00Z"}}
   {:id "b0" :kind :ex_booking :state :scheduled :created_at "2026-10-02T09:00:00Z"
    :data {:customer_name "the Okafors' hedge" :crew "elm"
           :starts_at "2026-10-21T09:00:00Z" :ends_at "2026-10-21T10:00:00Z"}}
   {:id "b3" :kind :ex_booking :state :done :created_at "2026-09-01T09:00:00Z"
    :data {:customer_name "the Moores' oaks" :crew "elm"
           :starts_at "2026-10-21T13:00:00Z" :ends_at "2026-10-21T15:00:00Z"}}
   {:id "b5" :kind :ex_booking :state :scheduled :created_at "2026-10-03T09:00:00Z"
    :data {:customer_name "the Diazes' beds" :crew "oak"
           :starts_at "2026-10-21T08:00:00Z" :ends_at "2026-10-21T12:00:00Z"}}])

(defn- row-of [id] (some #(when (= id (:id %)) %) bookings))

(defn- field-of [row f]
  (case f
    :state (some-> (:state row) name)
    :id (:id row)
    (get-in row [:data f])))

(defn- holds? [row {:keys [field op value values]}]
  (let [v (str (field-of row field))
        c (compare v (str value))]
    (case op
      := (zero? c)
      :not= (not (zero? c))
      :< (neg? c)
      :<= (not (pos? c))
      :> (pos? c)
      :>= (not (neg? c))
      :in (contains? (set (map str values)) v))))

(defn- ctx-over
  "The write ctx's hooks over `rows`: ids id-ordered, as ids-matching
  answers them, and `sees?` as the caller's grant."
  ([rows] (ctx-over rows nil))
  ([rows sees?]
   (let [hits (fn [kind conds]
                (filter (fn [row] (and (= kind (:kind row)) (every? #(holds? row %) conds)))
                        rows))]
     (cond-> {:rows-matching (fn [kind conds limit]
                               (vec (take limit (sort (map :id (hits kind conds))))))
              :count-matching (fn [kind conds] (count (hits kind conds)))
              :read (fn [kind id]
                      (some #(when (and (= kind (:kind %)) (= (str id) (:id %))) %) rows))}
       sees? (assoc :grant {:row? sees?})))))

(def ^:private newcomer {:id "b9" :data {:customer_name "the Lees' lawn"}})

(def ^:private half-nine
  {:crew "elm" :starts_at "2026-10-21T09:30:00Z" :ends_at "2026-10-21T14:00:00Z"})

;; ── 1. normalization: the binder erased, the kind read ─────────────────

(deftest the-binder-is-erased-and-the-kind-is-read
  (let [g (guard-on :schedule 0)
        counted (guard-on :schedule 1)]
    (is (= (e/normalize '(none :ex_booking {:crew (= (input :crew))
                                             :starts_at (< (input :ends_at))
                                             :ends_at (> (input :starts_at))
                                             :state (in "scheduled")}))
           (:when g)))
    (is (= (:when g)
           (:when (g/expr {:name :renamed :explain "That crew is busy."
                           :remedies [:ex_booking/finish]
                           :when '(none [other :ex_booking {:state (in "scheduled")
                                                            :ends_at (> (input :starts_at))
                                                            :starts_at (< (input :ends_at))
                                                            :crew (= (input :crew))}])})))
        "two laws differing only in the binder name are one law")
    (is (= [:ex_booking] (:reads g)) ":reads is derived from the kind it asks")
    (is (= [:crew :ends_at :starts_at] (:judges g)))
    (is (= #{:b} (set (keys (:binders g)))) "the name is kept beside the tree, for evidence")
    (is (= 'count-rows (first (second (:when counted))))
        "count over a kind is count-rows, never the collection count")
    (testing "the trees cross the wire and come back the same law"
      (is (= (:when g) (wire/wire->form (wire/form->wire (:when g)))))
      (is (= (:when counted) (wire/wire->form (wire/form->wire (:when counted))))))))

;; ── 2. overlap, the row itself left out ───────────────────────────────

(deftest overlap-leaves-the-row-itself-out
  (let [ctx (ctx-over bookings)
        oak {:crew "oak" :starts_at "2026-10-21T09:00:00Z" :ends_at "2026-10-21T11:00:00Z"}]
    (testing "deny: another row's slot overlaps a scheduled booking"
      (is (t/deny? (first (judge :schedule 0 newcomer half-nine ctx)))))
    (testing "allow: moving a booking never collides with its old self"
      (is (t/allow? (first (judge :reschedule 0 (row-of "b5") oak ctx))))
      (is (t/deny? (first (judge :schedule 0 newcomer oak ctx)))
          "the same slot asked by another row does collide"))
    (testing "allow: half-open — starting as another ends is no overlap"
      (is (t/allow? (first (judge :schedule 0 newcomer
                                  {:crew "elm" :starts_at "2026-10-21T12:00:00Z"
                                   :ends_at "2026-10-21T13:30:00Z"}
                                  ctx)))
          "b3 overlaps it too, but a done booking is outside the where"))
    (testing "unknown: no hook to ask is pending, never a refusal"
      (let [v (first (judge :schedule 0 newcomer half-nine {}))]
        (is (t/allow? v))
        (is (t/pending-input? v))))))

;; ── 3. evidence: the sentence and the remedy ──────────────────────────

(deftest the-refusal-names-the-oldest-match-it-may-show
  (let [[v d] (judge :schedule 0 newcomer half-nine (ctx-over bookings))]
    (is (= {:b "b1"} (:evidence v)) "b1 is older than b0, though b0 sorts first by id")
    (is (= (str "That crew is already at the Parks' yard from "
                "08:00 to 12:00.")
           (g/render-reason d v newcomer)))
    (is (= [{:door :ex_booking/reschedule :id "b1"}]
           (g/resolve-remedies d newcomer half-nine (:evidence v)))
        "(evidence :b) in a remedy is the match's id")
    (testing "a match the caller's grant cannot see is passed over"
      (let [[v d] (judge :schedule 0 newcomer half-nine
                         (ctx-over bookings (fn [_ id] (not= "b1" id))))]
        (is (= {:b "b0"} (:evidence v)))
        (is (= (str "That crew is already at the Okafors' hedge from "
                    "09:00 to 10:00.")
               (g/render-reason d v newcomer)))))
    (testing "every match hidden: the refusal stands and names none"
      (let [[v d] (judge :schedule 0 newcomer half-nine
                         (ctx-over bookings (constantly false)))]
        (is (t/deny? v))
        (is (nil? (:evidence v)))
        (is (= "What stops this is outside what you can see."
               (g/render-reason d v newcomer))
            "its one sentence reads the unseen binder: the engine's own stands")))))

;; ── 3b. no visible evidence: :hidden, and :vars beside the law ────────
;; children-are-finished's two sentences, over the crew's bookings.

(def ^:private elm-bookings
  '(none [b :ex_booking {:crew (= (input :crew)) :state (in "scheduled")}]))

(def ^:private elm-count
  {:count '(count [o :ex_booking {:crew (= (input :crew)) :state (in "scheduled")}])})

(defguard the-crew-is-finished
  (refuse "{count} of this crew's bookings are not finished. The oldest is \"{b.customer_name}\". A crew rests after its bookings: finish each one first, and then this door opens.")
  elm-bookings
  {:remedies [{:door :ex_booking/finish :id '(evidence :b)}]
   :vars elm-count
   :hidden {:b "They are outside what you can see."}})

(defguard the-crew-is-finished-plainly
  (refuse "{count} of this crew's bookings are not finished. The oldest is \"{b.customer_name}\". A crew rests after its bookings.")
  elm-bookings
  {:vars elm-count})

(deftest a-refusal-words-what-it-cannot-show
  (let [elm {:crew "elm"}]
    (testing "a visible match: the count from :vars, the oldest by name"
      (let [[v d] (g/evaluate the-crew-is-finished newcomer elm (ctx-over bookings))]
        (is (= {:b "b1"} (:evidence v)))
        (is (= (str "2 of this crew's bookings are not finished. The oldest is \"the Parks' yard\". "
                    "A crew rests after its bookings: finish each one first, and then this door opens.")
               (g/render-reason d v newcomer)))))
    (testing "every match hidden: the :hidden sentence stands in for the oldest"
      (let [[v d] (g/evaluate the-crew-is-finished newcomer elm
                              (ctx-over bookings (constantly false)))]
        (is (nil? (:evidence v)))
        (is (= (str "2 of this crew's bookings are not finished. They are outside what you can see. "
                    "A crew rests after its bookings: finish each one first, and then this door opens.")
               (g/render-reason d v newcomer)))))
    (testing "no :hidden: the sentence that reads the binder drops cleanly"
      (let [[v d] (g/evaluate the-crew-is-finished-plainly newcomer elm
                              (ctx-over bookings (constantly false)))]
        (is (= "2 of this crew's bookings are not finished. A crew rests after its bookings."
               (g/render-reason d v newcomer)))))
    (testing "a bare {b.field} never reaches the caller"
      (doseq [g [the-crew-is-finished the-crew-is-finished-plainly the-crew-is-free]
              ctx [(ctx-over bookings (constantly false)) {}]]
        (let [[v d] (g/evaluate g newcomer (merge half-nine elm) ctx)]
          (when (t/deny? v)
            (is (not (re-find #"\{b\." (g/render-reason d v newcomer))))))))
    (testing ":vars and :hidden are law: declared, they are hashed; absent, nothing moves"
      (is (= {:count (e/normalize (:count elm-count))} (:vars the-crew-is-finished)))
      (is (nil? (:hidden the-crew-is-finished-plainly)))
      (is (empty? (:vars the-crew-is-free))))
    (testing "a :hidden that names no binder is refused at the def line"
      (is (thrown? Exception
                   (eval '(waymark10.declare/defguard nobody-is-c
                            (waymark10.declare/refuse "Busy: {b.customer_name}.")
                            '(none [b :ex_booking {:crew (= (input :crew))}])
                            {:hidden {:c "Busy."}})))))))

;; ── 4. count-rows: the others, for capacity ───────────────────────────

(deftest count-rows-counts-the-others
  (let [ctx (ctx-over bookings)]
    (testing "two scheduled elm bookings: a third is refused"
      (is (t/deny? (first (judge :schedule 1 newcomer {:crew "elm"} ctx)))))
    (testing "one of the two, asking, counts only the other"
      (is (t/allow? (first (judge :schedule 1 (row-of "b1") {:crew "elm"} ctx)))))
    (testing "the oak crew holds one"
      (is (t/allow? (first (judge :schedule 1 newcomer {:crew "oak"} ctx)))))))

;; ── 4b. not=: a where may ask what a field is not ─────────────────────

(deftest a-where-may-ask-not=
  (let [law '(none [b :ex_booking {:crew (= (input :crew)) :state (not= "scheduled")}])
        d (-> (r/resource (booking-map {:schedule [(g/expr {:name :no-past
                                                            :explain "That crew has a past booking."
                                                            :remedies [:ex_booking/finish]
                                                            :when law})]}))
              (get-in [:actions :schedule :guards 0]))
        ctx (ctx-over bookings)]
    (is (empty? (e/problems law)) "check-where admits not=")
    (is (= [{:field :crew :op := :value "elm"} {:field :state :op :not= :value "scheduled"}]
           (e/where-conds (nth (:when d) 2) {:input {:crew "elm"}}))
        "the where reaches the store as a :not= cond")
    (testing "deny: b3, a done elm booking, is not scheduled"
      (is (t/deny? (first (g/evaluate d newcomer {:crew "elm"} ctx)))))
    (testing "allow: oak's one booking is scheduled"
      (is (t/allow? (first (g/evaluate d newcomer {:crew "oak"} ctx)))))
    (testing "allow: b3 asking leaves itself out"
      (is (t/allow? (first (g/evaluate d (row-of "b3") {:crew "elm"} ctx)))))))

;; ── 5. the gate ──────────────────────────────────────────────────────────────────

(defn- assemble [booking]
  (ca/run-all {:kinds {:ex_crew (:crew @world) :ex_booking booking}}))

(deftest the-gate-names-what-exists-cannot-do
  (testing "[exists-unfiltered]: a where field the kind does not index"
    (let [bad (g/expr {:name :by-name :explain "That customer is booked."
                       :remedies [:ex_booking/finish]
                       :when '(none [b :ex_booking {:customer_name (= (input :crew))}])})]
      (is (thrown-with-msg? Exception #"\[exists-unfiltered\]"
                            (assemble (r/resource (booking-map {:schedule [bad]})))))))
  (testing "[remedy-on-evidence-kind]: an evidence remedy names a door of the binder's kind"
    (let [bad (g/expr {:name :rest-them :explain "That crew is busy."
                       :remedies [{:door :ex_crew/rest :id '(evidence :b)}]
                       :when '(none [b :ex_booking {:crew (= (input :crew))}])})]
      (is (thrown-with-msg? Exception #"\[remedy-on-evidence-kind\]"
                            (assemble (r/resource (booking-map {:schedule [bad]})))))))
  (testing "a where may not read its binder: filters, not joins"
    (is (thrown? Exception
                 (g/expr {:name :join :explain "That crew is busy."
                          :remedies [:ex_booking/finish]
                          :when '(exists [b :ex_booking {:crew (= (get b :crew))}])}))))
  (testing "the fixture pair itself assembles"
    (is (map? (assemble (:booking @world))))))
