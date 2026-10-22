(ns waymark10.server.scope
  "The scope schema: the leash a grant carries, entry by entry, spelled
  once for every door that takes one — grant create and extend, the
  approval_request, the seat, and a member's invite. It lives apart
  from grants because members cannot require grants (grants requires
  members), and an invite's grant is a scope like any other: a blank
  box there was a picker withheld. Pure data; it requires nothing.")

(def field-spec-schema
  "One mode spec: allow renders only :names, deny renders all but."
  [:map
   [:mode {:x-display {:label "Allow or deny"
                       :choices {"allow" "Allow — only the fields named here are readable"
                                 "deny" "Deny — every field except the ones named here"}}}
    [:enum "allow" "deny"]]
   ;; no :x-options here, deliberately: the vocabulary IS this kind's
   ;; data fields, but :of names a SIBLING and the kind is spelled one
   ;; level up, on the scope entry this spec hangs off. A recipe
   ;; reaching out of its own map is a hole no client could fill from
   ;; the form in front of it, so the sentence says where to look
   [:names {:x-display {:label "Field names"
                        :help "Data field names of the kind this entry names — the same names its published schema lists."}}
    [:vector [:string {:min 1 :max 64}]]]])

(def scope-example
  "Something in the textarea instead of a blank page (waymark-0ee's
  composition policy, waymark-7rw's turn at it). An example is offered
  and never applied: the shape of the smallest honest leash — one
  kind, one action, nothing else."
  [{:kind "task" :actions ["claim" "complete"]}])

(def filter-map-schema
  "A filter in the shape of the kind's query where clause: field →
  exact value, the collection grammar's :eq only. It is spelled once
  because two declarations now carry it — the scope entry below,
  where it narrows what a grant admits, and the seat's `wake_on`
  entry (seats/wake-entry-schema), where it narrows what a count wake
  counts. Keys arrive keywordized off the wire."
  [:map-of :keyword [:string {:min 1 :max 200}]])

(def filter-spelled-by-hand
  "Why a filter is typed rather than offered, spelled once and worn
  twice: the scope entry below and the seat's wake entry
  (seats/wake-entry-schema) wear the same box for the same reason. A
  row of a list of entries is a sub-form since waymark-fp62.7.9, so
  every other field of an entry is a widget — this one is the map-of
  the client can only hand a person as a box, and waymark-2hd0's rule
  is that such a box wears the declaration's own reason."
  "No form can list another kind's field names, so this pair is typed: write the field name, then the value it must equal. The field is `state`, or one that kind declares filterable.")

;; The scope form is where a person decides whether to trust an agent,
;; so it is the last form in this codebase that should have been a
;; blank JSON textarea. Since waymark-8sg the two vocabularies it is
;; judged against can be named out loud (:x-options), and waymark-7rw
;; discovered that the spelling reaches INSIDE an array's item map with
;; no new capability at all: an item's fields are each other's
;; siblings, so :of resolves within the entry a person is filling in.
(def scope-schema
  [:vector
   [:map
    [:kind {:x-options {:from :kinds}
            :x-display {:label "Kind"
                        :help "The collection this entry opens — one kind name this engine serves."}}
     [:string {:min 1 :max 64}]]
    [:ids {:optional true
           :x-display {:label "Only these rows"
                       :help "Row ids, when the leash is a handful of documents rather than a collection. Omit it and the entry covers the whole kind (narrowed by any filter below)."}}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    [:actions {:x-options {:from :actions :of :kind :each true}
               :x-display {:label "Actions"
                           :help "What may be done to those rows, by name. An empty list is the read-only ask — sight without a single lever."}}
     [:vector [:string {:min 1 :max 64}]]]
    [:fields {:optional true
              :x-display {:label "Field disposition"
                          :help "Which of the kind's data fields are readable at all — allow-list or deny-list. Omit it and every field the kind publishes is readable."}}
     [:maybe field-spec-schema]]
    ;; the third disposition (waymark-rci): a field named here renders
    ;; as a stable opaque token — correlate and reference, never read.
    ;; hidden = not admitted at all; hashed = admitted-as-token; read =
    ;; admitted plain. Hashing DOMINATES its kind's sibling entries —
    ;; a restriction is never absorbed the way openness absorbs.
    [:hashed {:optional true
              :x-options {:from :fields :of :kind :each true}
              :x-display {:label "Tokenised fields"
                          :help "Fields the holder may correlate but never read — each value crosses as a stable opaque token."}}
     [:maybe [:vector [:string {:min 1 :max 64}]]]]
    ;; the filter-scoped entry (waymark: pools as first-class): the
    ;; rows this entry admits are the ones MATCHING, judged at render
    ;; — rows minted after the grant land inside the leash the moment
    ;; they match. field → exact value, the collection grammar's :eq
    ;; only; the field must be one the kind declares filterable (the
    ;; scope-filters guard), so the promise is enforceable at the
    ;; query AND the row. Keys arrive keywordized off the wire.
    ;; no :x-options: the vocabulary here is the legal KEYS of an
    ;; object, and the recipe's two composition words (:each for a
    ;; token list, :composes :query for a field=value string) both
    ;; describe a value BUILT from tokens. Calling a map one or the
    ;; other would be a lie about the grammar, so the help sentence
    ;; carries it and waymark-3ox holds the honest spelling
    [:filter {:optional true
              :x-display {:label "Only rows matching"
                          :spelled-by-hand filter-spelled-by-hand
                          :help "One field=value pair, judged at render — rows minted later land inside the leash the moment they match. The field is `state`, or one the kind declares filterable with eq; one filtered entry per kind."}}
     [:maybe filter-map-schema]]
    ;; the team-scoped entry (docs/spec-teams.md §6): the rows whose
    ;; `field` names the team, or a member on it, read live at each
    ;; request by `resolve-team-of` — a member added to a crew sees its
    ;; rows at the next read, with no new grant. ANDed with :filter;
    ;; one per kind (the scope-filters guard)
    [:team-of {:optional true
               :x-display {:label "Only rows of a team"
                           :help "A field that names a team or a member, and the team when it is one: a row is admitted while that active team is named, or holds the member named. With no team, any active team the grant's member is on. One per kind."}}
     [:maybe [:map
              [:field {:x-display {:label "Field"
                                   :help "The kind's field that names a team, or a member."}}
               [:string {:min 1 :max 64}]]
              [:team {:optional true
                      :kind :team
                      :x-display {:label "Team"
                                  :help "One team's id. Omit it for every active team the grant's member is on."}}
               [:maybe [:string {:min 1 :max 64}]]]]]]
    ;; the outside party's entry (docs/spec-teams.md §7): the rows one
    ;; of whose member refs names the grant's member, read live by
    ;; `resolve-names-me`. ANDed with :filter
    [:names-me {:optional true
                :x-display {:label "Only rows that name them"
                            :help "The kind's fields that name a member: a row is admitted when one of them names the grant's member."}}
     [:maybe [:vector {:min 1} [:string {:min 1 :max 64}]]]]
    ;; break glass (ticket b177a14b): an entry that carries `until` is
    ;; judged absent from that instant on, and the seat's clock sweep
    ;; takes it off the row. A string and not an instant: it sits
    ;; inside a list of maps, and `scope-ends-are-stated` parses it.
    [:until {:optional true
             :x-display {:label "Ends at"
                         :help "An RFC 3339 time with an offset, at most 30 days ahead. From that moment the entry is judged absent, and the sweep takes it off the seat."}}
     [:maybe [:string {:min 1 :max 40}]]]
    [:because {:optional true
               :x-display {:label "Why it was added"
                           :help "One sentence, required with an end: what this entry was added for."}}
     [:maybe [:string {:min 1 :max 240}]]]
    [:args {:optional true
            :x-display {:label "Argument limits"
                        :help "Per-action limits on WHICH arguments may be sent — {action, allow|deny, names}. Omit it and an admitted action takes any argument its schema accepts."}}
     [:maybe [:vector
              [:map
               ;; :of would have to reach the scope entry one level up
               ;; for the action vocabulary, the same wall
               ;; field-spec-schema's :names meets
               [:action {:x-display {:label "Action"
                                     :help "One of the action names this entry admits above."}}
                [:string {:min 1 :max 64}]]
               [:mode {:x-display {:label "Allow or deny"
                                   :choices {"allow" "Allow — only the arguments named here"
                                             "deny" "Deny — every argument except the ones named here"}}}
                [:enum "allow" "deny"]]
               [:names {:x-display {:label "Argument names"
                                    :help "Input field names of that action, as its published schema lists them."}}
                [:vector [:string {:min 1 :max 64}]]]]]]]]])
