(ns factorykit.change
  "The change kind's shared core, and the builder a factory composes
  its own change kind through (epic 459ed2eb, step 2a).

  A FACTORY'S CHANGE IS THE CORE AND ITS OWN EXTENSION. The core is
  what every factory's change has: the states, the tomb, the one-row
  index, the fields the bench and the stall write, and the cracks law.
  The extension is a defresource map of everything else. `build`
  answers the map `waymark10.dsl/defresource` takes.

  THE SOURCE'S DOORS ARE THE CORE'S (step 2b-1, ticket 47e2c880):
  adopt, adopt_submitted, observe, observe_submitted, fail, recover,
  merge and close, behind the wall `the-mirror-writes-this-row`. The
  doors whose handler reads what the change was built for (merge,
  close, fail and recover) have no handler here: the extension's
  `:handlers` gives each one its own, so the core holds no ticket
  code. A factory passes every other door as its extension, and the
  guards and the handlers ride on the doors that name them. Later
  steps move those.

  A factory in this repository depends on the kit by `:local/root`. A
  factory outside it depends on it by `:git/url` with
  `:deps/root \"factorykit\"`."
  (:require [waymark10.dsl :refer [defguardfn defhandler]]
            [waymark10.types :as t]
            [waymark10.verdict :as verdict]))

(set! *warn-on-reflection* true)

;; ── the wall on the source's doors ──────────────────────────────────

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

;; ── what a pull request writes back onto the row ────────────────────

(defhandler observe-the-pull-request [row inp ctx]
  ;; The source hands the facts it read. A fact it did not read is
  ;; absent, and absent means silent: the stored value stands. The
  ;; machine advances the state, never this handler.
  ;;
  ;; A HEAD THAT MOVES takes back what judged the old one (ticket
  ;; 35600491): see `waymark10.verdict/reopen-stale-verdicts!`.
  (let [head (some-> (:head_sha inp) str not-empty)]
    (when (and head (not= head (some-> (get-in row [:data :head_sha]) str not-empty)))
      (verdict/reopen-stale-verdicts! ctx :change (:id row) head)))
  (update row :data merge (into {} (remove (comp nil? val)) inp)))

(defhandler adopt-the-pull-request [row inp _ctx]
  ;; The identity lands as `observe` writes the facts, and the forge
  ;; pass's note that nobody had adopted the landed pull request goes
  ;; with the wait it described (ticket 58e706d6).
  (-> row
      (update :data merge (into {} (remove (comp nil? val)) inp))
      (update :data assoc :unadopted_since nil :adoption_note nil)))

(def core
  "What every factory's change has. `:fields` is the entries of the
  schema's `:map`, in the core's own order."
  {;; GitHub's own three, and the bench's two (waymark-fp62.6.3.2):
   ;; `submitted` is a change a seat has pushed at least one round of,
   ;; and `stuck` is one the house stopped working. `failing` is a
   ;; submitted change whose required checks went red (d1742908).
   :states [:open :submitted :failing :stuck :merged :closed]
   ;; ONE TOMB. A merged pull request is finished. A closed one is
   ;; not: GitHub reopens it, so `reopen` is a real door.
   :terminal #{:merged}
   ;; ONE ROW PER PULL REQUEST, enforced by an index
   :unique [[:change_id]]
   ;; A SUBMITTED CHANGE WAITS ON ITS REQUIRED CHECKS (ticket d9edac4e).
   ;; They are GitHub's and no row here, so the wait names no kind:
   ;; green lets the gate merge it, and red moves it to `failing`,
   ;; which sends its ticket back to the seat that built it.
   :waits [{:on :checks :in #{:submitted}
            :outcomes {:green :release :red :return}}]
   ;; THE EXITS NO SEAT TAKES AT A CHANGE DOOR (ticket 4b0460cc), so the
   ;; cracks audit does not report `submitted` as a state seats leave on
   ;; a cadence. The forge's mirror walks all three: the gate's merge,
   ;; a person's close on GitHub, and the red check that is `fail`.
   :left-by [{:in #{:submitted} :by :mirror :via :merge :to :merged}
             {:in #{:submitted} :by :mirror :via :close :to :closed}
             {:in #{:submitted} :by :mirror :via :fail :to :failing}]
   ;; AND THE ARRIVAL THAT IS NO CHANGE DOOR. A change is born open from
   ;; a groomed ticket (`born_from`), so the seat that builds is brought
   ;; to it by its wake on the ticket's groom, not by a change wake.
   :reached-by [{:in #{:open} :by {:kind :ticket :action :groom}}]
   ;; HOW LONG A CHANGE MAY REST (ticket 21b49b28), in seconds: open and
   ;; submitted six hours, failing four, stuck a day. The one-day
   ;; default let a change sit thirty hours at the front of its merge
   ;; line before anyone was told.
   :expected-age {:open 21600 :submitted 21600 :failing 14400 :stuck 86400}
   :fields
   [;; :raw because it is a LABEL and not prose — an opaque address is
    ;; shown exactly as it arrived. It also tells the assembly's ref
    ;; lint the truth: a field named <kind>_id for a kind this engine
    ;; serves reads as a reference by convention (checks-assembly's
    ;; id-target), and this one is not a reference to another change —
    ;; it is THIS row's own address at GitHub.
    [:change_id {:x-ref {:address true}
                 :x-display
                 {:raw true
                  :label "The pull request's own id"
                  :help "The address GitHub knows this pull request by, as github:owner/repo#number. The source reads it back to see whether this pull request is already a row here. A change a seat built from an ask carries the ask's own address instead — the walk kind, a colon and the row id — until the push opens the pull request and the source adopts the row."}}
     [:string {:min 1 :max 250}]]
    [:repository {:x-display
                  {:label "The repository"
                   :help "The repository as GitHub spells it, as owner/repo. Every policy about this change reads this field first."}}
     [:string {:min 1 :max 140}]]
    ;; OPTIONAL, because a change is not always born at GitHub (bead
    ;; waymark-fp62.6.3.10). A seat that walks a queue of asks gets a
    ;; change row minted for the ask BEFORE any pull request exists,
    ;; and a number nobody has been given is a number this row must
    ;; not invent. The source writes it at the adoption, with `adopt`.
    ;; Until then the summary line renders it as an em-dash, which is
    ;; the framework's own word for "not said yet".
    [:number {:optional true
              :x-display
              {:label "The pull request number"
               :help "The number GitHub shows on the pull request. It is unique inside the repository, not across repositories. A change a seat is still building has none until the push opens the pull request."}}
     [:maybe [:int {:min 1}]]]
    ;; :raw because it is a LABEL and not prose (inbox_item's subject,
    ;; one domain over): a long title must not refuse the mint
    [:title {:optional true
             :x-display
             {:raw true
              :label "Title"
              :help "The pull request title, exactly as it stands on GitHub. This is what the card reads."}}
     [:maybe [:string {:max 400}]]]
    [:author {:optional true
              :not-a-ref "A GitHub login, or the name of the seat that opened it: a handle, not a row id or a principal here."
              :x-display
              {:label "Who opened it"
               :help "The GitHub login of the person or the seat that opened the pull request."}}
     [:maybe [:string {:max 120}]]]
    [:base_branch {:optional true
                   :x-display
                   {:label "The base branch"
                    :help "The branch this change merges into. It is main in most repositories."}}
     [:maybe [:string {:max 200}]]]
    [:head_branch {:optional true
                   :x-display
                   {:label "The head branch"
                    :help "The branch that holds the change. A bench seat pushes to this branch."}}
     [:maybe [:string {:max 200}]]]
    [:worktree_dirty {:default 0
                      :x-display
                      {:label "Edits waiting in the worktree"
                       :help "How many paths of the worktree were different from the branch head when a door last looked. The bench itself holds the live count, and the sit answers it; this is what the doors wrote down."}}
     [:int {:min 0}]]
    [:rounds {:default 0
              :x-display
              {:label "Rounds spent"
               :help "How many times a seat has submitted this change. At the repository policy's ceiling the change stops and waits for a person."}}
     [:int {:min 0}]]
    ;; the stall's own sentence (ticket b6c8ea04): written by `stall`,
    ;; cleared by every door that takes the change out of `stuck`
    [:stall_reason {:optional true
                    :examples ["The same test fails on the base commit, so this change is not the cause and I cannot fix it here."]
                    :x-display
                    {:widget "prose"
                     :label "Why it was stalled"
                     :help "The sentence the stall door was given: what the seat tried, and what stopped it. Empty unless the change is stuck by a stall."}}
     [:maybe [:string {:max 480}]]]
    [:stalled_at {:optional true
                  :x-display
                  {:label "When it was stalled"
                   :help "The moment the stall door moved this change to stuck. Empty unless the change is stuck by a stall."}}
     [:maybe :waymark/instant]]
    [:stalled_by {:optional true
                  :x-ref {:principal true}
                  :x-display
                  {:label "Who stalled it"
                   :help "The principal, usually a seat, that walked the stall door. Empty unless the change is stuck by a stall."}}
     [:maybe [:string {:max 200}]]]
    ;; ── where a seat-born change CAME FROM (waymark-fp62.6.3.14) ──
    ;; The walk kind, a colon and the walk row's own id — the words
    ;; `change_id` carries at the mint. The adoption overwrites
    ;; `change_id` with GitHub's identity, so the origin needs a field
    ;; the adoption does not touch: the merge reads it to finish the
    ;; task this change was built for. Hidden, like every other fact
    ;; the engine writes and nobody types.
    [:born_from {:optional true :x-ref {:address true} :x-display {:hidden true}}
     [:maybe [:string {:max 250}]]]]
   ;; THE SOURCE'S DOORS (step 2b-1, ticket 47e2c880). Each one is the
   ;; mirror's, and each one is hidden from every hand but the engine's.
   :actions
   {;; THE MIRROR'S REFRESH. A self-loop on `open`: the pull request
    ;; moved under the row, and these are the facts that moved.
    :observe
    {:from #{:open} :to :open
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:title {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 400}]]]
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:draft {:optional true} [:maybe :boolean]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
             [:files_changed {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_added {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_removed {:optional true} [:maybe [:int {:min 0}]]]
             [:touched_paths {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:labels {:optional true} [:maybe [:vector [:string {:max 100}]]]]
             [:review_state {:optional true}
              [:maybe [:enum "pending" "approved" "changes_requested"
                       "commented"]]]]
     ;; :edit-shape — the input MIRRORS the document on purpose. This
     ;; is not a person editing a row; it is the authority restating
     ;; what it holds, and a prefill for a hidden door is scaffolding
     ;; for nobody.
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 1
               :description "The mirror writes what GitHub says about this pull request now"}}

    ;; THE SAME REFRESH, one state over. A v10 action declares one
    ;; `:to`, so a self-loop that serves two states is spelled twice
    ;; (server/definitions.clj's `measure`/`measure_pilot`). A change
    ;; a seat has pushed goes on moving under the row: the checks run,
    ;; a reviewer answers, the head moves.
    :observe_submitted
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler observe-the-pull-request
     :input [:map
             [:title {:optional true :x-display {:raw true}}
              [:maybe [:string {:max 400}]]]
             [:head_sha {:optional true} [:maybe [:string {:max 64}]]]
             [:draft {:optional true} [:maybe :boolean]]
             [:mergeable {:optional true}
              [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
             [:files_changed {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_added {:optional true} [:maybe [:int {:min 0}]]]
             [:lines_removed {:optional true} [:maybe [:int {:min 0}]]]
             [:touched_paths {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:labels {:optional true} [:maybe [:vector [:string {:max 100}]]]]
             [:review_state {:optional true}
              [:maybe [:enum "pending" "approved" "changes_requested"
                       "commented"]]]
             ;; the forge pass's re-run of an interrupted head (ticket
             ;; 22f91244)
             [:rerun_head {:optional true} [:maybe [:string {:max 64}]]]
             [:rerun_note {:optional true} [:maybe [:string {:max 240}]]]
             ;; the forge pass's read of a head that lacks a required
             ;; check (ticket 498a089e)
             [:missing_checks {:optional true}
              [:maybe [:vector [:string {:max 200}]]]]
             [:behind_base {:optional true} [:maybe :boolean]]
             [:base_compared_at {:optional true} [:maybe [:string {:max 40}]]]
             [:missing_checks_head {:optional true}
              [:maybe [:string {:max 64}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Observe" :order 10
               :description "The mirror writes what GitHub says about this pull request now"}}

    ;; THE MOVES GITHUB MAKES. NO HANDLER HERE: what a merge finishes
    ;; and what a close sends back is the factory's own ask, so the
    ;; extension's `:handlers` gives `merge`, `close`, `fail` and
    ;; `recover` their handler. The `:one-way` sentences say what the
    ;; move does with it.
    :merge
    {:from #{:open :submitted :failing :stuck} :to :merged
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub merged this pull request. The row follows GitHub, so there is no way back: a merged pull request is not reopened. The ask this change was born from — a task, or a ticket that has not ended — is completed with it, and another change built for that ask on the same branch is closed."}
     :display {:label "Merged" :order 2
               :description "GitHub merged the pull request"}}

    :close
    {:from #{:open :submitted :failing :stuck} :to :closed
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "GitHub closed this pull request without merging it, and a ticket this change was built for goes back to the queue. The way back is GitHub's own reopen, which the mirror follows with its reopen door."}
     :display {:label "Closed" :order 3
               :description "GitHub closed the pull request and merged nothing"}}

    ;; ── THE ADOPTION (bead waymark-fp62.6.3.10) ────────────────────
    ;; A row a seat's sit minted for an ask carries the ask's own id
    ;; and no number. The push opens the pull request, and the next
    ;; source pass writes GitHub's identity onto the row that is
    ;; already here. `observe` cannot: its input is the FACTS that
    ;; move under a row, and the id a row is known by is not one of
    ;; them. So the identity has a door of its own, hidden behind the
    ;; same wall, and `change_id` is `:unique` — the write lands or
    ;; the whole transaction does, and a second pull request can
    ;; never take a row that is already spoken for.
    :adopt
    {:from #{:open} :to :open
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-ref {:address true}
                          :x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             ;; :hidden, as the field is on the document — a url has
             ;; no shape a form could draw, and the origin LINK is the
             ;; affordance (the long-text battery asks for one of the
             ;; three)
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 11
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    ;; the same write, one state over — a change a seat has SUBMITTED
    ;; is exactly the change whose push opened the pull request, so
    ;; this is the state the adoption lands in almost every time. A
    ;; v10 action declares one `:to`, so the self-loop is spelled
    ;; twice (the `observe`/`observe_submitted` precedent).
    :adopt_submitted
    {:from #{:submitted} :to :submitted
     :guards [the-mirror-writes-this-row]
     :handler adopt-the-pull-request
     :input [:map
             [:change_id {:x-ref {:address true}
                          :x-display {:raw true}}
              [:string {:min 1 :max 250}]]
             [:number {:optional true} [:maybe [:int {:min 1}]]]
             [:url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 500}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false}
     :display {:label "Adopt" :order 12
               :description "The mirror writes the pull request GitHub opened for this change onto the row that asked for it"}}

    ;; ── THE CHECKS' VERDICT (ticket d1742908) ──────────────────────────
    ;; The source reads the checks on a submitted change's head and
    ;; walks one of these. They are the mirror's and hidden: no seat
    ;; and no person says a change is red.
    :fail
    {:from #{:submitted} :to :failing
     :guards [the-mirror-writes-this-row]
     :input [:map
             [:failing_checks [:vector {:min 1} [:string {:max 200}]]]
             [:conflicts {:optional true}
              [:maybe [:vector [:string {:max 400}]]]]
             [:landing_error {:optional true
                              :x-display {:widget "prose"
                                          :label "Why the push did not land"}}
              [:maybe [:string {:max 4000}]]]
             ;; a merge train's red (ticket 6566d32f): the head it
             ;; judged, and its branch and run url
             [:train_red_head {:optional true} [:maybe [:string {:max 64}]]]
             [:train_red {:optional true
                          :x-display {:widget "prose"
                                      :label "Why a merge train found it red"}}
              [:maybe [:string {:max 1000}]]]]
     :waives #{:edit-shape}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks finished red on this head, and the ticket this change was built for goes back to the queue, so the seat that wrote it walks it again. The way back is a green head, which the house reads on its next pass, or the seat's next submit."}
     :display {:label "Failing" :order 15
               :description "The required checks went red on the head the seat pushed"}}

    :recover
    {:from #{:failing} :to :submitted
     :guards [the-mirror-writes-this-row]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The required checks are green on the head the row names now, so the change is submitted again and the house merges it as it would any other."}
     :display {:label "Green again" :order 16
               :description "The required checks went green on a later head"}}}})

(def ^:private law
  "The cracks law: what a change waits on, who moves it at no change
  door, and how long it may rest."
  [:waits :left-by :reached-by :expected-age])

(defn- refuse! [why data]
  (throw (ex-info (str "factorykit.change/build: " why) data)))

(defn- not-twice!
  "Refuses an extension that names what the core already named."
  [what core-names own-names]
  (when-some [both (seq (filter (set core-names) own-names))]
    (refuse! (str "the extension redefines the core's " what " "
                  (pr-str (vec both)) ". A core name is the kit's to define.")
             {:redefined (vec both)})))

(defn- in-order
  "The fields in the order the extension said. A field the order does
  not name follows the named ones, in its own place among the rest."
  [fields order]
  (let [by-name (into {} (map (juxt first identity)) fields)
        said (set order)]
    (when-some [unknown (seq (remove by-name order))]
      (refuse! (str ":field-order names " (pr-str (vec unknown))
                    ", and neither the core nor the extension has that field.")
               {:unknown (vec unknown)}))
    (into (mapv by-name (distinct order)) (remove (comp said first)) fields)))

(defn- with-the-law
  "Each law key: the core's, with the extension's over it. Two maps
  merge and the extension's entries win; any other value of the
  extension's stands in place of the core's."
  [declaration core extension]
  (reduce (fn [declaration k]
            (let [ours (get core k)
                  theirs (get extension k)
                  v (cond
                      (nil? theirs) ours
                      (and (map? ours) (map? theirs)) (merge ours theirs)
                      :else theirs)]
              (cond-> declaration (some? v) (assoc k v))))
          declaration
          law))

(defn- with-the-handlers
  "The core's doors, each with the handler the extension's `:handlers`
  names for it. A core door that has its own handler keeps it."
  [doors handlers]
  (when-some [unknown (seq (remove (set (keys doors)) (keys handlers)))]
    (refuse! (str ":handlers names " (pr-str (vec unknown))
                  ", and the core has no such door.")
             {:unknown (vec unknown)}))
  (when-some [taken (seq (filter #(contains? (get doors %) :handler) (keys handlers)))]
    (refuse! (str ":handlers names " (pr-str (vec taken))
                  ", and the core's own handler is on that door.")
             {:taken (vec taken)}))
  (reduce-kv (fn [doors door handler] (assoc-in doors [door :handler] handler))
             doors
             handlers))

(defn build
  "The defresource map of a factory's change: `core` and the factory's
  own `extension`, which is a defresource map without the core's part.

  - `:states` and `:terminal` are the core's. An extension that names
    either is refused.
  - `:unique` is the core's, then the extension's.
  - The fields are the core's `:fields`, then the entries of the
    extension's `:schema`, which is a `[:map ...]` with no properties.
    `:field-order`, a vector of field names, says another order.
  - The doors are the core's `:actions` and the extension's. A guard
    and a handler ride on the door that names them.
  - `:handlers`, a map of a core door's name to a handler, gives a
    core door that has no handler the extension's own. A door the
    core does not have, and one with the core's handler, is refused.
  - An extension may not redefine a core field or a core door.
  - The cracks law (`:waits`, `:left-by`, `:reached-by`,
    `:expected-age`) is the core's, with the extension's over it.
  - Every other key is the extension's."
  [core extension]
  (let [{:keys [schema field-order]} extension
        [tag & own-fields] schema]
    (when-some [named (seq (filter #(contains? extension %) [:states :terminal]))]
      (refuse! (str "the extension names " (pr-str (vec named))
                    ", and the machine's states and its tomb are the core's.")
               {:named (vec named)}))
    (when (and (some? schema)
               (or (not= :map tag) (map? (first own-fields))))
      (refuse! "the extension's :schema is not a [:map ...] of field entries with no properties."
               {:schema-tag tag}))
    (not-twice! "field" (map first (:fields core)) (map first own-fields))
    (not-twice! "door" (keys (:actions core)) (keys (:actions extension)))
    (-> (dissoc extension :field-order :handlers)
        (merge (select-keys core [:states :terminal]))
        (assoc :unique (vec (distinct (concat (:unique core) (:unique extension))))
               :schema (into [:map]
                             (in-order (into (vec (:fields core)) own-fields)
                                       field-order))
               :actions (merge (with-the-handlers (:actions core) (:handlers extension))
                               (:actions extension)))
        (with-the-law core extension))))
