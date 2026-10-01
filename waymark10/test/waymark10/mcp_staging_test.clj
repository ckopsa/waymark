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
  "A row with three doors: two plain arguments, one secret argument,
  and a guard that refuses."
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
     :complete {:from #{:open} :to :done
                :guards [ready-gate]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}}}}))

;; ── the door ────────────────────────────────────────────────────────

(def ^:private headers {"x-waymark-principal" "colton"})

(defn- with-stage
  "An engine, its handler and a presence registry running in the
  engine's runtime, where the connector looks for it → (f eng h reg)."
  [f]
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [errand]
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
                [:ui "complete" {}]]
               (beats eng w))
            "no write and no closing beat")))))

(deftest a-secret-argument-is-never-typed
  (with-stage
    (fn [eng h _reg]
      (let [a (errand! h {})
            w (self-walk! h)]
        (is (tool h "waymark_invoke" {:kind "errand" :id a :action "assign"
                                      :input {:assignee "marco" :pin "4321"}}))
        (is (= [[:move (path a)]
                [:ui "assign" {}]
                [:ui "assign" {:assignee "marco"}]
                [:transition "assign"]
                [:ui nil {}]]
               (beats eng w)))
        (is (not (str/includes?
                  (pr-str (filter #(= "ui" (:type %)) (frames eng w)))
                  "4321")))))))

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
