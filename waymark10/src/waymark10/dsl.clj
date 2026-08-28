(ns waymark10.dsl
  "The one require an application declaration needs.

  Every name here is an alias: the fn vars hold the very same
  function objects as their homes (waymark10.declare, waymark10.guards,
  waymark10.resource), and the def- macros expand to the originals —
  so a dsl-spelled declaration and a split-spelled one are one value
  and one fingerprint. Two renames keep clojure.core unshadowed, so an
  app file never writes :refer-clojure exclusions:

    ref-to     — waymark10.declare/ref (the cross-resource reference)
    defguardfn — waymark10.guards/defguard (the residual code guard;
                 defguard here is declare's sentence-first macro)

  and the guard builders wear unshadowed names: all-of (guards/and),
  any-of (guards/or), require-fact (guards/require), expr-guard
  (guards/expr)."
  (:require [waymark10.declare :as d]
            [waymark10.guards :as g]
            [waymark10.resource :as r]))

(set! *warn-on-reflection* true)

(defmacro ^:private defalias
  "Alias a FUNCTION var: the same object, :doc and :arglists copied."
  [new old]
  `(do (def ~new ~old)
       (alter-meta! (var ~new) merge
                    (select-keys (meta (var ~old)) [:doc :arglists]))
       (var ~new)))

;; ── typed field words ───────────────────────────────────────────────

(defalias one-of d/one-of)
(defalias date d/date)
(defalias flag d/flag)
(defalias quantity d/quantity)
(defalias money d/money)
(defalias percent d/percent)
(defalias prose d/prose)
(defalias measured-by d/measured-by)
(defalias described d/described)
(defalias ref-to d/ref)

;; ── guard sentences and builders ────────────────────────────────────

;; the functional defresource — for declarations built by a wrapper
;; (the Mirror weave) rather than spelled as one literal map
(defalias resource r/resource)

;; a domain module's assembly fn stamps its kind list — global nav
;; between applications under one deployable rides the wire from here
(defalias in-domain r/in-domain)

(defalias refuse d/refuse)
(defalias warn d/warn)
(defalias guard g/guard)
(defalias expr-guard g/expr)
(defalias relation g/relation)
(defalias require-fact g/require)
(defalias all-of g/and)
(defalias any-of g/or)
(defalias role g/role)
(defalias owner g/owner)
(defalias four-eyes g/four-eyes)
(defalias unless g/unless)
(defalias unless-granted g/unless-granted)

;; ── the def forms: thin wrappers onto the originals ─────────────────
;; Syntax-quote qualifies the expansion, so the caller needs no other
;; require; the originals receive the literal forms unchanged, so
;; inline-fn form capture (defaction, defhandler, defguardfn) still
;; mints the canonical printed identity.

(defmacro defresource [name rmap]
  `(r/defresource ~name ~rmap))

(defmacro defhandler [name params & body]
  `(r/defhandler ~name ~params ~@body))

(defmacro defaction [name amap]
  `(d/defaction ~name ~amap))

(defmacro defderived [name spec]
  `(d/defderived ~name ~spec))

(defmacro defguard [name clause & [law]]
  `(d/defguard ~name ~clause ~law))

;; the declared policy test beside the law it judges — the same
;; sentence-first shape, one turn outward
(defmacro defscenario [name sentence smap]
  `(d/defscenario ~name ~sentence ~smap))

(defmacro defguardfn [name opts params & body]
  `(g/defguard ~name ~opts ~params ~@body))

;; the wrappers answer doc lookups with the originals' own words
(doseq [[w o] {#'defresource #'r/defresource
               #'defhandler  #'r/defhandler
               #'defaction   #'d/defaction
               #'defderived  #'d/defderived
               #'defguard    #'d/defguard
               #'defscenario #'d/defscenario
               #'defguardfn  #'g/defguard}]
  (alter-meta! w merge (select-keys (meta o) [:doc :arglists])))
