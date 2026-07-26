(ns workqueue10.sources.gtasks
  "Google Tasks as a TaskSource: the lists the household already keeps
  in a pocket, mirrored into the queue and marked done from it. Home
  Assistant covers capture on the LAN and keeps the \"todo\" tag; this
  is its own tag because a Google task's identity is scoped to the
  list it lives in and its authority is Google's, so folding the two
  under one name would make the routing tag lie about who owns the
  row.

  THE WIRE is Tasks v1, one tasks.list per configured list with the
  pages followed to the end — maxResults caps at 100, so a long list
  is several round trips and a truncated page would read as a
  list-wide deletion. A list that will not answer throws, and nothing
  here catches broadly on the way out: the confluence reads that as
  one unreachable source and the stored rows keep serving with their
  honest synced_at, which is the whole point of failing loudly rather
  than answering an empty feed.

  THE CURSOR is what makes this source unlike every other one the
  queue drinks from. Google offers updatedMin, so discover asks what
  changed since last time instead of re-reading the world. The cursor
  is the highest `updated` any task has shown us — Google's clock
  stamps that, never ours, so storing `now` would silently skip
  everything written during the pass — and each query re-asks from
  sixty seconds BEHIND it, which absorbs clock skew and same-second
  writes at the cost of re-seeing a handful of rows the mirror's
  per-row observe already tolerates. The cursor advances only once
  every configured list has answered, so a failure mid-pass re-reads
  rather than skips. There is no syncToken here: Calendar has one,
  Tasks does not.

  IDENTITY is \"tasklist/taskid\" — the source-local half of the
  confluence's \"gtasks:tasklist/taskid\". Google's task ids are
  unique within their list and the list is the thing an operator
  configures, so the pair is the only identity that survives a second
  list being added.

  THE LIST IS ALSO A ROW. Carrying the list only inside the task's
  identity meant the queue could route by it and never say it, so
  this source is a TaskListSource as well: list-discover /
  list-pull / list-pull-many mirror the account's task lists into
  :task_list, and every task document carries :list_key — its own
  list, source-local, which the confluence namespaces into the
  matching list row's external id. The two feeds share the transport,
  the credential and the tag, and nothing else.

  WHICH LISTS: all of them, unless told otherwise. Unset
  WORKQUEUE10_GTASKS_LISTS means every list the credential can see,
  read from users/@me/lists at the head of each discovery pass; an
  explicit comma-separated set narrows to exactly those and never
  calls that route at all. The household keeps ten lists and wanted
  ten, and a queue that silently mirrored one of them was the kind of
  wrong that looks like nothing at all. The cost is one extra round
  trip per pass in the all-lists posture, which is the honest price of
  not making the operator re-list Google's own inventory by hand.

  ETAGS are Google's own per-task etag with this namespace's
  translation revision composed onto it. Google mints a real version,
  so unlike Home Assistant no content hash is needed to notice the
  AUTHORITY changing; what a remote etag can never notice is US
  changing — a fix to the mapping below would leave every stored row
  serving the old translation forever, because the upstream version
  never moved. sources.waymark records that lesson and translation-rev
  is its spelling here: bump it whenever task->doc's output changes
  shape and every stored row re-observes on its next pull. The etag
  PRESENTED upstream on a write is the raw one — the fence is the
  authority's — and only what we report back to the mirror carries the
  revision.

  DELETIONS NEED THREE PARAMETERS TOGETHER. updatedMin alone hides
  deleted tasks and hides completed-then-cleared ones, so every list
  read sends showDeleted, showHidden and showCompleted at once and
  translates `deleted` or `hidden` into gone ({:status 404}). Miss one
  and nothing fails — the rows simply stop being reconciled, which is
  the failure mode that looks like nothing at all. A task absent from
  a list that ANSWERED is gone for the same reason: with all three
  flags set the list is the whole truth. A whole list that did not
  answer is unreachability and not deletion, exactly as the Home
  Assistant source has it.

  DUE TIMES are date-only by law: Google discards the time portion
  when a due is set, so the only honest reading is the day, and a day
  widens to its closing midnight UTC — the chore-source law's fourth
  writing, so overdue flips the morning after regardless of which
  authority the row came from. That the API returns the day dressed as
  an RFC 3339 instant is cosmetic; the first ten characters are the
  fact, and reading further would invent a precision the field cannot
  hold.

  THE CREDENTIAL is calendar10.oauth/access-token-fn, which in spite
  of its module name is a general OAuth 2.0 refresh-token grant and
  not a calendar-specific one. Recorded rather than fixed: a shared
  module would be the tidier home for it and moving it is a larger
  edit than this source deserves. The refresh token it spends must
  carry https://www.googleapis.com/auth/tasks, and the stored one was
  minted for the calendar scope alone — no code here can widen a
  scope, so the household re-consents once. Until it does, every read
  through this source throws, which is exactly the shape an
  un-mintable credential should have.

  PUNTS, recorded. Subtasks flatten: Google's parent/position express
  a hierarchy the canonical task doc has no room for, so a subtask
  becomes a task with its parent's title prefixed into :detail, and
  the parent's title is looked up cheaply from the list we already
  hold (a singular pull spends one extra GET for it, and a parent that
  cannot be read leaves the prefix off rather than failing the child).
  Ordering is dropped rather than half-honoured — position is Google's
  manual sort and the queue ranks by its own :priority and due date.
  assignmentInfo and links are ignored, and Google's notion of
  assignment deliberately does NOT become a :member. Nothing is born
  here: capture-to-Google is the create-push law, out of this cut, and
  \"todo\" remains the capture tag."
  (:require [calendar10.oauth :as oauth]
            [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [waymark10.wire :as wire])
  (:import (java.net URI URLDecoder URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(def api-base "https://tasks.googleapis.com/tasks/v1")

;; the API's own ceiling (the default is 20) — asking for more is a
;; 400, and asking for less is round trips for nothing
(def page-size 100)

;; how far behind the cursor each incremental query re-asks from. Wide
;; enough for clock skew and same-second writes, narrow enough that
;; the re-seen set stays small; the mirror de-duplicates by id anyway.
(def overlap-seconds 60)

(def translation-rev
  "This namespace's translation version, composed into every etag the
  source REPORTS (never the raw one it presents upstream on a write).
  Google's etag moves when Google's task moves and cannot move when
  task->doc does — bump this whenever the canonical document's shape
  changes here, and every stored row re-observes on its next pull
  instead of serving the old translation forever."
  ;; g2: every task document grew :list_key, so every stored row's
  ;; translation is out of date and must re-observe — which is the
  ;; whole reason this constant exists.
  "g2")

;; ── the translation ─────────────────────────────────────────────────

(defn due->instant
  "Google's due → the canonical instant string. The field records a
  DATE (the API discards the time portion on write), so only the day
  is read and it widens to the day's closing midnight UTC — the
  chore-source law. A due that will not parse yields nil rather than
  dropping the whole pass: one unreadable field should not cost a
  list."
  [due]
  (when-not (str/blank? (str due))
    (try (str (.plusDays (LocalDate/parse (subs (str due) 0 10)) 1)
              "T00:00:00Z")
         (catch DateTimeParseException _ nil)
         (catch StringIndexOutOfBoundsException _ nil))))

(defn task->doc
  "One Google task → the canonical task doc. parent-title is the
  flattening punt's whole implementation: a subtask carries its
  parent's title at the head of :detail so the row reads standalone,
  and nil (no parent, or a parent we could not read) simply leaves it
  off. Deletion is not a status here — a deleted or cleared task is
  gone, which the callers below raise as {:status 404}."
  [task parent-title]
  {:title (:title task)
   :status (case (:status task)
             "needsAction" "open"
             "completed" "done")
   :due_at (due->instant (:due task))
   :detail (not-empty (str/join " — " (remove str/blank?
                                              [parent-title (:notes task)])))
   :source_ui_href (some-> (:webViewLink task) str not-empty)})

(defn- rev-etag
  "Google's etag + this namespace's translation revision. A task with
  no etag reports none — nil means 'no version' to the mirror, which
  re-applies; composing the revision onto nothing would mint one
  constant etag for every row and freeze the lot."
  [etag]
  (when-not (str/blank? (str etag))
    (str etag "|" translation-rev)))

(defn gone?
  "Deleted, or completed-then-cleared. Both are gone to the queue —
  the list still shows them (that is what showDeleted and showHidden
  buy) precisely so the absence is an OBSERVATION rather than a
  silence."
  [task]
  (or (true? (:deleted task)) (true? (:hidden task))))

;; ── identity ────────────────────────────────────────────────────────

(defn split-id
  "\"MTIzNA/NTY3OA\" → [tasklist taskid]. The split is on the FIRST
  slash only, so a task id carrying one survives the round trip."
  [id]
  (let [[tasklist taskid] (str/split (str id) #"/" 2)]
    (when (str/blank? (str taskid))
      (throw (ex-info (str "google tasks id " (pr-str (str id))
                           " carries no tasklist/task split") {})))
    [tasklist taskid]))

(defn- join-id [tasklist taskid]
  (str tasklist "/" taskid))

;; ── the wire ────────────────────────────────────────────────────────

(defn- path-encode ^String [s]
  ;; a path segment, not a form field: the encoder's plus is a space
  ;; here, and "@" is legal unencoded in a segment — which matters
  ;; because "@default" is how Google spells the account's own list
  (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
      (str/replace "+" "%20")
      (str/replace "%40" "@")))

(defn- query-string [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "="
                        (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
                 params)))

(defn- tasks-path
  ([tasklist] (str "/lists/" (path-encode tasklist) "/tasks"))
  ([tasklist taskid]
   (str (tasks-path tasklist) "/" (path-encode taskid))))

(def lists-path
  "The tasklists collection — the account's own inventory of lists.
  \"@me\" is the only user this credential can ever mean."
  "/users/@me/lists")

(defn- list-path [tasklist]
  (str lists-path "/" (path-encode tasklist)))

(defn http-call
  "The real transport: (fn [method path {:keys [params body if-match]}])
  → the parsed body. The bearer is asked for PER REQUEST so a token-fn
  that re-mints never goes stale in a header map, and a refusal to
  mint throws from inside the call — one unreachable feed, which is
  the posture calendar10.oauth documents. Non-2xx throws ex-info
  carrying :status: 404 for a gone task, 412 for a failed If-Match,
  anything else the caller reads as unreachable."
  [{:keys [token-fn base]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        base (str/replace (str (or base api-base)) #"/+$" "")]
    (fn [^String method path {:keys [params body if-match]}]
      (let [url (str base path (when (seq params)
                                 (str "?" (query-string params))))
            builder (-> (HttpRequest/newBuilder (URI/create url))
                        (.timeout (Duration/ofSeconds 20))
                        (.header "authorization" (str "Bearer " (token-fn)))
                        (.header "content-type" "application/json"))
            ^HttpRequest$Builder builder (if if-match
                                           (.header builder "if-match" if-match)
                                           builder)
            publisher (if body
                        (HttpRequest$BodyPublishers/ofString
                         (wire/write-json body) StandardCharsets/UTF_8)
                        (HttpRequest$BodyPublishers/noBody))
            req (-> builder (.method method publisher) (.build))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)]
        (when (>= status 400)
          (throw (ex-info (str "google tasks answered " status " for "
                               method " " path)
                          {:status status :body (.body resp)})))
        ;; the body is judged only after the status: a proxy's
        ;; plain-text refusal must not become a parse crash that
        ;; erases the status the caller needs (waymark-t6s)
        (try (some-> ^String (.body resp) not-empty wire/read-json)
             (catch Exception _
               (when (< status 400)
                 (throw (ex-info (str "google tasks answered " status
                                      " with a body that is not JSON")
                                 {:status status :body (.body resp)})))))))))

;; ── the cursor ──────────────────────────────────────────────────────

(defn- ->instant [s]
  (try (Instant/parse (str s)) (catch Exception _ nil)))

(defn high-water
  "The cursor after a pass: the latest `updated` anything has shown us,
  the standing cursor included so a pass that saw nothing new does not
  walk it backwards. Google's stamps, never our clock."
  [cursor updates]
  (some->> (keep ->instant (cons cursor updates))
           seq
           (reduce (fn [a b] (if (pos? (compare b a)) b a)))
           str))

(defn updated-min
  "The cursor as the query bound: sixty seconds behind, so a write
  Google stamped a hair before our last read is not lost forever. nil
  cursor asks for everything — the first pass reads the world once."
  [cursor]
  (when-some [^Instant t (->instant cursor)]
    (str (.minusSeconds t overlap-seconds))))

;; ── the source ──────────────────────────────────────────────────────

(defn- list-tasks!
  "Every task in one list (since `since`, or all of them), pages
  followed. The three show-flags ride together on purpose — see the
  namespace docstring; dropping any one of them turns deletions into
  silence."
  [{:keys [call]} tasklist since]
  (loop [page nil, out []]
    (let [resp (call "GET" (tasks-path tasklist)
                     {:params (cond-> {:showDeleted true
                                       :showHidden true
                                       :showCompleted true
                                       :maxResults page-size}
                                since (assoc :updatedMin since)
                                page (assoc :pageToken page))})
          items (into out (:items resp))]
      (if-some [next-page (some-> (:nextPageToken resp) str not-empty)]
        (recur next-page items)
        items))))

(defn- get-task! [{:keys [call]} tasklist taskid]
  (call "GET" (tasks-path tasklist taskid) {}))

(defn- all-lists!
  "Every task list the credential can see, pages followed to the end
  for the same reason the task pages are: a truncated inventory reads
  as lists that simply do not exist. No show-flags here — google's
  tasklists collection has no deleted-but-listed state; a list is
  either in the inventory or it is gone."
  [{:keys [call]}]
  (loop [page nil, out []]
    (let [resp (call "GET" lists-path
                     {:params (cond-> {:maxResults page-size}
                                page (assoc :pageToken page))})
          items (into out (:items resp))]
      (if-some [next-page (some-> (:nextPageToken resp) str not-empty)]
        (recur next-page items)
        items))))

(defn- get-list! [{:keys [call]} tasklist]
  (call "GET" (list-path tasklist) {}))

(defn- list-ids!
  "The lists this source mirrors: the configured set when there is
  one, the account's whole inventory otherwise. The narrowed posture
  never asks google what it has — an operator who named the lists has
  already answered that question."
  [{:keys [lists] :as this}]
  (or lists
      (into [] (keep #(some-> (:id %) str not-empty)) (all-lists! this))))

(defn list->doc
  "One google task list → the canonical list doc. It is a title and
  nothing else: google publishes no per-list web URL a person could
  be sent to, so the origin link honestly omits rather than pointing
  at tasks.google.com and hoping."
  [tasklist]
  {:title (:title tasklist)})

(defn- by-id [tasks]
  (into {} (map (juxt :id identity)) tasks))

(defn- decorate
  "The canonical doc + the API route back to the row + the list it
  lives in. The BROWSER hop is Google's own webViewLink, which
  task->doc already carried through — the one origin link in the queue
  that no client of ours had to invent. :list_key is source-local
  here; the confluence namespaces it into the :task_list row's
  external id, the same way it stamps :source."
  [base tasklist taskid doc]
  (assoc doc
         :source_href (str base (tasks-path tasklist taskid))
         :list_key tasklist))

(defn- decorate-list
  [base tasklist doc]
  (assoc doc :source_href (str base (list-path tasklist))))

(defrecord GoogleTasksSource [call base lists cursor]
  conf/TaskSource
  (source-discover [this]
    (let [since (updated-min @cursor)
          ;; every list is read BEFORE the cursor moves: a list that
          ;; throws mid-pass leaves the cursor where it was, so the
          ;; next pass re-reads rather than skipping the window
          pages (mapv (fn [tasklist] [tasklist (list-tasks! this tasklist since)])
                      (list-ids! this))
          seen (mapcat second pages)]
      (reset! cursor (high-water @cursor (map :updated seen)))
      (into []
            (comp (mapcat (fn [[tasklist tasks]]
                            (keep #(when (and (not (gone? %))
                                              (= "needsAction" (:status %)))
                                     (join-id tasklist (:id %)))
                                  tasks)))
                  (distinct))
            pages)))

  (source-pull [this id]
    (let [[tasklist taskid] (split-id id)
          task (get-task! this tasklist taskid)]
      (when (gone? task)
        (throw (ex-info (str id " is deleted or cleared in google tasks")
                        {:status 404})))
      (let [parent-title (when-some [parent (some-> (:parent task) str not-empty)]
                           ;; the flattening punt's one extra hop; a
                           ;; parent we cannot read costs the prefix,
                           ;; never the child
                           (try (:title (get-task! this tasklist parent))
                                (catch Exception _ nil)))]
        [(decorate base tasklist taskid (task->doc task parent-title))
         (rev-etag (:etag task))])))

  (source-pull-many [this ids]
    (let [pairs (mapv split-id ids)
          ;; one whole-list read per list the batch mentions, not one
          ;; GET per id — and no updatedMin, because a batch asks about
          ;; rows the cursor has long since passed
          indexed (into {}
                        (map (fn [tasklist]
                               [tasklist (by-id (list-tasks! this tasklist nil))]))
                        (distinct (map first pairs)))]
      (into {}
            (map (fn [[tasklist taskid]]
                   (let [task (get-in indexed [tasklist taskid])]
                     [(join-id tasklist taskid)
                      (cond
                        ;; absent from a list that ANSWERED with every
                        ;; show-flag set, or present and flagged: both
                        ;; are the observed deletion the :on-gone
                        ;; policy reads. A list that did not answer at
                        ;; all threw above and never reaches here.
                        (or (nil? task) (gone? task)) :gone
                        :else
                        [(decorate base tasklist taskid
                                   (task->doc task (get-in indexed
                                                           [tasklist
                                                            (:parent task)
                                                            :title])))
                         (rev-etag (:etag task))])])))
            pairs)))

  (source-push [this id document]
    (let [[tasklist taskid] (split-id id)
          current (get-task! this tasklist taskid)]
      (when (gone? current)
        (throw (ex-info (str id " is deleted or cleared in google tasks")
                        {:status 404})))
      (case (conf/push-plan document (:status (task->doc current nil)))
        :noop (rev-etag (:etag current))
        :complete
        ;; status is the ONLY field that travels: the queue's local
        ;; writes are its own ranking, and re-sending a title we merely
        ;; mirrored would overwrite an edit made on the phone
        (let [updated (call "PATCH" (tasks-path tasklist taskid)
                            {:body {:status "completed"}
                             ;; the RAW upstream etag — a phone-side
                             ;; edit between our read and our write
                             ;; fails the push (412) and lands the row
                             ;; conflicted rather than losing the edit
                             :if-match (:etag current)})]
          (rev-etag (:etag updated))))))

  (source-create [_ _document]
    (throw (ex-info (str "google tasks takes no births from the queue — "
                         "capture-to-google is the create-push law and "
                         "\"todo\" is the capture tag")
                    {})))

  conf/TaskListSource
  (list-discover [this] (list-ids! this))

  (list-pull [this tasklist]
    (let [tl (get-list! this tasklist)]
      [(decorate-list base tasklist (list->doc tl)) (rev-etag (:etag tl))]))

  (list-pull-many [this tasklists]
    ;; one inventory read for the whole batch, not one GET per list.
    ;; A list absent from an inventory that ANSWERED is gone: unlike
    ;; the task collection there are no show-flags to forget here, so
    ;; absence is unambiguous.
    (let [indexed (into {} (map (juxt #(str (:id %)) identity))
                       (all-lists! this))]
      (into {}
            (map (fn [tasklist]
                   [(str tasklist)
                    (if-some [tl (get indexed (str tasklist))]
                      [(decorate-list base tasklist (list->doc tl))
                       (rev-etag (:etag tl))]
                      :gone)]))
            tasklists))))

(defn parse-lists
  "\"MTIzNA,NTY3OA\" → the list ids to mirror, or nil for ALL of them.
  Naming lists narrows; saying nothing means every list the account
  has, discovered from users/@me/lists on each pass. The household
  keeps ten and wants ten, and the earlier reading of blank — the
  account's own \"@default\" list and nothing else — mirrored one of
  them without ever saying so."
  [lists]
  (let [ids (if (string? lists)
              (remove str/blank? (map str/trim (str/split (str lists) #",")))
              (remove str/blank? (map str lists)))]
    (not-empty (vec ids))))

(defn http-source
  "The real boundary over Google Tasks.

  config: :token-fn (a zero-arg access-token source —
  calendar10.oauth/access-token-fn over a refresh token carrying the
  tasks scope), :lists (the task list ids to mirror, comma-separated
  string or seq — every list the account has when unsaid), :base (the
  API base, for tests that want a local server)."
  [{:keys [token-fn lists base]}]
  (->GoogleTasksSource (http-call {:token-fn token-fn :base base})
                       (str/replace (str (or base api-base)) #"/+$" "")
                       (parse-lists lists)
                       (atom nil)))

(defn from-env
  "The deployed boundary off WORKQUEUE10_GTASKS_CLIENT_ID /
  _CLIENT_SECRET / _REFRESH_TOKEN / _LISTS. nil when the credential is
  not configured, which is offline dev's cue to use the fake — the
  same nil-means-absent contract calendar10.source/from-env keeps.

  A configured-but-unscoped refresh token does NOT come back nil: it
  mints, Google refuses the tasks call, and the source reads as
  unreachable. That is deliberate. A credential that half works should
  look broken, not absent."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (when-some [token-fn (oauth/access-token-fn
                         {:client-id (env "WORKQUEUE10_GTASKS_CLIENT_ID")
                          :client-secret (env "WORKQUEUE10_GTASKS_CLIENT_SECRET")
                          :refresh-token (env "WORKQUEUE10_GTASKS_REFRESH_TOKEN")})]
     (http-source {:token-fn token-fn
                   :lists (env "WORKQUEUE10_GTASKS_LISTS")}))))

;; ── the scriptable twin ─────────────────────────────────────────────
;;
;; The fake is an in-memory GOOGLE, not an in-memory source: it stands
;; behind the same (method path opts) seam the real transport rides,
;; so a test exercises the real cursor arithmetic, the real paging
;; loop, the real path building and the real translation, and only the
;; socket is missing. It also POLICES the query: a read that forgets
;; showDeleted sees no deleted tasks here either, so the seam the spec
;; warns about fails a test instead of failing quietly in production.
;;
;; A fake list is a TITLED thing with tasks inside it —
;; {:lists {id {:title … :etag … :updated … :tasks {taskid task}}}} —
;; because the account's inventory is now a feed of its own, and a
;; list that were merely a map of tasks could not answer what it is
;; called.

(def ^:private fresh-state
  {:lists {}
   :rev 0
   :clock (Instant/parse "2026-07-01T00:00:00Z")
   :requests []
   :down false})

(defn- tick!
  "The fake's Google clock: one second per write, so `updated` stamps
  order themselves and a cursor test can say what it means."
  [state]
  (str (:clock (swap! state update :clock #(.plusSeconds ^Instant % 1)))))

(defn- flag? [params k]
  (= "true" (str (get params k))))

(defn- page-of
  "The fake's one paging rule, shared by both collections: maxResults
  (google's default 20 when unsaid) over a stable order, with the
  offset carried in the page token."
  [items params]
  (let [items (vec items)
        size (or (some-> (get params :maxResults) str parse-long) 20)
        from (or (some-> (get params :pageToken) str parse-long) 0)
        page (vec (take size (drop from items)))
        next-from (+ from (count page))]
    (cond-> {:items page}
      (< next-from (count items)) (assoc :nextPageToken (str next-from)))))

(defn- list-envelope
  "One stored list as google publishes it in the inventory."
  [tasklist entry]
  {:id tasklist :title (:title entry) :etag (:etag entry)
   :updated (:updated entry)})

(defn- fake-tasklists
  [state params]
  (page-of (map (fn [[id entry]] (list-envelope id entry))
                (sort-by key (:lists @state)))
           params))

(defn- fake-list
  [state tasklist params]
  (let [entry (get-in @state [:lists tasklist])
        _ (when (nil? entry)
            (throw (ex-info (str "no task list " (pr-str tasklist))
                            {:status 404})))
        tasks (:tasks entry)
        since (->instant (get params :updatedMin))
        visible (cond->> (sort-by :id (vals tasks))
                  since (filter #(when-some [u (->instant (:updated %))]
                                   (not (neg? (compare u since)))))
                  (not (flag? params :showDeleted)) (remove :deleted)
                  (not (flag? params :showHidden)) (remove :hidden)
                  (not (flag? params :showCompleted))
                  (remove #(= "completed" (:status %))))]
    (page-of visible params)))

(defn- fake-call
  "An in-memory Google Tasks behind the transport seam. Every request
  is recorded (params included) so a test can assert what went ON the
  wire — which is the only way to prove a query parameter that fails
  silently is actually being sent."
  [state]
  (fn [method path {:keys [params body if-match]}]
    (swap! state update :requests conj
           {:method method :path path :params params :body body
            :if-match if-match})
    (when (:down @state)
      (throw (ex-info "google tasks unreachable" {})))
    (let [dec* (fn [s] (when s (URLDecoder/decode ^String s StandardCharsets/UTF_8)))
          inventory (re-matches (re-pattern (str lists-path "(?:/(.+))?"))
                                (str path))
          [_ raw-list raw-task] (re-matches #"/lists/([^/]+)/tasks(?:/(.+))?"
                                            (str path))]
      (cond
        ;; the account's inventory of lists — the route the all-lists
        ;; posture rides, and the one a narrowed source never calls
        inventory
        (let [tasklist (dec* (second inventory))]
          (case [method (some? tasklist)]
            ["GET" false] (fake-tasklists state params)
            ["GET" true]
            (if-some [entry (get-in @state [:lists tasklist])]
              (list-envelope tasklist entry)
              (throw (ex-info (str "no task list " (pr-str tasklist))
                              {:status 404})))
            (throw (ex-info (str "the fake google speaks no " method " " path)
                            {}))))

        (nil? raw-list)
        (throw (ex-info (str "the fake google speaks no " path) {}))

        :else
        (let [tasklist (dec* raw-list)
              taskid (dec* raw-task)]
          (case [method (some? taskid)]
            ["GET" false] (fake-list state tasklist params)

            ["GET" true]
            (or (get-in @state [:lists tasklist :tasks taskid])
                (throw (ex-info (str "no task " taskid " in " tasklist)
                                {:status 404})))

            ["PATCH" true]
            (let [task (get-in @state [:lists tasklist :tasks taskid])]
              (when (nil? task)
                (throw (ex-info (str "no task " taskid " in " tasklist)
                                {:status 404})))
              (when (and if-match (not= if-match (:etag task)))
                (throw (ex-info "the task changed under the push" {:status 412})))
              (let [stamp (tick! state)
                    rev (:rev (swap! state update :rev inc))
                    task' (cond-> (merge task
                                         (select-keys body [:status :title :notes])
                                         {:updated stamp
                                          :etag (str "\"gt-" rev "\"")})
                            (= "completed" (:status body)) (assoc :completed stamp))]
                (swap! state assoc-in [:lists tasklist :tasks taskid] task')
                task'))

            (throw (ex-info (str "the fake google speaks no " method " " path)
                            {}))))))))

(defrecord FakeGoogleTasks [state source]
  ;; a thin delegation on purpose: the fake's whole value is that the
  ;; REAL source runs, so nothing here may reimplement a law
  conf/TaskSource
  (source-discover [_] (conf/source-discover source))
  (source-pull [_ id] (conf/source-pull source id))
  (source-pull-many [_ ids] (conf/source-pull-many source ids))
  (source-push [_ id document] (conf/source-push source id document))
  (source-create [_ document] (conf/source-create source document))

  conf/TaskListSource
  (list-discover [_] (conf/list-discover source))
  (list-pull [_ id] (conf/list-pull source id))
  (list-pull-many [_ ids] (conf/list-pull-many source ids)))

(defn- put-list!
  "Create or retitle a list in the fake inventory, stamped with the
  fake's own clock and revision."
  [state tasklist title]
  (let [rev (:rev (swap! state update :rev inc))
        stamp (tick! state)]
    (swap! state update-in [:lists tasklist]
           #(merge {:tasks {}} % {:title title :updated stamp
                                  :etag (str "\"gtl-" rev "\"")}))))

(defn fake-source
  "Google Tasks in memory: the real source over a scriptable Google.
  config: :lists (the list ids it mirrors — every list in the fake
  inventory when unsaid, the real source's own all-lists posture).
  Named lists are created empty so a narrowed source finds them;
  otherwise the inventory starts bare and seed! / list! fill it.
  Script it with list! / seed! / complete! / delete! / clear! /
  purge! / down!, and read it back with requests / stored / cursor."
  ([] (fake-source {}))
  ([{:keys [lists]}]
   (let [state (atom fresh-state)
         ids (parse-lists lists)]
     (doseq [id ids] (put-list! state id id))
     (->FakeGoogleTasks
      state
      (->GoogleTasksSource (fake-call state) api-base ids (atom nil))))))

(defn list!
  "Name a list in the fake inventory (creating it if new) — the twin
  of the household adding a list on the phone. Returns the list id."
  [fake tasklist title]
  (put-list! (:state fake) tasklist title)
  tasklist)

(defn seed!
  "Put a GOOGLE-shaped task in the fake list (not a canonical doc —
  the point of this twin is that the real translation runs). Only :id
  and :title are needed; :status defaults to needsAction, :updated and
  :etag to the fake's own clock and revision. A list google has never
  heard of is created on the way, titled after its own id, so a test
  about tasks never has to be a test about inventory."
  [fake tasklist task]
  (let [state (:state fake)
        _ (when (nil? (get-in @state [:lists tasklist]))
            (put-list! state tasklist tasklist))
        rev (:rev (swap! state update :rev inc))
        stamp (tick! state)
        task (merge {:status "needsAction"
                     :updated stamp
                     :etag (str "\"gt-" rev "\"")}
                    task)]
    (swap! state assoc-in [:lists tasklist :tasks (:id task)] task)
    (join-id tasklist (:id task))))

(defn- touch!
  [fake tasklist taskid f]
  (let [state (:state fake)
        rev (:rev (swap! state update :rev inc))
        stamp (tick! state)]
    (swap! state update-in [:lists tasklist :tasks taskid]
           #(-> (f %) (assoc :updated stamp :etag (str "\"gt-" rev "\""))))))

(defn complete!
  "The phone marks it done — still on the list, and still answered."
  [fake tasklist taskid]
  (touch! fake tasklist taskid #(assoc % :status "completed")))

(defn delete!
  "The phone deletes it: still LISTED (that is what showDeleted buys)
  and flagged, so the queue observes the deletion."
  [fake tasklist taskid]
  (touch! fake tasklist taskid #(assoc % :deleted true)))

(defn clear!
  "The phone clears completed tasks off the list: hidden, not deleted
  — the second way a row goes quiet, and the reason showHidden rides
  along with showDeleted."
  [fake tasklist taskid]
  (touch! fake tasklist taskid #(assoc % :status "completed" :hidden true)))

(defn purge!
  "The task leaves the list entirely — Google's eventual sweep of old
  deletions. Absence from a list that answered is gone too."
  [fake tasklist taskid]
  (swap! (:state fake) update-in [:lists tasklist :tasks] dissoc taskid))

(defn drop-list!
  "The household deletes a whole list on the phone: it leaves the
  inventory, tasks and all."
  [fake tasklist]
  (swap! (:state fake) update :lists dissoc tasklist))

(defn down! [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn requests
  "Every request the source made, oldest first — the instrument for
  asserting that a silently-failing query parameter is really sent."
  [fake]
  (:requests @(:state fake)))

(defn stored [fake tasklist taskid]
  (get-in @(:state fake) [:lists tasklist :tasks taskid]))

(defn cursor
  "The source's incremental cursor — the highest `updated` it has
  seen, or nil before the first pass."
  [fake]
  @(:cursor (:source fake)))
