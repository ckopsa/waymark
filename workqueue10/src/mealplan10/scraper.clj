(ns mealplan10.scraper
  "The stale-price queue's consumer — the scraper product.clj has
  promised since the pantry-prices era: \"the scraper's whole work
  queue is ?state=tracked&price_is_stale=true with no write and no
  poll\". This namespace is that consumer, a job, not a service: one
  pass over the queue, one honest report, exit.

  Best-effort BY DESIGN. Retailer HTML is hostile ground —
  JS-rendered pages carry no price in their bytes, markups drift,
  bot walls answer 403 — so a miss is a SKIP, not an error: the row
  stays stale and the queue re-offers it next run, the same no-write
  no-poll clock that put it there. Politeness caps what one run
  records: at most `limit` fetches (default 40), `delay-ms` apart
  (default 3000) — a family scraper, not a crawler.

  Extraction tries the three ways retailers actually say a price,
  cheapest lie first: JSON-LD (schema.org Product/Offer \"price\",
  including @graph nests and offers-as-array), then the
  og:price:amount / product:price:amount meta tags, then an
  itemprop=\"price\" content attribute. Regex-and-string over a real
  HTML parser — dependency-free, and a price that needs a DOM to
  find is a miss we accept.

  A hit becomes record_sighting {:seen_on today :price_cents p
  :source \"scrape\" :ref url} — idempotent by shape (the seen_on
  upsert), so a re-run the same day replaces instead of duplicating,
  and last_seen_on moving is what flips price_is_stale back off.

  run! is dependency-injected (find / invoke / fetch / now) so the
  tests script the world; handler-io builds the real find/invoke
  over an engine's ring handler, and fetch is the real HTTP GET.
  workqueue10.main/scrape! (the :scrape alias) is the deployed
  entry."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [waymark10.wire :as wire])
  (:import (java.math BigDecimal RoundingMode)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration Instant LocalDate ZoneOffset)))

(set! *warn-on-reflection* true)

;; ── money ───────────────────────────────────────────────────────────

(def ^:private max-cents
  "Past $10,000 for one grocery package the number is a parse
  artifact (a SKU, a phone number), never a price."
  1000000)

(defn- bd->cents
  "Dollars as BigDecimal → cents (long), HALF_UP on fractional
  cents; nil outside (0, $10,000]."
  [^BigDecimal bd]
  (try
    (let [n (-> bd (.movePointRight 2)
                (.setScale 0 RoundingMode/HALF_UP)
                (.longValueExact))]
      (when (and (pos? n) (<= n max-cents)) n))
    (catch ArithmeticException _ nil)))

(defn- money->cents
  "A price as retailers spell it — 31.77, 5, \"31.77\", \"$1,299.00\",
  \"31.77 USD\" — to cents, or nil when it isn't one."
  [v]
  (cond
    (instance? BigDecimal v) (bd->cents v)
    (number? v) (bd->cents (bigdec v))
    (string? v)
    (when-not (re-find #"-\s*[0-9]" v)   ; a negative is not a price
      (when-some [token (re-find #"[0-9][0-9,]*(?:\.[0-9]+)?" v)]
        (bd->cents (BigDecimal. ^String (str/replace token "," "")))))
    :else nil))

;; ── extraction ──────────────────────────────────────────────────────

(defn- json-ld-blocks [html]
  (map second
       (re-seq #"(?is)<script[^>]*type\s*=\s*[\"']application/ld\+json[\"'][^>]*>(.*?)</script>"
               html)))

(defn- price-candidates
  "Every price / lowPrice value anywhere in a parsed JSON-LD tree,
  depth-first — @graph nests and offers-as-array fall out of the
  walk instead of needing their own cases."
  [parsed]
  (->> (tree-seq coll? (fn [x] (if (map? x) (vals x) (seq x))) parsed)
       (filter map?)
       (mapcat (fn [m] (keep m [:price :lowPrice])))))

(defn- parse-json-ld [html]
  (->> (json-ld-blocks html)
       (keep (fn [s] (try (wire/read-json s) (catch Exception _ nil))))
       (mapcat price-candidates)
       (keep money->cents)
       first))

(defn- tag-attrs
  "One tag's quoted attributes, lower-cased keys."
  [tag]
  (into {}
        (map (fn [[_ k v]] [(str/lower-case k) v]))
        (re-seq #"([a-zA-Z:@_-]+)\s*=\s*[\"']([^\"']*)[\"']" tag)))

(defn- parse-meta-price [html]
  (->> (re-seq #"(?is)<meta[^>]*>" html)
       (map tag-attrs)
       (keep (fn [a]
               (when (contains? #{"og:price:amount" "product:price:amount"}
                                (some-> (or (get a "property") (get a "name"))
                                        str/lower-case))
                 (get a "content"))))
       (keep money->cents)
       first))

(defn- parse-itemprop-price [html]
  (->> (re-seq #"(?is)<[a-zA-Z][^>]*itemprop\s*=\s*[\"']price[\"'][^>]*>" html)
       (map tag-attrs)
       (keep #(get % "content"))
       (keep money->cents)
       first))

(defn parse-price-cents
  "Best-effort price extraction from retailer HTML — pure. Tries
  JSON-LD, then the og/product price meta tags, then
  itemprop=\"price\" content attributes; first value that reads as
  money in (0, $10,000] wins, as cents (long). nil is the honest
  answer for everything else — a JS-rendered page, a bot wall, a
  redesign — and the caller treats it as a skip, never an error."
  [html]
  (when (string? html)
    (or (parse-json-ld html)
        (parse-meta-price html)
        (parse-itemprop-price html))))

;; ── the real fetch ──────────────────────────────────────────────────

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn fetch
  "One retailer page as a string: JDK HttpClient, 15s timeout,
  redirects followed, an honest UA, Accept-Encoding identity so the
  body arrives uncompressed. Non-2xx throws — run! counts it a
  fetch error and moves on."
  [url]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str url)))
                (.timeout (Duration/ofSeconds 15))
                (.header "User-Agent" "waymark-scraper/1 (personal home pantry)")
                (.header "Accept" "text/html,application/xhtml+xml")
                (.header "Accept-Encoding" "identity")
                (.GET)
                (.build))
        ^HttpResponse resp (.send ^HttpClient @http-client req
                                  (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (or (< status 200) (>= status 300))
      (throw (ex-info (str "the retailer answered " status " for " url)
                      {:status status :url url})))
    (.body resp)))

;; ── the engine transport ────────────────────────────────────────────

(defn handler-io
  "find/invoke over an engine's ring HANDLER — the in-process
  transport precedent (workqueue10.sources.waymark/engine-transport):
  a request that never crosses the network edge carries the
  dev-header principal legitimately, because the require-auth gate
  lives in the http server's wrap, not in the handler.

  find pages the promised queue
  (?state=tracked&price_is_stale=true) by following :next links,
  then reads each row — the collection lists summaries, and the
  loop needs :url from data. invoke is a bare POST:
  record_sighting is idempotent by shape (the seen_on upsert) and
  unfenced, so there is no key and no etag dance."
  [{:keys [handler principal]}]
  (let [principal (or principal "scraper")
        req (fn [method uri body]
              (let [[path q] (str/split uri #"\?" 2)
                    resp (handler
                          (cond-> {:request-method method :uri path
                                   :headers {"x-waymark-principal" principal
                                             "accept" "application/json"}}
                            q (assoc :query-string q)
                            body (assoc :body (wire/write-json body))))]
                (when (>= (:status resp) 400)
                  (throw (ex-info (str "the engine answered " (:status resp)
                                       " for " (name method) " " uri)
                                  {:status (:status resp)
                                   :body (str (:body resp))})))
                (some-> (:body resp) str not-empty wire/read-json)))]
    {:find
     (fn []
       (loop [uri (str "/api/products?state=tracked&price_is_stale=true"
                       "&page%5Bsize%5D=100")
              selves []]
         (let [env (req :get uri nil)
               selves (into selves (map :self) (get-in env [:data :items]))]
           (if-some [next-href (get-in env [:links :next :href])]
             (recur next-href selves)
             {:total (get-in env [:data :total])
              :envs (mapv #(req :get % nil) selves)}))))
     :invoke
     (fn [self action input]
       (req :post (str self "/-/" (name action)) input))}))

;; ── the loop ────────────────────────────────────────────────────────

(defn run!
  "One pass over the stale-price queue. Injected world:

    :find     () → {:total n :envs [row envelopes]} — the queue
    :invoke   (self action input) → envelope, throws on refusal
    :fetch    (url) → html string, throws on transport failure
    :now      () → Instant (defaults to now) — seen_on's clock
    :limit    max fetches this run (default 40 — politeness)
    :delay-ms pause between fetches (default 3000)

  Rows without a :url are counted and skipped — update_details is
  their fix, not this loop. A parse miss or fetch error is counted
  and skipped — the row stays stale and the queue re-offers it next
  run. Prints and returns the honest report:
  {:queue-size :attempted :recorded :parse-misses :fetch-errors
   :invoke-errors :skipped-no-url}."
  [{:keys [find invoke fetch now limit delay-ms]}]
  (let [limit (or limit 40)
        delay-ms (or delay-ms 3000)
        now-fn (or now (fn [] (Instant/now)))
        {:keys [total envs]} (find)
        with-url (filterv #(some-> (get-in % [:data :url]) not-empty) envs)
        batch (vec (take limit with-url))
        seen-on (str (LocalDate/ofInstant ^Instant (now-fn) ZoneOffset/UTC))
        counts (volatile! {:queue-size (or total (count envs))
                           :attempted (count batch)
                           :recorded 0
                           :parse-misses 0
                           :fetch-errors 0
                           :invoke-errors 0
                           :skipped-no-url (- (count envs) (count with-url))})
        count! (fn [k] (vswap! counts update k inc))]
    (doseq [[i env] (map-indexed vector batch)]
      (when (and (pos? i) (pos? (long delay-ms)))
        (Thread/sleep (long delay-ms)))
      (let [url (get-in env [:data :url])
            html (try (fetch url)
                      (catch Exception _ ::fetch-error))]
        (if (= ::fetch-error html)
          (count! :fetch-errors)
          (if-some [cents (parse-price-cents html)]
            (try
              (invoke (:self env) :record_sighting
                      {:seen_on seen-on :price_cents cents
                       :source "scrape" :ref url})
              (count! :recorded)
              (catch Exception _ (count! :invoke-errors)))
            (count! :parse-misses)))))
    (let [{:keys [queue-size attempted recorded parse-misses fetch-errors
                  invoke-errors skipped-no-url] :as report} @counts]
      (println (str "scraper: queue " queue-size
                    " · attempted " attempted
                    " · recorded " recorded
                    " · parse misses " parse-misses
                    " · fetch errors " fetch-errors
                    " · invoke errors " invoke-errors
                    " · no url " skipped-no-url))
      report)))
