(ns factory10.change-kit-test
  "factory10's change kind is composed through `factorykit.change/build`
  (ticket ce3dd7b1), and the composition changed nothing: the
  declaration the engine serves is the one it served when the kind was
  one literal map. The pin below is the digest of that literal's
  declaration, taken on the commit before the kit; a change to the kind
  that is meant moves the pin with it. Ticket 48f9605b moved it:
  `close_without_pr` leaves `failing` and `stuck` for the kit's sweep
  behind `the-engine-closes-a-stopped-change`, and writes its sentence
  to `superseded_by`. Ticket 7e12f1e8 moved it: `close_without_pr`'s
  one-way sentence says the house opens the change again only when its
  ticket is queued again after the close. Ticket 7aff6a5c moved it:
  `line_why` may say `org_plan_waits_go_live`. Ticket ccc3ecae moved it:
  `born_from` is filterable by :eq, so a declarative guard finds a
  ticket's change by it. Ticket 14d32fc4 moved it: submit's
  `the-repository-has-a-policy` is an `exists` over `repo_policy`, and
  its three other walls say `:check-tier :given`. Ticket e0ff6d71 moved
  it: `line_why` may say `org_plan_retired`. Ticket 50f09817 moved it:
  the change records `last_round_kind`, and a submit that only merges
  the base passes the round ceiling. Ticket 59cd3e78 moved it:
  `release_lane` lets the mayor release a change held for an open org
  plan's lane, and writes `lane_released`. Ticket bc6a8732 moved it:
  `observe`, `adopt`, `discard` and `take_domain` are each one
  `:to :same` door, and their old per-state copies are `:aliases`.
  Ticket 56c97770 moved it: the change holds its `pull_request` row's
  id, unique and filterable, written by the mirror's hidden `link`.
  Ticket 995570f0 moved it: `checks_red` lands a pull request's red in
  `failing` or `stuck` by the change's rounds, and `recover` takes the
  head its green verdict judged and refuses one failing on its landing.
  Ticket 1ed59295 moved it: the house's hidden `merge_asked` writes the
  head it asked the rig at and a parked refusal on the row.
  Ticket a1a133ca moved it: the mirror's hidden `update_asked` and
  `rerun_asked` write the head the house asked `update_branch` and
  `rerun` at (`updated_head`, `current_head`, `rerun_head`). Ticket
  423164bf moved it: the hidden `refresh_head` writes the head and merge
  word its pull request's subscription walks in. Ticket e6729daa moved
  it: the merge pass walks the hidden `hold`, `release` and `line`, and
  the change records `held_as` and `held_why`. Ticket 1ae1afbc moved
  it: a merge train's hidden `train_done` writes the head it sent its
  lane one at a time at, and why, on each change it carried. Ticket
  0c2940ca moved it: a held change's `held_as` and `held_why` are
  cleared when it leaves `submitted`. Ticket 3806fb22 moved it: the
  hidden `wait_started` and `wait_cleared` keep the person's merge wait
  on the row (`merge_wait_head`, `merge_wait_since`). Ticket fa5e8b1a
  moved it: a change `stick` or `checks_red` leaves stuck sends its
  ticket to draft, and the two doors' one-way sentences say so."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.resources.change :refer [change]])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn- canonical
  "The declaration as plain data with one printed spelling: a map and a
  set in a sorted order, and a function as the name of its class, with
  the compiler's counter cut off."
  [x]
  (cond
    (map? x) (into [:map] (sort-by pr-str (map (fn [[k v]] [(canonical k) (canonical v)]) x)))
    (set? x) (into [:set] (sort-by pr-str (map canonical x)))
    (sequential? x) (into [:seq] (map canonical x))
    (or (nil? x) (string? x) (keyword? x) (symbol? x) (number? x)
        (boolean? x) (char? x)) x
    (instance? java.util.regex.Pattern x) [:re (str x)]
    (var? x) [:var (str x)]
    :else [:object (str/replace (.getName (class x)) #"(__|eval)\d+" "$1")]))

(defn- digest [x]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical x)) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(def ^:private the-literal-declaration
  "9adc3d716c6d9d0997b43eedfa7bdc7c1170f95b48849a2e743f4e29a779a885")

(deftest the-composed-change-is-the-declaration-it-was
  (is (= the-literal-declaration (digest change))))
