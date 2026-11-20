(ns waymark10.server.oidc
  "The OIDC relying party (waymark9 server/oidc.py, scoped to phase
  9a's deliverable): Authorization: Bearer <jwt> → a verified
  Principal. AuthN is externalized — the IdP owns login, passwords,
  MFA; waymark verifies the token it minted against the issuer's
  JWKS and maps claims to the principal the guards judge.

  Engine opts:

    {:oidc {:issuer \"https://idp/realms/home\"
            :audience \"mealplan\"
            :jwks-uri \"https://idp/…/certs\"   ; fetched + cached
            :jwks {…}                            ; static map (tests)
            :roles-claim :roles                  ; keyword or path vector
            :type-claim :actor_type              ; default :human
            :app-url \"https://work.example\"    ; the external base URL
            :delegate-clients {\"waymark10-connector-claude\" \"Claude\"}
            :resource-scopes [\"waymark-workqueue10\"]}}

  A claim option given as a vector walks nested claims — Keycloak's
  realm roles live at [:realm_access :roles].

  THE DELEGATE (docs/spec-connector-door.md). A token whose `azp`
  names a client in :delegate-clients was minted by a PERSON signing
  in through a tool — the claude.ai connector — and the tool can
  present a bearer and nothing else: no X-Waymark-Grant, ever. Such a
  token resolves to an AGENT principal, `<client>:<sub>`, acting for
  the person the token names (:acts-for) and holding none of the
  person's roles — a named agent never runs unscoped (waymark-rci),
  and the router reads the grant it wears off its member row instead
  of a header. :app-url is what the 401 challenge and the
  protected-resource document name themselves by; without it neither
  says anything about OAuth, and the document's route answers 404.

  Absent config = the dev-header resolver unchanged; WITH config the
  dev headers remain the no-Bearer fallback — waymark9's line: the
  dev resolver was always one resolver among several, kept for tests.

  Verification: RS256 only (buddy-sign unsign validates exp/iss/aud);
  the JWKS cache refetches once on an unknown kid — key rotation
  without a restart. Every failure is one 401 problem carrying
  WWW-Authenticate.

  Recorded scope (each a named punt, batch B re-affirmed): the
  authorization-code + PKCE browser dance, session cookies, and
  RP-initiated logout stay unported — a cookie wrapper around bearer
  verification would mint a second credential without the login flow
  that justifies one, which does not fit cleanly here; v10 remains
  the bearer-token resolver only. The member binding rides the
  members gate — batch B's invite token (X-Waymark-Invite → the
  concealed :bind) works under this resolver unchanged, replacing
  waymark9's invited-email flow with an out-of-band token."
  (:require [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [waymark10.server.problems :as p]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(declare key-map)

(defn config
  "Validate the engine's :oidc opts at boot and add the JWKS cache —
  a static :jwks parses once here. Definition errors, not
  request-time surprises. An :rp sub-map (the browser flow,
  waymark10.server.oidc-rp) validates here too: the same boot is the
  same gate."
  [{:keys [issuer audience jwks-uri jwks rp delegate-clients] :as opts}]
  (doseq [[k v] {:issuer issuer :audience audience}]
    (when (or (nil? v) (str/blank? (str v)))
      (throw (t/definition-error (str ":oidc config declares no " k)))))
  (when (and (nil? jwks-uri) (nil? jwks))
    (throw (t/definition-error ":oidc config needs :jwks-uri (fetched) or :jwks (static)")))
  (when (and (some? delegate-clients)
             (not (and (map? delegate-clients)
                       (every? #(and (string? %) (not (str/blank? %)))
                               (keys delegate-clients))
                       (every? string? (vals delegate-clients)))))
    (throw (t/definition-error
            ":oidc :delegate-clients must map client ids to display names")))
  (when rp
    (doseq [k [:client-id :client-secret :app-url :session-secret]]
      (when (str/blank? (str (get rp k)))
        (throw (t/definition-error (str ":oidc :rp config declares no " k))))))
  (cond-> (merge {:roles-claim :roles :type-claim :actor_type}
                 opts
                 {:cache (atom (some-> jwks key-map))})
    rp (assoc :rp (merge {:cookie-name "waymark_session"
                          :session-ttl-s 28800}
                         rp))))

(defn- parse-list
  "A comma-separated environment value → its trimmed, non-empty
  members, in order. nil in, empty out."
  [s]
  (into []
        (comp (map str/trim) (remove str/blank?))
        (str/split (str s) #",")))

(defn- parse-delegates
  "WAYMARK10_OIDC_DELEGATE_CLIENTS → {client-id display}: each entry
  is `client-id=Display`, and a bare `client-id` displays as itself.
  Empty map when the variable is unset."
  [s]
  (into {}
        (map (fn [entry]
               (let [[id display] (str/split entry #"=" 2)
                     id (str/trim id)
                     display (some-> display str/trim not-empty)]
                 [id (or display id)])))
        (parse-list s)))

(defn from-env
  "The :oidc engine opts off the process environment — nil when no
  WAYMARK10_OIDC_ISSUER rides it, so an app writes
  `:oidc (oidc/from-env)` unconditionally and pays nothing undeployed.
  The defaults are the Keycloak realm shapes: the JWKS under the
  issuer's /protocol/openid-connect/certs, realm roles at
  [:realm_access :roles], the audience the client's own id.

    WAYMARK10_OIDC_ISSUER           the realm URL; presence enables
    WAYMARK10_OIDC_AUDIENCE         default: the client id
    WAYMARK10_OIDC_JWKS_URI         default: under the issuer
    WAYMARK10_OIDC_CLIENT_ID        presence adds the :rp browser flow
    WAYMARK10_OIDC_CLIENT_SECRET
    WAYMARK10_OIDC_APP_URL          the app's external base URL
    WAYMARK10_OIDC_SESSION_SECRET   the session cookie's HS256 key
    WAYMARK10_OIDC_FRONTEND_ISSUER  browser-facing issuer, when split
    WAYMARK10_OIDC_TOKEN_ENDPOINT   back-channel override, when split
    WAYMARK10_OIDC_SESSION_TTL_S    default 28800
    WAYMARK10_OIDC_REQUIRE_AUTH     \"1\" closes the surface to anonymous
    WAYMARK10_OIDC_LOGIN_REDIRECT   default on; \"0\" keeps honest 401s
    WAYMARK10_OIDC_DELEGATE_CLIENTS \"client-id=Display,…\" — connector
                                    clients whose tokens arrive as a
                                    delegate (spec-connector-door.md)
    WAYMARK10_OIDC_RESOURCE_SCOPES  comma-separated scopes the
                                    protected-resource document advertises
    WAYMARK10_OIDC_CONNECTOR_REDIRECT_URIS
                                    comma-separated redirect URIs the
                                    Claude connector client holds; default
                                    claude.ai's callback (oidc/register)
    WAYMARK10_OIDC_CONNECTOR_CLIENT_SECRET_REQUIRED
                                    \"1\" says that Claude client is
                                    confidential: registration refuses,
                                    and the owner enters the client ID
                                    and secret the operator hands out;
                                    default public
    WAYMARK10_FIRST_OWNERS          comma-separated IdP subjects born
                                    manager and recovery-admin when the
                                    store has no member, else ignored
                                    (members.first-owners, read at boot)
    WAYMARK10_MCP_APP_CLIENTS       delegate client ids trusted with the
                                    MCP Apps tools (docs/spec-mcp-apps.md)
    WAYMARK10_MCP_APP_UNDECLARED_CLIENTS
                                    clientInfo NAMES (e.g. claude-code)
                                    taken as declaring the MCP Apps
                                    extension when they do not; empty by
                                    default. The name is self-reported:
                                    this is the owner trusting that host
                                    by name, behind the connector login
    WAYMARK10_MCP_APP_TICKET_SECRET the app ticket's HMAC key; unset, the
                                    app tools are listed for nobody

  WAYMARK10_OIDC_APP_URL is read at the top level too (:app-url), with
  or without a client id: the challenge and the protected-resource
  document need the engine's external name even on a bearer-only
  deployment.

  Reading stops here; validation stays config's — a missing
  client-secret is the same boot-time definition error either way.
  The one-arg form takes any (fn [name] value) — tests pass a map."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (when-some [issuer (env "WAYMARK10_OIDC_ISSUER")]
     (let [client-id (env "WAYMARK10_OIDC_CLIENT_ID")
           delegates (parse-delegates (env "WAYMARK10_OIDC_DELEGATE_CLIENTS"))
           scopes (parse-list (env "WAYMARK10_OIDC_RESOURCE_SCOPES"))
           redirects (parse-list (env "WAYMARK10_OIDC_CONNECTOR_REDIRECT_URIS"))
           confidential? (= "1" (some-> (env "WAYMARK10_OIDC_CONNECTOR_CLIENT_SECRET_REQUIRED")
                                        str str/trim))
           app-clients (parse-list (env "WAYMARK10_MCP_APP_CLIENTS"))
           undeclared (parse-list (env "WAYMARK10_MCP_APP_UNDECLARED_CLIENTS"))
           ticket-secret (some-> (env "WAYMARK10_MCP_APP_TICKET_SECRET")
                                 str str/trim not-empty)]
       (when-not ticket-secret
         (binding [*out* *err*]
           (println "waymark10 mcp apps: WAYMARK10_MCP_APP_TICKET_SECRET is not"
                    "set, so the app tools are listed for nobody.")))
       (cond-> {:issuer issuer
                :audience (or (env "WAYMARK10_OIDC_AUDIENCE") client-id)
                :jwks-uri (or (env "WAYMARK10_OIDC_JWKS_URI")
                              (str issuer "/protocol/openid-connect/certs"))
                :roles-claim [:realm_access :roles]}
         (env "WAYMARK10_OIDC_APP_URL")
         (assoc :app-url (env "WAYMARK10_OIDC_APP_URL"))
         (seq delegates)
         (assoc :delegate-clients delegates)
         (seq scopes)
         (assoc :resource-scopes scopes)
         (seq redirects)
         (assoc :connector-redirect-uris redirects)
         confidential?
         (assoc :connector-confidential? true)
         (seq app-clients)
         (assoc :app-clients app-clients)
         (seq undeclared)
         (assoc :app-undeclared-clients undeclared)
         ticket-secret
         (assoc :app-ticket-secret ticket-secret)
         client-id
         (assoc :rp
                (cond-> {:client-id client-id
                         :client-secret (env "WAYMARK10_OIDC_CLIENT_SECRET")
                         :app-url (env "WAYMARK10_OIDC_APP_URL")
                         :session-secret (env "WAYMARK10_OIDC_SESSION_SECRET")
                         :require-auth? (= "1" (env "WAYMARK10_OIDC_REQUIRE_AUTH"))
                         :login-redirect? (not= "0" (env "WAYMARK10_OIDC_LOGIN_REDIRECT"))}
                  (env "WAYMARK10_OIDC_FRONTEND_ISSUER")
                  (assoc :frontend-issuer (env "WAYMARK10_OIDC_FRONTEND_ISSUER"))
                  (env "WAYMARK10_OIDC_TOKEN_ENDPOINT")
                  (assoc :token-endpoint (env "WAYMARK10_OIDC_TOKEN_ENDPOINT"))
                  (env "WAYMARK10_OIDC_SESSION_TTL_S")
                  (assoc :session-ttl-s
                         (parse-long (env "WAYMARK10_OIDC_SESSION_TTL_S"))))))))))

(defn client-credentials-fn
  "The engine's own outbound credential: a zero-arg token source over
  the IdP's client_credentials grant — minted on first call, re-minted
  when the cached token nears its exp (30s skew), so no static bearer
  ever rides config and rotation stops being a concept (waymark-mvl).
  :scope names the TARGET engine's audience scope (the waymark-<app>
  client scopes); a mint that names no scope opens nothing. A refusal
  or unreachable IdP throws — to a mirror that is one unreachable
  feed, never a silently-empty one. Two racing callers may both mint;
  both tokens are valid and the cache keeps the later — recorded, not
  fenced. (f true) mints past the cache: a caller's one retry when
  the target refused a token before its exp."
  [{:keys [issuer client-id client-secret scope token-endpoint]}]
  (let [endpoint (or token-endpoint
                     (str issuer "/protocol/openid-connect/token"))
        cache (atom nil)]
    (fn [& [fresh?]]
      (let [now (quot (System/currentTimeMillis) 1000)
            {:keys [token exp]} @cache]
        (if (and token (not fresh?) (< (+ now 30) exp))
          token
          (let [{:keys [status body error]}
                @(http/post endpoint
                            {:timeout 10000
                             :form-params
                             {"grant_type" "client_credentials"
                              "client_id" client-id
                              "client_secret" client-secret
                              "scope" scope}})]
            (when (or error (not= 200 status))
              (throw (ex-info (str "the IdP refused the client-credentials mint ("
                                   (or (some-> error ex-message) status) ")")
                              {:status status})))
            (let [{:keys [access_token expires_in]}
                  (wire/read-json (if (string? body) body (slurp body)))]
              (reset! cache {:token access_token
                             :exp (+ now (or expires_in 60))})
              access_token)))))))

(defn outbound-token-fn
  "client-credentials-fn off the SAME env the RP flow reads
  (from-env): the engine's own client doubles as its outbound
  identity — per-engine callers, no shared house token. nil without
  deployed OIDC env, so an app wires it unconditionally and dev/test
  stay on static tokens or none."
  ([scope] (outbound-token-fn scope #(System/getenv ^String %)))
  ([scope env]
   (when-some [{:keys [issuer rp]} (from-env env)]
     (when rp
       (client-credentials-fn {:issuer issuer
                               :client-id (:client-id rp)
                               :client-secret (:client-secret rp)
                               :scope scope
                               ;; the same back-channel override the
                               ;; RP's code exchange honors
                               :token-endpoint (:token-endpoint rp)})))))

(defn- external-base
  "The engine's external base URL with no trailing slash, or nil when
  the deployment never named one."
  [oidc]
  (some-> (:app-url oidc) str str/trim not-empty (str/replace #"/+$" "")))

(defn resource-metadata-url
  "Where this engine's protected-resource metadata lives (RFC 9728,
  the path-inserted spelling for the MCP door): the address the 401
  challenge hands a client so discovery can start from the refusal
  itself. nil when the engine knows no external base URL — then the
  challenge says nothing about OAuth, which is honest: there is
  nothing to find."
  [oidc]
  (when-some [base (external-base oidc)]
    (str base "/.well-known/oauth-protected-resource/api/-/mcp")))

(defn challenge
  "The WWW-Authenticate value every 401 of this engine carries — the
  one spelling, so the bearer resolver's refusal, the MCP door's
  anonymous refusal and require-auth's cannot disagree. The realm
  names the engine; `error` (invalid_token) says a credential was
  presented and refused rather than merely absent; and
  resource_metadata, when the engine knows where it lives, is the one
  parameter the MCP authorization flow reads: a client that gets it
  fetches the document, finds the authorization server, and never
  needs the address configured by hand (docs/spec-connector-door.md)."
  ([oidc] (challenge oidc nil))
  ([oidc error]
   (str "Bearer realm=\"waymark\""
        (when error (str ", error=\"" error "\""))
        (when-some [url (resource-metadata-url oidc)]
          (str ", resource_metadata=\"" url "\"")))))

(def registration-path
  "Where the engine answers a connector's RFC 7591 client registration."
  "/api/-/oauth/register")

(def authorize-path
  "Where the engine answers a connector's authorization request and
  carries the person on to the provider (oidc/authorize)."
  "/api/-/oauth/authorize")

(def callback-path
  "Where the provider sends the person back (oidc/callback): the one
  redirect URI the provider's connector client holds."
  "/api/-/oauth/callback")

(def token-path
  "Where the engine carries a connector's token request through to the
  provider (oidc/token)."
  "/api/-/oauth/token")

(def claude-callbacks
  "The redirect URIs the per-house Claude connector client holds
  (docs/spec-connector-door.md, the ceremony): claude.ai's one
  callback. :connector-redirect-uris replaces it for a house whose
  client holds others."
  ["https://claude.ai/api/mcp/auth_callback"])

(defn connector-client
  "The house's one pre-made connector client for Claude: the delegate
  client whose display name is Claude, or nil."
  [oidc]
  (some (fn [[id display]]
          (when (= "claude" (str/lower-case (str display))) id))
        (sort (:delegate-clients oidc))))

(defn connector-confidential?
  "True when the house's connector client is confidential
  (:connector-confidential?): Claude is not handed it by registration,
  and the owner enters its client ID and the secret the operator gave."
  [oidc]
  (true? (:connector-confidential? oidc)))

(def confidential-refusal
  "What registration says on a house whose connector client is
  confidential: a sentence Claude can show the owner."
  (str "This house needs the client ID and secret from its operator: "
       "add the connector again, and enter both in its advanced settings."))

(defn- connector-redirects [oidc]
  (vec (or (seq (:connector-redirect-uris oidc)) claude-callbacks)))

(defn- frontend-issuer [oidc]
  (or (get-in oidc [:rp :frontend-issuer]) (:issuer oidc)))

(defn authorization-server
  "The RFC 8414 authorization-server metadata the engine serves as its
  own issuer, so a connector that registers itself (Claude with no
  client id, ticket 1d1f952a) registers HERE and not at the identity
  provider, which refuses an anonymous registration. RFC 8414 § 3.3
  says the `issuer` in the metadata must be the issuer whose
  well-known address was read, and RFC 9728 lists issuers in
  authorization_servers, so the only metadata that can name the
  engine's registration_endpoint is metadata whose issuer IS the
  engine. And because a client checks the authorization response's
  `iss` against that issuer (RFC 9207), authorize and token are the
  engine's too (ticket f7e1722d): the engine is a thin authorization
  server that carries both through to the provider — oidc/authorize,
  oidc/callback, oidc/token — so every value Claude checks is the
  engine's while the token stays the provider's. A confidential
  connector client keeps working: the token door passes its client
  authentication (the Authorization header, or client_secret in the
  form) through to the provider unchanged. nil — the provider stays
  the authorization server — when the engine knows no external base
  URL or no delegate client named Claude."
  [oidc]
  (when-some [base (external-base oidc)]
    (when (connector-client oidc)
      (do
        (cond-> {:issuer base
                 :authorization_endpoint (str base authorize-path)
                 :token_endpoint (str base token-path)
                 :registration_endpoint (str base registration-path)
                 :authorization_response_iss_parameter_supported true
                 :response_types_supported ["code"]
                 :grant_types_supported ["authorization_code" "refresh_token"]
                 :code_challenge_methods_supported ["S256"]
                 :token_endpoint_auth_methods_supported
                 (if (connector-confidential? oidc)
                   ["client_secret_basic" "client_secret_post"]
                   ["none"])}
          (seq (:resource-scopes oidc))
          (assoc :scopes_supported (vec (:resource-scopes oidc))))))))

(defn register
  "Answer one RFC 7591 registration request → [status body]. It creates
  nothing: every caller is handed the house's pre-made connector
  client, as a PUBLIC client (PKCE, no secret), with the redirect URIs
  that client holds, and what else the request asked for is ignored.
  A request whose redirect_uris are not all among those is refused
  with a sentence, because the provider would refuse the login
  anyway. On a house whose client is confidential every request is
  refused with `confidential-refusal`: the client ID and secret are the
  operator's to hand out. Each answer is one line on *err*: the
  evidence an owner's failed connect leaves."
  [oidc request]
  (let [client (connector-client oidc)
        held (connector-redirects oidc)
        asked (when (map? request) (:redirect_uris request))
        asked (when (sequential? asked) (mapv str asked))
        confidential? (connector-confidential? oidc)
        ok? (and (not confidential?) (seq asked) (every? (set held) asked))]
    (binding [*out* *err*]
      (println "waymark10 oauth: registration for"
               (pr-str (or asked []))
               (cond ok? (str "answered client " client)
                     confidential? "refused: the client is confidential"
                     :else "refused")))
    (cond
      confidential?
      [400 {:error "invalid_client_metadata"
            :error_description confidential-refusal}]

      ok?
      [201 {:client_id client
            :client_name (str (get-in oidc [:delegate-clients client]))
            :redirect_uris held
            :token_endpoint_auth_method "none"
            :grant_types ["authorization_code" "refresh_token"]
            :response_types ["code"]}]

      :else
      [400 {:error "invalid_redirect_uri"
            :error_description
            (str "This house's connector client accepts only "
                 (str/join ", " held)
                 " as its redirect URI, and this registration asked for "
                 (if (seq asked) (str/join ", " asked) "none") ".")}])))

;; ── the thin authorization server (ticket f7e1722d) ──────────────────
;;
;; Nothing is stored: Claude's state and redirect_uri ride the
;; provider round trip inside a signed, short-lived state of the
;; engine's own. PKCE runs end to end between Claude and the provider
;; (the challenge goes out unchanged, the verifier comes back
;; unchanged), the person's password only ever meets the provider, and
;; no code or token is logged.

(def state-ttl-s
  "How long a wrapped state lives: one sign-in, time to type a password."
  600)

(def ^:private state-audience
  "The wrapped state's `aud`: a session cookie signed with the same
  secret is never taken for one."
  "waymark-oauth-state")

(defonce ^:private process-secret
  (let [b (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (java.util.Base64/getUrlEncoder) b)))

(defn- state-secret
  "The key the wrapped state is signed with: the RP's session secret
  when the deployment has one — every allocation of a deploy shares
  it — and otherwise one random per process, so a restart mid-sign-in
  only makes that person start again."
  [oidc]
  (or (some-> (get-in oidc [:rp :session-secret]) str not-empty)
      process-secret))

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn form-decode
  "An x-www-form-urlencoded string — a query string or a form body —
  as a {string string} map; a pair that does not decode is dropped."
  [s]
  (into {}
        (keep #(let [[k v] (str/split % #"=" 2)]
                 (when-not (str/blank? k)
                   (try [(java.net.URLDecoder/decode ^String k "UTF-8")
                         (java.net.URLDecoder/decode ^String (or v "") "UTF-8")]
                        (catch IllegalArgumentException _ nil)))))
        (str/split (or s "") #"&")))

(defn- with-query
  "uri with params appended as its query; a nil value is left out."
  [uri params]
  (str uri (if (str/includes? uri "?") "&" "?")
       (str/join "&" (keep (fn [[k v]]
                             (when (some? v) (str k "=" (url-encode v))))
                           params))))

(defn- body-string [req]
  (let [b (:body req)]
    (cond (nil? b) "" (string? b) b :else (slurp b))))

(defn- callback-url [oidc] (str (external-base oidc) callback-path))

(defn- provider-auth-endpoint
  "Where the person is sent: the browser-facing issuer."
  [oidc]
  (or (get-in oidc [:rp :auth-endpoint])
      (str (frontend-issuer oidc) "/protocol/openid-connect/auth")))

(defn- provider-token-endpoint
  "The engine calls the provider's token endpoint itself, from inside:
  the backend issuer, as oidc-rp's own exchange does."
  [oidc]
  (or (get-in oidc [:rp :token-endpoint])
      (str (:issuer oidc) "/protocol/openid-connect/token")))

(defn- oauth-error [status error description]
  {:status status
   :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
   :body (wire/write-json {:error error :error_description description})})

(defn- found [url]
  {:status 302 :headers {"Location" url "Cache-Control" "no-store"} :body ""})

(defn- log! [& parts]
  (binding [*out* *err*] (apply println "waymark10 oauth:" parts)))

(defn- wrap-state [oidc state redirect-uri]
  (jwt/sign (cond-> {:aud state-audience :r redirect-uri
                     :exp (+ (now-secs) state-ttl-s)}
              (some? state) (assoc :s state))
            (state-secret oidc) {:alg :hs256}))

(defn- unwrap-state
  "The wrapped state back: nil when absent, altered or expired."
  [oidc token]
  (when-not (str/blank? token)
    (try (jwt/unsign token (state-secret oidc) {:alg :hs256 :aud state-audience})
         (catch Exception _ nil))))

(defn authorize
  "GET authorize-path → a ring response. The client must be the house's
  connector client and the redirect_uri one that client holds, both
  checked BEFORE any redirect, so a refusal there is a 400 and never a
  Location (no open redirect). Past them an error goes back to that
  redirect_uri as an OAuth error carrying the engine's iss; and a good
  request 302s to the provider with the same client_id, scope and PKCE
  challenge, the engine's callback as redirect_uri, and the wrapped
  state."
  [oidc req]
  (let [{:strs [client_id redirect_uri state response_type scope
                code_challenge code_challenge_method]}
        (form-decode (:query-string req))
        client (connector-client oidc)
        held (connector-redirects oidc)
        back (fn [error description]
               (log! "authorize refused:" error)
               (found (with-query redirect_uri
                        {"error" error "error_description" description
                         "state" state "iss" (external-base oidc)})))]
    (cond
      (or (nil? client) (not= client client_id))
      (do (log! "authorize refused: unknown client" (pr-str client_id))
          (oauth-error 400 "invalid_client"
                       (str "This house's connector client is " client
                            ", and this request named "
                            (if (str/blank? client_id) "none" client_id) ".")))

      (not (contains? (set held) redirect_uri))
      (do (log! "authorize refused: redirect" (pr-str redirect_uri))
          (oauth-error 400 "invalid_request"
                       (str "This house's connector client accepts only "
                            (str/join ", " held) " as its redirect URI.")))

      (not= "code" response_type)
      (back "unsupported_response_type" "This engine answers response_type=code only.")

      (or (str/blank? code_challenge) (not= "S256" code_challenge_method))
      (back "invalid_request" "PKCE is required: send a code_challenge with code_challenge_method=S256.")

      :else
      (do (log! "authorize: to the provider for client" client)
          (found (with-query (provider-auth-endpoint oidc)
                   {"response_type" "code"
                    "client_id" client
                    "redirect_uri" (callback-url oidc)
                    "scope" scope
                    "state" (wrap-state oidc state redirect_uri)
                    "code_challenge" code_challenge
                    "code_challenge_method" "S256"}))))))

(defn callback
  "GET callback-path → a ring response: the wrapped state verified (and
  its redirect_uri checked against the allow-list again), then a 302 to
  Claude's redirect_uri with the provider's code unchanged — or its
  error, as an OAuth error — Claude's own state, and iss = the engine."
  [oidc req]
  (let [params (form-decode (:query-string req))
        wrapped (unwrap-state oidc (get params "state"))
        r (:r wrapped)]
    (if-not (and wrapped (contains? (set (connector-redirects oidc)) r))
      (do (log! "callback refused: the state is missing, altered or expired")
          (oauth-error 400 "invalid_request"
                       (str "This sign-in's state is missing, altered or expired: "
                            "start the connection again from Claude.")))
      (let [{:strs [code error error_description error_uri]} params]
        (log! (if error
                (str "callback: the provider refused (" error ")")
                "callback: back to the connector"))
        (found (with-query r (merge (if error
                                      {"error" error
                                       "error_description" error_description
                                       "error_uri" error_uri}
                                      {"code" code})
                                    {"state" (:s wrapped)
                                     "iss" (external-base oidc)})))))))

(def ^:private carried-grants #{"authorization_code" "refresh_token"})

(defn token
  "POST token-path → a ring response: Claude's authorization_code or
  refresh_token grant forwarded to the provider's token endpoint, with
  redirect_uri replaced by the engine's callback (the one the code was
  issued for), code_verifier and any client authentication passed
  through, and the provider's answer returned unchanged. The access
  token stays the provider's JWT, judged at the MCP door as before."
  [oidc req]
  (let [form (form-decode (body-string req))
        grant (get form "grant_type")
        client-id (get form "client_id")
        client (connector-client oidc)]
    (cond
      (not (contains? carried-grants grant))
      (oauth-error 400 "unsupported_grant_type"
                   "This engine carries authorization_code and refresh_token grants only.")

      (and (some? client-id) (not= client client-id))
      (oauth-error 401 "invalid_client"
                   (str "This house's connector client is " client "."))

      :else
      (let [form (cond-> form
                   (= "authorization_code" grant)
                   (assoc "redirect_uri" (callback-url oidc)))
            auth (get-in req [:headers "authorization"])
            {:keys [status headers body error]}
            @(http/post (provider-token-endpoint oidc)
                        (cond-> {:timeout 10000 :as :text :form-params form}
                          auth (assoc :headers {"Authorization" auth})))]
        (log! "token:" grant "answered" (if error "nothing" status))
        (if error
          (oauth-error 502 "temporarily_unavailable"
                       "The identity provider did not answer the token request.")
          {:status status
           :headers {"Content-Type" (or (:content-type headers) "application/json")
                     "Cache-Control" "no-store"
                     "Pragma" "no-cache"}
           :body (cond (nil? body) "" (string? body) body :else (slurp body))})))))

(defn protected-resource
  "The RFC 9728 protected-resource document for the MCP door: the
  resource is the door's own URL, the authorization server is the
  engine itself when it answers registration (authorization-server)
  and otherwise the issuer (the browser-facing one when the
  deployment splits them — the client that reads this lives
  outside), bearer tokens ride the header, and the scopes are
  whatever the deployment says a client should ask for
  (:resource-scopes; omitted when unset, the RFC's own allowance).
  nil without an external base URL: an engine that cannot name itself
  advertises nothing, and the route answers 404."
  [oidc]
  (when-some [base (external-base oidc)]
    (cond-> {:resource (str base "/api/-/mcp")
             :authorization_servers [(if (authorization-server oidc)
                                       base
                                       (frontend-issuer oidc))]
             :bearer_methods_supported ["header"]
             :resource_name "waymark"}
      (seq (:resource-scopes oidc))
      (assoc :scopes_supported (vec (:resource-scopes oidc))))))

(defn delegate-id
  "The member id a delegate answers to: the connector client's id and
  the signer's subject, joined — one row per (tool, person), so two
  people using the same connector are two agents, and one person
  using two tools is two agents too. Stable across sessions, because
  both halves are the identity provider's own."
  [client sub]
  (str client ":" sub))

(defn- refuse
  "One 401 problem, WWW-Authenticate riding the response headers."
  [oidc detail]
  (p/problem :unauthenticated 401 "Unauthenticated"
             {:detail detail
              :waymark10/headers
              {"WWW-Authenticate" (challenge oidc "invalid_token")}}))

(defn- key-map
  "A parsed JWKS document → {kid → PublicKey}. Keys that refuse to
  parse are skipped loudly on *err* — one bad entry must not take
  down its siblings."
  [jwks]
  (into {}
        (keep (fn [jwk]
                (when (:kid jwk)
                  (try [(:kid jwk) (keys/jwk->public-key jwk)]
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "waymark10 oidc: JWKS key" (:kid jwk)
                                    "refused to parse -" (ex-message e)))
                         nil)))))
        (:keys jwks)))

(defn- fetch-jwks! [oidc]
  (let [{:keys [status body error]} @(http/get (:jwks-uri oidc)
                                               {:timeout 5000})]
    (when (or error (not= 200 status))
      (throw (refuse oidc (str "The issuer's JWKS is unreachable ("
                               (or (some-> error ex-message) status) ")."))))
    (let [m (key-map (wire/read-json (if (string? body) body (slurp body))))]
      (reset! (:cache oidc) m)
      m)))

(defn- key-for
  "The kid's public key: the cache (a static :jwks parsed at boot),
  then one refetch when a :jwks-uri is configured (rotation). nil is
  the caller's 401."
  [oidc kid]
  (or (get @(:cache oidc) kid)
      (when (:jwks-uri oidc)
        (get (fetch-jwks! oidc) kid))))

(defn- claims-of [oidc token]
  (let [kid (try (:kid (jwt/decode-header token))
                 (catch Exception _
                   (throw (refuse oidc "The bearer token is not a JWT."))))
        pkey (or (key-for oidc kid)
                 (throw (refuse oidc (str "No JWKS key matches kid "
                                          (pr-str kid) "."))))]
    (try
      (jwt/unsign token pkey {:alg :rs256
                              :iss (:issuer oidc)
                              :aud (:audience oidc)})
      (catch Exception e
        (let [{:keys [type cause]} (ex-data e)]
          (throw (refuse oidc
                         (if (= :validation type)
                           (case cause
                             :exp "The token has expired."
                             :aud "The token's audience is not this engine."
                             :iss "The token's issuer is not the configured one."
                             :signature "The token's signature does not verify."
                             (str "The token failed validation (" (name cause) ")."))
                           "The bearer token could not be read."))))))))

(defn- claim-at
  "A claim option is a keyword or a path vector into nested claims —
  Keycloak's realm roles live at [:realm_access :roles]."
  [claims path]
  (if (sequential? path) (get-in claims path) (get claims path)))

(defn- principal-of [oidc claims]
  (let [sub (:sub claims)
        _ (when (str/blank? (str sub))
            (throw (refuse oidc "The token carries no sub claim.")))
        roles (claim-at claims (:roles-claim oidc))
        at (some-> (claim-at claims (:type-claim oidc)) str str/lower-case keyword)
        display (or (:name claims) (:email claims) (str sub))
        client (some-> (:azp claims) str)
        tool (get (:delegate-clients oidc) client)]
    (if tool
      ;; the delegate (docs/spec-connector-door.md § 3): a person's
      ;; token, minted through a tool that can present no grant
      ;; header. It resolves to an AGENT — the leash-by-default type,
      ;; so the router's agent default (waymark-rci) holds: no worn
      ;; grant means the bootstrap surface, never the person's sight.
      ;; The person's roles stay with the person: an agent holding
      ;; recovery-admin is the one thing members.clj says must never
      ;; be minted, and a credential is not how a leash widens.
      ;; :acts-for rides OUTSIDE t/principal's closed shape, the way
      ;; oidc-rp's :session-grant does — the gate reads it at first
      ;; sight and the router reads it to look for the worn grant.
      (assoc (t/principal {:id (delegate-id client (str sub))
                           :type :agent
                           :roles #{}
                           :display (str tool " for " display)})
             :acts-for (str sub))
      (t/principal {:id (str sub)
                    ;; system stays engine-internal: an IdP cannot mint it
                    :type (if (contains? #{:human :agent} at) at :human)
                    :roles (set (when (sequential? roles) (map str roles)))
                    :display display
                    ;; the model claim (spec-seat.md R-9.4), one
                    ;; spelling with the session cookie's: the
                    ;; harness NAMES the model it runs. A token
                    ;; without the claim has no model, and the claim
                    ;; is never verified — see mint-session
                    ;; (waymark10.server.oidc-rp), where it is made
                    :model (:model claims)}))))

(defn verify
  "A raw token → its verified claims and the Principal they name —
  the RP callback's verification (waymark10.server.oidc-rp), the
  IDENTICAL path the bearer resolver walks. Throws the same 401
  problems."
  [oidc token]
  (let [claims (claims-of oidc token)]
    {:claims claims :principal (principal-of oidc claims)}))

(defn resolve-principal
  "The bearer resolver: nil when no Authorization: Bearer rides the
  request (the caller falls back to dev headers); a Principal when
  the token verifies; a thrown 401 problem otherwise."
  [oidc headers]
  (when oidc
    (when-some [[_ token] (some->> (get headers "authorization")
                                   (re-matches #"(?i)bearer\s+(\S+)"))]
      (principal-of oidc (claims-of oidc token)))))
