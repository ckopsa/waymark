# The silent cap: sites left to convert

Written by `waymark10.silent-cap-test` (ticket 2636da5a) each time it
runs; do not edit it by hand. A `query-rows` or `search-rows` call
that names neither `:limit n` nor `:all true` still gets the store's
page of 100, and logs a `[silent-cap]` warning naming its call
site. Each directory's ticket converts its sites — an aggregate goes
to `count-matching`, `sum-matching` or `sum-amounts`; a listing page
keeps a `:limit` with a one-line reason — and empties its part of
this list. The last ticket turns the warning into a refusal once
the list is empty. Calls through an engine's `:find` (a ctx's, or
`find'`) are not grepped here: their warning names them at run time.

## waymark10

Opts that name neither `:limit` nor `:all`:

- `waymark10/src/waymark10/dev.clj:246` — `(store/query-rows st tx kind {} {}))))))`

## waymark10/src/waymark10/server

Opts that name neither `:limit` nor `:all`:

- `waymark10/src/waymark10/server/scenes.clj:947` — `(store/query-rows st tx (keyword kind) {} {})`
- `waymark10/src/waymark10/server/scenes.clj:1305` — `(store/query-rows st tx :seat {:name nm} {})]`
- `waymark10/src/waymark10/server/seed.clj:781` — `(store/query-rows st tx :model {} {})))]`
- `waymark10/src/waymark10/server/seed.clj:858` — `(store/query-rows st tx kind {} {}))))))`

Opts handed on from a caller (the warning names that caller at run time):

- `waymark10/src/waymark10/server/collections.clj:934` — `(store/query-rows st tx k where opts))]`
- `waymark10/src/waymark10/server/grants.clj:2190` — `(store/query-rows st tx kind {(first branch) %} opts))`
- `waymark10/src/waymark10/server/grants.clj:2195` — `(store/query-rows st tx kind {} opts)))))`
- `waymark10/src/waymark10/server/invoke.clj:326` — `(store/search-rows (:storage engine) tx :posting`
- `waymark10/src/waymark10/server/invoke.clj:425` — `(store/query-rows`
- `waymark10/src/waymark10/server/invoke.clj:562` — `(store/query-rows (:storage engine) tx target-kind`
- `waymark10/src/waymark10/server/invoke.clj:833` — `(store/query-rows (:storage engine) tx target-kind`
- `waymark10/src/waymark10/server/mirror.clj:1534` — `(query-rows pages by LIMIT only — no offset — so a loop cannot`
