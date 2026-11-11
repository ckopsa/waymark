(ns waymark10.server.invoke.adopt
  "The engine-injected :adopt (phase 5; moved out of
  waymark10.server.invoke, ticket bab6336f): a same-state restamp of a
  row to its governing law — the piloted revision when the pilot's
  where-population claims the row, else the kind's current law.
  `invoke/invoke!` routes :adopt here."
  (:require [waymark10.server.invoke.labels :refer [encode-row summary-of]]
            [waymark10.server.invoke.rows :refer [actor-map body-digest decode-row]]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- where-claims?
  "Does a pilot population's where= equality map claim this decoded
  row? String-fallback comparison, the acceptance-membership
  discipline."
  [where row]
  (every? (fn [[f expected]]
            (let [v (get-in row [:data f])]
              (or (= v expected) (= (str v) (str expected)))))
          where))

(defn- adopt-target
  "The revision a row adopts INTO: the piloted revision when its
  where-population claims the row, else the kind's current law."
  [rdef row]
  (let [p (:piloted-law rdef)]
    (if (and p (some-> (get-in p [:population :where])
                       (where-claims? row)))
      (:revision p)
      (:current-law rdef))))

(defn adopt!
  "The engine-injected :adopt: a same-state restamp to the row's
  governing law — version+1, logged, no handler, no guards. A machine
  that declares its own :adopt shadows this. Enforcement accepts any
  state, terminal included, though render only advertises it on
  non-terminal rows — recorded deviation: adopting a closed row is
  the maintenance act that lets a grandfathered law finally die (the
  sweep counts every stamped row).

  Writes NO decision record, whatever the kind retains: a restamp is
  the engine moving a row's law, not a judgment of it — there are no
  guards to record, and an empty object would make the column lie
  about coverage."
  [engine rdef kind id {:keys [principal dry-run correlation-id]}]
  (store/with-tx (:storage engine)
    (fn [tx]
      (let [raw (or (store/load-row (:storage engine) tx kind id
                                    {:for-update (not dry-run)})
                    (throw (p/not-found kind id)))
            row (decode-row rdef raw)
            target (adopt-target rdef row)]
        (cond
          dry-run {:valid? true}
          (= target (:law-revision row)) {:row row :replayed? :natural}
          :else
          (let [advanced (-> row
                             (assoc :law-revision target)
                             (update :version inc))
                record (store/append-transition!
                        (:storage engine) tx
                        {:kind kind
                         :resource-id id
                         :action :adopt
                         :from-state (:state row)
                         :to-state (:state row)
                         :actor (actor-map principal nil)
                         :law-revision target
                         :input-digest (body-digest nil)
                         :correlation-id correlation-id
                         :summary (summary-of rdef row ((:now-fn engine)))})
                saved (store/save-row! (:storage engine) tx kind
                                       (encode-row rdef advanced)
                                       (:version row))]
            {:row (decode-row rdef saved) :transition record}))))))
