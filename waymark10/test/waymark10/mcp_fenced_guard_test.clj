(ns waymark10.mcp-fenced-guard-test
  "A guard's refusal at the MCP door is the guard's own 409, not a 412
  (ticket ec814cac).

  SEEN THROUGH THE CONNECTOR. `waymark_invoke` complete on a parent
  ticket with one unfinished child answered 412 \"Version conflict:
  the resource changed since you read it. Re-read and retry with the
  current etag\" — on a row read fresh a moment before, which had not
  changed, with and without dry_run. That document's remedy is a loop
  an agent can walk for ever: re-read, retry, 412 again. The
  envelope's `unavailable` block named the real reason all along.

  THE CAUSE IS THE DOOR, NOT THE GUARD. `waymark_invoke` reads the row
  first and takes the action's entry out of the envelope's `actions`
  block; `invoke-headers` sends If-Match only when THAT entry says the
  door is fenced. A door a guard shut is not in `actions` at all — it
  is in `unavailable`, which carries a reason, remedies and no
  `safety` — so the entry was nil, no If-Match went out, and invoke's
  fence (step 6, ahead of the guard loop) refused the write before any
  guard could speak.

  What this suite holds: the fence flag falls back to the DECLARATION,
  so a shut fenced door answers with the guard — its name, its
  sentence, its remedies — dry_run included; and a genuinely stale
  etag still answers 412, because the fence is still real.

  Memory storage, the real routes through the in-process door, no
  database — mcp_return_test's arrangement."
  ;; `defguard` and `defhandler` are defns; the sentence that says so
  ;; ships in waymark10/resources/clj-kondo.exports, which a lint run
  ;; from THIS project's directory reads and one from the repo root
  ;; does not — so say it here too, and the file lints from either.
  {:clj-kondo/config '{:lint-as {waymark10.guards/defguard clojure.core/defn
                                 waymark10.resource/defhandler clojure.core/defn}}}
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world: one ending, fenced, with a guard that shuts it ───────

;; The ticket's `children-are-finished`, with the count on the row
;; rather than in a child collection: one guard, one deny, remedies.
(g/defguard steps-are-finished
  {:vars [:count]
   :remedies [:mg_job/finish_steps]
   :explain "{count} of this job's steps are not finished. A job ends after its steps: finish them first, and then this door opens."}
  [row _inp _ctx]
  (let [waiting (long (or (get-in row [:data :open_steps]) 0))]
    (if (pos? waiting)
      (t/deny {:vars {:count waiting}})
      (t/allow))))

(defhandler finish-steps [row _inp _ctx]
  (assoc-in row [:data :open_steps] 0))

(defhandler close-the-job [row inp _ctx]
  (assoc-in row [:data :note] (:note inp)))

(def ^:private job
  (r/resource
   {:kind :mg_job
    :plural "mg_jobs"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 120}]]
             [:open_steps {:optional true} [:maybe [:int {:min 0 :max 500}]]]
             [:note {:optional true} [:maybe [:string {:max 480}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish_steps
     {:from #{:open} :to :open
      :handler finish-steps
      :safety {:idempotent true :reversible true :confirm false}}
     ;; the ticket's own ending: guarded, and fenced — there by way of
     ;; its :edit, which implies the fence; here said outright
     :complete
     {:from #{:open} :to :done
      :input [:map [:note [:string {:min 1 :max 480}]]]
      :guards [steps-are-finished]
      :handler close-the-job
      :safety {:idempotent true :reversible false :confirm false :fence true
               :one-way "This is the ending on the record, with its sentence."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private opts {:principal elena})

(defn- boot []
  (engine/engine {:storage (memory/storage) :resources [job]}))

(defn- create! [eng data]
  (:id (:row (inv/create! eng :mg_job data opts))))

(defn- tool [eng tool-name args]
  (mcp/call-tool eng (mcp/door eng) {:principal elena} tool-name args))

(defn- doc [out] (wire/read-json (get-in out [:content 0 :text])))

;; ── 1 · the shut door answers with its guard ─────────────────────

(deftest a-guard-shut-fenced-door-answers-409-and-not-a-version-conflict
  (let [eng (boot)
        id (create! eng {:name "ship it" :open_steps 2})]

    (testing "the envelope shuts the door and says why"
      (let [env (doc (tool eng "waymark_get" {:kind "mg_job" :id id}))]
        (is (not (contains? (:actions env) :complete))
            "the guard shut it, so it is not among the doors")
        (is (re-find #"not finished"
                     (str (get-in env [:unavailable :complete :reason]))))))

    (doseq [[label extra] [["straight" {}]
                           ["with dry_run" {:dry_run true}]]]
      (testing (str "waymark_invoke complete, " label)
        (let [out (tool eng "waymark_invoke"
                        (merge {:kind "mg_job" :id id :action "complete"
                                :input {:note "shipped"}}
                               extra))
              d (doc out)]
          (is (true? (:isError out)))
          (is (= 409 (:status d))
              "the guard's refusal, not the fence's 412")
          (is (re-find #"guard-refused" (str (:type d))))
          (is (re-find #"2 of this job's steps" (str (:detail d)))
              "the guard's sentence, with its count")
          (is (re-find #"steps" (str (:guard d)))
              "the refusal names the guard that shut the door")
          (is (re-find #"finish" (pr-str (:remedies d)))
              "and carries the remedies it declared"))))

    (testing "and neither refusal moved the row"
      (is (= "open" (:state (doc (tool eng "waymark_get"
                                       {:kind "mg_job" :id id}))))))))

;; ── 2 · the fence is still real ───────────────────────────────

(deftest a-genuinely-stale-etag-still-answers-412
  (let [eng (boot)
        id (create! eng {:name "ship it" :open_steps 0})]
    (testing "the guard allows, and the stale write is refused at the fence"
      (let [e (try (inv/invoke! eng :mg_job id :complete {:note "shipped"}
                                (assoc opts :if-match "W/\"stale\""))
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "a stale If-Match refuses")
        (is (= 412 (:status (ex-data e))))
        (is (= :version-conflict (:waymark10/problem (ex-data e))))))
    (testing "and the door, carrying the etag it read, goes through"
      (let [out (tool eng "waymark_invoke" {:kind "mg_job" :id id
                                            :action "complete"
                                            :input {:note "shipped"}})]
        (is (not (:isError out)))
        (is (= "done" (:state (doc out))))))))
