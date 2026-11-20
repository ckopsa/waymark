(ns waymark10.server.grants.owner-mayor
  "The owner's Claude is the mayor from its first call (ticket a4f42818):
  on a house whose seed names an owner, the owner's delegate through the
  house's Claude connector client (`oidc/connector-client`) wears the
  seeded `mayor` seat's grant with no key, no ask and no approval.

  The link is made at the delegate's FIRST SIGN-IN, not by the seed:
  the delegate's id is `<client id>:<sub>`, and the client id lives in
  the engine's :delegate-clients config, which a seed cannot know. So
  the first request of that delegate that finds no grant standing mints
  one citing the seat, the way `seats.grant/mint-seat-grant!` mints a
  sitter's, and `worn-visibility` accepts it on the same request.

  A seat grant is dark to a session outside the seat's `held_for`
  (R-5.7, resolve-seat's model wall), and the seed holds the mayor for
  its model alone. So the link first adds `connector:<client>` to that
  list, through the seat's own `restate` as the seat's owner — the
  person the owner decided this for, recorded in the seat's log.

  Nobody else's delegate is linked, and neither is the owner's delegate
  through any other client. A grant the owner revoked is never minted
  again; one that expired is. One left offered and not accepted blocks
  a second mint, so a refused accept cannot mint on every request.
  Split out of waymark10.server.grants, which keeps room under its
  ns-size entry; nothing here requires grants."
  (:require [clojure.string :as str]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def ^:private actor
  "The approvals actor `grants/approvals-actor` names, spelled here
  because this namespace cannot require grants: the link is minted as
  an approved seat ask's grant is."
  (t/principal {:id "waymark10-grants" :type :system
                :display "Grant approvals"}))

(defn- rows
  "The decoded rows of `kind` matching `where`, or nil on an engine
  that serves no such kind."
  [eng kind where]
  (when-some [rdef (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx (:storage eng)
            (fn [tx]
              (store/query-rows (:storage eng) tx kind where {:limit 100}))))))

(defn- person-ids
  "The ids the person a delegate acts for answers to: the sign-in
  subject, and the member row a binding stamped with it."
  [eng person]
  (into #{person} (map #(str (:id %))) (rows eng :member {:subject person})))

(defn- owners-mayor
  "The active seat named `mayor` that this person owns, or nil."
  [eng person]
  (let [ids (person-ids eng person)]
    (first (filter #(and (= "active" (name (:state %)))
                         (contains? ids (some-> (get-in % [:data :owner]) str)))
                   (rows eng :seat {:name "mayor"})))))

(defn- hold-for-client!
  "The mayor seat held for the connector client too: `connector:<client>`
  added to its `held_for` by its owner's restate, unless it is there.
  → true when the list holds it after."
  [eng seat client]
  (let [token (str "connector:" client)
        held (mapv str (get-in seat [:data :held_for]))
        owner (str (get-in seat [:data :owner]))]
    (or (some #{token} held)
        (try
          (inv/invoke! eng :seat (str (:id seat)) :restate
                       {:patch true
                        :held_for (conj held token)
                        :note "The owner's Claude connector sits here from its first call (ticket a4f42818)."}
                       {:principal (t/principal {:id owner :type :human
                                                 :roles (set (members/held-roles eng owner))})
                        :if-match (inv/etag :seat (str (:id seat)) (:version seat))})
          true
          (catch clojure.lang.ExceptionInfo e
            (binding [*out* *err*]
              (println (str "waymark10 owner-mayor: the mayor seat could not be held for "
                            token " — " (ex-message e) " " (pr-str (ex-data e)))))
            false)))))

(defn link!
  "The grant citing the owner's mayor seat, minted for `principal`
  when it is the owner's delegate through the house's Claude connector
  client and no grant of its for that seat stands, offered or was
  revoked; the seat is held for that client first. → the minted row,
  or nil when nothing was minted."
  [eng principal]
  (when-some [person (some-> (:acts-for principal) str str/trim not-empty)]
    (when-some [client (some-> (:oidc eng) oidc/connector-client)]
      (when (= (str (:id principal)) (oidc/delegate-id client person))
        (when-some [seat (owners-mayor eng person)]
          (let [seat-id (str (:id seat))
                mine (filter #(= seat-id (some-> (get-in % [:data :seat]) str))
                             (rows eng :grant {:audience (str (:id principal))}))]
            (when (and (not-any? #(contains? #{"offered" "accepted" "revoked"} (name (:state %)))
                                 mine)
                       (hold-for-client! eng seat client))
              (let [ttl (long (or (get-in seat [:data :standing_ttl_seconds]) 0))
                    now (Instant/now)]
                (try
                  (:row (inv/create! eng :grant
                                     (cond-> {:audience (str (:id principal))
                                              :seat seat-id
                                              :substitute false}
                                       (pos? ttl)
                                       (assoc :expires_at (str (.plusSeconds now ttl))))
                                     {:principal actor}))
                  (catch clojure.lang.ExceptionInfo _ nil))))))))))
