# Spec addendum — media: flickr, the first wired authority

**Parent:** [`spec-media.md`](spec-media.md). That spec named Jellyfin as a
presumed authority and honestly flagged the shapes as "named from their
public APIs, not from a probe of the LAN." The LAN has since answered: the
household's Jellyfin job is `count = 0`, and the media authority is
**flickr** — the household's own media engine at `https://stream.kopsa.info`
(source: `github.com/ckopsa/flickr`, deployment notes in
`home-infrastructure/terraform/docs/flickr-stream/`). This addendum is the
gtasks treatment the parent demanded: every shape below was verified against
the live API on 2026-07-28, not transcribed from documentation.

## Epistemic status

Verified live. The samples are real responses; the one on the feed even
carries a real household fact (an audience 1:19 into *12 Angry Men*). Two
verified gaps are recorded at the end: slug-form work keys pending the
authority's TMDB credential, and `file`-kind works excluded by decision.

## Why this authority is unusually easy

flickr was built on the same law as waymark — its playback decisions are
traces where the advertisement and the enforcement are one value — and its
self-description endpoints were shaped by the parent spec's constraints
without ever learning waymark's name:

- **It speaks works, not files.** "A show is not an episode" is enforced
  authority-side: `GET /api/works` collapses 2,233 files into 416 works
  (Ninjago is one work carrying 215 episodes). The mirror never sees an
  episode as a row candidate.
- **The fraction law is implemented at the source boundary.** Every
  progress fact arrives as the canonical fraction *beside the authority's
  own words* (`0.0137` beside `"1:19"`; `0.43` beside `"S02E05 · 12:30"`).
  Nothing to normalize here.
- **Audiences are the assignee pattern verbatim.** flickr profiles are bare
  names keying watch state; `:audience_name` maps with no translation.
- **It is the second cursor-bearing feed** — the parent expected Trakt to
  be second; flickr arrived first, and rides the `waymark-8si` cursor work
  exactly as intended.

## The authority, verified

| parent-spec column | flickr answer |
|---|---|
| media | movie, show |
| position fact | per-audience seconds/duration and furthest-episode, pre-derived |
| change feed | **yes** — `GET /api/feed/media?since=<cursor>` |
| note | speaks `tmdb:` ids when enriched → `:work_key` free; LAN-only ingress |

## Field mapping (live shapes)

`GET /api/works` element, verified:

```json
{"work_key": "movie:12-angry-men-1957", "kind": "movie",
 "title": "12 Angry Men", "year": 1957, "genres": [], "overview": "",
 "episode_count": 0, "item_count": 1, "representative_item_id": 51}
```

Feed doc adds the audience layer, verified:

```json
"audiences": [{"name": "Colton", "status": "active",
               "progress": 0.0137, "progress_text": "1:19",
               "updated_at": 1785133261.301}]
```

| `:media` field | flickr source | note |
|---|---|---|
| `:title`, `:year` | work `title`/`year` | enrichment-first, identity fallback, already resolved authority-side |
| `:medium` | work `kind` | `"movie"`/`"show"`; `"file"` kind excluded (punt below) |
| `:status` | audience `status` | flickr speaks only `active`/`finished` (≥0.9 law); `queued`/`abandoned` are hub-local — see the shield, below |
| `:creator` | — | flickr does not speak it yet; leave empty, healable via enrichment later |
| `:progress_text` / `:progress` | audience `progress_text`/`progress` | the fraction law, pre-obeyed |
| `:work_key` | work `work_key` | `tmdb:<id>` when enriched; slug form otherwise (gap recorded below) |
| `:audience_name` | audience `name` | profile names; no ref resolution needed |
| `:source` | `"flickr"` | |
| `:priority` | — | hub-local, `:partial`-shielded, flickr never learns it |
| `:source_href` | `https://stream.kopsa.info/#/item/<representative_item_id>` (movies) / `#/show/<title>` (shows) | hash deep links verified: reload lands correctly, the `:origin` affordance is real |

Poster, if the projection ever wants art:
`/api/items/<representative_item_id>/poster` (jpeg or 404).

## Discovery and the cursor

- **Initial sync:** cursorless `GET /api/feed/media` returns every work
  (verified: 416) plus `"cursor"` (e.g. `"l0.s43"`). The cursor is opaque —
  store and echo it, never parse it.
- **Cadenced pull:** `?since=<cursor>` returns only works with item- or
  playback-changes after the cursor, with per-audience progress recomputed
  fresh. Verified: current-cursor round-trip returns zero works; a single
  playback session surfaces exactly its one work. Mid-flight writes
  duplicate rather than drop — idempotent upsert absorbs this, the gtasks
  posture.
- **Deletions** are not tombstoned per-row: the authority bumps a resync
  mark and the feed signals full-resync, whereupon absence-against-the-full
  -list is `:on-gone` observed. Cheap at 416 works; revisit only if the
  library grows an order of magnitude.
- **Malformed cursor → 400**; treat as "resync from scratch."

## The shield, restated for this source

flickr owns *what happened* (positions, derived active/finished). The hub
owns *what is intended* (`queued`, `abandoned`, `:priority`, audience
assignment). No local write pushes to flickr today — `start`/`finish`/
`abandon`/`prioritize` are all hub-local for this source. The first honest
push target, when wanted, is already designed on the flickr side as
"explicit watched-marking" (its issue tracker: the `mark watched` note) —
until then, `finish` at the hub and `finished` from the feed are allowed to
disagree, and the disagreement is visible rather than resolved. The merge
law stays punted, exactly as the parent ruled.

## A parent punt, now observed

The parent recorded: *"one row holds one progress; two people mid-book at
different pages is real and unhandled; revisit on the first actual
collision."* **The collision has arrived**: flickr's feed emits per-audience
progress, plural, on the same work — that is the household's actual shape,
two profiles mid-show in different seasons. This addendum does **not**
redesign the row. The mapping rule is: a row whose `:audience` is set takes
that audience's entry; a row with no audience takes the most-recently
-updated entry. The richer fact stream is preserved upstream (flickr keeps
it all), so the future per-person design has its data waiting. The punt is
hereby marked *observed, healable, still punted*.

## Recorded gaps (verified, not guessed)

1. **Slug keys until the TMDB credential lands.** Without enrichment the
   authority emits `movie:12-angry-men-1957`-form keys. Slugs survive
   neither file renames (authority-side delete+add) nor cross-authority
   matching (Trakt will say `tmdb:`). The healing gesture is already
   specced by the parent: when the key upgrades, adopt the row by writing
   its `:work_key` — same move as the assignee ref. Until then, expect rare
   row churn on library reorganization.
2. **`file`-kind works are excluded.** flickr honestly projects
   unidentified media as per-file works (322 at verification, converging
   downward as its identity passes run). Mirroring them would flood the
   queue with inventory — the parent's "line between mirroring intent and
   rebuilding Jellyfin badly." Excluded by kind filter; revisit only if
   someone actually queues a home video.
3. **`genres`/`overview` arrive empty today** — same TMDB dependency as
   (1). The fields are already in the wire shape; they fill in without a
   mirror change.

## Effort

**Small — and this is the parent's "small per source thereafter" claim
taking its first test.** The pieces: one source namespace (gtasks-shaped:
cursored pull, canonical-doc translation, no push), the kind filter, and
config for the base URL. The fraction law, the audience pattern, and the
works grain all arrive pre-solved from the authority. If this source is not
small, the parent's effort curve was wrong and should be re-recorded.
