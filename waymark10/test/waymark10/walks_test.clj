(ns waymark10.walks-test
  "The walk and walk_frame kinds (docs/spec-guided-follow.md § 4): a
  recording holds only what its recorder saw, a sealed walk takes no
  frames, and the retention sweep purges the frames and keeps the row.
  Memory storage and the real engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
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