(ns waymark10.agent-door-test
  "The /auth/agent door — the invite link is the whole key: an agent
  holding ONLY the link reads the welcome document through the gate
  (token-gated by design), POSTs the same token to the door, and
  walks away bound with an engine-minted session; the link goes dark
  behind it. A live session renews itself; a lapsed or absent one is
  the honest 401; a spent or unknown token answers 404 and says
  nothing. Setup drives the RAW handler (an admin's system actor is
  the operator's own); the flow drives the WRAPPED one — the same
  gate production serves.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [buddy.core.keys :as bkeys]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.resource :refer [defresource]]
            [waymark10.server.capabilities :refer [capability]]
            [waymark10.server.engine :as engine]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "door-key" :alg "RS256" :use "sig")]})

;; the filter-scope fixture: a kind with a filterable data field, so
;; a grant can scope "the rows matching" instead of "these ids"
(defresource guest-chore
  {:kind :guest_chore
   :plural "guest_chores"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 80}]]
            [:assignee {:optional true} [:maybe [:string {:max 40}]]]
            ;; teaser-flagged prose: rides row :fields truncated
            [:detail {:optional true
                      :x-display {:widget "prose" :teaser true}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :assignee #{:eq}}
   :actions
   {:complete {:from #{:open} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Done is done; a fresh run is a fresh row."}
               :display {:label "Complete" :order 1}}}})

(def ^:private tables
  ["meals" "guest_chores" "capabilities" "members" "roles" "grants"
   "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *raw* nil)
(def ^:dynamic *gated* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine
                   {:storage st :resources [fx/meal guest-chore capability]
                    :oidc {:issuer "https://idp.test/realms/home"
                           :audience "door-test"
                           :jwks jwks
                           :rp {:client-id "door-test"
                                :client-secret "shh"
                                :app-url "http://app.test"
                                :session-secret "a-32-byte-session-secret-door!!"
                                :require-auth? true}}})]
          (binding [*raw* (engine/handler eng)
                    *gated* ((rp/wrap-handler eng) (engine/handler eng))]
            (f)))
        (finally (pg/close! st))))))

(defn- req* [h method uri & [{:keys [query body headers]}]]
  (h (cond-> {:request-method method :uri uri
              :headers (or headers {})}
       query (assoc :query-string query)
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))

(def ^:private admin
  {"x-waymark-principal" "admin" "x-waymark-actor-type" "system"})

(def ^:private token "door-tok-4471")

(deftest the-invite-link-is-the-whole-key
  ;; the admin's invite, born :invited — setup on the raw handler
  (let [resp (req* *raw* :post "/api/members"
                   {:body {:display "door-agent" :actor_type "agent"
                           :bind_token token}
                    :headers admin})]
    (is (= 201 (:status resp)))
    (is (= "invited" (:state (json resp)))))

  (testing "the gate refuses the anonymous, but not the welcome page —
            token-gated by design, and it advertises the door"
    (is (= 401 (:status (req* *gated* :get "/api/meals"))))
    (let [resp (req* *gated* :get "/api/-/welcome"
                     {:query (str "invite=" token)})]
      (is (= 200 (:status resp)))
      (is (= (str "/auth/agent?invite=" token)
             (get-in (json resp) [:session :href])))))

  (let [resp (req* *gated* :post "/auth/agent" {:query (str "invite=" token)})
        body (json resp)
        cookie (get-in body [:session :use :value])]
    (testing "the door binds and mints"
      (is (= 200 (:status resp)))
      (is (= "door-agent" (get-in body [:agent :display])))
      (is (string? (get-in body [:session :token])))
      (is (str/starts-with? cookie "waymark_session=")))

    (testing "the session is an IDENTITY, not sight (waymark-rci):
              the domain answers 404, the asking door answers 200 —
              an agent's access is off by default and negotiated"
      (is (= 404 (:status (req* *gated* :get "/api/meals"
                                {:headers {"cookie" cookie}}))))
      (is (= 200 (:status (req* *gated* :get "/api/approval_requests"
                                {:headers {"cookie" cookie}}))))
      (is (contains? (set (:kinds (json (req* *gated* :get
                                              "/api/.well-known/waymark"
                                              {:headers {"cookie" cookie}}))))
                     "meal")
          "the vocabulary stays whole — an agent must be able to
           NAME what it asks for"))

    (testing "the link went dark — door and welcome both — and the
              dark answer names the knock"
      (let [dead (req* *gated* :post "/auth/agent"
                       {:query (str "invite=" token)})]
        (is (= 404 (:status dead)))
        (is (= "/agentInvite" (get-in (json dead) [:knock :href]))
            "a dark invite's one legal remedy rides the refusal"))
      (is (= 404 (:status (req* *gated* :get "/api/-/welcome"
                                {:query (str "invite=" token)})))
          "anonymous with a spent token stays dark"))

    (testing "the manual survives being followed — a named principal
              re-reads the welcome bare, forever"
      (let [doc (json (req* *gated* :get "/api/-/welcome"
                            {:headers {"cookie" cookie}}))]
        (is (some? (:ask doc)))
        (is (nil? (:bind doc))
            "no standing invitation — no bind section to mislead")
        (is (some? (get-in doc [:then :watch]))
            "the doc teaches watching your own ask over polling")))

    (testing "well-known indexes the doors"
      (let [w (json (req* *gated* :get "/api/.well-known/waymark"
                          {:headers {"cookie" cookie}}))]
        (is (= "/agentInvite" (get-in w [:doors :knock :href])))
        (is (some? (get-in w [:doors :guest :template]))
            "the RP engine publishes the magic-link template")))

    (testing "a live session renews; nothing renews nothing"
      (let [renewed (req* *gated* :post "/auth/agent/renew"
                          {:headers {"cookie" cookie}})]
        (is (= 200 (:status renewed)))
        (is (string? (get-in (json renewed) [:session :token]))))
      (is (= 401 (:status (req* *gated* :post "/auth/agent/renew"))))))

  (testing "garbage never binds"
    (is (= 404 (:status (req* *gated* :post "/auth/agent"
                              {:query "invite=nope"}))))))

;; ── the whole negotiation: hidden < hashed < read ───────────────────

(def ^:private token2 "door-tok-9932")

(deftest sight-is-always-negotiated
  ;; the world: one meal (a human's, unscoped), one invited agent
  (let [meal (json (req* *raw* :post "/api/meals"
                         {:body {:name "Traeger brisket" :themes ["bbq"]}
                          :headers {"x-waymark-principal" "colton"}}))
        _ (is (some? (:self meal)))
        _ (req* *raw* :post "/api/members"
                {:body {:display "scoped-agent" :actor_type "agent"
                        :bind_token token2}
                 :headers admin})
        cookie (-> (req* *gated* :post "/auth/agent"
                         {:query (str "invite=" token2)})
                   json (get-in [:session :use :value]))
        as-agent (fn [method uri & [opts]]
                   (req* *gated* method uri
                         (update opts :headers merge {"cookie" cookie})))]

    (testing "default: the meal kind does not exist for the agent"
      (is (= 404 (:status (as-agent :get "/api/meals"))))
      (is (= 404 (:status (as-agent :get (:self meal))))))

    ;; the ask: correlate meals by name, never read them
    (let [ask (json (as-agent :post "/api/approval_requests"
                              {:body {:task "correlate meals without reading names"
                                      :scope [{:kind "meal" :actions []
                                               :hashed ["name"]}]}}))
          _ (is (some? (:self ask)) (pr-str ask))
          ;; the human approves (four-eyes: never the requester)
          approved (req* *raw* :post (str (:self ask) "/-/approve")
                         {:headers admin})
          _ (is (= 200 (:status approved)) (:body approved))
          grant-id (get-in (json (req* *raw* :get (:self ask)
                                       {:headers admin}))
                           [:data :grant_id])
          _ (is (some? grant-id))
          granted (fn [method uri & [opts]]
                    (as-agent method uri
                              (update opts :headers merge
                                      {"x-waymark-grant" grant-id})))]

      (testing "under the grant: the row exists, the name is a token"
        (let [row (json (granted :get (:self meal)))]
          (is (str/starts-with? (get-in row [:data :name]) "#")
              (pr-str (:data row)))
          (is (= ["bbq"] (get-in row [:data :themes]))
              "un-named fields stay plainly visible")
          (is (str/includes? (:summary row) "#")
              "the summary wears the token, never the value")))

      (testing "the token is stable — correlation is the point"
        (is (= (get-in (json (granted :get (:self meal))) [:data :name])
               (get-in (json (granted :get (:self meal))) [:data :name]))))

      (testing "the oracle is closed: filters and sorts on the hashed
                field answer the unknown-parameter 422"
        (is (= 422 (:status (granted :get "/api/meals"
                                     {:query "name=Traeger%20brisket"}))))
        (is (= 422 (:status (granted :get "/api/meals"
                                     {:query "sort=name"}))))
        (is (= 200 (:status (granted :get "/api/meals")))
            "the kind's own default sort softens to id order"))

      (testing "dropping the grant header drops the sight again"
        (is (= 404 (:status (as-agent :get (:self meal)))))))))

;; ── the knock: the invite loop inverted (/agentInvite) ──────────────

(deftest the-agent-knocks-and-carries-the-link-back
  (testing "the door teaches the anonymous before it mints — open
            through the require-auth gate by design"
    (let [resp (req* *gated* :get "/agentInvite")]
      (is (= 200 (:status resp)))
      (is (= "POST" (get-in (json resp) [:knock :method]))))
    (is (= 200 (:status (req* *gated* :get "/api/-/agent-invite")))
        "the /api/-/ spelling answers too"))

  (testing "a nameless knock is refused with the way in"
    (is (= 422 (:status (req* *gated* :post "/agentInvite" {:body {}})))))

  (testing "a named knock mints both halves: the agent's welcome link
            and the human's follow link"
    (let [resp (req* *gated* :post "/agentInvite"
                     {:body {:display "Knocker"}
                      :headers {"host" "app.test"
                                "x-forwarded-proto" "https"}})
          body (json resp)
          welcome (get-in body [:welcome :href])
          follow (get-in body [:follow :href])]
      (is (= 201 (:status resp)) (pr-str body))
      (is (str/starts-with? (str welcome)
                            "https://app.test/api/-/welcome?invite="))
      (is (str/starts-with? (str follow) "https://app.test/?follow="))
      (is (str/includes? (str follow) "follow_name=Knocker"))
      (is (str/ends-with? (str follow) "#access"))
      (is (str/starts-with? (str (get-in body [:approve :template]))
                            "https://app.test/#/api/approval_requests/")
          "the hand-off link template rides the knock answer")

      (let [tok (subs welcome (inc (str/index-of welcome "=")))]
        (testing "the minted token opens the same welcome doc"
          (is (= 200 (:status (req* *gated* :get "/api/-/welcome"
                                    {:query (str "invite=" tok)})))))
        (testing "and binds down the credential-less door — the follow
                  link already names the principal the session acts as"
          (let [bound (req* *gated* :post "/auth/agent"
                            {:query (str "invite=" tok)})
                agent-id (get-in (json bound) [:agent :id])]
            (is (= 200 (:status bound)))
            (is (= "Knocker" (get-in (json bound) [:agent :display])))
            (is (str/includes? (str follow) (str agent-id)))))))))

(deftest a-bodyless-knock-is-still-the-nameless-refusal
  (is (= 422 (:status (req* *gated* :post "/agentInvite")))))

(deftest the-knock-is-paced-two-ways
  (let [knock #(req* *gated* :post "/agentInvite"
                     {:body {:display (str "pace-probe-" %)}
                      :headers {"host" "app.test"}})
        token-of (fn [resp]
                   (let [w (get-in (json resp) [:welcome :href])]
                     (subs w (inc (str/index-of w "=")))))
        bind! (fn [tok]
                (is (= 200 (:status (req* *gated* :post "/auth/agent"
                                          {:query (str "invite=" tok)})))))]

    (testing "the rolling hour refuses a window already full — the
              wall binding cannot drain"
      (reset! members/knock-log
              (vec (repeat 12 (java.time.Instant/now))))
      (let [resp (knock "hour")]
        (is (= 429 (:status resp)))
        (is (str/includes? (:detail (json resp)) "paced"))))

    (testing "the standing shelf refuses the 13th unclaimed invitation,
              and binding reopens the door"
      (let [minted (loop [n 0 toks []]
                     (reset! members/knock-log [])   ; isolate the shelf
                     (let [resp (knock n)]
                       (cond (= 429 (:status resp))
                             (do (is (str/includes? (:detail (json resp))
                                                    "unclaimed"))
                                 toks)
                             (< n 13) (recur (inc n)
                                             (conj toks (token-of resp)))
                             :else (do (is false "the shelf never filled")
                                       toks))))]
        (is (pos? (count minted)) "the loop minted before it was refused")
        ;; drain the shelf: every invitation binds, none stand unclaimed
        (doseq [tok minted] (bind! tok))
        (reset! members/knock-log [])
        (let [again (knock "reopened")]
          (is (= 201 (:status again)))
          (bind! (token-of again)))))
    (reset! members/knock-log [])))

(deftest the-login-bounce-carries-the-whole-address
  ;; waymark-0hh: a deep link's query must survive the bounce — the
  ;; agent's follow link dies on a cold browser otherwise. (The
  ;; fragment never reaches a server; browsers carry it themselves.)
  (let [resp (req* *gated* :get "/"
                   {:query "follow=abc&follow_name=X"
                    :headers {"accept" "text/html"}})]
    (is (= 302 (:status resp)))
    (is (str/includes?
         (str (get-in resp [:headers "Location"]))
         (str "return-to=" (java.net.URLEncoder/encode
                            "/?follow=abc&follow_name=X" "UTF-8"))))))

;; ── the magic link: /auth/guest — invite + session + grant, one URL ──

(deftest the-magic-link-admits-a-scoped-guest
  (let [gtok "guest-tok-5150"
        member (json (req* *raw* :post "/api/members"
                           {:body {:display "guest-alice" :actor_type "agent"
                                   :bind_token gtok}
                            :headers admin}))
        mid (last (str/split (str (:self member)) #"/"))
        meal (json (req* *raw* :post "/api/meals"
                         {:body {:name "pool meal" :themes ["pool"]}
                          :headers {"x-waymark-principal" "colton"}}))
        _ (is (some? (:self meal)))
        expires (str (.plusSeconds (java.time.Instant/now) 259200))
        grant (json (req* *raw* :post "/api/grants"
                          {:body {:audience mid
                                  :scope [{:kind "meal" :actions []}]
                                  :expires_at expires}
                           :headers admin}))]
    (is (= "offered" (:state grant)) (pr-str grant))

    (testing "a garbage link is dark"
      (is (= 404 (:status (req* *gated* :get "/auth/guest"
                                {:query "invite=nope"})))))

    (let [resp (req* *gated* :get "/auth/guest"
                     {:query (str "invite=" gtok)})
          set-cookie (str (get-in resp [:headers "Set-Cookie"]))
          cookie (first (str/split set-cookie #";"))]
      (testing "first arrival: 302 home, session minted, offer accepted
                as the audience"
        (is (= 302 (:status resp)))
        (is (= "/" (get-in resp [:headers "Location"])))
        (is (str/starts-with? cookie "waymark_session="))
        (is (= "accepted" (:state (json (req* *raw* :get (:self grant)
                                              {:headers admin}))))))

      (testing "the visitor lands already scoped — no header presented,
                sight of exactly the granted kind and nothing else"
        (is (= 200 (:status (req* *gated* :get (:self meal)
                                  {:headers {"cookie" cookie}}))))
        (is (= 404 (:status (req* *gated* :get "/api/roles"
                                  {:headers {"cookie" cookie}})))))

      (testing "the same link re-admits while the grant lives — a
                lapsed cookie is not a lapsed welcome"
        (is (= 302 (:status (req* *gated* :get "/auth/guest"
                                  {:query (str "invite=" gtok)})))))

      (testing "revoke: the link goes dark AND the worn session scopes
                to nothing"
        (is (= 200 (:status (req* *raw* :post (str (:self grant) "/-/revoke")
                                  {:headers admin}))))
        (is (= 404 (:status (req* *gated* :get "/auth/guest"
                                  {:query (str "invite=" gtok)}))))
        (is (= 404 (:status (req* *gated* :get (:self meal)
                                  {:headers {"cookie" cookie}}))))))))

(deftest a-linkless-grant-and-a-grantless-link-both-stay-dark
  (testing "an invited member with NO standing grant: the guest door
            refuses WITHOUT spending the token"
    (let [tok "guest-tok-7207"
          _ (req* *raw* :post "/api/members"
                  {:body {:display "guest-limbo" :actor_type "agent"
                          :bind_token tok}
                   :headers admin})]
      (is (= 404 (:status (req* *gated* :get "/auth/guest"
                                {:query (str "invite=" tok)}))))
      (is (= 200 (:status (req* *gated* :get "/api/-/welcome"
                                {:query (str "invite=" tok)})))
          "the token is unspent — the invite still stands whole"))))

;; ── filter-scoped grants: the leash names a QUERY, not just ids ──────

(deftest a-filter-scoped-grant-admits-the-rows-matching
  (let [mk (fn [title assignee]
             (json (req* *raw* :post "/api/guest_chores"
                         {:body {:title title :assignee assignee}
                          :headers admin})))
        jack1 (mk "Dishes" "jack")
        jack2 (mk "Wipe out drawers" "jack")
        other (mk "Couple stuff" "colton")
        _ (is (every? (comp some? :self) [jack1 jack2 other]))
        gtok "guest-tok-8815"
        member (json (req* *raw* :post "/api/members"
                           {:body {:display "guest-iris" :actor_type "agent"
                                   :bind_token gtok}
                            :headers admin}))
        mid (last (str/split (str (:self member)) #"/"))
        grant (json (req* *raw* :post "/api/grants"
                          {:body {:audience mid
                                  :scope [{:kind "guest_chore"
                                           :actions ["complete"]
                                           :filter {:assignee "jack"}}]
                                  :expires_at (str (.plusSeconds
                                                    (java.time.Instant/now)
                                                    259200))}
                           :headers admin}))
        _ (is (some? (:self grant)) (pr-str grant))
        cookie (-> (req* *gated* :get "/auth/guest"
                         {:query (str "invite=" gtok)})
                   (get-in [:headers "Set-Cookie"]) str
                   (str/split #";") first)
        as-guest (fn [method uri & [opts]]
                   (req* *gated* method uri
                         (update opts :headers merge {"cookie" cookie})))]
    (is (str/starts-with? cookie "waymark_session="))

    (testing "the collection is the filtered story: jack's rows, an
              honest total, nothing else"
      (let [col (json (as-guest :get "/api/guest_chores"))]
        (is (= 2 (get-in col [:data :total])) (pr-str (:data col)))
        (is (every? #(str/includes? (str (:summary %)) "·")
                    (get-in col [:data :items])))))

    (testing "teaser-flagged prose rides the row truncated, ellipsis
              honest — never the whole 8000 chars"
      (let [long-detail (apply str (repeat 60 "scrub then dry "))
            row (json (req* *raw* :post "/api/guest_chores"
                            {:body {:title "Detailed dishes"
                                    :assignee "jack"
                                    :detail long-detail}
                             :headers admin}))
            _ (is (some? (:self row)))
            item (->> (get-in (json (as-guest :get "/api/guest_chores"))
                              [:data :items])
                      (filter #(str/includes? (str (:summary %)) "Detailed"))
                      first)
            teaser (get-in item [:fields :detail])]
        (is (string? teaser))
        (is (= 240 (count teaser)) (str "got " (count teaser)))
        (is (str/ends-with? teaser "…"))))

    (testing "row sight follows the filter, not the kind"
      (is (= 200 (:status (as-guest :get (:self jack1)))))
      (is (= 404 (:status (as-guest :get (:self other))))))

    (testing "the granted action works INSIDE the filter and is the
              same 404 outside it"
      (is (= 200 (:status (as-guest :post (str (:self jack1) "/-/complete")
                                    {:body {}}))))
      (is (= "done" (:state (json (as-guest :get (:self jack1))))))
      (is (= 404 (:status (as-guest :post (str (:self other) "/-/complete")
                                    {:body {}})))))

    (testing "a row minted AFTER the grant lands inside the leash the
              moment it matches — the pool stays covered"
      (let [late (mk "Dishes (next week)" "jack")]
        (is (= 200 (:status (as-guest :get (:self late)))))
        (is (= 4 (get-in (json (as-guest :get "/api/guest_chores"))
                         [:data :total])))))

    (testing "the collection oracle stays closed: the guest's own
              probe filters still answer the grammar honestly"
      (is (= 200 (:status (as-guest :get "/api/guest_chores"
                                    {:query "state=open"})))))))

(deftest a-filter-scope-speaks-the-declared-grammar-or-not-at-all
  (let [try-grant (fn [scope]
                    (req* *raw* :post "/api/grants"
                          {:body {:audience "whoever" :scope scope}
                           :headers admin}))]
    (testing "a filter on an unfilterable field refuses at the door"
      (is (contains? #{409 422}
                     (:status (try-grant [{:kind "guest_chore" :actions []
                                           :filter {:title "Dishes"}}])))))
    (testing "state is never a grant filter"
      (is (contains? #{409 422}
                     (:status (try-grant [{:kind "guest_chore" :actions []
                                           :filter {:state "open"}}])))))
    (testing "two filtered entries on one kind refuse"
      (is (contains? #{409 422}
                     (:status (try-grant [{:kind "guest_chore" :actions []
                                           :filter {:assignee "a"}}
                                          {:kind "guest_chore" :actions []
                                           :filter {:assignee "b"}}])))))
    (testing "the well-formed filter still lands"
      (is (= 201 (:status (try-grant [{:kind "guest_chore" :actions []
                                       :filter {:assignee "jack"}}])))))))

;; ── attenuated delegation: an agent mints only within its leash ──────

(deftest an-agent-mints-only-within-its-leash
  ;; the world this test stands on — its own rows, whatever order the
  ;; runner deals (kaocha randomizes; a sibling's fixtures are not ours)
  (let [_ (is (some? (:self (json (req* *raw* :post "/api/guest_chores"
                                        {:body {:title "Delegated dishes"
                                                :assignee "jack-d"}
                                         :headers admin})))))
        tok "guest-tok-9944"
        member (json (req* *raw* :post "/api/members"
                           {:body {:display "minter" :actor_type "agent"
                                   :bind_token tok}
                            :headers admin}))
        mid (last (str/split (str (:self member)) #"/"))
        kit (json (req* *raw* :post "/api/grants"
                        {:body {:audience mid
                                :scope [{:kind "guest_chore"
                                         :actions ["complete"]
                                         :filter {:assignee "jack-d"}}
                                        {:kind "member" :actions ["create"]}
                                        {:kind "grant" :actions ["create"]}]
                                :expires_at (str (.plusSeconds
                                                  (java.time.Instant/now)
                                                  259200))}
                         :headers admin}))
        _ (is (some? (:self kit)) (pr-str kit))
        cookie (-> (req* *gated* :get "/auth/guest"
                         {:query (str "invite=" tok)})
                   (get-in [:headers "Set-Cookie"]) str
                   (str/split #";") first)
        as-minter (fn [method uri & [opts]]
                    (req* *gated* method uri
                          (update opts :headers merge {"cookie" cookie})))]
    (is (str/starts-with? cookie "waymark_session="))

    (testing "widening refuses: an unfiltered complete is more than held"
      (is (contains? #{409 422}
                     (:status (as-minter :post "/api/grants"
                                {:body {:audience "someone"
                                        :scope [{:kind "guest_chore"
                                                 :actions ["complete"]}]}})))))

    (testing "a kind the minter does not hold refuses"
      (is (contains? #{409 422}
                     (:status (as-minter :post "/api/grants"
                                {:body {:audience "someone"
                                        :scope [{:kind "meal"
                                                 :actions []}]}})))))

    (testing "the attenuated mint lands and its link admits a working,
              filtered guest"
      (let [gtok2 (str "minted-" (System/nanoTime))
            guest (json (as-minter :post "/api/members"
                                   {:body {:display "minted-alice"
                                           :actor_type "agent"
                                           :bind_token gtok2}}))
            _ (is (some? (:self guest)) (pr-str guest))
            gid (last (str/split (str (:self guest)) #"/"))
            minted (as-minter :post "/api/grants"
                              {:body {:audience gid
                                      :scope [{:kind "guest_chore"
                                               :actions ["complete"]
                                               :filter {:assignee "jack-d"}}]
                                      :expires_at (str (.plusSeconds
                                                        (java.time.Instant/now)
                                                        86400))}})]
        (is (= 201 (:status minted)) (pr-str (json minted)))
        (let [c2 (-> (req* *gated* :get "/auth/guest"
                           {:query (str "invite=" gtok2)})
                     (get-in [:headers "Set-Cookie"]) str
                     (str/split #";") first)]
          (is (str/starts-with? c2 "waymark_session="))
          (let [col (json (req* *gated* :get "/api/guest_chores"
                                {:headers {"cookie" c2}}))]
            (is (pos? (get-in col [:data :total])))
            (is (every? #(not (str/includes? (str (:summary %)) "Couple"))
                        (get-in col [:data :items]))
                "the minted guest sees jack's rows, never colton's")))))))

;; ── capability grants: the authority crosses, not the data ──────────

(deftest a-capability-grant-is-law-about-access-not-data
  (testing "an unregistered dotted token refuses at the door"
    (is (contains? #{409 422}
                   (:status (req* *raw* :post "/api/grants"
                                  {:body {:audience "gate-pilot"
                                          :scope [{:kind "telegram.send"
                                                   :actions []}]}
                                   :headers admin})))))

  ;; the registry names the power; the dot is how a scope entry is
  ;; known to mean it
  (let [cap (json (req* *raw* :post "/api/capabilities"
                        {:body {:token "telegram.send"
                                :description "Send a Telegram message via Gate"
                                :enforced_by "gate-mcp"}
                         :headers admin}))]
    (is (= "active" (:state cap)) (pr-str cap))
    (is (contains? #{409 422}
                   (:status (req* *raw* :post "/api/capabilities"
                                  {:body {:token "undotted"
                                          :description "no dot no entry"}
                                   :headers admin})))
        "a token without a dot refuses — the dot IS the convention"))

  (let [grant (json (req* *raw* :post "/api/grants"
                          {:body {:audience "gate-pilot"
                                  :scope [{:kind "telegram.send"
                                           :actions []
                                           :filter {:chat "family"}}]
                                  :expires_at (str (.plusSeconds
                                                    (java.time.Instant/now)
                                                    3600))}
                           :headers admin}))
        gid (last (str/split (str (:self grant)) #"/"))
        as-pilot {"x-waymark-principal" "gate-pilot"
                  "x-waymark-actor-type" "agent"}
        check (fn [headers & [{:keys [principal capability]}]]
                (req* *raw* :get "/api/-/grant-check"
                      {:query (str "grant=" gid
                                   "&principal=" (or principal "gate-pilot")
                                   "&capability=" (or capability
                                                      "telegram.send"))
                       :headers headers}))]
    (is (some? (:self grant)) (pr-str grant))

    (testing "the registry is readable at bootstrap — an agent that
              cannot read what powers exist cannot compose its ask"
      (let [col (json (req* *raw* :get "/api/capabilities"
                            {:headers as-pilot}))]
        (is (pos? (get-in col [:data :total])) (pr-str (:data col)))))

    (testing "an offered grant confers nothing yet"
      (is (false? (:allowed (json (check admin))))))

    (testing "the audience accepts, and introspection answers whole"
      (is (= 200 (:status (req* *raw* :post
                                (str (:self grant) "/-/accept")
                                {:headers as-pilot}))))
      (let [ans (json (check admin))]
        (is (true? (:allowed ans)) (pr-str ans))
        (is (= {:chat "family"} (:constraints ans))
            "the constraint rides the yes, the enforcement point's to interpret")
        (is (string? (:expires_at ans)))))

    (testing "concealment at the introspection door"
      (is (= 401 (:status (req* *gated* :get "/api/-/grant-check"
                                {:query (str "grant=" gid
                                             "&principal=gate-pilot"
                                             "&capability=telegram.send")})))
          "the anonymous get nothing at all")
      (is (true? (:allowed (json (check as-pilot))))
          "the audience may introspect itself")
      (is (false? (:allowed (json (check {"x-waymark-principal" "snoop"
                                          "x-waymark-actor-type" "agent"}))))
          "a FOREIGN agent's answer is the same false as a dead grant")
      (is (false? (:allowed (json (check admin
                                         {:capability "gmail.search"}))))
          "a capability the grant never named is false, nothing more"))

    (testing "revoke severs the external power like any other sight"
      (is (= 200 (:status (req* *raw* :post (str (:self grant) "/-/revoke")
                                {:headers admin}))))
      (is (false? (:allowed (json (check admin))))))

    (testing "a retired capability refuses NEW grants; the registry is
              the vocabulary's clock"
      (let [row-self (-> (json (req* *raw* :get "/api/capabilities"
                                     {:query "token=telegram.send"
                                      :headers admin}))
                         (get-in [:data :items]) first :self)]
        (is (some? row-self))
        (is (= 200 (:status (req* *raw* :post (str row-self "/-/retire")
                                  {:headers admin})))))
      (is (contains? #{409 422}
                     (:status (req* *raw* :post "/api/grants"
                                    {:body {:audience "gate-pilot-2"
                                            :scope [{:kind "telegram.send"
                                                     :actions []}]}
                                     :headers admin})))))))
