(ns waymark10.phase8-test
  "Phase-8 acceptance: the engine gaps the mealplan10 dogfood forced —
  cross-resource guard reads (ctx :read/:find), engine-maintained ref
  labels, one-of clears, the owns cascade, shape upcasts at the load
  boundary, the Mirror, and the :waymark/instant clock. Suite-local
  kinds (fixtures.clj is frozen); every deftest owns its world against
  real Postgres; WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maint]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["p8_authors" "p8_books" "p8_weeks" "p8_projects" "p8_tasks" "p8_notes"
   "p8_feed_items" "p8_todos" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng [resources opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources resources} opts)))
      (finally (pg/close! st)))))

(def ^:private priya (t/principal {:id "priya" :display "Priya"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- decoded [eng kind id]
  (inv/decode-row (get (inv/resources eng) kind) (reload eng kind id)))

(defn- transitions-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id id} {}))))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (if (:waymark10/problem (ex-data e)) (ex-data e) (throw e)))))

;; ── fixtures: authors and books (ctx :read + root ref labels) ───────

(defhandler rename-author [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(def ^:private author
  (r/resource
   {:kind :p8_author
    :plural "p8_authors"
    :states [:active :retired]
    :initial :active
    :terminal #{:retired}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 60}]]]
    :actions
    {:rename {:from #{:active} :to :active
              ;; input shape deliberately ≠ the data field's: this
              ;; fixture provokes the rename-does-not-relabel scope,
              ;; not the edit lint
              :input [:map [:name [:string {:min 1 :max 61}]]]
              :safety {:idempotent true :reversible true :confirm false}
              :handler rename-author}
     :retire {:from #{:active} :to :retired
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A retired author is history."}}}}))

(g/defguard author-is-active
  {:judges [:author_id] :reads [:p8_author]
   :explain "That author is not active."}
  [_row inp ctx]
  (let [a ((:read ctx) :p8_author (:author_id inp))]
    (if (and a (= :active (:state a))) (t/allow) (t/deny))))

(defhandler set-author [row inp _ctx]
  (assoc-in row [:data :author_id] (:author_id inp)))

(def ^:private book
  (r/resource
   {:kind :p8_book
    :plural "p8_books"
    :states [:draft :done]
    :initial :draft
    :terminal #{:done}
    :summary "{data.title} · {state}"
    ;; no :name field — the declared template is the label's source
    :label-template "{data.title}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:author_id {:optional true :kind :p8_author :label :author_name}
              [:maybe :waymark/ref]]
             [:author_name {:optional true} [:maybe [:string {:max 200}]]]]
    :actions
    {:assign_author {:from #{:draft} :to :draft
                     ;; a pick, not an edit — the props keep the form
                     ;; distinct from the data field's, so the edit
                     ;; lint's mirror heuristic stays quiet
                     :input [:map [:author_id {:kind :p8_author}
                                   [:waymark/ref {:pick true}]]]
                     :guards [author-is-active]
                     :safety {:idempotent true :reversible true :confirm false}
                     :handler set-author}
     :finish {:from #{:draft} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is history."}}}}))

;; ── fixtures: the week (item ref labels + one-of clears) ────────────

(def ^:private date-in-week
  (g/guard {:name :date-in-week
            :judges [:date]
            :accepts (fn [row] (mapv :date (get-in row [:data :days])))
            :explain "{date} is not a day of this week."}))

(defhandler assign-book [row inp _ctx]
  (update-in row [:data :days]
             (fn [days]
               (mapv #(if (= (:date %) (:date inp))
                        (assoc % :book_id (:book_id inp))
                        %)
                     days))))

(defhandler mark-out [row inp _ctx]
  (update-in row [:data :days]
             (fn [days]
               (mapv #(if (= (:date %) (:date inp))
                        (assoc % :eating_out true)
                        %)
                     days))))

(def ^:private week
  (r/resource
   {:kind :p8_week
    :plural "p8_weeks"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "Week of {data.start} · {state}"
    :schema [:map
             [:start :waymark/date]
             [:days [:vector
                     [:map
                      [:date :waymark/date]
                      [:book_id {:optional true :kind :p8_book :label :book_title}
                       [:maybe :waymark/ref]]
                      [:book_title {:optional true} [:maybe [:string {:max 200}]]]
                      [:eating_out {:optional true} [:maybe :boolean]]]]]]
    :one-of {:days/coverage {:in [:days]
                             :arms {:book [:book_id :book_title]
                                    :out [:eating_out]}
                             :clears true}}
    :part-scopes {:days {:path :days :key :date}}
    :actions
    {:assign {:from #{:open} :to :open
              :place :days
              :input [:map
                      [:date :waymark/date]
                      [:book_id {:kind :p8_book} :waymark/ref]]
              :guards [date-in-week]
              :safety {:idempotent true :reversible true :confirm false}
              :handler assign-book}
     :mark_out {:from #{:open} :to :open
                :place :days
                :input [:map [:date :waymark/date]]
                :guards [date-in-week]
                :safety {:idempotent true :reversible true :confirm false}
                :handler mark-out}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed week is history."}}}}))

;; ── fixtures: the owns cascade + rollup ─────────────────────────────

(def ^:private project
  (r/resource
   {:kind :p8_project
    :plural "p8_projects"
    :states [:open :abandoned]
    :initial :open
    :terminal #{:abandoned}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:open_tasks {:optional true} [:maybe :int]]]
    :owns [{:kind :p8_task :via :project_id :on {:abandon :cancel}}]
    :derived {:open_tasks {:count {:owns :p8_task
                                   :where {:state #{"pending"}}}}}
    :actions
    {:abandon {:from #{:open} :to :abandoned
               :safety {:idempotent true :reversible false :confirm true
                        :consequence "The project's open tasks are cancelled."}}}}))

(def ^:private task
  (r/resource
   {:kind :p8_task
    :plural "p8_tasks"
    :states [:pending :done :cancelled]
    :initial :pending
    :terminal #{:done :cancelled}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:project_id {:kind :p8_project} :waymark/ref]]
    :filterable {:project_id #{:eq}}
    :actions
    {:complete {:from #{:pending} :to :done
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is history."}}
     :cancel {:from #{:pending} :to :cancelled
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Cancelled is history."}}}}))

;; ── fixtures: shape upcasts ─────────────────────────────────────────

(defn- fold-tag
  "shape 1 → 2: the single-tag era's :tag becomes a one-entry :tags.
  Idempotent: an already-folded document passes through."
  [data]
  (let [tag (:tag data)
        data (dissoc data :tag)]
    (if (and tag (empty? (:tags data)))
      (assoc data :tags [tag])
      data)))

(defhandler touch-note [row _inp _ctx]
  (update-in row [:data :touches] (fnil inc 0)))

(def ^:private note
  (r/resource
   {:kind :p8_note
    :plural "p8_notes"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :shape 2
    :upcasts {1 fold-tag}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:tags {:optional true} [:maybe [:vector [:string {:max 40}]]]]
             [:touches {:optional true} [:maybe :int]]]
    :actions
    {:touch {:from #{:open} :to :open
             :safety {:idempotent false :reversible true :confirm false}
             :handler touch-note}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed note is history."}}}}))

;; ── fixtures: the mirror ────────────────────────────────────────────

(defrecord FakeFeed [state]
  mirror/MirrorAdapter
  (discover [_]
    (let [{:keys [down discoverable]} @state]
      (when down (throw (ex-info "feed unreachable" {})))
      discoverable))
  (pull [_ xid]
    (swap! state update :pulls inc)
    (let [{:keys [down docs]} @state]
      (when down (throw (ex-info "feed unreachable" {})))
      (or (get docs xid)
          (throw (ex-info (str xid " is gone") {})))))
  (pull-many [_ xids]
    (swap! state update :pulls inc)
    (let [{:keys [down docs]} @state]
      (when down (throw (ex-info "feed unreachable" {})))
      (into {} (keep (fn [x] (when-some [d (get docs x)] [x d]))) xids))))

(defn- fake-feed []
  (->FakeFeed (atom {:docs {} :discoverable [] :down false :pulls 0})))

(defn- seed! [feed xid title day]
  (swap! (:state feed) update :docs assoc xid
         [{:title title :day day} (str "etag-" title "-" day)]))

(defn- feed-item [adapter]
  (r/resource
   (mirror/declaration
    {:kind :p8_feed_item
     :plural "p8_feed_items"
     :summary "{data.title} · {state}"
     :schema [:map
              [:title {:optional true} [:maybe [:string {:max 120}]]]
              [:day {:optional true} [:maybe :waymark/date]]]
     :filterable {:day #{:eq :range}}
     :sortable {:fields [:day] :default "day"}}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

;; ── fixtures: the instant clock ─────────────────────────────────────

(def ^:private todo
  (r/resource
   {:kind :p8_todo
    :plural "p8_todos"
    :states [:waiting :done]
    :initial :waiting
    :terminal #{:done}
    :summary "{data.note} · {state}"
    :schema [:map
             [:note [:string {:min 1 :max 60}]]
             [:due_at :waymark/instant]
             [:overdue {:optional true} [:maybe :boolean]]]
    :filterable {:due_at #{:after} :overdue #{:eq}}
    :derived {:overdue {:over [:due_at :now]
                        :expr '(< (var :due_at) (var :now))}}
    :actions {:finish {:from #{:waiting} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── 1. cross-resource guard reads (C1) ──────────────────────────────

(deftest ctx-read-in-guards
  (fresh!)
  (with-eng [author book] {}
    (fn [eng]
      (let [{arow :row} (inv/create! eng :p8_author {:name "Ursula"}
                                     {:principal priya})
            {brow :row} (inv/create! eng :p8_book {:title "Left Hand"}
                                     {:principal priya})]
        (testing "a guard reads the target through (:read ctx), same tx"
          (let [res (inv/invoke! eng :p8_book (:id brow) :assign_author
                                 {:author_id (:id arow)} {:principal priya})]
            (is (= (:id arow) (get-in res [:row :data :author_id])))))
        (testing "a dangling ref is refused by the reading guard"
          (let [p (problem-of #(inv/invoke! eng :p8_book (:id brow)
                                            :assign_author
                                            {:author_id "nope"}
                                            {:principal priya}))]
            (is (= 409 (:status p)))
            (is (= :author-is-active (:guard p)))
            (is (= "That author is not active." (:detail p)))))
        (testing "a retired target is refused the same way"
          (inv/invoke! eng :p8_author (:id arow) :retire nil {:principal priya})
          (let [{b2 :row} (inv/create! eng :p8_book {:title "Dispossessed"}
                                       {:principal priya})
                p (problem-of #(inv/invoke! eng :p8_book (:id b2)
                                            :assign_author
                                            {:author_id (:id arow)}
                                            {:principal priya}))]
            (is (= 409 (:status p)))))))))

;; ── 2. ref labels (C2) ──────────────────────────────────────────────

(deftest ref-labels-on-the-root
  (fresh!)
  (with-eng [author book] {}
    (fn [eng]
      (let [{a1 :row} (inv/create! eng :p8_author {:name "Ursula"}
                                   {:principal priya})
            {a2 :row} (inv/create! eng :p8_author {:name "Octavia"}
                                   {:principal priya})
            {brow :row} (inv/create! eng :p8_book {:title "Left Hand"}
                                     {:principal priya})]
        (testing "the write that sets the ref writes the label"
          (let [res (inv/invoke! eng :p8_book (:id brow) :assign_author
                                 {:author_id (:id a1)} {:principal priya})]
            (is (= "Ursula" (get-in res [:row :data :author_name])))))
        (testing "a target rename does NOT refresh the label (recorded scope)"
          (inv/invoke! eng :p8_author (:id a1) :rename {:name "U. K. Le Guin"}
                       {:principal priya})
          (is (= "Ursula" (get-in (decoded eng :p8_book (:id brow))
                                  [:data :author_name]))))
        (testing "re-pointing the ref rewrites the label"
          (let [res (inv/invoke! eng :p8_book (:id brow) :assign_author
                                 {:author_id (:id a2)} {:principal priya})]
            (is (= "Octavia" (get-in res [:row :data :author_name])))))))))

;; ── 3. item labels + one-of clears (C2 + the groups pass) ───────────

(deftest item-labels-and-one-of-clears
  (fresh!)
  (with-eng [author book week] {}
    (fn [eng]
      (let [{brow :row} (inv/create! eng :p8_book {:title "Parable"}
                                     {:principal priya})
            {wrow :row} (inv/create! eng :p8_week
                                     {:start "2026-07-14"
                                      :days [{:date "2026-07-14"}
                                             {:date "2026-07-15"}]}
                                     {:principal priya})
            wid (:id wrow)
            day (fn [res i] (get-in res [:row :data :days i]))]
        (testing "assigning a book labels the item"
          (let [res (inv/invoke! eng :p8_week wid :assign
                                 {:date "2026-07-14" :book_id (:id brow)}
                                 {:principal priya})]
            (is (= (:id brow) (:book_id (day res 0))))
            (is (= "Parable" (:book_title (day res 0))))))
        (testing "marking the day out clears the book arm, label included"
          (let [res (inv/invoke! eng :p8_week wid :mark_out
                                 {:date "2026-07-14"} {:principal priya})]
            (is (true? (:eating_out (day res 0))))
            (is (nil? (:book_id (day res 0))))
            (is (nil? (:book_title (day res 0))))))
        (testing "assigning again clears the eating-out arm back"
          (let [res (inv/invoke! eng :p8_week wid :assign
                                 {:date "2026-07-14" :book_id (:id brow)}
                                 {:principal priya})]
            (is (= "Parable" (:book_title (day res 0))))
            (is (nil? (:eating_out (day res 0))))))
        (testing "the untouched sibling day never moves"
          (is (nil? (:book_id (get-in (decoded eng :p8_week wid)
                                      [:data :days 1])))))))))

;; ── 4. the owns cascade + rollup (C3) ───────────────────────────────

(deftest owns-cascade-and-rollup
  (fresh!)
  (with-eng [project task] {}
    (fn [eng]
      (let [{prow :row} (inv/create! eng :p8_project {:title "garden"}
                                     {:principal priya})
            pid (:id prow)
            t1 (:row (inv/create! eng :p8_task {:title "weed" :project_id pid}
                                  {:principal priya}))
            t2 (:row (inv/create! eng :p8_task {:title "water" :project_id pid}
                                  {:principal priya}))
            t3 (:row (inv/create! eng :p8_task {:title "prune" :project_id pid}
                                  {:principal priya}))]
        ;; one task already done — the cascade must not touch it
        (inv/invoke! eng :p8_task (:id t3) :complete nil {:principal priya})
        (is (= 2 (get-in (decoded eng :p8_project pid) [:data :open_tasks])))
        (let [res (inv/invoke! eng :p8_project pid :abandon nil
                               {:principal priya :correlation-id "story-7"})]
          (testing "abandon cancels every open task"
            (is (= :cancelled (:state (reload eng :p8_task (:id t1)))))
            (is (= :cancelled (:state (reload eng :p8_task (:id t2)))))
            (is (= :done (:state (reload eng :p8_task (:id t3))))
                "a finished task is not re-judged"))
          (testing "the child log names the system actor and the parent's correlation id"
            (let [[latest] (transitions-of eng :p8_task (:id t1))
                  rec (last (transitions-of eng :p8_task (:id t1)))]
              (is (some? latest))
              (is (= :cancel (:action rec)))
              (is (= "waymark-cascade" (get-in rec [:actor :id])))
              (is (= "system" (get-in rec [:actor :type])))
              (is (= "story-7" (:correlation-id rec)))))
          (testing "the parent's rollup count tells the post-cascade truth"
            (is (= 0 (get-in res [:row :data :open_tasks])))
            (is (= 0 (get-in (decoded eng :p8_project pid)
                             [:data :open_tasks])))))))))

;; ── 5. shape upcasts (C5) ───────────────────────────────────────────

(deftest shape-upcasts-at-the-load-boundary
  (fresh!)
  (with-eng [note] {}
    (fn [eng]
      ;; a shape-1 row, stored by a previous era's code
      (store/with-tx (:storage eng)
        (fn [tx]
          (store/insert-row! (:storage eng) tx :p8_note
                             {:id "old-note" :state :open :version 1
                              :data {:title "era one" :tag "vintage"}
                              :shape 1 :owner "priya"})))
      (testing "the load boundary folds the old shape forward"
        (let [row (decoded eng :p8_note "old-note")]
          (is (= ["vintage"] (get-in row [:data :tags])))
          (is (nil? (get-in row [:data :tag])))
          (is (= 2 (:shape row)))))
      (testing "the stored bytes are untouched until a write"
        (is (= 1 (:shape (reload eng :p8_note "old-note")))))
      (testing "the next write stamps the declared shape"
        (inv/invoke! eng :p8_note "old-note" :touch nil
                     {:principal priya :idempotency-key "p8-touch-1"})
        (let [raw (reload eng :p8_note "old-note")]
          (is (= 2 (:shape raw)))
          (is (= ["vintage"] (get-in raw [:data :tags])))
          (is (nil? (get-in raw [:data :tag])))))
      (testing "a born-current row never upcasts"
        (let [{row :row} (inv/create! eng :p8_note
                                      {:title "era two" :tags ["fresh"]}
                                      {:principal priya})]
          (is (= 2 (:shape (reload eng :p8_note (:id row))))))))))

;; ── 6. the mirror (C4) ──────────────────────────────────────────────

(deftest mirror-discovery-and-pull-through
  (fresh!)
  (let [feed (fake-feed)
        clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [(feed-item feed)] {:now-fn (fn [] @clock)}
      (fn [eng]
        (seed! feed "uid-1@2026-07-14" "Recital" "2026-07-14")
        (swap! (:state feed) assoc :discoverable ["uid-1@2026-07-14"])
        (testing "discovery mints the unknown id and pull-many fills it eagerly"
          (is (= 1 (mirror/discover! eng :p8_feed_item)))
          (let [[raw] (store/with-tx (:storage eng)
                        (fn [tx] (store/query-rows (:storage eng) tx
                                                   :p8_feed_item
                                                   {:external_id "uid-1@2026-07-14"}
                                                   {:limit 1})))
                row (inv/decode-row (get (inv/resources eng) :p8_feed_item) raw)]
            (is (= :fresh (:state row)))
            (is (= "Recital" (get-in row [:data :title])))
            (is (some? (get-in row [:data :synced_at])))
            (testing "a second pass re-mints nothing"
              (is (zero? (mirror/discover! eng :p8_feed_item))))
            (let [rdef (get (inv/resources eng) :p8_feed_item)
                  id (:id row)]
              (testing "a fresh row inside its TTL serves without a pull"
                (let [pulls-before (:pulls @(:state feed))
                      served (mirror/refresh! eng rdef
                                              (decoded eng :p8_feed_item id))]
                  (is (= pulls-before (:pulls @(:state feed))))
                  (is (= "Recital" (get-in served [:data :title])))))
              (testing "past the TTL an unchanged etag writes nothing"
                (reset! clock (Instant/parse "2026-07-10T14:00:00Z"))
                (let [v (:version (reload eng :p8_feed_item id))
                      served (mirror/refresh! eng rdef
                                              (decoded eng :p8_feed_item id))]
                  (is (= v (:version served)))
                  (is (= v (:version (reload eng :p8_feed_item id))))))
              (testing "a changed document lands as observe_external, system actor"
                (seed! feed "uid-1@2026-07-14" "Recital (moved)" "2026-07-15")
                (let [served (mirror/refresh! eng rdef
                                              (decoded eng :p8_feed_item id))]
                  (is (= "Recital (moved)" (get-in served [:data :title])))
                  (is (= :fresh (:state served)))
                  (let [rec (last (transitions-of eng :p8_feed_item id))]
                    (is (= :observe_external (:action rec)))
                    (is (= "mirror-sync" (get-in rec [:actor :id]))))))
              (testing "a down feed marks unreachable ONCE; stored truth serves"
                (reset! clock (Instant/parse "2026-07-10T17:00:00Z"))
                (swap! (:state feed) assoc :down true)
                (let [served (mirror/refresh! eng rdef
                                              (decoded eng :p8_feed_item id))
                      n (count (transitions-of eng :p8_feed_item id))]
                  (is (= :unreachable (:state served)))
                  (is (= "Recital (moved)" (get-in served [:data :title])))
                  (mirror/refresh! eng rdef (decoded eng :p8_feed_item id))
                  (is (= n (count (transitions-of eng :p8_feed_item id)))
                      "the second failing read writes nothing")))
              (testing "recovery: the next successful pull returns it to fresh"
                (swap! (:state feed) assoc :down false)
                (let [served (mirror/refresh! eng rdef
                                              (decoded eng :p8_feed_item id))]
                  (is (= :fresh (:state served)))))
              (testing "sync actions are concealed from humans, offered to system"
                (let [row (decoded eng :p8_feed_item id)
                      human-env (render/envelope rdef row
                                                 {:principal t/anonymous
                                                  :now @clock})
                      sys-env (render/envelope rdef row
                                               {:principal mirror/system-observer
                                                :now @clock})]
                  (is (not (contains? (get human-env "actions")
                                      "observe_external")))
                  (is (not (contains? (get human-env "unavailable")
                                      "observe_external")))
                  (is (contains? (get sys-env "actions") "mark_stale")))))))))))

;; ── 7. the instant clock (:waymark/instant) ─────────────────────────

(deftest instant-clock-truth
  (fresh!)
  (let [clock (atom (Instant/parse "2026-07-10T12:00:00Z"))]
    (with-eng [todo] {:now-fn (fn [] @clock)}
      (fn [eng]
        (let [{row :row} (inv/create! eng :p8_todo
                                      {:note "thaw the pork"
                                       :due_at "2026-07-11T17:00:00Z"}
                                      {:principal priya})
              id (:id row)]
          (is (false? (get-in row [:data :overdue])))
          (testing "next_flip_at lands on the instant itself"
            (is (= (Instant/parse "2026-07-11T17:00:00Z")
                   (:next-flip-at (reload eng :p8_todo id)))))
          (testing "the sweep flips the fact once the instant passes"
            (reset! clock (Instant/parse "2026-07-11T18:00:00Z"))
            (maint/sweep-clocks! eng)
            (let [raw (reload eng :p8_todo id)]
              (is (true? (get-in raw [:data :overdue])))
              (is (= 1 (:version raw)) "a clock flip is maintenance"))))))))
