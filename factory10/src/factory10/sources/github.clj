(ns factory10.sources.github
  "GitHub as a ForgeSource: the pull requests and the red check runs of
  the configured repositories, read with one token and written as rows
  (bead waymark-fp62.6.4, Reading A).

  THE PROTOCOL IS A NEW ONE, AND IT IS `factory10.sources.forge`'s.
  This source does NOT implement workqueue10's TaskSource, and it does
  not reuse ThreadSource. The change and the ci_run are not
  mirror-synced kinds — neither declares an adapter, and each keeps a
  machine of its own — so the engine's Mirror has no part in this, and
  a protocol built for a sync machine would make this source answer
  questions GitHub is never asked: there is no push, no create and no
  list here. `ForgeSource` has five verbs (what moved, one log tail,
  one label, the call count, the checks on one head), which is ThreadSource's own argument
  applied a fourth time. `forge/pass!` writes the rows.

  THE WIRE is the REST API v3, with the version header pinned. Five
  routes are read and one is written:

      GET  /repos/{repo}/pulls                      the window
      GET  /repos/{repo}/pulls/{n}                  size and mergeable
      GET  /repos/{repo}/pulls/{n}/files            the paths
      GET  /repos/{repo}/pulls/{n}/reviews          the review state
      GET  /repos/{repo}/branches/{base}            the base's head
      GET  /repos/{repo}/commits/{sha}/check-runs   the red runs
      GET  /repos/{repo}/actions/runs?head_sha=…    …or, when refused, the
      GET  /repos/{repo}/actions/runs/{id}/jobs     runs and their jobs
      GET  /repos/{repo}/actions/jobs/{id}/logs     the log tail
      POST /repos/{repo}/issues/{n}/labels          THE ONE WRITE
      POST /repos/{repo}/actions/runs/{id}/rerun-failed-jobs
                                                    and the re-run of a
                                                    run that died without
                                                    a verdict, once per
                                                    head (ticket 22f91244)

  CHECK RUNS REFUSED. A fine-grained token cannot hold `Checks` (GitHub
  grants it to Apps only), so on a PRIVATE repository the check-runs
  route answers 403. The same head is then read through the Actions
  API, which `Actions: read` opens: each job of each workflow run on
  the head becomes a check run in the shape the rest of this source
  reads, its `details_url` the job's own page so the log hop still
  finds the job. The refusal is remembered per repository for the
  pass, so the next head goes straight to Actions. Any other failure
  of the check read costs that head its checks, never the
  repository's pass.

  THE CURSOR is GitHub's own `updated_at`, as gtasks's is Google's.
  The pulls listing is asked sorted by `updated` descending, and the
  read stops at the first pull request older than the cursor MINUS
  five minutes. The five minutes absorb clock skew, same-second writes
  and a listing that shows a new pull request late (PR #499 never
  appeared inside a sixty-second overlap); re-seeing a handful of pull
  requests costs nothing, because
  the pass writes a fact only when the fact moved.

  ONE CURSOR FOR EACH REPOSITORY, AND IT IS STORED (ticket c07b581f).
  A repository's cursor advances when THAT repository answered, so one
  that fails re-reads its own window at the next pass and costs no
  other repository its window. The source is handed a `store` — `:load`
  and `:save!`, which the wiring keeps on the `repo_policy` row — and
  reads it once for each repository in a boot, so a restart starts
  where the last pass stopped. A repository with no cursor at all is
  one the house has never read: its first listing asks for `state=open`
  only, because the pull requests merged and closed before then are
  not the house's to mirror.

  IDENTITY is `github:owner/repo#number` for a change and
  `github:owner/repo/check-run/{id}` for a ci_run. Both are unique
  indexes on their kinds, so a second sighting finds the row that is
  already here and mints nothing.

  THE LOG TAIL, AND WHY IT IS BEST EFFORT. A check run of GitHub
  Actions names its workflow run and its job in `details_url`. The
  jobs listing answers the job when that URL names only the run. The
  job log route then answers a redirect to a blob, and the blob is
  plain text for one job — a whole run's logs are a zip, which this
  source does not open. The redirect is followed WITHOUT the token,
  because the blob is a signed URL at another host and a bearer must
  not travel there. A log that is not plain text, or that will not
  answer at all, costs the excerpt and never the row: the pass stores
  a note in `log_note`, so the classifier reads why the log is missing
  instead of an empty field.

  THE ONE WRITE. A classified run's label goes to the issues labels
  route, which ADDS a label and removes none. The source pushes one
  label, from `factory10.mirror/label-for`, and then walks the hidden
  `stamp_label` door so the transition log carries the push. Nothing
  else here writes to GitHub.

  THE TOKEN is FACTORY10_GITHUB_TOKEN: read on pull requests, checks
  and actions logs, write on issues for labels, on the repositories
  the house works and no other. No token means no source at all —
  `from-env` answers nil, the same nil-means-absent contract every
  other boundary keeps.

  WHICH REPOSITORIES IS A ROW, NOT A VARIABLE (bead
  waymark-fp62.6.3.8). The source holds a `repos-fn` and reads it at
  EVERY pass, and the wiring gives it the active `repo_policy` rows:
  one sentence a person writes says what submit means in a repository
  AND that the house polls it. A person who retires a policy stops the
  polling with one tap and no deploy, and a pass with no active row
  polls nothing and says so once. The static `:repos` list stays for
  the suite's fake, which has no engine behind it.

  A HEAD THAT MOVES. The pass mints nothing for a dead head, and the
  red rows already here leave the queue through the ci_run kind's
  `supersede` door (bead waymark-fp62.6.9). A log this source cannot
  read costs the excerpt and never the row: the reason rides in the
  row's `log_note`, beside an empty excerpt.

  PUNTS, recorded. Reading B, the webhook door, is its own bead: when
  it lands it will tell this source to poll one repository now, and it
  will read nothing from the payload but the repository name."
  (:require [clojure.string :as str]
            [factory10.resources.ci-run :as ci]
            [factory10.sources.forge :as forge]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def api-base "https://api.github.com")

(def api-version
  "The REST version this source speaks. Pinned, because an unpinned
  read follows whatever GitHub makes current."
  "2022-11-28")

(def user-agent "waymark-factory10")

(def page-size
  "The API's own ceiling for a listing page."
  100)

(def max-pages
  "How many pages of pull requests one repository may cost in a pass.
  A ceiling rather than a promise: the cursor makes the ordinary pass
  one page, and a first pass over a big repository stops here and
  finishes on the next beat."
  10)

(def overlap-seconds
  "How far behind the cursor the window re-asks from. Wide enough for
  clock skew, same-second writes and GitHub's listing lagging a new
  pull request, narrow enough that the re-read set stays small."
  300)

(def default-repos
  "The repository a STATIC list falls back to when it names none
  (R-8): this repository's own gate. A source wired to the policy rows
  never reads this — no active row means no repository, which is the
  truth the rows tell."
  ["ckopsa/waymark"])

(def no-repositories-said
  "What one pass says when no policy row is active. It is said ONCE,
  not on every beat: a house with nothing to poll is a house waiting
  for a person, and a line each pass would be a log nobody reads."
  (str "no repository has an active policy row, so this pass polls "
       "nothing — state a repo_policy and the next pass reads it"))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "factory10 github: " parts))))

;; ── small readings ──────────────────────────────────────────────────

(defn- word
  "The value as a word worth keeping: no nil, no blank."
  [v]
  (some-> v str str/trim not-empty))

(defn- clamp
  "The word, no longer than the kind's own bound. A field refused for
  length is a pull request that never becomes a row."
  [v n]
  (when-some [t (word v)]
    (if (> (count t) n) (subs t 0 n) t)))

(defn- whole
  "The value as a whole number, or nil."
  [v]
  (cond
    (integer? v) (long v)
    (number? v) (long v)
    :else (some-> (word v) parse-long)))

(defn change-id
  "The pull request's own address: github:owner/repo#number."
  [repo number]
  (str "github:" repo "#" number))

(defn run-id
  "The check run's own address: github:owner/repo/check-run/{id}."
  [repo id]
  (str "github:" repo "/check-run/" id))

(defn status-id
  "A commit status's own address: github:owner/repo/status/{sha}/{context}.
  A status has no run id, so the head and the context name it (ticket
  3aca3ae8)."
  [repo sha context]
  (str "github:" repo "/status/" sha "/" context))

;; ── the transport ───────────────────────────────────────────────────

(defn- query-string [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "="
                        (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
                 params)))

(defn- header-of [^HttpResponse resp ^String nm]
  (let [^java.util.Optional v (.firstValue (.headers resp) nm)]
    (.orElse v nil)))

(defn http-call
  "The real transport: (fn [method path {:keys [params body url raw
  anonymous]}]) → the parsed body, or, with :raw, the whole answer as
  {:status :body :location :content-type} with nothing thrown.

  The bearer is asked for PER REQUEST, so a token-fn that re-mints
  never goes stale in a header map. Redirects are NOT followed by the
  client: the job log's redirect points at another host, and the
  caller follows it with :anonymous so the token stays here. Non-2xx
  throws ex-info carrying :status on every route but a raw one."
  [{:keys [token-fn base]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))
        base (str/replace (str (or base api-base)) #"/+$" "")]
    (fn [^String method path {:keys [params body url raw anonymous]}]
      (let [target (or url
                       (str base path
                            (when (seq params) (str "?" (query-string params)))))
            builder (-> (HttpRequest/newBuilder (URI/create target))
                        (.timeout (Duration/ofSeconds 30))
                        (.header "accept" (if raw
                                            "*/*"
                                            "application/vnd.github+json"))
                        (.header "x-github-api-version" api-version)
                        (.header "user-agent" user-agent))
            ^HttpRequest$Builder builder (if anonymous
                                           builder
                                           (.header builder "authorization"
                                                    (str "Bearer " (token-fn))))
            ^HttpRequest$Builder builder (if body
                                           (.header builder "content-type"
                                                    "application/json")
                                           builder)
            publisher (if body
                        (HttpRequest$BodyPublishers/ofString
                         (wire/write-json body) StandardCharsets/UTF_8)
                        (HttpRequest$BodyPublishers/noBody))
            req (-> builder (.method method publisher) (.build))
            ^HttpResponse resp (.send client req
                                      (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)
            text (str (.body resp))]
        (if raw
          {:status status :body text
           :location (header-of resp "location")
           :content-type (header-of resp "content-type")}
          (do
            (when (>= status 400)
              (throw (ex-info (str "github answered " status " for "
                                   method " " (or path url))
                              {:status status :body text})))
            ;; the body is judged only after the status: a proxy's
            ;; plain-text refusal must not become a parse crash that
            ;; erases the status the caller needs (gtasks, waymark-t6s)
            (try (some-> (not-empty text) wire/read-json)
                 (catch Exception _
                   (throw (ex-info (str "github answered " status
                                        " with a body that is not JSON")
                                   {:status status}))))))))))

(defn- call!
  "One request, counted. The census line's first number is this count."
  [{:keys [call calls]} method path opts]
  (swap! calls inc)
  (call method path opts))

;; ── the cursor ──────────────────────────────────────────────────────

(defn- ->instant [s]
  (try (Instant/parse (str s)) (catch Exception _ nil)))

(defn high-water
  "The cursor after a pass: the latest `updated_at` anything showed us,
  the standing cursor included, so a pass that saw nothing new does not
  walk the cursor backwards. GitHub's stamps, never our clock."
  [cursor updates]
  (some->> (keep ->instant (cons cursor updates))
           seq
           (reduce (fn [a b] (if (pos? (compare b a)) b a)))
           str))

(defn window-start
  "The cursor as the window's floor: sixty seconds behind it, so a
  write GitHub stamped a hair before the last read is not lost. A nil
  cursor has no floor: the repository was never read, and its first
  listing asks for the open pull requests only."
  [cursor]
  (some-> ^Instant (->instant cursor) (.minusSeconds overlap-seconds)))

(defn- inside-window?
  "Is this pull request at or after the window's floor? A time that
  will not parse is kept: a pass must not drop a row over a stamp it
  cannot read."
  [updated ^Instant floor]
  (if-some [t (->instant updated)]
    (not (neg? (compare t floor)))
    true))

;; ── the translation ─────────────────────────────────────────────────

(def mergeable-states
  "GitHub's `mergeable_state` → the change kind's own four words.
  `unstable` is clean: a check that is not required failed, and the
  pull request still merges."
  {"clean" "clean" "has_hooks" "clean" "unstable" "clean"
   "dirty" "conflicted"
   "blocked" "blocked" "behind" "blocked" "draft" "blocked"
   "unknown" "unknown"})

(defn mergeable-of
  "What the pull request says about merging. `unknown` when GitHub has
  not computed it yet, which is the honest answer and not a guess.
  `mergeable: false` is GitHub's own word for a conflict and is read
  first: `mergeable_state` is policy talk (blocked, behind, draft), and a
  pull request both blocked and conflicted is conflicted."
  [pull]
  (or (when (false? (:mergeable pull)) "conflicted")
      (get mergeable-states (word (:mergeable_state pull)))
      "unknown"))

(defn forge-state-of
  "GitHub's three states. A closed pull request that carries a merge
  time is merged, which is the only place the two differ."
  [pull]
  (cond
    (or (true? (:merged pull)) (word (:merged_at pull))) "merged"
    (= "closed" (word (:state pull))) "closed"
    :else "open"))

(defn review-state
  "The reviews of one pull request → the change kind's own four words.
  A request for changes outranks an approval, because it is the one
  that stops the merge."
  [reviews]
  (let [states (into #{} (map #(word (:state %))) reviews)]
    (cond
      (contains? states "CHANGES_REQUESTED") "changes_requested"
      (contains? states "APPROVED") "approved"
      (seq states) "commented"
      :else "pending")))

(defn- label-names [pull]
  (into [] (keep #(clamp (:name %) 100)) (:labels pull)))

(defn- path-names [files]
  (into [] (keep #(clamp (:filename %) 400)) files))

(defn pull->doc
  "One pull request, with whatever the detail routes answered, → the
  change document `forge/pass!` writes. A field GitHub did not answer
  is ABSENT, and absent means silent: the stored value stands."
  [repo pull {:keys [files reviews]}]
  (let [number (whole (:number pull))]
    (cond-> {:change_id (change-id repo number)
             :repository repo
             :number number
             :forge_state (forge-state-of pull)
             :draft (boolean (:draft pull))
             :mergeable (mergeable-of pull)
             :labels (label-names pull)}
      (clamp (:title pull) 400) (assoc :title (clamp (:title pull) 400))
      (clamp (get-in pull [:user :login]) 120)
      (assoc :author (clamp (get-in pull [:user :login]) 120))
      (clamp (get-in pull [:base :ref]) 200)
      (assoc :base_branch (clamp (get-in pull [:base :ref]) 200))
      (clamp (get-in pull [:head :ref]) 200)
      (assoc :head_branch (clamp (get-in pull [:head :ref]) 200))
      (clamp (get-in pull [:head :sha]) 64)
      (assoc :head_sha (clamp (get-in pull [:head :sha]) 64))
      (clamp (:html_url pull) 500) (assoc :url (clamp (:html_url pull) 500))
      (whole (:changed_files pull)) (assoc :files_changed
                                           (whole (:changed_files pull)))
      (whole (:additions pull)) (assoc :lines_added (whole (:additions pull)))
      (whole (:deletions pull)) (assoc :lines_removed
                                       (whole (:deletions pull)))
      (some? files) (assoc :touched_paths (path-names files))
      (some? reviews) (assoc :review_state (review-state reviews)))))

(defn check->doc
  "One check run → the ci_run document, plus the two facts the pass
  needs and the row does not keep: which change it ran on, and where
  the job log lives."
  [repo change-id* head-sha check]
  (cond-> {:run_id (if-some [context (:context check)]
                     (status-id repo (or (clamp (:head_sha check) 64) head-sha)
                                context)
                     (run-id repo (:id check)))
           :change_id change-id*
           :repository repo
           :check_id (whole (:id check))
           :details_url (word (:details_url check))}
    (or (clamp (:head_sha check) 64) head-sha)
    (assoc :head_sha (or (clamp (:head_sha check) 64) head-sha))
    (clamp (:name check) 200) (assoc :check_name (clamp (:name check) 200))
    (word (:conclusion check)) (assoc :conclusion (word (:conclusion check)))
    (word (:started_at check)) (assoc :started_at (word (:started_at check)))
    (word (:completed_at check)) (assoc :finished_at
                                        (word (:completed_at check)))
    (clamp (:html_url check) 500) (assoc :url (clamp (:html_url check) 500))))

(defn tail
  "The end of a log, as the ci_run kind takes it: the last
  `log-excerpt-lines` lines, cut to `log-excerpt-chars` from the END.
  A failure says what it was at the end, so the end is what is kept."
  [text]
  (let [lines (vec (str/split-lines (str text)))
        kept (if (> (count lines) ci/log-excerpt-lines)
               (subvec lines (- (count lines) ci/log-excerpt-lines))
               lines)
        s (str/join "\n" kept)]
    (if (> (count s) ci/log-excerpt-chars)
      (subs s (- (count s) ci/log-excerpt-chars))
      s)))

;; ── the reads ───────────────────────────────────────────────────────

(defn- list-pulls!
  "The pull requests that moved, newest first, stopping at the window's
  floor. `state=all` because a merge and a close are moves this mirror
  must follow, not rows it may drop. No floor is a repository this
  source has never read, and that one read asks for `state=open`: what
  merged or closed before it is not the house's to mirror (ticket
  c07b581f)."
  [this repo floor]
  (loop [page 1, out []]
    (let [items (vec (call! this "GET" (str "/repos/" repo "/pulls")
                            {:params {:state (if floor "all" "open")
                                      :sort "updated"
                                      :direction "desc"
                                      :per_page page-size :page page}}))
          fresh (if floor
                  (vec (take-while #(inside-window? (:updated_at %) floor)
                                   items))
                  items)
          out (into out fresh)]
      (if (and (= (count fresh) (count items))
               (= (count items) page-size)
               (< page max-pages))
        (recur (inc page) out)
        out))))

(defn- retry-pull!
  "One pull request whose row the last pass refused, read again by
  number: the cursor has moved past its stamp, so the listing will not
  answer it until it moves again (ticket 365043a6). A read that fails
  costs the retry, never the repository's pass."
  [this repo number]
  (try (call! this "GET" (str "/repos/" repo "/pulls/" number) {})
       (catch Exception e
         (warn! "the pull request " repo "#" number
                " could not be read again (" (ex-message e) ")")
         nil)))

(defn- detail!
  "The pull request's own route, which is the only one that carries the
  size and the mergeable state. A pull request the token cannot read
  falls back to the listing's own fields rather than costing the
  repository its pass."
  [this repo number pull]
  (try (or (call! this "GET" (str "/repos/" repo "/pulls/" number) {}) pull)
       (catch clojure.lang.ExceptionInfo e
         (if (= 404 (:status (ex-data e)))
           pull
           (throw e)))))

(defn- files!
  "The paths the pull request touches, up to the first page. A bigger
  change keeps the first hundred paths, which is what a policy that
  fences a directory reads."
  [this repo number]
  (try (vec (call! this "GET" (str "/repos/" repo "/pulls/" number "/files")
                   {:params {:per_page page-size}}))
       (catch Exception e
         (warn! "the paths of " repo "#" number " did not answer ("
                (ex-message e) ")")
         nil)))

(defn- reviews!
  [this repo number]
  (try (vec (call! this "GET" (str "/repos/" repo "/pulls/" number "/reviews")
                   {:params {:per_page page-size}}))
       (catch Exception e
         (warn! "the reviews of " repo "#" number " did not answer ("
                (ex-message e) ")")
         nil)))

(defn job->check
  "One Actions job → the check run shape this source reads. A job's
  page names its run and itself, which is what `job-of` looks for in
  `details_url`."
  [sha job]
  {:id (:id job)
   :name (:name job)
   :status (:status job)
   :conclusion (:conclusion job)
   :head_sha (or (word (:head_sha job)) sha)
   :started_at (:started_at job)
   :completed_at (:completed_at job)
   :html_url (:html_url job)
   :details_url (:html_url job)})

(defn- actions-checks!
  "Every job of every workflow run on the head, as check runs: the read
  a token without `Checks` can still make."
  [this repo sha]
  (let [runs (:workflow_runs
              (call! this "GET" (str "/repos/" repo "/actions/runs")
                     {:params {:head_sha sha :per_page page-size}}))]
    (into []
          (mapcat
           (fn [run]
             (map #(job->check sha %)
                  (:jobs (call! this "GET"
                                (str "/repos/" repo "/actions/runs/"
                                     (:id run) "/jobs")
                                {:params {:per_page page-size}})))))
          runs)))

(defn- run-job
  "One Actions job, as the re-run judgment reads it: its conclusion and
  the name and conclusion of each step."
  [job]
  {:name (word (:name job))
   :conclusion (word (:conclusion job))
   :steps (mapv (fn [s] {:name (word (:name s))
                         :conclusion (word (:conclusion s))})
                (:steps job))})

(defn- latest-runs!
  "The latest workflow run of each workflow on the head, newest first
  as GitHub lists them, with the jobs of a run that finished and did
  not pass (ticket 22f91244)."
  [this repo sha]
  (let [runs (:workflow_runs
              (call! this "GET" (str "/repos/" repo "/actions/runs")
                     {:params {:head_sha sha :per_page page-size}}))
        latest (vals (reduce (fn [m run]
                               (let [k (str (or (:workflow_id run) (:id run)))]
                                 (if (contains? m k) m (assoc m k run))))
                             {}
                             runs))]
    (mapv (fn [run]
            (let [status (word (:status run))
                  conclusion (word (:conclusion run))]
              (cond-> {:run_id (whole (:id run))
                       :status status
                       :conclusion conclusion}
                (and (= "completed" status)
                     (not (contains? forge/green-conclusions (str conclusion))))
                (assoc :jobs
                       (mapv run-job
                             (:jobs (call! this "GET"
                                           (str "/repos/" repo "/actions/runs/"
                                                (:id run) "/jobs")
                                           {:params {:per_page page-size}})))))))
          latest)))

(defn- refused? [e]
  (contains? #{401 403} (:status (ex-data e))))

(defn status->check
  "One commit status → the check run shape this source reads (ticket
  3aca3ae8). A status has no run: its `:context` names it, and the head
  and the context are its address. `pending` is still running; `error`
  is red, as `failure` is."
  [sha status]
  (let [state (word (:state status))
        done? (and state (not= "pending" state))]
    {:context (clamp (:context status) 100)
     :name (:context status)
     :status (if done? "completed" "in_progress")
     :conclusion (when done? (if (= "error" state) "failure" state))
     :head_sha sha
     :started_at (:created_at status)
     :completed_at (when done? (:updated_at status))
     :html_url (:target_url status)
     :details_url (:target_url status)}))

(defn- statuses!
  "Every commit status on the head, as check runs: a required check
  that reports as a status (an external quality gate) is a check like
  any other (ticket 3aca3ae8). GitHub's combined status answers the
  latest of each context. A token that may not read statuses answers
  none."
  [this repo sha]
  (try
    (mapv #(status->check sha %)
          (:statuses (call! this "GET"
                            (str "/repos/" repo "/commits/" sha "/status")
                            {:params {:per_page page-size}})))
    (catch clojure.lang.ExceptionInfo e
      (if (refused? e) [] (throw e)))))

(defn- check-runs!
  "Every check run on the head, as one page, and every commit status
  beside them. `filter=latest` is GitHub's own: a check run that ran
  twice answers once. A repository whose check-runs route refused the
  token is read through Actions, and stays so for the rest of the pass."
  [{:keys [actions-only] :as this} repo sha]
  (into
   (if (contains? @actions-only repo)
     (actions-checks! this repo sha)
     (try
       (let [resp (call! this "GET"
                         (str "/repos/" repo "/commits/" sha "/check-runs")
                         {:params {:per_page page-size :filter "latest"}})]
         (vec (:check_runs resp)))
       (catch clojure.lang.ExceptionInfo e
         (if (refused? e)
           (do (swap! actions-only conj repo)
               (actions-checks! this repo sha))
           (throw e)))))
   (statuses! this repo sha)))

(defn- head-checks!
  "The check runs of one open head for the pass. A read that fails
  costs this pull request its checks, like `files!` and `reviews!`,
  and never the repository's pass."
  [this repo number sha]
  (try (check-runs! this repo sha)
       (catch Exception e
         (warn! "the checks of " repo "#" number " did not answer ("
                (ex-message e) ")")
         [])))

(defn- red?
  "A check run that finished and failed. `cancelled` is not red:
  somebody stopped that job, and there is nothing to classify."
  [check]
  (and (= "completed" (word (:status check)))
       (contains? forge/red-conclusions (str (word (:conclusion check))))))

(defn- pull-pass!
  "One pull request → its document, with the detail routes read for
  the pull requests that are still open."
  [this repo pull]
  (let [number (whole (:number pull))
        detail (detail! this repo number pull)
        open? (= "open" (forge-state-of detail))]
    (pull->doc repo detail
               (when open?
                 {:files (files! this repo number)
                  :reviews (reviews! this repo number)}))))

(defn- pulls-route [repo]
  (str "GET /repos/" repo "/pulls"))

(def unreadable-statuses
  "The answers to the pulls listing that mean the token cannot read the
  repository (ticket 116dfb0d): not signed in, not allowed, or a
  private repository GitHub will not admit exists."
  #{401 403 404})

(defn- repo-pass!
  "One repository's whole read: the window of pull requests, each one's
  document, and the red check runs of every open head. `retried` names
  the pull requests a refused row asks to read again, which the window
  may no longer hold. A throw here is one repository's failure, which
  the poll catches."
  [this repo floor retried]
  (let [listed (try (list-pulls! this repo floor)
                   (catch clojure.lang.ExceptionInfo e
                     ;; the listing is the one route that says whether
                     ;; the token reads this repository at all, so its
                     ;; refusal carries the route out to the poll
                     (throw (ex-info (ex-message e)
                                     (assoc (ex-data e)
                                            :route (pulls-route repo))
                                     e))))
        seen (into #{} (map #(whole (:number %))) listed)
        pulls (into (vec listed)
                    (keep #(retry-pull! this repo %))
                    (remove seen retried))
        changes (mapv #(pull-pass! this repo %) pulls)
        checks (into []
                     (mapcat
                      (fn [doc]
                        (when (and (= "open" (:forge_state doc))
                                   (word (:head_sha doc)))
                          (into []
                                (comp (filter red?)
                                      (map #(check->doc repo (:change_id doc)
                                                        (:head_sha doc) %)))
                                (head-checks! this repo (:number doc)
                                              (:head_sha doc))))))
                     changes)]
    {:changes changes :checks checks
     :updates (mapv #(word (:updated_at %)) pulls)}))

;; ── the log tail ────────────────────────────────────────────────────

(def ^:private details-pattern
  #".*/actions/runs/(\d+)(?:/job/(\d+))?.*")

(defn- job-of
  "The job behind one check run. `details_url` names it outright most
  of the time; when it names only the workflow run, the jobs listing
  answers the job whose check run this is, or the job of the same
  name."
  [this check]
  (let [[_ run job] (some->> (:details_url check)
                             (re-matches details-pattern))]
    (cond
      job job
      (nil? run) nil
      :else
      (let [resp (call! this "GET"
                        (str "/repos/" (:repository check) "/actions/runs/"
                             run "/jobs")
                        {:params {:per_page page-size}})
            jobs (vec (:jobs resp))
            by-check (first (filter #(= (str (:check_id check))
                                        (last (str/split
                                               (str (:check_run_url %)) #"/")))
                                    jobs))
            by-name (first (filter #(= (word (:name %))
                                       (word (:check_name check)))
                                   jobs))]
        (some-> (or by-check by-name) :id str)))))

(defn- failed-steps!
  "The names of the steps that went red in the job behind one check run
  (ticket c0d7ce64), read from its run's jobs listing, which carries
  each step's conclusion. Empty when the check names no run or job."
  [this check]
  (let [[_ run] (some->> (:details_url check)
                         (re-matches details-pattern))
        job (when run (job-of this check))]
    (if job
      (let [jobs (:jobs (call! this "GET"
                               (str "/repos/" (:repository check)
                                    "/actions/runs/" run "/jobs")
                               {:params {:per_page page-size}}))]
        (into []
              (comp (filter #(contains? forge/red-conclusions
                                        (str (word (:conclusion %)))))
                    (keep #(word (:name %))))
              (:steps (first (filter #(= job (str (:id %))) jobs)))))
      [])))

(defn- log-answer!
  "The job log route, with its redirect followed WITHOUT the token: the
  blob is a signed URL at another host, and a bearer must not travel
  there."
  [this repo job]
  (let [resp (call! this "GET"
                    (str "/repos/" repo "/actions/jobs/" job "/logs")
                    {:raw true})
        location (when (and (>= (long (:status resp)) 300)
                            (< (long (:status resp)) 400))
                   (word (:location resp)))]
    (if location
      (call! this "GET" nil {:url location :raw true :anonymous true})
      resp)))

(defn- plain-text?
  "Is this answer a log a person can read? A zip is not: this source
  opens no archive, and a whole run's logs arrive as one."
  [{:keys [content-type body]}]
  (and (not (str/blank? (str body)))
       (not (str/includes? (str/lower-case (str content-type)) "zip"))
       (not (str/starts-with? (str body) "PK"))))

;; ── the source ──────────────────────────────────────────────────────

(defn- repos-now
  "The repositories THIS pass reads: the source's own function, asked
  again (R-5). An empty answer is said once and then held, so a house
  with no active policy prints one line rather than one each beat, and
  the line comes back the next time the rows empty."
  [{:keys [repos-fn said]}]
  (let [named (into [] (comp (map str)
                             (remove str/blank?))
                    (repos-fn))]
    (if (seq named)
      (do (reset! said false) named)
      (do (when (compare-and-set! said false true)
            (warn! no-repositories-said))
          named))))

(defn- cursor-of!
  "One repository's cursor: the one this source holds, else the stored
  one, read once for each repository in a boot (ticket c07b581f). nil
  is a repository nobody has read yet."
  [{:keys [cursor store]} repo]
  (let [held @cursor]
    (if (contains? held repo)
      (get held repo)
      (let [stored (when-some [load (:load store)] (word (load repo)))]
        (swap! cursor assoc repo stored)
        stored))))

(defn- move-cursor!
  "The repository's cursor after its own read, held here and written to
  the store when it moved. A store that will not take the write costs
  the next boot a wider window, never this pass."
  [{:keys [cursor store]} repo stood updates]
  (let [moved (high-water stood updates)]
    (when (not= moved stood)
      (swap! cursor assoc repo moved)
      (when-some [save! (:save! store)]
        (try (save! repo moved)
             (catch Exception e
               (warn! "the cursor of " repo " was not stored ("
                      (ex-message e) ")")))))))

(defrecord GitHubSource [call repos-fn cursor calls said actions-only retry
                         store]
  forge/ForgeSource
  (forge-poll [this]
    (reset! calls 0)
    (reset! actions-only #{})
    (let [pending (first (reset-vals! retry {}))
          repos (repos-now this)
          answers (mapv (fn [repo]
                          ;; each repository reads from its OWN cursor
                          ;; and moves it when it answered, so one that
                          ;; throws holds no other repository's window
                          (try (let [stood (cursor-of! this repo)
                                     answer (repo-pass! this repo
                                                        (window-start stood)
                                                        (get pending repo))]
                                 (move-cursor! this repo stood
                                               (:updates answer))
                                 (assoc answer :repo repo :ok? true))
                               (catch Exception e
                                 (warn! "the repository " repo
                                        " did not answer (" (ex-message e)
                                        "); its rows keep their stored truth")
                                 (let [{:keys [status route]} (ex-data e)]
                                   (cond-> {:repo repo :ok? false}
                                     (and route
                                          (contains? unreadable-statuses
                                                     status))
                                     (assoc :refusal {:status status
                                                      :route route}))))))
                        repos)
          answered (filterv :ok? answers)
          complete? (= (count answered) (count answers))]
      ;; a retry whose repository did not answer waits for the next pass
      (swap! retry #(merge-with into %
                                (apply dissoc pending (map :repo answered))))
      {:changes (into [] (mapcat :changes) answered)
       :checks (into [] (mapcat :checks) answered)
       :repositories (mapv :repo answers)
       :answered (mapv :repo answered)
       :refusals (into {} (keep #(when-some [r (:refusal %)] [(:repo %) r]))
                       answers)
       :complete? complete?}))

  (forge-log-tail [this check]
    (try
      (if-some [job (job-of this check)]
        (let [resp (log-answer! this (:repository check) job)]
          (cond
            (>= (:status resp) 400)
            {:excerpt nil
             :note (str "the forge answered " (:status resp)
                        " for the job log")}

            (not (plain-text? resp))
            {:excerpt nil :note "the job log did not arrive as plain text"}

            :else {:excerpt (tail (:body resp)) :note nil}))
        {:excerpt nil :note "the check run names no job of the forge"})
      (catch Exception e
        {:excerpt nil
         :note (str "the job log could not be read (" (ex-message e) ")")})))

  (forge-label! [this change label]
    (call! this "POST"
           (str "/repos/" (:repository change) "/issues/" (:number change)
                "/labels")
           {:body {:labels [label]}})
    label)

  (forge-calls [_] @calls)

  (forge-checks [this repository head-sha]
    ;; the same route the poll reads the red runs from, with nothing
    ;; filtered: a pending and a green check are what the failing pass
    ;; needs to tell "not yet" from "red" (ticket d1742908). The id and
    ;; the start say which of two runs of one name is the newer, so a
    ;; green re-run answers for the red run before it (ticket 6bdaf6fe).
    ;; Each carries its ci_run document on its metadata, so the run
    ;; pass mints a late red from this same read (ticket 3aca3ae8)
    (mapv (fn [check]
            (with-meta
              {:check_name (clamp (:name check) 200)
               :status (word (:status check))
               :conclusion (word (:conclusion check))
               :id (:id check)
               :started_at (word (:started_at check))}
              {:doc (check->doc repository nil head-sha check)}))
          (check-runs! this repository head-sha)))

  (forge-base [this repository branch]
    ;; the base branch's head, and every check on it through the same
    ;; read a pull request's head gets, the Actions fallback included
    ;; (ticket ade81ae9). Each check keeps what the log hop needs.
    (when-some [sha (word (get-in (call! this "GET"
                                         (str "/repos/" repository
                                              "/branches/" branch)
                                         {})
                                  [:commit :sha]))]
      {:head_sha sha
       :checks (mapv (fn [check]
                       (assoc (check->doc repository nil sha check)
                              :status (word (:status check))))
                     (check-runs! this repository sha))}))

  forge/ForgeDeploy
  (forge-covers? [this repository number sha]
    ;; the pull request's merge commit, then GitHub's compare of it with
    ;; the deployed commit: `ahead` means the merge is an ancestor of it
    ;; (ticket 47217098)
    (let [pull (call! this "GET" (str "/repos/" repository "/pulls/" number) {})
          merged (word (:merge_commit_sha pull))]
      (boolean
       (when (and merged (or (true? (:merged pull)) (word (:merged_at pull))))
         (or (= merged sha)
             (let [answer (call! this "GET"
                                 (str "/repos/" repository "/compare/"
                                      merged "..." sha)
                                 {})]
               (contains? #{"ahead" "identical"} (word (:status answer)))))))))

  forge/ForgePull
  (forge-pull [this repository number]
    ;; the pull request's own route, outside the window (ticket
    ;; 949d18c5); a 404 throws, so a number the forge lacks is no doc
    (when-some [pull (call! this "GET" (str "/repos/" repository "/pulls/"
                                          number)
                            {})]
      (pull-pass! this repository pull)))

  forge/ForgeSteps
  (forge-failed-steps [this check]
    (failed-steps! this check))

  forge/ForgeCompare
  (forge-behind? [this repository base head-sha]
    ;; GitHub's compare of the base branch with the head: `behind_by`
    ;; counts the base's commits the head lacks (ticket 498a089e)
    (let [answer (call! this "GET"
                        (str "/repos/" repository "/compare/" base "..." head-sha)
                        {})]
      (or (pos? (long (or (:behind_by answer) 0)))
          (contains? #{"behind" "diverged"} (word (:status answer))))))

  forge/ForgeRerun
  (forge-runs [this repository head-sha]
    (latest-runs! this repository head-sha))

  (forge-rerun! [this repository run-id]
    ;; the second write (ticket 22f91244): a run that died without a
    ;; verdict runs its failed jobs again
    (call! this "POST"
           (str "/repos/" repository "/actions/runs/" run-id
                "/rerun-failed-jobs")
           {})
    run-id)

  forge/ForgeRetry
  (forge-retry! [_ docs]
    (swap! retry
           (fn [m]
             (reduce (fn [m {:keys [repository number]}]
                       (if (and repository number)
                         (update m repository (fnil conj #{}) number)
                         m))
                     m docs)))
    nil))

(defn parse-repos
  "\"ckopsa/waymark, ckopsa/waymark-bench\" → the repositories to read,
  in order. Nothing named means the proving ground's own repository."
  [repos]
  (let [named (if (string? repos)
                (remove str/blank? (map str/trim (str/split (str repos) #",")))
                (remove str/blank? (map str repos)))]
    (or (not-empty (vec named)) default-repos)))

(defn- repos-fn-of
  "The function a pass asks for its repositories: the one the config
  names, else the static list frozen into one. Both spellings answer
  the same question; only the first one can answer it differently next
  pass."
  [{:keys [repos repos-fn]}]
  (or repos-fn (constantly (parse-repos repos))))

(defn http-source
  "The real boundary over GitHub.

  config: :token-fn (a zero-arg token source) or :token (the word
  itself), :repos-fn (a zero-arg function → the repositories to read,
  asked at every pass) or :repos (a static comma-separated string or
  seq), :base (the API base, for a test that wants a local server),
  :store (where each repository's cursor is kept across boots: `:load`,
  repository → cursor or nil, and `:save!`, repository and cursor)."
  [{:keys [token token-fn base store] :as config}]
  (->GitHubSource (http-call {:token-fn (or token-fn (constantly token))
                              :base base})
                  (repos-fn-of config)
                  (atom {})
                  (atom 0)
                  (atom false)
                  (atom #{})
                  (atom {})
                  store))

(defn from-env
  "The deployed boundary off FACTORY10_GITHUB_TOKEN. nil when the token
  is not configured, which is the nil-means-absent contract every
  other boundary keeps: no token, no source, and nothing starts.

  `repos-fn` is the wiring's own reading of the active `repo_policy`
  rows (R-5). A source built without one falls back to the proving
  ground's repository, which is what a boot with no engine behind it
  can honestly say. `store` is the wiring's cursor store on those same
  rows (ticket c07b581f); without one the cursors live in memory and a
  boot starts each repository at its open pull requests."
  ([] (from-env #(System/getenv ^String %)))
  ([env] (from-env env nil))
  ([env repos-fn] (from-env env repos-fn nil))
  ([env repos-fn store]
   (when-some [token (word (env "FACTORY10_GITHUB_TOKEN"))]
     (http-source {:token token :repos-fn repos-fn :store store}))))

;; ── the scriptable twin ─────────────────────────────────────────────
;;
;; The fake is an in-memory GITHUB, not an in-memory source: it stands
;; behind the same (method path opts) seam the real transport rides,
;; so a test exercises the real cursor arithmetic, the real window,
;; the real path building, the real translation and the real log
;; reading, and only the socket is missing. It records every request,
;; so a test can say what went ON the wire — which is the only way to
;; prove that the source made one write and no other.

(defn fake-state
  "A fresh in-memory GitHub. Script it with `seed-pull!`,
  `seed-check!`, `seed-status!`, `seed-job!`, `seed-log!`, `log-mode!` and `down!`;
  read it back with `requests` and `labels-pushed`."
  []
  (atom {:repos {} :logs {} :log-mode :text :labels [] :requests []
         :down false}))

(defn seed-pull!
  "One pull request at the fake, in GitHub's own shape — never a
  document: the whole point of this twin is that the real translation
  runs. opts may carry :files and :reviews, the two detail routes."
  [state repo pull & [{:keys [files reviews]}]]
  (swap! state
         (fn [s]
           (-> s
               (update-in [:repos repo :pulls]
                          (fn [ps]
                            (assoc (or ps {}) (long (:number pull)) pull)))
               (assoc-in [:repos repo :files (long (:number pull))] files)
               (assoc-in [:repos repo :reviews (long (:number pull))]
                         reviews)))))

(defn seed-check!
  "One check run at the fake, on one head."
  [state repo sha check]
  (swap! state update-in [:repos repo :checks sha]
         (fn [cs] (conj (vec cs) check))))

(defn seed-status!
  "One commit status at the fake, on one head, in GitHub's own shape
  ({:context :state :target_url …}). The combined status route answers
  them (ticket 3aca3ae8)."
  [state repo sha status]
  (swap! state update-in [:repos repo :statuses sha]
         (fn [ss] (conj (vec ss) status))))

(defn seed-branch!
  "One branch at the fake, with its head. The base pass reads it
  (ticket ade81ae9); a branch never seeded answers 404."
  [state repo branch sha]
  (swap! state assoc-in [:repos repo :branches branch] sha))

(defn seed-ancestor!
  "Say `sha` is an ancestor of `head`, for the compare route the deploy
  read walks (ticket 47217098). Any other pair of commits has diverged."
  [state repo head sha]
  (swap! state update-in [:repos repo :ancestors head] (fnil conj #{}) sha))

(defn seed-run!
  "One workflow run on one head, for the Actions read the source makes
  when the check-runs route refuses. Its jobs come from `seed-job!`."
  [state repo sha run]
  (swap! state update-in [:repos repo :runs sha]
         (fn [rs] (conj (vec rs) run))))

(defn checks-answer!
  "Make the check-runs route of one repository answer this status
  instead of its runs: 403 is a token without `Checks` on a private
  repository, 500 is any other failure. nil restores the answer."
  [state repo status]
  (swap! state assoc-in [:repos repo :checks-status] status))

(defn seed-job!
  "One job of one workflow run, for the log hop the source walks when
  `details_url` names only the run, and for the Actions read."
  [state repo run job]
  (swap! state update-in [:repos repo :jobs (str run)]
         (fn [js] (conj (vec js) job))))

(defn seed-log!
  "One job's log, in full. The source keeps only its tail."
  [state job text]
  (swap! state assoc-in [:logs (str job)] text))

(defn log-mode!
  "How the fake answers the job log route: :text (plain text, the
  ordinary answer), :redirect (a 302 to a blob, which the source
  follows without the token), :zip (an archive, which the source
  refuses) or :missing (a 404)."
  [state mode]
  (swap! state assoc :log-mode mode))

(defn down! [state down?] (swap! state assoc :down (boolean down?)))

(defn refuse!
  "Make every route of one repository answer this status, as GitHub
  does for a token that cannot read it; nil lifts the refusal."
  [state repo status]
  (swap! state update :refused
         (fn [m] (if status (assoc m repo status) (dissoc m repo)))))

(def ^:private repo-path #"/repos/([^/]+/[^/]+)(?:/.*)?")

(defn requests
  "Every request the source made, oldest first."
  [state]
  (:requests @state))

(defn labels-pushed
  "Every label push the source made, oldest first."
  [state]
  (:labels @state))

(defn cursor
  "The source's cursor — the highest `updated_at` it has seen in any
  repository, or with a repository named, that repository's own."
  ([source] (high-water nil (vals @(:cursor source))))
  ([source repo] (get @(:cursor source) repo)))

(def ^:private pulls-path #"/repos/([^/]+/[^/]+)/pulls")
(def ^:private pull-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)")
(def ^:private files-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)/files")
(def ^:private reviews-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)/reviews")
(def ^:private checks-path #"/repos/([^/]+/[^/]+)/commits/([^/]+)/check-runs")
(def ^:private statuses-path #"/repos/([^/]+/[^/]+)/commits/([^/]+)/status")
(def ^:private runs-path #"/repos/([^/]+/[^/]+)/actions/runs")
(def ^:private jobs-path #"/repos/([^/]+/[^/]+)/actions/runs/(\d+)/jobs")
(def ^:private job-log-path #"/repos/([^/]+/[^/]+)/actions/jobs/(\d+)/logs")
(def ^:private labels-path #"/repos/([^/]+/[^/]+)/issues/(\d+)/labels")
(def ^:private branch-path #"/repos/([^/]+/[^/]+)/branches/(.+)")
(def ^:private compare-path #"/repos/([^/]+/[^/]+)/compare/([^.]+)\.\.\.(.+)")
(def ^:private rerun-path
  #"/repos/([^/]+/[^/]+)/actions/runs/(\d+)/rerun-failed-jobs")

(defn reruns
  "Every re-run the source asked for, oldest first, as {:repository
  :run}."
  [state]
  (vec (:reruns @state)))

(def blob-base
  "Where the fake's log redirect points. Another host, which is why
  the source follows it without the token."
  "https://logs.example/job/")

(defn- newest-first [pulls]
  (vec (sort-by #(str (:updated_at %)) #(compare %2 %1) pulls)))

(defn fake-call
  "An in-memory GitHub behind the transport seam."
  [state]
  (fn [method path {:keys [params body url raw anonymous]}]
    (swap! state update :requests conj
           {:method method :path (or path url) :params params :body body
            ;; recorded so a test can prove the token did NOT travel to
            ;; the blob host the job log redirects to
            :anonymous (boolean anonymous)})
    (when (:down @state)
      (throw (ex-info "github unreachable" {})))
    (when-some [status (some->> path (re-matches repo-path) second
                                (get (:refused @state)))]
      (throw (ex-info (str "github answered " status " for " method " " path)
                      {:status status})))
    (let [st @state
          repo-of (fn [m] (get-in st [:repos (second m)]))]
      (cond
        ;; the blob the job log's redirect points at — no token here
        url
        (let [job (last (str/split (str url) #"/"))]
          {:status 200 :body (get (:logs st) job "") :content-type "text/plain"})

        (re-matches files-path path)
        (let [m (re-matches files-path path)]
          (vec (get-in (repo-of m) [:files (parse-long (nth m 2))])))

        (re-matches reviews-path path)
        (let [m (re-matches reviews-path path)]
          (vec (get-in (repo-of m) [:reviews (parse-long (nth m 2))])))

        (re-matches pull-path path)
        (let [m (re-matches pull-path path)]
          (or (get-in (repo-of m) [:pulls (parse-long (nth m 2))])
              (throw (ex-info "no such pull request" {:status 404}))))

        (re-matches pulls-path path)
        (newest-first
         (cond->> (vals (:pulls (repo-of (re-matches pulls-path path))))
           ;; `state=open` is GitHub's own filter; `all` drops nothing
           (= "open" (str (:state params)))
           (filter #(= "open" (str (:state % "open"))))))

        (re-matches checks-path path)
        (let [m (re-matches checks-path path)]
          (if-some [status (:checks-status (repo-of m))]
            (throw (ex-info (str "github answered " status " for " method
                                 " " path)
                            {:status status}))
            {:check_runs (vec (get-in (repo-of m) [:checks (nth m 2)]))}))

        (re-matches statuses-path path)
        (let [m (re-matches statuses-path path)]
          {:statuses (vec (get-in (repo-of m) [:statuses (nth m 2)]))})

        (re-matches runs-path path)
        (let [m (re-matches runs-path path)]
          {:workflow_runs (vec (get-in (repo-of m)
                                       [:runs (str (:head_sha params))]))})

        (re-matches jobs-path path)
        (let [m (re-matches jobs-path path)]
          {:jobs (vec (get-in (repo-of m) [:jobs (nth m 2)]))})

        (re-matches job-log-path path)
        (let [m (re-matches job-log-path path)
              job (nth m 2)]
          (case (:log-mode st)
            :redirect {:status 302 :body ""
                       :location (str blob-base job)}
            :zip {:status 200 :body "PK an archive"
                  :content-type "application/zip"}
            :missing {:status 404 :body "not found"}
            {:status 200 :body (get (:logs st) job "")
             :content-type "text/plain"}))

        (re-matches compare-path path)
        (let [[_ r from to] (re-matches compare-path path)]
          {:status (cond (= from to) "identical"
                         (contains? (get-in st [:repos r :ancestors to]) from) "ahead"
                         :else "diverged")})

        (re-matches branch-path path)
        (let [m (re-matches branch-path path)]
          (if-some [sha (get-in (repo-of m) [:branches (nth m 2)])]
            {:name (nth m 2) :commit {:sha sha}}
            (throw (ex-info "no such branch" {:status 404}))))

        (re-matches labels-path path)
        (let [m (re-matches labels-path path)]
          (swap! state update :labels conj
                 {:repository (second m) :number (parse-long (nth m 2))
                  :labels (:labels body)})
          (mapv (fn [l] {:name l}) (:labels body)))

        (re-matches rerun-path path)
        (let [m (re-matches rerun-path path)]
          (swap! state update :reruns (fnil conj [])
                 {:repository (second m) :run (parse-long (nth m 2))})
          nil)

        :else
        (if raw
          {:status 404 :body "not found"}
          (throw (ex-info (str "the fake github answers no " method " " path)
                          {:status 404})))))))

(defn fake-source
  "The REAL source over an in-memory GitHub: the translation, the
  window, the cursor arithmetic and the log reading all run.

  opts: :repos (a static list, default ckopsa/waymark), :repos-fn (the
  deployed spelling — a function asked at every pass), :cursor (a
  starting cursor for every repository, for the window's own test),
  :store (a cursor store as the wiring's, for the restart's test)."
  ([state] (fake-source state {}))
  ([state {:keys [cursor store] :as opts}]
   (->GitHubSource (fake-call state)
                   (repos-fn-of opts)
                   (atom {})
                   (atom 0)
                   (atom false)
                   (atom #{})
                   (atom {})
                   (or store
                       (when cursor {:load (constantly cursor)})))))
