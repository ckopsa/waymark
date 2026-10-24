(ns waymark10.server.webhooks
  "Webhooks (phase 9b): the outbox is a product. The transition log is
  already an outbox (phase 6); this exposes it. A :subscription is an
  engine-served resource — url, kind filter, optional secret — and
  delivery is at-least-once off the log: the deliverer keeps ONE
  cursor per subscription in waymark10_cursors, drains everything
  past it on every wake, and persists the cursor per delivered event,
  so a restart replays instead of dropping. The events dispatcher is
  only the wake signal (plus its poll backstop); the log carries
  truth, exactly the phase-6 discipline.

  The wire: POST each matching transition to the subscription's url —
  the body is the SSE frame's data (waymark10.server.events/
  transition-payload, snake keys), the event id rides
  X-Waymark-Event-Id, and when the subscription declares a secret the
  body is signed: X-Waymark-Signature: hex hmac-sha256(secret, body).
  Third parties verify with the shared secret and never learn the
  envelope format.

  THE SECRET MAY BE A REFERENCE. `secret` takes a literal key, or the
  bare id of a `secret` row (waymark10.server.secrets). The field keeps
  the id and never the value; the deliverer reads the row's value at
  each delivery, so a replaced value signs the next one. While the row
  holds no value the deliveries WAIT: the cursor stays, the
  subscription stays active, and `failure_reason` says what it waits
  for. Nothing goes out unsigned and nothing is marked failed.

  A LITERAL KEY IS NEVER SHOWN. At create, a `secret` that is not the
  id of a secret row moves to `signing_key`, a :secret field that no
  projection carries, and `secret` keeps only the mark `set`. So get,
  query, history and the event frames show that a key is there and
  never the key; the deliverer still signs with it. A row from before
  this, with its literal still in `secret`, signs as it did.

  Failure discipline, deliberately NOT waymark9's: subscriptions.py
  skipped a refusing event after its attempts and advanced the cursor
  (liveness over completeness); v10 marks the SUBSCRIPTION failed —
  bounded retries with backoff, then :mark_failed as the system actor,
  logged, cursor parked at the refusing event — so a resume (after
  fixing the endpoint) continues from exactly where delivery stopped.
  Nothing is silently dropped; the trade is that one broken endpoint
  stops its own stream (never anyone else's).

  Batch F makes that trade PER SUBSCRIPTION (:delivery_policy,
  declaration-driven): \"fail\" (the default, exactly the discipline
  above) or \"skip\" (waymark9's liveness posture — a delivery that
  exhausts its retries logs to *err*, the cursor advances past the
  refusing event, and the subscription stays active). And waymark9's
  revoked terminal state is ported after all: :revoke is owner-gated
  (the subscription's creator, never another principal) and terminal —
  paused and failed still both resume; revoked does not.

  WHO MADE THE MOVE. Each delivery names its actor: `actor` is the
  principal as the log holds it (its `id` is the address, `seat:…`),
  and `actor_name` is the name of the row that address names, or the
  principal's own display. A subscription's `skip_actors` lists
  addresses whose moves it does not want: such a transition is passed
  over at the drain, the cursor advancing, and is never POSTed.

  WHICH DOMAINS. A subscription's `domains` lists domain NAMES, and
  none means every domain. The filter judges only a kind that keeps a
  domain's name: one whose declaration says what an absent `domain`
  counts as (`:absent-as`), as ticket and change do. Such a row passes
  when its domain, or that absent value when it stores none, is in the
  list; it is passed over as a skipped actor's move is. A row of any
  other kind passes: a seat's `domain` is a row's id and not a name,
  and most kinds keep none.

  A row outside the list still passes in two cases. Its `requested_by`
  is in the list: one domain asked another for the work, and the asker
  hears its request move. Or the subscription sets `service_alerts` and
  the transition is a service seat's alert: a seat's `mark_halted`,
  which is how a halt and a budget wall are written, or a change's
  `stall` made by a seat, where that seat `serves` any and is itself in
  one of the listed domains.

  Recorded deviations and scope, each a sentence:
  - One deliverer thread drains every subscription's cursor in turn —
    the v10 spelling of a worker per active subscription; delivery is
    sequential per subscription either way, and the cursor rows are
    the real per-subscription state.
  - A new subscription hears the world from its own creation
    transition, never before (waymark9's discipline): the first drain
    seeds the cursor at the newest transition of the subscription row
    itself.
  - :subscription transitions are never delivered (waymark9 excluded
    them too) — a webhook narrating its own bookkeeping is feedback,
    not signal.
  - The active-subscription set re-reads from storage on every drain —
    no cache to invalidate; the drain is already IO-bound on delivery."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.audience :as audience]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.listen :as listen]
            [waymark10.server.members :as members]
            [waymark10.server.render :as render]
            [waymark10.server.scope :as scope]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 webhooks: " parts))))

(defn- said
  "What a Throwable says for the log: its message, or its class when it
  has none (a StackOverflowError has none)."
  [^Throwable e]
  (or (ex-message e) (.getName (class e))))

(def deliverer-actor
  "The system actor that records delivery failure on a subscription."
  (t/principal {:id "waymark10-webhooks" :type :system
                :display "Webhook deliverer"}))

;; ── the resource ────────────────────────────────────────────────────

(defn- system? [ctx]
  (= :system (get-in ctx [:principal :type])))

(g/defguard ^:private deliverer-only
  {:name :deliverer-marks-failure
   :explain "Failure is the deliverer's record, never a client's claim — pause instead."
   :reads [:principal]}
  [_ _ ctx]
  (if (system? ctx) (t/allow) (t/deny)))

(defhandler record-failure [row inp _ctx]
  (assoc-in row [:data :failure_reason] (:reason inp)))

(defhandler record-deliveries [row inp _ctx]
  (update row :data merge
          (into {} (filter (comp some? val))
                (select-keys inp [:last_delivered_at :skipped_count
                                  :last_skipped_at :last_skip_error]))))

(g/defguard ^:private owner-only
  {:name :owner-revokes
   :explain "Only the subscription's owner may revoke it — pause it instead."
   :reads [:principal]}
  [row _ ctx]
  (if (and (some? (:owner row))
           (= (:owner row) (get-in ctx [:principal :id])))
    (t/allow) (t/deny)))

(def ^:private row-id-form
  #"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

(def literal-mark
  "What `secret` reads as when a literal key was typed. The key itself
  is in `signing_key`."
  "set")

(defn- conceal-literal
  "At create: a `secret` that is not the id of a secret row is a
  literal key. It moves to `signing_key` and `secret` keeps the mark.
  A caller's own `signing_key` is dropped: the field is the engine's."
  [row ctx]
  (let [s (get-in row [:data :secret])
        ref? (and (string? s)
                  (re-matches row-id-form s)
                  (if-some [rd (:read ctx)]
                    (some? (rd :secret s))
                    true))
        row (update row :data dissoc :signing_key)]
    (if (or (not (string? s)) (str/blank? s) ref?)
      row
      (update row :data assoc :secret literal-mark :signing_key s))))

(defn- legacy-literal
  "The at-rest backfill's move (ticket dbeb2f25): a row from before
  `signing_key`, with its literal key still in the non-secret `secret`,
  has it moved to `signing_key` and `secret` keeps the mark, as
  conceal-literal does at create. A reference, the mark and an
  unsigned row stay as they are."
  [data]
  (let [s (:secret data)]
    (if (and (string? s) (not (str/blank? s)) (not= literal-mark s)
             (not (re-matches row-id-form s)) (nil? (:signing_key data)))
      (assoc data :secret literal-mark :signing_key s)
      data)))

(def ^:private skip-actors-field
  "The `skip_actors` field, one spelling for the row and for restate."
  [:skip_actors {:optional true
                 :examples [["seat:a7006f1e-175b-4b82-9d78-afc71af2d55b"]]
                 :x-display
                 {:raw true
                  :label "Whose moves to leave out"
                  :help "The addresses of the principals whose own moves this endpoint does not want to hear — seat:… for a seat, as a delivery's actor spells it. Left blank it hears everyone's."}}
   [:maybe [:vector {:max 20} [:string {:min 1 :max 120}]]]])

(def ^:private domains-field
  "The `domains` field, one spelling for the row and for restate."
  [:domains {:optional true
             :examples [["factory"]]
             :x-display
             {:raw true
              :label "Which domains to hear about"
              :help "The names of the domains whose rows this endpoint wants — factory, household. A row that stores no domain counts as its kind says, which is factory. A kind that keeps no domain name is heard whatever is listed. Left blank it hears every domain."}}
   [:maybe [:vector {:max 20} [:string {:min 1 :max 120}]]]])

(def ^:private service-alerts-field
  "The `service_alerts` field, one spelling for the row and for restate."
  [:service_alerts {:optional true
                    :x-display
                    {:label "Hear its service seats' alerts"
                     :help "Turn it on and this endpoint also hears a halt, a budget wall or a stalled change of a seat that serves any domain and is in one of the domains listed, whatever domain the work was for. It changes nothing when no domains are listed."}}
   [:maybe :boolean]])

(def ^:private url-field
  [:url {:x-display
         {:label "Where to POST"
          :help "The endpoint that will receive each event as a JSON POST — https, and reachable from this engine, or nothing arrives."}}
   [:string {:min 1 :max 250}]])

(def ^:private kinds-field
  [:kinds {:optional true
           :examples [["task" "chore"]]
           :x-display
           {:label "Which kinds to hear about"
            :help "The resource kinds whose transitions this endpoint wants — task, meal, chore. Left blank it hears about all of them."}}
   [:maybe [:vector [:string {:min 1 :max 64}]]]])

(def ^:private description-field
  [:description {:optional true
                 :x-display
                 {:label "What this endpoint is for"
                  :help "A line for whoever finds this subscription later — whose integration it feeds, and who to ask when it starts failing."}}
   [:maybe [:string {:max 200}]]])

(def ^:private secret-field
  [:secret {:optional true
            :x-display
            {:raw true
             :label "Signing secret"
             :help "The shared key each delivery is HMAC-signed with, so the receiver can prove the POST came from here. Give the id of a secret row, bare, and the engine signs with the value that row holds and never shows it; the deliveries wait while the row holds no value. A key typed here signs too, and is shown afterwards only as set. Leave it blank and the deliveries go unsigned."}}
   [:maybe [:string {:min 8 :max 120}]]])

(def ^:private delivery-policy-field
  [:delivery_policy {:optional true
                     :x-display
                     {:label "When an endpoint stops answering"
                      :choices
                      {"fail" "Stop and wait — the subscription goes failed and the cursor parks where it was, so nothing is lost when someone resumes it"
                       "skip" "Keep going — log the miss, advance past it, stay active; the event is gone but the stream is not"}}}
   [:maybe [:enum "fail" "skip"]]])

(def ^:private actions-field
  "The `actions` predicate field (E-1.2), as a `wake_on` entry spells it."
  [:actions {:optional true
             :examples [["complete"]]
             :x-display
             {:raw true
              :label "Which doors to hear about"
              :help "The actions whose transitions this subscription hears, by name — complete, groom. Left blank it hears every action of the kinds it names."}}
   [:maybe [:vector {:max 40} [:string {:min 1 :max 64}]]]])

(def ^:private filter-field
  "The `filter` predicate field (E-1.2): which moved rows it hears."
  [:filter {:optional true
            :x-display
            {:raw true
             :label "Only rows matching"
             :spelled-by-hand scope/filter-spelled-by-hand
             :help "Which rows it hears about: field=value pairs in the shape of that kind's own query. A transition is heard only when the row that moved matches, after the move. Left blank, every row of the kinds it names is heard."}}
   [:maybe scope/filter-map-schema]])

(def ^:private pull-cue-schema
  "One cue a pull receiver carries (E-3.11): a seat's `inbox.cues`
  entry as the seat wrote it, and the note the inbox door adds to each
  event it matches."
  [:map
   [:kind [:string {:min 1 :max 64}]]
   [:actions [:vector [:string {:min 1 :max 64}]]]
   [:filter {:optional true} [:maybe scope/filter-map-schema]]
   [:note {:optional true} [:maybe [:string {:min 1 :max 280}]]]])

(def ^:private receiver-field
  "Who hears a match (E-3.1): none is `url` (E-3.2), a `door` (E-3.3),
  or a `pull`, a seat's inbox (E-3.9)."
  [:receiver {:optional true
              :examples [{:type "door" :kind "seat" :action "fire"
                          :id "a7006f1e-175b-4b82-9d78-afc71af2d55b"}]
              :x-display
              {:raw true
               :spelled-by-hand "A receiver is a small JSON object: type door, kind seat, the seat's id and action fire, with the settle and count fields when it needs them."
               :label "Who hears it"
               :help "Left blank, the endpoint above hears each match as a POST. A door — type door, kind seat, the seat's id, action fire — wakes that seat instead: settle_seconds and max_wait_seconds hold the wake as a wake_on entry's do, and at_least or at_most, with count_filter, wait for the rows of the kind that moved to reach a size. Only a writer who holds seat.fire on that seat writes one, and it must name the kinds it hears. A pull — type pull, kind seat, the seat's id, and its cues — is that seat's inbox: its sittings read the matches, and nothing is sent."}}
   [:maybe
    [:map
     [:type {:x-display
             {:label "Kind of receiver"
              :choices {"url" "The endpoint above hears each match as a POST"
                        "door" "A seat's fire door: the match wakes that seat"
                        "pull" "A seat's inbox: its sittings read the match"}}}
      [:enum "url" "door" "pull"]]
     [:kind {:optional true :x-display {:label "The door's kind"}} [:maybe [:string {:min 1 :max 64}]]]
     [:id {:optional true :x-ref {:kind-from :kind} :x-display {:label "The seat"}}
      [:maybe [:string {:min 1 :max 64}]]]
     [:action {:optional true :x-display {:label "The door"}} [:maybe [:string {:min 1 :max 64}]]]
     [:settle_seconds {:optional true :x-display {:label "Quiet seconds"}} [:maybe [:int {:min 1 :max 604800}]]]
     [:max_wait_seconds {:optional true :x-display {:label "Longest wait, seconds"}} [:maybe [:int {:min 1 :max 604800}]]]
     [:at_least {:optional true :x-display {:label "Wake at this many or more"}} [:maybe [:int {:min 1}]]]
     [:at_most {:optional true :x-display {:label "Wake at this many or fewer"}} [:maybe [:int {:min 0}]]]
     [:count_filter {:optional true
                     :x-display {:label "Count only rows matching"
                                 :spelled-by-hand scope/filter-spelled-by-hand}}
      [:maybe scope/filter-map-schema]]
     [:cues {:optional true :x-display {:label "Notes on what the inbox hears"}}
      [:maybe [:vector {:max 20} pull-cue-schema]]]]]])

(defn door-receiver?
  "Is this subscription's receiver a door (E-3.3)? None is a url's."
  [row]
  (= "door" (some-> (get-in row [:data :receiver :type]) name)))

(defn pull-receiver?
  "Is this subscription's receiver a seat's inbox (E-3.9)?"
  [row]
  (= "pull" (some-> (get-in row [:data :receiver :type]) name)))

(defn seat-receiver?
  "Does this subscription hear for a seat, by its door or its inbox?
  Either is the seat's, and neither POSTs to a url."
  [row]
  (or (door-receiver? row) (pull-receiver? row)))

(defn holds-seat-fire?
  "Does `principal`, wearing grant view `g`, hold `seat.fire` on the seat (E-3.4)?"
  [principal g seat-id]
  (or (not= :agent (:type principal))
      (boolean (and (some? g)
                    ((:action? g) :seat :fire)
                    (or (nil? (:action-on? g))
                        ((:action-on? g) :seat :fire (str seat-id)))
                    ((:row? g) :seat (str seat-id))))))

(def ^:private predicate-keys
  "What a door receiver hears and whom it wakes (E-3.4)."
  [:receiver :kinds :actions :filter :skip_actors :domains :service_alerts])

(defn- as-written [row inp]
  (merge (:data row) inp))

(defn- stamp-owner
  "A door receiver records its writer (E-3.4): `owner`, and the grant an
  agent wrote under as `owner_grant`, which each delivery judges again.
  A caller's own spelling of either is dropped, but for the engine's
  own hand or a seat's own door, which name the seat's owner (E-5.4)."
  [row ctx]
  (let [p (:principal ctx)
        given (when (or (= :system (:type p))
                        (= :seat (get-in ctx [:within :kind])))
                (some-> (get-in row [:data :owner]) str not-empty))
        row (update row :data dissoc :owner :owner_grant)]
    (if (seat-receiver? row)
      (update row :data merge
              (if given
                {:owner given}
                (cond-> {:owner (str (:id p))}
                  (= :agent (:type p))
                  (assoc :owner_grant (some-> (get-in ctx [:grant :id]) str)))))
      row)))

(defn- on-create [row ctx]
  (-> row (conceal-literal ctx) (stamp-owner ctx)))

(g/defguard ^:private a-door-receiver-is-the-seats-fire
  {:vars [:problem]
   :explain "This subscription cannot be written so. {problem}"
   :open "A door receiver names one seat by its id and that seat's fire door; the seats are their own collection, one query away."}
  [row inp ctx]
  (let [d (as-written row inp)
        r (:receiver d)
        pull? (pull-receiver? {:data d})
        no #(t/deny {:vars {:problem %}})]
    (cond
      (not (seat-receiver? {:data d}))
      (if (str/blank? (str (:url d)))
        (no "A subscription with no door receiver POSTs to its url, and it names none.")
        (t/allow))

      (and pull? (not= "seat" (some-> (:kind r) name)))
      (no "A pull receiver is a seat's inbox: kind seat, and the seat's id.")

      (and (not pull?)
           (or (not= "seat" (some-> (:kind r) name))
               (not= "fire" (some-> (:action r) name))))
      (no "The one door a subscription may open is seat.fire: kind seat, action fire.")

      (str/blank? (str (:id r)))
      (no (if pull?
            "A pull receiver names the seat whose inbox it is, by its id."
            "A door receiver names the seat it fires, by its id."))

      (empty? (:kinds d))
      (no (if pull?
            "A pull receiver names the kinds its inbox hears."
            "A door receiver names the kinds it hears. With none it would hear every transition of every kind, and each would fire the seat and spend its budget."))

      (and (some? (:at_least r)) (some? (:at_most r)))
      (no "A count receiver names at_least or at_most, never both.")

      (when-some [rd (:read ctx)]
        (try (nil? (rd :seat (str (:id r)))) (catch Exception _ true)))
      (no (str "No seat " (:id r) (if pull? " stands to hear it." " stands to be fired.")))

      :else (t/allow))))

(g/defguard ^:private door-writer-holds-seat-fire
  {:reads [:principal :grant :within]
   :vars [:problem]
   :explain "Only a writer who holds seat.fire on the seat a door receiver fires may write one, or change what it hears or whom it wakes: anyone else could fire that seat and spend its budget. {problem}"
   :open "The way to seat.fire is an approval_request that names it, and a person who approves it."}
  [row inp ctx]
  (let [d (as-written row inp)]
    (if (or (not (seat-receiver? {:data d}))
            (and (some? (:id row))
                 (= (select-keys (:data row) predicate-keys)
                    (select-keys d predicate-keys)))
            (= :seat (get-in ctx [:within :kind]))
            (holds-seat-fire? (:principal ctx) (:grant ctx)
                              (get-in d [:receiver :id])))
      (t/allow)
      (t/deny {:vars {:problem (g/no-grant-said "seat.fire")}}))))

(g/defguard ^:private managed-by-its-seat
  {:reads [:principal :within]
   :explain "This subscription is its seat's own, written from the seat's wake_on, and only the seat's own door restates it. Change the seat, and its subscription follows."
   :open "The seat that manages this subscription is named in its receiver; its own write door is the one to use."}
  [row inp ctx]
  (let [managed (some-> (if (some? (:id row))
                          (get-in row [:data :managed_by])
                          (:managed_by inp))
                        name)]
    (if (or (not= "seat" managed)
            (= :seat (get-in ctx [:within :kind]))
            (and (nil? (:id row)) (= :system (get-in ctx [:principal :type]))))
      (t/allow)
      (t/deny))))

(g/defguard ^:private the-engines-own-hand
  {:reads [:principal]
   :hide true
   :explain "The boot hands the first wake move's subscriptions to their seat, never a hand at the wire."}
  [_row _inp ctx]
  (if (system? ctx) (t/allow) (t/deny)))

(g/defguard ^:private kept-by-no-one-yet
  {:explain "This subscription is already kept: there is nothing to hand over."
   :open "No door clears this one: a kept subscription stays its seat's, and its seat's own door changes it."}
  [row _inp _ctx]
  (if (some? (get-in row [:data :managed_by])) (t/deny) (t/allow)))

(defhandler hand-to-seat [row inp _ctx]
  (assoc-in row [:data :managed_by] (:managed_by inp)))

(g/defguard ^:private kept-by-its-seat
  {:explain "Only a subscription its seat keeps is released to its owner, and this one is not its seat's."
   :open "No door clears this one: a subscription that is not its seat's is already its owner's, or a person's."}
  [row _inp _ctx]
  (if (= "seat" (some-> (get-in row [:data :managed_by]) name)) (t/allow) (t/deny)))

(defhandler release-to-owner [row inp _ctx]
  (assoc-in row [:data :managed_by] (:managed_by inp)))

(def ^:private stated-fields
  "What restate may change. Each is optional there: an omitted field
  keeps its value. A blank `secret` keeps the key that is set, since a
  set key is never shown and so cannot be sent back."
  [(update url-field 1 assoc :optional true)
   kinds-field
   actions-field
   filter-field
   skip-actors-field
   domains-field
   service-alerts-field
   description-field
   (assoc-in secret-field [1 :x-display :help]
             "A new key to sign with, from the next delivery on: the id of a secret row, bare, or a key typed here, which is shown afterwards only as set. Left blank, the signing stays as it is.")
   delivery-policy-field
   receiver-field])

(defhandler restate-subscription [row inp ctx]
  (let [was (:data row)
        row (update row :data merge
                    (select-keys inp [:url :kinds :actions :filter :skip_actors
                                      :domains :service_alerts
                                      :description :delivery_policy
                                      :receiver]))
        row (if (some? (:secret inp))
              (conceal-literal (assoc-in row [:data :secret] (:secret inp)) ctx)
              row)]
    ;; a door's writer is its owner (E-3.4); the seat's own door keeps it
    (if (and (seat-receiver? row)
             (not= (select-keys was predicate-keys)
                   (select-keys (:data row) predicate-keys))
             (not= :seat (get-in ctx [:within :kind])))
      (stamp-owner row ctx)
      row)))

(defresource subscription
  {:kind :subscription
   :plural "subscriptions"
   :states [:active :paused :failed :revoked]
   :initial :active
   :terminal #{:revoked}               ; failed and paused both resume
   :nav :system
   :summary "{data.url} · {state}"
   :schema [:map
            (update url-field 1 assoc :optional true)
            kinds-field
            actions-field
            filter-field
            skip-actors-field
            domains-field
            service-alerts-field
            description-field
            ;; the HMAC key for X-Waymark-Signature; absent = unsigned
            secret-field
            ;; a literal key typed into `secret`, moved here at create:
            ;; never rendered, never filterable
            [:signing_key {:optional true
                           :secret true
                           :at-rest :encrypted
                           :x-display
                           {:hidden true
                            :label "The literal key"
                            :spelled-by-hand "Moved here by the engine from a key typed into the signing secret; never shown."}}
             [:maybe [:string {:min 8 :max 120}]]]
            ;; what an exhausted delivery does (batch F): "fail" (the
            ;; default — mark the subscription failed, park the cursor)
            ;; or "skip" (log to *err*, advance the cursor, stay active)
            delivery-policy-field
            receiver-field
            ;; the writer of a door receiver, judged again on each match
            ;; (E-3.4); written by the engine at the write
            [:owner {:optional true
                     :x-ref {:principal true}
                     :x-display
                     {:label "Who wrote it"
                      :help "The principal who last wrote this subscription's door receiver, or what it hears. Each match is judged against their seat.fire as it stands then. Written by the engine. Not yours to fill in."}}
             [:maybe [:string {:max 200}]]]
            [:owner_grant {:optional true
                           :x-display
                           {:label "The grant it was written under"
                            :help "The grant an agent wrote this door receiver under. While it stands and admits seat.fire, the matches wake the seat; once it does not, they are skipped. Written by the engine. Not yours to fill in."}}
             [:maybe [:string {:max 64}]]]
            ;; a seat's own subscription (E-5.4): its door is the seat's
            [:managed_by {:optional true
                          :x-display
                          {:label "Kept by"
                           :choices {"seat" "A seat's own subscription, kept from its walk; only that seat's door changes it"
                                     "owner" "Written by the engine from a seat's wake_on, and its owner's own to restate"}
                           :help "seat when this subscription is a seat's own, kept from its walk: only that seat's door changes it. owner when the engine wrote it from a seat's wake_on, at the seat's birth or since: it is then its owner's own row, and the seat's writes never touch it. Written by the engine. Not yours to fill in."}}
             [:maybe [:enum "seat" "owner"]]]
            [:failure_reason {:optional true
                              :x-display
                              {:label "Why deliveries stopped"
                               :help "Written by the deliverer when it gives up — the last error it saw. Not yours to fill in."}}
             [:maybe [:string {:max 200}]]]
            ;; the deliverer's account of its own work, written at most
            ;; once a drain pass: a quiet subscription and one whose
            ;; every delivery is refused differ here
            [:last_delivered_at {:optional true
                                 :x-display
                                 {:label "Last delivered"
                                  :help "When the endpoint last accepted a delivery. Written by the deliverer. Not yours to fill in."}}
             [:maybe :waymark/instant]]
            [:skipped_count {:optional true
                             :x-display
                             {:label "Deliveries skipped"
                              :help "How many deliveries the endpoint refused and the deliverer passed over, under the skip policy. Written by the deliverer. Not yours to fill in."}}
             [:maybe [:int {:min 0}]]]
            [:last_skipped_at {:optional true
                               :x-display
                               {:label "Last skipped"
                                :help "When the deliverer last passed over a refused delivery. Written by the deliverer. Not yours to fill in."}}
             [:maybe :waymark/instant]]
            [:last_skip_error {:optional true
                               :x-display
                               {:label "Last skip error"
                                :help "What the endpoint answered at the last delivery the deliverer passed over: a status code, or the error of the attempt. Written by the deliverer. Not yours to fill in."}}
             [:maybe [:string {:max 200}]]]]
   :filterable {:state #{:eq :in}}
   :create-guards [managed-by-its-seat a-door-receiver-is-the-seats-fire
                   door-writer-holds-seat-fire]
   :on-create on-create
   :at-rest-migrate legacy-literal
   :actions
   {:pause {:from #{:active} :to :paused
            :guards [managed-by-its-seat]
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Pause" :order 1}}
    :resume {:from #{:paused :failed} :to :active
             :guards [managed-by-its-seat]
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Resume" :order 1}}
    ;; where it sends, what it hears and how it signs can change while
    ;; it runs; the cursor stays where it is, and the next delivery
    ;; reads the new row. An omitted field keeps its value.
    ;; NOT :record: this input can carry a literal signing key, and a
    ;; recorded action persists its raw inputs into the log.
    :restate {:from #{:active} :to :active
              :input (into [:map] stated-fields)
              :edit {:prefill [:url :kinds :actions :filter :skip_actors
                               :domains :service_alerts
                               :description :delivery_policy :receiver]}
              :guards [managed-by-its-seat a-door-receiver-is-the-seats-fire
                       door-writer-holds-seat-fire]
              :handler restate-subscription
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Restate" :order 2
                        :description "Change where this endpoint is, what it hears or how it is signed; deliveries carry on from where they were"}}
    ;; restate's door for a paused subscription: a self-loop, so the
    ;; change does not resume it.
    :restate_paused {:from #{:paused} :to :paused
                     :input (into [:map] stated-fields)
                     :edit {:prefill [:url :kinds :actions :filter :skip_actors
                                      :domains :service_alerts
                                      :description :delivery_policy :receiver]}
                     :guards [managed-by-its-seat a-door-receiver-is-the-seats-fire
                              door-writer-holds-seat-fire]
                     :handler restate-subscription
                     :safety {:idempotent true :reversible true :confirm false}
                     :display {:label "Restate" :order 2
                               :description "Change where this endpoint is, what it hears or how it is signed; it stays paused, and a resume carries on from where it was"}}
    ;; the engine's own, at boot: a row the first wake move wrote with
    ;; no managed_by becomes its seat's, so the seat's restate keeps it
    ;; in step (E-5.4). Neither restate takes managed_by: a person never
    ;; marks a row as a seat's.
    :hand_to_seat {:from #{:active :paused} :to :same
                   :writes #{:managed_by}
                   :input [:map [:managed_by {:x-display
                                              {:label "Kept by"
                                               :help "seat: the subscription becomes its seat's own, kept from its wake_on."}}
                                 [:enum "seat"]]]
                   :guards [the-engines-own-hand kept-by-no-one-yet]
                   :record true
                   :waives #{:edit-shape}
                   :handler hand-to-seat
                   :safety {:idempotent true :reversible false :confirm false}
                   :display {:label "Hand to its seat" :hidden true :order 12}}
    ;; the engine's own, at boot: a row a seat's wake_on wrote, kept by
    ;; the seat until now, becomes its owner's (E-5.4), so a person's
    ;; restate of it stands and the seat's writes never touch it again
    :release_to_owner {:from #{:active :paused} :to :same
                       :writes #{:managed_by}
                       :input [:map [:managed_by {:x-display
                                                  {:label "Kept by"
                                                   :help "owner: the subscription becomes its owner's own, restated on this row and not from the seat."}}
                                     [:enum "owner"]]]
                       :guards [the-engines-own-hand kept-by-its-seat]
                       :record true
                       :waives #{:edit-shape}
                       :handler release-to-owner
                       :safety {:idempotent true :reversible false :confirm false}
                       :display {:label "Release to its owner" :hidden true :order 13}}
    :revoke {:from #{:active :paused :failed} :to :revoked
             :guards [managed-by-its-seat owner-only]
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The endpoint never hears another event; a new subscription starts from its own creation, not from here."}
             :display {:label "Revoke" :style :danger :order 8}}
    :mark_failed {:from #{:active} :to :failed
                  :input [:map [:reason {:optional true
                                         :x-display
                                         {:label "What went wrong"
                                          :help "The last error the deliverer saw before it gave up — a status code, a timeout, a refused connection."}}
                                [:maybe [:string {:max 200}]]]]
                  :record true
                  :guards [deliverer-only]
                  :handler record-failure
                  :safety {:idempotent true :reversible true :confirm false}
                  :display {:label "Mark failed" :order 9}}
    ;; the deliverer's note that deliveries wait for a secret row's
    ;; value, and its removal when the value is there
    :await_secret {:from #{:active} :to :active
                   :input [:map [:reason {:optional true
                                          :x-display
                                          {:label "What it waits for"
                                           :help "The secret row whose value the deliverer needs before it can sign. Empty when the wait is over."}}
                                 [:maybe [:string {:max 200}]]]]
                   :record true
                   :replay false
                   :guards [deliverer-only]
                   :handler record-failure
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "Bookkeeping the deliverer writes while it waits for a secret's value; it removes the note when the value is there."}
                   :display {:label "Wait for the secret" :order 10}}
    ;; the deliverer's tally of one drain pass: when the endpoint last
    ;; accepted a delivery, how many it has refused under policy skip,
    ;; and the last error of those. The count is the new total, so the
    ;; door is idempotent.
    :note_deliveries {:from #{:active} :to :active
                      :input [:map
                              [:last_delivered_at {:optional true
                                                   :x-display
                                                   {:label "Last delivered"
                                                    :help "When the endpoint last accepted a delivery in this pass."}}
                               [:maybe :waymark/instant]]
                              [:skipped_count {:optional true
                                               :x-display
                                               {:label "Deliveries skipped"
                                                :help "The count of skipped deliveries after this pass."}}
                               [:maybe [:int {:min 0}]]]
                              [:last_skipped_at {:optional true
                                                 :x-display
                                                 {:label "Last skipped"
                                                  :help "When this pass last passed over a refused delivery."}}
                               [:maybe :waymark/instant]]
                              [:last_skip_error {:optional true
                                                 :x-display
                                                 {:label "Last skip error"
                                                  :help "The status code, or the error, of the last delivery this pass passed over."}}
                               [:maybe [:string {:max 200}]]]]
                      :record true
                      :replay false
                      :guards [deliverer-only]
                      ;; the deliverer's own bookkeeping, not a person's
                      ;; edit: there is no form to prefill
                      :waives #{:edit-shape}
                      :handler record-deliveries
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "Bookkeeping the deliverer writes after a drain pass; the next pass writes over it."}
                      :display {:label "Note the deliveries" :order 11}}}})

;; ── the signature ───────────────────────────────────────────────────

(defn sign
  "hex hmac-sha256(secret, body) — the X-Waymark-Signature value."
  ^String [^String secret ^String body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec.
                      (.getBytes secret StandardCharsets/UTF_8)
                      "HmacSHA256")))]
    (str/join (map #(format "%02x" %)
                   (.doFinal mac (.getBytes body StandardCharsets/UTF_8))))))

;; ── delivery ────────────────────────────────────────────────────────

(defn- http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- post!
  "One POST attempt; nil on a 2xx/3xx answer, and otherwise the failure
  as a string: the status code, or the exception's message. An interrupt
  is not an answer: it is thrown on, so a stopping deliverer does not
  count it as a failed attempt."
  [^HttpClient client ^String url headers ^String body timeout-ms]
  (try
    (let [builder (-> (HttpRequest/newBuilder (URI. url))
                      (.timeout (Duration/ofMillis (long timeout-ms)))
                      (.POST (HttpRequest$BodyPublishers/ofString body)))
          builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                               (.header b ^String k ^String v))
                             builder headers)
          resp ^HttpResponse (.send client
                                    (.build ^HttpRequest$Builder builder)
                                    (HttpResponse$BodyHandlers/discarding))]
      (when-not (< (.statusCode resp) 400)
        (str "status " (.statusCode resp))))
    (catch InterruptedException e (throw e))
    (catch Exception e
      (let [said (ex-message e)]
        (if (str/blank? said) (.getSimpleName (class e)) said)))))

(defn signing-key
  "The key one delivery is signed with: nil for an unsigned
  subscription, the literal key in `signing_key` (or, on a row from
  before that field, in `secret`), or, when `secret` is the id of a
  secret row, the value that row holds now. → ::waiting when the row
  holds no value yet. The seat key check (`routes.seats/service-of`)
  knows a calling service by this same key."
  [eng sub]
  (let [s (get-in sub [:data :secret])
        k (get-in sub [:data :signing_key])]
    (if (some? k)
      k
      (if-some [held (when (and (string? s) (re-matches row-id-form s))
                       (secrets/value-of eng s))]
        (or (:value held) ::waiting)
        s))))

(def ^:private waiting-note "Waiting: the secret ")

(defn- waits? [sub]
  (str/starts-with? (str (get-in sub [:data :failure_reason])) waiting-note))

(defn- note-wait!
  "Writes `reason` as the subscription's `failure_reason` through the
  deliverer's own door. The subscription stays active."
  [eng sub reason]
  (try
    (inv/invoke! eng :subscription (:id sub) :await_secret
                 {:reason reason}
                 {:principal deliverer-actor})
    (catch Exception e
      (warn! "could not note the wait on subscription " (:id sub) ": "
             (ex-message e)))))

(defn- hold!
  "Says once, on the subscription, which secret row it waits for."
  [eng sub]
  (when-not (waits? sub)
    (note-wait! eng sub
                (str waiting-note (get-in sub [:data :secret])
                     " holds no value yet. Deliveries start when its owner enters the value."))))

(defn- settle-wait!
  "Removes the waiting note when the secret row holds a value now."
  [eng sub]
  (when (and (waits? sub) (not= ::waiting (signing-key eng sub)))
    (note-wait! eng sub nil)))

(defn- deliver-with-retries!
  "Attempt one event's delivery up to attempts times with exponential
  backoff; → nil when the endpoint accepted it, and otherwise the last
  attempt's failure as `post!` words it. `secret` is the resolved
  signing key, or nil for an unsigned delivery."
  [client sub secret t-id body {:keys [attempts backoff-ms timeout-ms]}]
  (let [url (get-in sub [:data :url])
        headers (cond-> {"Content-Type" "application/json"
                         "X-Waymark-Event-Id" (str t-id)}
                  secret
                  (assoc "X-Waymark-Signature" (sign secret body)))]
    (loop [n 0]
      (let [failure (post! client url headers body timeout-ms)]
        (cond
          (nil? failure) nil
          (<= attempts (inc n)) failure
          :else (do (Thread/sleep (long (* backoff-ms (bit-shift-left 1 n))))
                    (recur (inc n))))))))

;; who a transition is for lives in `audience`, so that `listen` reads
;; it without requiring this namespace; these names stay for callers

(def actor-address
  "The address of a transition's actor (`audience/actor-address`)."
  audience/actor-address)

(defn stored-row
  "The row of `kind` with this id as storage holds it, or nil when the
  engine serves no such kind or the row is gone."
  [eng kind id]
  (when (and id (get (inv/resources eng) kind))
    (let [st (:storage eng)]
      (store/with-tx st #(store/load-row st % kind (str id) {})))))

(def by-actor?
  "Did an actor of `addresses` make this transition (`audience/by-actor?`)?"
  audience/by-actor?)

(def in-domains?
  "Does the transition's row answer to one of `domains`
  (`audience/in-domains?`)?"
  audience/in-domains?)

(def seat-domain-name
  "The name of the domain this seat row is in (`audience/seat-domain-name`)."
  audience/seat-domain-name)

(def service-alert?
  "Is the transition a service seat's alert in one of `domains`
  (`audience/service-alert?`)?"
  audience/service-alert?)

(def ^:private family
  "The family the subscriptions drain in (docs/spec-events.md E-5.3).
  Each one's cursor is `webhook:<id>`, the row it had before the
  family, so no cursor was renamed; a subscription with none hears the
  world from its own create (consumers/seed-member!)."
  :webhook)

(def ^:private hearing
  "How a subscription hears the log as a family member: through its
  listen entry, and never the subscriptions' own transitions."
  {:kind :subscription
   :entry #(listen/from-subscription (:data %))
   :ignore #(= :subscription (:kind %))})

(defn- mark-failed! [eng sub reason]
  (try
    (inv/invoke! eng :subscription (:id sub) :mark_failed
                 {:reason (subs reason 0 (min (count reason) 200))}
                 {:principal deliverer-actor})
    (catch Exception e
      (warn! "could not mark subscription " (:id sub) " failed: "
             (ex-message e)))))

(defn- note-deliveries!
  "Writes one drain pass's tally on the subscription through the
  deliverer's own door: when the endpoint last accepted a delivery, how
  many deliveries were skipped, and the last skipped one's error. A pass
  that sent nothing writes nothing, so a quiet subscription's log does
  not grow."
  [eng sub {:keys [delivered skipped error]}]
  (when (or delivered (pos? (long skipped)))
    (let [at (str ((:now-fn eng (fn [] (Instant/now)))))]
      (try
        (inv/invoke! eng :subscription (:id sub) :note_deliveries
                     (cond-> {}
                       delivered (assoc :last_delivered_at at)
                       (pos? (long skipped))
                       (assoc :skipped_count
                              (+ (long skipped)
                                 (long (or (get-in sub [:data :skipped_count])
                                           0)))
                              :last_skipped_at at)
                       (and (pos? (long skipped)) error)
                       (assoc :last_skip_error
                              (subs error 0 (min (count error) 200))))
                     {:principal deliverer-actor})
        (catch Exception e
          (warn! "could not note the deliveries on subscription " (:id sub)
                 ": " (ex-message e)))))))

(defn actor-name
  "What a person calls the actor of this transition: the head of the
  summary line of the row its address names (`seat:<id>` is the seat's
  name), as render names a summary's ref; else the principal's own
  display. Nil when neither is there."
  [eng t]
  (let [a (:actor t)
        a (if (map? a) a {:id a})
        [k id] (some-> (:id a) str (str/split #":" 2))
        rdef (when (and id (not (str/blank? k)))
               (get (inv/resources eng) (keyword k)))
        line (when (and rdef (re-matches row-id-form id))
               (try
                 (let [st (:storage eng)]
                   (when-some [raw (store/with-tx st
                                     #(store/load-row st % (:kind rdef) id {}))]
                     (render/target-summary rdef (inv/decode-row rdef raw) nil)))
                 (catch Exception _ nil)))]
    (or (when (string? line)
          (not-empty (first (str/split line #" · " 2))))
        (some-> (:display a) str not-empty))))

(defn- wire-body
  "The delivery body: the SSE frame's data — one shape for the stream
  and the hook — and beside its `actor`, `actor_name` when the actor
  has a name."
  ^String [eng t]
  (let [who (actor-name eng t)]
    (wire/write-json (cond-> (events/transition-payload eng t)
                       who (assoc :actor_name who)))))

(defn- drain-subscription!
  "Deliver everything past one active subscription's cursor, advancing
  it per delivered event. A delivery that exhausts its retries follows
  the subscription's :delivery_policy: \"fail\" (the default) marks
  the subscription failed and PARKS the cursor at the refusing event,
  so a resume continues from exactly there; \"skip\" logs the loss to
  *err*, advances the cursor past the refusing event, and the
  subscription stays active — liveness over completeness, chosen per
  subscription. A delivery whose signing secret is a secret row with no
  value yet is not attempted: the drain stops there, the cursor stays,
  and the subscription says what it waits for. The subscription's state
  is read anew before each send: the batch was read before it, and a
  subscription paused, failed or revoked since is sent nothing more —
  the drain stops there and the cursor stays. The pass's tally —
  whether anything was delivered, how many were skipped, the last
  skipped one's error — is written on the subscription once, at the end
  of the pass or before it is marked failed, never once per event. It
  is one member's drain of the `webhook` family (consumers/drain-member!)."
  [eng client sub opts]
  (settle-wait! eng sub)
  (let [tally (atom {:delivered false :skipped 0})
        note! (fn []
                (let [[was _] (reset-vals! tally {:delivered false :skipped 0})]
                  (note-deliveries! eng sub was)))
        lost (fn [t e then]
               (str "delivery to " (get-in sub [:data :url])
                    " failed after " (:attempts opts)
                    " attempts at event " (:id t)
                    " (" (ex-message e) ")" then))
        deliver (fn [sub t]
                  (let [body (wire-body eng t)
                        secret (signing-key eng sub)]
                    (cond
                      (not= :active
                            (:state (stored-row eng :subscription (:id sub))))
                      consumers/stop

                      (= ::waiting secret)
                      (do (hold! eng sub)
                          consumers/stop)

                      :else
                      (if-some [failure (deliver-with-retries! client sub secret
                                                               (:id t) body opts)]
                        (throw (ex-info failure {::failure failure}))
                        (swap! tally assoc :delivered true)))))]
    (try
      (consumers/drain-member!
       eng family
       (assoc hearing
              :deliver deliver
              :policy #(if (= "skip" (get-in % [:data :delivery_policy])) :skip :fail)
              :on-skip (fn [_ t e]
                         (warn! (lost t e (str "; skipping it (delivery policy: skip) — "
                                               "the subscription stays active")))
                         (swap! tally #(-> %
                                           (update :skipped inc)
                                           (assoc :error (ex-message e)))))
              :on-park (fn [_ t e]
                         (warn! (lost t e (str "; marking the subscription failed — "
                                               "resume replays from here")))
                         (note!)
                         (mark-failed! eng sub
                                       (str "delivery failed after "
                                            (:attempts opts)
                                            " attempts at event " (:id t)
                                            ": " (ex-message e)))))
       sub)
      (finally (note!)))))

(defn- drain-opts [eng opts]
  (merge {:attempts (:webhook-attempts eng 3)
          :backoff-ms (:webhook-backoff-ms eng 250)
          :timeout-ms (:webhook-timeout-ms eng 10000)}
         opts))

(defn- active-subscriptions
  "The active url receivers: a door receiver drains in its own family
  (waymark10.server.door-receivers), and a pull receiver is read by
  the inbox door (E-3.9)."
  [eng]
  (into [] (remove seat-receiver?)
        (store/with-tx (:storage eng)
          (fn [tx] (store/query-rows (:storage eng) tx :subscription
                                     {:state :active} {:limit 500})))))

(defn- drain-one!
  "One subscription's drain, and nothing escapes it: a Throwable that
  is not an Exception (StackOverflowError, AssertionError) is logged
  the same way, so it ends neither the pass nor the thread that ran
  it. An interrupt is a stop, not a failure: the flag is set again and
  nothing is logged."
  [eng client sub opts]
  (try
    (drain-subscription! eng client sub opts)
    (catch InterruptedException _
      (.interrupt (Thread/currentThread)))
    (catch Throwable e
      (warn! "drain of subscription " (:id sub) " failed: " (said e)))))

(defn- family-spec
  "The subscriptions as a consumers family (E-5.3): the active rows are
  its members, each drains in `drain-one!`, and one paused or failed
  meanwhile is no longer a member."
  [eng client opts]
  (assoc hearing
         :members #(active-subscriptions eng)
         :drain #(drain-one! eng client % opts)
         :reload (fn [sub]
                   (let [fresh (stored-row eng :subscription (:id sub))]
                     (when (and (= :active (:state fresh))
                                (not (seat-receiver? fresh)))
                       fresh)))))

(defn drain!
  "One delivery pass, in the caller's thread: the family's shared read
  (consumers/drain-family!), then each subscription with something to
  hear drains past its own cursor, one after another. Tests call it for
  determinism; the deliverer hands each one to its pool instead.
  opts {:attempts 3 :backoff-ms 250 :timeout-ms 10000} (engine opts
  :webhook-attempts / :webhook-backoff-ms / :webhook-timeout-ms
  override)."
  ([eng] (drain! eng (http-client) {}))
  ([eng client opts]
   (consumers/drain-family! eng family (family-spec eng client (drain-opts eng opts)))))

;; ── the deliverer lifecycle (engine start!/stop!) ───────────────────

(defn start-deliverer!
  "The delivery worker: the `webhook` family on the running dispatcher
  (consumers/register-family!). Its thread reads the log once for every
  active subscription and hands each one with something to hear to a
  pool of engine opt :webhook-drainers threads (8), so no endpoint
  holds another. An Error in its loop is logged and the loop goes on.
  Returns the running deliverer; stop-deliverer! ends it."
  [eng dispatcher {:keys [poll-ms] :or {poll-ms 2000}}]
  (consumers/register-family!
   eng family (family-spec eng (http-client) (drain-opts eng {}))
   {:dispatcher dispatcher :poll-ms poll-ms
    :drainers (:webhook-drainers eng 8)}))

(defn stop-deliverer! [deliverer]
  (consumers/stop-family! deliverer))
