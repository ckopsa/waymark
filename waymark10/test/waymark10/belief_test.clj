(ns waymark10.belief-test
  "THE UPDATER'S ARITHMETIC, checked by hand (waymark-bug, built to
  docs/spec-hypotheses.md § 'The updater — three deterministic rules').

  No database, no engine, no HTTP: `waymark10.belief` is a pure
  function of (table, prior, atoms, now), which is the row's own
  promise said as a namespace — delete every posterior in the store
  and one pass rebuilds them identically. So the whole of the belief
  layer's arithmetic is provable the way `scripts/movements-fixture.sh`
  proves the reading's: with the log-odds written out in the comments
  and a program pointed at them.

  THE NUMBERS BELOW ARE THE SPEC'S OWN TABLE, spelled here rather than
  read off `feed/default-evidence-lr`, and deliberately: a test that
  read the table it is checking would go green when somebody edited
  the table, which is the one change that most needs a person to look.

  ── THE FIXTURE ──────────────────────────────────────────────────────

  now = 2026-08-30T00:00:00Z. All six atoms are about one belief with
  a prior of 0.1 (logit 0.1 = ln(1/9) = -2.197225).

    A1  costly_action, cost high        ep A, 6 days old
    A2  unprompted_mention              ep A, 6 days old
    A3  unprompted_mention              ep A, 6 days old   ← the pair
    A4  declined_invite                 ep B, 120 days old
    A5  specific_detail, SOLICITED      ep B, 120 days old
    A6  question_asked, NO EPISODE      dated by its own row, 6 days

  ── RULE 1: ln(LR), discounted where the house asked ─────────────────

    A1  ln 20  =  2.995732     (cost high picks costly_action_high)
    A2  ln 8   =  2.079442
    A3  ln 8   =  2.079442
    A4  ln 0.2 = -1.609438
    A5  ln 4 × 0.25 = 0.346574      ← the discount: we asked
    A6  ln 3   =  1.098612

  ── RULE 3: × 2^(−age ÷ half-life) ───────────────────────────────────

    A1  540-day half-life, 6 days:  2.995732 × 2^(-6/540)  = 2.972749
    A2  180-day half-life, 6 days:  2.079442 × 2^(-6/180)  = 2.031947
    A3  the same as A2                                     = 2.031947
    A4  365-day half-life, 120:    -1.609438 × 2^(-120/365) = -1.281461
    A5  180-day half-life, 120:     0.346574 × 2^(-120/180) =  0.218328
    A6   90-day half-life, 6 days:  1.098612 × 2^(-6/90)    = 1.048999

  ── RULE 2: one count per (TYPE, occasion), ×1.5 where repeated ──────

  AND THIS IS THE ONE PLACE THE ENGINE AND SLICE 1's jq DIFFER, so it
  is the claim worth reading twice. The key is `(evidence_type,
  episode)`, which the spec names in rule 2 and again in fork (m) —
  NOT the occasion alone. A1 and A2 share episode A and are DIFFERENT
  WORDS, so they both count: *he spent a Saturday on it* and *he
  brought it up* are two observations however close together they were
  said. A2 and A3 are the SAME word in the SAME evening, so they fold
  to one and take the intensity: that is one person being warm, not
  two independent facts.

    (costly_action, A)      {A1}      = 2.972749
    (unprompted_mention, A) {A2,A3}   = 2.031947 × 1.5 = 3.047921
    (declined_invite, B)    {A4}      = -1.281461
    (specific_detail, B)    {A5}      = 0.218328
    (question_asked, ·A6)   {A6}      = 1.048999   ← its own occasion

    Σ = 2.972749 + 3.047921 - 1.281461 + 0.218328 + 1.048999
      = 6.006536

  ── THE POSTERIOR, AND THE CLAMP ─────────────────────────────────────

    logit(0.1) + Σ = -2.197225 + 6.006536 = 3.809311
    → probability 1/(1+e^-3.809311) = 0.978317

  The clamp is ±6 and this is under it. The clamp's own claim is made
  separately, over a pile no household would ever have.

  ── MOVEMENT IS RULE 1 TWICE ─────────────────────────────────────────

  With the clock set back seven days, A1, A2, A3 and A6 had not
  happened yet — they are 6 days old — so only B's two atoms are
  there, decayed to 113 days:

    A4  -1.609438 × 2^(-113/365) = -1.298610
    A5   0.346574 × 2^(-113/180) =  0.224293
    Σ = -1.074317;  posterior = -2.197225 + -1.074317 = -3.271542

    moved = 3.809311 - (-3.271542) = 7.080853

  Run: cd waymark10 && clojure -M:test --focus waymark10.belief-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.belief :as belief]))

(def ^:private table
  "docs/spec-hypotheses.md's table, spelled out — see the ns docstring
  for why it is not read off the declaration."
  {:costly_action_high 20 :costly_action_low 5
   :unprompted_mention 8 :statement_against_interest 6
   :specific_detail 4 :question_asked 3
   :complaint_while_continuing 3
   :solicited_praise 1.05 :minimal_response 0.9
   :declined_invite 0.2
   :solicited_discount 0.25
   :half_life_costly_action 540
   :half_life_statement_against_interest 365
   :half_life_declined_invite 365
   :half_life_specific_detail 180
   :half_life_unprompted_mention 180
   :half_life_complaint_while_continuing 180
   :half_life_question_asked 90
   :half_life_solicited_praise 60
   :half_life_minimal_response 60
   :episode_intensity 1.5 :log_odds_clamp 6})

(def ^:private now
  "2026-08-30T00:00:00Z, in millis."
  1788048000000)

(def ^:private day 86400000)

(defn- at [days-ago] (- now (* days-ago day)))

(def ^:private ep-a "thread/7fda11c6 2026-08-24")
(def ^:private ep-b "thread/9c02af31 2026-05-02")

(def ^:private atoms
  [{:href "/api/insights/A1" :evidence_type "costly_action" :cost "high"
    :episode ep-a :at (at 6)}
   {:href "/api/insights/A2" :evidence_type "unprompted_mention"
    :episode ep-a :at (at 6)}
   {:href "/api/insights/A3" :evidence_type "unprompted_mention"
    :episode ep-a :at (at 6)}
   {:href "/api/insights/A4" :evidence_type "declined_invite"
    :episode ep-b :at (at 120)}
   {:href "/api/insights/A5" :evidence_type "specific_detail"
    :solicited true :episode ep-b :at (at 120)}
   {:href "/api/insights/A6" :evidence_type "question_asked" :at (at 6)}])

(defn- close?
  ([a b] (close? a b 1e-5))
  ([a b tol] (< (Math/abs (- (double a) (double b))) tol)))

;; ── rule 1, one atom at a time ──────────────────────────────────────

(deftest rule-one-is-the-logarithm-of-the-table
  (testing "each of the nine words weighs the logarithm of its own number, and the polarity needs no `if` anywhere"
    (is (close? 2.995732 (belief/atom-log-odds
                          table {:evidence_type "costly_action" :cost "high"})))
    (is (close? 1.609438 (belief/atom-log-odds
                          table {:evidence_type "costly_action" :cost "low"}))
        "ln 5 — and an ABSENT cost reads as low, the conservative direction")
    (is (close? 1.609438 (belief/atom-log-odds
                          table {:evidence_type "costly_action"})))
    (is (close? 2.079442 (belief/atom-log-odds
                          table {:evidence_type "unprompted_mention"})))
    (is (close? -1.609438 (belief/atom-log-odds
                           table {:evidence_type "declined_invite"}))
        "under 1 the logarithm is negative and the atom subtracts on its own"))

  (testing "solicited is a DISCOUNT and not a tenth word"
    (is (close? 0.346574 (belief/atom-log-odds
                          table {:evidence_type "specific_detail"
                                 :solicited true}))
        "a quarter of ln 4")
    (is (close? (Math/log 1.05)
                (belief/atom-log-odds table {:evidence_type "solicited_praise"
                                             :solicited true}))
        "…and it is not applied to solicited_praise, whose type IS the discount — applying both would charge politeness twice"))

  (testing "an unpriced word weighs nothing at all, and is dropped rather than guessed at"
    (is (nil? (belief/atom-log-odds table {:evidence_type "a-tenth-word"})))
    (is (nil? (belief/atom-log-odds table {})))))

;; ── rules 2 and 3, over the whole fixture ───────────────────────────

(deftest the-fold-is-the-three-rules-and-nothing-else
  (testing "the sum, with the hand arithmetic in the ns docstring"
    (is (close? 6.006536 (belief/fold table atoms now) 1e-4)))

  (testing "the posterior is that sum on top of the prior's log-odds"
    (is (close? 3.809311 (belief/posterior-log-odds table 0.1M atoms now)
                1e-4)))

  (testing "and read as a probability"
    (is (close? 0.978317 (belief/probability
                          (belief/posterior-log-odds table 0.1M atoms now))
                1e-4)))

  (testing "rule 2's key is (TYPE, occasion) and not the occasion alone — the spec's own sentence, and the one place this differs from slice 1's jq"
    (let [one-evening [{:href "/api/insights/X1"
                        :evidence_type "costly_action" :cost "high"
                        :episode ep-a :at (at 0)}
                       {:href "/api/insights/X2"
                        :evidence_type "unprompted_mention"
                        :episode ep-a :at (at 0)}]]
      (is (close? (+ 2.995732 2.079442) (belief/fold table one-evening now)
                  1e-4)
          "two different words in one evening are two observations")))

  (testing "…and the same word twice in one evening is one observation and a half"
    (let [twice [{:href "/api/insights/Y1"
                  :evidence_type "unprompted_mention"
                  :episode ep-a :at (at 0)}
                 {:href "/api/insights/Y2"
                  :evidence_type "unprompted_mention"
                  :episode ep-a :at (at 0)}]]
      (is (close? (* 1.5 2.079442) (belief/fold table twice now) 1e-4))
      (testing "and no further, however many it carried"
        (is (close? (* 1.5 2.079442)
                    (belief/fold table
                                 (conj twice {:href "/api/insights/Y3"
                                              :evidence_type "unprompted_mention"
                                              :episode ep-a :at (at 0)})
                                 now)
                    1e-4)))))

  (testing "AN ATOM WITH NO EPISODE IS ITS OWN OCCASION — the safe direction, since the fold only ever holds a contribution down"
    (let [bare [{:href "/api/insights/Z1" :evidence_type "question_asked"
                 :at (at 0)}
                {:href "/api/insights/Z2" :evidence_type "question_asked"
                 :at (at 0)}]]
      (is (close? (* 2 1.098612) (belief/fold table bare now) 1e-4))))

  (testing "rule 3 forgets toward the prior rather than reversing — a belief nothing has fed for years does not turn into its opposite"
    (let [old [{:href "/api/insights/O1" :evidence_type "solicited_praise"
                :episode ep-b :at (at 3650)}]]
      (is (close? 0.0 (belief/fold table old now) 0.001))
      (is (close? (belief/logit 0.1M)
                  (belief/posterior-log-odds table 0.1M old now)
                  0.001))))

  (testing "an atom that had not happened yet is simply not there"
    (is (close? 0.0 (belief/fold table atoms (at 3650)) 1e-9))))

;; ── the clamp ───────────────────────────────────────────────────────

(deftest no-pile-of-atoms-becomes-certainty
  (let [pile (mapv (fn [i] {:href (str "/api/insights/P" i)
                            :evidence_type "costly_action" :cost "high"
                            :episode (str "occasion " i)
                            :at now})
                   (range 30))]
    (testing "thirty costly actions today would be ninety log-odds, and the clamp holds them at six"
      (is (close? 6.0 (belief/posterior-log-odds table 0.1M pile now)))
      (is (close? 0.997527 (belief/probability
                            (belief/posterior-log-odds table 0.1M pile now))
                  1e-5)
          "about 99.75%, which is the number the spec quotes"))
    (testing "and it holds in both directions"
      (let [against (mapv #(assoc % :evidence_type "declined_invite"
                                  :cost nil)
                          pile)]
        (is (close? -6.0 (belief/posterior-log-odds table 0.1M against now)))))
    (testing "the reason, said as a claim: a belief that reached certainty would stop reading evidence"
      (is (close? (belief/posterior-log-odds table 0.1M pile now)
                  (belief/posterior-log-odds
                   table 0.1M (conj pile {:href "/api/insights/one-more"
                                          :evidence_type "costly_action"
                                          :cost "high"
                                          :episode "one more occasion"
                                          :at now})
                   now))
          "one more Saturday changes nothing once the belief is clamped"))))

;; ── movement is rule 1 twice ────────────────────────────────────────

(deftest what-moved-is-the-same-numbers-asked-twice
  (testing "today's posterior less the posterior seven days ago"
    (is (close? 7.080853 (belief/movement table 0.1M atoms now) 1e-4)))

  (testing "a belief nothing new has fed still MOVES, because the record forgot — and that is real news"
    (let [old-only (filterv #(= 120 (long (/ (- now (:at %)) day))) atoms)
          m (belief/movement table 0.1M old-only now)]
      (is (> (Math/abs (double m)) 0.005)
          "two atoms four months old are worth measurably less this week than last")
      (is (pos? m)
          "and it moved UP, which is worth reading twice: the heavier of the two counted AGAINST, so forgetting it lifts the belief. Decay is toward the PRIOR, not toward no.")))

  (testing "a belief with no atoms at all has not moved and stands at its prior"
    (is (close? 0.0 (belief/movement table 0.1M [] now)))
    (is (close? 0.1 (belief/probability
                     (belief/posterior-log-odds table 0.1M [] now))
                1e-9))))

;; ── what the row caches ─────────────────────────────────────────────

(deftest the-row-shows-its-working
  (let [b (belief/belief table 0.1M atoms now)]
    (testing "the posterior rides as both — the probability because that is what a person reads, the log-odds because that is what the arithmetic adds"
      (is (close? 0.978317 (:posterior b) 1e-4))
      (is (close? 3.809311 (:posterior_log_odds b) 1e-4))
      (is (decimal? (:posterior b))
          "exact decimals, so a row does not diff against itself on every pass"))

    (testing "every atom that fed it is listed, newest first, so the arithmetic can be redone from the row alone"
      (is (= 6 (:atom_count b)))
      (is (= 6 (count (:atoms b))))
      (is (= "/api/insights/A1" (:insight_href (first (:atoms b))))
          "A1 is the newest by id order within the same day"))

    (testing "an atom carries the price the table put on it, before the discount and before any decay"
      (let [a5 (some #(when (= "/api/insights/A5" (:insight_href %)) %)
                     (:atoms b))]
        (is (some? a5))
        (is (close? 4 (:lr_applied a5))
            "the table's number, not the contribution — the decay and the discount are redone from `at` and `solicited`")
        (is (true? (:solicited a5)))
        (is (= ep-b (:episode a5)))))

    (testing "last_moved is when the newest fact was SAID"
      (is (= (java.time.Instant/ofEpochMilli (at 6)) (:last_moved b))))

    (testing "and a belief nothing has fed says so with an absence rather than a zero"
      (let [empty' (belief/belief table 0.1M [] now)]
        (is (nil? (:last_moved empty')))
        (is (= 0 (:atom_count empty')))
        (is (= [] (:atoms empty')))
        (is (close? 0.1 (:posterior empty') 1e-9))))))

;; ── the rows, on both sides of the join ─────────────────────────────

(deftest an-atom-is-a-typed-finding-and-nothing-else
  (testing "an UNTYPED finding is not an atom — the whole compatibility story in one line"
    (is (nil? (belief/atom-of {:id "01H" :state :published
                               :updated-at "2026-08-30T09:00:00Z"
                               :data {:finding "Something, untyped"
                                      :evidence ["/api/tasks/1"]}}))))

  (testing "a typed one is dated by its OCCASION's day where the clerk wrote one"
    (let [a (belief/atom-of {:id "01H" :state :published
                             :updated-at "2026-08-30T09:00:00Z"
                             :data {:evidence_type "unprompted_mention"
                                    :episode ep-a
                                    :evidence ["/api/people/iris"]}})]
      (is (= (at 6) (:at a)) "2026-08-24, not the day it was indexed")
      (is (= #{"/api/people/iris"} (:cites a)))))

  (testing "…and by the day it was indexed where it did not, which is the reading's driver's own fallback"
    (let [a (belief/atom-of {:id "01H" :state :published
                             :updated-at "2026-08-24T09:00:00Z"
                             :data {:evidence_type "unprompted_mention"
                                    :evidence ["/api/people/iris"]}})]
      (is (= (at 6) (:at a)))))

  (testing "A DISMISSED FINDING IS NOT AN ATOM: the house said that claim was too thin, not backed, already known or not true"
    (let [rows [{:id "1" :state :published
                 :updated-at "2026-08-30T00:00:00Z"
                 :data {:evidence_type "question_asked" :evidence ["/api/x/1"]}}
                {:id "2" :state :taken
                 :updated-at "2026-08-30T00:00:00Z"
                 :data {:evidence_type "question_asked" :evidence ["/api/x/1"]}}
                {:id "3" :state :dismissed
                 :updated-at "2026-08-30T00:00:00Z"
                 :data {:evidence_type "question_asked" :evidence ["/api/x/1"]}}]]
      (is (= #{"/api/insights/1" "/api/insights/2"}
             (into #{} (map :href) (belief/atoms-of rows)))
          "published stands and taken is the house agreeing; only the dismissal leaves"))))

(deftest the-address-is-the-link
  (let [row {:id "H1" :data {:prior 0.1M
                             :about ["/api/people/iris" "/api/values/shop"]}}
        subject-atom {:href "/api/insights/S" :evidence_type "unprompted_mention"
                      :at now :cites #{"/api/people/iris"}}
        direct-atom {:href "/api/insights/D" :evidence_type "question_asked"
                     :at now :cites #{"/api/hypotheses/H1"}}
        stranger {:href "/api/insights/N" :evidence_type "costly_action"
                  :cost "high" :at now :cites #{"/api/tasks/nothing"}}]
    (testing "a finding that cites a row the belief is ABOUT feeds it"
      (is (belief/touched-by? subject-atom (belief/addresses-of row))))
    (testing "…and so does one that cites the belief ITSELF, which is the direct link a reading writes"
      (is (belief/touched-by? direct-atom (belief/addresses-of row))))
    (testing "…and one that cites neither does not, however heavy it is"
      (is (not (belief/touched-by? stranger (belief/addresses-of row)))))
    (testing "the fold reads exactly the two that touch it"
      (let [b (belief/fold-one table row [subject-atom direct-atom stranger]
                               now)]
        (is (= 2 (:atom_count b)))
        (is (close? (+ (belief/logit 0.1M) 2.079442 1.098612)
                    (:posterior_log_odds b) 1e-4))))))
