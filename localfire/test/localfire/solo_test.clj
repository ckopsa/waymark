(ns localfire.solo-test
  "A solo localfire (first-run R-5.5): R-12.2's token page with no
  sign-in, the config's routines run under the one stored token, and the
  pull loop asks the engine for nothing until that token is there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [localfire.config :as config]
            [localfire.people :as people]
            [localfire.pull :as pull]
            [localfire.server :as server]
            [localfire.server-test :as w])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.time Duration]))

(def the-key (byte-array (range 32)))
(def claude-token "sk-ant-oat01-THE-SOLO-PERSONS-TOKEN-0123456789")

(defn- base-config [port runs]
  {:port       port
   :public-url (str "http://127.0.0.1:" port)
   :place      (str (w/make-place!))
   :runs-dir   (str runs)
   :mcp        {:url "http://127.0.0.1:9/mcp"}
   :routines   {"pull" {:model "opus"}}})

(defn- world []
  (let [port   (w/free-port)
        runs   (w/tmpdir "lf-solo-runs")
        tokens (w/tmpdir "lf-solo-tokens")
        cfg    (config/normalize
                (assoc (base-config port runs) :solo {:tokens-dir (str tokens)}))
        st     (server/start! {:config  cfg
                               :spawner (w/fake-spawner {:calls (atom [])})
                               :closer  (w/fake-closer (atom []))
                               :sign-in {:key the-key}})]
    (assoc st :base (str "http://127.0.0.1:" port) :tokens tokens)))

(def ^:private http (HttpClient/newHttpClient))

(defn- req [method url form]
  (let [b (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofSeconds 10)))]
    (if (= :post method)
      (-> b
          (.header "content-type" "application/x-www-form-urlencoded")
          (.POST (HttpRequest$BodyPublishers/ofString (people/form-encode (or form {})))))
      (.GET b))
    (let [r (.send http (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r) :body (.body r)})))

(defn- csrf-of [body]
  (second (re-find #"name=\"csrf\" value=\"([^\"]+)\"" (str body))))

(deftest solo-config
  (testing "the flags name where the solo token is kept"
    (is (= {:tokens-dir "/data/tokens"}
           (:solo (config/normalize
                   (assoc (config/pull-config-map ["--engine" "https://e" "--link" "l"
                                                   "--solo-tokens" "/data/tokens"])
                          :place (str (w/make-place!))))))))
  (testing ":oidc and :solo are one or the other"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not both"
                          (config/normalize
                           (assoc (base-config 1 (w/tmpdir "lf-solo-runs"))
                                  :oidc {:issuer "http://127.0.0.1:9/realms/r" :tokens-dir "/tmp/t"}
                                  :solo {:tokens-dir "/tmp/t"}))))))

(deftest solo-token-page
  (let [w (world)]
    (try
      (testing "the page asks for a token, with no sign-in"
        (let [r (req :get (str (:base w) "/me") nil)]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "name=\"token\""))
          (is (not (str/includes? (:body r) "/signout"))))
        (is (= 404 (:status (req :get (str (:base w) "/signin") nil)))))
      (testing "with no token stored a fire is refused, and the pull is not ready"
        (is (= 401 (:status (server/fire! w "pull" (constantly "go")))))
        (is (false? (people/solo-ready? (:people w)))))
      (testing "a form without the page's csrf stores nothing"
        (is (= 403 (:status (req :post (str (:base w) "/me/token") {:token claude-token}))))
        (is (false? (people/solo-ready? (:people w)))))
      (testing "a token entered on the page is sealed, never rendered, and runs the routine"
        (let [csrf  (csrf-of (:body (req :get (str (:base w) "/me") nil)))
              r     (req :post (str (:base w) "/me/token") {:csrf csrf :token claude-token})
              page  (req :get (str (:base w) "/me") nil)
              files (vec (.listFiles ^File (:tokens w)))]
          (is (= 303 (:status r)))
          (is (people/solo-ready? (:people w)))
          (is (= claude-token (people/solo-token (:people w))))
          (is (not (str/includes? (:body page) claude-token)))
          (is (= 1 (count files)))
          (is (not (str/includes? (String. (Files/readAllBytes (.toPath ^File (first files)))
                                           StandardCharsets/ISO_8859_1)
                                  claude-token)))
          (is (= 200 (:status (server/fire! w "pull" (constantly "go")))))))
      (finally (server/stop! w)))))

(defn- until [p]
  (let [end (+ (System/currentTimeMillis) 5000)]
    (while (and (not (p)) (< (System/currentTimeMillis) end))
      (Thread/sleep 5))))

(deftest pull-idles-until-ready
  (let [ready (atom false)
        waits (atom [])
        dir   (w/tmpdir "lf-solo-journal")
        lp    (pull/start! {} {:engine-url "http://127.0.0.1:9" :link-id "l" :routine "pull"
                               :token-env  "LOCALFIRE_SOLO_TEST_UNSET"
                               :journal    (str dir "/journal.edn")}
                           "the-link-token"
                           {:ready?   #(deref ready)
                            :random   (constantly 0.999)
                            :first-ms 10
                            :max-ms   1000
                            :sleep    (fn [ms] (swap! waits conj ms) (Thread/sleep 1))})]
    (try
      (until #(<= 5 (count @waits)))
      (testing "not ready: it waits at the first step and asks the engine nothing"
        (is (<= 5 (count @waits)))
        (is (every? #{10} @waits)))
      (reset! ready true)
      (let [n (count @waits)]
        (until #(some (partial < 10) (drop n @waits)))
        (testing "ready: it polls, and the engine that is not there backs it off"
          (is (some (partial < 10) (drop n @waits)))))
      (finally (pull/stop! lp)))))
