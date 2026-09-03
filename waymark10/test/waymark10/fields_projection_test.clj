(ns waymark10.fields-projection-test
  "The caller's field projection on the collection route
  (waymark-pywy.2): GET /api/{plural}?fields=a,b narrows each item's
  \"fields\" to the pick, and the pick is ALWAYS a subset of the
  grant's projection — collections/item-of selects out of data
  render/envelope has already projected, so a field the grant
  conceals is not there to select, whatever fields= names. A name
  outside the caller's published vocabulary (never declared, redacted
  by the grant, or secret — one answer for all three) is one 400
  naming that vocabulary. waymark_query passes the argument through
  and inherits every one of those answers from the route.

  Memory storage, the real ring handler, no database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private line
  "A receipt line: the kind the connector's first session joined by
  hand. memo is what a grant will deny; pin is secret by declaration."
  (r/resource
   {:kind :fp_line
    :plural "fp_lines"
    :states [:open :matched]
    :initial :open
    :terminal #{:matched}
    :summary "{data.label} · {state}"
    :schema [:map
             [:label [:string {:min 1 :max 60}]]
             [:upc [:string {:min 1 :max 20}]]
             [:price [:int {:min 0}]]
             [:memo {:optional true} [:maybe [:string {:max 200}]]]
             [:pin {:optional true :secret true} [:maybe [:string {:max 20}]]]]
    :filterable {:state #{:eq :in} :upc #{:eq :in}}
    :sortable {:fields [:price] :default "price"}
    :actions
    {:match {:from #{:open} :to :matched
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A matched line is history."}}}}))

(def ^:private as-owner {"x-waymark-principal" "owner"})
(def ^:private as-agent {"x-waymark-principal" "agent-7"
                         "x-waymark-actor-type" "agent"})

(def ^:private owner (t/principal {:id "owner" :display "Owner"}))
(def ^:private the-agent (t/principal {:id "agent-7" :type :agent
                                   :display "agent-7"}))

(def ^:private secret-memo "MEMO-77-never-on-the-wire")
(def ^:private secret-pin "PIN-4242")

(defn- call!
  "One request through the real handler — identity, router, the
  problem boundary. :doc is the parsed body, :text the raw one (the
  never-on-the-wire assertions read that)."
  [eng method uri & {:keys [body query headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"content-type" "application/json"}
                                       (or headers as-owner))}
                query (assoc :query-string query)
                body (assoc :body (wire/write-json body))))
        text (let [b (:body resp)]
               (cond (nil? b) "" (string? b) b :else (slurp b)))]
    (assoc resp :text text
           :doc (when-not (str/blank? text) (wire/read-json text)))))

(defn- boot []
  (let [eng (engine/engine {:storage (memory/storage) :resources [line]})]
    (doseq [[label upc price] [["milk" "0001" 3] ["eggs" "0002" 5]
                               ["bread" "0003" 4]]]
      (let [r (call! eng :post "/api/fp_lines"
                     :body {:label label :upc upc :price price
                            :memo secret-memo :pin secret-pin})]
        (is (= 201 (:status r)) (pr-str (:doc r)))))
    eng))

(defn- wear!
  "Mint a grant for agent-7 over `scope`, accept it as agent-7, and
  answer [grant-id headers] — what the agent wears presenting it."
  [eng scope]
  (let [minted (call! eng :post "/api/grants"
                      :body {:audience "agent-7" :scope scope})
        _ (is (= 201 (:status minted)) (pr-str (:doc minted)))
        gid (last (str/split (str (get-in minted [:doc :self])) #"/"))
        accepted (call! eng :post (str "/api/grants/" gid "/-/accept")
                        :headers as-agent)]
    (is (= 200 (:status accepted)) (pr-str (:doc accepted)))
    [gid (assoc as-agent "x-waymark-grant" gid)]))

(defn- items [r] (get-in r [:doc :data :items]))
(defn- field-keys [r] (into #{} (map (comp set keys :fields)) (items r)))

;; ── 1. the pick narrows the grid ────────────────────────────────────

(deftest the-pick-narrows-every-item
  (let [eng (boot)]
    (testing "without a pick, the grid projection is render's own"
      (let [r (call! eng :get "/api/fp_lines")]
        (is (= 200 (:status r)))
        (is (= 3 (count (items r))))
        (is (every? #(contains? % :price) (map :fields (items r))))))

    (testing "fields=label,upc: exactly those two on every item,
              nothing else about the item changes"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label,upc")]
        (is (= 200 (:status r)) (:text r))
        (is (= 3 (count (items r))))
        (is (= 3 (get-in r [:doc :data :total])))
        (is (= #{#{:label :upc}} (field-keys r)))
        (is (every? #(not (contains? % :data)) (items r))
            "still a summary: no data member")
        (is (every? #(contains? (:actions %) :match) (items r))
            "the action partition survives the projection whole")
        (is (not (str/includes? (:text r) secret-memo))
            "the unpicked memo rides nowhere")
        (is (str/includes? (get-in r [:doc :self]) "fields=label%2Cupc")
            "the self href is the view the caller saw")))

    (testing "the pick rides the pager's links"
      (let [r (call! eng :get "/api/fp_lines"
                     :query "fields=upc&page[size]=2")]
        (is (= 200 (:status r)))
        (is (= 2 (count (items r))))
        (is (str/includes? (get-in r [:doc :links :next :href]) "fields=upc"))
        (let [nxt (call! eng :get "/api/fp_lines"
                         :query (second (str/split
                                         (get-in r [:doc :links :next :href])
                                         #"\?" 2)))]
          (is (= 200 (:status nxt)))
          (is (= #{#{:upc}} (field-keys nxt))))))

    (testing "a picked optional field rides whole — the pick is the
              caller's, the grid rule bounds only what nobody asked for"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=memo")]
        (is (= 200 (:status r)))
        (is (every? #(= secret-memo (get-in % [:fields :memo])) (items r)))))

    (testing "rows=none stays the field-free stub, pick or no pick"
      (let [r (call! eng :get "/api/fp_lines" :query "rows=none&fields=label")]
        (is (= 200 (:status r)) (:text r))
        (is (= 3 (count (items r))))
        (is (every? #(not (contains? % :fields)) (items r)))
        (is (every? #(nil? (:actions %)) (items r)))))

    (testing "the pick sits beside the grammar, not inside it: filters
              and sorts still parse around it"
      (let [r (call! eng :get "/api/fp_lines"
                     :query "upc=0002&sort=-price&fields=label")]
        (is (= 200 (:status r)) (:text r))
        (is (= [{:label "eggs"}] (mapv :fields (items r))))))))

;; ── 2. an unknown name is one 400 naming the vocabulary ─────────────

(deftest an-unknown-field-is-a-400-naming-the-vocabulary
  (let [eng (boot)]
    (testing "a name the kind never declared"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label,nope")]
        (is (= 400 (:status r)) (:text r))
        (is (= "application/problem+json"
               (get-in r [:headers "Content-Type"])))
        (is (= "Unknown field" (get-in r [:doc :title])))
        (is (str/includes? (get-in r [:doc :detail]) "nope"))
        (is (= ["label" "memo" "price" "upc"] (get-in r [:doc :fields]))
            "the vocabulary is the published schema's: the secret pin is
             not in it")
        (is (seq (get-in r [:doc :errors :fields])))))

    (testing "a blank pick names nothing, and hides nothing to clear"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=")]
        (is (= 400 (:status r)))
        (is (= "Unknown field" (get-in r [:doc :title])))
        (is (= ["label" "memo" "price" "upc"] (get-in r [:doc :fields])))))

    (testing "the secret disposition: naming the secret field is the
              same 400 an unknown name draws, and the value rides nowhere"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=pin")]
        (is (= 400 (:status r)))
        (is (not (str/includes? (:text r) secret-pin)))
        (is (not-any? #{"pin"} (get-in r [:doc :fields])))))

    (testing "the grammar's own refusals keep their status: an unknown
              PARAMETER is still the 422 it always was"
      (let [r (call! eng :get "/api/fp_lines" :query "colour=red")]
        (is (= 422 (:status r)))))))

;; ── 3. the pick is a subset of the grant's projection ───────────────

(deftest the-pick-is-a-subset-of-the-grant
  (let [eng (boot)
        [_ deny-memo] (wear! eng [{:kind "fp_line" :actions ["match"]
                                   :fields {:mode "deny" :names ["memo"]}}])]
    (testing "the unscoped reader may pick memo (the control)"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label,memo")]
        (is (= 200 (:status r)))
        (is (= #{#{:label :memo}} (field-keys r)))))

    (testing "under a deny-list, a pick inside the projection lands"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label,upc"
                     :headers deny-memo)]
        (is (= 200 (:status r)) (:text r))
        (is (= 3 (count (items r))))
        (is (= #{#{:label :upc}} (field-keys r)))))

    (testing "THE LAW: naming the denied field widens nothing — the
              answer is the unknown-field 400, its vocabulary the
              grant's projection, and the value rides nowhere"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label,memo"
                     :headers deny-memo)]
        (is (= 400 (:status r)) (:text r))
        (is (= "Unknown field" (get-in r [:doc :title])))
        (is (= ["label" "price" "upc"] (get-in r [:doc :fields]))
            "memo is not in this caller's vocabulary at all")
        (is (not (str/includes? (:text r) secret-memo)))))

    (testing "a pick that does not name the denied field never shows
              it either — the narrowing runs over the projected data"
      (let [r (call! eng :get "/api/fp_lines" :query "fields=label"
                     :headers deny-memo)]
        (is (= 200 (:status r)))
        (is (= #{#{:label}} (field-keys r)))
        (is (not (str/includes? (:text r) secret-memo)))))

    (testing "an allow-list is the same law from the other side"
      (let [[_ allow-label] (wear! eng [{:kind "fp_line" :actions ["match"]
                                         :fields {:mode "allow"
                                                  :names ["label"]}}])
            ok (call! eng :get "/api/fp_lines" :query "fields=label"
                      :headers allow-label)
            no (call! eng :get "/api/fp_lines" :query "fields=label,upc"
                      :headers allow-label)]
        (is (= 200 (:status ok)) (:text ok))
        (is (= #{#{:label}} (field-keys ok)))
        (is (= 400 (:status no)))
        (is (= ["label"] (get-in no [:doc :fields])))
        (is (not (str/includes? (:text no) "0001")))))))

;; ── 4. waymark_query passes the pick through ────────────────────────

(deftest waymark-query-passes-fields-through
  (let [eng (boot)
        door (mcp/door eng)
        tool-doc (fn [out] (wire/read-json (get-in out [:content 0 :text])))]
    (testing "the tool advertises the argument"
      (let [q (first (filter #(= "waymark_query" (:name %)) (mcp/listing)))]
        (is (= "array" (get-in q [:inputSchema :properties :fields :type])))))

    (testing "an array of names crosses as the route's own comma list"
      (let [out (mcp/call-tool eng door {:principal owner}
                               "waymark_query"
                               {:kind "fp_line" :fields ["label" "upc"]})
            doc (tool-doc out)]
        (is (not (:isError out)) (pr-str out))
        (is (= 3 (count (get-in doc [:data :items]))))
        (is (= #{#{:label :upc}}
               (into #{} (map (comp set keys :fields))
                     (get-in doc [:data :items]))))
        (is (str/includes? (:self doc) "fields=label%2Cupc"))))

    (testing "the route's 400 reaches the model as the route's own
              problem — isError, never a protocol fault"
      (let [out (mcp/call-tool eng door {:principal owner}
                               "waymark_query"
                               {:kind "fp_line" :fields ["label" "nope"]})
            doc (tool-doc out)]
        (is (true? (:isError out)))
        (is (= "Unknown field" (:title doc)))
        (is (= ["label" "memo" "price" "upc"] (:fields doc)))))

    (testing "the subset law is inherited, not re-implemented: a scoped
              session naming its denied field gets the grant's answer"
      (let [[gid _] (wear! eng [{:kind "fp_line" :actions ["match"]
                                 :fields {:mode "deny" :names ["memo"]}}])
            session {:principal the-agent
                     :visibility (grants/visibility eng gid the-agent)}
            out (mcp/call-tool eng door session "waymark_query"
                               {:kind "fp_line" :fields ["label" "memo"]})
            doc (tool-doc out)
            ok (mcp/call-tool eng door session "waymark_query"
                              {:kind "fp_line" :fields ["label"]})]
        (is (true? (:isError out)))
        (is (= ["label" "price" "upc"] (:fields doc)))
        (is (not (str/includes? (get-in out [:content 0 :text]) secret-memo)))
        (is (not (:isError ok)) (pr-str ok))
        (is (not (str/includes? (get-in ok [:content 0 :text]) secret-memo)))))))
