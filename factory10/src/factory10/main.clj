(ns factory10.main
  "factory10: the software factory's kinds.

  WHAT THIS MODULE IS. The clerk's shape is a queue, a tree, a ledger
  and a floor. The factory has the same shape with a codebase in the
  middle. This module holds the kinds that shape: a `change`, which
  mirrors one pull request, and a `ci_run`, which is a red check run
  and the tree that classifies it (bead waymark-fp62.6.2) — and a
  `repo_policy`, which is what submit MEANS in one repository, as a
  row a person restates (bead waymark-fp62.6.3.2).

  IT IS A MODULE, LIKE workqueue10. The household's queue does not
  carry the day job's kinds, and a company's engine boots without the
  household's. `resources` below is the whole of what this module
  declares, and it wears the `:factory` domain token.

  IT BOOTS ALONE, AND IT BOOTS BESIDE. Alone:

      cd factory10 && clojure -M:check   # the declaration gate
      cd factory10 && clojure -M:test    # the suite, no database

  Beside: workqueue10.main folds these kinds into the household
  registry when FACTORY10=1 is set. That is a switch and not a
  default, because the household's feed has no business carrying the
  day job's red builds. The proving ground of this bead is this
  repository's own gate, and that fold is what lets the house watch
  it.

  NOTHING HERE NAMES A SEAT (R-6). Which seat walks the ci_run tree
  is a row: the seat's walk field names `ci_run`, and its wake_on
  names a ci_run create. A kind is law; a seat is fluid."
  (:require [factory10.resources.change :refer [change]]
            [factory10.resources.ci-run :refer [ci-run]]
            [factory10.resources.repo-policy :refer [repo-policy]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

(defn resources
  "Every kind this module declares, stamped with the `:factory`
  domain. The `repo_policy` first, because it is the law the bench's
  doors read; then a `change`, because a `ci_run` points at one."
  []
  (in-domain :factory [repo-policy change ci-run]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter.
  The two mirrors are pull-only and the policy is a person's form;
  the bench's own doors reach their rig through the engine's Gate
  caller rather than through a declared seam — so this is `resources`
  and nothing more."
  []
  (resources))
