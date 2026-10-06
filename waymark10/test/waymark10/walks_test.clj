(ns waymark10.walks-test
  "The walk and walk_frame kinds (docs/spec-guided-follow.md § 4): a
  recording holds only what its recorder saw, a sealed walk takes no
  frames, and the retention sweep purges the frames and keeps the row.
  Memory storage and the real engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live :as live]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.server.walkthroughs :as walkthroughs]
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

(deftest the-export-carries-an-invitation's-given-values
  (let [[eng tick!] (clocked-engine {:resources [chore errand]})
        c (errand! eng "Dishes")
        colton (member! eng "Colton" "human")
        planner (member! eng "Planner" "agent")
        w (walk! eng)
        title-only (fn [vis] (assoc vis :arg? (fn [_kind _action arg] (= "title" arg))))]
    (tick!)
    (is (some? (walks/record-frame!
                eng (:id w) nil
                {:type "invitation"
                 :body {:id "inv-1"
                        :author planner
                        :subject colton
                        :self (errand-path c)
                        :action "rename"
                        :field "title"
                        :note "Pick the new title here."
                        :given {:title "Dishes, twice" :room "Kitchen"}}})))
    (seal! eng w)
    (testing "an unscoped exporter reads them whole, beside no suggest"
      (let [[line] (:lines (export-of eng w nil))]
        (is (= {:title "Dishes, twice" :room "Kitchen"} (:given line)))
        (is (not (contains? line :suggest)))))
    (testing "given crosses under the exporter's :arg?, as suggest does"
      (is (= [{:title "Dishes, twice"}]
             (mapv :given
                   (:lines (export-of eng w (title-only
                                             (vis-of c "inv-1" colton planner))))))))))

;; ── a walkthrough is recorded (docs/spec-walkthrough.md § 6) ────────

(def ^:private seeing-all
  "The guard's-eye view of a grant that sees every row and admits every
  door: the planner's, when it writes the steps."
  {:id "grant-planner"
   :action? (fn [_kind _action] true)
   :row? (fn [_kind _id] true)})

(defn- settle!
  "Drain the invitations' and the walkthroughs' consumers until neither
  hears anything."
  [eng]
  (loop [left 20]
    (let [n (+ (consumers/drain-consumer! eng invitations/consumer-name
                                          (invitations/consumer-fn eng))
               (consumers/drain-consumer! eng walkthroughs/consumer-name
                                          (walkthroughs/consumer-fn eng)))]
      (when (and (pos? n) (pos? left))
        (recur (dec left))))))

(defn- led-engine []
  (doto (stream-engine)
    ;; seed both cursors before any write, so every later one is heard
    (settle!)))

(defn- lead!
  "The planner offers Colton three person steps on one errand."
  [eng errand-id]
  (let [step (fn [fields note]
               {:who "person" :self (errand-path errand-id) :action "rename"
                :fields fields :note note})]
    (:row (inv/create! eng :walkthrough
                       {:subject "colton"
                        :title "Naming the errand"
                        :steps [(step ["title" "room"] "Pick the title, then the room.")
                                (step ["room"] "Now the room alone.")
                                (step ["title"] "And the title once more.")]}
                       {:principal guide :grant seeing-all}))))

(defn- move! [eng w principal action input]
  (inv/invoke! eng :walkthrough (str (:id w)) action input {:principal principal}))

(defn- answer! [eng errand-id title]
  (inv/invoke! eng :errand (str errand-id) :rename {:title title :room "Hall"}
               {:principal person}))

(defn- open-invitation
  "The invitation open to Colton now."
  [eng]
  (let [st (:storage eng)]
    (first (store/with-tx st
             (fn [tx]
               (vec (store/query-rows st tx :invitation
                                      {:state :open :subject "colton"}
                                      {:limit 10})))))))

(defn- hearer
  "A recorder's event tap fed from the log by hand → a function that
  hands it every entry it has not heard yet."
  [eng rec]
  (let [seen (atom 0)]
    (fn []
      (let [log (log-of eng)]
        (doseq [t (subvec log @seen)]
          ((:event rec) t))
        (reset! seen (count log))))))

(defn- state-of [eng k row]
  (some-> (row-of eng k (:id row)) :state name))

(deftest a-walkthroughs-invitation-is-recorded-whatever-hand-made-it
  (let [eng (led-engine)
        c (errand! eng "Dishes")
        w (walk! eng)
        watching (:row (inv/create! eng :walk
                                    {:followed "planner" :title "Iris watches"}
                                    {:principal other}))
        lead (lead! eng c)
        of (fn [walk type] (filterv #(= type (:type %)) (frames-in eng (:id walk))))]
    (move! eng lead person :start {:walk (str (:id w))})
    (settle! eng)
    (let [led (walks/recorder eng person nil "planner")
          bystander (walks/recorder eng other nil "planner")]
      (doseq [t (log-of eng)]
        ((:event led) t)
        ((:event bystander) t)))
    (testing "the engine made it, and it is keyed on its author"
      (is (= [{:author "planner"
               :subject "colton"
               :self (errand-path c)
               :action "rename"
               :field "title"
               :fields ["title" "room"]
               :note "Pick the title, then the room."
               :walkthrough (str (:id lead))
               :step 1
               :of 3}]
             (mapv #(select-keys (:body %) [:author :subject :self :action :field
                                            :fields :note :walkthrough :step :of])
                   (of w "invitation")))))
    (testing "the engine's create is no transition of the followed principal"
      (is (= ["walkthrough"] (mapv #(get-in % [:body :kind]) (of w "transition")))))
    (testing "a follower it was not addressed to records no such frame"
      (is (empty? (of watching "invitation"))))))

(deftest the-recorders-own-answer-is-a-frame
  (let [eng (led-engine)
        c (errand! eng "Dishes")
        d (errand! eng "Laundry")
        w (walk! eng)
        lead (lead! eng c)
        hear! (hearer eng (walks/recorder eng person nil "planner"))
        mine (fn []
               (->> (frames-in eng (:id w))
                    (filter #(and (= "transition" (:type %))
                                  (= "colton" (str (get-in % [:body :actor :id])))))
                    (mapv #(vector (get-in % [:body :kind]) (get-in % [:body :action])))))]
    (move! eng lead person :start {:walk (str (:id w))})
    (settle! eng)
    (hear!)
    (testing "a write of the recorder's that answers nothing is not recorded"
      (inv/invoke! eng :errand (str d) :rename {:title "Sheets"} {:principal person})
      (hear!)
      (is (= [] (mine))))
    (testing "the answer, heard while the invitation is still open"
      (answer! eng c "Towels")
      (hear!)
      (is (= [["errand" "rename"]] (mine))))
    (testing "the answer, heard after the engine answered the invitation"
      (settle! eng)
      (answer! eng c "Towels, twice")
      (settle! eng)
      (hear!)
      (is (= [["errand" "rename"] ["errand" "rename"]] (mine))))
    (testing "a skip is the recorder's answer too"
      (inv/invoke! eng :invitation (str (:id (open-invitation eng))) :decline {}
                   {:principal person})
      (hear!)
      (is (= [["errand" "rename"] ["errand" "rename"] ["invitation" "decline"]]
             (mine))))
    (testing "each question was recorded before its answer"
      (is (= [[1 3] [2 3] [3 3]]
             (->> (frames-in eng (:id w))
                  (filter #(= "invitation" (:type %)))
                  (mapv #(vector (get-in % [:body :step]) (get-in % [:body :of])))))))
    (testing "the export carries the step line's numbers"
      (settle! eng)
      (is (= "sealed" (state-of eng :walk w)))
      (is (= [[1 3] [2 3] [3 3]]
             (->> (:lines (export-of eng w nil))
                  (filter #(= "invitation" (:type %)))
                  (mapv (juxt :step :of))))))))

(deftest finish-seals-the-walkthroughs-walk
  (let [eng (led-engine)
        c (errand! eng "Dishes")
        w (walk! eng)
        lead (lead! eng c)]
    (move! eng lead person :start {:walk (str (:id w))})
    (settle! eng)
    (is (= (str (:id w))
           (str (get-in (row-of eng :walkthrough (:id lead)) [:data :walk]))))
    (testing "a stop does not seal it"
      (move! eng lead person :stop {})
      (settle! eng)
      (is (= "recording" (state-of eng :walk w)))
      (move! eng lead person :resume {})
      (settle! eng))
    (doseq [title ["Towels" "Towels, twice" "Towels, thrice"]]
      (answer! eng c title)
      (settle! eng))
    (is (= "finished" (state-of eng :walkthrough lead)))
    (is (= "sealed" (state-of eng :walk w)))
    (testing "a withdraw seals it too"
      (let [again (walk! eng)
            lead (lead! eng c)]
        (move! eng lead person :start {:walk (str (:id again))})
        (settle! eng)
        (move! eng lead guide :withdraw {})
        (settle! eng)
        (is (= "withdrawn" (state-of eng :walkthrough lead)))
        (is (= "sealed" (state-of eng :walk again)))))))

(deftest start-refuses-a-walk-that-is-not-the-subjects-own
  (let [eng (led-engine)
        c (errand! eng "Dishes")
        lead (lead! eng c)
        mine (walk! eng)
        theirs (:row (inv/create! eng :walk
                                  {:followed "planner" :title "Iris's own"}
                                  {:principal other}))
        of-another (walk! eng {:followed "iris"})
        sealed (walk! eng)]
    (seal! eng sealed)
    (doseq [[why walk] [["another person's walk" theirs]
                        ["a walk that follows someone else" of-another]
                        ["a walk that is not recording" sealed]]]
      (is (some? (refusal #(move! eng lead person :start {:walk (str (:id walk))})))
          why))
    (is (= "open" (state-of eng :walkthrough lead)))
    (is (nil? (refusal #(move! eng lead person :start {:walk (str (:id mine))}))))
    (is (= "running" (state-of eng :walkthrough lead)))))

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

(deftest a-self-walk-records-no-move-to-a-row-its-tab-did-not-leave
  (with-registry
    (fn [eng reg]
      (let [a (errand! eng "Dishes")
            b (errand! eng "Laundry")
            w (self-walk! eng)
            rec (walks/self-recorder eng person nil)
            beat! #(presence/report! reg person (errand-path %) nil (:presence rec))
            moves (fn [] (->> (frames-in eng (:id w))
                              (filter #(= "move" (:type %)))
                              (map #(get-in % [:body :self]))
                              frequencies))]
        (beat! a)
        (is (= {(errand-path a) 1} (moves)))
        (testing "a stream on another row moved the entry"
          (presence/stream-open! reg person (errand-path b))
          (beat! a)
          (is (= {(errand-path a) 1} (moves))))
        (testing "a read of another row moved the entry"
          (presence/read! reg person (errand-path b))
          (beat! a)
          (is (= {(errand-path a) 1} (moves))))
        (testing "the tab's own beat on another row is a move"
          (beat! b)
          (is (= {(errand-path a) 1 (errand-path b) 1} (moves))))))))

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

(deftest a-narrowed-recorder-who-may-create-keeps-their-create-form
  (with-registry
    (fn [eng reg]
      (let [seen (errand! eng "Dishes")
            w (self-walk! eng)
            verb (name (first (:create-action-names (get (inv/resources eng) :errand))))
            may (assoc (vis-of seen)
                       :action? (fn [kind action]
                                  (and (= :errand (keyword (name kind)))
                                       (= verb (name action)))))
            may-not (assoc (vis-of seen) :action? (fn [_kind _action] false))
            ui {:dialog {:self "/api/errands" :action verb}
                :fields {:title "Towels"}}
            beat! #(presence/report! reg person "/api/errands" ui
                                     (:presence (walks/self-recorder eng person %)))
            no! #(walks/record-refused! eng person %
                                        {:self "/api/errands" :action verb}
                                        (p/guard-refused (keyword verb) :open
                                                         "No more errands today."
                                                         {:guard :full} nil))
            of (fn [type frames] (filterv #(= type (:type %)) frames))]
        (testing "some rows and no create door: the collection is not theirs"
          (beat! may-not)
          (is (empty? (no! may-not)))
          (is (empty? (frames-of eng (:id w)))))
        (testing "some rows and the create door: the form and its refusal"
          (beat! may)
          (is (= 1 (count (no! may))))
          (let [frames (frames-in eng (:id w))
                [form] (of "ui" frames)
                [refusal] (of "refusal" frames)]
            (is (= verb (get-in form [:body :ui :dialog :action])))
            (is (= "Towels" (get-in form [:body :ui :fields :title])))
            (is (= {:self "/api/errands" :action verb}
                   (select-keys (:body refusal) [:self :action])))))
        (testing "the export gives them back to the recorder alone"
          (seal! eng w)
          (let [lines (fn [text] (mapv wire/read-json (rest (str/split-lines text))))
                own (lines (walks/export eng (:id w) may person))
                types (fn [ls] (set (map :type ls)))]
            (is (= #{"ui" "refusal"} (types own)))
            (is (= #{"/api/errands"} (set (map :self own))))
            (is (empty? (lines (walks/export eng (:id w) may other))))
            (is (empty? (lines (walks/export eng (:id w) may))))))))))

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

;; ── captions (docs/spec-agent-demo-walks.md § 3) ────────────────────

(def ^:private locker
  "A row whose door takes one open argument and one secret one."
  (r/resource
   {:kind :locker
    :plural "lockers"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:assign {:from #{:open} :to :open
              :input [:map
                      [:assignee {:x-display {:label "Assignee"}}
                       [:string {:max 80}]]
                      [:pin {:optional true :x-secret true
                             :x-display {:label "Pin"}}
                       [:maybe [:string {:max 12}]]]]
              :handler (fn [row _inp _ctx] row)
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Assign" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 2}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}}}))

(defn- with-captions
  "An engine whose clock steps a millisecond on every read, so each
  frame has its own `t`, and its presence registry → (f eng reg)."
  [f]
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [chore errand]
              :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})
        reg (presence/start! eng {:hb-ms 600000})]
    (try (f eng reg)
         (finally (presence/stop! reg)))))

(deftest a-caption-is-recorded-before-its-step
  (with-captions
    (fn [eng reg]
      (let [a (errand! eng "Dishes")
            w (self-walk! eng)
            rec (walks/self-recorder eng person nil)
            c {:self (errand-path a) :action "rename" :field "title"
               :text "Colton renames the errand before he finishes it."}]
        (is (nil? (walks/caption-problem eng c)))
        (is (= 1 (count (walks/caption! eng person nil c))))
        (presence/report! reg person (errand-path a)
                          {:dialog {:self (errand-path a) :action "rename"}
                           :fields {:title "Towels"}}
                          (:presence rec))
        (walks/record-own! eng person nil (rename! eng a "Towels" person))
        (let [[caption & step] (frames-in eng (:id w))]
          (is (= "caption" (:type caption)))
          (is (= c (select-keys (:body caption) [:self :action :field :text])))
          (is (= "colton" (get-in caption [:body :principal :id])))
          (is (= #{"move" "ui" "transition"} (set (map :type step))))
          (is (every? #(< (long (:t caption)) (long (:t %))) step)
              "the line is read, and then the act is seen"))
        (testing "someone who records nothing writes none"
          (is (empty? (walks/caption! eng other nil c)))
          (is (= 4 (count (frames-of eng (:id w))))))
        (testing "sealed, the walk takes no caption"
          (seal! eng w)
          (is (empty? (walks/caption! eng person nil c)))
          (is (= 4 (count (frames-of eng (:id w))))))))))

(deftest a-caption-on-a-row-the-recorder-cannot-see-is-not-written
  (with-captions
    (fn [eng _reg]
      (let [seen (errand! eng "Dishes")
            hidden (errand! eng "Laundry")
            w (self-walk! eng)
            sight (vis-of seen)
            say! #(walks/caption! eng person sight {:self %1 :text %2})]
        (is (empty? (say! (errand-path hidden) "Unseen")))
        (is (empty? (say! "/api/errands" "The whole list"))
            "sight of one row is not sight of the collection")
        (is (empty? (say! "/api/-/events" "No kind at all")))
        (is (empty? (frames-of eng (:id w))))
        (is (= 1 (count (say! (errand-path seen) "Seen"))))
        (is (= ["Seen"]
               (mapv #(get-in % [:body :text]) (frames-in eng (:id w)))))))))

(deftest a-caption-field-must-be-an-open-argument
  (let [eng (engine/engine {:storage (memory/storage) :resources [locker]})
        why (fn [c] (walks/caption-problem eng (merge {:text "A line."} c)))
        row "/api/lockers/l-1"]
    (is (nil? (why {:self row :action "assign" :field "assignee"})))
    (is (nil? (why {:self "/api/lockers" :action "assign" :field "assignee"}))
        "a create's form is on the collection, and the door is the same")
    (is (str/includes? (str (why {:self row :action "assign" :field "colour"}))
                       "is not an argument of `assign`"))
    (is (str/includes? (str (why {:self row :action "assign" :field "pin"}))
                       "secret argument"))
    (is (some? (why {:self row :field "assignee"}))
        "a field with no action names nothing")
    (testing "the line itself: one line of at most 140 characters"
      (is (nil? (why {:self row :text (apply str (repeat 140 "a"))})))
      (is (some? (why {:self row :text (apply str (repeat 141 "a"))})))
      (is (some? (why {:self row :text "two\nlines"})))
      (is (some? (why {:self row :text 7})))
      (is (nil? (why {:self row :text ""}))))))

(deftest an-empty-caption-clears
  (with-captions
    (fn [eng _reg]
      (let [a (errand! eng "Dishes")
            w (self-walk! eng)
            say! #(walks/caption! eng person nil {:self (errand-path a) :text %})]
        (is (nil? (walks/caption-problem eng {:self (errand-path a) :text ""})))
        (is (= 1 (count (say! "Colton looks at the errand."))))
        (is (= 1 (count (say! ""))) "the empty line is a frame of its own")
        (is (= ["Colton looks at the errand." ""]
               (mapv #(get-in % [:body :text]) (frames-in eng (:id w)))))
        (seal! eng w)
        (is (= [["caption" "Colton looks at the errand."] ["caption" ""]]
               (mapv (juxt :type :text) (:lines (export-of eng w nil))))
            "and it crosses the export, so replay clears the line")))))

(deftest a-caption-crosses-an-export-only-with-its-self
  (with-captions
    (fn [eng _reg]
      (let [seen (errand! eng "Dishes")
            hidden (errand! eng "Laundry")
            w (self-walk! eng)
            say! #(walks/caption! eng person nil %)]
        (say! {:self (errand-path seen) :action "rename" :field "title"
               :text "On the row the exporter sees."})
        (say! {:self (errand-path hidden) :text "On the row it does not."})
        (say! {:self "/api/errands" :text "On the whole list."})
        (seal! eng w)
        (let [whole (export-of eng w nil)
              narrow (export-of eng w (vis-of seen))
              captions (fn [e] (filterv #(= "caption" (:type %)) (:lines e)))
              [line] (captions narrow)]
          (is (= 3 (count (captions whole))))
          (is (= ["On the row the exporter sees."] (mapv :text (captions narrow))))
          (is (= {:self (errand-path seen) :action "rename" :field "title"}
                 (select-keys line [:self :action :field])))
          (is (re-matches #"[ap]1" (str (:who line)))
              "the one who said it crosses as a cast alias")
          (is (nil? (:principal line)))
          (is (not (str/includes? (:text narrow) "it does not")))
          (is (not (str/includes? (:text narrow) "whole list"))))))))

;; ── a refused write (docs/spec-agent-demo-walks.md § 2) ─────────────

(defn- refused
  "A guard's refusal of `action`, as a write door throws it."
  [action]
  (p/guard-refused action :open "The dishes are not dry."
                   {:guard :dry :remedies [:errand/rename :chore/hide]} nil))

(deftest a-refused-write-is-recorded-in-its-own-walk
  (with-captions
    (fn [eng _reg]
      (let [a (errand! eng "Dishes")
            w (self-walk! eng)
            door {:self (errand-path a) :action "finish"}
            no! #(walks/record-refused! eng %1 %2 door %3)]
        (is (= 1 (count (no! person nil (refused :finish)))))
        (let [[frame] (frames-in eng (:id w))]
          (is (= "refusal" (:type frame)))
          (is (= {:self (errand-path a) :action "finish" :title "Refused"
                  :detail "The dishes are not dry."
                  :remedies ["errand.rename" "chore.hide"]}
                 (select-keys (:body frame) [:self :action :title :detail :remedies])))
          (is (= "colton" (get-in frame [:body :principal :id])))
          (is (nil? (get-in frame [:body :guard]))
              "the box's own words, and no more of the problem"))
        (testing "an error that is no refusal writes none"
          (is (empty? (no! person nil (ex-info "boom" {})))))
        (testing "someone who records nothing writes none"
          (is (empty? (no! other nil (refused :finish)))))
        (testing "a row the recorder cannot see writes none"
          (is (empty? (no! person (vis-of) (refused :finish)))))
        (is (= 1 (count (frames-of eng (:id w)))))))))

(deftest a-refusal-crosses-an-export-with-the-doors-the-exporter-sees
  (with-captions
    (fn [eng _reg]
      (let [seen (errand! eng "Dishes")
            hidden (errand! eng "Laundry")
            w (self-walk! eng)
            no! #(walks/record-refused! eng person nil
                                        {:self (errand-path %) :action "finish"}
                                        (refused :finish))]
        (no! seen)
        (no! hidden)
        (seal! eng w)
        (let [refusals (fn [e] (filterv #(= "refusal" (:type %)) (:lines e)))
              narrow (assoc (vis-of seen)
                            :action? (fn [kind _action] (= :errand kind)))
              [line & more] (refusals (export-of eng w narrow))]
          (is (= 2 (count (refusals (export-of eng w nil)))))
          (is (nil? more) "the refusal on the unseen row stays behind")
          (is (= {:self (errand-path seen) :action "finish" :title "Refused"
                  :detail "The dishes are not dry." :remedies ["errand.rename"]}
                 (select-keys line [:self :action :title :detail :remedies])))
          (is (re-matches #"[ap]1" (str (:who line)))
              "the one refused crosses as a cast alias")
          (is (nil? (:principal line))))))))

(deftest a-schema-refusal-is-recorded-with-its-open-field-errors
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [locker]
              :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})
        id (:id (:row (inv/create! eng :locker {:title "Locker"} {:principal person})))
        self (str "/api/lockers/" id)
        w (self-walk! eng)
        invalid (p/schema-invalid :assign {:assignee ["should be a string"]
                                           :pin ["should be at most 12 characters"]})
        no! #(walks/record-refused! eng person nil {:self self :action %} invalid)
        errors-of #(some-> (:errors %) (update-keys name))
        open {"assignee" ["should be a string"]}]
    (is (= 1 (count (no! "assign"))))
    (no! "polish")
    (let [[known unknown] (mapv :body (frames-in eng (:id w)))]
      (is (= "Input failed validation" (:title known)))
      (is (= open (errors-of known))
          "a secret argument's sentence is dropped, as clean-ui drops its value")
      (is (nil? (:errors unknown))
          "a door the kind does not have says nothing of its arguments"))
    (seal! eng w)
    (testing "the export keeps the entries whose argument the exporter's :arg? admits"
      (let [exported (fn [vis]
                       (->> (:lines (export-of eng w vis))
                            (filter #(= "refusal" (:type %)))
                            first
                            errors-of))
            narrow (assoc (vis-of id) :arg? (fn [_kind _action arg] (= "title" arg)))]
        (is (= open (exported nil)))
        (is (= open (exported (vis-of id))))
        (is (nil? (exported narrow)))))))

(def ^:private crate
  "A row whose door takes a map with one secret child, and a list of maps."
  (r/resource
   {:kind :crate
    :plural "crates"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:stock {:from #{:open} :to :open
             :input [:map
                     [:shelf {:x-display {:label "Shelf"}}
                      [:map
                       [:label {:x-display {:label "Label"}}
                        [:string {:max 20}]]
                       [:code {:optional true :x-secret true
                               :x-display {:label "Code"}}
                        [:maybe [:string {:max 12}]]]]]
                     [:items {:optional true :x-display {:label "Items"}}
                      [:vector
                       [:map
                        [:name {:x-display {:label "Name"}}
                         [:string {:max 20}]]]]]]
             :handler (fn [row _inp _ctx] row)
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Stock" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 2}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}}}))

(deftest a-schema-refusal-is-recorded-with-a-nested-arguments-field-errors
  (let [clock (atom (Instant/now))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [crate]
              :now-fn (fn [] (swap! clock (fn [^Instant i] (.plusMillis i 1))))})
        id (:id (:row (inv/create! eng :crate {:title "Crate"} {:principal person})))
        self (str "/api/crates/" id)
        w (self-walk! eng)
        invalid (p/schema-invalid
                 :stock {:shelf {:label ["should be at most 20 characters"]
                                 :code ["should be at most 12 characters"]}
                         :items [nil {:name ["should be a string"]}]})
        errors-of #(some-> (:errors %) (update-keys name))
        shelf {"shelf.label" ["should be at most 20 characters"]}
        open (assoc shelf "items[1].name" ["should be a string"])]
    (is (= 1 (count (walks/record-refused! eng person nil
                                           {:self self :action "stock"} invalid))))
    (is (= open (errors-of (:body (first (frames-in eng (:id w))))))
        "each sentence is held by its slot's path, and the secret child's is dropped")
    (seal! eng w)
    (testing "the export judges a nested entry by its top-level argument"
      (let [exported (fn [vis]
                       (->> (:lines (export-of eng w vis))
                            (filter #(= "refusal" (:type %)))
                            first
                            errors-of))
            only (fn [admitted]
                   (assoc (vis-of id) :arg? (fn [_kind _action arg] (= admitted arg))))]
        (is (= open (exported nil)))
        (is (= open (exported (vis-of id))))
        (is (= shelf (exported (only "shelf"))))
        (is (nil? (exported (only "title"))))))))

;; ── the screens a walk carries (docs/spec-agent-demo-walks.md § 8a) ──

(defn- doc-walk! [eng]
  (walk! eng {:followed "colton" :title "A walk with its screens" :docs true}))

(defn- look!
  "The person's own beat lands on `self`: the recorder's tap takes the move."
  [rec self]
  ((:presence rec) {:event "move" :principal {:id "colton"} :self self}))

(defn- docs-in [eng walk-id]
  (filterv #(= "doc" (:type %)) (frames-in eng walk-id)))

(defn- types-in [eng walk-id]
  (frequencies (map :type (frames-in eng walk-id))))

(defn- room! [eng id room]
  (inv/invoke! eng :errand (str id) :rename {:title "Dishes" :room room}
               {:principal person}))

(deftest a-doc-follows-a-move-and-a-transition
  (let [eng (stream-engine)
        a (errand! eng "Dishes")
        plain (self-walk! eng)
        w (doc-walk! eng)
        rec (walks/self-recorder eng person nil)]
    (look! rec (errand-path a))
    (walks/record-own! eng person nil (rename! eng a "Towels" person))
    (look! rec "/api/errands")
    (let [docs (docs-in eng (:id w))
          of (fn [self] (filterv #(= self (get-in % [:body :self])) docs))]
      (is (= {"move" 2 "transition" 1 "doc" 3} (types-in eng (:id w))))
      (is (= 6 (get-in (row-of eng :walk (:id w)) [:data :frame_count])))
      (testing "a move to a row and a transition are each followed by the row's envelope"
        (is (= #{"Dishes" "Towels"}
               (set (map #(get-in % [:body :doc :data :title]) (of (errand-path a))))))
        (is (every? #(= "errand" (get-in % [:body :doc :kind])) (of (errand-path a))))
        (is (some? (get-in (first (of (errand-path a)))
                           [:body :doc :actions :rename :input]))
            "the envelope holds the door's input schema, so a dialog needs no frame"))
      (testing "a move to a collection is followed by its page"
        (let [[page] (of "/api/errands")]
          (is (= "errand_collection" (get-in page [:body :doc :kind])))
          (is (= [(errand-path a)]
                 (mapv :self (get-in page [:body :doc :data :items]))))))
      (testing "a walk made without docs records none"
        (is (= {"move" 2 "transition" 1} (types-in eng (:id plain))))))))

(deftest a-doc-holds-only-what-the-recorder-saw
  (let [eng (stream-engine)
        seen (errand! eng "Dishes")
        hidden (errand! eng "Laundry")
        _ (room! eng seen "Kitchen")
        w (doc-walk! eng)
        sight (assoc (vis-of seen)
                     :field? (fn [_kind field] (not= "room" (name field))))
        rec (walks/self-recorder eng person sight)]
    (look! rec (errand-path seen))
    (look! rec (errand-path hidden))
    (look! rec "/api/errands")
    (let [frames (frames-in eng (:id w))
          [doc :as docs] (docs-in eng (:id w))]
      (is (= {"move" 1 "doc" 1} (types-in eng (:id w)))
          "the unseen row and the collection it cannot read whole leave nothing")
      (is (= 1 (count docs)))
      (is (= (errand-path seen) (get-in doc [:body :self])))
      (is (= "Dishes" (get-in doc [:body :doc :data :title])))
      (is (not (contains? (get-in doc [:body :doc :data]) :room))
          "the document is rendered under the recorder's sight")
      (doseq [never ["Kitchen" "Laundry" (str hidden)]]
        (is (not (str/includes? (pr-str frames) never)) (str never " was recorded"))))))

(deftest an-unchanged-doc-is-not-recorded-twice
  (let [eng (stream-engine)
        a (errand! eng "Dishes")
        w (doc-walk! eng)
        rec (walks/self-recorder eng person nil)
        titles #(set (map (fn [f] (get-in f [:body :doc :data :title]))
                          (docs-in eng (:id w))))]
    (look! rec (errand-path a))
    (look! rec (errand-path a))
    (is (= {"move" 2 "doc" 1} (types-in eng (:id w)))
        "the same screen, byte for byte, is one document")
    (walks/record-own! eng person nil (rename! eng a "Towels" person))
    (look! rec (errand-path a))
    (is (= {"move" 3 "transition" 1 "doc" 2} (types-in eng (:id w)))
        "the changed row is recorded once more, and not again")
    (is (= #{"Dishes" "Towels"} (titles)))))

(deftest a-doc-over-the-cap-is-left-out
  (let [eng (stream-engine)
        a (errand! eng "Dishes")
        w (doc-walk! eng)
        rec (walks/self-recorder eng person nil)]
    (is (= [(* 64 1024) (* 8 1024 1024)] [walks/doc-cap walks/docs-cap]))
    (testing "a document over the cap is not recorded, and its move still is"
      (with-redefs [walks/doc-cap 64]
        (look! rec (errand-path a)))
      (is (= {"move" 1} (types-in eng (:id w)))))
    (testing "a walk's documents stop at the total"
      (look! rec (errand-path a))
      (let [[doc] (docs-in eng (:id w))
            held (long (get-in doc [:body :bytes]))]
        (is (pos? held))
        (with-redefs [walks/docs-cap (+ held 16)]
          (walks/record-own! eng person nil (rename! eng a "Towels" person)))
        (is (= {"move" 2 "transition" 1 "doc" 1} (types-in eng (:id w))))))))

(deftest an-exported-doc-is-redacted-under-the-exporter
  (let [eng (stream-engine)
        seen (errand! eng "Dishes")
        hidden (errand! eng "Laundry")
        _ (room! eng seen "Kitchen")
        w (doc-walk! eng)
        rec (walks/self-recorder eng person nil)
        selves [(errand-path seen) (errand-path hidden) "/api/errands"]
        docs-of (fn [export] (filterv #(= "doc" (:type %)) (:lines export)))]
    (doseq [self selves] (look! rec self))
    (seal! eng w)
    (let [whole (docs-of (export-of eng w nil))
          vis (assoc (vis-of seen)
                     :field? (fn [_kind field] (not= "room" (name field)))
                     :action? (fn [_kind action] (not= "finish" (name action))))
          narrow (export-of eng w vis)
          [doc :as crossed] (docs-of narrow)]
      (testing "an unscoped exporter reads each document whole"
        (is (= (set selves) (set (map :self whole))))
        (is (= "Kitchen"
               (get-in (first (filter #(= (errand-path seen) (:self %)) whole))
                       [:doc :data :room]))))
      (testing "the row must pass the exporter's :row?"
        (is (= [(errand-path seen)] (mapv :self crossed))))
      (testing "data keeps the keys :field? admits"
        (is (= "Dishes" (get-in doc [:doc :data :title])))
        (is (not (contains? (get-in doc [:doc :data]) :room))))
      (testing "actions keeps the entries :action? admits"
        (is (contains? (get-in doc [:doc :actions]) :rename))
        (is (not (contains? (get-in doc [:doc :actions]) :finish))))
      (is (nil? (:who doc)) "a document is nobody's act")
      (doseq [never ["Kitchen" "Laundry" (str hidden)]]
        (is (not (str/includes? (:text narrow) never)) (str never " crossed"))))))

(deftest an-exported-doc-names-principals-by-alias
  (let [eng (stream-engine)
        about (walk! eng)
        w (doc-walk! eng)]
    (is (some? (walks/record-doc! eng (:id w) person nil
                                  (str "/api/walks/" (:id about)) nil)))
    (seal! eng w)
    (let [{:keys [text header lines]} (export-of eng w nil)
          data (get-in (first lines) [:doc :data])
          cast (set (map name (keys (:cast header))))]
      (is (= ["doc"] (mapv :type lines)))
      (is (= "Filing a ticket" (:title data)))
      (is (contains? cast (:recorder data))
          "the recorder of the row on screen crosses as a cast alias")
      (is (contains? cast (:followed data)))
      (is (not= (:recorder data) (:followed data)))
      (doseq [never ["colton" "planner"]]
        (is (not (str/includes? text never)) (str never " crossed"))))))
