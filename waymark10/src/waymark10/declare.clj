(ns waymark10.declare
  "Declaration ergonomics: def the pieces, keep one law.

  defaction and defderived def PLAIN maps — the very value the inline
  spelling would put in the resource map — with construction
  validation run eagerly, so a malformed def fails at its own line
  instead of at some distant defresource. What must wait, waits: the
  cross-referencing checks (states exist, judged fields are input
  fields, the derived fact is a schema field, cycles) still run at
  defresource, because only the assembled declaration can answer
  them. The def site validates exactly what it can see.

  Because the def'd value IS the inline value, a def'd piece is usable
  everywhere the inline spelling is: directly in :actions / :derived,
  or colocated on a schema entry ([:end_date {:derived end-date} …]) —
  and the fingerprint cannot tell the spellings apart (two spellings,
  one law; batch_g_invariance_test pins it).

  Blessed idioms (docs/waymark10-g-notes.md):
  - action groups: a var holding a map of actions, merged into
    :actions — (merge review-actions {:archive …}).
  - named safety values: (def routine {:idempotent true …}) cited as
    :safety routine. Reference is explicit declaration, not inference:
    the safety map is still spelled once, in full, and every citation
    names it — no property is ever computed from behavior, so
    safety-never-inferred holds.
  - local builder fns returning plain maps, when a family of actions
    differs by a parameter."
  (:require [waymark10.expr :as expr]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── actions ─────────────────────────────────────────────────────────

(defn action
  "Construction-validate an action map eagerly — the same
  normalize-action the defresource gate runs — and return the PLAIN
  map, identical to the inline spelling. The normalized value is
  discarded: normalization is the seam, and it happens once, at
  defresource. aname labels the refusal."
  [aname a]
  (r/normalize-action :defaction aname a)
  a)

(defmacro defaction
  "Def an action as a plain map, construction-validated at the def
  site:

     (defaction assign-meal
       {:from #{:draft} :to :draft
        :safety {:idempotent true :reversible false :confirm false}
        …})

  An inline (fn …) :handler gets its canonical printed form captured
  as :waymark10/form metadata — the same identity defhandler mints —
  so the fingerprint hashes the law, never the object."
  [name amap]
  (let [amap (if (and (map? amap)
                      (seq? (:handler amap))
                      (= 'fn (first (:handler amap))))
               (assoc amap :handler
                      `(with-meta ~(:handler amap)
                         {:waymark10/form '~(:handler amap)}))
               amap)]
    `(def ~name (action ~(keyword name) ~amap))))

;; ── derivations ─────────────────────────────────────────────────────

(defn derived
  "Normalize and scope-validate one derived spec at construction:
  exactly one of :expr/:count, the expression's structural problems,
  and the :over scope — :over is right there, so an out-of-scope
  (var …) fails at the def line. Whether the fact is a schema field,
  whether the edge exists, whether facts cycle — defresource's and
  assembly's questions. Returns the normalized spec: the same value
  normalize-resource lands the inline spelling on."
  [dname spec]
  (let [err (fn [msg]
              (throw (t/definition-error
                      (str "defderived " (name dname) ": " msg))))]
    (when (= (contains? spec :expr) (contains? spec :count))
      (err (str "declare exactly one of :expr (an expression fact) or "
                ":count (an aggregate over a declared edge)")))
    (when (and (contains? spec :count) (seq (:over spec)))
      (err "a count's inputs are its declared edge; :over does not apply"))
    (let [spec (r/normalize-derived-spec spec)]
      (when-some [e (:expr spec)]
        (when-some [p (first (concat (expr/problems e)
                                     (expr/derived-scope-problems
                                      e (set (:over spec)))))]
          (err p)))
      spec)))

(defmacro defderived
  "Def a derived spec, normalized and scope-validated at the def site:

     (defderived end-date
       {:over [:start_date :weeks]
        :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))})

  The def'd value drops into :derived {:end_date end-date} or onto its
  own schema entry ([:end_date {:derived end-date} …]) — one law,
  wherever it is spelled."
  [name spec]
  `(def ~name (derived ~(keyword name) ~spec)))
