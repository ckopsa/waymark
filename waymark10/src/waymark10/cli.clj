(ns waymark10.cli
  "The affordance-following client as a shell command — one call per
  affordance, every Part IV rule enforced by waymark10.client
  rather than remembered by the operator:

      clojure -M:cli http://localhost:8010 index
      clojure -M:cli http://localhost:8010 get /api/plans/{id}
      clojure -M:cli http://localhost:8010 act /api/plans/{id} finalize
      clojure -M:cli http://localhost:8010 act /api/meals/{id} decline --yes
      clojure -M:cli http://localhost:8010 act /api/plans create \\
          --input '{\"weeks\": 1}' --dry-run
      clojure -M:cli http://localhost:8010 watch --kinds plan,meal

  Auth flags (persisted per base-url in the session file, so one
  --as suffices for a whole shell session):
      --as ID [--roles a,b]   dev principal headers
      --bearer TOKEN          OIDC bearer
      --grant ID              grant-scoped visibility
  Other flags: --input '{json}' (action input) · --yes (approve
  confirm gates AND acknowledge warnings without prompting) ·
  --dry-run (rule 5 at the shell: the verdict, not the act — schema
  AND guards judged server-side, nothing committed; creates ride act
  as ever) · --raw (full JSON bodies) · --session PATH (default
  ~/.waymark10/session.edn).

  The session file persists the idempotency key store, so a re-run
  of the same act with the same input REPLAYS instead of
  duplicating — the CLI inherits rule 3 across processes.

  Confirm gates prompt on the terminal ('--yes' is the recorded
  human approval for harnesses); a warning 409 prints every warning
  and prompts to acknowledge. A declined prompt does nothing and
  exits 2.

  Exit codes: 0 ok · 1 the server refused (problem) · 2 refused
  locally (confirm/acknowledge declined, unknown action, no route)
  · 3 transport failure."
  (:require [clojure.string :as str]
            [waymark10.client :as c]
            [waymark10.session :as sessionfile]
            [waymark10.wire :as wire])
  (:gen-class))

(set! *warn-on-reflection* true)

(def exit-ok 0)
(def exit-problem 1)
(def exit-refused 2)
(def exit-transport 3)

;; ── output ──────────────────────────────────────────────────────────

(defn- trunc [s limit]
  (let [s (str s)]
    (if (> (count s) limit)
      (str (subs s 0 limit) "… [" (count s) " chars; --raw for full]")
      s)))

(defn- print-json [v] (println (wire/write-json v)))

(defn- action-flags [entry]
  (let [safety (:safety entry)
        fields (keys (get-in entry [:input :properties]))
        flags (cond-> []
                (:confirm safety) (conj "confirm")
                (:fence safety) (conj "if-match")
                (not (:idempotent safety)) (conj "non-idempotent")
                (get-in entry [:effect :bulk]) (conj "bulk")
                (:draft entry) (conj "draftable"))]
    (str (when (seq flags) (str "  [" (str/join ", " flags) "]"))
         (when (seq fields)
           (str "  input: " (str/join ", " (map name fields)))))))

(defn- print-actions [doc]
  (when (seq (:actions doc))
    (println "actions:")
    (doseq [[aname entry] (sort-by (comp name key) (:actions doc))]
      (let [effect (:effect entry)]
        (println (str "  " (name aname)
                      (when (:to effect) (str "  → " (:to effect)))
                      (when (:terminal effect) " (terminal)")
                      (action-flags entry))))))
  (when (seq (:unavailable doc))
    (println "unavailable:")
    (doseq [[aname entry] (sort-by (comp name key) (:unavailable doc))]
      (println (str "  " (name aname) ": " (:reason entry)
                    (when-some [ba (:becomes_available entry)]
                      (str "  " (wire/write-json ba))))))))

(defn- collection-items [doc]
  (let [items (get-in doc [:data :items])]
    (when (and (vector? items) (map? (first items)) (:self (first items)))
      items)))

(defn- print-doc [doc raw?]
  (if raw?
    (print-json doc)
    (do
      (println (str (:kind doc) " " (:self doc) " · state=" (:state doc)
                    (when-some [v (get-in doc [:meta :version])]
                      (str " · v" v))))
      (when (:summary doc) (println (:summary doc)))
      (if-some [items (collection-items doc)]
        (do (doseq [item items]
              (println (str "  " (:self item) " · " (:state item)
                            " · " (:summary item))))
            (println (str "  (" (get-in doc [:data :total]) " total)")))
        (when (:data doc)
          (println (str "data: " (trunc (wire/write-json (:data doc)) 400)))))
      (print-actions doc)
      (doseq [[rel link] (:links doc)]
        (println (str "link " (name rel) " → " (:href link)))))))

(defn- print-problem [res raw?]
  (let [p (:problem res)]
    (if raw?
      (print-json p)
      (do
        (println (str "refused by the server: " (:status res) " " (:title p)))
        (when (:detail p) (println (:detail p)))
        (doseq [[field msgs] (:errors p)]
          (println (str "  " (name field) ": " (str/join "; " msgs))))
        (when-some [r (:resource p)]
          (println (str "  resource: " (or (:summary r) (:id r)))))))))

(defn- print-refused [res]
  (println (str "refused locally: " (get-in res [:refused :reason])))
  (when (= :confirm-required (get-in res [:refused :code]))
    (println "re-run with --yes once a human has approved")))

;; ── prompts (the human seams) ───────────────────────────────────────

(defn- prompt-yes? [text]
  (print (str text " [y/N] "))
  (flush)
  (contains? #{"y" "yes"} (some-> (read-line) str/trim str/lower-case)))

(defn- confirm-fn [yes?]
  (fn [{:keys [action effect consequence]}]
    (or yes?
        (prompt-yes? (str action " → " (:to effect)
                          (when (:terminal effect) " (terminal)")
                          "\n" consequence "\nProceed?")))))

;; ── commands ────────────────────────────────────────────────────────

(defn- cmd-index [session {:keys [raw]}]
  (let [idx (c/index session)]
    (cond
      (c/transport? idx) (do (println (str "cannot reach the server: "
                                           (get-in idx [:transport :message])))
                             exit-transport)
      (c/problem? idx) (do (print-problem idx raw) exit-problem)
      :else (do (if raw
                  (print-json idx)
                  (do (println (str "waymark " (:waymark idx)))
                      (doseq [[kind {:keys [href]}] (sort-by key (:resources idx))]
                        (println (str "  " (format "%-16s" (name kind)) href)))
                      (doseq [[sname {:keys [href]}] (:surfaces idx)]
                        (println (str "  surface " (name sname) " " href)))))
                exit-ok))))

(defn- result-exit
  "One result → one exit code + its printout."
  [res raw?]
  (cond
    (c/transport? res)
    (do (println (str "transport failure: " (get-in res [:transport :message])))
        exit-transport)

    (c/refused? res)
    (do (print-refused res) exit-refused)

    (c/problem? res)
    (do (print-problem res raw?) exit-problem)

    :else
    (do (if (c/doc? res) (print-doc res raw?) (print-json res))
        (when-some [d (c/diverged res)]
          (println (str "DIVERGED: " (:action d) " declared effect.to="
                        (:predicted d) " but the resource is in state "
                        (:actual d) " — surfacing instead of improvising")))
        exit-ok)))

(defn- cmd-get [session href {:keys [raw]}]
  (result-exit (c/get-doc session href) raw))

(defn- dry-run-exit
  "The rehearsal's printout: ✓ with any warnings on 0; a bulk/batch
  door's refusing verdicts (or the server's problem) on 1."
  [res raw?]
  (cond
    (c/transport? res)
    (do (println (str "transport failure: " (get-in res [:transport :message])))
        exit-transport)

    (c/refused? res) (do (print-refused res) exit-refused)
    (c/problem? res) (do (print-problem res raw?) exit-problem)

    (false? (:valid res))
    (do (println "✗ the rehearsal refuses:")
        (doseq [v (:verdicts res) :when (not= "ok" (:verdict v))]
          (println (str "  " (or (:self v) (str "input " (:index v)))
                        ": " (:reason v))))
        exit-problem)

    :else
    (do (println "✓ valid — schema and guards accept this input")
        (doseq [w (:warnings res)]
          (println (str "  warning " (:name w) ": " (:reason w))))
        exit-ok)))

(defn- cmd-act [session href action {:keys [input yes raw dry-run]}]
  (let [doc (c/get-doc session href)]
    (if-not (c/doc? doc)
      (result-exit doc raw)
      (if dry-run
        ;; rule 5 at the shell: the verdict, not the act
        (dry-run-exit (c/dry-run session doc (keyword action) input) raw)
      (let [res (c/act! session doc (keyword action) input
                        {:confirm! (confirm-fn yes)})]
        (if (c/warnings? res)
          (do (println "the server warns:")
              (doseq [w (:warnings res)]
                (println (str "  " (:name w) ": " (:reason w))))
              (if (or yes (prompt-yes? "Acknowledge and retry?"))
                (result-exit ((:acknowledge! res)) raw)
                (do (println "not acknowledged; nothing done")
                    exit-refused)))
          (result-exit res raw)))))))

(defn- cmd-watch [session {:keys [kinds]}]
  (println (str "watching " (:base-url session) "/api/-/events"
                (when kinds (str " · kinds=" (str/join "," kinds)))
                " — Ctrl-C stops"))
  (let [res (c/watch!
             session
             {:kinds kinds
              :on-event
              (fn [{:keys [data]}]
                (println (str (subs (str (:at data) "        ") 11 19)
                              "  " (get-in data [:actor :id] "?")
                              " " (:action data)
                              "  " (:kind data)
                              " " (or (:from data) "·") " → " (:to data)
                              "  " (:self data))))})]
    (if (c/transport? res)
      (do (println (str "transport failure: "
                        (get-in res [:transport :message])))
          exit-transport)
      exit-ok)))

;; ── argument parsing ────────────────────────────────────────────────

(defn- parse-args
  "[base-url command positionals… flags…] → {:base-url :command :args
  :opts} or {:usage-error \"…\"}."
  [args]
  (loop [remaining args
         acc {:positional [] :opts {}}]
    (if-some [a (first remaining)]
      (case a
        "--input"
        (let [v (second remaining)
              parsed (try (wire/read-json v) (catch Exception _ ::bad))]
          (cond
            (nil? v) {:usage-error "--input needs a JSON object argument"}
            (or (= ::bad parsed) (not (map? parsed)))
            {:usage-error (str "--input is not a JSON object: " v)}
            :else (recur (drop 2 remaining)
                         (assoc-in acc [:opts :input] parsed))))
        "--yes" (recur (rest remaining) (assoc-in acc [:opts :yes] true))
        "--dry-run" (recur (rest remaining) (assoc-in acc [:opts :dry-run] true))
        "--raw" (recur (rest remaining) (assoc-in acc [:opts :raw] true))
        "--kinds" (if-some [v (second remaining)]
                    (recur (drop 2 remaining)
                           (assoc-in acc [:opts :kinds]
                                     (vec (str/split v #","))))
                    {:usage-error "--kinds needs a comma-separated list"})
        "--as" (if-some [v (second remaining)]
                 (recur (drop 2 remaining) (assoc-in acc [:opts :as] v))
                 {:usage-error "--as needs a principal id"})
        "--roles" (if-some [v (second remaining)]
                    (recur (drop 2 remaining)
                           (assoc-in acc [:opts :roles]
                                     (vec (str/split v #","))))
                    {:usage-error "--roles needs a comma-separated list"})
        "--bearer" (if-some [v (second remaining)]
                     (recur (drop 2 remaining) (assoc-in acc [:opts :bearer] v))
                     {:usage-error "--bearer needs a token"})
        "--grant" (if-some [v (second remaining)]
                    (recur (drop 2 remaining) (assoc-in acc [:opts :grant] v))
                    {:usage-error "--grant needs a grant id"})
        "--session" (if-some [v (second remaining)]
                      (recur (drop 2 remaining)
                             (assoc-in acc [:opts :session] v))
                      {:usage-error "--session needs a file path"})
        (if (str/starts-with? a "--")
          {:usage-error (str "unknown flag " a)}
          (recur (rest remaining) (update acc :positional conj a))))
      (let [[base-url command & args] (:positional acc)]
        (cond
          (nil? base-url) {:usage-error "no base-url"}
          (not (str/starts-with? base-url "http"))
          {:usage-error (str "base-url must be http(s)://…, got " base-url)}
          (nil? command) {:usage-error "no command"}
          :else {:base-url (str/replace base-url #"/+$" "")
                 :command command
                 :args (vec args)
                 :opts (:opts acc)})))))

(def usage
  (str "usage: clojure -M:cli <base-url> <command> [flags]\n"
       "commands:\n"
       "  index                          discover kinds and surfaces\n"
       "  get <href>                     fetch a resource or collection\n"
       "  act <href> <action>            invoke a declared action\n"
       "      [--input '{json}'] [--yes] [--dry-run]\n"
       "  watch [--kinds a,b]            tail the transition firehose\n"
       "auth (persisted per base-url):\n"
       "  --as ID [--roles a,b] | --bearer TOKEN | --grant ID\n"
       "other: --raw · --session PATH (default ~/.waymark10/session.edn)"))

;; ── the entry point ─────────────────────────────────────────────────

(defn run
  "The CLI body: args → exit code. Split from -main so a test can
  invoke it in-process."
  [& args]
  (let [{:keys [usage-error base-url command args opts]} (parse-args args)]
    (if usage-error
      (do (println (str "✗ " usage-error)) (println usage) exit-refused)
      (let [session-path (or (:session opts) (sessionfile/default-path))
            file (sessionfile/load-file* session-path)
            stored (get file base-url {})
            ;; flags override stored auth; either way it persists
            auth (or (when (:as opts)
                       {:principal (cond-> {:id (:as opts)}
                                     (:roles opts) (assoc :roles (:roles opts)))})
                     (when (:bearer opts) {:bearer (:bearer opts)})
                     (:auth stored))
            auth (cond-> (or auth {})
                   (:grant opts) (assoc :grant (:grant opts)))
            key-store (atom (or (:keys stored) {}))
            session (c/connect base-url (assoc auth :key-store key-store))
            code (try
                   (case command
                     "index" (cmd-index session opts)
                     "get" (if-some [href (first args)]
                             (cmd-get session href opts)
                             (do (println usage) exit-refused))
                     "act" (if (= 2 (count args))
                             (cmd-act session (first args) (second args) opts)
                             (do (println usage) exit-refused))
                     "watch" (cmd-watch session opts)
                     (do (println (str "✗ unknown command " command))
                         (println usage)
                         exit-refused))
                   (finally
                     (try (sessionfile/save-file!
                           session-path
                           (assoc file base-url {:auth auth
                                                 :keys @key-store}))
                          (catch Exception _ nil))))]
        code))))

(defn -main [& args]
  (System/exit (apply run args)))
