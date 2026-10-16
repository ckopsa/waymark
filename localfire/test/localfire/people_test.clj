(ns localfire.people-test
  "Sign-in and the person's own Claude token (spec-local-fire.md §12),
  from the outside, over the loopback.

  The realm is the seam: the exchange of the code is a fake that
  answers the claims Keycloak would, so no network beyond the loopback.
  What this suite proves is R-12.6: a signed-in person stores and
  revokes a token, the stored bytes are not the token, no page and no
  log carries it, and without `:oidc` the routes are not there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [localfire.config :as config]
            [localfire.page :as page]
            [localfire.people :as people]
            [localfire.pull :as pull]
            [localfire.runs :as runs]
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
  [{:keys [oidc? stdout exit pull-url]}]
  (let [calls  (atom [])
        port   (w/free-port)
        runs   (w/tmpdir "lf-runs")
        tokens (w/tmpdir "lf-tokens")
        codes  (atom [])
        cfg    (config/normalize
                (cond-> (base-config port runs)
                  oidc? (assoc :oidc {:issuer     "http://127.0.0.1:9/realms/domestic"
                                      :tokens-dir (str tokens)})
                  pull-url (assoc :person-pull-url pull-url)))
        st     (server/start!
                {:config  cfg
                 :token   "the-token"
                 :spawner (w/fake-spawner {:calls calls :stdout stdout :exit exit})
                 :closer  (w/fake-closer (atom []))
                 :sign-in {:key      the-key
                           :exchange (fn [code verifier]
                                       (swap! codes conj [code verifier])
                                       (when (= code "good-code") {"sub" subject}))}})]
    (assoc st :base (str "http://127.0.0.1:" port) :tokens tokens :codes codes :calls calls)))

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

;; ── a person's routine (R-12.7 to R-12.11) ──────────────────────────

(def alex "alex-subject-2")
(def alex-token "sk-ant-oat01-ALEX-OWN-TOKEN-9876543210")

(defn- fire! [w nm bearer]
  (w/POST (str (:base w) "/fire/" nm) bearer "{\"text\":\"go\"}"))

(defn- env-of
  "The environment the spawner saw for the run a 200 started."
  [w resp]
  (let [id (get-in resp [:json :claude_code_session_id])]
    (some (fn [c] (when (some #{id} (:argv c)) (:env c))) @(:calls w))))

(deftest each-persons-fires-run-under-their-own-token-and-config
  (let [w       (world {:oidc? true})
        p       (:people w)
        sam-nm  (people/routine-name subject)
        alex-nm (people/routine-name alex)]
    (try
      (people/store-token! p subject claude-token)
      (people/store-token! p alex alex-token)
      (let [[r logs]
            (capture
             #(let [sam   (fire! w sam-nm (people/fire-token p sam-nm))
                    al    (fire! w alex-nm (people/fire-token p alex-nm))
                    _     (w/wait-for (fn [] (= 2 (count @(:calls w)))))
                    cross (fire! w sam-nm (people/fire-token p alex-nm))
                    mach  (fire! w sam-nm "the-token")
                    _     (people/revoke-token! p alex)
                    gone  (fire! w alex-nm (people/fire-token p alex-nm))
                    me    (req :get (str (:base w) "/me") {:cookie (:cookie (sign-in! w))})]
                {:sam sam :al al :cross cross :mach mach :gone gone :me me}))
            {:keys [sam al cross mach gone me]} r
            runs-dir (get-in w [:config :runs-dir])
            sam-env  (env-of w sam)
            al-env   (env-of w al)]
        (testing "each person's fire runs with their own token and a config directory of its own (R-12.8)"
          (is (= [200 200] [(:status sam) (:status al)]))
          (is (= claude-token (get sam-env "CLAUDE_CODE_OAUTH_TOKEN")))
          (is (= alex-token (get al-env "CLAUDE_CODE_OAUTH_TOKEN")))
          (is (= (.getPath (runs/claude-config-dir runs-dir (get-in sam [:json :claude_code_session_id])))
                 (get sam-env "CLAUDE_CONFIG_DIR")))
          (is (some? (get al-env "CLAUDE_CONFIG_DIR")))
          (is (not= (get sam-env "CLAUDE_CONFIG_DIR") (get al-env "CLAUDE_CONFIG_DIR")))
          (is (and (contains? sam-env "ANTHROPIC_API_KEY") (nil? (get sam-env "ANTHROPIC_API_KEY")))
              "the machine's own key is dropped, not inherited"))
        (testing "a person's routine takes its own bearer and no other (R-12.7)"
          (is (= 401 (:status cross)))
          (is (= 401 (:status mach))))
        (testing "a revoked token is no live routine (R-12.8)"
          (is (= 401 (:status gone))))
        (testing "the person's page lists the routine and its credential (R-12.10)"
          (is (str/includes? (str (:body me)) (str (:base w) "/fire/" sam-nm)))
          (is (str/includes? (str (:body me)) (people/fire-token p sam-nm))))
        (testing "no token in a log, a page or a run record (R-12.11)"
          (doseq [t [claude-token alex-token]]
            (is (not (str/includes? logs t)))
            (doseq [x [sam al cross mach gone me]]
              (is (not (str/includes? (str (:body x)) t))))
            (doseq [^File f (file-seq (io/file runs-dir)) :when (.isFile f)]
              (is (not (str/includes? (slurp f) t)) (str f))))))
      (finally (server/stop! w)))))

(deftest a-person-whose-usage-ran-out-waits-for-the-reset
  (let [reset (+ (quot (System/currentTimeMillis) 1000) 7200)
        w     (world {:oidc? true :exit 1
                      :stdout (json/write-value-as-string
                               {"type" "result" "is_error" true
                                "result" (str "Claude AI usage limit reached|" reset)})})
        p     (:people w)
        nm    (people/routine-name subject)]
    (try
      (people/store-token! p subject claude-token)
      (let [one (fire! w nm (people/fire-token p nm))
            _   (w/wait-for #(get @(:exhausted w) nm))
            two (fire! w nm (people/fire-token p nm))]
        (is (= 200 (:status one)))
        (is (= 429 (:status two)))
        (is (<= 7000 (Long/parseLong (str (:retry-after two))) 7200))
        (is (= 1 (count @(:calls w))) "no run starts until the reset"))
      (finally (server/stop! w)))))

;; ── a person's pull link (R-12.12) ──────────────────────────────────

(def pull-token "the-persons-pull-link-token-0123456789")

(deftest a-person-stores-a-pull-link-and-its-loop-follows
  (let [w      (world {:oidc? true :pull-url "http://127.0.0.1:9"})
        p      (:people w)
        nm     (people/routine-name subject)
        starts (atom [])
        mgr    (pull/start-people! w "http://127.0.0.1:9"
                                   {:start (fn [_ pl token _]
                                             (swap! starts conj [(:link-id pl) (:routine pl) token])
                                             {:run (future :stopped) :stop (atom false)})})]
    (try
      (people/store-token! p subject claude-token)
      (let [s      (sign-in! w)
            page   (req :get (str (:base w) "/me") {:cookie (:cookie s)})
            csrf   (csrf-of (:body page))
            bad    (req :post (str (:base w) "/me/pull")
                        {:cookie (:cookie s) :form {:csrf csrf :link_id "not a link" :pull_token pull-token}})
            ok     (req :post (str (:base w) "/me/pull")
                        {:cookie (:cookie s) :form {:csrf csrf :link_id "link-1" :pull_token pull-token}})
            after  (req :get (str (:base w) "/me") {:cookie (:cookie s)})
            stored (people/routine-pull-link p nm)
            sealed (some (fn [^File f]
                           (when (str/ends-with? (.getName f) ".pull.sealed")
                             (String. (Files/readAllBytes (.toPath f)) StandardCharsets/UTF_8)))
                         (token-files w))
            listed (people/pull-routines p)
            gone   (req :post (str (:base w) "/me/pull/remove")
                        {:cookie (:cookie s) :form {:csrf csrf}})]
        (testing "a pull link is offered, refused when malformed, and stored sealed"
          (is (str/includes? (str (:body page)) "/me/pull"))
          (is (= 422 (:status bad)))
          (is (= 303 (:status ok)))
          (is (= {:link-id "link-1" :token pull-token} stored))
          (is (= [nm] listed))
          (is (some? sealed))
          (is (not (str/includes? sealed pull-token))))
        (testing "the page names the link and never its token"
          (is (str/includes? (str (:body after)) "link-1"))
          (is (not (str/includes? (str (:body after)) pull-token))))
        (testing "storing it starts the person's loop as their routine; removing it stops it"
          (is (= [["link-1" nm pull-token]] @starts))
          (is (= 303 (:status gone)))
          (is (nil? (people/routine-pull-link p nm)))
          (is (empty? @(:loops mgr)))))
      (finally
        (pull/stop-people! mgr)
        (server/stop! w)))))

(deftest the-cli-usage-sentence-is-read
  (is (= 3600 (server/usage-exhausted "You've hit your usage limit." 0)))
  (is (= 100 (server/usage-exhausted "Claude AI usage limit reached|1760000100" 1760000000000)))
  (is (nil? (server/usage-exhausted "Error: something else" 0))))
