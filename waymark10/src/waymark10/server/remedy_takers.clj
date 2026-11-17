(ns waymark10.server.remedy-takers
  "Who can take a remedy the caller cannot (ticket 14dc1516, split from
  0e1fe0d3). A guard's refusal names its remedies as doors; a door the
  caller's grant does not admit is a dead end unless the refusal says
  whose it is. `annotate` adds one sentence per such door: the seats
  and people whose scope admits it (grants/door-takers), or, when
  nobody's does, the person who opened the caller's seat -- what
  held-calls.nobody/sentence says of the approver role, said of any
  door."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]))

(set! *warn-on-reflection* true)

(defn- shut-doors
  "The [kind action] of each qualified remedy door the engine serves
  and `vis` does not admit, in the refusal's order. A nil `vis` is an
  unscoped caller, whom no grant narrows: nil."
  [eng vis remedies]
  (when vis
    (into []
          (comp (map g/remedy-door)
                (filter qualified-keyword?)
                (map (juxt (comp keyword namespace) name))
                (filter (fn [[k _]] (contains? (inv/resources eng) k)))
                (remove (fn [[k a]] ((:action? vis) k a)))
                (distinct))
          remedies)))

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

(defn annotate
  "`e` with who can take each remedy the caller cannot, when `e` is a
  guard's refusal naming such a remedy: the sentences ride after the
  guard's own in :detail, and :remedy-takers carries them per door.
  Any other `e`, or one whose remedies are all the caller's, is
  returned as it came."
  [eng principal vis e]
  (let [d (ex-data e)]
    (if-let [shut (and (= :guard-refused (:waymark10/problem d))
                       (not-empty (shut-doors eng vis (:remedies d))))]
      (let [except (some-> (:id principal) str)
            seat? (some? (:seat vis))
            said (mapv (fn [[k a]]
                         {:door (str (name k) "/" a)
                          :says (sentence eng k a except seat?)})
                       shut)
            detail (str/join " " (remove str/blank?
                                         (cons (str (:detail d)) (map :says said))))]
        (ex-info (str (:title d) ": " detail)
                 (assoc d :detail detail :remedy-takers said)
                 e))
      e)))
