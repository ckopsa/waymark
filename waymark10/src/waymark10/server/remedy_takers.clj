(ns waymark10.server.remedy-takers
  "Who can take a remedy the caller cannot (ticket 14dc1516, split from
  0e1fe0d3). A guard's refusal names its remedies as doors; a door the
  caller's grant does not admit is a dead end unless the refusal says
  whose it is. `annotate` adds one sentence per such door: the seats
  and people whose scope admits it (grants/door-takers), or, when
  nobody's does, the person who opened the caller's seat -- what
  held-calls.nobody/sentence says of the approver role, said of any
  door.

  A remedy gated by a ROLE its guard's code checks (ticket 01e3c909)
  is named too, when the guard declares that role as `:role` and the
  caller's principal does not hold it: the door needs the role, in
  held-calls.nobody/sentence's words."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

(defn- served-doors
  "The [kind action] of each qualified remedy door the engine serves,
  in the refusal's order."
  [eng remedies]
  (into []
        (comp (map g/remedy-door)
              (filter qualified-keyword?)
              (map (juxt (comp keyword namespace) name))
              (filter (fn [[k _]] (contains? (inv/resources eng) k)))
              (distinct))
        remedies))

(defn- shut-doors
  "The served remedy doors `vis` does not admit, in the refusal's
  order. A nil `vis` is an unscoped caller, whom no grant narrows: nil."
  [eng vis remedies]
  (when vis
    (into [] (remove (fn [[k a]] ((:action? vis) k a)))
          (served-doors eng remedies))))

(defn door-roles
  "The roles the guards of `kind`.`action` declare as `:role`, in
  order: what a guard's code checks of the principal, said as data.
  A create verb's are its create guards'."
  [eng kind action]
  (when-some [rdef (get (inv/resources eng) kind)]
    (let [guards (if (some #{(name action)} (map name (:create-action-names rdef)))
                   (:create-guards rdef)
                   (get-in rdef [:actions (keyword (name action)) :guards]))]
      (into [] (comp (keep :role) (map str) (distinct))
            (mapcat g/iter-leaves guards)))))

(defn- lacked-roles
  "The roles of `kind`.`action` `principal` does not hold. The engine's
  own principal holds them all."
  [eng principal kind action]
  (when-not (= :system (:type principal))
    (let [held (set (map str (:roles principal)))]
      (into [] (remove held) (door-roles eng kind action)))))

(defn- spelled [names]
  (str/join " or " (map #(str "`" % "`") names)))

(defn sentence
  "The one sentence for a remedy door `kind`.`action` the caller's
  grant does not admit: who can take it, by display name, `except` the
  caller; with nobody, the person who opened the caller's seat when it
  sits in one (`seat?`), else the ask door."
  [eng kind action except seat?]
  (let [door (str (name kind) "." (name action))
        takers (grants/door-takers eng kind action nil except)]
    (str "Your grant does not admit " door ": "
         (cond
           (seq takers) (str (spelled takers) " can take it.")
           seat? (str "nobody holds it yet; the person who opened this seat"
                      " can take it, or grant it to someone who will.")
           :else (str "nobody holds it yet; ask for it with an"
                      " approval_request.")))))

(defn role-sentence
  "The one sentence for a remedy door `kind`.`action` whose guards need
  `roles` the caller does not hold: held-calls.nobody/sentence's words
  for the role, and its door."
  [kind action roles]
  (str "Taking " (name kind) "." (name action) " needs the " (spelled roles)
       " role, and you do not hold it: a recovery-admin gives it to a"
       " person (member.assign_roles)."))

(defn annotate
  "`e` with who can take each remedy the caller cannot, when `e` is a
  guard's refusal naming such a remedy: the sentences ride after the
  guard's own in :detail, and :remedy-takers carries them per door --
  a door the grant does not admit, then a door needing a declared role
  the principal lacks (with its :roles). Any other `e`, or one whose
  remedies are all the caller's, is returned as it came."
  [eng principal vis e]
  (let [d (ex-data e)
        refused? (= :guard-refused (:waymark10/problem d))
        shut (when refused? (shut-doors eng vis (:remedies d)))
        roled (when refused?
                (for [[k a] (served-doors eng (:remedies d))
                      :let [lacks (lacked-roles eng principal k a)]
                      :when (seq lacks)]
                  {:door (str (name k) "/" a)
                   :roles lacks
                   :says (role-sentence k a lacks)}))]
    (if (or (seq shut) (seq roled))
      (let [except (some-> (:id principal) str)
            seat? (some? (:seat vis))
            said (into (mapv (fn [[k a]]
                               {:door (str (name k) "/" a)
                                :says (sentence eng k a except seat?)})
                             shut)
                       roled)
            detail (str/join " " (remove str/blank?
                                         (cons (str (:detail d)) (map :says said))))]
        (ex-info (str (:title d) ": " detail)
                 (assoc d :detail detail :remedy-takers said)
                 e))
      e)))
