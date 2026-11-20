(ns waymark10.server.members.setup-cards
  "A fresh hosted engine's setup cards (ticket 8268d86e,
  docs/spec-first-run.md R-4.8, docs/spec-watch.md § 7): when
  WAYMARK10_FIRST_OWNERS gives birth to the first owners, each is given
  the \"Setting up\" watch, filed as that owner through the watch kind's
  own create, with one cue on their own member row. Where the engine
  serves `org_plan`, the first owner the variable lists files one
  `house` plan with no brief, and is given the \"Next steps\" watch on
  it; the plan's doors are its owner's alone, so no other owner is
  given that card. No bench, no repository, no seat, no GitHub card:
  those are the factory seed's (factory/seed.edn), which a hosted
  engine never runs.

  Every step skips what is already there, so filing twice files once.
  A boot that died between the owners' birth and their cards is
  resumed by `resume!`: the next boot finds only the variable's own
  people and no watch but the seeded ones and these cards, and files
  what is missing."
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def setting-up "Setting up")

(def next-steps "Next steps")

(def connect-note
  "The hosted \"Connect your Claude\" card. It names no mayor: on a
  hosted engine the Claude you connect wears none yet (ticket 6afc8ac7).
  Its steps are one to a line, the address on a line of its own
  (ticket 96ea43ed). The address is all Claude needs: the engine
  answers its client registration (ticket 1d1f952a), and the last line
  says who to tell when that fails."
  "1. In Claude (claude.ai or the Claude app), open Settings, then Connectors, and add a custom connector.\n2. Name it after your house and give it this engine's address:\n{engine_url}\n3. Claude sends you here to sign in with your house account; allow it.\n4. Open a new chat with that connector on and say what you want this house to do.\nYou know it worked when Claude answers from this house.\nAlready added it? Open a chat with it on; if Claude says it needs to sign in again, reconnect it.\nIf Claude says it could not register, tell your house's operator: the connector client is missing or not public.")

(def connect-note-public
  "The card on a house whose Claude connector client is public: the
  address alone works, and one line more names the client ID for a
  Claude that asks for one (ticket 55375bf1). A cue's note holds 600
  characters, so its first and last lines are shorter than
  `connect-note`'s."
  "1. In Claude, open Settings, then Connectors, and add a custom connector.\n2. Name it after your house and give it this engine's address:\n{engine_url}\nIf Claude asks for a client ID, it is {connector_client_id}.\n3. Claude sends you here to sign in with your house account; allow it.\n4. Open a new chat with that connector on and say what you want this house to do.\nYou know it worked when Claude answers from this house.\nAlready added it? Open a chat with it on; if Claude says it needs to sign in again, reconnect it.\nIf Claude says it could not register, tell your house's operator.")

(def connect-note-confidential
  "The card on a house whose Claude connector client is confidential
  (WAYMARK10_OIDC_CONNECTOR_CLIENT_SECRET_REQUIRED): step 2 asks for the
  client ID and the secret the operator gave. The engine never shows or
  stores the secret. It keeps under a cue note's 600 characters."
  "1. In Claude, open Settings, then Connectors, and add a custom connector.\n2. Name it after your house, give it this engine's address:\n{engine_url}\nthen open the advanced settings and enter the client ID {connector_client_id} and the client secret your house's operator gave you.\n3. Claude sends you here to sign in with your house account; allow it.\n4. Open a new chat with that connector on and say what you want this house to do.\nYou know it worked when Claude answers from this house.\nAlready added it? Open a chat with it on; if Claude says it needs to sign in again, reconnect it.")

(defn connect-note-for
  "The \"Connect your Claude\" note for a house's :oidc config: the
  address-only steps when it names no Claude delegate client, the
  confidential steps when that client needs its secret, and the public
  steps otherwise."
  [oidc]
  (cond (nil? (oidc/connector-client oidc)) connect-note
        (oidc/connector-confidential? oidc) connect-note-confidential
        :else connect-note-public))

(def house-note
  "\"Say what the house is for\", as factory/seed.edn says it."
  "Write a paragraph on what this house runs for you, in your own words. The onboarding seat turns it into a tree of parts for you to agree to.")

(defn- decoded [eng kind]
  (let [rdef (get (inv/resources eng) kind)
        st (:storage eng)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx st (fn [tx] (store/query-rows st tx kind {} {:all true}))))))

(defn- principal-of [row]
  (let [id (str (:id row))]
    (t/principal {:id id :display (str (or (get-in row [:data :display]) id)) :type :human})))

(defn- holds? [watches nm owner]
  (some #(and (= nm (str (get-in % [:data :name])))
              (= owner (str (get-in % [:data :for]))))
        watches))

(defn- house! [eng owner]
  (or (some #(when (= "house" (str (get-in % [:data :name]))) %) (decoded eng :org_plan))
      (:row (inv/create! eng :org_plan {:name "house"} {:principal (principal-of owner)}))))

(defn file!
  "File the setup cards for `owners`, member rows in the variable's
  order; the first is the house plan's owner. Answers nil."
  [eng owners]
  (let [rs (inv/resources eng)]
    (when (and (contains? rs :watch) (seq owners))
      (let [watches (decoded eng :watch)]
        (doseq [o owners
                :let [id (str (:id o))]
                :when (not (holds? watches setting-up id))]
          (inv/create! eng :watch
                       {:name setting-up :required true
                        :cues [{:kind "member" :actions [] :states ["active"]
                                :title "Connect your Claude"
                                :filter {:id id} :note (connect-note-for (:oidc eng))}]}
                       {:principal (principal-of o)}))
        (when (contains? rs :org_plan)
          (let [owner (first owners)
                house (house! eng owner)]
            (when-not (holds? watches next-steps (str (:id owner)))
              (inv/create! eng :watch
                           {:name next-steps
                            :cues [{:kind "org_plan" :actions ["write_brief"] :states ["drafting"]
                                    :title "Say what the house is for"
                                    :filter {:id (str (:id house)) :brief_set "false"}
                                    :note house-note}]}
                           {:principal (principal-of owner)})))))))
  nil)

(defn resume!
  "On a boot that finds people already members: when they are all named
  by the variable (`named`, in its order) and the engine holds no watch
  but the seeded ones and these cards, file what is missing — the boot
  that bore them died before their cards. Answers true when it did."
  [eng named persons]
  (let [by-id (into {} (map (juxt (comp str :id) identity)) persons)]
    (when (and (seq by-id)
               (contains? (inv/resources eng) :watch)
               (every? (set named) (keys by-id))
               (every? #(or (true? (get-in % [:data :seeded]))
                            (contains? #{setting-up next-steps} (str (get-in % [:data :name]))))
                       (decoded eng :watch)))
      (file! eng (keep by-id named))
      true)))
