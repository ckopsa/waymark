(ns waymark10.walks-test
  "The walk and walk_frame kinds (docs/spec-guided-follow.md § 4): a
  recording holds only what its recorder saw, a sealed walk takes no
  frames, and the retention sweep purges the frames and keeps the row.
  Memory storage and the real engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live :as live]
            [waymark10.server.presence :as presence]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private chore
  "A row a frame can be about."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open]
    :initial :open
    :terminal #{:open}
    :summary "{data.title} · {state}"
    :schema [:map [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- row-of [eng k id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) k)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx k (str id) {})
                 (inv/decode-row rdef))))))

(defn- frames-of [eng walk-id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                               {:limit 100}))))))

(defn- chore! [eng title]
  (:id (:row (inv/create! eng :chore {:title title} {:principal person}))))

(defn- walk!
  ([eng] (walk! eng {}))
  ([eng extra]
   (:row (inv/create! eng :walk
                      (merge {:followed "planner" :title "Filing a ticket"} extra)
                      {:principal person}))))

(defn- sight-of
  "The recorder's visibility: it sees one chore and no whole kind."
  [visible]
  {:row? (fn [_kind id] (= (str id) (str visible)))
   :whole-kind? (fn [_kind] false)})

(defn- refusal [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(deftest a-walk-records-only-what-its-recorder-saw
  (let [eng (fresh-engine)
        seen (chore! eng "Dishes")
        hidden (chore! eng "Laundry")
        w (walk! eng)
        sight (sight-of seen)]
    (is (= "recording" (name (:state w))))
    (is (= "colton" (get-in w [:data :recorder]))
        "the engine stamps the recorder from the principal")
    (is (= 30 (get-in w [:data :retention_days])))
    (testing "a frame about a row the recorder sees is written"
      (is (some? (walks/record-frame! eng (:id w) sight
                                      {:type "move" :body {:self (str "/api/chores/" seen)}}))))
    (testing "a frame about a row the recorder cannot see is not"
      (is (nil? (walks/record-frame! eng (:id w) sight
                                     {:type "move" :body {:self (str "/api/chores/" hidden)}})))
      (is (nil? (walks/record-frame! eng (:id w) sight
                                     {:type "move" :body {:self "/api/chores"}}))
          "nor the collection without whole-kind sight"))
    (testing "headers, grant ids, keys and credentials never reach a frame"
      (let [f (walks/record-frame! eng (:id w) sight
                                   {:type "ui"
                                    :body {:self (str "/api/chores/" seen)
                                           :dialog "rename"
                                           :headers {:authorization "Bearer x"}
                                           :grant_id "g-1"
                                           :ui {:key "k-1" :field "title"}}})]
        (is (= {:self (str "/api/chores/" seen) :dialog "rename" :ui {:field "title"}}
               (get-in f [:data :body])))))
    (testing "a type the stream does not carry is not written"
      (is (nil? (walks/record-frame! eng (:id w) sight {:type "gossip" :body {}}))))
    (is (= 2 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
    (is (= 2 (count (frames-of eng (:id w)))))))

(deftest a-sealed-walk-takes-no-frames
  (let [eng (fresh-engine)
        w (walk! eng)]
    (is (some? (walks/record-frame! eng (:id w) nil {:type "ui" :body {:dialog "open"}})))
    (testing "only the recorder seals it"
      (is (some? (refusal #(inv/invoke! eng :walk (str (:id w)) :seal {}
                                        {:principal other})))))
    (inv/invoke! eng :walk (str (:id w)) :seal {} {:principal person})
    (let [sealed (row-of eng :walk (:id w))]
      (is (= "sealed" (name (:state sealed))))
      (is (some? (get-in sealed [:data :ended_at]))))
    (is (nil? (walks/record-frame! eng (:id w) nil {:type "ui" :body {:dialog "close"}})))
    (is (= 1 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
    (is (= 1 (count (frames-of eng (:id w)))))))

(deftest retention-purges-frames-and-keeps-the-row
  (let [eng (fresh-engine)
        short-lived (walk! eng {:retention_days 1})
        long-lived (walk! eng {:retention_days 30})]
    (doseq [w [short-lived long-lived]]
      (walks/record-frame! eng (:id w) nil {:type "move" :body {}})
      (inv/invoke! eng :walk (str (:id w)) :seal {} {:principal person}))
    (testing "inside its days nothing moves"
      (is (= 0 (walks/sweep! eng))))
    (let [later (.plusSeconds (Instant/now) (* 2 86400))
          eng' (assoc eng :now-fn (constantly later))]
      (is (= 1 (walks/sweep! eng')))
      (let [row (row-of eng :walk (:id short-lived))]
        (is (= "purged" (name (:state row))))
        (is (= 1 (get-in row [:data :frame_count])) "the row keeps its counts")
        (is (= "Filing a ticket" (get-in row [:data :title]))))
      (is (empty? (frames-of eng (:id short-lived))))
      (is (= "sealed" (name (:state (row-of eng :walk (:id long-lived))))))
      (is (= 1 (count (frames-of eng (:id long-lived))))))))

;; ── the export (waymark-walk/1) ─────────────────────────────────────

(defn- clocked-engine
  "An engine whose clock the test moves → [eng tick!]; a tick is a second."
  [opts]
  (let [clock (atom (Instant/now))]
    [(engine/engine (merge {:storage (memory/storage) :resources [chore]
                            :now-fn (fn [] @clock)}
                           opts))
     (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1000))))]))

(defn- vis-of
  "An exporter's visibility: the rows named, no whole kind, and every
  action, argument and field of what it sees."
  [& visible]
  (let [ids (set (map str visible))]
    {:row? (fn [_kind id] (contains? ids (str id)))
     :whole-kind? (fn [_kind] false)
     :action? (fn [_kind _action] true)
     :arg? (fn [_kind _action _arg] true)
     :field? (fn [_kind _field] true)}))

(defn- member!
  "A member row → its id, which is the principal id a frame carries."
  [eng display actor-type]
  (str (:id (:row (inv/create! eng :member
                               {:display display :actor_type actor-type}
                               {:principal walks/engine-actor})))))

(defn- by [pid actor-type]
  {:id pid :type actor-type :display "as recorded"})

(defn- seal! [eng w]
  (inv/invoke! eng :walk (str (:id w)) :seal {} {:principal person}))

(defn- export-of
  "The export, parsed → {:text :header :lines}."
  [eng w vis]
  (let [text (walks/export eng (:id w) vis)
        [header & lines] (mapv wire/read-json (str/split-lines text))]
    {:text text :header header :lines (vec lines)}))

(defn- recorded-walk
  "A sealed walk of five frames by one person and one agent, about a
  chore the narrow exporter sees and one it does not."
  []
  (let [[eng tick!] (clocked-engine {})
        seen (chore! eng "Dishes")
        hidden (chore! eng "Laundry")
        colton (member! eng "Colton" "human")
        planner (member! eng "Planner" "agent")
        w (walk! eng)
        self-of #(str "/api/chores/" %)
        frame! (fn [type body]
                 (tick!)
                 (is (some? (walks/record-frame! eng (:id w) nil
                                                 {:type type :body body}))))]
    (frame! "move" {:principal (by colton "human") :self (self-of seen)
                    :source "ui" :event "move" :grant_id "g-1"})
    (frame! "move" {:principal (by planner "agent") :self (self-of hidden)
                    :event "move"})
    (frame! "transition" {:kind "chore" :self (self-of hidden)
                          :action "create" :to "open"
                          :actor {:type "agent" :id planner :grant "g-9"}
                          :summary "Laundry · Open"})
    (frame! "ui" {:principal (by planner "agent") :self (self-of seen)
                  :event "ui"
                  :headers {:authorization "Bearer x"}
                  :ui {:dialog {:self (self-of seen) :action "rename"}
                       :fields {:title "Dishes, twice"}
                       :focus (str "https://work.example" (self-of hidden))}})
    (frame! "transition" {:kind "chore" :self (self-of seen)
                          :action "rename" :from "open" :to "open"
                          :actor {:type "human" :id colton :grant "g-1"}
                          :summary "Dishes · Open"})
    (seal! eng w)
    {:eng eng :w w :seen seen :hidden hidden :colton colton :planner planner}))

(deftest an-export-carries-no-pid-grant-or-key
  (let [{:keys [eng w hidden colton planner]} (recorded-walk)
        {:keys [text header lines]} (export-of eng w nil)]
    (is (= "waymark-walk/1" (:format header)))
    (is (= "Filing a ticket" (:title header)))
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (str (:recorded header))))
    (is (= ["move" "move" "transition" "ui" "transition"] (mapv :type lines)))
    (is (= ["p1" "a1" "a1" "a1" "p1"] (mapv :who lines))
        "a principal crosses as its cast alias")
    (is (= (str "/api/chores/" hidden) (get-in (nth lines 3) [:ui :focus]))
        "a self is a path, never an origin")
    (doseq [never [colton planner "g-1" "g-9" "Bearer" "work.example"
                   "as recorded" "grant" "actor" "principal" "headers"]]
      (is (not (str/includes? text never)) (str never " crossed")))))

(deftest an-export-re-redacts-under-the-exporting-grant
  (let [{:keys [eng w seen hidden colton]} (recorded-walk)
        whole (export-of eng w nil)
        narrow (export-of eng w (vis-of seen colton))]
    (is (= {:p1 {:display "Colton" :type "human"}
            :a1 {:display "Planner" :type "agent"}}
           (:cast (:header whole))))
    (testing "a frame about a row the exporter cannot see is left out, and t keeps the gap"
      (is (= ["move" "ui" "transition"] (mapv :type (:lines narrow))))
      (is (= (mapv #(:t (nth (:lines whole) %)) [0 3 4])
             (mapv :t (:lines narrow))))
      (is (apply < (mapv :t (:lines whole))))
      (is (not (str/includes? (:text narrow) (str hidden)))))
    (testing "what is left of a frame is what the exporter may read"
      (let [ui (:ui (nth (:lines narrow) 1))]
        (is (nil? (:focus ui)))
        (is (= "Dishes, twice" (get-in ui [:fields :title])))))
    (testing "a cast member whose row the exporter cannot see is someone"
      (is (= {:p1 {:display "Colton" :type "human"}
              :a1 {:display "someone" :type "agent"}}
             (:cast (:header narrow)))))))

(deftest an-unsealed-walk-does-not-export
  (let [eng (fresh-engine)
        w (walk! eng)
        h (engine/handler eng)
        get! (fn [id]
               (h {:request-method :get
                   :uri (str "/api/walks/" id "/export")
                   :headers {"x-waymark-principal" "colton"}}))]
    (walks/record-frame! eng (:id w) nil {:type "move" :body {:self "/api/chores"}})
    (testing "while it records there is nothing to export"
      (is (nil? (walks/export eng (:id w) nil)))
      (is (= 404 (:status (get! (:id w))))))
    (seal! eng w)
    (testing "sealed, the route answers newline-delimited JSON"
      (let [resp (get! (:id w))]
        (is (= 200 (:status resp)))
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (= (walks/export eng (:id w) nil) (:body resp)))
        (is (= 2 (count (str/split-lines (:body resp)))))))
    (testing "a walk nobody recorded answers the same not-found"
      (is (nil? (walks/export eng "no-such-walk" nil)))
      (is (= 404 (:status (get! "no-such-walk")))))))

(deftest the-cast-types-agents-and-humans
  (let [[eng tick!] (clocked-engine {})
        colton (member! eng "Colton" "human")
        planner (member! eng "Planner" "agent")
        c (chore! eng "Dishes")
        w (walk! eng)
        move! (fn [principal]
                (tick!)
                (walks/record-frame! eng (:id w) nil
                                     {:type "move"
                                      :body {:principal principal
                                             :self (str "/api/chores/" c)}}))]
    (move! (by colton "human"))
    (move! (by planner "agent"))
    (move! (by "seat:no-such-seat" "agent"))
    (move! (by "iris" "human"))
    (move! (by planner "agent"))
    (seal! eng w)
    (let [{:keys [header lines]} (export-of eng w nil)]
      (is (= {:p1 {:display "Colton" :type "human"}
              :a1 {:display "Planner" :type "agent"}
              :a2 {:display "someone" :type "agent"}
              :p2 {:display "someone" :type "human"}}
             (:cast header))
          "a member is typed by its row, and a principal no row names by what the frame recorded")
      (is (= ["p1" "a1" "a2" "p2" "a1"] (mapv :who lines))))))

(deftest the-header-names-the-engine
  (let [engine-of (fn [eng]
                    (let [w (walk! eng)]
                      (seal! eng w)
                      (:engine (:header (export-of eng w nil)))))]
    (is (= "demo" (engine-of (first (clocked-engine {:name "demo"})))))
    (let [eng (fresh-engine)]
      (is (string? (:name eng)))
      (is (= (:name eng) (engine-of eng))
          "an engine given no name answers its default"))))

;; ── the recorder: a follower's stream writes the walk ───────────────

(def ^:private errand
  "A row with a door: what a transition and an invitation are about."
  (r/resource
   {:kind :errand
    :plural "errands"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:room {:optional true :x-display {:label "Room"}}
      [:maybe [:string {:max 40}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:room {:optional true :x-display {:label "Room"}}
                       [:maybe [:string {:max 40}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room])))
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Rename" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 2}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}}}))

(def ^:private guide
  "The followed principal: walk!'s `planner`."
  (t/principal {:id "planner" :type :agent :display "Planner"}))

(defn- stream-engine []
  (engine/engine {:storage (memory/storage) :resources [chore errand]}))

(defn- errand! [eng title]
  (:id (:row (inv/create! eng :errand {:title title} {:principal person}))))

(defn- errand-path [id] (str "/api/errands/" id))

(defn- frames-in
  "A walk's frames as recorded, oldest first → [{:t :type :body}]."
  [eng walk-id]
  (let [frdef (get (inv/resources eng) :walk_frame)]
    (->> (frames-of eng walk-id)
         (map #(:data (inv/decode-row frdef %)))
         (map #(-> %
                   (update :type name)
                   (update :body walk/keywordize-keys)))
         (sort-by :t)
         vec)))

(defn- log-of
  "Every committed transition, oldest first: the firehose's own events."
  [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (vec (store/transitions st tx {:since 0} {:limit 500}))))))

(defn- pump
  "live's pump, by hand: take from a source until `pred` matches an
  event or the wait runs out, rendering each event as the stream would
  → the event, or nil."
  [source pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [left (- deadline (System/currentTimeMillis))]
        (when (pos? left)
          (let [evt ((:take source) left)]
            (when-not (or (nil? evt) (keyword? evt))
              ((:frame source) evt)
              (if (pred evt) evt (recur)))))))))

(defn- with-stream
  "An unscoped follower's stream that asked for `followed`'s ui, as
  routes/realtime composes it: the firehose and the presence source,
  each tapped by the one recorder. f gets {:eng :reg :firehose
  :presence}."
  [follower followed f]
  (let [eng (stream-engine)
        d (events/dispatcher eng {:poll-ms 50})
        reg (presence/start! eng {:hb-ms 600000})
        rec (walks/recorder eng follower nil followed)
        sub (events/subscribe d {:classes #{:transition :derivation}})
        firehose (live/tapped
                  {:label :events
                   :take (fn [ms] (events/take-event sub ms))
                   :frame (fn [evt] (events/frame eng evt))
                   :close! (fn [] (events/unsubscribe d sub))}
                  (:event rec))
        presence (live/tapped
                  (live/presence-source
                   reg nil {:ui followed :redact (presence/ui-redactor eng nil)})
                  (:presence rec))]
    (try
      (f {:eng eng :reg reg :firehose firehose :presence presence})
      (finally
        ((:close! presence))
        ((:close! firehose))
        (presence/stop! reg)
        (events/stop! d)))))

(defn- of-pid [event pid]
  #(and (= event (:event %)) (= pid (get-in % [:principal :id]))))

(deftest a-guided-follow-with-a-recording-walk-writes-move-ui-and-transition-frames
  (with-stream person "planner"
    (fn [{:keys [eng reg firehose presence]}]
      (let [a (errand! eng "Dishes")
            b (errand! eng "Laundry")
            w (walk! eng)
            ui {:dialog {:self (errand-path b) :action "rename"}
                :fields {:title "Laundry, twice"}
                :focus (errand-path b)}]
        (presence/report! reg guide (errand-path a))
        (presence/report! reg other (errand-path a))
        (presence/report! reg other (errand-path b))
        (presence/report! reg guide (errand-path b))
        (is (some? (pump presence (of-pid "move" "planner"))))
        (presence/report! reg guide (errand-path b) ui)
        (is (some? (pump presence (of-pid "ui" "planner"))))
        (inv/invoke! eng :errand (str b) :rename {:title "Towels"} {:principal person})
        (inv/invoke! eng :errand (str b) :rename {:title "Laundry, twice"}
                     {:principal guide})
        (is (some? (pump firehose #(= "planner" (str (get-in % [:actor :id]))))))
        (let [frames (frames-in eng (:id w))
              of (fn [type] (filterv #(= type (:type %)) frames))]
          (is (= #{"move" "ui" "transition"} (set (map :type frames))))
          (is (= (count frames)
                 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
          (testing "only the followed principal's frames are written"
            (is (= #{"planner"}
                   (set (map #(get-in % [:body :principal :id])
                             (concat (of "move") (of "ui"))))))
            (is (= ["planner"]
                   (mapv #(str (get-in % [:body :actor :id])) (of "transition")))))
          (testing "each as the stream sent it"
            (is (= (errand-path b) (get-in (first (of "move")) [:body :self])))
            (is (= "rename" (get-in (first (of "ui")) [:body :ui :dialog :action])))
            (is (= "Laundry, twice"
                   (get-in (first (of "ui")) [:body :ui :fields :title])))
            (is (= {:kind "errand" :self (errand-path b) :action "rename"}
                   (select-keys (:body (first (of "transition")))
                                [:kind :self :action])))))
        (testing "closing the stream does not seal the walk"
          ((:close! presence))
          ((:close! firehose))
          (is (= "recording" (name (:state (row-of eng :walk (:id w)))))))))))

(deftest a-sealed-walk-stops-taking-frames
  (with-stream person "planner"
    (fn [{:keys [eng reg presence]}]
      (let [a (errand! eng "Dishes")
            b (errand! eng "Laundry")
            move! (fn [id]
                    (presence/report! reg guide (errand-path id))
                    (is (some? (pump presence (of-pid "move" "planner")))))]
        (presence/report! reg guide (errand-path a))
        (testing "a follow with no walk writes nothing, and the stream goes on"
          (move! b))
        (let [w (walk! eng)]
          (testing "a walk started after the stream opened takes the next frame"
            (move! a)
            (is (= 1 (count (frames-of eng (:id w))))))
          (seal! eng w)
          (testing "sealed, it takes no more"
            (move! b)
            (is (= 1 (count (frames-of eng (:id w)))))
            (is (= 1 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))))))))

(deftest an-invitation-frame-has-the-pinned-body
  (let [eng (stream-engine)
        c (errand! eng "Dishes")
        narrow (walk! eng)
        whole (:row (inv/create! eng :walk
                                 {:followed "planner" :title "The same, seen whole"}
                                 {:principal other}))
        ;; a follower that sees every row and one argument of the step
        title-only (assoc (vis-of)
                          :row? (fn [_kind _id] true)
                          :arg? (fn [_kind _action arg] (= "title" arg)))
        invitation (:row (inv/create! eng :invitation
                                      {:subject "colton"
                                       :self (errand-path c)
                                       :action "rename"
                                       :field "title"
                                       :note "Pick the new title here."
                                       :suggest {:title "Dishes, twice"
                                                 :room "Kitchen"}}
                                      {:principal guide
                                       :grant {:id "grant-planner"
                                               :action? (fn [_kind _action] true)
                                               :row? (fn [_kind _id] true)}}))
        pinned {:id (str (:id invitation))
                :author "planner"
                :subject "colton"
                :self (errand-path c)
                :action "rename"
                :field "title"
                :fields ["title"]
                :note "Pick the new title here."}
        of (fn [w type] (filterv #(= type (:type %)) (frames-in eng (:id w))))
        scoped (walks/recorder eng person title-only "planner")
        unscoped (walks/recorder eng other nil "planner")]
    (doseq [t (log-of eng)]
      ((:event scoped) t)
      ((:event unscoped) t))
    (testing "suggest keeps only the keys the follower's :arg? admits"
      (is (= [(assoc pinned :suggest {:title "Dishes, twice"})]
             (mapv :body (of narrow "invitation")))))
    (testing "an unscoped follower's frame carries it whole"
      (is (= [(assoc pinned :suggest {:title "Dishes, twice" :room "Kitchen"})]
             (mapv :body (of whole "invitation")))))
    (testing "the transition that created it is written beside it"
      (is (= ["invitation"] (mapv #(get-in % [:body :kind]) (of narrow "transition")))))))

(deftest the-export-reads-the-pinned-invitation-body
  (let [[eng tick!] (clocked-engine {:resources [chore errand]})
        c (errand! eng "Dishes")
        colton (member! eng "Colton" "human")
        planner (member! eng "Planner" "agent")
        w (walk! eng)
        frame! (fn [body]
                 (tick!)
                 (is (some? (walks/record-frame! eng (:id w) nil
                                                 {:type "invitation" :body body}))))
        step {:author planner
              :subject colton
              :self (errand-path c)
              :action "rename"
              :field "title"
              :note "Pick the new title here."}
        title-only (fn [vis] (assoc vis :arg? (fn [_kind _action arg] (= "title" arg))))]
    (frame! (assoc step :id "inv-1" :suggest {:title "Dishes, twice" :room "Kitchen"}))
    ;; the shape the export used to guess at: no line
    (frame! {:invitation "/api/invitations/inv-2" :data step})
    (seal! eng w)
    (let [{:keys [text lines]} (export-of eng w nil)]
      (is (= [{:type "invitation"
               :who "a1"
               :subject "p1"
               :self (errand-path c)
               :action "rename"
               :field "title"
               :note "Pick the new title here."
               :suggest {:title "Dishes, twice" :room "Kitchen"}}]
             (mapv #(dissoc % :t) lines)))
      (doseq [never ["inv-1" "inv-2" colton planner]]
        (is (not (str/includes? text never)) (str never " crossed"))))
    (testing "suggest crosses under the exporter's :arg?"
      (is (= [{:title "Dishes, twice"}]
             (mapv :suggest
                   (:lines (export-of eng w (title-only
                                             (vis-of c "inv-1" colton planner))))))))
    (testing "an exporter that cannot see the invitation gets no line"
      (is (empty? (:lines (export-of eng w (vis-of c colton planner))))))))

;; ── the self walk: a person's own screen, nobody following ──────────

(defn- self-walk! [eng]
  (walk! eng {:followed "colton" :title "Colton, alone"}))

(defn- with-registry
  "A stream engine and its presence registry, and no stream of anyone's."
  [f]
  (let [eng (stream-engine)
        reg (presence/start! eng {:hb-ms 600000})]
    (try (f eng reg)
         (finally (presence/stop! reg)))))

(defn- rename! [eng id title who]
  (inv/invoke! eng :errand (str id) :rename {:title title} {:principal who}))

(deftest a-self-walk-records-its-own-beat-and-write-with-no-stream-open
  (with-registry
    (fn [eng reg]
      (let [a (errand! eng "Dishes")
            w (self-walk! eng)
            rec (walks/self-recorder eng person nil)
            ui {:dialog {:self (errand-path a) :action "rename"}
                :fields {:title "Dishes, twice"}}]
        (is (= "colton" (get-in w [:data :recorder]) (get-in w [:data :followed]))
            "the create door admits followed = recorder")
        (presence/report! reg person (errand-path a) ui (:presence rec))
        (walks/record-own! eng person nil (rename! eng a "Towels" person))
        (testing "someone else's write is not in this person's walk"
          (walks/record-own! eng other nil (rename! eng a "Sheets" other)))
        (let [frames (frames-in eng (:id w))
              of (fn [type] (filterv #(= type (:type %)) frames))]
          (is (= ["move" "transition" "ui"] (sort (map :type frames))))
          (is (= 3 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
          (is (= (errand-path a) (get-in (first (of "move")) [:body :self])))
          (is (= "rename" (get-in (first (of "ui")) [:body :ui :dialog :action])))
          (is (= "Dishes, twice"
                 (get-in (first (of "ui")) [:body :ui :fields :title])))
          (is (= {:kind "errand" :self (errand-path a) :action "rename"}
                 (select-keys (:body (first (of "transition")))
                              [:kind :self :action])))
          (is (= "colton"
                 (str (get-in (first (of "transition")) [:body :actor :id])))))
        (testing "sealed, it takes no more"
          (walks/record-own! eng person nil (seal! eng w))
          (presence/report! reg person (errand-path a) ui (:presence rec))
          (walks/record-own! eng person nil (rename! eng a "Dishes" person))
          (is (= 3 (count (frames-of eng (:id w))))))))))

(deftest a-self-walk-holds-no-row-its-person-cannot-see
  (with-registry
    (fn [eng reg]
      (let [seen (errand! eng "Dishes")
            hidden (errand! eng "Laundry")
            w (self-walk! eng)
            sight (vis-of seen)
            rec (walks/self-recorder eng person sight)
            ui {:dialog {:self (errand-path hidden) :action "rename"}
                :fields {:title "Unseen"}}]
        (presence/report! reg person (errand-path hidden) ui (:presence rec))
        (walks/record-own! eng person sight (rename! eng hidden "Towels" person))
        (is (empty? (frames-of eng (:id w)))
            "neither the beat nor the write on the unseen row is written")
        (testing "a dialog on the unseen row crosses as a plain move"
          (presence/report! reg person (errand-path seen) ui (:presence rec))
          (let [frames (frames-in eng (:id w))]
            (is (seq frames))
            (is (= #{"move"} (set (map :type frames))))
            (is (= #{(errand-path seen)}
                   (set (map #(get-in % [:body :self]) frames))))
            (is (not (str/includes? (pr-str frames) "Unseen")))))
        (testing "a write on the row the person sees is written"
          (walks/record-own! eng person sight (rename! eng seen "Plates" person))
          (is (= [(errand-path seen)]
                 (->> (frames-in eng (:id w))
                      (filter #(= "transition" (:type %)))
                      (mapv #(get-in % [:body :self]))))))))))

(deftest a-walk-of-someone-else-still-records-only-from-the-follower-stream
  (with-registry
    (fn [eng reg]
      (let [a (errand! eng "Dishes")
            w (walk! eng)
            ui {:focus (errand-path a)}]
        (doseq [who [person guide]
                :let [rec (walks/self-recorder eng who nil)]]
          (presence/report! reg who (errand-path a) ui (:presence rec))
          (walks/record-own! eng who nil (rename! eng a "Towels" who)))
        (is (empty? (frames-of eng (:id w)))
            "neither the recorder's own beat and write nor the followed principal's")
        (testing "the follower's stream is still what records it"
          ((:presence (walks/recorder eng person nil "planner"))
           {:event "move" :principal {:id "planner"} :self (errand-path a)})
          (is (= 1 (count (frames-of eng (:id w))))))))))

;; ── the frame ceiling (docs/spec-agent-demo-walks.md § 4) ───────────

(deftest a-walk-seals-itself-at-the-frame-ceiling
  (with-redefs [walks/frame-ceiling 3]
    (let [eng (fresh-engine)
          w (walk! eng)
          frame! #(walks/record-frame! eng (:id w) nil {:type "move" :body {}})]
      (is (some? (frame!)))
      (is (some? (frame!)))
      (is (= "recording" (name (:state (row-of eng :walk (:id w)))))
          "under the ceiling the walk still records")
      (testing "the frame that reaches the ceiling is kept, and the engine seals the walk"
        (is (some? (frame!)))
        (let [row (row-of eng :walk (:id w))]
          (is (= "sealed" (name (:state row))))
          (is (some? (get-in row [:data :ended_at])))
          (is (= 3 (get-in row [:data :frame_count])))))
      (testing "it takes no more frames"
        (is (nil? (frame!)))
        (is (= 3 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
        (is (= 3 (count (frames-of eng (:id w))))))
      (testing "the walk's history says why"
        (is (= 1 (count (filter #(str/includes? (pr-str %) "frame ceiling")
                                (log-of eng)))))))))