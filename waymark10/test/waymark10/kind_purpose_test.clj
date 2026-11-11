(ns waymark10.kind-purpose-test
  "A kind declares its purpose (ticket a1f19281): {:what :when :not},
  held to its shape at the def site and shown on every surface an
  agent reads before it acts — discover, waymark_schema, the MCP
  instructions and the OpenAPI tags.

  Memory storage, the real handler: no database, no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.dashboard :as dashboard]
            [waymark10.saved-view :as saved-view]
            [waymark10.server.amendment-policy :as amendment-policy]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.capabilities :as capabilities]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.kind-doc :as kind-doc]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.openapi :as openapi]
            [waymark10.server.promises :as promises]
            [waymark10.server.readings :as readings]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.conformance :as conf]
            [waymark10.types :as t]
            [waymark10.usability :as usability]
            [waymark10.wire :as wire]))

(def ^:private note-map
  {:kind :purpose_note
   :plural "purpose_notes"
   :states [:open :filed]
   :initial :open
   :terminal #{:filed}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :purpose {:what "A note the house keeps about one thing."
             :for :person
             :when "Reach for it to write a thing down; it asks nobody to act."
             :not [{:kind :quest
                    :why "A quest is a goal with steps; a note is only words."}
                   {:kind :no_such_kind
                    :why "Named so the surfaces show they leave out a kind the engine does not serve."}]}
   :actions {:file {:from #{:open} :to :filed
                    :safety {:idempotent false :reversible false :confirm false
                             :one-way "A filed note stays filed."}}}})

(def ^:private note (r/resource note-map))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- fresh-engine []
  ;; capability is :app-opt-in, so the app names it
  (engine/engine {:storage (memory/storage)
                  :resources [note capabilities/capability]}))

(defn- discover-doc [eng]
  (let [resp ((engine/handler eng) {:request-method :get
                                    :uri "/api/.well-known/waymark"
                                    :headers {"x-waymark-principal" "elena"}})]
    (some-> (:body resp) wire/read-json)))

(defn- schema-doc [eng kind]
  (-> (mcp/call-tool eng (mcp/door eng) {:principal elena}
                     "waymark_schema" {:kind kind})
      (get-in [:content 0 :text])
      wire/read-json))

(deftest a-declared-purpose-projects-to-every-surface
  (let [eng (fresh-engine)
        discover (discover-doc eng)
        instructions (str mcp/instructions
                          (kind-doc/purpose-lines eng {:principal elena}))]
    (doseq [k [:purpose_note :quest :scheduled_action
               ;; cluster 2, the seat machinery (ticket cc56dc7a)
               :seat :sitting :model :schedule :runner_link :runner_provider
               :subscription :lease :transcript :transcript_entry :sitting_call
               ;; cluster 3, authority (ticket be0ad60e)
               :member :role :team :grant :approval_request :capability
               :mcp_server :secret :domain :definition :held_call]]
      (testing (name k)
        (let [vs (conf/purpose-violations (get (inv/resources eng) k) discover
                                          (schema-doc eng (name k)) instructions)]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "a neighbour the engine does not serve is left out"
      (is (= ["quest"]
             (mapv :kind (get-in discover [:resources :purpose_note :purpose :not]))))
      (doseq [[_ entry] (:resources discover)
              {k :kind} (get-in entry [:purpose :not])]
        (is (contains? (inv/resources eng) (keyword k)) k)))
    (testing "the OpenAPI tag carries the sentence"
      (is (some #(and (= "quest" (:name %))
                      (str/includes? (:description %) "A goal a person accepted"))
                (:tags (openapi/document eng)))))))

(defn- every-kind-engine
  "The engine with every kind an app may opt into beside the enrolled
  ones, so discover lists every kind the framework serves."
  []
  (engine/engine {:storage (memory/storage)
                  :resources (into [note capabilities/capability saved-view/saved-view
                                    goals/goal readings/measure-reading
                                    promises/promise amendments/amendment
                                    amendment-policy/amendment-policy]
                                   dashboard/resources)}))

(deftest every-domainless-kind-declares-a-purpose
  (let [eng (every-kind-engine)
        discover (discover-doc eng)
        instructions (str mcp/instructions
                          (kind-doc/purpose-lines eng {:principal elena}))
        domainless (sort (for [[k entry] (:resources discover)
                               :when (nil? (:domain entry))]
                           k))]
    (is (< 40 (count domainless)) "discover lists the engine's own kinds")
    (doseq [k domainless]
      (testing (name k)
        (if-let [rdef (get (inv/resources eng) (keyword (name k)))]
          (let [vs (conf/purpose-violations rdef discover (schema-doc eng (name k))
                                            instructions)]
            (is (empty? vs) (str/join "\n" vs)))
          (is false (str (name k) ": discover lists a kind the engine does not serve")))))))

(deftest a-purpose-is-held-to-its-shape
  (testing "a sentence of 200 characters or more refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :what] (apply str (repeat 200 "a")))))))
  (testing "a :not entry without its why refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :not] [{:kind :quest}])))))
  (testing "an unknown key refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :who] "me")))))
  (testing "a :for outside person, agent, either and engine refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :for] :robot))))))

(deftest a-kind-says-who-it-is-for
  (let [eng (fresh-engine)
        discover (discover-doc eng)
        instructions (kind-doc/purpose-lines eng {:principal elena})]
    (testing "a quest is a person's; an agent is told to pursue"
      (is (= "person" (get-in discover [:resources :quest :purpose :for])))
      (is (str/includes? (get-in discover [:resources :quest :purpose :for_note])
                         "waymark_pursue"))
      (is (= "person" (get-in (schema-doc eng "quest") [:purpose :for]))))
    (testing "held_call approves an agent's act"
      (is (= "agent" (get-in discover [:resources :held_call :purpose :for]))))
    (testing "the instructions group the kinds by who they are for"
      (let [person-at (str/index-of instructions "For a person")
            quest-at (str/index-of instructions "- quest: ")
            agent-at (str/index-of instructions "For an agent:")]
        (is (and person-at quest-at agent-at (< person-at quest-at agent-at)))))))

(deftest measure-reading-is-served-beside-goal
  (let [discover (discover-doc (every-kind-engine))
        entry (get-in discover [:resources :measure_reading])]
    (testing "discover lists it as the engine's, read beside goal"
      (is (some? entry) "measure_reading is served where goal is")
      (is (= "engine" (get-in entry [:purpose :for]))))
    (testing "its purpose sets it apart from a verdict and a goal"
      (is (= #{"goal" "verdict"}
             (set (map :kind (get-in entry [:purpose :not]))))))))

(deftest a-core-kind-without-a-purpose-is-warned
  (let [eng (fresh-engine)]
    (is (= [] (usability/purpose-missing (get (inv/resources eng) :quest))))
    (is (= 1 (count (usability/purpose-missing (dissoc note :purpose)))))))
