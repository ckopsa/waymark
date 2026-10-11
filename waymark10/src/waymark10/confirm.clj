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
  another to reach it.")

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
