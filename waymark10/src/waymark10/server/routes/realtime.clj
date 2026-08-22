(ns waymark10.server.routes.realtime
  "The live surfaces' six routes: presence, intents (three doors),
  the collab ticket, and the collab websocket itself.

  One module, four namespaces — presence, curtain, intents and collab
  are the spec's `realtime` bundle, and they are bundled because they
  share a substrate: the curtain judges who may be watched, and both
  watching surfaces read it through one cache.

  The websocket is the seam's only :plural contributor besides the
  worksheet: …/{action}/draft/collab is a SUFFIX on the draft
  sub-resource, and the draft is core (a declared :draft policy on an
  :edit action is law). Live collab is a socket on top of a draft
  row; the dependency runs one way only, so dropping this module
  leaves every draft where it was."
  (:require [clojure.string :as str]
            [waymark10.server.collab :as collab]
            [waymark10.server.grants :as grants]
            [waymark10.server.intents :as intents]
            [waymark10.server.invoke :as inv]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.router :as router]))

(set! *warn-on-reflection* true)

;; ── presence and intents (ephemeral, never law) ─────────────────────

;; The reported self must pass the REPORTER's own sight (waymark-tti.3
;; L7). Both ephemeral doors take a caller-supplied self and validate
;; it for SHAPE only, then the registry publishes the frame to
;; everyone whose visibility can GET that self — so a stranger could
;; post `{self: "/api/letters/<id>"}` and have "someone is opening
;; your letter" delivered to exactly the two people who can read that
;; letter, from a principal that 404s the row. The frame carries no
;; letter CONTENT, but it is an unearned knock on a private door, and
;; on the private trio (self, journal, letter) that is the whole
;; surface being protected.
;;
;; So: for a self naming a row of a private own-surface kind, the
;; reporter must be able to see that row. Everything else is
;; untouched — an ordinary kind's self, a collection self, the
;; workspace, a door self. An unscoped viewer (human, system) has no
;; :row? to consult and passes, exactly as it passes everywhere else:
;; it really can see every letter.
;;
;; The refusal is SILENT — the report is accepted and publishes
;; nothing, the same 204 either way. That is the curtain's own
;; discipline (intents.clj): the wire must not narrate to a reporter
;; what its own frame's fate was, or the door becomes the row-probe
;; the 404 already refuses to be. Selves too long for the registries'
;; own cap fall through to report! and meet its 422 there, so
;; validation is still the registry's, never guessed at here.
;;
;; THE GATE JUDGES WHAT THE DOOR WILL STORE, not what the caller
;; typed. presence/report! strips an http(s)://origin off a self
;; before it stores it — a raw-HTTP agent's natural spelling — and
;; this gate used to run against the RAW string: a full URL split
;; into six parts, failed the four-part row shape, and was waved
;; through as "not a row self" moments before report! turned it back
;; into exactly the private row self this gate exists to refuse. So
;; the door's own normalizer comes in as `normalize` and runs FIRST;
;; every check below reads the normalized value, which is the value
;; that will be published. Each door passes ITS OWN spelling
;; (presence/normalize-self, intents/normalize-self — the intents
;; surface stores what it is given and refuses a full URL outright),
;; because assuming the two agree is how this gap opened. What we
;; hand onward to report! is still the caller's own string: the
;; registries normalize as they always did, and the gate only judges
;; where that lands.
(defn- reportable-self?
  [eng req normalize self]
  (let [s (str (normalize self))
        parts (str/split s #"/")]
    (or (not (and (string? self)
                  (<= (count s) presence/self-max-chars)
                  (= 4 (count parts))
                  (= "api" (nth parts 1))))
        (let [rdef (some (fn [[_ r]] (when (= (nth parts 2) (:plural r)) r))
                         (inv/resources eng))]
          (or (nil? rdef)
              (not (grants/private-kind? (:kind rdef)))
              (boolean ((presence/self-visible? eng (router/visibility-of req)) s)))))))

(defn- presence-registry
  "The engine's running presence registry — 503 on an engine that
  never started (the dispatcher's discipline)."
  [eng]
  (or (some-> (:runtime eng) deref :presence)
      (throw (p/problem :presence-unavailable 503 "Presence unavailable"
                        {:detail (str "This engine is not started; the "
                                      "presence registry is not running.")}))))

(defn- presence-stream
  "GET /api/-/presence: the where-they-look stream. Unlike the
  firehose, a scoped request is not 404'd — it gets the stream
  PROJECTED: only presences on selves its visibility could GET, the
  frames it may not see byte-level absent."
  [eng]
  (fn [req]
    (let [reg (presence-registry eng)]
      (presence/sse-handler eng reg
                            (presence/self-visible?
                             eng (router/visibility-of req))
                            req))))

(defn- presence-report
  "POST /api/-/presence {self}: the explicit heartbeat for clients
  that only hold the firehose (the ported UI's case). A scoped
  principal's own reporting is always accepted — and a beat on a
  private row the reporter cannot itself see is accepted too and
  publishes nothing (reportable-self? above)."
  [eng]
  (fn [req]
    (let [reg (presence-registry eng)
          self (:self (router/read-body req))]
      (when (reportable-self? eng req presence/normalize-self self)
        (presence/report! reg (router/principal-of req) self))
      {:status 204 :headers {}})))

(defn- intents-registry
  "The engine's running intents registry — 503 on an engine that
  never started (the dispatcher's discipline)."
  [eng]
  (or (some-> (:runtime eng) deref :intents)
      (throw (p/problem :intents-unavailable 503 "Intents unavailable"
                        {:detail (str "This engine is not started; the "
                                      "intents registry is not running.")}))))

(defn- intents-stream
  "GET /api/-/intents: the considering/asking stream. Like presence
  (and unlike the firehose), a scoped request is not 404'd — it gets
  the stream PROJECTED: only intents on selves its visibility could
  GET, the frames it may not see byte-level absent."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)]
      (intents/sse-handler eng reg
                           (presence/self-visible?
                            eng (router/visibility-of req))
                           req))))

(defn- intents-report
  "POST /api/-/intents {self, action, question?}: the explicit door —
  a client surfacing a considering the router cannot see (or its own
  confirm gate as an ask, question = the consequence sentence). A
  principal's own reporting is always accepted, scoped or not — and
  a CURTAINED one's is accepted too and publishes nothing (the
  curtain lives at the registry's publish point, intents.clj): the
  204 is the same 204 either way, so the wire never narrates the
  curtain to whoever sent the report. A frame naming a PRIVATE row
  the reporter cannot itself see is dropped the same silent way
  (reportable-self?, waymark-tti.3 L7) — one more reason the 204 says
  nothing."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (router/read-body req)]
      (when (reportable-self? eng req intents/normalize-self (:self body))
        (intents/report! reg (router/principal-of req)
                         (select-keys body [:self :action :question])))
      {:status 204 :headers {}})))

(defn- intents-abandon
  "POST /api/-/intents/abandon {self, action}: the caller clears its
  own card — the considering that came to nothing, the ask it no
  longer stands behind."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (router/read-body req)]
      (intents/abandon! reg (router/principal-of req)
                        (select-keys body [:self :action]))
      {:status 204 :headers {}})))

(defn- intents-answer
  "POST /api/-/intents/answer {id, names?}: the human's yes on a
  pending ask — delivered back down the stream; the asker's retry
  still passes the guard through the E1 header. Concealment holds:
  an intent the answerer may not see is the same 404 as none."
  [eng]
  (fn [req]
    (let [reg (intents-registry eng)
          body (router/read-body req)]
      (intents/answer! reg (router/principal-of req)
                       (select-keys body [:id :names])
                       (presence/self-visible?
                        eng (router/visibility-of req)))
      {:status 204 :headers {}})))

;; ── live collab (websockets, phase 9b) ──────────────────────────────

(defn- draft-collab [eng]
  (fn [{{:keys [plural id action]} :path-params :as req}]
    (let [rdef (router/rdef-by-plural eng plural)]
      (router/check-row! req rdef id)
      (router/check-action! req rdef (keyword action))
      ;; identity over the socket: a browser WS cannot send the
      ;; headers wrap-identity reads, so a ?ticket= (minted by the
      ;; authenticated POST /api/-/collab-ticket) names the joiner. A
      ;; presented ticket that does not redeem refuses BEFORE the
      ;; upgrade — plain HTTP, never a half-open socket; no ticket
      ;; keeps the header/anonymous path exactly as before.
      (let [principal (if-some [tk (get (router/query-params req) "ticket")]
                        (or (collab/redeem-ticket! eng tk)
                            (throw (p/problem
                                    :collab-ticket-invalid 401 "Ticket invalid"
                                    {:detail (str "The ticket is unknown, expired or"
                                                  " already spent; mint a fresh one"
                                                  " (POST /api/-/collab-ticket).")})))
                        (router/principal-of req))]
        (collab/join eng rdef (keyword action) id principal req)))))

(defn- collab-ticket-mint
  "POST /api/-/collab-ticket: the authenticated session mints the
  one-time voucher its WebSocket join will present — the socket's
  identity rides the SAME resolved principal every other request
  carries."
  [eng]
  (fn [req]
    (router/json-response
     200
     (p/wire-value (collab/mint-ticket! eng (router/principal-of req)))
     router/media-type nil)))

(defn routes
  "Five static doors and one plural suffix. The suffix is seven
  segments deep, one past the draft it hangs off, so no core route
  can match it — but it stays in the :plural bucket where it belongs,
  because that is where a reader looks for it and where the next
  route on that grammar will have to go."
  [eng]
  {:module :realtime
   :static [["/api/-/presence" {:get (presence-stream eng)
                                :post (presence-report eng)}]
            ["/api/-/intents" {:get (intents-stream eng)
                               :post (intents-report eng)}]
            ["/api/-/intents/abandon" {:post (intents-abandon eng)}]
            ["/api/-/intents/answer" {:post (intents-answer eng)}]
            ["/api/-/collab-ticket" {:post (collab-ticket-mint eng)}]]
   :plural [["/api/:plural/:id/-/:action/draft/collab"
             {:get (draft-collab eng)}]]})
