# EVERY ROW A RUN WROTE, CLASSIFIED (waymark-kfm; the extra is
# waymark-mqo). A pure function of the last manifest and the rows this
# principal wrote since the mark — no network, no state — so it can be
# run over literal JSON: `bash scripts/verify-classes-fixture.sh`.
#
# `scripts/sitting-run.sh verify` runs it with:
#   input   the hydrated outcome/insight rows, one JSON object per line
#           ({self, kind, at, by, offer, evidence, cites}), slurped (-s)
#   $m      the last manifest.json
#   $rk     the remarks this principal said since the mark, hydrated
#           ({self, kind, at, by, in_reply_to, subject})
#   $s      the mark; $me the principal; $mode the run's own mode
#   $jt     the journal bodies written since the mark
#   $faults the TWIN lines already printed
# and prints one line per row beyond the orders. Output is a stream of
# strings — the caller prints them as they are.
#
# ONE LINE PER ROW, AND EACH SAYS WHICH LAWFUL THING IT WAS. The grader
# used to know two shapes — a row that cited an order's subject, and a
# row that did not — so every other kind of work the manifest itself
# asked for came out as FILLER. On 2026-08-31 the sitting's manifest
# owed advance_arrivals:7, enrich_a_bare_task:3, index_facts:3; the
# clerk published nine typed insights doing exactly that and the report
# printed seven FILLER lines against them. The lists were right there in
# the manifest the grade is read from — the run was doing what it was
# asked, and only the grader could not see it.
#
# THE CLASSES, IN PRECEDENCE ORDER — first match wins, because a row
# that answers an order is that answer whatever else it touches:
#
#   ORDER-ANSWER      cites an order's subject as the write that order
#                     named, or cites a row on an owed list (a thread,
#                     a handed-back bundle, an offered request, a
#                     decline owed a diagnosis) — graded above this in
#                     the report, one line per order, so never repeated
#                     as a class line
#   FORM-ANSWER       cites the SUBJECT a MAILED form names — the same
#                     address the FORM lines above are
#                     graded on, so the two never disagree — or IS a
#                     remark of ours replying to the turn that form
#                     names, which carries no evidence at all. A row
#                     that merely shares one of a form's other
#                     citations is not answering that form: it is
#                     whatever else it is, and the classes below say so
#   FACT INDEXED      an insight citing a turn the manifest listed
#                     under candidate facts (a person said it and
#                     nothing indexed it)
#   ARRIVAL ADVANCED  cites a row the manifest listed as arrived
#   ENRICHED          an insight citing a task the manifest listed as
#                     bare
#   EXTRA             a reading's ONE freedom: cited, distinct, and the
#                     journal says why (waymark-mqo)
#   FILLER            none of the above — the warning it always was
#
# AN ORDER ABSORBS ONLY ITS OWN EXPECTED WRITE (waymark-alj). This
# subtracted any row that CITED an order's subject, and the first
# reading's one extra — a chat fact, which must cite its thread row —
# cited the very thread ORDER 2 was about. The order's expected write
# was a journal sentence; an insight is not that, and the reading was
# graded "EXTRA: none" for the row it had written on purpose. So an
# order's subject is matched WITH the kind the order asked for: a row
# answers an order when it is the write the order named, and a row that
# merely shares a subject with one is still beyond the orders. The owed
# LISTS keep the old, kindless rule — a thread, a decline or a bundle is
# owed whatever shape the answer takes.
#
# THE CAP COUNTS EXTRAS ONLY. A reading gets one row beyond everything
# the manifest named; arrivals advanced, facts indexed and tasks
# enriched are the assignment, not the freedom, and they never spend it.

. as $rows
| ($m[0]) as $mf
| ([ ($mf.work_orders // [])[]
     | . as $o | (($o.write.kind) // "") as $wk
     | ([$o.subject] + (($o.write.cite // []))) | map(select(. != null))
     | .[] | {addr:., kind:$wk} ]) as $order_writes
| ([ ($mf.unanswered_threads // [])[] | "/api/" + .subject_kind + "s/" + .subject_id ]
   + [ ($mf.unanswered_threads // [])[] | .last.self ]
   + [ ($mf.unanswered_threads // [])[] | (.person_turns // [])[] | .self ]
   + [ ($mf.rework_orders // [])[] | .self ]
   + [ ($mf.offered_requests // [])[] | .self ]
   + [ ($mf.declines // [])[] | select(.owed_a_diagnosis) | .cite[] ]
   | map(select(. != null)) | unique) as $owed
# THE MAILED FORMS ONLY. `notes_for_sittings` is the last reading's
# block read back: on a SITTING those lines are already folded into
# work_orders (so ORDER-ANSWER has them), and on a READING they are
# forms for the CLERK, printed under the review — crediting a reading's
# own row against one would hand it a class it never earned, which is
# how the reading of 2026-08-31 read as answering two forms it had left
# for somebody else.
| (($mf.letter_forms // [])) as $forms
| ([ $forms[] | .subject | select(. != null) ] | unique) as $form_rows
# A LIST THIS MANIFEST DOES NOT CARRY IS NOT A LIST OF NONE. An older
# manifest has no arrivals/bare_tasks/candidate_facts array at all, and
# reading its absence as "nothing arrived" would grade its rows FILLER
# for the very reason this bead exists. null means unknown, the class
# never fires, and the report says so in one line.
| (if ($mf | has("candidate_facts")) then [ ($mf.candidate_facts // [])[] | .self ] else null end) as $facts
| (if ($mf | has("arrivals")) then [ ($mf.arrivals // [])[] | .self ] else null end) as $arrivals
| (if ($mf | has("bare_tasks")) then [ ($mf.bare_tasks // [])[] | .self ] else null end) as $bare
| ([ $rows[] | select(.kind == "outcome" or .kind == "insight")
             | select(.at >= $s) | select(.by == $me)
     | . as $r
     # the entry is bound BEFORE index() is asked: inside index(f), `.`
     # is the ARRAY being searched, so index(.addr) reads .addr off
     # $r.cites and jq dies
     | ((([ $r.cites[] | . as $c | select(($owed | index($c)) != null) ] | length) > 0)
        or (([ $order_writes[] | . as $ow
               | select($ow.kind == $r.kind)
               | select(($r.cites | index($ow.addr)) != null) ] | length) > 0)) as $ordered
     | (if $ordered then "ORDER-ANSWER"
        elif ([ $r.cites[] | . as $c | select(($form_rows | index($c)) != null) ] | length) > 0
        then "FORM-ANSWER"
        elif ($facts != null and $r.kind == "insight"
              and ([ $r.cites[] | . as $c | select(($facts | index($c)) != null) ] | length) > 0)
        then "FACT INDEXED"
        elif ($arrivals != null
              and ([ $r.cites[] | . as $c | select(($arrivals | index($c)) != null) ] | length) > 0)
        then "ARRIVAL ADVANCED"
        elif ($bare != null and $r.kind == "insight"
              and ([ $r.cites[] | . as $c | select(($bare | index($c)) != null) ] | length) > 0)
        then "ENRICHED"
        else "BEYOND" end) as $class
     | $r + {class:$class} ]) as $graded
# A REPLY IS A WRITE. A remark carries no evidence list, so it is read
# by its in_reply_to (the turn a form named) and by the row its
# conversation sits on, and by nothing else — which is why the form
# whose expected write was "reply with a REMARK … in_reply_to
# /api/remarks/<turn>" graded UNANSWERED on 2026-08-31 however exactly
# the clerk answered it. A remark is never filler here: nothing else
# about it is graded by this program.
| ([ ($rk[0] // [])[] | . as $r
     | select(($r.at >= $s) and ($r.by == $me))
     | select([ $forms[] | . as $f
                | (([$f.subject] + (($f.write.cite) // [])) | map(select(. != null))) as $addrs
                | select(((([ $addrs[] | select(startswith("/api/remarks/")) | split("/") | last ])
                           | index($r.in_reply_to)) != null)
                         or (($r.subject != "") and ($f.subject != null) and ($r.subject == $f.subject)))
              ] | length > 0)
     | $r + {class:"FORM-ANSWER"} ]) as $replied
| ([ $graded[] | select(.class == "BEYOND") ]) as $ex
| ([ ($graded + $replied)[] | select(.class != "ORDER-ANSWER" and .class != "BEYOND")
     | if .class == "FORM-ANSWER"
       then "FORM-ANSWER: \(.self) — the write a form on this manifest named"
       elif .class == "FACT INDEXED"
       then "FACT INDEXED: \(.self) — indexes a turn the manifest listed as an uncited fact"
       elif .class == "ARRIVAL ADVANCED"
       then "ARRIVAL ADVANCED: \(.self) — cites a row the manifest listed as arrived; advancing what arrived is the run own job, never an extra"
       else "ENRICHED: \(.self) — enriches a task the manifest listed as bare"
       end ])
  + (if ($ex | length) == 0
     then (if $mode == "reading" then ["EXTRA: none — lawful, and the journal says why or it says nothing"] else [] end)
     elif $mode != "reading"
     then [ $ex[] | "FILLER: \(.self) answers nothing this manifest ordered, owed, listed as arrived, listed as bare or listed as an uncited fact — a sitting has no extra; the orders are the ceiling" ]
     elif ($ex | length) > 1
     then ["FILLER: \($ex | length) rows beyond everything the manifest named — \([ $ex[] | .self ] | join(", ")) — and a reading gets ONE extra, or none"]
     else [ $ex[0] | . as $x
            | if (($x.evidence | length) == 0) then "FILLER: \($x.self) — the extra cites nothing"
              elif ($faults | contains($x.self)) then "FILLER: \($x.self) — the extra is a twin (see TWIN above)"
              elif (($jt | contains($x.self)) or ($jt | contains($x.self | split("/") | last)) | not)
              then "EXTRA: cited, distinct — \($x.self) — but the journal never says why it was worth a row"
              else "EXTRA: cited, distinct — \($x.self)" end ]
     end)
  + ([ (if $arrivals == null then "arrivals" else empty end),
       (if $bare == null then "bare_tasks" else empty end),
       (if $facts == null then "candidate_facts" else empty end) ]
     | if length == 0 then []
       else ["CLASSES UNAVAILABLE: this manifest carries no \(join(", ")) list, so rows answering it fall through to the older grade (a reading extra, or filler on a sitting). An older manifest, graded by the rule it was written under."]
       end)
| .[]
