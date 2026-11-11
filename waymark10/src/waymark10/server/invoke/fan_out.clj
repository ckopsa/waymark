(ns waymark10.server.invoke.fan-out
  "What bulk! and batch! share around their items (phase 7; moved out
  of waymark10.server.invoke, ticket bab6336f): the declaration's spec
  map, the refusal test and sentence the job worker reads too, the
  bulk_report document, and whole-call idempotency — the stored report
  replayed under its marker and stored on the way out."
  (:require [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn fan-out-spec
  "A :bulk/:batch declaration as a spec map — `true` is the all-default
  spelling."
  [v]
  (if (map? v) v {}))

(defn problem-reason
  "The refusal's sentence — public since phase 9b: the job worker
  reports per-item refusals with the same words a bulk report would."
  [e]
  (let [d (ex-data e)]
    (or (:detail d) (ex-message e))))

(defn refusal?
  "A per-item outcome that is a refusal (a tagged problem or a storage
  version conflict), as opposed to a failure. Public since phase 9b
  (the job worker classifies item outcomes the same way bulk! does)."
  [e]
  (boolean (or (p/problem? e)
               (:waymark10/version-conflict (ex-data e)))))

(defn report-doc
  "The bulk_report wire document."
  [action-name data extra]
  (p/wire-value (merge {:kind "bulk_report"
                        :action action-name
                        :data data}
                       extra)))

(defn fan-out-replay
  "Whole-call idempotency for bulk/batch: the stored report replays
  byte-identical under its marker; nil means proceed (and store on
  the way out)."
  [engine kind action-name marker digest idempotency-key idempotent?]
  (when-not idempotent?
    (when (nil? idempotency-key)
      (throw (p/idempotency-key-required action-name))))
  (when idempotency-key
    (when-some [hit (store/with-tx (:storage engine)
                      #(store/idempotency-lookup (:storage engine) %
                                                 idempotency-key kind))]
      (if (and (= (:action hit) marker)
               (= (:request-digest hit) digest))
        {:replayed? :idempotency :response hit}
        (throw (p/idempotency-key-reuse action-name))))))

(defn fan-out-store!
  [engine kind marker digest idempotency-key doc]
  (when idempotency-key
    (store/with-tx (:storage engine)
      #(store/idempotency-store! (:storage engine) % idempotency-key kind
                                 marker digest 200 (wire/write-json doc)
                                 "application/waymark+json"))))
