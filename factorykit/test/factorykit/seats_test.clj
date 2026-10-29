(ns factorykit.seats-test
  "The seat templates (ticket 64543127): each one reads, names every
  hole it has, and fills into a map the seat kind's create door takes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.gate-proxy :as gate]))

(def ^:private names ["draft-judge" "code-seat" "ops-seat" "sitting-judge" "mayor" "onboarding" "director"])

(def ^:private judgment-names ["draft-ticket" "sitting"])

(defn- template [n]
  (edn/read-string (slurp (io/resource (str "seats/" n ".edn")))))

(defn- judgment-template [n]
  (edn/read-string (slurp (io/resource (str "judgments/" n ".edn")))))

(defn- holes [s]
  (set (map (comp keyword second) (re-seq #"\{\{([a-z_]+)\}\}" s))))

(defn- filled
  "The template's seat with every hole its params name filled."
  [{:keys [template seat]}]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") "owner/repo"))
           (pr-str seat)
           (keys (:params template)))))

(defn- filled-judgment
  "The judgment template's judgment with every hole its params name filled."
  [{:keys [template judgment]}]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") "owner/repo"))
           (pr-str judgment)
           (keys (:params template)))))

(defn- unfilled [x]
  (re-seq #"\{\{[^}]*\}\}" (pr-str x)))

(deftest every-template-fills-with-no-hole-left
  (doseq [n names]
    (testing n
      (is (empty? (unfilled (filled (template n)))))))
  (doseq [n judgment-names]
    (testing n
      (is (empty? (unfilled (filled-judgment (judgment-template n))))))))

(deftest every-judgment-template-fills-into-a-judgment-the-kind-takes
  (doseq [n judgment-names
          :let [t (judgment-template n)
                j (filled-judgment t)
                words (map :name (:verdicts j))]]
    (testing n
      (is (= (holes (pr-str (:judgment t))) (set (keys (:params (:template t)))))
          "every hole is a param, and every param a hole")
      (is (<= 1 (count (:name j)) 120))
      (is (seq (:subject_kind j)))
      (is (<= 1 (count words) 12) "between one and twelve verdicts")
      (is (apply distinct? words) "the verdict names are distinct")
      (doseq [{w :name s :sentence} (:verdicts j)]
        (is (re-matches #"[a-z][a-z_]{0,39}" w) (str w " is one snake_case word"))
        (is (<= 1 (count s) 240) (str w "'s sentence")))
      (is (every? (set words) (:files_ticket_on j))
          "a ticket is filed only on one of its verdicts")
      (is (<= 40 (:remedy_max j) 1000))
      (is (<= (count (:notes j)) 1200)))))

(deftest every-verdict-a-judge-seat-names-is-in-its-judgment
  (is (= #{"draft-judge" "sitting-judge"}
         (set (filter #(get-in (template %) [:template :judgment_template]) names))))
  (doseq [n names
          :let [t (template n)
                jn (get-in t [:template :judgment_template])]
          :when jn]
    (testing n
      (let [seat (filled t)
            text (str (:charter seat) "\n" (:instructions seat))
            words (set (map :name (:verdicts (filled-judgment (judgment-template jn)))))]
        (is (seq (get-in t [:template :verdicts])))
        (doseq [v (get-in t [:template :verdicts])]
          (is (re-find (re-pattern (str "\\b" v "\\b")) text)
              (str v " is a verdict the seat names"))
          (is (contains? words v) (str v " is a verdict of " jn)))))))

(deftest every-template-fills-into-a-seat-the-seat-kind-takes
  (doseq [n names
          :let [t (template n)
                seat (filled t)]]
    (testing n
      (is (= (disj (holes (pr-str (:seat t))) :domain)
             (disj (set (keys (:params (:template t)))) :domain))
          "every hole is a param, and every param a hole; domain, the name, is declared beside domain_id even where no hole takes it")
      (is (= n (:name seat)))
      (is (<= 1 (count (:charter seat)) 1200) "the charter's cap")
      (is (<= (count (:instructions seat)) 2000) "the instructions' cap")
      (is (seq (:scope seat)))
      (is (every? #(and (string? (:kind %)) (vector? (:actions %))) (:scope seat)))
      (doseq [k [:standing_ttl_seconds :cadence_seconds
                 :budget_usd_per_week :sitting_budget_tokens]]
        (is (contains? seat k) (str "it states " (name k)))))))

(defn- fill
  "The template's seat with each hole filled from `values`."
  [{:keys [seat]} values]
  (edn/read-string
   (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") v))
              (pr-str seat)
              values)))

(deftest the-onboarding-seat-fits-its-caps-filled-with-a-real-org
  (doseq [org ["rd_tax_services" "dress_rehearsal"]
          :let [seat (fill (template "onboarding") {:org org})]]
    (testing org
      (is (<= 1 (count (:charter seat)) 1200) "the charter's cap")
      (is (<= (count (:instructions seat)) 2000) "the instructions' cap"))))

(deftest the-onboarding-seat-reads-the-org-grammar
  (let [seat (fill (template "onboarding") {:org "shop"})
        globs (fn [kind]
                (some #(when (= kind (:kind %))
                         (str/split (get-in % [:filter :path]) #","))
                      (:scope seat)))]
    (doseq [kind ["bench.read" "bench.find"]
            path ["docs/orgkit.md" "docs/spec-related-row-law.md"
                  "waymark10/src/waymark10/dsl.clj" "CLAUDE.md"
                  "orgs/shop" "orgs/shop/org.edn"]]
      (testing (str kind " " path)
        (is (some #(gate/path-glob-matches? % path) (globs kind)))))))

(def ^:private domain-names #{"draft-judge" "ops-seat" "sitting-judge" "mayor"})

(deftest every-domain-template-writes-the-domain-ref-and-not-in-domain
  (is (= domain-names
         (set (filter #(contains? (get-in (template %) [:template :params]) :domain) names))))
  (doseq [n names
          :let [t (template n)]]
    (testing n
      (is (not (contains? (:seat t) :in_domain))
          "in_domain is the seat kind's computed field, not one a create writes")
      (when (domain-names n)
        (is (contains? (get-in t [:template :params]) :domain_id))
        (is (= "{{domain_id}}" (get-in t [:seat :domain])))))))

(def ^:private house-repos
  "ckopsa/waymark,ckopsa/waymark-doors,ckopsa/waymark-bench,ckopsa/home-infrastructure,ckopsa/colton-tools")

(def ^:private factory-domain-id
  "A stand-in for the factory domain row's id: the live rows were read
  by their computed in_domain, which names the domain and not its id.
  factory10.seat-templates-test creates against a real domain row."
  "factory-domain-id")

(def ^:private house
  "This house's value for each hole of each template (ticket 9a7a6b1a)."
  {"draft-judge" {:repos house-repos
                  :judgment "6804d539-7365-4392-8c8e-d02cfde8f6c7"
                  :domain "factory"
                  :domain_id factory-domain-id}
   "ops-seat" {:repos house-repos
               :restart_jobs "clone-mcp,waymark-bench,github-runner"
               :domain "factory"
               :domain_id factory-domain-id}
   "sitting-judge" {:judgment "2d3adcf5-dae8-4411-a2a8-aebd884c0d56"
                    :domain "factory"
                    :domain_id factory-domain-id}})

(def ^:private live
  "The live seat rows' template fields, as the mayor read them on
  2026-10-14 (ticket 9a7a6b1a). The draft judge's instructions no longer
  spell its repositories, which would not fit the seat kind's 2000-character
  cap (ticket 56ea5929): each ticket names its own repo; its scope spells them."
  {"draft-judge"
       {:name "draft-judge" :mode "fired" :walk "ticket"
        :charter "You judge whether a draft ticket is ready to be groomed, then act on your verdict. For each draft the sit hands you, read its title, detail, type, repository, parent and history. Look for the same ask elsewhere: scan the titles of tickets in its repository across states. Choose the one verdict whose sentence is true; when two are true, choose the one that asks the least of the person. Write one remedy sentence naming the other ticket by id where there is one. Then act. Filers land at P4 and P4 never enters the queue; you decide whether it earns more. ready: when it names a failure that happened on a path the house uses, or blocks a goal the owner set, groom it at P3 (P2 only when that failure stops work now); otherwise it stays a P4 draft. A follow-up whose ticket is still open, blocked or in review is blocked on it. already_done or duplicate: drop it naming what settles it. stale: drop it saying what moved on; a refinement that held nothing up is stale. Leave needs_person, needs_restate and epic in draft. needs_person: a credential, a host step, a grant or power, anything that speaks for the person or moves money, and a cracks-audit note (aged:, silent:, orphan:) for the mayor."
        :instructions "You sit in `draft-judge`. The sit answers the charter, the judgment (verdicts and their sentences) and up to ten drafts.\n\nFor each ticket:\n1. Read it with waymark_get kind \"ticket\" and its full id. For the same ask: there is no title search; waymark_query kind \"ticket\", filter {\"state\": \"open,draft,blocked,in_review,done\", \"repo\": its repo}, fields [\"title\"], return \"summary\", scan titles; read a matching done one's close_reason.\n2. A follow-up builds on another ticket: its found_in, or a ticket its detail says it follows or extends. Read that ticket. If it is open, blocked or in review, the follow-up is blocked on it. Not needs_person if 1 or 2 finds a duplicate, done work or open found_in.\n3. Before ready or needs_restate, read main for what it names, through waymark_power (ToolSearch \"select:mcp__Waymark__waymark_power\"): bench__prepare once per ticket repo, then bench__find (mode \"grep\") and bench__read (offset, limit), all with branch \"read/draft-judge\" (main itself is refused). For a CI flake, read .github/workflows too. If main already does it, it is already_done. If the code lives in another repository, it is needs_restate, naming it. If main cannot be read, say so and call it neither.\n4. Judge: waymark_invoke kind \"verdict\", action \"judge\", no id, input {subject_kind: \"ticket\", subject_id: full id, verdict, remedy: one sentence}.\n5. Act by the charter: groom (input {priority: 3}, or 2) for a ready ticket that earns it; block (input {blocked_by: [full id], then: \"open\"} for a P0-P3 ticket, then: \"draft\" for a P4); or drop (input {close_reason: one sentence}). needs_person, needs_restate, epic and an unearned P4 get no act.\n\nWhen a door refuses, leave the ticket and say so in the note. When halted or parked, say why and stop. If a routine-fire-payload names a row id, walk that row and stop. When the Stop hook asks you to close the sitting, make that one call. In the note, list each ticket id with its verdict and act; take counts from that list."
        :scope [{:kind "change" :actions []}
                {:kind "verdict" :actions ["judge"]}
                {:kind "bench.find" :actions [] :filter {:repo house-repos}}
                {:kind "bench.read" :actions [] :filter {:repo house-repos}}
                {:kind "ticket" :actions ["groom" "block" "drop"]}]
        :delivers [{:kind "verdict" :action "judge"}]
        :judgment "6804d539-7365-4392-8c8e-d02cfde8f6c7"
        :cadence_seconds 21600 :fire_interval_seconds 300
        :rows_per_firing 10 :max_open_sittings 1
        :sitting_budget_tokens 400000 :budget_usd_per_week 30M
        :sitting_idle_seconds 3600 :keep_transcripts "fired" :transcript_days 30
        :domain factory-domain-id}
       "ops-seat"
       {:name "ops-seat" :mode "fired" :walk "ci_run"
        :charter "You keep the house's CI honest. For each red CI run the sit hands you, decide what broke it from its log excerpt, its change and its head commit. Take classify_this_change when the change itself is wrong, naming the file or line to fix; classify_base_red when the base branch was already red and this change is not the cause, which the door takes only while the base is read red; classify_infra when a runner, cache, network or registry broke and no test ran; supersede when its commit is no longer its change's head. After classify_infra, re-run the failed job once, and when a house job it depends on is down, read it with nomad.read and restart it only if the restart allowlist names it. Never re-run a run you classified this_change or base_red, never re-run a job twice, and never touch a job the allowlist does not name. When one infra break or red base holds more than one run, or only a stop, a variable or a command inside a job would fix it, file one draft ticket saying what is broken and what would fix it, and leave that fix to a person. You never edit code, merge, dispatch, or read or write a secret."
        :instructions "You sit in `ops-seat`. The sit answers the charter and up to ten red ci_run rows, oldest first.\n\nFor each run: read log_excerpt (the end first) and check_name. Read its change with waymark_get kind \"change\" (head_sha, state). If the run's head_sha is not the change's head_sha, or the change is merged or closed, take supersede. Otherwise take one classify door with a remedy of one sentence that says the act.\n\nAfter classify_infra: re-run with waymark_power bench__rerun once. When a house job looks down, read it with nomad__job_status or nomad__alloc_logs, and restart it with nomad__job_restart only when nomad__restart_allowlist names it. Bench and nomad tools go through waymark_power; load it with ToolSearch \"select:mcp__Waymark__waymark_power\".\n\nFollow-ups: waymark_invoke kind \"ticket\", action \"create\", no id, input {title, detail, type: \"bug\", priority: 2, repo}. One ticket per cause, not per run; query open and draft tickets first so you do not file it twice.\n\nIds are full uuids. Do not call discover or schema. When halted or parked, say why and stop. When the Stop hook asks you to close the sitting, make that one call with its numbers. In the note, give the count of each classification, supersedes, re-runs, restarts and tickets filed."
        :scope [{:kind "ci_run" :actions ["classify_infra" "classify_base_red" "classify_this_change" "supersede"]}
                {:kind "change" :actions []}
                {:kind "ticket" :actions ["create"]}
                {:kind "nomad.read" :actions []}
                {:kind "nomad.restart" :actions [] :filter {:job "clone-mcp,waymark-bench,github-runner"}}
                {:kind "bench.rerun" :actions [] :filter {:repo house-repos}}
                {:kind "bench.log" :actions []}
                {:kind "bench.run_status" :actions []}]
        :delivers [{:kind "ci_run" :action "classify_infra"}
                   {:kind "ci_run" :action "classify_base_red"}
                   {:kind "ci_run" :action "classify_this_change"}
                   {:kind "ci_run" :action "supersede"}]
        :judgment nil
        :cadence_seconds 3600 :fire_interval_seconds 300
        :rows_per_firing 10 :max_open_sittings 1
        :sitting_budget_tokens 400000 :budget_usd_per_week 30M
        :sitting_idle_seconds 3600 :keep_transcripts "fired" :transcript_days 30
        :domain factory-domain-id}
       "sitting-judge"
       {:name "sitting-judge" :mode "fired" :walk "sitting"
        :charter "You judge how a fired seat sat, from what it left behind. For each sitting the sit hands you, read the sitting row (its seat, note, counts, refusals and served calls), then its transcript: the transcript row for that sitting, then its entries in order, the assistant's words and every entry marked is_error first. Read what the sitting was asked to do from the row it walked, named in the transcript. Choose the one verdict whose sentence is true of this sitting; when two are true, choose the one a person must act on first. Write one remedy sentence: the fix, or the ticket that already carries it, by id. Judge only what the transcript shows. Do not guess at what a run meant."
        :instructions "You sit in `sitting-judge`. The sit answers the charter, the judgment (its verdicts, each with its sentence, and the remedy ceiling) and up to five closed fired sittings to judge.\n\nFor each sitting: read it with waymark_get kind \"sitting\". Find its transcript with waymark_query kind \"transcript\" filter {\"sitting\": <id>}. Read its entries with waymark_query kind \"transcript_entry\" filter {\"transcript\": <id>}, sort \"seq\", with fields seq, type, tool, is_error and text; page through them, and read the entries with is_error true and the last assistant entries first. When there is no transcript, the verdict is no_transcript.\n\nThen take the verdict's judge door on that sitting, with the verdict word and one remedy sentence. One verdict per sitting.\n\nDo not call discover or schema; a refusal names its own remedy. When the seat says halted or parked, say why and stop. If a routine-fire-payload block names a row id, walk that row and stop. When the Stop hook asks you to close the sitting, make that one call with the numbers it gives. In the note, say how many sittings you judged and the count of each verdict."
        :scope [{:kind "sitting" :actions []}
                {:kind "transcript" :actions []}
                {:kind "transcript_entry" :actions []}
                {:kind "verdict" :actions ["judge"]}]
        :delivers [{:kind "verdict" :action "judge"}]
        :judgment "2d3adcf5-dae8-4411-a2a8-aebd884c0d56"
        :cadence_seconds 21600 :fire_interval_seconds 3600
        :rows_per_firing 5 :max_open_sittings 1
        :sitting_budget_tokens 400000 :budget_usd_per_week 60M
        :sitting_idle_seconds 3600 :keep_transcripts "fired" :transcript_days 30
        :domain factory-domain-id}})

(def ^:private live-wakes
  "The door subscriptions beside each live seat, read with `live`. The
  seat kind carries no wakes of its own; these are what the template's
  `:wakes` write."
  {"draft-judge" [{:kind "ticket" :actions ["create" "shelve" "ungroom"]
                   :filter {:state "draft"} :settle_seconds 1800 :max_wait_seconds 7200}]
   "ops-seat" nil
   "sitting-judge" [{:kind "transcript" :actions ["seal"] :at_least 5 :settle_seconds 600}]})

(deftest each-template-filled-with-this-house-gives-back-its-live-seat
  (doseq [[n values] house
          :let [t (template n)
                seat (fill t values)]]
    (testing n
      (is (= (set (keys values)) (set (keys (:params (:template t)))))
          "this house has a value for every hole")
      (when-let [i (:instructions seat)]
        (is (<= (count i) 2000) "instructions fit the seat kind's 2000-character cap"))
      (doseq [[k v] (get live n)]
        (is (= v (get seat k)) (str (name k) " is the live seat's")))
      (when (contains? live-wakes n)
        (is (= (get live-wakes n) (fill {:seat (:wakes t)} values))
            "wakes are the live seat's subscriptions")))))
