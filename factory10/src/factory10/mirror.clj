(ns factory10.mirror
  "The seam between GitHub and the factory's rows.

  WHAT THIS NAMESPACE IS. factory10 holds two kinds that MIRROR
  GitHub: a change is a pull request, a ci_run is a check run. A
  person does not write either one. A source reads GitHub and writes
  the rows. This namespace holds the two things both kinds share: the
  wall that keeps a model and a person out of the mirror's own doors,
  and the label vocabulary the write-back uses.

  THE WALL IS THE MIRROR'S, NOT A ROLE'S. `the-mirror-writes-this-row`
  admits the engine's own hand and nothing else. It is HIDDEN, so the
  doors behind it are absent from every envelope a person or a model
  reads. This is the Mirror weave's own posture, spelled by hand
  because these two kinds carry their own state machines: a change
  moves through GitHub's states, a ci_run through the classifier's
  tree, and neither machine is the sync machine
  (waymark10.server.mirror). The guard is NOT named `system-only`,
  which is the weave's own name: a second guard under that name would
  collide with the remedies waiver list, which names the weave's
  guard without naming a kind.

  A HIDDEN GUARD OWES NO REMEDY. The remedies census
  (waymark10.checks/speaks?) asks a way out of every guard that
  refuses IN WORDS. A hidden guard answers 404 and says nothing, so a
  sentence about the way out would leak the law the hiding conceals.

  WHICH HAND IS THE ENGINE'S. The source of bead waymark-fp62.6.4
  runs inside the engine and writes as a system principal, exactly as
  the Mirror's own discovery pass does
  (waymark10.server.mirror/system-observer). `source-principal` below
  is the principal that source uses.

  THE LABEL IS THE MIRROR'S JOB (bead waymark-fp62.6.2, R-5). A
  classified ci_run gets one label on its pull request. The model
  never holds a GitHub power: it walks the tree and writes a verdict,
  and the source reads the verdict and pushes the label. This
  namespace holds the verdict-to-label table both sides read, so the
  two halves cannot drift. The push itself is bead
  waymark-fp62.6.4's."
  (:require [waymark10.dsl :refer [defguardfn]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── the hand the mirror writes with ─────────────────────────────────

(def source-principal
  "The hand the GitHub source writes with. It is a system principal:
  the engine's own actor, not a person and not a model."
  (t/principal {:id "factory10-source"
                :type :system
                :display "GitHub mirror"}))

(defguardfn the-mirror-writes-this-row
  {:reads [:principal]
   :hide true
   :explain "The mirror writes this row. A person and a model read it."}
  [_row _inp ctx]
  ;; The Mirror weave's own test (waymark10.server.mirror/system-only),
  ;; written out here because these kinds keep their own machines.
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

;; ── the label the mirror pushes ─────────────────────────────────────

(def verdict-labels
  "The one label each verdict earns, as a table. The classifier writes
  the verdict. The source reads this table and pushes the label. Both
  sides read one value, so neither can invent a label the other does
  not know."
  {"infra" "ci:infra"
   "base_red" "ci:base-red"
   "this_change" "ci:this-change"})

(def verdicts
  "The three verdicts, in the order the tree offers them."
  ["infra" "base_red" "this_change"])

(def labels
  "The three labels, in the same order."
  (mapv verdict-labels verdicts))

(defn label-for
  "The label for one verdict, or nil when the verdict is not one of
  the three."
  [verdict]
  (get verdict-labels verdict))
