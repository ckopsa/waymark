(ns waymark10.server.belief
  "THE UPDATER'S PASS (waymark-bug, docs/spec-hypotheses.md § 'The
  hypothesis kind' and § 'The updater'): the engine's own hand on the
  posterior.

  `waymark10.belief` holds the arithmetic and nothing else. This file
  holds the three things the arithmetic cannot do for itself: find the
  atoms a hypothesis is fed by, read the table the household wrote,
  and cache the answer on the row. It is a MAINTENANCE write —
  `store/update-data!`, the clock sweeper's own door: the document
  moves, the version does not, and no transition is logged. A
  posterior is not a thing that happened to a row; it is what the row
  already meant.

  ── NO DOOR SETS ANY OF THIS ─────────────────────────────────────────

  `posterior`, `posterior_log_odds`, `movement_7d`, `atoms`,
  `atom_count` and `last_moved` are absent from `hypothesis`'s
  `:create-schema` and from every action's `:input`. The walls are
  STRUCTURAL rather than guarded — there is no door to refuse at —
  and the promise the structure buys is the row's own: a hypothesis is
  A CACHE OF AN ARITHMETIC ANYONE CAN REDO. Delete every posterior in
  the store and one pass rebuilds them identically. The spec's fork
  (g) records the choice: a hand-set posterior is an opinion wearing
  arithmetic's coat.

  ── WHAT AN ATOM OF A HYPOTHESIS IS ──────────────────────────────────

  A published or taken `insight` that carries one of the nine evidence
  words AND touches this hypothesis — where *touches* is address
  overlap and nothing cleverer, `not-a-twin`'s honest boundary read
  one kind over:

    1. the finding cites a row the hypothesis is ABOUT, or
    2. the finding cites the HYPOTHESIS ITSELF,
       `/api/hypotheses/<id>` — the direct link a reading writes when
       it wants an atom on the belief rather than on the subject.

  Both are the same act — a finding said what it read — and unioning
  them is what makes the backfill possible: a reading may mint a
  hypothesis `about` the rows a standing pile of atoms already cites,
  and every one of them is linked the moment the row exists, with no
  edit to any finding. No new field on `insight`, no second citation
  vocabulary, and no membership table: the addresses ARE the link
  (fork (i), and § 'Embedding-based entity resolution' for why nothing
  here compares sentences).

  DISMISSED FINDINGS ARE NOT ATOMS. The house said that claim was too
  thin, not backed, already known or not true; a belief hung on a
  claim the house rejected is not a belief with evidence under it.
  This is also the reading's ONLY lawful way to take an atom back out
  of a fold — `insight.dismiss`, in public, with a word attached —
  because the extraction-blind rule forbids the quiet retype that
  would do it invisibly.

  ── TWO PASSES, AND WHICH ONE ANSWERS FOR WHAT ───────────────────────

  A belief's cached fold is a projection of two moving things: the
  atoms that touch it, and how old they are.

  THE CLOCK's pass (`sweep-beliefs!`, nightly) answers for the second.
  Decay is a per-day arithmetic against half-lives measured in months,
  so a night is the honest interval and the pass is cheap because it
  is rare.

  THE EVIDENCE's pass (`after-write`, waymark-2ozr) answers for the
  first, on the engine's `:maintain` seam: a committed write on a
  finding refolds the beliefs that finding's citations feed, in the
  same call, each row in its own transaction. It exists because a new
  atom is not a clock — a reading that publishes a finding and reads
  the belief back one second later must see it, and until this pass
  landed it did not: a hypothesis was refreshed only by its own doors
  and by a nightly sweep whose first pass is one whole interval after
  the process starts, so a deployment that redeploys daily never swept
  and every belief stood at whatever its last door write computed.

  ── THE CLOCK ────────────────────────────────────────────────────────

  A daemon on an interval, elected per storage, `:when`-gated on a
  hypothesis kind being served — `feed/start-tickler-sweeper!`'s shape
  exactly, and for its reasons. Nightly by default (24h) because
  decay is a per-day arithmetic and a house does not need to watch a
  half-life move. The pass is also a plain function a test and a REPL
  call by name; the loop is only the clock."
  (:require [waymark10.belief :as belief]
            [waymark10.feed-recipe :as recipe]
            [waymark10.server.feed :as feed]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def hypothesis-kind
  "The kind this pass folds, named here as a KEYWORD and nowhere else
  — `feed`'s own posture with `:insight` and `:tickler`: the framework
  reads a kind the application enrols without requiring the namespace
  that declares it."
  :hypothesis)

(def atom-kind
  "Where the evidence lives. The finding is the atom's carrier and
  always was (fork (b)): it already cites, already has an author,
  already has an instant and already has a verdict machine."
  :insight)

(def atom-scan-cap
  "How many findings one pass reads, newest first. `dropped-scan-cap`'s
  number one sweep over, and a bound on the READ rather than on any
  write: a house with more than five thousand typed findings has an
  archive, and the oldest of them are decayed past mattering by the
  time they fall off this window."
  5000)

(def hypothesis-scan-cap
  "How many hypotheses one pass folds. A household that holds more
  than a thousand beliefs about itself has stopped believing and
  started collecting."
  1000)

;; ── the read ────────────────────────────────────────────────────────

(defn- rows-of
  "One kind's newest rows matching an equality map, capped —
  `feed/rows-of`'s body, private there and small enough to say twice
  rather than widen that namespace's surface for one caller. A kind in
  the registry whose table this engine never made answers nothing
  rather than failing the pass."
  [eng kind where ^long limit]
  (let [st (:storage eng)]
    (try
      (store/with-tx st
        (fn [tx] (store/query-rows st tx kind where
                                   {:limit limit :newest-first true})))
      (catch Exception _ []))))

(defn serves-hypotheses?
  "Does this engine hold a hypothesis kind at all? The feed module's
  `:when` gate for this sweep, `feed/serves-ticklers?`'s sentence one
  kind over: an engine that serves no hypothesis starts no sweeper and
  pays nothing for it."
  [eng]
  (some? (get (inv/resources eng) hypothesis-kind)))

(defn evidence-table
  "The numbers this pass weighs by: the HOUSEHOLD's own recipe row,
  with the deployment's filled in for anything it did not state.
  `for-reader` is asked with NO member, exactly as the diagnosis
  document asks it and for the same reason — a belief about this house
  is the house's, and a member's private feed order is not the place
  its beliefs are priced from."
  [eng]
  (let [built-in (try (feed/check-recipe! (:feed eng feed/default-recipe))
                      (catch Exception _ feed/default-recipe))
        {:keys [recipe]} (try (recipe/for-reader eng built-in nil)
                              (catch Exception _ {:recipe built-in}))]
    (feed/evidence-lr-of recipe)))

;; ── the fold, over the store ────────────────────────────────────────

(defn- unchanged?
  "Has this pass anything to say? Comparing the whole cached map
  against the fold means a store nothing has moved takes no writes at
  all, which is what lets the interval be shortened without a row's
  `updated_at` becoming a heartbeat."
  [row folded]
  (= (select-keys (:data row) (keys folded)) folded))

(defn- refold-row!
  "Fold ONE hypothesis row and cache the answer where it moved. The
  maintenance write both passes below share — the sweep's and the
  atom's — spelled once, because two spellings of *what the fold
  writes* is exactly the second opinion nobody can see.

  Returns `:rewritten`, `:unchanged` or `:failed`, which is the key
  each caller's tally increments."
  [eng table atoms now-ms row]
  (let [folded (belief/cached
                (belief/fold-one table row atoms (long now-ms)))]
    (if (unchanged? row folded)
      :unchanged
      (try
        (let [st (:storage eng)]
          (store/with-tx
            st (fn [tx]
                 (store/update-data!
                  st tx hypothesis-kind (:id row)
                  (merge (:data row) folded)
                  (:next-flip-at row)))))
        :rewritten
        (catch Exception e
          (binding [*out* *err*]
            (println "waymark10 belief: could not fold hypothesis"
                     (str (:id row)) "—" (ex-message e)))
          :failed)))))

(defn sweep-beliefs!
  "ONE PASS: read every hypothesis and every typed finding, fold each
  belief over the atoms that touch it, and cache the answer on rows
  whose answer moved. →
  `{:hypotheses n :atoms n :rewritten n :unchanged n :failed n}`, zero
  everything on an engine that serves no hypothesis kind.

  Every state is folded, `dismissed` and `retired` included, and that
  is the spec read literally: *nothing about the posterior changes
  with the state.* An affirmed hypothesis still reads its evidence —
  the affirmation says this house agrees the claim is worth holding,
  never stop reading — and a dismissed one keeps an honest number
  under it so a reading can see that the house said no to something
  the record went on supporting.

  A test drives this directly, the way `sweep-dropped!` and
  `sweep-orphans!` are driven; the loop below is only the clock."
  ([eng] (sweep-beliefs! eng (System/currentTimeMillis)))
  ([eng ^long now-ms]
   (if-not (serves-hypotheses? eng)
     {:hypotheses 0 :atoms 0 :rewritten 0 :unchanged 0 :failed 0}
     (let [table (evidence-table eng)
           atoms (belief/atoms-of (rows-of eng atom-kind {} atom-scan-cap))
           rows (rows-of eng hypothesis-kind {} hypothesis-scan-cap)]
       (reduce
        (fn [acc row]
          (update acc (refold-row! eng table atoms now-ms row) inc))
        {:hypotheses (count rows) :atoms (count atoms)
         :rewritten 0 :unchanged 0 :failed 0}
        rows)))))

;; ── the fold, when an atom lands (waymark-2ozr) ──────────────────────
;;
;; THE NIGHTLY PASS IS THE CLOCK'S AND THIS ONE IS THE EVIDENCE'S.
;; A belief's cached fold is a projection of two things: the atoms
;; that touch it, and how old they are. The clock moves the second and
;; a night is the honest interval for it — decay is a per-day
;; arithmetic against half-lives measured in months. But the FIRST
;; moves the moment a finding lands, and until waymark-2ozr nothing
;; refreshed it: a hypothesis was refolded by its own doors (birth,
;; `restate`, `revise`) and by a nightly sweep whose first pass is one
;; whole interval after the process starts. A deployment that
;; redeploys more often than daily therefore never swept at all, and
;; every belief stood at whatever its last door write computed.
;;
;; What that looked like from outside is the bug's own shape and worth
;; keeping: the SAME address fed one belief and not another. Backfill
;; #9's `16e01a3b` cited `/api/insights/8d6a0338…`, which sits in the
;; `about` of two beliefs; `95816939` was restated a minute after the
;; finding landed and took the atom, and `0cc6d78f` had last been
;; written seven hours before it and did not. Nothing was wrong with
;; either row and nothing was wrong with the join — the two rows had
;; simply been folded at different moments, and a cache whose
;; freshness depends on when somebody last touched the row is not a
;; cache of an arithmetic anyone can redo.
;;
;; So the atom's own write refreshes the beliefs it feeds, on the
;; engine's `:maintain` seam — the derivation maintainer's own posture
;; one kind over (*the maintainer computes what the in-commit pass
;; cannot: cross-row facts*), post-commit, each row in its own
;; transaction, and a maintenance write throughout: no version, no
;; transition. Only the beliefs the finding actually touches are
;; refolded, which is the join read backwards and is usually none.

(defn refold-touched!
  "Refold every belief one `insight` row's citations reach. →
  `{:hypotheses n :atoms n :rewritten n :unchanged n :failed n}` over
  the TOUCHED rows, zero everything where the finding names nothing
  this house holds a belief about — which is the ordinary case and
  costs one capped read.

  A dismissal is refolded exactly like a publication and for the same
  reason: the house saying no takes the atom back OUT of the fold, and
  a belief that kept counting a rejected claim until the next night
  would be evidence the record has already refused. So the citations
  are read off the row rather than off `atom-of`, which answers nil
  for a finding that is not an atom — an untyped finding never was
  one and moves nothing, but a typed one that just left the fold moves
  everything it fed."
  ([eng row] (refold-touched! eng row (System/currentTimeMillis)))
  ([eng row ^long now-ms]
   (let [cites (belief/cites-of row)
         touched (when (seq cites)
                   (belief/fed-by cites (rows-of eng hypothesis-kind {}
                                                 hypothesis-scan-cap)))]
     (if (empty? touched)
       {:hypotheses 0 :atoms 0 :rewritten 0 :unchanged 0 :failed 0}
       (let [table (evidence-table eng)
             atoms (belief/atoms-of (rows-of eng atom-kind {} atom-scan-cap))]
         (reduce
          (fn [acc r]
            (update acc (refold-row! eng table atoms now-ms r) inc))
          {:hypotheses (count touched) :atoms (count atoms)
           :rewritten 0 :unchanged 0 :failed 0}
          touched))))))

(defn after-write
  "The engine's `:maintain` hook, this module's arm: a committed write
  on a finding refolds the beliefs that finding feeds. Returns nil,
  which the seam reads as *nothing to say about the response's row* —
  the write's own row is a finding and this pass never touches it.

  Every other kind passes through untouched, and an engine that serves
  no hypothesis pays one map lookup. The hypothesis kind is
  deliberately NOT refolded here: its own doors fold it in their own
  transaction (`hypothesis/fold-now`), and a maintenance write
  chaining off a maintenance write is the loop this seam has no
  visited set for."
  [eng kind _action res]
  (when (and (= atom-kind kind)
             (some? (:row res))
             (serves-hypotheses? eng))
    (try
      (let [now ^java.time.Instant (if-some [f (:now-fn eng)]
                                     (f)
                                     (java.time.Instant/now))]
        (refold-touched! eng (:row res) (.toEpochMilli now)))
      (catch Exception e
        (binding [*out* *err*]
          (println "waymark10 belief: could not refold what a finding fed —"
                   (ex-message e))))))
  nil)

;; ── the clock ───────────────────────────────────────────────────────

(defn start-belief-sweeper!
  "The pass's loop: every `:interval-ms` (default a day),
  `sweep-beliefs!` refolds the store. Returns the handle
  `stop-belief-sweeper!` takes. One process per storage runs it — the
  feed module's lifecycle hook carries `:elected :belief-sweeper` —
  and the first pass is one interval after the start, the tickler
  sweeper's own posture, so a boot writes nothing and no test finds a
  posterior it did not make.

  NIGHTLY IS THE SPEC'S WORD AND A DAY IS THE HONEST INTERVAL. Decay
  is a per-day arithmetic against half-lives measured in months; a
  belief that is refolded every minute reads the same as one refolded
  every night, and the pass is cheap only because it is rare. What
  makes a fresh atom visible before the next night is not a faster
  clock — it is that the reading's brief falls back to computing the
  fold itself when the store has nothing to say."
  [eng {:keys [interval-ms] :or {interval-ms 86400000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-beliefs! eng)
                              (catch Exception e
                                (binding [*out* *err*]
                                  (println "waymark10 belief: the fold failed:"
                                           (ex-message e)))))
                         (recur))))
                   "waymark10-belief-sweep")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-belief-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
