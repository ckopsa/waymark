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
  `accepted`, `still_offered` — nothing to diagnose yet or ever."
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

;; ── the document ────────────────────────────────────────────────────

(defn- one-outcome
  [eng vis consents now o]
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
  answer, its reasons, its floor and its lesson; the duty sentence on
  top; and which of the two records this read was admitted to."
  [eng {:keys [principal visibility outcome]}]
  (let [now ((:now-fn eng))
        pid (str (:id principal))
        held? (some? (get (resources eng) :outcome))
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
        outcomes (mapv #(one-outcome eng visibility consents now %) rows)
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
                                 " fire.")])}
       capped (assoc :reached_cap true)))))
