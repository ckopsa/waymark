(ns waymark10.mcp-resolve-test
  "waymark_resolve (waymark-pywy.3): the batch lookup by a declared-
  filterable key field, through the real MCP door on memory storage.

  What is proven: the matched/unmatched split and the compact row
  summary; the refusal for a field the declaration did not make a
  key, naming the fields that are; a concealed row is an unmatched
  row (the tool cannot tell absent from concealed); more values than
  one chunk, and more rows than one page, all come back. No
  database, no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the kind: one key field, one eq-only field, two that are not keys ─

(defresource sku
  {:kind :sku
   :states [:draft :live]
   :initial :draft
   :terminal #{:live}
   :summary "{data.name} · {state}"
   :schema [:map
            ;; the key: filterable by :in, so `code=a,b` is the grammar
            [:code {:filter #{:eq :in}} [:string {:min 1 :max 40}]]
            ;; :eq alone is a key too — the grammar splits a comma
            ;; list only where :in was declared, so the tool asks one
            ;; value per call here and the answer is the same shape
            [:store {:filter #{:eq}} [:string {:min 1 :max 40}]]
            ;; substring search is not identity
            [:name {:sort :default :filter #{:contains}} [:string {:min 1 :max 120}]]
            ;; not filterable at all
            [:notes {:optional true} [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:publish {:from #{:draft} :to :live
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A published sku is on the shelf."}}}})

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [sku]}))

(defn- seed! [eng id data]
  (inv/create! eng :sku data {:principal grants/approvals-actor :id id}))

(defn- json [resp] (wire/read-json (:body resp)))

(def ^:private as-mom {"x-waymark-principal" "mom"})
(def ^:private as-agent {"x-waymark-principal" "agent-7"
                         "x-waymark-actor-type" "agent"})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method method
                              :params params})}))

(defn- resolve!
  "One waymark_resolve call → {:error? :out} with the tool's JSON
  text parsed."
  [h headers args]
  (let [resp (rpc h headers "tools/call" {:name "waymark_resolve" :arguments args})
        _ (is (= 200 (:status resp)) (:body resp))
        r (get-in (json resp) [:result])]
    {:error? (:isError r)
     :out (wire/read-json (get-in r [:content 0 :text]))}))

;; ── 1. the split, the summary, the projection ────────────────────────

(deftest matched-and-unmatched-are-a-set-difference
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (seed! eng "a1" {:code "A1" :name "Apple" :store "costco" :notes "crisp"})
    (seed! eng "b2" {:code "B2" :name "Bread" :store "winco"})
    (testing "the tool is advertised beside the six"
      (is (contains? (into #{} (map :name) (:tools (:result (json (rpc h as-mom "tools/list" {})))))
                     "waymark_resolve")))
    (testing "each value lands on exactly one side"
      (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by "code"
                                                    :values ["A1" "ZZ" "B2" " A1 " ""]})]
        (is (false? error?) (pr-str out))
        (is (= "sku" (:kind out)))
        (is (= "code" (:by out)))
        (is (= #{:A1 :B2} (set (keys (:matched out)))))
        (is (= ["ZZ"] (:unmatched out)) "trimmed, de-duplicated, blanks dropped")
        (is (nil? (:ambiguous out)) "absent when nothing is ambiguous")))
    (testing "a matched value is a compact summary, not an envelope"
      (let [row (get-in (resolve! h as-mom {:kind "sku" :by "code" :values ["A1"]})
                        [:out :matched :A1])]
        (is (= "a1" (:id row)))
        (is (= "sku" (:kind row)) "the same shape return: summary answers")
        (is (= "draft" (:state row)))
        (is (= "Apple · Draft" (:summary row)))
        (is (= "A1" (get-in row [:fields :code])))
        (is (= "crisp" (get-in row [:fields :notes])))
        (is (not (contains? row :actions)) "no actions block")
        (is (not (contains? row :data)) "the route's summary carries fields, not data")))
    (testing "`fields` keeps only the named ones"
      (let [row (get-in (resolve! h as-mom {:kind "sku" :by "code" :values ["A1"]
                                            :fields ["name"]})
                        [:out :matched :A1])]
        (is (= {:name "Apple"} (:fields row))
            "the key field rode along to read the match, then left again")))
    (testing "naming the key field keeps it"
      (let [row (get-in (resolve! h as-mom {:kind "sku" :by "code" :values ["A1"]
                                            :fields ["code" "name"]})
                        [:out :matched :A1])]
        (is (= {:code "A1" :name "Apple"} (:fields row)))))
    (testing "`fields` is the route's own projection — a name outside the vocabulary is its 400"
      (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by "code" :values ["A1"]
                                                    :fields ["nope"]})]
        (is (true? error?))
        (is (= 400 (:status out)))
        (is (some #{"code"} (:fields out)) "the vocabulary rides the refusal")))
    (testing "`filter` narrows the candidates with the query grammar"
      (let [{:keys [out]} (resolve! h as-mom {:kind "sku" :by "code"
                                              :values ["A1" "B2"]
                                              :filter {:store "costco"}})]
        (is (= [:A1] (keys (:matched out))))
        (is (= ["B2"] (:unmatched out)))))
    (testing "a value the comma grammar cannot carry is unmatched, not mangled"
      (let [{:keys [out]} (resolve! h as-mom {:kind "sku" :by "code" :values ["A1,B2"]})]
        (is (= {} (:matched out)))
        (is (= ["A1,B2"] (:unmatched out)))))
    (testing "an eq-only field resolves too — the vocabulary is :eq OR :in"
      (let [{:keys [out]} (resolve! h as-mom {:kind "sku" :by "store"
                                              :values ["costco" "target"]})]
        (is (= "a1" (get-in out [:matched :costco :id])))
        (is (= ["target"] (:unmatched out)))))
    (testing "a value several rows share is ambiguous, never matched"
      (seed! eng "a1-again" {:code "A1" :name "Apple again" :store "winco"})
      (let [{:keys [out]} (resolve! h as-mom {:kind "sku" :by "code" :values ["A1" "B2"]})]
        (is (= [:B2] (keys (:matched out))))
        (is (= #{"a1" "a1-again"} (into #{} (map :id) (get-in out [:ambiguous :A1]))))
        (is (= [] (:unmatched out)))))
    (testing "the route's own refusal passes through — an unknown filter is its 422"
      (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by "code" :values ["A1"]
                                                    :filter {:nonsense "x"}})]
        (is (true? error?))
        (is (= 422 (:status out)))))))

;; ── 2. the refusal names the declaration's vocabulary ────────────────

(deftest a-field-that-is-not-a-key-is-refused-with-the-fields-that-are
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (seed! eng "a1" {:code "A1" :name "Apple" :store "costco" :notes "crisp"})
    (doseq [by ["notes" "name" "state" "nope"]]
      (testing (str "by " by)
        (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by by :values ["Apple"]})]
          (is (true? error?))
          (is (= 422 (:status out)))
          (is (= "https://waymark.dev/problems/not-resolvable" (:type out)))
          (is (= by (:by out)))
          (is (= ["code" "store"] (:resolvable out))
              "the vocabulary, off the declaration — never a scan")
          (is (str/includes? (:detail out) "\"code\""))
          (is (some #(str/includes? % "\"store\"") (:remedies out))))))
    (testing "the vocabulary reads the declaration, not the rows"
      (is (= ["code" "store"] (mcp/resolvable-fields sku))))
    (testing "an unknown kind is the plural door's own 404"
      (let [{:keys [error? out]} (resolve! h as-mom {:kind "widget" :by "code" :values ["A1"]})]
        (is (true? error?))
        (is (= 404 (:status out)))))))

;; ── 3. concealment: a row outside the grant is simply unmatched ──────

(deftest a-concealed-row-is-unmatched
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (seed! eng "a1" {:code "A1" :name "Apple" :store "costco"})
    (seed! eng "b2" {:code "B2" :name "Bread" :store "winco"})
    (inv/create! eng :grant
                 {:audience "agent-7"
                  :scope [{:kind "sku" :actions [] :ids ["a1"]}]}
                 {:principal grants/approvals-actor
                  :id "grant-resolve-1"
                  :mint? true})
    (let [accepted (h {:request-method :post
                       :uri "/api/grants/grant-resolve-1/-/accept"
                       :headers (assoc as-agent "content-type" "application/json")
                       :body "{}"})]
      (is (= 200 (:status accepted)) (:body accepted)))
    (let [worn (assoc as-agent "x-waymark-grant" "grant-resolve-1")]
      (testing "under an id-scoped grant, the other row is unmatched — not refused, not narrated"
        (let [{:keys [error? out]} (resolve! h worn {:kind "sku" :by "code" :values ["A1" "B2"]})]
          (is (false? error?) (pr-str out))
          (is (= [:A1] (keys (:matched out))))
          (is (= ["B2"] (:unmatched out)))))
      (testing "and the same call by the household sees both"
        (let [{:keys [out]} (resolve! h as-mom {:kind "sku" :by "code" :values ["A1" "B2"]})]
          (is (= #{:A1 :B2} (set (keys (:matched out)))))
          (is (= [] (:unmatched out))))))))

;; ── 4. chunking and paging ───────────────────────────────────────────

(deftest more-values-than-one-chunk-all-come-back
  (let [eng (fresh-engine)
        h (engine/handler eng)
        n (+ (* 2 mcp/resolve-chunk) 20)
        codes (mapv #(format "C%03d" %) (range n))]
    (doseq [[i c] (map-indexed vector codes)]
      (seed! eng (str "c" i) {:code c :name (str "Item " i) :store "costco"}))
    (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by "code"
                                                  :values (conj codes "NOPE")})]
      (is (false? error?) (pr-str out))
      (is (= n (count (:matched out))))
      (is (= ["NOPE"] (:unmatched out)))
      (is (= "c7" (get-in out [:matched :C007 :id]))))))

(deftest more-rows-than-one-page-are-walked-to-the-end
  ;; forty keys, three rows each: one chunk, 120 candidate rows, and
  ;; the route's page maximum is 100 — the second page must be read
  ;; or the last keys would read as unmatched
  (let [eng (fresh-engine)
        h (engine/handler eng)
        codes (mapv #(format "D%02d" %) (range 40))]
    (doseq [c codes, k (range 3)]
      (seed! eng (str c "-" k) {:code c :name (str c " #" k) :store "costco"}))
    (let [{:keys [error? out]} (resolve! h as-mom {:kind "sku" :by "code" :values codes})]
      (is (false? error?) (pr-str out))
      (is (= {} (:matched out)))
      (is (= [] (:unmatched out)))
      (is (= 40 (count (:ambiguous out))))
      (is (every? #(= 3 (count %)) (vals (:ambiguous out)))))))
