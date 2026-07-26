# Spec — media: the household's consumption queue

**Thesis.** RSS did not host the news; it named a canonical envelope — title,
guid, pointer, date — and every kind of content that fit it became one feed.
The household's media life (movies, shows, books, audiobooks, comics) is
five catalogs today and zero queues. The waymark move is the RSS move: **do
not build a library, mirror the intent.** One `:media` kind, one canonical
doc, every catalog an authority behind the confluence — the same declaration
that just made four task engines into one queue.

## Epistemic status

This is an integration spec in the gtasks lineage, written before any
authority is wired, and it carries two honest gaps:

1. **Which authorities this household actually runs is unverified.** The
   shapes below (Jellyfin, Trakt, Audiobookshelf, Komga, Open Library) are
   named from their public APIs, not from a probe of the LAN. Each one gets
   the gtasks treatment — verify against the live API before trusting the
   mapping table — when its turn comes.
2. **One framework question is forced here** (the way time-travel forced the
   bytes decision): whether the mirror admits an *authority-less* row. A
   recommendation heard at dinner has no catalog yet; it should still be a
   row. See "the hub authority," below.

Build the kind and one source when someone asks "what were we going to
watch?" and nobody can answer. The capture door alone may be worth building
first — a queue of intent is useful before any catalog syncs into it.

## What exists

- `workqueue10/confluence.clj` — `TaskSource` / `TaskListSource`. Nothing in
  the protocol is task-specific: sources speak canonical docs, push
  translates locally-born facts, discovery is cadenced. A second confluence
  instance over the same protocol, speaking a *media* doc, is instantiation,
  not invention.
- `resources/task.clj` — the template for every hard part, already worked:
  domain state as data (the mirror rule), `:partial` as the hub-local
  priority's shield, `:on-gone` as deletion observed, and the
  raw-text-beside-resolvable-ref pattern (`:assignee_name`/`:assignee`,
  `:list_key`/`:task_list`) that this spec leans on three times.
- `sources/gtasks.clj` — the first cursor-bearing feed and the
  `waymark-8si` pilot. Trakt's `/sync/last_activities` is the second real
  change feed on the horizon; this domain grows the cursor work rather than
  duplicating it.
- The growth direction itself, recorded: external integration grows, the
  mirror is the substrate. Five media catalogs is the direction cashed out.

## The canonical doc

One kind. The task queue's lesson stands — one list endpoint, one grantable
scope, one place to look — and RSS's lesson is the same lesson older: the
envelope unifies, the `:medium` tag differentiates.

```clojure
{:kind :media
 :schema
 [:title      string]
 [:medium     enum: "movie" "show" "book" "audiobook" "comic"]   ; filterable
 [:status     enum: "queued" "active" "finished" "abandoned"]    ; domain state IS data
 [:creator    open vocab]     ; author, director, showrunner — text, not a ref
 [:year       int]
 [:progress_text  string]     ; the authority's own words: "S02E05", "p. 213", "4h 12m"
 [:progress       float 0..1] ; the canonical projection beside the text (the fraction law)
 [:work_key   string]         ; the catalog-neutral identity, when spoken: "tmdb:603", "isbn:9780..."
 [:audience_name / :audience] ; WHO it's for — the assignee pattern verbatim
 [:source     enum of wired tags]
 [:priority   int, hub-local, :partial-shielded]  ; "what do we watch tonight?"
 [:source_href / :source_ui_href]}                ; hidden; the :origin link is the affordance
```

**The fraction law** (this domain's day-end-widening): every medium measures
position in its own unit — pages, seconds, episodes, issues — and none of
those units compare. Normalize to a fraction of the whole at the source
boundary, and keep the authority's own words beside it, untranslated. Page
213 of 400 and 4h12m of 11h30m rank against each other; the text is what a
person reads. A source that knows position but not extent (an ongoing show
has a moving denominator; a serial comic has no last issue) leaves the
fraction nil beside intact text — the gap renders, the same sentence
`:assignee` already earned.

**Status is the consumption lifecycle, not the sync machine.** `queued` →
`active` → `finished`, with `abandoned` as the honest exit. Abandonment is a
first-class state, not a deletion: a household that can see what it gave up
on stops re-queueing it. The machine on the kind remains the sync machine,
exactly as `task.clj` declares.

**Local writes.** `start`, `finish`, `abandon` (each a status write, pushed
where the authority has an opinion — Trakt history, Audiobookshelf progress),
`log_progress` (position, hub-recordable for the media no service tracks —
paper books, borrowed comics), and `prioritize`/`deprioritize` copied from
the task kind under the same `:ranker` role. Nothing else. Ratings and
reviews are a different feature wearing this one's coat; see punts.

## The authorities, sketched

| authority | media | position fact | change feed | note |
|---|---|---|---|---|
| Jellyfin | movie, show | playback ticks / runtime | no (poll) | speaks tmdb/tvdb ids → `:work_key` free |
| Trakt | movie, show | watched history, watchlist | **yes** — `/sync/last_activities` | the second `waymark-8si` feed; watchlist accepts writes → `create-push` candidate |
| Audiobookshelf | audiobook | seconds / duration | no (poll) | progress API is read-write; `finish` pushes |
| Komga / Kavita | comic | page / pages per book | no (poll) | series-vs-issue is the hierarchy punt, again |
| Open Library | book | none — catalog only | — | identity donor (`isbn:`/`olid:`), not a source |

Verified knowledge worth recording now: **the Goodreads API is dead**
(retired 2020, no new keys). Book *progress* has no good remote authority
unless the household runs Hardcover or StoryGraph exports; paper books are
the strongest case for `log_progress` being hub-local law rather than a
push.

## The hub authority — the one decision this spec forces

A task is born here and pushed to the engine that will own it
(`create-push`, the "todo" default). A media row often has **no owner to
push to**: the recommendation, the paper book, the borrowed comic. The
capture door must accept a birth that stays here.

Two shapes, one to choose:

1. **A noop source.** `:source "hub"` wired to an authority that discovers
   nothing, answers every pull with the stored doc's own etag, and takes
   every push as `:noop`. The mirror machinery never learns a special case;
   hub rows are merely rows whose authority always agrees. Cheap, slightly
   dishonest — `unreachable` can never mean anything for them.
2. **A mirror that admits authority-less rows.** A real framework change:
   `:source nil` rows skip the sync machine entirely. Honest, and priced at
   touching `mirror.clj` for one domain's convenience.

Recommendation: **shape 1.** It is one namespace of about twenty lines, it
keeps `mirror.clj` untouched, and if a real book authority ever arrives, hub
rows can be adopted by writing their `:work_key` — the same healing gesture
the assignee ref already performs when a member arrives.

## Two things that are easy to get wrong

**One work, two authorities.** Nothing in the task domain ever had this: the
same movie sits in Jellyfin *and* on the Trakt watchlist, and naive
discovery makes it two rows. RSS solved it with `guid`; here the guid is
`:work_key`, and the honest first cut is: **dupes are visible, not
resolved.** Store the key whenever an authority speaks one, filter on it,
and let a later merge law (a real design, involving which source wins which
field) be written against observed collisions rather than imagined ones.
Resist inventing the merge now — this is the subtasks punt's shape: revisit
when the household actually collides.

**A show is not an episode.** The row is the *unit of intent* — the thing
you would say out loud when queueing: a movie, a show, a book, an
audiobook, a comic series-or-volume as the household actually reads it.
Episodes, seasons, and issues live inside `:progress_text`/`:progress`,
never as rows. The moment an episode becomes a row, this kind becomes a
catalog, the doc count goes up two orders of magnitude, and the queue
drowns in its own inventory. This is the line between mirroring intent and
rebuilding Jellyfin badly.

## Recorded punts

- **The merge law.** Above. `:work_key` is stored from day one so the punt
  is healable, not fatal.
- **Ratings and reviews.** Opinion after the fact, not intent before it —
  a different lifecycle. If wanted, a sibling kind, not fields here.
- **Hierarchy.** Seasons, book series, comic runs. Flattened into the intent
  unit; revisit only if the household queues at a different grain than it
  speaks.
- **Availability.** "Where can we stream this tonight?" is a live lookup
  against a licensing landscape that shifts weekly — a client's question at
  render time, never a stored fact that can stale.
- **Recommendations from taste.** The AI is a client, not a service — the
  mealplan law holds here too. An agent with the grant can read the
  finished/abandoned record and propose; the engine stores intent, not
  taste.
- **Kids' profiles / per-person progress.** One row holds one progress. Two
  people mid-book at different pages is real and unhandled; the audience ref
  names who it's *for*, not who is *where*. Revisit on the first actual
  collision.

## Effort

**Medium for the kind + hub source + first real authority**, then small per
source thereafter — the gtasks curve. The fraction law and the noop-source
decision are the only genuinely new thought; everything else is the task
confluence, instantiated a second time. Trakt, when it comes, rides the
`waymark-8si` cursor work rather than growing its own.
