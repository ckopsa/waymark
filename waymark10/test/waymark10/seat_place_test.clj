(ns waymark10.seat-place-test
  "The mayor's place (docs/spec-first-run.md R-5.6): GET
  /api/seats/{id}/-/place offers a fresh key and answers a tar of
  CLAUDE.md, which holds it, and inbox-feed.sh. The test unpacks it
  with the real `tar`, sits with the key it finds there, and looks for
  the key in every row and transition the store holds.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler — mcp_sit_test's shape, re-spelled here."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyPairGenerator)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "place-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "place-key" :alg "RS256" :use "sig")]})

(defn- bearer [claims]
  {"authorization"
   (str "Bearer "
        (jwt/sign (merge {:iss issuer :aud audience
                          :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                         claims)
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "place-key"}}))})

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- open-seat!
  "A person's seat over the fixture's meal kind, with no key yet."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-place-5" :display "Place 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "mayor"
                     :charter "Keep the town."
                     :scope [{:kind "meal" :actions ["accept"]}]
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    seat))

(defn- place [h seat headers]
  (h {:request-method :get
      :uri (str "/api/seats/" (:id seat) "/-/place")
      :headers headers}))

(defn- unpack!
  "The answered tar through the real `tar`, into a fresh directory —
  the mayor card's `| tar x`."
  ^File [^bytes body]
  (let [dir (.toFile (Files/createTempDirectory "seat-place" (make-array FileAttribute 0)))
        out (sh/sh "tar" "xf" "-" :in body :dir dir)]
    (is (zero? (:exit out)) (:err out))
    dir))

(defn- key-in [^File dir]
  (second (re-find #"key:\s+(\S+)" (slurp (io/file dir "CLAUDE.md")))))

(defn- sit! [h k]
  (let [init (rpc h (bearer colton) "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {} :clientInfo {:name "mayor" :version "0"}})
        sid (get-in init [:headers "Mcp-Session-Id"])
        resp (rpc h (assoc (bearer colton) "mcp-session-id" sid)
                  "tools/call" {:name "waymark_sit" :arguments {:key k}})]
    (get-in (wire/read-json (:body resp)) [:result])))

(deftest the-place-is-a-tar-whose-key-sits-and-is-kept-nowhere-else
  (let [eng (fresh-engine)
        h (engine/handler eng)
        seat (open-seat! eng)
        resp (place h seat (bearer colton))]
    (is (= 200 (:status resp)) (pr-str (:body resp)))
    (is (= "application/x-tar" (get-in resp [:headers "Content-Type"])))
    (let [dir (unpack! (:body resp))
          feed (io/file dir "inbox-feed.sh")
          k (key-in dir)]
      (testing "the tar holds both files, and the script is the repository's"
        (is (.isFile (io/file dir "CLAUDE.md")))
        (is (.isFile feed))
        (is (.canExecute feed))
        (is (= (vec (seats/place-feed)) (vec (Files/readAllBytes (.toPath feed))))))
      (testing "CLAUDE.md carries a key, and the key sits as the seat"
        (is (string? k))
        (let [result (sit! h k)
              doc (wire/read-json (str (get-in result [:content 0 :text])))]
          (is (not (:isError result)) (pr-str result))
          (is (= "mayor" (:seat doc)))
          (is (some? (:sitting doc)))))
      (testing "the key is in no row, log or transition"
        (is (not (str/includes? (pr-str @(:state (:storage eng))) k))))
      (testing "a second place is a fresh key, and the first no longer sits"
        (let [k2 (key-in (unpack! (:body (place h seat (bearer colton)))))]
          (is (string? k2))
          (is (not= k k2))
          (is (:isError (sit! h k))))))))

(deftest the-card-line-on-a-solo-engine-gets-the-tar
  ;; R-5.6: the mayor card's `curl` carries the solo identity headers,
  ;; and nothing else, on the engine's own machine.
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [fx/meal]
                            :solo-owner "colton"})
        h (engine/handler eng)
        seat (open-seat! eng)
        resp (place h seat {"x-waymark-principal" "colton"
                            "x-waymark-actor-type" "human"})]
    (is (= 200 (:status resp)) (pr-str (:body resp)))
    (let [dir (unpack! (:body resp))]
      (is (.isFile (io/file dir "CLAUDE.md")))
      (is (.isFile (io/file dir "inbox-feed.sh")))
      (is (string? (key-in dir)))
      (testing ".mcp.json points Claude Code at the engine as the owner"
        (let [mcp-json (wire/read-json (slurp (io/file dir ".mcp.json")))
              server (get-in mcp-json [:mcpServers :Waymark])]
          (is (= "http" (:type server)))
          (is (= (str seats/place-origin "/api/-/mcp") (:url server)))
          (is (= {:X-Waymark-Principal "colton" :X-Waymark-Actor-Type "human"}
                 (:headers server))))))
    (testing "and with no headers the solo engine still answers no tar"
      (is (not= 200 (:status (place h seat {})))))))

(deftest the-anonymous-are-told-the-place-does-not-exist
  (let [eng (fresh-engine)
        h (engine/handler eng)
        seat (open-seat! eng)]
    (is (= 404 (:status (place h seat {}))))
    (is (nil? (get-in (seat-routes/seat-row eng (:id seat)) [:data :sitter_key_hash]))
        "and no key was offered")))
