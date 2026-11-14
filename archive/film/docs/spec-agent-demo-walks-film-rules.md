<!-- Moved from docs/spec-agent-demo-walks.md, § 8d, as it stood at 529270e4 (Film archive 3 of 3). -->

### 8d. The rules a film is scored by (waymark)

Each measured rule of a good demo is a `film_rule` row
(`server/film_rules.clj`), so a critique a person makes of a film
becomes a row and an agent can meet the scorecard without seeing the
film. A rule carries `name` (a slug, unique), `metric`, `op` (`>=`,
`<=` or `=`), `threshold` (a number), `scope` (`film`, `shot` or
`beat`; with `shot`, an optional `role`: `friction`, `turn` or
`payoff`), `severity` (`fail` or `warn`), `why` (one sentence) and
`origin` (a person's words, quoted, or `craft`). Two more fields are
optional. `output` (`phone` or `desktop`) is a filter like `role`: the
rule is scored only on a film whose take says that output in
`film.output`, and a rule with no `output` is scored at any. `unless`
(`zoom`, with the scope `shot` only) names the shot property that
exempts a shot: a shot whose take says `shot.zoom` is true passes the
rule whatever it measures. `film-rules/verdict` judges both, and
answers `:unscored`, `:pass` or `:miss`. Its states are
`active` and `retired`. A person or the sitter of a domain's mayor
seat makes one; only a person retires one, and only a person restores
a retired one.

The metric vocabulary is closed (`film-rules/metrics`). A rule that
names any other metric is refused, and the refusal lists these. Each
metric is read from one field of the take:

| Metric | What it measures | Source in the take |
|---|---|---|
| `frame_fill` | The content box over the frame. | `film.content_box` over `film.frame` |
| `focus_share` | The focus box's area over the viewport. | `shot.focus_box` over `shot.viewport` |
| `type_px` | The focus text's size at output pixels. | `shot.focus_type_px` |
| `contrast` | The focus text's WCAG contrast ratio. | `shot.focus_contrast` |
| `read_time_ratio` | The hold over the time the words take at 3 a second. | `shot.hold_s` over `shot.words / 3` |
| `dead_air_s` | The longest span in which nothing changes. | `film.dead_air_s` |
| `surfaces_changed` | The named surfaces that changed in a shot, besides the pointer. | `shot.surfaces_changed` |
| `chrome_leaks` | The count of `/api` paths, uuids, raw snake_case keys or `[epic]` in visible text. | `film.chrome_leaks` |
| `arc` | 1 when the shot roles run friction, turn, payoff and the last shot's goal state is done; else 0. | `shot.role` in order, and the last shot's `goal_state` |
| `runtime_s` | The film's length in seconds. | `film.runtime_s` |
| `frame_match_min` | The lowest score of a film frame against the still of the beat its caption is for, over every caption run. | the lowest `run.frame_match` |
| `bare_lists` | The day captions that list three or more things, split by `, `, and say no verb of the day phrasing (`went`, `was`, `were`, `came`, `chased`, `signed`, `sent`, `asked`, `paid`). | the day captions |
| `quiet_days` | The day captions that end in `A quiet day.` | the day captions |
| `asked_person` | The day captions that say `was asked`. | the day captions |
| `week_bare_lists` | The day captions and the cards (`shot.card`) of a first week that are bare lists, as `bare_lists` counts them. | the day captions, `shot.card` |
| `busy_days` | The days of the week's calls that hold a move; with no calls, the day captions that do not end in `A quiet day.` | the week's calls, else the day captions |
| `arc_moves` | The arc's roles (0 to 3) whose day holds a move on a kind other than `film.kind`: a move of the week's calls on the role shot's day, else, for a week the clone's clock played, a day caption that does not end in `A quiet day.`, else a `shot.meanwhile` move. | the week's calls and `shot.day`, else the day captions, else `shot.meanwhile`; `shot.role`, `film.kind` |
| `person_asks` | The week's calls that were held; with no calls, for a week the clone's clock played, the day captions that say `was asked`; else the meanwhile calls held for a person, or moved by the person (`film.cast`'s first) through a door a person holds. | the week's calls, else the day captions, else `shot.meanwhile` and `film.cast` |
| `week_runtime_s` | The length in seconds of a live first week. | `film.runtime_s` |
| `day_one_shown` | How many of a seat page, a sitting page and a call (0 to 3) the shots of day 1 of a live first week show: the seat that works the queue and a sitting of it at work. | `shot.open` of a `seat` and of a `sitting`, and a `shot.call` or `shot.meanwhile`, on `shot.day` 1 |
| `unpressed_person_calls` | The week's calls as the person that no press of their day plays: on each day, the person's calls beyond the day's presses. | the week's calls, `shot.press`, `shot.pressed` |
| `short_cards` | The cards of a live first week held under 1.5 s and 60 ms a word. | `shot.card`, `shot.hold_s` else `shot.hold.ms` |

`bare_lists`, `quiet_days` and `asked_person` are the first week's
caption metrics. A day caption is a `shot.caption` that opens with a
weekday name (`Monday` to `Sunday`), and each of the three counts the
day captions of the take that hold it. A take is a first week when its
scene says `first_week: true` or a shot of it carries one of the arc's
roles, `friction`, `turn` or `payoff`. A rule on any of the eleven
week metrics, the table's last eleven rows, is `unscored` on a take
that is no first week. The three are `unmeasured`, with `missing`
naming `shot.caption`, on a first week with no day caption. On a take
whose scene says `first_week: true`, `arc` is `unscored`: a first week
pins no quest, and `week-arc` judges its story.

`week_bare_lists`, `busy_days`, `arc_moves` and `person_asks` are the
played week's (tickets 853b6707 and 4b7af799). They read the week's
calls: the plan's `dry_week`, the scripted week the audit played,
which forge hands in beside the take, else the calls the take's own
`week.calls` recorded as the camera rolled. Each call is
`{day, as, kind, action, outcome}`, and a move is a call whose
`outcome` is `moved` or `held` (a held call is the house moving to the
person). A shot's day is the `n` of the scene's last `day` shot at or
before it (`shot.day = {n}`, which line-up copies onto the take), else
how many day captions came up to it. `arc_moves` counts a move on any
kind when the take names no `film.kind`.

With no week's calls, a week the clone's clock played
(`scene.clock_days: true` and no meanwhile shot) is read from its day
captions, as the table says, and any other take from its meanwhile
shots. A meanwhile shot is one of the director's calls,
`shot.meanwhile = {as, kind, action, outcome, held_by}`: `outcome` is
`moved`, `held` or `refused`, and `held_by` is who holds the door the
call named. A day caption's day is the calls since the day caption
before it; a role shot that is no day caption, as the closing, reads
the last day and the calls since. `film.kind` is the kind the world
knocks on and `film.cast` the scene's cast, the person first. A take
with no week's calls, no clock-played mark and no meanwhile shot has
nothing to read `busy_days`, `arc_moves` and `person_asks` from, and
each is `unscored`, not a miss; `week_bare_lists` is `unscored` on a
take with no day caption and no card. A meanwhile shot is a call, not
a frame: a shot rule leaves it `unscored`, and `arc` does not read it.

`week_runtime_s`, `day_one_shown`, `unpressed_person_calls` and
`short_cards` are the live first week's (ticket 4b7af799): a first
week played as the camera rolls, whose shots carry the scene's `day`
shots. They are `unscored` on any other take, and
`unpressed_person_calls` is `unscored` too on a take with no week's
calls. A press is the scene's `shot.press`, or a press the renderer
reports in `shot.pressed`. A card is held for its `shot.hold_s`, else
the scene's `shot.hold.ms`; `short_cards` is `unmeasured`, with
`missing` naming `shot.hold_s`, when a card carries neither, and
`day_one_shown` is `unmeasured`, with `missing` naming `shot.day`, when
no shot is on day 1.

A take is a map with `film`, the fields read once, `shots`, one
map per shot in order, and `runs`, one map per caption run in order. A
box is `{x, y, w, h}`; a frame and a viewport
are `{w, h}`. `surfaces_changed` is a count or the list of names. A
run carries its `caption` and `frame_match`: one score, or the scores
by frame, as `{held, mid}`, where each one counts.
`film-rules/measure` reads one metric from a take by these names: one
number for a film metric, and one number per shot for a shot metric.
`film.output` is `phone` or `desktop`, `shot.zoom` is true on a shot
that asks for a zoom, and a shot may carry its `caption`.

`POST /api/film_rules/-/judge` is the door that judges a take
(`film-rules/judge`). The kind declares it under `:collection-doors`,
the key for a safe door on a collection, so `GET /api/film_rules` names
`judge` under `actions` with its method, href, input schema and
`safety`, the OpenAPI document lists the route, and `waymark_invoke`
with the kind, the action `judge`, the input and no id answers what the
route answers. It is safe: it reads the rules and writes
nothing, and whoever may read `film_rule` rows may call it. Its input
is `{take: {film, shots}}` in the field names above. Its answer is
`{verdict, unmeasured, rules}`. `rules` holds one entry for each active
rule, in order of name: the rule's `name`, `metric`, `op`, `threshold`,
`severity` and `why`, and a `verdict`:

- `pass`: the value holds, or the shot is exempt.
- `miss`: the value does not hold.
- `unscored`: a filter leaves the rule out, as `output` does for
  `type-size` on a desktop film.
- `unmeasured`: the take lacks a field the metric needs. `missing`
  names each one, as `film.dead_air_s`. An unmeasured rule is not a
  pass by default: each entry carries the rule's `unmeasured`, what
  such a verdict counts as, which is the rule's own `unmeasured`
  (`miss` or `pass`), else `miss` for a fail rule and `pass` for a
  warn one (ticket 179f7824).

A scored film rule carries its `value`. A rule read from each shot
misses when any shot misses, and carries `worst`: the `index`,
`caption` and `value` of the shot that misses by the most, or of the
measured shot nearest to a miss when none does. A rule read from each
run holds the lowest score over the runs against the threshold, and
carries that `value` and `worst`: the `index`, `caption` and `value` of
the run that carries it. It is unmeasured when the take has no `runs`
(`missing` names `runs`) or a run has no score (`run.frame_match`),
unless a measured run already misses. The overall `verdict`
is `red` when a `fail` rule misses, else `warn` when any rule misses,
else `green`; `unmeasured` lists the names of the unmeasured rules
beside it. The scorer builds the take from its measurements and reads
this answer; it holds no operator, filter or exemption of its own.

The boot seed (`film-rules/ensure-seed-rules!`) runs when an engine
starts (`engine/start-runtime!`), in every application, because the
kind is enrolled in every one. It makes twenty rules,
each once by its name: `frame-fill` (`frame_fill >= 0.95`, fail),
`chrome-leaks` (`chrome_leaks = 0`, fail), `type-size`
(`type_px >= 28` per shot, `output: phone`, warn), `read-time`
(`read_time_ratio >= 1.2` per shot, warn), `dead-air`
(`dead_air_s <= 0.8`, warn), `one-surface` (`surfaces_changed <= 1`
per shot, warn), `focus-share` (`focus_share >= 0.25` per shot,
`unless: zoom`, warn), `arc` (`arc = 1`, fail), and the first week's
three: `caption-not-list` (`bare_lists = 0`, fail), `quiet-days`
(`quiet_days <= 2`, fail) and `asked-person` (`asked_person >= 1`,
fail), and the played week's four, each fail: `no-bare-list`
(`week_bare_lists = 0`), `busy-week` (`busy_days >= 5`), `week-arc`
(`arc_moves = 3`) and `asks-the-person` (`person_asks >= 1`), and the
live week's five: `week-long-enough` (`week_runtime_s >= 90`, warn),
`week-short-enough` (`week_runtime_s <= 150`, warn), `day-one-shows`
(`day_one_shown = 3`, fail), `person-calls-pressed`
(`unpressed_person_calls = 0`, fail) and `cards-held`
(`short_cards = 0`, warn). The seed makes a
rule only when none carries its name. An active `type-size` or
`focus-share` row made before these fields existed gains the field
the seed names at the next boot, through `restate`; a value a row
carries is not written over, and a retired row is left as it is.

Whoever may make a rule may restate its `output` and `unless`: a
person, or the sitter of a domain's mayor seat. A seat with no right to
make a rule is refused. Such a restate writes the caller's id in
`restated_by`; the boot seed's own restate does not. The seed leaves a
rule that carries `restated_by` alone, so a `type-size` a person
restated to no `output` stays that way at the next boot.
