(ns waymark10.server.members.first-owners
  "An IdP-backed engine's first owners (docs/spec-first-run.md R-4.8,
  ticket 627ad2bf): WAYMARK10_FIRST_OWNERS names IdP subjects (the
  token's `sub`), comma-separated. Read at boot, and only on an engine
  with :oidc, whose claim page and solo owner are both off: while the
  store holds NO member who is a real person (ticket e6792a44: seed
  markers, cast members, seat sitters and agents are the engine's own
  rows, not people), each named subject is born an :active member
  with the solo owner's standing (manager and recovery-admin,
  members/provision!), by the registrar, the log saying it came from
  this variable; a subject already a member is left alone. Once any
  real person is a member the variable is ignored, and
  boot says so in one line: it is a bootstrap, never a backdoor, so a
  member removed later is never brought back by a restart. Blank or
  malformed entries do nothing but warn; nobody it does not name is
  admitted past :invited-only. Split out of members.clj for size."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(def env-var "WAYMARK10_FIRST_OWNERS")

(defn- warn!
  [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 first owners: " parts))))

(defn- malformed?
  "A subject is one opaque token: blank, or carrying whitespace or a
  control character, it names nobody."
  [s]
  (or (str/blank? s) (boolean (re-find #"[\s\p{Cntrl}]" s))))

(defn subjects
  "The variable's value → {:subjects [distinct, in order] :malformed n}."
  [raw]
  (let [entries (map str/trim (str/split (str raw) #"," -1))]
    {:subjects (vec (distinct (remove malformed? entries)))
     :malformed (count (filter malformed? entries))}))

(defn- member-rows [eng]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/query-rows st tx :member {} {:all true})))))

(defn person?
  "A member row a real person holds: human (or no actor_type at all),
  not a seed's cast, not a seed's completion marker (`seed-<name>`),
  not a seat's sitter (`seat:<id>`), and not answering to the
  registrar."
  [row]
  (let [{:keys [actor_type cast subject]} (:data row)
        id (str (:id row))
        subject (str subject)]
    (and (contains? #{nil "" "human"} actor_type)
         (not (true? cast))
         (not (str/starts-with? id "seed-"))
         (nil? (members/seat-id id))
         (nil? (members/seat-id subject))
         (not= (:id members/registrar) id)
         (not= (:id members/registrar) subject))))

(defn ensure!
  "Born the first owners `raw` names, as the namespace doc says, and
  answer the member rows born — nil when nothing was. The one-arg form
  reads the process environment."
  ([eng] (ensure! eng (System/getenv ^String env-var)))
  ([eng raw]
   (when (and (:oidc eng)
              (some? raw)
              (contains? (inv/resources eng) :member))
     (let [{named :subjects bad :malformed} (subjects raw)
           rows (member-rows eng)
           others (count (remove person? rows))
           had (set (map (comp str :id) rows))]
       (when (pos? bad)
         (warn! bad " blank or malformed entr" (if (= 1 bad) "y" "ies")
                " in " env-var " ignored"))
       (cond
         (empty? named) nil

         (some person? rows)
         (warn! env-var " ignored: this engine already has a person as a "
                "member, and the variable only bootstraps an engine with "
                "none (" others " non-person member row"
                (when (not= 1 others) "s") " seen too)")

         :else
         (let [roles (members/ensure-solo-roles! eng)
               by (assoc members/registrar
                         :why (str "born a first owner from " env-var))
               born (into []
                          (keep (fn [sub]
                                  (try
                                    (:row (inv/create! eng :member
                                                       (cond-> {:display (subs sub 0 (min (count sub) 80))
                                                                :actor_type "human"
                                                                :subject sub}
                                                         (seq roles) (assoc :roles roles))
                                                       {:principal by :id sub}))
                                    (catch Exception e
                                      (warn! "first owner " (pr-str sub)
                                             " was not born — " (ex-message e))
                                      nil))))
                          (remove had named))]
           (warn! (count born) " first owner" (when (not= 1 (count born)) "s")
                  " born from " env-var
                  (when (pos? others)
                    (str ", beside " others " non-person member row"
                         (when (not= 1 others) "s"))))
           (not-empty born)))))))
