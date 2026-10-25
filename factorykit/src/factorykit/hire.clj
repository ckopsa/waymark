(ns factorykit.hire
  "Hire a seat into an org (ticket 3d7897a6, M4a-3): the onboarding
  seat's tool for a hire plan, pure over an org directory, no engine.
  `(draft-seat org-dir gap)` drafts the one seat that closes a hiring
  gap, {:org :kind :state :reason :key}, as a seed step;
  `(apply-seat seed step)` appends that step to the seed's :seats.

  The seat walks the gap's kind in the gap's state, holds the doors out
  of that state the org's owner also holds, reads the kind, and wakes
  on every door that brings a row there. A gap the owner holds no door
  out of answers {:error :scope_exceeds_owner}; an org whose domain
  budget its seats already spend answers {:error :no_headroom}."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [factorykit.conform :as conform]
            [waymark10.machine :as machine])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private weekly-budget 10M)

(defn- text [x] (if (keyword? x) (name x) (str x)))

;; ── the seat ────────────────────────────────────────────────────────

(defn- owner-of
  "The seed's owner as [cast-key member]: the cast member keyed owner,
  or else the one whose :id is owner."
  [seed]
  (or (find (:cast seed) :owner)
      (first (filter #(= "owner" (text (:id (val %)))) (:cast seed)))))

(defn- holds?
  "Does the ceiling hold `action` on `kind`? No ceiling holds the whole
  org, and \"*\" is every action."
  [ceiling kind action]
  (or (nil? ceiling)
      (boolean (some #(and (= kind (text (:kind %)))
                           (some #{action "*"} (map text (:actions %))))
                     ceiling))))

(defn- exits
  "The action names that move a row OUT of `state`."
  [rdef state]
  (into [] (comp (filter #(and (some? (:to %)) (not= state (:to %))))
                 (map (comp text :name)))
        (machine/transitions-from rdef state)))

(defn- arrivals
  "The action names that put a row INTO `state`, create for the initial."
  [rdef state]
  (cond-> (into (sorted-set)
                (comp (filter #(= state (:to %))) (map (comp text :name)))
                (machine/actions-seq rdef))
    (= state (:initial rdef)) (conj "create")))

(defn- seat-name
  "<org>-<kind>-<n>, the lowest n no seat takes."
  [org kind taken]
  (first (remove taken (map #(str org "-" kind "-" %) (iterate inc 1)))))

(defn- headroom
  "The weekly dollars the seed's domains state and its seats do not
  spend yet; nil when no domain states a budget."
  [seed seats]
  (let [stated (filter number? (map :budget_usd_per_week (:domains seed)))]
    (when (seq stated)
      (- (reduce + 0M (map bigdec stated))
         (reduce + 0M (map bigdec (filter number? (map :budget_usd_per_week seats))))))))

(defn- doors-said [doors]
  (if (next doors)
    (str (str/join ", " (butlast doors)) " or " (last doors))
    (first doors)))

(defn- charter [kind state doors]
  (str "You move each " kind " row that rests in " state ", one row a firing. "
       "Take " (doors-said doors) " on it when the row is ready for that door, and say why in one sentence. "
       "Do nothing else: no other door, no row in another state, and no other kind."))

(defn draft
  "The seed step that hires the seat closing `gap`, over the org's kinds
  `rs` and its `seed`: {:seat {…} :wakes […] :owner …}, or {:error …}."
  [rs seed {:keys [org kind state]}]
  (let [org (text (or org (:org seed)))
        k (text kind)
        s (keyword (text state))
        rdef (first (filter #(= k (text (:kind %))) rs))
        [owner-key member] (owner-of seed)
        ceiling (:scope member)
        out (when rdef (exits rdef s))
        doors (filterv #(holds? ceiling k %) out)
        seats (:seats (#'conform/seed-seats seed))
        room (headroom seed seats)
        wakes (when rdef (vec (arrivals rdef s)))
        in-state {:state (text s)}]
    (cond
      (nil? rdef) {:error :unknown_kind}
      (empty? out) {:error :no_door_out}
      (empty? doors) {:error :scope_exceeds_owner}
      (and room (<= room 0)) {:error :no_headroom}
      :else
      (cond-> {:seat {:name (seat-name org k (set (keep :name seats)))
                              :mode "fired"
                              :walk k
                              :rows_per_firing 1
                              :cadence_seconds 3600
                              :standing_ttl_seconds 604800
                              :budget_usd_per_week (if room (min weekly-budget room) weekly-budget)
                              :sitting_budget_tokens 60000
                              :charter (charter k (text s) doors)
                              :scope (cond-> [{:kind k :actions doors :filter in-state}]
                                       (or (nil? ceiling) (some #(= k (text (:kind %))) ceiling))
                                       (conj {:kind k :actions []}))}}
        (seq wakes) (assoc :wakes [{:kind k :actions wakes :filter in-state}])
        owner-key (assoc :owner (text owner-key))))))

(defn draft-seat
  "The seed step that hires the seat closing `gap` in the org at
  org-dir, read as factorykit.conform reads it; or {:error …}."
  [org-dir gap]
  (let [^File dir (.getCanonicalFile (io/file org-dir))
        org (.getName dir)
        seed (edn/read-string (slurp (io/file dir "resources" org "seed.edn")))
        {rs :resources} (#'conform/load-org dir org)]
    (if rs (draft rs seed gap) {:error :org_does_not_load})))

;; ── the seed ────────────────────────────────────────────────────────

(defn- masked
  "The text with its strings and comments blanked, every index kept."
  ^String [^String s]
  (let [sb (StringBuilder. s)
        n (count s)
        blank #(when (< % n) (.setCharAt sb (int %) \space))]
    (loop [i 0 mode :code]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s (int i))]
          (case mode
            :code (cond (= c \") (recur (inc i) :string)
                        (= c \;) (do (blank i) (recur (inc i) :comment))
                        (= c \\) (do (blank (inc i)) (recur (+ i 2) :code))
                        :else (recur (inc i) :code))
            :string (cond (= c \\) (do (blank i) (blank (inc i)) (recur (+ i 2) :string))
                          (= c \") (recur (inc i) :code)
                          :else (do (blank i) (recur (inc i) :string)))
            :comment (if (= c \newline)
                       (recur (inc i) :code)
                       (do (blank i) (recur (inc i) :comment)))))))))

(def ^:private opens #{\( \[ \{})
(def ^:private closes #{\) \] \}})

(defn- close-of
  "The index of the bracket that closes the one at `open`."
  [^String m open]
  (loop [i open depth 0]
    (when (< i (count m))
      (let [c (.charAt m (int i))]
        (cond (opens c) (recur (inc i) (inc depth))
              (closes c) (if (= 1 depth) i (recur (inc i) (dec depth)))
              :else (recur (inc i) depth))))))

(defn- blank? [c] (or (Character/isWhitespace (char c)) (= \, c)))

(defn- seats-close
  "The index of the `]` that closes the seed text's top-level :seats
  vector; nil when it has none."
  [^String s]
  (let [m (masked s)
        n (count m)]
    (loop [i 0 depth 0]
      (when (< i n)
        (let [c (.charAt m (int i))
              after (+ i (count ":seats"))]
          (cond
            (and (= 1 depth) (.startsWith m ":seats" (int i))
                 (< after n) (or (blank? (.charAt m (int after))) (= \[ (.charAt m (int after)))))
            (let [j (first (drop-while #(and (< % n) (blank? (.charAt m (int %)))) (iterate inc after)))]
              (when (and (< j n) (= \[ (.charAt m (int j))))
                (close-of m j)))
            (opens c) (recur (inc i) (inc depth))
            (closes c) (recur (inc i) (dec depth))
            :else (recur (inc i) depth)))))))

(defn apply-seat
  "The seed with `step` appended to its :seats. A map is edited as data;
  seed.edn's text is edited as text, keeping every byte but the step's,
  and printed whole by the edn writer only when it has no :seats vector."
  [seed step]
  (if (string? seed)
    (if-some [at (seats-close seed)]
      (str (subs seed 0 at) "\n  " (pr-str step) (subs seed at))
      (pr-str (apply-seat (edn/read-string seed) step)))
    (update seed :seats (fnil conj []) step)))
