(ns choreplan10.mirror.mealplan
  "The mealplan10 boundary: the family meal planner's prep_task rows
  as a MirrorAdapter — the feed choreplan10's prep_task mirror syncs
  from, and pushes the one legal local write back to.

  The authority is ANOTHER WAYMARK ENGINE (meals10), which makes this
  the cheapest boundary in the family: discover is one filtered
  collection GET (?assignee=… — the :filter #{:eq} on mealplan10's
  prep_task assignee exists for exactly this query), pull is a row GET
  whose envelope already carries the engine's own etag (no
  content-hash synthesis — the authority mints real versions), and
  push rides the same If-Match fence every waymark write rides.

  PUSH IS A TRANSLATION, NOT A DOCUMENT WRITE. Mealplan's done is a
  state transition (:complete), not a column, so push-plan reduces
  the pushed document to one of three honest outcomes: POST
  /-/complete (the move), :noop (the authority already agrees —
  idempotence, its etag stands), or a throw (the move does not apply
  — the cook cancelled the step upstream; the row lands conflicted
  and resolve_conflict decides). Every other fact (meal_name, due_at,
  notes, …) is pull-only and never travels.

  DOMAIN STATE IS DATA (the mirror rule): the authority's row state
  (pending/scheduled/done/cancelled) rides the document as :status;
  the mirror's machine is the sync machine.

  fake-feed is the scriptable in-memory twin (the warehouse
  FakeSource precedent) — the declaration gate's and tests' adapter,
  and the offline-dev default. Its push runs the SAME push-plan the
  real boundary runs, so the translation semantics are what the
  tests hold; a doc the walker never seeded is treated as a pending
  task the authority simply hasn't shown us (the FakeEvents
  auto-vivify spirit), while a remove!d id fails like a row the
  planner no longer carries."
  (:require [clojure.string :as str]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

;; ── the translation at the heart of push ────────────────────────────

(defn push-plan
  "The pushed local document + the authority's row state → the one
  move to make: :complete (POST the action), :noop (already done —
  idempotence), or a throw (the move does not apply; the conflicted
  state, resolve_conflict decides)."
  [document upstream-state]
  (cond
    (not= "done" (:status document))
    (throw (ex-info (str "the mirror pushes only the complete move; "
                         "the local document says status "
                         (pr-str (:status document)))
                    {:status-fact (:status document)}))

    (= "done" upstream-state) :noop

    (contains? #{"pending" "scheduled"} upstream-state) :complete

    :else
    (throw (ex-info (str "complete does not apply — the planner's task is "
                         (pr-str upstream-state)
                         "; resolve the conflict once the kitchen and the "
                         "plan agree")
                    {:upstream-state upstream-state}))))

;; ── the wire ────────────────────────────────────────────────────────

(defn row->document
  "One row envelope → the wire-shaped mirror document. :status is the
  authority's row state — domain state as data."
  [env]
  (let [d (:data env)]
    {:meal_name (:meal_name d)
     :date (:date d)
     :task_type (:task_type d)
     :assignee (:assignee d)
     :due_at (:due_at d)
     :duration_minutes (:duration_minutes d)
     :notes (:notes d)
     :status (:state env)}))

(defn- enc [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- self->id [self]
  (last (str/split (str self) #"/")))

(defn- request ^HttpRequest [{:keys [base headers]} method path extra]
  (let [b (-> (HttpRequest/newBuilder (URI/create (str base path)))
              (.timeout (Duration/ofSeconds 20)))]
    (doseq [[^String k ^String v] (merge headers extra)]
      (.header b k v))
    (.build (case method
              :get (.GET b)
              :post (.POST b (HttpRequest$BodyPublishers/noBody))))))

(defn- send!
  "One request → {:etag … :env …}; non-2xx throws with the status in
  ex-data (pull-many reads it to tell a gone row from a down feed);
  a connection failure throws raw — unreachable, as the protocol
  asks."
  [{:keys [^HttpClient client] :as feed} method path & [extra-headers]]
  (let [resp (.send client (request feed method path extra-headers)
                    (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        env (some-> ^String (.body resp) not-empty wire/read-json)]
    (when (>= status 400)
      (throw (ex-info (str "mealplan answered " status " for "
                           (name method) " " path)
                      {:status status :problem env})))
    {:etag (.orElse (.firstValue (.headers resp) "etag") nil)
     :env env}))

(def ^:private page-size 100)

(defrecord MealplanFeed [^HttpClient client base assignee headers]
  mirror/MirrorAdapter
  (discover [this]
    (loop [n 1 acc []]
      (let [{:keys [env]} (send! this :get
                                 (str "/api/prep_tasks?assignee=" (enc assignee)
                                      "&page%5Bsize%5D=" page-size
                                      "&page%5Bnumber%5D=" n))
            items (get-in env [:data :items])
            acc (into acc (map (comp self->id :self)) items)]
        (if (< (count items) page-size) acc (recur (inc n) acc)))))
  (pull [this xid]
    (let [{:keys [env etag]} (send! this :get (str "/api/prep_tasks/" (enc xid)))]
      [(row->document env) (or etag (get-in env [:meta :etag]))]))
  (pull-many [this xids]
    (into {}
          (keep (fn [xid]
                  (try [(str xid) (mirror/pull this xid)]
                       (catch clojure.lang.ExceptionInfo e
                         ;; a gone row drops from the batch (the feed
                         ;; no longer carries it); anything else is
                         ;; the boundary's problem — rethrow
                         (when-not (= 404 (:status (ex-data e)))
                           (throw e))
                         nil))))
          xids))
  (push [this xid document]
    (let [{:keys [env etag]} (send! this :get (str "/api/prep_tasks/" (enc xid)))
          etag (or etag (get-in env [:meta :etag]))]
      (case (push-plan document (:state env))
        :noop etag
        :complete
        (let [{:keys [env etag]}
              (send! this :post
                     (str "/api/prep_tasks/" (enc xid) "/-/complete")
                     {"if-match" etag})]
          (or etag (get-in env [:meta :etag])))))))

(defn http-feed
  "The real boundary over a running mealplan10 engine.

  config: :url (the engine root, e.g. https://meals10.kopsa.info),
  :assignee (the feed's key — default \"housekeeper\"), :principal
  (the x-waymark-principal the pushes act as — default
  \"choreplan10\"), :token (optional bearer)."
  [{:keys [url assignee principal token]}]
  (->MealplanFeed
   (-> (HttpClient/newBuilder)
       (.connectTimeout (Duration/ofSeconds 10))
       (.build))
   (str/replace (or url "http://localhost:8010") #"/+$" "")
   (or assignee "housekeeper")
   (cond-> {"x-waymark-principal" (or principal "choreplan10")
            "accept" "application/json"}
     token (assoc "authorization" (str "Bearer " token)))))

;; ── the scriptable twin ─────────────────────────────────────────────

(defn- content-etag [doc]
  (wire/sha256-hex (pr-str (into (sorted-map) doc))))

(defn- doc+etag [doc]
  [doc (content-etag doc)])

(defrecord FakeFeed [state]
  mirror/MirrorAdapter
  (discover [_]
    (let [{:keys [down discoverable]} @state]
      (when down (throw (ex-info "mealplan unreachable" {})))
      (vec discoverable)))
  (pull [_ xid]
    (let [{:keys [down removed docs]} @state]
      (when down (throw (ex-info "mealplan unreachable" {})))
      (when (contains? removed xid)
        (throw (ex-info (str xid " is no longer in the plan") {:status 404})))
      (if-some [doc (get docs xid)]
        (doc+etag doc)
        (throw (ex-info (str "no prep task " xid) {:status 404})))))
  (pull-many [this xids]
    (let [{:keys [down]} @state]
      (when down (throw (ex-info "mealplan unreachable" {})))
      (into {}
            (keep (fn [xid]
                    (try [(str xid) (mirror/pull this xid)]
                         (catch clojure.lang.ExceptionInfo _ nil))))
            xids)))
  (push [_ xid document]
    (let [{:keys [down removed push-fail docs]} @state]
      (when down (throw (ex-info "mealplan unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "the planner's task changed under our push")
                        {})))
      (when (contains? removed xid)
        (throw (ex-info (str xid " is no longer in the plan") {:status 404})))
      ;; the same translation the real boundary runs; an unseeded doc
      ;; reads as a pending task the authority hasn't shown us yet
      (case (push-plan document (:status (get docs xid) "pending"))
        :noop (content-etag (get docs xid document))
        :complete
        (let [doc' (assoc (or (get docs xid) document) :status "done")]
          (swap! state
                 (fn [s]
                   (-> s
                       (assoc-in [:docs xid] doc')
                       (update :discoverable
                               #(vec (distinct (conj (vec %) xid)))))))
          (content-etag doc'))))))

(defn fake-feed
  "The boundary in memory: scriptable via seed! / remove! / down! /
  fail-pushes!."
  []
  (->FakeFeed (atom {:docs {} :discoverable [] :removed #{}
                     :down false :push-fail false})))

(defn seed!
  "Put a prep task in the fake plan (and, unless told otherwise, its
  discovery feed)."
  [fake xid doc & [{:keys [discoverable?] :or {discoverable? true}}]]
  (swap! (:state fake)
         (fn [s]
           (cond-> (-> s
                       (assoc-in [:docs xid] doc)
                       (update :removed disj xid))
             discoverable? (update :discoverable
                                   #(vec (distinct (conj (vec %) xid))))))))

(defn remove!
  "Simulate the planner no longer carrying the task: the next pull
  fails like a gone row."
  [fake xid]
  (swap! (:state fake)
         (fn [s]
           (-> s
               (update :docs dissoc xid)
               (update :discoverable (fn [d] (vec (remove #{xid} d))))
               (update :removed conj xid)))))

(defn down! [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn fail-pushes!
  "Script the push seam: truthy makes every push throw (pass a string
  for the exact failure sentence); false restores success."
  [fake failing]
  (swap! (:state fake) assoc :push-fail failing))
