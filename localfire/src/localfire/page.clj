(ns localfire.page
  "The run page and the run list (R-7.2).

  One HTML page with no script, because the engine writes this URL
  onto the schedule row as `last_run_url` and a person opens it from
  there to answer one question: what did that firing do. Everything on
  it is escaped, including the fire text, which is a person's own
  prose and Claude Code's own output — neither is trusted markup.

  The figures from `stdout.json` are Claude Code's own report of the
  API's usage (R-7.4). They are not the ledger's: the engine's `close`
  handler prices the sitting from the model row."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import [java.time Duration Instant]))

(defn esc
  "Escape for HTML text and for an attribute. Everything on the page
  goes through this (R-7.2)."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- parse-instant [v]
  (try (some-> v str not-empty Instant/parse) (catch Exception _ nil)))

(defn duration-of
  "The run's wall time as a person reads it, or nil when it has no end
  yet."
  [started ended]
  (when-let [^Instant a (parse-instant started)]
    (when-let [^Instant b (parse-instant ended)]
      (let [s (.getSeconds (Duration/between a b))]
        (format "%d:%02d:%02d" (quot s 3600) (quot (mod s 3600) 60) (mod s 60))))))

(defn parse-stdout
  "Claude Code's result JSON, leniently (R-7.2). A run that was killed
  wrote half a line and a run that failed wrote none, so the page says
  what it has and does not pretend."
  [text]
  (try (some-> text not-empty (json/read-value json/keyword-keys-object-mapper))
       (catch Exception _ nil)))

(defn- row [k v]
  (str "<dt>" (esc k) "</dt><dd>" (esc v) "</dd>"))

(defn- usage-rows [m]
  (let [u (:usage m)]
    (str (when (:num_turns m) (row "num_turns" (:num_turns m)))
         (when (:total_cost_usd m) (row "total_cost_usd" (:total_cost_usd m)))
         (when (map? u)
           (apply str
                  (for [k [:input_tokens :output_tokens
                           :cache_creation_input_tokens :cache_read_input_tokens]]
                    (row (name k) (get u k)))))
         (when-let [mu (:modelUsage m)]
           (row "modelUsage" (pr-str mu))))))

(defn- doc [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<title>" (esc title) "</title>"
       "<style>body{font:14px/1.5 ui-monospace,monospace;margin:2rem;max-width:60rem}"
       "dt{font-weight:700;float:left;width:14rem;clear:left}dd{margin-left:14rem}"
       "pre{background:#f4f4f4;padding:.75rem;overflow-x:auto;white-space:pre-wrap}"
       "h2{margin-top:2rem}</style></head><body>" body "</body></html>"))

(defn run-page
  "One run's page (R-7.2): the routine, the model, the status, the
  instants and the duration, the exit code, the session id, then what
  `stdout.json` gives, then `fire.txt`, then the tail of
  `stderr.log`."
  [{:keys [id run fire-text stdout-text stderr-tail]}]
  (let [parsed (parse-stdout stdout-text)
        title  (str "run " id)]
    (doc title
         (str "<h1>" (esc title) "</h1>"
              "<dl>"
              (row "routine" (:routine run))
              (row "model" (:model run))
              (row "status" (name (or (:status run) :unknown)))
              (row "session id" id)
              (row "started at" (:started-at run))
              (row "ended at" (or (:ended-at run) "—"))
              (row "duration" (or (duration-of (:started-at run) (:ended-at run)) "—"))
              (row "exit" (if (some? (:exit run)) (:exit run) "—"))
              (if parsed
                (usage-rows parsed)
                (row "stdout.json" "did not parse"))
              "</dl>"
              "<h2>fire.txt</h2><pre>" (esc (or fire-text "")) "</pre>"
              "<h2>stderr.log (tail)</h2><pre>" (esc (or stderr-tail "")) "</pre>"))))

(defn runs-page
  "The run list (R-4.4): newest first, at most 100, each linking to its
  own page."
  [runs]
  (doc "runs"
       (str "<h1>runs</h1><ul>"
            (apply str
                   (for [r runs]
                     (str "<li><a href=\"/runs/" (esc (:id r)) "\">"
                          (esc (:id r)) "</a> — "
                          (esc (:routine r)) " — "
                          (esc (name (or (:status r) :unknown))) " — "
                          (esc (:started-at r)) "</li>")))
            "</ul>")))

(defn not-found-page [id]
  (doc "no run" (str "<h1>no run</h1><p>No run " (esc id)
                     " is on this server.</p>")))
