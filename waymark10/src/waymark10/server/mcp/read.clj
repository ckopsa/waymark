(ns waymark10.server.mcp.read
  "The read tools' definitions, carved out of waymark10.server.mcp
  (ticket c7c2b65a): `waymark_discover`, `waymark_schema`,
  `waymark_query`, `waymark_get`, `waymark_history`, `waymark_resolve`
  and `waymark_in_flight`, each its name, title, description and input
  schema, plus the `caption` argument the staged tools share.

  The bodies, the dispatch table and the tool list stay in mcp, which
  reads these vars; no tool's wire shape changes.")

(def discover-tool
  {:name "waymark_discover"
   :title "Discover this engine's surface"
   :description
   (str "What this engine serves and what YOU may see of it: every "
        "kind, its collection href, its action names, its view names, "
        "the field names its filters may name, its navigation "
        "tier and domain, plus the doors (welcome, knock, ask, grant "
        "check) and the principal you resolved to. Start here. The "
        "list is your grant's projection — a kind you were not granted "
        "is absent, not refused. This answer is also where most "
        "x-options recipes land (see waymark_schema): when a field "
        "says its options come from here, the tokens are in THIS "
        "document, at the path the recipe names.")
   :input-schema {:type "object" :properties {} :additionalProperties false}})

(def schema-tool
  {:name "waymark_schema"
   :title "One kind's schema and doors"
   :description
   (str "One kind's published JSON Schema — already projected through "
        "your grant, so a field you may not read is not in it — plus "
        "every action you may invoke on that kind: its input schema, "
        "its prose, its effort class, its safety (idempotent, "
        "reversible, confirm, fence) and the consequence sentence a "
        "confirm-gated action requires. Availability is per ROW and "
        "per state; read waymark_get for what a particular row affords "
        "right now.\n\n"
        "A field whose legal tokens this engine enumerates only at "
        "RUNTIME carries x-options — {from, of, href, at, each, "
        "composes, note} — instead of an enum: fetch `href` (usually "
        "the waymark_discover answer, sometimes another kind's schema; "
        "an href opening with a hole, {subject}/-/places, is a ROW's "
        "own document — the address that argument holds, with /-/places "
        "behind it, served over HTTP and by no tool here yet), "
        "walk `at` (an array's elements are the tokens, an object's "
        "keys are), and you have the vocabulary. Where `href` or `at` "
        "carries a hole like {target}, fill it with the value of the "
        "SIBLING argument of that name — so answer that one first; "
        "inside a nested argument the hole names the top-level "
        "argument first, then a sibling of its own map. "
        "`each` means the field is a list of such tokens; `composes` "
        "means the value is BUILT from them (\"query\" = a "
        "field=value&… filter string). The options are advertisement, "
        "not law: the guard still judges the write, and may accept a "
        "token no source lists. "
        "`cracks` is the cracks audit of this kind, as the cracks door "
        "of waymark_discover answers you: the states no active seat "
        "can leave, the ones no seat is woken to, and the remedies no "
        "seat holds.")
   :input-schema {:type "object"
                  :properties {:kind {:type "string"
                                      :description "A kind name from waymark_discover."}}
                  :required ["kind"]
                  :additionalProperties false}})

(def caption-arg
  "The `caption` argument of the staged tools
  (docs/spec-agent-demo-walks.md § 3)."
  {:type "string"
   :description (str "One line, at most 140 characters, about this step, "
                     "for a self walk you are recording: it is written "
                     "before the call's beats and stays until the next "
                     "caption. An empty one clears it. With no recording "
                     "walk it does nothing.")})

(def query-tool
  {:name "waymark_query"
   :title "Query a collection"
   :description
   (str "A kind's collection: filtered, sorted, paged, with totals and "
        "facets. Filterable fields and sort keys are declared per kind "
        "— the answer's own actions.query.input names exactly which, "
        "so read one page before guessing. A filter or sort naming a "
        "field your grant does not admit is refused the same way an "
        "unknown one is, deliberately.\n\n"
        "return: \"envelope\" (default) is the collection document "
        "whole — every item with its actions, the query action's "
        "input schema with the filter vocabulary and facets. "
        "\"summary\" is the same page projected to what a reader "
        "usually wants once it knows the kind: per item its id, "
        "kind, state, summary line and grid fields, plus total, page, "
        "next/prev and facets — no actions, no schemas. Use summary "
        "when you already read waymark_schema and are scanning rows; "
        "use envelope when you need each row's affordances or the "
        "filter grammar.")
   :input-schema {:type "object"
                  :properties
                  {:kind {:type "string" :description "A kind name from waymark_discover."}
                   :return {:type "string" :enum ["envelope" "summary"]
                            :description (str "envelope (default): the route's document, "
                                              "byte for byte. summary: items as id/kind/"
                                              "state/summary/fields plus paging and facets, "
                                              "without the actions block.")}
                   :filter {:type "object"
                            :description (str "Field → value, ANDed. Values cross as "
                                              "strings; a comma-separated value means "
                                              "\"any of\" on a field that admits it.")
                            :additionalProperties {:type "string"}}
                   :sort {:type "string"
                          :description "A declared sortable field; prefix with - for descending."}
                   :page_size {:type "integer" :minimum 1}
                   :page_number {:type "integer" :minimum 1}
                   :caption caption-arg
                   :rows {:type "string" :enum ["none"]
                          :description "\"none\" returns totals and facets without the rows."}
                   :fields {:type "array" :items {:type "string"} :minItems 1
                            :description (str "Field names from waymark_schema: each item's "
                                              "fields narrows to exactly these — always a "
                                              "subset of what your grant projects, never "
                                              "more. A name outside your published schema "
                                              "is refused with the vocabulary you may use.")}}
                  :required ["kind"]
                  :additionalProperties false}})

(def get-tool
  {:name "waymark_get"
   :title "Read one row"
   :description
   (str "One row's envelope: its fields, its state, its links, and the "
        "actions it affords YOU right now — each with its input "
        "schema, its prose and its safety — plus the ones it does not, "
        "with the reason and what would make them available. This is "
        "the document to read before acting: the action hrefs and the "
        "consequence sentence waymark_invoke needs both live here.\n\n"
        "return: \"envelope\" (default) is that whole document. "
        "\"summary\" is the row alone — id, kind, state, summary line "
        "and data — without the actions, unavailable, links, parts and "
        "meta blocks. Use summary to read a row's values when you "
        "already know the kind's doors from waymark_schema; use "
        "envelope before acting, since the consequence sentence and "
        "each action's availability on THIS row live only there. "
        "\"export\" is for a sealed walk: what its export file is — "
        "format, header, frames, bytes, sha256, href — with its first "
        "lines up to 64 KB and `truncated` when that was not all of "
        "it. The whole file is read at href over HTTP.")
   :input-schema {:type "object"
                  :properties {:kind {:type "string"}
                               :id {:type "string"}
                               :caption caption-arg
                               :depth {:type "string" :enum ["full" "summary"]
                                       :description "summary drops data and parts."}
                               :return {:type "string" :enum ["envelope" "summary" "export"]
                                        :description (str "envelope (default): the row's "
                                                          "document, byte for byte. summary: "
                                                          "id/kind/state/summary/data only, "
                                                          "without the actions block. export: "
                                                          "a sealed walk's file, described.")}}
                  :required ["kind" "id"]
                  :additionalProperties false}})

(def history-tool
  {:name "waymark_history"
   :title "One row's transitions"
   :description
   (str "The audit trail of one row, newest first: which action moved "
        "it, from which state to which, who did it and when — and, for "
        "each, WHY it was allowed. `basis` names the guards that judged "
        "it under the law revision of that day; `judgment` carries what "
        "those guards read, where the kind retains it. Every write in "
        "this engine is logged; nothing here is reconstructed after the "
        "fact, and `evidence` says which of the two answers you are "
        "reading.")
   :input-schema {:type "object"
                  :properties {:kind {:type "string"}
                               :id {:type "string"}
                               :limit {:type "integer" :minimum 1 :maximum 200}}
                  :required ["kind" "id"]
                  :additionalProperties false}})

;; ── waymark_resolve (waymark-pywy.3): the batch lookup ──────────────
;;
;; The seventh tool, and still no new route: it is the collection
;; route's own in-filter grammar (`field=a,b`, collections.clj's :in
;; op) called through the door as many times as the page limits ask,
;; plus set difference. A field is resolvable exactly when the
;; declaration already made it filterable by :eq or :in, so the
;; refusal for any other field names that vocabulary — read off the
;; declaration, never found by a scan.

(def resolve-tool
  {:name "waymark_resolve"
   :title "Resolve many keys to rows"
   :description
   (str "Batch lookup: which rows of a kind carry these values in a key "
        "field — a list of barcodes to products, of names to ingredients "
        "— answered as {matched: {value: row}, unmatched: [values]} in "
        "one call instead of one waymark_query per value. `by` must be a "
        "field the kind declares filterable by equality (a refusal lists "
        "the fields that are); `filter` narrows the candidates with "
        "waymark_query's grammar. A matched row is a compact summary — "
        "id, state, summary line, fields — not the envelope with its "
        "actions block; `fields` keeps only the named ones (waymark_query's "
        "fields, judged by the route). Values cross "
        "as strings and match the field's wire spelling. A value several "
        "rows share comes back under `ambiguous`, not `matched`: "
        "resolving means ONE row.\n\n"
        "UNMATCHED IS NOT ABSENT. You see exactly what your grant admits, "
        "so a row outside it is simply unmatched here — this tool cannot "
        "tell a value nobody has from one you may not see, and does not "
        "try.")
   :input-schema
   {:type "object"
    :properties
    {:kind {:type "string" :description "A kind name from waymark_discover."}
     :by {:type "string"
          :description (str "The key field: one the kind declares filterable "
                            "by :eq or :in (waymark_schema's query input "
                            "names them).")}
     :values {:type "array" :items {:type "string"} :minItems 1
              :description "The values to look up, as strings."}
     :filter {:type "object"
              :description (str "Extra field → value filters, ANDed with the "
                                "lookup — waymark_query's grammar.")
              :additionalProperties {:type "string"}}
     :fields {:type "array" :items {:type "string"}
              :description "Keep only these of each matched row's fields."}}
    :required ["kind" "by" "values"]
    :additionalProperties false}})

;; ── waymark_in_flight (ticket 524a4705): the in-flight read ─────────
;;
;; GET /api/-/in-flight (ticket d0407b84) answered over MCP: an agent
;; cannot GET an href, so the discover door alone left it unread. No
;; new reading — the function the route answers with, with its four
;; filters (the door mounts core's routes only, as for cracks).

(def in-flight-tool
  {:name "waymark_in_flight"
   :title "Every open row and who holds it"
   :description
   (str "Every open row your grant admits, with its waiting_on, grouped "
        "by who holds its next move — the in-flight read, the same "
        "document as GET /api/-/in-flight (discover's `in_flight` door). "
        "`domain`, `repo`, `kind` and `holder` narrow it; each is matched "
        "exactly. A kind or row outside your grant is absent, not refused.")
   :input-schema
   {:type "object"
    :properties
    {:domain {:type "string" :description "A domain name from waymark_discover."}
     :repo {:type "string" :description "Only rows whose repo is this."}
     :kind {:type "string" :description "A kind name from waymark_discover."}
     :holder {:type "string"
              :description "Only the group held by this holder (person, seat, nobody ...)."}}
    :additionalProperties false}})
