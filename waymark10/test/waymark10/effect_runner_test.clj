(ns waymark10.effect-runner-test
  "The effect runner (docs/spec-integrations.md § 4, build step 6): one
  deftest per row of § 4's table. The call goes through the power door
  as the integration, to an in-process fake server registered through
  the engine's `:client-fn` seam. Memory storage, real rows through the
  engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.effect-runner :as runner]
            [waymark10.server.engine :as engine]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private clock (Instant/parse "2026-10-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private mom
  (t/principal {:id "mom" :display "Mom" :roles #{"approver"}}))

(def ^:private once
  {:idempotent true :reversible false :confirm false
   :one-way "A parcel's label is bought once."})

(defresource parcel
  {:kind :parcel
   :states [:packed :label_asked :labelled :label_failed]
   :initial :packed
   :summary "{data.number} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:label_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :actions {:buy_label {:from #{:packed} :to :label_asked
                         :safety once
                         :effect {:power "postage__create_label"
                                  :input {:number '(data :number)}
                                  :why "Buy the postage label for parcel {number}."
                                  :idempotency-key '(str "label:" (self))
                                  :writes {:label_ref '(result "object_id")}
                                  :lands :labelled
                                  :fails-to :label_failed}}
             :repack {:from #{:label_asked :labelled :label_failed} :to :packed
                      :safety once}}})

(def ^:private label-tool
  [{:name "create_label" :description "Buy a label."
    :inputSchema {:type "object" :properties {:number {:type "string"}}}}])

(defn- fake-server
  "An in-process postage server. Every tools/call lands on `log`;
  `answer` is (fn [n params]) of the call's count, and answers the
  CallToolResult or throws."
  [log answer]
  (with-meta
    (fn [method params]
      (case method
        "tools/list" {:tools label-tool}
        "tools/call" (answer (count (swap! log conj params)) params)))
    ;; a dropped wire is this test's subject, not a reason to go dark
    {::client/dead? (constantly false)}))

(defn- labelled [id]
  {:content [{:type "text" :text (wire/write-json {:object_id id})}]
   :isError false})

(defn- world
  "An engine with a postage server whose `postage.send` entry is
  `entry`, a live integration holding that server and wearing the
  power, and one parcel asked for its label. → {:eng :log :pid}."
  [entry answer]
  (let [log (atom [])
        eng (engine/engine
             {:storage (memory/storage)
              :resources [caps/capability parcel]
              :now-fn (fn [] clock)
              :services {:mcp-servers {:client-fn (constantly (fake-server log answer))}
                         :integrations {:effect-backoff-ms 0}}})
        _ (inv/create! eng :capability
                       {:token "postage.send"
                        :description "postage.send through a server row."
                        :enforced_by "this engine's own power door"}
                       {:principal colton})
        sid (str (get-in (inv/create! eng :mcp_server
                                      {:name "postage" :transport "http"
                                       :url "http://fake.invalid/mcp/"
                                       :powers [(merge {:power "postage.send"
                                                        :tools ["create_label"]}
                                                       entry)]}
                                      {:principal colton})
                         [:row :id]))
        iid (str (get-in (inv/create! eng :integration
                                      {:name "shippo" :system "Shippo, the postage seller"
                                       :server sid}
                                      {:principal colton})
                         [:row :id]))
        _ (inv/invoke! eng :integration iid :approve {} {:principal mom})
        gid (get-in (store/with-tx (:storage eng)
                      #(store/load-row (:storage eng) % :integration iid {}))
                    [:data :grant])]
    ;; the law does not yet name an effect's power in scope_wanted, so
    ;; the test widens the grant the approve minted, behind the doors
    (store/with-tx (:storage eng)
      (fn [tx]
        (let [raw (store/load-row (:storage eng) tx :grant (str gid) {})]
          (store/save-row! (:storage eng) tx :grant
                           (assoc-in raw [:data :scope] [{:kind "postage.send" :actions []}])
                           (:version raw)))))
    (let [pid (str (get-in (inv/create! eng :parcel {:number "P-1"} {:principal colton})
                           [:row :id]))]
      (inv/invoke! eng :parcel pid :buy_label {} {:principal colton})
      {:eng eng :log log :pid pid :iid iid})))

(defn- row-of [eng kind id]
  (some->> (store/with-tx (:storage eng)
             #(store/load-row (:storage eng) % kind (str id) {}))
           (inv/decode-row (get (inv/resources eng) kind))))

(defn- the-call [eng]
  (let [rd (get (inv/resources eng) :effect_call)]
    (inv/decode-row rd (first (store/with-tx (:storage eng)
                                #(store/query-rows (:storage eng) % :effect_call {}
                                                   {:limit 5}))))))

(deftest answered-is-done-and-the-row-lands
  (let [{:keys [eng log pid iid]} (world {:approval "none"} (fn [_ _] (labelled "L1")))]
    (runner/sweep! eng)
    (let [c (the-call eng)]
      (is (= :done (:state c)))
      (is (= "L1" (get (wire/read-json (get-in c [:data :answer])) :object_id))
          "the answer is kept on the call")
      (is (= 1 (get-in c [:data :tries]))))
    (is (= 1 (count @log)) "one call on the wire")
    (is (re-find #"P-1" (pr-str (:arguments (first @log))))
        "its input, read from the row, went on the wire")
    (let [row (row-of eng :parcel pid)]
      (is (= :labelled (:state row)))
      (is (= "L1" (get-in row [:data :label_ref]))))
    (testing "a second sweep makes no second call"
      (runner/sweep! eng)
      (is (= 1 (count @log))))
    (testing "the call names the integration, on behalf of the door's actor, under its key"
      (let [hs (servers/caller-headers
                {:caller (str "integration:" iid)
                 :principal (assoc (integrations/principal {:id iid :data {:name "shippo"}})
                                   :acts-for "colton")
                 :idempotency-key (str "label:" pid)})]
        (is (str/includes? (get hs "X-Waymark-Principal") (str "integration:" iid)))
        (is (= "colton" (get hs "X-Waymark-Acts-For")))
        (is (= (str "label:" pid) (get hs "Idempotency-Key")))))))

(deftest a-refusal-is-failed-with-the-servers-sentence
  (let [{:keys [eng pid]} (world {:approval "none"}
                                 (fn [_ _] {:content [{:type "text" :text "The address was not found."}]
                                            :isError true}))]
    (runner/sweep! eng)
    (let [c (the-call eng)]
      (is (= :failed (:state c)))
      (is (= "The address was not found." (get-in c [:data :reason]))))
    (is (= :label_failed (:state (row-of eng :parcel pid))))))

(deftest unknown-on-an-idempotent-entry-is-tried-again-with-the-same-key
  (let [{:keys [eng log pid]} (world {:approval "none" :idempotent true}
                                     (fn [n _]
                                       (if (< n 3)
                                         (throw (client/unreachable "the wire dropped."))
                                         (labelled "L3"))))]
    (runner/sweep! eng)
    (is (= 3 (count @log)) "three tries")
    (let [c (the-call eng)]
      (is (= :done (:state c)))
      (is (= 3 (get-in c [:data :tries]))))
    (is (= "L3" (get-in (row-of eng :parcel pid) [:data :label_ref])))))

(deftest unknown-on-any-other-entry-may-or-may-not-have-been-sent
  (let [{:keys [eng log pid]} (world {:approval "none"}
                                     (fn [_ _] (throw (client/unreachable "the wire dropped."))))]
    (runner/sweep! eng)
    (is (= 1 (count @log)) "tried once, and never again")
    (let [c (the-call eng)]
      (is (= :unknown (:state c)))
      (is (str/includes? (get-in c [:data :reason]) "It may or may not have been sent")))
    (let [row (row-of eng :parcel pid)]
      (is (= :label_failed (:state row))))
    (runner/sweep! eng)
    (is (= 1 (count @log)))))

(deftest held-is-held-and-a-refusal-there-is-a-failure
  (let [{:keys [eng log pid iid]} (world {:approval "person"} (fn [_ _] (labelled "L5")))]
    (runner/sweep! eng)
    (let [c (the-call eng)
          hid (get-in c [:data :held_call])
          h (row-of eng :held_call hid)]
      (is (= :held (:state c)))
      (is (some? h) "the held_call is named")
      (is (= (str "integration:" iid) (get-in h [:data :caller])))
      (is (empty? @log) "nothing reached the wire")
      (is (= :label_asked (:state (row-of eng :parcel pid))) "the row is unchanged")
      (inv/invoke! eng :held_call hid :refuse {:reason "Not this one."} {:principal mom})
      (runner/sweep! eng)
      (let [c (the-call eng)]
        (is (= :failed (:state c)))
        (is (str/includes? (get-in c [:data :reason]) "Not this one.")))
      (is (= :label_failed (:state (row-of eng :parcel pid))))
      (is (empty? @log)))))
