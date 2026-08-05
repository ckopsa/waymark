(ns calendar10.oauth
  "The outbound Google credential (waymark-6k5.1): a zero-arg access
  token source over OAuth 2.0's refresh-token grant, in the shape
  oidc/client-credentials-fn established for waymark-mvl — minted on
  first call, re-minted when the cached token nears its exp, so no
  access token ever rides config and rotation stops being a concept.

  WHAT IS STORED is the REFRESH token, and only the refresh token: a
  long-lived bearer for the family calendar, so it lives in
  secrets.local.json and a nomad var, never in source (the rule
  mealplan10.event-source recorded for the secret iCal URL, inherited
  here). scripts/gcal-refresh-token.sh mints one through the consent
  flow. Access tokens are derived, short (Google's hour), and held
  only in this atom.

  A REFUSAL THROWS, and that is the right shape: to a mirror, an
  un-mintable credential is one unreachable feed — the stored rows
  keep serving with their honest synced_at — never a silently empty
  one. Google answers 400 invalid_grant for a refresh token that was
  revoked, expired from disuse, or invalidated by a password change;
  the thrown message carries Google's own body so the operator reads
  the real reason rather than \"unreachable\".

  Two racing callers may both mint; both tokens are valid and the
  cache keeps the later — recorded, not fenced (oidc's precedent)."
  (:require [clojure.string :as str]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def token-endpoint "https://oauth2.googleapis.com/token")

;; the margin a cached token must still have to be reused. Wider than
;; oidc's 30s on purpose: a discovery pass over several calendars can
;; itself outlive a thin margin, and a token that expires mid-pass
;; reads as an unreachable feed rather than as the clock it is.
(def ^:private refresh-skew-seconds 120)

(defn form-encode ^String [m]
  (str/join "&"
            (map (fn [[k v]]
                   (str (URLEncoder/encode (str (name k)) StandardCharsets/UTF_8)
                        "="
                        (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
                 m)))

(defn http-mint
  "The real mint: POST the refresh-token grant → the parsed body.
  Separated from the caching so tests can script the seam without a
  network (the fake-adapter habit, applied to a credential)."
  [{:keys [client-id client-secret refresh-token endpoint]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create (or endpoint token-endpoint)))
                (.timeout (Duration/ofSeconds 10))
                (.header "content-type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (form-encode {"grant_type" "refresh_token"
                                      "client_id" client-id
                                      "client_secret" client-secret
                                      "refresh_token" refresh-token})
                        StandardCharsets/UTF_8))
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "google refused the refresh-token mint (" status "): "
                           (.body resp))
                      {:status status})))
    (wire/read-json (.body resp))))

(defn access-token-fn
  "A zero-arg token source. config: :client-id, :client-secret,
  :refresh-token, optional :refresh-token-fn (a zero-arg fn returning
  the CURRENT refresh token at mint time — workqueue10's reconsent
  door stores a fresh one on the connection row, and a static string
  could never see it; given, it wins and :refresh-token is its
  fallback), optional :endpoint (the token URL — tests point it at a
  local server), optional :mint-fn (the whole HTTP step, for tests
  that want no server at all).

  Returns nil when the credential is not configured — the client pair
  blank, or NO refresh token source at all (neither the static string
  nor the fn) — so an app may wire it unconditionally and offline dev
  simply has no calendar — the same nil-means-absent contract
  oidc/outbound-token-fn uses. A :refresh-token-fn that answers blank
  at mint time throws instead: a configured-but-empty credential
  should look broken, not absent."
  [{:keys [client-id client-secret refresh-token refresh-token-fn
           mint-fn]
    :as config}]
  (when (and (not (some str/blank? [(str client-id) (str client-secret)]))
             (or refresh-token-fn
                 (not (str/blank? (str refresh-token)))))
    (let [mint (or mint-fn http-mint)
          current (if refresh-token-fn
                    #(or (refresh-token-fn) refresh-token)
                    (constantly refresh-token))
          cache (atom nil)]
      (fn []
        (let [now (quot (System/currentTimeMillis) 1000)
              {:keys [token exp]} @cache]
          (if (and token (< (+ now refresh-skew-seconds) exp))
            token
            (let [rt (current)
                  _ (when (str/blank? (str rt))
                      (throw (ex-info (str "no refresh token to mint with — "
                                           "nothing stored on a connection row "
                                           "and none configured in env")
                                      {})))
                  {:keys [access_token expires_in]}
                  (mint (assoc config :refresh-token rt))]
              (when (str/blank? (str access_token))
                (throw (ex-info "google's token response carried no access_token"
                                {})))
              (reset! cache {:token access_token
                             :exp (+ now (or expires_in 3600))})
              access_token)))))))

(defn from-env
  "The deployed credential off CALENDAR10_GOOGLE_CLIENT_ID /
  _CLIENT_SECRET / _REFRESH_TOKEN. nil when unconfigured — offline
  dev and the declaration gate run over the fake calendar instead.
  opts may carry :refresh-token-fn (the row-first read the reconsent
  door feeds); with it, the client pair alone configures — the token
  arrives by row, or the env fallback, at mint time."
  ([] (from-env #(System/getenv ^String %)))
  ([env] (from-env env nil))
  ([env {:keys [refresh-token-fn]}]
   (access-token-fn {:client-id (env "CALENDAR10_GOOGLE_CLIENT_ID")
                     :client-secret (env "CALENDAR10_GOOGLE_CLIENT_SECRET")
                     :refresh-token (env "CALENDAR10_GOOGLE_REFRESH_TOKEN")
                     :refresh-token-fn refresh-token-fn})))
