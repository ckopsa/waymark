# Film, archived

Film was a demo written as shots: a scene listed what the camera saw and
did, a take was one performance of it, and the film rules scored whether
a take was a good film. It was used to film an org's simulated first
week, so a person could watch the week before merging the org's plan.

Colton archived it on 2026-11-14, because no org plan ever landed a
film. Nothing under this directory is on a classpath or in CI. Each file
keeps the path it had in the repository, below `archive/film/`.

The film tests were deleted, not moved; git history keeps them:

- Film archive 1b (pull request 2546, commit 8a832c6a) deleted
  `factory10/test/factory10/first_week_test.clj`,
  `org_film_pass_test.clj` and `org_plan_refilm_test.clj` (last at
  e2a70171).
- Film archive 1d deleted `factory10/test/factory10/open_plans_scenes_test.clj`,
  `scene_check_test.clj` and `scene_doc_test.clj`; they are last whole at
  a6ae2114, the commit before it. It also removed the `open-plans-scenes`
  CI job.

- Film archive 2 of 3 took `scene`, `take` and `film_rule` out of the
  engine and deleted `waymark10/test/waymark10/film_rules_test.clj` and
  `scenes_test.clj`; they are last whole at a9de592f, the commit before
  it. The film rules are kept as data in `rows/film_rules.edn`: the 20
  the engine seeded and `frame-shows-beat`, which was only a row on the
  home server. Stored `film_rule`, `scene` and `take` rows and their law
  rows remain in the database, for a person to clear if they wish.

Film archive 3 of 3 adds the engine's film files (`scene`, `take` and
`film_rule`) to this directory.
