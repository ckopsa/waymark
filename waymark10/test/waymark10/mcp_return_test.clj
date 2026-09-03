(ns waymark10.mcp-return-test
  "`return: summary` on waymark_query, waymark_get and waymark_invoke
  (waymark-pywy.1): an MCP-layer projection of the envelope the route
  already answered, and nothing wider.

  The motivating waste: the connector's first real session received
  the identical action-schema block fourteen times in a row, once per
  invoke, from a caller that had already read waymark_schema. What
  these tests hold:

  • the default is the envelope BYTE FOR BYTE — a call with no
    `return`, a call with `return: envelope`, and the route itself
    answer the same text;
  • a summary drops the actions block and keeps the row — id, kind,
    state, the summary line, data;
  • an invoke's summary names the transition (action, from, to) and
    the data fields that changed;
  • a query's summary keeps paging and facets per row;
  • the projection is INHERITED — a field the grant redacts is not in
    the summary because it was not in the envelope;
  • a refusal, the confirm gate and a dry-run verdict come back the
    same under both.

  Memory storage, the real routes through the in-process door, no
  database — the connector_door_test / gate_proxy_test arrangement."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(defhandler annotate [row inp _ctx]
  (assoc-in row [:data :recipe] (:recipe inp)))

(def ^:private dish
  "A meal-shaped kind with one data-changing action (`annotate`), one
  confirm-gated one (`decline`), and two faceted fields, so a query
  summary has facets to keep."
  (r/resource
   {:kind :rs_dish
    :plural "rs_dishes"
    :states [:suggested :on_list :retired]
    :initial :suggested
    :terminal #{:retired}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 120}]]
             [:tag {:optional true} [:maybe [:string {:max 40}]]]
             [:recipe {:optional true :x-display {:widget "prose"}}
              [:maybe [:string {:max 8000}]]]]
    :filterable {:state #{:eq :in} :tag #{:eq :in}}
    :faceted [:state :tag]
    :actions
    {:accept {:from #{:suggested} :to :on_list
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Joining the list is low-stakes."}}
     :decline {:from #{:suggested} :to :retired
               :safety {:idempotent true :reversible false :confirm true
                        :consequence "The suggestion is discarded."}}
     :annotate {:from #{:on_list} :to :on_list
                :input [:map [:recipe {:x-display {:widget "prose"}}
                              [:string {:max 8000}]]]
                :edit {:prefill [:recipe] :draft {:shared true :live true}}
                :safety {:idempotent true :reversible true :confirm false}
                :handler annotate}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private opts {:principal elena})

(defn- boot []
  (engine/engine {:storage (memory/storage) :resources [dish]}))

(defn- create! [eng data]
  (:id (:row (inv/create! eng :rs_dish data opts))))

(defn- tool
  ([eng tool-name args] (tool eng {:principal elena} tool-name args))
  ([eng session tool-name args]
   (mcp/call-tool eng (mcp/door eng) session tool-name args)))

(defn- text [out] (get-in out [:content 0 :text]))
(defn- doc [out] (wire/read-json (text out)))

(defn- route-get
  "The same GET at the real door, as Elena — the bytes the envelope
  default must match."
  [eng uri]
  (:body ((engine/handler eng)
          {:request-method :get :uri uri
           :headers {"x-waymark-principal" "elena"}})))

;; ── the default is the envelope, byte for byte ──────────────────────

(deftest the-default-is-the-envelope-byte-for-byte
  (let [eng (boot)
        id (create! eng {:name "tacos" :tag "mexican"})]

    (testing "waymark_get: no return, return envelope, and the route agree"
      (let [bare (tool eng "waymark_get" {:kind "rs_dish" :id id})
            env (tool eng "waymark_get" {:kind "rs_dish" :id id :return "envelope"})]
        (is (not (:isError bare)))
        (is (= (text bare) (text env)))
        (is (= (text bare) (route-get eng (str "/api/rs_dishes/" id))))
        (is (contains? (doc bare) :actions) "the envelope still carries its doors")))

    (testing "waymark_query: the same three-way agreement"
      (let [bare (tool eng "waymark_query" {:kind "rs_dish"})
            env (tool eng "waymark_query" {:kind "rs_dish" :return "envelope"})]
        (is (= (text bare) (text env)))
        (is (= (text bare) (route-get eng "/api/rs_dishes")))
        (is (contains? (:actions (doc bare)) :query))))

    (testing "waymark_invoke: the default answer is the moved row's envelope"
      (let [out (tool eng "waymark_invoke" {:kind "rs_dish" :id id :action "accept"})
            d (doc out)]
        (is (not (:isError out)))
        (is (= "on_list" (:state d)))
        (is (contains? d :actions))
        (is (contains? d :unavailable))
        (is (not (contains? d :transition)) "no summary limbs on the envelope")))))

;; ── summary: the row without its doors ──────────────────────────────

(deftest a-summary-drops-the-actions-block-and-keeps-the-row
  (let [eng (boot)
        id (create! eng {:name "tacos" :tag "mexican" :recipe "fry, fold"})
        env (doc (tool eng "waymark_get" {:kind "rs_dish" :id id}))
        out (tool eng "waymark_get" {:kind "rs_dish" :id id :return "summary"})
        s (doc out)]
    (is (not (:isError out)))
    (is (= #{:id :kind :state :summary :data} (set (keys s)))
        "exactly the row: no actions, unavailable, links, parts or meta")
    (is (= id (:id s)))
    (is (= "rs_dish" (:kind s)))
    (is (= "suggested" (:state s)))
    (is (= (:summary env) (:summary s)))
    (is (= (:data env) (:data s)) "the data is the envelope's own, unchanged")
    (is (= {:name "tacos" :tag "mexican" :recipe "fry, fold"} (:data s)))

    (testing "depth=summary under return summary keeps the grid fields instead"
      (let [s' (doc (tool eng "waymark_get" {:kind "rs_dish" :id id
                                             :depth "summary" :return "summary"}))]
        (is (= #{:id :kind :state :summary :fields} (set (keys s'))))
        (is (= "tacos" (get-in s' [:fields :name])))))

    (testing "a row that is not there is the route's own 404, not a summary of nothing"
      (let [gone (tool eng "waymark_get" {:kind "rs_dish" :id "nope" :return "summary"})]
        (is (true? (:isError gone)))
        (is (= 404 (:status (doc gone))))))))

;; ── summary on invoke: the transition and what changed ──────────────

(deftest an-invoke-summary-names-the-transition-and-what-changed
  (let [eng (boot)
        id (create! eng {:name "tacos" :tag "mexican"})]

    (testing "a state move that touches no data: from → to, changed empty"
      (let [out (tool eng "waymark_invoke" {:kind "rs_dish" :id id
                                            :action "accept" :return "summary"})
            s (doc out)]
        (is (not (:isError out)))
        (is (= #{:id :kind :state :summary :data :transition :changed}
               (set (keys s))))
        (is (= {:action "accept" :from "suggested" :to "on_list"} (:transition s)))
        (is (= [] (:changed s)))
        (is (= "on_list" (:state s)))
        (is (= id (:id s)))))

    (testing "a data edit in place: the changed field is named, its new value is in data"
      (let [out (tool eng "waymark_invoke" {:kind "rs_dish" :id id
                                            :action "annotate"
                                            :input {:recipe "fry, fold, serve"}
                                            :return "summary"})
            s (doc out)]
        (is (not (:isError out)))
        (is (= {:action "annotate" :from "on_list" :to "on_list"} (:transition s)))
        (is (= ["recipe"] (:changed s)))
        (is (= "fry, fold, serve" (get-in s [:data :recipe])))
        (is (= "tacos" (get-in s [:data :name])) "the unchanged fields ride along")))

    (testing "a create has no from; every present field changed"
      (let [out (tool eng "waymark_invoke" {:kind "rs_dish" :action "create"
                                            :input {:name "soup" :tag "warm"}
                                            :return "summary"})
            s (doc out)]
        (is (not (:isError out)))
        (is (= {:action "create" :to "suggested"} (:transition s)))
        (is (= ["name" "tag"] (:changed s)))
        (is (string? (:id s)))
        (is (= "soup" (get-in s [:data :name])))))

    (testing "a dry-run's verdict is not an envelope and passes through untouched"
      (let [a (tool eng "waymark_invoke" {:kind "rs_dish" :id id :action "annotate"
                                          :input {:recipe "x"} :dry_run true})
            b (tool eng "waymark_invoke" {:kind "rs_dish" :id id :action "annotate"
                                          :input {:recipe "x"} :dry_run true
                                          :return "summary"})]
        (is (= (text a) (text b)))
        (is (true? (:valid (doc b))))
        (is (not (contains? (doc b) :transition)))))

    (testing "the confirm gate holds under summary, in the same words"
      (let [id2 (create! eng {:name "pie"})
            a (tool eng "waymark_invoke" {:kind "rs_dish" :id id2 :action "decline"})
            b (tool eng "waymark_invoke" {:kind "rs_dish" :id id2 :action "decline"
                                          :return "summary"})]
        (is (true? (:isError a)))
        (is (true? (:isError b)))
        (is (= (text a) (text b)))
        (is (= 409 (:status (doc b))))
        ;; and with the sentence, the summary answers the move
        (let [s (doc (tool eng "waymark_invoke"
                           {:kind "rs_dish" :id id2 :action "decline"
                            :acknowledge "The suggestion is discarded."
                            :return "summary"}))]
          (is (= {:action "decline" :from "suggested" :to "retired"}
                 (:transition s))))))))

;; ── summary on query: rows, paging, facets ──────────────────────────

(deftest a-query-summary-keeps-paging-and-facets-per-row
  (let [eng (boot)
        ids (mapv #(create! eng %) [{:name "tacos" :tag "mexican"}
                                    {:name "pho" :tag "soup"}
                                    {:name "ramen" :tag "soup"}])
        _ (inv/invoke! eng :rs_dish (first ids) :accept nil opts)
        env (doc (tool eng "waymark_query" {:kind "rs_dish" :page_size 2}))
        out (tool eng "waymark_query" {:kind "rs_dish" :page_size 2 :return "summary"})
        s (doc out)]
    (is (not (:isError out)))

    (testing "the page's own facts, without the query action's schema"
      (is (= #{:kind :summary :items :total :page :next :facets} (set (keys s))))
      (is (= "rs_dish_collection" (:kind s)))
      (is (= 3 (:total s)))
      (is (= {:size 2 :number 1} (:page s)))
      (is (= (get-in env [:links :next :href]) (:next s)))
      (is (not (contains? s :actions))))

    (testing "each item is the row projected: id, kind, state, summary, grid fields"
      (is (= 2 (count (:items s))))
      (doseq [it (:items s)]
        (is (= #{:id :kind :state :summary :fields} (set (keys it))))
        (is (contains? (set ids) (:id it)))
        (is (= "rs_dish" (:kind it)))
        (is (string? (get-in it [:fields :name])))))

    (testing "the facets are the envelope's own, lifted out of the query schema"
      (is (= (get-in env [:actions :query :input :properties :state :x-facets])
             (get-in s [:facets :state])))
      (is (= {:suggested 2 :on_list 1} (get-in s [:facets :state])))
      (is (= {:mexican 1 :soup 2} (get-in s [:facets :tag]))))

    (testing "a filter narrows the summary exactly as it narrows the envelope"
      (let [s' (doc (tool eng "waymark_query" {:kind "rs_dish" :filter {:tag "soup"}
                                               :return "summary"}))]
        (is (= 2 (:total s')))
        (is (= #{"pho" "ramen"} (into #{} (map #(get-in % [:fields :name])) (:items s'))))))

    (testing "rows=none: the stubs carry no fields, and the count still answers"
      (let [s' (doc (tool eng "waymark_query" {:kind "rs_dish" :rows "none"
                                               :return "summary"}))]
        (is (= 3 (:total s')))
        (doseq [it (:items s')]
          (is (= #{:id :kind :state :summary} (set (keys it)))))))))

;; ── concealment is inherited, never re-implemented ──────────────────

(deftest a-summary-shows-nothing-the-envelope-hid
  (let [eng (boot)
        id (create! eng {:name "tacos" :tag "mexican" :recipe "secret sauce"})
        ;; the grant's own closure shapes, recipe redacted
        narrow {:kind? (constantly true)
                :row? (constantly true)
                :action? (constantly true)
                :arg? (constantly true)
                :field? (fn [_kind f] (not= "recipe" (name f)))}
        session {:principal elena :visibility narrow}
        env (doc (tool eng session "waymark_get" {:kind "rs_dish" :id id}))
        s (doc (tool eng session "waymark_get" {:kind "rs_dish" :id id :return "summary"}))]
    (is (not (contains? (:data env) :recipe)) "the envelope conceals it")
    (is (not (contains? (:data s) :recipe)) "so the summary cannot carry it")
    (is (= (:data env) (:data s)))
    (is (= "tacos" (get-in s [:data :name])))

    (testing "the same on an invoke's summary and its changed set"
      (let [_ (inv/invoke! eng :rs_dish id :accept nil opts)
            s' (doc (tool eng session "waymark_invoke"
                          {:kind "rs_dish" :id id :action "annotate"
                           :input {:recipe "new sauce"} :return "summary"}))]
        (is (not (:isError s')))
        (is (not (contains? (:data s') :recipe)))
        (is (= [] (:changed s')) "a concealed field's change is not narrated either")))

    (testing "and on a query's rows"
      (let [s' (doc (tool eng session "waymark_query" {:kind "rs_dish" :return "summary"}))]
        (doseq [it (:items s')]
          (is (not (contains? (:fields it) :recipe))))))))

;; ── the argument outside its enum ───────────────────────────────────

(deftest a-return-outside-the-enum-is-refused-in-this-namespaces-own-voice
  (let [eng (boot)
        id (create! eng {:name "tacos"})]
    (doseq [[tool-name args] [["waymark_get" {:kind "rs_dish" :id id}]
                              ["waymark_query" {:kind "rs_dish"}]
                              ["waymark_invoke" {:kind "rs_dish" :id id :action "accept"}]]]
      (testing tool-name
        (let [out (tool eng tool-name (assoc args :return "brief"))
              d (doc out)]
          (is (true? (:isError out)))
          (is (= 422 (:status d)))
          (is (= "return" (:argument d)))
          (is (= ["envelope" "summary"] (:enum d))))))
    (testing "the refusal wrote nothing"
      (is (= "suggested" (:state (doc (tool eng "waymark_get" {:kind "rs_dish" :id id}))))))))
