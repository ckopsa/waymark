(ns waymark10.mcp-staging-test
  "The connector stages its calls (docs/spec-agent-demo-walks.md § 2):
  while the caller is recording a self walk, a get moves the gaze to the
  row, a query reports its collection, and an invoke opens the form,
  types each argument, writes, and closes. A rehearsal and a refusal
  leave the form open, a secret argument is never typed, and with no
  recording walk there is no beat at all.

  Memory storage, the real handler through POST /api/-/mcp, a
  suite-local kind, and a presence registry in the engine's runtime: no
  database, no network. The engine's clock steps a millisecond on every
  read, so each frame has its own `t` and the order asserted here is
  the order recorded."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.presence :as presence]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the suite-local kind ────────────────────────────────────────────

(def ^:private ready-gate
  (g/expr {:name :ready
           :when '(= (data :ready) true)
           :explain "This errand is not ready."}))

(r/defhandler rename-handler [row inp _ctx]
  (update row :data merge (select-keys inp [:title :room])))

(r/defhandler assign-handler [row _inp _ctx]
  row)

(def ^:private errand
  "A row with five doors: two plain arguments, one secret argument, a
  ref argument and a list of them, an argument of its own named
  `caption`, and a guard that refuses."
  (r/resource
   {:kind :errand
    :plural "errands"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:room {:optional true} [:maybe [:string {:max 40}]]]
             [:ready {:optional true} [:maybe :boolean]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title [:string {:min 1 :max 80}]]
                      [:room {:optional true} [:maybe [:string {:max 40}]]]]
              :handler rename-handler
              :safety {:idempotent true :reversible true :confirm false}}
     :assign {:from #{:open} :to :open
              :input [:map
                      [:assignee [:string {:max 400}]]
                      [:pin {:optional true :x-secret true}
                       [:maybe [:string {:max 12}]]]]
              :handler assign-handler
              :safety {:idempotent true :reversible true :confirm false}}
     :hand {:from #{:open} :to :open
            :input [:map
                    [:crew_id {:kind :crew} :waymark/ref]
                    [:helpers {:optional true :kind :crew} [:vector :waymark/ref]]
                    [:note {:optional true} [:maybe [:string {:max 80}]]]]
            :handler assign-handler
            :safety {:idempotent true :reversible true :confirm false}}
     :label {:from #{:open} :to :open
             :input [:map [:caption [:string {:max 80}]]]
             :handler assign-handler
             :safety {:idempotent true :reversible true :confirm false}}
     :complete {:from #{:open} :to :done
                :guards [ready-gate]
                :bulk true
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}}}}))

(def ^:private crew
  "The kind an errand's ref arguments name."
  (r/resource
   {:kind :crew
    :plural "crews"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name}"
    :schema [:map [:name [:string {:min 1 :max 80}]]]
    :actions
    {:retire {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Gone is gone."}}}}))

;; ── the door ────────────────────────────────────────────────────────

(def ^:private headers {"x-waymark-principal" "colton"})

(defn- with-stage
  "An engine, its handler and a presence registry running in the
  engine's runtime, where the connector looks for it → (f eng h reg)."
  [f]
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [errand crew]
              :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})
        reg (presence/start! eng {:hb-ms 600000})]
    (swap! (:runtime eng) assoc :presence reg)
    (try (f eng (engine/handler eng) reg)
         (finally (presence/stop! reg)))))

(defn- json [resp] (wire/read-json (:body resp)))

(defn- post [h uri body]
  (h {:request-method :post :uri uri :headers headers
      :body (wire/write-json body)}))

(defn- tool
  "One tools/call → true when the tool answered, false when it refused."
  [h tool-name args]
  (let [resp (post h "/api/-/mcp" {:jsonrpc "2.0" :id 1 :method "tools/call"
                                   :params {:name tool-name :arguments args}})]
    (is (= 200 (:status resp)) (:body resp))
    (not (:isError (:result (json resp))))))

(defn- errand! [h data]
  (let [resp (post h "/api/errands" (merge {:title "Dishes"} data))]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(defn- crew! [h crew-name]
  (let [resp (post h "/api/crews" {:name crew-name})]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(defn- path [id] (str "/api/errands/" id))

(defn- self-walk!
  "A recording walk of colton's own, made at the HTTP door → its id."
  [h]
  (let [resp (post h "/api/walks" {:followed "colton" :title "My own walk"})]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(defn- frames
  "A walk's frames as recorded, oldest first → [{:t :type :body}]."
  [eng walk-id]
  (let [st (:storage eng)
        frdef (get (inv/resources eng) :walk_frame)]
    (->> (store/with-tx st
           (fn [tx]
             (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                                    {:limit 100}))))
         (map #(:data (inv/decode-row frdef %)))
         (map #(-> %
                   (update :type name)
                   (update :body walk/keywordize-keys)))
         (sort-by :t)
         vec)))

(defn- beat
  "One frame as the tests read it: where the gaze went, which form was
  open with which values, or which action was written."
  [{:keys [type body]}]
  (case type
    "move" [:move (:self body)]
    "ui" [:ui (get-in body [:ui :dialog :action]) (or (get-in body [:ui :fields]) {})]
    "transition" [:transition (:action body)]
    [type]))

(defn- beats [eng walk-id] (mapv beat (frames eng walk-id)))

;; ── 1. the read tools ───────────────────────────────────────────────

(deftest a-get-moves-the-gaze-to-the-row
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_get" {:kind "errand" :id a}))
        (is (= [[:move (path a)]] (beats eng w)))
        (testing "the same row again: the gaze did not change"
          (is (tool h "waymark_get" {:kind "errand" :id a}))
          (is (= [[:move (path a)]] (beats eng w))))
        (testing "a row that is not there moves nothing"
          (is (not (tool h "waymark_get" {:kind "errand" :id "no-such-row"})))
          (is (= [[:move (path a)]] (beats eng w))))))))

(deftest a-get-of-the-walk-itself-makes-no-frame
  (with-stage
    (fn [eng h _reg]
      (let [w (self-walk! h)]
        (is (tool h "waymark_get" {:kind "walk" :id w}))
        (is (= [] (beats eng w)))))))

;; A grant-scoped GET is its caller's gaze already (`presence/read!`),
;; and that door has no tap: the get's own beat still writes the move.

(def ^:private mayor
  {"x-waymark-principal" "mayor" "x-waymark-actor-type" "agent"})

(defn- under-a-grant
  "`mayor`'s headers under a grant colton gave over every errand, and
  over these of its actions."
  [eng & [actions]]
  (let [gid (get-in (inv/create! eng :grant
                                 {:audience "mayor"
                                  :scope [{:kind "errand" :actions (vec actions)}]}
                                 {:principal (t/principal {:id "colton"})})
                    [:row :id])]
    (inv/invoke! eng :grant gid :accept nil
                 {:principal (t/principal {:id "mayor" :type :agent})})
    (assoc mayor "x-waymark-grant" (str gid))))

(defn- post-as [h headers uri body]
  (h {:request-method :post :uri uri :headers headers
      :body (wire/write-json body)}))

(defn- tool-as
  "`tool`, with the caller's own headers."
  [h headers tool-name args]
  (let [resp (post-as h headers "/api/-/mcp"
                      {:jsonrpc "2.0" :id 1 :method "tools/call"
                       :params {:name tool-name :arguments args}})]
    (is (= 200 (:status resp)) (:body resp))
    (not (:isError (:result (json resp))))))

(deftest a-scoped-get-writes-its-move-and-its-doc
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            b (errand! h {:title "Laundry"})
            scoped (under-a-grant eng)
            ;; an agent with no grant is served no walks, so the walk
            ;; is made at the engine's own door
            w (str (get-in (inv/create! eng :walk
                                        {:followed "mayor" :title "The mayor's walk"
                                         :docs true}
                                        {:principal (t/principal {:id "mayor" :type :agent})})
                           [:row :id]))
            shown (fn []
                    (mapv (fn [{:keys [type body]}] [(keyword type) (:self body)])
                          (frames eng w)))]
        (is (tool-as h scoped "waymark_get" {:kind "errand" :id a}))
        (is (= [[:move (path a)] [:doc (path a)]] (shown))
            "the read marked the gaze first, and the move is still written")
        (testing "a second row: a second move, and its screen"
          (is (tool-as h scoped "waymark_get" {:kind "errand" :id b}))
          (is (= [[:move (path a)] [:doc (path a)]
                  [:move (path b)] [:doc (path b)]]
                 (shown))))
        (testing "the same row again: the gaze did not change"
          (is (tool-as h scoped "waymark_get" {:kind "errand" :id b}))
          (is (= 4 (count (frames eng w)))))))))

(deftest a-query-reports-its-collection
  (with-stage
    (fn [eng h _reg]
      (let [_ (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_query" {:kind "errand" :filter {:state "open"}
                                     :page_number 1}))
        (let [[move ui :as recorded] (frames eng w)]
          (is (= ["move" "ui"] (mapv :type recorded)))
          (is (= "/api/errands" (get-in move [:body :self])))
          (is (= {:self "/api/errands" :filter {:state "open"} :page 1}
                 (get-in ui [:body :ui :collection]))))
        (testing "a query for totals alone shows nothing"
          (is (tool h "waymark_query" {:kind "errand" :rows "none"}))
          (is (= 2 (count (frames eng w)))))))))

;; ── 2. an invoke ────────────────────────────────────────────────────

(deftest an-invoke-types-each-argument-then-writes-then-closes
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        ;; the arguments arrive room first; the schema says title first
        (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                      :input {:room "Kitchen" :title "Towels"}}))
        (is (= [[:move (path a)]
                [:ui "rename" {}]
                [:ui "rename" {:title "Towels"}]
                [:ui "rename" {:title "Towels" :room "Kitchen"}]
                [:transition "rename"]
                [:ui nil {}]]
               (beats eng w)))))))

(deftest each-typing-beat-names-its-argument-in-focus
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                      :input {:room "Kitchen" :title "Towels"}}))
        (is (= [nil "title" "room" nil]
               (->> (frames eng w)
                    (filter #(= "ui" (:type %)))
                    (mapv #(get-in % [:body :ui :focus]))))
            "the opening beat and the closing beat type nothing")))))

(deftest a-ref-argument-is-typed-with-its-rows-summary-line
  ;; ticket 097e60da: a replay fetches no collection, so the beat itself
  ;; says what a live picker would show for the id
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            ada (crew! h "Ada")
            grace (crew! h "Grace")
            w (self-walk! h)
            both {:crew_id "Ada" :helpers ["Grace" "Ada"]}]
        (is (tool h "waymark_invoke" {:kind "errand" :id a :action "hand"
                                      :input {:note "soon" :helpers [grace ada]
                                              :crew_id ada}}))
        (is (= [nil {:crew_id "Ada"} both both nil]
               (->> (frames eng w)
                    (filter #(= "ui" (:type %)))
                    (mapv #(get-in % [:body :ui :labels]))))
            "each typing beat labels the refs it shows; a plain argument has none")))))

(deftest a-ref-the-recorder-may-not-see-has-no-label
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            ada (crew! h "Ada")
            ;; errands and their `hand`, and no crew
            scoped (under-a-grant eng ["hand"])
            w (str (get-in (inv/create! eng :walk
                                        {:followed "mayor" :title "The mayor's walk"}
                                        {:principal (t/principal {:id "mayor" :type :agent})})
                           [:row :id]))
            _ (tool-as h scoped "waymark_invoke" {:kind "errand" :id a :action "hand"
                                                  :input {:crew_id ada}})
            typed (filter #(= ada (get-in % [:body :ui :fields :crew_id]))
                          (frames eng w))]
        (is (seq typed) "the argument is typed all the same")
        (is (every? #(nil? (get-in % [:body :ui :labels])) typed))))))

(deftest a-dry-run-leaves-the-dialog-open-and-the-invoke-does-not-retype
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)
            call {:kind "errand" :id a :action "rename" :input {:title "Towels"}}
            typed [[:move (path a)]
                   [:ui "rename" {}]
                   [:ui "rename" {:title "Towels"}]]]
        (is (tool h "waymark_invoke" (assoc call :dry_run true)))
        (is (= typed (beats eng w))
            "the rehearsal types the form and leaves it open")
        (is (tool h "waymark_invoke" call))
        (is (= (conj typed [:transition "rename"] [:ui nil {}]) (beats eng w))
            "the invoke writes and closes, and types nothing again")))))

(deftest a-refused-invoke-leaves-the-dialog-open
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (not (tool h "waymark_invoke" {:kind "errand" :id a :action "complete"}))
            "the guard refuses: this errand is not ready")
        (is (= [[:move (path a)]
                [:ui "complete" {}]
                ["refusal"]]
               (beats eng w))
            "no write and no closing beat: the refusal is the form's last frame")))))

(deftest a-secret-argument-is-never-typed
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_invoke" {:kind "errand" :id a :action "assign"
                                      :input {:assignee "marco" :pin "zq-pin-zq"}}))
        (is (= [[:move (path a)]
                [:ui "assign" {}]
                [:ui "assign" {:assignee "marco"}]
                [:transition "assign"]
                [:ui nil {}]]
               (beats eng w)))
        (is (not (str/includes?
                  (pr-str (filter #(= "ui" (:type %)) (frames eng w)))
                  "zq-pin-zq")))))))

(deftest a-create-types-its-form-on-the-collection
  (with-stage
    (fn [eng h _reg]
      (let [w (self-walk! h)
            call {:kind "errand" :action "create"
                  ;; the arguments arrive room first; the schema says title first
                  :input {:room "Kitchen" :title "Towels"}}
            typed [[:move "/api/errands"]
                   [:ui "create" {}]
                   [:ui "create" {:title "Towels"}]
                   [:ui "create" {:title "Towels" :room "Kitchen"}]]]
        (is (tool h "waymark_invoke" (assoc call :dry_run true)))
        (is (= typed (beats eng w))
            "the rehearsal types the form and leaves it open")
        (is (= #{"/api/errands"}
               (into #{} (keep #(get-in % [:body :ui :dialog :self]))
                     (frames eng w)))
            "the form is on the collection")
        (is (tool h "waymark_invoke" call))
        (is (= (conj typed [:transition "create"] [:ui nil {}]) (beats eng w))
            "the create writes and closes, and types nothing again")
        (testing "an action the kind does not create with opens no form"
          (is (not (tool h "waymark_invoke" {:kind "errand" :action "rename"
                                             :input {:title "Mop"}})))
          (is (= 6 (count (frames eng w)))))))))

(defn- refusals
  "The doors a walk's `refusal` frames are about → [{:self :action}]."
  [eng walk-id]
  (->> (frames eng walk-id)
       (filter #(= "refusal" (:type %)))
       (mapv #(select-keys (:body %) [:self :action]))))

(deftest a-refused-create-leaves-its-form-open-with-the-refusal
  (with-stage
    (fn [eng h _reg]
      (let [w (self-walk! h)]
        (is (not (tool h "waymark_invoke"
                       {:kind "errand" :action "create"
                        :input {:title "Towels"
                                :room (apply str (repeat 41 "k"))}}))
            "the schema refuses: a room is at most 40 characters")
        (is (= [{:self "/api/errands" :action "create"}] (refusals eng w))
            "one refusal, keyed as the create form is")
        (is (= ["refusal"] (last (beats eng w)))
            "no closing beat: the refusal is the form's last frame")
        (is (= #{["/api/errands" "create"]}
               (into #{} (keep #(some-> (get-in % [:body :ui :dialog])
                                        ((juxt :self :action))))
                     (frames eng w)))
            "the form it answers is the one on the collection")
        (testing "a rehearsal's refusal records none"
          (is (not (tool h "waymark_invoke"
                         {:kind "errand" :action "create" :dry_run true
                          :input {:title "Towels"
                                  :room (apply str (repeat 41 "k"))}})))
          (is (= 1 (count (refusals eng w)))))))))

(deftest a-bulk-write-records-each-row-it-refused
  (with-stage
    (fn [eng h _reg]
      (let [ready (errand! h {:ready true})
            not-ready (errand! h {})
            w (self-walk! h)]
        (post h "/api/errands/-/complete" {:ids [ready not-ready]})
        (is (= [{:self (path not-ready) :action "complete"}] (refusals eng w))
            "the row that landed has no refusal, the row refused has one")
        (testing "an atomic call names the row that refused it"
          (is (= 409 (:status (post h "/api/errands/-/complete"
                                    {:ids [not-ready] :on_error "atomic"}))))
          (is (= 2 (count (refusals eng w)))))))))

;; ── 3. when nothing is staged ───────────────────────────────────────

(defn- every-staged-call [h a]
  (is (tool h "waymark_get" {:kind "errand" :id a}))
  (is (tool h "waymark_query" {:kind "errand"}))
  (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                :input {:title "Towels"}})))

(deftest no-recording-walk-means-no-beat
  (with-stage
    (fn [eng h reg]
      (let [a (errand! h {})]
        (testing "nobody is recording"
          (every-staged-call h a)
          (is (empty? @(:local reg)) "no presence was reported"))
        (testing "a sealed walk is not a recording one"
          (let [w (self-walk! h)
                sealed (post h (str "/api/walks/" w "/-/seal") {})]
            (is (= 200 (:status sealed)) (:body sealed))
            (every-staged-call h a)
            (is (empty? @(:local reg)))
            (is (empty? (frames eng w)))))))))

(deftest discover-and-schema-make-no-beat
  (with-stage
    (fn [eng h reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_discover" {}))
        (is (tool h "waymark_schema" {:kind "errand"}))
        (is (tool h "waymark_history" {:kind "errand" :id a}))
        (is (empty? @(:local reg)) "no presence was reported")
        (is (empty? (frames eng w)))))))

;; ── 4. captions (docs/spec-agent-demo-walks.md § 3) ─────────────────

(deftest a-caption-is-written-before-the-calls-beats
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            line "The agent renames the errand."]
        (testing "with no recording walk the arguments do nothing"
          (is (tool h "waymark_invoke" {:kind "errand" :id a :action "assign"
                                        :input {:assignee "marco"}
                                        :caption line :caption_field "pin"})))
        (let [w (self-walk! h)]
          (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                        :input {:title "Towels"}
                                        :caption line :caption_field "title"}))
          (is (= [["caption"]
                  [:move (path a)]
                  [:ui "rename" {}]
                  [:ui "rename" {:title "Towels"}]
                  [:transition "rename"]
                  [:ui nil {}]]
                 (beats eng w)))
          (is (= {:self (path a) :action "rename" :field "title" :text line}
                 (select-keys (:body (first (frames eng w)))
                              [:self :action :field :text])))
          (testing "a secret argument is no anchor, and the call is not made"
            (is (not (tool h "waymark_invoke" {:kind "errand" :id a :action "assign"
                                               :input {:assignee "marco"}
                                               :caption line :caption_field "pin"})))
            (is (= 6 (count (frames eng w)))))
          (testing "a get and a query take one, and the empty one is a frame too"
            (is (tool h "waymark_get" {:kind "errand" :id a :caption "The row."}))
            (is (tool h "waymark_query" {:kind "errand" :caption ""}))
            (is (= [["The row." (path a)] ["" "/api/errands"]]
                   (->> (frames eng w)
                        (drop 6)
                        (filter #(= "caption" (:type %)))
                        (mapv (juxt #(get-in % [:body :text])
                                    #(get-in % [:body :self]))))))))))))

(deftest a-caption-inside-input-is-the-calls-own
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            line "The agent renames the errand."
            w (self-walk! h)
            captions (fn [] (->> (frames eng w)
                                 (filter #(= "caption" (:type %)))
                                 (mapv #(get-in % [:body :text]))))]
        (testing "it writes the caption frame and does not reach the door"
          (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                        :input {:title "Towels" :caption line
                                                :caption_field "title"}}))
          (is (= [["caption"]
                  [:move (path a)]
                  [:ui "rename" {}]
                  [:ui "rename" {:title "Towels"}]
                  [:transition "rename"]
                  [:ui nil {}]]
                 (beats eng w)))
          (is (= {:self (path a) :action "rename" :field "title" :text line}
                 (select-keys (:body (first (frames eng w)))
                              [:self :action :field :text]))))
        (testing "the argument beside input wins"
          (is (tool h "waymark_invoke" {:kind "errand" :id a :action "rename"
                                        :input {:title "Sheets" :caption "Inside."}
                                        :caption "Beside."}))
          (is (= [line "Beside."] (captions))))
        (testing "a door's own `caption` argument stays the door's"
          (is (tool h "waymark_invoke" {:kind "errand" :id a :action "label"
                                        :input {:caption "Linen"}}))
          (is (= [line "Beside."] (captions)))
          (is (= [:ui "label" {:caption "Linen"}]
                 (last (filter #(= [:ui "label"] (vec (take 2 %)))
                               (beats eng w))))))))))

;; ── the quest's sheet ───────────────────────────────────────────────

;; a cross-kind read, so the goal's door is shut with its remedy bound
;; to the crate's own latch (quests_test.clj's fixture chain)
(g/defguard the-staged-latch-is-up
  {:reads [:s_latch]
   :explain "Lift the latch first."
   :remedies [{:door :s_latch/lift :id '(data :latch_id)}]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [latch (read :s_latch (get-in row [:data :latch_id]))]
      (if (= "up" (some-> latch :state name)) (t/allow) (t/deny)))
    (t/allow)))

(def ^:private routine {:idempotent true :reversible true :confirm false})

(def ^:private latch
  (r/resource
   {:kind :s_latch
    :plural "s_latches"
    :states [:down :up]
    :initial :down
    :summary "Latch · {state}"
    :schema [:map [:label {:optional true} [:maybe [:string {:max 40}]]]]
    :actions
    {:lift {:from #{:down} :to :up :safety routine}
     :lower {:from #{:up} :to :down :safety routine}}}))

(def ^:private crate
  (r/resource
   {:kind :s_crate
    :plural "s_crates"
    :states [:shut :open]
    :initial :shut
    :summary "Crate · {state}"
    :schema [:map
             [:latch_id {:not-a-ref "staging fixture: the-staged-latch-is-up binds its remedy to it"}
              [:string {:max 80}]]]
    :actions
    {:open {:from #{:shut} :to :open
            :guards [the-staged-latch-is-up]
            :safety routine
            :display {:label "Open the crate" :order 1}}
     :close {:from #{:open} :to :shut :safety routine}}}))

(defn- with-crates
  "`with-stage`, over the fixture chain a quest can plan."
  [f]
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [latch crate]
              :probe-reads true
              :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})
        reg (presence/start! eng {:hb-ms 600000})
        h (engine/handler eng)]
    (swap! (:runtime eng) assoc :presence reg)
    ;; the owner is made a member, as the identity boundary makes anybody who calls
    (h {:request-method :get :uri "/api/s_crates" :headers headers})
    (try (f eng h reg)
         (finally (presence/stop! reg)))))

(defn- made! [h uri data]
  (let [resp (post h uri data)]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(deftest a-rehearsed-quest-create-opens-the-sheet-and-the-create-accepts-it
  (with-crates
    (fn [eng h _reg]
      (let [l (made! h "/api/s_latches" {})
            crate! #(str "/api/s_crates/" (made! h "/api/s_crates" {:latch_id l}))
            self (crate!)
            goal {:self self :action "open"}
            w (self-walk! h)
            shown (fn [fs] (filterv #(#{"move" "ui" "transition"} (:type %)) fs))
            quest #(get-in % [:body :ui :quest])]
        (testing "the rehearsal is the tap: the gaze goes to the goal's row and the sheet opens"
          (is (tool h "waymark_invoke" {:kind "quest" :action "create" :dry_run true
                                        :input goal}))
          (let [fs (shown (frames eng w))
                q (quest (last fs))]
            (is (= ["move" "ui"] (mapv :type fs)) (pr-str fs))
            (is (= self (get-in (first fs) [:body :self])))
            (is (= goal (:goal q)) (pr-str q))
            (is (= "Open the crate" (:label q)))
            (is (true? (get-in q [:seen :ok])))
            (is (= ["lift" "open"]
                   (mapv :door (get-in q [:seen :body :preview :plan]))))
            (is (some? (not-empty (get-in q [:seen :body :preview :shut_reason]))))
            (is (every? #(nil? (get-in % [:body :ui :dialog])) fs)
                "no create form is opened or typed")))
        (testing "the create that follows is Accept: the write, and the sheet closes"
          (is (tool h "waymark_invoke" {:kind "quest" :action "create" :input goal}))
          (let [fs (subvec (shown (frames eng w)) 2)]
            (is (= [[:transition "create"] [:ui nil {}]] (mapv beat fs)) (pr-str fs))
            (is (nil? (quest (last fs))))))
        (testing "any other staged call closes an open sheet first"
          (let [other (crate!)
                n (count (shown (frames eng w)))]
            (is (tool h "waymark_invoke" {:kind "quest" :action "create" :dry_run true
                                          :input {:self other :action "open"}}))
            (is (tool h "waymark_get" {:kind "s_latch" :id l}))
            (let [fs (subvec (shown (frames eng w)) n)]
              (is (= ["move" "ui" "ui" "move"] (mapv :type fs)) (pr-str fs))
              (is (= other (get-in (quest (nth fs 1)) [:goal :self])))
              (is (nil? (quest (nth fs 2))))
              (is (= (str "/api/s_latches/" l) (get-in (nth fs 3) [:body :self]))))))))))
