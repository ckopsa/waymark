(ns mealplan10.resources.grocery-list
  "The GroceryList resource: compiled by the AI from a finalized plan.

  The agent reads the plan's assigned meals and their recipes, then
  creates one list per plan (or per two-week stretch) in draft and
  fills it with add_item. finalize is guarded on the plan actually
  being finalized — a list can't get ahead of the plan it shops for.
  In ready the humans shop, checking items off; complete refuses while
  anything is unchecked. Two doors close the lifecycle, and they say
  different things: done means the shopping happened (the purchase
  stamp fires); discard means the list was a mistake or is superseded
  (a test compile, a plan re-cut into per-trip windows) — it leaves
  the plan's totals for good but stays readable as history, from
  draft or from ready alike, stamping nothing.

  The item-shaped actions share one part scope; item_on_list /
  item_not_checked / item_checked are pure acceptance-set
  declarations; plan_id is a :waymark/ref.

  Spelled in the batch-H declaration style: the whole machine is
  :flow rows (so :states is not spelled — the rows name them), the
  draft-phase and shopping-phase item edits reading as rows of the
  states they serve, and finalize/reopen declare each other as :undo
  — the engine verifies the pointers, so \"honestly reversible\" is
  graph-checked instead of a comment. Recorded deviation, a sentence:
  the item edits keep :input (add_item's fields are optional, and
  :args admits only required arguments). One deliberate law revision
  rode this spelling (the batch-H candidate, taken): check/uncheck
  declare each other as :undo — each is the other's exact inverse
  (same :name input, the not-checked/checked guards fencing the
  no-ops), so :reversible false was the law understating the truth.
  The part scope, the plan_id filter, and the def'd shopping rollup
  still ride their entries (batch G).
  mealplan10.style-invariance-test pins this kind's fingerprint hash
  byte-identical across spellings, the old split spelling carrying
  the same revision.

  The money layer (pantry-prices era): items carry an optional
  pantry ref and an AI-stamped estimate — the client authors the
  cross-kind price judgment through add_item, the list owns only the
  arithmetic (the three derived totals below), and the plan sums its
  live lists — every state but discarded — in the same commit.

  The pantry era (spec-pantry, era 1): the list compiles itself.
  compile_from_plan walks the derivation chain plan → plan_day →
  meal_line and writes the items — grams summed per ingredient
  across every planned night (a meal cooked twice buys twice),
  estimates summed from the lines' write-time ests (pricing is
  linear in grams, so the sum of line ests IS the total est), each
  item stamped with its provenance (:source \"plan\"; nil reads as
  manual, so existing rows need no shape bump). Recompile replaces
  only its own plan-stamped rows; hand-added extras survive.
  Honestly non-idempotent — the outcome depends on the plan and the
  price world outside the row, the reprice story.

  The purchase stamp (era 2): complete gained a handler — every
  CHECKED item carrying an ingredient ref invokes that ingredient's
  restock through ctx :invoke, advertised as :touches, so a finished
  shop refreshes the pantry in the same commit. The all-checked gate
  already guarantees checked = bought; unref'd manual items stamp
  nothing.

  The noise fix (era 3): compile consults the pantry — each grouped
  ingredient's stocked fact, read through ctx :read (materialized in
  :data by the ingredient's last write or clock sweep, the same value
  its own laws judge by). Stocked ingredients leave the list but
  never silently: assumed_on_hand is the compiler's honest record —
  {name, meals}, replaced wholesale every compile like the
  plan-stamped rows — so one glance verifies twenty staples, and a
  wrong assumption is mark_out + recompile (or just add_item) away.
  A manual item whose ingredient is assumed on hand survives
  untouched; the assumption is recorded beside it, honestly.

  The anchor/flex solver (era 4): a two-week plan gets one list per
  trip — an optional covers_from/covers_until window, both ends or
  neither (window_paired is required at create, the :distinct
  pattern), and a list with NO window covers the whole plan: era-1
  law, every existing row unchanged. The windowed compile walks the
  same chain but keeps each use's date, reads the plan's other
  lists' covers_from as the trip schedule, and assigns purchases
  greedily from the earliest use: a use rides the standing purchase
  while both clocks hold (use − trip ≤ shelf_life_days, use −
  first_use ≤ opened_shelf_life_days; a nil clock never binds), else
  a new purchase opens at the latest trip that covers it. Each
  purchase belongs to the trip where it opens, and this list
  compiles exactly its own trip's purchases — anchors (flour) ride
  trip 1, flex (cilantro, cream that won't survive opened to day 12)
  rides the later trip, and a use no trip can honestly serve still
  lands on the latest trip before it, never dropped.

  The shopping identity (the field test's second pass — waymark-cx0,
  waymark-ltr, waymark-9th): compile resolves each unstocked group's
  best tracked product exactly as the pricing law does
  (meal-line/best-product — preferred-store order, then
  cents_per_100g) and stamps product / store / product_id beside the
  item; :name STAYS the ingredient — it is the part key, and identity
  must survive product changes — and nothing resolved leaves the
  fields nil, honestly. The est is still the lines' write-time sum
  when any line priced; a group NO line priced but whose resolved
  product unit-prices is priced directly (grams × unit, HALF_UP —
  price-line's arithmetic), a priced-but-weightless product stamps
  price_note instead of inventing a unit price, and an est priced
  through a stale product stamps price_stale — unknown and $0 stop
  reading the same. Recompile resolves derive-or-curate as
  derive-the-need, curate-the-shopping: grams/quantity/meals always
  follow the plan, while a prior plan item's hand-editable fields
  (category above all — aisle curation is human truth — plus store,
  product, product_id, est_cost_cents, price_note) survive wherever
  the new compile has nothing better. And the unlinked stay visible
  (waymark-9th, decided): ingredient_id stays optional on add_item —
  birthday candles are legal — so unlinked_items counts them instead
  of coercing a ref.

  Recorded punt: the with_plan profile has no v10 spelling."
  (:require [mealplan10.resources.meal-line :as meal-line]
            [waymark10.dsl :refer [defderived defguardfn defresource
                                   defhandler guard require-fact]]
            [waymark10.types :as t])
  (:import (java.time LocalDate)))

;; ── guards ──────────────────────────────────────────────────────────

;; the recorded 8.0 §5 residue: a verdict that READS another kind's
;; state is not pure over (row, input, clock), so it stays code —
;; :reads [:plan] names the dependency honestly. The pure render probe
;; carries no :read and declines; every enforcement ctx carries it.
(defguardfn plan-is-planned
  {:reads [:plan]
   :explain "Finalize the meal plan first — the grocery list follows from it."
   :remedies [:plan/finalize]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :plan (get-in row [:data :plan_id]))]
      (cond
        (nil? plan) (t/deny {:errors {:plan_id ["plan not found"]}})
        (contains? #{:planned :active} (:state plan)) (t/allow)
        :else (t/deny)))
    (t/allow)))

;; what's on the list: the rendered enum, the per-part availability,
;; and the enforcement, from one set
(def item-on-list
  (guard {:name :item-on-list
          :judges [:name]
          :accepts (fn [row] (mapv :name (get-in row [:data :items])))
          :explain "No item named '{name}' on this list."}))

;; a checked item drops out of check_item's admitted set — so the
;; button disappears from that row instead of staying clickable for a
;; no-op
(def item-not-checked
  (guard {:name :item-not-checked
          :judges [:name]
          :accepts (fn [row]
                     (into [] (keep #(when-not (:have %) (:name %)))
                           (get-in row [:data :items])))
          :explain "'{name}' is already checked off."}))

;; the mirror of item_not_checked: uncheck_item only admits rows that
;; are actually checked, so an accidental tap has a one-tap way back
(def item-checked
  (guard {:name :item-checked
          :judges [:name]
          :accepts (fn [row]
                     (into [] (keep #(when (:have %) (:name %)))
                           (get-in row [:data :items])))
          :explain "'{name}' isn't checked off yet."}))

;; the gate judges the stored rollup fact; hoisted so its :check fn
;; has one identity per process (a fresh g/require per boot would
;; fingerprint as a different guard)
(def all-checked-gate
  (require-fact :all_items_checked
                {:explain "Some items are still unchecked — check them off (or remove them) before closing the list."
                 :remedies [:grocery_list/check_item]}))

;; ── handlers ────────────────────────────────────────────────────────

(defhandler add-item [row inp _ctx]
  (update-in row [:data :items]
             (fn [items]
               (let [items (vec items)
                     at (first (keep-indexed
                                #(when (= (:name %2) (:name inp)) %1)
                                items))]
                 (if (some? at)
                   (update items at
                           (fn [it]
                             (-> it
                                 (assoc :quantity (or (:quantity inp)
                                                      (:quantity it))
                                        :category (or (:category inp)
                                                      (:category it))
                                        ;; the AI's cross-kind price
                                        ;; judgment: a re-add only
                                        ;; overwrites what it states
                                        :ingredient_id (or (:ingredient_id inp)
                                                           (:ingredient_id it))
                                        :est_cost_cents (or (:est_cost_cents inp)
                                                            (:est_cost_cents it)))
                                 (update :meals
                                         #(vec (distinct (into (vec %)
                                                               (:meals inp))))))))
                   (conj items {:name (:name inp)
                                :quantity (:quantity inp)
                                :category (:category inp)
                                :meals (vec (:meals inp))
                                :ingredient_id (:ingredient_id inp)
                                :est_cost_cents (:est_cost_cents inp)
                                :have false}))))))

(defhandler remove-item [row inp _ctx]
  ;; removing an absent item is a no-op, so retries stay replay-safe
  (update-in row [:data :items]
             (fn [items] (vec (remove #(= (:name %) (:name inp)) items)))))

(defn- set-have [row inp have?]
  (update-in row [:data :items]
             (fn [items]
               (mapv #(if (= (:name %) (:name inp)) (assoc % :have have?) %)
                     items))))

(defhandler check-item [row inp _ctx] (set-have row inp true))
(defhandler uncheck-item [row inp _ctx] (set-have row inp false))

;; the purchase stamp (spec-pantry era 2): every CHECKED item carrying
;; an ingredient ref restocks its pantry row through the ctx :invoke
;; door — the absorb-cascade shape, one commit, one writer for
;; stocked_on. The all-checked gate already guarantees checked =
;; bought at this door; unref'd manual items ("birthday candles")
;; simply don't stamp.
(defhandler stamp-purchases [row _inp ctx]
  (let [invoke! (:invoke ctx)]
    (doseq [it (get-in row [:data :items])
            :when (and (true? (:have it)) (some? (:ingredient_id it)))]
      (invoke! :ingredient (:ingredient_id it) :restock nil))
    row))

;; ── the anchor/flex solver (spec-pantry era 4) ──────────────────────

(defn- day-span
  "Whole days from a to b — the solver's only arithmetic."
  [^LocalDate a ^LocalDate b]
  (- (.toEpochDay b) (.toEpochDay a)))

;; the coverage law, one ingredient at a time: walk its uses in date
;; order, ride the standing purchase while both clocks hold, else
;; open a new purchase at the latest trip that covers the use. The
;; greedy is safe because the newest purchase carries both the latest
;; trip and the latest first-use — if IT can't cover a use, no older
;; purchase can. Returns the uses whose purchase opens at `at`.
(defn- trip-uses
  [trips at shelf opened ls]
  (let [covers? (fn [^LocalDate trip ^LocalDate first-use ^LocalDate d]
                  ;; a purchase at `trip`, first opened at `first-use`,
                  ;; still serves the use at `d` iff both clocks hold —
                  ;; and a nil clock never binds (shelf-stable; opening
                  ;; changes nothing)
                  (and (or (nil? shelf) (<= (day-span trip d) shelf))
                       (or (nil? opened) (<= (day-span first-use d) opened))))
        opens-at (fn [^LocalDate d]
                   (or ;; the latest trip on/before the use that the
                       ;; raw clock still allows — the opened clock
                       ;; starts AT the use, so it never bars an open
                       (last (filter (fn [^LocalDate t]
                                       (and (not (.isAfter t d))
                                            (or (nil? shelf)
                                                (<= (day-span t d) shelf))))
                                     trips))
                       ;; no trip can honestly serve d (cilantro used
                       ;; day 12, one trip on day 1): the purchase
                       ;; still lands on the latest trip before d —
                       ;; the best the schedule offers; dropping it
                       ;; would silently lose groceries
                       (last (filterv (fn [^LocalDate t]
                                        (not (.isAfter t d)))
                                      trips))
                       ;; a use before every trip: the first trip, the
                       ;; same best-the-schedule-offers honesty
                       (first trips)))
        purchases
        (reduce (fn [ps {d ::date :as line}]
                  (let [p (peek ps)]
                    (if (and p (covers? (::trip p) (::first-use p) d))
                      (conj (pop ps) (update p ::uses conj line))
                      (conj ps {::trip (opens-at d) ::first-use d
                                ::uses [line]}))))
                []
                (sort-by ::date ls))]
    (into [] (comp (filter #(= at (::trip %))) (mapcat ::uses)) purchases)))

;; the era-1 compiler (spec-pantry): the derivation chain plan →
;; plan_day → meal_line, walked as handler code over ctx :find/:read
;; — the fn= boundary price-line and absorb-duplicate already record.
;; A meal cooked on two nights contributes its lines once PER NIGHT
;; (double groceries, honestly), so meal_ids are never deduped. The
;; store's find pages by :limit alone (no cursor), so the honest page
;; is one wide enough for the law: a plan owns at most 14 days
;; (:weeks caps at 2) and a recipe stays far under 200 lines.
;; Era 3, the pantry consult: each group also reads its ingredient's
;; stocked fact — a stocked group skips the items and lands in
;; assumed_on_hand instead, the noise fix that never goes silent.
;; Era 4, the windowed branch: a list carrying covers_from/covers_until
;; keeps each contribution's use date, reads the plan's other lists as
;; the trip schedule, and compiles only the purchases the solver opens
;; at ITS covers_from; the nil-window list takes every use, era-1 law
;; untouched.
;; The shopping identity (waymark-cx0/ltr): each unstocked group
;; resolves its best tracked product through meal-line/best-product —
;; the pricing law's own order — for product/store/product_id, prices
;; the group directly when no line priced but the product
;; unit-prices, stamps price_note when only a package price exists,
;; and price_stale when the est priced through a stale product. The
;; recompile merge below is derive-or-curate, resolved: derive the
;; NEED (grams, quantity, meals — always the plan's), curate the
;; SHOPPING (the prior plan item's hand-editable fields survive
;; wherever this compile has nothing better).
(defhandler compile-from-plan [row _inp ctx]
  (let [find' (:find ctx)
        read' (:read ctx)
        ;; PLAN order is the walk's own law, sorted here — the store's
        ;; find orders by created_at, and a week's days are born in one
        ;; breath (ties), so heap order would otherwise leak into item
        ;; :meals order (waymark-m6j surfaced it: the day rows gained a
        ;; maintained fact, and maintenance rewrites moved the ties)
        days (sort-by #(get-in % [:data :date])
                      (find' :plan_day {:plan_id (get-in row [:data :plan_id])
                                        :state :planned}
                             {:limit 200}))
        lines (into []
                    (mapcat
                     (fn [day]
                       (map #(assoc % ::meal
                                    (or (get-in day [:data :meal_name])
                                        (get-in % [:data :meal_name]))
                                    ;; era 4 keeps each contribution's
                                    ;; USE DATE — the night it feeds
                                    ::date (get-in day [:data :date]))
                            (find' :meal_line
                                   {:meal_id (get-in day [:data :meal_id])
                                    :state :on_recipe}
                                   {:limit 200}))))
                    days)
        from (get-in row [:data :covers_from])
        ;; the trip schedule: every windowed sibling's covers_from plus
        ;; this list's own, ascending. Draft, ready, and done lists are
        ;; all real trips — none excluded but this row itself. A
        ;; half-set window cannot be born (window_paired is required at
        ;; create), so both-ends-set IS the windowed branch; nil-window
        ;; siblings simply contribute no trip date.
        trips (when (and from (get-in row [:data :covers_until]))
                (vec (into (sorted-set from)
                           (comp (remove #(= (str (:id %)) (str (:id row))))
                                 (keep #(get-in % [:data :covers_from])))
                           (find' :grocery_list
                                  {:plan_id (get-in row [:data :plan_id])}
                                  {:limit 200}))))
        groups (group-by #(get-in % [:data :ingredient_id]) lines)
        item-of (fn [iid ing ls stocked?]
                  (let [ests (keep #(get-in % [:data :est_cost_cents]) ls)
                        grams (transduce (map #(get-in % [:data :grams]))
                                         + ls)
                        ;; the shopping identity (waymark-cx0): the
                        ;; best tracked product, resolved exactly as
                        ;; the pricing law resolves it — skipped for
                        ;; stocked groups, which buy nothing
                        best (when-not stocked?
                               (meal-line/best-product ctx iid))
                        unit (get-in best [:data :cents_per_100g])
                        latest (get-in best [:data :latest_price_cents])
                        est (cond
                              ;; the lines' write-time estimates,
                              ;; summed — pricing is linear in grams,
                              ;; so a partial sum stays an honest
                              ;; lower bound
                              (seq ests) (reduce + ests)
                              ;; no line priced, but the resolved
                              ;; product unit-prices: price the grams
                              ;; here — price-line's arithmetic,
                              ;; HALF_UP (waymark-ltr)
                              (some? unit)
                              (quot (+ (* (long grams) (long unit)) 50)
                                    100))]
                    {:name (or (some #(not-empty
                                       (get-in % [:data :ingredient_name]))
                                     ls)
                               (get-in ing [:data :name]))
                     :quantity (str grams " g")
                     :category (get-in ing [:data :category])
                     :meals (vec (distinct (keep ::meal ls)))
                     :ingredient_id iid
                     ;; :name stays the ingredient (the part key —
                     ;; identity survives product changes); the
                     ;; product identity rides beside it, nil when
                     ;; nothing tracked resolves, honestly
                     :product (get-in best [:data :name])
                     :store (get-in best [:data :store])
                     :product_id (:id best)
                     :est_cost_cents est
                     ;; a package price with no weight: visible,
                     ;; never converted — no invented unit price
                     :price_note (when (and (nil? est) (some? latest))
                                   (format "≈$%d.%02d per package, weight unknown"
                                           (quot (long latest) 100)
                                           (mod (long latest) 100)))
                     ;; line-sums can price through several products
                     ;; (and substitutions); staleness reads the one
                     ;; resolved best product — rough, and said so
                     :price_stale (when (and (some? est)
                                             (true? (get-in best [:data :price_is_stale])))
                                    true)
                     :source "plan"
                     :have false}))
        compiled
        (into []
              (keep (fn [iid]
                      (let [ls (get groups iid)
                            ;; the pantry consult (era 3): the derived
                            ;; stocked fact, materialized in the
                            ;; ingredient's :data by its last write or
                            ;; clock sweep — the same value its own
                            ;; laws judge by. Era 3 wins before era 4:
                            ;; a stocked group is the pantry's,
                            ;; whatever the trip schedule says
                            ing (read' :ingredient iid)
                            stocked? (true? (get-in ing [:data :stocked]))
                            mine (if (and trips (not stocked?))
                                   (trip-uses
                                    trips from
                                    (get-in ing [:data :shelf_life_days])
                                    (get-in ing [:data :opened_shelf_life_days])
                                    ls)
                                   ls)]
                        ;; an unstocked group whose every purchase
                        ;; opens at another trip is not this trip's
                        ;; problem — no item, no assumption
                        (when (or stocked? (seq mine))
                          (assoc (item-of iid ing mine stocked?)
                                 ::stocked stocked?)))))
              (distinct (map #(get-in % [:data :ingredient_id]) lines)))
        to-buy (into [] (comp (remove ::stocked)
                              (map #(dissoc % ::stocked)))
                     compiled)
        on-hand (filterv ::stocked compiled)]
    (-> row
        ;; the compiler's record of THIS compile, replaced wholesale
        ;; every run (the source=plan posture): stocked ingredients
        ;; leave the list but never silently — and a manual row for
        ;; one of them stays a manual row, the assumption recorded
        ;; beside it
        (assoc-in [:data :assumed_on_hand]
                  (mapv (fn [g] {:name (:name g) :meals (:meals g)})
                        on-hand))
        (update-in [:data :items]
                   (fn [items]
                     (let [prior (into {}
                                       (comp (filter #(= "plan" (:source %)))
                                             (map (juxt :name identity)))
                                       items)
                           kept (into [] (remove #(= "plan" (:source %)))
                                      items)
                           taken (into #{} (map :name) to-buy)
                           ;; derive the need, curate the shopping
                           ;; (waymark-cx0): grams/quantity/meals are
                           ;; the plan's and always recompute; the
                           ;; prior plan item's hand-editable
                           ;; shopping fields survive wherever this
                           ;; compile has nothing better — and a
                           ;; prior category ALWAYS wins over the
                           ;; ingredient-category default (aisle
                           ;; curation is human truth; the compile
                           ;; only fills a blank)
                           curate (fn [{nm :name :as it}]
                                    (if-some [old (get prior nm)]
                                      (-> it
                                          (assoc :category
                                                 (or (:category old)
                                                     (:category it)))
                                          (update :store
                                                  #(or % (:store old)))
                                          (update :product
                                                  #(or % (:product old)))
                                          (update :product_id
                                                  #(or % (:product_id old)))
                                          (update :est_cost_cents
                                                  #(or % (:est_cost_cents old)))
                                          (update :price_note
                                                  #(or % (:price_note old))))
                                      it))]
                       ;; manual items survive (nil :source); old plan
                       ;; items are the compiler's to drop, rewrite,
                       ;; and curate from. A manual item whose NAME the
                       ;; compile now claims becomes the compiled row —
                       ;; the part scope keys by :name, one row per name
                       (into (into [] (remove #(contains? taken (:name %)))
                                   kept)
                             (map curate)
                             to-buy)))))))

(defn- ensure-items [row _ctx]
  (update-in row [:data :items] #(vec (or % []))))

;; ── the declaration ─────────────────────────────────────────────────

(def name-input [:map [:name [:string {:min 1 :max 200}]]])

;; the shopping rollup as a declared fact (design §2): complete's gate
;; and its rendered reason are one definition
(defderived all-items-checked
  {:over [:items]
   :expr '(every [i (var :items)] (= (get i :have) true))
   :explain "Some items are still unchecked."})

;; the money rollups (pantry-prices era): what the week will cost, as
;; law over the embedded items. The (max … 0) wraps the nil-dodge —
;; sum nils wholesale on a nil addend, max drops nils, and est ≥ 0 by
;; schema, so an unpriced item counts 0 exactly as v9's where=is_set
(defderived estimated-total-cents
  {:over [:items]
   :expr '(sum [i (var :items)] (max (get i :est_cost_cents) 0))
   :explain "The priced items' estimates, summed."})

(defderived priced-items
  {:over [:items]
   :expr '(count [i (var :items)] (is-set (get i :est_cost_cents)))})

(defderived total-items
  {:over [:items]
   :expr '(count (var :items))})

;; the unlinked, visible (waymark-9th, decided): ingredient_id stays
;; optional on add_item — birthday candles are legal — so the count
;; surfaces what the pantry logic cannot see instead of coercing a ref
(defderived unlinked-items
  {:over [:items]
   :expr '(count [i (var :items)] (not (is-set (get i :ingredient_id))))})

;; the window's honest contract (era 4): both ends or neither, in
;; order — and required at create (the substitution :distinct
;; pattern, design §24), so a half-set window is unrepresentable
(defderived window-paired
  {:over [:covers_from :covers_until]
   :expr '(or (and (is-set (var :covers_from))
                   (is-set (var :covers_until))
                   (<= (var :covers_from) (var :covers_until)))
              (and (not (is-set (var :covers_from)))
                   (not (is-set (var :covers_until)))))
   :explain "A coverage window is both ends or neither — covers_from through covers_until, in order."})

(def window-is-paired
  (require-fact :window_paired
                {:explain "A coverage window is both ends or neither — covers_from through covers_until, in order."}))

(defresource grocery-list
  {:kind :grocery_list
   :initial :draft
   :terminal #{:done :discarded}
   :summary "Groceries · {state}"
   ;; a list is known by the trip it is for — covers_from IS the trip
   ;; date (era 4). A list with no window covers the whole plan and
   ;; renders the dash, which is the honest reading: there is only one
   :label-template "Groceries · {data.covers_from}"
   :schema [:map
            [:plan_id {:kind :plan :filter #{:eq}} :waymark/ref]
            ;; the coverage window (spec-pantry era 4): this trip's
            ;; slice of a two-week plan. Nil = the whole plan (era-1
            ;; law, every existing row); the trip date is covers_from
            [:covers_from {:optional true
                           :x-display {:label "Covers from"}}
             [:maybe :waymark/date]]
            [:covers_until {:optional true
                            :x-display {:label "Covers until"}}
             [:maybe :waymark/date]]
            [:items {:part-scope {:key :name}}
             [:vector
              [:map
               [:name [:string {:min 1 :max 200}]]
               [:quantity {:optional true}
                [:maybe [:string {:max 50}]]]
               [:category {:optional true}
                [:maybe [:string {:max 50}]]]
               [:meals {:optional true}
                [:maybe [:vector [:string {:max 200}]]]]
               ;; the pantry link and the AI-stamped estimate: the
               ;; client authors the cross-kind price judgment; the
               ;; list owns only the arithmetic
               [:ingredient_id {:optional true :kind :ingredient}
                [:maybe :waymark/ref]]
               [:est_cost_cents {:optional true
                                 :x-display {:widget "money"
                                             :label "Est. cost"}}
                [:maybe [:int {:min 0}]]]
               ;; the shopping identity (waymark-cx0): the resolved
               ;; product's name, store, and ref — compiler-stamped,
               ;; nil when nothing tracked resolves; :name stays the
               ;; ingredient, the part key
               [:product {:optional true} [:maybe [:string {:max 200}]]]
               [:store {:optional true} [:maybe [:string {:max 50}]]]
               [:product_id {:optional true :kind :product}
                [:maybe :waymark/ref]]
               ;; the honest price margins (waymark-ltr): a package
               ;; price with no weight, and an est priced through a
               ;; stale product
               [:price_note {:optional true} [:maybe [:string {:max 200}]]]
               [:price_stale {:optional true} [:maybe :boolean]]
               ;; provenance (spec-pantry era 1): the compiler's
               ;; stamp, never the client's claim — add_item's input
               ;; does not admit it, and nil reads as manual (no
               ;; shape bump for existing rows)
               [:source {:optional true} [:maybe [:enum "plan"]]]
               [:have {:optional true} [:maybe :boolean]]]]]
            ;; the compiler's record (spec-pantry era 3): what the
            ;; meals need that the pantry already holds — written only
            ;; by compile_from_plan (no create-schema entry, no action
            ;; input admits it), replaced wholesale each compile
            [:assumed_on_hand {:optional true
                               :x-display {:label "Assumed on hand"}}
             [:maybe [:vector
                      [:map
                       [:name [:string {:min 1 :max 200}]]
                       [:meals {:optional true}
                        [:maybe [:vector [:string {:max 200}]]]]]]]]
            [:all_items_checked {:optional true :derived all-items-checked}
             [:maybe :boolean]]
            ;; promoted (:filter): the plan's sums run on these
            [:estimated_total_cents {:optional true
                                     :derived estimated-total-cents
                                     :filter #{:eq :range}
                                     :x-display {:widget "money"
                                                 :label "Est. total"}}
             [:maybe :int]]
            [:priced_items {:optional true :derived priced-items
                            :filter #{:eq :range}}
             [:maybe :int]]
            [:total_items {:optional true :derived total-items
                           :filter #{:eq :range}}
             [:maybe :int]]
            ;; the unlinked count (waymark-9th): visibility instead
            ;; of coercion — manual no-ref items, surfaced
            [:unlinked_items {:optional true :derived unlinked-items
                              :filter #{:eq :range}}
             [:maybe :int]]
            [:window_paired {:optional true :derived window-paired}
             [:maybe :boolean]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   ;; the create form: pick the plan (and this trip's window, both
   ;; ends or neither); items arrive via add_item
   :create-schema [:map
                   [:plan_id {:kind :plan
                              :x-display
                              {:label "Meal plan"
                               :help "The week this trip shops for — the list compiles itself from that plan's dinners."}}
                    :waymark/ref]
                   [:covers_from {:optional true
                                  :x-display
                                  {:label "Covers from"
                                   :help "The day of the shopping trip — leave both dates blank when one trip covers the whole plan."}}
                    [:maybe :waymark/date]]
                   [:covers_until {:optional true
                                   :x-display
                                   {:label "Covers until"
                                    :help "The last night this trip has to feed, before the next trip takes over."}}
                    [:maybe :waymark/date]]
                   [:notes {:optional true
                            :examples ["Costco run — Dad's going Saturday before the kids are up."]
                            :x-display
                            {:widget "prose"
                             :label "Notes"
                             :help "What the shopper needs to know that the items can't say — which store, who's going, a coupon to bring."}}
                    [:maybe [:string {:max 2000}]]]]
   :create-guards [window-is-paired]
   :on-create ensure-items
   :links [{:rel "plan" :kind :plan
            :href "/api/plans/{data.plan_id}"
            :summary "The meal plan this list shops for"}]
   :filterable {:state #{:eq :in}}
   :display {:title "Grocery list"}
   :deviations
   ["The compile walk (plan_day → meal_line, summed per ingredient) is handler code, not law — join-and-group sits outside the expression grammar, the fn= boundary price-line and absorb-duplicate already record."
    "The era-4 coverage solver (the trip schedule, the two clocks, purchases opening greedily at the latest covering trip) is the same boundary grown — cross-row date arithmetic sits outside the grammar too, so the law lives in trip-uses and its tests."]
   ;; the whole machine as rows: the draft phase (build the list),
   ;; the shopping phase (check things off), and the doors between —
   ;; the self-loop item edits mint the idempotent-overwrite safety,
   ;; every input keyed by :name, the part-scoped rows citing
   ;; :place :items. Discard's two origins cite one opts value (the
   ;; abandon pattern): terminal like done but saying the opposite
   ;; thing, so it confirms — the worksheet's own discard voice
   ;; (danger, order 9), not decline's cheap :one-way, because a live
   ;; list can carry hand-added items and check-offs.
   :flow
   (let [discard
         {:confirm "The list was a mistake or is superseded; it leaves the plan's totals and stays readable as history."
          :display {:label "Discard list" :style :danger :order 9}}]
     [[:draft :add_item     :draft
       {:input [:map
                [:name {:x-display
                        {:label "Item"
                         :help "What to buy, in the words you'd read off in the aisle — 'chicken thighs', not a recipe line."}}
                 [:string {:min 1 :max 200}]]
                [:quantity {:optional true
                            :x-display
                            {:label "How much"
                             :help "The amount to pick up, said the way you'd say it in the store — '2 lbs', '900 g', 'one bunch'."}}
                 [:maybe [:string {:max 50}]]]
                [:category {:optional true
                            :x-display
                            {:label "Aisle"
                             :help "Where it lives in the store — produce, dairy, frozen — so the list sorts the way you actually walk it."}}
                 [:maybe [:string {:max 50}]]]
                [:meals {:optional true
                         :x-display
                         {:label "For which dinners"
                          :help "The nights this item feeds, so a cancelled dinner shows what comes off the list with it."}}
                 [:maybe [:vector [:string {:max 200}]]]]
                [:ingredient_id {:optional true :kind :ingredient
                                 :pick {:state "active"}
                                 :x-display
                                 {:label "Pantry ingredient"
                                  :help "Link it to the pantry and a finished shop restocks it — leave it blank for one-offs like birthday candles."}}
                 [:maybe :waymark/ref]]
                [:est_cost_cents {:optional true
                                  :x-display {:widget "money"
                                              :label "Est. cost"}}
                 [:maybe [:int {:min 0}]]]]
        :handler add-item
        :display {:label "Add item" :style :primary :order 1}}]
      ;; the :altitude waivers below answer a false positive: these
      ;; actions are already placed on :items — assumed_on_hand merely
      ;; shares the :name spelling, is compiler-written, and no action
      ;; addresses its entries. Waived, not re-scoped, honestly.
      [:draft :remove_item  :draft
       {:input name-input :place :items
        :requires [item-on-list]
        :waives #{:altitude}
        :handler remove-item
        :display {:label "Remove item" :order 2}}]
      [:draft :compile_from_plan :draft
       {;; the outcome depends on the plan and the price world OUTSIDE
        ;; the row — natural replay must never swallow a repeat, so:
        ;; honestly non-idempotent (the reprice story)
        :safety {:idempotent false :reversible false :confirm false}
        :handler compile-from-plan
        :display {:label "Compile from plan" :style :primary :order 3}}]
      [:draft :finalize     :ready
       {:requires [plan-is-planned]
        :undo :reopen
        :display {:label "Ready to shop" :style :primary :order 1}}]
      [:ready :check_item   :ready
       {:input name-input :place :items
        :requires [item-on-list item-not-checked]
        :undo :uncheck_item
        :waives #{:altitude}
        :handler check-item
        :display {:label "Check off" :style :primary :order 1}}]
      [:ready :uncheck_item :ready
       {:input name-input :place :items
        :requires [item-on-list item-checked]
        :undo :check_item
        :waives #{:altitude}
        :handler uncheck-item
        :display {:label "Uncheck" :order 2}}]
      [:ready :reopen       :draft
       {:undo :finalize
        :display {:label "Back to editing" :order 3}}]
      [:ready :complete     :done
       {:requires [all-checked-gate]
        :touches [{:kind :ingredient :action :restock :may true}]
        :handler stamp-purchases
        :one-way "Completing records a finished shop; the list stays readable as history."
        :display {:label "Shopping done" :order 2}}]
      [:draft :discard      :discarded discard]
      [:ready :discard      :discarded discard]])})
