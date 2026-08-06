(ns waymark10.curtain-test
  "The presence curtain (waymark-tti.4), in memory — no database, no
  network: the full Storage twin carries the member rows, the
  presence registry stays process-local (its recorded non-Postgres
  scope), and every assertion is the same meaning the Postgres wire
  tests assert.

  Four surfaces:
  - the member actions' guard (curtain-is-your-own-hand): self by id,
    self by bound subject, the recovery-admin-human valve read from
    the ROW (never the claim), agents/strangers/system refused;
  - suppression at every door: report!/read!/stream-open! publish
    nothing while curtained, a live entry clears within one heartbeat
    of the draw (with a leave frame), snapshot omits, open_curtain
    lets the next beat through, staleness is bounded by the cache TTL
    plus one heartbeat, and a failing curtain lookup SUPPRESSES
    (fail closed);
  - the INTENTS stream, which is presence-of-intent and binds the
    same way: a curtained actor's considering publishes nothing, its
    card is absent from the snapshot, and an opened curtain deals
    again;
  - the invalidation wire: the committed draw/open travels the events
    log, so the refusal and the reopening are immediate — asserted
    with a TTL and a heartbeat far longer than the test, so nothing
    but the wire can explain the result;
  - the widening: self-visible? admits a collection self exactly when
    the visibility's :whole-kind? does — never by sampling row? — and
    :whole-kind? itself is judged per SCOPE ENTRY, through the real
    grants/visibility."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.curtain :as curtain]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.grants :as grants]
            [waymark10.server.intents :as intents]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.presence :as presence]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

(defn- eng!
  "A fresh memory engine — the built-in kinds (member, role, grant …)
  ride full-registry, so nothing app-shaped is needed."
  []
  (engine/engine {:storage (memory/storage) :resources []}))

(defn- member!
  "An ACTIVE member row with a chosen id (the registrar's create,
  the gate!-provision shape)."
  [eng id display actor-type & [data]]
  (:row (inv/create! eng :member
                     (merge {:display display :actor_type actor-type} data)
                     {:principal members/registrar :id id})))

(defn- row-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :member id {}))))

(defn- next-frame
  "presence_test's idiom: consume the subscription until pred matches
  (the frame) or the timeout passes (nil)."
  ([sub pred] (next-frame sub pred 4000))
  ([sub pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [remaining (- deadline (System/currentTimeMillis))]
         (when (pos? remaining)
           (let [f (presence/take-frame sub remaining)]
             (cond
               (nil? f) nil
               (keyword? f) nil
               (pred f) f
               :else (recur)))))))))

(defn- no-frame-for?
  "TRUE when no frame naming pid arrives within window-ms — the
  suppression assertions' shape (byte-level absence has a registry
  spelling too: the frame never enqueues)."
  [sub pid window-ms]
  (nil? (next-frame sub #(= pid (get-in % [:principal :id])) window-ms)))

;; ── the guard: whose hand may touch the curtain ─────────────────────

(deftest the-curtain-is-your-own-hand
  (let [eng (eng!)]
    (inv/create! eng :role {:name "recovery-admin"}
                 {:principal members/registrar})
    (member! eng "spy" "Spy" "agent")
    (member! eng "elena" "Elena" "human")
    (member! eng "norole" "No Role" "human")
    (member! eng "colton" "Colton" "human" {:roles ["recovery-admin"]})
    (member! eng "claims" "Claims Only" "human")

    (testing "the member themself draws — and the no is visible state"
      (inv/invoke! eng :member "spy" :draw_curtain nil {:principal spy})
      (is (true? (get-in (row-of eng "spy") [:data :curtain]))))

    (testing "and opens"
      (inv/invoke! eng :member "spy" :open_curtain nil {:principal spy})
      (is (false? (get-in (row-of eng "spy") [:data :curtain]))))

    (testing "another agent is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! eng :member "spy" :draw_curtain nil
                                {:principal (t/principal
                                             {:id "other" :type :agent})}))))

    (testing "a role-less human is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! eng :member "spy" :draw_curtain nil
                                {:principal (t/principal {:id "norole"})}))))

    (testing "a human whose recovery-admin is ONLY a claim is refused —
              the valve reads the ROW (the reentry-minters precedent)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! eng :member "spy" :draw_curtain nil
                                {:principal (t/principal
                                             {:id "claims"
                                              :roles ["recovery-admin"]})}))))

    (testing "the recovery-admin human (role on the row) may draw and
              open — the household valve"
      (let [colton (t/principal {:id "colton" :roles ["recovery-admin"]})]
        (inv/invoke! eng :member "spy" :draw_curtain nil {:principal colton})
        (is (true? (get-in (row-of eng "spy") [:data :curtain])))
        (inv/invoke! eng :member "spy" :open_curtain nil {:principal colton})
        (is (false? (get-in (row-of eng "spy") [:data :curtain])))))

    (testing "a system principal is refused even wearing the role"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! eng :member "spy" :draw_curtain nil
                                {:principal (t/principal
                                             {:id "sys" :type :system
                                              :roles ["recovery-admin"]})}))))

    (testing "a bound member reaches its own curtain through the
              subject a :bind wrote (gate!'s second resolution)"
      (member! eng "row-x" "Bound" "agent" {:subject "bound-pid"})
      (inv/invoke! eng :member "row-x" :draw_curtain nil
                   {:principal (t/principal {:id "bound-pid" :type :agent})})
      (is (true? (get-in (row-of eng "row-x") [:data :curtain]))))))

;; ── suppression: the three doors, the sweep, the reopening ──────────

(deftest the-curtain-suppresses-every-door
  (let [eng (eng!)
        _ (member! eng "elena" "Elena" "human")
        ;; the cache TTL is its OWN seam here (:curtain-ttl-ms), not a
        ;; shadow of the heartbeat: the staleness bound this test
        ;; asserts should be the one an operator can set
        reg (presence/start! eng {:hb-ms 150 :curtain-ttl-ms 200})
        sub (presence/subscribe reg nil)]
    (try
      (testing "before the draw: the explicit door publishes"
        (presence/report! reg elena "/api/members/elena")
        (is (some? (next-frame sub #(and (= "join" (:event %))
                                         (= "elena" (get-in % [:principal :id])))))))

      (testing "drawing over a LIVE entry: the entry clears within one
                heartbeat and a leave frame is observed"
        (inv/invoke! eng :member "elena" :draw_curtain nil {:principal elena})
        (is (some? (next-frame sub #(and (= "leave" (:event %))
                                         (= "elena" (get-in % [:principal :id])))
                               2000))
            "the sweep's fresh read clears the board — ≤ one heartbeat")
        (is (nil? (get @(:local reg) "elena"))))

      (testing "while curtained, the explicit door stores and publishes
                NOTHING (the call itself still answers normally)"
        (is (nil? (presence/report! reg elena "/api/members/elena")))
        (is (nil? (get @(:local reg) "elena")))
        (is (no-frame-for? sub "elena" 500)))

      (testing "while curtained, the read door stamps nothing"
        (presence/read! reg elena "/api/members/elena")
        (is (nil? (get @(:local reg) "elena")))
        (is (no-frame-for? sub "elena" 400)))

      (testing "while curtained, a stream open registers nothing"
        (presence/stream-open! reg elena "/api/members/elena")
        (is (nil? (get @(:local reg) "elena")))
        (is (no-frame-for? sub "elena" 400))
        ;; and the paired close is harmless
        (presence/stream-closed! reg elena "/api/members/elena"))

      (testing "the snapshot omits the curtained pid"
        (is (empty? (filter #(= "elena" (get-in % [:principal :id]))
                            (presence/snapshot reg (constantly true))))))

      (testing "staleness is bounded: past cache TTL + one heartbeat,
                a beat cannot land at all"
        ;; :curtain-ttl-ms 200 above is the bound; sleep past ttl + hb
        (Thread/sleep 500)
        (presence/report! reg elena "/api/members/elena")
        (is (nil? (get @(:local reg) "elena"))))

      (testing "open_curtain: the next beat publishes again"
        (inv/invoke! eng :member "elena" :open_curtain nil {:principal elena})
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          ;; beat until the cache turns over (≤ ttl) and the join lands
          (loop []
            (presence/report! reg elena "/api/members/elena")
            (or (some? (get @(:local reg) "elena"))
                (when (< (System/currentTimeMillis) deadline)
                  (Thread/sleep 50)
                  (recur)))))
        (is (some? (get @(:local reg) "elena")))
        (is (some? (next-frame sub #(and (= "join" (:event %))
                                         (= "elena" (get-in % [:principal :id])))
                               2000))))
      (finally
        (presence/unsubscribe reg sub)
        (presence/stop! reg)))))

(deftest a-failing-curtain-lookup-suppresses
  ;; fail CLOSED: when the registry cannot know, it does not publish —
  ;; a privacy switch must never fail toward leaking
  (let [reg (presence/start! {:storage nil}
                             {:hb-ms 200
                              :curtained? (fn [_]
                                            (throw (RuntimeException. "store down")))})
        sub (presence/subscribe reg nil)]
    (try
      (is (nil? (presence/report! reg elena "/api/members/elena")))
      (is (nil? (get @(:local reg) "elena")))
      (is (no-frame-for? sub "elena" 400))
      (presence/stream-open! reg elena "/api/members/elena")
      (is (nil? (get @(:local reg) "elena")))
      (finally
        (presence/unsubscribe reg sub)
        (presence/stop! reg)))))

;; ── the intents stream honors the same curtain ──────────────────────

(defn- next-intent
  "curtain_test's frame idiom, on the intents subscription."
  ([sub pred] (next-intent sub pred 2000))
  ([sub pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [remaining (- deadline (System/currentTimeMillis))]
         (when (pos? remaining)
           (let [f (intents/take-frame sub remaining)]
             (cond
               (nil? f) nil
               (keyword? f) nil
               (pred f) f
               :else (recur)))))))))

(deftest the-curtain-suppresses-the-intents-stream
  ;; F1: an intent frame names {principal, self, action} — presence of
  ;; intent — so a curtained principal's dry-run must announce
  ;; nothing while the dry-run itself still runs.
  (let [eng (eng!)
        _ (member! eng "elena" "Elena" "human")
        _ (member! eng "spy" "Spy" "agent")
        ;; :ttl-ms 0 on the curtain — every ask is a fresh row read,
        ;; so this test judges the DOOR, never the cache
        cur (curtain/start! eng {:ttl-ms 0})
        reg (intents/start! eng {:hb-ms 60000 :curtain cur})
        sub (intents/subscribe reg nil)
        consider! (fn [principal]
                    (intents/report! reg principal
                                     {:self "/api/members/elena"
                                      :action "set_display"
                                      :status "considering"}))]
    (try
      (testing "an OPEN principal's considering opens a card"
        (consider! spy)
        (is (some? (next-intent sub #(and (= "open" (:event %))
                                          (= "spy" (get-in % [:principal :id])))))))

      (testing "the draw CLOSES the card already dealt — merged-view
                refuses the curtained actor, belt and braces"
        (inv/invoke! eng :member "spy" :draw_curtain nil {:principal spy})
        (is (nil? (consider! elena)) "elena is open: her card is dealt")
        (is (some? (next-intent sub #(and (= "close" (:event %))
                                          (= "spy" (get-in % [:principal :id])))))))

      (testing "and a fresh consideration publishes NOTHING — nothing
                new stored, nothing on the wire — while still
                answering normally (the dry-run behind it ran)"
        (let [iid "spy:set_display@/api/members/elena"
              before (get-in @(:local reg) [iid :entry :at-ms])]
          (Thread/sleep 5)
          (is (nil? (consider! spy)))
          (is (= before (get-in @(:local reg) [iid :entry :at-ms]))
              "the refused report never restamped the entry")
          (is (nil? (next-intent sub #(and (= "open" (:event %))
                                           (= "spy" (get-in % [:principal :id])))
                                 400))
              "byte-level absent")))

      (testing "the snapshot filters the card already dealt (belt and
                braces over the door's own refusal)"
        (let [ids (map :id (intents/snapshot reg (constantly true)))]
          (is (not-any? #(re-find #"^spy:" %) ids))
          (is (some #(re-find #"^elena:" %) ids)
              "an open actor's card is untouched")))

      (testing "open_curtain: the next consideration is dealt again"
        (inv/invoke! eng :member "spy" :open_curtain nil {:principal spy})
        (consider! spy)
        (is (some? (next-intent sub #(and (= "open" (:event %))
                                          (= "spy" (get-in % [:principal :id])))))))
      (finally
        (intents/unsubscribe reg sub)
        (intents/stop! reg)
        (curtain/stop! cur)))))

;; ── the invalidation wire: no TTL sleep anywhere in this test ───────

(deftest a-committed-draw-invalidates-at-once
  ;; F2b: the curtain's verdict is invalidated by the committed
  ;; transition itself (the events log, every process included), so
  ;; neither the ten-minute cache nor the ten-minute heartbeat below
  ;; can be what clears — or reopens — the board.
  (let [eng (eng!)
        _ (member! eng "elena" "Elena" "human")
        dispatcher (events/dispatcher eng {:poll-ms 50})
        cur (curtain/start! eng {:ttl-ms 600000 :dispatcher dispatcher})
        reg (presence/start! eng {:hb-ms 600000 :curtain cur})
        ireg (intents/start! eng {:hb-ms 600000 :curtain cur})
        sub (presence/subscribe reg nil)
        beat-until (fn [pred]
                     (let [deadline (+ (System/currentTimeMillis) 5000)]
                       (loop []
                         (presence/report! reg elena "/api/members/elena")
                         (or (pred)
                             (when (< (System/currentTimeMillis) deadline)
                               (Thread/sleep 50)
                               (recur))))))]
    (try
      (testing "a beat lands and the verdict (open) is now cached"
        (presence/report! reg elena "/api/members/elena")
        (is (some? (next-frame sub #(and (= "join" (:event %))
                                         (= "elena" (get-in % [:principal :id])))))))

      (testing "the committed draw evicts the live entry — the leave
                frame arrives without waiting on any clock"
        (inv/invoke! eng :member "elena" :draw_curtain nil {:principal elena})
        (is (some? (next-frame sub #(and (= "leave" (:event %))
                                         (= "elena" (get-in % [:principal :id])))
                               5000)))
        (is (nil? (get @(:local reg) "elena"))))

      (testing "and the very next beat is refused — the cached OPEN
                verdict was dropped, not waited out"
        (presence/report! reg elena "/api/members/elena")
        (is (nil? (get @(:local reg) "elena")))
        (is (nil? (next-frame sub #(= "elena" (get-in % [:principal :id]))
                              300))))

      (testing "the intents surface shares that one invalidated cache"
        (is (nil? (intents/report! ireg elena {:self "/api/members/elena"
                                               :action "set_display"
                                               :status "considering"})))
        (is (empty? @(:local ireg))))

      (testing "open_curtain: the next beats publish again, long
                before the cached TRUE could have expired"
        (inv/invoke! eng :member "elena" :open_curtain nil {:principal elena})
        (beat-until #(some? (get @(:local reg) "elena")))
        (is (some? (get @(:local reg) "elena")))
        (is (some? (next-frame sub #(and (= "join" (:event %))
                                         (= "elena" (get-in % [:principal :id])))
                               2000))))
      (finally
        (presence/unsubscribe reg sub)
        (intents/stop! ireg)
        (presence/stop! reg)
        (curtain/stop! cur)
        (events/stop! dispatcher)))))

;; ── whole-kind sight is per ENTRY (through the real grants) ─────────

(deftest whole-kind-sight-is-a-per-entry-question
  ;; F6: surface-of absorbs :ids and :filters INDEPENDENTLY, so a
  ;; scope holding one ids-narrowed entry beside one filter-narrowed
  ;; entry used to read as sight of the whole kind — neither entry
  ;; ever conferred it.
  (let [eng (eng!)
        _ (member! eng "m1" "One" "human")
        agent (fn [id] (t/principal {:id id :type :agent :display id}))
        grant! (fn [audience scope]
                 (let [gid (get-in (inv/create! eng :grant
                                                {:audience audience
                                                 :scope scope}
                                                {:principal elena})
                                   [:row :id])]
                   (inv/invoke! eng :grant gid :accept nil
                                {:principal (agent audience)})
                   gid))
        whole (grant! "seer" [{:kind "member" :actions []}])
        mixed (grant! "half" [{:kind "member" :ids ["m1"] :actions []}
                              {:kind "member" :filter {:actor_type "human"}
                               :actions []}])
        vis-of (fn [gid pid] (grants/visibility eng gid (agent pid)))]
    (testing "one entry with neither :ids nor :filter IS the whole kind"
      (let [vis (vis-of whole "seer")]
        (is (true? ((:whole-kind? vis) :member)))
        (is (true? ((presence/self-visible? eng vis) "/api/members")))))
    (testing "an ids-narrowed entry BESIDE a filter-narrowed one is
              NOT whole-kind sight — each entry is judged whole"
      (let [vis (vis-of mixed "half")]
        (is (false? ((:whole-kind? vis) :member)))
        (is (false? ((presence/self-visible? eng vis) "/api/members"))
            "and the collection frame stays concealed")
        (is (true? ((:row? vis) "member" "m1"))
            "row-level sight is untouched — the ids entry still admits m1")))
    (testing "a dead grant confers no kind-level sight at all"
      (let [vis (vis-of "no-such-grant" "half")]
        (is (false? ((:whole-kind? vis) :member)))))))

;; ── the widening: collection selves for whole-kind sight ────────────

(deftest collection-selves-follow-whole-kind-sight
  (let [eng (eng!)   ; real rdefs: member → "members", grant → "grants"
        vis {:row? (fn [k id] (and (= :member k) (= "m1" id)))
             :whole-kind? (fn [k] (= :member k))}
        visible? (presence/self-visible? eng vis)]
    (testing "a collection self shows iff the grant sees the WHOLE kind"
      (is (true? (visible? "/api/members")))
      (is (false? (visible? "/api/grants"))))
    (testing "row-level behavior is unchanged"
      (is (true? (visible? "/api/members/m1")))
      (is (false? (visible? "/api/members/m2"))))
    (testing "door selves, trailing slashes, queries and non-/api/
              selves stay concealed from scoped viewers"
      (is (false? (visible? "/api/-/events")))
      (is (false? (visible? "/api/-")))
      (is (false? (visible? "/api/members/")))
      (is (false? (visible? "/api/members?state=active")))
      (is (false? (visible? "/somewhere/else"))))
    (testing "a visibility WITHOUT :whole-kind? (an older caller)
              conceals collections — fail toward concealment"
      (let [old (presence/self-visible? eng {:row? (constantly true)})]
        (is (false? (old "/api/members")))
        (is (true? (old "/api/members/m1")))))
    (testing "an unscoped viewer (nil visibility) still sees all"
      (let [all (presence/self-visible? eng nil)]
        (is (true? (all "/api/members")))
        (is (true? (all "/api/-/events")))))))
