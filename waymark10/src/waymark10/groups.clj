(ns waymark10.groups
  "OneOf field groups (waymark9 core/groups.py, design §5): declared
  exclusivity. One declaration yields the invariant — at most one arm
  filled, enforced by the invoker after every handler — and, with
  :clears true, the clearing that v2 re-maintained by hand in three
  handlers: filling one arm clears the others.

  A group spec ({:one-of {name spec}} on the resource map, shape
  checked at import by waymark10.checks/check-oneof):

    {:in [:days]           ; the vector-of-map field the group governs;
                           ; absent/nil ⇒ the data root
     :arms {:meal [:meal_id :meal_name]
            :eating_out [:eating_out :eating_out_where]}
     :clears true}

  An arm's first field is its primary — the arm is filled iff the
  primary is neither nil nor false. Recorded deviation from waymark9:
  clearing resets fields to nil, not to a model default — malli
  entries declare no defaults, and nil is the one honest empty."
  (:require [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- filled? [v]
  (and (some? v) (not (false? v))))

(defn- apply-group
  "One group over one map (the data root, or one item of a governed
  vector). `before` is the pre-handler map — nil (create) treats every
  filled arm as newly filled."
  [gname spec m before]
  (let [arms (:arms spec)
        filled (into []
                     (filter (fn [arm] (filled? (get m (first (get arms arm))))))
                     (sort (keys arms)))]
    (if (<= (count filled) 1)
      m
      (let [newly (into []
                        (filter (fn [arm]
                                  (or (nil? before)
                                      (not (filled? (get before (first (get arms arm))))))))
                        filled)]
        (if (and (:clears spec) (= 1 (count newly)))
          (let [keep (first newly)]
            (reduce (fn [m arm]
                      (if (= arm keep)
                        m
                        (reduce (fn [m f] (assoc m f nil)) m (get arms arm))))
                    m
                    filled))
          (throw (t/definition-error
                  (str "one-of " gname ": arms " (vec filled)
                       " are filled together — either the handler fills two "
                       "arms (a bug) or two arms were newly set in one write "
                       "(ambiguous even with :clears true)"))))))))

(defn enforce
  "Post-handler enforcement: every declared group, applied to the data
  root or per item of its governed vector. `before` is the pre-handler
  data map (nil at create). Returns the (possibly cleared) data."
  [rdef data before]
  (reduce-kv
   (fn [data gname spec]
     (if-some [in (first (:in spec))]
       (if-some [items (get data in)]
         (let [prev (vec (get before in))]
           (assoc data in
                  (vec (map-indexed
                        (fn [i item]
                          (apply-group gname spec item (get prev i)))
                        items))))
         data)
       (apply-group gname spec data before)))
   data
   (:one-of rdef)))
