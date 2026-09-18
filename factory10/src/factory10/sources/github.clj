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
  list here. `ForgeSource` has four verbs (what moved, one log tail,
  one label, the call count), which is ThreadSource's own argument
  applied a fourth time. `forge/pass!` writes the rows.

  THE WIRE is the REST API v3, with the version header pinned. Five
  routes are read and one is written:

      GET  /repos/{repo}/pulls                      the window
      GET  /repos/{repo}/pulls/{n}                  size and mergeable
      GET  /repos/{repo}/pulls/{n}/files            the paths
      GET  /repos/{repo}/pulls/{n}/reviews          the review state
      GET  /repos/{repo}/commits/{sha}/check-runs   the red runs
      GET  /repos/{repo}/actions/runs/{id}/jobs     …and their jobs
      GET  /repos/{repo}/actions/jobs/{id}/logs     the log tail
      POST /repos/{repo}/issues/{n}/labels          THE ONE WRITE

  THE CURSOR is GitHub's own `updated_at`, as gtasks's is Google's.
  The pulls listing is asked sorted by `updated` descending, and the
  read stops at the first pull request older than the cursor MINUS
  sixty seconds. The sixty seconds absorb clock skew and same-second
  writes; re-seeing a handful of pull requests costs nothing, because
  the pass writes a fact only when the fact moved. The cursor advances
  only when every configured repository answered, so a failure in the
  middle of a pass makes the next pass re-read rather than skip.

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
  a note in place of the tail, so the classifier reads why the log is
  missing instead of an empty field.

  THE ONE WRITE. A classified run's label goes to the issues labels
  route, which ADDS a label and removes none. The source pushes one
  label, from `factory10.mirror/label-for`, and then walks the hidden
  `stamp_label` door so the transition log carries the push. Nothing
  else here writes to GitHub.

  THE TOKEN is FACTORY10_GITHUB_TOKEN: read on pull requests, checks
  and actions logs, write on issues for labels, on the configured
  repositories only. FACTORY10_GITHUB_REPOS names them, comma
  separated; unset means ckopsa/waymark, which is the proving ground
  of R-8. No token means no source at all — `from-env` answers nil,
  the same nil-means-absent contract every other boundary keeps.

  PUNTS, recorded. A head that moves leaves the old head's red rows at
  `red`: the ci_run machine has no `supersede` door, and this bead
  adds no doors to a kind. The pass mints nothing for the dead head
  and counts those rows in its census. Reading B, the webhook door, is
  its own bead: when it lands it will tell this source to poll one
  repository now, and it will read nothing from the payload but the
  repository name."
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
  clock skew and same-second writes, narrow enough that the re-read
  set stays small."
  60)

(def default-repos
  "The repository the proving ground reads when nothing names one
  (R-8): this repository's own gate."
  ["ckopsa/waymark"])

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
  write GitHub stamped a hair before the last read is not lost. nil
  cursor reads everything — the first pass reads the world once."
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
  not computed it yet, which is the honest answer and not a guess."
  [pull]
  (or (get mergeable-states (word (:mergeable_state pull)))
      (when (false? (:mergeable pull)) "conflicted")
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
  (cond-> {:run_id (run-id repo (:id check))
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
  must follow, not rows it may drop."
  [this repo floor]
  (loop [page 1, out []]
    (let [items (vec (call! this "GET" (str "/repos/" repo "/pulls")
                            {:params {:state "all" :sort "updated"
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

(defn- check-runs!
  "Every check run on the head, as one page. `filter=latest` is
  GitHub's own: a check run that ran twice answers once."
  [this repo sha]
  (let [resp (call! this "GET"
                    (str "/repos/" repo "/commits/" sha "/check-runs")
                    {:params {:per_page page-size :filter "latest"}})]
    (vec (:check_runs resp))))

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

(defn- repo-pass!
  "One repository's whole read: the window of pull requests, each one's
  document, and the red check runs of every open head. A throw here is
  one repository's failure, which the poll catches."
  [this repo floor]
  (let [pulls (list-pulls! this repo floor)
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
                                (check-runs! this repo (:head_sha doc))))))
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

(defrecord GitHubSource [call repos cursor calls]
  forge/ForgeSource
  (forge-poll [this]
    (reset! calls 0)
    (let [floor (window-start @cursor)
          answers (mapv (fn [repo]
                          (try (assoc (repo-pass! this repo floor)
                                      :repo repo :ok? true)
                               (catch Exception e
                                 (warn! "the repository " repo
                                        " did not answer (" (ex-message e)
                                        "); its rows keep their stored truth")
                                 {:repo repo :ok? false})))
                        repos)
          answered (filterv :ok? answers)
          complete? (= (count answered) (count answers))]
      ;; the cursor moves only when EVERY repository answered, so a
      ;; failure in the middle of a pass re-reads rather than skips
      (when complete?
        (reset! cursor (high-water @cursor (mapcat :updates answered))))
      {:changes (into [] (mapcat :changes) answered)
       :checks (into [] (mapcat :checks) answered)
       :repositories (mapv :repo answers)
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

  (forge-calls [_] @calls))

(defn parse-repos
  "\"ckopsa/waymark, ckopsa/waymark-bench\" → the repositories to read,
  in order. Nothing named means the proving ground's own repository."
  [repos]
  (let [named (if (string? repos)
                (remove str/blank? (map str/trim (str/split (str repos) #",")))
                (remove str/blank? (map str repos)))]
    (or (not-empty (vec named)) default-repos)))

(defn http-source
  "The real boundary over GitHub.

  config: :token-fn (a zero-arg token source) or :token (the word
  itself), :repos (comma-separated string or seq), :base (the API
  base, for a test that wants a local server)."
  [{:keys [token token-fn repos base]}]
  (->GitHubSource (http-call {:token-fn (or token-fn (constantly token))
                              :base base})
                  (parse-repos repos)
                  (atom nil)
                  (atom 0)))

(defn from-env
  "The deployed boundary off FACTORY10_GITHUB_TOKEN and
  FACTORY10_GITHUB_REPOS. nil when the token is not configured, which
  is the nil-means-absent contract every other boundary keeps: no
  token, no source, and nothing starts."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (when-some [token (word (env "FACTORY10_GITHUB_TOKEN"))]
     (http-source {:token token :repos (env "FACTORY10_GITHUB_REPOS")}))))

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
  `seed-check!`, `seed-job!`, `seed-log!`, `log-mode!` and `down!`;
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

(defn seed-job!
  "One job of one workflow run, for the log hop the source walks when
  `details_url` names only the run."
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

(defn requests
  "Every request the source made, oldest first."
  [state]
  (:requests @state))

(defn labels-pushed
  "Every label push the source made, oldest first."
  [state]
  (:labels @state))

(defn cursor
  "The source's cursor — the highest `updated_at` it has seen."
  [source]
  @(:cursor source))

(def ^:private pulls-path #"/repos/([^/]+/[^/]+)/pulls")
(def ^:private pull-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)")
(def ^:private files-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)/files")
(def ^:private reviews-path #"/repos/([^/]+/[^/]+)/pulls/(\d+)/reviews")
(def ^:private checks-path #"/repos/([^/]+/[^/]+)/commits/([^/]+)/check-runs")
(def ^:private jobs-path #"/repos/([^/]+/[^/]+)/actions/runs/(\d+)/jobs")
(def ^:private job-log-path #"/repos/([^/]+/[^/]+)/actions/jobs/(\d+)/logs")
(def ^:private labels-path #"/repos/([^/]+/[^/]+)/issues/(\d+)/labels")

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
        (newest-first (vals (:pulls (repo-of (re-matches pulls-path path)))))

        (re-matches checks-path path)
        (let [m (re-matches checks-path path)]
          {:check_runs (vec (get-in (repo-of m) [:checks (nth m 2)]))})

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

        (re-matches labels-path path)
        (let [m (re-matches labels-path path)]
          (swap! state update :labels conj
                 {:repository (second m) :number (parse-long (nth m 2))
                  :labels (:labels body)})
          (mapv (fn [l] {:name l}) (:labels body)))

        :else
        (if raw
          {:status 404 :body "not found"}
          (throw (ex-info (str "the fake github answers no " method " " path)
                          {:status 404})))))))

(defn fake-source
  "The REAL source over an in-memory GitHub: the translation, the
  window, the cursor arithmetic and the log reading all run.

  opts: :repos (default ckopsa/waymark), :cursor (a starting cursor,
  for the window's own test)."
  ([state] (fake-source state {}))
  ([state {:keys [repos cursor]}]
   (->GitHubSource (fake-call state)
                   (parse-repos repos)
                   (atom cursor)
                   (atom 0))))
