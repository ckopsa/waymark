(ns workqueue10.resources.weather
  "The hearth thermometer (waymark-tti.1): one-touch, coarse,
  first-person weather reports — how each inhabitant is doing, in
  three words, shared with the whole household.

  :weather is one row per REPORT, append-only: \"current weather\" is
  simply the newest row per owner, and a wrong tap is corrected by
  tapping again — weather passes, it is not edited. So there are no
  actions beyond create: no update, no retire, no cross-row state
  flipping. Coarse by design — exactly three skies (quiet / steady /
  loud), never a numeric scale — because this is weather, not a
  diagnosis, and not mood tracking.

  VISIBILITY is household-shared, deliberately NOT own-surface (the
  dwelling kinds' privacy would defeat the point — the family reads
  each other's sky). Humans run unscoped and see all (the framework
  default); agents ride the normal grant machinery — a scope entry
  {kind \"weather\" actions [\"create\"]} grants read+create.

  The one rule this file owns is FIRST PERSON: the engine stamps the
  owner from the principal, and the create guard refuses a foreign
  owner from EVERYONE — unlike dwelling there is no on-behalf path,
  not even for a recovery-admin human. Nobody reports the sky for
  you."
  (:require [clojure.string :as str]
            [waymark10.dsl :refer [defguardfn defresource defscenario]]
            [waymark10.types :as t]))

(defn- supplied-owner [inp]
  (some-> (:owner inp) str str/trim not-empty))

;; the first-person wall: the supplied owner must be nil (the engine
;; stamps the caller) or the caller's own id — for humans AND agents,
;; with no curator branch. Judged against the create BODY because
;; create guards run with row nil BEFORE :on-create (invoke.clj
;; create-in-tx!: create-guard-pass precedes the :on-create call), so
;; the stamp cannot be read yet; the guard applies the same rule one
;; write earlier.
(defguardfn weather-is-first-person
  {:reads [:principal]
   :explain "Weather is first-person; nobody reports the sky for you."}
  [_row inp ctx]
  (let [supplied (supplied-owner inp)]
    (if (or (nil? supplied) (= supplied (:id (:principal ctx))))
      (t/allow) (t/deny))))

;; owner is stamped by the ENGINE, never trusted from the body: always
;; the caller's own id (the guard already refused a foreign one), so
;; even a body that lies about :owner cannot report another's sky.
(defn- stamp-owner [row ctx]
  (assoc-in row [:data :owner] (:id (:principal ctx))))

;; the file's one rule, written down as a sentence the house can
;; check. The create door judges the BODY (row nil, before the
;; stamp), so the scenario names an :input and no :row — and since
;; the only create guard declares :reads [:principal], it is judged
;; with no database at all.

(defscenario nobody-reports-the-sky-for-you
  "A weather report signed with someone else's name is refused at the
   door — there is no on-behalf path here, not even for a curator."
  {:kind    :weather
   :attempt :create
   :input   {:owner "iris" :sky "loud"}
   :as      {:id "otto" :type :person :roles #{:recovery-admin}}
   :expect  {:refused :weather-is-first-person
             :because "Weather is first-person"}})

(defscenario your-own-sky-passes
  "Reporting your own weather is free for anyone in the house, named
   or unnamed — the engine stamps the owner either way."
  {:kind    :weather
   :attempt :create
   :input   {:sky "quiet"}
   :as      {:id "otto" :type :person}
   :expect  {:allowed true}})

(defresource weather
  {:kind :weather
   :plural "weathers"
   :states [:noted]
   :initial :noted
   :terminal #{}
   ;; :noted is the whole life — a report is born and never moves, so
   ;; the dead-end is the design, not an omission
   :allow-dead #{:noted}
   :nav :system
   :summary "{data.sky} · {data.owner}"
   :label-template "{data.sky}"
   :schema [:map
            ;; WHOSE weather — the reporting principal's id. Optional
            ;; in the schema because the engine stamps it (on-create);
            ;; a persisted row always carries it.
            [:owner {:optional true :x-display {:raw true}}
             [:maybe [:string {:min 1 :max 128}]]]
            ;; exactly three skies — coarse by design
            [:sky [:enum "quiet" "steady" "loud"]]
            ;; a glance, not an essay — plain text (raw), never a
            ;; prose widget
            [:note {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 280}]]]]
   :create-schema [:map
                   [:owner {:optional true} [:maybe [:string {:min 1 :max 128}]]]
                   [:sky [:enum "quiet" "steady" "loud"]]
                   [:note {:optional true} [:maybe [:string {:max 280}]]]]
   :filterable {:owner #{:eq} :sky #{:eq} :state #{:eq}}
   ;; current weather = newest row per owner; newest-first is the law
   :sortable {:fields [:created_at] :default "-created_at"}
   :create-guards [weather-is-first-person]
   :scenarios [nobody-reports-the-sky-for-you your-own-sky-passes]
   :on-create stamp-owner
   ;; no actions beyond create: a wrong tap is corrected by tapping
   ;; again (latest row wins) — weather passes, it is not edited
   :actions {}})
