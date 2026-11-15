(ns waymark10.watches-test
  "The watch kind (ticket 14cbd2f4, docs/spec-watch.md): the held read,
  dismiss and its self-clear, and the seeded default watch."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.watches :as watches]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private watch-task
  ;; a draft rests; an open task waits on the member its `owner` names
  (r/resource
   {:kind :watch_task
    :plural "watch_tasks"
    :states [:draft :open :closed]
    :initial :draft
    :terminal #{:closed}
    :expected-age {:open 3600}
    :holder-field {:open :owner}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]
             [:owner {:optional true :x-ref {:principal true}} [:maybe :string]]]
    :actions {:groom {:from #{:draft} :to :open :safety quiet}
              :close {:from #{:open} :to :closed :safety quiet}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private dana (t/principal {:id "dana" :display "Dana"}))

(defn- eng! []
  (engine/engine {:storage (memory/storage) :resources [watch-task]}))

(defn- task! [eng title owner]
  (str (get-in (inv/create! eng :watch_task (cond-> {:title title} owner (assoc :owner owner))
                            {:principal elena})
               [:row :id])))

(defn- watch! [eng who data]
  (str (get-in (inv/create! eng :watch data {:principal who}) [:row :id])))

(defn- move! [eng k id action input who]
  (inv/invoke! eng k id action input {:principal who}))

(defn- ids [doc] (into #{} (map :id) (:rows doc)))

(def ^:private drafts
  {:name "My drafts" :cues [{:kind "watch_task" :actions [] :states ["draft" "open"]}]})

(deftest a-held-watch-answers-what-its-cues-match
  (let [eng (eng!)
        d1 (task! eng "one" nil)
        d2 (task! eng "two" nil)
        _ (move! eng :watch_task d2 :groom {} elena)]
    (testing "no watch holds nothing"
      (is (= #{} (ids (watches/held-read eng nil elena)))))
    (let [w (watch! eng elena {:name "Drafts"
                               :cues [{:kind "watch_task" :actions [] :states ["draft"]}]})]
      (testing "a cue naming a state holds the rows in it"
        (let [doc (watches/held-read eng nil elena)]
          (is (= #{d1} (ids doc)))
          (is (= w (get-in doc [:rows 0 :watch :id])))))
      (testing "another member's watch is not mine"
        (is (= #{} (ids (watches/held-read eng nil dana)))))
      (testing "dropping it removes them"
        (move! eng :watch w :drop {} elena)
        (is (= #{} (ids (watches/held-read eng nil elena))))))))

(deftest a-cue-with-no-states-holds-what-waits-on-me
  (let [eng (eng!)
        mine (task! eng "mine" "elena")
        theirs (task! eng "theirs" "dana")]
    (move! eng :watch_task mine :groom {} elena)
    (move! eng :watch_task theirs :groom {} elena)
    (watch! eng elena {:name "Mine" :cues [{:kind "watch_task" :actions []
                                            :note "{data.title} waits"}]})
    (let [doc (watches/held-read eng nil elena)]
      (is (= #{mine} (ids doc)))
      (is (= "mine waits" (get-in doc [:rows 0 :note]))))))

(deftest only-the-member-or-the-mayor-writes-a-watch
  (let [eng (eng!)]
    (is (thrown? Exception (watch! eng dana (assoc drafts :for "elena"))))
    (let [w (watch! eng elena drafts)]
      (is (thrown? Exception (move! eng :watch w :drop {} dana))))))

(deftest dismiss-sets-a-row-aside-until-it-moves
  (let [eng (eng!)
        d1 (task! eng "one" nil)
        w (watch! eng elena drafts)
        mark {:row_id d1 :row_kind "watch_task"}]
    (testing "dismiss hides the row and lists it apart"
      (move! eng :watch w :dismiss mark elena)
      (let [doc (watches/held-read eng nil elena)]
        (is (= #{} (ids doc)))
        (is (= 1 (get-in doc [:dismissed :count])))
        (is (= [d1] (mapv :id (get-in doc [:dismissed :rows]))))))
    (testing "undismiss brings it back"
      (move! eng :watch w :undismiss mark elena)
      (is (= #{d1} (ids (watches/held-read eng nil elena)))))
    (testing "a state change of the row brings it back"
      (move! eng :watch w :dismiss mark elena)
      (is (= #{} (ids (watches/held-read eng nil elena))))
      (move! eng :watch_task d1 :groom {} elena)
      (let [doc (watches/held-read eng nil elena)]
        (is (= #{d1} (ids doc)))
        (is (= 0 (get-in doc [:dismissed :count])))))
    (testing "a required watch refuses dismiss"
      (let [req (watch! eng elena (assoc drafts :required true))]
        (is (thrown? Exception (move! eng :watch req :dismiss mark elena)))))))

(deftest every-member-is-seeded-one-default-watch
  (let [eng (eng!)
        mine (task! eng "mine" "elena")]
    (move! eng :watch_task mine :groom {} elena)
    (let [row (watches/seed! eng "elena")]
      (testing "it is a held row named for what it holds"
        (is (some? row))
        (is (= "Waiting on you" (get-in row [:data :name])))
        (is (= "hold" (str (get-in row [:data :mode]))))
        (is (= #{"quest" "held_call" "approval_request" "question" "*"}
               (into #{} (map (comp str :kind)) (get-in row [:data :cues])))))
      (testing "it is seeded once, and not again after a drop"
        (is (nil? (watches/seed! eng "elena")))
        (move! eng :watch (str (:id row)) :drop {} elena)
        (is (nil? (watches/seed! eng "elena")))))
    (testing "it answers the rows in-flight holder=me does"
      (let [eng (eng!)
            mine (task! eng "mine" "elena")
            _ (task! eng "theirs" "dana")
            _ (move! eng :watch_task mine :groom {} elena)
            _ (watches/seed! eng "elena")
            flight (into #{} (comp (mapcat :rows) (map :id))
                         (:groups (in-flight/read-all eng nil {:holder "member:elena"})))]
        (is (contains? flight mine))
        (is (= flight (ids (watches/held-read eng nil elena))))))))

(deftest the-held-read-on-the-wire
  (let [eng (eng!)
        h (engine/handler eng)
        d1 (task! eng "one" nil)
        _ (watch! eng elena drafts)
        get-held (fn [headers]
                   (let [resp (h {:request-method :get :uri "/api/-/watches/held"
                                  :headers headers})]
                     (assoc resp :parsed (some-> (:body resp) wire/read-json))))]
    (let [{:keys [status parsed]} (get-held {"x-waymark-principal" "elena"})]
      (is (= 200 status))
      (is (= #{d1} (ids parsed))))
    (is (= 404 (:status (get-held {}))))))
