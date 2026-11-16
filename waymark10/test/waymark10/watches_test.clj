(ns waymark10.watches-test
  "The watch kind (ticket 14cbd2f4, docs/spec-watch.md): the held read,
  dismiss and its self-clear, and the seeded default watch."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
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
              :assign {:from #{:draft} :to :draft
                       :input [:map [:owner {:x-ref {:principal true}} [:string {:min 1 :max 64}]]]
                       :handler (fn [row inp _ctx] (assoc-in row [:data :owner] (:owner inp)))
                       :safety quiet}
              :close {:from #{:open} :to :closed :safety quiet}}}))

(def ^:private watch-connection
  ;; a source's connection, live or dark, as workqueue10 declares it
  (r/resource
   {:kind :connection
    :plural "connections"
    :states [:live :dark]
    :initial :live
    :terminal #{}
    :summary "{data.tag} · {state}"
    :schema [:map [:tag [:string {:min 1 :max 40}]]]
    :actions {:go_dark {:from #{:live} :to :dark :safety quiet}
              :go_live {:from #{:dark} :to :live :safety quiet}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private dana (t/principal {:id "dana" :display "Dana"}))

(defn- eng! []
  (engine/engine {:storage (memory/storage) :resources [watch-task watch-connection]}))

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

(deftest a-required-watch-wins-a-row-the-seeded-watch-also-holds
  (let [eng (eng!)
        other (task! eng "other" "elena")
        mine (task! eng "mine" "elena")
        _ (move! eng :watch_task other :groom {} elena)
        _ (move! eng :watch_task mine :groom {} elena)
        _ (watches/seed! eng "elena")
        req (watch! eng elena {:name "Setting up" :required true
                               :cues [{:kind "watch_task" :actions [] :states ["open"]
                                       :filter {:title "mine"} :note "Finish {data.title}"}]})
        doc (watches/held-read eng nil elena)
        row (first (filter #(= mine (:id %)) (:rows doc)))]
    (testing "the row is answered once, from the required watch, with its note"
      (is (= 1 (count (filter #(= mine (:id %)) (:rows doc)))))
      (is (= req (get-in row [:watch :id])))
      (is (= "Finish mine" (:note row))))
    (testing "the required row comes first, the seeded watch's rows after it"
      (is (= [mine other] (mapv :id (:rows doc)))))
    (testing "it refuses dismiss on the watch it came from"
      (is (thrown? Exception (move! eng :watch req :dismiss {:row_id mine :row_kind "watch_task"} elena)))
      (is (= #{mine other} (ids (watches/held-read eng nil elena)))))))

(deftest a-watch-answers-its-rows-in-the-order-of-its-cues
  (let [eng (eng!)
        task (task! eng "step" nil)
        conn (str (get-in (inv/create! eng :connection {:tag "gtasks"} {:principal elena})
                          [:row :id]))
        steps (watch! eng elena {:name "Steps"
                                 :cues [{:kind "watch_task" :actions [] :states ["draft"]}
                                        {:kind "watch" :actions [] :states ["active"]
                                         :filter {:name "Steps"}}
                                        {:kind "connection" :actions [] :states ["live"]}]})
        rows (:rows (watches/held-read eng nil elena))]
    (testing "one row of each kind; today's sort alone would answer them by kind"
      (is (= ["connection" "watch" "watch_task"] (sort (map :kind rows)))))
    (testing "the rows come out in cue order"
      (is (= [task steps conn] (mapv :id rows))))))

(deftest rows-one-cue-holds-keep-todays-order
  (let [eng (eng!)
        a (task! eng "a" "elena")
        b (task! eng "b" "elena")
        _ (move! eng :watch_task a :groom {} elena)
        _ (move! eng :watch_task b :groom {} elena)
        _ (watch! eng elena {:name "Mine" :cues [{:kind "watch_task" :actions []}]})
        rows (:rows (watches/held-read eng nil elena))]
    (is (= #{a b} (into #{} (map :id) rows)))
    (is (= (mapv :id (sort-by (juxt #(or (:since %) "~") :kind :id) rows))
           (mapv :id rows)))))

(deftest a-required-watch-comes-before-another-watch
  (let [eng (eng!)
        other (task! eng "other" nil)
        mine (task! eng "mine" nil)
        _ (watch! eng elena {:name "Other"
                             :cues [{:kind "watch_task" :actions [] :states ["draft"]
                                     :filter {:title "other"}}]})
        _ (watch! eng elena {:name "Setting up" :required true
                             :cues [{:kind "watch_task" :actions [] :states ["draft"]
                                     :filter {:title "mine"}}]})]
    (is (= [mine other] (mapv :id (:rows (watches/held-read eng nil elena)))))))

(deftest a-cue-naming-an-id-holds-that-row-alone
  (let [eng (eng!)
        one (task! eng "one" nil)
        _ (task! eng "two" nil)]
    (watch! eng elena {:name "One" :cues [{:kind "watch_task" :actions [] :states ["draft"]
                                            :filter {:id one}}]})
    (is (= #{one} (ids (watches/held-read eng nil elena))))
    (testing "it leaves the read when it leaves the cue's states"
      (move! eng :watch_task one :groom {} elena)
      (is (= #{} (ids (watches/held-read eng nil elena)))))))

(deftest a-cue-asks-whether-a-field-is-set
  (let [eng (eng!)
        t (task! eng "t" nil)
        cue (fn [v] {:kind "watch_task" :actions [] :states ["draft"] :filter {:owner_set v}})
        unset (watch! eng elena {:name "Unset" :cues [(cue "false")]})]
    (testing "empty holds the row whose field is nil"
      (is (= #{t} (ids (watches/held-read eng nil elena)))))
    (move! eng :watch unset :drop {} elena)
    (let [set' (watch! eng elena {:name "Set" :cues [(cue "true")]})]
      (testing "not empty holds nothing while it is nil"
        (is (= #{} (ids (watches/held-read eng nil elena)))))
      (move! eng :watch_task t :assign {:owner "dana"} elena)
      (testing "not empty holds it once the field has a value"
        (is (= #{t} (ids (watches/held-read eng nil elena)))))
      (move! eng :watch set' :drop {} elena))
    (watch! eng elena {:name "Unset again" :cues [(cue "false")]})
    (testing "empty stops holding it once the field has a value"
      (is (= #{} (ids (watches/held-read eng nil elena)))))
    (testing "a _set key that is not true or false is refused"
      (is (thrown? Exception (watch! eng elena {:name "Bad" :cues [(cue "maybe")]}))))))

(deftest equality-on-a-nil-field-holds-nothing
  (let [eng (eng!)]
    (task! eng "t" nil)
    (watch! eng elena {:name "Dana's" :cues [{:kind "watch_task" :actions [] :states ["draft"]
                                               :filter {:owner "dana"}}]})
    (is (= #{} (ids (watches/held-read eng nil elena))))))

(deftest the-grant-still-conceals
  (let [eng (eng!)
        t (task! eng "t" nil)
        _ (watch! eng elena {:name "Unowned" :cues [{:kind "watch_task" :actions [] :states ["draft"]
                                                     :filter {:owner_set "false"}}]})
        vis (fn [kind? field?] {:kind? kind? :whole-kind? kind? :row? (constantly true)
                                :field? field?})]
    (is (= #{t} (ids (watches/held-read eng (vis (constantly true) (constantly true)) elena))))
    (testing "a reader whose grant does not admit the field is not told"
      (is (= #{} (ids (watches/held-read eng (vis (constantly true)
                                                  (fn [_ f] (not= "owner" (name f))))
                                         elena)))))
    (testing "a reader whose grant does not admit the row is not told"
      (is (= #{} (ids (watches/held-read eng (vis #(not= "watch_task" (name %)) (constantly true))
                                         elena)))))))

(deftest every-member-is-seeded-one-default-watch
  (let [eng (eng!)
        mine (task! eng "mine" "elena")]
    (move! eng :watch_task mine :groom {} elena)
    (let [row (watches/seed! eng "elena")]
      (testing "it is a held row named for what it holds"
        (is (some? row))
        (is (= "Waiting on you" (get-in row [:data :name])))
        (is (= "hold" (str (get-in row [:data :mode]))))
        (is (= #{"quest" "held_call" "approval_request" "question" "connection" "*"}
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

(defn- cue-kinds [eng who]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :watch)]
    (mapv #(mapv (comp str :kind) (get-in (inv/decode-row rdef %) [:data :cues]))
          (store/with-tx st (fn [tx] (store/query-rows st tx :watch {:for who} {:limit 10}))))))

(deftest a-dark-connection-is-a-card-on-the-seeded-watch
  (let [eng (eng!)
        c (str (get-in (inv/create! eng :connection {:tag "gtasks"} {:principal elena})
                       [:row :id]))]
    (watches/seed! eng "elena")
    (is (not (contains? (ids (watches/held-read eng nil elena)) c))
        "a live connection is no card")
    (move! eng :connection c :go_dark {} elena)
    (let [row (first (filter #(= c (:id %)) (:rows (watches/held-read eng nil elena))))]
      (is (some? row))
      (is (= "The connection gtasks is dark: its source has stopped answering." (:note row))))))

(deftest a-seeded-watch-gains-the-dark-connection-cue-once
  (let [eng (eng!)
        old! (fn [who] (inv/create! eng :watch {:for who :name watches/default-name :mode "hold"
                                                 :cues watches/seeded-before-connections}
                                    {:principal watches/engine-actor}))
        kinds (fn [cues] [(mapv (comp str :kind) cues)])
        restated (subvec watches/seeded-before-connections 1)]
    (old! "elena")
    (let [{:keys [id version]} (:row (old! "dana"))]
      (inv/invoke! eng :watch (str id) :restate {:cues restated}
                   {:principal dana :if-match (str version)}))
    (is (nil? (watches/seed! eng "elena")))
    (watches/seed! eng "dana")
    (testing "a watch still as it was seeded gains the cue"
      (is (= (kinds watches/default-cues) (cue-kinds eng "elena"))))
    (testing "a watch its member restated is left alone"
      (is (= (kinds restated) (cue-kinds eng "dana"))))
    (testing "it is gained once"
      (watches/seed! eng "elena")
      (is (= (kinds watches/default-cues) (cue-kinds eng "elena"))))))

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
