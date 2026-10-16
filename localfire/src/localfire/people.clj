(ns localfire.people
  "A person signs in through Keycloak and keeps their own Claude token
  here (spec-local-fire.md §12).

  This is the whole of it: the authorization code flow with PKCE
  against one realm, a session in a cookie, and one sealed file per
  person. The token goes in through one form and comes out through
  `read-token` only — no page renders it and no line logs it — and the
  person can delete it. Without `:oidc` in the config `init` answers
  nil, and the server has none of these routes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [localfire.page :as page])
  (:import [java.io File]
           [java.net URI URLDecoder URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.security MessageDigest SecureRandom]
           [java.time Duration]
           [java.util Base64]
           [javax.crypto Cipher Mac]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def cookie-name "localfire_session")

(def ^:private pending-ms
  "How long a sign-in begun here may take to come back (R-12.1)."
  (* 10 60 1000))

(def ^:private session-ms
  "How long a session lasts (R-12.1)."
  (* 12 60 60 1000))

(def ^:private max-token-chars 4096)

;; ── bytes ───────────────────────────────────────────────────────────

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- random-bytes ^bytes [n]
  (let [b (byte-array n)] (.nextBytes rng b) b))

(defn- utf8 ^bytes [s] (.getBytes (str s) StandardCharsets/UTF_8))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- sha256 ^bytes [^bytes b]
  (.digest (MessageDigest/getInstance "SHA-256") b))

(defn- hex [^bytes b]
  (apply str (map #(format "%02x" (bit-and (long %) 0xff)) b)))

(defn parse-key
  "A key as LOCALFIRE_TOKEN_KEY spells it — 32 bytes in base64 — or
  nil (R-12.2)."
  [s]
  (let [b (try (some->> s str str/trim not-empty (.decode (Base64/getDecoder)))
               (catch IllegalArgumentException _ nil))]
    (when (and b (= 32 (alength ^bytes b))) b)))

(defn env-key
  "The sealing key, from LOCALFIRE_TOKEN_KEY and from nowhere else."
  []
  (parse-key (System/getenv "LOCALFIRE_TOKEN_KEY")))

(defn env-secret
  "The realm client's secret, when the client is confidential (R-12.5)."
  []
  (some-> (System/getenv "LOCALFIRE_OIDC_SECRET") not-empty))

;; ── forms ───────────────────────────────────────────────────────────

(defn- url-encode [v] (URLEncoder/encode (str v) StandardCharsets/UTF_8))
(defn- url-decode [v] (URLDecoder/decode (str v) StandardCharsets/UTF_8))

(defn form-encode
  "A map, or pairs, as `k=v&...`."
  [m]
  (str/join "&" (for [[k v] m] (str (url-encode (name k)) "=" (url-encode v)))))

(defn parse-form
  "A query string or a form body as a map of strings; {} when it does
  not decode."
  [s]
  (try
    (into {} (for [pair (str/split (str s) #"&")
                   :when (not (str/blank? pair))
                   :let [[k v] (str/split pair #"=" 2)]]
               [(url-decode k) (url-decode (or v ""))]))
    (catch IllegalArgumentException _ {})))

;; ── the sealed token (R-12.2, R-12.3) ───────────────────────────────

(defn seal
  "AES-256-GCM: a fresh 12-byte nonce, then the ciphertext and its tag.
  The subject's hash — the file's name — is the associated data, so one
  person's file moved under another's name does not open, and a fire
  that knows only its routine's name opens its own."
  ^bytes [^bytes k sub ^String plain]
  (let [iv  (random-bytes 12)
        c   (doto (Cipher/getInstance "AES/GCM/NoPadding")
              (.init Cipher/ENCRYPT_MODE (SecretKeySpec. k "AES") (GCMParameterSpec. 128 iv))
              (.updateAAD (utf8 sub)))
        ct  ^bytes (.doFinal ^Cipher c (utf8 plain))
        out (byte-array (+ 12 (alength ct)))]
    (System/arraycopy iv 0 out 0 12)
    (System/arraycopy ct 0 out 12 (alength ct))
    out))

(defn unseal
  "What `seal` sealed, or a throw when the key or the subject is not
  the one it was sealed under."
  ^String [^bytes k sub ^bytes sealed]
  (let [c (doto (Cipher/getInstance "AES/GCM/NoPadding")
            (.init Cipher/DECRYPT_MODE (SecretKeySpec. k "AES") (GCMParameterSpec. 128 sealed 0 12))
            (.updateAAD (utf8 sub)))]
    (String. ^bytes (.doFinal ^Cipher c sealed 12 (- (alength sealed) 12))
             StandardCharsets/UTF_8)))

(defn- person-hex [sub] (hex (sha256 (utf8 sub))))

(defn- sealed-file ^File [people h]
  (io/file (get-in people [:oidc :tokens-dir]) (str h ".sealed")))

(defn token-file
  "One file per person, named by the hash of the subject so the
  directory names nobody."
  ^File [people sub]
  (sealed-file people (person-hex sub)))

(defn- owner-only! [^File f]
  (doto f
    (.setReadable false false) (.setReadable true true)
    (.setWritable false false) (.setWritable true true)))

(defn store-token!
  "Seal the token and write it, owner-only, before a byte is in it."
  [people sub token]
  (let [f (token-file people sub)]
    (.mkdirs (.getParentFile f))
    (.createNewFile f)
    (owner-only! f)
    (Files/write (.toPath f) (seal (:key people) (person-hex sub) token) (make-array OpenOption 0))
    nil))

(defn has-token? [people sub]
  (.isFile (token-file people sub)))

(defn revoke-token! [people sub]
  (io/delete-file (token-file people sub) true)
  nil)

;; ── the person's routine (R-12.7, R-12.8) ───────────────────────────

(defn routine-name
  "The routine a person's fires run as: named, as their file is, by the
  hash of the subject, so the engine's rows name nobody either."
  [sub]
  (str "p-" (person-hex sub)))

(defn- routine-hex [nm]
  (second (re-matches #"p-([0-9a-f]{64})" (str nm))))

(defn person-routine?
  "Whether `nm` is spelled as a person's routine."
  [nm]
  (some? (routine-hex nm)))

(defn fire-token
  "The bearer a person's routine takes, and no other routine does: an
  HMAC of its name under the key, so nothing more is stored and it is
  the same after a restart, a revoke and a new token."
  [people nm]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. ^bytes (:key people) "HmacSHA256")))]
    (b64url (.doFinal ^Mac mac (utf8 (str "localfire fire " nm))))))

(defn routine-token
  "The Claude token behind the person's routine `nm`, opened — for a run
  under their own login, never for a page — or nil when they stored
  none, revoked it, or its file does not open under the key."
  [people nm]
  (when-let [h (routine-hex nm)]
    (let [f (sealed-file people h)]
      (when (.isFile f)
        (try (unseal (:key people) h (Files/readAllBytes (.toPath f)))
             (catch Exception _ nil))))))

(defn read-token
  "The person's token, opened — for a run under their own login, never
  for a page."
  [people sub]
  (routine-token people (routine-name sub)))

;; ── the realm (R-12.1, R-12.5) ──────────────────────────────────────

(defn redirect-uri [public-url] (str public-url "/signin/callback"))

(defn- challenge [verifier] (b64url (sha256 (utf8 verifier))))

(defn authorize-url [oidc public-url state verifier]
  (str (:authorize-url oidc) "?"
       (form-encode [[:response_type "code"]
                     [:client_id (:client-id oidc)]
                     [:redirect_uri (redirect-uri public-url)]
                     [:scope "openid"]
                     [:state state]
                     [:code_challenge (challenge verifier)]
                     [:code_challenge_method "S256"]])))

(defn- jwt-claims [jwt]
  (let [parts (str/split (str jwt) #"\.")]
    (when (= 3 (count parts))
      (try (json/read-value (.decode (Base64/getUrlDecoder) ^String (second parts)))
           (catch Exception _ nil)))))

(defn- audience? [claims client-id]
  (let [aud (get claims "aud")]
    (if (sequential? aud) (boolean (some #{client-id} aud)) (= aud client-id))))

(def ^:private client (delay (HttpClient/newHttpClient)))

(defn exchange-code
  "The back channel: the code and the verifier for the tokens at the
  realm's token endpoint. The id token comes straight from the issuer
  over TLS, so its claims are read without a signature check (OIDC
  Core 3.1.3.7), but its `iss` and `aud` must be ours. → the claims,
  or nil."
  [oidc secret public-url code verifier]
  (let [form (cond-> [[:grant_type "authorization_code"]
                      [:code code]
                      [:redirect_uri (redirect-uri public-url)]
                      [:client_id (:client-id oidc)]
                      [:code_verifier verifier]]
               secret (conj [:client_secret secret]))
        req  (-> (HttpRequest/newBuilder (URI/create (:token-url oidc)))
                 (.timeout (Duration/ofSeconds 10))
                 (.header "content-type" "application/x-www-form-urlencoded")
                 (.POST (HttpRequest$BodyPublishers/ofString (form-encode form)))
                 (.build))
        resp (try (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))
                  (catch Exception _ nil))]
    (when (and resp (= 200 (.statusCode ^HttpResponse resp)))
      (let [body   (try (json/read-value (str (.body ^HttpResponse resp)))
                        (catch Exception _ nil))
            claims (some-> body (get "id_token") jwt-claims)]
        (when (and claims
                   (= (get claims "iss") (:issuer oidc))
                   (audience? claims (:client-id oidc)))
          ;; the access token is the person's own, for the engine's doors
          (assoc claims :access-token (get body "access_token")))))))

(defn engine-post
  "One POST to the engine as the signed-in person: their own access
  token is the bearer, so the engine sees localfire-claude acting for
  them. → {:status :body}, the body read as JSON, or nil when the
  engine did not answer."
  [url access-token body]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 10))
                (.header "content-type" "application/json")
                (.header "accept" "application/json")
                (.header "authorization" (str "Bearer " access-token))
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body)))
                (.build))]
    (try (let [resp ^HttpResponse (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
           {:status (.statusCode resp)
            :body   (try (json/read-value (str (.body resp))) (catch Exception _ nil))})
         (catch Exception _ nil))))

(defn init
  "The sign-in's state, or nil when the config has no `:oidc`.
  `:exchange` is the seam the tests fake: (code verifier) → claims.
  `:engine` is the other: (url access-token body) → {:status :body}."
  [config {:keys [key secret exchange engine]}]
  (when-let [oidc (:oidc config)]
    (when-not key
      (throw (ex-info "the config has :oidc, and LOCALFIRE_TOKEN_KEY is not a base64 AES key of 32 bytes."
                      {:localfire/config true})))
    {:oidc     oidc
     :key      key
     :sessions (atom {})
     :pending  (atom {})
     :exchange (or exchange
                   (fn [code verifier]
                     (exchange-code oidc secret (:public-url config) code verifier)))
     :engine   (or engine engine-post)}))

;; ── the person's runner link (ticket eae4b907) ───────────────────────

(defn link-file
  "Where the id of the person's runner link is kept, beside their token.
  It is no secret, and a revoke keeps it, so a later press restates the
  same link instead of making another."
  ^File [people sub]
  (io/file (get-in people [:oidc :tokens-dir]) (str (person-hex sub) ".link")))

(defn read-link [people sub]
  (let [f (link-file people sub)]
    (when (.isFile f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn- write-link! [people sub link]
  (let [f (link-file people sub)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str link))
    nil))

(defn- links-url [people & parts]
  (apply str (get-in people [:oidc :engine-url]) "/api/runner_links" parts))

(defn- answered? [r]
  (boolean (some-> (:status r) (< 300))))

(defn- refused [r]
  (if-let [s (:status r)]
    (str "The engine refused your link (" s
         (some->> (get-in r [:body "detail"]) (str ": ")) ").")
    "The engine did not answer. Try again in a moment."))

(defn connect!
  "Register the person's routine as their own runner link: the engine
  sees localfire-claude acting for them and stamps them its owner. The
  first press makes a push link with the routine's fire URL and bearer;
  a later one restates it, restoring it first when a revoke retired it.
  → nil, or the sentence for the page."
  [people public-url sess]
  (let [sub  (:sub sess)
        at   (:access-token sess)
        nm   (routine-name sub)
        url  (str public-url "/fire/" nm)
        tok  (fire-token people nm)
        post (:engine people)
        link (read-link people sub)]
    (cond
      (str/blank? at)
      "Your sign-in carried no access token for the engine. Sign out and sign in again."

      (nil? link)
      (let [r  (post (links-url people) at {:provider "localfire" :delivery "push"
                                             :fire_url url :fire_token tok})
            id (some-> (get-in r [:body "id"]) str not-empty)]
        (if (and (answered? r) id)
          (write-link! people sub {:id id})
          (refused r)))

      :else
      (let [id (str (:id link))]
        ;; a person may have restored it by hand: the restate judges
        (when (:retired link)
          (post (links-url people "/" (url-encode id) "/-/restore") at {}))
        (let [r (post (links-url people "/" (url-encode id) "/-/restate") at
                      {:provider "localfire" :fire_url url :token tok})]
          (if (answered? r)
            (write-link! people sub {:id id})
            (refused r)))))))

(defn- retire-link!
  "A revoked token leaves the routine nothing to run under, so its link
  is retired: nothing fires through it until the next press."
  [people sess]
  (when-let [id (some-> (read-link people (:sub sess)) :id str)]
    (when-not (str/blank? (:access-token sess))
      ((:engine people) (links-url people "/" (url-encode id) "/-/retire")
                        (:access-token sess) {}))
    (write-link! people (:sub sess) {:id id :retired true})))

;; ── the routes ──────────────────────────────────────────────────────

(defn- html [status body]
  {:status  status
   :headers {"content-type" "text/html; charset=utf-8" "cache-control" "no-store"}
   :body    body})

(defn- redirect
  ([loc] (redirect loc nil))
  ([loc cookie]
   {:status  303
    :headers (cond-> {"location" loc "cache-control" "no-store"}
               cookie (assoc "set-cookie" cookie))
    :body    ""}))

(defn- session-cookie [sid public-url]
  (str cookie-name "=" sid "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" (quot session-ms 1000)
       (when (str/starts-with? (str public-url) "https:") "; Secure")))

(defn- cookie-value [req nm]
  (some (fn [part]
          (let [[k v] (str/split (str/trim part) #"=" 2)]
            (when (= k nm) v)))
        (str/split (str (get-in req [:headers "cookie"])) #";")))

(defn- session [people req]
  (when-let [sid (cookie-value req cookie-name)]
    (let [s (get @(:sessions people) sid)]
      (when (and s (< (System/currentTimeMillis) (:expires s)))
        (assoc s :sid sid)))))

(defn- begin-signin [people public-url]
  (let [state    (b64url (random-bytes 24))
        verifier (b64url (random-bytes 32))
        now      (System/currentTimeMillis)]
    (swap! (:pending people)
           (fn [m] (-> (into {} (filter (fn [[_ v]] (< (- now (:at v)) pending-ms))) m)
                       (assoc state {:verifier verifier :at now}))))
    {:status  302
     :headers {"location" (authorize-url (:oidc people) public-url state verifier)
               "cache-control" "no-store"}
     :body    ""}))

(defn- finish-signin [people public-url q]
  (let [st      (get q "state")
        now     (System/currentTimeMillis)
        [old _] (swap-vals! (:pending people) dissoc st)
        pend    (get old st)
        claims  (when (and pend (get q "code") (< (- now (:at pend)) pending-ms))
                  ((:exchange people) (get q "code") (:verifier pend)))
        sub     (some-> (get claims "sub") str not-empty)]
    (if-not sub
      (html 401 (page/problem-page "sign-in failed"
                                   "The sign-in did not come back as one this server began. Sign in again."))
      (let [sid (b64url (random-bytes 32))]
        (swap! (:sessions people) assoc sid
               {:sub sub :csrf (b64url (random-bytes 24)) :expires (+ now session-ms)
                :access-token (some-> (:access-token claims) str not-empty)})
        (redirect "/me" (session-cookie sid public-url))))))

(defn- forbidden []
  (html 403 (page/problem-page "refused"
                               "This form did not come from your own signed-in page. Open /me and try again.")))

(defn- me [people public-url sess problem]
  (let [sub  (:sub sess)
        has? (boolean (and sess (has-token? people sub)))
        nm   (when has? (routine-name sub))]
    (page/me-page {:sub        sub
                   :has-token? has?
                   :csrf       (:csrf sess)
                   :problem    problem
                   ;; R-12.10: what the runner link's registration needs
                   :routine    (when nm {:name    nm
                                         :url     (str public-url "/fire/" nm)
                                         :token   (fire-token people nm)
                                         :engine? (boolean (get-in people [:oidc :engine-url]))
                                         :link    (read-link people sub)})})))

(defn handle
  "The sign-in and token routes (R-12.1–R-12.3), or nil for a path
  that is not one of them."
  [people public-url req]
  (let [method   (:request-method req)
        path     (str/replace (str (:uri req)) #"/+$" "")
        sess     (session people req)
        form     (delay (parse-form (some-> (:body req) slurp)))
        csrf-ok? (fn []
                   (let [given (get @form "csrf")]
                     (and sess given
                          (MessageDigest/isEqual (utf8 (:csrf sess)) (utf8 given)))))]
    (case [method path]
      [:get "/signin"]          (begin-signin people public-url)
      [:get "/signin/callback"] (finish-signin people public-url (parse-form (:query-string req)))
      [:get "/me"]              (html 200 (me people public-url sess nil))

      [:post "/me/token"]
      (if-not (csrf-ok?)
        (forbidden)
        (let [t (str/trim (str (get @form "token")))]
          (if (or (str/blank? t) (> (count t) max-token-chars))
            (html 422 (me people public-url sess "That is not a token from claude setup-token. Nothing was stored."))
            (do (store-token! people (:sub sess) t)
                (redirect "/me")))))

      [:post "/me/revoke"]
      (if-not (csrf-ok?)
        (forbidden)
        (do (revoke-token! people (:sub sess))
            (retire-link! people sess)
            (redirect "/me")))

      [:post "/me/connect"]
      (if-not (csrf-ok?)
        (forbidden)
        (if-let [problem (cond
                           (not (has-token? people (:sub sess)))
                           "Store a token first: your link fires your routine, which runs under it."
                           (str/blank? (get-in people [:oidc :engine-url]))
                           "This localfire names no engine (:oidc :engine-url), so it cannot register your link."
                           :else (connect! people public-url sess))]
          (html 422 (me people public-url sess problem))
          (redirect "/me")))

      [:post "/signout"]
      (if-not (csrf-ok?)
        (forbidden)
        (do (swap! (:sessions people) dissoc (:sid sess))
            (redirect "/me" (str cookie-name "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"))))

      nil)))
