(ns waymark10.dsl-test
  "The front door changes spelling, never law: every dsl fn is the
  split spelling's own value (identical objects), every def form
  expands to the original, and a dsl-spelled declaration fingerprints
  byte-identical to the split spelling."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.declare :as d]
            [waymark10.dsl :as dsl]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]))

(dsl/defhandler mark-touched [row _inp _ctx]
  (assoc-in row [:data :touched] true))

(def probe-rmap
  {:kind :dsl_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:sort :default} [:string {:min 1 :max 50}]]
            [:grade {:optional true} [:maybe (dsl/one-of :a :b)]]
            [:touched {:optional true} [:maybe :boolean]]]
   :flow
   [[:open :touch :open
     {:handler mark-touched
      :display {:label "Touch"}}]
    [:open :close :closed
     {:one-way "Closing records completion; nothing external changes."
      :display {:label "Close"}}]]})

(dsl/defresource probe-dsl probe-rmap)
(r/defresource probe-split probe-rmap)

(deftest the-def-forms-are-the-same-law
  (is (= (into {} probe-split) (into {} probe-dsl)))
  (is (= (fp/fingerprint-hash (fp/fingerprint-of probe-split))
         (fp/fingerprint-hash (fp/fingerprint-of probe-dsl)))))

(deftest the-fn-words-are-the-split-spellings-own-values
  (is (identical? dsl/ref-to d/ref))
  (is (identical? dsl/one-of d/one-of))
  (is (identical? dsl/prose d/prose))
  (is (identical? dsl/money d/money))
  (is (identical? dsl/refuse d/refuse))
  (is (identical? dsl/guard g/guard))
  (is (identical? dsl/expr-guard g/expr))
  (is (identical? dsl/relation g/relation))
  (is (identical? dsl/require-fact g/require))
  (is (identical? dsl/all-of g/and))
  (is (identical? dsl/any-of g/or))
  (is (identical? dsl/resource r/resource)))

(deftest the-aliases-answer-doc-lookups-with-the-originals-words
  (is (= (:doc (meta #'r/defresource)) (:doc (meta #'dsl/defresource))))
  (is (= (:doc (meta #'d/ref)) (:doc (meta #'dsl/ref-to))))
  (is (= (:arglists (meta #'g/expr)) (:arglists (meta #'dsl/expr-guard)))))

;; the same sentence and law through both doors — everything but the
;; def'd var name and the compiled check closure must agree
(dsl/defguard probe-named
  (dsl/refuse "A probe carries its name — it explains itself.")
  '(present? :name))

(d/defguard probe-named-split
  (d/refuse "A probe carries its name — it explains itself.")
  '(present? :name))

(deftest the-sentence-guard-wrapper-passes-forms-through
  (is (= :probe-named (:name probe-named)))
  (is (= (dissoc probe-named :check :name)
         (dissoc probe-named-split :check :name))))

;; the code guard through both doors: the canonical printed form (the
;; fingerprint's identity) must be the same tree for the same body
(dsl/defguardfn probe-fn
  {:judges [:name] :explain "The name is judged."}
  [_row inp _ctx]
  (if (:name inp) :allow :deny))

(g/defguard probe-fn-split
  {:judges [:name] :explain "The name is judged."}
  [_row inp _ctx]
  (if (:name inp) :allow :deny))

(deftest the-code-guard-wrapper-mints-the-same-form-identity
  (is (= (:waymark10/form (meta (:check probe-fn)))
         (:waymark10/form (meta (:check probe-fn-split))))))
