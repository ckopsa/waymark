(ns mealplan10.scraper-test
  "The stale-price queue's consumer, both halves:

  - parse-price-cents, pure, over the three ways retailers spell a
    price — JSON-LD (nested @graph, offers-as-array, string and
    number forms), the og/product price meta tags, an
    itemprop=\"price\" content attr — and the honest nils: garbage,
    zero, negative, absurd;
  - run! end to end over the real ring handler with an injected fake
    fetch: the queue is exactly ?state=tracked&price_is_stale=true
    (a fresh row is never offered), a parseable page becomes a
    record_sighting with source \"scrape\" that flips
    price_is_stale, a garbage page is a counted miss with no write,
    a missing url is a counted skip, a throwing fetch is a counted
    error, and :limit caps the pass.

  No live HTTP anywhere — the fake fetch records its calls, and an
  unexpected url throws.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.main :as main]
            [mealplan10.scraper :as scraper]
            [calendar10.source :as es]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world (the pantry-stock boot) ───────────────────────────────

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-calendar)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :now-fn (fn [] @clock)})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge colton headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural " create: " (:body resp)))
    (json resp)))

(defn- get-env [self & [query]]
  (json (req :get (cond-> self query (str "?" query)))))

(defn- etag-of [self]
  (get-in (req :get self) [:headers "ETag"]))

(defn- act!
  ([self action] (act! self action nil))
  ([self action body]
   (let [resp (req :post (str self "/-/" (name action)) body
                   {"if-match" (etag-of self)})]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- active-ingredient! [nm]
  (act! (:self (created! "ingredients" {:name nm})) :accept))

;; ── parse-price-cents ───────────────────────────────────────────────

(def ^:private jsonld-product
  "<html><head><title>Almonds</title>
   <script type=\"application/ld+json\">
   {\"@context\":\"https://schema.org\",\"@type\":\"Product\",
    \"name\":\"Kirkland Almonds\",
    \"offers\":{\"@type\":\"Offer\",\"priceCurrency\":\"USD\",
                \"price\":\"31.77\"}}
   </script></head><body>Add to cart</body></html>")

(def ^:private jsonld-graph
  "<html><head>
   <script type='application/ld+json'>
   {\"@context\":\"https://schema.org\",
    \"@graph\":[{\"@type\":\"BreadcrumbList\",\"itemListElement\":[]},
                {\"@type\":\"Product\",\"name\":\"Olive oil\",
                 \"offers\":[{\"@type\":\"Offer\",\"price\":12.49},
                             {\"@type\":\"Offer\",\"price\":13.99}]}]}
   </script></head><body></body></html>")

(def ^:private og-meta
  "<html><head>
   <meta property=\"og:title\" content=\"Butter\"/>
   <meta property=\"og:price:amount\" content=\"4.99\"/>
   <meta property=\"og:price:currency\" content=\"USD\"/>
   </head><body></body></html>")

(def ^:private product-meta
  ;; attributes in the other order, name= instead of property=
  "<html><head>
   <meta content=\"7.50\" name=\"product:price:amount\">
   </head><body></body></html>")

(def ^:private itemprop-span
  "<html><body>
   <span itemprop=\"price\" content=\"3.29\">$3.29</span>
   </body></html>")

(deftest parse-price-cents-reads-what-retailers-write
  (testing "JSON-LD: Product/Offer, price as string"
    (is (= 3177 (scraper/parse-price-cents jsonld-product))))
  (testing "JSON-LD: @graph nest, offers as array, price as number —
            first candidate in document order wins"
    (is (= 1249 (scraper/parse-price-cents jsonld-graph))))
  (testing "og:price:amount meta"
    (is (= 499 (scraper/parse-price-cents og-meta))))
  (testing "product:price:amount meta, attrs reversed, name= spelling"
    (is (= 750 (scraper/parse-price-cents product-meta))))
  (testing "itemprop=price content attr"
    (is (= 329 (scraper/parse-price-cents itemprop-span))))
  (testing "JSON-LD outranks the meta tag on the same page"
    (is (= 3177 (scraper/parse-price-cents
                 (str jsonld-product og-meta))))))

(deftest parse-price-cents-tells-honest-nils
  (testing "a JS-rendered page carries no price in its bytes"
    (is (nil? (scraper/parse-price-cents
               "<html><body><div id=app>loading…</div></body></html>"))))
  (testing "malformed JSON-LD is a skip, not a crash"
    (is (nil? (scraper/parse-price-cents
               "<script type=\"application/ld+json\">{oops</script>"))))
  (testing "zero and negative are not prices"
    (is (nil? (scraper/parse-price-cents
               "<meta property=\"og:price:amount\" content=\"0.00\">")))
    (is (nil? (scraper/parse-price-cents
               "<meta property=\"og:price:amount\" content=\"-5.00\">"))))
  (testing "absurd is a parse artifact: > $10,000 rejected, $10,000 kept"
    (is (nil? (scraper/parse-price-cents
               "<meta property=\"og:price:amount\" content=\"10000.01\">")))
    (is (= 1000000 (scraper/parse-price-cents
                    "<meta property=\"og:price:amount\" content=\"10000.00\">"))))
  (testing "dollar signs and thousands separators are noise"
    (is (= 3177 (scraper/parse-price-cents
                 "<span itemprop=\"price\" content=\"$31.77\"></span>")))
    (is (= 129900 (scraper/parse-price-cents
                   "<meta property=\"og:price:amount\" content=\"$1,299.00\">"))))
  (testing "fractional cents round half-up"
    (is (= 400 (scraper/parse-price-cents
                "<meta property=\"og:price:amount\" content=\"3.999\">"))))
  (testing "not a string, not a price"
    (is (nil? (scraper/parse-price-cents nil)))))

;; ── run!, end to end over the real handler ──────────────────────────

(deftest the-scraper-consumes-the-stale-queue
  (let [almonds (active-ingredient! "Almonds (scr)")
        iid (id-of almonds)
        mk! (fn [nm url]
              (act! (:self (created! "products"
                                     (cond-> {:ingredient_id iid
                                              :store "costco" :name nm}
                                       url (assoc :url url))))
                    :confirm_match))
        aaa (mk! "AAA parseable (scr)" "https://shop.example/aaa")
        bbb (mk! "BBB garbage (scr)" "https://shop.example/bbb")
        ccc (mk! "CCC url-less (scr)" nil)
        ddd (mk! "DDD fresh (scr)" "https://shop.example/ddd")
        eee (mk! "EEE unreachable (scr)" "https://shop.example/eee")
        ;; the fresh control: priced today, so the queue never offers it
        _ (act! (:self ddd) :record_sighting
                {:seen_on "2026-07-08" :price_cents 999 :source "receipt"})
        calls (atom [])
        fake-fetch (fn [url]
                     (swap! calls conj url)
                     (case url
                       "https://shop.example/aaa" jsonld-product
                       "https://shop.example/bbb"
                       "<html><body><div id=app>loading…</div></body></html>"
                       "https://shop.example/eee"
                       (throw (ex-info "connection refused" {:url url}))
                       (throw (ex-info (str "unexpected fetch: " url)
                                       {:url url}))))
        {:keys [find invoke]} (scraper/handler-io {:handler *h*
                                                   :principal "scraper"})
        base {:find find :invoke invoke :fetch fake-fetch :delay-ms 0
              :now (fn [] (Instant/parse "2026-07-08T12:00:00Z"))}
        version-of (fn [env] (get-in (get-env (:self env)) [:meta :version]))
        bbb-v (version-of bbb)
        ddd-v (version-of ddd)]
    (testing "limit caps the pass: one fetch, the first by the kind's sort"
      (let [report (scraper/run! (assoc base :limit 1))]
        (is (= {:queue-size 4 :attempted 1 :recorded 1 :parse-misses 0
                :fetch-errors 0 :invoke-errors 0 :skipped-no-url 1}
               report))
        (is (= ["https://shop.example/aaa"] @calls)
            "exactly one polite fetch went out")))
    (testing "the hit is a sighting with source scrape, and the clock
              fact flips with the write"
      (let [env (get-env (:self aaa))
            ss (get-in env [:data :sightings])]
        (is (= [{:seen_on "2026-07-08" :price_cents 3177 :source "scrape"
                 :ref "https://shop.example/aaa"}]
               (mapv #(select-keys % [:seen_on :price_cents :source :ref])
                     ss)))
        (is (= 3177 (get-in env [:data :latest_price_cents])))
        (is (false? (get-in env [:data :price_is_stale]))
            "last_seen_on moved, so the queue lets the row go")))
    (testing "the second pass drains what remains — the miss and the
              error counted, nothing written"
      (let [report (scraper/run! base)]
        (is (= {:queue-size 3 :attempted 2 :recorded 0 :parse-misses 1
                :fetch-errors 1 :invoke-errors 0 :skipped-no-url 1}
               report)))
      (let [env (get-env (:self bbb))]
        (is (empty? (get-in env [:data :sightings])) "a miss is not a write")
        (is (true? (get-in env [:data :price_is_stale]))
            "the row stays stale — the queue re-offers it next run")
        (is (= bbb-v (version-of bbb)) "no version moved on a miss")))
    (testing "the url-less row waits for update_details, not the loop"
      (is (empty? (get-in (get-env (:self ccc)) [:data :sightings]))))
    (testing "the fresh row was never offered, never fetched, never touched"
      (is (not-any? #(= "https://shop.example/ddd" %) @calls))
      (is (= ddd-v (version-of ddd)))
      (is (= ["receipt"]
             (mapv :source (get-in (get-env (:self ddd)) [:data :sightings])))))
    (testing "the unreachable page recorded nothing"
      (is (empty? (get-in (get-env (:self eee)) [:data :sightings]))))
    (testing "a same-day re-record replaces instead of duplicating — the
              seen_on upsert is record_sighting's idempotency, so a
              crashed run's retry is safe"
      (invoke (:self aaa) :record_sighting
              {:seen_on "2026-07-08" :price_cents 3199
               :source "scrape" :ref "https://shop.example/aaa"})
      (let [ss (get-in (get-env (:self aaa)) [:data :sightings])]
        (is (= 1 (count ss)))
        (is (= 3199 (:price_cents (first ss))))))))
