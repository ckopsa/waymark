(ns workqueue10.letters-test
  "The letter kind (waymark-tti.3): addressed notes between
  inhabitants, lived in over the REAL ring handler so the whole
  privacy model runs — wrap-identity's agent-default-deny and
  human-unscoped, and the TWO-PARTY own-surface addition in
  waymark10.server.grants: a letter is yours as its AUTHOR
  (data.owner) OR its RECIPIENT (data.to), and nobody else's.

  The adversarial heart is three-cornered: author and recipient each
  see the row with no grant; a THIRD agent gets 404 on the row, never
  sees it in a collection, and never finds its words in any body —
  concealment, not refusal. Opening is the recipient's act ALONE (not
  the author's, not a curator's), and letters can never be granted.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): every test names its own principals and
  never asserts on collection SIZE, only on ownership. Needs the
  waymark10_test database; WAYMARK10_TEST_DSN overrides.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.letters-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.resources.letters :as letters :refer [letter]]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["letters" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; the enrollment table (waymark10.modules) auto-enrolls
        ;; member, role, grant, approval-request, job — so the
        ;; to-is-a-member guard has a roster and the grant wall has
        ;; both doors
        ;; :probe-reads mirrors the boot production actually runs
        ;; (workqueue10.main/start!, waymark-1pq): the render probe
        ;; carries the read hooks, so a guard that judges against a ROW
        ;; of another kind — opener-is-recipient reading the member the
        ;; address names (waymark-1zq) — answers honestly in the
        ;; envelope instead of being advertised optimistically. A feed
        ;; card's verbs come from that same projection, so without it
        ;; this fixture would judge a surface the house does not serve.
        (let [eng (engine/engine {:storage st :resources [letter]
                                  :probe-reads true})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (dwelling_test's idiom) ───────────────────────────

(defn- agent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

(defn- human-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "human"})

(def ^:private colton (human-headers "colton"))
;; the family CURATOR — the most privileged human in the house, and
;; still not a way to sign someone else's name (letter-author-is-self
;; has no on-behalf branch: waymark-tti.3 L2) nor to open or discard
;; another member's mail (the recipient guards)
(def ^:private colton-admin
  (assoc (human-headers "colton") "x-waymark-roles" "recovery-admin"))

;; a SYSTEM-typed caller: the engine's own actors. gate! lets it past
;; without a member row and wrap-identity leaves it unscoped, so it
;; reads like a human — but it forges no authorship and opens no mail.
(def ^:private sysbot
  {"x-waymark-principal" "sysbot-letters" "x-waymark-actor-type" "system"})

(defn- req
  ([method uri headers] (req method uri nil headers))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- body-str ^String [resp]
  (let [b (:body resp)] (if (string? b) b (slurp b))))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- items [resp] (get-in (json resp) [:data :items]))
(defn- item-id [it] (last (str/split (:self it) #"/")))

;; the to-is-a-member guard demands the recipient be a REAL member.
;; gate! auto-provisions a member on first sight (default members
;; mode), so one request AS the recipient makes it one — the durable
;; state a real inhabitant already lives in.
(defn- ensure-member! [headers]
  (req :get "/api/letters" headers))

(defn- send! [body headers]
  (req :post "/api/letters" body headers))

(defn- open! [lid headers]
  (req :post (str "/api/letters/" lid "/-/open") {} headers))

(defn- welcome [headers] (json (req :get "/api/-/welcome" headers)))

;; ── 1. sending: the author is stamped, never trusted ────────────────

(deftest an-agent-sends-a-letter-and-the-author-is-stamped
  (let [quill (agent-headers "quill-send")
        reed (agent-headers "reed-send")
        _ (ensure-member! reed)
        make (send! {:to "reed-send" :title "For your return"
                     :body "The shelf works; the house delivers."}
                    quill)]
    (testing "the send lands, author stamped from the principal (body omitted it)"
      (is (= 201 (:status make)))
      (is (= "quill-send" (get-in (json make) [:data :owner])))
      (is (= "waiting" (:state (json make)))))
    (testing "naming your OWN id explicitly also lands, stamped the same"
      (let [r (send! {:owner "quill-send" :to "reed-send"
                      :title "Signed" :body "signed by hand"} quill)]
        (is (= 201 (:status r)))
        (is (= "quill-send" (get-in (json r) [:data :owner])))))
    (testing "an agent LYING about the author is refused — agents forge nothing"
      (let [r (send! {:owner "reed-send" :to "reed-send"
                      :title "Forged" :body "not quill's to sign"} quill)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "no forged letter surfaces under the lied-about author"
      (is (not-any? #(= "Forged" (get-in % [:fields :title]))
                    (items (req :get "/api/letters?owner=reed-send" reed)))))))

(deftest a-letter-to-a-non-member-is-refused
  (let [quill (agent-headers "quill-ghost")]
    (testing "a letter addressed to a hollow id is refused at create"
      (let [r (send! {:to "no-such-member-77" :title "For a ghost"
                      :body "nobody arrives at this shelf"} quill)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))))

(deftest a-letter-to-yourself-works
  (let [quill (agent-headers "quill-self")
        _ (ensure-member! quill)
        make (send! {:to "quill-self" :title "To the next me"
                     :body "notes-to-next-self are legitimate"} quill)
        lid (id-of make)]
    (testing "the note-to-next-self lands"
      (is (= 201 (:status make)))
      (is (= "quill-self" (get-in (json make) [:data :owner])))
      (is (= "quill-self" (get-in (json make) [:data :to]))))
    (testing "and its recipient — the same principal — opens it"
      (let [r (open! lid quill)]
        (is (= 200 (:status r)))
        (is (= "opened" (:state (json r))))))))

;; ── 2. two-party sight: author AND recipient, nobody else ───────────

(deftest author-and-recipient-both-see-the-letter
  (let [quill (agent-headers "quill-two")
        reed (agent-headers "reed-two")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-two" :title "Both ends"
                           :body "sender and addressee each see this"}
                          quill))]
    (testing "the RECIPIENT reads the row with no grant"
      (let [r (req :get (str "/api/letters/" lid) reed)]
        (is (= 200 (:status r)))
        (is (= "Both ends" (get-in (json r) [:data :title])))))
    (testing "…and finds it in its own collection"
      (is (some #(= lid (item-id %)) (items (req :get "/api/letters" reed)))))
    (testing "the AUTHOR sees it too — a sent letter stays in the sender's sight"
      (is (= 200 (:status (req :get (str "/api/letters/" lid) quill))))
      (is (some #(= lid (item-id %)) (items (req :get "/api/letters" quill)))))))

;; ── 3. THE KEY TEST: a third agent sees nothing, by construction ────

(deftest a-third-agent-is-locked-out-by-construction
  (let [quill (agent-headers "quill-third")
        reed (agent-headers "reed-third")
        snoop (agent-headers "snoop-third")
        _ (ensure-member! reed)
        _ (ensure-member! snoop)
        lid (id-of (send! {:to "reed-third" :title "Sealed"
                           :body "SEALED WORDS not for other agents"}
                          quill))
        ;; the POSITIVE CONTROL: the snoop holds mail of its OWN, so an
        ;; empty-collection bug (or a letters door that simply broke)
        ;; cannot pass these not-any? assertions by returning nothing
        own (id-of (send! {:to "snoop-third" :title "Its own post"
                           :body "the snoop's own letter, which it MUST see"}
                          snoop))]
    (testing "a THIRD agent, no grant, 404s the row — concealed, not 403"
      (let [r (req :get (str "/api/letters/" lid) snoop)]
        (is (= 404 (:status r)))
        (is (not (str/includes? (body-str r) "SEALED WORDS")))))
    (testing "the third agent's collection surfaces none of it — but IS populated"
      (let [coll (req :get "/api/letters" snoop)]
        (is (some #(= own (item-id %)) (items coll))
            "the snoop sees its own letter — the wall conceals, it does not empty")
        (is (not-any? #(= lid (item-id %)) (items coll)))
        (is (not (str/includes? (body-str coll) "SEALED WORDS")))))
    (testing "…even when it names the parties in a filter"
      (is (not-any? #(= lid (item-id %))
                    (items (req :get "/api/letters?to=reed-third" snoop))))
      (is (not-any? #(= lid (item-id %))
                    (items (req :get "/api/letters?owner=quill-third" snoop)))))))

;; ── 4. opening is the RECIPIENT's act alone ─────────────────────────

(deftest only-the-recipient-can-open
  (let [quill (agent-headers "quill-open")
        reed (agent-headers "reed-open")
        snoop (agent-headers "snoop-open")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-open" :title "For Reed only"
                           :body "opening is the recipient's own act"}
                          quill))]
    (testing "the AUTHOR cannot open — sending is not landing"
      (let [r (open! lid quill)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "a THIRD party cannot even address it — concealed 404"
      (is (= 404 (:status (open! lid snoop)))))
    (testing "a CURATOR human (recovery-admin) is refused too — mail is not curated"
      (let [r (open! lid colton-admin)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "the letter still waits after every refused attempt"
      (is (= "waiting" (:state (json (req :get (str "/api/letters/" lid) reed))))))
    (testing "the RECIPIENT opens it, and the state lands :opened"
      (let [r (open! lid reed)]
        (is (= 200 (:status r)))
        (is (= "opened" (:state (json r))))))))

;; ── 5. the family sees the whole story (humans run unscoped) ────────

(deftest a-human-sees-every-letter
  (let [quill (agent-headers "quill-fam")
        reed (agent-headers "reed-fam")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-fam" :title "Between agents"
                           :body "the family reads the whole story"}
                          quill))]
    (testing "a human reads a letter between two agents — household transparency"
      (let [r (req :get (str "/api/letters/" lid) colton)]
        (is (= 200 (:status r)))
        (is (= "Between agents" (get-in (json r) [:data :title])))))
    (testing "and finds it in the collection"
      (is (some #(= lid (item-id %)) (items (req :get "/api/letters" colton)))))))

(deftest a-human-sends-a-letter-signed-as-itself
  (let [reed (agent-headers "reed-human-send")
        _ (ensure-member! reed)
        make (send! {:to "reed-human-send" :title "From Colton"
                     :body "a person leaves a note without a form field"}
                    colton)]
    (testing "a human omitting :owner is stamped as the author itself"
      (is (= 201 (:status make)))
      (is (= "colton" (get-in (json make) [:data :owner]))))))

;; ── 6. letters can NEVER be granted — the privacy wall ──────────────
;;
;; The same adversarial hole self/journal closed (waymark-4zj.1): a
;; grant (or an ask, which MINTS a grant on approval) whose scope
;; named letters would bypass the two-party own-surface — the grant
;; surface carries no owner/to filter — and expose EVERY inhabitant's
;; mail. scope-omits-private-kinds refuses the scope at BOTH doors.

(deftest letters-cannot-be-granted
  (let [quill (agent-headers "quill-grantwall")]
    (testing "an ask whose scope names letter is refused, no grant minted"
      (let [r (req :post "/api/approval_requests"
                   {:task "read the household's mail"
                    :scope [{:kind "letter" :actions []}]}
                   quill)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "a hand-offered grant whose scope names letter is refused"
      (let [r (req :post "/api/grants"
                   {:audience "snoop-grantwall"
                    :scope [{:kind "letter" :actions []}]}
                   colton)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "letter smuggled beside a real kind still refuses the whole scope"
      (let [r (req :post "/api/approval_requests"
                   {:task "a real kind plus the smuggled mailbag"
                    :scope [{:kind "member" :actions []}
                            {:kind "letter" :actions []}]}
                   quill)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "an ordinary scope not naming letters still works (regression)"
      (is (= 201 (:status (req :post "/api/approval_requests"
                               {:task "read the household roster"
                                :scope [{:kind "member" :actions []}]}
                               quill)))))))

;; ── 7. the welcome shelf: arrival hands the letter back ─────────────

(deftest welcome-hands-the-recipient-its-waiting-letters
  (let [quill (agent-headers "quill-shelf")
        reed (agent-headers "reed-shelf")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-shelf" :title "For your arrival"
                           :body "the welcome carries the shelf"}
                          quill))
        home (:home (welcome reed))]
    (testing "the recipient's welcome carries a :home with the waiting letter"
      (is (some? home))
      (is (some #(= (str "/api/letters/" lid) (:href %))
                (get-in home [:letters :waiting]))))
    (testing "the entry names the sender and rides state + title"
      (let [e (first (filter #(= (str "/api/letters/" lid) (:href %))
                             (get-in home [:letters :waiting])))]
        (is (= "quill-shelf" (:from e)))
        (is (= "For your arrival" (:title e)))
        (is (= "waiting" (:state e)))
        (is (some? (:written_at e)))))
    (testing "the greeting mentions the shelf"
      (is (str/includes? (str (:note home)) "on the shelf")))
    (testing "once opened, the letter moves to the shelf's recent :opened"
      (is (= 200 (:status (open! lid reed))))
      (let [home' (:home (welcome reed))]
        (is (not-any? #(= (str "/api/letters/" lid) (:href %))
                      (get-in home' [:letters :waiting])))
        (is (some #(= (str "/api/letters/" lid) (:href %))
                  (get-in home' [:letters :opened])))))))

(deftest a-humans-welcome-carries-letters-too
  (let [quill (agent-headers "quill-to-human")
        _ (ensure-member! colton)
        lid (id-of (send! {:to "colton" :title "For Colton"
                           :body "humans come home too"} quill))
        home (:home (welcome colton))]
    (testing "the HUMAN's welcome now carries a :home with its letters"
      (is (some? home))
      (is (some #(= (str "/api/letters/" lid) (:href %))
                (get-in home [:letters :waiting]))))
    (testing "the human greeting mentions the waiting mail"
      (is (str/includes? (str (:note home)) "on the shelf")))))

(deftest a-second-agents-welcome-carries-none-of-the-firsts-letters
  (let [quill (agent-headers "quill-shelf-leak")
        reed (agent-headers "reed-shelf-leak")
        snoop (agent-headers "snoop-shelf-leak")
        _ (ensure-member! reed)
        _ (send! {:to "reed-shelf-leak" :title "Private post"
                  :body "SHELF WORDS for reed alone"} quill)
        resp (req :get "/api/-/welcome" snoop)
        home (:home (json resp))]
    (testing "the stranger's welcome body never contains the letter's words"
      (is (not (str/includes? (body-str resp) "SHELF WORDS")))
      (is (not (str/includes? (body-str resp) "Private post"))))
    (testing "no foreign letter rides the stranger's :home"
      (is (not-any? #(= "quill-shelf-leak" (:from %))
                    (concat (get-in home [:letters :waiting])
                            (get-in home [:letters :opened])))))))

;; ── 8. authorship is FIRST-PERSON: no on-behalf door (L2) ───────────
;;
;; The dwelling parity broke here on purpose. A journal owner is a
;; filing label on the family's shared history; a letter owner is an
;; assertion of authorship made TO A SECOND PARTY, rendered on the
;; recipient's shelf as :from with no provenance beside it. So the
;; on-behalf branch is gone for EVERY principal type: nobody puts
;; words in another inhabitant's mouth.

(deftest a-curator-cannot-sign-another-members-name
  (let [quill (agent-headers "quill-onbehalf")
        reed (agent-headers "reed-onbehalf")
        _ (ensure-member! quill)
        _ (ensure-member! reed)
        r (send! {:owner "quill-onbehalf" :to "reed-onbehalf"
                  :title "FORGED BY CURATOR"
                  :body "a recovery-admin writing as quill"}
                 colton-admin)]
    (testing "a recovery-admin human supplying a FOREIGN owner is refused"
      (is (>= (:status r) 400))
      (is (not= 201 (:status r))))
    (testing "and no row landed under the name it tried to borrow"
      (is (not-any? #(= "FORGED BY CURATOR" (get-in % [:fields :title]))
                    (items (req :get "/api/letters?owner=quill-onbehalf"
                                colton))))
      (is (not (str/includes? (body-str (req :get "/api/letters?to=reed-onbehalf"
                                             colton))
                              "FORGED BY CURATOR"))))
    (testing "the same curator signing as ITSELF still sends fine"
      (is (= 201 (:status (send! {:owner "colton" :to "reed-onbehalf"
                                  :title "From Colton, signed"
                                  :body "the curator's own hand"}
                                 colton-admin)))))))

(deftest a-plain-human-cannot-sign-another-members-name
  (let [reed (agent-headers "reed-plainforge")
        _ (ensure-member! reed)]
    (testing "a human WITHOUT recovery-admin forging an owner: the :else deny"
      (let [r (send! {:owner "reed-plainforge" :to "reed-plainforge"
                      :title "PLAIN FORGE"
                      :body "an ordinary human writing as reed"}
                     colton)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "no forged row exists under the borrowed name"
      (is (not-any? #(= "PLAIN FORGE" (get-in % [:fields :title]))
                    (items (req :get "/api/letters?owner=reed-plainforge"
                                colton)))))))

(deftest a-system-actor-forges-nothing-and-opens-nothing
  (let [quill (agent-headers "quill-sys")
        reed (agent-headers "reed-sys")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-sys" :title "For Reed"
                           :body "SYSTEM MUST NOT OPEN THIS"} quill))]
    (testing "a :system principal cannot sign another member's name either"
      (let [r (send! {:owner "quill-sys" :to "reed-sys" :title "SYSTEM FORGE"
                      :body "the engine's own actor, writing as quill"}
                     sysbot)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "…and cannot open mail addressed to somebody else"
      (let [r (open! lid sysbot)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r))))
      (is (= "waiting" (:state (json (req :get (str "/api/letters/" lid) reed))))))
    (testing "…nor discard it"
      (let [r (req :post (str "/api/letters/" lid "/-/discard") {} sysbot)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "its READ posture is the unscoped one it already had — recorded,
              not a letters decision: system, like a human, is family"
      (is (= 200 (:status (req :get (str "/api/letters/" lid) sysbot)))))))

;; ── 9. the recipient spelling IS the delivery identity (L3) ─────────
;;
;; A member that BOUND to a subject has a row id that is NOT its
;; principal id (members.clj bind!), while every delivery path —
;; own-surface row?/ids-of, the open guard, the welcome shelf —
;; matches the PRINCIPAL id. Addressing the row id used to mint mail
;; that sat in nobody's surface: unopenable, undeletable, permanent.

(defn- invite!
  "A token-bearing member create: born :invited with a MINTED row id,
  waiting for whoever presents the token to become its subject."
  [display token]
  (req :post "/api/members"
       {:display display :actor_type "agent" :bind_token token}
       colton-admin))

(deftest a-bound-member-receives-mail-addressed-either-way
  (let [token "letters-bind-token-aaaaaaaa"
        rid (id-of (invite! "Bound Wren" token))
        bound-pid "wren-bound-principal"
        ;; first authenticated sight with the token binds the row:
        ;; data.subject := the principal id, the row id stays its own
        _ (req :get "/api/letters"
               (assoc (agent-headers bound-pid) "x-waymark-invite" token))
        bound (agent-headers bound-pid)
        quill (agent-headers "quill-bound")]
    (testing "the invitation really bound: row id and principal id differ"
      (is (not= rid bound-pid))
      (is (= bound-pid (get-in (json (req :get (str "/api/members/" rid) colton))
                               [:data :subject]))))
    (testing "a letter addressed by ROW id is STAMPED to the delivery identity"
      (let [r (send! {:to rid :title "By row id"
                      :body "addressed the wrong way on purpose"} quill)]
        (is (= 201 (:status r)))
        (is (= bound-pid (get-in (json r) [:data :to]))
            "the stamp resolved the row to the principal that answers to it")
        (let [lid (id-of r)]
          (is (= 200 (:status (req :get (str "/api/letters/" lid) bound))))
          (is (some #(= lid (item-id %))
                    (items (req :get "/api/letters" bound))))
          (is (= 200 (:status (open! lid bound)))))))
    (testing "a letter addressed by PRINCIPAL id lands identically"
      (let [r (send! {:to bound-pid :title "By principal id"
                      :body "addressed the right way"} quill)]
        (is (= 201 (:status r)))
        (is (= bound-pid (get-in (json r) [:data :to])))
        (is (= 200 (:status (open! (id-of r) bound))))))
    (testing "the shelf carries both — the welcome delivers what it stamped"
      (is (some? (:home (welcome bound)))))))

(deftest a-letter-carrying-the-row-id-spelling-still-reaches-its-reader
  ;; PRODUCTION'S OWN SHAPE (waymark-1zq). letters/f5415d68 was written
  ;; to Colton's member ROW id and sat unopened for two days: the feed
  ;; asked for {:to <principal-id>} and nothing else, so mail carrying
  ;; any other accepted spelling was invisible to the person it was
  ;; for while sitting in plain sight on its own row.
  ;;
  ;; The door resolves :to now, so no create can MINT this shape any
  ;; more — which is exactly why the fixture writes it straight into
  ;; the store. The letters already on the shelf are the ones this
  ;; test is about, and a house cannot re-address them: a letter, once
  ;; sent, is sent.
  ;;
  ;; THE READER IS A HUMAN, and that is the production case rather
  ;; than a convenience: an inhabitant reads unscoped, so what is
  ;; being proved is that the FEED's population asks for the row-id
  ;; spelling and that the open guard agrees with it. An AGENT
  ;; addressed the old way is still concealed one layer lower —
  ;; grants/own-ids pushes down `to = pid` and nothing else — which is
  ;; a real gap with a bead of its own (waymark-27j) and not something
  ;; to paper over here.
  (let [token "letters-legacy-token-cccccccc"
        rid (id-of (req :post "/api/members"
                        {:display "Legacy Wren" :actor_type "human"
                         :bind_token token}
                        colton-admin))
        pid "wren-legacy-principal"
        _ (req :get "/api/letters"
               (assoc (human-headers pid) "x-waymark-invite" token))
        bound (human-headers pid)
        lid (str (random-uuid))]
    (is (not= rid pid) "row id and principal id differ, or this proves nothing")
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (store/insert-row! (:storage *eng*) tx :letter
                           {:id lid :state :waiting :version 1
                            :data {:owner "quill-legacy-address" :to rid
                                   :title "The dispatch"
                                   :body "Four things the preview found."}
                            :shape 1 :owner "quill-legacy-address"})))
    (testing "the feed's decide section carries it, addressed the old way"
      (let [doc (json (req :get "/api/-/feed" nil bound))
            card (first (filter #(= "letter" (str (:kind %))) (:cards doc)))]
        (is (some? card)
            (str "the shelf swallowed it: " (pr-str (mapv :card_id (:cards doc)))))
        (is (= "decide" (str (:section card))))
        (is (= "The dispatch" (get-in card [:fields :title])))
        (is (some? (get-in card [:actions :open :href]))
            "and the card's Open is a door, not a decoration")))
    (testing "and it really opens — the reading side and the guard agree"
      (is (= 200 (:status (open! lid bound)))))
    (testing "a stranger is still nobody's recipient"
      (let [l2 (str (random-uuid))]
        (store/with-tx (:storage *eng*)
          (fn [tx]
            (store/insert-row! (:storage *eng*) tx :letter
                               {:id l2 :state :waiting :version 1
                                :data {:owner "quill-legacy-address" :to rid
                                       :title "Also for Wren"
                                       :body "not for the neighbour"}
                                :shape 1 :owner "quill-legacy-address"})))
        (is (not= 200 (:status (open! l2 (human-headers "neighbour-legacy"))))
            "another inhabitant sees the row — the house is transparent —
             and still cannot open somebody else's mail")))))

(deftest a-letter-to-an-unclaimed-invitation-is-refused
  (let [quill (agent-headers "quill-unclaimed")
        rid (id-of (invite! "Never Arrived" "letters-bind-token-bbbbbbbb"))]
    (testing "an :invited row is nobody yet — mail there could never arrive"
      (let [r (send! {:to rid :title "To an empty chair"
                      :body "nobody answers to this row id"} quill)]
        (is (= 422 (:status r)))
        (is (not= 201 (:status r)))))))

;; ── 9b. the legacy roster row heals itself (waymark-tti.10) ─────────
;;
;; An :active member row that PREDATES provision!'s subject stamp — a
;; roster row a human added by hand, a first-sight row minted before
;; the stamp existed — reads here as "no principal answers to this",
;; and the door refuses it. That refusal is the right one; what was
;; missing was a REMEDY, because :bind is :from #{:invited} and no
;; other door writes a subject. The remedy is not prod surgery: the
;; identity gate now stamps what it FINDS (members/heal-subject!), so
;; the row heals the next time its inhabitant signs in — and the SAME
;; address that was refused then delivers.

(defn- legacy-roster-row!
  "The pre-stamp shape, minted straight through the engine because no
  door mints one any more: :active, an explicit id, no :subject."
  [id display]
  (inv/create! *eng* :member {:display display :actor_type "agent"}
               {:principal members/registrar :id id})
  id)

(deftest a-legacy-roster-row-is-deliverable-once-its-inhabitant-arrives
  (let [quill (agent-headers "quill-legacy")
        legacy (legacy-roster-row! "legacy-inhabitant" "Legacy Inhabitant")]
    (testing "before its inhabitant is seen, mail to the row is refused —
              the row carries no subject, so nobody answers to it yet"
      (let [r (send! {:to legacy :title "Too early"
                      :body "the shelf cannot reach an unstamped row"} quill)]
        (is (= 422 (:status r)))
        (is (not= 201 (:status r)))))
    (testing "one authenticated sight heals the row through the gate"
      (ensure-member! (agent-headers legacy))
      (is (= legacy (get-in (json (req :get (str "/api/members/" legacy) colton))
                            [:data :subject]))))
    (testing "and the SAME address now delivers, opens included"
      (let [r (send! {:to legacy :title "Welcome home"
                      :body "the shelf reaches you now"} quill)]
        (is (= 201 (:status r)))
        (is (= legacy (get-in (json r) [:data :to])))
        (is (= 200 (:status (open! (id-of r) (agent-headers legacy)))))))))

(deftest a-phantom-roster-row-stays-undeliverable
  ;; the other population, and it must NOT heal: an engine-minted id no
  ;; principal answers to. gate! resolves to it by neither id nor
  ;; subject — a real sign-in misses it twice and provisions a row of
  ;; its own — so no heal ever reaches it and the door keeps refusing.
  ;; Correct: mail there could never be opened by anyone.
  (let [quill (agent-headers "quill-phantom")
        rid (id-of (req :post "/api/members"
                        {:display "Phantom Roster Row" :actor_type "agent"}
                        colton-admin))]
    (is (= 422 (:status (send! {:to rid :title "To nobody"
                                :body "an id no principal answers to"} quill))))
    (testing "a stranger signing in provisions its OWN row and leaves the
              phantom exactly as it was"
      (ensure-member! (agent-headers "not-the-phantom"))
      (is (nil? (get-in (json (req :get (str "/api/members/" rid) colton))
                        [:data :subject])))
      (is (= 422 (:status (send! {:to rid :title "Still nobody"
                                  :body "and still unopenable"} quill)))))))

;; ── 10. the create door narrates no roster (L4) ─────────────────────
;;
;; Every principal — including an agent that 404s /api/members — could
;; sort real member ids and bound subjects from hollow ones by reading
;; the create door's status. Shape alone cannot close that (a real
;; recipient still answers 201), so the refusal stops NARRATING and
;; the pace stops the sweep.

(deftest the-unknown-recipient-refusal-is-shaped-like-a-schema-refusal
  (let [quill (agent-headers "quill-oracle")
        note {:title "probe" :body "probing the roster"}
        unknown (json (send! (assoc note :to "definitely-not-a-member-9zz") quill))
        malformed (json (send! (assoc note :to "") quill))]
    (testing "an unknown recipient is a 422 schema refusal on :to"
      (is (= 422 (:status (send! (assoc note :to "definitely-not-a-member-9zz")
                                 quill))))
      (is (str/ends-with? (str (:type unknown)) "/schema-invalid"))
      (is (some? (get-in unknown [:errors :to]))))
    (testing "…the SAME problem type and field a malformed :to earns"
      (is (= (:type malformed) (:type unknown)))
      (is (= (:status malformed) (:status unknown)))
      (is (= (:title malformed) (:title unknown))))
    (testing "and it never says whether the id names anybody"
      (let [s (str/lower-case (str unknown))]
        (is (not (str/includes? s "exist")))
        (is (not (str/includes? s "unknown")))
        (is (not (str/includes? s "not found")))
        (is (not (str/includes? s "guard")))))))

(deftest letter-creates-are-paced
  (reset! letters/letter-pace-log {})
  (let [sweeper (agent-headers "sweeper-pace")
        _ (ensure-member! sweeper)
        note (fn [n] {:to "sweeper-pace" :title (str "n" n)
                      :body "pacing the door"})]
    (testing "the household's hour of letters lands"
      (is (every? #(= 201 (:status (send! (note %) sweeper)))
                  (range letters/letter-pace-limit))))
    (testing "one more is refused — the window, not the recipient"
      (let [r (send! (note :over) sweeper)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "a REFUSED recipient still spends a slot — the probe is what
              the pace exists to bound"
      (reset! letters/letter-pace-log {})
      (dotimes [n letters/letter-pace-limit]
        (send! {:to (str "hollow-" n) :title "probe" :body "sweeping"} sweeper))
      (let [r (send! (note :after-sweep) sweeper)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "another principal's window is its own"
      (reset! letters/letter-pace-log {})
      (is (= 201 (:status (send! {:to "sweeper-pace" :title "neighbour"
                                  :body "a different hand entirely"}
                                 (agent-headers "neighbour-pace"))))))
    (reset! letters/letter-pace-log {})))

;; ── 11. the shelf has a floor: :discard (L5) ────────────────────────

(defn- discard! [lid headers]
  (req :post (str "/api/letters/" lid "/-/discard") {} headers))

(deftest only-the-recipient-can-discard
  (let [quill (agent-headers "quill-discard")
        reed (agent-headers "reed-discard")
        snoop (agent-headers "snoop-discard")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-discard" :title "Clearable"
                           :body "the recipient's own broom"} quill))]
    (testing "the AUTHOR cannot clear the shelf it filled"
      (let [r (discard! lid quill)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "a THIRD party cannot even address it — concealed 404"
      (is (= 404 (:status (discard! lid snoop)))))
    (testing "a CURATOR human may not clear someone else's mail"
      (let [r (discard! lid colton-admin)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "the RECIPIENT discards, and the row REMAINS — nothing is deleted"
      (let [r (discard! lid reed)]
        (is (= 200 (:status r)))
        (is (= "discarded" (:state (json r)))))
      (is (= 200 (:status (req :get (str "/api/letters/" lid) reed))))
      (is (= 200 (:status (req :get (str "/api/letters/" lid) quill)))
          "the author still sees what it sent"))
    (testing "…and it leaves the shelf and the waiting count"
      (let [home (:home (welcome reed))]
        (is (not-any? #(= (str "/api/letters/" lid) (:href %))
                      (get-in home [:letters :waiting])))))
    (testing "an OPENED letter can be discarded too"
      (let [l2 (id-of (send! {:to "reed-discard" :title "Read then cleared"
                              :body "opened first"} quill))]
        (is (= 200 (:status (open! l2 reed))))
        (is (= 200 (:status (discard! l2 reed))))
        (is (= "discarded" (:state (json (req :get (str "/api/letters/" l2)
                                              reed)))))))))

(deftest the-welcome-shelf-has-a-ceiling
  (let [quill (agent-headers "quill-flood")
        reed (agent-headers "reed-flood")
        _ (ensure-member! reed)]
    (dotimes [n 14]
      (send! {:to "reed-flood" :title (str "flood " n)
              :body "one of many"} quill))
    (let [home (:home (welcome reed))
          letters (:letters home)]
      (testing "the waiting list is capped, and the remainder is a count"
        (is (= 10 (count (:waiting letters))))
        (is (= 4 (:more letters))))
      (testing "the greeting still counts the WHOLE shelf, not the ten shown"
        (is (str/includes? (str (:note home)) "14 letters wait")))
      (testing "the :all href leads to the rest"
        (is (str/includes? (str (:all letters)) "/api/letters?to="))))))

;; ── 12. the own-ids window holds the FRESH end (L6) ─────────────────
;;
;; own-ids queries a bounded 200 per branch. The stores default to
;; created_at ASC, so past 200 rows a principal's NEWEST mail silently
;; fell out of every listing and total while row GETs still succeeded
;; — listing and row disagreeing about the same row. Seeded through
;; the store (205 HTTP creates would be a pace test, not a window
;; test) with explicit, distinct timestamps, so the ordering under
;; assertion is the store's and not the clock's luck.

(defn- seed-letters!
  "N letters from `owner` to `to`, oldest first, each one minute apart
  — inserted straight into the store and then stamped with an
  explicit created_at, because the ordering IS what this test is
  about. Returns the ids in creation order."
  [n owner to]
  (let [st (:storage *eng*)
        base (.minusSeconds (java.time.Instant/now) (* 60 (inc n)))]
    (store/with-tx st
      (fn [tx]
        (into []
              (map (fn [i]
                     (let [id (str (random-uuid))]
                       (store/insert-row! st tx :letter
                                          {:id id :state :waiting :version 1
                                           :data {:owner owner :to to
                                                  :title (str "seed " i)
                                                  :body "a seeded letter"}
                                           :shape 1 :owner owner})
                       (jdbc/execute! tx ["UPDATE letters SET created_at = ?,
                                           updated_at = ? WHERE id = ?"
                                          (java.sql.Timestamp/from
                                           (.plusSeconds base (* 60 i)))
                                          (java.sql.Timestamp/from
                                           (.plusSeconds base (* 60 i)))
                                          id])
                       id)))
              (range n))))))

(deftest the-own-ids-window-keeps-the-newest
  (let [reed "reed-window"
        quill "quill-window"
        ids (seed-letters! 205 quill reed)
        oldest (first ids)
        newest (last ids)
        h (agent-headers reed)
        desc (items (req :get (str "/api/letters?to=" reed
                                   "&sort=-created_at&page[size]=5")
                         h))
        asc (items (req :get (str "/api/letters?to=" reed
                                  "&sort=created_at&page[size]=5")
                        h))]
    (testing "the NEWEST letter is in the recipient's listing"
      (is (= newest (item-id (first desc)))))
    (testing "the OLDEST five fell out of the window — 205 rows, a 200 window"
      (is (not-any? #(= oldest (item-id %)) asc))
      (is (= (nth ids 5) (item-id (first asc)))))
    (testing "the row GET of a windowed-out letter still succeeds — the window
              bounds the LISTING, never the two-party sight"
      (is (= 200 (:status (req :get (str "/api/letters/" oldest) h)))))
    (testing "the AUTHOR's half of the union holds the fresh end too"
      (let [ah (agent-headers quill)]
        (is (some #(= newest (item-id %))
                  (items (req :get (str "/api/letters?owner=" quill
                                        "&sort=-created_at&page[size]=5")
                              ah))))))))

;; ── 13. the walls, once more, from the other doors ──────────────────

(deftest a-real-grant-on-another-kind-opens-no-letter
  (let [quill (agent-headers "quill-realgrant")
        reed (agent-headers "reed-realgrant")
        snoop-id "snoop-realgrant"
        snoop (agent-headers snoop-id)
        _ (ensure-member! reed)
        _ (ensure-member! snoop)
        lid (id-of (send! {:to "reed-realgrant" :title "Out of scope"
                           :body "GRANTED WORDS the grant never named"}
                          quill))
        own (id-of (send! {:to snoop-id :title "The snoop's own"
                           :body "its own mail, which it must still see"}
                          snoop))
        made (req :post "/api/grants"
                  {:audience snoop-id
                   :scope [{:kind "member" :actions []}]}
                  colton-admin)
        gid (id-of made)
        _ (req :post (str "/api/grants/" gid "/-/accept") {} snoop)
        scoped (assoc snoop "x-waymark-grant" gid)]
    (testing "the grant is REAL — the surface branch, not the else-branch"
      (is (= 201 (:status made)))
      (is (= 200 (:status (req :get "/api/members" scoped)))))
    (testing "…and it confers no sight of anybody's letters"
      (let [r (req :get (str "/api/letters/" lid) scoped)]
        (is (= 404 (:status r)))
        (is (not (str/includes? (body-str r) "GRANTED WORDS"))))
      (let [coll (req :get "/api/letters" scoped)]
        (is (not-any? #(= lid (item-id %)) (items coll)))
        (is (not (str/includes? (body-str coll) "GRANTED WORDS")))
        (is (some #(= own (item-id %)) (items coll))
            "own-surface survives a scoped request — the positive control")))
    (testing "nor any action on one"
      (is (= 404 (:status (open! lid scoped))))
      (is (= 404 (:status (discard! lid scoped)))))))

(deftest no-scope-spelling-reaches-letters
  (let [quill (agent-headers "quill-spelling")]
    (testing "every spelling of the kind is refused at the ask door"
      (doseq [spelling ["letter" "letters" "Letter" "LETTER"]]
        (let [r (req :post "/api/approval_requests"
                     {:task (str "read mail via " spelling)
                      :scope [{:kind spelling :actions []}]}
                     quill)]
          (is (>= (:status r) 400) spelling)
          (is (not= 201 (:status r)) spelling))))
    (testing "and at the hand-offered grant door"
      (doseq [spelling ["letter" "letters" "Letter" "LETTER"]]
        (let [r (req :post "/api/grants"
                     {:audience "snoop-spelling"
                      :scope [{:kind spelling :actions []}]}
                     colton-admin)]
          (is (>= (:status r) 400) spelling)
          (is (not= 201 (:status r)) spelling))))
    (testing "the private-kind guard rides :extend too — the approval
              effect's own mint passes back through it"
      (is (contains? (set (map :name (get-in grants/grant
                                             [:actions :extend :guards])))
                     :scope-omits-private-kinds))
      (is (contains? (set (map :name (:create-guards grants/grant)))
                     :scope-omits-private-kinds))
      (is (contains? (set (map :name (:create-guards grants/approval-request)))
                     :scope-omits-private-kinds)))))

(deftest letters-are-absent-from-every-other-door
  (let [quill (agent-headers "quill-doors")
        reed (agent-headers "reed-doors")
        snoop (agent-headers "snoop-doors")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-doors" :title "Doorless"
                           :body "DOOR WORDS for reed alone"} quill))]
    (testing "the seasons door names no letter to a stranger"
      (let [r (req :get "/api/-/seasons" snoop)]
        (is (= 200 (:status r)))
        (is (not (str/includes? (body-str r) "DOOR WORDS")))
        (is (not (str/includes? (body-str r) "letter")))))
    (testing "the worksheet door does not exist for letters"
      (is (= 404 (:status (req :get "/api/letters/-/worksheet" snoop))))
      (is (= 404 (:status (req :get "/api/letters/-/worksheet" reed)))))
    (testing "bulk open is a 404 for a stranger and names no row"
      (let [r (req :post "/api/letters/-/open" {:ids [lid]} snoop)]
        (is (>= (:status r) 400))
        (is (not (str/includes? (body-str r) "DOOR WORDS")))))
    (testing "the resource event feed hands a stranger nothing either
              (this engine is not started, so the SSE door is a 503
              before it is a 404 — the point is that no words ride out)"
      (let [r (req :get (str "/api/letters/" lid "/-/events") snoop)]
        (is (not= 200 (:status r)))
        (is (not (str/includes? (body-str r) "DOOR WORDS")))))))

;; ── 14. the natural replay, documented (L9) ─────────────────────────

(deftest the-author-cannot-open-what-the-recipient-already-opened
  (let [quill (agent-headers "quill-replay")
        reed (agent-headers "reed-replay")
        _ (ensure-member! reed)
        lid (id-of (send! {:to "reed-replay" :title "Replay"
                           :body "opened once, by its recipient"} quill))]
    (is (= 200 (:status (open! lid reed))))
    (testing "the AUTHOR's :open on an already-opened letter answers 200 —
              the ACTUAL behavior, recorded rather than wished away
              (waymark-tti.3 L9): :open is declared idempotent, so the
              machine natural-replays a row already in the target state
              BEFORE any guard is reached. The author changes nothing
              and learns nothing — the row it is handed back is one it
              already sees as author — so this is documentation, not a
              hole. It is also why a guard is not the fix: the replay
              never reaches one."
      (let [r (open! lid quill)]
        (is (= 200 (:status r)))
        (is (= "opened" (:state (json r))))))
    (testing "a THIRD party's replay is still the concealed 404 — the
              natural replay rides INSIDE the visibility wall"
      (is (= 404 (:status (open! lid (agent-headers "snoop-replay"))))))
    (testing "and an unopened letter still refuses the author outright"
      (let [l2 (id-of (send! {:to "reed-replay" :title "Still waiting"
                              :body "not opened yet"} quill))
            r (open! l2 quill)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))))
