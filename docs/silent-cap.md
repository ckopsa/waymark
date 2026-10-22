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

## tests

Opts that name neither `:limit` nor `:all`:

- `factory10/test/factory10/orgkit_test.clj:99` — `(store/query-rows st tx kind {} {})))))`
- `waymark10/test/waymark10/batch_f_gin_test.clj:122` — `(store/search-rows st tx :f_gadget`
- `waymark10/test/waymark10/batch_f_memory_test.clj:359` — `(store/search-rows st % :task`
- `waymark10/test/waymark10/btree_index_test.clj:82` — `(store/search-rows st tx :bt_change [c] {})))))))`
- `waymark10/test/waymark10/delegation_test.clj:249` — `(store/query-rows (:storage eng) tx :seat`
- `waymark10/test/waymark10/delegation_test.clj:844` — `(store/query-rows (:storage eng) tx :seat`
- `waymark10/test/waymark10/lines_test.clj:316` — `(store/search-rows st tx :posting [] {})))`
- `waymark10/test/waymark10/mcp_served_test.clj:611` — `(store/query-rows (:storage eng) % :sitting_call`
- `waymark10/test/waymark10/mcp_sit_test.clj:1075` — `(store/query-rows (:storage eng) tx :held_call`
- `waymark10/test/waymark10/migrate_test.clj:161` — `(store/search-rows st tx :widget`
- `waymark10/test/waymark10/migrate_test.clj:168` — `(store/search-rows st tx :widget []`
- `waymark10/test/waymark10/org_boot_sit_test.clj:125` — `(store/query-rows st % kind {} {}))))`
- `waymark10/test/waymark10/postings_test.clj:100` — `(store/search-rows st tx :posting`
- `waymark10/test/waymark10/postings_test.clj:214` — `(store/search-rows st tx :posting [] {}))))`
- `waymark10/test/waymark10/process_sugar_test.clj:138` — `(store/query-rows (:storage eng) tx :pp_list {:plan_id pid} {}))))`

Opts handed on from a caller (the warning names that caller at run time):

- `waymark10/src/waymark10/test/factories.clj:190` — `(store/query-rows (:storage eng) tx kind`
- `waymark10/test/waymark10/lines_test.clj:113` — `(store/search-rows st tx kind conds (or opts {})))))`

## waymark10/src/waymark10/server

Opts handed on from a caller (the warning names that caller at run time):

- `waymark10/src/waymark10/server/definitions.clj:944` — `(store/query-rows st tx target-kind (or where {})`
- `waymark10/src/waymark10/server/invoke.clj:326` — `(store/search-rows (:storage engine) tx :posting`
- `waymark10/src/waymark10/server/invoke.clj:425` — `(store/query-rows`
- `waymark10/src/waymark10/server/invoke.clj:562` — `(store/query-rows (:storage engine) tx target-kind`
- `waymark10/src/waymark10/server/invoke.clj:833` — `(store/query-rows (:storage engine) tx target-kind`

## workqueue10

Opts that name neither `:limit` nor `:all`:

- `workqueue10/src/workqueue10/connections.clj:330` — `(store/query-rows st tx :connection`
