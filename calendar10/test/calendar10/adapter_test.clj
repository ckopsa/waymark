(ns calendar10.adapter-test
  "The adapter against a real HTTP server speaking canned API v3 —
  a JDK HttpServer on a loopback port, so every byte of the wire
  (paths, the bearer, If-Match, pagination, status mapping) is
  exercised without Google and without a network. Then the same
  protocol over the scriptable fake, which is what offline dev, the
  declaration gate and stage 2's tests will actually run on."
  (:require [calendar10.source :as src]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

;; ── a loopback Google ───────────────────────────────────────────────

(defn- start-server!
  "handler: request-map → [status body]. body may be a map (sent as
  JSON) or a string (sent raw — how a proxy's plain-text 502 gets
  simulated)."
  [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        requests (atom [])]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [uri (.getRequestURI exchange)
               raw (slurp (.getRequestBody exchange))
               headers (.getRequestHeaders exchange)
               req {:method (.getRequestMethod exchange)
                    :path (.getPath uri)
                    :query (str (.getQuery uri))
                    :body (when (seq raw) (wire/read-json raw))
                    :if-match (.getFirst headers "If-Match")
                    :authorization (.getFirst headers "Authorization")}
               _ (swap! requests conj req)
               [status body] (handler req)
               payload (cond (nil? body) ""
                             (string? body) body
                             :else (wire/write-json body))
               bytes (.getBytes ^String payload StandardCharsets/UTF_8)]
           (.add (.getResponseHeaders exchange) "content-type" "application/json")
           (if (zero? (alength bytes))
             (.sendResponseHeaders exchange status -1)
             (do (.sendResponseHeaders exchange status (alength bytes))
                 (with-open [os (.getResponseBody exchange)]
                   (.write os bytes))))
           (.close exchange)))))
    (.start server)
    {:server server
     :requests requests
     :base (str "http://127.0.0.1:" (.getPort (.getAddress server))
                "/calendar/v3")}))

(defn- with-server
  "handler → (fn [calendar requests] …), with the server stopped after.
  A plain function rather than a macro on purpose: the binding vector
  a macro would take reads no better and costs the linter its ability
  to see these names."
  [handler f]
  (let [{:keys [server requests base]} (start-server! handler)]
    (try
      (f (src/google-calendar {:token-fn (constantly "ya29.test")
                               :calendars {"family" "primary"}
                               :zone "America/Denver"
                               :base base})
         requests)
      (finally (.stop ^HttpServer server 0)))))

(def recital
  {:id "6bl1c9k" :etag "\"318116\"" :summary "Piano recital"
   :start {:dateTime "2026-08-01T18:30:00-06:00"}
   :end {:dateTime "2026-08-01T20:00:00-06:00"}})

(def picnic
  {:id "picnicday" :etag "\"318117\"" :summary "Family picnic"
   :start {:date "2026-08-08"} :end {:date "2026-08-10"}
   :transparency "transparent"})

(def dropped
  {:id "gonesoon" :etag "\"318118\"" :status "cancelled"
   :start {:date "2026-08-11"} :end {:date "2026-08-12"}})

(defn- listing [& items] [200 {:items (vec items)}])

;; ── discovery ───────────────────────────────────────────────────────

(deftest discovery-tags-what-it-finds
  (with-server
    (fn [_] (listing recital picnic dropped))
    (fn [cal requests]
      (is (= ["family:6bl1c9k" "family:picnicday"] (mirror/discover cal))
          "cancelled events are not minted — a deletion is not a discovery")
      (let [{:keys [path query authorization]} (first @requests)]
        (is (= "/calendar/v3/calendars/primary/events" path))
        (is (= "Bearer ya29.test" authorization)
            "the credential rides every call")
        (testing "the query asks Google to expand recurrence and show deletions"
          (is (re-find #"singleEvents=true" query))
          (is (re-find #"showDeleted=true" query))
          (is (re-find #"timeMin=" query))
          (is (re-find #"timeMax=" query)))))))

(deftest discovery-follows-the-pages
  ;; a truncated feed would read as a calendar-wide deletion — the one
  ;; failure mode here that silently destroys rows
  (let [calls (atom 0)]
    (with-server
      (fn [_]
        (if (= 1 (swap! calls inc))
          [200 {:items [recital] :nextPageToken "page2"}]
          [200 {:items [picnic]}]))
      (fn [cal requests]
        (is (= ["family:6bl1c9k" "family:picnicday"] (mirror/discover cal)))
        (is (= 2 (count @requests)))
        (is (re-find #"pageToken=page2" (:query (second @requests))))))))

;; ── reads ───────────────────────────────────────────────────────────

(deftest a-single-pull
  (with-server
    (fn [_] [200 recital])
    (fn [cal requests]
      (let [[doc etag] (mirror/pull cal "family:6bl1c9k")]
        (is (= "Piano recital" (:title doc)))
        (is (= "2026-08-01" (:date doc)))
        (is (= "\"318116\"" etag) "Google's own etag, not a content hash"))
      (is (= "/calendar/v3/calendars/primary/events/6bl1c9k"
             (:path (first @requests)))))))

(deftest a-cancelled-event-reads-as-gone
  (with-server
    (fn [_] [200 dropped])
    (fn [cal _]
      (is (thrown-with-msg? Exception #"cancelled"
                            (mirror/pull cal "family:gonesoon"))))))

(deftest a-404-is-gone-not-an-outage
  (with-server
    (fn [_] [404 {:error {:message "Not Found"}}])
    (fn [cal _]
      (let [thrown (try (mirror/pull cal "family:nope")
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= 404 (:status (ex-data thrown))))))))

(deftest pull-many-is-one-fetch-per-calendar
  (with-server
    (fn [_] (listing recital picnic dropped))
    (fn [cal requests]
      (let [got (mirror/pull-many cal ["family:6bl1c9k" "family:picnicday"
                                       "family:gonesoon" "family:notinwindow"])]
        (is (= 1 (count @requests))
            "the window fetch answers the whole batch — not one call per id")
        (is (= "Piano recital" (:title (first (get got "family:6bl1c9k"))))
            "a live event comes back as [document etag]")
        (is (= :gone (get got "family:gonesoon"))
            "a cancelled event inside the window is an OBSERVED deletion")
        (is (not (contains? got "family:notinwindow"))
            "an id the window does not carry is simply absent — ambiguous by
             protocol, so stored truth keeps serving")))))

;; ── writes ──────────────────────────────────────────────────────────

(deftest a-push-rides-if-match
  (let [patched (atom nil)]
    (with-server
      (fn [{:keys [method body]}]
        (case method
          "GET" [200 recital]
          "PATCH" (do (reset! patched body)
                      [200 (assoc recital :etag "\"318999\"")])))
      (fn [cal requests]
        (is (= "\"318999\""
               (mirror/push cal "family:6bl1c9k"
                            {:title "Piano recital (moved)" :all_day false
                             :starts_at "2026-08-02T01:30:00Z"
                             :ends_at "2026-08-02T03:00:00Z"
                             :kind "blocking"})))
        (let [patch-req (first (filter #(= "PATCH" (:method %)) @requests))]
          (is (= "\"318116\"" (:if-match patch-req))
              "the etag just read — a family member editing in between fails
               the push instead of losing their edit")
          (is (= "Piano recital (moved)" (:summary @patched)))
          (is (= "opaque" (:transparency @patched))))))))

(deftest a-push-refuses-a-recurring-series
  (with-server
    (fn [_] [200 (assoc recital :recurrence ["RRULE:FREQ=WEEKLY"])])
    (fn [cal _]
      (is (thrown-with-msg? Exception #"recurring SERIES"
                            (mirror/push cal "family:6bl1c9k"
                                         {:title "x" :all_day false
                                          :starts_at "2026-08-02T01:30:00Z"}))
          "editing the series would move every occurrence — refuse by name"))))

(deftest an-instance-of-a-series-still-pushes
  (with-server
    (fn [{:keys [method]}]
      (if (= "GET" method)
        [200 (assoc recital :recurringEventId "series1")]
        [200 (assoc recital :etag "\"319000\"")]))
    (fn [cal _]
      (is (= "\"319000\""
             (mirror/push cal "family:6bl1c9k"
                          {:title "just this Tuesday" :all_day false
                           :starts_at "2026-08-02T01:30:00Z"}))
          "a per-instance id already means 'this occurrence'"))))

(deftest a-push-onto-a-deleted-event-refuses
  (with-server
    (fn [_] [200 dropped])
    (fn [cal _]
      (is (thrown-with-msg? Exception #"family removed"
                            (mirror/push cal "family:gonesoon"
                                         {:title "x" :all_day true
                                          :date "2026-08-11"}))))))

(deftest a-create-mints-the-identity
  (with-server
    (fn [{:keys [method]}]
      (is (= "POST" method))
      [200 {:id "born1" :etag "\"400\"" :summary "Dinner"
            :start {:date "2026-08-20"} :end {:date "2026-08-21"}}])
    (fn [cal requests]
      (is (= ["family:born1" "\"400\""]
             (mirror/push-create cal {:title "Dinner" :all_day true
                                      :date "2026-08-20" :end_date "2026-08-20"
                                      :kind "note"})))
      (is (= "/calendar/v3/calendars/primary/events" (:path (first @requests))))
      (is (= {:date "2026-08-21"} (:end (:body (first @requests))))
          "the inclusive end goes out exclusive"))))

(deftest a-create-needs-a-start
  (with-server
    (fn [_] [200 {}])
    (fn [cal requests]
      (is (thrown-with-msg? Exception #"all-day calendar event needs a date"
                            (mirror/push-create cal {:title "x" :all_day true})))
      (is (empty? @requests)
          "refused here, so the authority never sees a malformed write"))))

;; ── the failure the mirror has to read correctly ────────────────────

(deftest a-proxys-plain-text-502-is-an-unreachable-feed
  ;; waymark-t6s in its original shape: parse-before-status turned this
  ;; into a raw parse exception that killed the discovery loop
  (with-server
    (fn [_] [502 "Bad Gateway"])
    (fn [cal _]
      (let [thrown (try (mirror/discover cal)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown))
        (is (= 502 (:status (ex-data thrown))))))))

;; ── the scriptable twin keeps the same promises ─────────────────────

(deftest the-fake-honours-the-protocol
  (let [fake (src/fake-calendar)]
    (src/seed! fake "family:a" {:title "Recital" :date "2026-08-01"})
    (src/seed! fake "family:b" {:title "Picnic" :date "2026-08-08"})

    (is (= #{"family:a" "family:b"} (set (mirror/discover fake))))

    (testing "pull-many is ONE call, so a test can tell the eager batch
              apart from N first reads"
      (mirror/pull-many fake ["family:a" "family:b"])
      (is (= 1 (src/pulls fake))))

    (testing "a cancelled event is gone to the batch and absent to discovery"
      (src/cancel! fake "family:b")
      (is (= :gone (get (mirror/pull-many fake ["family:b"]) "family:b")))
      (is (= ["family:a"] (mirror/discover fake)))
      (is (thrown-with-msg? Exception #"not on the calendar"
                            (mirror/pull fake "family:b"))))

    (testing "a push lands and moves the etag"
      (let [[_ before] (mirror/pull fake "family:a")
            etag (mirror/push fake "family:a" {:title "Recital (moved)"
                                               :all_day true
                                               :date "2026-08-02"})]
        (is (not= before etag))
        (is (= "Recital (moved)" (:title (src/stored fake "family:a"))))))

    (testing "a create is born with an identity the authority minted"
      (let [[x etag] (mirror/push-create fake {:title "Dinner" :all_day true
                                               :date "2026-08-20"})]
        (is (string? etag))
        (is (= "Dinner" (:title (src/stored fake x))))
        (is (some #{x} (mirror/discover fake)))))

    (testing "the seams script"
      (src/fail-pushes! fake "the event changed under our push")
      (is (thrown-with-msg? Exception #"changed under our push"
                            (mirror/push fake "family:a" {:title "x"
                                                          :all_day true
                                                          :date "2026-08-02"})))
      (src/fail-pushes! fake false)
      (src/down! fake true)
      (is (thrown-with-msg? Exception #"unreachable" (mirror/discover fake))))))
