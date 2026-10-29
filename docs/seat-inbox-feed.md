# The inbox feed of a seat

`seat/inbox-feed.sh` is the one client of the inbox door
(`docs/spec-seat.md` R-12.38). Every seat repository uses this copy
and keeps none of its own. It needs `bash`, `curl` and `python3`.

## Start it right after the sit

The sit answers `inbox {url, key}`. Start the feed as a Claude Code
Monitor with those two values, before the first move:

```bash
bash seat/inbox-feed.sh <inbox url> <inbox key>
```

Each line the Monitor reads is one batch:

```
2 events: ticket 3ad250ef claim open->claimed by infra-seat: Music Assistant | change eb592ac3 submit open->review by code-seat: Seat feeds 3
```

An event reads `kind id8 action from->to by actor_name: summary`. The
`by actor_name` part is there when the door's line carries the name.

An event ends with ` ⇒ cue` when the door's line carries a `cue`. A
cue is a standing note that the seat's `inbox.cues` attaches to the
events it matches, and it says what the seat does about the event
(spec-seat.md R-12.38). A cue does not make an event urgent:
`--urgent` decides that.

## Only the held calls the seat filed

A seat that should hear its own held calls, and no other seat's, says
so on its inbox:

```json
{"inbox": {"held_call": "own"}}
```

The inbox then hears every action on a held call whose `caller` is the
seat's sitter (`seat:<seat id>`): its create, and the person's allow,
refuse and land. It hears these even when the seat itself made the
move. It hears no held call another seat or a person filed. The switch
adds held_call to `only` when `only` does not name it, and it hears
every action of the kind. The engine judges it as a `filter` on
`caller`, through the same matcher a cue's filter uses
(`docs/spec-events.md` E-1.4).

## Options

| option | default | meaning |
|---|---|---|
| `--batch N` | 10 | print the batch when it holds N events |
| `--every SECONDS` | 60 | print the batch this long after its first event |
| `--urgent kind[:action],...` | `held_call,approval_request,change:fail,change:stall` | an event of this kind, or of this kind and action, prints the batch at once |

The door holds one request 25 seconds at most, so the batching is in
the client.

## The cursor

The feed keeps the door's `Waymark-Inbox-After` in the file
`.inbox-feed.after` beside the script, so a restart resumes where the
last printed batch ended. `WAYMARK_INBOX_CURSOR` names another path;
set it when two sittings on one machine share the script.

With no cursor file the feed begins at the present: it asks the door
for `after=now` one time, and tails from the event that answer names.
A sitting can be open for days, and its history is not replayed. To
read history, write the event to read from in the file first (`0` is
the whole log).

## When it ends

On a 401 or a 403 the feed prints one line and exits 1:

```
The inbox key lapsed (the door answered 401): sit again, with the same session, and start this feed with the inbox the sit answers.
```

Sit again with the same key, seat and session; the engine reuses the
open sitting and answers a new inbox key.

Any other status but 200 (a 5xx, a 429), and a request that gets no
answer at all, says nothing about the key: an engine deploy answers
503 for a moment. The feed waits 5 seconds, then twice as long each
time up to 60, and asks again with the same cursor. When it has waited
longer than 2 minutes it prints one line, and keeps waiting:

```
The inbox door has been down for more than 120 seconds (it last answered 503): the key is not refused, so the feed keeps waiting and asks again with the same cursor.
```

`WAYMARK_INBOX_RETRY` is the first wait in seconds and
`WAYMARK_INBOX_DOWN` the seconds before that line; the test sets both.

The test is `waymark10/test/waymark10/inbox_feed_test.clj`: it runs
the script against a stub door.
