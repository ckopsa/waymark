(ns dayplan10.resources.span
  "The Span resource: one contiguous window of one block on one day —
  {starts_at ends_at}, two instants the clock can compare
  (docs/spec-dayplan.md § span). A block owns its spans; plan_id is
  DENORMALISED onto the span on purpose, so the one guard that matters
  is one indexed query: no-overlap-in-plan reads the plan's other
  planned spans and refuses a window that intersects any of them. Gaps
  are allowed, overlaps never — which is what makes *the* current block
  well-defined without a tie-break.

  Two clock facts ride the row: current (*this window contains now*)
  and missed (*this window has passed*), each flipped by the
  maintainer at the instant itself, no write, no poll — prep_task's
  overdue, twice. missed is a fact about the WINDOW: a derived law may
  only read data fields and the clock (the machine's state is not one),
  so *missed and still planned* is the read-side pairing
  `?missed=true&state=planned`, which the feed and the notice both
  spell; a done or skipped span whose window has passed reads missed
  too, and its state says what happened.

  The doors, each a sentence: move takes a new window; swap exchanges
  windows with another planned span of the same day; extend reaches
  later and slides ONLY the next span's start — never its end — and
  refuses when that would leave the neighbour no time at all (the one
  thing this model does on a person's behalf); split ends this span at
  an instant and births a second span of the same block after a gap
  (the lunch hour, unless named); skip lets the window go; finish says
  it was worked. Every refusal names what would make the door
  available.

  Spelled with the :fields lifecycle groups: four facts fixed at birth,
  no authoring phase, two engine-maintained clock facts."
  (:require [dayplan10.zone :as zone]
            [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario described ref-to]]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── reading the day ─────────────────────────────────────────────────

(defn- planned-spans-of
  "The plan's planned spans — the set no-overlap holds — other than
  the row itself."
  [ctx plan-id row]
  (->> ((:find ctx) :span {:plan_id (str plan-id) :state "planned"} {:limit 500})
       (remove #(= (:id %) (:id row)))))

(defn- overlaps?
  "Two half-open windows share a minute."
  [^Instant s1 ^Instant e1 ^Instant s2 ^Instant e2]
  (and (.isBefore s1 e2) (.isBefore s2 e1)))

(defn- next-span
  "The plan's nearest planned span starting at or after this row's end
  — the neighbour extend may lean on; nil when the rest of the day is
  clear."
  [ctx row]
  (let [^Instant e (get-in row [:data :ends_at])]
    (->> (planned-spans-of ctx (get-in row [:data :plan_id]) row)
         (filter #(not (.isBefore ^Instant (get-in % [:data :starts_at]) e)))
         (sort-by #(get-in % [:data :starts_at]))
         first)))

(defn- window-label
  "How a refusal names another span: its block and its window, as the
  household reads a clock."
  [s]
  (str (or (get-in s [:data :block_name]) "another")
       " " (zone/clock (get-in s [:data :starts_at]))
       "–" (zone/clock (get-in s [:data :ends_at]))))

(def ^:private lunch-hour 60)

(defn- gap-of
  "The split's gap in minutes — the lunch hour unless the input names
  one (an explicit blank is the default too: no gap is a *zero*)."
  [inp]
  (long (or (:gap_minutes inp) lunch-hour)))

;; ── the guards ──────────────────────────────────────────────────────

(defguardfn ends-after-starts
  {:judges [:starts_at :ends_at]
   :explain "A window ends after it starts — {starts_at} to {ends_at} is no time at all."}
  [_row inp _ctx]
  (let [^Instant s (:starts_at inp)
        ^Instant e (:ends_at inp)]
    (if (and s e (.isBefore s e))
      (t/allow)
      (t/deny {:vars {:starts_at (zone/clock s) :ends_at (zone/clock e)}}))))

(defguardfn still-ahead
  {:reads [:now]
   :vars [:ends_at]
   :explain "This window ended at {ends_at}; a span whose time has passed is history — plan the next one instead."}
  [row _inp ctx]
  (let [^Instant e (get-in row [:data :ends_at])]
    (if (and e (.isAfter e ^Instant (:now ctx)))
      (t/allow)
      (t/deny {:vars {:ends_at (zone/clock e)}}))))

(defguardfn no-overlap-in-plan
  {:judges [:starts_at :ends_at] :reads [:span :within]
   :vars [:from :to :other]
   :remedies [:span/move :span/skip]
   :explain "{from}–{to} overlaps another span of this day ({other}); move or skip that one first. Gaps are fine, overlaps never — one block is current at a time."}
  [row inp ctx]
  ;; swap and split rearrange windows INSIDE a set that already has no
  ;; overlaps — an exchange, and a second half born inside the window
  ;; the first just vacated — so the door they open inside yields to
  ;; them by name (`:within`, vocabulary § 6) and to nothing else
  (let [{:keys [kind action]} (:within ctx)]
    (cond
      (nil? (:find ctx)) (t/allow)

      (and (= :span kind) (contains? #{:swap :split} action)) (t/allow)

      :else
      (let [plan-id (or (:plan_id inp) (get-in row [:data :plan_id]))
            s (:starts_at inp)
            e (:ends_at inp)
            hit (some (fn [o]
                        (when (overlaps? s e
                                         (get-in o [:data :starts_at])
                                         (get-in o [:data :ends_at]))
                          o))
                      (planned-spans-of ctx plan-id row))]
        (if hit
          (t/deny {:vars {:from (zone/clock s) :to (zone/clock e)
                          :other (window-label hit)}})
          (t/allow))))))

(defguardfn swap-partner-is-ahead-on-this-day
  {:judges [:with_span_id] :reads [:span :now]
   :explain "Swap exchanges windows with another planned span of this same day whose time is still ahead — {with_span_id} is not one."}
  [row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [other ((:read ctx) :span (:with_span_id inp))]
      (if (and other
               (not= (:id other) (:id row))
               (= :planned (:state other))
               (= (str (get-in other [:data :plan_id]))
                  (str (get-in row [:data :plan_id])))
               (.isAfter ^Instant (get-in other [:data :ends_at]) ^Instant (:now ctx)))
        (t/allow)
        (t/deny {:vars {:with_span_id (str (:with_span_id inp))}})))))

(defguardfn extends-later
  {:judges [:ends_at]
   :vars [:current_end]
   :explain "Extend reaches later than the window's end ({current_end}); to end sooner, move the span."}
  [row inp _ctx]
  (let [^Instant e (:ends_at inp)
        ^Instant cur (get-in row [:data :ends_at])]
    (if (and e cur (.isAfter e cur))
      (t/allow)
      (t/deny {:vars {:current_end (zone/clock cur)}}))))

(defguardfn neighbour-keeps-some-width
  {:judges [:ends_at] :reads [:span]
   :vars [:to :neighbour]
   :remedies [:span/move :span/skip]
   :explain "Extending to {to} would leave your {neighbour} span no time at all; shorten it (move) or skip it first."}
  [row inp ctx]
  (if (nil? (:find ctx))
    (t/allow)
    (let [^Instant e (:ends_at inp)
          nb (next-span ctx row)]
      (if (and nb (not (.isBefore e ^Instant (get-in nb [:data :ends_at]))))
        (t/deny {:vars {:to (zone/clock e) :neighbour (window-label nb)}})
        (t/allow)))))

(defguardfn at-inside-window
  {:judges [:at :gap_minutes]
   :vars [:starts_at :ends_at]
   :explain "Split at a moment inside the window with time left after the gap — this span runs {starts_at} to {ends_at}."}
  [row inp _ctx]
  (let [^Instant at (:at inp)
        ^Instant s (get-in row [:data :starts_at])
        ^Instant e (get-in row [:data :ends_at])
        resume (when at (.plusSeconds at (* 60 (gap-of inp))))]
    (if (and at (.isAfter at s) (.isBefore resume e))
      (t/allow)
      (t/deny {:vars {:starts_at (zone/clock s) :ends_at (zone/clock e)}}))))

;; ── the handlers ────────────────────────────────────────────────────

(defn- wire-window
  "A window as the inner door takes it — the digest speaks wire, so
  instants cross as their RFC 3339 strings."
  [^Instant s ^Instant e]
  {:starts_at (str s) :ends_at (str e)})

(defhandler move-window [row inp _ctx]
  (update row :data assoc :starts_at (:starts_at inp) :ends_at (:ends_at inp)))

(defhandler swap-windows [row inp ctx]
  ;; THE EXCHANGE. The partner takes this row's window through its own
  ;; move door — still-ahead judged again, no-overlap yielding to the
  ;; swap by name — and this row takes the partner's. Same transaction:
  ;; a refusal on either side rolls both back, so no swap ever moves
  ;; one window and not the other.
  (let [other ((:read ctx) :span (:with_span_id inp))
        theirs (select-keys (:data other) [:starts_at :ends_at])]
    ((:invoke ctx) :span (:id other) :move
     (wire-window (get-in row [:data :starts_at]) (get-in row [:data :ends_at])))
    (update row :data merge theirs)))

(defhandler extend-window [row inp ctx]
  ;; THE ONE SLIDE. When the new end reaches into the next planned span,
  ;; that span's START moves to the new end through its own move door —
  ;; its end stands, so the rest of the day stands. The guard has
  ;; already refused the slide that would leave it no width.
  (let [^Instant e (:ends_at inp)
        nb (next-span ctx row)]
    (when (and nb (.isBefore ^Instant (get-in nb [:data :starts_at]) e))
      ((:invoke ctx) :span (:id nb) :move
       (wire-window e (get-in nb [:data :ends_at]))))
    (assoc-in row [:data :ends_at] e)))

(defhandler split-window [row inp ctx]
  ;; THIS SPAN ENDS AT :at; a second span of the same block is born
  ;; after the gap and runs to the old end, through the ordinary create
  ;; door — no-overlap yields to the split by name, because the second
  ;; half lies inside the window this row is vacating in the same
  ;; stroke.
  (let [^Instant at (:at inp)
        resume (.plusSeconds at (* 60 (gap-of inp)))]
    ((:create ctx) :span {:block_id (get-in row [:data :block_id])
                          :plan_id (get-in row [:data :plan_id])
                          :starts_at resume
                          :ends_at (get-in row [:data :ends_at])})
    (assoc-in row [:data :ends_at] at)))

;; ── the law, written down as scenarios ──────────────────────────────
;;
;; Every door here reads the plan's other spans (:reads [:span]), so
;; these are CONFORMANCE-tier: staged through the real doors and judged
;; by the real clock. Windows are therefore spelled in 2099 (ahead) or
;; 2020 (passed), and each scenario's plan id is its own, so the spans
;; it stages are the only ones no-overlap can see.

(def ^:private a-plan "01HZQ7Y7F2R3W4V5X6Y7Z8A9E0")
(def ^:private another-plan "01HZQ7Y7F2R3W4V5X6Y7Z8A9E1")
(def ^:private a-block "01HZQ7Y7F2R3W4V5X6Y7Z8A9E2")

(defn- window [plan from to]
  {:block_id a-block :plan_id plan :starts_at from :ends_at to})

(defscenario an-overlap-is-refused
  "Two spans of one day never share a minute: a window that intersects
   a planned span of the same plan is refused, naming the span in the
   way and the two doors that would clear it."
  {:kind    :span
   :attempt :create
   :given   [{:kind :span :state :planned
              :data (window a-plan "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}]
   :input   (window a-plan "2099-01-06T10:00:00Z" "2099-01-06T11:00:00Z")
   :as      {:id "colton" :type :person}
   :expect  {:refused :no-overlap-in-plan
             :because "overlaps another span of this day"
             :remedies [:span/move :span/skip]}})

(defscenario a-gap-is-not-an-overlap
  "Gaps are fine: a window that ends exactly where the next begins is
   admitted — the day may have holes, it may not have collisions."
  {:kind    :span
   :attempt :create
   :given   [{:kind :span :state :planned
              :data (window another-plan "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}]
   :input   (window another-plan "2099-01-06T12:00:00Z" "2099-01-06T13:00:00Z")
   :as      {:id "colton" :type :person}
   :expect  {:allowed true}})

(defscenario a-past-window-does-not-move
  "A span whose window has passed is history; move refuses it and says
   to plan the next one."
  {:kind    :span
   :attempt :move
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E3"
                           "2020-01-06T09:00:00Z" "2020-01-06T12:00:00Z")}
   :input   {:starts_at "2020-01-06T13:00:00Z" :ends_at "2020-01-06T14:00:00Z"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :still-ahead
             :because "has passed"}})

(defscenario a-window-ends-after-it-starts
  "A window of no time at all is refused before anything else is asked
   of it."
  {:kind    :span
   :attempt :move
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E4"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:starts_at "2099-01-06T14:00:00Z" :ends_at "2099-01-06T13:00:00Z"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :ends-after-starts
             :because "no time at all"}})

(defscenario swap-needs-a-partner-on-this-day
  "Swap exchanges windows with another planned span of the same plan;
   a span that is not there is refused by name."
  {:kind    :span
   :attempt :swap
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E5"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:with_span_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9E6"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :swap-partner-is-ahead-on-this-day}})

(defscenario extend-never-squeezes-a-neighbour-to-nothing
  "Extending to the neighbour's own end would leave it no time at all;
   the door refuses, names the neighbour, and offers move or skip."
  {:kind    :span
   :attempt :extend
   :given   [{:kind :span :state :planned
              :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E7"
                            "2099-01-06T13:00:00Z" "2099-01-06T14:00:00Z")}]
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E7"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:ends_at "2099-01-06T14:00:00Z"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :neighbour-keeps-some-width
             :because "no time at all"
             :remedies [:span/move :span/skip]}})

(defscenario extend-reaches-later
  "Extend only ever reaches later; an earlier end is a move, and the
   refusal says so."
  {:kind    :span
   :attempt :extend
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E8"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:ends_at "2099-01-06T11:00:00Z"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :extends-later
             :because "move the span"}})

(defscenario a-split-happens-inside-the-window
  "Split wants a moment inside the window with time left after the
   gap; a moment outside it is refused naming the window."
  {:kind    :span
   :attempt :split
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9E9"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:at "2099-01-06T14:00:00Z"}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :at-inside-window
             :because "inside the window"}})

(defscenario a-gap-may-not-swallow-the-rest
  "A split whose gap runs past the window's end would leave a second
   half of no time at all; split earlier or name a smaller gap."
  {:kind    :span
   :attempt :split
   :row     {:state :planned
             :data (window "01HZQ7Y7F2R3W4V5X6Y7Z8A9EA"
                           "2099-01-06T09:00:00Z" "2099-01-06T12:00:00Z")}
   :input   {:at "2099-01-06T11:30:00Z" :gap_minutes 60}
   :at      "2026-09-05T12:00:00Z"
   :as      {:id "colton" :type :person}
   :expect  {:refused :at-inside-window}})

;; ── the declaration ─────────────────────────────────────────────────

(defresource span
  {:kind :span
   :plural "spans"
   :nav :secondary
   :states [:planned :done :skipped]
   :initial :planned
   ;; neither ending is terminal — unskip and reopen are the honest
   ;; reverses, the chore-run shape
   :terminal #{}
   :over {:accomplished #{:done} :let-go #{:skipped}}
   :summary "{data.block_name} · {data.starts_at} → {data.ends_at} · {state}"
   :label-template "{data.block_name} {data.starts_at} → {data.ends_at}"
   ;; plan_id is the guard's one indexed query; current and missed are
   ;; the feed's and the notice's
   :filterable {:state #{:eq :in} :plan_id #{:eq} :block_id #{:eq}
                :current #{:eq} :missed #{:eq}}
   :sortable {:fields [:starts_at] :default "starts_at"}
   :deviations
   ["missed reads the window alone — a derived law may read data fields and the clock, never the machine's state — so 'missed and still planned' is the read-side pairing ?missed=true&state=planned."]

   :fields
   {:at-create [[:block_id (described (ref-to :block {:label :block_name})
                                      {:label "Which block"
                                       :help "The block this window belongs to — the context's presence on the day."})]
                [:plan_id (described (ref-to :day_plan)
                                     {:label "Which day"
                                      :help "The day plan, stated here too so the day's no-overlap check is one query."})]
                [:starts_at (described :waymark/instant
                                       {:label "Starts"
                                        :help "When the window opens."})]
                [:ends_at (described :waymark/instant
                                     {:label "Ends"
                                      :help "When it closes — after it starts, and clear of the day's other windows."})]]
    :open      #{:planned}
    ;; engine-maintained, clock-flipped: the maintainer indexes the two
    ;; instants and sweeps them
    :facts     [[:current :boolean]
                [:missed :boolean]]}

   :derived
   {:current {:over [:starts_at :ends_at :now]
              :expr '(and (<= (var :starts_at) (var :now))
                          (< (var :now) (var :ends_at)))}
    :missed  {:over [:ends_at :now]
              :expr '(<= (var :ends_at) (var :now))}}

   :create-guards [ends-after-starts no-overlap-in-plan]

   :actions
   {:move
    {:from #{:planned} :to :planned
     :input [:map
             [:starts_at {:x-display {:label "Starts"}} :waymark/instant]
             [:ends_at {:x-display {:label "Ends"}} :waymark/instant]]
     :guards [still-ahead ends-after-starts no-overlap-in-plan]
     :edit {:prefill [:starts_at :ends_at]}
     :handler move-window
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Move" :order 1}}

    :swap
    {:from #{:planned} :to :planned
     :input [:map
             [:with_span_id {:kind :span
                             :x-display {:label "Swap with"
                                         :help "Another planned span of this same day; the two exchange windows."}}
              :waymark/ref]]
     :guards [still-ahead swap-partner-is-ahead-on-this-day]
     :handler swap-windows
     ;; the partner's move, without :may — a swap that moved one side
     ;; would be a lie
     :touches [{:kind :span :action :move}]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Swap" :order 2}}

    :extend
    {:from #{:planned} :to :planned
     :input [:map
             [:ends_at {:x-display {:label "Extend to"
                                    :help "The new end; the next span's start slides to meet it, never its end."}}
              :waymark/instant]]
     :guards [still-ahead extends-later neighbour-keeps-some-width]
     :edit {:prefill [:ends_at]}
     :handler extend-window
     :touches [{:kind :span :action :move :may true}]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Extend" :order 3}}

    :split
    {:from #{:planned} :to :planned
     :input [:map
             [:at {:x-display {:label "Split at"
                               :help "The moment this span ends; a second span of the same block starts after the gap."}}
              :waymark/instant]
             [:gap_minutes {:optional true :default 60
                            :x-display {:label "Gap (minutes)"
                                        :help "The break between the two halves — the lunch hour unless you say otherwise."}}
              [:maybe [:int {:min 0 :max 720}]]]]
     :guards [still-ahead at-inside-window]
     :handler split-window
     :touches [{:kind :span :action :create}]
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Split" :order 4}}

    :finish
    {:from #{:planned} :to :done
     :undo :reopen
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Done" :style :primary :order 5}}

    :skip
    {:from #{:planned} :to :skipped
     :undo :unskip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Skip" :order 6}}

    :reopen
    {:from #{:done} :to :planned
     :undo :finish
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 7}}

    :unskip
    {:from #{:skipped} :to :planned
     :undo :skip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Un-skip" :order 7}}}})
