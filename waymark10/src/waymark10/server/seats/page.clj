(ns waymark10.server.seats.page
  "The seat and sitting pages' words, moved out of
  `waymark10.server.seats` in round five (ticket f190fba5): a seat as
  its page leads with it (`reads-as`, ticket 9935e3a3) and a sitting at
  work, each call in its owner's words (`at-work`, ticket ff5bfbb9),
  with the two :computed fields the seat and sitting resources name.
  Pure over the rows and the reads the render ctx lends; nothing here
  requires `waymark10.server.seats`."
  (:require [clojure.string :as str]
            ;; the law page's words, which a seat's page leads with
            ;; (`reads-as`, ticket 9935e3a3)
            [waymark10.law-words :as lw]))

(set! *warn-on-reflection* true)

(defn first-sentence
  "The first sentence of `s`, its whitespace made even; all of it when
  it has no sentence end; nil when blank."
  [s]
  (when-some [t (some-> s str (str/replace #"\s+" " ") str/trim not-empty)]
    (or (re-find #"^.*?[.!?](?=\s|$)" t) t)))

(defn reads-as
  "A seat in words, as its page leads with it (ticket 9935e3a3): its
  charter's first sentence, `Wakes when: …` from the door subscriptions
  on its fire door (E-5.4) and `May: …`
  from scope, both phrased as the org's law page phrases them
  (waymark10.law-words), and `works`: one queue for each kind an entry
  of its scope may move, with its collection's href and the count of
  rows waiting in a state one of the entry's doors leaves. `rdef-of`
  answers a kind's rdef or nil; `find'` is the render's find, or nil,
  when no queue is counted and no wake is read."
  [rdef-of find' row]
  (let [{:keys [charter scope]} (:data row)
        rdef-of (or rdef-of (constantly nil))
        wakes (when find'
                (for [sub (find' :subscription {:state "active"} {:limit 500})
                      :when (and (= "door" (some-> (get-in sub [:data :receiver :type]) name))
                                 (= (str (:id row)) (str (get-in sub [:data :receiver :id]))))
                      kind (get-in sub [:data :kinds])
                      a (get-in sub [:data :actions])]
                  (lw/wake-phrase rdef-of kind a)))
        may (map lw/scope-phrase scope)
        works (when find'
                (reduce
                 (fn [acc {:keys [kind actions ids filter]}]
                   (let [r (when (seq actions) (rdef-of kind))]
                     (if (or (nil? r) (some #(= (name kind) (:kind %)) acc))
                       acc
                       (let [only (some->> ids (map str) set)
                             plural (or (:plural r) (str (name kind) "s"))
                             n (count (for [s (lw/from-states r actions)
                                            x (find' (keyword (name kind))
                                                     (assoc (or filter {}) :state (name s))
                                                     {:limit 1000})
                                            :when (or (nil? only) (contains? only (str (:id x))))]
                                        x))]
                         (conj acc {:kind (name kind)
                                    :href (str "/api/" plural)
                                    :waiting n
                                    :label (str (lw/capital (lw/words plural)) " (" n " waiting)")})))))
                 [] scope))
        lead (first-sentence charter)
        ;; its open sitting, so the page links to the seat at work
        ;; (ticket ff5bfbb9)
        open (when find'
               (first (find' :sitting {:seat (str (:id row)) :state "open"}
                             {:limit 1})))]
    (cond-> {:works (vec works)}
      lead (assoc :charter lead)
      (seq wakes) (assoc :wakes (str "Wakes when: " (lw/either wakes) "."))
      (seq may) (assoc :may (str "May: " (str/join "; " may) "."))
      open (assoc :sitting {:href (str "/api/sittings/" (:id open))
                            :label "At work now: watch its sitting"}))))

(defn reads-as-field
  "The seat's :computed `reads_as`: `reads-as` over the registry and the
  find the render ctx lends."
  [row ctx]
  (reads-as (:rdef-of ctx) (:find ctx) row))

(defn- said-done
  "An action's name said as done, for a line of a sitting: qualify →
  qualified, mark_paid → marked paid."
  [action]
  (let [[head & more] (str/split (lw/words (str action)) #" ")
        head (cond (str/ends-with? head "e") (str head "d")
                   (re-find #"[^aeiou]y$" head) (str (subs head 0 (dec (count head))) "ied")
                   :else (str head "ed"))]
    (str/join " " (cons head more))))

(defn- call-row-name
  "What a call named, as its owner calls it: the row's summary line up
  to its first ` · `, or the kind when the answer carried no line."
  [{:keys [row_summary row_kind]}]
  (or (some-> row_summary str (str/split #" · ") first str/trim not-empty)
      (lw/words (str row_kind))))

(defn- tool-words
  "A tool's name in words, never its raw key: waymark_get → get,
  bench__edit_many → bench edit many."
  [tool]
  (str/trim (str/replace (str/replace (str tool) #"^waymark_" "") #"_+" " ")))

(defn- seat-words
  "A seat's name as its page says it, never its raw key:
  dress_rehearsal-mayor → Dress rehearsal mayor, as the presence chip
  says it (ticket 4a984e27)."
  [seat-name]
  (lw/capital (str/trim (str/replace (str seat-name) #"[_-]+" " "))))

(defn call-words
  "One call of a sitting in its owner's words (ticket ff5bfbb9): `Read
  Acme Robotics`, `Qualified Acme Robotics: <why>`, `Asked the
  principal to decline Cedar Bakery: <why>`. `c` is a sitting_call's
  data; the why is the caller's own sentence, when it gave one."
  [{:keys [tool action outcome row_id row_kind why] :as c}]
  (let [what (call-row-name c)
        door (lw/words (str action))
        tail (if-some [w (some-> why str str/trim not-empty)] (str ": " w) "")
        n (long (or (:count c) 0))]
    (case (str outcome)
      "read" (cond (seq row_id) (str "Read " what)
                   (seq row_kind) (str "Looked through " n " " (lw/words (str row_kind))
                                       (if (= 1 n) " row" " rows"))
                   :else (str "Read " (tool-words tool)))
      "moved" (str (lw/capital (said-done action)) " " what tail)
      "held" (str "Asked the principal to " door " " what tail)
      "refused" (str "Was refused: " (if (seq action) (str door " " what) (tool-words tool)))
      "rehearsed" (str "Tried " door " on " what ", without writing")
      "scheduled" (str "Scheduled " door " " what " for later" tail)
      (if (= "waymark_sit" (str tool))
        "Sat in the seat"
        (str "Called " (tool-words tool) tail)))))

(defn at-work
  "A sitting as its page leads with it (ticket ff5bfbb9): the seat's
  name and `Working` while it is open or `Done`, then its calls from
  the record of calls (sitting_call), newest last, each in its owner's
  words (`call-words`) with a link to the row it named. A call whose
  answer carried no summary line (a held call's) borrows the line an
  earlier call on the same row saw. `rdef-of` answers a kind's rdef or
  nil, for that link's collection."
  [rdef-of seat-name row calls]
  (let [rdef-of (or rdef-of (constantly nil))
        open? (= "open" (some-> (:state row) name))]
    {:lead (str (or (some-> seat-name str not-empty seat-words) "The seat") " · "
                (if open? "Working" "Done"))
     :working open?
     :calls (first
             (reduce
              (fn [[out seen] c]
                (let [kind (some-> (:row_kind c) str not-empty)
                      id (some-> (:row_id c) str not-empty)
                      line (some-> (:row_summary c) str not-empty)
                      c (cond-> c (and id (not line) (seen id)) (assoc :row_summary (seen id)))
                      r (when (and kind id) (rdef-of (keyword kind)))]
                  [(conj out (cond-> {:n (long (or (:n c) 0))
                                      :outcome (str (:outcome c))
                                      :text (call-words c)}
                               r (assoc :href (str "/api/" (or (:plural r) (str kind "s")) "/" id))))
                   (cond-> seen (and id line) (assoc id line))]))
              [[] {}]
              (sort-by #(long (or (:n %) 0)) calls)))}))

(defn at-work-field
  "The sitting's :computed `at_work`: `at-work` over the calls the
  render ctx's find answers and the seat its read answers."
  [row ctx]
  (when-some [find' (:find ctx)]
    (let [rdef-of (:rdef-of ctx)
          seat-name (when-some [read' (:read ctx)]
                      (some->> (get-in row [:data :seat]) str not-empty
                               (read' :seat) :data :name))
          calls (when (and rdef-of (rdef-of :sitting_call))
                  (map :data (find' :sitting_call {:sitting (str (:id row))}
                                    {:limit 1000})))]
      (at-work rdef-of seat-name row calls))))
