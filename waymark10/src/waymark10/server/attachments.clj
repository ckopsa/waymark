(ns waymark10.server.attachments
  "Attachments (waymark9 server/attachments.py, scoped to phase 9a's
  deliverable): metadata is a resource, bytes live behind two
  dedicated routes, upload is a two-phase transition. The attachment
  row is ordinary Waymark — audited transitions, guards, visibility —
  and the bytes ride PUT/GET /api/attachments/{id}/bytes against a
  local directory (engine opt :attachment-dir, default
  target/attachments) with a size cap (engine opt
  :attachment-max-bytes, default 10 MiB → 413 problem).

  A successful byte PUT transitions the metadata row pending → stored
  (mark_stored, the bytes system actor, hidden from every envelope) —
  a re-PUT of byte-identical content on a stored row natural-replays
  (the sha256 detects the duplicate); different content answers the
  honest 409. Ref fields can point at attachments like any kind (the
  default {data.name} label applies).

  Batch F closes two of the phase-9a punts:
  - sha256 stamping: the byte PUT records the content's sha256 hex in
    data (envelope-visible like any field), and duplicate content is
    DETECTED by it — a re-PUT of byte-identical content on a stored
    row natural-replays (200, first execution's outcome), while
    different content refuses 409 even at the same size.
  - The purge sweep (waymark9's BlobJanitor, resized): purge-deleted!
    removes the stored bytes of :deleted attachments from the
    directory (metadata rows stay, the audited record);
    start-purge-sweeper! is the loop, and the module's lifecycle
    hook carries `:elected :attachments-purge` so ONE process per
    database runs it (waymark-db9.4 — the election used to be wired
    in here, by name).

  Recorded deviations and named punts (each a sentence):
  - No presigned URLs, no S3, no BlobStore protocol: the local
    directory is the store, production stores are a named punt.
  - Blob write and metadata transition are not atomic: the bytes land
    on disk before mark_stored commits, so a crash in between leaves
    pending-with-bytes, healed by the next PUT (waymark9's
    log-consumer choreography is unported).
  - Duplication (waymark9's duplicate/BlobCopier) and the
    resource_kind/resource_id target binding are unported — v10
    attachments attach BY being referenced, not by naming a target.
  - A row stored BEFORE batch F carries no sha256; a re-PUT on it
    refuses 409 (the pre-sha digest can never natural-replay) — the
    honest answer for bytes whose provenance was never recorded."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.io ByteArrayOutputStream File InputStream)
           (java.security MessageDigest)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def bytes-actor
  "The system actor the byte route acts as."
  (t/principal {:id "waymark10-attachment-bytes" :type :system
                :display "Attachment bytes"}))

(def default-max-bytes (* 10 1024 1024))
(def default-dir "target/attachments")

;; ── the resource ────────────────────────────────────────────────────

(g/defguard bytes-route-only
  {:reads [:principal]
   :hide true
   :explain "The stored mark is written by the bytes route, never by hand."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(defhandler record-size [row inp _ctx]
  (cond-> (assoc-in row [:data :size] (:size inp))
    (:sha256 inp) (assoc-in [:data :sha256] (:sha256 inp))))

(defresource attachment
  {:kind :attachment
   :plural "attachments"
   :states [:pending :stored :deleted]
   :initial :pending
   :terminal #{:deleted}
   :nav :system
   :summary "{data.name} · {data.media_type} · {state}"
   :label-template "{data.name}"
   :schema [:map
            [:name {:x-display
                    {:label "File name"
                     :help "What the file should be called when someone downloads it again — the name, not the path."}}
             [:string {:min 1 :max 160}]]
            [:media_type {:x-display
                          {:label "Content type"
                           :help "The IANA media type the bytes will be served with — \"image/png\", \"application/pdf\". Get it wrong and the browser guesses."}}
             [:string {:min 3 :max 100}]]
            ;; stamped by the bytes route's system transition; never
            ;; supplied by hand — the prose says so rather than
            ;; pretending the form is asking (waymark-0y7)
            [:size {:optional true
                    :x-display {:raw true
                                :label "Size in bytes"}}
             [:maybe [:int {:min 0}]]]
            ;; the content's sha256 hex, stamped with the size (batch
            ;; F) — the duplicate-content anchor, envelope-visible
            [:sha256 {:optional true
                      :x-display {:raw true
                                  :label "Content fingerprint"
                                  :help "The sha256 of the stored bytes, written by the upload route when they land — two rows carrying the same one hold the same file."}}
             [:maybe [:string {:min 64 :max 64}]]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :actions
   {:mark_stored {:from #{:pending} :to :stored
                  :input [:map
                          [:size [:int {:min 0}]]
                          [:sha256 {:optional true}
                           [:maybe [:string {:min 64 :max 64}]]]]
                  :record true
                  :edit {:prefill [:size] :fence false
                         :unfenced-reason "Written once by the bytes route at upload; there is no human form to clobber."}
                  :guards [bytes-route-only]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "Recorded by the bytes route once the bytes landed; nothing is lost."}
                  :handler record-size
                  :display {:label "Mark stored" :order 5}}
    :delete {:from #{:pending :stored} :to :deleted
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The attachment disappears from its references and its bytes stop being served; the metadata row stays as the audited record."}
             :display {:label "Delete" :style :danger :order 9}}}})

;; ── the byte store (a directory, keyed by attachment id) ────────────

(defn- safe-name ^String [id]
  (let [s (apply str (filter #(or (Character/isLetterOrDigit ^char %)
                                  (contains? #{\- \_ \.} %))
                             (str id)))]
    (when (empty? s)
      (throw (p/not-found :attachment id)))
    s))

(defn- file-of ^File [eng id]
  (io/file (or (:attachment-dir eng) default-dir) (safe-name id)))

(defn- read-capped
  "The request body as bytes, refused 413 past the cap. Strings (test
  ring maps) and streams both cross."
  ^bytes [body cap]
  (let [in (cond
             (nil? body) (byte-array 0)
             (string? body) (.getBytes ^String body "UTF-8")
             :else body)]
    (if (bytes? in)
      (do (when (> (alength ^bytes in) (long cap))
            (throw (p/problem :attachment-too-large 413 "Attachment too large"
                              {:detail (str "The byte cap is " cap
                                            " bytes per attachment.")})))
          in)
      (let [out (ByteArrayOutputStream.)
            buf (byte-array 8192)]
        (loop [total 0]
          (let [n (.read ^InputStream in buf)]
            (if (neg? n)
              (.toByteArray out)
              (let [total (+ total n)]
                (when (> total (long cap))
                  (throw (p/problem :attachment-too-large 413
                                    "Attachment too large"
                                    {:detail (str "The byte cap is " cap
                                                  " bytes per attachment.")})))
                (.write out buf 0 n)
                (recur total)))))))))

(defn- load-attachment [eng id]
  (let [rdef (or (get (inv/resources eng) :attachment)
                 (throw (p/not-found :attachment id)))
        raw (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :attachment
                                       (str id) {})))]
    (when-not raw (throw (p/not-found :attachment id)))
    [rdef (inv/decode-row rdef raw)]))

(defn sha256-of
  "The content's sha256 hex — the duplicate-content anchor."
  ^String [^bytes data]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") data)]
    (str/join (map #(format "%02x" %) d))))

(defn put-bytes!
  "PUT …/bytes: cap-checked bytes land in the attachment dir, then
  the metadata row transitions pending → stored (system actor), the
  content's size AND sha256 recorded in data. Returns the invoke
  result whose :row the router renders. Duplicate content detects by
  the sha: a stored row re-PUT with byte-identical content
  natural-replays through mark_stored (same input digest → the 2.0
  replay, 200); different content — same size or not — refuses 409
  before the file is touched (no clobber)."
  [eng id body]
  (let [[rdef row] (load-attachment eng id)
        cap (:attachment-max-bytes eng default-max-bytes)
        data (read-capped body cap)
        sha (sha256-of data)]
    (when (zero? (alength data))
      (throw (p/schema-invalid :mark_stored {:bytes ["at least one byte"]})))
    (when-not (or (= :pending (:state row))
                  (and (= :stored (:state row))
                       (= sha (get-in row [:data :sha256]))))
      (throw (p/wrong-state :mark_stored (:state row)
                            (get-in rdef [:actions :mark_stored :from])
                            {:kind :attachment :id (str id)})))
    (let [f (file-of eng id)]
      (io/make-parents f)
      (io/copy data f))
    (inv/invoke! eng :attachment (str id) :mark_stored
                 {:size (alength data) :sha256 sha}
                 {:principal bytes-actor})))

(defn get-bytes
  "GET …/bytes: the stored file with the declared media type; a row
  without served bytes (pending, deleted, or a lost file) is an
  honest 404 problem."
  [eng id]
  (let [[_ row] (load-attachment eng id)
        f (file-of eng id)]
    (when-not (and (= :stored (:state row)) (.isFile f))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str "Attachment " (pr-str (str id))
                                      " has no stored bytes.")})))
    {:status 200
     :headers {"Content-Type" (get-in row [:data :media_type])
               "Content-Length" (str (.length f))}
     :body f}))

;; ── the purge sweep (batch F — waymark9's BlobJanitor, resized) ─────

(defn stored-bytes?
  "Does this attachment still have BYTES on disk? The metadata row is
  no answer — the purge leaves it standing as the audited record —
  so the sweep's only observable is the file, and this predicate is
  the one honest way to look at it from outside.

  PUBLIC for the module's conformance obligation
  (waymark10.test.packs :attachments/purge-sweep, waymark-db9.8): a
  running-surface obligation must watch a promise come TRUE on a
  schedule, and 'the deleted attachment's bytes are gone' is not a
  sentence the wire can say — a deleted row's bytes 404 either way.
  The File itself stays private; what leaves this namespace is the
  question, not the path."
  [eng id]
  (.isFile (file-of eng id)))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 attachments: " parts))))

(defn purge-deleted!
  "Remove the stored bytes of every :deleted attachment from the
  directory — the metadata rows stay, the audited record; deleting a
  file that is already gone is a no-op, so the sweep is idempotent
  and cheap to re-run. → the number of files removed."
  [eng]
  (let [st (:storage eng)
        rows (store/with-tx st
               (fn [tx] (store/query-rows st tx :attachment
                                          {:state :deleted} {:limit 500})))]
    (reduce
     (fn [n row]
       (let [f (file-of eng (:id row))]
         (if (and (.isFile f) (.delete f))
           (inc n)
           n)))
     0
     rows)))

(defn start-purge-sweeper!
  "The purge sweep's loop: every :interval-ms (default 60s),
  purge-deleted! removes the stored bytes of :deleted attachments.
  Returns the handle stop-purge-sweeper! takes.

  ONE process per database should run this, and that is no longer
  decided here: the attachments module's lifecycle hook carries
  `:elected :attachments-purge` and the engine elects the holder
  through the storage (waymark10.modules, store/elect-role!)."
  [eng {:keys [interval-ms] :or {interval-ms 60000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (purge-deleted! eng)
                              (catch Exception e
                                (warn! "purge sweep failed: "
                                       (ex-message e))))
                         (recur))))
                   "waymark10-attachments-purge")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-purge-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
