(ns waymark10.ui-test
  "Phase 10 acceptance, part three: the generic UI's automated floor —
  the page serves at GET /api/-/ui off the classpath, and it is
  SELF-CONTAINED: no reference to any external host survives review
  (the CSP-shaped promise a generic client page must keep — an
  envelope-driven UI that phones home is not generic, it is a leak).
  The behavioral verification is by hand against dev10, documented
  in docs/waymark10-design.md §10."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]))

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [fx/meal fx/plan]}))]
          (f))
        (finally (pg/close! st))))))

(deftest ui-serves
  (let [resp (*h* {:request-method :get :uri "/api/-/ui" :headers {}})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html"))
    (is (str/includes? (:body resp) "waymark"))))

(deftest ui-lite-serves
  ;; the original phase-10 page, preserved beside the ported client
  (let [resp (*h* {:request-method :get :uri "/api/-/ui-lite" :headers {}})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html"))
    (is (str/includes? (:body resp) "waymark"))
    (is (not= (:body resp)
              (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}})))
        "lite and full are distinct assets")))

(defn- check-page [path]
  (let [body (:body (*h* {:request-method :get :uri path :headers {}}))]
    (testing (str path ": no CDN scripts, stylesheets, fonts, or remote fetches")
      (is (empty? (re-seq #"(?i)(src|href)\s*=\s*[\"']https?://" body))
          "every asset attribute is same-origin")
      (is (empty? (re-seq #"(?i)@import|fonts\.googleapis|cdn\." body)))
      (is (empty? (re-seq #"(?i)fetch\(\s*[\"']https?://" body))
          "every fetch is a relative href off the wire"))
    (testing (str path ": the page consumes the wire, not a baked-in app")
      (is (str/includes? body "/api/.well-known/waymark")
          "discovery drives the nav")
      (is (str/includes? body "/api/-/events")
          "live updates ride the firehose")
      (is (str/includes? body "x-waymark-principal")
          "the dev principal header is the auth seam")
      (is (str/includes? body "Waymark-Acknowledge")
          "the acknowledge protocol is wired")
      (is (str/includes? body "Idempotency-Key"))
      (is (str/includes? body "If-Match")))))

(deftest ui-references-no-external-hosts
  (check-page "/api/-/ui")
  (check-page "/api/-/ui-lite"))

(deftest ui-port-keeps-the-ten-wire
  ;; the ported waymark9 client speaks wire 10, not 9: the grant scope
  ;; selector, the relay/2 draft socket, and the named SSE classes are
  ;; all in the page; the 9-only surfaces are not
  (let [body (:body (*h* {:request-method :get :uri "/api/-/ui" :headers {}}))]
    (is (str/includes? body "X-Waymark-Grant"))
    (is (str/includes? body "/collab"))
    (is (str/includes? body "\"transition\""))
    (is (str/includes? body "\"derivation\""))
    (is (not (str/includes? body "X-Principal-Id"))
        "the waymark9 dev headers do not survive the port")
    (is (not (str/includes? body "wmk_"))
        "no minted bearer-token vocabulary on wire 10")))
