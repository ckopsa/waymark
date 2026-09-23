(ns waymark10.server.delegation
  "Delegated seat authorship: a seat that authors other seats, and the
  person's tap on what it may not do alone.

  A MAYOR IS A SEAT WHOSE PERSON WROTE IT A CEILING. `not-a-sitter`
  refused every seat write from a hand holding a seat grant, so a
  person who put seat.create in a seat's scope got nothing. A seat
  whose row carries `delegates` is a DELEGATING seat, and its sitter
  may do what the person may do at a seat's doors. Some of those calls
  go straight through. The rest are HELD: the engine records the exact
  call as a `held_call` row, the person's Allow replays it as the
  author, and the replay lands in the target's history like any other
  transition. Nothing the author asks for is refused outright; what
  it may not do alone waits for a tap.

  ── the five invariants, and where each is spoken ─────────────────

  1. A seat never restates ITSELF, or any seat whose grant it holds,
     on its own. Its scope, charter, budgets and ceiling stay the
     person's: every such call is held.
  2. Nothing past the CEILING. A child's scope must fit under
     `delegates.scope` entry by entry and filter by filter, the way
     grants/entry-within-surface? fits a minted entry under a leash,
     and its budgets and models under the ceiling's caps. The ceiling
     is NOT the author's own scope: a mayor gives bench.write on repo
     bench without holding it. A call past the ceiling is held, and
     the sentence names the ceiling entry that would have admitted it.
  3. Every authored seat records `authored_by` (the author seat) and
     `owner` (the person the author acts for). The seat's on-create
     stamps both; no door takes them from a hand.
  4. An authored seat is born PARKED, and the person's first unpark
     is the approval. After it, the author restates its own child
     within the ceiling with no new tap. Unpark, merge and retire are
     the person's: from the author they are held.
  5. A judgment is promoted by a delegating seat only when a seat it
     authored cites that judgment and is still parked, so a promotion
     never changes a live seat's work unseen. Anything else is held.

  ── how a hold is spoken ───────────────────────────────────────────

  A guard can only allow or refuse, so a hold is a refusal whose guard
  is one of `hold-guards`. The router catches exactly those refusals
  and mints the held call in their place (router/held-instead). Every
  other guard on the door runs FIRST, so a held call is one the engine
  would have served, except that it needs the person.

  THE REPLAY IS RECOGNISED BY `:within`. The engine's own replay
  (held-calls/forward!) invokes the door as the author, with `:within`
  naming the held row. The guards read that row back through the
  write's own transaction and admit the call only while it is
  `allowed`, names this door and names this caller. A hand at the wire
  cannot set `:within`, so it cannot forge the person's yes."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── who is asking ───────────────────────────────────────────────────

(defn- nonblank [x] (some-> x str str/trim not-empty))

(defn- live-grant?
  "Accepted and unexpired by the live clock, `not-a-sitter`'s own
  reading."
  [now gr]
  (and (= :accepted (:state gr))
       (let [e (get-in gr [:data :expires_at])]
         (or (nil? e) (neg? (compare now e))))))

(defn cited-seats
  "The ids of the seats the principal's live grants cite, as a set of
  strings. Empty for a person, and for a ctx with no :find hook (the
  render probe, which declines to guess)."
  [ctx]
  (if-some [find' (:find ctx)]
    (let [p (:principal ctx)]
      (if (= :system (:type p))
        #{}
        (into #{}
              (comp (filter #(live-grant? (:now ctx) %))
                    (keep #(nonblank (get-in % [:data :seat]))))
              (find' :grant {:audience (:id p)} {:limit 100}))))
    #{}))

(defn delegating?
  "Does this seat row carry a ceiling its person wrote?"
  [seat-row]
  (some? (get-in seat-row [:data :delegates])))

(defn author-seat
  "The DELEGATING seat among the ones this principal sits in, or nil.
  A sitter of a seat that delegates nothing is not an author, and
  `not-a-sitter` answers for it as it always did."
  ([ctx] (author-seat ctx (cited-seats ctx)))
  ([ctx cited]
   (when-some [read' (:read ctx)]
     (some (fn [id]
             (let [s (read' :seat id)]
               (when (and s (delegating? s)) s)))
           (sort cited)))))

(defn allowed-hold
  "The held call this write replays, when it is the engine's replay of
  one the person allowed, or nil. `:within` names the held row, and
  the row is read back through the write's own transaction: it must be
  `allowed`, name this kind and this row, and name this caller.
  `target` is the row id, nil at a create door."
  [ctx kind target]
  (let [{wkind :kind waction :action hid :id} (:within ctx)
        read' (:read ctx)]
    (when (and (= :held_call wkind) (= :allow waction) hid read')
      (when-some [h (read' :held_call (str hid))]
        (let [door (get-in h [:data :door])]
          (when (and (= :allowed (:state h))
                     (= (name kind) (str (:kind door)))
                     (= (some-> target str) (nonblank (:id door)))
                     (= (str (get-in ctx [:principal :id]))
                        (str (get-in h [:data :caller]))))
            h))))))

(defn approved-hold?
  "`allowed-hold`, as the yes-or-no a guard asks."
  [ctx kind target]
  (some? (allowed-hold ctx kind target)))

(defn authoring-seat
  "The seat that authors THIS write, or nil: the author the held call
  recorded when this is the replay of one (a grant may have lapsed
  between the ask and the tap, and the person approved the author the
  row names), else the delegating seat the principal sits in."
  [ctx kind target]
  (if-some [h (allowed-hold ctx kind target)]
    (when-some [a (nonblank (get-in h [:data :door :author]))]
      (when-some [read' (:read ctx)] (read' :seat a)))
    (author-seat ctx)))

(defn owner-of
  "The person an author acts for: the delegate mark the identity gate
  (or the sit) put on the principal."
  [ctx]
  (nonblank (get-in ctx [:principal :acts-for])))

;; ── the ceiling (invariant 2) ───────────────────────────────────────

(defn- strs [xs] (into #{} (map str) xs))

(defn- filter-pairs
  "A filter map as {field-name value-string}, however the wire or the
  store spelled its keys."
  [fm]
  (into {} (map (fn [[k v]] [(name k) (str v)])) fm))

(defn- norm-fields [f]
  (when f {:mode (str (:mode f)) :names (strs (:names f))}))

(defn- norm-args [as]
  (into #{} (map (fn [a] {:action (str (:action a)) :mode (str (:mode a))
                          :names (strs (:names a))}))
        as))

(defn entry-fits?
  "Does one child scope entry fit under one ceiling entry? The same
  kind; every action of the child among the ceiling's; the child's ids
  inside the ceiling's when the ceiling names ids; every filter pair
  of the ceiling present in the child's filter (the child may narrow
  further, never widen); and every other restriction the ceiling
  carries — fields, hashed, args — carried by the child too."
  [child ceiling]
  (let [cf (filter-pairs (:filter ceiling))
        chf (filter-pairs (:filter child))]
    (and (= (str (:kind child)) (str (:kind ceiling)))
         (every? (strs (:actions ceiling)) (map str (:actions child)))
         (or (nil? (:ids ceiling))
             (and (seq (:ids child))
                  (every? (strs (:ids ceiling)) (map str (:ids child)))))
         (every? (fn [[f v]] (= v (get chf f))) cf)
         (or (nil? (:fields ceiling))
             (= (norm-fields (:fields ceiling)) (norm-fields (:fields child))))
         (every? (strs (:hashed child)) (map str (:hashed ceiling)))
         (or (empty? (:args ceiling))
             (= (norm-args (:args ceiling)) (norm-args (:args child)))))))

(defn entry-text
  "One scope entry as a person reads it back, and as an
  approval_request would spell it."
  [e]
  (str "{kind " (:kind e)
       ", actions [" (str/join ", " (map str (:actions e))) "]"
       (when (seq (:ids e)) (str ", ids [" (str/join ", " (map str (:ids e))) "]"))
       (when (seq (:filter e))
         (str ", filter " (str/join ", " (map (fn [[f v]] (str f "=" v))
                                              (sort (filter-pairs (:filter e)))))))
       (when-some [f (:fields e)]
         (str ", fields " (:mode f) " [" (str/join ", " (map str (:names f))) "]"))
       (when (seq (:hashed e))
         (str ", hashed [" (str/join ", " (map str (:hashed e))) "]"))
       "}"))

(defn- nearest
  "The ceiling entry for the same kind the child came closest to, or
  nil — what the sentence names beside the entry that would admit."
  [child ceiling]
  (first (filter #(= (str (:kind child)) (str (:kind %))) ceiling)))

(defn- scope-misfit [ceiling scope]
  (when-some [bad (first (remove (fn [e] (some #(entry-fits? e %) ceiling))
                                 scope))]
    (let [near (nearest bad ceiling)]
      (str "the scope entry " (entry-text bad) " is past the ceiling"
           (if near
             (str " (the nearest ceiling entry is " (entry-text near) ")")
             (str " (the ceiling names no entry for " (:kind bad) ")"))
           ". A ceiling entry " (entry-text bad) " would have admitted it"))))

(defn- over? [asked cap]
  (and (some? asked) (some? cap) (pos? (compare (bigdec asked) (bigdec cap)))))

(defn- models-misfit [field asked cap]
  (let [cap (strs cap)
        asked (map str asked)]
    (when (seq cap)
      (cond
        (empty? asked)
        (str field " is empty, which means any model may sit, and the"
             " ceiling holds it to [" (str/join ", " (sort cap)) "]. A"
             " ceiling held_for of any model would have admitted it")
        (not (every? cap asked))
        (let [extra (sort (remove cap asked))]
          (str field " names " (str/join ", " extra) ", which the ceiling"
               " does not hold. A ceiling held_for that adds "
               (str/join ", " extra) " would have admitted it"))))))

(defn misfit
  "Why this seat body is past the author's ceiling, as one sentence,
  or nil when it fits. Invariant 2, in the order a person reads a
  seat: the ceiling itself, the scope, the money, the tokens, the
  models."
  [author inp]
  (let [ceil (get-in author [:data :delegates])
        budget (:budget_usd_per_week inp)
        tokens (:sitting_budget_tokens inp)]
    (or (when (some? (:delegates inp))
          (str "the body writes a ceiling of its own, and a ceiling is the"
               " person's to write. No ceiling entry admits it"))
        (scope-misfit (:scope ceil) (:scope inp))
        (when (over? budget (:budget_usd_per_week ceil))
          (str "budget_usd_per_week " budget " is over the ceiling's "
               (:budget_usd_per_week ceil) ". A ceiling budget_usd_per_week of "
               budget " would have admitted it"))
        (when (true? (:ignore_sitting_budget inp))
          (str "ignore_sitting_budget lifts the token cap, and the ceiling"
               " caps sitting_budget_tokens at " (:sitting_budget_tokens ceil)
               ". No ceiling entry admits it"))
        (when (over? tokens (:sitting_budget_tokens ceil))
          (str "sitting_budget_tokens " tokens " is over the ceiling's "
               (:sitting_budget_tokens ceil) ". A ceiling sitting_budget_tokens of "
               tokens " would have admitted it"))
        (models-misfit "held_for" (:held_for inp) (:held_for ceil))
        (models-misfit "substitute_for" (:substitute_for inp) (:held_for ceil)))))

;; ── the sentences ───────────────────────────────────────────────────

(def inv-self
  "Invariant 1: a seat never restates itself or a seat whose grant it holds")

(def inv-ceiling
  "Invariant 2: a seat never authors past the ceiling its person delegated")

(def inv-authored
  "Invariant 4: an author tunes only the seats it authored, once its person approved them")

(def inv-lever
  "Invariant 4: unpark, merge and retire are its person's levers")

(def inv-judgment
  "Invariant 5: a judgment is promoted only under a parked seat its promoter authored")

(defn- seat-name [row] (or (nonblank (get-in row [:data :name])) (str (:id row))))

(defn- hold [invariant detail]
  (t/deny {:vars {:invariant invariant :detail detail}}))

;; ── the guards ──────────────────────────────────────────────────────

(g/defguard authors-within-the-ceiling
  {:judges [:scope]
   :reads [:principal :now :grant :seat :held_call :within]
   :vars [:invariant :detail]
   :open "The ceiling is the delegates field of the author's own seat row, one GET away; it names kinds this form cannot enumerate."
   :explain "Held for the person's tap. {invariant}: {detail}. The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row inp ctx]
  ;; seat create and restate. A person, the engine, and a sitter of a
  ;; seat that delegates nothing all pass here: the first two were
  ;; never the question, and the third is `not-a-sitter`'s.
  (let [cited (cited-seats ctx)
        author (when (seq cited) (author-seat ctx cited))]
    (cond
      (nil? author) (t/allow)
      (approved-hold? ctx :seat (:id row)) (t/allow)

      ;; the create door
      (nil? (:id row))
      (if-some [m (misfit author inp)]
        (hold inv-ceiling m)
        (t/allow))

      (contains? cited (str (:id row)))
      (hold inv-self (str (seat-name row) " is a seat whose grant "
                          (seat-name author) " holds, so its scope, charter,"
                          " budgets and ceiling are the person's"))

      (or (not= (str (:id author)) (nonblank (get-in row [:data :authored_by])))
          (nil? (nonblank (get-in row [:data :approved_by]))))
      (hold inv-authored (str (seat-name row) " was not authored by "
                              (seat-name author)
                              ", or its person has not approved it yet"))

      :else
      (if-some [m (misfit author inp)]
        (hold inv-ceiling m)
        (t/allow)))))

(g/defguard parks-only-its-own
  {:reads [:principal :now :grant :seat :held_call :within]
   :vars [:invariant :detail]
   :explain "Held for the person's tap. {invariant}: {detail}. The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  ;; a park narrows and costs nothing, so an author parks the seats it
  ;; authored on its own. Every other park is the person's.
  (let [cited (cited-seats ctx)
        author (when (seq cited) (author-seat ctx cited))]
    (cond
      (nil? author) (t/allow)
      (approved-hold? ctx :seat (:id row)) (t/allow)
      (contains? cited (str (:id row)))
      (hold inv-self (str (seat-name row) " is a seat whose grant "
                          (seat-name author) " holds"))
      (= (str (:id author)) (nonblank (get-in row [:data :authored_by])))
      (t/allow)
      :else
      (hold inv-authored (str (seat-name row) " was not authored by "
                              (seat-name author))))))

(g/defguard the-persons-lever
  {:reads [:principal :now :grant :seat :held_call :within]
   :vars [:invariant :detail]
   :explain "Held for the person's tap. {invariant}: {detail}. The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  ;; unpark, merge and retire: from an author, always held. An
  ;; authored seat's first unpark IS its approval, so an author that
  ;; could unpark its own child would be approving itself.
  (let [cited (cited-seats ctx)
        author (when (seq cited) (author-seat ctx cited))]
    (cond
      (nil? author) (t/allow)
      (approved-hold? ctx :seat (:id row)) (t/allow)
      :else (hold inv-lever (str (seat-name row) " waits on the person, not on "
                                 (seat-name author))))))

(g/defguard promotes-under-a-parked-child
  {:reads [:principal :now :grant :seat :held_call :within]
   :vars [:invariant :detail]
   :explain "Held for the person's tap. {invariant}: {detail}. The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  (let [cited (cited-seats ctx)
        author (when (seq cited) (author-seat ctx cited))]
    (cond
      (nil? author) (t/allow)
      (approved-hold? ctx :judgment (:id row)) (t/allow)
      (seq ((:find ctx) :seat {:authored_by (str (:id author))
                               :judgment (str (:id row))
                               :state "parked"}
            {:limit 1}))
      (t/allow)
      :else
      (hold inv-judgment
            (str "no parked seat authored by " (seat-name author)
                 " cites " (or (nonblank (get-in row [:data :name]))
                               (str (:id row)))
                 ", so promoting it could change work the person has not seen")))))

(g/defguard the-persons-judgment
  {:reads [:principal :now :grant :seat :held_call :within]
   :vars [:invariant :detail]
   :explain "Held for the person's tap. {invariant}: {detail}. The call is recorded as a held_call, and the person's Allow runs it exactly as written."}
  [row _inp ctx]
  ;; supersede takes a judgment out of force under every seat that
  ;; says it, so from an author it is always the person's
  (let [cited (cited-seats ctx)
        author (when (seq cited) (author-seat ctx cited))]
    (cond
      (nil? author) (t/allow)
      (approved-hold? ctx :judgment (:id row)) (t/allow)
      :else (hold inv-judgment
                  (str "superseding a judgment changes every seat that says it,"
                       " and that is the person's call, not " (seat-name author) "'s")))))

(def hold-guards
  "The guards whose refusal is a HOLD. The router mints a held call in
  place of exactly these refusals, and passes every other one through
  as the 409 it always was."
  #{(:name authors-within-the-ceiling)
    (:name parks-only-its-own)
    (:name the-persons-lever)
    (:name promotes-under-a-parked-child)
    (:name the-persons-judgment)})

(defn hold-guard?
  "Is this refusal's guard one of `hold-guards`? The problem document
  carries the guard's name as a keyword or its string, depending on
  which side of the wire read it."
  [guard]
  (contains? hold-guards (some-> guard name keyword)))
