(ns waymark10.confirm
  "The confirm gate's sentence, read in one place.

  An action whose safety.confirm is true does not run until the caller
  echoes its consequence sentence. Three readers compare against that
  sentence: the affordance-following client (`waymark10.client`), the
  MCP surface's confirm gate (`server/mcp`), and the scheduled run that
  reads the gate again at `run_at` (docs/spec-scheduled-actions.md
  R-3.3). Two readings of one sentence is a gate that can be walked
  around, so the reading lives here and each of them calls it.

  Pure: no server namespace is required here, so the client and every
  server namespace may require it and none of them has to require
  another to reach it."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def fallback
  "What a confirm action says when its declaration wrote no sentence."
  "This action requires confirmation.")

(defn consequence-of
  "The confirm gate's text, read off the row's own rendered entry — the
  declaration's `:consequence` rides the wire as display.description,
  a per-origin map already resolved against this row's state. The
  label stands in when the entry carries no description, and `fallback`
  when it carries neither."
  [entry]
  (or (get-in entry [:display :description])
      (get-in entry [:display :label])
      fallback))

;; ── the create door (ticket 13e3b323) ─────────────────────────────────

(defn create-confirm?
  "Does this kind's create door declare safety.confirm? A create has no
  row to render an entry on, so the declaration's :create-safety is
  read directly."
  [rdef]
  (boolean (get-in rdef [:create-safety :confirm])))

(defn- fill
  "A template's {field} holes filled from the create's input. A hole the
  input does not answer stays as written, so the sentence shows what
  was not given rather than reading as if it were."
  [template input]
  (str/replace template #"\{([^{}]+)\}"
               (fn [[hole k]]
                 (let [v (if (contains? input (keyword k))
                           (get input (keyword k))
                           (get input k))]
                   (if (some? v) (str v) hole)))))

(defn create-consequence
  "The sentence a confirm create owes, filled from ITS input: the
  declaration's :create-safety :consequence is a fn of the input (keyword
  keys) → one sentence, or a string template whose {field} holes the
  input fills. `fallback` when it is neither."
  [rdef input]
  (let [c (get-in rdef [:create-safety :consequence])
        input (or input {})]
    (cond
      (string? c) (fill c input)
      (fn? c) (or (c input) fallback)
      :else fallback)))
