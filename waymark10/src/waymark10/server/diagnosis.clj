(ns waymark10.server.diagnosis
  "The composer's diagnosis (waymark-8um.4): law 4's duty, made a READ.

  Laws v3, law 4 — *no burial without a diagnosis*: when a high-value
  card keeps losing, the system must first produce a friction
  diagnosis before the old form may fade; non-engagement is the
  composer's work order, and the composer's duty fires before the
  ranker's. This document is the work order, written out. For every
  outcome the caller composed it answers the four questions a
  recomposition has to be specific about:

  1. SHOWN ON HOW MANY MORNINGS — off `feed_view` rows keyed by the
     card ids this row can wear (`feed/card-ids`), honouring the
     member's own opt-in (law 7). When no member who could have been
     shown it was recording, the answer is *exposure unknown* and never
     0: a record the house was not keeping is not evidence of silence.
  2. ANSWERED HOW — accepted, declined, expired, lapsed, or still
     offered, off the outcome's own state and leash.
  3. WITH WHICH REASONS — every `verdict_reason` row about the bundle
     and about each of its pieces, the quick word and the sentence
     underneath (waymark-jfv.16's input, read).
  4. AND WHAT THE FLOOR SAYS — `not_before`, `declined_count`, the
     chain in both directions.

  …and from those, ONE LESSON per outcome, which is the distinction
  waymark-1uv.4 asked this bead to make before the cap comes off:
  SHOWN AND DECLINED teaches, SHOWN AND PASSED OVER teaches, NEVER
  SHOWN does not — a composer that stages fifty and sees five shown has
  learned about the rank, not about the house — and UNKNOWN is said
  as unknown. A decline is itself an exposure: somebody read the card
  and answered it, so a declined outcome teaches whether or not the
  decider's screen was recording.

  ── TWO DIAGNOSES, KEPT APART (waymark-1uv.4) ──

  Since the cap on staging left (waymark-1uv.3) most of what a busy
  composer stages never reaches a screen, and the ratio of
  `never_shown` to `shown_*` in this document is the rank's own
  report card. So the document carries a TALLY over the outcomes it
  folds — a count per lesson — and every outcome carries the RANK'S
  OWN READING of it (`rank`): the lift the crown read it at as
  staged, every input that went into that number, and where that
  reading places it among the outcomes here. The inputs are read
  through `feed/crown-inputs` and weighed by `feed/crown-lift` under
  the household's own `crown_rank`, so the number a composer reads is
  the number the crown sorted by and never a second opinion about it.

  Which is which, in one sentence: shown-and-declined and
  shown-and-passed-over are a diagnosis of the HOUSE — what people did
  with what they saw, each owing an insight before its line comes
  back — and never-shown is a diagnosis of the RANK — where the
  composer's bundles stood against the numbers on the recipe row, to
  be answered by staging differently, by tuning the rank through
  `recipe_proposal` (waymark-1uv.5), or by waiting for the floor, and
  never by an insight about the house.

  ── A READ, NOT A KIND ──

  Three shapes were weighed. A `diagnosis` KIND the composer writes
  would be a row whose whole content is a join the engine can already
  compute, and a row the composer could write is a row the composer
  could write wrong. A FIELD GROUP on the outcome's own envelope would
  ride `waymark_get` at the MCP door, but the envelope is the generic
  projection of one row and `derived` reaches across kinds only
  through declared edges — a view row names its card as a string,
  not a ref, so there is no edge to declare. So it is a DOCUMENT,
  beside the feed and in the feed's module, because every fact in it
  is the feed's: the card-id convention, the view record, the reason
  record, and the crown's own population. The outcome kind is named
  here by keyword exactly as `feed/outcomes` names it, and for the
  same reason — a core reader over an optional application kind that
  answers nothing when the engine holds neither.

  ── PROJECTED THROUGH THE CALLER'S OWN SIGHT ──

  The outcomes are the composer's own (`:own-surface {:by
  :composed_by}`) and need no grant. The two records are other
  members' — a view row is the member's, a reason is the sayer's — so
  each half is read ONLY when the caller's leash admits the whole kind
  (`:whole-kind?`, never the own-surface courtesy), which is exactly
  the read grant waymark-8um.1 and waymark-jfv.16 each designed for
  this reader: `{:kind \"feed_view\" :actions []}` and `{:kind
  \"verdict_reason\" :actions []}`. A human reads unscoped. A leash
  that names neither reads a diagnosis that says, per half, that it
  was withheld and which grant to ask for — never a quiet zero.

  ── WHAT IT DOES NOT DO ──

  It writes nothing, decides nothing and fades nothing. The wall that
  makes the duty structural is the outcome kind's own
  (`no-burial-without-a-diagnosis`, at the create door, on
  `supersedes`); this document is what that wall tells a composer to
  go and read."
  (:require [clojure.string :as str]
            [waymark10.server.feed :as feed]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def outcome-cap
  "The newest outcomes one document folds, per composer. Twenty — a
  composer diagnoses what it staged lately, and `?outcome=<id>` asks
  about one older row by name. The document says when it reached the
  cap, `history/fold-cap`'s posture."
  20)

(def duty
  "Law 4, in the sentence this document exists to say. Quoted on
  every read, because a duty that only appeared once it was owed
  would be a duty nobody could plan around."
  (str "An outcome shown N mornings and declined with reason R is a"
       " diagnosis to write before a recomposition; an outcome never"
       " shown is not a verdict. A decline is itself an exposure —"
       " somebody read the card and answered it. Shown and passed"
       " over until the week ran out teaches the same way. Never"
       " shown teaches about the rank, not about the house, and"
       " recomposes freely. Exposure the house was not keeping a"
       " record of is unknown, and this document says unknown rather"
       " than nought."))

;; ── the reads ───────────────────────────────────────────────────────

(defn- resources [eng] (inv/resources eng))

(defn- rows-of
  "One kind's rows matching an equality map, decoded, newest first,
  capped — or none when this engine holds no such kind or never made
  its table (`feed/rows-of`'s posture)."
  [eng kind where limit]
  (if-some [rdef (get (resources eng) kind)]
    (let [st (:storage eng)]
      (try
        (mapv #(inv/decode-row rdef %)
              (store/with-tx st
                (fn [tx] (store/query-rows st tx kind where
                                           {:limit limit :newest-first true}))))
        (catch Exception _ [])))
    []))

(defn- admits?
  "Does the caller's sight admit the WHOLE of a kind? Nil visibility
  is an unscoped human; otherwise the honest kind-level closure, and
  deliberately not `:kind?`, which answers yes for every own-surface
  kind — a composer holding no grant at all would otherwise read
  every member's view rows through the courtesy that lets a member
  read their own."
  [vis kind]
  (or (nil? vis) (boolean ((:whole-kind? vis) kind))))

(defn- collection-of [eng kind]
  (some->> (get (resources eng) kind) :plural (str "/api/")))

(defn- address [eng kind id]
  (str (collection-of eng kind) "/" id))

;; ── exposure (law 7, read honestly) ─────────────────────────────────

(defn- measured-members
  "The members whose screens COULD have reported this outcome: every
  member other than its composer whose `feed_view_consent` is
  RECORDING and existed before the outcome stopped being offered. A
  switch turned on after the week was over recorded nothing about it,
  and counting that member as measured would turn *unknown* into
  *never shown* — the one substitution this document exists to refuse.
  A stopped switch is read the same way, and the cost is recorded: a
  member who recorded the week and stopped after it is counted as
  unmeasured for the 0-rows case (their rows, where they exist, still
  count), because the switch's transitions are not read here and a
  guess in the other direction is the worse error."
  [consents composer answered-at]
  (into []
        (comp (remove #(= composer (get-in % [:data :member])))
              (filter #(= "recording" (name (:state %))))
              (filter #(or (nil? answered-at)
                           (nil? (:created-at %))
                           (not (pos? (compare (:created-at %) answered-at)))))
              (map #(str (get-in % [:data :member])))
              (distinct))
        consents))

(defn- exposure
  "Shown on how many mornings, by whom — or unknown, or withheld.

  Three answers and each is a different sentence: `known true` with
  the counts (a zero here is a real zero — somebody was recording and
  never had it on screen); `known false` because nobody who could have
  been shown it was keeping a record; `known false, withheld true`
  because the caller's leash does not name `feed_view`."
  [eng vis o measured]
  (if-not (admits? vis :feed_view)
    {:known false :withheld true
     :says (str "Withheld: your leash does not name feed_view. Ask for"
                " {\"kind\": \"feed_view\", \"actions\": []} — the read"
                " grant waymark-8um.1 designed for exactly this reader.")}
    (let [rows (into []
                     (mapcat #(rows-of eng :feed_view {:card_id %} 500))
                     (feed/card-ids :outcome (:id o)))
          by-member (frequencies (map #(str (get-in % [:data :member])) rows))]
      (cond
        (seq rows)
        {:known true
         :mornings (count (distinct (map #(str (get-in % [:data :day])) rows)))
         :views (count rows)
         :by (into [] (map (fn [[m n]] {:member m :mornings n})) (sort by-member))
         :measured measured}

        (seq measured)
        {:known true :mornings 0 :views 0 :by [] :measured measured
         :says (str "Never shown: " (count measured) " member"
                    (when (not= 1 (count measured)) "s")
                    " recording, and none of their screens ever had it"
                    " on for a second. This teaches about the rank, not"
                    " about the house.")}

        :else
        {:known false
         :says (str "Exposure unknown: no member who could have been shown"
                    " this was keeping a record of what their screen showed"
                    " (law 7 — off for everybody until each person turns"
                    " their own on). Unknown is not nought.")}))))

;; ── the reasons (waymark-jfv.16, read) ──────────────────────────────

(defn- reasons-about
  "Every reason the house gave about this bundle and about each of
  its pieces — nil when the caller's leash does not name the kind, an
  empty vector when the house declined and said nothing, which is the
  ordinary case and a complete answer."
  [eng vis o pieces]
  (when (admits? vis :verdict_reason)
    (into []
          (mapcat (fn [[k id]]
                    (map (fn [r]
                           (let [d (:data r)]
                             (cond-> {:self (address eng :verdict_reason (:id r))
                                      :about (address eng (keyword k) id)
                                      :verdict (str (:verdict d))
                                      :reason (str (:reason d))
                                      :said_by (str (:said_by d))}
                               (not (str/blank? (str (:words d))))
                               (assoc :words (str (:words d))))))
                         (rows-of eng :verdict_reason
                                  {:subject_kind k :subject_id (str id)} 10))))
          (into [["outcome" (:id o)]]
                (map (fn [p] ["outcome_piece" (:id p)])) pieces))))

;; ── the lesson ──────────────────────────────────────────────────────

(defn- answered
  "How the house answered, in one word — the state, except that an
  offered outcome whose week has run out is `lapsed` rather than
  `offered`: nobody will tap it now, and `expire` is bookkeeping
  anybody may run."
  [o now]
  (let [state (name (:state o))
        good (get-in o [:data :good_until])]
    (if (and (= "offered" state) good (not (pos? (compare good now))))
      "lapsed"
      state)))

(defn lesson
  "What this outcome teaches, from how it was answered and whether it
  was seen — the one function the document and its sentences agree
  through. Public because the pack and the deftests pin it.

  `shown_and_declined` — a person read it and said no; the decline is
  the exposure, whatever the view record holds.
  `shown_and_passed_over` — on a recording screen at least one morning
  and never answered before the week ran out.
  `never_shown` — somebody was recording and it was never on screen.
  `unknown` — expired, and nobody who could have seen it was recording.
  `accepted`, `still_offered` — nothing to diagnose yet or ever.

  THE THRESHOLD IS ONE RECORDED MORNING, and it is not a recipe
  number — decided by waymark-1uv.4 rather than left open. A morning
  on a recording screen is a fact about a person's screen, not a
  weight the household tunes; the six numbers on the recipe row say
  how the crown CHOOSES, and a number that said how many showings
  count as being seen would be a guard reading the formula, which is
  the thing waymark-8um.4 refused for 8um.3's reason. If a house ever
  wants a bundle to lapse unremarked after one glance, that is a
  question about the view record's grain, and it is filed then."
  [answered exposure]
  (case answered
    "accepted" :accepted
    "declined" :shown_and_declined
    ("offered" "lapsed") :still_offered
    "expired" (cond
                (not (:known exposure)) :unknown
                (pos? (long (:mornings exposure 0))) :shown_and_passed_over
                :else :never_shown)
    :unknown))

(def needs-diagnosis
  "The two lessons that owe a diagnosis before a recomposition — the
  wall `outcome/no-burial-without-a-diagnosis` fires on exactly these."
  #{:shown_and_declined :shown_and_passed_over})

(defn- reason-words [reasons]
  (when (seq reasons)
    (str " The house said why: "
         (str/join "; " (map (fn [r] (str (:reason r) " on " (:about r)
                                           (when (:words r)
                                             (str " — " (pr-str (:words r))))))
                             reasons))
         ".")))

(defn- lesson-says
  [eng o answered exposure reasons lesson]
  (let [self (address eng :outcome (:id o))
        mornings (when (:known exposure)
                   (str "shown " (:mornings exposure) " morning"
                        (when (not= 1 (long (:mornings exposure))) "s")))
        floor (get-in o [:data :not_before])]
    (case lesson
      :shown_and_declined
      (str "Declined" (when mornings (str ", " mornings))
           (when-not (:known exposure)
             " — the decline is the exposure; the mornings before it are unknown")
           "." (reason-words reasons)
           " This is a diagnosis to write before a recomposition: publish"
           " an insight citing " self " in its evidence, and name it as"
           " diagnosis_id on the outcome that supersedes this one"
           (when floor (str " — not before " floor)) ".")

      :shown_and_passed_over
      (str "Expired, " mornings " and never answered — shown and passed"
           " over." (reason-words reasons)
           " This teaches the same way a decline does: diagnose the"
           " friction, publish the insight citing " self ", and name it as"
           " diagnosis_id on the recomposition.")

      :never_shown
      (str "Expired and never on a recording screen. " (:says exposure)
           " Recompose freely, or wait for the rank; nothing here is a"
           " verdict.")

      :unknown
      (str "Expired, and " (str/lower-case (str (:says exposure)))
           " Whether this was passed over or never seen cannot be said,"
           " so no diagnosis is owed and none can be honest.")

      :accepted
      "Accepted — the work is on its own rows, and nothing here is owed."

      :still_offered
      (str (if (= "lapsed" answered)
             "The week ran out and nobody answered; expire it, and the lesson follows."
             "Still offered.")
           (when mornings (str " So far " mornings "."))
           (when-not (:known exposure) (str " " (:says exposure)))))))

;; ── the rank's own reading (waymark-1uv.4) ──────────────────────────

(def lessons
  "Every lesson the document can teach, in the order the tally counts
  them — the two that owe a diagnosis first."
  [:shown_and_declined :shown_and_passed_over :never_shown :unknown
   :accepted :still_offered])

(defn- rank-reading
  "What the crown read for ONE outcome, as it stood when it was staged
  — the rank's own inputs through `feed/crown-inputs` at the row's own
  `created-at`, with `seen 0` because nobody had been shown it yet,
  and the sort key `feed/crown-key` places it by. A value the house no
  longer holds reads `:gone`, which lifts nothing, so a composer can
  tell *observed* from *retired since*."
  [eng rdef weights o]
  (let [inputs (-> (feed/crown-inputs {:eng eng :now (:created-at o)} rdef o)
                   (update :value #(or % :gone))
                   (assoc :seen 0 :cooled 0))]
    {:inputs inputs
     :key (feed/crown-key weights inputs (str (:id o)))}))

(defn- rank-says
  "The rank's reading of one outcome, narrated in the crown's own
  arithmetic — each input and what the household's number made of it
  — then where that reading stands among the outcomes in this
  document and what the crown shows a morning. It ends by saying
  whose verdict this is, because that is the sentence the never-shown
  pile exists to hear."
  ^String [weights {:keys [asked value declined days-left early judged]
                    :as inputs} place of take']
  (let [w (fn ^long [k] (long (get weights k 0)))
        lift (feed/crown-lift weights inputs)
        days (long (or days-left 0))
        parts (cond-> []
                asked
                (conj "it answers a person's own request, so it stands in the first tier whatever the numbers say")
                true
                (conj (case value
                        :declared (str "it serves a value this house declared, lifting it " (w :declared))
                        :observed "it serves a value an agent only observed, which lifts it nothing"
                        "the value it served is no longer held, which lifts it nothing"))
                true
                (conj (str days " day" (when (not= 1 days) "s") " left on its week"
                           " as staged, lifting it " (* (w :fresh) days)))
                (some? declined)
                (conj (str "the house said " (str/replace (str declined) "_" " ")
                           " about the line it recomposes, holding it "
                           (* (w :declined) (feed/reason-weight declined))))
                (and (some? early) (pos? (long early)))
                (conj (str early " day" (when (not= 1 (long early)) "s")
                           " early against the day the house named, holding it "
                           (* (w :early) (long early))))
                (some? judged)
                (conj (let [j (feed/judged-lift (w :judged) (:score judged))]
                        (str (:by judged) " scored it " (:score judged) ", "
                             (cond (pos? j) (str "lifting it " j)
                                   (neg? j) (str "holding it " (- j))
                                   :else "moving it nothing")))))]
    (str "As staged the rank read it at lift " lift ": "
         (str/join "; " parts) ". That reading places it " place " of " of
         " among the outcomes here"
         (when take'
           (str ", and the crown shows " take' " a morning by these numbers,"
                " house-wide"))
         "; which bundles it stood under on each morning it lost is not in"
         " the record. This is the rank's verdict, not the house's.")))

(defn- tally
  "The summary over every outcome this document folds: a count per
  lesson, how many owe a diagnosis, the never-shown pile with the lift
  each carried (highest first — the one nearest the page is the one to
  look at), the crown's numbers in force and the recipe they came
  from, and the one sentence that says which diagnosis is whose."
  [recipe source outcomes take']
  (let [counts (into {} (map (fn [l] [l (count (filter #(= l (:lesson %)) outcomes))]))
                     lessons)
        never (->> outcomes
                   (filter #(= :never_shown (:lesson %)))
                   (sort-by #(get-in % [:rank "lift"]) >)
                   (mapv (fn [o] {:self (:self o)
                                  :lift (get-in o [:rank "lift"])
                                  :place (get-in o [:rank "place"])})))]
    {:outcomes (count outcomes)
     :lessons counts
     :owing (count (filter :diagnosis_needed outcomes))
     :never_shown never
     :crown (cond-> {:crown_rank (feed/crown-rank-as-written recipe)
                     :recipe source}
              take' (assoc :take take'))
     :says (str "Two diagnoses, and this document keeps them apart:"
                " shown_and_declined and shown_and_passed_over are the"
                " HOUSE's — people saw these and answered them, or let them"
                " lapse, and each owes an insight before its line comes"
                " back — while never_shown is the RANK's — the crown had"
                " these and showed its take by the numbers under crown,"
                " and what this pile teaches is where your bundles stood,"
                " not what the house thinks of them: stage differently,"
                " propose new numbers through recipe_proposal, or wait for"
                " the floor, and write no insight about the house from it;"
                " unknown is neither, and accepted and still_offered owe"
                " nothing.")}))

;; ── the document ────────────────────────────────────────────────────

(defn- one-outcome
  [eng vis consents now weights take' of {:keys [inputs place]} o]
  (let [d (:data o)
        composer (str (:composed_by d))
        terminal? (contains? #{"accepted" "declined" "expired"} (name (:state o)))
        answered-at (if terminal? (:updated-at o) now)
        measured (measured-members consents composer answered-at)
        pieces (rows-of eng :outcome_piece {:outcome_id (:id o)} 10)
        ans (answered o now)
        exp (exposure eng vis o measured)
        reasons (reasons-about eng vis o pieces)
        lsn (lesson ans exp)
        after (rows-of eng :outcome {:supersedes (:id o)} 10)]
    (cond-> {:self (address eng :outcome (:id o))
             :card_ids (feed/card-ids :outcome (:id o))
             :goal (str (:goal d))
             :value (str (:value_name d))
             :staged_at (str (:created-at o))
             :answered ans
             :exposure exp
             :pieces (mapv (fn [p]
                             {:self (address eng :outcome_piece (:id p))
                              :says (str (get-in p [:data :says]))
                              :state (name (:state p))})
                           (sort-by :created-at pieces))
             :declined_count (long (or (:declined_count d) 0))
             :lesson lsn
             :diagnosis_needed (contains? needs-diagnosis lsn)
             ;; the rank's own reading (waymark-1uv.4): `why.crown`'s
             ;; shape, as staged, plus where it stands among the
             ;; outcomes here — on EVERY outcome, because a lift means
             ;; nothing alone and the never-shown pile is read against
             ;; the lifts of the ones that were shown
             :rank (assoc (feed/crown-as-cited weights inputs)
                          "place" place
                          "of" of
                          "says" (rank-says weights inputs place of take'))
             :says (lesson-says eng o ans exp reasons lsn)}
      (some? reasons) (assoc :reasons reasons)
      (:decided_by d) (assoc :answered_by (str (:decided_by d)))
      (:good_until d) (assoc :good_until (str (:good_until d)))
      (:not_before d) (assoc :not_before (str (:not_before d)))
      (some-> (:supersedes d) str not-empty)
      (assoc :supersedes (address eng :outcome (:supersedes d)))
      (some-> (:diagnosis_id d) str not-empty)
      (assoc :diagnosis (address eng :insight (:diagnosis_id d)))
      (seq after)
      (assoc :superseded_by
             (mapv (fn [a]
                     (cond-> {:self (address eng :outcome (:id a))
                              :state (name (:state a))}
                       (some-> (get-in a [:data :diagnosis_id]) str not-empty)
                       (assoc :diagnosis (address eng :insight
                                                  (get-in a [:data :diagnosis_id])))))
                   after)))))

(defn document
  "GET /api/-/diagnosis — the composer's own work order, as one
  document: every outcome the caller staged (newest first, `outcome-cap`
  of them, or the one `:outcome` names), each with its exposure, its
  answer, its reasons, its floor, its lesson and the rank's own
  reading of it; the duty sentence on top; the tally over all of them
  (waymark-1uv.4); and which of the two records this read was
  admitted to.

  `:recipe` and `:recipe-source` are the HOUSEHOLD's recipe as the
  route resolved it (`feed-recipe/for-reader` with no member — the
  household's row, or the built-in), because the composer has no
  feed of its own and the numbers a never-shown bundle lost under are
  the house's. The built-in stands in when the route hands none."
  [eng {:keys [principal visibility outcome recipe recipe-source]}]
  (let [now ((:now-fn eng))
        pid (str (:id principal))
        rdef (get (resources eng) :outcome)
        held? (some? rdef)
        recipe (or recipe feed/default-recipe)
        weights (feed/crown-rank-of recipe)
        take' (some #(when (= :outcomes (:section %)) (:take %)) (:order recipe))
        consents (rows-of eng :feed_view_consent {} 500)
        rows (cond
               (not held?) []
               outcome (into [] (filter #(= (str outcome) (str (:id %))))
                             (rows-of eng :outcome {:composed_by pid} 500))
               :else (rows-of eng :outcome {:composed_by pid} (inc outcome-cap)))
        capped (and (nil? outcome) (> (count rows) outcome-cap))
        rows (if capped (subvec rows 0 outcome-cap) rows)
        reads {:exposure (admits? visibility :feed_view)
               :reasons (admits? visibility :verdict_reason)}
        ;; the rank's reading of each row, and where that reading
        ;; places it among the rows here — sorted by the crown's own
        ;; key, so two the formula cannot tell apart are placed the
        ;; way the crown would place them
        readings (into {} (map (fn [o] [(:id o) (rank-reading eng rdef weights o)])) rows)
        places (into {}
                     (map-indexed (fn [i [id _]] [id (inc i)]))
                     (sort-by (comp :key val) readings))
        of (count rows)
        outcomes (mapv (fn [o]
                         (one-outcome eng visibility consents now weights take' of
                                      (assoc (get readings (:id o))
                                             :place (get places (:id o)))
                                      o))
                       rows)
        owed (count (filter :diagnosis_needed outcomes))]
    (p/wire-value
     (cond-> {:waymark "10"
              :kind "diagnosis"
              :self (str "/api/-/diagnosis" (when outcome (str "?outcome=" outcome)))
              :composer pid
              :at (str now)
              :summary (str "Diagnosis · " pid " · " (count outcomes)
                            " outcome" (when (not= 1 (count outcomes)) "s")
                            " · " owed " owing a diagnosis")
              :duty duty
              :reads (assoc reads
                            :says (str "The outcomes are your own and need no"
                                       " grant. Exposure reads other members'"
                                       " view rows and is "
                                       (if (:exposure reads) "admitted" "withheld")
                                       "; the reasons are the sayers' own and are "
                                       (if (:reasons reads) "admitted" "withheld")
                                       ". A leash admits each with"
                                       " {\"kind\": \"feed_view\", \"actions\": []}"
                                       " and {\"kind\": \"verdict_reason\","
                                       " \"actions\": []}."))
              :tally (tally recipe recipe-source outcomes take')
              :outcomes outcomes
              :notes (into []
                           (remove nil?)
                           [(when-not held?
                              "This engine serves no outcome kind; there is nothing here to diagnose.")
                            (when (and held? outcome (empty? rows))
                              (str "No outcome " outcome " composed by " pid
                                   " — a composer diagnoses what it staged, and nothing else."))
                            (str "This read writes nothing. The wall that holds the"
                                 " duty is outcome's own create door: a recomposition"
                                 " (supersedes) of an outcome that was shown and"
                                 " declined, or shown and passed over, is refused"
                                 " unless diagnosis_id names an insight citing it."
                                 " Never shown, or unknown, and the wall does not"
                                 " fire.")
                            (str "Read the tally before every sitting. Recompose"
                                 " only what the house answered — shown and"
                                 " declined, shown and passed over — and with a"
                                 " diagnosis; treat never_shown as the rank's"
                                 " verdict, not the house's, and answer it by"
                                 " staging differently or by proposing new numbers"
                                 " through recipe_proposal. Every outcome here"
                                 " carries the rank's own reading of it under"
                                 " rank, as staged."
                                 (when capped
                                   (str " The tally counts the " outcome-cap
                                        " newest outcomes this document folds;"
                                        " ?outcome=<id> asks about an older one"
                                        " by name.")))])}
       capped (assoc :reached_cap true)))))
