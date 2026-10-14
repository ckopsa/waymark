# The factory kit

`factorykit/` holds what every factory's kinds share. A factory is an
engine that turns tickets into merged changes: `factory10/` in this
repository is one, and a factory outside it depends on the kit by
`:git/url` with `:deps/root "factorykit"`.

Each kit namespace gives a `core` and a `build`. The core is data: the
part of a kind every factory has. `(build core extension)` answers the
map `waymark10.dsl/defresource` takes, where the extension is the
factory's own defresource map without the core's part. An extension
that names what the core already named is refused when the namespace
loads.

## The repository policy (`factorykit.repo-policy`)

A repo_policy row says what submit means in one repository. A person
states it, and the engine writes beside it what the bench and the
forge did with it.

### What the core holds

| Part | The core's |
| --- | --- |
| Machine | `active` and `retired`, born `active`, and no tomb |
| Index | one row for each `repository` |
| Stated fields | `repository`, `branch_pattern`, `base`, `required_checks`, `rounds_per_change` |
| Offered fields | `forge`, `workspace`, `forge_repo`, `advisory_checks` |
| Doors | the machine of `restate`, `retire`, `restore` and `note_source` |
| Guard | `a-person-or-their-delegate-states-the-policy` |

An offered field is on a factory's policy only when the extension's
`:takes` names it. All four are optional, so a factory that takes one
later restates no row.

The guard refuses a model that acts for nobody. A factory names it on
its create door and on the three doors a person walks.

### What the extension gives

| Key | Meaning |
| --- | --- |
| `:policy` | the factory's own stated fields, as schema entries |
| `:takes` | the names of the offered fields it has |
| `:policy-order` | the order of the stated fields on the form; a field it does not name follows |
| `:engine` | the fields the engine writes and no form offers |
| `:actions` | its own doors, and for a core door the guards, the handler, the safety and the words |
| `:expected-age` and the other cracks law | its own; the core states none |
| every other key | passed through to `defresource` |

`build` writes `:schema` (the stated fields, then the engine's),
`:create-schema` (the stated fields), and the `restate` door's
`:input` and `:edit` prefill (the stated fields again). An extension
that names `:states`, `:initial`, `:terminal`, `:schema`,
`:create-schema`, a core door's `:from`, `:to` or `:undo`, or
`restate`'s `:input` or `:edit` is refused.

`(statement core extension)` answers the stated fields alone, for a
handler that must know which fields a restatement replaces.

### factory10's extension

`factory10.resources.repo-policy` takes no offered field. Its own
stated fields are the clone URL, the size ceiling, the merge (who
merges, the method, the strategy, the train and the waits), the
deploy check, the groom floor, the formatter, the deny list, the test
workflow, the check step, the hosted workflows and the orientation
document. Its engine fields are the enrolment, the forge pass, the
base branch's state, the deploy wait and the merge line. Its two
hidden doors, `mark_enrolled` and `note_base`, are its own.
