(ns workqueue10.reconsent
  "The reconsent door (waymark-kyg.2): a Google OAuth
  authorization-code flow that runs through the app itself, so a dead
  refresh token is fixed by one consent click on the breaker panel
  instead of a shell script and an SSH session
  (scripts/gcal-refresh-token.sh, retired from the critical path).

  Routes (composed OUTSIDE oidc-rp's wrap in main.clj start! — the
  same handler→handler shape engine/start!'s :wrap-handler takes):

    GET /auth/google/reconsent?connection=ID   a LIVE SESSION starts
        the dance for one provider=google connection row; 302 → the
        Google consent screen with access_type=offline &
        prompt=consent (MANDATORY — without them Google answers with
        no refresh token, the classic silent failure) + PKCE S256
    GET /auth/google/callback?code&state       code exchanged at
        Google's token endpoint; the refresh token lands on the row
        via connections/receive-token! (the audited system-only
        transition, state-preserving); 302 → the row

  THE STASH rides a signed JWT cookie of its own — waymark_reconsent,
  DISTINCT from the login's waymark_auth so an in-flight login is
  never clobbered — Path=/auth (the callback lives under it) and a
  900s TTL: Google's unverified-app interstitial alone can outlive
  the login stash's 300s.

  THE CLIENT PAIR is WORKQUEUE10_RECONSENT_CLIENT_ID / _SECRET — a
  Web-application OAuth client (the household's existing Desktop
  client cannot take server redirects). Unset, both routes answer 404
  and SAY WHY: this is an operator-facing door, and an unconfigured
  one should name its own missing wiring, not vanish. NOTE a refresh
  token spends only at the client that minted it, so the adapters'
  mint pairs must name this same Web client."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.problems :as p]
            [workqueue10.connections :as connections]))

(set! *warn-on-reflection* true)

(def auth-endpoint "https://accounts.google.com/o/oauth2/v2/auth")

(def token-endpoint "https://oauth2.googleapis.com/token")

(def default-scopes
  (str "https://www.googleapis.com/auth/calendar"
       " https://www.googleapis.com/auth/tasks"))

(def stash-ttl-s
  "Wider than the login stash's 300s on purpose: a consent screen
  with an unverified-app interstitial is a slow door."
  900)

(def cookie-name "waymark_reconsent")

(defn from-env
  "The door's config off WORKQUEUE10_RECONSENT_CLIENT_ID / _SECRET /
  _SCOPES (default: calendar + tasks — the one credential family both
  adapters share). nil when the client pair is unset."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (let [id (env "WORKQUEUE10_RECONSENT_CLIENT_ID")
         secret (env "WORKQUEUE10_RECONSENT_CLIENT_SECRET")]
     (when-not (some str/blank? [(str id) (str secret)])
       {:client-id id
        :client-secret secret
        :scopes (or (not-empty (env "WORKQUEUE10_RECONSENT_SCOPES"))
                    default-scopes)}))))

;; ── the refusals ────────────────────────────────────────────────────

(defn- unconfigured []
  (rp/problem 404 "Reconsent door not configured"
              (str "This deployment names no Google OAuth client for the "
                   "reconsent door — set WORKQUEUE10_RECONSENT_CLIENT_ID and "
                   "WORKQUEUE10_RECONSENT_CLIENT_SECRET (a Web-application "
                   "OAuth client; a Desktop client cannot take server "
                   "redirects) and restart.")))

(defn- no-session-machinery []
  (rp/problem 404 "Reconsent door not configured"
              (str "The reconsent door rides the OIDC browser flow's session "
                   "machinery and this deployment has none — configure "
                   "WAYMARK10_OIDC_* (the :rp browser flow) first.")))

(defn- retry-remedy [connection-id]
  {:retry {:href (str "/auth/google/reconsent?connection="
                      connection-id)
           :note "start the consent again from the breaker panel"}})

;; ── the authorization (finding #15 + #1): the SAME identity layer the
;; router runs, re-run here because this door composes OUTSIDE
;; wrap-identity in main.clj — resolve-session for the live principal,
;; members/gate! to union the member's HELD roles onto the credential
;; and enforce the suspension gate exactly as the router does, and the
;; household owner's rule on top: ONLY recovery-admin may reconsent ───

(def required-role
  "The household owner's decision (waymark-kyg.2): reconsenting a
  Google credential is a recovery operation, reserved to one role."
  "recovery-admin")

(defn- role-refused []
  (rp/problem 403 "Reconsent is the recovery-admin's"
              (str "Reconsenting a Google credential is reserved to the "
                   (pr-str required-role) " role, and this session does not "
                   "hold it — agents, guests, and ordinary members are "
                   "refused. Ask the household's recovery administrator.")))

(defn- authorize
  "resolve-session + members/gate! (the router's identity boundary,
  members/gate!): the live principal with its member row's held roles
  unioned and suspension enforced, required to hold recovery-admin.
  Returns [:ok principal] or [:refuse response]. gate! REFUSES by
  THROWING a problem ex-info (suspended, invited-only) — and this door
  sits outside wrap-problems — so those throws are caught and rebuilt
  as responses here; only recovery-admin's absence and a missing
  session are judged locally."
  [eng oidc req]
  (if-some [principal (rp/resolve-session oidc req)]
    (let [gated (try (members/gate! eng principal)
                     (catch clojure.lang.ExceptionInfo e
                       (if (p/problem? e) e (throw e))))]
      (cond
        (instance? clojure.lang.ExceptionInfo gated)
        (let [d (ex-data gated)]
          [:refuse (rp/problem (or (:status d) 403)
                               (or (:title d) "Refused")
                               (or (:detail d) (ex-message gated)))])

        (not (contains? (set (:roles gated)) required-role))
        [:refuse (role-refused)]

        :else [:ok gated]))
    [:refuse (rp/problem 401 "Unauthenticated"
                         (str "Reconsent starts from a live session — sign in "
                              "at /auth/login and follow the breaker panel's "
                              "link again."))]))

;; ── GET /auth/google/reconsent ──────────────────────────────────────

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- redirect-uri [rp-cfg]
  (str (:app-url rp-cfg) "/auth/google/callback"))

(defn- start [eng config req]
  (let [oidc (:oidc eng)
        rp-cfg (:rp oidc)]
    (if (nil? rp-cfg)
      (no-session-machinery)
      ;; AUTH BEFORE ANY ROW READ (finding #1/#15): the connection row
      ;; — its tag, its provider — is only quoted after recovery-admin
      ;; is proven; an unauthenticated caller learns nothing about it
      (let [[verdict principal] (authorize eng oidc req)]
        (if (= :refuse verdict)
          principal
          (let [id (get (rp/query-params req) "connection")]
            (cond
              (str/blank? (str id))
              (rp/problem 400 "No connection named"
                          "Name the breaker: /auth/google/reconsent?connection=<id> — the panel at /api/connections lists them.")

              :else
              (let [row (connections/connection-by-id eng id)]
                (cond
                  (nil? row)
                  (rp/problem 404 "No such connection"
                              (str "No connection row " id
                                   " — read the panel at /api/connections."))

                  (not= "google" (get-in row [:data :provider]))
                  (rp/problem 400 "Not a google credential"
                              (str "Connection " (get-in row [:data :tag])
                                   " names provider "
                                   (pr-str (get-in row [:data :provider]))
                                   " — this door reconsents google alone."))

                  :else
                  (let [state (rp/rand-token)
                        verifier (rp/rand-token)
                        ;; :typ binds this stash to the reconsent purpose
                        ;; (finding #4): the session cookie and login
                        ;; stash carry NO :typ, so neither can be
                        ;; presented in this door's cookie slot and
                        ;; resolve — key reuse under one secret is closed
                        stash (jwt/sign {:typ "reconsent"
                                         :state state :verifier verifier
                                         :connection id :sub (:id principal)
                                         :exp (+ (now-secs) stash-ttl-s)}
                                        (:session-secret rp-cfg) {:alg :hs256})]
                    {:status 302
                     :headers
                     {"Location"
                      (str (or (:auth-endpoint config) auth-endpoint) "?"
                           (rp/query-str
                            {"response_type" "code"
                             "client_id" (:client-id config)
                             "redirect_uri" (redirect-uri rp-cfg)
                             "scope" (or (:scopes config) default-scopes)
                             "state" state
                             "code_challenge" (rp/s256 verifier)
                             "code_challenge_method" "S256"
                             ;; MANDATORY: without offline+consent Google
                             ;; answers the exchange with NO refresh token
                             ;; — the silent failure this door exists to
                             ;; end (scripts/gcal-refresh-token.sh:101)
                             "access_type" "offline"
                             "prompt" "consent"}))
                      ;; Path=/auth: the stash rides only the /auth doors
                      "Set-Cookie" (rp/set-cookie rp-cfg cookie-name stash
                                                  stash-ttl-s "/auth")}
                     :body ""}))))))))))

;; ── GET /auth/google/callback ───────────────────────────────────────

(defn- unstash
  "The reconsent stash back off its own cookie: nil on absence,
  expiry, tamper, OR a wrong purpose — every failure is the same
  'start again'. The :typ gate (finding #4) is the load-bearing line:
  a valid session cookie (or the login stash) verifies under the same
  session-secret, but neither carries :typ \"reconsent\", so presented
  in this slot they resolve to nil — never to a live principal."
  [rp-cfg req]
  (when-some [c (get (rp/cookies req) cookie-name)]
    (try
      (let [claims (jwt/unsign c (:session-secret rp-cfg) {:alg :hs256})]
        (when (= "reconsent" (:typ claims))
          claims))
      (catch Exception _ nil))))

(defn- missing-scopes
  "The scopes asked for that the consent did NOT grant (finding #4,
  first review): Google echoes the granted set in the token
  response's :scope — a user who unchecked a box shows up here. When
  the answer carries no :scope at all, we cannot judge and return nil
  (no false alarm); a present-but-short set names exactly what fell
  out, sorted."
  [requested granted]
  (let [split (fn [s] (set (remove str/blank? (str/split (str s) #"\s+"))))
        want (split requested)
        got (split granted)]
    (when (seq got)
      (seq (sort (remove got want))))))

(defn- landed-redirect
  "The success 302 → the row. The connection id is URL-encoded
  (finding #16) and the target is a fixed same-origin prefix + that
  id, never a caller-supplied URL — no open redirect rides this door."
  [rp-cfg connection-id]
  {:status 302
   :headers {"Location" (str "/#/api/connections/"
                             (java.net.URLEncoder/encode
                              ^String (str connection-id) "UTF-8"))
             "Set-Cookie" (rp/set-cookie rp-cfg cookie-name "" 0 "/auth")}
   :body ""})

(defn- exchange-and-land
  "The post-auth body: exchange the code, land the token. WRAPPED in
  try/catch (finding #2) because this door sits outside wrap-problems
  and the write can still throw AFTER a good consent — an oversized
  token, a live↔dark race in the exact window the door exists for, a
  tx error — and a raw 500 would discard the token and risk leaking
  internals. A failed write maps to an honest problem that never
  echoes the token."
  [eng config rp-cfg code stash]
  (if-some [tokens (rp/token-exchange
                    {:endpoint (or (:token-endpoint config) token-endpoint)
                     :client-id (:client-id config)
                     :client-secret (:client-secret config)
                     :redirect-uri (redirect-uri rp-cfg)
                     :code code
                     :verifier (:verifier stash)})]
    (if (str/blank? (str (:refresh_token tokens)))
      (rp/problem 502 "No refresh token in Google's answer"
                  (str "Google exchanged the code but sent no "
                       "refresh_token — the access_type=offline & "
                       "prompt=consent trap. This door always sends "
                       "both, so a missing token here usually means "
                       "the OAuth client or account refused offline "
                       "access; start again.")
                  (retry-remedy (:connection stash)))
      (try
        (if (some? (connections/receive-token!
                    eng (:connection stash)
                    {:refresh-token (:refresh_token tokens)
                     :reconsented-by (:sub stash)}))
          ;; token stored — but a consent that dropped a scope is NOT
          ;; plain success (finding #4, first review): the source would
          ;; 403 on that capability with no clue why, so surface it
          (if-some [missing (missing-scopes (or (:scopes config) default-scopes)
                                            (:scope tokens))]
            (rp/problem 409 "Reconsent stored, but a scope was dropped"
                        (str "Google stored a fresh refresh token, but the "
                             "consent did NOT grant: " (str/join ", " missing)
                             ". The source will 403 on that capability until "
                             "you reconsent and leave every box checked.")
                        (retry-remedy (:connection stash)))
            (landed-redirect rp-cfg (:connection stash)))
          (rp/problem 404 "No such connection"
                      (str "The connection row " (:connection stash)
                           " vanished mid-consent — read the panel at "
                           "/api/connections.")))
        (catch Exception _
          ;; the token is NOT echoed — a write failure must not leak it
          (rp/problem 502 "The reconsent could not be written"
                      (str "Google consented and returned a token, but writing "
                           "it to the connection row failed — nothing was "
                           "stored. Start again from the breaker panel.")
                      (retry-remedy (:connection stash))))))
    (rp/problem 502 "Token exchange failed"
                "Google's token endpoint refused the code — the reconsent cannot complete."
                (retry-remedy (:connection stash)))))

(defn- callback [eng config req]
  (let [oidc (:oidc eng)
        rp-cfg (:rp oidc)]
    (if (nil? rp-cfg)
      (no-session-machinery)
      (let [{:strs [code state]} (rp/query-params req)
            stash (unstash rp-cfg req)]
        (cond
          (nil? stash)
          (rp/problem 400 "Reconsent expired"
                      (str "No live reconsent attempt rides this browser — "
                           "start again at /auth/google/reconsent?connection=<id> "
                           "(the breaker panel at /api/connections links it)."))

          ;; every piece must be present before the equality (finding
          ;; #5): a nil==nil state comparison would otherwise pass an
          ;; empty callback straight through
          (or (str/blank? (str code))
              (str/blank? (str state))
              (str/blank? (str (:state stash)))
              (str/blank? (str (:verifier stash)))
              (str/blank? (str (:connection stash)))
              (not= state (:state stash)))
          (rp/problem 400 "Reconsent refused"
                      "The callback's state does not match the reconsent attempt — start again."
                      (retry-remedy (:connection stash)))

          :else
          ;; RE-RESOLVE the live session (finding #3): the stash's :sub
          ;; is an audit string, never authority. The callback must be
          ;; the SAME recovery-admin acting as themselves — a stolen
          ;; stash without the session, or a different principal's
          ;; session, is refused. This collapses the replay window: a
          ;; replay can only be that same admin, non-escalating.
          (let [[verdict principal] (authorize eng oidc req)]
            (cond
              (= :refuse verdict) principal

              (not= (:id principal) (:sub stash))
              (rp/problem 403 "This is not the session that began the reconsent"
                          (str "The reconsent was started by another principal; "
                               "the callback must be completed by the same "
                               "recovery-admin. Start again yourself.")
                          (retry-remedy (:connection stash)))

              :else
              (exchange-and-land eng config rp-cfg code stash))))))))

;; ── the wrap main.clj composes ──────────────────────────────────────

(defn wrap
  "The ring middleware main.clj composes OUTSIDE oidc-rp's own wrap
  ((comp (reconsent/wrap eng) (oidc-rp/wrap-handler eng)) — this
  door's two routes answer before the require-auth gate can judge
  them; it carries its own session check): the /auth/google doors in
  front, everything else through untouched. config defaults to
  from-env; tests pass {:client-id … :client-secret …
  :token-endpoint … :auth-endpoint … :scopes …} explicitly — an empty
  map is the unconfigured door."
  [eng & [config]]
  (let [config (or config (from-env))
        configured? (not (some str/blank? [(str (:client-id config))
                                           (str (:client-secret config))]))]
    (fn [handler]
      (fn [req]
        (if (and (= :get (:request-method req))
                 (contains? #{"/auth/google/reconsent"
                              "/auth/google/callback"}
                            (:uri req)))
          (if-not configured?
            (unconfigured)
            (case (:uri req)
              "/auth/google/reconsent" (start eng config req)
              "/auth/google/callback" (callback eng config req)))
          (handler req))))))
