(ns waymark10.declaration
  "The authored-surface gate: the resource map's KEY SETS, closed
  before normalize-resource runs.

  Leaves stay :any — malli forms, handler fns, guard maps, upcast fns
  are other gates' law (the schema registry, normalize-action,
  parse-flow-row, desugar-fields). The one law added here is closure:
  an unknown top-level or action key refuses at the def site with a
  path, where today a typo like :filtrable or :guard silently
  declares nothing. The usability doctrine turned inward — everything
  the framework knows about its own vocabulary, the author sees
  without decoding."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def action-keys
  "normalize-action's whole authored surface, plus the :undo pointer
  verify-undo-pointers reads and strips."
  [:from :to :input :guards :safety :display :handler :emits :edit
   :place :bulk :batch :waives :touches :unless :record :undo
   ;; :engine marks a door the ENGINE owns rather than the household —
   ;; the sync machine's bookkeeping, woven in by mirror/declaration.
   ;; No application writes it. The ending wall (waymark-fp62.4.1)
   ;; reads it: a row whose work is over shuts the household's doors,
   ;; and a mirror must go on recording what its authority says about
   ;; a task the house finished last month. Outside the fingerprint's
   ;; action facet, like the weave that stamps it
   :engine
   ;; what the out-of-state refusal ADDS about this row: a fn of
   ;; (row ctx) → one sentence or nil, read at invoke's step 5 with the
   ;; guard ctx's reads. Prose, never law — the machine has already
   ;; refused, and this only says what to do instead (the verdict's
   ;; `reopen` names the verdict that stands). Outside the
   ;; fingerprint's action facet, like :display
   :out-of-state-says
   ;; :replay false takes an in-state door out of invoke's natural
   ;; replay: its handler runs on every call, and the door stays
   ;; idempotent, so a caller needs no Idempotency-Key
   :replay
   ;; what the transition log says of THIS move, where the row's own
   ;; summary would say nothing (ticket e99ac1f5): a fn of (row inp)
   ;; → one line or nil, read as the transition is appended, with the
   ;; row as the write leaves it. Prose, never law; outside the
   ;; fingerprint's action facet, like :out-of-state-says
   :log-says
   ;; the person's answer to the org audit's question about this door
   ;; (ticket e1ed02c7): one sentence, rendered by factory10.law-text
   ;; under 'What you decided'. Prose, never law; outside the
   ;; fingerprint's action facet, like :log-says
   :decided
   ;; {:none "why"}: the door's :decided sentence names a person's tap
   ;; as narrative, and the AI may act (ticket 7315ae72). Read by the
   ;; org audit and the law page; prose, never law, like :decided
   :decided-held])

(def top-level-keys
  "Everything normalize-resource, the check batteries, assembly, and
  the engine's own kinds read from an AUTHORED map. A new declaration
  key that skips this list refuses every boot — loudly, at the def
  site, which is the point."
  [;; identity & machine
   :kind :states :initial :terminal :plural :nav :domain :adoption
   ;; :stored-only: the live cracks pass places a row by its stored
   ;; `domain` (or :absent-as) alone, never by the kind's :domain
   :row-domain
   ;; how a row's work ENDS, in the household's own words (spec-feed.md,
   ;; waymark-iqa.24/.25): which states — or, for a mirror kind whose
   ;; lifecycle is data rather than machine, which values of which
   ;; field — mean the work was ACCOMPLISHED, and which mean it was LET
   ;; GO. Since waymark-fp62.4.1 the DOORS read it too: a row whose
   ;; work is over takes no more doors except the ways back (an
   ;; optional :ways-back names them where the machine cannot show
   ;; them). Still outside the fingerprint — the :nav precedent, and
   ;; the reason a household can teach the difference without minting
   ;; a revision
   :over
   ;; data
   :schema :create-schema :fields :shape :upcasts
   ;; behavior
   :flow :actions :create-guards :create-action-names :creates :allow-dead :renames
   ;; the create door's own safety (ticket 13e3b323): {:confirm true
   ;; :consequence <fn of input, or a {field} template>} — the create
   ;; refuses until its caller echoes the sentence its input fills
   ;; (waymark10.confirm/create-consequence)
   :create-safety
   ;; the doors licensed to depart a TERMINAL state (docs/spec-undo.md).
   ;; check-terminal-no-exit's flat rule — no action leaves a tomb — is
   ;; right about every door that CONTINUES a story and wrong about the
   ;; one that says the last sentence was never spoken. Named per
   ;; action, so the waiver is a list a reviewer can read rather than a
   ;; flag on the kind; :allow-dead's precedent, and like it a
   ;; declaration-time waiver rather than fingerprinted law — the DOOR
   ;; it licenses is in :actions and hashes there
   :allow-undo
   ;; the standalone verdict (spec-decision-kind): one key desugaring
   ;; into states, verdict actions, decider walls, the leash and the
   ;; pacing — a spelling, never a mechanism, resolved before the
   ;; check battery and before the fingerprint
   :decision
   ;; the workflow as a resource (spec-process): one key desugaring
   ;; into the step machine, one door per step (or one `run` door),
   ;; the roll-back door and its derived :touches — a spelling, never
   ;; a mechanism, resolved ahead of :decision and :flow
   :process
   ;; who sees their OWN rows of this kind with no grant at all
   ;; (spec-decision-kind seam 2): the negotiation courtesy that used
   ;; to be a literal set of kind names in grants.clj, relocated to
   ;; the seven declarations that always owned it
   :own-surface
   ;; one sentence: why a grant-wearer should NOT reach their own rows
   ;; of this kind, though the engine stamps a principal on them. It
   ;; silences check-owned-surface's warning and does nothing else;
   ;; never in the fingerprint — :own-surface's precedent
   :no-own-surface
   ;; the states of this kind in which WORDS ARE NOT AN ANSWER
   ;; (waymark-vf8): {state {:door … :whose … :explain "… {door} …"}}.
   ;; A row sitting in such a state is waiting on an ACT by whoever
   ;; holds that door, and `remark`'s create wall reads this map to
   ;; refuse that one hand a promise in place of the act — naming the
   ;; door, at this row's own address. Every other hand still speaks
   ;; freely, which is why this is a clause on the SUBJECT's
   ;; declaration rather than application vocabulary inside the
   ;; thread kind: the framework owns the predicate, the kind owns
   ;; the sentence
   :answered-at-a-door
   ;; what a row of this kind WAITS ON, and what each ending of the
   ;; awaited row does to the one waiting (ticket d9edac4e): [{:on
   ;; <field or relation> :awaits <kind> :in #{waiting states}
   ;; :restated-by [doors] :waiter "who, when it is no row of this
   ;; kind" :outcomes {<awaited outcome> :release | :return |
   ;; :nothing}}]. The cracks audit (waymark10.server.cracks) reads it
   ;; and reports `dead_wait` where an outcome does nothing and no seat
   ;; is woken to act. It moves no row: the handlers that release a
   ;; waiter are the kind's own, and this says what they do. Never in
   ;; the fingerprint — :answered-at-a-door's precedent
   :waits
   ;; the ways OUT of a state that are no door of this kind a seat
   ;; takes (ticket 071c563c): [{:in #{states} :by <:mirror | :sweep |
   ;; :wait | :person | :engine, or {:kind <another kind> :action <its door>}> :via <this
   ;; kind's action the mover walks> :to <the state it lands in>}]. The
   ;; cracks audit reads it beside :waits: a seat that holds the other
   ;; kind's door leaves the state as if it held one here, and a source
   ;; leaves it with no seat at all. It moves no row. Never in the
   ;; fingerprint — :waits' precedent
   :left-by
   ;; the ways INTO a state that are no door of this kind (ticket
   ;; 4b0460cc): [{:in #{states} :by {:kind <another kind> :action <its
   ;; door>}}]. The cracks audit reads it beside :left-by: a seat woken
   ;; on the other kind's door is woken for the state as if it woke on a
   ;; door here. It moves no row. Never in the fingerprint — :waits'
   ;; precedent
   :reached-by
   ;; how long a row may REST in a state with no write (ticket
   ;; 21b49b28): {<state> <seconds>}. The live cracks pass
   ;; (waymark10.server.live-cracks) reads it and files an `aged` note
   ;; for a row past it; a state it does not name takes the default for
   ;; its name, or no age. It moves no row. Never in the fingerprint —
   ;; :waits' precedent
   :expected-age
   ;; seconds a row may rest in a non-terminal state that :expected-age
   ;; and the defaults for its name do not cover; an org's in-domain
   ;; stamps it from its template, nil opts out. Never in the fingerprint
   :default-expected-age
   ;; the person's answers to the org audit's questions about this
   ;; kind's states (ticket e1ed02c7): {<state> "one sentence"} — how
   ;; long a row may wait there, and who is told. factory10.law-text
   ;; renders them; it moves no row. Never in the fingerprint — :waits'
   ;; precedent
   :decided
   ;; derivations & constraints
   :derived :one-of :unique :part-scopes
   ;; read-only fields the server computes at read time: {field {:schema
   ;; <malli> :x-display {…} :fn (fn [row ctx] value) :reads? bool}} —
   ;; :reads? true renders nil where no :read is lent; never stored,
   ;; never an input, never in the fingerprint (its whitelist does not
   ;; name them), rendered by render/envelope before the projection
   :computed
   ;; collection surface
   :filterable :sortable :faceted :worksheet :default-filters :views
   ;; {field value}: the value a row with NO stored value filters (and
   ;; facets) as — field=<value> matches absent rows too, any other
   ;; value skips them; nothing is stored, nothing is backfilled
   :absent-as
   ;; {field :btree|:none}: whether a promoted filterable field carries
   ;; a btree index — by default one filterable by :eq or :in does
   ;; (ticket 68ebaa0f). In the fingerprint through the storage facet
   :index
   ;; the kind's SAFE collection doors (ticket 80c6c9e2): {name {:input
   ;; <malli> :summary "…" :handler (fn [engine body ctx])}}. Each is
   ;; POST /api/{plural}/-/{name}, a route that reads and writes
   ;; nothing, so it is no action: it moves no row, and whoever may read
   ;; the kind may call it. The key is the ADVERTISEMENT — the
   ;; collection document, the connector and the OpenAPI document read
   ;; it — and the MOUNT: the router checks the kind, holds the body to
   ;; :input and answers what :handler returns. Never in the
   ;; fingerprint — :computed's precedent
   :collection-doors
   ;; edges
   :owns :links :related
   ;; advertisement
   :display :label-template :summary :deviations
   ;; the summary line a template cannot say (ticket ea061a48): `(fn
   ;; [row ctx])` → the line, or nil for the :summary template. The
   ;; envelope asks it with the render's clock (:now) and :read; never
   ;; stored, never in the fingerprint — :computed's precedent
   :summary-line
   ;; the summary line a template cannot say (waymark10.summary/line):
   ;; a pure (fn [row] line) beside :summary, which stays the fallback
   ;; and what a redacted read answers. Never in the fingerprint
   :summary-fn
   ;; hooks & engine weaves
   :on-create :mirror
   ;; (fn [data]) → data: the at-rest backfill's move for a row from
   ;; before a field was sealed (ticket dbeb2f25), applied by the
   ;; Postgres store at boot before it seals. Never in the fingerprint
   ;; — :on-create's precedent
   :at-rest-migrate
   ;; (fn [data]) → data: the data backfill's move for a row from
   ;; before a field was defaulted (ticket 0fa0eb6c), applied by every
   ;; store at boot (ensure-kind!), key or none, to each row it
   ;; changes — no version bumped, no transition logged, so it must be
   ;; idempotent. Never in the fingerprint — :on-create's precedent
   :data-migrate
   ;; what the create door's FULL rehearsal answers beyond its verdict:
   ;; `(fn [engine inp {:keys [principal grant]}])` → a map that rides
   ;; the dry-run's body as `preview`. Asked after the create guards
   ;; passed and outside their transaction; it writes nothing. Never in
   ;; the fingerprint — :on-create's precedent
   :on-rehearse
   ;; what the log carries forward past the write (spec-decision-record,
   ;; spec-time-travel): {:judgment true} retains the evidence the
   ;; guards read, {:data true} the document as it stood. ONE map, two
   ;; entries, default off — write amplification on the one write path
   ;; is bytes on every transition of every kind forever
   :retain
   ;; proof — declared policy tests (waymark10.scenario), judged where
   ;; they can be judged for free and by the suite where they cannot.
   ;; Data, never law: fingerprint-of's whitelist does not name them,
   ;; so editing a scenario mints no revision
   :scenarios])

(def ^:private action-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        action-keys))

(def filter-ops
  "The closed set of ops a :filterable entry (or colocated :filter)
  may declare — the collection grammar (waymark10.server.collections)
  serves exactly these: field= (:eq), field=a,b (:in), field_ne=
  (:ne), field_gte=/field_lte= (:range), field_after= (:after),
  field_before= (:before), field_set= (:set), field_contains=
  (:contains). checks.clj refuses an op outside this set at boot."
  #{:eq :in :ne :range :after :before :set :contains})

(def link-keys
  "One :links entry's whole authored surface — the same closure
  action-keys already gives :actions, extended here so a typo'd link
  key (:rel misspelled, an :emebd) refuses at the def site instead of
  rendering nothing. :download marks a byte route (a redirect or a
  file body): clients navigate it in the BROWSER, never through an
  in-app XHR router that would swallow the redirect. :external marks
  an :href that leaves this engine entirely (another engine's row, a
  foreign system): the same real-browser navigation as :download,
  under an honest name — a client must never read an origin hop as
  bytes to save. :states is a set of this kind's states: the link is
  advertised only on a row in one of them (a walk's export, which
  answers only once the walk is sealed)."
  [:rel :owns :edge :href :kind :summary :badge :embed :where :download
   :external :states])

(def ^:private link-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        link-keys))

(def view-keys
  "One :views entry's whole authored surface — an alternate collection
  view (:deck swipe-triage, :feed sequential). The same closure the
  other surfaces get: a typo'd view key refuses at the def site instead
  of advertising nothing. The semantic rules (deck gestures name
  reversible actions, the queue drains itself) live in checks.clj."
  [:name :kind :where :right :left :card :display])

(def ^:private view-schema
  (into [:map {:closed true}]
        (map (fn [k] [k {:optional true} :any]))
        view-keys))

(def ^:private declaration-schema
  (m/schema
   (-> [:map {:closed true}]
       (into (map (fn [k] [k {:optional true} :any]))
             (remove #{:actions :links :views} top-level-keys))
       (conj [:actions {:optional true} [:map-of :keyword action-schema]])
       (conj [:links {:optional true} [:vector link-schema]])
       (conj [:views {:optional true} [:vector view-schema]]))))

(def ^:private lone-action-schema (m/schema action-schema))

(defn- render-path [in]
  (if (empty? in)
    "top level"
    (str/join "." (map #(if (keyword? %) (name %) (str %)) in))))

(defn errors
  "Every declaration-surface violation as {:path [...] :message ...},
  nil when the key sets are clean."
  [rmap]
  (when-some [ex (m/explain declaration-schema rmap)]
    (seq (for [{:keys [in] :as e} (:errors ex)]
           {:path (vec in) :message (me/error-message e)}))))

(defn- refuse!
  [kind-label {:keys [path message]} legal what]
  (throw (t/definition-error
          (str kind-label " [declaration] " (render-path path)
               " — " message "; " what " keys are "
               (vec (sort legal)))
          {:check :declaration :path path})))

(defn check!
  "Throw the first declaration-surface violation as a definition
  error carrying {:check :declaration :path [...]}."
  [rmap]
  (when-some [[e & _] (errors rmap)]
    (let [kind-label (or (some-> (:kind rmap) name) "(unnamed kind)")]
      (case (first (:path e))
        :actions (refuse! kind-label e action-keys "action")
        :views (refuse! kind-label e view-keys "view")
        (refuse! kind-label e top-level-keys "declaration"))))
  rmap)

(defn check-action!
  "The same gate over one action map alone — what defaction's eager
  def-site validation runs, so a stray key fails at its own line with
  the path spelled [:actions aname key]."
  [kind aname a]
  (when (map? a)
    (when-some [ex (m/explain lone-action-schema a)]
      (when-some [{:keys [in] :as e} (first (:errors ex))]
        (refuse! (str (some-> kind name) " action " (some-> aname name))
                 {:path (into [:actions aname] in)
                  :message (me/error-message e)}
                 action-keys "action"))))
  a)
