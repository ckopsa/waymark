(ns waymark10.check-all
  "The declaration gate over EVERY module at once (ticket c09e2f88):
  waymark10's enrolled kinds, workqueue10's (the chore, meal, calendar
  and day-plan kinds folded in) and factory10's, assembled into ONE
  registry — the union CI's suites see.

      cd workqueue10 && clojure -M:check

  WHY THE UNION. checks-assembly runs when the registry is built, and
  some of its findings need another module's kind to fire: a plain
  string field named after a kind (`[unref'd-ids]`) is only a finding
  where that kind is registered. PR #542 met three of them in CI's
  suites while the module's own check answered clean.

  It lives on workqueue10's classpath because that module is the one
  that depends on all the others."
  (:require [factory10.main :as factory]
            [waymark10.check :as check]
            [workqueue10.main :as queue]))

(defn resources
  "Every module's kinds, each once. workqueue10's check set already
  folds factory10's in when FACTORY10=1, and a kind named twice would
  die in the registry as `one law per kind`."
  []
  (let [seen (volatile! #{})]
    (into []
          (filter (fn [r]
                    (when-not (contains? @seen (:kind r))
                      (vswap! seen conj (:kind r)))))
          (concat (queue/check-resources) (factory/check-resources)))))

(defn -main [& _]
  (check/-main "waymark10.check-all/resources"))
