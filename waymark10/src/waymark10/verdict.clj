(ns waymark10.verdict
  "A verdict is a row about a row (waymark-fp62.11 R-2, R-3).

  `waymark10.judgment` declares the question; this kind holds the
  answers. One row carries the judgment it was written under, the
  subject it is about — `{subject_kind, subject_id}`, the tickler's
  shape, so ONE KIND ANSWERS EVERY JUDGMENT IN THE HOUSE — the word,
  the remedy, and who said it.

  ── THE CREATE VERB IS `judge` ──

  A verdict is not *created*, it is *passed*: the word a house uses
  for this act is the word the door wears. `:create-action-names`
  already let a kind name its birth door something other than
  `create`, and this is the first kind in the tree to spend it. A
  scope that admits a seat to judge names `verdict.judge`, which is
  the sentence a person approving that scope actually reads.

  ── ONE SEAT, ONE SUBJECT, ONE VERDICT ──

  A second judge on the same subject under the same judgment is
  refused. Not because judging twice is rude but because the ledger
  under R-3 counts CORRECTIONS, and a seat that could quietly rewrite
  its own answer would be a seat whose measurement it also wrote. The
  exception is the correction, and the correction is a PERSON's: a new
  row citing the first, which the engine's own hand then overrules.
  The first row stays. A record that could be edited away is not a
  record of what anybody judged.

  ── WHY THE OVERRULE IS A HIDDEN DOOR ──

  `overrule` moves the cited row out of `said` and it is never a
  caller's tap: it fires from this kind's own `:on-create`, through
  `ctx :invoke`, inside the correction's own transaction. The wall
  admits the engine's own hand — a write opened INSIDE another write
  (`(:within ctx)`, the same fact `guards/names-a-row-that-stands`
  reads) and the system principal — and it is `:hide`, so the door is
  absent from every envelope a person or a model reads. A hidden guard
  owes no remedy: a sentence about the way out would leak the law the
  hiding conceals (factory10.mirror's `the-mirror-writes-this-row`,
  spelled here because this kind carries its own machine).

  ── THE REOPEN: A STORY THAT IS NOT OVER ──

  A correction needs an honest word for the new state, and some
  judgments have none: every word pull-request follow-up names is
  final, so a `ci_red` that turned out to be a bench bug since fixed
  could not be answered with \"keep following it\" (verdict aed87c06,
  pcore PR 554). `reopen` is that answer. It moves the standing
  verdict to `overruled` and writes NOTHING in its place, so the
  subject has no standing verdict under the judgment and is back in
  its queue — by the walk's one existing rule (`mcp/judged-subjects`
  subtracts `said` rows and nothing else), not by a second mechanism.
  The row stays; the note rides the transition and the row, so the
  record says who reopened it and why.

  Who may: the person who owns the judgment, and a seat whose scope
  names `verdict.reopen`. Never the seat that said it — its own error
  is what the reopen corrects, and a seat that could reopen its own
  verdict could skip any answer it did not want to stand behind. Only
  the answer that stands reopens, and a refusal of any other names the
  one that does. Verdicts ABOUT the reopened one (a later seat judging
  it) are rows of their own and do not move.

  ── WHAT THE LEDGER READS ──

  `judgment`, `said_by`, `subject_kind`, `subject_id` and `corrects`
  are all filterable by equality, so \"how many corrections under this
  judgment, and against which seat\" is a collection query and not a
  second table. That count is the measurement of the seat (R-3), and a
  measurement nobody can re-derive from rows is a number somebody has
  to be trusted about."
  (:require [clojure.string :as str]
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.judgment :as judgment]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def verdict-kind
  "The record's kind keyword — the definite marker, never a name
  string."
  :verdict)

(def standing-state
  "The state a verdict stands in. A verdict that has been corrected
  leaves it, and the ledger's counts read this word."
  "said")

;; ── reading the judgment this verdict is written under ──────────────

(defn- cited-judgment
  "The judgment row the body names, through the ctx cross-kind read —
  nil when no read is in scope (a storage-free probe, which advertises
  optimistically) or when the body names no row."
  [inp ctx]
  (when-some [read' (:read ctx)]
    (when-some [jid (some-> (:judgment inp) str str/trim not-empty)]
      (read' judgment/judgment-kind jid))))

(defn- verdict-words
  "The words one judgment names, in the order it names them."
  [jrow]
  (mapv #(str (:name %)) (get-in jrow [:data :verdicts])))

;; ── the walls the judge door keeps ──────────────────────────────────

(g/defguard judgment-is-promoted
  {:judges [:judgment]
   :reads [:judgment]
   :vars [:state]
   :remedies [:judgment/promote]
   :explain "This would be written under a judgment that is not in force — {state}. A verdict is an answer to a question the house has promoted; under a draft it would be an answer to a question still being written, and under a superseded one an answer to a question nobody is asking."}
  [_row inp ctx]
  ;; THE KIND SPEAKS FIRST about its own ref. The framework's
  ;; dangling-ref wall stands behind every create door and would say
  ;; "names no judgment"; this guard says it in the household's words
  ;; and in one breath with "and it is still a draft", which are the
  ;; two halves of one question a caller is asking.
  (if (nil? (:read ctx))
    (t/allow)
    (if-some [jrow (cited-judgment inp ctx)]
      (if (= :promoted (keyword (name (:state jrow))))
        (t/allow)
        (t/deny {:vars {:state (str "it is " (name (:state jrow)))}}))
      (t/deny {:vars {:state "no judgment this house holds"}}))))

(g/defguard verdict-is-in-the-vocabulary
  {:judges [:verdict :judgment]
   :reads [:judgment]
   :vars [:word :words]
   :open "The words are the judgment's own closed list, and that is the whole point of declaring a judgment: a house that could answer with any word at all could not count the answers. A word the judgment does not name is added by revising the judgment, which is a draft's door — or it is a different judgment."
   :explain "{word} is not a verdict this judgment names. It names {words}."}
  [_row inp ctx]
  (if-some [jrow (cited-judgment inp ctx)]
    (let [word (str (:verdict inp))
          words (verdict-words jrow)]
      (if (some #(= word %) words)
        (t/allow)
        (t/deny {:vars {:word word :words (str/join ", " words)}})))
    (t/allow)))

(g/defguard remedy-within-the-ceiling
  {:judges [:remedy :judgment]
   :reads [:judgment]
   :vars [:length :max]
   :open "The ceiling is the judgment's own, and it is a ceiling rather than a wish because a remedy nobody reads is a remedy nobody does. Say the next thing to do; the reasoning belongs where reasoning belongs."
   :explain "This remedy is {length} characters and this judgment allows {max}. A remedy is the one thing to do next, said in a sentence."}
  [_row inp ctx]
  (if-some [jrow (cited-judgment inp ctx)]
    (let [ceiling (long (get-in jrow [:data :remedy_max]
                                judgment/default-remedy-max))
          n (count (str (:remedy inp)))]
      (if (<= n ceiling)
        (t/allow)
        (t/deny {:vars {:length n :max ceiling}})))
    (t/allow)))

(g/defguard subject-kind-matches
  {:judges [:subject_kind :judgment]
   :reads [:judgment]
   :vars [:said :wanted]
   :open "A judgment is about one kind, so which kind a verdict is about is the judgment's answer and not the caller's. Judging a row of another kind is another judgment's job — and R-6 says the house may hold as many as it needs."
   :explain "This names a {said}, and this judgment judges {wanted}."}
  [_row inp ctx]
  (if-some [jrow (cited-judgment inp ctx)]
    (let [said (str (:subject_kind inp))
          wanted (str (get-in jrow [:data :subject_kind]))]
      (if (= said wanted)
        (t/allow)
        (t/deny {:vars {:said said :wanted wanted}})))
    (t/allow)))

(g/defguard subject-is-a-row
  {:judges [:subject_kind :subject_id]
   :reads [:storage]
   :vars [:kind :id]
   :open "A verdict points at a row rather than copying one, so the row has to be there to be pointed at. There is no door that makes it exist: either the subject is in this house or the judgment is about something else."
   :explain "{kind} {id} is not a row this house holds. A verdict is a row ABOUT a row, so a verdict about nothing is a record nobody could ever read back."}
  [_row inp ctx]
  (let [read' (:read ctx)
        rdef-of (:rdef-of ctx)
        k (some-> (:subject_kind inp) str str/trim not-empty)
        sid (some-> (:subject_id inp) str str/trim not-empty)]
    ;; the storage-free probe carries neither hook and advertises
    ;; optimistically — `remark/handed-back`'s posture, and the whole
    ;; tree's. A kind this engine does not serve is not judged here
    ;; either: what a verdict may be about is the JUDGMENT's answer,
    ;; and `subject-kind-matches` above has already given it.
    (if (or (nil? read') (nil? rdef-of) (nil? k) (nil? sid))
      (t/allow)
      (if-some [rdef (rdef-of k)]
        (if (some? (read' (:kind rdef) sid))
          (t/allow)
          (t/deny {:vars {:kind k :id sid}}))
        (t/allow)))))

(g/defguard one-standing-verdict-per-subject
  {:judges [:judgment :subject_id :corrects]
   :reads [:verdict]
   :vars [:subject]
   :open "One judgment asks one question about one row, so there is one standing answer. A second is not a fuller answer, it is two — and the ledger under R-3 counts corrections, which it cannot do if a seat may quietly write over its own word. What changes an answer is a person's correction, and that is a row of its own citing this one."
   :explain "This judgment has already been answered about {subject}, and that answer stands. Nothing was written and nothing was lost — the verdict is on the record, and a person who disagrees corrects it."}
  [_row inp ctx]
  (let [find' (:find ctx)]
    ;; the storage-free probe advertises optimistically — the write
    ;; path always carries the consult (`verdict_reason`'s own posture)
    (if (nil? find')
      (t/allow)
      (let [jid (str (:judgment inp))
            sid (str (:subject_id inp))
            cited (some-> (:corrects inp) str str/trim not-empty)
            standing (find' verdict-kind
                            {:judgment jid :subject_id sid
                             :state standing-state}
                            {:limit 2})]
        (cond
          (empty? standing) (t/allow)
          ;; the correction is the exception, and it is narrow: this
          ;; body must cite THE row that stands, not merely cite
          ;; something. A correction naming a different verdict would
          ;; leave two standing answers behind it.
          (and cited (= 1 (count standing)) (= cited (str (:id (first standing)))))
          (t/allow)
          :else (t/deny {:vars {:subject (str (:subject_kind inp) " " sid)}}))))))

(g/defguard a-person-corrects
  {:judges [:corrects :judgment :subject_id]
   :reads [:principal :verdict]
   :vars [:problem]
   :open "Correcting is a person's act and stays one, and it is bound to the answer it corrects: the same judgment, the same row, and an answer that still stands. An agent that could overrule a verdict — its own or another agent's — would be writing its own measurement, and the count of corrections is precisely what R-3 measures a seat by. What an agent may do about a verdict it disagrees with is say so where an agent may say things."
   :explain "{problem}"}
  ;; ONE WALL, TWO HALVES, and they are one law: who may correct, and
  ;; what a correction may cite. Splitting them would have the door
  ;; refuse an agent's wandering citation for the citation, which is
  ;; the smaller of the two things wrong with it.
  [_row inp ctx]
  (let [cited (some-> (:corrects inp) str str/trim not-empty)
        p (:principal ctx)
        read' (:read ctx)]
    (if (nil? cited)
      (t/allow)
      ;; "a person" is `(not= :agent …)` everywhere in this tree
      ;; (`remark/words-do-not-answer`, `guards/unless-granted`): the
      ;; actor types are human, agent and system, and the one this law
      ;; is about is the agent.
      (if (= :agent (:type p))
        (t/deny {:vars {:problem
                        (str "A correction is a person's answer and you are "
                             (or (not-empty (str (:display p))) (:id p) "an agent")
                             ". The verdict that stands stays where it is "
                             "until somebody in this house says otherwise.")}})
        ;; the storage-free probe carries no read and advertises
        ;; optimistically; the write path always carries it
        (if-some [prior (when read' (read' verdict-kind cited))]
          (if-some [problem
                    (cond
                      (and (not= standing-state (name (:state prior)))
                           (some? (get-in prior [:data :reopened_by])))
                      (str "The verdict this cites was reopened — nothing"
                           " stands on this subject under this judgment, so"
                           " there is nothing to correct. Judge it afresh:"
                           " the same verdict, with no corrects.")
                      (not= standing-state (name (:state prior)))
                      (str "The verdict this cites has already been corrected"
                           " — it is " (name (:state prior)) ", and only the"
                           " answer that stands can be the one a correction"
                           " replaces.")
                      (not= (str (get-in prior [:data :judgment]))
                            (str (:judgment inp)))
                      "The verdict this cites was written under a different judgment."
                      (not= (str (get-in prior [:data :subject_id]))
                            (str (:subject_id inp)))
                      "The verdict this cites is about a different row.")]
            (t/deny {:vars {:problem problem}})
            (t/allow))
          (t/allow))))))

;; ── the engine's own door ───────────────────────────────────────────

(g/defguard the-engine-overrules-this-row
  {:reads [:principal :within]
   :hide true
   :explain "The engine overrules a verdict. A person corrects one, and a correction is a row of its own."}
  [_row _inp ctx]
  ;; A WRITE OPENED INSIDE ANOTHER WRITE is the engine's own hand —
  ;; `(:within ctx)`, the same fact `guards/names-a-row-that-stands`
  ;; reads to know a client did not type this. The correction's
  ;; `:on-create` walks this door through `ctx :invoke`, so the only
  ;; caller that ever reaches it is the create it belongs to. The
  ;; system principal is admitted beside it for the sweeps' sake, the
  ;; mirror's own test one module over.
  (if (or (some? (:within ctx)) (= :system (:type (:principal ctx))))
    (t/allow)
    (t/deny)))

;; ── the reopen's walls ──────────────────────────────────────────────

(def reopen-consequence
  "The sentence the reopen's confirm gate asks a caller to echo back.
  A def of its own because the test and the door must read one
  spelling of it."
  "The subject re-enters this judgment's queue and the seat that walks it will judge it again.")

(defn- standing-others
  "The `said` verdicts on this row's subject under this row's
  judgment, other than this row, newest first — or nil when no find
  is in scope (the storage-free probe advertises optimistically)."
  [row ctx]
  (when-some [find' (:find ctx)]
    (->> (find' verdict-kind
                {:judgment (str (get-in row [:data :judgment]))
                 :subject_id (str (get-in row [:data :subject_id]))
                 :state standing-state}
                {:limit 5 :newest-first true})
         (remove #(= (str (:id %)) (str (:id row))))
         (seq))))

(defn- naming
  "One verdict, as a refusal names it: its id and its word."
  [v]
  (str (:id v) " (" (get-in v [:data :verdict]) ")"))

(defn- what-stands-instead
  "The reopen's `:out-of-state-says`: a verdict already out of `said`
  is not the answer that stands, so the refusal names the one that
  does — or says that nothing does, which means the subject is
  already back in the queue and there is nothing to reopen."
  [row ctx]
  (when (:find ctx)
    (if-some [s (first (standing-others row ctx))]
      (str "Only the answer that stands can be reopened, and the one"
           " standing on this subject under this judgment is " (naming s)
           ". Reopen that one.")
      (str "Nothing stands on this subject under this judgment, so it is"
           " already in the judgment's queue."))))

(g/defguard only-the-standing-verdict-reopens
  {:reads [:verdict]
   :vars [:standing]
   :open "One judgment has one standing answer about one row, and that answer is the only one a reopen can take back. Reopening an older one would leave the newer standing and the queue unchanged — a record that says the story reopened while the walk still skips it."
   :explain "A newer verdict stands on this subject under this judgment — {standing}. Only the answer that stands can be reopened; reopen that one."}
  [row _inp ctx]
  ;; the `said` half of the law. A row already `overruled` never
  ;; reaches a guard — the machine refuses it at step 5, and
  ;; `what-stands-instead` names the standing verdict there. This wall
  ;; is the belt under the one-standing invariant: a subject with two
  ;; `said` rows (a race the unique index is not there to catch) takes
  ;; back only the newest.
  (let [others (standing-others row ctx)
        newer (first (filter #(pos? (compare (str (:created-at %))
                                             (str (:created-at row))))
                             others))]
    (if-some [s newer]
      (t/deny {:vars {:standing (naming s)}})
      (t/allow))))

(g/defguard who-may-reopen
  {:reads [:principal :judgment]
   :vars [:problem]
   :open "A reopen says a seat's answer was wrong in a way the judgment has no word for, so it is the judgment's owner's to say, or a seat's the household has named for it. It is never the saying seat's: its own error is what a reopen exists to correct, and a seat that could reopen its own verdict could skip any answer it did not want to stand behind — which is the measurement R-3 counts it by."
   :explain "{problem}"}
  [row _inp ctx]
  (let [p (:principal ctx)
        me (str (:id p))
        author (str (get-in row [:data :said_by]))
        read' (:read ctx)]
    (cond
      (= :system (:type p)) (t/allow)

      ;; "a person" is `(not= :agent …)` everywhere in this tree
      ;; (`a-person-corrects` above): the hand this law is about is
      ;; the agent's
      (and (= :agent (:type p)) (= me author))
      (t/deny {:vars {:problem
                      (str "This verdict is yours, and a seat does not"
                           " reopen its own answer. Its owner or another"
                           " seat named for reopening may; if you think it"
                           " was wrong, say so where an agent may say things.")}})

      ;; any other seat reached this door through a scope that names
      ;; verdict.reopen — the router's default deny answers the rest
      (= :agent (:type p)) (t/allow)

      (nil? read') (t/allow)

      :else
      (let [jid (str (get-in row [:data :judgment]))
            owner (some-> (read' judgment/judgment-kind jid) :owner str)]
        (if (or (nil? owner) (= me owner))
          (t/allow)
          (t/deny {:vars {:problem
                          (str "This judgment is " owner "'s, and reopening"
                               " a verdict under it is theirs to do, or a"
                               " seat's they name for it. A person who"
                               " disagrees with the verdict corrects it.")}}))))))

;; ── the hands ───────────────────────────────────────────────────────

(defhandler stamp-and-overrule
  [row ctx]
  ;; TWO THINGS, and the second is what makes a correction a
  ;; correction. The stamp is `verdict_reason/stamp-the-sayer`'s —
  ;; whoever judges is whose verdict it is, never the body's to give.
  ;; The overrule rides `ctx :invoke` inside this create's own
  ;; transaction, so the pair lands or neither does: there is no moment
  ;; in which two answers to one question both stand.
  (let [row (assoc-in row [:data :said_by] (:id (:principal ctx)))
        cited (some-> (get-in row [:data :corrects]) str str/trim not-empty)]
    (when (and cited (:invoke ctx))
      ((:invoke ctx) verdict-kind cited :overrule nil))
    row))

(defhandler record-the-reopen
  [row inp ctx]
  ;; the transition carries the note already (`:record true`); the row
  ;; carries it too, so the verdict's own screen — and the correction
  ;; wall above — can read that this answer was taken back rather than
  ;; answered again. The stamp is whoever reopened, never the body's.
  (-> row
      (assoc-in [:data :reopened_by] (:id (:principal ctx)))
      (assoc-in [:data :reopen_note] (:note inp))))

;; ── the law, written down as a scenario ─────────────────────────────
;;
;; ONE SCENARIO, AND IT IS THE ONE THAT WRITES NOTHING. Every guard
;; here reads rows, so the whole chain is the conformance tier's
;; (`waymark10.scenario`'s chain rule, which is why `verdict_reason`'s
;; body-only walls are deferred too) — and a conformance-tier scenario
;; that stages `:given` rows writes them into whatever database the
;; suite is holding, under FIXED names. A judgment is `:unique` by
;; name, so a second suite in the same run would find the first one's
;; setup already standing (the sentence workqueue10's fixture already
;; carries about dayplan10's scenarios). The rest of this kind's law is
;; proved in `waymark10.verdict-test`, where a fixture owns its own
;; database and can stage as much as it likes.

(defscenario a-verdict-answers-a-judgment-in-force
  "A verdict is an answer to a question the house has promoted. Under
   a draft it would answer a question still being written, and under a
   judgment that names no row at all it would answer nothing — so the
   door says which, in one sentence, and names the door that puts a
   judgment in force."
  {:kind    :verdict
   :attempt :judge
   :input   {:judgment "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0"
             :subject_kind "judgment"
             :subject_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B1"
             :verdict "infra"
             :remedy "Re-run the job on a clean runner."}
   :as      {:id "colton" :type :person}
   :expect  {:refused :judgment-is-promoted
             :because "no judgment this house holds"
             :remedies [:judgment/promote]}})

;; ── the prose the doors wear ────────────────────────────────────────

(def ^:private prose
  {:judgment
   {:x-display
    {:label "Under which judgment"
     :help "The judgment this verdict answers. It decides which words you may write, how long the remedy may be, and which kind the subject must be."}}
   :subject_kind
   {:x-display
    {:raw true
     :label "What sort of row"
     :help "The kind of the row being judged, by its own token. It must be the kind the judgment names."}}
   :subject_id
   {:x-display
    {:label "Which row"
     :help "That row's own id, the one in its address bar. The verdict holds a pointer, never a copy, so the row it is about stays where it lives."}}
   :verdict
   {:x-display
    {:label "The verdict"
     :help "One of the words this judgment names, exactly as it spells it. The judgment's own screen lists them with what each one means."}}
   :remedy
   {:examples ["Re-run the job. The runner lost its network before the checkout, and the code did not change."]
    :x-display
    {:widget "prose"
     :label "What to do about it"
     :help "The one thing to do next, in a sentence — not why, and not a report. The judgment sets how long this may be; two hundred and forty characters is the usual ceiling."}}
   :said_by
   {:x-display
    {:hidden true
     :label "Said by"
     :spelled-by-hand "Refused here: whoever judges is whose verdict it is, and the engine stamps it from the hand that posted."
     :help "Whose verdict this is. The engine writes it from whoever judged; no body may name somebody else."}}
   :reopened_by
   {:x-display
    {:hidden true
     :label "Reopened by"
     :spelled-by-hand "Refused here: whoever reopens is who reopened it, and the engine stamps it from the hand that posted."
     :help "Who took this verdict back so its subject is judged again. The engine writes it from whoever reopened."}}
   :reopen_note
   {:x-display
    {:label "Why it was reopened"
     :help "The one sentence the reopener gave for putting the subject back in the judgment's queue."}}
   :corrects
   {:x-display
    {:label "Corrects"
     :help "The verdict this one replaces, when this is a person's correction. Cite the answer that stands, under the same judgment and about the same row; it moves to overruled and stays on the record."}}})

(defn- entry [k extra form]
  [k (merge (get prose k) extra) form])

;; ── :verdict — a row about a row ────────────────────────────────────

(defresource verdict
  {:kind :verdict
   :plural "verdicts"
   ;; the judgment's own reason, one kind over: a verdict is a record
   ;; of an answer already given, so a :primary spelling would card it
   ;; beside the work it is about.
   :nav :system
   ;; THE BIRTH DOOR IS NAMED FOR WHAT IT DOES. See the ns docstring:
   ;; a scope admitting a seat here reads `verdict.judge`, which is the
   ;; sentence a person approving that scope is actually approving.
   :create-action-names #{:judge}
   :states [:said :overruled]
   :initial :said
   :terminal #{:overruled}
   ;; An overruled verdict was LET GO — a person said otherwise — and
   ;; no verdict is ever ACCOMPLISHED: a verdict does not finish, it
   ;; either stands or it has been answered again. Spelled out, because
   ;; the machine's default would read the tomb as a deed.
   :over {:accomplished #{} :let-go #{:overruled}}
   :summary "{data.verdict} on {data.subject_kind} {data.subject_id} · {state}"
   :label-template "{data.verdict}"
   :display {:title "The verdicts this house has passed"}
   :links [{:rel "judgment" :kind :judgment
            :href "/api/judgments/{data.judgment}"
            :summary "The judgment this verdict answers"}
           {:rel "corrects" :kind :verdict
            :href "/api/verdicts/{data.corrects}"
            :summary "The verdict this one corrected"}]
   :schema
   [:map
    ;; :in on the two fields a downstream seat names MORE THAN ONE of:
    ;; a steward follows several desks' judgments, an engineer wakes on
    ;; two words of one. Under :eq alone, `judgment=A,B` is one literal
    ;; value, and a queue or a wake_on filter spelled that way matched
    ;; nothing — no refusal, just a seat that was never woken and a
    ;; queue that was always empty
    (entry :judgment {:kind :judgment :filter #{:eq :in}} :waymark/ref)
    (entry :subject_kind {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :subject_id {:filter #{:eq}} [:string {:min 1 :max 64}])
    (entry :verdict {:filter #{:eq :in}} [:string {:min 1 :max 40}])
    (entry :remedy {} [:string {:min 1 :max 1000}])
    (entry :said_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    (entry :corrects {:optional true :kind :verdict :filter #{:eq}}
           [:maybe :waymark/ref])
    ;; written by `reopen` and by nothing else — not in the create
    ;; model, so no body may name them
    (entry :reopened_by {:optional true :filter #{:eq}}
           [:maybe [:string {:max 128}]])
    (entry :reopen_note {:optional true} [:maybe [:string {:max 240}]])]
   ;; :said_by is NOT in the create model, and that is the difference
   ;; from `verdict_reason`'s deliberate redundancy. There the field is
   ;; declared so a body naming somebody else can be refused by NAME;
   ;; here the door's whole vocabulary is the judgment's, so a stray
   ;; key is exactly what a body naming a sayer is — and "unknown
   ;; field" is the honest sentence for a field this door never offers.
   :create-schema
   [:map
    (entry :judgment {:kind :judgment} :waymark/ref)
    (entry :subject_kind {} [:string {:min 1 :max 64}])
    (entry :subject_id {} [:string {:min 1 :max 64}])
    (entry :verdict {} [:string {:min 1 :max 40}])
    (entry :remedy {} [:string {:min 1 :max 1000}])
    (entry :corrects {:optional true :kind :verdict} [:maybe :waymark/ref])]
   :filterable {:state #{:eq :in}}
   :sortable {:fields [:created_at] :default "-created_at"}
   ;; whoever judged reads their own verdicts with no grant — an answer
   ;; you cannot re-read is not an answer. Reading only: the doors here
   ;; are the engine's (`overrule`) and the household's (`judge`, which
   ;; a seat holds by a scope a person approved by name).
   :own-surface {:by :said_by}
   :on-create stamp-and-overrule
   ;; THE JUDGMENT FIRST, because every other wall reads it: which
   ;; words are legal, how long a remedy may be and which kind the
   ;; subject must be are all the judgment's answers, so a body under a
   ;; judgment that is not in force hears that and nothing else. Then
   ;; the body against the judgment, then the house.
   ;;
   ;; `a-person-corrects` stands BEFORE `one-standing-verdict-per-
   ;; subject` and not after it, though it is the narrower law: the
   ;; standing-answer wall READS the citation (a correction is its one
   ;; exception), so a citation that is not a person's, or that wanders
   ;; to another row, has to be answered as the wrong CITATION rather
   ;; than as "this was already answered" — which is true, and is not
   ;; what is wrong.
   :create-guards [judgment-is-promoted
                   verdict-is-in-the-vocabulary
                   remedy-within-the-ceiling
                   subject-kind-matches
                   subject-is-a-row
                   a-person-corrects
                   one-standing-verdict-per-subject]
   :actions
   {:overrule
    {:from #{:said} :to :overruled
     :guards [the-engine-overrules-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The verdict stops standing and stays on the record under the name of whoever said it. The correction that replaced it cites it, so both rows read as one story."}
     :display {:label "Overrule" :order 1
               :description "The engine's own hand: a correction has replaced this verdict"}}
    ;; THE STORY IS NOT OVER (see the ns docstring). Same tomb as
    ;; `overrule`, and the difference is what is NOT written: no
    ;; replacement, so nothing stands and the walk takes the subject
    ;; back. A normal, visible transition, so a seat's `wake_on` can
    ;; name it — and a judgment seat that wrote none wakes on it by
    ;; default (`seats/effective-wake-on`).
    :reopen
    {:from #{:said} :to :overruled
     :input [:map
             ;; one line, not a prose box: a reopen says why in a
             ;; sentence, and a `composition` field would carry a draft
             ;; policy and a scaffold for what is a single remark
             [:note {:examples ["The bench bug that turned this red has been fixed; keep following it."]
                     :x-display
                     {:label "Why it is reopened"
                      :help "One sentence: what the verdict missed, or what changed since. It stays on the record beside your name."}}
              [:string {:min 1 :max 240}]]]
     :record true
     :guards [who-may-reopen
              only-the-standing-verdict-reopens]
     :out-of-state-says what-stands-instead
     :handler record-the-reopen
     :safety {:idempotent true :reversible false :confirm true
              :consequence reopen-consequence}
     :display {:label "Reopen" :order 2}}}
   :scenarios [a-verdict-answers-a-judgment-in-force]})
