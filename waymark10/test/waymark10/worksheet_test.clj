(ns waymark10.worksheet-test
  "The worksheet resource: an upload STAGES as a row of the engine's
  worksheet kind — the plan is recorded data on it before the 201
  renders — and revalidate / apply / discard are its own actions.
  Apply replays every line through the target kind's actions as the
  person who applied (push-on-write rides along); stale versions
  conflict, read-only edits become notes, id-less lines create (a
  create-push mirror claims its mint). Needs the test database:
  WAYMARK10_TEST_DSN=…waymark10_test."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.worksheet :as worksheet]
            [waymark10.server.xlsx :as xlsx]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ana (t/principal {:id "ana" :type :human :display "Ana"}))

(defn- etag-of [doc] (wire/sha256-hex (pr-str (into (sorted-map) doc))))

(defrecord MintingRemote [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (if-some [doc (get-in @state [:docs xid])]
      [doc (etag-of doc)]
      (throw (ex-info (str xid " is gone") {}))))
  (pull-many [_ xids]
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (etag-of doc)]])))
          xids))
  (push [_ xid document]
    (swap! state update :pushes (fnil inc 0))
    (swap! state assoc-in [:docs xid] document)
    (etag-of document))
  mirror/MirrorCreateAdapter
  (push-create [_ document]
    (let [xid (str "r-" (:minted (swap! state update :minted (fnil inc 0))))]
      (swap! state assoc-in [:docs xid] document)
      [xid (etag-of document)])))

(defn- remote [] (->MintingRemote (atom {:docs {}})))

(defhandler retitle-handler [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(defhandler rescore-handler [row inp _ctx]
  (assoc-in row [:data :score] (:score inp)))

(defhandler finish-handler [row inp ctx]
  (assoc-in row [:data :done_at] (or (:done_at inp) (:now ctx))))

(defhandler unfinish-handler [row _inp _ctx]
  (assoc-in row [:data :done_at] nil))

(def ^:private writable #{:fresh :stale :unreachable})
(def ^:private quiet {:idempotent true :reversible false :confirm false
                      :one-way "The prior value is on the audit trail."})

(defn- note-kind [adapter]
  (r/resource
   (mirror/declaration
    {:kind :note
     :summary "{data.title} · {state}"
     :schema [:map
              [:title {:optional true} [:maybe [:string {:max 120}]]]
              [:score {:optional true} [:maybe :decimal]]
              [:done_at {:optional true} [:maybe :waymark/instant]]
              [:author {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 120}]]]]
     :filterable {:state #{:eq}}
     :create-schema [:map
                     [:title [:string {:min 1 :max 120}]]
                     [:score {:optional true} [:maybe :decimal]]]
     :on-create (fn [row _ctx] (assoc-in row [:data :author] "local"))
     :worksheet {:columns [{:field :title :action :retitle}
                           {:field :score :action :rescore}
                           {:field :done_at
                            :on-set {:action :finish :param :done_at}
                            :on-clear {:action :unfinish}}
                           {:field :author}]
                 :create true}
     :actions
     {:retitle {:from writable :to :fresh
                :input [:map [:title [:string {:min 1 :max 120}]]]
                :safety quiet :handler retitle-handler
                :display {:label "Retitle"}}
      :rescore {:from writable :to :fresh
                :input [:map [:score [:maybe :decimal]]]
                :safety quiet :handler rescore-handler
                :display {:label "Rescore"}}
      :finish {:from writable :to :fresh
               :input [:map [:done_at {:optional true}
                             [:maybe :waymark/instant]]]
               :safety quiet :handler finish-handler
               :display {:label "Finish"}}
      :unfinish {:from writable :to :fresh
                 :safety quiet :handler unfinish-handler
                 :display {:label "Unfinish"}}}}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600
     :push-on-write true :create-push true})))

(defn- with-worksheet-engine
  "The FULL boot (engine/engine — the worksheet kind and its pass are
  the engine's own wiring, which the low-level test engine skips),
  fresh tables, the push pass wrapped exactly as an app would."
  [f]
  (let [rm (remote)
        st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["notes" "worksheets" "definitions"
                         "waymark10_transitions" "waymark10_idempotency"
                         "waymark10_drafts" "waymark10_cursors"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (mirror/with-push
                 (engine/engine {:storage st
                                 :resources [(note-kind rm)]
                                 :suppress-mirror-refresh true}))]
        (f eng rm))
      (finally (pg/close! st)))))

(defn- note-rdef [eng] (get (inv/resources eng) :note))

(defn- export-rows [eng params]
  (let [res (worksheet/export eng (note-rdef eng) params)]
    (is (= 200 (:status res)))
    (xlsx/read-sheet (:body res))))

(defn- stage! [eng rows & [filename]]
  (:row (worksheet/stage! eng (note-rdef eng) (xlsx/write-sheet rows)
                          {:principal ana :filename filename})))

(defn- fresh-key [] (str (random-uuid)))

(defn- apply! [eng ws-id]
  (:row (inv/invoke! eng :worksheet ws-id :apply nil
                     {:principal ana :idempotency-key (fresh-key)})))

(defn- report-of [row] (get-in row [:data :report]))

(defn- by-line [row]
  (into {} (map (juxt :line identity)) (report-of row)))

(defn- col
  [sheet row-i header]
  (let [idx (some (fn [[i h]] (when (= header h) i))
                  (map-indexed vector (first sheet)))]
    (get-in sheet [row-i idx])))

(defn- set-cell [sheet row-i header v]
  (let [idx (some (fn [[i h]] (when (= header h) i))
                  (map-indexed vector (first sheet)))]
    (assoc-in sheet [row-i idx] v)))

(defn- row-of [eng xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (first (store/query-rows (:storage eng) tx :note
                               {:external_id xid} {:limit 1})))))

(deftest export-carries-the-view-and-its-identity
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score 1.5M :done_at nil :author "feed"}
              "n2" {:title "Two" :score nil :done_at "2026-01-05T00:00:00Z"
                    :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})]
        (is (= ["id" "version" "state" "title" "score" "done_at" "author"]
               (first sheet)))
        (is (= 3 (count sheet)))
        (is (= #{"One" "Two"} (set (map #(col sheet % "title") [1 2]))))
        (is (every? #(some? (col sheet % "id")) [1 2]))
        (is (every? #(number? (col sheet % "version")) [1 2]))))))

(deftest an-upload-stages-as-a-planned-resource
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score 1.5M :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            edited (set-cell sheet 1 "title" "One, renamed")
            ws (stage! eng edited "notes.xlsx")]
        (is (= :staged (:state ws)))
        (is (= "note" (get-in ws [:data :target])))
        (is (= "notes.xlsx" (get-in ws [:data :filename])))
        (testing "the 201's row already carries the recorded plan"
          (is (= "planned" (:outcome (get (by-line ws) 2))))
          (is (= ["retitle"] (:actions (get (by-line ws) 2))))
          (is (= {:planned 1} (get-in ws [:data :tally]))))
        (testing "staging applied nothing"
          (is (= "One" (get-in (row-of eng "n1") [:data :title]))))
        (testing "discard abandons the batch, audited"
          (let [{:keys [row]} (inv/invoke! eng :worksheet (:id ws) :discard
                                           nil {:principal ana})]
            (is (= :discarded (:state row)))
            (is (= "One" (get-in (row-of eng "n1") [:data :title])))))))))

(deftest apply-replays-the-lines-and-records-the-outcomes
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score 1.5M :done_at nil :author "feed"}
              "n2" {:title "Two" :score 2M :done_at "2026-01-05T00:00:00Z"
                    :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            n1-i (if (= "One" (col sheet 1 "title")) 1 2)
            n2-i (- 3 n1-i)
            edited (-> sheet
                       (set-cell n1-i "title" "One, renamed")
                       (set-cell n1-i "score" 3.25)
                       (set-cell n1-i "done_at" "2026-02-01")
                       (set-cell n2-i "done_at" nil))
            ws (stage! eng edited)
            applied (apply! eng (:id ws))]
        (is (= :applied (:state applied)))
        (is (some? (get-in applied [:data :applied_at])))
        (let [lines (by-line applied)]
          (is (= "applied" (:outcome (get lines (inc n1-i)))))
          (is (= #{"retitle" "rescore" "finish"}
                 (set (:actions (get lines (inc n1-i))))))
          (is (= ["unfinish"] (:actions (get lines (inc n2-i))))))
        (let [n1 (row-of eng "n1")]
          (is (= "One, renamed" (get-in n1 [:data :title])))
          (is (= 3.25M (get-in n1 [:data :score])))
          (is (= "2026-02-01T00:00:00Z" (str (get-in n1 [:data :done_at])))))
        (is (nil? (get-in (row-of eng "n2") [:data :done_at])))
        (testing "the target writes rode the push pass"
          (is (= "One, renamed" (get-in @(:state rm) [:docs "n1" :title]))))
        (testing "the audit names the person, not the runner"
          (let [ts (store/with-tx (:storage eng)
                     (fn [tx] (store/transitions
                               (:storage eng) tx
                               {:kind :note
                                :resource-id (:id (row-of eng "n1"))}
                               {})))]
            (is (some #(and (= "retitle" (name (:action %)))
                            (= "ana" (get-in % [:actor :id])))
                      ts))))
        (testing "an applied worksheet is terminal — no further applies"
          (let [e (try (apply! eng (:id ws)) nil (catch Exception e e))]
            (is (= 409 (:status (ex-data e))))))
        (testing "re-staging the applied view plans all-unchanged"
          (let [ws2 (stage! eng (export-rows eng {}))]
            (is (every? #(= "unchanged" (:outcome %)) (report-of ws2)))))))))

(deftest stale-lines-conflict-and-revalidate-re-plans
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score nil :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            ws (stage! eng (set-cell sheet 1 "title" "From the sheet"))]
        ;; the row moves after the staging
        (inv/invoke! eng :note (col sheet 1 "id") :retitle {:title "Moved"}
                     {:principal ana})
        (testing "revalidate sees the world as it stands now"
          (let [{:keys [row]} (inv/invoke! eng :worksheet (:id ws) :revalidate
                                           nil {:principal ana
                                                :idempotency-key (fresh-key)})]
            (is (= :staged (:state row)))
            (is (= "conflict" (:outcome (first (report-of row)))))))
        (testing "apply skips the conflicted line — never a lost update"
          (let [applied (apply! eng (:id ws))]
            (is (= "conflict" (:outcome (first (report-of applied)))))
            (is (= "Moved" (get-in (row-of eng "n1") [:data :title])))))))))

(deftest id-less-lines-create-and-claim
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score nil :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            good [nil nil nil "Born offline" 4.5 "2026-03-01" nil]
            bad [nil nil nil nil 1 nil nil]      ; no title: the create door refuses
            ws (stage! eng (-> sheet (conj good) (conj bad)))
            plan (by-line ws)]
        (testing "the plan rehearses the create door — bad births refuse at staging"
          (is (= ["create" "finish"] (:actions (get plan 3))))
          (is (= "refused" (:outcome (get plan 4))))
          (is (re-find #"title" (str (:reason (get plan 4))))))
        (let [applied (apply! eng (:id ws))
              lines (by-line applied)]
          (is (= "created" (:outcome (get lines 3))))
          (is (= ["create" "finish"] (:actions (get lines 3))))
          (is (= "refused" (:outcome (get lines 4))))
          (let [minted (row-of eng "r-1")]
            (is (some? minted) "the authority minted and the claim stamped")
            (is (= "Born offline" (get-in minted [:data :title])))
            (is (= "local" (get-in minted [:data :author])))
            (is (= "2026-03-01T00:00:00Z"
                   (str (get-in minted [:data :done_at]))))))))))

(deftest the-json-door-is-the-same-door
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score nil :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [n1 (row-of eng "n1")
            {:keys [row]} (inv/create!
                           eng :worksheet
                           {:target "note"
                            :lines [{:line 2 :id (str (:id n1))
                                     :version (:version n1)
                                     :cells {:title "Via JSON"}}]}
                           {:principal ana})]
        (is (= "planned" (:outcome (first (report-of row))))
            "a bare JSON create plans exactly like an upload")
        (let [applied (apply! eng (:id row))]
          (is (= "applied" (:outcome (first (report-of applied)))))
          (is (= "Via JSON" (get-in (row-of eng "n1") [:data :title]))))))))

(deftest worksheets-refuse-what-they-do-not-declare
  (with-worksheet-engine
    (fn [eng _rm]
      (testing "an upload with no id column names the mistake"
        (let [e (try (stage! eng [["title"] ["x"]])
                     nil
                     (catch Exception e e))]
          (is (= 422 (:status (ex-data e))))
          (is (re-find #"names no id column"
                       (str (get-in (ex-data e) [:errors :id]))))))
      (testing "a body that is not a workbook refuses 400"
        (is (thrown-with-msg?
             Exception #"not an xlsx workbook"
             (worksheet/stage! eng (note-rdef eng) (byte-array [1 2 3])
                               {:principal ana})))))))
