# Quiet mode

`WAYMARK10_QUIET=1` (also `true` or `yes`) boots an engine that serves
and reads, but sends and writes nothing outside. It is for the green
half of a blue/green pair: a second engine on its own database, kept in
sync from the live one by logical replication. Singleton passes are
elected with `pg_advisory_lock`, which is per database, so a green
engine on its own database would believe it is the only one and act on
the world twice. Quiet mode lets green boot, serve and be checked end
to end before cutover.

## What it does

- **The background passes do not start.** The choke point is the
  runtime walker (`waymark10.server.runtime/start-hooks!`). In quiet
  mode a lifecycle hook starts only when its row in
  `waymark10.modules/inventory` declares `:quiet :serves` — a surface
  that serves requests and neither writes nor sends. Today that is the
  event dispatcher, the law refresh, the curtain, presence and
  intents. Every other hook — the clock sweeper, the notifier, the
  webhooks deliverer, the seat clock and wakes, scheduled actions, the
  jobs worker, mirror discovery, retention and the rest — is held
  back. A new hook is held back too unless it says otherwise, so a new
  pass is quiet by default.
- **The boot seeds do not run.** `start-runtime!` skips the default
  work calendar and the film rules seed.
- **Boot says so.** One line on stderr:
  `waymark10: QUIET MODE, nothing leaves this engine (WAYMARK10_QUIET)`,
  then one more naming, with a count, every pass that would have
  started.
- **`GET /healthz` reports it**: `{"ok": true, "quiet": true}` as JSON,
  in place of the plain `ok`.

Unset, nothing changes: every hook starts and `/healthz` answers `ok`.

An engine map carrying `:quiet?` answers for itself, which is how the
tests set it (`waymark10.server.quiet/quiet?`).

## What it does not do yet

Quiet mode holds back the background. A request made to a quiet
engine is still served as usual, a write included, and so is any
outbound call a request makes. Keep green out of traffic until
cutover.
