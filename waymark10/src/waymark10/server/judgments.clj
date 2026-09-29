(ns waymark10.server.judgments
  "The consequence consumer (bead waymark-fp62.11, R-5): the mirror
  that walks the subject's own door after a verdict is said.

  THE VERDICT IS THE PRODUCT. A seat that says a judgment produces
  one row per subject and nothing else — the sitting never touches
  the subject, and its scope never names a door on it. A judgment
  with no `consequence` therefore has NO EFFECT on the subject at
  all, which is the honest reading of a judgment whose job is to
  record what it found. When the judgment does name one, this file is
  what walks it, after the fact and out of band, so the seat's
  authority stays what R-4 says it is: read the subjects, write the
  verdicts.

  ── why a consumer and not a handler ───────────────────────────────

  The verdict's own handler could invoke the subject's door in the
  same transaction, and that is exactly the coupling to refuse. A
  door that refuses — a subject already moved, a guard that says no,
  a kind the judgment named and this engine does not serve — would
  then roll the VERDICT back, and the judgment would record nothing
  because the consequence was unavailable. The verdict is the
  product; the consequence is a consequence. So it rides a named,
  durable log consumer (`server/consumers`, the wakes consumer's own
  shape and its own cursor) and a refusal is a warning.

  The replay is the consumer's contract, and the key is what makes it
  free: every invoke carries `judgment:<verdict id>`, so a
  re-delivered transition answers the stored result and no second
  door opens.

  A CORRECTION CARRIES NO CONSEQUENCE. A second `judge` naming
  `corrects` overrules the first verdict, and the subject was already
  walked once for it. Walking again would be one subject taken twice
  — so a verdict with `corrects` is heard and passed over, and the
  person who overruled decides what the subject needs now.

  Not to be confused with `waymark10.server.judgment`, its singular
  neighbour, which is the law overlay that judges a ROW under the
  revision it was written at. This file knows nothing about that one;
  they share four letters and no seam.

  A VERDICT MAY ALSO FILE A TICKET. A judgment whose `files_ticket_on`
  names the verdict's word files ONE draft ticket for the subject,
  under the engine's hand, so a remedy a person must act on reaches
  the groomers. The key is the judgment's and the subject's, never the
  verdict's: a second verdict on the same subject finds it taken and
  files nothing more.

  Nothing here re-throws. A throwing consumer parks its cursor, and a
  parked cursor stops every later verdict's consequence with it."
  (:require [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 judgments: " parts))))

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:judgments)."
  :judgments)

(defn- serves? [eng kind] (contains? (inv/resources eng) kind))

(def judged-page
  "The most standing verdicts one walk subtracts by. A judgment whose
  said verdicts outrun this in a single queue has more decided
  subjects than one page can hold, and the honest fix there is a
  narrower `queue`, not a longer read. The count wake reads the same
  bound for its sealed transcripts (`wakes/unjudged-transcripts`)."
  500)

(defn judged-subjects
  "The subject ids this judgment has already spoken on: every verdict
  of it still `said`. An `overruled` row is not here on purpose — a
  correction overrules the first and the second verdict is the one
  standing, so a subject leaves the queue once and stays gone.

  …until its verdict is REOPENED. `verdict.reopen` moves the standing
  row to `overruled` and writes nothing in its place, so this set no
  longer holds the subject and the next walk hands it back. That is
  the whole of the reopen's queue mechanism: this one rule, read the
  same way, and no second list of subjects to re-admit.

  One home for the sit's walk (`mcp/walk-of`) and the count wake
  (`wakes/unjudged-transcripts`), so the two cannot drift."
  [eng judgment-id]
  (if (serves? eng :verdict)
    (into #{}
          (keep #(some-> (get-in % [:data :subject_id]) str not-empty))
          (store/with-tx (:storage eng)
            (fn [tx] (store/query-rows (:storage eng) tx :verdict
                                       {:judgment (str judgment-id)
                                        :state "said"}
                                       {:limit judged-page}))))
    #{}))

(defn serving?
  "Does this engine have the two kinds to hear at all? The module's
  hook asks it, `schedules/serving?`'s precedent: a consumer thread
  on an engine that can never match anything is a thread and a cursor
  row for nothing."
  [eng]
  (and (serves? eng :judgment) (serves? eng :verdict)))

(defn- raw-row
  "One stored row, or nil — wakes.clj's reader verbatim, and its
  reason: the engine's own hand reads the law it is about to apply,
  never the sitter's."
  [eng kind id]
  (when (and id (serves? eng kind))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

(defn- walk-consequence!
  "The judgment's door on the subject, opened as the engine. Best
  effort by design: a refusal is the subject's own law saying no — a
  row already moved, a guard unmet, a door this state does not afford
  — and the verdict stands either way. → true when the door opened,
  nil when it said no."
  [eng subject-kind subject-id door verdict-id]
  (try
    (inv/invoke! eng (keyword subject-kind) (str subject-id) (keyword door) {}
                 {:principal schedules/system-actor
                  :idempotency-key (str "judgment:" verdict-id)})
    true
    (catch Exception e
      (warn! "the consequence " door " on " subject-kind " " subject-id
             " would not run — " (ex-message e))
      nil)))

(defn- short-id [id]
  (let [s (str id)] (subs s 0 (min 8 (count s)))))

(defn ticket-title
  "'<verdict> on <seat name>'s sitting <short id>' when the subject is a
  sitting and its seat has a name; '<verdict> on <kind> <short id>'
  otherwise."
  [verdict-name seat-name subject-kind subject-id]
  (if seat-name
    (str verdict-name " on " seat-name "'s sitting " (short-id subject-id))
    (str verdict-name " on " subject-kind " " (short-id subject-id))))

(defn- seat-name-of
  "The name of the seat a sitting belongs to, or nil for any other kind."
  [eng subject-kind subject-id]
  (when (= "sitting" subject-kind)
    (when-some [seat-id (some-> (raw-row eng :sitting subject-id)
                                (get-in [:data :seat]) str not-empty)]
      (or (some-> (raw-row eng :seat seat-id) (get-in [:data :name])
                  str not-empty)
          seat-id))))

(defn- ticket-key
  "One ticket per judgment per subject: the idempotency key it is filed
  under, and what a second verdict finds already taken."
  [judgment subject-kind subject-id]
  (str "judgment-ticket:" (:id judgment) ":" subject-kind ":" subject-id))

(defn- files-ticket? [judgment verdict]
  (contains? (set (map str (get-in judgment [:data :files_ticket_on])))
             (str (get-in verdict [:data :verdict]))))

(defn- file-ticket!
  "The draft ticket a listed verdict earns, filed as the engine. Best
  effort, `walk-consequence!`'s posture: a refusal is a warning and the
  verdict stands. → true when a ticket was filed, nil otherwise."
  [eng judgment verdict subject-kind subject-id]
  (when (serves? eng :ticket)
    (let [st (:storage eng)
          k (ticket-key judgment subject-kind subject-id)
          word (str (get-in verdict [:data :verdict]))]
      (when-not (store/with-tx st (fn [tx] (store/idempotency-lookup st tx k :ticket)))
        (try
          (inv/create! eng :ticket
                       {:title (ticket-title word
                                             (seat-name-of eng subject-kind subject-id)
                                             subject-kind subject-id)
                        :detail (str (get-in verdict [:data :remedy])
                                     "\n\nSubject: " subject-kind " " subject-id
                                     "\nVerdict: " (:id verdict)
                                     "\nJudgment: " (get-in judgment [:data :name]))
                        :repo (or (some-> (get-in judgment [:data :ticket_repo])
                                          str not-empty)
                                  "ckopsa/waymark")
                        :type "bug"
                        :priority 2}
                       {:principal schedules/system-actor
                        :idempotency-key k})
          true
          (catch Exception e
            (warn! "the ticket for " subject-kind " " subject-id
                   " would not file — " (ex-message e))
            nil))))))

(defn handle-transition!
  "One transition → the consequence it implies, or nothing.

      verdict judge, no corrects   walk the judgment's consequence on
                                   the subject, under the engine's own
                                   actor and the verdict's own key;
                                   and file one draft ticket when the
                                   word is in `files_ticket_on`
      verdict judge, corrects      nothing: the subject was walked for
                                   the verdict this one overrules
      everything else              nothing

  Never throws, for `consumers`' own reason: a parked cursor stops
  every later verdict's consequence, and a judgment naming a door
  this engine cannot open is not a reason to stop the house."
  [eng t]
  (try
    (when (and (= :verdict (:kind t)) (= :judge (:action t)))
      (when-some [verdict (raw-row eng :verdict (:resource-id t))]
        (when (nil? (some-> (get-in verdict [:data :corrects]) str not-empty))
          (when-some [judgment (raw-row eng :judgment
                                        (get-in verdict [:data :judgment]))]
            (let [kind (some-> (get-in verdict [:data :subject_kind])
                               str not-empty)
                  id (some-> (get-in verdict [:data :subject_id])
                             str not-empty)]
              (when (and kind id (serves? eng (keyword kind)))
                (when-some [door (some-> (get-in judgment [:data :consequence])
                                         str not-empty)]
                  (walk-consequence! eng kind id door (:id verdict)))
                (when (files-ticket? judgment verdict)
                  (file-ticket! eng judgment verdict kind id))))))))
    (catch Exception e
      (warn! "transition " (:id t) " could not be handled — " (ex-message e))
      nil))
  nil)

(defn consumer-fn
  "The consumer's function of one transition. Public because a test
  drains it directly (`consumers/drain-consumer!`), which is how this
  suite stays deterministic — wakes/consumer-fn's own shape."
  [eng]
  (fn [t] (handle-transition! eng t)))

(defn start-judgments!
  "Register the durable log consumer that walks a judgment's
  consequence after a verdict. Returns the handle `stop-judgments!`
  takes. opts: :dispatcher, :poll-ms, :from-origin?."
  ([eng] (start-judgments! eng {}))
  ([eng opts]
   (consumers/register-consumer!
    eng consumer-name (consumer-fn eng)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop-judgments! [consumer]
  (some-> consumer consumers/stop-consumer!)
  nil)
