(ns workqueue10.resources.media
  "The Media resource: the household's consumption queue — movies,
  shows, books, audiobooks, comics — as ONE Mirror kind over its own
  confluence. The RSS move, made again: do not build a library,
  mirror the intent. One canonical envelope, the :medium tag
  differentiating, every catalog an authority behind the same
  TaskSource protocol the task queue already runs (spec-media.md;
  the first wired authority is the household's own flickr engine,
  spec-media-flickr.md).

  THE ROW IS THE UNIT OF INTENT — the thing you would say out loud
  when queueing. Episodes, seasons and issues live inside
  :progress_text/:progress, never as rows; the moment an episode
  becomes a row this kind becomes a catalog and drowns in its own
  inventory.

  DOMAIN STATE IS DATA (the mirror rule): the consumption lifecycle
  — queued → active → finished, abandoned as the honest exit — is
  :status; the machine here is the sync machine, exactly as task.clj
  declares. flickr only ever speaks active/finished; queued and
  abandoned are the hub's own words, and a work with no watch state
  at all carries a nil status — the gap renders, the same sentence
  :assignee earned.

  THE FRACTION LAW: every medium measures position in its own unit
  and none of them compare, so :progress is the canonical fraction
  of the whole beside :progress_text — the authority's own words,
  untranslated (\"S02E05 · 12:30\" beside 0.43). A source that knows
  position but not extent leaves the fraction nil beside intact
  text.

  :document :partial IS THE HUB'S SHIELD, carrying three facts no
  authority speaks: :priority (\"what do we watch tonight?\" — the
  ranking, lower ranks sooner, nulls last), queued/abandoned status,
  and any position logged by hand for the media no service tracks.
  Absence in a pulled document is silence, never an unset.

  :audience_name / :audience is the assignee pattern verbatim: the
  source's raw name beside its resolvable member projection, the gap
  rendering until a matching handle arrives. :work_key is the
  catalog-neutral identity when spoken (\"tmdb:603\") — stored from
  day one so the one-work-two-authorities merge law stays a healable
  punt: dupes are visible, not resolved.

  CAPTURE IS THE HUB'S. A task is born here and pushed to the engine
  that will own it; a media row often has NO owner to push to — the
  recommendation heard at dinner. The birth door defaults
  :source \"hub\" (the noop authority, sources/hub.clj) and the enum
  admits nothing else: flickr's works are scanned from its own
  files, never typed here. If a real catalog authority later takes
  births, widening the enum must not move anyone who never asked.

  LOCAL WRITES, per the spec's list and nothing else: start / finish
  / abandon (status writes — pushed only where an authority has an
  opinion, and no wired source does today, so every push is the
  shared :noop freshness round-trip), log_progress (position, hub-
  recordable for paper books and borrowed comics), and prioritize /
  deprioritize copied from the task kind under the same :ranker
  role. Ratings and reviews are a different feature wearing this
  one's coat — a sibling kind if ever wanted, not fields here.

  A WORK THE FEED STOPPED CARRYING (the feed ANSWERED, the work was
  absent — flickr's deletion resync mark cashes out as exactly this
  observation) is abandoned, not deleted: the household let it go,
  and a queue that can see what it gave up on stops re-queueing it.

  Recorded punts, inherited from the spec: the merge law, hierarchy,
  availability (\"where can we stream this?\" is a render-time
  lookup, never a stored fact), recommendations from taste (the AI
  is a client, not a service), and per-person progress — observed in
  flickr's plural audiences, mapped by the addendum's rule, still
  punted."
  (:require [waymark10.dsl :refer [defhandler resource]]
            [waymark10.guards :as g]
            [waymark10.server.mirror :as mirror]))

;; a queue of intent moves at human cadence — slower than the work
;; queue's 300s; the authority's own feed is cursored anyway
(def ttl-seconds 900)
(def discover-every 900)

;; local writes move between the writable sync states (the machine
;; owns conflicted; resolve_conflict is the way back)
(def ^:private writable #{:fresh :stale :unreachable})

(defhandler start-consuming [row _inp _ctx]
  (assoc-in row [:data :status] "active"))

(defhandler mark-finished [row _inp _ctx]
  (assoc-in row [:data :status] "finished"))

(defhandler mark-abandoned [row _inp _ctx]
  (assoc-in row [:data :status] "abandoned"))

(defhandler record-progress [row inp _ctx]
  (-> row
      (assoc-in [:data :progress_text] (:progress_text inp))
      (assoc-in [:data :progress] (:progress inp))))

(defhandler set-priority [row inp _ctx]
  (assoc-in row [:data :priority] (:priority inp)))

(defhandler clear-priority [row _inp _ctx]
  (assoc-in row [:data :priority] nil))

(defn media-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :media
     :plural "media"
     :summary "{data.title} · {data.medium} · {data.status}"
     :label-template "{data.title}"
     :schema [:map
              [:title {:optional true}
               [:maybe [:string {:max 200}]]]
              ;; the envelope unifies, the medium differentiates —
              ;; RSS's lesson, one tag
              [:medium {:optional true :filter #{:eq :in}
                        :x-display {:showcase true}}
               [:maybe [:enum "movie" "show" "book" "audiobook" "comic"]]]
              ;; the consumption lifecycle, as data; abandoned is a
              ;; first-class state, never a deletion
              [:status {:optional true :filter #{:eq :in}
                        :x-display {:showcase true}}
               [:maybe [:enum "queued" "active" "finished" "abandoned"]]]
              ;; author, director, showrunner — text, not a ref: an
              ;; open vocabulary, because a creator is a label to
              ;; filter by, not an account to resolve
              [:creator {:optional true :filter #{:eq}}
               [:maybe [:waymark/vocab {:open true}]]]
              [:year {:optional true}
               [:maybe :int]]
              ;; the fraction law: the authority's own words, and the
              ;; canonical projection beside them — never one without
              ;; room for the other
              [:progress_text {:optional true
                               :x-display {:label "Position (the authority's own words)"}}
               [:maybe [:string {:max 100}]]]
              [:progress {:optional true
                          :x-display {:label "Fraction of the whole"}}
               [:maybe [:decimal {:min 0 :max 1}]]]
              ;; the catalog-neutral identity, when spoken — the guid
              ;; that keeps the merge punt healable
              [:work_key {:optional true :filter #{:eq}
                          :x-display {:label "Work key (catalog-neutral)"}}
               [:maybe [:string {:max 256}]]]
              ;; WHO it's for — the assignee pattern verbatim: raw
              ;; text beside its resolvable member projection
              [:audience_name {:optional true :filter #{:eq}
                               :x-display {:label "For (as named)"}}
               [:maybe [:waymark/vocab {:open true}]]]
              [:audience {:optional true :filter #{:eq}
                          :kind :member
                          :external-key :audience_name
                          :match :handle
                          :x-display {:label "For"}}
               [:maybe :waymark/ref]]
              ;; the confluence's routing tag — which authority this
              ;; row drinks from; the enum is the tag set main wires
              [:source {:optional true :filter #{:eq :in}}
               [:maybe [:enum "flickr" "hub"]]]
              ;; HUB-LOCAL: the cross-catalog ranking no authority
              ;; carries — :partial keeps every pull's hands off it
              [:priority {:optional true :sort :default
                          :x-display {:label "Priority (lower ranks sooner)"}}
               [:maybe [:int {:min 0}]]]
              ;; where the row drinks from, as URLs the source
              ;; stamps. Hidden: the :origin LINK is the affordance,
              ;; a raw URL in the fields is noise.
              [:source_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]
              [:source_ui_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]]
     :filterable {:state #{:eq :in}}
     :display {:title "{data.title}"}
     ;; CAPTURE: the one decision the parent spec forced, cashed out.
     ;; The birth input is what you'd say out loud — a title and its
     ;; medium, the rest optional — and it stays HERE: :source
     ;; defaults to "hub" (the noop authority) and admits nothing
     ;; else, because no wired catalog takes births. The create-push
     ;; law still runs — the hub mints the identity — so a hub row is
     ;; an ordinary mirror row, adoptable by a future authority
     ;; through its :work_key.
     :create-schema [:map
                     [:title {:x-display
                              {:label "What is it called"
                               :help "The name you would say out loud when somebody asks what we should watch — the words on the poster or the spine."}}
                      [:string {:min 1 :max 200}]]
                     [:medium {:x-display
                               {:label "What kind of thing"
                                :choices {"movie" "Movie — one evening, start to finish"
                                          "show" "Show — episodes we work through"
                                          "book" "Book — read on paper or a screen"
                                          "audiobook" "Audiobook — listened to"
                                          "comic" "Comic — issues, or a graphic novel"}}}
                      [:enum "movie" "show" "book" "audiobook" "comic"]]
                     [:creator {:optional true
                                :x-display
                                {:label "Who made it"
                                 :help "The author, director or showrunner — the name we would go looking under later; leave it empty if nobody remembers."}}
                      [:maybe [:waymark/vocab {:open true}]]]
                     [:year {:optional true
                             :x-display {:label "Year it came out"}}
                      [:maybe :int]]
                     [:audience_name {:optional true
                                      :x-display
                                      {:label "For (a name)"
                                       :help "Who in the house this one is meant for — a name that matches somebody's handle links it to them, any other name simply stands as written."}}
                      [:maybe [:waymark/vocab {:open true}]]]
                     [:source {:optional true
                               :x-display
                               {:label "Where this row lives"
                                :choices {"hub" "Here at home — the house keeps this one itself"}}}
                      [:maybe [:enum "hub"]]]]
     :on-create (fn [row _ctx]
                  (-> row
                      (update-in [:data :source] #(or % "hub"))
                      (update-in [:data :status] #(or % "queued"))))
     ;; the way BACK to the work at the engine that keeps it: flickr's
     ;; verified hash deep links. A hub row stamps no href and the
     ;; link simply omits — the framework's own rule.
     :links [{:rel "origin" :href "{data.source_ui_href}" :external true
              :summary "This work at the engine that keeps it"}]
     :actions
     {:start
      {:from writable :to :fresh
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Starting is a status the queue records — the next word from a source that tracks playback overwrites it honestly."}
       :handler start-consuming
       :display {:label "Start" :style :primary :order 1}}

      :finish
      {:from writable :to :fresh
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Finished is the queue's record; no wired authority hears about it yet, and the feed's own word may honestly disagree."}
       :handler mark-finished
       :display {:label "Finished" :order 2}}

      ;; the honest exit — a first-class state, so the household can
      ;; see what it gave up on instead of re-queueing it
      :abandon
      {:from writable :to :fresh
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Abandoned keeps the record — nothing is deleted, and start picks it back up if the household relents."}
       :handler mark-abandoned
       :display {:label "Abandon" :order 3}}

      ;; position for the media no service tracks — paper books,
      ;; borrowed comics: the strongest case for this being hub-local
      ;; law rather than a push. Both halves of the fraction law land
      ;; together; an unknown extent honestly leaves the fraction off.
      :log_progress
      {:from writable :to :fresh
       :input [:map
               [:progress_text
                {:x-display {:label "Where you are, in your own words"
                             :help "However this medium counts itself — \"S02E05\", \"page 140\", \"halfway through disc two\"; nothing translates it, it is kept exactly as you write it."}}
                [:string {:min 1 :max 100}]]
               [:progress
                {:optional true
                 :x-display {:label "Fraction of the whole (0–1), if known"}}
                [:maybe [:decimal {:min 0 :max 1}]]]]
       :edit {:prefill [:progress_text :progress]}
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Nothing is lost — a new position overwrites this one; log again to correct it."}
       :handler record-progress
       :display {:label "Log progress" :order 4}}

      ;; ranking is the RANKER role's, copied from the task kind:
      ;; anyone may finish a book, only rankers reorder the night
      :prioritize
      {:from writable :to :fresh
       :guards [(g/role :ranker)]
       :input [:map [:priority {:x-display {:label "Rank (lower ranks sooner)"}}
                     [:int {:min 0}]]]
       :edit {:prefill [:priority]}
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Nothing is lost — a new rank overwrites this one; prioritize again to change it."}
       :handler set-priority
       :display {:label "Prioritize" :order 5}}

      :deprioritize
      {:from writable :to :fresh
       :guards [(g/role :ranker)]
       :safety {:idempotent true :reversible false :confirm false
                :one-way "The rank is let go — the row rejoins the unranked tail; prioritize ranks it again."}
       :handler clear-priority
       :display {:label "Clear priority" :order 6}}}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     ;; every push is the shared :noop today (no wired authority takes
     ;; a media write); the declaration stays because the local writes
     ;; ride it, and the freshness round-trip earns their :to :fresh
     :push-on-write true
     :create-push true
     :document :partial
     ;; a work the feed stopped carrying (the feed ANSWERED — flickr's
     ;; deletion resync mark, cashed out through the full-list batch)
     ;; is the household letting it go: abandoned, kept as record. The
     ;; hub source never answers gone, so its rows are untouchable
     ;; here.
     :on-gone {:set {:status "abandoned"}}
     ;; the cadenced whole-kind heal — one cursorless feed read per
     ;; pass at this source's shape, so deletions and translation
     ;; changes land within the hour, not at the next boot
     :resync-every 3600})))
