(ns waymark10.server.seams
  "The four doors CORE knocks on when the answer belongs to a module.

  waymark-db9.3 moved every module ROUTE out of the router and left
  four module namespaces required there for calls that were not
  routes at all: presence's read mark and its two stream hooks, the
  intents announcement at the dry-run doors, the mirror's
  pull-through on a stale GET, and the job the bulk door mints when
  it defers. Each was a core handler naming a module by namespace,
  which is what kept `waymark10.server.presence` on the classpath of
  an engine assembled without the realtime module. This namespace is
  where those four calls go instead.

  The rule the seam obeys is the spec's own (docs/spec-modularization
  § 'The forms-only rule'): a module may not hand the boot an opaque
  function keyed by a keyword, because an opaque fn is not a value a
  reviewer can diff. So none of the doors below is a registry entry.
  Each is a PROTOCOL core names, implemented by a value the assembly
  already put in core's hand — and finding the implementation is
  ordinary type dispatch, not a lookup in a table somebody filled at
  load.

  ── which value, and why it differs ──

  Two of the doors hang off a RUNNING surface. `Gaze` and
  `Considering` are implemented by the presence and intents
  registries themselves, which core reaches with
  `runtime/surface` — the handle the module's own lifecycle hook
  published. A nil answer is the whole degradation story: an engine
  without the realtime module, or one that never started, marks no
  gaze and deals no considering card, and the read or the rehearsal
  it hangs off runs exactly as it always did. That was already the
  posture (`mark-read!` and the intents announcement were
  best-effort by construction); the seam only makes it the CONTRACT
  instead of a comment.

  Two of them are not runtime at all, and the difference is worth
  the paragraph. `ReadThrough` and `Deferring` are called on engines
  that never start — the mirror suites read mirrored rows, and every
  deferred-bulk test 202s, through a bare `engine/handler` nobody
  called start! on. A running-surface lookup would quietly turn both
  off there. They are properties of a DECLARATION, not of a process:
  a kind that declares `:mirror` declares a pull-through, and the
  `:job` kind is the kind a deferred bulk call becomes. So each
  rides the declaration the module itself minted —
  `mirror/declaration`'s own `:mirror` spec value, and the `:job`
  rdef's metadata, which is where this framework already parks what
  is true about a declaration but is not law (`defhandler`'s
  `:waymark10/form`, `r/resource`'s `:waymark10/warnings`).

  Metadata over a key, for the job kind, because
  `declaration/top-level-keys` is CLOSED on purpose — growing it so
  a module could park a function on an rdef would be the plugin
  registry arriving through the authored surface, which is the one
  door this spec will not open.

  This namespace requires NOTHING. It is four protocol declarations
  and two metadata accessors, so every module in the artifact can
  implement it without a thought about load order.")

(set! *warn-on-reflection* true)

;; ── the running surfaces ────────────────────────────────────────────

(defprotocol Gaze
  "The implicit presence door, as core knocks on it. Implemented by
  the presence registry (waymark10.server.presence), reached with
  `(runtime/surface eng :presence)`; nil means nobody is watching and
  the read simply happens.

  Every method is BEST-EFFORT by contract, not by the caller's good
  manners: a read's fate never rides on being seen."
  (mark-read! [surface principal self]
    "A grant-scoped principal's successful GET marks its gaze on what
    it read (source \"read\").")
  (watch-opened! [surface principal self]
    "A per-resource SSE subscription IS presence: the stream registers
    on subscribe (source \"stream\").")
  (watch-closed! [surface principal self]
    "…and drops on disconnect."))

(defprotocol Considering
  "The dry-run doors' announcement. Implemented by the intents
  registry (waymark10.server.intents), reached with
  `(runtime/surface eng :intents)`; nil means no card is dealt and the
  rehearsal answers untouched."
  (announce! [surface principal intent]
    "One ephemeral intent frame — {:self :action :status :warnings
    …} — from a named principal."))

;; ── the declared doors ──────────────────────────────────────────────

(defprotocol ReadThrough
  "A kind whose declaration carries a read-through: the value under
  `:mirror` on the rdef, minted by `mirror/declaration`. Core's GET
  asks the SPEC to serve the row rather than asking the mirror
  namespace to refresh it."
  (pull-through [spec eng rdef row]
    "The (possibly refreshed) row this GET should serve."))

(defprotocol Deferring
  "The door a bulk call walks through when it exceeds its declared
  `:defer-over` threshold. Implemented by the jobs module and stamped
  on the `:job` rdef with `with-deferral`, so core reaches it through
  the kind the module ENROLLED — the same enrollment that makes the
  202's envelope renderable."
  (defer! [door eng deferred principal]
    "Mint the row for one deferred call ({:kind :action :ids :input},
    the marker `invoke/bulk!` hands back) → the create! result."))

(def ^:private deferral-key
  "The metadata key the job kind's mint rides on. Namespaced, and
  read only through the two fns below, so the spelling has one home."
  ::deferral)

(defn with-deferral
  "Stamp a declaration with the door core's bulk defer knocks on. The
  module calls this on its own rdef, next to the resource it belongs
  to; nothing assembles it later and nothing discovers it."
  [rdef door]
  (vary-meta rdef assoc deferral-key door))

(defn deferral
  "The `Deferring` door this kind carries, nil when the kind is absent
  (an engine assembled without the jobs module has no `:job` rdef at
  all) or carries none. Every caller has to have an answer for nil,
  which is the honest shape: what an engine can defer to is a fact
  about what it enrolled."
  [rdef]
  (get (meta rdef) deferral-key))
