(ns waymark10.modules.hooks
  "The spine's named hooks (docs/core-tiers.md, core tier slice 1): the
  door through which a core namespace asks a module for something only
  the module's kinds know, instead of reading those kinds by name.

  A module registers a hook when its namespace loads:

      (hooks/register-hook! :gate-policy-for-repo policy-for-repo)

  and the spine calls it by name:

      (hooks/hook :gate-policy-for-repo eng repository)

  When no loaded module registered the hook, or the engine asking (the
  hook's first argument) did not assemble the module that owns it, the
  call answers the hook's documented default from `defaults`, so an
  engine assembled without the module behaves as one that never
  declared its kinds.

  This is not `waymark10.modules`'s `:hooks` column: those are
  lifecycle hooks the engine starts and stops. And it is its own
  namespace, not a part of `waymark10.modules`, because that table
  requires the whole spine; a core namespace that required it back
  would be a cycle. This one requires nothing.

  `defaults` is the closed list. A hook name not in it is refused at
  registration and at call, so a typo is an error, not a silent
  default. The ratchet that keeps new reads from coming back is
  scripts/core-tier-check.sh.")

(def defaults
  "Every hook the spine calls, each with the fn that answers when no
  module registered one.

    :gate-policy-for-repo  (fn [eng repository]) — the active
                           `repo_policy` row for that repository,
                           decoded, or nil. Default nil: no policy.
                           The repository policy's kit
                           (factorykit.repo-policy) registers it.

    :factory-changes       (fn [eng tx where limit]) — the `change`
                           rows whose data match `where`, at most
                           `limit`, as stored, read in `tx`. Default
                           nil: no change, so the gate finds no row a
                           branch was minted for and the bench mints
                           its worktree for no change. The change's
                           kit (factorykit.change) registers it.

    :factory-ci-runs       (fn [eng tx change-id limit]) — the
                           `ci_run` rows that point at that change, at
                           most `limit`, as stored, read in `tx`.
                           Default nil: no run, so the sit's feedback
                           carries no verdict of one. factorykit.change
                           registers it.

    :factory-merged-changes (fn [eng tx author limit]) — the `change`
                           rows in `merged` whose author is that seat
                           name, newest first, at most `limit`, as
                           stored, read in `tx`. Default nil: the seat
                           has merged none, so its health counts no
                           merge. factorykit.change registers it.

    :factory-tickets       (fn [eng tx where limit]) — the `ticket`
                           rows whose data match `where`, at most
                           `limit`, as stored, read in `tx`. Default
                           nil: no ticket, so a ticket walk withholds
                           no parent. The ask's kit (factorykit.ask)
                           registers it.

    :factory-ticket        (fn [eng tx id]) — the `ticket` row `id`,
                           as stored, read in `tx`, or nil. Default
                           nil: no such ticket, so none has ended and
                           none names a repository. factorykit.ask
                           registers it.

    :factory-ticket-filed  (fn [eng tx key]) — the `ticket` already
                           filed under that idempotency key, as the
                           store recorded it, read in `tx`, or nil.
                           Default nil: none filed, so the engine's
                           draft ticket (a judgment's, an org crack's)
                           is filed and its own key keeps it single.
                           factorykit.ask registers it.

    :factory-ticket-transitions (fn [eng tx id limit]) — the `ticket`
                           row `id`'s transitions, at most `limit`, as
                           stored, read in `tx`. Default nil: no
                           transition, so the cracks audit finds no
                           shelving. factorykit.ask registers it.

    :home-self             (fn [eng pid]) — the welcome home's :self:
                           that principal's ACTIVE `self` row, as the
                           homecoming shows it, or nil. Default nil:
                           no self. The dwelling module
                           (workqueue10.resources.dwelling) registers
                           it.

    :home-journal          (fn [eng pid]) — the welcome home's
                           :journal: {:recent [entry …] :all href}
                           for that principal's own `journal` rows,
                           or nil when there are none. Default nil.
                           The dwelling module registers it.

    :home-letters          (fn [eng pid]) — the welcome home's
                           :letters: the shelf of `letter` rows
                           addressed to that principal, or nil when it
                           is empty. Default nil. The letters module
                           (workqueue10.resources.letters) registers
                           it."
  {:gate-policy-for-repo   (fn [_eng _repository] nil)
   :factory-changes        (fn [_eng _tx _where _limit] nil)
   :factory-ci-runs        (fn [_eng _tx _change-id _limit] nil)
   :factory-merged-changes (fn [_eng _tx _author _limit] nil)
   :factory-tickets        (fn [_eng _tx _where _limit] nil)
   :factory-ticket         (fn [_eng _tx _id] nil)
   :factory-ticket-filed   (fn [_eng _tx _key] nil)
   :factory-ticket-transitions (fn [_eng _tx _id _limit] nil)
   :home-self              (fn [_eng _pid] nil)
   :home-journal           (fn [_eng _pid] nil)
   :home-letters           (fn [_eng _pid] nil)})

(def modules
  "The two modules whose kinds the spine reaches only through these
  hooks, each with the namespaces that register its hooks when they
  load (docs/core-tiers.md). Loading them is what registers the hooks;
  `waymark10.modules/inventory` names both modules, so an engine may
  leave either out, and one that does is answered the defaults."
  {:factory '[factorykit.ask factorykit.change factorykit.repo-policy]
   :dwelling '[workqueue10.resources.dwelling workqueue10.resources.letters]})

(def owners
  "The module each hook in `defaults` belongs to."
  {:gate-policy-for-repo   :factory
   :factory-changes        :factory
   :factory-ci-runs        :factory
   :factory-merged-changes :factory
   :factory-tickets        :factory
   :factory-ticket         :factory
   :factory-ticket-filed   :factory
   :factory-ticket-transitions :factory
   :home-self              :dwelling
   :home-journal           :dwelling
   :home-letters           :dwelling})

(defn assembled?
  "Whether `eng` assembled `module`. An engine's `:modules` is its
  selection (`waymark10.modules/selected`); nil — every engine deployed
  today — is the whole table, both modules loaded."
  [eng module]
  (let [selection (when (map? eng) (:modules eng))]
    (or (nil? selection) (boolean (some #{module} selection)))))

(defonce ^:private registry (atom {}))

(defn- unknown! [hook-name]
  (throw (ex-info (str "waymark10.modules.hooks: no hook named " (pr-str hook-name)
                       "; the hooks are " (pr-str (sort (keys defaults))))
                  {:hook hook-name :known (set (keys defaults))})))

(defn register-hook!
  "Register `f` as the answer to `hook-name`, replacing any earlier
  registration. Answers `hook-name`. Refuses a name not in `defaults`."
  [hook-name f]
  (when-not (contains? defaults hook-name)
    (unknown! hook-name))
  (swap! registry assoc hook-name f)
  hook-name)

(defn unregister-hook!
  "Remove any registration of `hook-name`, so the hook answers its
  default again. Answers `hook-name`. Refuses a name not in
  `defaults`."
  [hook-name]
  (when-not (contains? defaults hook-name)
    (unknown! hook-name))
  (swap! registry dissoc hook-name)
  hook-name)

(defn hook
  "Call the hook `hook-name` with `args`: the registered fn when the
  engine (the first of `args`) assembled the hook's module, and the
  default otherwise — no module registered one, or this engine left
  the module out. Refuses a name not in `defaults`."
  [hook-name & args]
  (let [default (or (get defaults hook-name) (unknown! hook-name))
        registered (get @registry hook-name)
        f (if (and registered (assembled? (first args) (owners hook-name)))
            registered
            default)]
    (apply f args)))
