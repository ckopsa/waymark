# WHAT THE HOUSE ALREADY SAYS (waymark-frv, begun under waymark-nl0) —
# ONE function, for every place the manifest hands a composer something
# it must answer: an unanswered person turn, a mailed FORM, a
# handed-back bundle.
#
# WHY IT EXISTS. On 2026-08-29 the owner asked on the placard bundle
# "Can he get this without a proper Utah ID yet?" and the house's own
# record answered yes — the TC-842 task's detail says "No state ID
# required — accepts SSN as the ID type, and it mails in". Gemini,
# handed the thread and nothing else, answered from the QUESTION —
# "getting the ID would be a prerequisite" — and published that as an
# insight: a false fact with an address. On 2026-08-31 a clerk was
# asked whether Clark's baptismal interview happened and answered
# "still unknown" without opening the Messages thread the record was
# pointing straight at. So the block prints two things and not one:
# WHAT STANDS (the rows, with their detail, and every published
# finding that names one of them) and WHERE TO LOOK NEXT (the readable
# thread the record points at, and the household's own search key) —
# because "the record is silent" is only true when somebody looked.
#
# The caller prepends `scripts/wm-keys.jq`, so the Gate key printed
# here is derived by the SAME `wm_keys` a work order carries.
#
# IN (--argjson subjects; the rest --slurpfile, each a bare array):
#   $subjects   the addresses to build a block for, in any kind
#   $tasks $events $people $ins $out $chats
# OUT: one entry per subject that resolved to a row —
#   {subject, kind, label, rows:[…], findings:[…], where:[…]}
# A subject that resolves to nothing is DROPPED, and the printer then
# says "read the row at its own address": an empty block reads as "the
# house is silent", which is the one sentence this material exists to
# stop a model from saying without looking.

def hs_flat: tostring | gsub("\\s+"; " ");

# THE CAPS, said once. The block is read by a weak model at the top of
# a long manifest: past a handful of rows it stops being the record and
# starts being scenery.
def hs_rows_cap: 8;
def hs_findings_per_row: 4;
def hs_findings_cap: 8;
def hs_where_cap: 4;

# ── one address, rendered as the house's own sentence ────────────────
def hs_render($a; $tk; $ev; $pp; $in; $ou; $ch):
  if ($a | test("^/api/tasks/")) then
    (([ $tk[] | select(.self == $a) ] | first) // null) as $t
    | if $t == null then empty else
      {self:$a, kind:"task",
       says: ((($t.fields.title // $t.display.title // "") | hs_flat)
              + (if (($t.fields.status // "open") != "open") then " [" + ($t.fields.status | tostring) + "]" else "" end)
              + (if (($t.fields.due_at // "") | tostring) != "" then " · due " + (($t.fields.due_at | tostring) | .[0:10]) else "" end)),
       detail: ((($t.fields.detail // "") | hs_flat) | .[0:400])} end
  elif ($a | test("^/api/events/")) then
    (([ $ev[] | select(.self == $a) ] | first) // null) as $e
    | if $e == null then empty else
      {self:$a, kind:"event",
       says: ((($e.fields.title // $e.display.title // "") | hs_flat)
              + " · " + (($e.fields.starts_at // $e.fields.date // "no start on the row") | tostring)
              + (if (($e.fields.ends_at // "") | tostring) != "" then " to " + ($e.fields.ends_at | tostring) else "" end)
              + (if (($e.fields.location // "") | tostring) != "" then " · " + ($e.fields.location | tostring) else "" end)),
       detail: ((($e.fields.detail // $e.fields.description // "") | hs_flat) | .[0:400])} end
  elif ($a | test("^/api/people/")) then
    (([ $pp[] | select(.self == $a) ] | first) // null) as $p
    | if $p == null then empty else
      {self:$a, kind:"person",
       says: ((($p.data.name // "") | hs_flat) + " — " + (($p.data.relation // "?") | hs_flat)
              + " [" + (($p.state // "?") | tostring) + "]"),
       detail: ""} end
  elif ($a | test("^/api/threads/")) then
    (([ $ch[] | select(.self == $a) ] | first) // null) as $c
    | if $c == null then empty else
      {self:$a, kind:"thread",
       says: ((($c.data.title // "(untitled)") | hs_flat)
              + " · " + (($c.data.source // "?") | tostring)
              + " · last message " + ((($c.data.last_message_at // "") | tostring) | .[0:16])),
       detail: ("with " + ((($c.data.participant_names // []) | join(", ")) | hs_flat)
                + " — a CONVERSATION: its messages are not in this manifest, so read it before you call anything unknown")} end
  elif ($a | test("^/api/insights/")) then
    (([ $in[] | select(.self == $a) ] | first) // null) as $i
    | if $i == null then empty else
      {self:$a, kind:"finding",
       says: (((($i.data.finding // "") | hs_flat) | .[0:300])
              + " [" + (($i.state // "?") | tostring) + "]"),
       detail: ""} end
  elif ($a | test("^/api/outcomes/")) then
    (([ $ou[] | select(.self == $a) ] | first) // null) as $b
    | if $b == null then empty else
      {self:$a, kind:"bundle",
       says: (((($b.data.goal // "") | hs_flat) | .[0:200])
              + " [" + (($b.state // "?") | tostring) + "]"),
       detail: ""} end
  else empty end;

# ── the addresses a subject is ABOUT: itself, and what it cites ──────
def hs_subject_rows($a; $in; $ou):
  ([$a]
   + (if ($a | test("^/api/insights/"))
      then ((([ $in[] | select(.self == $a) ] | first) // {}) | (.data.evidence // []))
      elif ($a | test("^/api/outcomes/"))
      then ((([ $ou[] | select(.self == $a) ] | first) // {}) | (.data.evidence // []))
      else [] end))
  | unique;

# ── WHERE TO LOOK NEXT ───────────────────────────────────────────────
# A thread row is an address with no words in it: the manifest names
# the conversation and never carries the messages. So when the record
# points at one, the block says READ IT and how — a clerk that answers
# "still unknown" off a manifest that named the thread has not read the
# record, it has read the index of it. A thread the rows CITE outranks
# one merely sharing a person; after the threads comes the house's own
# search key, for the rigs the row set never reached.
def hs_where($a; $rows; $words; $ch):
  ([ $rows[] | select(test("^/api/threads/")) ]) as $cited
  | ([ $rows[] | select(test("^/api/people/")) | split("/") | last ]) as $person_ids
  | ([ $ch[] | . as $c
       | select((($c.data.status // "live") | tostring) != "dropped")
       | select((($c.data.participants // []) | any(. as $p | ($person_ids | index($p)) != null))
                or (($cited | index($c.self)) != null))
       | {self:$c.self, title:(($c.data.title // "(untitled)") | hs_flat),
          at:((($c.data.last_message_at // "") | tostring) | .[0:16]),
          cited:(($cited | index($c.self)) != null),
          names:((($c.data.participant_names // []) | join(", ")) | hs_flat)} ]
     | sort_by([(if .cited then 1 else 0 end), .at]) | reverse) as $threads
  | ([ $threads[]
       | {kind:"thread", self:.self,
          say:("read " + .self + " via tgram__get_messages, title \"" + .title + "\""
               + (if .names != "" then " (with " + .names + ")" else "" end)
               + (if .cited then " — the record CITES this thread" else " — a person on these rows is in it" end)
               + (if .at != "" then "; last message " + .at else "" end))} ])
    as $tw
  | ([ ($words | wm_keys)[]
       | {kind:"gate", key:.,
          say:("search Gate for \"" + . + "\" — tgram__search_messages, or the mailbox — that is the household's own word for this")} ])
    as $gw
  | ($tw + $gw) | .[0:hs_where_cap];

def house_says($subjects; $tasks; $events; $people; $ins; $out; $chats):
  ([ $ins[] | select(.state == "published") ]) as $pub
  | [ ($subjects | unique)[] | . as $a
      | (hs_subject_rows($a; $ins; $out)) as $addrs
      | ([ $addrs[] | hs_render(.; $tasks; $events; $people; $ins; $out; $chats) ]) as $found
      | select(($found | length) > 0)
      # THE SUBJECT FIRST, then the rows in the order a person would
      # want them: the things with words in them (task, event) ahead of
      # the things that only point (person, thread), and the rows that
      # are themselves somebody's writing (bundle, finding) last. Sorted
      # by address, the placard bundle led with a finding about itself.
      | (([ $found[] | select(.self == $a) ])
         + ([ $found[] | select(.self != $a) ]
            # the kind is bound before index() is asked: inside
            # index(f), `.` is the array being searched
            | sort_by(. as $r
                      | (["task","event","person","thread","bundle","finding"]
                         | index($r.kind)) // 9))) as $rows
      | ([ $rows[] | .self ]) as $seen
      | {subject: $a,
         kind: ($a | split("/") | .[2]),
         label: (([ $rows[] | select(.self == $a) | .says ] | first
                  // ($rows[0].says // "")) | .[0:120]),
         rows: ($rows | .[0:hs_rows_cap]),
         findings:
           ([ $seen[] | . as $r
              # a finding already standing as a ROW above is not
              # reprinted here — it said its piece once
              | [ $pub[] | . as $p
                  | select(($seen | index($p.self)) == null)
                  | select((($p.data.evidence // []) | index($r)) != null)
                  | {self, at:((.meta.updated_at // "") | tostring),
                     finding: (((.data.finding // "") | hs_flat) | .[0:300]),
                     about: $r} ]
                | sort_by(.at) | reverse | .[0:hs_findings_per_row] ]
            | add // []
            | unique_by(.self) | sort_by(.at) | reverse | .[0:hs_findings_cap]),
         where:
           # the search key comes off the SUBJECT's own sentence and not
           # the whole row set: a task's detail is instructions, and
           # keying on it hands back "Windshield" where the household's
           # word is "Grandpa".
           (hs_where($a; $seen;
                     (([ $rows[] | select(.self == $a) | .says ] | first)
                      // ([ $rows[] | .says ] | join(" ")));
                     $chats))} ];

# Every row set arrives by --slurpfile, so it is wrapped in a
# one-element array; the unwrapping is done HERE and once, so the
# caller — driver or fixture — passes the same flags either way.
house_says($subjects;
           ($tasks[0] // []); ($events[0] // []); ($people[0] // []);
           ($ins[0] // []); ($out[0] // []); ($chats[0] // []))
