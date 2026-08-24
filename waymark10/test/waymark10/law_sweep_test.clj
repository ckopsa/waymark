(ns waymark10.law-sweep-test
  "The law sweep (waymark-442.3, docs/spec-law-sweep.md): under a
  propose hold, which live rows does the proposal re-judge
  differently?

  This suite boots TWICE over one storage on purpose, because a hold
  is not a state you can stage — it is two codebases against one
  database, and the second boot's resident code IS the proposal. That
  is the batch-C overlay suite's own shape, and it is why the
  conformance pack cannot pay this obligation: a driver has one
  classpath.

  The in-memory twin hosts it. A sweep reads rows through the storage
  protocol and nothing else (no `pg/distinct-states`, no SQL), so the
  twin is not a convenience here — it is the proof that the surface
  is portable."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.law-sweep :as law-sweep]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world: one threshold, spelled in two places ─────────────────
;;
;; The guard and the derived fact read the SAME number, so moving it
;; once moves both — an availability drift and a derivation drift in
;; one data-law diff, which is what a real policy edit looks like.

(defn- acct [threshold]
  (r/resource
   {:kind :s_acct
    :plural "s_accts"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:balance {:filter #{:eq :range}} :int]
             [:flagged {:optional true} [:maybe :boolean]]]
    :derived {:flagged {:over [:balance]
                        :expr (list '<= threshold '(var :balance))}}
    :actions
    {:close {:from #{:open} :to :closed
             :guards [(g/expr {:name :balance-covers-the-close
                               :when (list '<= threshold '(data :balance))
                               :explain (str "A balance under " threshold
                                             " cannot close on its own.")
                               :remedies [:s_acct/top_up]})]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed is history."}}
     :top_up {:from #{:open} :to :open
              :safety {:idempotent false :reversible false :confirm false}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- boot [st resources mode]
  (engine/engine {:storage st :resources resources :deploy-mode mode}))

(defn- get-json [eng uri & [headers]]
  (let [resp ((engine/handler eng)
              {:request-method :get :uri (first (.split ^String uri "\\?"))
               :query-string (second (.split ^String uri "\\?"))
               :headers (or headers {"x-waymark-principal" "elena"})})]
    [(:status resp) (some-> (:body resp) wire/read-json)]))

(defn- proposal-id [eng kind]
  (get-in (get (inv/resources eng) kind) [:proposed-law :definition-id]))

(defn- findings-of
  "Findings of one class, read the same whether the document came
  straight off `report` (keywords) or back through the wire (strings)
  — the two spellings are one value and the test says so."
  [doc class]
  (filterv #(= class (let [c (:class %)] (if (keyword? c) (name c) (str c))))
           (get-in doc [:data :findings])))

(defn- definition-row [eng id]
  (let [st (:storage eng)]
    (inv/decode-row (get (inv/resources eng) :definition)
                    (store/with-tx st #(store/load-row st % :definition id {})))))

;; ── the sweep ───────────────────────────────────────────────────────

(deftest a-held-proposal-names-the-rows-it-re-judges
  (let [st (memory/storage)
        ids (atom {})]
    ;; boot 1: the law as it stands. 60 cannot close; 200 can.
    (boot st [(acct 100)] :promote)
    (let [eng (boot st [(acct 100)] :promote)]
      (doseq [[nm bal] [["thin" 60] ["thick" 200]]]
        (swap! ids assoc nm
               (:id (:row (inv/create! eng :s_acct {:name nm :balance bal}
                                       {:principal elena})))))
      (testing "the law of the day: 60 is refused, 200 is not"
        (is (thrown? clojure.lang.ExceptionInfo
                     (inv/invoke! eng :s_acct (@ids "thin") :close nil
                                  {:principal elena})))))

    ;; boot 2: the threshold drops to 50. Guard expr + derived expr,
    ;; both overlayable → a data-law diff, held at proposed.
    (let [eng (boot st [(acct 50)] :propose)
          rdef (get (inv/resources eng) :s_acct)
          did (proposal-id eng :s_acct)]
      (is (some? did) "the diff held at proposed")

      (testing "the two probes are already in hand — no loader, and the
                naive reading of the keys is backwards"
        (let [doc (law-sweep/report eng (definition-row eng did) elena {})]
          (is (= "s_acct" (get-in doc [:data :target_kind])))
          (is (= 1 (get-in doc [:data :from_revision])))
          (is (= 2 (get-in doc [:data :to_revision])))
          (is (= "data_law" (get-in doc [:data :diff_class])))
          (is (= 2 (get-in doc [:data :scanned])))
          (is (false? (get-in doc [:data :truncated])))

          (testing "availability drift: the thin account's close flips
                    from refused to available, and the sentence that
                    goes away is the OLD law's own explain"
            (let [as (findings-of doc "availability")
                  f (first as)]
              (is (= 1 (count as))
                  (str "one row re-judged, got " (pr-str as)))
              (is (= (@ids "thin") (:id f)))
              (is (= "thin · Open" (:summary f)))
              (is (= "close" (get-in f [:detail :action])))
              (is (= "refused" (get-in f [:detail :under_current])))
              (is (= "available" (get-in f [:detail :under_proposed])))
              (is (= "A balance under 100 cannot close on its own."
                     (get-in f [:detail :because]))
                  "the guard's own :explain, rendered by the same path
                   a refusal takes on the wire — the sweep invents no
                   prose")))

          (testing "derivation drift is DELEGATED: the :measure meter's
                    own {:fact :flips :of :sample}, projected"
            (let [ds (findings-of doc "derivation")
                  f (first ds)]
              (is (= 1 (count ds)))
              (is (= "s_acct.flagged" (get-in f [:detail :fact]))
                  "the meter names a fact kind-first; the sweep does not re-word it")
              (is (= 1 (get-in f [:detail :flips])))
              (is (= 2 (get-in f [:detail :of])))
              (is (= [(@ids "thin")] (get-in f [:detail :sample])))
              (is (nil? (:id f))
                  "the meter's grain is the fact, and the finding wears it")))

          (testing "a data-law diff cannot move the schema or the machine,
                    and the notes say so rather than letting an empty
                    page read as a clean bill"
            (is (empty? (findings-of doc "schema")))
            (is (empty? (findings-of doc "state")))
            (is (some #(re-find #"pre-existing drift" %)
                      (get-in doc [:data :notes])))
            (is (some #(re-find #"Probed as elena" %)
                      (get-in doc [:data :notes]))))

          (testing "the totals add up to the findings"
            (is (= (count (get-in doc [:data :findings]))
                   (reduce + 0 (vals (get-in doc [:data :totals]))))))))

      (testing "the collection grammar scopes it VERBATIM — a filter,
                never a flag"
        (let [[status doc] (get-json eng (str "/api/definitions/" did
                                              "/sweep?balance_gte=100"))]
          (is (= 200 status))
          (is (= 1 (get-in doc [:data :scanned])) "only the thick account")
          (is (empty? (findings-of doc "availability"))
              "and it is not the one that drifts")
          (is (= {:balance_gte "100"} (get-in doc [:data :filters]))))
        (testing "and an unknown parameter refuses as the collection does"
          (is (= 422 (first (get-json eng (str "/api/definitions/" did
                                               "/sweep?nonsense=1")))))))

      (testing "the door: a proposal reports, everything else refuses,
                and a definition that is not there is not there"
        (let [[status doc] (get-json eng (str "/api/definitions/" did "/sweep"))]
          (is (= 200 status))
          (is (= "law_sweep" (:kind doc)))
          (is (= (str "/api/definitions/" did "/sweep") (:self doc))))
        (let [cur (first (filter #(= "current" (:state %))
                                 (get-in (second (get-json eng "/api/definitions"))
                                         [:data :items])))
              [status doc] (get-json eng (str (:self cur) "/sweep"))]
          (is (= 409 status))
          (is (re-find #"Piloted, Proposed" (str (:detail doc)))))
        (is (= 404 (first (get-json eng "/api/definitions/nope/sweep"))))
        (testing "and an anonymous caller is told nothing at all"
          (is (= 404 (first (get-json eng (str "/api/definitions/" did "/sweep")
                                      {}))))))

      (testing "the resident code must BE the proposal — otherwise
                'under proposed' is a third law nobody proposed
                (definitions.clj's unported _resident_only, grown here
                because here it is fatal)"
        (let [other (assoc-in rdef [:fingerprint-hash] "not-the-proposal")]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"resident code no longer expresses"
               (law-sweep/sweepable!
                (assoc eng :registry (atom {:kinds {:s_acct other
                                                    :definition (get (inv/resources eng) :definition)}}))
                {:id did :state :proposed
                 :data {:target_kind "s_acct" :revision 2
                        :fingerprint_hash (:fingerprint-hash rdef)}}))))))))

;; ── adoption is the reason a sweep can be honestly empty ────────────

(deftest a-never-adopting-kind-says-why-nothing-drifts
  (let [st (memory/storage)
        keeper (fn [threshold]
                 (assoc (acct threshold) :adoption :never))]
    (boot st [(keeper 100)] :promote)
    (let [eng (boot st [(keeper 100)] :promote)]
      (inv/create! eng :s_acct {:name "thin" :balance 60} {:principal elena}))
    (let [eng (boot st [(keeper 50)] :propose)
          did (proposal-id eng :s_acct)
          [status doc] (get-json eng (str "/api/definitions/" did "/sweep"))]
      (is (= 200 status))
      (is (= "never" (get-in doc [:data :adoption])))
      (is (empty? (findings-of doc "availability"))
          "a grandfathering kind re-judges nothing on promote")
      (is (some #(re-find #"adopts :never" %) (get-in doc [:data :notes]))
          "and the report says why the page is empty rather than
           letting the emptiness speak"))))
