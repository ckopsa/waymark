(ns workqueue10.substitute-test
  "The stand-in's bar (docs/spec-seat.md § 8, acceptance case 5): a
  SUBSTITUTE sitter reads the seat's memory — :self, :journal,
  :letter — and does not write it.

  The reason is continuity, not capability. The memory is the seat's
  voice across sessions, and a stand-in that wrote it would leave the
  next full sitter inheriting somebody else's words. So the bar is a
  guard (grants/not-a-substitute) and never a scope entry: these three
  kinds are private own-surface kinds no scope can name, which is why
  the fact the guard reads — `substitute` — rides the visibility
  BESIDE the leash rather than inside it.

  What this proves, and what it deliberately does not: every read is
  served, the three writes are refused with the spec's own sentence,
  and the SAME agent holding a FULL seat grant writes all three. The
  bar is the flag on the grant, not the kind and not the hand.

  No database — waymark10.dev/scratch! is the whole world, the way the
  framework's own seat suite runs.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.substitute-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.wire :as wire]
            [workqueue10.resources.dwelling :refer [journal self]]
            [workqueue10.resources.letters :refer [letter]]))

;; ── the world ───────────────────────────────────────────────────────

(defn- world []
  (let [eng (dev/scratch! [self journal letter])]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> {:request-method method :uri path :headers (or headers {})}
          query (assoc :query-string query)
          body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- agent-headers [id & [grant]]
  (cond-> {"x-waymark-principal" id "x-waymark-actor-type" "agent"}
    grant (assoc "x-waymark-grant" grant)))

;; ── the office, and the two hands that sit in it ────────────────────

(def ^:private seat-name "memory-keeper")

(defn- open-seat! [h]
  (let [made (req h :post "/api/seats"
                  {:headers colton
                   :body {:name seat-name
                          :charter "Keep the house's inner record in one voice."
                          ;; a scope over a kind that is NOT the memory:
                          ;; self, journal and letter can never be named
                          ;; in a scope (they are private own-surface
                          ;; kinds), which is the whole reason the bar
                          ;; below has to be a guard
                          :scope [{:kind "member" :actions []}]
                          :standing_ttl_seconds 604800
                          :cadence_seconds 3600
                          :budget_usd_per_week 5
                          :sitting_budget_tokens 60000}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- sit!
  "One agent's grant in the seat — `substitute` says which hand."
  [h who substitute?]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers (agent-headers who)
                    :body (cond-> {:task "Sit in the seat and keep its record."
                                   :seat seat-name}
                            substitute? (assoc :substitute true))})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers colton})]
    (assert (= 200 (:status approved)) (pr-str (json approved)))
    (get-in (json approved) [:data :grant_id])))

(def ^:private bar-sentence
  "The sentence R-8.2 pins, byte for byte — the refusal is the whole
  point of the wall, so the test reads it rather than a guard name
  alone."
  "A substitute reads the seat's memory and does not write it. The seat's own sitter writes here.")

(defn- self-body [display]
  {:display display
   :about "A steady hand at the house's inner work."
   :working_notes "Mid-flight: the seat's own record."})

;; ── case 5 · the substitute reads the memory and does not write it ──

(deftest a-substitute-reads-the-seats-memory-and-does-not-write-it
  (let [{:keys [h]} (world)
        _ (open-seat! h)
        ;; the household's roster: a letter's recipient must be
        ;; somebody the house actually has, and first sight provisions
        _ (req h :get "/api/journals" {:headers colton})
        stand-in (sit! h "vera" true)
        vera (agent-headers "vera" stand-in)
        ;; the self exists before the bar is tested: `self/create` is
        ;; not barred — a stand-in may have a profile of its own; what
        ;; it may not do is EDIT the record it inherited
        made (req h :post "/api/selves" {:headers (agent-headers "vera")
                                         :body (self-body "Vera")})
        sid (id-of made)]
    (is (= 201 (:status made)) (pr-str (json made)))

    (testing "every READ is served (R-8.3)"
      (is (= 200 (:status (req h :get (str "/api/selves/" sid) {:headers vera}))))
      (is (= 200 (:status (req h :get "/api/journals" {:headers vera}))))
      (is (= 200 (:status (req h :get "/api/letters" {:headers vera})))))

    (testing "self/update is refused, in the spec's own words"
      (let [etag (get-in (json (req h :get (str "/api/selves/" sid)
                                    {:headers vera}))
                         [:meta :etag])
            refused (req h :post (str "/api/selves/" sid "/-/update")
                         {:headers (assoc vera "if-match" etag)
                          :body {:display "Vera, edited"
                                 :about "Rewritten in another voice."}})]
        (is (= 409 (:status refused)))
        (is (= "not-a-substitute" (:guard (json refused))))
        (is (= bar-sentence (:detail (json refused))))))

    (testing "journal/create is refused"
      (let [refused (req h :post "/api/journals"
                         {:headers vera
                          :body {:title "A day in somebody else's chair"
                                 :body "The entry a stand-in does not write."}})]
        (is (= 409 (:status refused)))
        (is (= "not-a-substitute" (:guard (json refused))))
        (is (= bar-sentence (:detail (json refused))))))

    (testing "letter/create is refused"
      (let [refused (req h :post "/api/letters"
                         {:headers vera
                          :body {:to "colton"
                                 :title "Standing in"
                                 :body "A note the stand-in does not send."}})]
        (is (= 409 (:status refused)))
        (is (= "not-a-substitute" (:guard (json refused))))
        (is (= bar-sentence (:detail (json refused))))))

    (testing "and the SAME agent, without the leash, is not a substitute:
              the bar reads the grant, never the hand"
      (let [etag (get-in (json (req h :get (str "/api/selves/" sid)
                                    {:headers (agent-headers "vera")}))
                         [:meta :etag])]
        (is (= 200 (:status (req h :post (str "/api/selves/" sid "/-/update")
                                 {:headers (assoc (agent-headers "vera")
                                                  "if-match" etag)
                                  :body {:display "Vera"
                                         :about "Edited, unleashed."}}))))))))

(deftest the-seats-own-sitter-writes-the-memory
  (let [{:keys [h]} (world)
        _ (open-seat! h)
        _ (req h :get "/api/journals" {:headers colton})
        full (sit! h "ari" false)
        ari (agent-headers "ari" full)
        made (req h :post "/api/selves" {:headers ari :body (self-body "Ari")})
        sid (id-of made)]
    (is (= 201 (:status made)) (pr-str (json made)))

    (testing "the full sitter edits its own self"
      (let [etag (get-in (json (req h :get (str "/api/selves/" sid)
                                    {:headers ari}))
                         [:meta :etag])]
        (is (= 200 (:status (req h :post (str "/api/selves/" sid "/-/update")
                                 {:headers (assoc ari "if-match" etag)
                                  :body {:display "Ari"
                                         :about "The record, kept in one voice."}}))))))

    (testing "…writes the journal"
      (is (= 201 (:status (req h :post "/api/journals"
                               {:headers ari
                                :body {:title "A day at the office"
                                       :body "What the seat decided today."}})))))

    (testing "…and sends a letter"
      (is (= 201 (:status (req h :post "/api/letters"
                               {:headers ari
                                :body {:to "colton"
                                       :title "This week's record"
                                       :body "What the seat has been deciding."}})))))))

;; ── a connector sitting in the seat reads and writes the seat's journal

(defn- journal-ids [h headers]
  (into #{} (map #(last (str/split (str (:self %)) #"/")))
        (get-in (json (req h :get "/api/journals" {:headers headers}))
                [:data :items])))

(deftest a-sitter-reads-and-writes-the-seats-journal
  (let [{:keys [h]} (world)
        seat-id (open-seat! h)
        _ (req h :get "/api/journals" {:headers colton})
        ;; the seat's own fired sitting: its principal IS the seat
        fired (agent-headers (str "seat:" seat-id))
        left (req h :post "/api/journals"
                  {:headers fired
                   :body {:title "Where the last sitting stopped"
                          :body "Pick up the queue at the third row."}})
        left-id (id-of left)
        full (sit! h "connector-ari" false)
        ari (agent-headers "connector-ari" full)]
    (is (= 201 (:status left)) (pr-str (json left)))

    (testing "the connector reads the journal the seat's last sitting left"
      (is (= 200 (:status (req h :get (str "/api/journals/" left-id) {:headers ari}))))
      (is (contains? (journal-ids h ari) left-id)))

    (testing "a journal the connector writes is the seat's, read by the next sitting"
      (let [made (req h :post "/api/journals"
                      {:headers ari
                       :body {:title "Left for the next sitter"
                              :body "The connector stopped here."}})
            jid (id-of made)]
        (is (= 201 (:status made)) (pr-str (json made)))
        (is (= (str "seat:" seat-id) (get-in (json made) [:data :owner])))
        (is (= 200 (:status (req h :get (str "/api/journals/" jid) {:headers fired}))))
        (is (contains? (journal-ids h fired) jid))))

    (testing "a substitute reads the seat's journal and cannot amend it"
      (let [stand-in (sit! h "connector-vera" true)
            vera (agent-headers "connector-vera" stand-in)
            got (req h :get (str "/api/journals/" left-id) {:headers vera})
            etag (get-in (json got) [:meta :etag])]
        (is (= 200 (:status got)))
        (is (contains? (journal-ids h vera) left-id))
        (is (= 409 (:status (req h :post (str "/api/journals/" left-id "/-/amend")
                                 {:headers (assoc vera "if-match" etag)
                                  :body {:title "Where the last sitting stopped"
                                         :body "A stand-in's words."}}))))))

    (testing "after the grant is revoked, nothing of the seat's journal shows"
      (let [revoked (req h :post (str "/api/grants/" full "/-/revoke")
                         {:headers colton :body {}})]
        (is (= 200 (:status revoked)) (pr-str (json revoked)))
        (is (= 404 (:status (req h :get (str "/api/journals/" left-id) {:headers ari}))))
        (is (not (contains? (journal-ids h ari) left-id)))))))

;; ── a connector sitting in the seat reads and sends the seat's letters

(defn- letter-ids [h headers]
  (into #{} (map #(last (str/split (str (:self %)) #"/")))
        (get-in (json (req h :get "/api/letters" {:headers headers}))
                [:data :items])))

(deftest a-sitter-reads-and-sends-the-seats-letters
  (let [{:keys [h]} (world)
        seat-id (open-seat! h)
        seat-addr (str "seat:" seat-id)
        _ (req h :get "/api/journals" {:headers colton})
        ;; the seat's own fired sitting: first sight puts the seat on
        ;; the roster, so a letter can be addressed to it
        fired (agent-headers seat-addr)
        _ (req h :get "/api/letters" {:headers fired})
        to-seat (req h :post "/api/letters"
                     {:headers colton
                      :body {:to seat-addr
                             :title "For the office"
                             :body "Whoever sits here next, read this."}})
        to-id (id-of to-seat)
        stand-in-to (req h :post "/api/letters"
                         {:headers colton
                          :body {:to seat-addr
                                 :title "For the office, again"
                                 :body "A second note for the seat."}})
        stand-in-to-id (id-of stand-in-to)
        full (sit! h "connector-ari" false)
        ari (agent-headers "connector-ari" full)]
    (is (= 201 (:status to-seat)) (pr-str (json to-seat)))
    (is (= seat-addr (get-in (json to-seat) [:data :to])))
    (is (= 201 (:status stand-in-to)) (pr-str (json stand-in-to)))

    (testing "the connector lists and opens the letter addressed to the seat"
      (is (= 200 (:status (req h :get (str "/api/letters/" to-id) {:headers ari}))))
      (is (contains? (letter-ids h ari) to-id))
      (let [opened (req h :post (str "/api/letters/" to-id "/-/open")
                        {:headers ari :body {}})]
        (is (= 200 (:status opened)) (pr-str (json opened)))))

    (testing "a letter the connector sends is authored by the seat"
      (let [sent (req h :post "/api/letters"
                      {:headers ari
                       :body {:to "colton"
                              :title "From the office"
                              :body "The seat's word, sent by its sitter."}})
            sid (id-of sent)]
        (is (= 201 (:status sent)) (pr-str (json sent)))
        (is (= seat-addr (get-in (json sent) [:data :owner])))
        (is (contains? (letter-ids h fired) sid))
        (is (contains? (letter-ids h ari) sid))))

    (testing "a substitute reads the seat's letters and does not open them"
      (let [stand-in (sit! h "connector-vera" true)
            vera (agent-headers "connector-vera" stand-in)]
        (is (= 200 (:status (req h :get (str "/api/letters/" stand-in-to-id)
                                 {:headers vera}))))
        (is (contains? (letter-ids h vera) stand-in-to-id))
        (is (= 409 (:status (req h :post (str "/api/letters/" stand-in-to-id "/-/open")
                                 {:headers vera :body {}}))))))

    (testing "after the grant is revoked, nothing of the seat's mail shows"
      (let [revoked (req h :post (str "/api/grants/" full "/-/revoke")
                         {:headers colton :body {}})]
        (is (= 200 (:status revoked)) (pr-str (json revoked)))
        (is (= 404 (:status (req h :get (str "/api/letters/" to-id) {:headers ari}))))
        (is (not (contains? (letter-ids h ari) to-id)))))))
