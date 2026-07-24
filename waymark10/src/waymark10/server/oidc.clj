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
            :type-claim :actor_type}}            ; default :human

  A claim option given as a vector walks nested claims — Keycloak's
  realm roles live at [:realm_access :roles].

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
  [{:keys [issuer audience jwks-uri jwks rp] :as opts}]
  (doseq [[k v] {:issuer issuer :audience audience}]
    (when (or (nil? v) (str/blank? (str v)))
      (throw (t/definition-error (str ":oidc config declares no " k)))))
  (when (and (nil? jwks-uri) (nil? jwks))
    (throw (t/definition-error ":oidc config needs :jwks-uri (fetched) or :jwks (static)")))
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

  Reading stops here; validation stays config's — a missing
  client-secret is the same boot-time definition error either way.
  The one-arg form takes any (fn [name] value) — tests pass a map."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (when-some [issuer (env "WAYMARK10_OIDC_ISSUER")]
     (let [client-id (env "WAYMARK10_OIDC_CLIENT_ID")]
       (cond-> {:issuer issuer
                :audience (or (env "WAYMARK10_OIDC_AUDIENCE") client-id)
                :jwks-uri (or (env "WAYMARK10_OIDC_JWKS_URI")
                              (str issuer "/protocol/openid-connect/certs"))
                :roles-claim [:realm_access :roles]}
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

(defn- refuse
  "One 401 problem, WWW-Authenticate riding the response headers."
  [detail]
  (p/problem :unauthenticated 401 "Unauthenticated"
             {:detail detail
              :waymark10/headers
              {"WWW-Authenticate"
               "Bearer realm=\"waymark\", error=\"invalid_token\""}}))

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
      (throw (refuse (str "The issuer's JWKS is unreachable ("
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
                   (throw (refuse "The bearer token is not a JWT."))))
        pkey (or (key-for oidc kid)
                 (throw (refuse (str "No JWKS key matches kid "
                                     (pr-str kid) "."))))]
    (try
      (jwt/unsign token pkey {:alg :rs256
                              :iss (:issuer oidc)
                              :aud (:audience oidc)})
      (catch Exception e
        (let [{:keys [type cause]} (ex-data e)]
          (throw (refuse (if (= :validation type)
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
            (throw (refuse "The token carries no sub claim.")))
        roles (claim-at claims (:roles-claim oidc))
        at (some-> (claim-at claims (:type-claim oidc)) str str/lower-case keyword)]
    (t/principal {:id (str sub)
                  ;; system stays engine-internal: an IdP cannot mint it
                  :type (if (contains? #{:human :agent} at) at :human)
                  :roles (set (when (sequential? roles) (map str roles)))
                  :display (or (:name claims) (:email claims) (str sub))})))

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
