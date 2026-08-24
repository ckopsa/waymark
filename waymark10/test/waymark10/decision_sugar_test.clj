(ns waymark10.decision-sugar-test
  "Two spellings, one law — the batch-G invariant turned on the
  framework's own oldest verdict machine.

  approval_request was hand-written for a year before there was a
  :decision key: three states, two verdict actions, a four-eyes wall,
  a requester stamped at birth, a default leash, a decision queue with
  its own filter and sort. This suite keeps that hand-written spelling
  alive — constructed here byte-for-byte from the pre-sugar source,
  citing grants.clj's own guard and handler objects so the imperative
  residue hashes as itself — and pins the two things that make the
  sugar safe to have added at all:

  1. the sugared declaration and the split one are THE SAME MAP, and
  2. their fingerprint hashes are byte-identical, and equal to the
     literal pinned below — the hash approval_request carried before
     the :decision key existed.

  If either moves, the sugar changed the law and the generalization
  failed. The literal pin is the stronger half: it survives a future
  refactor that rewrites this file's old spelling too.

  Sharing rules, inherited from waymark10.batch-g-invariance-test and
  mealplan10.style-invariance-test:
  - code guards and handlers hash by canonical printed form, so the
    old spelling cites the very objects the new spelling reaches for;
  - g/not-the-field mints a FRESH :check fn per call (the g/require
    precedent), so the sugar's decider wall and grants.clj's own
    someone-else-decides are distinct OBJECTS with identical forms —
    map equality is asserted modulo those two closures, and the hash
    equality is what proves they are one law;
  - :on-create is not fingerprinted and the sugar MINTS it, so it is
    compared by BEHAVIOUR (the stamps it lands) rather than by object
    identity;
  - :schema and :create-schema are compared as ENTRY MAPS, because
    the sugar appends the entries it owns and a malli [:map …] form
    is an ordered vector while the fingerprint's storage facet sorts
    its columns. Order is spelling; the entries are law."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.types :as t]))

;; ── the pre-sugar spelling of approval_request ──────────────────────
;; Copied from grants.clj as it stood at waymark-442.5, comments
;; stripped. :on-create is a placeholder: the real one is judged by
;; behaviour below, because the sugar mints a fresh fn and an fn is
;; not comparable by =.

(def ^:private split-approval-request
  {:kind :approval_request
   :plural "approval_requests"
   :states [:offered :approved :denied]
   :initial :offered
   :terminal #{:approved :denied}
   :nav :system
   :summary "Access request by {data.requested_by} · {state} · until {data.expires_at}"
   ;; the prose is the sugar's too, on the same argument the :note
   ;; comment below records (waymark-0ee, widened to :asks and :expires
   ;; by waymark-7rw): the hand spelling carries the very words the
   ;; sugar mints, here the ones approval_request spells for itself in
   ;; its :asks / :expires maps
   :schema [:map
            [:grant_id {:optional true :kind :grant
                        :x-display {:label "Widen this grant"
                                    :help "The grant you already hold and want more of. Leave it empty for the bootstrap ask — an approval then mints a fresh grant in your name."}}
             [:maybe :waymark/ref]]
            [:task {:x-display
                    {:label "What you need it for"
                     :help "The work this access is for, in one sentence. The approver is deciding about the TASK as much as the scope — 'file the week's receipts' earns a yes that 'admin' does not."}}
             [:string {:min 1 :max 240}]]
            [:scope {:examples [grants/scope-example]
                     :x-display {:label "What you are asking for"
                                 :help "The leash you want, entry by entry: a kind, the actions on it, and optionally the rows, fields and filter that narrow it. Ask for the least that does the job — an approver reads this."}}
             grants/scope-schema]
            [:expires_at {:optional true
                          :x-display
                          {:label "Good until"
                           :help "When the access should die on its own. Leave it empty and the engine stamps its own short default at birth, so the approver approves the leash that will actually exist."}}
             [:maybe :waymark/instant]]
            [:requested_by {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 128}]]]
            [:approved_by {:optional true :x-display {:raw true}}
             [:maybe [:string {:max 128}]]]
            [:note {:optional true} [:maybe [:string {:max 240}]]]]
   :create-schema [:map
                   [:grant_id {:optional true :kind :grant
                               :x-display {:label "Widen this grant"
                                           :help "The grant you already hold and want more of. Leave it empty for the bootstrap ask — an approval then mints a fresh grant in your name."}}
                    [:maybe :waymark/ref]]
                   [:task {:x-display
                           {:label "What you need it for"
                            :help "The work this access is for, in one sentence. The approver is deciding about the TASK as much as the scope — 'file the week's receipts' earns a yes that 'admin' does not."}}
                    [:string {:min 1 :max 240}]]
                   [:scope {:examples [grants/scope-example]
                            :x-display {:label "What you are asking for"
                                        :help "The leash you want, entry by entry: a kind, the actions on it, and optionally the rows, fields and filter that narrow it. Ask for the least that does the job — an approver reads this."}}
                    grants/scope-schema]
                   [:expires_at {:optional true
                                 :x-display
                                 {:label "Good until"
                                  :help "When the access should die on its own. Leave it empty and the engine stamps its own short default at birth, so the approver approves the leash that will actually exist."}}
                    [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in}
                :grant_id #{:eq}
                :requested_by #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "offered"}
   :links [{:rel "grant" :kind :grant
            :href "/api/grants/{data.grant_id}"
            :summary "The grant this request extends or minted"}]
   :create-guards [grants/requester-is-named
                   grants/requester-holds-the-grant
                   grants/asks-are-paced
                   grants/asks-are-few
                   grants/asks-are-short
                   grants/scope-names-real-kinds
                   grants/scope-names-real-actions
                   grants/scope-filters-are-filterable
                   grants/scope-omits-private-kinds]
   :scenarios [grants/the-asker-does-not-decide
               grants/another-principal-may-deny]
   :own-surface {:by :requested_by
                 :actions #{"create" "approve" "deny"}}
   :on-create (fn [row _ctx] row)
   :actions
   {:approve {:from #{:offered} :to :approved
              :guards [grants/someone-else-decides grants/grant-still-accepting]
              :safety {:idempotent true :reversible false :confirm true
                       :consequence "The requester's grant gains exactly the scope shown, immediately."}
              :handler grants/stamp-approver
              :display {:label "Approve" :style :primary :order 1}}
    :deny {:from #{:offered} :to :denied
           ;; the note's prose is the sugar's too (waymark-0ee): a
           ;; generated field with no label and no hint is a usability
           ;; warning the declaration's author cannot clear, so the
           ;; hand spelling carries the very words the sugar mints
           :input [:map [:note {:optional true
                                :x-display
                                {:label "Note"
                                 :help (str "Optional. Say why, in a sentence "
                                            "the asker will read beside the "
                                            "verdict.")}}
                         [:maybe [:string {:max 240}]]]]
           :edit {:prefill [:note] :fence false
                  :unfenced-reason "A denial's note is written once with the verdict; a frozen offered ask has nothing to clobber."}
           :guards [grants/someone-else-decides]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A denied ask stays on record; asking differently is a new request."}
           :handler grants/record-verdict-note
           :display {:label "Deny" :style :danger :order 9}}}})

(def ^:private split (r/normalize-resource split-approval-request))
(def ^:private sugared (into {} grants/approval-request))

(defn- strip-checks
  "Both spellings' decider walls compute the same verdict from the
  same printed form; only the closure objects differ (a factory mints
  one per call). Blank them so map equality can speak about
  everything else."
  [rmap]
  (update rmap :actions
          (fn [as]
            (into (sorted-map)
                  (map (fn [[an a]]
                         [an (update a :guards
                                     #(mapv (fn [g] (dissoc g :check)) %))]))
                  as))))

(deftest the-sugar-projects-the-very-map-the-hand-spelling-declared
  (let [ks [:on-create :schema :create-schema]]
    (is (= (strip-checks (apply dissoc split ks))
           (strip-checks (apply dissoc sugared ks)))
        "one normalized map, two spellings"))
  (testing "the schema entries the sugar appends are the ones the hand wrote"
    (is (= (schema/entry-map (:schema split))
           (schema/entry-map (:schema sugared))))
    (is (= (schema/entry-map (:create-schema split))
           (schema/entry-map (:create-schema sugared)))))
  (testing "the decider wall is one law in two objects"
    (doseq [[an a] (:actions sugared)]
      ;; both sides carry a canonical form, so callable-hash reads the
      ;; LAW and not the address it was handed (waymark-j82) — assert
      ;; that first, or the comparison below would pass on any two
      ;; bare fns quoted at one address
      (is (some? (:waymark10/form (meta (:check (first (:guards a))))))
          (str (name an) "'s decider wall carries its printed form"))
      (is (= (fp/callable-hash "probe" (:check (first (:guards a))))
             (fp/callable-hash "probe" (:check grants/someone-else-decides)))
          (str (name an) "'s first guard IS someone-else-decides, by form")))))

(deftest the-minted-on-create-stamps-what-the-hand-written-one-stamped
  (let [now (java.time.Instant/parse "2026-08-24T18:00:00Z")
        ctx (t/ctx {:principal (t/principal {:id "agent-ari" :type :agent})
                    :now now
                    :services {:grant-default-ttl-seconds 3600}})
        stamped ((:on-create sugared) {:data {:task "read the pantry"}} ctx)]
    (is (= "agent-ari" (get-in stamped [:data :requested_by]))
        "the requester is stamped from the acting principal, never supplied")
    (is (= (.plusSeconds now 3600) (get-in stamped [:data :expires_at]))
        "a blank leash gets the deployment's configured default TTL")
    (let [asked (.plusSeconds now 900)
          kept ((:on-create sugared)
                {:data {:task "read the pantry" :expires_at asked}} ctx)]
      (is (= asked (get-in kept [:data :expires_at]))
          "a leash the asker named is left exactly as asked"))))

(def ^:private the-canonical-hash
  ;; The hash approval_request carried BEFORE the :decision key
  ;; existed — recomputed from the pre-sugar source at waymark-442.5
  ;; and pinned as a literal so no future respelling can move this
  ;; fingerprint even if it rewrites the split spelling above too.
  ;; Re-pin only for a deliberate law change, with a note saying why.
  "01ca868b7440b6c13c9e10260904eb82a18217f79b439134369fdb337496d9f3")

(deftest the-decision-sugar-moved-not-one-byte-of-law
  (is (= (fp/fingerprint-hash (r/fingerprint split))
         (fp/fingerprint-hash (r/fingerprint grants/approval-request)))
      "byte-identical fingerprint hashes: two spellings, one law")
  (is (= the-canonical-hash
         (fp/fingerprint-hash (r/fingerprint grants/approval-request)))
      "…and the hash is the one the hand-written machine always had"))

;; ── the sugar's own refusals ────────────────────────────────────────
;; Each is a sentence the declaration surface owes an author, checked
;; where a reader can see it rather than trusted from a docstring.

(defn- refusal [rmap]
  (try (r/normalize-resource rmap) nil
       (catch Exception e (ex-message e))))

(def ^:private minimal
  {:kind :verdict_probe
   :plural "verdict_probes"
   :summary "{data.ask} · {state}"
   :decision {:asks :ask
              :by :asked_by
              :decider {:not :asked_by}
              :verdicts [{:name :yes :to :allowed}
                         {:name :no :to :refused}]}})

(deftest the-sugar-refuses-what-it-cannot-mean
  (testing "a decision projects an honest machine from the floor alone"
    (let [r (r/normalize-resource minimal)]
      (is (= [:offered :allowed :refused] (:states r)))
      (is (= :offered (:initial r)))
      (is (= #{:allowed :refused} (:terminal r)))
      (is (= #{:yes :no} (set (keys (:actions r)))))
      (is (= :system (:nav r)))
      (is (= {:state "offered"} (:default-filters r)))))
  (testing "one verdict is a task with a checkbox"
    (is (re-find #":verdicts is two or more"
                 (refusal (assoc-in minimal [:decision :verdicts]
                                    [{:name :yes :to :allowed}])))))
  (testing "a decision that lands nowhere is a queue that never drains"
    (is (re-find #"no verdict leaves"
                 (refusal (assoc-in minimal [:decision :verdicts]
                                    [{:name :yes :to :offered}
                                     {:name :no :to :offered}])))))
  (testing "one home per action"
    (is (re-find #"one home per action"
                 (refusal (assoc minimal :actions
                                 {:yes {:from #{:offered} :to :allowed}})))))
  (testing "one home per birth hook"
    (is (re-find #"one home per hook"
                 (refusal (assoc minimal :on-create (fn [row _] row))))))
  (testing "a decider that says nothing"
    (is (re-find #":decider says nothing"
                 (refusal (assoc-in minimal [:decision :decider] {}))))
    (is (re-find #":decider says nothing"
                 (refusal (update-in minimal [:decision] dissoc :decider)))
        "silence still refuses — an omitted :decider is a typo far more
         often than it is a policy"))
  (testing ":anyone is a wall's absence, said out loud (waymark-iqa.4)"
    (let [r (r/normalize-resource
             (assoc-in minimal [:decision :decider] :anyone))]
      (is (= [] (:guards (get-in r [:actions :yes])))
          "no wall means no guard — a guard that refuses nobody would
           print its name into every decision record it never stopped")
      (is (= [] (:guards (get-in r [:actions :no]))))
      (is (= #{:yes :no} (set (keys (:actions r))))
          "everything else the sugar projects is untouched")))
  (testing "two walls cannot share one sentence"
    (is (re-find #"give\s+each wall its own"
                 (refusal (assoc-in minimal [:decision :decider]
                                    {:not :asked_by :role "parent"
                                     :explain "one sentence, two walls"})))))
  (testing "a typo in the decision map refuses at the def site"
    (is (re-find #"unknown key"
                 (refusal (assoc-in minimal [:decision :verdict] []))))
    (is (re-find #"unknown key"
                 (refusal (update-in minimal [:decision :verdicts 0]
                                     assoc :labell "Yes"))))))

(deftest the-own-surface-is-a-declaration-not-a-literal
  (testing "a bare field is the one-branch case"
    (is (= {:by [[:requested_by]] :all false
            :actions #{"create" "approve" "deny"}}
           (:own-surface grants/approval-request))))
  (testing "a decision spelling :own-surface true owns its verdict doors"
    (let [r (r/normalize-resource
             (assoc-in minimal [:decision :own-surface] true))]
      (is (= {:by [[:asked_by]] :all false :actions #{"create" "yes" "no"}}
             (:own-surface r)))))
  (testing "owned by nobody and owned by everybody are not one typo apart"
    (is (re-find #"names neither :by"
                 (refusal (assoc minimal :own-surface {:actions #{"create"}}))))))
