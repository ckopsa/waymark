(ns dayplan10.passage
  "A PASSAGE is a place inside something the house owns — a scene of a
  film, a stretch of an episode, a chapter or a page range of a book —
  spelled in the authority's own words and judged here, once, for the
  decision's passage launch (docs/spec-dayplan.md § decision, the
  fourth launch) and for the link flickr projects from it
  (workqueue10.sources.flickr/passage-link).

  THE GRAMMAR is the fraction law read the other way round
  (spec-media.md): every medium counts position in its own unit, and
  a person spells a place the way that medium counts —

    time     1:19:00 · 4:30 · S02E05 0:12:00      (H:MM:SS or M:SS,
             an episode S<season>E<episode> in front of it for a show)
    chapter  ch. 7 · ch 7 · chapter 7
    page     p. 213 · page 213 · pg 213
    percent  34% · pct 0.34

  A film, a show, an audiobook and an album are TIME; a book and a
  comic are chapter, page or percent. Nothing here normalizes the person's
  words away — the decision keeps `from` and `to` exactly as typed,
  and these fns only READ them: whether each reads at all, whether
  two count the same way, which comes first, and whether the grammar
  is the one the subject's medium counts in.

  Pure: no engine, no clock, no rows. The refusal sentences a guard
  builds from these answers live in the guard; this namespace only
  says what is so."
  (:require [clojure.string :as str]))

;; ── the four spellings ──────────────────────────────────────────────

(def ^:private time-re
  ;; an optional episode, then H:MM:SS or M:SS
  #"(?i)^(?:s(\d{1,2})\s*e(\d{1,3})\s+)?(\d{1,3}):(\d{2})(?::(\d{2}))?$")

(def ^:private chapter-re #"(?i)^(?:ch\.?|chapter)\s*(\d{1,4})$")
(def ^:private page-re #"(?i)^(?:p\.?|pg\.?|page)\s*(\d{1,5})$")
(def ^:private percent-re #"^(\d{1,3}(?:\.\d+)?)\s*%$")
(def ^:private pct-re #"(?i)^pct\s*(0(?:\.\d+)?|1(?:\.0+)?)$")

(defn- episode-text
  "The episode as flickr spells it, S02E05 — two digits each, more
  when the numbers ask."
  [season episode]
  (format "S%02dE%02d" (long season) (long episode)))

(defn parse
  "One place, read. nil when the words read in no grammar; else a map
  with :grammar — :time, :chapter, :page or :percent — and the number
  the grammar counts:

    :time     :seconds, and :episode {:season :episode :text} when
              the words named one
    :chapter  :n (the chapter)
    :page     :n (the page)
    :percent  :n (a fraction of the whole, 0..1, exact)"
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      (or (when-some [[_ season episode a b c] (re-matches time-re s)]
            (let [a (parse-long a) b (parse-long b) c (some-> c parse-long)
                  ;; H:MM:SS wants MM and SS under sixty; M:SS wants SS
                  ok? (if c (and (< b 60) (< c 60)) (< b 60))]
              (when ok?
                (cond-> {:grammar :time
                         :seconds (if c (+ (* a 3600) (* b 60) c) (+ (* a 60) b))}
                  season (assoc :episode {:season (parse-long season)
                                          :episode (parse-long episode)
                                          :text (episode-text (parse-long season)
                                                              (parse-long episode))})))))
          (when-some [[_ n] (re-matches chapter-re s)]
            {:grammar :chapter :n (parse-long n)})
          (when-some [[_ n] (re-matches page-re s)]
            {:grammar :page :n (parse-long n)})
          (when-some [[_ n] (re-matches percent-re s)]
            (let [f (/ (bigdec n) 100M)]
              (when (<= f 1M) {:grammar :percent :n f})))
          (when-some [[_ n] (re-matches pct-re s)]
            {:grammar :percent :n (bigdec n)})))))

(def grammar-words
  "Each grammar in plain words, for a refusal to name."
  {:time "a time (1:19:00, or S02E05 0:12:00 with the episode in front)"
   :chapter "a chapter (ch. 7)"
   :page "a page (p. 213)"
   :percent "a percent (34%)"})

(def the-grammar
  "The whole grammar in one sentence, for a refusal that could not
  read the words at all."
  (str "a time — 1:19:00, or S02E05 0:12:00 for a show — a chapter (ch. 7), "
       "a page (p. 213) or a percent (34%)"))

;; ── order ───────────────────────────────────────────────────────────

(defn- rank
  "A place as something `compare` orders within its grammar: a time
  is its episode first (none reads as the first), then its seconds;
  the text grammars are their number."
  [{:keys [grammar seconds n episode]}]
  (if (= :time grammar)
    [(long (or (:season episode) 0)) (long (or (:episode episode) 0)) (long seconds)]
    [n]))

(defn same-grammar?
  "Do two parsed places count the same way — both times, both
  chapters, both pages, both percents? Only then can one precede the
  other."
  [a b]
  (and (some? a) (some? b) (= (:grammar a) (:grammar b))))

(defn precedes?
  "Does parsed place `a` come strictly before parsed place `b`? False
  across grammars — a chapter and a page have no order between them —
  and false when they are the same place."
  [a b]
  (boolean
   (and (same-grammar? a b)
        (neg? (compare (rank a) (rank b))))))

;; ── the medium's grammar ────────────────────────────────────────────

(def medium-grammars
  "Which grammars each medium counts in — the fraction law's unit,
  per medium. A medium not named here has no opinion (a hub row
  whose medium was never said)."
  {"movie" #{:time}
   "show" #{:time}
   "audiobook" #{:time}
   "album" #{:time}
   "book" #{:chapter :page :percent}
   "comic" #{:chapter :page :percent}})

(defn a
  "The medium with its article — 'a movie', 'an audiobook', 'an
  album' — for a refusal that names it in a sentence."
  [medium]
  (let [m (str medium)]
    (str (if (re-find #"^[aeiouAEIOU]" m) "an " "a ") m)))

(defn medium-words
  "How a medium counts, in plain words — 'a time (1:19:00)' for a
  film, 'a chapter (ch. 7), a page (p. 213) or a percent (34%)' for a
  book. nil for a medium this grammar does not know."
  [medium]
  (case (str medium)
    "movie" "a time (1:19:00)"
    "audiobook" "a time (4:12:00)"
    "album" "a time (41:10)"
    "show" "an episode and a time (S02E05 0:12:00)"
    ("book" "comic") "a chapter (ch. 7), a page (p. 213) or a percent (34%)"
    nil))

(defn misfit
  "Why a parsed place does NOT read in a medium's grammar, in plain
  words — or nil when it does (or when the medium is one this grammar
  has no opinion about). A show's place names its episode; a film's,
  an audiobook's and an album's do not, because they have none."
  [place medium]
  (let [medium (str medium)
        allowed (get medium-grammars medium)]
    (when (and place allowed)
      (cond
        (not (contains? allowed (:grammar place)))
        (str "reads as " (get grammar-words (:grammar place))
             ", and " (a medium) "'s place is " (medium-words medium))

        (and (= "show" medium) (nil? (:episode place)))
        "names no episode, and a show's place is an episode and a time (S02E05 0:12:00)"

        (and (contains? #{"movie" "audiobook" "album"} medium) (some? (:episode place)))
        (str "names an episode, and " (a medium) " has none — its place is "
             (medium-words medium))

        :else nil))))
