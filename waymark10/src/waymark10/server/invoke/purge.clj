(ns waymark10.server.invoke.purge
  "The purge (docs/spec-documents.md §5; moved out of
  waymark10.server.invoke, ticket 2d02c8ab): a :purge door clears a
  row's personal fields and lets go of its files, deleting each one no
  other live row still holds, by the engine's own hand."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.invoke.labels :refer [schema-head* target-of]]
            [waymark10.server.invoke.rows :refer [resources]]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def purge-actor
  "The engine's own hand on the files a purge deletes: the purge was
  the person's act, and its files go with it whatever the caller's
  grant says of the attachment kind."
  (t/principal {:id "waymark10-purge" :type :system :display "Purge"}))

(defn held-elsewhere?
  "Does a live row other than [kind id] hold attachment `aid` in a field
  that refers to attachments? A single ref is asked by equality and a
  list by containment ({:has aid}), one live state at a time, so the
  store answers either and terminal holders cannot use up the limit."
  [engine tx kind id aid]
  (let [st (:storage engine)
        aid (str aid)
        other? (fn [k rd r]
                 (and (not (contains? (:terminal rd) (keyword (name (:state r)))))
                      (not (and (= k kind) (= (str (:id r)) (str id))))))]
    (some (fn [[k rd]]
            (when (:schema rd)
              (some (fn [[f {:keys [properties schema]}]]
                      (when (= :attachment (:kind properties))
                        (let [want (if (= :vector (schema-head* schema))
                                     {:has aid}
                                     aid)]
                          (some (fn [s]
                                  (some #(other? k rd %)
                                        (store/query-rows st tx k {f want :state s} {:limit 2})))
                                (remove (set (:terminal rd)) (:states rd))))))
                    (schema/entry-map (:schema rd)))))
          (resources engine))))

(defn purge!
  "A :purge door's write, inside its transaction: every personal field
  of `handled` cleared, and each file those fields held on `row` moved
  to `deleted` through the attachment's own door by the engine's hand —
  unless another live row still holds it, when this row only lets go
  of it. → [handled {:cleared :deleted :unlinked}], what the log line
  names: field names and file ids, never a value."
  [engine tx rdef row handled ctx]
  (let [form (:schema rdef)
        fields (schema/personal-fields form)
        ids (schema/document-ids form (:data row))
        shared (into #{} (filter #(held-elsewhere? engine tx (:kind rdef) (:id row) %)) ids)
        doomed (filterv (complement shared) ids)]
    (when-some [invoke (:invoke ctx)]
      (doseq [aid doomed
              :let [a (target-of engine tx :attachment aid)]
              :when (and a (not= "deleted" (name (:state a))))]
        (invoke :attachment aid :delete nil {:as purge-actor})))
    [(update handled :data #(reduce (fn [d f] (assoc d f nil)) % fields))
     {:cleared fields :deleted doomed :unlinked (filterv shared ids)}]))

(defn purge-says
  "What a purge's log line adds to the row's: the fields it cleared by
  name and the files it let go of, never a value."
  [{:keys [cleared deleted unlinked]}]
  (str " · erased " (if (seq cleared) (str/join ", " (map name cleared)) "no field")
       (when (seq deleted)
         (str "; " (count deleted) (if (= 1 (count deleted)) " file" " files")
              " deleted"))
       (when (seq unlinked)
         (str "; unlinked " (str/join ", " unlinked) ", still held by another row"))))
