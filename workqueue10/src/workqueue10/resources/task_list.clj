(ns workqueue10.resources.task-list
  "The TaskList resource: the named list a task belongs to, as a row.

  Two of the authorities the queue drinks from group their work by
  list — google keeps task lists, home assistant keeps todo entities —
  and until now neither said so in a way the queue could use. Google
  buried the list inside the task's identity (\"tasklist/taskid\") and
  published no field at all; home assistant string-prefixed the list's
  friendly name into :detail, which made a filterable, labelable,
  linkable fact into prose. This kind is the same correction the
  household just made for people: :assignee stopped being a word that
  spelled a member's id and became a ref to :member, and every surface
  read the person instead of the token. A list is no different.

  NOT GOOGLE'S KIND. One row per list from ANY authority — the tag in
  the external id says which, exactly as it does for a task
  (\"gtasks:MTIzNA\", \"todo:todo.woodworking\"), and the :source enum
  is the same routing vocabulary the queue's own kind declares. A
  third authority with lists joins by implementing confluence's
  TaskListSource; nothing here learns its name.

  PULL-ONLY, deliberately. The queue mirrors the household's lists; it
  does not create, rename or delete them. That is not a limitation
  waiting to be lifted — a list is a shape its authority owns, and the
  hub inventing one would leave a row no phone could ever see. So
  there are no local writes, no :push-on-write, and for a MIRRORED
  list no birth door: those rows arrive by discovery and by discovery
  alone.

  AND ONE SOURCE WITHOUT AN UPSTREAM: \"native\", the list that lives
  only in this engine. The field test found every list door demanding
  an external id from an authority that had never heard of the list,
  so standalone work sat parentless rather than fabricate an upstream
  — the fabrication stops here. A native list is born at the create
  door with a title and no external_id at all (the birth law is the
  pairing, identity_paired: native carries no identity, a mirrored
  source always names one), and the sync machinery never learns its
  name — nothing pulls it, resync never rewrites it, no feed can mark
  it gone, because every one of those passes selects rows BY their
  external identity ({:local-rows true}, the mirror's own fence).
  External integration stays a growth direction: native lists COEXIST
  with the mirrors beside them, they do not replace them.

  A GONE LIST KEEPS SERVING (:on-gone unsaid — the framework's :keep).
  A list the household deletes stops being discovered, and its tasks
  go gone on their own feed, which is where the deletion is visible.
  Dropping the list row would only cost the tasks that still cite it
  their label, so the record stands and the sync machine renders its
  staleness honestly instead.

  It fills FIRST (:priority 40, ahead of the default 50 :task takes).
  Nothing depends on it — a task observed before its list exists
  carries a nil ref, and the list's own discovery pass heals every
  edge pointing at what it just minted — but ten rows read before a
  few hundred means the ref is usually right the first time rather
  than one beat later.

  PUNT, recorded: :task_list declares no :related edge back to the
  tasks that cite it, so \"show me this list's work\" is a filter
  (GET /api/tasks?task_list=<id>) rather than an embedded table. The
  filter is the honest primitive and the embed is a display choice;
  it can be declared the day someone wants it on the page."
  (:require [waymark10.dsl :refer [defderived require-fact resource]]
            [waymark10.server.mirror :as mirror]))

;; a list is named once and renamed almost never — the queue can be
;; slower here than it is about the work itself (task's 300s)
(def ttl-seconds 900)
(def discover-every 900)

;; ── the native birth law ────────────────────────────────────────────
;; the paired-field create law, the pantry window's pattern
;; (spec-pantry: window_paired — a derived fact require-fact'd at the
;; birth door, so the half-set shape is unrepresentable): a list is
;; native XOR it names its upstream. Discovery mints speak neither
;; :source nor the create-schema's vocabulary and skip create guards
;; by the engine's own rule; the fact still reads them as mirrored
;; (an external id, no \"native\").
(defderived identity-paired
  {:over [:source :external_id]
   :expr '(or (and (= (var :source) "native")
                   (not (is-set (var :external_id))))
              (and (not= (var :source) "native")
                   (is-set (var :external_id))))
   :explain "A native list is this engine's own and carries no external id; a mirrored list names the identity its authority keeps."})

(def identity-is-paired
  (require-fact :identity_paired
                {:explain "A native list is this engine's own and carries no external id; a mirrored list names the identity its authority keeps."}))

(defn task-list-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :task_list
     :summary "{data.title} · {data.source}"
     :label-template "{data.title}"
     :schema [:map
              [:title {:optional true
                       :x-display
                       {:label "What the list is called"
                        :help "The household's own name for this pile of work — \"Errands\", \"Woodworking\" — the word you would look for on the phone."}}
               [:maybe [:string {:max 200}]]]
              ;; the confluence's routing tag, stamped by the adapter —
              ;; the same vocabulary task declares, narrowed to the
              ;; authorities that actually keep lists — plus "native",
              ;; the one tag no adapter ever stamps: the engine's own
              [:source {:optional true :filter #{:eq :in}
                        :x-display
                        {:label "Who keeps this list"
                         :choices {"native" "Just us — a list that lives only here, with no phone behind it"
                                   "gtasks" "Google Tasks — mirrored from the phone"
                                   "todo" "Home assistant — mirrored from the wall tablet"}}}
               [:maybe [:enum "todo" "gtasks" "native"]]]
              ;; the birth law as a maintained fact (see above)
              [:identity_paired {:optional true :derived identity-paired}
               [:maybe :boolean]]
              ;; where the row drinks from, as URLs the source stamps.
              ;; Hidden: the origin LINK is the affordance, a raw URL
              ;; in the fields is noise. A source with no browser face
              ;; for a list (google publishes no per-list web URL)
              ;; simply leaves it unset and the link omits.
              [:source_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]
              [:source_ui_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]]
     :filterable {:state #{:eq :in}}
     :display {:title "{data.title}"}
     ;; the one birth door: native lists (and, unchanged, the
     ;; full-schema creates a mirrored row could always take) — the
     ;; paired law is the whole gate
     :create-guards [identity-is-paired]
     :links [{:rel "origin" :href "{data.source_ui_href}" :external true
              :summary "The list this row mirrors, at the authority that keeps it"}]}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     ;; native lists live here alone: external_id optional-and-absent,
     ;; and every sync pass (pull-through, resync, discovery) already
     ;; selects rows by external identity, so they are never touched
     :local-rows true
     :priority 40
     ;; the cadenced whole-kind heal: a list renamed on the phone lands
     ;; within the window rather than at the next boot
     :resync-every 3600})))
