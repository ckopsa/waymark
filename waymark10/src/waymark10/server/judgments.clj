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

(defn handle-transition!
  "One transition → the consequence it implies, or nothing.

      verdict judge, no corrects   walk the judgment's consequence on
                                   the subject, under the engine's own
                                   actor and the verdict's own key
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
            (when-some [door (some-> (get-in judgment [:data :consequence])
                                     str not-empty)]
              (let [kind (some-> (get-in verdict [:data :subject_kind])
                                 str not-empty)
                    id (some-> (get-in verdict [:data :subject_id])
                               str not-empty)]
                (when (and kind id (serves? eng (keyword kind)))
                  (walk-consequence! eng kind id door (:id verdict)))))))))
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
