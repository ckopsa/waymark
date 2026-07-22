(ns workqueue10.sources.homeassistant
  "Home Assistant's todo lists as a TaskSource: the personal-capture
  half of the queue. Capture happens wherever HA already listens (the
  app, voice, automations); the queue mirrors the configured lists
  and pushes Done back as todo.update_item.

  THE WIRE is HA's service API, one POST per read —
  /api/services/todo/get_items?return_response with the whole list
  set — so discover and pull-many are a single round trip. A list
  whose integration is down simply drops out of service_response:
  its items vanish from the batch (stored truth keeps serving, the
  mirror's honest staleness), never an error that would dam the
  other lists.

  IDENTITY is \"entity/uid\" (\"todo.woodworking/70be…\") — the
  source-local half of the confluence's \"todo:entity/uid\". A uid
  gone from a present list is a gone row ({:status 404}); a whole
  list absent is unreachability, not deletion.

  ETAGS are content hashes of the canonical doc (HA mints no
  per-item versions), which also makes them translation-honest for
  free: any change to this namespace's mapping changes every etag,
  and stored rows re-observe on their next pull — the lesson
  sources.waymark/translation-rev records, inherited here by
  construction.

  DUE TIMES: a date-only due widens to the day's closing midnight
  UTC (the chore-source law — overdue flips the morning after); a
  datetime parses with its offset when HA sends one, else in the
  household's :zone (HA speaks local time; the queue speaks
  instants)."
  (:require [clojure.string :as str]
            [workqueue10.confluence :as conf]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration LocalDate LocalDateTime OffsetDateTime ZoneId)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

;; ── the translation ─────────────────────────────────────────────────

(defn due->instant
  "HA's due (date, offset datetime, or naive local datetime) → the
  canonical instant string."
  [due zone]
  (when due
    (if (re-matches #"\d{4}-\d{2}-\d{2}" due)
      (str (.plusDays (LocalDate/parse ^String due) 1) "T00:00:00Z")
      (try (str (.toInstant (OffsetDateTime/parse ^String due)))
           (catch DateTimeParseException _
             (str (.toInstant (.atZone (LocalDateTime/parse ^String due)
                                       (ZoneId/of (str zone))))))))))

(defn item->task
  "One HA todo item → the canonical task doc. The list's friendly
  name rides :detail so a mixed queue says which context the todo
  came from; HA has no dropped state — a dropped todo is a deleted
  one, which reads as gone, not as a status."
  [list-name item zone]
  {:title (:summary item)
   :status (case (:status item)
             "needs_action" "open"
             "completed" "done")
   :due_at (due->instant (or (:due item) (:due_datetime item)) zone)
   :detail (str/join " — " (remove str/blank? [list-name (:description item)]))})

;; ── the wire ────────────────────────────────────────────────────────

(defn- split-id
  "\"todo.woodworking/70be…\" → [entity uid]."
  [id]
  (let [[entity uid] (str/split (str id) #"/" 2)]
    (when (nil? uid)
      (throw (ex-info (str "todo id " (pr-str (str id))
                           " carries no entity/uid split") {})))
    [entity uid]))

(defn- content-etag [doc]
  (wire/sha256-hex (pr-str (into (sorted-map) doc))))

(defn list-name
  "The context :detail carries: the configured friendly name, else
  one derived from the entity id (\"todo.my_tasks\" → \"My tasks\")."
  [names entity]
  (or (get names entity)
      (-> (str entity)
          (str/replace #"^todo\." "")
          (str/replace "_" " ")
          str/capitalize)))

(defn- service-call!
  "POST one HA service → the parsed body (with ?return_response, the
  service_response rides it). Non-2xx throws; a connection failure
  throws raw — unreachable, as the protocol asks."
  [{:keys [^HttpClient client api-base token]} service body return?]
  (let [req (-> (HttpRequest/newBuilder
                 (URI/create (str api-base "/api/services/" service
                                  (when return? "?return_response"))))
                (.timeout (Duration/ofSeconds 20))
                (.header "authorization" (str "Bearer " token))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (wire/write-json body) StandardCharsets/UTF_8))
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "home assistant answered " status " for " service)
                      {:status status :body (.body resp)})))
    (some-> ^String (.body resp) not-empty wire/read-json)))

(defn- items-by-entity
  "entity → items, for the lists home assistant answered about — an
  unavailable list is simply absent (keys are keywordized on the
  wire; entity ids come back as keywords)."
  [src entities]
  (let [resp (service-call! src "todo/get_items" {:entity_id entities} true)]
    (into {}
          (map (fn [[k v]] [(name k) (:items v)]))
          (:service_response resp))))

(defn- decorate
  "The canonical doc + where it came from: HA has no per-item URL, so
  both hrefs anchor on the LIST — the API state row for clients, the
  todo panel for a person's tap."
  [{:keys [api-base ui-base]} entity doc]
  (assoc doc
         :source_href (str api-base "/api/states/" entity)
         :source_ui_href (str ui-base "/todo?entity_id=" entity)))

(defrecord HomeAssistantSource [^HttpClient client api-base ui-base token
                                lists names zone capture-list]
  conf/TaskSource
  (source-discover [this]
    (into []
          (mapcat (fn [[entity items]]
                    (keep #(when (= "needs_action" (:status %))
                             (str entity "/" (:uid %)))
                          items)))
          (items-by-entity this lists)))
  (source-pull [this id]
    (let [[entity uid] (split-id id)
          items (get (items-by-entity this [entity]) entity)]
      (when (nil? items)
        (throw (ex-info (str entity " is unavailable in home assistant") {})))
      (if-some [item (first (filter #(= uid (:uid %)) items))]
        (let [doc (decorate this entity
                            (item->task (list-name names entity) item zone))]
          [doc (content-etag doc)])
        (throw (ex-info (str "no todo " uid " in " entity) {:status 404})))))
  (source-pull-many [this ids]
    (let [pairs (mapv split-id ids)
          by-entity (items-by-entity this (vec (distinct (map first pairs))))]
      (into {}
            (keep (fn [[entity uid]]
                    ;; an absent LIST drops its ids from the batch
                    ;; (ambiguous — stored truth serves); an absent
                    ;; UID in a PRESENT list is a deletion observed:
                    ;; :gone, the honest sentinel the :on-gone policy
                    ;; reads
                    (let [items (get by-entity entity ::absent)]
                      (when-not (= ::absent items)
                        (if-some [item (first (filter #(= uid (:uid %))
                                                      items))]
                          (let [doc (decorate this entity
                                              (item->task (list-name names entity)
                                                          item zone))]
                            [(str entity "/" uid) [doc (content-etag doc)]])
                          [(str entity "/" uid) :gone])))))
            pairs)))
  (source-push [this id document]
    (let [[doc _] (conf/source-pull this id)]
      (case (conf/push-plan document (:status doc))
        :noop (content-etag doc)
        :complete
        (let [[entity uid] (split-id id)]
          (service-call! this "todo/update_item"
                         {:entity_id entity :item uid :status "completed"}
                         false)
          (content-etag (assoc doc :status "done"))))))
  (source-create [this document]
    ;; THE UID-DIFF BIRTH: add_item declares no response (the service
    ;; registry's word), so the minted uid is recovered by
    ;; differencing the list around the add. Ambiguity (concurrent
    ;; HA-side adds of the same summary in the same window) throws —
    ;; and that is safe, not racy: a failed create push lands
    ;; conflicted with no external id, and resolve_conflict
    ;; keep=local retries.
    (let [entity capture-list
          _ (when (str/blank? (str entity))
              (throw (ex-info "no capture list configured — set the source's :capture-list" {})))
          before-items (get (items-by-entity this [entity]) entity ::absent)
          _ (when (= ::absent before-items)
              (throw (ex-info (str entity " is unavailable in home assistant") {})))
          before (into #{} (map :uid) before-items)
          body (cond-> {:entity_id entity :item (:title document)}
                 (:due_at document) (assoc :due_datetime (:due_at document))
                 (not (str/blank? (str (:detail document))))
                 (assoc :description (:detail document)))
          _ (service-call! this "todo/add_item" body false)
          after-items (get (items-by-entity this [entity]) entity)
          new-items (remove #(contains? before (:uid %)) after-items)
          item (case (count new-items)
                 0 (throw (ex-info "add_item answered but no new item appeared" {}))
                 1 (first new-items)
                 ;; several landed in the window: the summary picks ours,
                 ;; or nobody does and a person resolves
                 (let [mine (filter #(= (:title document) (:summary %))
                                    new-items)]
                   (if (= 1 (count mine))
                     (first mine)
                     (throw (ex-info (str (count new-items)
                                          " items landed at once and the "
                                          "summary picks none apart — "
                                          "resolve keep=local retries")
                                     {})))))
          doc (decorate this entity
                        (item->task (list-name names entity) item zone))]
      [(str entity "/" (:uid item)) (content-etag doc)])))

(defn http-source
  "The real boundary over a running home assistant.

  config: :url (the API base the QUEUE's node can reach, e.g. the
  LAN address), :ui-url (the base a PERSON's browser reaches — the
  origin link's home), :token (a long-lived access token), :lists
  (the todo entity ids to mirror, comma-separated string or seq),
  :names (entity id → friendly name, optional — entity id shown
  otherwise), :zone (the household zone naive due datetimes parse
  in — default America/Denver), :capture-list (the entity captured
  tasks are born into — unset, the queue takes no births)."
  [{:keys [url ui-url token lists names zone capture-list]}]
  (->HomeAssistantSource
   (-> (HttpClient/newBuilder)
       (.connectTimeout (Duration/ofSeconds 10))
       (.build))
   (str/replace (str url) #"/+$" "")
   (str/replace (str (or ui-url url)) #"/+$" "")
   token
   (if (string? lists)
     (vec (remove str/blank? (map str/trim (str/split lists #","))))
     (vec lists))
   (or names {})
   (or zone "America/Denver")
   capture-list))
