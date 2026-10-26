(ns factory10.resources.pull-request
  "The `pull_request` kind: one GitHub pull request, mirrored through the
  engine's Mirror (docs/spec-change.md §3, R-C.1 and R-C.2; ticket
  d2ceedc9).

  ITS MACHINE IS THE SYNC MACHINE AND NOTHING ELSE. Every field is the
  adapter's to write, and no door here is a person's. `pull` makes one
  GitHub read per row, and one more of an open row's head checks.

  THE ROW CARRIES ITS CHECKS' VERDICT (docs/spec-change.md R-C.10;
  ticket 04b740e6). `checks_verdict` is the forge pass's own reading
  of the required checks — red, green, or pending for a run still
  going or one that died without a verdict — and a conflicted pull
  request is red as `merge-conflict`. Nothing time-based moves here:
  the waits and the held log stay the forge pass's. A verdict that
  moves opens `checks_red` or `checks_green`. A head whose red checks
  all died before their tests is re-run here, once (ticket 04e40648),
  and `checks_interrupted` tells the change.

  GITHUB'S MERGE, CLOSE AND REOPEN MOVE THE CHANGE (docs/spec-change.md
  R-C.7 and R-C.9). A pulled `gh_state` that moves opens an advance
  door — `merged`, `closed` or `reopened` — and the kind's own
  subscriptions walk `merge`, `close` or `reopen` on the change linked
  to the row, under the mirror's hand. A change the forge pass already
  moved refuses `wrong_state`, and the walk notes that as a skip.

  DISCOVERY ASKS THE HOUSE, NOT GITHUB. The kit's ForgeSource has no
  verb that lists a repository's open pull requests, and this kind must
  not cost a second GitHub read. So `discover` reads storage: the
  number of every `change` row in a repository an active `repo_policy`
  names, in any state but merged or closed. The forge pass mints those
  rows, so every pull request the house hears of is covered.

  GITHUB'S WEBHOOKS MARK A ROW STALE, AND THE PULL READS THE TRUTH
  (docs/spec-change.md §4.1, R-C.8; ticket 73fd079f). The `github`
  integration's events walk `mark_stale` on the row they name, and the
  engine pulls it at once; no payload is written. An event that names
  no row runs discovery once, so a pull request opened before its row
  was minted mints and fills it.

  PULL IS THE FORGE'S OWN `forge-pull`, one read per row. It writes
  nothing and moves no cursor: the forge pass's window and its `change`
  rows are untouched by this kind.

  THE ADAPTER IS CONNECTED AT BOOT. A kind is declared before the
  engine it serves exists, so the adapter holds the engine and the
  forge source in an atom that `connect!` fills. Until then it
  discovers nothing and every pull throws, so an engine with no forge
  mirrors nothing."
  (:require [factory10.bench :as bench]
            [factory10.mirror :refer [source-principal]]
            [factory10.sources.runs :as runs]
            [factorykit.forge :as kit]
            [waymark10.dsl :refer [defscenario resource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

;; the forge pass's own five minutes (forge/default-every-seconds)
(def ttl-seconds 300)
(def discover-every 300)

(def ^:private ended
  "The change states whose pull request is no longer the mirror's to
  find."
  #{"merged" "closed"})

(def ^:private forge-fields
  "What this kind keeps of the forge's change document, by the same
  names."
  [:repository :number :url :title :author :draft :labels
   :base_branch :head_branch :head_sha :mergeable :review_state
   :files_changed :lines_added :lines_removed :touched_paths])

(defn external-id
  "`<owner>/<name>#<number>`, the row's identity."
  [repository number]
  (str repository "#" number))

(defn- parse-external-id [xid]
  (when-some [[_ repository n] (re-matches #"(.+/.+)#(\d+)" (str xid))]
    [repository (parse-long n)]))

(defn- rows
  "Every row of `kind` matching `where`, decoded; none in an engine that
  does not declare the kind."
  [eng kind where]
  (if-some [rd (get (inv/resources eng) kind)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rd %)
            (store/with-tx st
              (fn [tx] (store/query-rows st tx kind where {:all true})))))
    []))

(defn open-pull-requests
  "The external ids discovery answers: one for each `change` row with a
  number, in a repository an active policy names, that is not merged or
  closed. Storage only."
  [eng]
  (let [repositories (into (sorted-set)
                           (keep #(some-> (get-in % [:data :repository])
                                          str not-empty))
                           (rows eng :repo_policy {:state :active}))]
    (into []
          (comp (mapcat #(rows eng :change {:repository %}))
                (remove #(contains? ended (name (:state %))))
                (keep (fn [row]
                        (when-some [n (get-in row [:data :number])]
                          (external-id (get-in row [:data :repository]) n))))
                (distinct))
          repositories)))

(defn- owning-change
  "{:change id} for the live change that holds this pull request's
  identity, `github:<owner>/<name>#<number>` — the change its `opened`
  advance links (ticket 20a962be) — or nil."
  [eng xid]
  (when eng
    (when-some [row (first (remove #(contains? ended (name (:state %)))
                                   (rows eng :change {:change_id (str "github:" xid)})))]
      {:change (str (:id row))})))

(defn- document
  "The forge's change document → this kind's. The two endings' instants
  ride the document's metadata (github/pull->doc), because the change
  kind has no field for them."
  [doc]
  (let [m (meta doc)]
    (cond-> (select-keys doc forge-fields)
      (:forge_state doc) (assoc :gh_state (:forge_state doc))
      (:merged_at m) (assoc :merged_at (:merged_at m))
      (:closed_at m) (assoc :closed_at (:closed_at m)))))

(defn rejected-verdict
  "A workflow the forge rejected on the head (a failed run with no job,
  read as a check marked `:rejected`, ticket 004188aa) → {:verdict :red
  :names [its file …]}, else nil. Read only where the checks' verdict
  says not yet: the checks that workflow would run never come."
  [checks]
  (when-some [names (seq (into [] (comp (filter :rejected)
                                        (keep :check_name)
                                        (map str)
                                        (distinct))
                               checks))]
    {:verdict :red :names (vec names)}))

(defn checks-verdict
  "The checks fields of one head: the kit's verdict over the required
  checks, then a rejected workflow, as the forge pass reads them. An
  interrupted run and a run still going are `pending`, never red; a
  conflicted pull request is red as `merge-conflict`. `checks` nil is
  a head not read.

  A red verdict reads the head's runs through `runs-of`, a thunk, when
  one is given (spec-change R-C.10, ticket 0fac9efc): when every red
  check is a job of a run that died before its tests
  (`runs/interrupted-run?`, the forge pass's own reading), the head is
  `pending` and `interrupted_runs` names those runs."
  ([required checks mergeable]
   (checks-verdict required checks mergeable nil))
  ([required checks mergeable runs-of]
   (let [v (when (some? checks)
             (or (kit/verdict {:forge :github :required required} checks)
                 (rejected-verdict checks)))
         red (when (= :red (:verdict v)) (mapv str (:names v)))
         died (when (and (seq red) runs-of (not= "conflicted" mergeable))
                (runs/interrupted-runs (distinct red) (runs-of)))
         names (cond-> (if died [] (vec red))
                 (= "conflicted" mergeable) (conj "merge-conflict"))]
     (cond
       (seq names) {:checks_verdict "red" :failing_checks (vec (distinct names))
                    :interrupted_runs nil}
       died {:checks_verdict "pending" :failing_checks nil :interrupted_runs died}
       (= :green (:verdict v)) {:checks_verdict "green" :failing_checks nil
                                :interrupted_runs nil}
       :else {:checks_verdict "pending" :failing_checks nil
              :interrupted_runs nil}))))

(defn- required-checks
  "The required checks of the repository's active policy; none, which
  requires every check on the head, where no policy names it."
  [eng repository]
  (bench/required-checks-of
   (when eng
     (some #(when (= repository (str (get-in % [:data :repository]))) %)
           (bench/policies eng :active)))))

(defn missing-checks
  "The required checks with NO run at all among `checks`: a check that
  is pending or running is not missing (ticket 498a089e). The forge
  pass's J7 reads the same list through `forge/missing-checks`."
  [required checks]
  (let [ran (into #{} (map #(str (:check_name %))) checks)]
    (into [] (comp (map str) (distinct) (remove ran)) required)))

(defn- behind-base
  "Whether `head` lacks the head of `base`: J7's own read, the forge's
  `ForgeCompare`, one compare call. nil where the source cannot compare
  or the forge does not answer."
  [source repository base head]
  (when (and base (satisfies? kit/ForgeCompare source))
    (try (boolean (kit/forge-behind? source repository base head))
         (catch Exception _ nil))))

(defn- missing-facts
  "The required checks with no run on the head, stamped with it, and —
  when one is missing and the source can compare — whether the head
  lacks its base (ticket 6a530e5d): the facts J7 writes, read here.
  `checks` nil is a head not read, and has none."
  [source repository required checks doc]
  (when-some [head (when (some? checks) (some-> (:head_sha doc) str not-empty))]
    (let [missing (missing-checks required checks)]
      {:missing_checks missing
       :missing_checks_head head
       :behind_base (when (seq missing)
                      (behind-base source repository
                                   (some-> (:base_branch doc) str not-empty)
                                   head))})))

(defn- checks-of
  "An open pull request's checks fields: one forge read, of its head's
  checks, and one compare of its base while a required check is
  missing from it."
  [eng source repository doc]
  (let [head (some-> (:head_sha doc) str not-empty)
        checks (when head (kit/forge-checks source repository head))
        required (required-checks eng repository)
        ;; a red head's runs, read once more and for that head only; a
        ;; forge that cannot say leaves the red as it is
        runs-of (when (and head (satisfies? runs/ForgeRerun source))
                  #(try (runs/forge-runs source repository head)
                        (catch Exception _ nil)))]
    (merge (assoc (checks-verdict required checks (:mergeable doc) runs-of)
                  :checks_head head)
           (missing-facts source repository required checks doc))))

;; ── a run that died before its tests, re-run by the pull (ticket 04e40648)
;;
;; The pull reads a head whose red checks all died before their tests as
;; pending, with `interrupted_runs`. It re-runs those runs ONCE per head
;; and writes the head as `reran_head`, carried over from the stored row
;; on every pull after, so each refresh of that head re-runs nothing. A
;; forge that refuses the re-run is logged once and tried again on the
;; next pull: `rerun-tries` tries a head, then it is let go. The forge
;; pass's J6 still re-runs until it is deleted, and each side leaves a
;; head the other already re-ran.

(def rerun-tries
  "How many pulls try one head's re-run while the forge refuses it."
  2)

(def ^:private rerun-fields
  "What the pull carries over from the stored row: no forge read says
  them."
  [:reran_head :rerun_refused_head :rerun_refusals])

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "factory10 pull_request: " parts))))

(defn- stored-data
  "The data of the row this pull request already has; nil before its
  birth, or with no engine."
  [eng xid]
  (when eng (:data (first (rows eng :pull_request {:external_id xid})))))

(defn- change-reran?
  "Whether the change already holds `head` as re-run, by the forge
  pass's J6: the pull leaves that head to it."
  [eng change-id head]
  (when (and eng change-id)
    (let [st (:storage eng)]
      (= head (some-> (store/with-tx st
                        (fn [tx] (store/load-row st tx :change (str change-id) {})))
                      (get-in [:data :rerun_head])
                      str)))))

(defn- rerun-spent? [d head]
  (and (= head (:rerun_refused_head d))
       (>= (long (or (:rerun_refusals d) 0)) (long rerun-tries))))

(defn- rerun-interrupted
  "`d` with the stored re-run fields carried over and, when its head's
  runs died before their tests and that head was not re-run yet, those
  runs re-run and the head written as `reran_head`."
  [eng source repository xid d]
  (let [d (merge d (into {} (remove (comp nil? val))
                         (select-keys (stored-data eng xid) rerun-fields)))
        head (some-> (:checks_head d) str not-empty)]
    (if (or (nil? head)
            (empty? (:interrupted_runs d))
            (not (satisfies? runs/ForgeRerun source))
            (= head (some-> (:reran_head d) str))
            (rerun-spent? d head)
            (change-reran? eng (:change d) head))
      d
      (try
        (doseq [run (:interrupted_runs d)]
          (runs/forge-rerun! source repository run))
        (assoc d :reran_head head)
        (catch Exception e
          (let [n (if (= head (:rerun_refused_head d))
                    (inc (long (or (:rerun_refusals d) 0)))
                    1)]
            (when (= 1 n)
              (warn! "the interrupted runs of " xid " at " head
                     " were not re-run (" (ex-message e)
                     "); the next pull tries again"))
            (assoc d :rerun_refused_head head :rerun_refusals n)))))))

(defn- pull-one [conn xid]
  (let [source (or (:source @conn)
                   (throw (ex-info "no forge is connected to the pull_request mirror"
                                   {:external-id xid})))
        [repository number] (or (parse-external-id xid)
                                (throw (ex-info (str "not a pull request id: " xid)
                                                {:external-id xid})))
        doc (or (kit/forge-pull source repository number)
                (throw (ex-info (str xid " answered no pull request")
                                {:external-id xid})))
        ;; a closed or merged pull request's checks are not read
        d (cond-> (document doc)
            (= "open" (:forge_state doc))
            (merge (checks-of (:engine @conn) source repository doc))
            :always (merge (owning-change (:engine @conn) xid)))
        d (rerun-interrupted (:engine @conn) source repository xid d)]
    ;; the compare's instant is not a fact: like J7, it is written only
    ;; with a pull whose facts moved
    [(cond-> d
       (some? (:behind_base d))
       (assoc :base_compared_at (str (java.time.Instant/now))))
     (str (hash d))]))

(defrecord ForgeMirror [conn]
  mirror/MirrorAdapter
  (discover [_]
    (if-some [eng (:engine @conn)] (open-pull-requests eng) []))
  (pull [_ xid] (pull-one conn xid))
  (pull-many [_ xids]
    ;; a number the forge answers 404 for is gone; any other failure is
    ;; the forge's, and throws for the whole batch
    (reduce (fn [m xid]
              (try (assoc m xid (pull-one conn xid))
                   (catch clojure.lang.ExceptionInfo e
                     (if (= 404 (:status (ex-data e)))
                       (assoc m xid :gone)
                       (throw e)))))
            {} xids))
  (push [_ _ _]
    (throw (ex-info "the pull_request mirror is pull-only" {}))))

(defn forge-mirror
  "A fresh adapter, connected to nothing."
  []
  (->ForgeMirror (atom nil)))

(def the-mirror
  "The adapter the module's own `pull_request` kind is declared with."
  (forge-mirror))

(defn- own-counter
  "The source as the mirror pulls through it: the same source, with its
  own call counter where it keeps one (ticket 8967d9d1). The forge
  census reads the source's counter as the pass's own calls, so a pull
  between passes must not bump it. Every other atom — the budget, the
  ETags, the cursor — stays shared."
  [source]
  (if (and (map? source) (instance? clojure.lang.IAtom (:calls source)))
    (assoc source :calls (atom 0))
    source))

(defn connect!
  "Hand the adapter the engine its discovery reads and the forge source
  its pull spends — the same source the forge pass runs, counting its
  calls apart from the pass's."
  ([eng source] (connect! the-mirror eng source))
  ([adapter eng source]
   (reset! (:conn adapter) {:engine eng :source (own-counter source)})
   adapter))

(defn mirror-calls
  "How many calls the adapter's pulls made since it was connected; nil
  when its source keeps no counter."
  [adapter]
  (some-> (:source @(:conn adapter)) :calls deref))

(def github-integration
  "The `github` integration row (R-C.8). It is the owner's to propose,
  with a secret holding the webhook's key, and another person's to
  approve; then GitHub's webhook posts pull_request,
  pull_request_review, check_run and check_suite to it."
  {:name "github"
   :system "GitHub, where the house's pull requests live"
   :id_header "X-GitHub-Delivery"
   :type_header "X-GitHub-Event"
   :signature {:header "X-Hub-Signature-256" :prefix "sha256="}})

(def ^:private by-number
  "A pull request's own events name its row by repository and number."
  {:external_id '(str (event "repository" "full_name") "#"
                      (event "pull_request" "number"))})

(defn- by-head
  "A check's events name the rows whose head is the commit it ran on:
  more than one is `ambiguous`, which is right for a shared head."
  [object]
  {:repository '(event "repository" "full_name")
   :head_sha (list 'event object "head_sha")})

(defn- by-its-pull-request
  "A check's events name the pull requests it ran for, and the first is
  its row by repository and number, whatever head the row holds yet
  (ticket eec51600). A check on no pull request names none."
  [object]
  {:external_id (list 'str '(event "repository" "full_name") "#"
                      (list 'event object "pull_requests" 0 "number"))})

(defn- no-row-owns
  "A check on a branch no pull_request row is born for, from the payload
  alone: one on no pull request (main, bench-test/*, profile-*), or one
  on a merge train's train/* branch, whose pull request the line opens
  and no change owns."
  [object branch-path]
  (list 'or
        (list 'not (list 'is-set (list 'event object "pull_requests" 0)))
        (list 'starts-with (apply list 'event branch-path) "train/")))

(def on-event
  "Every event says this moved, and the Mirror reads the rest. The
  header carries the event's name and not its action, so every action
  of each refreshes. An opened pull request may beat its row:
  discovery runs once, and the forge pass that mints its change is
  waited for ten minutes. A check finds its row by the pull request it
  names, so one that beats the row's new head still lands, and by its
  head when it names none; one no row could own is ignored, and so is
  a merge train's own pull request, which no change owns (ticket
  f81707f2). A refusal, as on a conflicted row, is the mayor's to hear."
  (mapv #(assoc % :source :github :door :mark_stale :tell ["mayor"])
        [{:type "pull_request" :match by-number
          :discover true :retry-unmatched-for 600
          :ignore-when '(starts-with (event "pull_request" "head" "ref") "train/")}
         {:type "pull_request_review" :match by-number}
         {:type "check_run" :match (by-its-pull-request "check_run")
          :retry-unmatched-for 600
          :ignore-when (no-row-owns "check_run" ["check_run" "check_suite" "head_branch"])}
         {:type "check_run" :match (by-head "check_run")}
         {:type "check_suite" :match (by-its-pull-request "check_suite")
          :retry-unmatched-for 600
          :ignore-when (no-row-owns "check_suite" ["check_suite" "head_branch"])}
         {:type "check_suite" :match (by-head "check_suite")}]))

(defn- red-moved?
  "Red now, and not red before — or red with other names at a new
  head."
  [was doc]
  (and (= "red" (:checks_verdict doc))
       (or (not= "red" (:checks_verdict was))
           (and (not= (:checks_head doc) (:checks_head was))
                (not= (set (:failing_checks doc))
                      (set (:failing_checks was)))))))

(def ^:private followed-facts
  "What the change follows of its pull request beside its head (ticket
  6a530e5d): the fields `facts_moved` watches and `follow_facts` writes."
  [:draft :touched_paths :missing_checks :missing_checks_head :behind_base])

(defn- rerun-moved?
  "The pull re-ran a new head, or the head it already re-ran died before
  its tests again after it ran (ticket 04e40648)."
  [was doc]
  (let [h (:reran_head doc)]
    (boolean
     (and (some? h)
          (or (not= h (:reran_head was))
              (and (= h (:checks_head doc))
                   (seq (:interrupted_runs doc))
                   (empty? (:interrupted_runs was))))))))

(def advances
  "The doors a moved `gh_state` or `checks_verdict` opens (R-C.9,
  R-C.10): after observe_external, and only on a changed pull. A
  discovery mint opens none of them, and pending opens none."
  {:merged {:when (fn [was doc] (and (= "merged" (:gh_state doc))
                                     (not= "merged" (:gh_state was))))}
   :closed {:when (fn [was doc] (and (= "closed" (:gh_state doc))
                                     (not= "closed" (:gh_state was))))}
   :reopened {:when (fn [was doc] (and (= "open" (:gh_state doc))
                                       (= "closed" (:gh_state was))))}
   :checks_red {:when red-moved?}
   :checks_green {:when (fn [was doc] (and (= "green" (:checks_verdict doc))
                                           (not= "green" (:checks_verdict was))))}
   ;; R-C.5, ticket 423164bf: the change's head follows its pull request
   :head_moved {:when (fn [was doc] (or (not= (:head_sha doc) (:head_sha was))
                                        (not= (:mergeable doc) (:mergeable was))))}
   ;; ticket 6a530e5d: the facts the forge pass's observe and J7 write
   :facts_moved {:when (fn [was doc] (not= (select-keys was followed-facts)
                                           (select-keys doc followed-facts)))}
   ;; ticket 04e40648: the pull re-ran a head, or it died again
   :checks_interrupted {:when rerun-moved?}
   ;; ticket 20a962be: the birth of an open pull request a change holds
   ;; links that change, with no forge pass. Only a birth: a stored row
   ;; already has its gh_state
   :opened {:on-mint true
            :when (fn [was doc] (and (nil? (:gh_state was))
                                     (= "open" (:gh_state doc))
                                     (some? (:change doc))))}})

(defn- follows
  "One subscription (R-C.7): this kind's `door` walks `walks` on each
  change whose `pull_request` is the moved row, as the mirror, given
  `input` — the door's fields, each from a field of the moved row."
  ([door walks] (follows door walks nil))
  ([door walks input]
   {:name (str "change-follows-" door)
    :kinds ["pull_request"] :actions [door]
    :receiver (cond-> {:type "door" :kind "change" :door walks
                       :id_from "pull_request=(self)"}
                input (assoc :input input))
    :owner (:id source-principal)}))

(def subscriptions
  "The change follows its pull request: GitHub's merge, close and
  reopen, each one walk; and its checks (R-C.9, ticket 995570f0): a
  red verdict fails or sticks the change by its rounds, a green one
  recovers it, each naming the head it judged; and its head and merge
  word (R-C.5, ticket 423164bf), which `refresh_head` writes; and its
  draft, touched paths, missing checks and behind-base (ticket
  6a530e5d), which `follow_facts` writes."
  [(follows "merged" "merge")
   (follows "closed" "close")
   (follows "reopened" "reopen")
   (follows "checks_red" "checks_red"
            {:failing_checks "failing_checks" :head_sha "checks_head"})
   (follows "checks_green" "recover" {:head_sha "checks_head"})
   (follows "head_moved" "refresh_head" {:head_sha "head_sha" :mergeable "mergeable"})
   (follows "facts_moved" "follow_facts"
            {:draft "draft" :touched_paths "touched_paths"
             :missing_checks "missing_checks"
             :missing_checks_head "missing_checks_head"
             :behind_base "behind_base" :base_compared_at "base_compared_at"})
   ;; ticket 04e40648: the change records the re-run, or notes it died again
   (follows "checks_interrupted" "rerun_interrupted" {:head_sha "reran_head"})
   ;; ticket 20a962be: a pull request born for a change links it
   {:name "change-links-its-opened-pull-request"
    :kinds ["pull_request"] :actions ["opened"]
    :receiver {:type "door" :kind "change" :door "link"
               :id_from "change" :input {:pull_request "(self)"}}
    :owner (:id source-principal)}])

(def ^:private a-mirrored-pull-request
  {:external_id "ckopsa/waymark#31"
   :repository "ckopsa/waymark"
   :number 31
   :title "A pull request the house mirrors"
   :gh_state "open"})

(defscenario a-seat-does-not-mark-a-pull-request-stale
  "Every field is the forge's and every door is the engine's: a seat
   that thinks a row is out of date has nothing to write here."
  {:kind    :pull_request
   :attempt :mark_stale
   :row     {:state :fresh :data a-mirrored-pull-request}
   :as      {:id "code-seat" :type :agent}
   :expect  {:refused :system-only}})

(defscenario a-person-does-not-mark-a-pull-request-unreachable
  "Nor does a person: whether GitHub answers is the sync's to record."
  {:kind    :pull_request
   :attempt :mark_unreachable
   :row     {:state :fresh :data a-mirrored-pull-request}
   :as      {:id "colton" :type :person}
   :expect  {:refused :system-only}})

(defn pull-request-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :pull_request
     :plural "pull_requests"
     ;; the day job's work, like `change`
     :nav :secondary
     :summary "{data.repository}#{data.number} · {data.title}"
     :label-template "{data.repository}#{data.number}"
     :display {:title "{data.title}"}
     :unique [[:external_id]]
     ;; the sync leaves every state it reaches, and no seat does: the
     ;; cracks audit reads it here
     :left-by [{:in #{:stale :unreachable :conflicted} :by :mirror
                :via :observe_external :to :fresh}
               {:in #{:fresh} :by :mirror :via :mark_stale :to :stale}]
     :schema
     [:map
      [:repository {:optional true :filter #{:eq :in}
                    :x-display
                    {:label "The repository"
                     :help "owner/name, as GitHub spells it. ckopsa/waymark is one."}}
       [:maybe [:string {:max 140}]]]
      [:number {:optional true
                :x-display
                {:label "The pull request number"
                 :help "The number GitHub gave the pull request in its repository."}}
       [:maybe [:int {:min 1}]]]
      [:url {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 500}]]]
      [:title {:optional true
               :x-display
               {:raw true :label "Title"
                :help "The pull request's title, exactly as GitHub shows it."}}
       [:maybe [:string {:max 400}]]]
      [:author {:optional true
                :not-a-ref "A GitHub login: a handle, not a row id or a principal here."
                :x-display
                {:label "Who opened it"
                 :help "The GitHub login of the account that opened it."}}
       [:maybe [:string {:max 120}]]]
      [:draft {:optional true
               :x-display
               {:label "A draft"
                :help "True while GitHub marks it a draft, which no merge takes."}}
       [:maybe :boolean]]
      [:labels {:optional true
                :x-display
                {:label "Its labels"
                 :help "The labels on the pull request at GitHub, by name."}}
       [:maybe [:vector [:string {:max 100}]]]]
      [:base_branch {:optional true
                     :x-display
                     {:label "The base branch"
                      :help "The branch it would merge into."}}
       [:maybe [:string {:max 200}]]]
      [:head_branch {:optional true
                     :x-display
                     {:label "The head branch"
                      :help "The branch its commits are on."}}
       [:maybe [:string {:max 200}]]]
      [:head_sha {:optional true :filter #{:eq}
                  :x-display
                  {:label "The head commit"
                   :help "The full sha of the head branch's newest commit."}}
       [:maybe [:string {:max 64}]]]
      [:mergeable {:optional true :filter #{:eq :in}
                   :x-display
                   {:label "Whether it merges"
                    :help "What GitHub says a merge would do now."
                    :choices {"clean" "It merges cleanly"
                              "conflicted" "It conflicts with its base"
                              "blocked" "A rule of the repository blocks the merge"
                              "unknown" "GitHub has not worked it out yet"}}}
       [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
      [:review_state {:optional true :filter #{:eq :in}
                      :x-display
                      {:label "What the reviews say"
                       :help "The reviews read together: a request for changes outranks an approval."
                       :choices {"pending" "Nobody has reviewed it"
                                 "approved" "A reviewer approved it"
                                 "changes_requested" "A reviewer asked for changes"
                                 "commented" "Reviewers commented and did not decide"}}}
       [:maybe [:enum "pending" "approved" "changes_requested" "commented"]]]
      [:files_changed {:optional true
                       :x-display
                       {:label "Files changed"
                        :help "How many files the pull request changes."}}
       [:maybe [:int {:min 0}]]]
      [:lines_added {:optional true
                     :x-display
                     {:label "Lines added"
                      :help "How many lines it adds, as GitHub counts them."}}
       [:maybe [:int {:min 0}]]]
      [:lines_removed {:optional true
                       :x-display
                       {:label "Lines removed"
                        :help "How many lines it removes, as GitHub counts them."}}
       [:maybe [:int {:min 0}]]]
      [:touched_paths {:optional true
                       :x-display
                       {:label "The paths it touches"
                        :help "The files it changes, up to the first hundred. Read while it is open."}}
       [:maybe [:vector [:string {:max 400}]]]]
      [:gh_state {:optional true :filter #{:eq :in}
                  :x-display
                  {:label "Where it stands on GitHub"
                   :help "GitHub's own state: a closed pull request with a merge time is merged."
                   :choices {"open" "Open"
                             "merged" "Merged"
                             "closed" "Closed without a merge"}}}
       [:maybe [:enum "open" "merged" "closed"]]]
      [:merged_at {:optional true
                   :x-display
                   {:label "When it merged"
                    :help "The moment GitHub merged it."}}
       [:maybe :waymark/instant]]
      [:closed_at {:optional true
                   :x-display
                   {:label "When it closed"
                    :help "The moment GitHub closed it, merged or not."}}
       [:maybe :waymark/instant]]
      [:checks_verdict {:optional true :filter #{:eq :in}
                        :x-display
                        {:label "What its checks say"
                         :help "The required checks on the head, read together. A run that died without a verdict is pending, never red."
                         :choices {"green" "Every required check passed"
                                   "red" "A required check failed, or it conflicts with its base"
                                   "pending" "A required check has not finished"}}}
       [:maybe [:enum "green" "red" "pending"]]]
      [:failing_checks {:optional true
                        :x-display
                        {:label "The checks that failed"
                         :help "Their names while it is red; merge-conflict when it conflicts with its base."}}
       [:maybe [:vector [:string {:max 200}]]]]
      [:checks_head {:optional true
                     :x-display
                     {:label "The commit the checks were read at"
                      :help "The head commit the verdict above speaks for."}}
       [:maybe [:string {:max 64}]]]
      [:missing_checks {:optional true
                        :x-display
                        {:label "Required checks that never ran"
                         :help "The required checks with no run at all on the head: a branch cut before its base gained them."}}
       [:maybe [:vector [:string {:max 200}]]]]
      [:missing_checks_head {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 64}]]]
      [:behind_base {:optional true
                     :x-display
                     {:label "Behind its base"
                      :help "True when the head lacks its base's newest commit. Read only while a required check is missing."}}
       [:maybe :boolean]]
      [:base_compared_at {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 40}]]]
      [:interrupted_runs {:optional true
                          :x-display
                          {:label "The runs that died before their tests"
                           :help "The workflow runs on the head whose red checks died in set-up, cancelled or timed out, so the verdict is pending and not red."}}
       [:maybe [:vector [:string {:max 40}]]]]
      ;; the head the pull re-ran the interrupted runs of, and the head
      ;; the forge refused that re-run on, with how many times (ticket
      ;; 04e40648)
      [:reran_head {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 64}]]]
      [:rerun_refused_head {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 64}]]]
      [:rerun_refusals {:optional true :x-display {:hidden true}}
       [:maybe [:int {:min 0}]]]
      ;; the live change that holds this pull request's identity, read
      ;; by the pull, so a birth links it (ticket 20a962be)
      [:change {:optional true :kind :change :x-display {:hidden true}}
       [:maybe :waymark/ref]]]
     :filterable {:state #{:eq :in}}
     :on-event on-event
     :subscriptions subscriptions
     :links [{:rel "origin" :href "{data.url}" :external true
              :summary "The pull request on GitHub"}]
     :scenarios [a-seat-does-not-mark-a-pull-request-stale
                 a-person-does-not-mark-a-pull-request-unreachable]}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     :advances advances})))

(def pull-request (pull-request-resource the-mirror))
