(ns waymark10.server.scheduled-words
  "The words the scheduled_action kind says: of itself (its declared
  :purpose, ticket a1f19281) and the small sentence helpers its doors
  and sweeps build their sentences with. Split out of
  waymark10.server.scheduled, which may not grow
  (ci/ns-size-grandfather.edn).")

(set! *warn-on-reflection* true)

(def what-it-is-for
  "scheduled_action's :purpose, with the two neighbours it is told
  apart from."
  {:what "One action or tool call the engine will make later, at a set time, if what it depends on still holds then."
   :when "Use it to do one thing at one time, like grooming a ticket at 08:30: pass `at` to waymark_invoke or waymark_power."
   :not [{:kind :promise
          :why "A promise watches a measure over time and makes no call of its own."}
         {:kind :quest
          :why "A quest is a goal a person works toward step by step; a scheduled action is one call the engine makes for them."}]})

(defn said
  "A refusal's own sentence."
  [^Throwable e]
  (str (or (:detail (ex-data e)) (ex-message e))))

(defn cut
  "A sentence at most `n` characters long, ending in … when it was longer."
  [s ^long n]
  (let [s (str s)]
    (if (< n (count s)) (str (subs s 0 (dec n)) "…") s)))

(defn call-of [door]
  (if-some [tool (:tool door)]
    (str "`" tool "`")
    (str "`" (name (:action door)) "` on " (name (:kind door)))))
