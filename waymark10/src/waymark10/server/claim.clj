(ns waymark10.server.claim
  "The claim (docs/spec-first-run.md R-4.5 to R-4.7): an engine with no
  members, no solo owner, no IdP and a keys volume boots UNCLAIMED. It
  prints one line, `claim code: <code>` — 128 random bits, single use —
  and serves only the claim page and the well-known door; a browser's
  navigation elsewhere is sent to the page, and every other request
  answers 503 naming it.

  The page takes the code, a member id and the repository, as
  owner/repo. A right code makes that member the solo owner: the id is
  written to `owner` in the keys volume, so a later boot resolves it as
  WAYMARK10_SOLO_OWNER does, and the :on-claim hook (the first-run
  seed) runs with the owner and the repository. A repository that is
  not owner/repo is refused 422 before the code is judged. A wrong code
  answers 403, and five end the code; a restart prints a new one.

  The keys volume is the :keys-dir option, else WAYMARK10_KEYS_DIR,
  else the folder of WAYMARK10_DATA_KEY_FILE. An engine with none has
  nowhere to keep its owner and boots as before.

  The engine still binds 127.0.0.1 (R-4.7): the code keeps a second
  local user or process from claiming first, and no more than that."
  (:require [clojure.string :as str]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.io File)
           (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security MessageDigest SecureRandom)))

(set! *warn-on-reflection* true)

(def page-path "/claim")

(def owner-cookie
  "The cookie the claim's 303 leaves for the shell: the owner's id, which
  100-core.js writes into the principal box once and then spends. It
  carries no authority — the request still names the principal by
  header, and solo-admits judges it — it only spares the person who
  just claimed from typing their own name."
  "wm10.claimed")

(def max-misses
  "Wrong codes that end the code (R-4.6)."
  5)

(def ^:private repo-shape
  "A repository as the claim takes it: owner/repo, as GitHub spells it."
  #"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+")

;; ── the keys volume ───────────────────────────────────────────────

(defn keys-dir
  "The keys volume's folder: the :keys-dir option, else
  WAYMARK10_KEYS_DIR, else the folder of WAYMARK10_DATA_KEY_FILE, else
  nil. `env` is any (fn [name] value)."
  [opts env]
  (or (not-empty (some-> (:keys-dir opts) str))
      (not-empty (env "WAYMARK10_KEYS_DIR"))
      (when-some [^String f (not-empty (env "WAYMARK10_DATA_KEY_FILE"))]
        (.getParent (File. f)))))

(defn- owner-file ^File [^String dir] (File. dir "owner"))

(defn read-owner
  "The owner a claim kept in `dir`, or nil."
  [dir]
  (when dir
    (let [f (owner-file dir)]
      (when (.isFile f)
        (not-empty (str/trim (slurp f)))))))

(defn- write-owner!
  "Keep `owner` in the keys volume, writable by the engine alone and
  readable by all: it is a member id, not a secret, and localfire
  (uid 1500, selfhost/compose.yml) reads it as each run starts for
  X-Waymark-Acts-For, while the engine writes it as another uid."
  [dir owner]
  (let [f (owner-file dir)]
    (.createNewFile f)
    (try (Files/setPosixFilePermissions (.toPath f)
                                        (PosixFilePermissions/fromString "rw-r--r--"))
         (catch UnsupportedOperationException _ nil))
    (spit f (str owner "\n"))))

;; ── arming ────────────────────────────────────────────────────────

(defn- fresh-code []
  (let [bs (byte-array 16)]
    (.nextBytes (SecureRandom.) bs)
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) bs))))

(defn- any-member? [storage]
  (boolean (seq (store/with-tx storage
                  (fn [tx] (store/query-rows storage tx :member {} {:limit 1}))))))

(defn arm
  "eng, unclaimed when it has no solo owner, no IdP, no member and a
  keys volume (R-4.5): it then carries :claim, an atom of the code, the
  wrong codes so far and, once claimed, the owner, and the claim code
  is printed. Otherwise eng as it is."
  [eng opts env]
  (let [dir (keys-dir opts env)]
    (if (or (:oidc eng) (:solo-owner eng) (nil? dir)
            (any-member? (:storage eng)))
      eng
      (let [code (fresh-code)]
        (println (str "claim code: " code))
        (assoc eng :claim (atom {:code code :misses 0 :keys-dir dir
                                 :on-claim (:on-claim opts)}))))))

;; ── the claim ─────────────────────────────────────────────────────

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- attempt!
  "Judge one code for `member` → [:right member] | [:wrong misses] |
  [:spent]. A right code keeps the owner before it says so."
  [c code member]
  (locking c
    (let [{:keys [misses keys-dir] :as st} @c]
      (cond
        (:owner st) [:spent]
        (>= (long misses) max-misses) [:spent]
        (MessageDigest/isEqual (utf8 (:code st)) (utf8 (str code)))
        (do (write-owner! keys-dir member)
            (swap! c assoc :owner member)
            [:right member])
        :else [:wrong (:misses (swap! c update :misses inc))]))))

(defn- form? [req]
  (str/includes? (str (get-in req [:headers "content-type"]))
                 "application/x-www-form-urlencoded"))

(defn- params
  "The code, the member and the repo, from a form or a JSON body."
  [req]
  (let [body (some-> (:body req) slurp)]
    (if (form? req)
      (into {}
            (for [pair (str/split (str body) #"&")
                  :let [[k v] (str/split pair #"=" 2)]
                  :when (#{"code" "member" "repo"} k)]
              [(keyword k) (URLDecoder/decode (str v) "UTF-8")]))
      (let [m (when-not (str/blank? body) (wire/read-json body))]
        {:code (or (:code m) (get m "code"))
         :member (or (:member m) (get m "member"))
         :repo (or (:repo m) (get m "repo"))}))))

(defn- esc [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- page
  "The claim page, saying `said` when a claim was refused."
  [status said]
  {:status status
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><html><head><meta charset=\"utf-8\">"
              "<title>Claim this engine</title></head><body><main>"
              "<h1>Claim this engine</h1>"
              "<p>This engine has no owner yet. Its log printed one line, "
              "<code>claim code: …</code>. Enter that code, your name "
              "and your repository, and you become its owner.</p>"
              "<p>Your GitHub repository, as owner/repo: the project the "
              "factory works on and opens pull requests in. It must already "
              "exist, and the GitHub token you paste next needs write access "
              "to it.</p>"
              (when said (str "<p role=\"alert\">" (esc said) "</p>"))
              "<form method=\"post\" action=\"" page-path "\">"
              "<p><label>Claim code <input name=\"code\" type=\"password\" "
              "autocomplete=\"off\" required></label></p>"
              "<p><label>Your name (your member id) <input name=\"member\" "
              "placeholder=\"your GitHub user\" required></label></p>"
              "<p><label>Your repository <input name=\"repo\" "
              "placeholder=\"owner/repo\" required></label></p>"
              "<p><button type=\"submit\">Claim</button></p>"
              "</form></main></body></html>")})

(defn- seed-failed-page
  "What a claim by the form answers when its first-run seed failed: the
  claim stands, and the person reads the seed's sentence here, not in
  the log alone (ticket f1ee8cfa)."
  [said]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><html><head><meta charset=\"utf-8\">"
              "<title>Claimed, but the seed failed</title></head><body><main>"
              "<h1>Claimed, but the seed failed</h1>"
              "<p>You own this engine now, but its first-run seed did not load, "
              "so the front page has no setup cards. The seed said:</p>"
              "<p role=\"alert\">" (esc said) "</p>"
              "<p><a href=\"/\">Go to the front page</a></p>"
              "</main></body></html>")})

(defn- problem [status title detail]
  {:status status
   :headers {"content-type" "application/problem+json"}
   :body (wire/write-json {:type "https://waymark.dev/problems/unclaimed"
                           :status status :title title :detail detail
                           :claim page-path})})

(defn- refuse [req status title detail]
  (if (form? req) (page status detail) (problem status title detail)))

(defn- navigation?
  "A browser's navigation: a GET whose Accept names text/html ahead of
  any application/json. An API caller's JSON Accept is not one."
  [req]
  (let [accept (str/lower-case (str (get-in req [:headers "accept"])))
        html (str/index-of accept "text/html")
        json (str/index-of accept "application/json")]
    (boolean (and (= :get (:request-method req))
                  html
                  (or (nil? json) (< (long html) (long json)))))))

(defn- claim!
  [c claimed-eng req]
  (let [{:keys [code member repo]} (params req)
        member (some-> member str str/trim not-empty)
        repo (some-> repo str str/trim not-empty)]
    (cond
      (nil? member)
      (refuse req 422 "No member" "Name the member who becomes the owner.")

      (and repo (not (re-matches repo-shape repo)))
      (refuse req 422 "Not a repository"
              (str "The repository is owner/repo, as GitHub spells it, and \""
                   repo "\" is not."))

      :else
      (let [[verdict n] (attempt! c code member)]
        (case verdict
          :right
          (let [failed (when-some [f (:on-claim @c)]
                         (try (f @claimed-eng member repo) nil
                              (catch Exception e
                                (let [said (or (ex-message e) (str e))]
                                  (binding [*out* *err*]
                                    (println (str "waymark10 claim: the first-run seed failed — "
                                                  said)))
                                  said))))
                cookie (str owner-cookie "="
                            (URLEncoder/encode ^String member "UTF-8")
                            "; Path=/; Max-Age=600; SameSite=Strict")]
            (cond
              (and failed (form? req))
              (assoc-in (seed-failed-page failed) [:headers "set-cookie"] cookie)
              (form? req) {:status 303 :headers {"location" "/" "set-cookie" cookie} :body ""}
              :else
              {:status 200
               :headers {"content-type" "application/json"}
               :body (wire/write-json (cond-> {:owner member :repo repo :next "/"}
                                        failed (assoc :seed_failed failed)))}))
          :wrong
          (refuse req 403 "Wrong code"
                  (if (>= (long n) max-misses)
                    "That code is wrong, and five wrong codes ended it: restart the engine for a new one."
                    (str "That code is wrong (" n " of " max-misses ").")))
          :spent
          (refuse req 403 "Code spent"
                  "This claim code has ended: restart the engine for a new one."))))))

(defn handler
  "The ring handler of an unclaimed engine. `build` is the engine's
  handler fn: until the claim it answers the well-known door alone,
  and after it every request, over the engine with its solo owner."
  [eng build]
  (let [c (:claim eng)
        bare (dissoc eng :claim)
        unclaimed (build bare)
        claimed-eng (delay (assoc bare :solo-owner (:owner @c)))
        claimed (delay (build @claimed-eng))]
    (fn [req]
      (if (:owner @c)
        (@claimed req)
        (let [uri (:uri req)]
          (cond
            (= uri page-path)
            (case (:request-method req)
              :get (page 200 nil)
              :post (claim! c claimed-eng req)
              (problem 405 "Method not allowed" "The claim page takes GET and POST."))

            (= uri "/api/.well-known/waymark")
            (unclaimed req)

            (navigation? req)
            {:status 303 :headers {"location" page-path} :body ""}

            :else
            (problem 503 "Unclaimed"
                     (str "This engine has no owner yet: claim it at " page-path
                          " with the code its log printed."))))))))
