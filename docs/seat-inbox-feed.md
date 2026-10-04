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
set it when two sittings on one machine share the script. Delete the
file to read from the sitting's start again.

## When it ends

On any status but 200 the feed prints one line and exits 1:

```
The inbox key lapsed (the door answered 401): sit again, with the same session, and start this feed with the inbox the sit answers.
```

Sit again with the same key, seat and session; the engine reuses the
open sitting and answers a new inbox key. A request that gets no
answer at all is not a status: the feed waits five seconds and asks
again.

The test is `waymark10/test/waymark10/inbox_feed_test.clj`: it runs
the script against a stub door.
