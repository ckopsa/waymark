# The org kit

An org is code-first: a thin directory of declarations that a seat
writes and the factory builds. Boot, conformance, onboarding and
clones all read this one shape. `orgkit/template/` is the starter
directory every org copies; `orgs/example/` is a filled copy, a tiny
two-kind org with one seat.

## The shape

```
orgs/<org>/
  deps.edn                 waymark10 and factorykit by local root
  src/<org>/org.clj        the module: resources, check-resources, domain
  src/<org>/kinds/*.clj    one defresource per kind, guards as defguard sentences
  resources/<org>/seed.edn what a fresh engine holds at boot
  README.md                what each file is
```

To start an org, copy `orgkit/template/` to `orgs/<org>/` and replace
every `myorg` with the org's name: the directories under `src/` and
`resources/`, the namespaces, the domain keyword and the seed. The
`deps.edn` roots are relative to `orgs/<org>/`, so a copy keeps them.
`clojure -M:check` in the org's directory runs the declaration gate,
and `factory10.orgkit-test` loads the template and the example.

## Naming rules

- The org name is the namespace root and the domain name: org
  `example` is `example.org`, `example.kinds.*`, `resources/example/`
  and the domain `:example`. It is snake_case: `[a-z][a-z0-9_]*`.
- A kind name is the org's to choose, but it may not be one the
  engine already enrols (`job`, `member`, `grant`, `seat`, `ticket`
  and the rest of `waymark10.modules/inventory`): the registry holds
  one law per kind. That is why the example's work is a `work_order`.
- The module is `<org>.org`, and it declares `resources` (its kinds,
  stamped by `in-domain`) and `check-resources` (zero-arg, for the
  gate).

## The seed

`resources/<org>/seed.edn` is the demo seed's grammar
(`docs/spec-demo-clones.md` § 1), with an org's own sections beside
it:

- `:seed`, `:version` and `:org`, the org's name.
- `:domains`: each `{:name, :label}`; the first is the org's own.
- `:cast`: the members, as in the demo seed.
- `:seats`: each a factorykit seat template and its params,
  `{:template "mayor" :params {...}}`, or a whole seat map under
  `:seat` when no template walks the org's kinds.
- `:judgments`: each a factorykit judgment template and its params.
- `:steps`: the starting rows, each an ordinary create or invoke by a
  cast member, as in the demo seed.

The loader that walks it on a fresh engine is M1b's.

## Conformance

`factorykit.conform` judges an org the way the engine would:
`(conform org-dir)` answers findings, each `{:key :severity :class
:subject :sentence :remedy}`, and an org passes when it answers none.
It loads the module, fills the seed's seats, runs the defresource and
registry checks, the cracks audit over the kinds and the seed's seats,
the sentence checks on guards and confirm doors, a memory engine's
discover, schema, query, get and dry-run create for every kind, and
the seats' scopes against the kinds the engine serves.
`clojure -M:conform` runs it in an org's directory, and
`clojure -M:conform ../orgs` in `factorykit/` runs it over every org;
CI runs the latter.

## What an org may not do

- No engine code: an org declares kinds, guards and its seed. A
  framework gap is a ticket against `waymark10`, not a workaround in
  the org.
- No new top-level deps beyond the kit: `deps.edn` names Clojure,
  waymark10 and factorykit and nothing else.
