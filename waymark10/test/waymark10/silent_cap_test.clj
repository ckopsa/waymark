(ns waymark10.silent-cap-test
  "The silent cap's warn mode and its lint (ticket 2636da5a). A
  query-rows or search-rows call that names neither :limit nor :all
  still gets the store's page of 100, and logs [silent-cap] naming its
  call site. The lint finds every such call in the source — a grep for
  the call, then a read of its form to resolve its opts — and writes
  the list to docs/silent-cap.md, which each directory's ticket
  empties. The money and measure sites hold to none."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory])
  (:import (clojure.lang LineNumberingPushbackReader LispReader$Resolver)
           (java.io File StringReader StringWriter)))

;; ── the warn mode ───────────────────────────────────────────────────────

(deftest page-limit-reads-all-limit-or-warns
  (is (nil? (store/page-limit {:all true})))
  (is (= 7 (store/page-limit {:limit 7})))
  (let [err (StringWriter.)
        n (binding [*err* err] (store/page-limit {}))]
    (is (= store/silent-cap n))
    (is (str/includes? (str err) "[silent-cap]"))
    (testing "the warning names its call site"
      (is (str/includes? (str err) "waymark10.silent-cap-test")))))

(deftest all-reads-past-the-page
  (let [st (memory/storage)]
    (doseq [i (range 150)]
      (store/insert-row! st nil :thing {:id (format "t%03d" i) :state :open :data {:n i}}))
    (is (= 150 (count (store/query-rows st nil :thing {} {:all true}))))
    (is (= 150 (count (store/search-rows st nil :thing [] {:all true}))))
    (is (= 20 (count (store/query-rows st nil :thing {} {:limit 20}))))
    (testing "naming neither still answers the page, while it warns"
      (is (= store/silent-cap
             (count (binding [*err* (StringWriter.)]
                      (store/search-rows st nil :thing [] {}))))))))

;; ── the lint ────────────────────────────────────────────────────────────

(def ^:private self "waymark10/test/waymark10/silent_cap_test.clj")

(def ^:private call-re
  "The grep: a call of query-rows or search-rows, qualified or not."
  #"\((?:[\w.\-]+/)?(?:query-rows|search-rows)\s")

(def ^:private lenient
  "Reads a form whatever its aliases: the lint needs its shape only."
  (reify LispReader$Resolver
    (currentNS [_] 'user)
    (resolveClass [_ _] nil)
    (resolveAlias [_ sym] sym)
    (resolveVar [_ sym] sym)))

(defn- read-at
  "The one form that opens at `offset` of `text`, or ::unread."
  [text offset]
  (try
    (binding [*read-eval* false
              *reader-resolver* lenient
              *default-data-reader-fn* tagged-literal]
      (read {:read-cond :allow :eof ::unread}
            (LineNumberingPushbackReader. (StringReader. (subs text offset)))))
    (catch Exception _ ::unread)))

(defn- names-cap?
  "Whether an opts form names :limit or :all anywhere in it."
  [form]
  (boolean (some #{:limit :all} (tree-seq coll? seq form))))

(defn- verdict
  "A call form's place on the list: nil when it names its cap or is a
  definition, :flagged when its opts are a map naming neither (or it
  has none), :passed when it hands on opts it did not write."
  [form]
  (cond
    (= ::unread form) :unread
    (not (seq? form)) nil
    (vector? (second form)) nil
    (< (count form) 6) :flagged
    :else (let [opts (nth form 5)]
            (cond (names-cap? opts) nil
                  (or (map? opts) (nil? opts)) :flagged
                  :else :passed))))

(defn- repo-root
  "The checkout's root: the nearest directory up from here holding
  waymark10/deps.edn."
  []
  (loop [d (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (cond (nil? d) nil
          (.exists (io/file d "waymark10" "deps.edn")) d
          :else (recur (.getParentFile d)))))

(defn- walk
  "Every file under `d`, past dot directories, target and node_modules."
  [^File d]
  (mapcat (fn [^File f]
            (cond (re-find #"^(\..*|target|node_modules)$" (.getName f)) nil
                  (.isDirectory f) (walk f)
                  :else [f]))
          (.listFiles d)))

(defn- sites
  "Every call the lint lists: {:path :line :verdict :text}, in path and
  line order."
  [^File root]
  (let [prefix (inc (count (.getPath root)))]
    (sort-by (juxt :path :line)
             (for [^File f (walk root)
                   :let [p (subs (.getPath f) prefix)]
                   :when (and (re-find #"\.clj[cs]?$" p) (not= p self))
                   :let [text (slurp f)
                         m (re-matcher call-re text)]
                   offset (vec (take-while some? (repeatedly #(when (.find m) (.start m)))))
                   :let [v (verdict (read-at text offset))]
                   :when v]
               {:path p
                :line (inc (count (filter #{\newline} (subs text 0 offset))))
                :verdict v
                :text (str/replace (str/trim (first (str/split-lines (subs text offset))))
                                   "`" "'")}))))

(defn- area
  "The ticket a site belongs to: server/, the rest of waymark10, each
  app directory, and the tests."
  [path]
  (cond (re-find #"(^|/)test/" path) "tests"
        (str/starts-with? path "waymark10/src/waymark10/server/") "waymark10/src/waymark10/server"
        :else (first (str/split path #"/"))))

(def ^:private heading
  {:flagged "Opts that name neither `:limit` nor `:all`"
   :passed "Opts handed on from a caller (the warning names that caller at run time)"
   :unread "Calls the lint could not read (look at them by hand)"})

(defn- doc-text [found]
  (str "# The silent cap: sites left to convert\n\n"
       "Written by `waymark10.silent-cap-test` (ticket 2636da5a) each time it\n"
       "runs; do not edit it by hand. A `query-rows` or `search-rows` call\n"
       "that names neither `:limit n` nor `:all true` still gets the store's\n"
       "page of " store/silent-cap ", and logs a `[silent-cap]` warning naming its call\n"
       "site. Each directory's ticket converts its sites — an aggregate goes\n"
       "to `count-matching`, `sum-matching` or `sum-amounts`; a listing page\n"
       "keeps a `:limit` with a one-line reason — and empties its part of\n"
       "this list. The last ticket turns the warning into a refusal once\n"
       "the list is empty. Calls through an engine's `:find` (a ctx's, or\n"
       "`find'`) are not grepped here: their warning names them at run time.\n"
       (str/join
        (for [[a in-area] (sort-by key (group-by (comp area :path) found))]
          (str "\n## " a "\n"
               (str/join
                (for [[v vs] (sort-by key (group-by :verdict in-area))]
                  (str "\n" (heading v) ":\n\n"
                       (str/join (for [{:keys [path line text]} vs]
                                   (str "- `" path ":" line "` — `" text "`\n")))))))))))

(def ^:private money-and-measure
  "The sites this ticket converts first: money and measures."
  #{"waymark10/src/waymark10/server/postings.clj"
    "waymark10/src/waymark10/server/invoke.clj"
    "waymark10/src/waymark10/server/measure.clj"})

(deftest the-lint-resolves-opts
  (is (= :flagged (verdict '(store/query-rows st tx :k {} {}))))
  (is (= :flagged (verdict '(store/search-rows st tx :k []))))
  (is (nil? (verdict '(store/query-rows st tx :k {} {:limit 2}))))
  (is (nil? (verdict '(store/search-rows st tx :k [] {:all true}))))
  (is (nil? (verdict '(store/search-rows st tx :k [] (assoc opts :limit n)))))
  (is (= :passed (verdict '(store/search-rows st tx :k conds opts))))
  (testing "a definition is not a call"
    (is (nil? (verdict '(query-rows [st tx kind where opts] "doc")))))
  (is (= :flagged (verdict (read-at "x (store/query-rows st tx ::a/k {} {})" 2)))))

(defn- listed
  "The [path text] of each site a written list names: its line numbers
  drift with every edit above a call, so they are not compared."
  [doc-text]
  (set (map (fn [[_ p t]] [p t])
            (re-seq #"(?m)^- `([^`]+):\d+` — `(.*)`$" doc-text))))

(deftest the-lint-writes-the-list
  (when-some [root (repo-root)]
    (let [found (sites root)
          doc (io/file root "docs" "silent-cap.md")
          text (doc-text found)
          before (if (.exists doc) (slurp doc) "")
          missing (remove (comp (listed before) (juxt :path :text)) found)]
      (when (not= text before)
        (spit doc text))
      (testing "every silent cap is on the list: a new one names its cap instead"
        (doseq [{:keys [path line verdict] t :text} missing]
          (is false (str "not in docs/silent-cap.md: " (name verdict)
                         " - `" path ":" line "` — `" t "`"))))
      (testing "the money and measure sites name their cap"
        (is (= [] (filterv #(and (money-and-measure (:path %))
                                 (not= :passed (:verdict %)))
                           found)))))))
