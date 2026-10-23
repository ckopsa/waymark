(ns waymark10.server.retention
  "The retention sweep (docs/spec-documents.md §4, Documents 5). Each
  pass, for every kind that declares a :keep map, it pages the rows
  standing where the :then door opens and invokes :then on each whose
  due date has passed, as the engine's retention actor. The log line
  says why: purged by the 7-year retention of patient, due 14 March
  2031. The door's own retention-has-passed judges the same date, so
  the sweep never purges what a person could not.

  A refusal — a guard the author added — is logged and the row is
  tried on the next pass; it never stops the sweep. ONE process per
  storage runs it: the attachments module's hook carries `:elected
  :retention`, beside :attachments-purge."
  (:require [waymark10.machine :as machine]
            [waymark10.retention :as retention]
            [waymark10.server.clock :as clock]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

(def actor
  "The engine's own hand on a purge the law has made due."
  (t/principal {:id "waymark10-retention" :type :system :display "Retention"}))

(def ^:private page
  "How many rows of one state of one kind a pass reads."
  500)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 retention: " parts))))

(defn- then-door
  "The door a kind's :keep names, as the machine holds it, or nil."
  [rdef]
  (let [then (get-in rdef [:keep :then])]
    (some #(when (= then (:name %)) %) (machine/actions-seq rdef))))

(defn- rows-in [eng kind state]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/query-rows st tx kind {:state state} {:limit page})))))

(defn sweep!
  "One pass: every row a :keep has let go moves through its :then door
  under the retention actor's hand. → how many moved."
  [eng]
  (let [hooks (inv/render-hooks eng)
        zone (retention/zone-of (:calendar hooks))
        today (.toLocalDate (.atZone ^Instant ((:now-fn eng)) zone))]
    (reduce
     (fn [n [kind rdef]]
       (let [keep (:keep rdef)
             door (when (map? keep) (then-door rdef))]
         (reduce
          (fn [n row]
            (let [row (assoc row :kind kind)
                  due (retention/due-on keep row (:entered-at hooks) zone)]
              (if (and due (not (.isBefore today ^LocalDate due)))
                (try
                  (inv/invoke! eng kind (str (:id row)) (:name door) {}
                               {:principal (assoc actor :why (retention/swept-why rdef due))})
                  (inc (long n))
                  (catch Exception e
                    (warn! (name kind) " " (:id row) " is due but was not purged ("
                           (ex-message e) "); it is tried again next pass")
                    n))
                n)))
          n
          (when door
            (mapcat #(rows-in eng kind %) (sort (:from door)))))))
     0
     (sort-by key (inv/resources eng)))))

(defn start-sweeper!
  "Every `:interval-ms` (default an hour), `sweep!`. The first pass is
  one interval after the start. Returns the handle `stop-sweeper!`
  takes."
  [eng {:keys [interval-ms] :or {interval-ms 3600000}}]
  {:stop (clock/every! {:name "waymark10-retention" :period-ms interval-ms
                        :f #(sweep! eng)})})

(defn stop-sweeper! [{:keys [stop]}]
  (when stop (stop))
  nil)
