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
  a re-PUT of the identical size on a stored row natural-replays; a
  different size answers the honest 409. Ref fields can point at
  attachments like any kind (the default {data.name} label applies).

  Recorded deviations and named punts (each a sentence):
  - No presigned URLs, no S3, no BlobStore protocol: the local
    directory is the phase-9a store, production stores are a named
    punt.
  - Deletion is metadata-only: the :delete transition stops the bytes
    being SERVED, but no purge runs (waymark9's BlobJanitor log
    consumer is a named punt) — the file stays on disk.
  - Blob write and metadata transition are not atomic: the bytes land
    on disk before mark_stored commits, so a crash in between leaves
    pending-with-bytes, healed by the next PUT (waymark9's
    log-consumer choreography is unported).
  - Duplication (waymark9's duplicate/BlobCopier), sha256 stamping,
    and the resource_kind/resource_id target binding are unported —
    v10 phase 9a attachments attach BY being referenced, not by
    naming a target."
  (:require [clojure.java.io :as io]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.io ByteArrayOutputStream File InputStream)))

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
  (assoc-in row [:data :size] (:size inp)))

(defresource attachment
  {:kind :attachment
   :plural "attachments"
   :states [:pending :stored :deleted]
   :initial :pending
   :terminal #{:deleted}
   :nav :secondary
   :summary "{data.name} · {data.media_type} · {state}"
   :label-template "{data.name}"
   :schema [:map
            [:name [:string {:min 1 :max 160}]]
            [:media_type [:string {:min 3 :max 100}]]
            ;; stamped by the bytes route's system transition; never
            ;; supplied by hand
            [:size {:optional true :x-display {:raw true}}
             [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:name] :default "name"}
   :actions
   {:mark_stored {:from #{:pending} :to :stored
                  :input [:map [:size [:int {:min 0}]]]
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

(defn put-bytes!
  "PUT …/bytes: cap-checked bytes land in the attachment dir, then
  the metadata row transitions pending → stored (system actor).
  Returns the invoke result whose :row the router renders. A stored
  row re-PUT with the same size natural-replays through mark_stored;
  any other state refuses before the file is touched (no clobber)."
  [eng id body]
  (let [[rdef row] (load-attachment eng id)
        cap (:attachment-max-bytes eng default-max-bytes)
        data (read-capped body cap)]
    (when (zero? (alength data))
      (throw (p/schema-invalid :mark_stored {:bytes ["at least one byte"]})))
    (when-not (or (= :pending (:state row))
                  (and (= :stored (:state row))
                       (= (alength data) (get-in row [:data :size]))))
      (throw (p/wrong-state :mark_stored (:state row)
                            (get-in rdef [:actions :mark_stored :from])
                            {:kind :attachment :id (str id)})))
    (let [f (file-of eng id)]
      (io/make-parents f)
      (io/copy data f))
    (inv/invoke! eng :attachment (str id) :mark_stored
                 {:size (alength data)}
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
