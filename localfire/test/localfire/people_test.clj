(ns localfire.people-test
  "Sign-in and the person's own Claude token (spec-local-fire.md §12),
  from the outside, over the loopback.

  The realm is the seam: the exchange of the code is a fake that
  answers the claims Keycloak would, so no network beyond the loopback.
  What this suite proves is R-12.6: a signed-in person stores and
  revokes a token, the stored bytes are not the token, no page and no
  log carries it, and without `:oidc` the routes are not there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [localfire.config :as config]
            [localfire.page :as page]
            [localfire.people :as people]
            [localfire.server :as server]
            [localfire.server-test :as w])
  (:import [java.io File StringWriter]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.time Duration]
           [java.util Base64]))

;; ── the world ───────────────────────────────────────────────────────

(def the-key (byte-array (range 32)))
(def subject "sam-subject-1")
(def claude-token "sk-ant-oat01-THE-PERSONS-OWN-TOKEN-0123456789")

(defn- base-config [port runs]
  {:port       port
   :public-url (str "http://127.0.0.1:" port)
   :place      (str (w/make-place!))
   :runs-dir   (str runs)
   :mcp        {:url "http://127.0.0.1:9/mcp"}
   :routines   {"sonnet" {:model "claude-sonnet-4-5"}}})

(defn- world
  "A server with a fake spawner and a fake realm, which answers the
  claims for `good-code` and nothing for any other code."
  [{:keys [oidc?]}]
  (let [port   (w/free-port)
        runs   (w/tmpdir "lf-runs")
        tokens (w/tmpdir "lf-tokens")
        codes  (atom [])
        cfg    (config/normalize
                (cond-> (base-config port runs)
                  oidc? (assoc :oidc {:issuer     "http://127.0.0.1:9/realms/domestic"
                                      :tokens-dir (str tokens)})))
        st     (server/start!
                {:config  cfg
                 :token   "the-token"
                 :spawner (w/fake-spawner {:calls (atom [])})
                 :closer  (w/fake-closer (atom []))
                 :sign-in {:key      the-key
                           :exchange (fn [code verifier]
                                       (swap! codes conj [code verifier])
                                       (when (= code "good-code") {"sub" subject}))}})]
    (assoc st :base (str "http://127.0.0.1:" port) :tokens tokens :codes codes)))

;; ── a browser that follows nothing ───────────────────────────────────

(def ^:private http (HttpClient/newHttpClient))

(defn- req [method url {:keys [cookie form]}]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofSeconds 10)))]
    (when cookie (.header b "cookie" cookie))
    (if (= :post method)
      (-> b
          (.header "content-type" "application/x-www-form-urlencoded")
          (.POST (HttpRequest$BodyPublishers/ofString (people/form-encode (or form {})))))
      (.GET b))
    (let [r (.send http (.build b) (HttpResponse$BodyHandlers/ofString))
          h (.headers r)]
      {:status     (.statusCode r)
       :body       (.body r)
       :location   (-> h (.firstValue "location") (.orElse nil))
       :set-cookie (-> h (.firstValue "set-cookie") (.orElse nil))})))

(defn- query-of [location]
  (people/parse-form (.getRawQuery (URI/create location))))

(defn- sign-in! [w]
  (let [a  (req :get (str (:base w) "/signin") {})
        q  (query-of (:location a))
        cb (req :get (str (:base w) "/signin/callback?"
                          (people/form-encode {:code "good-code" :state (get q "state")}))
                {})]
    {:authorize a :query q :callback cb
     :cookie (some-> (:set-cookie cb) (str/split #";") first)}))

(defn- csrf-of [body]
  (second (re-find #"name=\"csrf\" value=\"([^\"]+)\"" (str body))))

(defn- token-files [w]
  (vec (or (.listFiles ^File (:tokens w)) [])))

(defn- capture
  "Run `f` with every log line of every thread caught. → [what f
  answered, the lines]. The asserts go after, so a failure's report is
  not caught with them."
  [f]
  (let [sw  (StringWriter.)
        out (.getRawRoot #'*out*)
        err (.getRawRoot #'*err*)]
    (alter-var-root #'*out* (constantly sw))
    (alter-var-root #'*err* (constantly sw))
    (try
      [(binding [*out* sw *err* sw] (f)) (str sw)]
      (finally
        (alter-var-root #'*out* (constantly out))
        (alter-var-root #'*err* (constantly err))))))

;; ── R-12.1 to R-12.4 ───────────────────────────────────────────────

(defn- flow
  "Sign in, look, store without and with the CSRF value, look again,
  revoke, look again. → what each step answered and what the disk held."
  [w]
  (let [base   (:base w)
        s      (sign-in! w)
        ck     (:cookie s)
        me1    (req :get (str base "/me") {:cookie ck})
        csrf   (csrf-of (:body me1))
        bare   (req :post (str base "/me/token") {:cookie ck :form {:token claude-token}})
        n-bare (count (token-files w))
        store  (req :post (str base "/me/token") {:cookie ck :form {:csrf csrf :token claude-token}})
        files  (token-files w)
        sealed (some-> ^File (first files) .toPath Files/readAllBytes)
        back   (people/read-token (:people w) subject)
        me2    (req :get (str base "/me") {:cookie ck})
        revoke (req :post (str base "/me/revoke") {:cookie ck :form {:csrf csrf}})
        n-rev  (count (token-files w))
        me3    (req :get (str base "/me") {:cookie ck})]
    {:signin s :me1 me1 :bare bare :n-bare n-bare :store store :files files
     :sealed sealed :back back :me2 me2 :revoke revoke :n-rev n-rev :me3 me3}))

(deftest a-person-signs-in-stores-and-revokes-a-token
  (let [w (world {:oidc? true})]
    (try
      (let [[r logs] (capture #(flow w))
            {:keys [signin me1 bare n-bare store files sealed back me2 revoke n-rev me3]} r
            q (:query signin)]
        (testing "the sign-in goes to the realm with the code flow and PKCE (R-12.1)"
          (is (= 302 (get-in signin [:authorize :status])))
          (is (str/starts-with? (str (get-in signin [:authorize :location]))
                                "http://127.0.0.1:9/realms/domestic/protocol/openid-connect/auth?"))
          (is (= "code" (get q "response_type")))
          (is (= "localfire-claude" (get q "client_id")))
          (is (= (str (:base w) "/signin/callback") (get q "redirect_uri")))
          (is (= "S256" (get q "code_challenge_method")))
          (is (= 303 (get-in signin [:callback :status])))
          (is (= "/me" (get-in signin [:callback :location])))
          (is (str/includes? (str (get-in signin [:callback :set-cookie])) "HttpOnly"))
          (is (= ["good-code"] (map first @(:codes w)))))
        (testing "the page names the person and says who may read the tokens before it asks for one (R-12.4)"
          (let [b (str (:body me1))]
            (is (str/includes? b subject))
            (is (< -1 (or (str/index-of b page/admin-disclosure) -1)
                   (or (str/index-of b "name=\"token\"") -1)))))
        (testing "a POST without the session's CSRF value stores nothing (R-12.3)"
          (is (= 403 (:status bare)))
          (is (zero? n-bare)))
        (testing "the token is stored sealed: the bytes are not the token, and the key opens them (R-12.2)"
          (is (= 303 (:status store)))
          (is (= 1 (count files)))
          (is (some? sealed))
          (is (not (str/includes? (String. ^bytes (or sealed (byte-array 0)) StandardCharsets/ISO_8859_1)
                                  claude-token)))
          (is (not (str/includes? (str (first files)) subject)) "the file name names nobody")
          (is (= claude-token back)))
        (testing "no page and no log carries the token (R-12.3)"
          (doseq [x [me1 bare store me2 revoke me3]]
            (is (not (str/includes? (str (:body x)) claude-token))))
          (is (str/includes? (str (:body me2)) "/me/revoke"))
          (is (not (str/includes? (str (:body me2)) "name=\"token\"")))
          (is (not (str/includes? logs claude-token))))
        (testing "revoke deletes it"
          (is (= 303 (:status revoke)))
          (is (zero? n-rev))
          (is (nil? (people/read-token (:people w) subject)))
          (is (str/includes? (str (:body me3)) "name=\"token\""))))
      (finally (server/stop! w)))))

(deftest a-callback-this-server-did-not-begin-is-refused
  (let [w (world {:oidc? true})]
    (try
      (let [base   (:base w)
            cb     (fn [code state]
                     (req :get (str base "/signin/callback?"
                                    (people/form-encode {:code code :state state}))
                          {}))
            forged (cb "good-code" "not-one-of-ours")
            s      (sign-in! w)
            replay (cb "good-code" (get (:query s) "state"))
            bad    (cb "bad-code" (get (query-of (:location (req :get (str base "/signin") {})))
                                       "state"))]
        (is (= 401 (:status forged)))
        (is (nil? (:set-cookie forged)))
        (is (= 303 (get-in s [:callback :status])))
        (is (= 401 (:status replay)) "a state is single-use")
        (is (= 401 (:status bad)) "no claims from the realm is no session")
        (is (= ["good-code" "bad-code"] (map first @(:codes w)))
            "a state this server did not begin never reaches the realm"))
      (finally (server/stop! w)))))

(deftest without-oidc-localfire-runs-as-today
  (let [w (world {:oidc? false})]
    (try
      (is (nil? (:people w)))
      (is (nil? (get-in w [:config :oidc])))
      (doseq [[m p] [[:get "/signin"] [:get "/signin/callback?code=x&state=y"] [:get "/me"]
                     [:post "/me/token"] [:post "/me/revoke"] [:post "/signout"]]]
        (is (= 404 (:status (req m (str (:base w) p) {}))) p))
      (is (= 200 (:status (req :get (str (:base w) "/runs") {}))))
      (finally (server/stop! w)))))

(deftest the-oidc-config-and-the-key
  (let [base (base-config 0 (w/tmpdir "lf-runs"))]
    (is (= {:issuer        "https://kc/realms/domestic"
            :client-id     "localfire-claude"
            :authorize-url "https://kc/realms/domestic/protocol/openid-connect/auth"
            :token-url     "https://kc/realms/domestic/protocol/openid-connect/token"
            :tokens-dir    "/t"}
           (:oidc (config/normalize (assoc base :oidc {:issuer "https://kc/realms/domestic/"
                                                       :tokens-dir "/t"})))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":tokens-dir"
                          (config/normalize (assoc base :oidc {:issuer "https://kc/realms/domestic"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"LOCALFIRE_TOKEN_KEY"
                          (server/start! {:config (config/normalize
                                                   (assoc base :oidc {:issuer "https://kc" :tokens-dir "/t"}))
                                          :token  "t"})))
    (is (nil? (people/parse-key "too-short")))
    (is (= 32 (alength ^bytes (people/parse-key (.encodeToString (Base64/getEncoder) ^bytes the-key)))))))

(deftest a-sealed-token-opens-only-for-its-own-subject
  (let [sealed (people/seal the-key "sam" claude-token)]
    (is (= claude-token (people/unseal the-key "sam" sealed)))
    (is (thrown? javax.crypto.AEADBadTagException (people/unseal the-key "alex" sealed)))))
