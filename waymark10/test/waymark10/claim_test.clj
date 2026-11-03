(ns waymark10.claim-test
  "The claim (docs/spec-first-run.md R-4.5 to R-4.7): an engine with no
  members, no solo owner and a keys volume boots unclaimed, prints a
  one-time code, and the first person to enter it becomes owner."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.wire :as wire])
  (:import (java.io ByteArrayInputStream File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermission)))

(defn- keys-dir []
  (str (Files/createTempDirectory "waymark-keys" (make-array FileAttribute 0))))

(defn- boot
  "[engine printed] for a scratch engine over this keys volume."
  [dir & [opts]]
  (let [eng (atom nil)
        out (with-out-str (reset! eng (dev/scratch! [] (merge {:keys-dir dir} opts))))]
    [@eng out]))

(defn- code-of [printed]
  (second (re-find #"claim code: ([0-9a-f]+)" printed)))

(defn- post [h body]
  (h {:request-method :post :uri "/claim"
      :headers {"content-type" "application/json"}
      :body (ByteArrayInputStream. (.getBytes ^String (wire/write-json body) "UTF-8"))}))

(defn- whoami [h who]
  (let [resp (h {:request-method :get :uri "/api/.well-known/waymark"
                 :headers {"x-waymark-principal" who}})]
    [(:status resp) (some-> (:body resp) wire/read-json :principal)]))

(deftest an-unclaimed-engine-serves-only-the-claim
  (let [[eng printed] (boot (keys-dir))
        h (dev/handler eng)
        code (code-of printed)]
    (testing "it prints one claim code of 128 bits"
      (is (some? (:claim eng)))
      (is (= 32 (count code))))
    (testing "the claim page and the well-known door answer"
      (is (= 200 (:status (h {:request-method :get :uri "/claim"}))))
      (is (= 200 (:status (h {:request-method :get :uri "/api/.well-known/waymark"})))))
    (testing "every other route answers 503 naming the claim page"
      (let [resp (h {:request-method :get :uri "/api/-/in-flight"})]
        (is (= 503 (:status resp)))
        (is (= "/claim" (:claim (wire/read-json (:body resp)))))))
    (testing "it binds loopback"
      (is (= "127.0.0.1" (engine/bind-host eng {} {}))))))

(deftest a-right-code-makes-the-owner
  (let [dir (keys-dir)
        seeded (atom nil)
        [eng printed] (boot dir {:on-claim (fn [e owner repo]
                                             (reset! seeded [(:solo-owner e) owner repo]))})
        h (dev/handler eng)
        resp (post h {:code (code-of printed) :member "tim" :repo "tim/widgets"})]
    (is (= 200 (:status resp)))
    (testing "the owner is kept in the keys volume"
      (is (= "tim" (.trim (slurp (File. ^String dir "owner"))))))
    (testing "another uid (localfire's 1500) may read the owner, and not write it"
      (let [perms (Files/getPosixFilePermissions (.toPath (File. ^String dir "owner"))
                                                 (make-array LinkOption 0))]
        (is (contains? perms PosixFilePermission/OTHERS_READ))
        (is (not (contains? perms PosixFilePermission/OTHERS_WRITE)))
        (is (not (contains? perms PosixFilePermission/GROUP_WRITE)))))
    (testing "the first-run seed ran for the owner and the repository"
      (is (= ["tim" "tim" "tim/widgets"] @seeded)))
    (testing "the engine serves its owner alone"
      (let [[status p] (whoami h "tim")]
        (is (= 200 status))
        (is (= #{"manager" "recovery-admin"} (set (:roles p)))))
      (is (= 401 (first (whoami h "mallory"))))
      (is (not= 503 (:status (h {:request-method :get :uri "/api/-/in-flight"
                                 :headers {"x-waymark-principal" "tim"}})))))
    (testing "the code is single use"
      (is (not= 200 (:status (post h {:code (code-of printed) :member "mallory"}))))
      (is (= "tim" (.trim (slurp (File. ^String dir "owner"))))))))

(deftest the-claiming-browser-is-the-owner
  ;; ticket 55748c2f: the page's form claims, and its 303 hands the
  ;; shell the owner's id, so the front page is read as the owner with
  ;; nothing typed; the identity wall is unchanged
  (let [[eng printed] (boot (keys-dir))
        h (dev/handler eng)
        form (str "code=" (code-of printed) "&member=tim&repo=tim%2Fwidgets")
        resp (h {:request-method :post :uri "/claim"
                 :headers {"content-type" "application/x-www-form-urlencoded"}
                 :body (ByteArrayInputStream. (.getBytes form "UTF-8"))})
        cookie (get-in resp [:headers "set-cookie"])
        handed (second (re-find #"^wm10\.claimed=([^;]*)" (str cookie)))]
    (is (= 303 (:status resp)))
    (is (= "/" (get-in resp [:headers "location"])))
    (testing "the 303 carries the owner for the shell"
      (is (= "tim" handed)))
    (testing "what the shell then sends reads the front page as the owner"
      (let [r (h {:request-method :get :uri "/api/-/in-flight"
                  :query-string "holder=me"
                  :headers {"x-waymark-principal" handed}})]
        (is (not (#{401 404 503} (:status r))))))
    (testing "a request naming another principal is still refused"
      (is (= 401 (first (whoami h "mallory")))))))

(deftest a-malformed-repository-is-refused-at-the-claim
  ;; ticket 307a2db7: the claim asks for owner/repo and refuses another
  ;; shape before it judges the code, so the code is not spent
  (let [dir (keys-dir)
        seeded (atom nil)
        [eng printed] (boot dir {:on-claim (fn [_ owner repo] (reset! seeded [owner repo]))})
        h (dev/handler eng)]
    (doseq [bad ["widgets" "tim/" "/widgets" "tim/wid gets" "a/b/c"]]
      (let [resp (post h {:code (code-of printed) :member "tim" :repo bad})]
        (is (= 422 (:status resp)) bad)
        (is (re-find #"owner/repo" (:detail (wire/read-json (:body resp)))) bad)))
    (is (nil? @seeded))
    (is (not (.exists (File. ^String dir "owner"))))
    (testing "the code still claims with a right repository"
      (is (= 200 (:status (post h {:code (code-of printed) :member "tim" :repo "tim/widgets"}))))
      (is (= ["tim" "tim/widgets"] @seeded)))))

(deftest five-wrong-codes-end-the-code
  (let [dir (keys-dir)
        [eng printed] (boot dir)
        h (dev/handler eng)]
    (dotimes [_ 5]
      (is (= 403 (:status (post h {:code "0000" :member "mallory"})))))
    (testing "the right code no longer claims"
      (is (= 403 (:status (post h {:code (code-of printed) :member "tim"}))))
      (is (not (.exists (File. ^String dir "owner")))))
    (testing "a restart prints a new code that claims"
      (let [[eng2 printed2] (boot dir)
            code2 (code-of printed2)]
        (is (some? code2))
        (is (not= (code-of printed) code2))
        (is (= 200 (:status (post (dev/handler eng2) {:code code2 :member "tim"}))))))))

(deftest a-restart-after-the-claim-resolves-the-owner
  (let [dir (keys-dir)
        [eng printed] (boot dir)
        _ (post (dev/handler eng) {:code (code-of printed) :member "tim"})
        [eng2 printed2] (boot dir)
        h (dev/handler eng2)]
    (is (nil? (:claim eng2)))
    (is (nil? (code-of printed2)))
    (is (= "tim" (:solo-owner eng2)))
    (is (= 200 (first (whoami h "tim"))))
    (is (= 401 (first (whoami h "mallory"))))))

(deftest without-a-keys-volume-the-engine-boots-as-before
  (let [eng (dev/scratch! [])]
    (is (nil? (:claim eng)))
    (is (not= 503 (:status ((dev/handler eng) {:request-method :get :uri "/api/-/in-flight"}))))))
