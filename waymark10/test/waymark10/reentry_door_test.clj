(ns waymark10.reentry-door-test
  "The homecoming door (waymark-4zj.8) — a one-shot re-entry token
  that re-admits an ACTIVE agent member as its STABLE id: a
  recovery-admin HUMAN mints :offer_reentry (nobody else — not an
  agent, not the engine's own actors), the token rides the POST
  /auth/agent body, the concealed :spend_reentry nulls it exactly
  once under race, and the session that comes back names the member
  the story already belongs to. The welcome's :home rides with or
  without a standing grant; when one lives it is worn the way a
  guest's first arrival would wear it. Unknown, spent and expired
  tokens answer one byte-identical 404, and an expired token does
  NOT spend. Neither credential field ever renders (:secret), and
  the mint's transition record holds no raw token.

  The engine rides a test clock (:now-fn over an atom) so expiry is
  proved against the live clock, never by sleeping.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [buddy.core.keys :as bkeys]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.resource :refer [defresource]]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "reentry-key" :alg "RS256" :use "sig")]})

;; a dwelling-shaped kind, so the homecoming can be proved PROPER:
;; welcome-home (router.clj) keys :self rows on data.owner == the
;; authenticated id — the whole point of re-admitting the STABLE
;; member is that this row comes back
(defresource home-self
  {:kind :self
   :plural "selves"
   :states [:active :retired]
   :initial :active
   :terminal #{:retired}
   :summary "{data.display} · {state}"
   :schema [:map
            [:owner [:string {:min 1 :max 128}]]
            [:display [:string {:min 1 :max 80}]]
            [:about {:optional true} [:maybe [:string {:max 240}]]]]
   :filterable {:owner #{:eq} :state #{:eq}}
   :actions
   {:retire {:from #{:active} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired self stays readable; a fresh one takes its place."}
             :display {:label "Retire" :order 9}}}})

(def ^:private tables
  ["meals" "selves" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions" "capabilities"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *raw* nil)
(def ^:dynamic *gated* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          clock (atom (Instant/now))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine
                   {:storage st :resources [fx/meal home-self]
                    :now-fn (fn [] @clock)
                    ;; small pacing ceilings so R6b's throttle is cheap
                    ;; to drive; the two door windows are reset before
                    ;; each test (below) so ordinary tests never hit
                    ;; them. Separate buckets per flow (N1) — a re-entry
                    ;; flood must not starve invite onboarding
                    :services {:reentry-door-hourly 20
                               :invite-door-hourly 20}
                    :oidc {:issuer "https://idp.test/realms/home"
                           :audience "reentry-test"
                           :jwks jwks
                           :rp {:client-id "reentry-test"
                                :client-secret "shh"
                                :app-url "http://app.test"
                                :session-secret "a-32-byte-session-secret-home!!"
                                :require-auth? true}}})]
          (binding [*eng* eng
                    *clock* clock
                    *raw* (engine/handler eng)
                    *gated* ((rp/wrap-handler eng) (engine/handler eng))]
            ;; R1: the minter's authority must live in the member ROW,
            ;; not a token claim — register recovery-admin and seed
            ;; Colton as a HUMAN row carrying it, id = his principal id
            ;; so gate! resolves him to this row
            (inv/create! eng :role {:name "recovery-admin"}
                         {:principal members/registrar})
            (inv/create! eng :member
                         {:display "Colton" :actor_type "human"
                          :roles ["recovery-admin"]}
                         {:principal members/registrar :id "colton"})
            (f)))
        (finally (pg/close! st))))))

;; the /auth/agent door is rate-paced (R6b); fresh windows per test so
;; one test's attempts never spend another's slack. Both buckets (N1:
;; re-entry and invite-bind are decoupled) are cleared.
(use-fixtures :each
  (fn [f]
    (reset! members/reentry-door-log [])
    (reset! members/invite-door-log [])
    (f)))

;; ── request sugar ───────────────────────────────────────────────────

(defn- body-stream
  ;; a real ring body is an InputStream — and body-invite slurps,
  ;; which would read a bare String as a file path
  [m]
  (java.io.ByteArrayInputStream. (.getBytes (wire/write-json m) "UTF-8")))

(defn- req* [h method uri & [{:keys [query body headers]}]]
  (h (cond-> {:request-method method :uri uri
              :headers (or headers {})}
       query (assoc :query-string query)
       body (assoc :body (body-stream body)))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(def ^:private admin
  ;; the system operator — setup only; it can NOT mint re-entry
  {"x-waymark-principal" "admin" "x-waymark-actor-type" "system"})

(def ^:private colton
  ;; the human recovery-admin — the one hand that mints
  {"x-waymark-principal" "colton" "x-waymark-roles" "recovery-admin"})

(defn- agent-member!
  "An ACTIVE agent member (no bind_token — born :active), its id."
  [display]
  (let [resp (req* *raw* :post "/api/members"
                   {:body {:display display :actor_type "agent"}
                    :headers admin})]
    (is (= 201 (:status resp)) (pr-str (json resp)))
    (is (= "active" (:state (json resp))))
    (id-of resp)))

(defn- mint!
  "POST offer_reentry as `headers`; the raw response."
  [mid token & [{:keys [expires_at headers]}]]
  (req* *raw* :post (str "/api/members/" mid "/-/offer_reentry")
        {:body (cond-> {:token token}
                 expires_at (assoc :expires_at expires_at))
         :headers (or headers colton)}))

(defn- door!
  "POST /auth/agent with the token riding the BODY — the re-entry
  spelling; the raw response."
  [token]
  (req* *gated* :post "/auth/agent" {:body {:invite token}}))

(defn- cookie-of [resp]
  (get-in (json resp) [:session :use :value]))

(def ^:private tok-a "reentry-tok-aaaaaaaaaa-0001")
(def ^:private tok-b "reentry-tok-bbbbbbbbbb-0002")
(def ^:private tok-c "reentry-tok-cccccccccc-0003")
(def ^:private tok-d "reentry-tok-dddddddddd-0004")
(def ^:private tok-e "reentry-tok-eeeeeeeeee-0005")
(def ^:private tok-f "reentry-tok-ffffffffff-0006")
(def ^:private tok-g "reentry-tok-gggggggggg-0007")

;; ── who may mint ────────────────────────────────────────────────────

(deftest minting-is-the-recovery-admin-humans-alone
  (let [mid (agent-member! "mint-target")]
    (testing "an agent principal cannot mint — not even wearing the
              role: a re-entry token IS a session, and an agent must
              never mint its own way back in. An ungranted agent meets
              concealment's 404 before the guard can even refuse; a
              granted one would meet the guard's 409 — either wall
              holds"
      (is (contains? #{404 409 422}
                     (:status (mint! mid tok-a
                                     {:headers {"x-waymark-principal" "sly"
                                                "x-waymark-actor-type" "agent"
                                                "x-waymark-roles" "recovery-admin"}})))))
    (testing "a human WITHOUT recovery-admin cannot"
      (is (contains? #{409 422}
                     (:status (mint! mid tok-a
                                     {:headers {"x-waymark-principal" "norole"}})))))
    (testing "even a GRANTED agent meets the guard: a scope may name
              the action, the mint still refuses on principal type —
              this is the wall, not concealment"
      (let [sly {"x-waymark-principal" "sly" "x-waymark-actor-type" "agent"
                 "x-waymark-roles" "recovery-admin"}
            grant (json (req* *raw* :post "/api/grants"
                              {:body {:audience "sly"
                                      :scope [{:kind "member"
                                               :actions ["offer_reentry"]}]
                                      :expires_at (str (.plusSeconds ^Instant @*clock* 3600))}
                               :headers admin}))
            gid (last (str/split (str (:self grant)) #"/"))]
        (is (some? (:self grant)) (pr-str grant))
        (is (= 200 (:status (req* *raw* :post (str (:self grant) "/-/accept")
                                  {:headers sly}))))
        (is (contains? #{409 422}
                       (:status (mint! mid tok-a
                                       {:headers (assoc sly "x-waymark-grant" gid)}))))))
    (testing "the engine's own system actor cannot either — a system
              path would be one ctx :invoke away from any handler"
      (is (contains? #{409 422}
                     (:status (mint! mid tok-a {:headers admin})))))
    (testing "the recovery-admin human mints"
      (is (= 200 (:status (mint! mid tok-a)))))
    (testing "a human-actor member is never a target — humans re-enter
              through the identity provider"
      (let [resp (req* *raw* :post "/api/members"
                       {:body {:display "harold" :actor_type "human"}
                        :headers admin})
            hid (id-of resp)]
        (is (= 201 (:status resp)))
        (is (contains? #{409 422} (:status (mint! hid tok-b))))))
    (testing "a suspended member is out of the from-state's reach"
      (is (= 200 (:status (req* *raw* :post
                                (str "/api/members/" mid "/-/suspend")
                                {:headers admin}))))
      (is (contains? #{404 409 422} (:status (mint! mid tok-b)))))))

(deftest the-expiry-is-capped-against-the-live-clock
  (let [mid (agent-member! "cap-target")]
    (testing "past the hour refuses — the guard never silently clamps"
      (is (contains? #{409 422}
                     (:status (mint! mid tok-c
                                     {:expires_at (str (.plusSeconds ^Instant @*clock* 7200))})))))
    (testing "under the hour lands"
      (is (= 200 (:status (mint! mid tok-c
                                 {:expires_at (str (.plusSeconds ^Instant @*clock* 1800))})))))))

;; ── the door ────────────────────────────────────────────────────────

(deftest the-homecoming-readmits-the-stable-member
  (let [mid (agent-member! "cairn")
        _ (is (= 201 (:status (req* *raw* :post "/api/selves"
                                    {:body {:owner mid :display "Cairn"
                                            :about "the house keeps our story"}
                                     :headers admin}))))
        _ (is (= 200 (:status (mint! mid tok-d))))
        resp (door! tok-d)
        body (json resp)
        cookie (cookie-of resp)]
    (testing "the session names the member's STABLE id — never a
              fresh row"
      (is (= 200 (:status resp)) (pr-str body))
      (is (= mid (get-in body [:agent :id])))
      (is (= "cairn" (get-in body [:agent :display]))))
    (testing "no standing grant lives, and the welcome is still
              proper: :home is own-surface and owes nothing to one"
      (let [doc (json (req* *gated* :get "/api/-/welcome"
                            {:headers {"cookie" cookie}}))]
        (is (= "cairn" (:welcome doc)))
        (is (= "Cairn" (get-in doc [:home :self :display]))
            "the returning id owns its self — the homecoming is whole"))
      (is (= 404 (:status (req* *gated* :get "/api/meals"
                                {:headers {"cookie" cookie}})))
          "grant-less means unscoped sight stays dark, not a dark welcome"))
    (testing "spent, expired and garbage answer one byte-identical
              404 — and the expired token does NOT spend"
      (let [spent (door! tok-d)
            garbage (door! "reentry-tok-zzzzzzzzzz-9999")
            expired (let [mid2 (agent-member! "expired-target")]
                      (is (= 200 (:status (mint! mid2 tok-e))))
                      ;; sixteen minutes by the engine's own clock —
                      ;; past the default fifteen
                      (swap! *clock* #(.plusSeconds ^Instant % 960))
                      (door! tok-e))]
        (is (= 404 (:status spent) (:status garbage) (:status expired)))
        (is (= (:body spent) (:body garbage) (:body expired))
            "one dark answer, whatever the token's fate")
        ;; R9: an expired token names a row but opens no door — the
        ;; door sweeps it lazily so a dead credential does not linger
        ;; raw in rows and nightly backups. No session was minted (the
        ;; 404 above proves that); only the dead value was cleaned.
        (is (nil? (first (store/with-tx (:storage *eng*)
                           (fn [tx] (store/query-rows
                                     (:storage *eng*) tx :member
                                     {:reentry_token tok-e} {:limit 1})))))
            "the dead credential was swept — 404 opened no way in, and
             left nothing behind")))))

(deftest a-remint-overwrites-the-standing-token
  (let [mid (agent-member! "remint-target")]
    (is (= 200 (:status (mint! mid tok-f))))
    (is (= 200 (:status (mint! mid tok-g))))
    (is (= 404 (:status (door! tok-f)))
        "at most ONE live credential per member — the prior token died
         at the remint")
    (is (= 200 (:status (door! tok-g))))))

(deftest a-standing-grant-rides-the-homecoming-when-one-lives
  (let [mid (agent-member! "granted-comer")
        tok "reentry-tok-granted-00000008"
        meal (json (req* *raw* :post "/api/meals"
                         {:body {:name "welcome-home brisket" :themes ["bbq"]}
                          :headers {"x-waymark-principal" "chef"}}))
        _ (is (some? (:self meal)))
        grant (json (req* *raw* :post "/api/grants"
                          {:body {:audience mid
                                  :scope [{:kind "meal" :actions []}]
                                  :expires_at (str (.plusSeconds ^Instant @*clock* 259200))}
                           :headers admin}))
        _ (is (= "offered" (:state grant)) (pr-str grant))
        _ (is (= 200 (:status (mint! mid tok))))
        resp (door! tok)
        cookie (cookie-of resp)]
    (is (= 200 (:status resp)))
    (testing "the offer was accepted AS the audience on arrival — the
              guest door's courtesy, worn by the homecoming too"
      (is (= "accepted" (:state (json (req* *raw* :get (:self grant)
                                            {:headers admin}))))))
    (testing "the session wears the grant: scoped sight, no header
              to know"
      (is (= 200 (:status (req* *gated* :get (:self meal)
                                {:headers {"cookie" cookie}})))))))

(deftest a-double-spend-races-to-exactly-one-session
  (let [mid (agent-member! "raced-comer")
        tok "reentry-tok-raced-0000000009"
        _ (is (= 200 (:status (mint! mid tok))))
        gate (java.util.concurrent.CountDownLatch. 1)
        posts (mapv (fn [_]
                      (future (.await gate) (door! tok)))
                    (range 2))
        _ (.countDown gate)
        statuses (mapv (comp :status deref) posts)]
    (is (= [200 404] (sort statuses))
        (str "exactly one session per credential; got " statuses))
    (is (nil? (first (store/with-tx (:storage *eng*)
                       (fn [tx] (store/query-rows (:storage *eng*) tx :member
                                                  {:reentry_token tok}
                                                  {:limit 1})))))
        "the row ends spent — the credential is gone whoever won")))

;; ── nothing leaks ───────────────────────────────────────────────────

(deftest the-credential-never-leaves-the-engine
  (is (contains? (schema/secret-fields (:schema members/member))
                 :reentry_token))
  (is (contains? (schema/secret-fields (:schema members/member))
                 :reentry_expires_at)
      "R7: the expiry is concealed too — a raw death time is a
       live-credential beacon")
  (is (contains? (schema/secret-fields (:schema members/member))
                 :bind_token)
      "the recorded punt is closed in the same change")
  (let [mid (agent-member! "sealed-comer")
        rtok "reentry-tok-sealed-000000010"
        btok "bind-tok-sealed-11"
        _ (is (= 200 (:status (mint! mid rtok))))
        _ (is (= 201 (:status (req* *raw* :post "/api/members"
                                    {:body {:display "sealed-invitee"
                                            :actor_type "agent"
                                            :bind_token btok}
                                     :headers admin}))))]
    (testing "neither token rides a row or collection render, even to
              the unscoped operator"
      (let [row (req* *raw* :get (str "/api/members/" mid)
                      {:headers admin})
            col (req* *raw* :get "/api/members" {:headers admin})]
        (is (= 200 (:status row)))
        (is (= 200 (:status col)))
        (doseq [b [(str (:body row)) (str (:body col))]]
          (is (not (str/includes? b rtok)))
          (is (not (str/includes? b btok)))
          ;; R7: neither the raw expiry value nor its field name rides
          ;; any render — no live-credential beacon
          (is (not (str/includes? b "reentry_expires_at"))))))
    (testing "the mint's transition record holds no raw token — the
              action is NOT :record, and the digest is one-way"
      (let [ts (store/with-tx (:storage *eng*)
                 (fn [tx] (store/transitions (:storage *eng*) tx
                                             {:kind :member
                                              :resource-id mid} {})))
            minted (first (filter #(= :offer_reentry (:action %)) ts))]
        (is (some? minted))
        (is (nil? (:inputs minted))
            "raw inputs are persisted only for :record actions")
        (is (not (str/includes? (pr-str minted) rtok)))
        (is (re-matches #"[0-9a-f]{64}" (str (:input-digest minted)))
            "the audit keeps a hash, never the credential")))))

;; ── the review's CONFIRMED breaks, reproduced-then-closed ────────────

(deftest r1-minting-authority-is-read-from-the-member-row
  ;; R1 [S1-F1]: the review's HIGH — a token's actor_type claim is
  ;; optional and fails open to :human, so an agent claiming
  ;; recovery-admin minted its own way back in. Authority (both the
  ;; human-ness AND the role) now comes from the durable member ROW.
  (let [mid (agent-member! "r1-target")
        tok "r1-row-authority-reentry-0001"]
    (testing "a human whose recovery-admin is ONLY a token/header claim
              — never assigned to its member row — cannot mint"
      (is (contains? #{409 422}
                     (:status (mint! mid tok
                                     {:headers {"x-waymark-principal" "claimful"
                                                "x-waymark-roles" "recovery-admin"}})))))
    (testing "Colton, whose recovery-admin lives in his HUMAN member
              row, mints"
      (is (= 200 (:status (mint! mid tok)))))))

(deftest r2-the-credential-cannot-be-planted-by-create
  ;; R2 [S1-F2]: a create carrying reentry_token used to land :active
  ;; with a live, uncapped, unaudited, unrenderable backdoor (a 201).
  ;; Only :offer_reentry may write the field now.
  (testing "a create carrying reentry_token is refused — never a 201"
    (let [resp (req* *raw* :post "/api/members"
                     {:body {:display "trojan" :actor_type "agent"
                             :reentry_token "planted-reentry-token-000001"
                             :reentry_expires_at
                             (str (.plusSeconds ^Instant @*clock* 1800))}
                      :headers {"x-waymark-principal" "planter"}})]
      (is (not= 201 (:status resp)) (pr-str (json resp)))
      (is (contains? #{409 422} (:status resp)))))
  (testing "reentry_expires_at alone is refused too"
    (is (contains? #{409 422}
                   (:status (req* *raw* :post "/api/members"
                                  {:body {:display "trojan2" :actor_type "agent"
                                          :reentry_expires_at
                                          (str (.plusSeconds ^Instant @*clock* 600))}
                                   :headers admin})))))
  (testing "an honest create — no credential field — still lands active"
    (is (= 201 (:status (req* *raw* :post "/api/members"
                              {:body {:display "honest" :actor_type "agent"}
                               :headers admin}))))))

(deftest r3-the-door-refuses-a-non-agent-row-carrying-a-token
  ;; R3 [S1-F5]: the agents-only rule held only at the mint. The door
  ;; now checks actor_type too — defense in depth against any row that
  ;; acquired a token by some other path.
  (let [resp (req* *raw* :post "/api/members"
                   {:body {:display "human-holder" :actor_type "human"}
                    :headers admin})
        hid (id-of resp)
        htok "door-human-reentry-token-00003"
        future (str (.plusSeconds ^Instant @*clock* 3600))]
    (is (= 201 (:status resp)))
    ;; plant a LIVE token on the human row directly in storage — the
    ;; only way such a row could exist, and exactly what R3 closes
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (jdbc/execute! tx
          ["UPDATE members SET data = data || ?::jsonb WHERE id = ?"
           (doto (org.postgresql.util.PGobject.)
             (.setType "jsonb")
             (.setValue (wire/write-json {:reentry_token htok
                                          :reentry_expires_at future})))
           hid])))
    (testing "the token names a live row, but the door refuses it"
      (is (= 404 (:status (door! htok)))))
    (testing "and the refusal swept nothing — the door rejected on
              actor_type, not expiry (the token is still live)"
      (is (some? (first (store/with-tx (:storage *eng*)
                          (fn [tx] (store/query-rows
                                    (:storage *eng*) tx :member
                                    {:reentry_token htok} {:limit 1})))))))))

(deftest r4-the-spend-is-bound-to-the-token-presented
  ;; R4 [S2-F1]: the spend nulled whatever token was on the row, in a
  ;; separate tx from the lookup — a stale in-flight token could spend
  ;; a freshly-reminted credential (revocation defeat). The spend is
  ;; now a compare-and-set on the exact presented token.
  (let [mid (agent-member! "r4-target")
        tok "cas-live-reentry-token-0000004"
        wrong "cas-wrong-reentry-token-000004"]
    (is (= 200 (:status (mint! mid tok))))
    (testing "spend_reentry with a token that does not match the row's
              standing one refuses and spends nothing"
      (is (thrown? Exception
                   (inv/invoke! *eng* :member mid :spend_reentry
                                {:token wrong}
                                {:principal members/registrar}))))
    (testing "the live credential survived the mismatched spend"
      (is (some? (first (store/with-tx (:storage *eng*)
                          (fn [tx] (store/query-rows
                                    (:storage *eng*) tx :member
                                    {:reentry_token tok} {:limit 1}))))))
      (is (= 200 (:status (door! tok)))))))

(deftest r5-the-reentry-token-is-refused-off-the-query-string
  ;; R5 [S1-F6, S3-F1/F2, S4-F1]: the re-entry branch read the query
  ;; spelling FIRST; a bodyless ?invite= minted+spent, and an
  ;; uncredentialed GET copied the live token into a 302 Location
  ;; without spending it. Re-entry now reads the BODY only.
  (let [mid (agent-member! "r5-target")
        tok "query-string-reentry-tok-005"]
    (is (= 200 (:status (mint! mid tok))))
    (testing "the token ONLY in the query string is not honored and
              spends nothing"
      (is (= 404 (:status (req* *gated* :post "/auth/agent"
                                {:query (str "invite=" tok)}))))
      (is (some? (first (store/with-tx (:storage *eng*)
                          (fn [tx] (store/query-rows
                                    (:storage *eng*) tx :member
                                    {:reentry_token tok} {:limit 1})))))
          "the query presentation spent nothing — still live"))
    (testing "the same token in the BODY opens the door"
      (is (= 200 (:status (door! tok)))))))

(deftest r6c-a-token-already-live-elsewhere-is-refused-at-mint
  ;; R6c [S1-F3/F4]: one token minted onto two rows opened two
  ;; sessions as two identities. A mint refuses a token already live
  ;; on another member row.
  (let [a (agent-member! "r6c-a")
        b (agent-member! "r6c-b")
        tok "duplicate-reentry-token-000006"]
    (is (= 200 (:status (mint! a tok))))
    (testing "the same token minted onto a SECOND member is refused"
      (is (contains? #{409 422} (:status (mint! b tok)))))
    (testing "member A's credential is untouched and opens the door"
      (is (= 200 (:status (door! tok)))))))

(deftest r6b-the-door-is-paced-against-guessing
  ;; R6b [S2-F5, S4-F4]: POST /auth/agent had no pacing, so the
  ;; uniform-404 door could be guessed against a short window. It is
  ;; now throttled like the knock door (a rolling-hour window; the
  ;; test engine's re-entry ceiling is 20). A single abuser flooding
  ;; the re-entry flow trips its 429 (N1 test (c)).
  (reset! members/reentry-door-log [])
  (let [results (mapv (fn [i]
                        (:status (door! (str "guess-reentry-token-000000"
                                             (format "%03d" i)))))
                      (range 22))]
    (testing "under the ceiling the door still answers its honest 404"
      (is (= 404 (first results))))
    (testing "past the ceiling the door answers 429, not a limitless
              stream of 404s"
      (is (some #{429} results)
          (str "expected a 429 once the window filled; got " results)))))

;; ── N1: the two flows are DECOUPLED ─────────────────────────────────

(defn- invite-door!
  "POST /auth/agent with the invite token on the QUERY string — the
  onboarding spelling the guest link and the Access panel use; the raw
  response. Distinct from door! (body token = the re-entry flow)."
  [token]
  (req* *gated* :post "/auth/agent" {:query (str "invite=" token)}))

(deftest n1-a-reentry-flood-does-not-lock-out-invite-onboarding
  ;; N1 [re-attack verdict]: the old single global window was charged
  ;; before BOTH branches, so an anonymous flood of garbage re-entry
  ;; guesses 429'd the invite-onboarding door too. Repro against the
  ;; pre-fix code: fill the window with re-entry misses, then a
  ;; legitimate invite bind → 429. Post-fix the buckets are separate,
  ;; so onboarding stays open. (This is the Keycloak-unreachable
  ;; fallback door — a global lockout defeats its purpose.)
  (reset! members/reentry-door-log [])
  (reset! members/invite-door-log [])
  ;; stand a real invitation up (setup on the raw handler)
  (let [invited (req* *raw* :post "/api/members"
                      {:body {:display "n1-invitee" :actor_type "agent"
                              :bind_token "n1-invite-bind-token-000001"}
                       :headers admin})]
    (is (= 201 (:status invited)))
    (is (= "invited" (:state (json invited))))
    ;; flood the RE-ENTRY flow well past its ceiling (20) with garbage
    ;; body tokens — every one a re-entry miss
    (let [flood (mapv (fn [i]
                        (:status (door! (str "n1-garbage-reentry-000000"
                                             (format "%03d" i)))))
                      (range 40))]
      (is (some #{429} flood)
          (str "the re-entry flood should trip its own 429; got " flood)))
    (testing "the legitimate invite bind is UNTOUCHED — its own window
              never saw the re-entry flood (N1: decoupled buckets)"
      (let [bound (invite-door! "n1-invite-bind-token-000001")]
        (is (= 200 (:status bound))
            (str "invite onboarding must survive a re-entry flood; got "
                 (:status bound) " " (pr-str (json bound))))
        (is (= "n1-invitee" (get-in (json bound) [:agent :display])))))))

(deftest n1-an-invite-flood-does-not-lock-out-homecoming
  ;; the mirror of the above: an invite-onboarding flood must not
  ;; starve the re-entry homecoming door either — the buckets cut both
  ;; ways.
  (reset! members/reentry-door-log [])
  (reset! members/invite-door-log [])
  (let [mid (agent-member! "n1-homecomer")]
    (is (= 200 (:status (mint! mid tok-a))))
    (let [flood (mapv (fn [i]
                        (:status (invite-door! (str "n1-garbage-invite-000000"
                                                    (format "%03d" i)))))
                      (range 40))]
      (is (some #{429} flood)
          (str "the invite flood should trip its own 429; got " flood)))
    (testing "the homecoming still opens — the re-entry window never
              saw the invite flood"
      (let [home (door! tok-a)]
        (is (= 200 (:status home))
            (str "homecoming must survive an invite flood; got "
                 (:status home) " " (pr-str (json home))))
        (is (= mid (get-in (json home) [:agent :id])))))))

;; ── homecoming is durable-only (waymark-4zj.8.3, reproduced-then-closed) ─

(deftest offer-reentry-refuses-a-hollow-guest
  ;; waymark-4zj.8.3 [LIVE ACCEPTANCE FAILURE]: offer_reentry had no
  ;; durable-self guard, so a recovery-admin could mint a way home onto a
  ;; HOLLOW namesake — a knock-born guest row that owns no self, no
  ;; story (in prod the mint bound to an empty duplicate 'Cairn' and
  ;; welcome-home came back empty). Against the pre-guard code that mint
  ;; SUCCEEDED (200); the durable-only guard now refuses it.
  (reset! members/knock-log [])
  (testing "a durable idp agent (born 'idp') can be offered re-entry —
            even self-less: an idp row is your own durable identity with
            an empty journal, not a foreign namesake (GAP A, intended —
            durable == provenance idp, owns-self is not a signal)"
    (let [durable (agent-member! "durable-cairn")]
      (is (= "idp" (get-in (json (req* *raw* :get (str "/api/members/" durable)
                                       {:headers admin}))
                           [:data :provenance])))
      (is (= 200 (:status (mint! durable "durable-reentry-token-00001"))))))
  (testing "a knock-born guest — active agent, provenance 'knock', owns no
            self — is REFUSED (the hollow mint 4zj.8.3 let through)"
    (let [krow (members/knock! *eng* {:display "hollow-cairn"})
          bound (members/bind-agent! *eng* (get-in krow [:data :bind_token]))
          gid (:id bound)]
      (is (= :active (:state bound)))
      (is (= "knock" (get-in bound [:data :provenance])))
      (is (contains? #{409 422}
                     (:status (mint! gid "hollow-reentry-token-000001")))
          "the durable guard closes the hollow-namesake homecoming")
      (is (nil? (first (store/with-tx (:storage *eng*)
                         (fn [tx] (store/query-rows
                                   (:storage *eng*) tx :member
                                   {:reentry_token "hollow-reentry-token-000001"}
                                   {:limit 1})))))
          "no credential was minted onto the hollow row"))))

(deftest offer-reentry-fails-closed-without-provenance
  ;; REVISED (waymark-4zj.9.1): durable == provenance "idp", the sole
  ;; unforgeable signal — the old "owns an active :self" fallback is
  ;; GONE. A :self is forgeable (waymark-4zj.10), so it must never open
  ;; the door. A row that PREDATES the backfill carries no provenance
  ;; and now FAILS CLOSED, whether or not it owns a self — which is why
  ;; the backfill is a required deploy companion, not optional cleanup.
  (let [with-self (agent-member! "legacy-with-self")
        without (agent-member! "legacy-without-self")]
    ;; strip provenance to simulate a pre-backfill row
    (doseq [id [with-self without]]
      (store/with-tx (:storage *eng*)
        (fn [tx]
          (jdbc/execute! tx
            ["UPDATE members SET data = data - 'provenance' WHERE id = ?" id]))))
    (is (= 201 (:status (req* *raw* :post "/api/selves"
                              {:body {:owner with-self :display "Legacy Home"}
                               :headers admin}))))
    (testing "no provenance + owns an active self → STILL refused: a
              forgeable self is no longer a durable signal"
      (is (contains? #{409 422}
                     (:status (mint! with-self "legacy-self-reentry-tok-0001")))
          "the guard fails closed on an unlabelled row, self or no self"))
    (testing "no provenance + owns no self → refused"
      (is (contains? #{409 422}
                     (:status (mint! without "legacy-none-reentry-tok-01")))))))

(deftest a-planted-self-cannot-open-a-provenance-less-door
  ;; the GAP-4 repro (skeptic pass on waymark-4zj.9.1, closed): :self
  ;; ownership is forgeable (waymark-4zj.10) — a role-less human can
  ;; create a :self naming ANY owner id — and the old owns-self fallback
  ;; treated "owns an active :self" as durable, so a planted self flipped
  ;; offer_reentry 409→200 on a hollow provenance-less row. The revised
  ;; guard reads provenance ALONE, so the plant can no longer open the
  ;; door: an unlabelled row fails closed regardless of any :self on it.
  (let [hollow (agent-member! "gap4-hollow")
        mallory {"x-waymark-principal" "mallory"}]
    ;; strip provenance to simulate a pre-backfill hollow row
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (jdbc/execute! tx
          ["UPDATE members SET data = data - 'provenance' WHERE id = ?" hollow])))
    (testing "before the plant, the hollow (unlabelled) row is refused"
      (is (contains? #{409 422}
                     (:status (mint! hollow "gap4-before-plant-tok-0001")))))
    (testing "mallory — a role-less human — plants a :self on the hollow id"
      (is (= 201 (:status (req* *raw* :post "/api/selves"
                                {:body {:owner hollow :display "Planted"}
                                 :headers mallory})))
          "the plant itself still lands — :self ownership is forgeable
           (waymark-4zj.10, tracked separately)"))
    (testing "the planted self does NOT open the door — provenance is
              still absent, so the guard fails closed (GAP 4 closed)"
      (is (contains? #{409 422}
                     (:status (mint! hollow "gap4-after-plant-tok-00001")))))))

(deftest the-provenance-backfill-proposes-and-writes-nothing
  ;; REVISED (waymark-4zj.9.1): the classifier reads the UNFORGEABLE
  ;; subject/origin signals, NOT "owns a :self" (forgeable — a planted
  ;; self would mis-propose a hollow guest as durable). A subject==nil,
  ;; origin-less row is idp; a subject==self row is a guest.
  (reset! members/knock-log [])
  (let [;; a token-less create — never bound (subject nil), no origin → idp
        durable (agent-member! "backfill-durable")
        ;; a self, planted on the durable id, must NOT sway the proposal
        _ (is (= 201 (:status (req* *raw* :post "/api/selves"
                                    {:body {:owner durable :display "Durable"}
                                     :headers admin}))))
        ;; a knock, BOUND: subject == its own id, invited_by == registrar
        knock-row (members/knock! *eng* {:display "backfill-knock"})
        bound (members/bind-agent! *eng* (get-in knock-row [:data :bind_token]))
        knock-id (:id bound)
        snap #(store/with-tx (:storage *eng*)
                (fn [tx] (store/query-rows (:storage *eng*) tx :member
                                           {} {:limit 100000})))
        before (snap)
        proposal (members/provenance-backfill-proposal *eng*)
        by-id (into {} (map (juxt :id identity)) proposal)]
    (testing "a subject==nil, origin-less row is proposed 'idp' — on the
              subject signal, not on the self it happens to own"
      (is (= "idp" (:provenance (by-id durable))))
      (is (nil? (get-in (by-id durable) [:because :subject])))
      (is (false? (get-in (by-id durable) [:because :subject-is-self?]))))
    (testing "a self-bound knock guest (subject == its own id, registrar
              inviter) is proposed 'knock'"
      (is (= "knock" (:provenance (by-id knock-id))))
      (is (true? (get-in (by-id knock-id) [:because :subject-is-self?])))
      (is (true? (get-in (by-id knock-id) [:because :self-invited?]))))
    (testing "the proposal wrote NOTHING — every member row is unchanged"
      (is (= before (snap))))))

(deftest r8-suspend-revokes-the-standing-credential
  ;; R8 [S1-F6/F7]: suspension hid the member but left the re-entry
  ;; token live, so a suspend/reinstate cycle resurrected it. Suspend
  ;; now nulls the credential.
  (let [mid (agent-member! "r8-target")
        tok "suspend-clears-reentry-tok-08"]
    (is (= 200 (:status (mint! mid tok))))
    (is (= 200 (:status (req* *raw* :post
                              (str "/api/members/" mid "/-/suspend")
                              {:headers admin}))))
    (testing "the token is gone the moment the member is suspended"
      (is (nil? (first (store/with-tx (:storage *eng*)
                         (fn [tx] (store/query-rows
                                   (:storage *eng*) tx :member
                                   {:reentry_token tok} {:limit 1})))))))
    (testing "and a reinstate cannot resurrect it — the door stays dark"
      (is (= 200 (:status (req* *raw* :post
                                (str "/api/members/" mid "/-/reinstate")
                                {:headers admin}))))
      (is (= 404 (:status (door! tok)))))))
