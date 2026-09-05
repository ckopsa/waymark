(ns dayplan10.resources.block
  "The Block resource: one context's presence on one day
  (docs/spec-dayplan.md § block). The workday, the shop, the evening —
  each a row on the plan, carrying a stance (free text: *heads down,
  phone off* — no launch, no verdict) and owning the SPANS it occupies,
  because the workday continues after lunch and one context can hold
  several windows — and owning the DECISIONS made into it
  (dayplan10.resources.decision). Decisions belong to the block, never
  to a span; a span door never moves a decision, and skipping the
  block lets its planned and started decisions go with its spans.

  BORN WITH ITS WINDOWS. The birth door hands a hook no id back while
  a create is still deferred, so a plan cannot mint a block and then
  address its spans — instead the block's create takes an optional
  vector of windows and its own :on-create births one span per window,
  the grandchildren landing right after it. That is also what makes
  *adding a block the shape left out* one call: the ordinary create
  door with one window in it (or none, and a span added after). A
  block created by hand takes its date from its plan, never from the
  caller.

  Two endings, said out loud through :over: done is the block worked,
  skipped is the block let go — and skipping cascades to the planned
  spans through the :owns edge, advertised by :touches. Neither ending
  is a tomb: unskip and reopen are the honest reverses, the chore-run
  shape.

  current — *any span of mine contains now* — is NOT a stored fact
  here, on purpose. The aggregate grammar could count spans whose own
  clock fact reads true, but the maintainer's clock sweep does not
  chain into a parent's counts (waymark10.server.maintainer, recorded
  bound), so a stored copy would go stale at every flip; the spec
  allows exactly this fallback. The population answers it with one
  indexed query on span by plan_id and current=true, and nothing here
  is a second writer of the clock.

  Spelled :schema + :create-schema + :actions rather than the :fields
  groups the spec sketched: the windows are a create-only input the
  hook consumes, and a :fields :at-create row has no optional spelling."
  (:require [waymark10.dsl :refer [defguardfn defhandler defresource
                                   defscenario]]
            [waymark10.types :as t]))

;; ── the create wall ─────────────────────────────────────────────────

(defguardfn on-an-open-days-plan
  {:judges [:plan_id] :reads [:day_plan]
   :vars [:why]
   :explain "A block sits on a day that is still being planned or lived: {why}."}
  [_row inp ctx]
  (if (nil? (:read ctx))
    (t/allow)
    (let [plan ((:read ctx) :day_plan (:plan_id inp))]
      (cond
        (nil? plan)
        (t/deny {:vars {:why "no plan stands at that id — create the day first"}
                 :errors {:plan_id ["plan not found"]}})

        (= :closed (:state plan))
        (t/deny {:vars {:why (str "the plan for " (get-in plan [:data :date])
                                  " is closed, and a closed day takes no new block")}})

        :else (t/allow)))))

;; ── the birth ───────────────────────────────────────────────────────

(defn- stamp-and-mint
  "The block takes its date from its plan and births one span per
  window it was born with; the windows themselves are not stored — the
  spans are the truth, and a stored copy would be a second writer."
  [row ctx]
  (let [plan ((:read ctx) :day_plan (get-in row [:data :plan_id]))
        windows (get-in row [:data :windows])]
    (doseq [{:keys [starts_at ends_at]} windows]
      ((:create ctx) :span {:block_id (:id row)
                            :plan_id (get-in row [:data :plan_id])
                            :starts_at starts_at
                            :ends_at ends_at}))
    (-> row
        (assoc-in [:data :date] (get-in plan [:data :date]))
        (update :data dissoc :windows))))

;; ── the stance ──────────────────────────────────────────────────────

(defhandler set-stance [row inp _ctx]
  (assoc-in row [:data :stance] (:stance inp)))

;; ── the law, written down as scenarios ──────────────────────────────

(defscenario a-block-sits-on-a-plan-that-exists
  "A block for a plan nobody made is refused naming the fix — create
   the day first — rather than landing a block no day would list."
  {:kind    :block
   :attempt :create
   :input   {:plan_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9D0"
             :context_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9D1"}
   :as      {:id "colton" :type :person}
   :expect  {:refused :on-an-open-days-plan
             :because "create the day first"}})

;; ── the declaration ─────────────────────────────────────────────────

(def ^:private window-form
  [:map
   [:starts_at {:x-display {:label "Starts"}} :waymark/instant]
   [:ends_at {:x-display {:label "Ends"}} :waymark/instant]])

(defresource block
  {:kind :block
   :plural "blocks"
   :nav :secondary
   :states [:planned :skipped :done]
   :initial :planned
   ;; neither ending is terminal — unskip and reopen are the honest
   ;; reverses, and the plan's reshape lets a block go through skip
   :terminal #{}
   :over {:accomplished #{:done} :let-go #{:skipped}}
   :summary "{data.context_name} · {data.date} · {state}"
   ;; the context's maintained label copy plus the date is how the
   ;; household says which block it means; a span's block_name and the
   ;; feed's card both read this
   :label-template "{data.context_name} · {data.date}"
   :filterable {:state #{:eq :in}}
   :schema [:map
            [:plan_id {:kind :day_plan :filter #{:eq}
                       :x-display {:label "Which day"
                                   :help "The day plan this block sits on."}}
             :waymark/ref]
            ;; the context's name rides as the maintained label; its seam
            ;; sentence is carried alongside, so the card reads 'your
            ;; Workday block' and its closing line without a hop
            [:context_id {:kind :context :filter #{:eq}
                          :label :context_name
                          :carry {:seam :context_seam}
                          :x-display {:label "Which context"
                                      :help "The context this block is — the workday, the shop, the evening."}}
             :waymark/ref]
            [:date {:filter #{:eq :range} :sort :default
                    :x-display {:label "Day" :hidden true}}
             :waymark/date]
            [:stance {:optional true
                      :examples ["Heads down, phone in the drawer."]
                      :x-display {:widget "prose"
                                  :label "Stance"
                                  :help "How you mean to be in this block — one line, no verb the engine acts on."}}
             [:maybe [:string {:max 500}]]]]
   ;; the create form: the plan, the context, and the windows the
   ;; block is born with — the date comes from the plan
   :create-schema [:map
                   [:plan_id {:kind :day_plan
                              :x-display {:label "Which day"
                                          :help "The day plan this block sits on."}}
                    :waymark/ref]
                   [:context_id {:kind :context
                                 :x-display {:label "Which context"
                                             :help "The context this block is — the workday, the shop, the evening."}}
                    :waymark/ref]
                   [:windows {:optional true
                              :x-display {:label "Windows"
                                          :help "The windows the block opens with, each a start and an end on the day; leave it empty to add spans one at a time."}}
                    [:maybe [:vector window-form]]]]
   :create-guards [on-an-open-days-plan]
   :on-create stamp-and-mint
   ;; skipping the block lets its planned spans — and its planned or
   ;; started decisions — go with it
   :owns {:spans {:kind :span :via :block_id :on {:skip :skip}}
          :decisions {:kind :decision :via :block_id :on {:skip :skip}}}
   :links [{:rel "spans" :owns :span :embed true
            :summary "The windows this block occupies on the day"}
           {:rel "decisions" :owns :decision :embed true
            :summary "What the person meant to do in this block, in order"}]
   :actions
   {:restate
    {:from #{:planned} :to :planned
     :input [:map [:stance {:x-display {:widget "prose"
                                        :label "Stance"
                                        :help "How you mean to be in this block — one line."}}
                   [:string {:min 1 :max 500}]]]
     :edit {:prefill [:stance]}
     :handler set-stance
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Restate" :order 3}}

    :finish
    {:from #{:planned} :to :done
     :undo :reopen
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Done" :style :primary :order 1}}

    :skip
    {:from #{:planned} :to :skipped
     :undo :unskip
     ;; the cascade IS a touch — advertised; :may because a block
     ;; whose spans are all done has none left to skip, and one with
     ;; no decisions yet has nothing to let go
     :touches [{:kind :span :action :skip :may true}
               {:kind :decision :action :skip :may true}]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Skip" :order 2}}

    :reopen
    {:from #{:done} :to :planned
     :undo :finish
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reopen" :order 4}}

    :unskip
    {:from #{:skipped} :to :planned
     :undo :skip
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Un-skip" :order 4}}}})
