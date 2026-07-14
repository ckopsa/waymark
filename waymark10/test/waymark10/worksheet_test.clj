(ns waymark10.worksheet-test
  "The worksheet round-trip: export carries the filtered view with
  its identity columns; import diffs cells against stored rows and
  replays the diffs through the kind's OWN actions (push-on-write
  rides along), refuses stale versions as conflicts, notes read-only
  edits, creates from id-less rows (create-push claims the mint), and
  a dry run plans everything while applying nothing. Needs the test
  database: WAYMARK10_TEST_DSN=…waymark10_test."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
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

(defn- with-worksheet-engine [f]
  (let [rm (remote)]
    (db/with-test-engine
      [(note-kind rm)]
      (fn [eng] (f (mirror/with-push eng) rm)))))

(defn- export-rows [eng params]
  (let [rdef (get (inv/resources eng) :note)
        res (worksheet/export eng rdef params)]
    (is (= 200 (:status res)))
    (xlsx/read-sheet (:body res))))

(defn- import-rows [eng rows & [opts]]
  (let [rdef (get (inv/resources eng) :note)]
    (worksheet/import! eng rdef (xlsx/write-sheet rows)
                       (merge {:principal ana} opts))))

(defn- col
  "The value under a header in one exported row."
  [sheet row-i header]
  (let [idx (some (fn [[i h]] (when (= header h) i))
                  (map-indexed vector (first sheet)))]
    (get-in sheet [row-i idx])))

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
        (let [titles (set (map #(col sheet % "title") [1 2]))]
          (is (= #{"One" "Two"} titles)))
        (is (every? #(some? (col sheet % "id")) [1 2]))
        (is (every? #(number? (col sheet % "version")) [1 2]))))))

(deftest the-round-trip-applies-edits-through-the-actions
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs
             {"n1" {:title "One" :score 1.5M :done_at nil :author "feed"}
              "n2" {:title "Two" :score 2M :done_at "2026-01-05T00:00:00Z"
                    :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            edit (fn [row-i header v]
                   (let [idx (some (fn [[i h]] (when (= header h) i))
                                   (map-indexed vector (first sheet)))]
                     [row-i idx v]))
            n1-i (if (= "One" (col sheet 1 "title")) 1 2)
            n2-i (- 3 n1-i)
            edited (reduce (fn [s [r c v]] (assoc-in s [r c] v))
                           sheet
                           [(edit n1-i "title" "One, renamed")
                            (edit n1-i "score" 3.25)
                            (edit n1-i "done_at" "2026-02-01")
                            (edit n2-i "done_at" nil)])
            report (import-rows eng edited)
            outcomes (into {} (map (juxt :line :outcome))
                           (get-in report [:data :rows]))]
        (is (= "applied" (get outcomes (inc n1-i))))
        (is (= "applied" (get outcomes (inc n2-i))))
        (let [n1 (row-of eng "n1")
              n2 (row-of eng "n2")]
          (is (= "One, renamed" (get-in n1 [:data :title])))
          (is (= 3.25M (get-in n1 [:data :score])))
          (is (= "2026-02-01T00:00:00Z" (str (get-in n1 [:data :done_at])))
              "a bare date cell lands as UTC midnight through :on-set")
          (is (nil? (get-in n2 [:data :done_at]))
              "a blanked cell rides :on-clear"))
        (testing "every edit pushed back to the authority"
          (is (= "One, renamed" (get-in @(:state rm) [:docs "n1" :title]))))
        (testing "re-importing the same file is all unchanged"
          (let [sheet2 (export-rows eng {})
                report2 (import-rows eng sheet2)]
            (is (every? #(= "unchanged" (:outcome %))
                        (get-in report2 [:data :rows])))))))))

(deftest stale-versions-conflict-and-read-only-cells-note
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs {"n1" {:title "One" :score nil
                                            :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            id (col sheet 1 "id")]
        ;; the row moves after the export
        (inv/invoke! eng :note id :retitle {:title "Moved"} {:principal ana})
        (testing "a stale sheet edit is a conflict, not a lost update"
          (let [edited (assoc-in sheet [1 3] "From the sheet")
                report (import-rows eng edited)
                row (first (get-in report [:data :rows]))]
            (is (= "conflict" (:outcome row)))
            (is (= "Moved" (get-in (row-of eng "n1") [:data :title])))))
        (testing "a read-only cell that moved is a note, never a write"
          (let [sheet (export-rows eng {})
                author-idx 6
                report (import-rows eng (assoc-in sheet [1 author-idx] "me"))
                row (first (get-in report [:data :rows]))]
            (is (= "unchanged" (:outcome row)))
            (is (some #(re-find #"read-only" %) (:notes row)))
            (is (= "feed" (get-in (row-of eng "n1") [:data :author])))))))))

(deftest id-less-rows-create-and-claim
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs {"n1" {:title "One" :score nil
                                            :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            new-row [nil nil nil "Born offline" 4.5 "2026-03-01" nil]
            report (import-rows eng (conj sheet new-row))
            row (last (get-in report [:data :rows]))]
        (is (= "created" (:outcome row)))
        (is (= ["create" "finish"] (:actions row))
            "the :on-set cell applies as an ordinary edit on the fresh row")
        (let [minted (row-of eng "r-1")]
          (is (some? minted) "the authority minted and the claim stamped")
          (is (= "Born offline" (get-in minted [:data :title])))
          (is (= "local" (get-in minted [:data :author]))
              "the birth hook ran")
          (is (= "2026-03-01T00:00:00Z"
                 (str (get-in minted [:data :done_at])))))
        (testing "the created row round-trips as unchanged"
          (let [report2 (import-rows eng (export-rows eng {}))]
            (is (every? #(= "unchanged" (:outcome %))
                        (get-in report2 [:data :rows])))))))))

(deftest dry-run-plans-and-applies-nothing
  (with-worksheet-engine
    (fn [eng rm]
      (swap! (:state rm) assoc :docs {"n1" {:title "One" :score nil
                                            :done_at nil :author "feed"}})
      (mirror/discover! eng :note)
      (let [sheet (export-rows eng {})
            edited (-> (assoc-in sheet [1 3] "Would rename")
                       (conj [nil nil nil "Would create" nil nil nil]))
            report (import-rows eng edited {:dry-run true})
            [r1 r2] (get-in report [:data :rows])]
        (is (true? (:dry_run report)))
        (is (= "planned" (:outcome r1)))
        (is (= ["retitle"] (:actions r1)))
        (is (= "planned" (:outcome r2)))
        (is (= ["create"] (:actions r2)))
        (is (= "One" (get-in (row-of eng "n1") [:data :title])))
        (is (nil? (row-of eng "r-1")))))))

(deftest worksheets-refuse-what-they-do-not-declare
  (with-worksheet-engine
    (fn [eng _rm]
      (testing "an upload with no id column names the mistake"
        (let [e (try (import-rows eng [["title"] ["x"]])
                     nil
                     (catch Exception e e))]
          (is (= 422 (:status (ex-data e))))
          (is (re-find #"names no id column"
                       (str (get-in (ex-data e) [:errors :id]))))))
      (testing "a body that is not a workbook refuses 400"
        (let [rdef (get (inv/resources eng) :note)]
          (is (thrown-with-msg?
               Exception #"not an xlsx workbook"
               (worksheet/import! eng rdef (byte-array [1 2 3])
                                  {:principal ana}))))))))
