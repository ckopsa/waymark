(ns waymark10.server.seats.words
  "The words the seat machinery's kinds say of themselves — the
  declared :purpose (ticket a1f19281) of seat, sitting, model and
  schedule — and the price shape model's fields share. Split out of
  waymark10.server.seats and waymark10.server.schedules, which may not
  grow (ci/ns-size-grandfather.edn).")

(set! *warn-on-reflection* true)

(def price-entry
  "One price field's shape — dollars per million tokens, exact
  decimals (never a float: the E.lit(\"0.02\") lesson, batch H)."
  [:decimal {:min 0 :max 10000}])

(def seat
  "seat's :purpose: the office, not the agent that sits in it."
  {:what "A standing office the house fills with a model: its charter, its grant and its schedule, kept while the agents that sit in it come and go."
   :when "Use it to give an agent a recurring job: its wakes are subscriptions, its claims leases, its spend postings, and it fires through runner links."
   :not [{:kind :model
          :why "A model is the agent and its price list; the same seat is sat by different models over time."}
         {:kind :sitting
          :why "A sitting is one wake of a seat and what it cost; the seat is the office that outlasts every sitting."}]})

(def sitting
  "sitting's :purpose: what a wake cost, not what it did."
  {:what "One wake of a seat, from its sit to its close: who sat, on which model, and what it cost in tokens and money."
   :when "Read it to learn what a wake cost or how it ended; the engine opens one at each sit and closes it at the stop."
   :not [{:kind :transcript
          :why "A sitting records what a wake cost; its transcript records what the wake did, line by line."}
         {:kind :lease
          :why "A lease says which row a sitting holds right now; the sitting is the whole wake, holding a row or not."}]})

(def model
  "model's :purpose: the price list and the chair, not the seat."
  {:what "One model a seat can be sat by, with its vendor, its rung on the ladder and its price list per million tokens."
   :when "Use it to price a sitting and to fire a seat: a seat's chair is the first of its held_for, and the chair's pool fires a seat with no pool of its own."
   :not [{:kind :seat
          :why "A seat is the office; a model is who sits in it, and one seat is sat by different models over time."}]})

(def schedule
  "schedule's :purpose: how a seat's sittings start, not one call later."
  {:what "How a seat's sittings get started: the cadence and the provider that wake it, firing through a runner link in its pool."
   :when "Read it to see when and through which link a seat fires next; the engine keeps it in step as the seat moves."
   :not [{:kind :scheduled_action
          :why "A scheduled action makes one call at one time; a schedule wakes a seat again and again."}
         {:kind :runner_link
          :why "A runner link is the credential a fire goes out on; the schedule says when, and picks the link from its pool."}]})
