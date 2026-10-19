(ns two_words.org
  "two_words: a fixture org whose name holds an underscore. Its
  namespace root is the name exactly, `two_words`, as docs/orgkit.md
  § Naming rules says."
  (:require [two_words.kinds.thing :refer [thing]]
            [waymark10.dsl :refer [in-domain]]))

(defn resources [] (in-domain :two_words [thing]))
