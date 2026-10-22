# The silent cap: sites left to convert

Written by `waymark10.silent-cap-test` (ticket 2636da5a) each time it
runs; do not edit it by hand. A `query-rows` or `search-rows` call
that names neither `:limit n` nor `:all true` now throws: the store
refuses it with a `[silent-cap]` error naming its call site, rather
than answer a page of 100. An aggregate goes to `count-matching`,
`sum-matching` or `sum-amounts`; a listing page keeps a `:limit` with
a one-line reason. The entries left below are pass-through sites,
where the caller's opts decide. Calls through an engine's `:find`
(a ctx's, or `find'`) are not grepped here: their refusal names them
at run time. A call that hands its opts on is judged at its callers.
