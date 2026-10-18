(ns factory10.org-live
  "A going_live org_plan reads live once the house serves its org
  (ticket 25c1520b, M2e-4 of epic cafa6106). The merge put the org's
  directory on main, the deploy's boot loaded it (`waymark10.server.org`),
  and this pass, run after that boot as the engine's own hand, walks
  each going_live plan whose org is now served: a domain named the
  plan's `name`, and an active seat in that domain named the plan's
  `mayor`. It takes the plan's `mark_live` with that seat's id, which
  the plan keeps as `mayor_seat`.

  Until then it does nothing: going_live's expected age makes a stuck
  deploy a crack for the mayor. A plan already live is not going_live,
  so a second pass changes nothing."
  (:require [factory10.mirror :as mirror]
            [waymark10.server.domains :as domains]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

(defn- rows-of [eng kind where]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 1000})))
    []))

(defn mayor-seat
  "The id of the active seat named `mayor` in the domain named `org`,
  or nil while the house serves no such domain or no such seat."
  [eng org mayor]
  (when (and (seq org) (seq mayor))
    (when-some [domain (domains/domain-named eng org)]
      (some->> (rows-of eng :seat {:name mayor})
               (filter #(and (= "active" (name (:state %)))
                             (= (str (:id domain))
                                (some-> (get-in % [:data :domain]) str))))
               first :id str))))

(defn mark-live-plans!
  "One pass: every going_live org_plan whose org the house now serves,
  with its mayor's seat, marked live through `mark_live`. → how many
  it marked. A plan whose step fails is left going_live, and the next
  pass tries again."
  [eng]
  (if-some [rd (get (inv/resources eng) :org_plan)]
    (reduce (fn [n raw]
              (let [plan (inv/decode-row rd raw)
                    org (some-> (get-in plan [:data :name]) str not-empty)
                    mayor (some-> (get-in plan [:data :mayor :name]) str not-empty)]
                (if-some [seat (mayor-seat eng org mayor)]
                  (try
                    (inv/invoke! eng :org_plan (str (:id plan)) :mark_live
                                 {:mayor_seat seat}
                                 {:principal mirror/source-principal})
                    (inc n)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "waymark10 org-live: " org " did not go live ("
                                      (ex-message e) "); the next pass tries again")))
                      n))
                  n)))
            0
            (rows-of eng :org_plan {:state "going_live"}))
    0))
