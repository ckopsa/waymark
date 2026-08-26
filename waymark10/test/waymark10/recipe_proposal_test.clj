(ns waymark10.recipe-proposal-test
  "The staged proposal (waymark-0k4): an agent prepares an exact change
  to the feed's order and a person's tap applies it.

  The conformance pack (`:feed/staged-proposals`) walks the same claim
  against whatever an application declared. What belongs HERE is what
  a driver with one world cannot arrange: a CLOCK this test holds, so
  the leash can be watched running out; both target shapes in one run,
  so the built-in case (which is production's own case, where no
  recipe row exists at all) is not left to whatever the suite happened
  to leave behind; and the diff read as sentences rather than as a
  boolean, because the sentences are what a person taps under.

  The in-memory twin hosts it, the same way feed-test does and for the
  same reason: every read here goes through the storage protocol, so
  the twin is the proof that the surface is portable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.feed-recipe :as feed-recipe]
            [waymark10.recipe-proposal :as proposal]
            [waymark10.server.engine :as engine]
            [waymark10.server.feed :as feed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── a world with a clock in it ──────────────────────────────────────

(def ^:private start (Instant/parse "2026-08-25T09:00:00Z"))


(defn- call!
  [eng method uri & {:keys [body headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"x-waymark-principal" "mom"
                                        "x-waymark-actor-type" "human"
                                        "content-type" "application/json"}
                                       headers)}
                body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(def ^:private as-ari
  {"x-waymark-principal" "ari" "x-waymark-actor-type" "agent"})
(def ^:private as-bo
  {"x-waymark-principal" "bo" "x-waymark-actor-type" "agent"})
(def ^:private as-mom
  {"x-waymark-principal" "mom" "x-waymark-actor-type" "human"})

(defn- id-of [self] (last (str/split (str self) #"/")))

;; the composer's leash, minted below beside the grant helper it needs
(declare ari)

(defn- feed-doc [eng] (:doc (call! eng :get "/api/-/feed")))

(defn- current-order [eng] (get-in (feed-doc eng) [:recipe :order]))

(defn- seam-sentence [doc]
  (some #(when (= "seam" (str (:card_id %))) (str (:sentence %)))
        (:cards doc)))

(defn- reseam
  "The house's current order with one word changed — the seam's own
  sentence, which is the one recipe field that appears VERBATIM on a
  card. `recipe.order` is the document's copy of the order in the
  EDITOR's shape, so this is literally what a person (or an agent)
  does: read the order you have, edit one line, hand it back."
  [order sentence]
  (mapv (fn [l] (cond-> l (= "seam" (str (:section l)))
                        (assoc :sentence sentence)))
        order))

(defn- stage!
  [eng order & {:keys [target headers]}]
  (call! eng :post "/api/recipe_proposals"
         :headers (or headers (ari eng))
         :body (cond-> {:proposal "The seam should say what this house says"
                        :label "The school-run morning"
                        :evidence ["/api/fd_errands/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
                        :current_order (current-order eng)
                        :order order}
                 target (assoc :target_id target))))

(defn- verdict!
  [eng pid action & {:keys [headers]}]
  (call! eng :post (str "/api/recipe_proposals/" pid "/-/" (name action))
         :headers (or headers as-mom)))

(defn- guard-of [resp] (some-> (:doc resp) :guard keyword))

(defn- leash!
  "One grant over one kind's named doors, offered to an agent and
  accepted by it → headers that present it.

  An UNLEASHED agent is already answered 404 by the router's default
  deny, which proves nothing about any wall — so every claim below
  about what an agent may not do is made by an agent that was handed
  exactly the leash a careless household might approve. 4yn's own pack
  says this in the same words, one kind over."
  [eng audience kind actions]
  (let [made (call! eng :post "/api/grants"
                    :headers as-mom
                    :body {:audience audience
                           :scope [{:kind (name kind) :actions actions}]})
        gid (id-of (get-in made [:doc :self]))
        hs {"x-waymark-principal" audience "x-waymark-actor-type" "agent"}]
    (call! eng :post (str "/api/grants/" gid "/-/accept") :headers hs)
    (assoc hs "x-waymark-grant" gid)))

(def ^:private leashes
  "One composer's leash per engine, minted on first use. An AGENT
  needs a grant to stage — a proposal is a prepared write, and which
  agents may put one in front of the household is the household's to
  say — while a human is unscoped and needs none, as ever. The leash
  covers `apply` too, so that when the four-eyes wall refuses the
  stager it is the WALL refusing and not concealment."
  (atom {}))

(defn- boot []
  (let [clock (atom start)]
    {:clock clock
     :eng (engine/engine {:storage (memory/storage)
                          :resources []
                          :now-fn (fn [] @clock)})}))

(defn- ari [eng]
  (or (get @leashes eng)
      (let [hs (leash! eng "ari" :recipe_proposal ["create" "apply"])]
        (swap! leashes assoc eng hs)
        hs)))

(defn- newest-transition
  "The last thing that happened to a row — the log is newest-LAST
  (store/transitions' own contract), and this is the whole actor
  proof: `ctx :invoke` hands the inner write the OUTER principal, so a
  recipe changed by an applied proposal carries the member's name
  here, and would carry the composer's or the engine's if the
  cross-write had gone through a system actor."
  [eng kind id]
  (let [st (:storage eng)]
    (last (store/with-tx
            st (fn [tx] (store/transitions
                         st tx {:kind kind :resource-id id} {}))))))

(defn- etag-of [eng kind id]
  (get-in (call! eng :get (str "/api/" kind "/" id)) [:headers "ETag"]))

(defn- revise!
  "A member revising a recipe the way a client does: the row's current
  etag on the wire, because feed_recipe's :revise declares an :edit
  and an edit implies the fence."
  [eng rid order]
  (call! eng :post (str "/api/feed_recipes/" rid "/-/revise")
         :headers (assoc as-mom "if-match" (etag-of eng "feed_recipes" rid))
         :body {:label "The house's own" :order order}))

;; ── the diff, as sentences ──────────────────────────────────────────

(defn- line-index
  "Where a population's line sits in the built-in order — read rather
  than counted, so adding a population above it does not turn this
  test red for a reason that has nothing to do with the diff."
  [population]
  (first (keep-indexed (fn [i e] (when (= population (:population e)) i))
                       (:order feed/default-recipe))))

(deftest the-diff-reads-line-by-line-in-household-words
  (let [was (:order feed/default-recipe)
        at (line-index :insights)
        now (assoc-in (vec was) [at :take] 1)
        lines (feed/order-diff was now)]
    (testing "one changed key, one sentence, and it names the place"
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) (str "Line " (inc at) " ")))
      (is (str/includes? (first lines) "shows 1 card instead of 2 cards")))
    (testing "and it says what the line is FOR, not what its keys are"
      (let [swapped (assoc-in (vec was) [at :population] :proposals)]
        (is (str/includes? (first (feed/order-diff was swapped))
                           "instead of findings an agent published"))))))

(deftest the-diff-says-when-nothing-changes
  (let [order (:order feed/default-recipe)]
    (is (= ["Nothing changes — this is the order already in force, line for line."]
           (feed/order-diff order order)))))

(deftest the-diff-counts-the-lines-when-the-count-moves
  (let [was (:order feed/default-recipe)
        now (vec (butlast was))
        lines (feed/order-diff was now)]
    (is (str/includes? (first lines)
                       (str "goes from " (count was) " lines to "
                            (dec (count was)))))
    (is (some #(str/includes? % "goes — it used to be") lines))))

(defn- seam-line
  "Which line the seam is, in the built-in order — 1-based, the way a
  diff sentence names it."
  []
  (inc (first (keep-indexed (fn [i e] (when (:seam e) i))
                            (:order feed/default-recipe)))))

(deftest the-diff-is-computed-from-the-editors-own-shape
  ;; proposal/diff-of takes the WIRE spelling — the shape recipe.order
  ;; rides the feed document in and the shape the create form takes —
  ;; so the sentence in the row is a function of what was actually
  ;; staged, never of a second conversion nobody can see
  (let [;; the wire spelling has STRING keys on the way out and
        ;; keyword keys on the way back in (wire/read-json
        ;; keywordizes), and the door only ever meets the second — so
        ;; the pure test meets it there too
        wire (walk/keywordize-keys (feed/order-as-written feed/default-recipe))
        changed (reseam wire "Everything the house had, and that is all.")]
    (is (= [(str "Line " (seam-line)
                 ", the caught-up line, reads"
                 " \"Everything the house had, and that is all.\".")]
           (proposal/diff-of wire changed nil nil)))))

(deftest the-contest-is-diffed-beside-the-order
  ;; waymark-8um.3: the formula rides the same staged change, and a
  ;; proposal that touches only the two numbers says the order is
  ;; unchanged rather than saying "nothing changes" beside a sentence
  ;; saying what changes
  (let [wire (walk/keywordize-keys (feed/order-as-written feed/default-recipe))
        now (walk/keywordize-keys (feed/formula-as-written feed/default-recipe))]
    (is (= ["The order itself is unchanged, line for line."
            "A card cools a step after 5 days untouched instead of 3."]
           (proposal/diff-of wire wire now (assoc now :cools_after 5))))
    (is (= ["The order itself is unchanged, line for line."
            (str "The contest turns OFF: nothing below the crown is weighted"
                 " by what anybody has already been shown, and the seed alone"
                 " decides the order.")]
           (proposal/diff-of wire wire now (assoc now :cools_after 0))))
    ;; an absent formula and one spelling the deployment's own numbers
    ;; are the same contest, and neither reads as a change
    (is (= [feed/order-unchanged] (proposal/diff-of wire wire nil now)))))

;; ── staging: the wall stands, and the door beside it opens ──────────

(deftest an-agent-stages-what-it-may-not-write
  (let [{:keys [eng]} (boot)
        words "Everything the house had, and that is all."
        staged (stage! eng (reseam (current-order eng) words))
        pid (id-of (get-in staged [:doc :self]))]
    (testing "the staging door takes an agent"
      (is (= 201 (:status staged))))
    (testing "and the recipe's own door still refuses the same principal
              — HOLDING a recipe-write leash, which is the only version
              of the claim worth making"
      (let [both (merge (ari eng)
                        {"x-waymark-grant"
                         (get (leash! eng "ari" :feed_recipe
                                      ["create" "revise"])
                              "x-waymark-grant")})
            direct (call! eng :post "/api/feed_recipes"
                          :headers both
                          :body {:label "Ari's own"
                                 :scope "household"
                                 :order (reseam (current-order eng) words)})]
        (is (= 409 (:status direct)))
        (is (= :written-by-a-person (guard-of direct)))))
    (testing "the engine wrote the diff — nobody typed it"
      (let [row (:doc (call! eng :get (str "/api/recipe_proposals/" pid)))]
        (is (= [(str "Line " (seam-line) ", the caught-up line, reads "
                     (pr-str words) ".")]
               (get-in row [:data :diff])))
        (is (= "ari" (get-in row [:data :proposed_by])))
        (is (= (str (.plusSeconds start (* 86400 proposal/leash-days)))
               (str (get-in row [:data :expires_at]))))))))

(deftest a-proposal-is-judged-against-the-doors-it-will-use
  (let [{:keys [eng]} (boot)
        order (current-order eng)]
    (testing "an order that will not assemble is refused at STAGING"
      (let [bad (stage! eng (conj (vec order) {:section "seam"
                                               :sentence "Really, caught up."}))]
        (is (= 409 (:status bad)))
        (is (= :the-order-will-assemble (guard-of bad)))))
    (testing "a citation nobody can follow is not a citation"
      (let [uncited (call! eng :post "/api/recipe_proposals"
                           :headers (ari eng)
                           :body {:proposal "It would feel better this way"
                                  :label "A feeling"
                                  :current_order order
                                  :order (reseam order "Nor this.")})]
        (is (= 409 (:status uncited)))
        (is (= :it-cites-what-it-read (guard-of uncited)))))
    (testing "and a built-in staging whose 'today' would not assemble is
              not staged against today"
      (let [wrong (call! eng :post "/api/recipe_proposals"
                         :headers (ari eng)
                         :body {:proposal "Staged against a fiction"
                                :label "A fiction"
                                :evidence ["/api/fd_errands/01H"]
                                :current_order (conj (vec order)
                                                     {:section "seam"
                                                      :sentence "Twice."})
                                :order (reseam order "Nor this.")})]
        (is (= 409 (:status wrong)))
        (is (= :the-staging-is-current (guard-of wrong)))))
    (testing "three waiting is the fridge full"
      (dotimes [n 3]
        (is (= 201 (:status (stage! eng (reseam order (str "Take " n)))))))
      (let [over (stage! eng (reseam order "One too many"))]
        (is (= 409 (:status over)))
        (is (= :staged-changes-are-few (guard-of over)))))))

;; ── the tap IS the write ────────────────────────────────────────────

(deftest a-members-tap-writes-the-order-under-the-members-name
  (let [{:keys [eng]} (boot)
        words "Everything the house had, and that is all."
        before (feed-doc eng)
        staged (stage! eng (reseam (current-order eng) words))
        pid (id-of (get-in staged [:doc :self]))]
    (testing "the house has no order of its own — this is production's case"
      (is (= "built-in" (get-in before [:recipe :source :source]))))

    (testing "the stager cannot answer its own proposal"
      (let [self (verdict! eng pid :apply :headers (ari eng))]
        (is (= 409 (:status self)))
        (is (= :the-proposer-does-not-decide (guard-of self)))))

    (testing "and neither can a second agent, leash and all"
      (let [leashed (leash! eng "bo" :recipe_proposal ["apply" "decline"])
            other (verdict! eng pid :apply :headers leashed)]
        (is (= 409 (:status other)))
        (is (= :a-person-answers (guard-of other)))))

    (testing "a member's one tap lands it"
      (is (= 200 (:status (verdict! eng pid :apply :headers as-mom)))))

    (let [row (:doc (call! eng :get (str "/api/recipe_proposals/" pid)))
          rid (id-of (get-in row [:data :applied_to]))
          after (feed-doc eng)
          t (newest-transition eng :feed_recipe rid)]
      (testing "the citation rides the proposal"
        (is (= "applied" (str (:state row))))
        (is (= "mom" (get-in row [:data :decided_by])))
        (is (some? rid)))
      (testing "…and the order the house reads is the order proposed"
        (is (= words (seam-sentence after)))
        (is (= "household" (get-in after [:recipe :source :source])))
        (is (= rid (str (get-in after [:recipe :source :id])))))
      (testing "THE ACTOR ON THE RECIPE IS THE MEMBER WHO TAPPED"
        (is (= "mom" (get-in t [:actor :id])))
        (is (= "human" (str (get-in t [:actor :type]))))))))

(deftest a-proposal-against-an-existing-row-revises-it-as-the-member
  (let [{:keys [eng]} (boot)
        made (call! eng :post "/api/feed_recipes"
                    :headers as-mom
                    :body {:label "The house's own"
                           :scope "household"
                           :order (reseam (current-order eng) "Caught up.")})
        rid (id-of (get-in made [:doc :self]))
        words "And that is the lot."
        staged (stage! eng (reseam (current-order eng) words) :target rid)
        pid (id-of (get-in staged [:doc :self]))]
    (is (= 201 (:status made)))
    (is (= 201 (:status staged)))
    (is (= 200 (:status (verdict! eng pid :apply :headers as-mom))))
    (let [t (newest-transition eng :feed_recipe rid)]
      (testing "the recipe moved through its OWN revise door"
        (is (= :revise (:action t))))
      (testing "…with the member's name on it, not the stager's and not the engine's"
        (is (= "mom" (get-in t [:actor :id]))))
      (testing "…and the order it wrote is the order the proposal carried"
        (is (= words (seam-sentence (feed-doc eng))))))))

(deftest declining-applies-nothing
  (let [{:keys [eng]} (boot)
        words "Nobody wanted this one."
        staged (stage! eng (reseam (current-order eng) words))
        pid (id-of (get-in staged [:doc :self]))
        was (seam-sentence (feed-doc eng))]
    (is (= 200 (:status (verdict! eng pid :decline :headers as-mom))))
    (is (= was (seam-sentence (feed-doc eng))))
    (is (empty? (get-in (:doc (call! eng :get "/api/feed_recipes"))
                        [:data :items])))
    (testing "and an answered proposal does not answer twice"
      (is (= 409 (:status (verdict! eng pid :apply :headers as-mom)))))))

;; ── the two things that move underneath a staged change ─────────────

(deftest a-proposal-whose-target-moved-refuses-rather-than-writing-over-it
  (let [{:keys [eng]} (boot)
        made (call! eng :post "/api/feed_recipes"
                    :headers as-mom
                    :body {:label "The house's own"
                           :scope "household"
                           :order (reseam (current-order eng) "Caught up.")})
        rid (id-of (get-in made [:doc :self]))
        staged (stage! eng (reseam (current-order eng) "Ari's suggestion.")
                       :target rid)
        pid (id-of (get-in staged [:doc :self]))
        ;; somebody else edits the order in the meantime
        moved (revise! eng rid (reseam (current-order eng) "Moved on."))
        stale (verdict! eng pid :apply :headers as-mom)]
    (is (= 200 (:status moved)))
    (is (= 409 (:status stale)))
    (is (= :the-order-has-not-moved (guard-of stale)))
    (is (str/includes? (str (get-in stale [:doc :detail]))
                       "reads differently now"))
    (testing "and the edit that happened is still what the house reads"
      (is (= "Moved on." (seam-sentence (feed-doc eng)))))))

(deftest a-built-in-proposal-refuses-once-the-house-has-written-its-own
  (let [{:keys [eng]} (boot)
        staged (stage! eng (reseam (current-order eng) "Ari's suggestion."))
        pid (id-of (get-in staged [:doc :self]))]
    (call! eng :post "/api/feed_recipes"
           :headers as-mom
           :body {:label "The house's own"
                  :scope "household"
                  :order (reseam (current-order eng) "Ours now.")})
    (let [stale (verdict! eng pid :apply :headers as-mom)]
      (is (= 409 (:status stale)))
      (is (= :the-order-has-not-moved (guard-of stale)))
      (is (str/includes? (str (get-in stale [:doc :detail]))
                         "written its own order since")))))

;; ── the leash, watched running out ──────────────────────────────────

(deftest the-leash-runs-out-and-a-lapsed-proposal-applies-nothing
  (let [{:keys [eng clock]} (boot)
        staged (stage! eng (reseam (current-order eng) "Too late for this."))
        pid (id-of (get-in staged [:doc :self]))
        was (seam-sentence (feed-doc eng))]
    (testing "a live proposal cannot be expired out of the way"
      (let [early (verdict! eng pid :expire :headers as-mom)]
        (is (= 409 (:status early)))
        (is (= :the-leash-has-run-out (guard-of early)))))

    (testing "…and while it is live the household is asked"
      (is (some #(= "recipe_proposal" (str (:kind %)))
                (:cards (feed-doc eng)))))

    (reset! clock (.plusSeconds start (* 86400 (inc proposal/leash-days))))

    (testing "a week later the tap applies nothing, and says why"
      (let [late (verdict! eng pid :apply :headers as-mom)]
        (is (= 409 (:status late)))
        (is (= :the-leash-has-not-run-out (guard-of late)))
        (is (str/includes? (str (get-in late [:doc :detail])) "lapsed at"))))

    (testing "…the household has stopped being asked"
      (is (not (some #(= "recipe_proposal" (str (:kind %)))
                     (:cards (feed-doc eng))))))

    (testing "…the order never moved"
      (is (= was (seam-sentence (feed-doc eng)))))

    (testing "…and the bookkeeping verb tidies the row"
      (is (= 200 (:status (verdict! eng pid :expire :headers as-mom))))
      (is (= "expired"
             (str (:state (:doc (call! eng :get
                                       (str "/api/recipe_proposals/" pid))))))))))

;; ── the card, as the household meets it ─────────────────────────────

(deftest the-card-carries-the-diff-and-two-taps

  (let [{:keys [eng]} (boot)
        words "Everything the house had, and that is all."
        staged (stage! eng (reseam (current-order eng) words))
        pid (id-of (get-in staged [:doc :self]))
        card (some #(when (= (str "decide/recipe_proposal/" pid)
                             (str (:card_id %))) %)
                   (:cards (feed-doc eng)))]
    (is (some? card))
    (testing "it is a DECIDE card"
      (is (= "decide" (str (:section card)))))
    (testing "its sentence is the diff, and says what it is staged against"
      (is (str/includes? (str (:sentence card)) words))
      (is (str/includes? (str (:sentence card)) "deployment ships with"))
      (is (str/includes? (str (:sentence card)) "1 row behind it")))
    (testing "both answers are under the thumb — neither takes a note"
      (is (= #{"apply" "decline"}
             (set (map (comp name key) (:actions card))))))
    (testing "and the stager is not offered a door it cannot walk through"
      (let [theirs (:doc ((engine/handler eng)
                          {:request-method :get :uri "/api/-/feed"
                           :headers (merge as-ari
                                           {"content-type" "application/json"})}))]
        (is (nil? (some #(when (= (str "decide/recipe_proposal/" pid)
                                  (str (:card_id %))) %)
                        (:cards (some-> theirs)))))))))

;; ── the reader is not what a `defresource` is for ───────────────────

(deftest the-declaration-says-what-it-is
  (let [r proposal/recipe-proposal]
    (is (= :recipe_proposal (:kind r)))
    (is (= #{:applied :declined :expired} (:terminal r)))
    (testing "apply advertises what it touches — feed_recipe's own doors"
      (is (= [{:kind :feed_recipe :action :revise}
              {:kind :feed_recipe :action :create}]
             (get-in r [:actions :apply :touches]))))
    (testing "…and both of those doors take the input this kind prepares"
      (is (some? (get-in feed-recipe/feed-recipe [:actions :revise :input]))))))

;; ── the crown's rank rides the same change (waymark-1uv.5) ──────────
;;
;; Option A of the epic's ruling, proved on the twin: an agent proposes
;; NUMBERS for a declared, readable formula; a person applies; the
;; diff speaks the household's words; the numbers it replaced are on
;; the applied proposal's own row, so the way back is a revise anybody
;; can copy. The live half — the leash minted through the grant door,
;; `crown_rank_says` on the household's own registry, the revert — is
;; workqueue10.rank-tuning-test.

(defn- crown-rank-of-feed [eng]
  (get-in (feed-doc eng) [:recipe :crown_rank]))

(defn- stage-rank!
  "A rank change, staged the way a tuning agent stages one: against the
  order AND the crown the house reads today, citing what it read."
  [eng target crown-rank & {:keys [current headers evidence order]}]
  (call! eng :post "/api/recipe_proposals"
         :headers (or headers (ari eng))
         :body (cond-> {:proposal "Declared values are being passed over for observed ones"
                        :label "Declared first"
                        :evidence (or evidence
                                      ["/api/feed_views/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
                                       "/api/verdict_reasons/01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"])
                        :current_order (current-order eng)
                        :order (or order (current-order eng))
                        :current_crown_rank (or current (crown-rank-of-feed eng))
                        :crown_rank crown-rank}
                 target (assoc :target_id target))))

(deftest the-crowns-rank-is-diffed-beside-the-contest
  (let [wire (walk/keywordize-keys (feed/order-as-written feed/default-recipe))
        now (walk/keywordize-keys (feed/crown-rank-as-written feed/default-recipe))]
    (testing "a proposal that touches only the crown's numbers says the order
              is unchanged, then each moved number in the household's words —
              and the declined line does the multiplication out loud"
      (is (= ["The order itself is unchanged, line for line."
              "In the crown, serving a value this house declared lifts a bundle 12 instead of 10."
              (str "In the crown, each rank of the house's quick word about a line"
                   " of thinking holds a bundle 3 instead of 2 — a never-this line"
                   " of thinking is held 12 instead of 8.")]
             (proposal/diff-of wire wire nil nil
                               now (assoc now :declared 12 :declined 3)))))
    (testing "an absent rank and one spelling the deployment's numbers are the
              same crown, and neither reads as a change"
      (is (= [feed/order-unchanged] (proposal/diff-of wire wire nil nil nil now)))
      (is (= [feed/order-unchanged] (proposal/diff-of wire wire nil nil now nil))))
    (testing "the four-argument spelling still says nothing about the crown"
      (is (= [feed/order-unchanged] (proposal/diff-of wire wire nil nil))))
    (testing "a number the rank grows later renders the day it lands, by its
              wire name — the diff walks the map's keys and never names four"
      (is (= ["In the crown, crown_rank recomposed is 3 instead of 0."]
             (feed/crown-rank-diff nil {:recomposed 3})))
      (is (= ["In the crown, each day left on a bundle's week lifts it 2 instead of 1."
              "In the crown, crown_rank recomposed is 0 instead of 5."]
             (feed/crown-rank-diff {:recomposed 5} {:fresh 2})))
      (is (str/includes? (first (feed/crown-rank-diff
                                 {:recomposed 5}
                                 {:declared 0 :cooled 0 :declined 0 :fresh 0
                                  :early 0 :judged 0 :recomposed 0}))
                         "every one of its 7 numbers is 0")))))

(deftest an-agent-tunes-the-crown-and-a-member-applies-it
  (let [{:keys [eng]} (boot)
        made (call! eng :post "/api/feed_recipes"
                    :headers as-mom
                    :body {:label "The house's own"
                           :scope "household"
                           :order (current-order eng)})
        rid (id-of (get-in made [:doc :self]))
        before (feed-doc eng)
        staged (stage-rank! eng rid {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1})
        pid (id-of (get-in staged [:doc :self]))]
    (is (= 201 (:status made)) (pr-str (:doc made)))
    (is (= 201 (:status staged)) (pr-str (:doc staged)))
    (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
           (get-in before [:recipe :crown_rank])))

    (testing "the card the member reads says the numbers, in household words"
      (let [card (some #(when (= (str "decide/recipe_proposal/" pid)
                                 (str (:card_id %))) %)
                       (:cards (feed-doc eng)))]
        (is (some? card))
        (is (str/includes? (str (:sentence card))
                           "lifts a bundle 12 instead of 10"))
        (is (str/includes? (str (:sentence card))
                           "a never-this line of thinking is held 12 instead of 8"))
        (is (str/includes? (str (:sentence card)) "2 rows behind it"))))

    (testing "a rank the recipe's own door would refuse is refused at staging —
              by the FORM, before any guard: the proposal wears the recipe's
              own crown-rank-schema, so a nonsense number never reaches a wall"
      (let [bad (stage-rank! eng rid {:declared 101})]
        (is (= 422 (:status bad)))))

    (testing "a proposal that misreads today's crown is refused at staging, by
              name — the diff has to describe the world it is read in"
      (let [wrong (stage-rank! eng rid {:declared 12}
                               :current {:declared 99})]
        (is (= 409 (:status wrong)))
        (is (= :the-staging-is-current (guard-of wrong)))
        (is (str/includes? (str (get-in wrong [:doc :detail]))
                           "ranks its crown differently today"))))

    (testing "the member's tap lands the numbers, through the recipe's own door,
              and the very next read is answered by them"
      (is (= 200 (:status (verdict! eng pid :apply :headers as-mom))))
      (let [after (feed-doc eng)
            t (newest-transition eng :feed_recipe rid)]
        (is (= {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}
               (get-in after [:recipe :crown_rank])))
        (is (str/includes? (get-in after [:recipe :crown_rank_says])
                           "lifts a bundle 12"))
        (is (str/includes? (get-in after [:recipe :crown_rank_says])
                           "12 for never this"))
        (is (= (get-in before [:recipe :order]) (get-in after [:recipe :order]))
            "the order is untouched by a proposal that named only the crown")
        (is (= (get-in before [:recipe :formula]) (get-in after [:recipe :formula]))
            "and so is the contest")
        (is (= :revise (:action t)))
        (is (= "mom" (get-in t [:actor :id])))))

    (testing "an order-only proposal staged against the tuned crown carries the
              tuned numbers through — and one that misses them is refused"
      (let [words "Tuned, and caught up."
            blind (call! eng :post "/api/recipe_proposals"
                         :headers (ari eng)
                         :body {:proposal "Just the seam"
                                :label "Just the seam"
                                :evidence ["/api/fd_errands/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"]
                                :target_id rid
                                :current_order (current-order eng)
                                :order (reseam (current-order eng) words)})
            carried (stage-rank! eng rid nil
                                 :order (reseam (current-order eng) words))
            cid (id-of (get-in carried [:doc :self]))]
        (is (= 409 (:status blind)))
        (is (= :the-staging-is-current (guard-of blind)))
        (is (= 201 (:status carried)) (pr-str (:doc carried)))
        (is (= 200 (:status (verdict! eng cid :apply :headers as-mom))))
        (is (= words (seam-sentence (feed-doc eng))))
        (is (= {:declared 12 :cooled 2 :declined 3 :fresh 1 :early 2 :judged 1}
               (crown-rank-of-feed eng)))))

    (testing "a rank proposal whose target's crown moved underneath it refuses,
              by name, rather than writing the stale numbers back"
      (let [second' (stage-rank! eng rid {:declared 20})
            sid (id-of (get-in second' [:doc :self]))
            moved (call! eng :post (str "/api/feed_recipes/" rid "/-/revise")
                         :headers (assoc as-mom "if-match"
                                         (etag-of eng "feed_recipes" rid))
                         :body {:label "The house's own"
                                :order (current-order eng)
                                :crown_rank {:declared 15 :cooled 2
                                             :declined 3 :fresh 1 :early 2 :judged 1}})
            stale (verdict! eng sid :apply :headers as-mom)]
        (is (= 201 (:status second')) (pr-str (:doc second')))
        (is (= 200 (:status moved)) (pr-str (:doc moved)))
        (is (= 409 (:status stale)))
        (is (= :the-order-has-not-moved (guard-of stale)))
        (is (str/includes? (str (get-in stale [:doc :detail]))
                           "ranks its crown differently now"))
        (is (= 15 (:declared (crown-rank-of-feed eng))))))

    (testing "the way back: the numbers an applied proposal replaced are on its
              own row, and a revise with them restores the crown"
      (let [row (:doc (call! eng :get (str "/api/recipe_proposals/" pid)))
            was (get-in row [:data :current_crown_rank])
            back (call! eng :post (str "/api/feed_recipes/" rid "/-/revise")
                        :headers (assoc as-mom "if-match"
                                        (etag-of eng "feed_recipes" rid))
                        :body {:label "The house's own"
                               :order (current-order eng)
                               :crown_rank was})]
        (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1} was))
        (is (= 200 (:status back)) (pr-str (:doc back)))
        (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
               (crown-rank-of-feed eng)))))))
