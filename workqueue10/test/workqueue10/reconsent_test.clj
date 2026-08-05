(ns workqueue10.reconsent-test
  "The reconsent door (waymark-kyg.2) without a browser or a Google:
  a DB-backed engine hosting the connection kind, a session cookie
  minted with the rp session secret, and a local http-kit stub as
  Google's token endpoint capturing the exact form the exchange
  sends. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as server]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.connections :as connections :refer [connection]]
            [workqueue10.reconsent :as reconsent])
  (:import (java.security KeyPairGenerator)))

;; ── the engine: the connection kind over the test database ──────────

(def ^:private session-secret "a-32-byte-session-secret-for-test!!")

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "test-key" :alg "RS256" :use "sig")]})

(def ^:private oidc-opts
  {:issuer "https://idp.test/realms/home"
   :audience "workqueue10"
   :jwks jwks
   :rp {:client-id "workqueue10" :client-secret "shh"
        :app-url "http://app.test"
        :session-secret session-secret}})

(def ^:private tables
  ["connections" "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)

;; ── the stub Google: one token door, scripted per case ──────────────

(def ^:private google-response (atom nil))
(def ^:private google-saw (atom nil))

(def ^:dynamic *token-endpoint* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          srv (server/run-server
               (fn [req]
                 (reset! google-saw (slurp (:body req)))
                 (or @google-response
                     {:status 400 :body "{\"error\":\"unscripted\"}"}))
               {:port 0 :legacy-return-value? false})]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st
                                        :resources [connection]
                                        :oidc oidc-opts})
                  *token-endpoint*
                  (str "http://localhost:" (server/server-port srv)
                       "/token")]
          (f))
        (finally
          (server/server-stop! srv)
          (pg/close! st))))))

;; ── small cloth ─────────────────────────────────────────────────────

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- app
  "The wrap over a marker handler. No config argument = the test
  client pair against the stub endpoint; pass {} for the
  unconfigured door."
  [& [config]]
  ((reconsent/wrap *eng* (or config
                             {:client-id "web-client"
                              :client-secret "web-shh"
                              :token-endpoint *token-endpoint*}))
   (fn [_req] {:status 200 :headers {} :body "through"})))

(defn- get-req [uri & [{:keys [query headers]}]]
  {:request-method :get :uri uri :query-string query
   :headers (or headers {})})

(defn- session-jwt
  "A session JWT under the rp session secret. Recovery-admin by
  default — the only role the door admits — so the happy paths carry
  it; the refusal tests pass {:roles [...]} / {:actor-type \"agent\"}."
  [sub & [{:keys [roles actor-type grant]
           :or {roles ["recovery-admin"] actor-type "human"}}]]
  (jwt/sign (cond-> {:sub sub :roles roles :actor_type actor-type
                     :display sub :exp (+ (now-secs) 600)}
              grant (assoc :grant grant))
            session-secret {:alg :hs256}))

(defn- session-cookie [sub & [opts]]
  (str "waymark_session=" (session-jwt sub opts)))

(defn- location [resp] (get-in resp [:headers "Location"]))

(defn- loc-params [resp]
  (into {}
        (map #(let [[k v] (str/split % #"=" 2)]
                [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))
        (str/split (second (str/split (location resp) #"\?" 2)) #"&")))

(defn- set-cookie-headers [resp]
  (let [sc (get-in resp [:headers "Set-Cookie"])]
    (if (string? sc) [sc] (vec sc))))

(defn- cookie-of [resp]
  (into {}
        (map #(let [[kv] (str/split % #";" 2)
                    [k v] (str/split kv #"=" 2)]
                [k (or v "")]))
        (set-cookie-headers resp)))

(defn- row [tag]
  (let [st (:storage *eng*)
        raw (store/with-tx st
              (fn [tx]
                (first (store/query-rows st tx :connection
                                         {:tag tag} {:limit 1}))))]
    (some->> raw (inv/decode-row (get (inv/resources *eng*) :connection)))))

(defn- seed! [tag provider]
  (connections/ensure-connections!
   *eng* {tag (cond-> {:mode "real"}
                provider (assoc :provider provider))})
  (row tag))

(defn- start-reconsent
  "Drive GET /auth/google/reconsent for the row — the 302 response.
  opts beyond :sub (:roles, :actor-type, :grant) shape the session."
  [id & [{:keys [sub] :as opts :or {sub "ana"}}]]
  ((app) (get-req "/auth/google/reconsent"
                  {:query (str "connection=" id)
                   :headers {"cookie" (session-cookie sub (dissoc opts :sub))}})))

(defn- callback
  "Drive GET /auth/google/callback with the started dance's state and
  stash cookie AND a live session cookie (the callback re-resolves the
  session now — the stash alone is no authority). :sub picks the
  session principal (default \"ana\", the starter); :cookie overrides
  the whole Cookie header (the stolen-stash / no-session cases)."
  [start-resp & [{:keys [code state cookie sub]}]]
  ((app) (get-req "/auth/google/callback"
                  {:query (str "code=" (or code "c123")
                               "&state=" (or state
                                             (get (loc-params start-resp)
                                                  "state")))
                   :headers {"cookie"
                             (or cookie
                                 (str "waymark_reconsent="
                                      (get (cookie-of start-resp)
                                           "waymark_reconsent")
                                      "; " (session-cookie (or sub "ana"))))}})))

;; ── the consent redirect ────────────────────────────────────────────

(deftest the-door-sends-the-browser-to-google-fully-armed
  (let [{:keys [id]} (seed! "gtasks" "google")
        resp (start-reconsent id)
        q (loc-params resp)]
    (is (= 302 (:status resp)))
    (is (str/starts-with? (location resp)
                          "https://accounts.google.com/o/oauth2/v2/auth?"))
    (is (= "code" (q "response_type")))
    (is (= "web-client" (q "client_id")))
    (is (= "http://app.test/auth/google/callback" (q "redirect_uri")))
    (testing "the two params whose absence is the classic silent failure"
      (is (= "offline" (q "access_type")))
      (is (= "consent" (q "prompt"))))
    (testing "both scopes ride the default consent"
      (is (str/includes? (q "scope") "auth/calendar"))
      (is (str/includes? (q "scope") "auth/tasks")))
    (is (= "S256" (q "code_challenge_method")))
    (is (seq (q "state")))
    (testing "the stash: its own cookie, /auth-scoped, 900s — and the
              challenge is the stashed verifier's S256"
      (let [header (first (filter #(str/starts-with? % "waymark_reconsent=")
                                  (set-cookie-headers resp)))
            stash (jwt/unsign (get (cookie-of resp) "waymark_reconsent")
                              session-secret {:alg :hs256})]
        (is (str/includes? header "Path=/auth"))
        (is (str/includes? header (str "Max-Age=" reconsent/stash-ttl-s)))
        (is (= 900 reconsent/stash-ttl-s))
        (is (= (q "state") (:state stash)))
        (is (= id (:connection stash)))
        (is (= "ana" (:sub stash)))
        (is (= (q "code_challenge") (rp/s256 (:verifier stash))))))))

(deftest the-door-refuses-the-anonymous-and-the-wrong-target
  (let [{:keys [id]} (seed! "gtasks" "google")]
    (testing "no session = 401, not a silent bounce"
      (is (= 401 (:status ((app) (get-req "/auth/google/reconsent"
                                          {:query (str "connection=" id)}))))))
    (testing "no connection named"
      (is (= 400 (:status (start-reconsent "")))))
    (testing "a connection that does not exist"
      (is (= 404 (:status (start-reconsent "conn-nope")))))
    (testing "a row whose provider is not google"
      (let [{:keys [id]} (seed! "todo" nil)]
        (is (= 400 (:status (start-reconsent id))))))))

(deftest an-unconfigured-door-says-why
  (let [a (app {})]
    (doseq [uri ["/auth/google/reconsent" "/auth/google/callback"]]
      (let [resp (a (get-req uri))]
        (is (= 404 (:status resp)))
        (is (str/includes? (str (:body resp))
                           "WORKQUEUE10_RECONSENT_CLIENT_ID")
            "the refusal names the missing configuration")))
    (testing "everything else passes through untouched"
      (is (= 200 (:status (a (get-req "/api/connections"))))))))

;; ── the callback ────────────────────────────────────────────────────

(deftest the-callback-lands-the-token-on-the-row-audited
  (let [{:keys [id]} (seed! "gtasks" "google")
        start-resp (start-reconsent id)
        stash (jwt/unsign (get (cookie-of start-resp) "waymark_reconsent")
                          session-secret {:alg :hs256})]
    (reset! google-response
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (wire/write-json {:access_token "ya29.at"
                                     :refresh_token "1//fresh"
                                     :expires_in 3599})})
    (let [resp (callback start-resp)]
      (is (= 302 (:status resp)))
      (is (= (str "/#/api/connections/" id) (location resp)))
      (testing "the exchange carried the whole form — PKCE included"
        (is (str/includes? @google-saw "grant_type=authorization_code"))
        (is (str/includes? @google-saw "code=c123"))
        (is (str/includes? @google-saw "client_id=web-client"))
        (is (str/includes? @google-saw "client_secret=web-shh"))
        (is (str/includes? @google-saw
                           (str "code_verifier="
                                (java.net.URLEncoder/encode
                                 ^String (:verifier stash) "UTF-8")))))
      (testing "the stash cookie is cleared"
        (is (= "" (get (cookie-of resp) "waymark_reconsent"))))
      (testing "the row carries the token, the witness, and its state"
        (let [r (row "gtasks")]
          (is (= :live (:state r)))
          (is (= "1//fresh" (get-in r [:data :refresh_token])))
          (is (= "ana" (get-in r [:data :reconsented_by])))
          (is (some? (get-in r [:data :reconsented_at])))))
      (testing "the landing is an audited receive_token transition"
        (let [ts (store/with-tx (:storage *eng*)
                   (fn [tx] (store/transitions (:storage *eng*) tx
                                               {:kind :connection
                                                :resource-id id} {})))]
          (is (some #(str/starts-with? (name (:action %)) "receive_token")
                    ts)))))))

(deftest a-dark-row-stays-dark-until-a-pass-answers
  (let [{:keys [id]} (seed! "calendar" "google")]
    (connections/report! *eng* "calendar" false "invalid_grant")
    (connections/report! *eng* "calendar" false "invalid_grant")
    (is (= :dark (:state (row "calendar"))) "the breaker flipped")
    (reset! google-response
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (wire/write-json {:access_token "ya29.at"
                                     :refresh_token "1//dark-fix"})})
    (let [resp (callback (start-reconsent id))]
      (is (= 302 (:status resp)))
      (let [r (row "calendar")]
        (is (= :dark (:state r))
            "the door never force-flips — the next pass's answer does")
        (is (= "1//dark-fix" (get-in r [:data :refresh_token])))))
    (testing "and the dark spelling of the transition was the one taken"
      (let [ts (store/with-tx (:storage *eng*)
                 (fn [tx] (store/transitions (:storage *eng*) tx
                                             {:kind :connection
                                              :resource-id id} {})))]
        (is (some #(= "receive_token_in_dark" (name (:action %))) ts))))))

(deftest the-callback-refuses-a-broken-dance
  (let [{:keys [id]} (seed! "gtasks" "google")
        start-resp (start-reconsent id)]
    (reset! google-response nil)
    (testing "a state that is not the stash's"
      (is (= 400 (:status (callback start-resp
                                    {:state "not-the-state"})))))
    (testing "no stash cookie at all"
      (is (= 400 (:status (callback start-resp {:cookie "nope=1"})))))
    (testing "a refused exchange"
      (is (= 502 (:status (callback start-resp)))))))

(deftest an-answer-without-a-refresh-token-names-the-trap
  (let [{:keys [id]} (seed! "gtasks" "google")
        before (get-in (row "gtasks") [:data :refresh_token])
        start-resp (start-reconsent id)]
    (reset! google-response
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (wire/write-json {:access_token "ya29.only"})})
    (let [resp (callback start-resp)]
      (is (= 502 (:status resp)))
      (is (str/includes? (str (:body resp)) "prompt=consent")
          "the refusal teaches the trap, not just the failure")
      (is (= before (get-in (row "gtasks") [:data :refresh_token]))
          "nothing landed"))))

;; ── the authorization: only recovery-admin (findings #1/#15) ────────

(deftest the-door-admits-only-recovery-admin
  (let [{:keys [id]} (seed! "gtasks" "google")]
    (testing "a recovery-admin session starts the dance"
      (is (= 302 (:status (start-reconsent id {:sub "ra-real"})))))
    (testing "an ordinary member (no recovery-admin) is refused 403"
      (is (= 403 (:status (start-reconsent id {:sub "pat" :roles ["ops"]})))))
    (testing "an agent is refused 403"
      (is (= 403 (:status (start-reconsent id {:sub "gary" :roles []
                                               :actor-type "agent"})))))
    (testing "a guest (agent bearing a session grant) is refused 403"
      (is (= 403 (:status (start-reconsent id {:sub "glen" :roles []
                                               :actor-type "agent"
                                               :grant "g-1"})))))
    (testing "the 403 names the required role"
      (is (str/includes? (str (:body (start-reconsent id {:sub "pat"
                                                          :roles ["ops"]})))
                         "recovery-admin")))
    (testing "a SUSPENDED recovery-admin is refused 403 (the router's gate)"
      (members/gate! *eng* (t/principal {:id "suzy"}))
      (inv/invoke! *eng* :member "suzy" :suspend {}
                   {:principal members/registrar})
      (is (= 403 (:status (start-reconsent id {:sub "suzy"})))))))

(deftest the-unauthenticated-row-is-never-quoted
  (testing "an anonymous start refuses BEFORE any row is read (finding #15)"
    (let [{:keys [id]} (seed! "todo" nil)          ; a non-google row
          resp ((app) (get-req "/auth/google/reconsent"
                               {:query (str "connection=" id)}))]
      (is (= 401 (:status resp)))
      (is (not (str/includes? (str (:body resp)) "provider"))
          "the 401 leaks nothing of the row's tag or provider"))))

;; ── the callback re-resolves the live session (findings #2/#3) ──────

(deftest the-callback-demands-the-live-session-match-the-stash
  (let [{:keys [id]} (seed! "gtasks" "google")
        start-resp (start-reconsent id {:sub "ana"})]
    (reset! google-response
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (wire/write-json {:access_token "ya29" :refresh_token "1//x"})})
    (testing "a stolen stash with NO live session is refused 401"
      (is (= 401 (:status (callback start-resp
                                    {:cookie (str "waymark_reconsent="
                                                  (get (cookie-of start-resp)
                                                       "waymark_reconsent"))})))))
    (testing "a DIFFERENT recovery-admin's session cannot spend ana's stash"
      (is (= 403 (:status (callback start-resp {:sub "mallory"})))))))

;; ── the stash is purpose-bound and fully-formed (findings #3/#4) ─────

(deftest a-stash-of-the-wrong-purpose-is-refused
  (let [{:keys [id]} (seed! "gtasks" "google")
        no-typ (jwt/sign {:state "s" :verifier "v" :connection id :sub "ana"
                          :exp (+ (now-secs) 300)}
                         session-secret {:alg :hs256})]
    (testing "a JWT under the session secret but lacking :typ reconsent"
      (is (= 400 (:status (callback nil {:state "s"
                                         :cookie (str "waymark_reconsent="
                                                      no-typ)})))))
    (testing "the session cookie itself cannot cross into the stash slot"
      (is (= 400 (:status (callback nil {:state "s"
                                         :cookie (str "waymark_reconsent="
                                                      (session-jwt "ana"))})))))))

(deftest a-nil-state-stash-cannot-slip-the-equality
  (let [{:keys [id]} (seed! "gtasks" "google")
        ;; typ-valid, but state nil — the finding #5 shape
        nil-state (jwt/sign {:typ "reconsent" :state nil :verifier "v"
                             :connection id :sub "ana"
                             :exp (+ (now-secs) 300)}
                            session-secret {:alg :hs256})
        ;; a callback with NO state param at all: query state resolves
        ;; to nil, and nil = nil would slip the old equality guard
        resp ((app) (get-req "/auth/google/callback"
                             {:query "code=c123"
                              :headers {"cookie" (str "waymark_reconsent="
                                                      nil-state)}}))]
    (is (= 400 (:status resp)))))

;; ── a dropped scope is surfaced, not swallowed (finding #4 review) ───

(deftest a-dropped-scope-is-surfaced-not-swallowed
  (let [{:keys [id]} (seed! "gtasks" "google")
        start-resp (start-reconsent id)]
    (reset! google-response
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (wire/write-json
                    {:access_token "ya29.at"
                     :refresh_token "1//scoped"
                     ;; tasks was unchecked at the consent screen
                     :scope "https://www.googleapis.com/auth/calendar"})})
    (let [resp (callback start-resp)]
      (is (= 409 (:status resp)))
      (is (str/includes? (str (:body resp)) "tasks")
          "the refusal names the dropped scope")
      (testing "the token DID land — the operator is told, not lied to"
        (is (= "1//scoped" (get-in (row "gtasks") [:data :refresh_token])))))))

;; ── the revoke lever (finding #7 review) ────────────────────────────

(deftest the-revoke-action-clears-a-stored-token
  (let [{:keys [id]} (seed! "gtasks" "google")]
    (connections/receive-token! *eng* id {:refresh-token "1//to-clear"
                                          :reconsented-by "ana"})
    (is (= "1//to-clear" (get-in (row "gtasks") [:data :refresh_token])))
    (testing "recovery-admin clears the credential from the app"
      (let [state (:state (row "gtasks"))
            action (if (= :dark state)
                     :revoke_token_in_dark :revoke_token_in_live)]
        (inv/invoke! *eng* :connection id action {}
                     {:principal (t/principal {:id "ra"
                                               :roles #{"recovery-admin"}})}))
      (let [r (row "gtasks")]
        (is (nil? (get-in r [:data :refresh_token])))
        (is (nil? (get-in r [:data :reconsented_by])))
        (is (nil? (get-in r [:data :reconsented_at])))))
    (testing "an ordinary principal is denied the lever"
      (connections/receive-token! *eng* id {:refresh-token "1//again"
                                            :reconsented-by "ana"})
      (let [state (:state (row "gtasks"))
            action (if (= :dark state)
                     :revoke_token_in_dark :revoke_token_in_live)]
        (is (thrown? Exception
                     (inv/invoke! *eng* :connection id action {}
                                  {:principal (t/principal
                                               {:id "pat" :roles #{"ops"}})}))))
      (is (= "1//again" (get-in (row "gtasks") [:data :refresh_token]))
          "the token survived the denied revoke"))))

;; ── the row-first token read (the mint-time seam) ───────────────────

(deftest the-token-fn-reads-the-row-first-and-env-as-fallback
  (testing "no engine yet (boot order) = the env value"
    (let [f (connections/google-refresh-token-fn (atom nil) "1//env")]
      (is (= "1//env" (f)))))
  (testing "an engine with no reconsented row = the env value still"
    (seed! "todo" nil)
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx] (jdbc/execute! tx ["DELETE FROM connections WHERE (data->>'provider') = 'google'"])))
        (finally (pg/close! st))))
    (let [f (connections/google-refresh-token-fn (atom *eng*) "1//env")]
      (is (= "1//env" (f)))))
  (testing "the freshest reconsented google row wins over env"
    (let [{gid :id} (seed! "gtasks" "google")
          {cid :id} (seed! "calendar" "google")]
      (connections/receive-token! *eng* gid {:refresh-token "1//older"
                                             :reconsented-by "ana"})
      (Thread/sleep 20)
      (connections/receive-token! *eng* cid {:refresh-token "1//newer"
                                             :reconsented-by "ana"})
      (let [f (connections/google-refresh-token-fn (atom *eng*) "1//env")]
        (is (= "1//newer" (f))))
      (is (= "1//newer" (connections/stored-refresh-token *eng*))))))
