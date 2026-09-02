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
           scopes (parse-list (env "WAYMARK10_OIDC_RESOURCE_SCOPES"))]
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
  fenced."
  [{:keys [issuer client-id client-secret scope token-endpoint]}]
  (let [endpoint (or token-endpoint
                     (str issuer "/protocol/openid-connect/token"))
        cache (atom nil)]
    (fn []
      (let [now (quot (System/currentTimeMillis) 1000)
            {:keys [token exp]} @cache]
        (if (and token (< (+ now 30) exp))
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

(defn protected-resource
  "The RFC 9728 protected-resource document for the MCP door: the
  resource is the door's own URL, the authorization server is the
  issuer (the browser-facing one when the deployment splits them —
  the client that reads this lives outside), bearer tokens ride the
  header, and the scopes are whatever the deployment says a client
  should ask for (:resource-scopes; omitted when unset, the RFC's own
  allowance). nil without an external base URL: an engine that cannot
  name itself advertises nothing, and the route answers 404."
  [oidc]
  (when-some [base (external-base oidc)]
    (cond-> {:resource (str base "/api/-/mcp")
             :authorization_servers [(or (get-in oidc [:rp :frontend-issuer])
                                         (:issuer oidc))]
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
                    :display display}))))

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
