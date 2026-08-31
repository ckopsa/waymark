# THE TWO WARNINGS ON AN ANSWER (waymark-frv), as one program over
# literal JSON so `scripts/house-says-fixture.sh` can run them with no
# house at all — the same reason `scripts/verify-classes.jq` is a file
# and not a heredoc: a rule with a judgment in it should be checkable.
#
# Both are WARNINGS. Neither blocks anything, and each has a lawful
# answer that clears it: cite a house row, or say in the journal that
# you looked and could not read it.
#
#   SAYS-SO       a finding this run published whose evidence is only a
#                 remark and an outcome — somebody SAID it, and no
#                 task, event, person, thread or value stands behind
#                 it. This is the exact shape of the false fact a weak
#                 model writes when it answers a question from the
#                 question (Gemini, 2026-08-29: "Rod needs his ID
#                 situation resolved", published, while the TC-842
#                 task's detail said SSN suffices).
#
#   UNREAD SOURCE a reply of this run's under a person's QUESTION,
#                 where the manifest's WHAT THE HOUSE ALREADY SAYS
#                 pointed at a readable THREAD and no row this run
#                 wrote cites it and the journal never names it. A
#                 thread row is an address with no words in it: the
#                 manifest can name the conversation and never carries
#                 the messages, so "still unknown" off a manifest that
#                 named the thread is not a reading of the record, it
#                 is a reading of the index (a clerk, 2026-08-31, on
#                 Clark's baptismal interview).
#
# CALLED AS (both read their rows on stdin, slurped):
#   $which "says-so"        . = the rows this principal wrote  ($s, $me)
#   $which "unread-source"  . = one {subject, mine:[…]} per thread,
#                           plus --slurpfile m (the manifest),
#                           --slurpfile tw (the rows), --rawfile jt.

def says_so($rows; $s; $me):
  $rows[]
  | select(.kind == "insight") | select(.at >= $s) | select(.by == $me)
  | . as $i
  | ([ ($i.evidence // [])[]
       | select(test("^/api/(tasks|events|people|threads|values|chore_runs|media|task_lists)/")) ]
     | length) as $house
  | select($house == 0 and ((($i.evidence // []) | length) > 0))
  | "SAYS-SO: \($i.self) — no house row behind it: it cites \($i.evidence | join(", ")) and no task, event, person, thread or value row, so it indexes what somebody SAID. Read the rows the thread is about before it stands as the record.";

def unread_source($ans; $m; $tw; $s; $me; $jt):
  ([ $tw[] | select(.at >= $s) | select(.by == $me) | ((.cites // [])[]) ] | unique) as $touched
  | (($m.unanswered_threads) // [])[]
  | . as $t
  | ($t.subject_kind + "/" + $t.subject_id) as $subj
  | ("/api/" + $t.subject_kind + "s/" + $t.subject_id) as $saddr
  | ([ $ans[] | select(.subject == $subj) | (.mine // [])[] ]) as $replies
  | select(($replies | length) > 0)
  | ((([ (($m.house_says) // [])[] | select(.subject == $saddr) ]) | first) // null) as $h
  # ONLY the threads the subject's own ROWS carry — a thread the record
  # CITES. WHERE TO LOOK NEXT also offers threads reached through a
  # person on those rows, and those are suggestions: a run that skips
  # one has skipped a suggestion, not the record, and this warning does
  # not fault it.
  | ([ (($h.rows // [])[] | .self // empty)
       | select(test("^/api/threads/")) ] | unique) as $sources
  # the address is BOUND before contains() is asked: inside contains(f),
  # `.` is the journal being searched, so a bare `.` there searches the
  # journal for the journal and every source drops out silently
  | ([ $sources[] | . as $src
       | select(($touched | index($src)) == null)
       | select(($jt | contains($src)) | not)
       | select(($jt | contains($src | split("/") | last)) | not) ]) as $unread
  | select(($unread | length) > 0)
  | ($t.owed_turns // [])[] | select(.shape == "question")
  | (.says // "" | gsub("\\s+"; " ")) as $says
  | "UNREAD SOURCE: the record pointed at \($unread | join(", ")) and the answer never looked — \($subj), \(.said_by) asked “\($says[0:80])”. A thread row is an address with no words in it; nothing is unknown until somebody opens it (tgram__get_messages), and a thread you could not read is said out loud in the journal.";

if $which == "says-so"
then says_so(.; $s; $me)
else unread_source(.; ($m[0] // {}); ($tw[0] // []); $s; $me; $jt)
end
