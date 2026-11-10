(ns waymark10.modules.hooks
  "The spine's named hooks (docs/core-tiers.md, core tier slice 1): the
  door through which a core namespace asks a module for something only
  the module's kinds know, instead of reading those kinds by name.

  A module registers a hook when its namespace loads:

      (hooks/register-hook! :gate-policy-for-repo policy-for-repo)

  and the spine calls it by name:

      (hooks/hook :gate-policy-for-repo eng repository)

  When no loaded module registered the hook, the call answers the
  hook's documented default from `defaults`, so an engine assembled
  without the module behaves as one that never declared its kinds.

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
                           registers it."
  {:gate-policy-for-repo (fn [_eng _repository] nil)
   :factory-changes      (fn [_eng _tx _where _limit] nil)
   :factory-ci-runs      (fn [_eng _tx _change-id _limit] nil)})

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

(defn hook
  "Call the hook `hook-name` with `args`: the registered fn, or the
  default when no module registered one. Refuses a name not in
  `defaults`."
  [hook-name & args]
  (let [f (or (get @registry hook-name)
              (get defaults hook-name)
              (unknown! hook-name))]
    (apply f args)))
