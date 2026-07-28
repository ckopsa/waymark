(ns workqueue10.sources.hub
  "The hub itself as an authority: the noop source spec-media.md
  chose (shape 1), so an authority-LESS media row — the dinner
  recommendation, the paper book, the borrowed comic — works from
  day one as merely a row whose authority always agrees. Discovery
  names nothing (hub rows are born at the capture door, never minted
  from a feed); every pull answers an empty document under the
  kind's :partial contract (absence is silence — nothing is ever
  overwritten, nothing ever reads gone) beside the one constant etag
  the hub ever speaks, so the sync machine sees eternal agreement;
  every push is the :noop (a hub-local write has no authority to
  tell); a birth mints a random identity. The mirror machinery never
  learns a special case — and the recorded dishonesty stands as
  recorded: unreachable can never mean anything for these rows. If a
  real book authority ever arrives, hub rows are adopted by writing
  their :work_key — the same healing gesture the assignee ref
  performs when a member arrives."
  (:require [workqueue10.confluence :as conf]))

(def etag
  "The hub's one version: its rows have no external truth to move,
  so agreement is a constant."
  "hub")

(defrecord HubSource []
  conf/TaskSource
  (source-discover [_] [])
  (source-pull [_ _id] [{} etag])
  (source-pull-many [_ ids]
    (into {} (map (fn [id] [(str id) [{} etag]])) ids))
  (source-push [_ _id _document] etag)
  (source-create [_ _document] [(str (random-uuid)) etag]))

(defn source [] (->HubSource))
