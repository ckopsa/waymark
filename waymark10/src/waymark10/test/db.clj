(ns waymark10.test.db
  "The test database lifecycle: one engine per test namespace. Tables
  are dropped first — every run starts from bytes it made itself —
  and only tables the passed declarations name are touched: a suite
  drops nothing it did not enroll.

  WAYMARK10_TEST_DSN overrides the default local DSN.

  Loading it seals under test-data-key (ticket d682ee14): every
  Postgres suite loads it, and with no key a field declared :at-rest
  :encrypted refuses its write."
  (:require [next.jdbc :as jdbc]
            [waymark10.modules :as modules]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(def test-data-key
  "A fixed, public 32-byte data key for the suites — never a real one.
  HMAC and the key id under it are deterministic, so a suite may pin
  them."
  (at-rest/parse-key
   (.encodeToString (java.util.Base64/getEncoder)
                    (.getBytes "waymark10 test key, never real!!" "UTF-8"))))

(defn use-test-key!
  "Seal under test-data-key in place of the environment's key. A no-key
  test binds it off with (at-rest/use-key! nil) and puts back what that
  answers."
  []
  (at-rest/use-key! test-data-key))

(defonce ^:private test-key-in-place (use-test-key!))

(def ^:private built-in-kinds
  "Kind -> plural of every kind the module table enrolls on every
  engine. A test rdef may claim neither: its table is the shard's
  shared one, and building it from the test's rdef corrupts it for
  every namespace after (ticket 4d791724)."
  (delay
    (into {}
          (comp (filter #(= :always (:enroll %)))
                (mapcat #((:kinds %) []))
                (map (juxt :kind :plural)))
          (mapcat :enrols modules/inventory))))

(defn shadowed-built-ins
  "The clashes of `resources` with the built-in kinds: one
  {:kind :plural :shadows} per rdef whose kind or plural a built-in
  module kind already holds."
  [resources]
  (let [kinds @built-in-kinds
        by-plural (into {} (map (fn [[k p]] [p k])) kinds)]
    (vec (for [{:keys [kind plural]} resources
               :let [shadows (or (when (contains? kinds kind) kind)
                                 (get by-plural plural))]
               :when shadows]
           {:kind kind :plural plural :shadows shadows}))))

(defn with-test-engine
  "Connect, drop the resources' tables plus the engine's transition
  and idempotency tables, build the engine over `resources`
  (normalized declarations), call (f eng), close the pool.

  Refuses, before touching the database, an rdef whose kind or plural
  shadows a built-in module kind, naming the clash."
  [resources f]
  (when-let [clashes (seq (shadowed-built-ins resources))]
    (throw (ex-info (str "with-test-engine: test kind(s) shadow built-in module kind(s) — "
                         (pr-str (vec clashes))
                         "; rename the test kind and its plural")
                    {:check :shadows-built-in :clashes (vec clashes)})))
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table (concat (map #(store/definition-checked-name (:plural %))
                                     resources)
                                ["waymark10_transitions" "waymark10_idempotency"
                                 "waymark10_drafts"])]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (f (inv/engine {:storage st :resources resources}))
      (finally (pg/close! st)))))
