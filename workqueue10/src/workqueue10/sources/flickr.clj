(ns workqueue10.sources.flickr
  "flickr — the household's own media engine at stream.kopsa.info —
  as the :media confluence's first wired authority. This is the
  gtasks shape (a cursor-bearing pull source, the waymark-8si work
  riding again) with three of that source's hard parts arriving
  pre-solved, because flickr was built on the same law as waymark
  and its feed was shaped by spec-media.md's constraints without
  ever learning waymark's name:

  - IT SPEAKS WORKS, NOT FILES. \"A show is not an episode\" is
    enforced authority-side: the feed collapses thousands of files
    into a few hundred works, and episodes live inside
    :progress_text, never as row candidates.
  - THE FRACTION LAW IS IMPLEMENTED AT THE SOURCE. Every progress
    fact arrives as the canonical fraction BESIDE the authority's
    own words (0.0137 beside \"1:19\"); nothing here normalizes.
  - AUDIENCES ARE THE ASSIGNEE PATTERN VERBATIM. Profile names key
    watch state; :audience_name maps with no translation.

  THE WIRE is one route: GET /api/feed/media. Cursorless it answers
  every work plus an opaque cursor (the initial sync — 416 works at
  verification); ?since=<cursor> answers only the works with item-
  or playback-changes after it, per-audience progress recomputed
  fresh. The cursor is opaque — stored and echoed, never parsed. A
  malformed cursor is a 400 and means \"resync from scratch\": the
  cursor resets and the same pass re-reads the world. Mid-flight
  writes duplicate rather than drop; the mirror's idempotent
  observe absorbs the re-seen rows, the gtasks posture exactly.

  DELETIONS are not tombstoned per-row: the authority bumps a
  resync mark, and a since= pull from before that mark answers the
  FULL list rather than risk a silently stale mirror. Absence
  against the full list is the observation :on-gone reads — and
  pull-many IS a full list by construction (this source has no
  per-work route, so the batch reads the whole cursorless feed once
  and answers :gone for every id the library no longer carries).
  The kind's :resync-every cadence rides that batch, so a deleted
  work lands within the window. Cheap at a few hundred works; the
  addendum records the order-of-magnitude revisit.

  ONLY MOVIE AND SHOW KINDS MIRROR. flickr honestly projects
  unidentified media as per-file works (322 at verification);
  mirroring them would flood the queue with inventory — the
  parent spec's line between mirroring intent and rebuilding the
  catalog badly. A work that stops being movie/show reads as gone.

  THE AUDIENCE RULE (the parent punt, observed): the feed emits
  per-audience progress, PLURAL, on one work — the household's
  actual shape, two profiles mid-show in different seasons — and
  one row holds one progress. The addendum's mapping rule: a row
  whose :audience ref is set takes that audience's entry; a row
  with no audience takes the most-recently-updated entry.
  preferred-fn is that rule's hub half — work_key → the audience
  name the row follows, nil when the hub has no opinion —
  engine-audience-fn wires it over the engine that stores the rows.

  STATUS only ever arrives active/finished (flickr's ≥0.9 law);
  queued/abandoned/:priority are hub-local, and NO local write
  pushes to flickr today — the shield section of the addendum.
  source-push answers the fresh content etag (the round trip
  doubles as a freshness check, the task confluence's :noop
  posture), and source-create refuses: a birth with no catalog is
  the hub's.

  ETAGS are content hashes of the translated document with this
  namespace's translation revision composed on — flickr mints no
  version of its own, and a content hash notices the authority
  changing while the revision notices US changing (the
  sources.waymark lesson, gtasks' spelling). The hash covers the
  CHOSEN audience's slice, so the hub re-pointing a row at another
  profile re-observes on the next pull without any feed change.

  RECORDED GAPS, from the addendum, all healable without a change
  here: slug-form work keys until the authority's TMDB credential
  lands (expect rare row churn on library reorganization);
  :creator/:genres/:overview arrive empty today and fill in
  authority-side; explicit watched-marking is flickr's own issue
  and the first honest push target when wanted."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def default-base
  "The household's engine — LAN-only ingress, no credential."
  "https://stream.kopsa.info")

(def feed-path "/api/feed/media")

(def mirrored-kinds
  "The work kinds that become rows. \"file\" — flickr's honest
  projection of not-yet-identified media — is excluded by decision
  (recorded gap #2): intent, not inventory."
  #{"movie" "show"})

(def translation-rev
  "This namespace's translation version, composed into every etag it
  reports. flickr mints no document version, so the etag is a content
  hash — which can see the authority moving and can never see US
  moving; bump this whenever work->doc's output changes shape and
  every stored row re-observes on its next pull."
  "f1")

;; ── the translation ─────────────────────────────────────────────────

(defn pick-audience
  "The addendum's audience rule over one work's plural entries: the
  preferred (hub-followed) name's entry when it exists, else the
  most-recently-updated, else nil — a work nobody has started carries
  no watch state at all."
  [audiences preferred]
  (or (when-not (str/blank? (str preferred))
        (first (filter #(= (str preferred) (str (:name %))) audiences)))
      (when (seq audiences)
        (reduce (fn [a b]
                  (if (pos? (compare (:updated_at b) (:updated_at a))) b a))
                audiences))))

(defn- fragment-encode ^String [s]
  ;; the UI's own spelling: encodeURIComponent (its showHash fn), so
  ;; the deep link this source stamps is byte-for-byte the one the
  ;; UI mints for itself
  (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
      (str/replace "+" "%20")))

(defn deep-link
  "The verified hash deep link back to the engine's own UI — the
  :origin affordance: #/item/<representative_item_id> for a movie's
  detail pane, #/show/<title> for a show's episode list."
  [base work]
  (if (= "show" (:kind work))
    (str base "/#/show/" (fragment-encode (:title work)))
    (str base "/#/item/" (:representative_item_id work))))

(defn work->doc
  "One feed work → the canonical media doc, under the chosen
  audience's slice. A work with no watch state says nothing about
  status or position — under the kind's :partial contract that
  silence keeps the hub's own words (queued, abandoned, a logged
  position) intact. A third status fails loudly rather than mapping
  silently; a nil fraction beside intact text is the fraction law's
  own gap (an ongoing show has a moving denominator)."
  [work preferred]
  (let [a (pick-audience (:audiences work) preferred)]
    (cond-> {:title (:title work)
             :medium (:kind work)
             :work_key (:work_key work)}
      (some? (:year work)) (assoc :year (:year work))
      a (assoc :status (case (:status a)
                         "active" "active"
                         "finished" "finished")
               :audience_name (:name a))
      (some? (:progress_text a)) (assoc :progress_text (:progress_text a))
      (some? (:progress a)) (assoc :progress (:progress a)))))

(defn- content-etag
  "flickr mints no version, so the version is the translated content
  itself, with the translation revision composed on."
  [doc]
  (str (wire/sha256-hex (pr-str (into (sorted-map) doc)))
       "|" translation-rev))

(defn mirrorable? [work]
  (contains? mirrored-kinds (:kind work)))

;; ── the wire ────────────────────────────────────────────────────────

(defn- query-string [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "="
                        (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
                 params)))

(defn http-call
  "The real transport: (fn [method path {:keys [params]}]) → the
  parsed body. No credential — the engine is LAN-only ingress.
  Non-2xx throws ex-info carrying :status: 400 for a malformed
  cursor (resync from scratch), anything else the caller reads as
  unreachable."
  [{:keys [base]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        base (str/replace (str (or base default-base)) #"/+$" "")]
    (fn [^String method path {:keys [params]}]
      (let [url (str base path (when (seq params)
                                 (str "?" (query-string params))))
            req (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 30))
                    (.method method (HttpRequest$BodyPublishers/noBody))
                    (.build))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))
            status (.statusCode resp)]
        (when (>= status 400)
          (throw (ex-info (str "flickr answered " status " for "
                               method " " path)
                          {:status status :body (.body resp)})))
        (some-> ^String (.body resp) not-empty wire/read-json)))))

;; ── the source ──────────────────────────────────────────────────────

(defn- feed!
  "One feed read: since nil is the initial sync (the whole library),
  a cursor asks for changes after it."
  [{:keys [call]} since]
  (call "GET" feed-path {:params (when since {:since since})}))

(defn- indexed
  "The whole library, keyed by work_key — the full list absence is
  judged against."
  [this]
  (into {} (map (juxt #(str (:work_key %)) identity)) (:works (feed! this nil))))

(defn- doc-for [{:keys [base preferred-fn]} work]
  (assoc (work->doc work (preferred-fn (str (:work_key work))))
         :source_ui_href (deep-link base work)))

(defrecord FlickrSource [call base preferred-fn cursor]
  conf/TaskSource
  (source-discover [this]
    (let [resp (try (feed! this @cursor)
                    (catch clojure.lang.ExceptionInfo e
                      ;; a malformed cursor is a 400 and means
                      ;; "resync from scratch" — the same pass
                      ;; re-reads the world rather than waiting a beat
                      (if (and @cursor (= 400 (:status (ex-data e))))
                        (do (reset! cursor nil)
                            (feed! this nil))
                        (throw e))))]
      ;; the cursor advances only on a pass that answered — a throw
      ;; above leaves it standing, so the next pass re-asks the window
      (reset! cursor (some-> (:cursor resp) str not-empty))
      (into []
            (comp (filter mirrorable?) (map #(str (:work_key %))) (distinct))
            (:works resp))))

  (source-pull [this id]
    (let [work (get (indexed this) (str id))]
      (when-not (and work (mirrorable? work))
        (throw (ex-info (str id " is not in flickr's library")
                        {:status 404})))
      (let [doc (doc-for this work)]
        [doc (content-etag doc)])))

  (source-pull-many [this ids]
    ;; no per-work route exists, so the batch IS the full list — one
    ;; cursorless read, absence answered as :gone (the feed spoke for
    ;; its whole library; a missing id is an observation, never an
    ;; outage). This is exactly the full-resync the deletion mark
    ;; demands, and :resync-every rides it.
    (let [works (indexed this)]
      (into {}
            (map (fn [id]
                   [(str id)
                    (let [work (get works (str id))]
                      (if (and work (mirrorable? work))
                        (let [doc (doc-for this work)]
                          [doc (content-etag doc)])
                        :gone))]))
            ids)))

  (source-push [this id _document]
    ;; NOTHING travels — flickr owns what happened, the hub owns what
    ;; is intended (the addendum's shield). The answer is the fresh
    ;; content etag: the round trip doubles as a freshness check, so
    ;; a hub-local write's :to :fresh is earned, not asserted. A work
    ;; the library dropped refuses 404-shaped — the conflicted
    ;; landing, a person decides.
    (let [work (get (indexed this) (str id))]
      (when-not (and work (mirrorable? work))
        (throw (ex-info (str id " is no longer in flickr's library")
                        {:status 404})))
      (content-etag (doc-for this work))))

  (source-create [_ _document]
    (throw (ex-info (str "flickr takes no births from the queue — its works "
                         "are scanned from the library's own files; a media "
                         "row with no catalog is the hub's (:source \"hub\")")
                    {}))))

(defn engine-audience-fn
  "The audience rule's hub half, over the engine that stores the
  rows: work_key → the audience name the row follows — its
  :audience_name, but only when the :audience ref actually resolved
  to a member (the addendum's \"row whose :audience is set\") — nil
  when the hub has no opinion and the most-recently-updated entry
  should speak. Reads through the late-bound engine-ref (the
  in-process sources' pattern); before the engine exists, or on any
  read failure, it simply has no opinion."
  [{:keys [engine-ref tag] :or {tag "flickr"}}]
  (fn [work-key]
    (when-some [eng (some-> engine-ref deref)]
      (try
        (store/with-tx (:storage eng)
          (fn [tx]
            (let [row (first (store/query-rows
                              (:storage eng) tx :media
                              {:external_id (conf/xid tag work-key)}
                              {:limit 1}))]
              (when (some? (get-in row [:data :audience]))
                (some-> (get-in row [:data :audience_name]) str not-empty)))))
        (catch Exception _ nil)))))

(defn http-source
  "The real boundary over a flickr engine.
  config: :base (the engine's URL — default the household's),
  :preferred-fn (work_key → the audience name the row follows, nil
  for no opinion — engine-audience-fn is the wired spelling)."
  [{:keys [base preferred-fn]}]
  (let [base (str/replace (str (or base default-base)) #"/+$" "")]
    (->FlickrSource (http-call {:base base}) base
                    (or preferred-fn (constantly nil))
                    (atom nil))))

(defn from-env
  "The deployed boundary off WORKQUEUE10_FLICKR_URL. nil when unset,
  which is offline dev's cue to use the fake — the same
  nil-means-absent contract every boundary here keeps."
  ([] (from-env nil))
  ([{:keys [preferred-fn]}]
   (when-some [url (some-> (System/getenv "WORKQUEUE10_FLICKR_URL")
                           str not-empty)]
     (http-source {:base url :preferred-fn preferred-fn}))))

;; ── the scriptable twin ─────────────────────────────────────────────
;;
;; The fake is an in-memory FLICKR, not an in-memory source: it stands
;; behind the same (method path opts) seam the real transport rides,
;; so a test exercises the real cursor echo, the real 400-means-resync
;; path, the real kind filter and the real translation, and only the
;; socket is missing. Its cursor grammar is the live engine's own
;; (l<n>.s<n>, verified): one change counter stamps every write, the
;; deletion mark demands a full resync from any cursor behind it, and
;; a cursor that will not parse is the same 400 the live engine
;; answers.

(def ^:private fresh-state
  {:works {} :seq 0 :deletion-seq 0 :down false :requests []})

(defn- parse-cursor [s]
  (if-some [[_ lib st] (re-matches #"l(\d+)\.s(\d+)" (str s))]
    {:lib (parse-long lib) :state (parse-long st)}
    (throw (ex-info (str "malformed cursor " (pr-str (str s))
                         " (want l<n>.s<n>)")
                    {:status 400}))))

(defn- fake-call [state]
  (fn [method path {:keys [params]}]
    (swap! state update :requests conj
           {:method method :path path :params params})
    (when (:down @state)
      (throw (ex-info "flickr unreachable" {})))
    (when-not (and (= "GET" method) (= feed-path path))
      (throw (ex-info (str "the fake flickr speaks no " method " " path) {})))
    (let [{:keys [works deletion-seq]} @state
          n (:seq @state)
          since (some-> (:since params) parse-cursor)
          ;; a cursor from before the deletion mark cannot know which
          ;; works lost items, so the feed answers the WHOLE library —
          ;; always correct, just not minimal (the live engine's rule)
          resync? (or (nil? since) (> (long deletion-seq) (long (:lib since))))
          out (cond->> (sort-by :work_key (vals works))
                (not resync?) (filter #(> (long (:seq %))
                                          (long (:state since)))))]
      {:cursor (str "l" n ".s" n)
       :works (mapv #(dissoc % :seq) out)})))

(defrecord FakeFlickr [state source]
  ;; a thin delegation on purpose: the fake's whole value is that the
  ;; REAL source runs, so nothing here may reimplement a law
  conf/TaskSource
  (source-discover [_] (conf/source-discover source))
  (source-pull [_ id] (conf/source-pull source id))
  (source-pull-many [_ ids] (conf/source-pull-many source ids))
  (source-push [_ id document] (conf/source-push source id document))
  (source-create [_ document] (conf/source-create source document)))

(defn fake-source
  "flickr in memory: the real source over a scriptable engine.
  config: :preferred-fn (the audience rule's hub half, when a test
  wants one). Script it with seed! / play! / delete! / down!, and
  read it back with requests / cursor."
  ([] (fake-source {}))
  ([{:keys [preferred-fn]}]
   (let [state (atom fresh-state)]
     (->FakeFlickr state
                   (->FlickrSource (fake-call state) default-base
                                   (or preferred-fn (constantly nil))
                                   (atom nil))))))

(defn- stamp!
  "One write against the fake's change counter."
  [state f]
  (let [n (:seq (swap! state update :seq inc))]
    (swap! state f n)
    n))

(defn seed!
  "Put a FEED-shaped work in the fake library (not a canonical doc —
  the point of this twin is that the real translation and the real
  kind filter run). Returns the work_key."
  [fake work]
  (stamp! (:state fake)
          (fn [s n]
            (assoc-in s [:works (str (:work_key work))]
                      (merge {:audiences [] :genres [] :overview ""}
                             work {:seq n}))))
  (str (:work_key work)))

(defn play!
  "One playback session lands: the named audience's entry (status /
  progress / progress_text) replaces its previous one on the work,
  stamped by the fake's own clock — updated_at orders exactly as the
  live feed's epoch floats do."
  [fake work-key entry]
  (stamp! (:state fake)
          (fn [s n]
            (update-in s [:works (str work-key)]
                       (fn [w]
                         (-> w
                             (update :audiences
                                     (fn [as]
                                       (conj (vec (remove #(= (:name %) (:name entry)) as))
                                             (merge {:updated_at n} entry))))
                             (assoc :seq n)))))))

(defn delete!
  "The household removes the files: the work leaves the library and
  the authority bumps its resync mark — the next since= pull answers
  the full list, and absence against it is the :on-gone observation."
  [fake work-key]
  (stamp! (:state fake)
          (fn [s n]
            (-> s
                (update :works dissoc (str work-key))
                (assoc :deletion-seq n)))))

(defn down! [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn requests
  "Every request the source made, oldest first."
  [fake]
  (:requests @(:state fake)))

(defn cursor
  "The source's stored cursor — opaque, or nil before the first pass."
  [fake]
  @(:cursor (:source fake)))
