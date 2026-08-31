(ns waymark10.belief
  "THE UPDATER (waymark-bug, docs/spec-hypotheses.md § 'The updater —
  three deterministic rules'): the whole of the belief arithmetic, and
  it is small enough to check by hand, which is the point.

  A hypothesis is a claim with a number on it that moved because of
  atoms you can name. This namespace is where the number comes from
  and it holds nothing else — no storage, no rows, no engine. Given a
  prior, a table of likelihood ratios and a list of atoms, it answers
  what the record now says. `waymark10.server.belief` is the pass that
  finds the atoms and caches the answer; this file is the arithmetic
  that pass runs, so a test can check the numbers without a database
  and a person can read them without a program.

  ── THE THREE RULES ──────────────────────────────────────────────────

  1. LOG-ODDS ADDITION. `posterior = logit(prior) + Σ ln(LR_i)`, each
     atom's LR read off the table for its type, cost-graded for
     `costly_action`, and scaled by `solicited_discount` in log-odds
     where the house asked first. Addition in log-odds is
     multiplication of odds, which is Bayes with independent evidence
     and nothing else — and it is why polarity needs no `if`
     anywhere: `declined_invite` at 0.2 has a negative logarithm and
     subtracts on its own. CLAMPED at ±`log_odds_clamp`, because a
     belief that reaches certainty stops reading evidence, and this
     house's posture is that the system proposes and never believes.

  2. ONE COUNT PER (TYPE, EPISODE), ×`episode_intensity`. Atoms
     sharing an evidence type AND an occasion collapse to ONE
     contribution — the strongest — and if there were two or more it
     is multiplied by the intensity and no further. Enthusiasm in a
     single conversation is warmth, not four independent
     observations. A missing `episode` is its own episode, which is
     the safe direction: fewer discounts, never a merge the record
     cannot justify.

     THE KEY IS THE SPEC'S AND IT IS NOT slice 1's. `scripts/
     movements.jq` groups by the OCCASION ALONE, so a costly action
     and an unprompted mention in one evening fold to one number
     there and to two here. The spec settles it twice — rule 2's own
     sentence names `(hypothesis, evidence_type, episode)` and fork
     (m) names `(type, episode)` — and the reason is the honest one:
     *he spent a Saturday on it* and *he brought it up* are two
     different observations however close together they were said,
     while two unprompted mentions in one evening are one person
     being warm. Slice 1's jq is corrected to agree
     (`scripts/movements-fixture.sh` carries the hand arithmetic),
     because a fallback that computes a different number from the
     store is the second opinion nobody can see.

  3. DECAY BY TYPE, TOWARD THE PRIOR. Each contribution is multiplied
     by `2^(−age_days / half_life[type])`. As every atom decays its
     contribution approaches zero, which in log-odds is LR 1, which
     leaves the prior — so a hypothesis nothing has fed for two years
     does not REVERSE, it FORGETS, which is the honest thing for a
     record to do about a person who has changed. Decay is computed
     at READ time from each atom's own `at`; nothing is rewritten on
     a clock except the cached posterior.

  ── MOVEMENT IS RULE 1 TWICE ─────────────────────────────────────────

  The spec's own sentence: *the same fold with the clock set back
  seven days, subtracted from the fold today.* No stored history and
  no weekly snapshot kind — the atoms carry their own instant, and
  *what moved* is a question about the same numbers asked twice. A
  hypothesis moves when a new atom lands AND when an old one fades,
  and both are real news.

  ── THE ATOM, AS THIS FILE TAKES IT ──────────────────────────────────

      {:href          \"/api/insights/01H…\"   the finding it came from
       :evidence_type \"unprompted_mention\"   one of insight's nine
       :cost          \"high\" | \"low\" | nil   grades costly_action
       :solicited     true | false | nil      did the house ask?
       :episode       \"thread/7fda11c6 2026-08-24\" | nil
       :at            <epoch millis>}         when the thing was SAID

  `:at` is the SOURCE's instant and not the run's — the episode's own
  day where the clerk wrote one, the day the finding was indexed where
  it did not. That is the backfill rule read literally
  (§ 'The backfill rule'): a run walking two years of history must
  not manufacture a wall of fresh evidence.

  An atom whose type the table does not price contributes NOTHING and
  is dropped rather than guessed at — a house that grew a tenth word
  on the kind and did not price it on its recipe row reads the nine it
  declared. An UNTYPED finding never becomes an atom at all, which is
  the compatibility story in one line: LR 1, which is silence.

  ── WHAT IS NOT HERE, DELIBERATELY ───────────────────────────────────

  No `:derived {:expr …}` tree. `waymark10.expr/ops` has `+ - *`,
  `min max abs`, and no division and no logarithm — deliberately
  (docs/waymark10-vocabulary.md § 2) — so this is engine arithmetic in
  Clojure over declared numbers, cached on the row. The spec's fork
  (f) records the choice and its price: growing the law's vocabulary
  for one kind's arithmetic would put a model where a household can no
  longer read the law."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ── the clock ───────────────────────────────────────────────────────

(def ^:private day-ms
  "One day in milliseconds. Ages are computed in days because the
  half-lives are stated in days, and the half-lives are stated in days
  because that is the unit a household argues in."
  86400000)

(def week-ms
  "The window *what moved this week* asks about. Public because the
  pass and the brief both name it, and two spellings of seven days is
  exactly the drift a shared name prevents."
  (* 7 day-ms))

;; ── the two functions the log-odds are ──────────────────────────────

(defn logit
  "A probability as log-odds: `ln(p / (1 − p))`. The prior's own
  translation, and the only place a probability enters the
  arithmetic. Refuses nothing — the kind's schema bounds `prior` to
  0.02–0.5 — but clamps the arguments off 0 and 1 so a stored row that
  somehow carries one does not hand the fold an infinity."
  ^double [p]
  (let [d (double (or p 0.1))
        d (cond (< d 1e-9) 1e-9 (> d (- 1.0 1e-9)) (- 1.0 1e-9) :else d)]
    (Math/log (/ d (- 1.0 d)))))

(defn probability
  "Log-odds back to a probability, for reading: `1 / (1 + e^−x)`. The
  posterior is STORED as both — the probability because that is what a
  person reads, the log-odds because that is what the arithmetic
  adds — and this is the one function between them."
  ^double [x]
  (/ 1.0 (+ 1.0 (Math/exp (- (double x))))))

;; ── the table, read the way slice 1 reads it ────────────────────────

(defn lr-key
  "Which of the table's ten ratios an atom is priced by.
  `costly_action` is the one cost-graded type — `high` and `low` are
  two different numbers — and an absent cost reads as LOW, the
  conservative direction and the one worth being wrong in: a fact
  somebody spent something on counts for something even where the
  clerk could not say how much. `cost: none` never reaches here;
  `insight/the-typing-agrees-with-itself` refuses it at the door.

  Every other type is its own key, and `cost` on those eight is
  stored and unpriced — the recorded punt, so the household can find
  out from observed atoms whether a cost-graded scale is worth having
  for the rest."
  [evidence-type cost]
  (let [ty (some-> evidence-type str str/trim not-empty)]
    (when ty
      (if (= "costly_action" ty)
        (if (= "high" (some-> cost str str/trim)) :costly_action_high
            :costly_action_low)
        (keyword ty)))))

(defn half-life
  "How many days this kind of evidence takes to be worth half what it
  was. Per TYPE because forgetting is per type — a Saturday spent
  building something is still evidence eighteen months later and a
  polite yes at dinner is not evidence at all by autumn — and 180 for
  a type the table does not name, which is the middle of the nine
  rather than a number with an argument behind it."
  ^double [table evidence-type]
  (let [ty (some-> evidence-type str str/trim not-empty)
        v (when ty (get table (keyword (str "half_life_" ty))))
        d (double (or v 180))]
    (if (pos? d) d 180.0)))

(defn atom-lr
  "The likelihood ratio this atom carries BEFORE the solicited
  discount and before any decay — the table's own number for its key,
  cost-graded. nil when the table prices no such word, which is how an
  atom is dropped rather than guessed at.

  This is the number cached on the hypothesis as `lr_applied`: the
  fact the fold read, so a person redoing the arithmetic by hand does
  not have to hold the table open beside the row."
  [table a]
  (when-some [k (lr-key (:evidence_type a) (:cost a))]
    (when-some [v (get table k)]
      (let [d (double v)]
        (when (pos? d) d)))))

(defn atom-log-odds
  "One atom's undecayed contribution in log-odds: `ln(LR)`, scaled by
  `solicited_discount` where the house asked first. nil when the atom
  is unpriced.

  `solicited` IS A DISCOUNT AND NOT A TENTH TYPE: an answer to a
  question you put in somebody's mouth is a quarter of the evidence of
  the same words unprompted. `solicited_praise` is exempt because the
  type IS the discount — applying both would charge politeness twice,
  and 1.05 discounted to a quarter would be a number nobody argued
  for."
  [table a]
  (when-some [lr (atom-lr table a)]
    (let [lg (Math/log lr)
          ty (some-> (:evidence_type a) str str/trim)
          discount (double (or (:solicited_discount table) 0.25))]
      (if (and (true? (:solicited a)) (not= "solicited_praise" ty))
        (* lg discount)
        lg))))

;; ── rule 2's key ────────────────────────────────────────────────────

(defn- occasion
  "The pair rule 2 folds on: `[evidence_type episode]`. An atom with
  no episode is ITS OWN occasion — keyed on its own href, so it is
  never folded with another. That is the safe direction to be wrong
  in, because the fold only ever holds a contribution DOWN: an
  unfolded pair overstates rather than hides, and the reading says so
  out loud where the field is blank."
  [a]
  (let [ep (some-> (:episode a) str str/trim not-empty)]
    [(some-> (:evidence_type a) str str/trim)
     (or ep (str "(no episode) " (:href a)))]))

;; ── the fold ────────────────────────────────────────────────────────

(defn fold
  "Rules 3, 2 and 1 in that order, over one hypothesis's atoms at one
  moment: decay each atom to `at-ms`, keep one per occasion with the
  intensity where the occasion carried more, add. Returns the SUM in
  log-odds, unclamped and WITHOUT the prior — `posterior-log-odds`
  adds both, and `movement` wants the difference of two folds where
  the prior would only cancel.

  Atoms that had not happened yet at `at-ms` are simply not there,
  which is what makes the clock-set-back fold honest rather than a
  reweighting."
  ^double [table atoms ^long at-ms]
  (let [intensity (double (or (:episode_intensity table) 1.5))]
    (->> atoms
         (keep (fn [a]
                 (let [at (long (or (:at a) 0))]
                   (when (<= at at-ms)
                     (when-some [w0 (atom-log-odds table a)]
                       (let [age-days (/ (double (- at-ms at)) (double day-ms))
                             hl (half-life table (:evidence_type a))]
                         {:key (occasion a)
                          :w (* (double w0) (Math/pow 0.5 (/ age-days hl)))}))))))
         (group-by :key)
         (reduce (fn [^double acc [_ group]]
                   (let [strongest (apply max-key #(Math/abs (double (:w %)))
                                          group)]
                     (+ acc (* (double (:w strongest))
                               (if (> (count group) 1) intensity 1.0)))))
                 0.0))))

(defn clamp
  "±`log_odds_clamp` (default 6, about 0.25%–99.75%). No finite pile
  of atoms becomes certainty, because a belief that reaches certainty
  stops reading evidence — the failure mode this whole design exists
  to avoid, and the fork (o) that records it."
  ^double [table x]
  (let [c (Math/abs (double (or (:log_odds_clamp table) 6)))
        d (double x)]
    (cond (> d c) c (< d (- c)) (- c) :else d)))

(defn posterior-log-odds
  "Rule 1 whole: `clamp(logit(prior) + fold(atoms))`. The clamp is on
  the POSTERIOR rather than on the sum, because it is the belief that
  may not reach certainty and the prior is part of the belief."
  ^double [table prior atoms ^long at-ms]
  (clamp table (+ (logit prior) (fold table atoms at-ms))))

(defn movement
  "Rule 1 twice: today's posterior less the posterior seven days ago.
  A hypothesis moves when a new atom lands and when an old one fades,
  and this one number says both without storing a week of history."
  ([table prior atoms now-ms]
   (movement table prior atoms now-ms week-ms))
  ([table prior atoms now-ms window-ms]
   (- (posterior-log-odds table prior atoms (long now-ms))
      (posterior-log-odds table prior atoms (- (long now-ms)
                                               (long window-ms))))))

;; ── what the pass caches ────────────────────────────────────────────

(defn- ^java.math.BigDecimal scaled
  "A number as the row stores it: an exact decimal with four places,
  because `:decimal` is a BigDecimal on the wire and a posterior with
  seventeen digits of double noise would diff against itself on every
  pass."
  [x]
  (.setScale (java.math.BigDecimal/valueOf (double x)) 4
             java.math.RoundingMode/HALF_UP))

(defn atom-record
  "One atom as the hypothesis CACHES it — the spec's
  `{insight_href, evidence_type, lr_applied, at}` plus the two fields
  the arithmetic cannot be redone without.

  `episode` and `solicited` ride because the row's own promise is that
  it is *a cache of an arithmetic anyone can redo*: without the
  occasion nobody can check rule 2, and without the discount flag the
  `lr_applied` beside it would not reconcile with the posterior. They
  are facts the atom already carried, copied rather than derived.

  `lr_applied` is the table's cost-graded number BEFORE the discount
  and before any decay — the price, not the contribution — so a
  person reading the row sees what the table said about this word."
  [table a]
  (cond-> {:insight_href (str (:href a))
           :evidence_type (str (:evidence_type a))
           :lr_applied (scaled (or (atom-lr table a) 1))
           :at (str (java.time.Instant/ofEpochMilli (long (or (:at a) 0))))}
    (some-> (:episode a) str str/trim not-empty)
    (assoc :episode (str/trim (str (:episode a))))

    (true? (:solicited a)) (assoc :solicited true)))

(defn belief
  "The whole answer for one hypothesis, in the shape the pass writes
  onto the row: the posterior as both a probability and log-odds, the
  seven-day movement, the instant the newest atom was said, and the
  atoms themselves in the order they were said, newest first.

  A pure function of (table, prior, atoms, now), which is the row's
  own guarantee: delete every posterior in the store and one pass
  rebuilds them identically."
  [table prior atoms ^long now-ms]
  (let [lo (posterior-log-odds table prior atoms now-ms)
        priced (filter #(some? (atom-lr table %)) atoms)]
    {:posterior (scaled (probability lo))
     :posterior_log_odds (scaled lo)
     :movement_7d (scaled (movement table prior atoms now-ms))
     :atom_count (count priced)
     :last_moved (some->> priced (map :at) (remove nil?) seq (apply max)
                          (java.time.Instant/ofEpochMilli))
     :atoms (into [] (map #(atom-record table %))
                  (sort-by #(- (long (or (:at %) 0))) priced))}))

;; ── the rows, on both sides of the join ─────────────────────────────
;;
;; WHAT AN ATOM OF A HYPOTHESIS IS: a published or taken `insight`
;; that carries one of the nine evidence words AND touches this
;; hypothesis — where *touches* is address overlap and nothing
;; cleverer, `not-a-twin`'s honest boundary read one kind over:
;;
;;   1. the finding cites a row the hypothesis is ABOUT, or
;;   2. the finding cites the HYPOTHESIS ITSELF,
;;      `/api/hypotheses/<id>` — the direct link a reading writes when
;;      it wants an atom on the belief rather than on the subject.
;;
;; Both are the same act — a finding said what it read — and unioning
;; them is what makes the backfill possible: a reading may mint a
;; hypothesis `about` the rows a standing pile of atoms already cites,
;; and every one of them is linked the moment the row exists, with no
;; edit to any finding. No new field on `insight`, no second citation
;; vocabulary and no membership table: the addresses ARE the link
;; (fork (i), and § 'Embedding-based entity resolution' for why
;; nothing here compares sentences).
;;
;; DISMISSED FINDINGS ARE NOT ATOMS. The house said that claim was too
;; thin, not backed, already known or not true; a belief hung on a
;; claim the house rejected is not a belief with evidence under it.
;; This is also the reading's ONLY lawful way to take an atom back out
;; of a fold — `insight.dismiss`, in public, with a word attached —
;; because the extraction-blind rule forbids the quiet retype that
;; would do it invisibly.

(def live-atom-states
  "A finding that still means what it said. `published` is standing
  and `taken` is the house agreeing with it; `dismissed` is the house
  saying no, and it leaves the fold."
  #{:published :taken "published" "taken"})

(defn- day-of
  "The day an episode names, as `YYYY-MM-DD`, or nil. The episode's
  shape is *the source plus the day* — `thread/7fda11c6 2026-08-24` —
  and the day is the half the arithmetic reads."
  [episode]
  (re-find #"\d{4}-\d{2}-\d{2}" (str episode)))

(defn- millis-of-day
  "A `YYYY-MM-DD` at midnight UTC, in epoch millis, or nil. Days
  rather than instants because that is what the record honestly
  knows: a clerk writing an episode wrote a day, and pretending to an
  hour would be inventing precision."
  [ymd]
  (try
    (-> (java.time.LocalDate/parse (str ymd))
        (.atStartOfDay java.time.ZoneOffset/UTC)
        .toInstant
        .toEpochMilli)
    (catch Exception _ nil)))

(defn atom-of
  "One `insight` row as an atom, or nil when it is not one — an
  untyped finding is not an atom, which is the compatibility story:
  every finding written before the epic weighs a likelihood ratio of
  1, which is silence.

  `:at` IS THE DAY THE THING WAS SAID: the episode's own day where the
  clerk wrote one, and the day the finding was last touched where it
  did not. Truncated to the day at both, because both are days.

  The fallback is the reading's driver's, character for character
  (`scripts/movements.jq`), and its cost is recorded rather than
  hidden: a finding the household later TOOK re-dates to the day of
  the tap. The wire carries no birth instant — `render/envelope`
  publishes `meta.updated-at` and nothing earlier — so a store-side
  fold reading `created-at` would disagree with a brief that cannot.
  Two arithmetics that disagree is the thing this design most needs
  not to be, and an episode makes the question moot: a typed atom that
  names its occasion is dated by the occasion."
  [row]
  (let [d (:data row)
        ty (some-> (:evidence_type d) str str/trim not-empty)
        ep (some-> (:episode d) str str/trim not-empty)
        at (or (some-> ep day-of millis-of-day)
               (let [u (str (:updated-at row))]
                 (when (>= (count u) 10) (millis-of-day (subs u 0 10)))))]
    (when (and ty at)
      {:href (str "/api/insights/" (:id row))
       :evidence_type ty
       :cost (some-> (:cost d) str str/trim not-empty)
       :solicited (true? (:solicited d))
       :episode ep
       :at at
       :cites (into #{}
                    (comp (map #(str/trim (str %))) (remove str/blank?))
                    (:evidence d))})))

(defn atoms-of
  "Every atom in a pile of `insight` rows, dismissals dropped."
  [insight-rows]
  (into []
        (comp (filter #(contains? live-atom-states (:state %)))
              (keep atom-of))
        insight-rows))

(defn addresses-of
  "The address set a hypothesis is fed through: everything it is
  ABOUT, plus its own address. Trimmed and blank-free, the way
  `insight/read-rows` reads the other side of the same join."
  [row]
  (into (if-some [id (:id row)] #{(str "/api/hypotheses/" id)} #{})
        (comp (map #(str/trim (str %))) (remove str/blank?))
        (get-in row [:data :about])))

(defn touched-by?
  "Does this atom touch that address set? Address overlap and nothing
  cleverer — the finding cites a row the hypothesis is about, or it
  cites the hypothesis itself."
  [a addresses]
  (boolean (some addresses (:cites a))))

(defn fold-one
  "What the record now says about ONE hypothesis row, given every atom
  in the house — a pure function of (table, row, atoms, now), which is
  the row's own guarantee: delete every posterior in the store and one
  pass rebuilds them identically."
  [table row atoms ^long now-ms]
  (belief table (get-in row [:data :prior])
          (filterv #(touched-by? % (addresses-of row)) atoms)
          now-ms))

(defn cached
  "The fold in the row's own document spelling — what a birth writes
  and what the pass rewrites. Instants become strings because that is
  what a `:waymark/instant` field holds on the way to the wire, and
  `last_moved` is ABSENT rather than nil when no atom has ever fed
  this belief: an em-dash is the honest render for *nothing has moved
  it yet*."
  [folded]
  (cond-> {:posterior (:posterior folded)
           :posterior_log_odds (:posterior_log_odds folded)
           :movement_7d (:movement_7d folded)
           :atom_count (:atom_count folded)
           :atoms (:atoms folded)}
    (:last_moved folded) (assoc :last_moved (str (:last_moved folded)))))
