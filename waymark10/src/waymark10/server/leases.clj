(ns waymark10.server.leases
  "Leases build ticket 1 (docs/spec-leases.md §1, §2, Settled): who
  holds a row is a row of the engine-served kind `lease`, beside the
  subject rather than in it, so holding never bumps the subject's
  version and `waymark_history` on the lease answers who took it, who
  renewed it and how it ended.

  A lease is LIVE while it is `held` and either its own `expires_at` is
  ahead, or `expires_at` is null and its holder is live (not in one of
  its kind's terminal states). One subject has at most one live lease:
  a partial unique index on (subject_kind, subject_id) over `held` rows
  is the law, and `acquire` expires the stale `held` leases on its
  subject in its own transaction before it inserts, so nobody waits for
  a sweep to free a row.

  No caller yet: the claim (build ticket 2) enrols the kind and is the
  first to acquire."
  (:require [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def leaser
  "The system actor that expires a lease: the sweep, and `acquire`
  clearing a stale lease off its subject."
  (t/principal {:id "waymark10-leases" :type :system :display "Leases"}))

;; ── the half-life rule (§2) ─────────────────────────────────────────

(defn- instant-of ^Instant [v]
  (cond (instance? Instant v) v
        (some? v) (some-> v str not-empty Instant/parse)))

(defn half-left?
  "Is at least half of `ttl-seconds` still ahead of `now` before
  `expires-at`? The half-life rule a grant's standing and a holder's
  renewal share: below half, mint or renew; otherwise do nothing. No
  ttl, or no end, is always enough."
  [now expires-at ttl-seconds]
  (let [ttl (long (or ttl-seconds 0))
        exp (instant-of expires-at)]
    (or (not (pos? ttl))
        (nil? exp)
        (>= (.getSeconds (Duration/between (instant-of now) exp))
            (quot ttl 2)))))

;; ── liveness ────────────────────────────────────────────────────────

(defn- holder-live?
  "Does the lease's holder row exist and stand outside its kind's
  terminal states? A holder of a kind this engine does not serve is
  not live."
  [ctx lease]
  (let [{:keys [holder_kind holder_id]} (:data lease)
        rdef-of (:rdef-of ctx)
        read' (:read ctx)]
    (boolean
     (when-some [hrdef (and rdef-of read' (rdef-of holder_kind))]
       (when-some [h (read' (:kind hrdef) holder_id)]
         (not (contains? (into #{} (map name) (:terminal hrdef))
                         (name (:state h)))))))))

(defn live?
  "Is `lease` live at `(:now ctx)`: held, and its own expires_at ahead,
  or no expires_at and its holder live?"
  [ctx lease]
  (and (= "held" (name (:state lease)))
       (if-some [exp (instant-of (get-in lease [:data :expires_at]))]
         (.isBefore (instant-of (:now ctx)) exp)
         (holder-live? ctx lease))))

(defn- held-on
  "The `held` leases on one subject, read through the ctx :find hook
  (the write's own transaction)."
  [ctx subject-kind subject-id]
  (when-some [find' (:find ctx)]
    (find' :lease {:subject_kind (str subject-kind)
                   :subject_id (str subject-id)
                   :state "held"}
           {:limit 50})))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard subject-held
  {:judges [:subject_kind :subject_id]
   :reads [:lease :storage]
   :vars [:subject :holder :lease]
   :explain "{subject} is already held by {holder} — lease {lease}."
   :open "A subject is free once its lease is released and its grace runs out, its work is finished, or its holder ends."}
  [_row inp ctx]
  (if-some [held (some #(when (live? ctx %) %)
                       (when (and (:subject_kind inp) (:subject_id inp))
                         (held-on ctx (:subject_kind inp) (:subject_id inp))))]
    (t/deny {:vars {:subject (str (:subject_kind inp) " " (:subject_id inp))
                    :holder (str (get-in held [:data :holder_kind]) " "
                                 (get-in held [:data :holder_id]))
                    :lease (str (:id held))}})
    (t/allow)))

(g/defguard lease-is-live
  {:reads [:storage]
   :explain "This lease is past its end; it is no one's to renew or release, and its expiry is the engine's."
   :open "Acquire the subject afresh."}
  [row _inp ctx]
  (if (or (nil? row) (nil? (:read ctx)) (live? ctx row))
    (t/allow)
    (t/deny)))

(g/defguard not-released
  {:explain "A released lease runs out on its grace; it is never renewed."
   :open "Acquire the subject afresh once the grace runs out."}
  [row _inp _ctx]
  (if (some? (get-in row [:data :released_at]))
    (t/deny)
    (t/allow)))

(g/defguard the-engine-expires-it
  {:reads [:principal]
   :hide true
   :explain "A lease is expired by the engine — the sweep, or an acquire clearing a stale lease — never by a hand at the wire."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

;; ── doors ───────────────────────────────────────────────────────────

(defn- expire-stale!
  "acquire's :on-create: stamp the lease's birth, and expire every
  `held` lease on its subject that is no longer live, through the
  lease's own `expire` door in this transaction — so the partial index
  is free for the insert and the expiry is logged."
  [row ctx]
  (let [{:keys [subject_kind subject_id]} (:data row)]
    (when-some [invoke' (:invoke ctx)]
      (doseq [stale (held-on ctx subject_kind subject_id)
              :when (not (live? ctx stale))]
        (invoke' :lease (:id stale) :expire {} {:as leaser})))
    (assoc-in row [:data :acquired_at] (instant-of (:now ctx)))))

(defn- ended [row ctx reason]
  (update row :data assoc
          :ended_at (instant-of (:now ctx))
          :end_reason reason))

(defhandler renew-lease
  [row inp _ctx]
  (assoc-in row [:data :expires_at] (:expires_at inp)))

(defhandler release-lease
  [row inp ctx]
  (let [now (instant-of (:now ctx))]
    (update row :data assoc
            :released_at now
            :expires_at (.plusSeconds now (long (or (:grace_seconds inp) 0))))))

(defhandler finish-lease
  [row _inp ctx]
  (ended row ctx "finished"))

(defhandler expire-lease
  [row inp ctx]
  (ended row ctx (or (:reason inp)
                     (if (some? (get-in row [:data :released_at])) "released" "expired"))))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private end-reasons ["released" "expired" "finished" "taken_over"])

(def ^:private holder-fields
  [[:holder_kind {:not-a-ref "A kind's name: the holder's kind, which varies by lease."
                  :x-display {:raw true :label "Holder's kind"
                              :help "The kind of the row that holds this lease — a sitting, in this epic."}}
    [:string {:min 1 :max 64}]]
   [:holder_id {:not-a-ref "The holder's id under holder_kind, which varies by lease."
                :x-display {:raw true :label "Holder"
                            :help "The id of the row that holds this lease."}}
    [:string {:min 1 :max 80}]]
   [:subject_kind {:not-a-ref "A kind's name: the held row's kind, which varies by lease."
                   :x-display {:raw true :label "Subject's kind"
                               :help "The kind of the row held."}}
    [:string {:min 1 :max 64}]]
   [:subject_id {:not-a-ref "The held row's id under subject_kind, which varies by lease."
                 :x-display {:raw true :label "Subject"
                             :help "The id of the row held. One live lease per subject."}}
    [:string {:min 1 :max 80}]]
   [:seat {:optional true
           :not-a-ref "The seat's id, copied from the holder so a claim reads by seat."
           :x-display {:raw true :label "Seat"
                       :help "The seat whose walk took this lease."}}
    [:maybe [:string {:max 80}]]]])

(def ^:private expires-field
  [:expires_at {:optional true
                :x-display {:label "Expires"
                            :help "The lease's own end. Empty when it follows its holder: live exactly as long as the holder is."}}
   [:maybe :waymark/instant]])

(defresource lease
  {:kind :lease
   :plural "leases"
   :states [:held :ended]
   :initial :held
   :terminal #{:ended}
   :nav :system
   :summary "{data.subject_kind} {data.subject_id} held by {data.holder_kind} {data.holder_id} · {state}"
   :schema
   (into [:map]
         (concat holder-fields
                 [[:acquired_at {:optional true
                                 :x-display {:label "Acquired"
                                             :help "When the lease was taken."}}
                   [:maybe :waymark/instant]]
                  expires-field
                  [:released_at {:optional true
                                 :x-display {:label "Released"
                                             :help "When the holder let go. A released lease stays held until its grace runs out, and is never renewed."}}
                   [:maybe :waymark/instant]]
                  [:ended_at {:optional true
                              :x-display {:label "Ended"
                                          :help "When the lease ended."}}
                   [:maybe :waymark/instant]]
                  [:end_reason {:optional true
                                :x-display {:label "How it ended"
                                            :help "released, expired, finished (the subject's work is over) or taken_over."}}
                   [:maybe (into [:enum] end-reasons)]]]))
   :create-schema (into [:map] (conj holder-fields expires-field))
   :create-guards [subject-held]
   :on-create expire-stale!
   :unique [[:subject_kind :subject_id {:while #{:held}}]]
   :filterable {:state #{:eq :in}
                :holder_kind #{:eq}
                :holder_id #{:eq :in}
                :subject_kind #{:eq}
                :subject_id #{:eq :in}
                :seat #{:eq}}
   :actions
   {:renew {:from #{:held} :to :held
            :input [:map [:expires_at {:x-display {:label "Expires"
                                                   :help "The lease's new end."}}
                          :waymark/instant]]
            :guards [lease-is-live not-released]
            :safety {:idempotent true :reversible true :confirm false}
            :handler renew-lease
            :display {:label "Renew" :order 1
                      :description "Move the lease's end later"}}
    :release {:from #{:held} :to :held
              :input [:map [:grace_seconds {:optional true
                                            :x-display {:label "Grace"
                                                        :help "Seconds the lease stays held after letting go; none ends it at the next acquire or sweep."}}
                            [:maybe [:int {:min 0 :max 86400}]]]]
              :guards [lease-is-live]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A released lease runs out on its grace; the subject is acquired afresh after."}
              :handler release-lease
              :display {:label "Release" :order 2
                        :description "Let go of the subject, after an optional grace"}}
    :finish {:from #{:held} :to :ended
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "The subject's work is over; a new lease is one acquire away."}
             :handler finish-lease
             :display {:label "Finish" :order 3
                       :description "End the lease at once: the subject's work is over"}}
    :expire {:from #{:held} :to :ended
             :input [:map [:reason {:optional true
                                    :x-display {:label "Why"
                                                :help "taken_over when another holder took the subject; otherwise left out."}}
                           [:maybe [:enum "expired" "taken_over"]]]]
             :guards [the-engine-expires-it]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An expired lease stays in the log; the subject is acquired afresh."}
             :handler expire-lease
             :display {:label "Expire" :order 9
                       :description "The engine ends a lease past its end"}}}
   :deviations
   ["§1 has `release` end a lease at once when it takes no grace; a door lands in one state, so `release` always stays held with expires_at = now + grace (0 by default), and the next acquire or the sweep ends it as `released`."]})
