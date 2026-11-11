(ns waymark10.server.seats.guards
  "The guards of the seat, model and sitting doors: who may touch a
  seat, what a seat may walk and what its inbox may hear, the step
  note, merge and hand-over, the sitting's lease, and the fire door's
  walls (docs/spec-seat.md). They moved out of waymark10.server.seats
  whole (ticket 2019c930, round four), so a change to one door's law
  reads one screen of guards and not the three resources around them.

  `walk-filters` and `walk-doors` stand here because the walk guards
  read the same scope entries; wakes.clj and mcp.clj call them here.
  This namespace requires nothing of waymark10.server.seats: the
  resources there refer each guard by name."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            ;; the walked kind's own doors, read out of its rdef: the
            ;; filter guard asks whether one of them LEAVES the state
            ;; the walk filters by (bead waymark-fp62.12, R-2)
            [waymark10.machine :as machine]
            [waymark10.server.collections :as collections]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.members :as members]
            [waymark10.server.seats.inbox :refer [->instant interactive-seat?]]
            [waymark10.server.seats.ledger :refer [field-moved?]]
            [waymark10.server.seats.pools :refer [chair-of pool-from]]
            [waymark10.server.seats.wakes :refer [entry-filter walk-entries walk-entry]]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── guards: who may touch a seat ────────────────────────────────────

(g/defguard a-person
  {:reads [:principal]
   :explain "A seat is an office a person opens, restates, parks and closes — in person, or through a tool the person is signed in to. An agent does not open its own office: ask for a grant that cites a seat somebody already opened, and the sitting is yours."}
  [_row _inp ctx]
  ;; :human, or a DELEGATE — an :agent principal the identity gate
  ;; marked :acts-for, which is a person signed in through a tool
  ;; (spec-connector-door § 3): the members gate admits it only while
  ;; that person is an active member, and the mark is the gate's own,
  ;; never a request's. Not a bare :agent (the whole point) and not
  ;; :system either: the engine's own actors reach every handler
  ;; through one ctx :invoke, and a seat is the one row whose
  ;; authority a system path must not be able to widen. The concealed
  ;; transitions below are where the engine writes, and they say so
  ;; out loud. Ruled 2026-09-17 (spec-seat.md § 17): the owner's
  ;; connector opens the first seat, and the person behind the
  ;; delegate is the person the rule always meant.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(g/defguard a-person-at-the-chair
  {:reads [:principal]
   :explain "The chair's key and the chair's link are a person's to write: a person mints the key, a person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes both here. An agent does not write a chair's credential."
   :open "No door changes who the caller is: ask the person to write the chair's key and its link."}
  [_row _inp ctx]
  ;; `a-person`'s three-line check, spelled again rather than reused,
  ;; and schedules.clj's `a-person-or-a-delegate` makes the same trade
  ;; for the same reason: that guard's sentence is about opening an
  ;; OFFICE, and what a caller reads at a model's door is about a
  ;; credential. One admission, two sentences, and the refusal names
  ;; the thing the caller was actually touching.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

;; THE SITTER KEY'S WRITE FENCE (R-12.12), members.clj's
;; `reentry-not-written-by-hand` made real for this kind: sitter_key is
;; a schema field and a :secret one, so a create or a restate that
;; could carry it would stamp a LIVE credential with no mint door, no
;; audit and nothing a reader could ever see again. The one writer is
;; `set-sitter-key`, under `offer_key`'s own guards. The field IS
;; declared on both inputs, deliberately — a guard may only judge a
;; field of the door it stands on (checks/check-create-guards), and a
;; fence nobody can name is a fence nobody can read. Both spellings
;; are :secret, so no form asks and no advertised input names it.
(g/defguard key-not-written-by-hand
  {:judges [:sitter_key :sitter_key_hash]
   :explain "The sitter key is written by offer_key alone, never by hand — a create or a restate may not carry sitter_key or sitter_key_hash. Open the seat, or add the model, and offer the key at its own door."}
  [_row inp _ctx]
  (if (some #(contains? inp %) [:sitter_key :sitter_key_hash])
    (t/deny)
    (t/allow)))

(g/defguard one-seat-spelling
  {:judges [:name]
   :reads [:seat]
   :open "The taken spellings are the seats collection, one query away; enumerating them into the create form would offer exactly the tokens the guard is about to refuse."
   :explain "A seat named {name} is already open — one spelling per seat, or an ask and a grant name different offices with the same word."}
  [_row inp ctx]
  (if-some [find' (:find ctx)]
    (if (and (some? (:name inp))
             (seq (find' :seat {:name (str (:name inp)) :state "active"}
                        {:limit 1})))
      (t/deny {:vars {:name (str (:name inp))}})
      (t/allow))
    (t/allow)))

(g/defguard not-a-sitter
  {:reads [:principal :now :grant :seat :held_call :within]
   :remedies [:seat/restate]
   :explain "A sitter does not widen its own office, and an ask cannot do it: an anchored ask on a seat grant carries no scope, and an anchorless one mints a grant this sitting never wears. Ask the person who opened the seat to restate it (seat/restate), or to give it a `delegates` ceiling so its own restates are held for their tap."}
  [row inp ctx]
  ;; A grant CITES a seat through `grant.seat` — the field wave two
  ;; adds (R-5.1). Until it exists this check reads nil off every
  ;; grant and finds nobody, which is the honest answer for an engine
  ;; where no grant can cite a seat yet.
  (let [p (:principal ctx)
        find' (:find ctx)]
    (if (or (= :system (:type p)) (nil? find'))
      (t/allow)
      (let [now (:now ctx)
            live? (fn [gr]
                    (and (= :accepted (:state gr))
                         (let [e (get-in gr [:data :expires_at])]
                           (or (nil? e) (neg? (compare now e))))))
            cited (into #{}
                        (comp (filter live?)
                              (keep #(some-> (get-in % [:data :seat]) str)))
                        (find' :grant {:audience (:id p)} {:limit 100}))]
        (cond
          (empty? cited) (t/allow)
          ;; A DELEGATING SEAT'S SITTER IS AN AUTHOR (server/delegation).
          ;; Its seat writes are judged by the delegation guards, which
          ;; stand last on these doors and hold for the person's tap
          ;; what this wall would have refused outright — its own seat
          ;; included. The engine's replay of a held call the person
          ;; allowed passes here for the same reason.
          (or (some? (delegation/author-seat ctx cited))
              (delegation/approved-hold? ctx :seat (:id row)))
          (t/allow)
          ;; the create door: there is no seat yet to compare against,
          ;; and R-4.4 still says the actor is "a person, not a
          ;; sitter" — a sitter that could open a fresh office with a
          ;; scope of its choosing would be widening itself sideways
          (nil? (:id row)) (t/deny)
          :else (if (some cited
                          (remove nil? [(str (:id row))
                                        (some-> (:into inp) str)]))
                  (t/deny)
                  (t/allow)))))))

;; ── a seat is never wider than its owner (epic 46831b3c, R-4.1–4.3) ──
;;
;; The owner is the seat's recorded `owner`; at the create door, where
;; there is no row yet, it is the person `seat-born` would write, else
;; the hand that opens the seat — its creator. A restated seat that
;; records no owner is not judged, as before. Only the entries a write
;; ADDS are judged, so a restate that leaves the scope alone passes.
;; Dotted powers are not judged here: an effective scope names kinds
;; only, and a person holds powers as granted.

;; `scope-inside-the-owners` and its helpers live in seats/owners.clj
;; (ticket 6aad331d), required above.

(g/defguard ttl-within-standing
  {:judges [:standing_ttl_seconds]
   :vars [:max_days :asked_days]
   :explain "A seat's longest leash is {max_days} days — the standing rotation's own window — and this one asks for {asked_days}. Ask for less; a sitter whose grant lapses files a fresh ask, which costs one tap and leaves a record."}
  [_row inp _ctx]
  (let [asked (long (or (:standing_ttl_seconds inp) 0))]
    (if (<= asked (long members/reentry-standing-ttl-seconds))
      (t/allow)
      (t/deny {:vars {:max_days (quot (long members/reentry-standing-ttl-seconds)
                                      86400)
                      :asked_days (quot asked 86400)}}))))

(g/defguard held-for-active-models
  {:judges [:held_for :substitute_for]
   :reads [:model :services]
   :vars [:model]
   :open "The models are the models collection, one query away — and the list a seat names is a handful of rows, not a vocabulary a create form could recite."
   :explain "A seat is held for models that can still sit, and {model} is retired or unknown. Reactivate it, or name a model that is active — or, for a connector, `connector:<client>` with a client this engine signs in; an empty list means any model may sit."}
  [_row inp ctx]
  (if-some [read' (:read ctx)]
    (let [clients (some->> (:connector-clients (:services ctx)) (map str) set)
          bad (some (fn [id]
                      ;; a connector token names a client, not a model
                      ;; row (R-5.7); a bare one is malformed, and where
                      ;; the engine knows its delegate clients a token
                      ;; naming none of them is a typo
                      (if (grants/connector-token? id)
                        (let [client (subs (str id) (count grants/connector-token-prefix))]
                          (when (or (str/blank? client)
                                    (and clients (not (contains? clients client))))
                            (str id)))
                        (let [r (read' :model (str id))]
                          (when-not (and r (= :active (:state r))) (str id)))))
                    (concat (:held_for inp) (:substitute_for inp)))]
      (if bad (t/deny {:vars {:model bad}}) (t/allow)))
    ;; the pure render probe carries no read hooks — advertise
    ;; optimistically there (members' reentry-minters precedent); the
    ;; real invoke, which DOES carry them, is the wall
    (t/allow)))


;; ── the walk's own filter (bead waymark-fp62.12, R-1) ───────────────
;;
;; A seat walked its kind under the KIND's default filter and nothing
;; else, so a kind that declares none could not be walked at all —
;; mealplan10's `plan` is one. A scope entry already carries a filter
;; in the grant's own grammar (grants/filter-map-schema): field=value,
;; equality only. That filter is now the walk's, and the seat row
;; gains no field for it. One map therefore says what the seat SEES
;; and what it WALKS, and a row that leaves the filter leaves both in
;; the same commit.

(defn walk-filters
  "The filters a walk's entries put on it when it runs under no ONE of
  them: several entries that all filter the kind, and not exactly one
  of them names a door (`walk-entry` answers none). The grant reads
  them as the union of their filters, so the sit's page, read under
  that grant, holds only rows under one of them, and a count of the
  walk reads the same union (ticket 9afe8b49). nil for every other
  shape, which `walk-filter` speaks for."
  [seat-row]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)
        entries (walk-entries (get-in seat-row [:data :scope]) walk)]
    (when (and (next entries)
               (every? #(seq (:filter %)) entries)
               (nil? (walk-entry entries)))
      (mapv :filter entries))))

(defn walk-doors
  "Every door the seat's scope names on the kind it walks, as strings:
  the union over every entry that names the kind, because each door one
  of them grants is a door the sitter holds (ticket c09893b1)."
  [seat-row]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
    (into #{} (comp (mapcat :actions) (map name))
          (walk-entries (get-in seat-row [:data :scope]) walk))))

(g/defguard walk-promises-are-served
  {:judges [:walk_promises]
   :reads [:services]
   :explain "A seat walks the breaches of the promises it keeps only where this engine serves promises."
   :open "Leave walk_promises off, or serve the promise kind."}
  [_row inp ctx]
  (let [rdef-of (:rdef-of ctx)]
    (if (and (true? (:walk_promises inp)) rdef-of (nil? (rdef-of "promise")))
      (t/deny)
      (t/allow))))

(g/defguard walk-names-a-kind-in-scope
  ;; :judges names :walk alone, though the check reads the scope beside
  ;; it: the refusal is ABOUT the walk, and a judged field needs either
  ;; a published constraint or an :open acknowledgment (check-closure).
  ;; :walk has a maxLength and the scope is a list of maps that has
  ;; neither — declaring both would buy an :open this guard does not
  ;; need and a picker warning nobody could clear.
  {:judges [:walk]
   :reads [:services]
   :vars [:walk :problem]
   :explain "A seat walks a queue it can already see, one row at a time, in the order the queue's own default sort gives: {walk} {problem}."}
  [_row inp ctx]
  (if-some [walk (some-> (:walk inp) str str/trim not-empty)]
    (let [rdef-of (:rdef-of ctx)
          entries (walk-entries (:scope inp) walk)
          in-scope? (boolean (seq entries))
          rdef (when rdef-of (rdef-of walk))]
      (cond
        (not in-scope?)
        (t/deny {:vars {:walk walk
                        :problem (str "is not a kind this seat's scope opens"
                                      " — add an entry for it, or leave walk"
                                      " empty and the seat walks nothing")}})
        ;; the probe ctx carries no registry — decline to guess
        (nil? rdef-of) (t/allow)
        (nil? rdef)
        (t/deny {:vars {:walk walk
                        :problem "is not a kind this engine serves"}})
        ;; A QUEUE FILTERS ITSELF, AND THE FIELD IS THE KIND'S OWN
        ;; (bead waymark-fp62.6.3.10). The check asked for a default
        ;; filter over STATE, which refused `task`: that kind keeps
        ;; its lifecycle in `status`, in the data, because its machine
        ;; is the sync machine and its endings are the authority's
        ;; word (workqueue10.resources.task's own note). What the seat
        ;; needs is that the collection a firing opens is the work
        ;; WAITING and not every row ever mirrored, and a default
        ;; filter over any field says that. So the check asks for one
        ;; default filter, whichever field it names.
        ;; A JUDGMENT'S QUEUE IS THE DEFAULT FILTER (R-4 of
        ;; waymark-fp62.11). A seat that names a judgment walks the
        ;; judgment's `queue` minus the subjects already judged, so
        ;; the collection a firing opens is the work waiting whatever
        ;; the kind declares — the filter stands where the kind's own
        ;; default would have.
        ;; A SCOPE ENTRY'S FILTER IS THE THIRD (bead waymark-fp62.12,
        ;; R-1). The entry that opens the kind may narrow it itself,
        ;; and then the collection a firing opens is that narrowing.
        ;; So one of three must hold, and this clause refuses the
        ;; seat that has none of them; `walk-leaves-its-filter` next
        ;; door asks whether the filter it does have can be emptied.
        (and (empty? (:default-filters rdef))
             (nil? (entry-filter entries))
             (nil? (some-> (:judgment inp) str not-empty)))
        (t/deny {:vars {:walk walk
                        :problem (str "declares no default filter at all,"
                                      " so the collection a firing opens is"
                                      " every row of it rather than the work"
                                      " waiting — give its scope entry a"
                                      " filter, walk a kind whose queue"
                                      " filters itself, or name a judgment"
                                      " whose queue filters it for you")}})
        :else (t/allow)))
    (t/allow)))

;; ── the walk must be able to empty its own filter (R-2) ─────────────
;;
;; A filter the seat cannot get a row out of is a queue that never
;; drains: the firing opens the same rows tomorrow, the sitting bills
;; for them again, and nothing on the row says why. So the engine
;; asks for the PROOF at the door, and it can only ask for the proof
;; it can see.
;;
;; Over `state` it sees everything it needs: the machine is declared,
;; and a door of the walked kind whose `from` includes the filtered
;; state and whose `to` is not that state moves a row out of the
;; filter. The entry must NAME that door — a door the seat may not
;; take is not the seat's way out.
;;
;; Over a data field it sees nothing: a handler writes that field, the
;; declaration does not say which one, and no reading of the rdef can
;; prove a row ever leaves. The one filter it can accept blind is the
;; kind's OWN default, which the kind wrote about itself and which
;; `walk-names-a-kind-in-scope` already accepts as the queue.

(defn- named-filter
  "One filter map as {field-name value-string} — the shape two
  spellings of one field (a keyword off the wire, a string out of the
  store) both reduce to."
  [fm]
  (into {} (map (fn [[k v]] [(name k) (str v)])) fm))

(defn- leaves-the-state?
  "Does one action of `rdef` named in `actions` move a row out of
  `state`? `usability/leaves-state?`'s reading, narrowed to the doors
  this scope entry opens: `:from` is a set and `:to` is one state, so
  a self-loop frees nothing and is not an exit."
  [rdef actions state]
  (let [named (into #{} (map str) actions)]
    (boolean
     (some (fn [a]
             (and (contains? named (name (:name a)))
                  (contains? (into #{} (map name) (:from a)) state)
                  (not= state (some-> (:to a) name))))
           (machine/edges-seq rdef)))))

(g/defguard walk-leaves-its-filter
  ;; :judges [:walk] and :reads [:services], the way
  ;; `walk-names-a-kind-in-scope` does it and for its reason: the
  ;; refusal is ABOUT the walk, and the scope entry beside it is what
  ;; the walk is judged against.
  {:judges [:walk]
   :reads [:services]
   :vars [:walk :problem]
   :open "The walked kind's doors are its published schema's, one GET away; no form can recite another kind's state machine, and the way out of this refusal is the scope entry in this same form."
   :explain "A seat walks a collection its own doors can empty: {problem}."}
  [_row inp ctx]
  (if-some [walk (some-> (:walk inp) str str/trim not-empty)]
    (let [rdef-of (:rdef-of ctx)
          rdef (when rdef-of (rdef-of walk))
          entries (walk-entries (:scope inp) walk)
          entry (walk-entry entries)
          fm (named-filter (entry-filter entries))
          state (get fm "state")
          problem
          (cond
            ;; the probe ctx carries no registry, and a walk naming a
            ;; kind this engine does not serve is the guard next door's
            ;; refusal — decline to guess at both, its own posture
            (or (nil? rdef-of) (nil? rdef)) nil

            ;; R-3: a judgment seat keeps its relaxation. It walks the
            ;; judgment's `queue` minus the subjects already judged,
            ;; and the verdict row is the exit — a door on another
            ;; kind, which nothing here could read off this one.
            (some-> (:judgment inp) str not-empty) nil

            (empty? fm)
            (when (empty? (:default-filters rdef))
              (str "the scope entry for " walk " carries no filter, " walk
                   " declares no default filter, and this seat names no"
                   " judgment — narrow the entry with a filter, or name a"
                   " judgment whose queue narrows it"))

            ;; an any-of filter ("drafting,simulated") is judged state by
            ;; state: each one the walk covers needs its own way out
            state
            (when-some [stuck (first (remove #(leaves-the-state? rdef (:actions entry) %)
                                             (map str/trim (str/split state #","))))]
              (str "no door this seat may take moves a " stuck " " walk
                   " out of " stuck " — add a door of " walk
                   " that leaves " stuck " to that scope entry's actions,"
                   " or filter the entry by a state one of its doors"
                   " leaves"))

            ;; A DATA FIELD OVER A KIND THAT NARROWS ITSELF IS THE
            ;; LEASH, NOT THE QUEUE. The bead's sentence is "a filter
            ;; over a field that is not state is accepted only when it
            ;; equals the kind's default filter", and the departure
            ;; recorded here is that a kind which declares ANY default
            ;; filter has already answered the question this guard
            ;; asks: its own default is what opens the collection on
            ;; the work waiting, `walk-names-a-kind-in-scope` accepts
            ;; it for that (R-12.32), and the entry's data filter
            ;; narrows the leash beside it as it always has. The rule
            ;; bites where it was written to bite — a kind with NO
            ;; default filter, where the entry's filter is the whole
            ;; of what narrows the walk and nothing in the
            ;; declaration can prove a row ever leaves it.
            :else
            (when (empty? (:default-filters rdef))
              (str "the scope entry for " walk " filters by "
                   (str/join ", " (map (fn [[f v]] (str f "=" v))
                                       (sort-by key fm)))
                   ", which is the whole of what narrows this walk: " walk
                   " declares no default filter of its own, and the engine"
                   " cannot see the handler that writes a data field — so it"
                   " cannot prove a row ever leaves that filter. Filter by"
                   " state, and name the door that leaves it, or walk a kind"
                   " whose own default filter narrows the queue")))]
      (if problem (t/deny {:vars {:walk walk :problem problem}}) (t/allow)))
    (t/allow)))

(defn- default-state-of
  "The state a kind's own default filter shows, as written, or nil."
  [rdef]
  (some-> (or (get-in rdef [:default-filters :state])
              (get-in rdef [:default-filters "state"]))
          str not-empty))

(defn- doors-the-default-hides
  "The doors a seat's scope names on the kind it walks that the kind's
  own default filter hides from that walk (ticket 086dcbfd). Entries
  that put no filter on the walk leave it under the kind's
  `:default-filters` (`walk-filter`), and a door whose every from-state
  is outside the default state never meets a row there. → [{:door
  :from}] by door name, or nil — nil too when an entry filters the
  kind, and when the kind declares no default state."
  [rdef entries]
  (when-some [dflt (default-state-of rdef)]
    (when (and (seq entries) (every? #(empty? (:filter %)) entries))
      (let [shown (into #{} (map str/trim) (str/split dflt #","))
            named (into #{} (comp (mapcat :actions) (map str)) entries)
            froms (reduce (fn [m a]
                            (let [door (name (:name a))]
                              (cond-> m
                                (contains? named door)
                                (update door (fnil into #{}) (map name (:from a))))))
                          (sorted-map)
                          (machine/edges-seq rdef))]
        (not-empty
         (into []
               (keep (fn [[door from]]
                       (when (and (seq from) (not-any? shown from))
                         {:door door :from (str/join "," (sort from))})))
               froms))))))

(g/defguard walk-not-hidden-by-its-default
  ;; an advisory, judged as `walk-leaves-its-filter` is: a walk under
  ;; its kind's default filter is legal, and a seat may mean it — but a
  ;; door it holds that leaves only states the default hides is a walk
  ;; that never offers that door a row (ticket 086dcbfd)
  {:severity :warning
   :judges [:walk]
   :reads [:services]
   :vars [:walk :default :doors]
   :open "The walked kind's default filter is in its published schema; the way out is a filter on the scope entry in this same form, or this warning acknowledged by its name."
   :explain "The scope entry for {walk} carries no filter, so this seat walks {walk} under its default filter state={default}, and {doors} never meets a row there. Give the entry a filter naming the states this seat works, or acknowledge this warning to walk the default on purpose."}
  [_row inp ctx]
  (let [walk (some-> (:walk inp) str str/trim not-empty)
        rdef-of (:rdef-of ctx)
        rdef (when (and walk rdef-of) (rdef-of walk))
        hidden (when (and rdef (not (some-> (:judgment inp) str not-empty)))
                 (doors-the-default-hides rdef (walk-entries (:scope inp) walk)))]
    (if hidden
      (t/deny {:vars {:walk walk
                      :default (default-state-of rdef)
                      :doors (str/join ", " (map #(str "`" (:door %) "` (from " (:from %) ")")
                                                 hidden))}})
      (t/allow))))

;; ── the seat that says a judgment (bead waymark-fp62.11, R-4) ───────

(g/defguard walk-matches-the-judgment
  ;; :judges names :judgment alone, `walk-names-a-kind-in-scope`'s own
  ;; reading: the refusal is ABOUT the judgment this seat cites, and
  ;; the walk and the scope beside it are what it is judged against.
  ;; `:remedies` names the one DOOR that turns a refusal into a pass
  ;; without the person changing a word of what they typed — a draft
  ;; judgment is promoted, and the other two readings name a field of
  ;; the form the person is already standing in.
  {:judges [:judgment]
   :reads [:judgment :principal :now :grant :seat :held_call :within]
   :vars [:problem]
   :remedies [:judgment/promote]
   :explain "A seat that says a judgment walks that judgment's own subjects and answers with its verdicts: {problem}."}
  [row inp ctx]
  (if-some [id (some-> (:judgment inp) str not-empty)]
    ;; the pure render probe carries no read hooks — advertise
    ;; optimistically there, `held-for-active-models`' posture
    (if-some [read' (:read ctx)]
      (let [j (read' :judgment id)
            subject (some-> (get-in j [:data :subject_kind]) str not-empty)
            walk (some-> (:walk inp) str str/trim not-empty)
            says-verdicts? (boolean
                            (some (fn [e]
                                    (and (= "verdict" (str (:kind e)))
                                         (some #(= "judge" (str %))
                                               (:actions e))))
                                  (:scope inp)))
            problem (cond
                      (nil? j)
                      (str "there is no judgment " id " on this engine")

                      ;; AN AUTHORED SEAT IS BORN PARKED (server/delegation,
                      ;; invariant 5), and a parked seat walks nothing. So
                      ;; its author may cite a DRAFT and promote it while
                      ;; the seat still waits on its person's unpark —
                      ;; `judgment-in-force` refuses the unpark until it
                      ;; is promoted.
                      (and (= :draft (:state j))
                           (nil? (:id row))
                           (some? (delegation/authoring-seat ctx :seat nil)))
                      nil

                      ;; a superseded judgment cannot be promoted, so
                      ;; it is `walk-judgment-not-superseded`'s to
                      ;; refuse: this guard's remedy is a promote
                      (= :superseded (:state j))
                      nil

                      (not= :promoted (:state j))
                      (str "that judgment is " (name (:state j))
                           ", and only a promoted one is walked — promote it,"
                           " or name one that is already promoted")

                      (not= walk subject)
                      (str "its subjects are rows of kind " subject
                           ", and this seat walks " (or walk "nothing")
                           " — set the walk to " subject)

                      (not says-verdicts?)
                      (str "the scope opens no way to say one — add an entry"
                           " for kind verdict with the action judge"))]
        (if problem (t/deny {:vars {:problem problem}}) (t/allow)))
      (t/allow))
    (t/allow)))

(g/defguard walk-judgment-not-superseded
  ;; No remedy: a superseded judgment cannot be promoted, so the door
  ;; `walk-matches-the-judgment` names would only send the reader to a
  ;; wall — `judgment-not-superseded`'s split, at the create and the
  ;; restate. Say where the house went next (ticket 86514746).
  {:judges [:judgment]
   :open "No door revives a superseded judgment. The way out is in this same form: name the judgment the sentence names, or another promoted one."
   :reads [:judgment]
   :vars [:problem]
   :explain "A seat that says a judgment walks that judgment's own subjects and answers with its verdicts: {problem}."}
  [_row inp ctx]
  (let [id (some-> (:judgment inp) str not-empty)
        read' (:read ctx)
        j (when (and id read') (read' :judgment id))]
    (if (and j (= :superseded (:state j)))
      (t/deny {:vars {:problem
                      (if-some [s (some-> (get-in j [:data :successor])
                                          str not-empty)]
                        (str "that judgment is superseded by " s
                             ", and only a promoted one is walked — name " s
                             " instead")
                        (str "that judgment is superseded with no successor"
                             ", and only a promoted one is walked — name one"
                             " that is promoted"))}})
      (t/allow))))

;; ── what an inbox hears is named the way its scope is ──────────────
;;
;; An `inbox` entry, spelled as a wake entry, is judged the
;; way a scope entry is: the kind must be one this engine serves, and
;; each action must be one that kind actually has. No seat door takes
;; `wake_on` itself any more (ticket 6a46a233): what wakes a seat is
;; written on its fire door's subscriptions. An entry nobody can match
;; is a seat that never hears and never says why.

(defn- wake-on-unknown-kind
  "The first wake entry naming something this engine does not
  serve, or nil. `names-of` is the ctx's action-name lookup: nil for a
  kind the registry has never heard of."
  [names-of entries]
  (first (for [e entries
               :let [k (str (:kind e))]
               :when (nil? (names-of k))]
           k)))

(defn- wake-on-unknown-action
  "The first wake entry naming an action its own kind does not
  have, as the refusal's vars, or nil."
  [names-of entries]
  (first (for [e entries
               :let [known (names-of (str (:kind e)))]
               :when known
               a (:actions e)
               :when (not (contains? known (str a)))]
           {:kind (str (:kind e)) :action (str a)
            :actions (str/join ", " (sort known))})))

;; An `inbox`'s `only` is a kind → actions map, the shape of a
;; subscriber's `only`; spelled as wake entries it is judged by
;; the same two lookups, in two guards of its own because a guard
;; judges the one field it names.

(defn- inbox-entries
  "The seat's `inbox` as wake entries: each kind of its
  `only`, then each of its `cues`, so the two guards below judge a
  cue's kind and actions as they judge the `only`."
  [inbox]
  (concat (for [[k actions] (:only inbox)]
            {:kind (name k) :actions actions})
          (for [c (:cues inbox)]
            {:kind (str (:kind c)) :actions (:actions c)})))

(g/defguard inbox-names-real-kinds
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind]
   :open "The legal kind names are well-known's resources, one GET away; enumerating the registry into this form would duplicate it."
   :explain "An inbox holds a kind this surface serves; there is no kind {kind}."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-kind names-of (inbox-entries (:inbox inp)))]
      (t/deny {:vars {:kind bad}})
      (t/allow))
    (t/allow)))

(g/defguard inbox-names-real-actions
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind :action :actions]
   :open "Each kind's action vocabulary is well-known's actions list, one GET away; the refusal spells the kind's real actions when an entry misses."
   :explain "There is no action {action} on {kind}; its actions are: {actions}. Name its actions, or an empty list for every one."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-action names-of (inbox-entries (:inbox inp)))]
      (t/deny {:vars bad})
      (t/allow))
    (t/allow)))

;; A cue's `filter` is matched against the row when the inbox door
;; serves its event (`routes.seats/cue-match`), by equality on `state`
;; or on a data field. A field the kind does not declare filterable is
;; a cue that never matches and never says why, so it is refused here.

(g/defguard inbox-cues-filter-declared-fields
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind :field :fields]
   :open "A kind's filterable fields and their ops are its collection grammar, one GET away; the refusal spells them when a cue misses."
   :explain "A cue on {kind} cannot filter by {field}: a cue's filter names `state`, or a field the kind declares filterable (eq), and a comma-separated value (any of) only on a field it declares filterable with in. The filterable fields of {kind} are: {fields}."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [bad (first (for [c (:cues (:inbox inp))
                               :let [rdef (rdef-of (:kind c))]
                               :when rdef
                               [f v] (:filter c)
                               :let [fname (name f)
                                     ops (get (:filterable rdef) (keyword fname))]
                               :when (and (not= "state" fname)
                                          (or (not-any? #(contains? ops %) [:eq :in])
                                              (and (str/includes? (str v) ",")
                                                   (not (contains? ops :in)))))]
                           {:kind (str (:kind c)) :field fname
                            :fields (str/join ", " (sort (map name (keys (:filterable rdef)))))}))]
      (t/deny {:vars bad})
      (t/allow))
    ;; the pure render probe carries no registry — decline to guess
    (t/allow)))

;; A listener's filter is judged by `listen/matches?` through
;; `collections/row-matches?`, which reads it as the kind's query does.
;; A filter that query refuses would never match, so the write door
;; refuses it in the query's own sentence (docs/spec-events.md E-1.5).

(defn- filter-refusal
  "The first of `entries` whose `filter` the kind's query refuses, as
  `{:kind … :sentence …}`, or nil."
  [rdef-of entries]
  (first (for [e entries
               :let [rdef (rdef-of (:kind e))]
               :when (and rdef (seq (:filter e)))
               :let [problems (collections/filter-problems rdef [(:filter e)])]
               :when problems
               :let [said (val (first problems))]]
           {:kind (str (:kind e))
            :sentence (str (if (coll? said) (first said) said))})))

(g/defguard inbox-cues-filter-is-filterable
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind :sentence]
   :open "A kind's filterable fields are its collection grammar, one GET away; the refusal is the sentence that grammar gives a list page."
   :explain "A cue's filter on {kind} cannot be judged: {sentence}"}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [bad (filter-refusal rdef-of (:cues (:inbox inp)))]
      (t/deny {:vars bad})
      (t/allow))
    ;; the pure render probe carries no registry — decline to guess
    (t/allow)))

(g/defguard cast-only-on-a-clone
  {:judges [:acts_as_cast]
   :reads [:services.features]
   :open "No door makes an engine a clone: it is a fact of the boot (WAYMARK10_CLONE=1, which the clone door sets). Off a clone, state the seat without acts_as_cast."
   :explain "Only a clone seats a cast member: acts_as_cast plays an org's customers and writes rows no real customer filed, and this engine is not a clone (WAYMARK10_CLONE is not 1)."}
  [_row inp ctx]
  (if (and (true? (:acts_as_cast inp))
           (not (contains? (set (map name (:features (:services ctx)))) "clone")))
    (t/deny)
    (t/allow)))

(g/defguard step-carries-a-note
  {:judges [:note]
   :explain "A step up or down the ladder is a record: a restate that changes held_for or substitute_for carries a note saying which model it was, which it is now, and why. Everything else about a seat may move silently; the model it is held for may not."}
  [row inp _ctx]
  (if (and (or (field-moved? row inp :held_for)
               (field-moved? row inp :substitute_for))
           (str/blank? (str (:note inp))))
    (t/deny)
    (t/allow)))

(g/defguard merge-target-is-active
  {:judges [:into]
   :reads [:seat]
   :vars [:into]
   :explain "A merge folds this seat into a LIVE one: {into} is not an active seat, or it is this seat. Name the office the work is moving to."}
  [row inp ctx]
  (let [into-id (some-> (:into inp) str)]
    (cond
      (nil? into-id) (t/allow)          ; the schema refuses the blank
      (= into-id (str (:id row))) (t/deny {:vars {:into into-id}})
      (nil? (:read ctx)) (t/allow)      ; probe ctx — decline to guess
      :else (let [target ((:read ctx) :seat into-id)]
              (if (and target (= :active (:state target)))
                (t/allow)
                (t/deny {:vars {:into into-id}}))))))

(g/defguard author-can-take-it
  {:judges [:author]
   :reads [:seat]
   :vars [:detail]
   :explain "A seat is handed only to a seat that delegates, and only when it fits under that seat's ceiling: {detail}."
   :open "The way out is in this same form: name a seat that delegates and whose ceiling covers this one. A seat's ceiling is the delegates field of its own row, one GET away."}
  [row inp ctx]
  ;; hand_to (invariant 3 and 4 of server/delegation): the person's
  ;; tap writes the author AND the approval, so the seat must already
  ;; be one the author could have authored — R-14.3 whole, the ceiling
  ;; judged by the same `misfit` the create and restate doors read.
  (let [author-id (some-> (:author inp) str not-empty)]
    (cond
      (nil? author-id) (t/allow)          ; the schema refuses the blank
      (= author-id (str (:id row)))
      (t/deny {:vars {:detail "a seat does not author itself"}})
      (delegation/delegating? row)
      (t/deny {:vars {:detail (str "this seat carries a ceiling of its own"
                                   " (delegates), and an authored seat may not")}})
      (nil? (:read ctx)) (t/allow)      ; probe ctx — decline to guess
      :else
      (let [author ((:read ctx) :seat author-id)]
        (cond
          (not (and author (delegation/delegating? author)))
          (t/deny {:vars {:detail (str author-id " is not a seat that"
                                       " delegates: it carries no ceiling")}})
          :else
          (if-some [m (delegation/misfit author (:data row))]
            (t/deny {:vars {:detail m}})
            (t/allow)))))))

(g/defguard the-engines-own-hand
  {:reads [:principal]
   :hide true
   :explain "The sweep and the router write this, never a hand at the wire."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard the-engine-or-the-persons-tap
  {:reads [:principal :within]
   :hold true
   :vars [:seat :started_at]
   :explain "Abandoning seat {seat}'s sitting, open since {started_at}, is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the person's tap, and an agent that could abandon a sitting alone could end another seat's work."}
  [row _inp ctx]
  ;; NOT hidden (ticket be2c2c16): a door the grant admits must never
  ;; answer not-found. The sweep and a person pass; an agent's abandon,
  ;; the mayor's of another seat's sitting included, waits for the
  ;; person, and the one agent abandon this admits is the engine's
  ;; replay of the held call that person allowed.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :sitting :abandon (:id row)) (t/allow)
    :else (t/deny {:vars {:seat (str (get-in row [:data :seat]))
                          :started_at (str (get-in row [:data :started_at]))}})))

(g/defguard lease-expired
  {:reads [:now]
   :vars [:expires_at]
   :explain "This sitting's lease ran out at {expires_at}, and a lapsed lease is not renewed."
   :open "No door clears it: the session sits afresh, and the sweep ends this sitting."}
  [row _inp ctx]
  ;; judged on the row the renewal holds for update, so a renewal
  ;; racing the sweep either lands first or is refused here
  (let [exp (->instant (get-in row [:data :expires_at]))
        now (->instant (:now ctx))]
    (if (or (nil? exp) (nil? now) (.isBefore ^java.time.Instant now exp))
      (t/allow)
      (t/deny {:vars {:expires_at (str exp)}}))))

(g/defguard lease-still-running
  {:reads [:now]
   :vars [:expires_at]
   :explain "This sitting's lease runs until {expires_at}; only a lease that has run out is expired."
   :open "Time clears it: the lease runs out at its end unless a call renews it first, and no door hurries that."}
  [row _inp ctx]
  ;; judged on the row the expiry holds for update, as `renew` is
  (let [exp (->instant (get-in row [:data :expires_at]))
        now (->instant (:now ctx))]
    (if (and exp now (.isBefore ^java.time.Instant exp now))
      (t/allow)
      (t/deny {:vars {:expires_at (str exp)}}))))

(g/defguard folded-by-a-merge
  {:reads [:within]
   :hide true
   :explain "A seat's scope grows by a merge and by nothing else: POST merge on the seat being folded in, and this fold happens in the same transaction."}
  [_row _inp ctx]
  ;; THE :within READ (waymark-jfv.20, composition_request's
  ;; precedent). At the wire and on the render probe it is nil and the
  ;; door is concealed, which is the truth: no client taps it. Inside
  ;; `merge`'s own handler it names that write, and the door opens.
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :seat kind) (= :merge action))
      (t/allow)
      (t/deny))))

(g/defguard followed-by-a-supersede
  {:reads [:within]
   :hide true
   :explain "A seat follows a judgment's successor when that judgment is superseded, and by nothing else: POST supersede on the judgment, and every seat that says it follows in the same transaction."}
  [_row _inp ctx]
  ;; `folded-by-a-merge`'s posture (ticket 86514746): concealed at the
  ;; wire, open only inside the judgment's own `supersede` handler
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :judgment kind) (= :supersede action))
      (t/allow)
      (t/deny))))

;; ── the fire door's four guards (R-12.19, R-12.20) ──────────────────
;;
;; Each refuses with ONE sentence, and each says what to do next. A
;; fire is fuel: the engine never fires a seat behind a wall, and
;; never fires one whose Routine nobody has linked.

(g/defguard a-person-or-the-engine
  {:reads [:principal]
   :explain "A fire is a person's act or the engine's. An agent with no person behind it does not fire a seat. Ask the person who opened this seat, or file an approval_request."}
  [_row _inp ctx]
  ;; `a-person` plus the engine. The engine is here because the wake
  ;; consumer fires through this same door (R-12.22) and a second,
  ;; concealed door would be the same law written twice; a BARE agent
  ;; is refused exactly as it is at every other seat door.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (= :system type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(g/defguard not-parked
  {:explain "The seat is parked. Unpark it first."}
  [row _inp _ctx]
  ;; `parked` is in the door's from-set on purpose: a refusal that
  ;; names the park and says the way back is worth more than a 409
  ;; saying the door is not there.
  (if (= :parked (:state row))
    (t/deny)
    (t/allow)))

(g/defguard judgment-not-superseded
  ;; No remedy: a superseded judgment cannot be promoted, so the door
  ;; `judgment-in-force` names would only send the reader to a wall.
  ;; Say where the house went next, as `walk-judgment-not-superseded`
  ;; does (ticket 86514746). A supersede re-points parked seats too,
  ;; so this bites only rows written before it did.
  {:reads [:judgment]
   :vars [:judgment :problem]
   :explain "This seat says the judgment {judgment}, which is superseded, and a seat walks only a judgment in force: {problem}, then unpark."
   :open "No door revives a superseded judgment. The way out is this seat's own restate, naming the judgment the sentence names."}
  [row _inp ctx]
  (let [id (some-> (get-in row [:data :judgment]) str not-empty)
        read' (:read ctx)]
    (if (and id read')
      (let [j (read' :judgment id)]
        (if (and j (= :superseded (:state j)))
          (t/deny {:vars {:judgment (or (some-> (get-in j [:data :name]) str)
                                        id)
                          :problem
                          (if-some [s (some-> (get-in j [:data :successor])
                                              str not-empty)]
                            (str "it is superseded by " s
                                 " — restate the seat to name " s)
                            (str "it is superseded with no successor"
                                 " — restate the seat to name a promoted"
                                 " judgment, or without one"))}})
          (t/allow)))
      (t/allow))))

(g/defguard judgment-in-force
  {:reads [:judgment]
   :vars [:judgment]
   :remedies [:judgment/promote]
   :explain "This seat says the judgment {judgment}, which is not promoted, and a seat walks only a judgment in force. Promote it, or restate the seat without it, then unpark."}
  [row _inp ctx]
  ;; An authored seat may be born citing a DRAFT (invariant 5), and
  ;; the unpark is the moment it would start walking one. A person's
  ;; unpark meets this wall too: it is about the seat, not the hand.
  ;; A superseded judgment is `judgment-not-superseded`'s to refuse:
  ;; this one's remedy is a promote, and that one cannot be promoted.
  (let [id (some-> (get-in row [:data :judgment]) str not-empty)
        read' (:read ctx)]
    (if (and id read')
      (let [j (read' :judgment id)]
        (if (and j (not (#{:promoted :superseded} (:state j))))
          (t/deny {:vars {:judgment (or (some-> (get-in j [:data :name]) str)
                                        id)}})
          (t/allow)))
      (t/allow))))

(g/defguard not-interactive
  {:explain "The seat is an interactive seat. A person sits here; nothing fires it."}
  [row _inp _ctx]
  ;; R-10.8. The mode is the SEAT'S, so the wall is on the seat's own
  ;; door rather than on the principal: a person's fire, a wake and
  ;; the schedules consumer's cadence all arrive here, and an
  ;; interactive seat is woken by none of the three.
  (if (interactive-seat? row)
    (t/deny)
    (t/allow)))

(def ^:private wall-sentences
  "One sentence for each wall, for the rare halt that carries no
  detail — a row written before the router had a sentence, or one
  written by hand in a test. The router's own detail wins where it is
  there, which is why these are short."
  {"seat_not_active" "The seat serves nothing until somebody opens it again."
   "model_not_held" "The session's model is not one this seat is held for."
   "budget_reached" "The week's fuel is spent. The wall lifts as the window rolls."
   "sitting_budget_reached" "This sitting's fuel is spent. Close the sitting; a new one opens fresh."})

(def ^:private wall-exits
  "What lifts each wall, said at the fire door (R-4 of
  waymark-fp62.7.13). A halt line is a RECORD of a wall, not a lock:
  the door judges the week's fuel again for itself, so the two walls
  it cannot judge with no sitter in the room must say what a person
  does about them."
  {"seat_not_active" "Unpark the seat and the line lifts."
   "model_not_held" "Restate the seat to hold the model that sits here, and the line lifts."
   "budget_reached" "The wall lifts on its own as the window rolls."
   "sitting_budget_reached" "The wall lifts when that sitting closes."})

(defn- fuel-left?
  "Is there fuel left in this seat's week? The reading is the WALL'S
  own — `grants/spent-with` over `grants/spend-window-conds`, through
  the ctx `:sum-amounts` hook — so the door and R-5.2 step 3 cannot
  disagree about one number. nil when there is no hook to ask with: a
  ctx without one cannot judge the week, and the caller then reads the
  line as it stands."
  [row ctx]
  (when (and (:sum-amounts ctx) (:now ctx))
    (grants/under-budget? (grants/spent-with (:sum-amounts ctx) (:id row) (:now ctx))
                          (get-in row [:data :budget_usd_per_week]))))

(g/defguard not-halted
  {:reads [:storage]
   :vars [:wall :exit]
   :explain "The seat is against a wall. {wall} {exit}"}
  [row _inp ctx]
  ;; R-3: the halt line is a record of a wall that HELD, and the
  ;; rolling window lifts the fuel wall with no hand. A fired seat
  ;; makes no request of its own that could clear the line, so the
  ;; door that reads the line judges that one wall again. The other
  ;; three it cannot: `model_not_held` needs the sitter's claim, a
  ;; parked seat is a person's choice, and a sitting past its ceiling
  ;; is an interactive seat's, which `not-interactive` already refuses.
  (if-some [halt (get-in row [:data :halt])]
    (let [reason (str (:reason halt))]
      (if (and (= "budget_reached" reason) (true? (fuel-left? row ctx)))
        (t/allow)
        (t/deny {:vars {:wall (or (some-> (:detail halt) str not-empty)
                                  (get wall-sentences reason)
                                  "Wait for the wall to lift.")
                        :exit (get wall-exits reason
                                   "The wall lifts when the condition clears.")}})))
    (t/allow)))

(g/defguard linked-for-fire
  {:reads [:schedule :model :runner_link]
   :explain "Bring a runner link live in the pool of this seat's schedule, or of the model it is held for, first."}
  [row _inp ctx]
  ;; The schedule is read through the ctx `:find` hook — the write's
  ;; own transaction, `schedules/one-per-seat?`'s spelling exactly. A
  ;; ctx without the hook (the pure render probe) advertises
  ;; optimistically, as every cross-row guard here does.
  ;;
  ;; A SEAT WITH NO POOL OF ITS OWN FIRES THROUGH ITS CHAIR'S (R-5 of
  ;; waymark-fp62.7.23). The runner link's pool is the only fire
  ;; credential (ticket 9fa42626).
  ;;
  ;; A POOL IS READ AS THE WAKE READS IT (`schedules/pool-of` — this
  ;; file cannot require that one, which requires it): a fire is
  ;; allowed while one of its runners is live. A schedule the provider
  ;; broke is NOT refused here: a person's fire of it is the probe that
  ;; checks a repair, and the engine's own wakes hold on
  ;; `schedules/held?` instead.
  (if-some [find' (:find ctx)]
    (let [sched (first (find' :schedule {:seat (str (:id row))} {:limit 1}))
          read' (:read ctx)
          chair (when read'
                  (some->> (chair-of row) (read' :model)))
          pool (pool-from #(find' :runner_link {} {:limit 100000})
                          sched chair)]
      (if (and read'
               (some #(= "live" (some-> (read' :runner_link %) :state name))
                     pool))
        (t/allow)
        (t/deny)))
    (t/allow)))

(g/defguard one-model-spelling
  {:judges [:name]
   :reads [:model]
   :open "The registered identifiers are the models collection, one query away; enumerating them into the create form would offer exactly the tokens the guard is about to refuse."
   :explain "A model named {name} is already on record — one row per API identifier. If it was retired, reactivate that row rather than minting a second one: its prices are the history a closed sitting was costed against."}
  [row inp ctx]
  ;; on `restate` the row is the model itself, and its own name is no
  ;; collision; on create there is no row, so nothing is set aside
  (if-some [find' (:find ctx)]
    (if (and (some? (:name inp))
             (seq (remove #(= (str (:id %)) (some-> row :id str))
                          (find' :model {:name (str (:name inp))} {:limit 2}))))
      (t/deny {:vars {:name (str (:name inp))}})
      (t/allow))
    (t/allow)))
