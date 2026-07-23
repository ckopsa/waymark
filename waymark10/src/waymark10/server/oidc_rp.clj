(ns waymark10.server.oidc-rp
  "The OIDC relying-party browser flow — oidc.clj's recorded punt,
  now ported: authorization-code + PKCE against the IdP, a signed
  session cookie as the browser's credential, RP-initiated logout.
  Bearer verification stays the API's path; this namespace exists so
  a browser can obtain a principal without ever handling a token.

  Engine opts — :oidc gains an :rp sub-map (validated in oidc/config):

    {:oidc {… bearer config …
            :rp {:client-id \"company-tools\"
                 :client-secret \"…\"
                 :app-url \"https://tools.example.com\" ; external base
                 :session-secret \"…\"                 ; HS256 key
                 :frontend-issuer \"…\"   ; browser-facing; default :issuer
                 :session-ttl-s 28800     ; cap; exp = min(token exp, ttl)
                 :cookie-name \"waymark_session\"
                 :login-redirect? false   ; 302 anonymous HTML ui GETs
                 ;; endpoint overrides (tests, non-Keycloak IdPs);
                 ;; defaults are the Keycloak realm shapes
                 :auth-endpoint … :token-endpoint … :logout-endpoint …}}}

  Routes (composed via engine/start!'s :wrap-handler seam — the
  router table stays untouched):

    GET /auth/login?return-to=/api/-/ui  302 → the IdP; state + PKCE
        verifier ride a short-lived signed cookie — no server state
    GET /auth/callback?code=…&state=…    code exchange, the access
        token verified through the SAME JWKS path as bearer
        (oidc/verify), session cookie minted
    GET /auth/logout                     session cleared, 302 → the
        IdP's RP-initiated logout

  The session cookie is an HS256 JWT of the principal's own claims —
  sub, roles, actor type, display — never the IdP's tokens: nothing
  stored server-side, nothing worth stealing beyond the session
  itself (HttpOnly, SameSite=Lax, Secure under https). Recorded punt
  this round: refresh tokens — session expiry re-runs /auth/login,
  which the IdP's SSO cookie answers silently."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [waymark10.server.oidc :as oidc]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

;; ── small cloth: randomness, digests, wire shapes ───────────────────

(def ^:private ^java.util.Base64$Encoder b64url
  (.withoutPadding (java.util.Base64/getUrlEncoder)))

(defn- b64 ^String [^bytes bs] (.encodeToString b64url bs))

(defn- rand-token []
  (let [bs (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (b64 bs)))

(defn- s256
  "The PKCE S256 challenge: BASE64URL(SHA-256(verifier))."
  [^String verifier]
  (b64 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                (.getBytes verifier "US-ASCII"))))

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- query-str [m]
  (str/join "&" (map (fn [[k v]] (str k "=" (url-encode v))) m)))

(defn- query-params [req]
  (into {}
        (keep #(let [[k v] (str/split % #"=" 2)]
                 (when v [k (java.net.URLDecoder/decode ^String v "UTF-8")])))
        (str/split (or (:query-string req) "") #"&")))

(defn- cookies [req]
  (into {}
        (keep #(let [[k v] (str/split (str/trim %) #"=" 2)]
                 (when v [k v])))
        (str/split (or (get-in req [:headers "cookie"]) "") #";")))

(defn- secure? [rp] (str/starts-with? (str (:app-url rp)) "https"))

(defn- set-cookie [rp cname value max-age path]
  (str cname "=" value
       "; Path=" path
       "; Max-Age=" max-age
       "; HttpOnly; SameSite=Lax"
       (when (secure? rp) "; Secure")))

(defn- clear-cookie [rp cname path] (set-cookie rp cname "" 0 path))

(defn- problem
  "These routes sit OUTSIDE wrap-problems (the :wrap-handler seam), so
  refusals are built responses, not thrown ones."
  [status title detail]
  {:status status
   :headers {"Content-Type" "application/problem+json"}
   :body (wire/write-json {:type "about:blank" :status status
                           :title title :detail detail})})

;; ── the IdP's doors (Keycloak realm shapes, overridable) ────────────

(defn- frontend-issuer [oidc]
  (or (get-in oidc [:rp :frontend-issuer]) (:issuer oidc)))

(defn- auth-endpoint [oidc]
  (or (get-in oidc [:rp :auth-endpoint])
      (str (frontend-issuer oidc) "/protocol/openid-connect/auth")))

(defn- token-endpoint
  "The back-channel exchange talks to the BACKEND issuer — inside the
  cluster the frontend URL may not even resolve."
  [oidc]
  (or (get-in oidc [:rp :token-endpoint])
      (str (:issuer oidc) "/protocol/openid-connect/token")))

(defn- logout-endpoint [oidc]
  (or (get-in oidc [:rp :logout-endpoint])
      (str (frontend-issuer oidc) "/protocol/openid-connect/logout")))

(defn- redirect-uri [rp] (str (:app-url rp) "/auth/callback"))

;; ── /auth/login ─────────────────────────────────────────────────────

(defn- sanitize-return-to
  "Same-origin relative paths only — an absolute return-to is an open
  redirect wearing a query param."
  [s]
  (if (and s (str/starts-with? s "/") (not (str/starts-with? s "//")))
    s
    "/api/-/ui"))

(defn- login [oidc req]
  (let [rp (:rp oidc)
        state (rand-token)
        verifier (rand-token)
        stash (jwt/sign {:state state :verifier verifier
                         :return-to (sanitize-return-to
                                     (get (query-params req) "return-to"))
                         :exp (+ (now-secs) 300)}
                        (:session-secret rp) {:alg :hs256})]
    {:status 302
     :headers {"Location"
               (str (auth-endpoint oidc) "?"
                    (query-str {"response_type" "code"
                                "client_id" (:client-id rp)
                                "redirect_uri" (redirect-uri rp)
                                "scope" "openid"
                                "state" state
                                "code_challenge" (s256 verifier)
                                "code_challenge_method" "S256"}))
               ;; Path=/auth: the stash rides ONLY the callback
               "Set-Cookie" (set-cookie rp "waymark_auth" stash 300 "/auth")}
     :body ""}))

;; ── /auth/callback ──────────────────────────────────────────────────

(defn- unstash
  "The login stash back off the cookie: nil on absence, expiry, or
  tamper — every failure is the same 'start again'."
  [rp req]
  (when-some [c (get (cookies req) "waymark_auth")]
    (try (jwt/unsign c (:session-secret rp) {:alg :hs256})
         (catch Exception _ nil))))

(defn- exchange-code
  "The back-channel POST: code + verifier → the IdP's token response,
  or nil when the IdP refuses."
  [oidc code verifier]
  (let [{:keys [status body error]}
        @(http/post (token-endpoint oidc)
                    {:timeout 10000
                     :form-params
                     {"grant_type" "authorization_code"
                      "code" code
                      "redirect_uri" (redirect-uri (:rp oidc))
                      "client_id" (get-in oidc [:rp :client-id])
                      "client_secret" (get-in oidc [:rp :client-secret])
                      "code_verifier" verifier}})]
    (when (and (nil? error) (= 200 status))
      (wire/read-json (if (string? body) body (slurp body))))))

(defn- mint-session
  "The session cookie's JWT: the principal's OWN claims, capped at
  min(access-token exp, now + ttl) — the session never outlives the
  credential that justified it."
  [rp principal token-exp]
  (let [now (now-secs)
        cap (+ now (:session-ttl-s rp))
        exp (if token-exp (min (long token-exp) cap) cap)]
    {:exp exp
     :token (jwt/sign {:sub (:id principal)
                       :roles (vec (:roles principal))
                       :actor_type (name (:type principal))
                       :display (:display principal)
                       :iat now :exp exp}
                      (:session-secret rp) {:alg :hs256})}))

(defn- callback [oidc req]
  (let [rp (:rp oidc)
        {:strs [code state]} (query-params req)
        stash (unstash rp req)]
    (cond
      (nil? stash)
      (problem 400 "Login expired"
               "No live login attempt rides this browser — start again at /auth/login.")

      (or (str/blank? (str code)) (not= state (:state stash)))
      (problem 400 "Login refused"
               "The callback's state does not match the login attempt.")

      :else
      (if-some [tokens (exchange-code oidc code (:verifier stash))]
        (try
          (let [{:keys [claims principal]} (oidc/verify oidc (:access_token tokens))
                {:keys [exp token]} (mint-session rp principal (:exp claims))]
            {:status 302
             :headers {"Location" (:return-to stash)
                       "Set-Cookie" [(set-cookie rp (:cookie-name rp) token
                                                 (- exp (now-secs)) "/")
                                     (clear-cookie rp "waymark_auth" "/auth")]}
             :body ""})
          (catch Exception e
            (problem 401 "Unauthenticated"
                     (str "The IdP's token did not verify: " (ex-message e)))))
        (problem 502 "Token exchange failed"
                 "The IdP's token endpoint refused the code — the login cannot complete.")))))

;; ── /auth/logout ────────────────────────────────────────────────────

(defn- logout [oidc _req]
  (let [rp (:rp oidc)]
    {:status 302
     :headers {"Location"
               (str (logout-endpoint oidc) "?"
                    (query-str {"client_id" (:client-id rp)
                                ;; land on login, not the root — the
                                ;; URI IdP clients already register
                                ;; (and a fresh login is the honest
                                ;; post-logout state anyway)
                                "post_logout_redirect_uri"
                                (str (:app-url rp) "/auth/login")}))
               "Set-Cookie" (clear-cookie rp (:cookie-name rp) "/")}
     :body ""}))

;; ── the resolver wrap-identity consults ─────────────────────────────

(defn resolve-session
  "The session resolver, slotted between bearer and dev headers: the
  signed session cookie → a Principal; nil on ANYTHING else — a stale
  or tampered cookie must fall through, never lock out a bearer
  client with a 401."
  [oidc req]
  (when-some [rp (:rp oidc)]
    (when-some [c (get (cookies req) (:cookie-name rp))]
      (try
        (let [{:keys [sub roles actor_type display]}
              (jwt/unsign c (:session-secret rp) {:alg :hs256})
              at (some-> actor_type str str/lower-case keyword)]
          (when-not (str/blank? (str sub))
            (t/principal {:id (str sub)
                          ;; system stays engine-internal, same as bearer
                          :type (if (contains? #{:human :agent} at) at :human)
                          :roles (set (map str roles))
                          :display (or display (str sub))})))
        (catch Exception _ nil)))))

;; ── the wrap engine/start! composes ─────────────────────────────────

(defn- ui-redirect?
  "An anonymous HTML GET on the ui: a browser that should be sent to
  login, not shown a 401 problem. API requests keep honest 401s —
  the Accept header is the tell. The session is RESOLVED, not merely
  present: a stale or tampered cookie must bounce to login, not
  slip past the door because it exists."
  [oidc req]
  (and (:login-redirect? (:rp oidc))
       (str/starts-with? (:uri req) "/api/-/ui")
       (some-> (get-in req [:headers "accept"]) (str/includes? "text/html"))
       (nil? (get-in req [:headers "authorization"]))
       (nil? (resolve-session oidc req))))

(defn wrap
  "The ring wrap engine/start! composes: the three /auth doors in
  front, everything else through to the engine. No :rp config = the
  identity wrap, untouched."
  [eng]
  (let [oidc (:oidc eng)
        rp (:rp oidc)]
    (fn [handler]
      (if (nil? rp)
        handler
        (fn [req]
          (if (= :get (:request-method req))
            (case (:uri req)
              "/auth/login" (login oidc req)
              "/auth/callback" (callback oidc req)
              "/auth/logout" (logout oidc req)
              (if (ui-redirect? oidc req)
                {:status 302
                 :headers {"Location" (str "/auth/login?return-to="
                                           (url-encode (:uri req)))}
                 :body ""}
                (handler req)))
            (handler req)))))))
