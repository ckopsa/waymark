(ns waymark10.kind-purpose-test
  "A kind declares its purpose (ticket a1f19281): {:what :when :not},
  held to its shape at the def site and shown on every surface an
  agent reads before it acts — discover, waymark_schema, the MCP
  instructions and the OpenAPI tags.

  Memory storage, the real handler: no database, no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.kind-doc :as kind-doc]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.openapi :as openapi]
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
  (engine/engine {:storage (memory/storage) :resources [note]}))

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
    (doseq [k [:purpose_note :quest :scheduled_action]]
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

(deftest a-purpose-is-held-to-its-shape
  (testing "a sentence of 200 characters or more refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :what] (apply str (repeat 200 "a")))))))
  (testing "a :not entry without its why refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :not] [{:kind :quest}])))))
  (testing "an unknown key refuses"
    (is (thrown-with-msg? Throwable #"\[purpose\]"
          (r/resource (assoc-in note-map [:purpose :who] "me"))))))

(deftest a-core-kind-without-a-purpose-is-warned
  (let [eng (fresh-engine)]
    (is (= [] (usability/purpose-missing (get (inv/resources eng) :quest))))
    (is (= 1 (count (usability/purpose-missing (dissoc note :purpose)))))))
