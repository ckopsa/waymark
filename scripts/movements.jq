# WHAT MOVED THIS WEEK — the reading's belief arithmetic (waymark-2m2)
#
# The hypotheses epic's first slice, built to docs/spec-hypotheses.md
# § 'The updater — three deterministic rules', with one difference the
# spec itself names: there are no hypothesis rows yet, so the thing a
# fold is ABOUT is the row an atom cites rather than a claim. Hence
# CLAIM-LESS MOVER. Saying what a movement MEANS is the reading's own
# work, in its own words, citing the atoms.
#
# The house holds no belief anywhere. This is COMPUTED ON THE FLY on
# every reading and nothing it produces is written back — no posterior,
# no weekly snapshot, no cache. If the epic stops at slice 1 there is
# nothing to unwind.
#
# ── WHY THIS IS A FILE AND NOT A HEREDOC IN THE DRIVER ────────────────
#
# Every other probe in `scripts/sitting-run.sh` is inline, and this one
# is not, for one reason: it is the only one that does ARITHMETIC a
# person would want checked. `scripts/movements-fixture.sh` runs this
# exact program over six synthetic atoms with the log-odds hand
# computed in its comments, and CI runs that fixture. A program you can
# point a test at is worth the one indirection.
#
# The price is `to_epoch`, which the driver keeps in `$JQ_DATES` and a
# `-f` file cannot be handed. It is repeated below rather than shared,
# and it must stay character-identical to the driver's: two spellings
# of one date parser is exactly the drift this comment exists to catch.
#
# ── THE THREE RULES ───────────────────────────────────────────────────
#
# 1. LOG-ODDS ADDITION. Each atom contributes `ln(LR)` for its type,
#    cost-graded for `costly_action`, scaled by `solicited_discount`
#    when the house asked. Addition in log-odds is multiplication of
#    odds, which is Bayes with independent evidence and nothing else —
#    and it is why the polarity needs no `if` anywhere: `declined_invite`
#    at 0.2 has a negative log and subtracts on its own. The sum is
#    CLAMPED at ±`log_odds_clamp`, because a belief that reaches
#    certainty stops reading evidence.
#
# 2. ONE COUNT PER EPISODE, ×`episode_intensity`. Atoms sharing an
#    occasion collapse to ONE contribution — the strongest — and if
#    there were two or more it is multiplied by the intensity and no
#    further. Enthusiasm in a single conversation is warmth, not four
#    independent observations. A missing `episode` is its own episode,
#    which is the safe direction: fewer discounts, never a merge the
#    record cannot justify.
#
# 3. DECAY BY TYPE, TOWARD SILENCE. Each contribution is multiplied by
#    `2^(−age_days / half_life[type])`. As an atom decays it approaches
#    zero, which in log-odds is LR 1, so a row nothing has fed for two
#    years does not REVERSE — it forgets, which is the honest thing for
#    a record to do about a person who has changed.
#
# Age comes off the episode's own day where the clerk wrote one, and
# off the day the fact was indexed where it did not. That fallback is
# the honest one and it is also why `sitting-run.sh verify` asks, once
# and gently, for the episode.
#
# ── MOVEMENT IS RULE 1 TWICE ──────────────────────────────────────────
#
# The spec's own sentence: *the same fold with the clock set back seven
# days, subtracted from the fold today.* No stored history and no
# weekly snapshot kind — the atoms carry their own instant, and *what
# moved* is a question about the same numbers asked twice. So a row
# moves when a new fact lands AND when an old one fades, and both are
# true movements of what the record says.
#
# ── WHAT IS LEFT OUT, and it is a judgment rather than an oversight ──
#
# DISMISSED findings. The house said that claim was too thin, not
# backed, already known or not true; an atom hung on a claim the house
# rejected is not evidence. Published and taken findings both count —
# taking one is the house agreeing with it.
#
# ── INPUTS ───────────────────────────────────────────────────────────
#   $ins    slurped [ insight rows, hydrated (.data, .state, .meta) ]
#   $tbl    slurped [ {source, lr: the whole evidence table} ]
#   $people $values $chats $lists   slurped row lists, for names only
#   $nows   epoch seconds — the reading's own now

def to_epoch:
  if . == null or . == "" then null
  else (tostring
        | (if (length == 10) then (. + "T00:00:00Z") else . end)
        | sub("\\.[0-9]+"; "")
        | sub("[+-][0-9]{2}:?[0-9]{2}$"; "Z")
        | (if test("Z$") then . else (. + "Z") end)
        | (try (strptime("%Y-%m-%dT%H:%M:%SZ") | mktime) catch null))
  end;

# a number with two decimals and its sign said out loud, because a
# movement of +2.13 and one of -2.13 are opposite readings and a reader
# skimming ten lines must not have to hunt for the minus
def sig: ((. * 100 | round) / 100)
         | (if . >= 0 then "+" else "" end) + tostring;

($tbl[0] // {}) as $T
| ($T.lr // {}) as $LR
| (($people[0] // []) + ($values[0] // []) + ($chats[0] // [])
   + ($lists[0] // [])) as $named
| (($LR.log_odds_clamp // 6) | tonumber) as $CLAMP
| (($LR.episode_intensity // 1.5) | tonumber) as $INTENSITY
| (($LR.solicited_discount // 0.25) | tonumber) as $DISCOUNT

# an about-row said the way a person would say it: the row's own name
# where the snapshot holds one, the bare address otherwise. Never a
# guess — an address nobody can resolve prints as an address, because a
# reading that invented a name would be citing something not there.
| def name_of($addr):
    ([ $named[] | select(.self == $addr)
       | ((.data.name // .data.statement // .data.title
           // .fields.name // .fields.statement // .fields.title // "")
          | tostring) ]
     | map(select(. != "")) | first) as $n
    | if $n == null then $addr else "\($n)  \($addr)" end;

  # ── the atoms ──────────────────────────────────────────────────────
  ([ ($ins[0] // [])[]
     | select((.state // "") != "dismissed")
     | ((.data.evidence_type // "") | tostring) as $ty
     | ((.data.cost // "") | tostring) as $cost
     # costly_action is the one cost-graded type: high and low are two
     # different numbers, and an absent cost reads as LOW — the
     # conservative direction, and the one worth being wrong in.
     # `cost: none` never reaches here; the create door refuses it.
     | (if $ty == "costly_action"
        then (if $cost == "high" then "costly_action_high"
              else "costly_action_low" end)
        else $ty end) as $key
     # a word the table does not carry weighs nothing and is skipped
     # rather than guessed at — a house that added a tenth word to the
     # kind and not to its recipe reads the nine it declared
     | select($ty != "" and (($LR[$key] // null) != null))
     | ((.data.episode // "") | tostring) as $ep
     | (if ($ep | test("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        then ($ep | capture("(?<d>[0-9]{4}-[0-9]{2}-[0-9]{2})") | .d)
        else null end) as $epday
     | (($epday // ((.meta.updated_at // "") | .[0:10])) | to_epoch) as $at
     | select($at != null)
     # rule 1: ln(LR), discounted where the house asked. solicited_praise
     # needs no discount — the type IS the discount, and applying both
     # would charge politeness twice.
     | (($LR[$key] | tonumber) | log) as $lg
     | (if ((.data.solicited // false) == true) and $ty != "solicited_praise"
        then ($lg * $DISCOUNT) else $lg end) as $w0
     | (($LR["half_life_" + $ty] // 180) | tonumber) as $hl
     | {self, ty: $ty, key: $key, at: $at,
        # AN ATOM WITH NO EPISODE IS ITS OWN EPISODE: one fact, once.
        # It is never folded with another, which is the safe direction
        # to be wrong in — the fold only ever holds a mover DOWN, so an
        # unfolded pair overstates rather than hides, and verify says so.
        ep: (if $ep == "" then ("(no episode) " + .self) else $ep end),
        bare: ($ep == ""),
        solicited: ((.data.solicited // false) == true),
        w0: $w0,
        hl: (if $hl > 0 then $hl else 180 end),
        finding: ((.data.finding // "") | gsub("\\s+"; " ") | .[0:100]),
        # THE ABOUT-ROWS ARE THE CITED ROWS, all of them. An atom that
        # cites a person and the thread it was said in moves both, which
        # is right: the fact is about the person AND about the
        # conversation, and slice 2's hypotheses will name which.
        rows: [ (.data.evidence // [])[]
                | select(tostring | test("^/api/")) ]} ]
  ) as $atoms

  # ── the fold, as a function of the clock (rules 2 and 3) ───────────
  # Given a list of one row's atoms and a moment, this is the whole
  # posterior movement of that row at that moment: rule 3 decays each
  # atom to `$t`, rule 2 keeps one per occasion, rule 1 adds and clamps.
  # Atoms that had not happened yet at `$t` are simply not there.
| def fold($as; $t):
    ([ $as[] | select(.at <= $t)
       | . + {w: (.w0 * pow(0.5; (($t - .at) / 86400) / .hl))} ]
     | group_by(.ep)
     | map((sort_by(.w | fabs) | last) as $strong
           | $strong.w * (if length > 1 then $INTENSITY else 1 end))
     | add // 0)
    | if . > $CLAMP then $CLAMP elif . < (0 - $CLAMP) then (0 - $CLAMP) else . end;

  ([ $atoms[] | . as $a | $a.rows[] | {row: ., a: $a} ]
   | group_by(.row)
   | map(.[0].row as $row
         | [ .[] | .a ] as $as
         | fold($as; $nows) as $now_fold
         | fold($as; $nows - 604800) as $then_fold
         | {row: $row,
            standing: $now_fold,
            moved: ($now_fold - $then_fold),
            n: ($as | length),
            fresh: ([ $as[] | select(.at > ($nows - 604800)) ] | length),
            # the occasions, said the way a reading would read them out,
            # NEWEST FIRST — by the occasion's own instant and not by
            # its name, because an episode string starts with a source
            # and sorting on that would order the week by thread id
            eps: ([ $as[] ]
                  | group_by(.ep)
                  | map({ep: .[0].ep, n: length, at: .[0].at,
                         label: ("\(.[0].ep) — "
                                 + ([ .[] | .ty
                                      + (if .solicited then " (asked for)" else "" end) ]
                                    | join(", "))
                                 + (if length > 1
                                    then " (\(length) facts, one occasion — counted once and \(($INTENSITY - 1) * 100 | round)% again)"
                                    else "" end))})
                  | sort_by(.at) | reverse)})
  ) as $movers

  # Ten, because a reading handed thirty numbers reads none of them.
  # Ranked by how far the row MOVED in seven days, which is what the
  # section is called; a row that stands somewhere and did not budge is
  # not news, and its standing total rides the line all the same.
| ($movers
   | map(select((.moved | fabs) > 0.005))
   | sort_by(.moved | fabs) | reverse | .[0:10]) as $top
| ([ $atoms[] | select(.at > ($nows - 604800)) ] | length) as $new_this_week

| {typed: ($atoms | length),
   source: ($T.source // "unknown"),
   new_this_week: $new_this_week,
   clamp: $CLAMP,
   bare_episodes: [ $atoms[] | select(.bare) | {self, ty: .ty, finding} ],
   movers: $top,
   all_movers: $movers,
   lines:
     (if ($atoms | length) == 0
      then [ "WHAT MOVED THIS WEEK — nothing yet: no finding in this house carries one of the nine evidence words, so there is no belief to move. A clerk types a fact as it indexes it (evidence_type, solicited, cost, episode); until one does, this section has nothing to say, and saying so is the whole of what it can honestly do.",
             "  (the table it would have weighed them by: \($T.source // "unknown"))" ]
      else ([ "WHAT MOVED THIS WEEK — computed on the fly from \($atoms | length) typed fact(s), \($new_this_week) of them new in the last 7 days, weighed by \($T.source // "unknown"). Nothing is stored anywhere; these are log-odds, clamped at \($CLAMP | sig), and a mover is an ABOUT-ROW rather than a claim — what the movement MEANS is yours to say:" ]
            + (if $new_this_week == 0
               then [ "  (nothing new was typed this week, so every movement below is the record FORGETTING — which is a real movement and worth reading as one)" ]
               else [] end)
            + ($top
               | map("  CLAIM-LESS MOVER: \(name_of(.row)) moved \(.moved | sig) this week (standing at \(.standing | sig)) — atoms: "
                     + ([ .eps[] | .label ] | join("; ")))
               | if length == 0
                 then [ "  (nothing moved as much as 0.01 either way this week — the record is steady)" ]
                 else . end)
            + (if (($atoms | map(select(.bare)) | length) > 0)
               then [ "  (\($atoms | map(select(.bare)) | length) typed fact(s) carry no episode, so each counts as its own occasion and is dated by when it was indexed — verify names them)" ]
               else [] end)) end)}
