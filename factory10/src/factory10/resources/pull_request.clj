(ns factory10.resources.pull-request
  "The `pull_request` kind: one GitHub pull request, mirrored through the
  engine's Mirror (docs/spec-change.md §3, R-C.1 and R-C.2; ticket
  d2ceedc9).

  ITS MACHINE IS THE SYNC MACHINE AND NOTHING ELSE. Every field is the
  adapter's to write, and no door here is a person's. The advance
  doors, the events and the link to `change` come in later pieces of
  the spec, and so do the checks fields: `pull` makes one GitHub read
  per row.

  DISCOVERY ASKS THE HOUSE, NOT GITHUB. The kit's ForgeSource has no
  verb that lists a repository's open pull requests, and this kind must
  not cost a second GitHub read. So `discover` reads storage: the
  number of every `change` row in a repository an active `repo_policy`
  names, in any state but merged or closed. The forge pass mints those
  rows, so every pull request the house hears of is covered. One opened
  with no change yet is minted by the `pull_request.opened` webhook
  (ticket 73fd079f), not here.

  PULL IS THE FORGE'S OWN `forge-pull`, one read per row. It writes
  nothing and moves no cursor: the forge pass's window and its `change`
  rows are untouched by this kind.

  THE ADAPTER IS CONNECTED AT BOOT. A kind is declared before the
  engine it serves exists, so the adapter holds the engine and the
  forge source in an atom that `connect!` fills. Until then it
  discovers nothing and every pull throws, so an engine with no forge
  mirrors nothing."
  (:require [factorykit.forge :as kit]
            [waymark10.dsl :refer [defscenario resource]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

;; the forge pass's own five minutes (forge/default-every-seconds)
(def ttl-seconds 300)
(def discover-every 300)

(def ^:private ended
  "The change states whose pull request is no longer the mirror's to
  find."
  #{"merged" "closed"})

(def ^:private forge-fields
  "What this kind keeps of the forge's change document, by the same
  names."
  [:repository :number :url :title :author :draft :labels
   :base_branch :head_branch :head_sha :mergeable :review_state
   :files_changed :lines_added :lines_removed :touched_paths])

(defn external-id
  "`<owner>/<name>#<number>`, the row's identity."
  [repository number]
  (str repository "#" number))

(defn- parse-external-id [xid]
  (when-some [[_ repository n] (re-matches #"(.+/.+)#(\d+)" (str xid))]
    [repository (parse-long n)]))

(defn- rows
  "Every row of `kind` matching `where`, decoded; none in an engine that
  does not declare the kind."
  [eng kind where]
  (if-some [rd (get (inv/resources eng) kind)]
    (let [st (:storage eng)]
      (mapv #(inv/decode-row rd %)
            (store/with-tx st
              (fn [tx] (store/query-rows st tx kind where {:all true})))))
    []))

(defn open-pull-requests
  "The external ids discovery answers: one for each `change` row with a
  number, in a repository an active policy names, that is not merged or
  closed. Storage only."
  [eng]
  (let [repositories (into (sorted-set)
                           (keep #(some-> (get-in % [:data :repository])
                                          str not-empty))
                           (rows eng :repo_policy {:state :active}))]
    (into []
          (comp (mapcat #(rows eng :change {:repository %}))
                (remove #(contains? ended (name (:state %))))
                (keep (fn [row]
                        (when-some [n (get-in row [:data :number])]
                          (external-id (get-in row [:data :repository]) n))))
                (distinct))
          repositories)))

(defn- document
  "The forge's change document → this kind's. The two endings' instants
  ride the document's metadata (github/pull->doc), because the change
  kind has no field for them."
  [doc]
  (let [m (meta doc)]
    (cond-> (select-keys doc forge-fields)
      (:forge_state doc) (assoc :gh_state (:forge_state doc))
      (:merged_at m) (assoc :merged_at (:merged_at m))
      (:closed_at m) (assoc :closed_at (:closed_at m)))))

(defn- pull-one [conn xid]
  (let [source (or (:source @conn)
                   (throw (ex-info "no forge is connected to the pull_request mirror"
                                   {:external-id xid})))
        [repository number] (or (parse-external-id xid)
                                (throw (ex-info (str "not a pull request id: " xid)
                                                {:external-id xid})))
        doc (or (kit/forge-pull source repository number)
                (throw (ex-info (str xid " answered no pull request")
                                {:external-id xid})))
        d (document doc)]
    [d (str (hash d))]))

(defrecord ForgeMirror [conn]
  mirror/MirrorAdapter
  (discover [_]
    (if-some [eng (:engine @conn)] (open-pull-requests eng) []))
  (pull [_ xid] (pull-one conn xid))
  (pull-many [_ xids]
    ;; a number the forge answers 404 for is gone; any other failure is
    ;; the forge's, and throws for the whole batch
    (reduce (fn [m xid]
              (try (assoc m xid (pull-one conn xid))
                   (catch clojure.lang.ExceptionInfo e
                     (if (= 404 (:status (ex-data e)))
                       (assoc m xid :gone)
                       (throw e)))))
            {} xids))
  (push [_ _ _]
    (throw (ex-info "the pull_request mirror is pull-only" {}))))

(defn forge-mirror
  "A fresh adapter, connected to nothing."
  []
  (->ForgeMirror (atom nil)))

(def the-mirror
  "The adapter the module's own `pull_request` kind is declared with."
  (forge-mirror))

(defn connect!
  "Hand the adapter the engine its discovery reads and the forge source
  its pull spends — the same source the forge pass runs."
  ([eng source] (connect! the-mirror eng source))
  ([adapter eng source]
   (reset! (:conn adapter) {:engine eng :source source})
   adapter))

(def ^:private a-mirrored-pull-request
  {:external_id "ckopsa/waymark#31"
   :repository "ckopsa/waymark"
   :number 31
   :title "A pull request the house mirrors"
   :gh_state "open"})

(defscenario a-seat-does-not-mark-a-pull-request-stale
  "Every field is the forge's and every door is the engine's: a seat
   that thinks a row is out of date has nothing to write here."
  {:kind    :pull_request
   :attempt :mark_stale
   :row     {:state :fresh :data a-mirrored-pull-request}
   :as      {:id "code-seat" :type :agent}
   :expect  {:refused :system-only}})

(defscenario a-person-does-not-mark-a-pull-request-unreachable
  "Nor does a person: whether GitHub answers is the sync's to record."
  {:kind    :pull_request
   :attempt :mark_unreachable
   :row     {:state :fresh :data a-mirrored-pull-request}
   :as      {:id "colton" :type :person}
   :expect  {:refused :system-only}})

(defn pull-request-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :pull_request
     :plural "pull_requests"
     ;; the day job's work, like `change`
     :nav :secondary
     :summary "{data.repository}#{data.number} · {data.title}"
     :label-template "{data.repository}#{data.number}"
     :display {:title "{data.title}"}
     :unique [[:external_id]]
     ;; the sync leaves every state it reaches, and no seat does: the
     ;; cracks audit reads it here
     :left-by [{:in #{:stale :unreachable :conflicted} :by :mirror
                :via :observe_external :to :fresh}
               {:in #{:fresh} :by :mirror :via :mark_stale :to :stale}]
     :schema
     [:map
      [:repository {:optional true :filter #{:eq :in}
                    :x-display
                    {:label "The repository"
                     :help "owner/name, as GitHub spells it. ckopsa/waymark is one."}}
       [:maybe [:string {:max 140}]]]
      [:number {:optional true
                :x-display
                {:label "The pull request number"
                 :help "The number GitHub gave the pull request in its repository."}}
       [:maybe [:int {:min 1}]]]
      [:url {:optional true :x-display {:hidden true}}
       [:maybe [:string {:max 500}]]]
      [:title {:optional true
               :x-display
               {:raw true :label "Title"
                :help "The pull request's title, exactly as GitHub shows it."}}
       [:maybe [:string {:max 400}]]]
      [:author {:optional true
                :not-a-ref "A GitHub login: a handle, not a row id or a principal here."
                :x-display
                {:label "Who opened it"
                 :help "The GitHub login of the account that opened it."}}
       [:maybe [:string {:max 120}]]]
      [:draft {:optional true
               :x-display
               {:label "A draft"
                :help "True while GitHub marks it a draft, which no merge takes."}}
       [:maybe :boolean]]
      [:labels {:optional true
                :x-display
                {:label "Its labels"
                 :help "The labels on the pull request at GitHub, by name."}}
       [:maybe [:vector [:string {:max 100}]]]]
      [:base_branch {:optional true
                     :x-display
                     {:label "The base branch"
                      :help "The branch it would merge into."}}
       [:maybe [:string {:max 200}]]]
      [:head_branch {:optional true
                     :x-display
                     {:label "The head branch"
                      :help "The branch its commits are on."}}
       [:maybe [:string {:max 200}]]]
      [:head_sha {:optional true
                  :x-display
                  {:label "The head commit"
                   :help "The full sha of the head branch's newest commit."}}
       [:maybe [:string {:max 64}]]]
      [:mergeable {:optional true :filter #{:eq :in}
                   :x-display
                   {:label "Whether it merges"
                    :help "What GitHub says a merge would do now."
                    :choices {"clean" "It merges cleanly"
                              "conflicted" "It conflicts with its base"
                              "blocked" "A rule of the repository blocks the merge"
                              "unknown" "GitHub has not worked it out yet"}}}
       [:maybe [:enum "clean" "conflicted" "blocked" "unknown"]]]
      [:review_state {:optional true :filter #{:eq :in}
                      :x-display
                      {:label "What the reviews say"
                       :help "The reviews read together: a request for changes outranks an approval."
                       :choices {"pending" "Nobody has reviewed it"
                                 "approved" "A reviewer approved it"
                                 "changes_requested" "A reviewer asked for changes"
                                 "commented" "Reviewers commented and did not decide"}}}
       [:maybe [:enum "pending" "approved" "changes_requested" "commented"]]]
      [:files_changed {:optional true
                       :x-display
                       {:label "Files changed"
                        :help "How many files the pull request changes."}}
       [:maybe [:int {:min 0}]]]
      [:lines_added {:optional true
                     :x-display
                     {:label "Lines added"
                      :help "How many lines it adds, as GitHub counts them."}}
       [:maybe [:int {:min 0}]]]
      [:lines_removed {:optional true
                       :x-display
                       {:label "Lines removed"
                        :help "How many lines it removes, as GitHub counts them."}}
       [:maybe [:int {:min 0}]]]
      [:touched_paths {:optional true
                       :x-display
                       {:label "The paths it touches"
                        :help "The files it changes, up to the first hundred. Read while it is open."}}
       [:maybe [:vector [:string {:max 400}]]]]
      [:gh_state {:optional true :filter #{:eq :in}
                  :x-display
                  {:label "Where it stands on GitHub"
                   :help "GitHub's own state: a closed pull request with a merge time is merged."
                   :choices {"open" "Open"
                             "merged" "Merged"
                             "closed" "Closed without a merge"}}}
       [:maybe [:enum "open" "merged" "closed"]]]
      [:merged_at {:optional true
                   :x-display
                   {:label "When it merged"
                    :help "The moment GitHub merged it."}}
       [:maybe :waymark/instant]]
      [:closed_at {:optional true
                   :x-display
                   {:label "When it closed"
                    :help "The moment GitHub closed it, merged or not."}}
       [:maybe :waymark/instant]]]
     :filterable {:state #{:eq :in}}
     :links [{:rel "origin" :href "{data.url}" :external true
              :summary "The pull request on GitHub"}]
     :scenarios [a-seat-does-not-mark-a-pull-request-stale
                 a-person-does-not-mark-a-pull-request-unreachable]}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every})))

(def pull-request (pull-request-resource the-mirror))
