(ns waymark10.org-boot-sit-test
  "An org booted from orgs/ on disk, as WAYMARK10_ORG boots one, and its
  seeded mayor sat through waymark_sit (ticket a39b9396). mcp_sit_test
  covers the sit with a seat named like an org's mayor; this boots the
  seed itself, so an org seed that leaves its mayor unsittable fails.

  The org is found through `:orgs-dir`, not the deps: `org/boot!` puts
  its directory on the classpath as the image's boot does. The seed's
  `org-mayor` template is factorykit's, so factorykit's resources go on
  the same way, through `org/with-classpath`. Memory storage, a
  locally-minted RSA keypair as the IdP's, the real handler: the
  connector door as mcp_sit_test stands it up."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.org :as org]
            [waymark10.server.scenes :as scenes]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the connector door, as mcp_sit_test stands it up ────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "org-boot-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "org-key" :alg "RS256" :use "sig")]})

(defn- bearer [claims]
  {"authorization"
   (str "Bearer "
        (jwt/sign (merge {:iss issuer :aud audience
                          :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                         claims)
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "org-key"}}))})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

;; ── an org, booted from orgs/ ───────────────────────────────────────

(def ^:private orgs-dir
  "The repo's orgs/, from waymark10/, where the suite runs."
  "../orgs")

(def ^:private kit-dir
  "factorykit, whose resources hold the seat templates a seed names."
  "../factorykit")

(defn- boot-org!
  "A fresh engine serving the org `org` found under orgs/, its seed
  loaded with `org/boot!` through `:orgs-dir`, each param a stand-in, on
  a house that already holds an active model. → [eng model]."
  [org]
  (org/with-classpath
    [(io/file kit-dir)]
    (fn []
      (let [eng (engine/engine {:storage (memory/storage)
                                :resources (org/resources org orgs-dir)
                                :oidc {:issuer issuer :audience audience :jwks jwks
                                       :app-url "https://app.test/"
                                       :delegate-clients {"connector" "Claude"}}})
            model (:row (inv/create! eng :model
                                     {:name "claude-house-5" :display "House 5"
                                      :vendor "anthropic" :tier "strong"
                                      :price_input_per_mtok 3M
                                      :price_output_per_mtok 15M
                                      :price_cache_read_per_mtok 0.3M
                                      :price_cache_write_per_mtok 3.75M}
                                     {:principal (t/principal {:id "house" :display "The House"})}))
            params (into {} (map (fn [k] [k (str "test-" (name k))]))
                         (seed/param-names (org/read-seed org orgs-dir)))]
        (org/boot! eng org {:orgs-dir orgs-dir :params params})
        [eng model]))))

(defn- seat-named [eng nm]
  (let [st (:storage eng)]
    (first (filter #(= nm (get-in % [:data :name]))
                   (store/with-tx st #(store/query-rows st % :seat {} {}))))))

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "bWF5b3Ita2V5LWZvci10aGUtb3JnLWJvb3Q")

(deftest landscape-rehearsals-seeded-mayor-sits
  (let [[eng model] (boot-org! "landscape_rehearsal")
        seed (org/read-seed "landscape_rehearsal" orgs-dir)
        owner-id (get-in seed [:cast :owner :id])
        owner (t/principal {:id owner-id :display "The Owner"})
        mayor (seat-named eng "landscape_rehearsal-mayor")]
    (testing "the seed made its mayor, held for the house's model"
      (is (some? mayor))
      (is (= [(str (:id model))] (mapv str (get-in mayor [:data :held_for])))))
    (testing "its owner offers it a key, and a session sits it with that key"
      (schedules/ensure-schedule! eng mayor)
      (inv/invoke! eng :seat (:id mayor) :offer_key {:key a-key}
                   {:principal owner})
      (let [h (engine/handler eng)
            as-owner (bearer {:sub owner-id :azp "connector" :name "The Owner"})
            init (rpc h as-owner "initialize"
                      {:protocolVersion mcp/protocol-version
                       :capabilities {} :clientInfo {:name "routine" :version "0"}})
            sid (get-in init [:headers "Mcp-Session-Id"])
            resp (rpc h (assoc as-owner "mcp-session-id" sid) "tools/call"
                      {:name "waymark_sit"
                       :arguments {:key a-key :seat "landscape_rehearsal-mayor"
                                   :session "run-1"}})
            r (:result (wire/read-json (:body resp)))
            sat (wire/read-json (text-of r))]
        (is (some? sid))
        (is (false? (:isError r)) (text-of r))
        (is (some? (:sitting sat)) (text-of r))
        (testing "and the scene's sitting open resolves to that sitting"
          (is (= {"self" (str "/api/sittings/" (:sitting sat))}
                 (scenes/open-as eng {:sitting {:of "landscape_rehearsal-mayor"
                                                :which "latest"}}
                                 (constantly true)))))))))
