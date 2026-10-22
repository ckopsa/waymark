(ns waymark10.expr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [waymark10.declare :as d]
            [waymark10.expr :as e]
            [waymark10.fingerprint :as fp]
            [waymark10.gen-forms :as gf]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.judgment :as judgment]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant LocalDate LocalTime Period ZoneId)))

(def trials 300)

;; ── properties (the phase-0 acceptance bar) ─────────────────────────

(defspec generated-forms-are-well-formed trials
  (prop/for-all [f gf/gen-form]
    (empty? (e/problems f))))

(defspec normalize-is-idempotent trials
  (prop/for-all [f gf/gen-form]
    (= (e/normalize f) (e/normalize (e/normalize f)))))

(defspec normalized-forms-stay-well-formed trials
  (prop/for-all [f gf/gen-form]
    (empty? (e/problems (e/normalize f)))))

(defspec evaluation-is-total trials
  (prop/for-all [f gf/gen-form
                 scope gf/gen-scope]
    (do (e/evaluate f scope)
        (e/evaluate (e/normalize f) scope)
        true)))

(defspec normalization-preserves-meaning trials
  (prop/for-all [f gf/gen-form
                 scope gf/gen-scope]
    (let [a (e/evaluate f scope)
          b (e/evaluate (e/normalize f) scope)]
      (or (= a b)
          ;; exact decimals compare by value, not scale
          (and (number? a) (number? b)
               (zero? (.compareTo (bigdec a) (bigdec b))))))))

;; ── semantics units ─────────────────────────────────────────────────

(deftest nil-satisfies-no-ordering
  (is (false? (e/evaluate '(< (data :x) 3) {:data {}})))
  (is (false? (e/evaluate '(<= (data :x) (data :y)) {:data {:x nil :y 2}})))
  (is (true? (e/evaluate '(= (data :x) nil) {:data {}})))
  (is (false? (e/evaluate '(not= (data :x) nil) {:data {}}))))

(deftest arithmetic-propagates-nil
  (is (nil? (e/evaluate '(+ (data :x) 1) {:data {}})))
  (is (nil? (e/evaluate '(* 2 (data :x)) {:data {}})))
  (is (nil? (e/evaluate '(abs (data :x)) {:data {}})))
  (is (= 5 (e/evaluate '(min (data :x) 5) {:data {}}))
      "min/max skip missing operands"))

(deftest decimals-are-exact
  (is (true? (e/evaluate '(= (+ 0.1M 0.2M) 0.3M) {})))
  (is (true? (e/evaluate '(<= (abs (var :difference)) 0.02M)
                         {:vars {:difference -0.015M}})))
  (is (false? (e/evaluate '(<= (abs (var :difference)) 0.02M)
                          {:vars {:difference 0.021M}})))
  (is (true? (e/evaluate '(= 1.50M 1.5M) {}))
      "value equality, not scale equality"))

(deftest date-arithmetic
  (let [scope {:vars {:start_date (LocalDate/parse "2026-07-07") :weeks 2}}]
    (is (= (LocalDate/parse "2026-07-20")
           (e/evaluate (e/normalize '(+ (var :start_date)
                                        (days (- (* 7 (var :weeks)) 1))))
                       scope))))
  (is (true? (e/evaluate '(<= (data :start_date) (date-of (now)))
                         {:data {:start_date (LocalDate/parse "2026-07-01")}
                          :now (Instant/parse "2026-07-09T12:00:00Z")})))
  (is (= (LocalDate/parse "2026-07-09")
         (e/evaluate '(date-of (now))
                     {:now (Instant/parse "2026-07-09T23:59:59Z")})))
  (testing "(days …) accepts the whole long family — JSON decoding
            hands scope values over as Integer, and evaluation is
            total over JSON-shaped values"
    (let [expect (LocalDate/parse "2026-07-17")]
      (doseq [n [(long 10) (int 10) (short 10) (byte 10) 10M]]
        (is (= expect
               (e/evaluate '(+ (var :on) (days (var :n)))
                           {:vars {:on (LocalDate/parse "2026-07-07")
                                   :n n}}))
            (str (class n) " counts days like its long twin"))))
    (is (nil? (e/evaluate '(+ (var :on) (days (var :n)))
                          {:vars {:on (LocalDate/parse "2026-07-07")
                                  :n 1.5M}}))
        "the calendar still has no fractional day")))

(deftest periods-and-durations
  (let [on (LocalDate/parse "2026-01-31")
        at (Instant/parse "2026-03-08T06:30:00Z")
        ev #(e/evaluate (e/normalize %) {:vars {:on on :at at :n 2}})]
    (testing "date ± Period is a date; a month past the 31st is its last day"
      (is (= (LocalDate/parse "2026-02-14") (ev '(+ (var :on) (weeks 2)))))
      (is (= (LocalDate/parse "2026-02-28") (ev '(+ (var :on) (months 1)))))
      (is (= (LocalDate/parse "2028-01-31") (ev '(+ (var :on) (years (var :n))))))
      (is (= (LocalDate/parse "2025-12-31") (ev '(- (var :on) (months 1))))))
    (testing "date ± Duration is nil: a date has no hour"
      (is (nil? (ev '(+ (var :on) (hours 1)))))
      (is (nil? (ev '(- (var :on) (minutes 30))))))
    (testing "instant ± Duration is an instant"
      (is (= (Instant/parse "2026-03-09T06:30:00Z") (ev '(+ (var :at) (hours 24)))))
      (is (= (Instant/parse "2026-03-08T06:00:00Z") (ev '(- (var :at) (minutes 30)))))
      (is (true? (ev '(<= (var :at) (+ (var :at) (hours 24)))))))
    (testing "instant ± Period with no calendar is unknown, never UTC"
      (is (e/unknown? (ev '(+ (var :at) (days 1))))))
    (testing "Period ± Period is a Period"
      (is (= (Period/of 0 1 7) (ev '(+ (months 1) (weeks 1)))))
      (is (= (Period/ofDays 5) (ev '(- (weeks 1) (days 2)))))
      (is (= (Duration/ofMinutes 90) (ev '(+ (hours 1) (minutes 30))))))
    (testing "a fractional count is no span"
      (is (nil? (ev '(hours 1.5M)))))))

(deftest days-between-counts-whole-days
  (let [ev #(e/evaluate % {:vars {:a (LocalDate/parse "2026-01-01")
                                  :b (LocalDate/parse "2026-03-01")
                                  :t (Instant/parse "2026-03-01T00:00:00Z")
                                  :u e/unknown}})]
    (is (= 59 (ev '(days-between (var :a) (var :b)))))
    (is (= -59 (ev '(days-between (var :b) (var :a)))))
    (is (true? (ev '(> (days-between (var :a) (var :b)) 15))))
    (is (nil? (ev '(days-between (var :a) (var :t)))) "an instant is not a date")
    (is (e/unknown? (ev '(days-between (var :a) (var :u)))))
    (is (seq (e/problems '(days-between (var :a))))
        "days-between takes two expressions")))

(def ^:private elm-street
  "A Denver house's calendar, as the :calendar hook answers one."
  {:id "elm" :version 1 :zone (ZoneId/of "America/Denver")
   :workdays #{:mon :tue :wed :thu :fri}
   :hours {:from (LocalTime/parse "07:00") :to (LocalTime/parse "17:00")}
   :holidays (sorted-map (LocalDate/parse "2027-05-31") "Memorial Day")})

(defn- calendar-of
  "The :calendar hook over elm-street: no ref is the default, \"crew\" a
  calendar that keeps no hours, any other ref names no row."
  ([] elm-street)
  ([ref] (case ref nil elm-street "crew" (assoc elm-street :hours nil) nil)))

(deftest calendar-readers
  (let [scope {:now (Instant/parse "2027-05-14T03:00:00Z")   ; Thu 13 May, 21:00 in Denver
               :calendar calendar-of
               :data {:signed_on (LocalDate/parse "2027-02-13")
                      :ordered_on (LocalDate/parse "2027-05-28")
                      :visit_on (LocalDate/parse "2027-05-31")
                      :starts_at (Instant/parse "2027-06-01T05:00:00Z")}
               :vars {:at (Instant/parse "2027-03-13T15:00:00Z")}}
        ev #(e/evaluate (e/normalize %) scope)
        bare #(e/evaluate (e/normalize %) (dissoc scope :calendar))]
    (testing "(today) is the date of (now) in the house's zone, not Greenwich's"
      (is (= (LocalDate/parse "2027-05-13") (ev '(today))))
      (is (= (LocalDate/parse "2027-05-14") (ev '(date-of (now)))) "date-of stays UTC")
      (is (e/unknown? (bare '(today))) "no hook is unknown"))
    (testing "the worked shape: a prescription is live"
      (is (true? (ev '(< (today) (+ (data :signed_on) (days 90))))))
      (is (false? (ev '(< (date-of (now)) (+ (data :signed_on) (days 90)))))
          "Greenwich's day has already ended it"))
    (testing "(local-date …) is an instant's date in that zone"
      (is (= (LocalDate/parse "2027-05-31") (ev '(local-date (data :starts_at)))))
      (is (= (LocalDate/parse "2027-05-31") (bare '(local-date (data :visit_on))))
          "a date is its own date")
      (is (e/unknown? (bare '(local-date (data :starts_at))))))
    (testing "instant ± Period keeps the local time across the spring change"
      (is (= (Instant/parse "2027-03-14T14:00:00Z") (ev '(+ (var :at) (days 1)))))
      (is (= (Instant/parse "2027-03-14T15:00:00Z") (ev '(+ (var :at) (hours 24)))))
      (is (= (Instant/parse "2027-03-13T15:00:00Z") (ev '(- (+ (var :at) (days 1)) (days 1)))))
      (is (e/unknown? (bare '(+ (var :at) (days 1)))) "never UTC by accident"))
    (testing "business days skip the weekend and the holiday"
      (is (= (LocalDate/parse "2027-06-03") (ev '(+ (data :ordered_on) (business-days 3)))))
      (is (= (LocalDate/parse "2027-05-27")
             (ev '(- (local-date (data :starts_at)) (business-days 2)))))
      (is (false? (ev '(business-day? (data :visit_on)))))
      (is (true? (ev '(business-day? (data :ordered_on)))))
      (is (= (LocalDate/parse "2027-06-01") (ev '(next-business-day (data :visit_on)))))
      (is (= (LocalDate/parse "2027-05-28") (ev '(next-business-day (data :ordered_on))))
          "the day itself when it is one")
      (is (e/unknown? (bare '(+ (data :ordered_on) (business-days 3)))))
      (is (nil? (e/evaluate '(+ (data :ordered_on) (business-days 1))
                            (assoc scope :calendar (fn [& _] (assoc elm-street :workdays #{})))))
          "past the 366-day cap is nil"))
    (testing "working? is the window on a workday, inside the hours, in the zone"
      (is (true? (ev '(working? "2027-05-13T14:00:00Z" "2027-05-13T16:00:00Z"))))
      (is (false? (ev '(working? "2027-05-13T23:00:00Z" "2027-05-14T01:00:00Z")))
          "17:00 to 19:00 is past the hours")
      (is (true? (ev '(working? "2027-05-13T23:00:00Z" "2027-05-14T01:00:00Z" "crew")))
          "a calendar that keeps no hours keeps the whole day")
      (is (false? (ev '(working? "2027-05-31T14:00:00Z" "2027-05-31T16:00:00Z")))
          "a holiday")
      (is (e/unknown? (ev '(working? "2027-05-13T14:00:00Z" "2027-05-13T16:00:00Z" "gone")))
          "a ref that names no row is unknown")
      (is (e/unknown? (bare '(working? "2027-05-13T14:00:00Z" "2027-05-13T16:00:00Z")))))
    (testing "the readers' grammar"
      (is (seq (e/problems '(today 1))))
      (is (seq (e/problems '(working? (now)))))
      (is (seq (e/problems '(business-days 1 2))))
      (is (empty? (e/problems '(working? (now) (now) (data :calendar_id))))))
    (testing "a derived fact reads the house's day as (var :today)"
      (is (seq (e/derived-scope-problems '(< (today) (var :due_on)) #{:due_on})))
      (is (empty? (e/derived-scope-problems '(< (var :today) (var :due_on))
                                            #{:today :due_on}))))))

(deftest a-week-is-seven-days-in-law
  (is (= (e/normalize '(days (* 7 (var :n))))
         (e/normalize '(weeks (var :n)))))
  (is (= (e/normalize '(weeks (var :n)))
         (e/normalize (e/normalize '(weeks (var :n)))))
      "idempotent")
  (testing "a law spelled with days keeps its tree, so its fingerprint stays"
    (is (= '(+ (days (- (* (var :weeks) 7) 1)) (var :start_date))
           (e/normalize '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1))))))))

(deftest quantifiers
  (is (true? (e/evaluate '(every [d (var :days)]
                            (or (is-set (get d :meal_id))
                                (= (get d :eating_out) true)))
                         {:vars {:days [{:meal_id "m1"} {:eating_out true}]}})))
  (is (false? (e/evaluate '(every [d (var :days)] (is-set (get d :meal_id)))
                          {:vars {:days [{:meal_id "m1"} {}]}})))
  (testing "vacuous truth over empty and missing collections"
    (is (true? (e/evaluate '(every [d (var :days)] (is-set (get d :x))) {:vars {:days []}})))
    (is (true? (e/evaluate '(every [d (var :days)] (is-set (get d :x))) {})))
    (is (false? (e/evaluate '(some [d (var :days)] (is-set (get d :x))) {}))))
  (testing "nested binders reach the right quantifier"
    (is (true? (e/evaluate
                '(some [g (var :groups)]
                   (every [m (get g :members)]
                     (= (get m :ok) true)))
                {:vars {:groups [{:members [{:ok false}]}
                                 {:members [{:ok true} {:ok true}]}]}})))))

(deftest de-bruijn-normalization
  (is (= '(every (var :days) (is-set (get (it 0) :meal_id)))
         (e/normalize '(every [d (var :days)] (is-set (get d :meal_id))))))
  (is (= (e/normalize '(some [x (var :xs)] (every [y (var :ys)] (= x y))))
         (e/normalize '(some [outer (var :xs)]
                         (every [inner (var :ys)] (= outer inner)))))
      "alpha-equivalent spellings are the same tree"))

(deftest respellings-normalize-identically
  (is (= (e/normalize '(> (data :a) (data :b)))
         (e/normalize '(< (data :b) (data :a)))))
  (is (= (e/normalize '(and (data :p) (and (data :q) (data :p))))
         (e/normalize '(and (data :p) (data :q))))
      "nested same-op flattens; duplicates collapse")
  (is (= (e/normalize '(not (not (= (data :a) 1))))
         (e/normalize '(= (data :a) 1))))
  (is (= (e/normalize '(not (= (data :a) 1)))
         (e/normalize '(not= 1 (data :a))))
      "not-of-= canonicalizes to not=; commutative operands sort")
  (is (= (e/normalize '(= 1.50M (data :x)))
         (e/normalize '(= (data :x) 1.5M)))
      "decimal literals normalize scale"))

(deftest collapse-respects-the-value-domain
  (is (= '(and (data :a)) (e/normalize '(and (data :a) (data :a))))
      "a non-boolean operand keeps its coercing wrapper")
  (is (= '(not (not (data :a))) (e/normalize '(not (not (data :a))))))
  (is (= '(is-set (data :a))
         (e/normalize '(and (is-set (data :a)) (is-set (data :a)))))
      "a boolean-valued operand may stand alone"))

(deftest scope-validators
  (is (empty? (e/derived-scope-problems
               '(+ (var :start_date) (days (var :weeks)))
               #{:start_date :weeks})))
  (is (seq (e/derived-scope-problems '(var :undeclared) #{:start_date})))
  (is (seq (e/derived-scope-problems '(data :start_date) #{:start_date}))
      "derivations read declared inputs, not the row")
  (is (seq (e/derived-scope-problems '(now) #{:start_date}))
      "the clock enters a derivation as a declared input")
  (is (empty? (e/guard-scope-problems
               '(<= (data :start_date) (date-of (now))))))
  (is (seq (e/guard-scope-problems '(var :start_date)))))

(deftest info-reads-the-tree
  (is (= {:vars #{:days} :data #{} :inputs #{} :uses-now false}
         (e/info '(every [d (var :days)] (is-set (get d :meal_id))))))
  (is (= {:vars #{} :data #{:start_date} :inputs #{:date} :uses-now true}
         (e/info '(and (<= (data :start_date) (date-of (now)))
                       (is-set (input :date)))))))

;; ── the unknown verdict (docs/spec-related-row-law.md §5) ───────────

(def ^:private three
  {:vars {:t true :f false :u e/unknown :xs [1 2]}})

(defn- truth [v]
  (if (e/unknown? v) :unknown v))

(defn- ev [form] (truth (e/evaluate form three)))

(defspec a-scope-without-unknown-never-answers-unknown trials
  (prop/for-all [f gf/gen-form
                 scope gf/gen-scope]
    (not (e/unknown? (e/evaluate f scope)))))

(deftest kleene-truth-tables
  (let [cells [[:t :t] [:t :f] [:t :u] [:f :t] [:f :f] [:f :u]
               [:u :t] [:u :f] [:u :u]]]
    (testing "and"
      (is (= [true false :unknown false false false :unknown false :unknown]
             (mapv (fn [[a b]] (ev (list 'and (list 'var a) (list 'var b)))) cells))))
    (testing "or"
      (is (= [true true true true false :unknown true :unknown :unknown]
             (mapv (fn [[a b]] (ev (list 'or (list 'var a) (list 'var b)))) cells))))
    (testing "not"
      (is (= [false true :unknown]
             (mapv #(ev (list 'not (list 'var %))) [:t :f :u]))))))

(deftest unknown-propagates-through-every-op
  (doseq [form '[(= (var :u) 1) (not= (var :u) 1) (= (var :u) nil)
                 (< (var :u) 1) (<= 1 (var :u)) (> (var :u) 1) (>= (var :u) 1)
                 (+ (var :u) 1) (- (var :u) 1) (- (var :u)) (* 2 (var :u))
                 (min (var :u) 5) (max 5 (var :u)) (abs (var :u))
                 (days (var :u)) (date-of (var :u)) (is-set (var :u))
                 (get (var :u) :x) (not (var :u))
                 (every [x (var :u)] (= x 1)) (some [x (var :u)] (= x 1))
                 (count (var :u)) (sum [x (var :u)] x)
                 (every [x (var :xs)] (= (var :u) x))
                 (some [x (var :xs)] (= (var :u) x))
                 (count [x (var :xs)] (= (var :u) x))
                 (sum [x (var :xs)] (+ x (var :u)))]]
    (is (= :unknown (ev form)) (pr-str form))))

(deftest known-operands-still-decide
  (is (false? (ev '(every [x (var :xs)] (and (= x 2) (var :u))))))
  (is (true? (ev '(some [x (var :xs)] (or (= x 1) (var :u))))))
  (is (false? (ev '(and (< 2 1) (= (var :u) 1)))))
  (is (true? (ev '(or (is-set (var :xs)) (> (var :u) 1)))))
  (testing "an empty collection decides without its predicate"
    (is (true? (ev '(every [x (var :none)] (var :u)))))
    (is (= 0 (ev '(count [x (var :none)] (var :u)))))))

(deftest an-unknown-when-answers-pending
  (let [listed (g/expr {:name :listed
                        :when '(= (data :listed) true)
                        :explain "The meal must be on the list."})
        [v] (g/evaluate listed {:data {:listed e/unknown}} {} {})]
    (is (t/allow? v) "an unknown verdict is never a refusal")
    (is (t/pending-input? v) "it is reported as pending"))
  (testing "a known verdict is unchanged"
    (let [listed (g/expr {:name :listed
                          :when '(= (data :listed) true)
                          :explain "The meal must be on the list."})]
      (is (t/deny? (first (g/evaluate listed {:data {:listed false}} {} {}))))
      (is (= (t/allow) (first (g/evaluate listed {:data {:listed true}} {} {})))))))

;; ── separation of duties: did, (me), (self) ─────────────────────────

(def ^:private the-preparer-does-not-sign
  (g/expr {:name :the-preparer-does-not-sign
           :when '(not (did :revise :prepare))
           :explain "You prepared this study, so someone else signs it off."}))

(defn- sign-off
  "The verdict on signing `row` off as `principal`, over `log` (newest
  first) through the same `actors-of` the engine's hook answers with."
  [principal log]
  (first (g/evaluate the-preparer-does-not-sign
                     {:id "study-1" :data {}} {}
                     {:principal principal
                      :actor-of (fn [_row door & every]
                                  (apply g/actors-of log door every))})))

(deftest whoever-submitted-never-signs-off
  (let [log [{:action :prepare :actor {:id "ann"}}]]
    (is (t/deny? (sign-off {:id "ann"} log)) "the preparer is refused")
    (is (t/allow? (sign-off {:id "bob"} log)) "another member signs"))
  (testing "any actor of any named door, not only the newest"
    (let [log [{:action :revise :actor {:id "eve"}}
               {:action :revise :actor {:id "bob"}}
               {:action :prepare :actor {:id "ann"}}]]
      (doseq [who ["ann" "bob" "eve"]]
        (is (t/deny? (sign-off {:id who} log)) who))
      (is (t/allow? (sign-off {:id "dan"} log)))))
  (testing "an acted-as transition binds both faces"
    (let [log [{:action :prepare
                :actor {:id "cast-cleo" :acted_by "seat:robot"}}]]
      (is (t/deny? (sign-off {:id "cast-cleo"} log)) "the member it was done as")
      (is (t/deny? (sign-off {:id "seat:robot"} log)) "the seat that did it")
      (is (t/deny? (sign-off {:id "cast-dan" :acted-by "seat:robot"} log))
          "the same seat, acting as someone else")
      (is (t/allow? (sign-off {:id "bob"} log)) "another member signs")
      (is (t/allow? (sign-off {:id "cast-dan" :acted-by "seat:other"} log))
          "another seat, as another member")))
  (testing "no log to ask is pending, never a refusal"
    (let [[v] (g/evaluate the-preparer-does-not-sign {:id "study-1" :data {}}
                          {} {:principal {:id "ann"}})]
      (is (t/allow? v))
      (is (t/pending-input? v))))
  (testing "the tree declares what it reads, and spells its doors once"
    (is (= [:transitions :principal] (:reads the-preparer-does-not-sign)))
    (is (= '(did :prepare :revise) (e/normalize '(did :revise :prepare :revise))))))

(deftest me-and-self
  (let [mine (g/expr {:name :is-the-requester
                      :when '(and (is-set (data :requested_by))
                                  (= (data :requested_by) (me)))
                      :explain "Only the requester may withdraw this."})
        row {:id "study-1" :data {:requested_by "ann"}}]
    (is (t/allow? (first (g/evaluate mine row {} {:principal {:id "ann"}}))))
    (is (t/deny? (first (g/evaluate mine row {} {:principal {:id "bob"}}))))
    (is (t/deny? (first (g/evaluate mine {:id "study-1" :data {}} {}
                                    {:principal {:id "ann"}})))
        "an unset field is refused")
    (is (= [:principal] (:reads mine))))
  (is (= "study-1" (e/evaluate '(self) {:self "study-1"})))
  (is (e/unknown? (e/evaluate '(self) {})))
  (is (e/unknown? (e/evaluate '(me) {})))
  (testing "arity, and a derivation reads no actor"
    (is (seq (e/problems '(did))))
    (is (seq (e/problems '(did "prepare"))))
    (is (seq (e/problems '(me 1))))
    (is (seq (e/derived-scope-problems '(= (var :x) (me)) #{:x})))))

(deftest actor-and-team-readers
  (let [teams (atom [{:id "t1" :state "active"
                      :data {:name "Crew B" :members ["ann"] :roles ["foreman"]}}])
        members {"ann" {:id "ann" :data {:roles []}}}
        ctx (fn [p]
              {:principal p
               :read (fn [kind id] (when (= :member kind) (get members (str id))))
               :find (fn [kind where _]
                       (when (= :team kind)
                         (filter #(= (:state where) (:state %)) @teams)))})
        ann {:id "ann" :type :person}
        foreman (g/expr {:name :a-foreman
                         :when '(holds-role? (actor) "foreman")
                         :explain "You are not a foreman."})
        on-b (g/expr {:name :on-crew-b
                      :when '(in-team? (actor) "Crew B")
                      :explain "You are not on Crew B."})
        row {:id "job-1" :data {}}]
    (testing "a role conferred by a team is lost by leaving it"
      (is (t/allow? (first (g/evaluate foreman row {} (ctx ann)))))
      (is (t/allow? (first (g/evaluate on-b row {} (ctx ann)))))
      (swap! teams assoc-in [0 :data :members] [])
      (is (t/deny? (first (g/evaluate foreman row {} (ctx ann)))))
      (is (t/deny? (first (g/evaluate on-b row {} (ctx ann)))))
      (is (= [:principal :member :team] (:reads foreman))))
    (testing "an agent's (actor :acts-for) is its person"
      (let [bot {:id "bot" :type :agent :acts-for "ann"}
            for-ann (g/expr {:name :for-ann
                             :when '(and (= (actor) "bot") (= (actor :acts-for) "ann"))
                             :explain "Not ann's agent."})]
        (is (t/allow? (first (g/evaluate for-ann row {} (ctx bot)))))
        (is (= "ann" (e/evaluate '(actor :acts-for) {:actor "ann" :acts-for "ann"})))))
    (testing "no scope is unknown; arity; a derivation reads no team"
      (is (e/unknown? (e/evaluate '(actor) {})))
      (is (e/unknown? (e/evaluate '(holds-role? "ann" "foreman") {})))
      (is (seq (e/problems '(actor :other))))
      (is (seq (e/problems '(in-team? (actor)))))
      (is (seq (e/derived-scope-problems '(holds-role? (var :x) "r") #{:x}))))))

(deftest did-unknown-door
  (let [base {:kind :study
              :states [:open :closed]
              :initial :open
              :terminal #{:closed}
              :summary "{data.name} · {state}"
              :schema [:map [:name [:string {:max 100}]]]
              :actions {:close {:from #{:open} :to :closed
                                :safety {:idempotent true :reversible false
                                         :confirm false
                                         :one-way "Closing is cheap."}}}}
        guarded (fn [form]
                  (assoc-in base [:actions :close :guards]
                            [(g/expr {:name :not-the-closer
                                      :when form
                                      :explain "Someone else closes this."})]))
        load (fn [m] (binding [*err* (java.io.StringWriter.)] (r/resource m)))]
    (is (some? (load (guarded '(not (did :close))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[did-unknown-door\].*:prepair"
                          (load (guarded '(not (did :prepair))))))))

;; ── str, and :pending :deny (docs/spec-related-row-law.md §7) ───────

(deftest str-joins-its-parts
  (is (= "ticket:T-1" (e/evaluate '(str "ticket:" (self)) {:self "T-1"})))
  (is (= "a1true:x" (e/evaluate '(str "a" 1 true ":" (data :x)) {:data {:x "x"}})))
  (is (= "2.5" (e/evaluate '(str 2.5M) {})))
  (testing "unknown in, unknown out"
    (is (e/unknown? (e/evaluate '(str "ticket:" (self)) {})))
    (is (= :unknown (ev '(str "a" (var :u))))))
  (testing "a missing part is a missing string"
    (is (nil? (e/evaluate '(str "ticket:" (data :id)) {:data {}}))))
  (testing "arity, order and the wire"
    (is (seq (e/problems '(str))))
    (is (empty? (e/problems '(str "ticket:" (self)))))
    (is (= '(str "b" "a") (e/normalize '(str "b" "a")))
        "order is meaning: str does not sort")
    (is (= '(str "ticket:" (self))
           (wire/wire->form (wire/form->wire '(str "ticket:" (self))))))))

(deftest instant-reads-an-outside-moment
  (let [at (Instant/parse "2026-10-21T09:00:00Z")]
    (is (= at (e/evaluate '(instant (event "created")) {:event {:created 1792573200}}))
        "epoch seconds")
    (is (= at (e/evaluate '(instant (event "at")) {:event {:at "2026-10-21T03:00:00-06:00"}}))
        "RFC 3339")
    (is (nil? (e/evaluate '(instant (event "created")) {:event {:created "yesterday"}})))
    (is (nil? (e/evaluate '(instant (event "created")) {:event {}})))
    (is (e/unknown? (e/evaluate '(instant (event "created")) {})) "no event is unknown")
    (is (empty? (e/problems '(instant (event "created")))))
    (is (seq (e/problems '(instant))))))

(deftest pending-deny-refuses-an-unknown-verdict
  (let [law '(exists [c :change {:born_from (= (str "ticket:" (self)))
                                 :state (= "submitted")}])
        sentence {:severity :refuse :explain "Its change is not under review."}
        strict (d/sentence-guard :its-change-is-under-review sentence law {:pending :deny})
        lenient (d/sentence-guard :its-change-is-under-review sentence law)
        row {:kind :ticket :id "T-1" :state :in_review :data {}}
        probe {:mode :probe}]
    (is (= :deny (:pending strict)))
    (is (not (contains? lenient :pending)) "the default is left out")
    (is (t/deny? (first (g/evaluate strict row nil probe)))
        "the render probe, with no hook to ask, refuses")
    (is (t/pending-input? (first (g/evaluate lenient row nil probe)))
        "without it the unknown verdict stays pending")
    (testing "a known verdict is unchanged"
      (let [ctx {:rows-matching (fn [_kind conds _n]
                                  (when (some #(= "ticket:T-1" (:value %)) conds)
                                    ["C-1"]))}]
        (is (t/allow? (first (g/evaluate strict row {} ctx))))
        (is (t/deny? (first (g/evaluate strict (assoc row :id "T-2") {} ctx))))))
    (testing "the fingerprint names :pending only when it is not the default"
      (let [plain (g/expr {:name :x :when '(= (data :a) 1) :explain "X."})
            allow (g/expr {:name :x :when '(= (data :a) 1) :explain "X." :pending :allow})]
        (is (= (@#'fp/guard-fp "k.a" plain) (@#'fp/guard-fp "k.a" allow)))
        (is (not (contains? (@#'fp/guard-fp "k.a" lenient) "pending")))
        (is (= "deny" (get (@#'fp/guard-fp "k.a" strict) "pending")))))
    (testing "a stored revision rebuilds with its :pending"
      (is (= :deny (:pending (@#'judgment/rebuild-guard
                              (@#'fp/guard-fp "ticket.complete" strict) strict)))))
    (testing "anything else is refused at the def line"
      (is (thrown? Exception (d/sentence-guard :x sentence law {:pending :maybe})))
      (is (thrown? Exception (g/expr {:name :x :when '(= (data :a) 1) :explain "X."
                                      :pending true}))))))

;; ── the boundary refuses ────────────────────────────────────────────

(deftest read-form-refuses-hostile-input
  (testing "arbitrary code is not law"
    (is (thrown? Exception (e/read-form "(eval (slurp \"/etc/passwd\"))")))
    (is (thrown? Exception (e/read-form "(fn [x] x)")))
    (is (thrown? Exception (e/read-form "(clojure.core/+ 1 2)"))))
  (testing "tagged literals are refused"
    (is (thrown? Exception (e/read-form "#inst \"2026-07-09\"")))
    (is (thrown? Exception (e/read-form "#uuid \"00000000-0000-0000-0000-000000000000\"")))
    (is (thrown? Exception (e/read-form "#custom/tag 1"))))
  (testing "floats are refused, not rounded"
    (is (thrown? Exception (e/read-form "(<= (data :x) 0.5)"))))
  (testing "arity and reference discipline"
    (is (thrown? Exception (e/read-form "(data :a :b)")))
    (is (thrown? Exception (e/read-form "(+ 1)")))
    (is (thrown? Exception (e/read-form "unbound")))
    (is (thrown? Exception (e/read-form "(it 0)"))
        "a binder reference outside any quantifier")
    (is (thrown? Exception (e/read-form "(every [d (var :days)] missing)"))))
  (testing "oversized input"
    (is (thrown? Exception
                 (e/read-form (apply str "(and " (concat (repeat 4000 "(data :a) ") [")"]))))))
  (testing "well-formed law reads"
    (is (= '(<= (abs (var :difference)) 0.02M)
           (e/read-form "(<= (abs (var :difference)) 0.02M)")))))

;; ── instants on the reader's clock (ticket 2af2d5dc) ────────────────

(def ^:private clock-guard
  (g/expr {:name :clock-guard
           :when '(is-set (data :never))
           :explain "near {near}, week {week}, far {far}, raw {raw}, clock {hm}, day {dd}"
           :vars {:near '(input :near) :week '(input :week) :far '(input :far)
                  :raw '(iso (input :far)) :hm '(clock (input :far))
                  :dd '(day (input :far))}}))

(deftest a-datetime-hole-reads-the-readers-clock
  (let [row {:id "r1" :state :open
             :data {:starts_at (Instant/parse "2099-01-14T16:00:00Z")}}
        inp {:near (Instant/parse "2099-01-14T20:00:00Z")
             :week (Instant/parse "2099-01-13T20:00:00Z")
             :far (Instant/parse "2099-01-06T20:00:00Z")}
        ctx {:mode :invoke :now (Instant/parse "2026-10-20T12:00:00Z")
             :principal {:id "p1"}
             :read (fn [kind id]
                     (when (and (= :member kind) (= "p1" (str id)))
                       {:id "p1" :data {:zone "America/Denver"}}))}
        [v d] (g/evaluate clock-guard row inp ctx)]
    (testing "on the row's day, within a week, beyond — in the member's zone"
      (is (= (str "near 13:00, week Tue 13:00, far 6 Jan 13:00, "
                  "raw 2099-01-06T20:00:00Z, clock 13:00, day Tue 6 Jan")
             (g/render-reason d v row))))
    (testing "no member zone and no row day: the house zone, today"
      (is (= "2099-01-06T20:00:00Z"
             (e/evaluate '(iso (input :far)) {:input inp})))
      (is (= "20:00" (e/evaluate '(clock (input :far)) {:input inp}))
          "no :zone in scope reads UTC"))))
