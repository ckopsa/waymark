(ns waymark10.test.conformance
  "Phase-5 conformance obligations over a booted engine — library fns
  the application suites call from their own deftests.

  The replay-history obligation (waymark9 design §3: \"the audit
  trail answers 'under which gate?' for free\"): every logged
  transition stamped with a law revision must be an action THAT
  revision's stored fingerprint declares, with matching from/to
  states. A nil stamp is the pre-law horizon (skipped); :create and
  :adopt are engine actions a machine never declares (allowed —
  :create additionally lands in declared create states the stored
  machine cannot see, waymark9's created_in)."
  (:require [clojure.walk :as walk]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- wire-keys [v]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k val]] [(if (keyword? k) (name k) k) val])) x)
       x))
   v))

(def ^:private engine-actions #{:create :adopt})

(defn replay-violations
  "Every logged transition with a non-nil law-revision, checked
  against its revision's stored fingerprint. Returns a vector of
  violation maps — empty is conformance."
  [eng]
  (let [st (:storage eng)
        laws (store/with-tx st
               (fn [tx]
                 (into {}
                       (map (fn [row]
                              [[(get-in row [:data :target_kind])
                                (get-in row [:data :revision])]
                               (wire-keys (get-in row [:data :fingerprint]))]))
                       (store/query-rows st tx :definition {} {:limit 1000}))))
        ts (store/with-tx st
             (fn [tx] (store/transitions st tx {} {:limit 100000})))]
    (into []
          (keep
           (fn [t]
             (let [rev (:law-revision t)
                   witness (select-keys t [:kind :resource-id :action
                                           :from-state :to-state
                                           :law-revision])]
               (when (and rev
                          (:from-state t)
                          (not (engine-actions (:action t))))
                 (if-some [fp' (get laws [(name (:kind t)) rev])]
                   (if-some [a (get-in fp' ["machine" "actions"
                                            (name (:action t))])]
                     (when-not (and (some #(= % (name (:from-state t)))
                                          (get a "from"))
                                    (= (name (:to-state t)) (get a "to")))
                       (assoc witness :violation :edge-not-in-law
                              :law {:from (get a "from") :to (get a "to")}))
                     (assoc witness :violation :action-not-in-law))
                   (assoc witness :violation :no-stored-law))))))
          ts)))
