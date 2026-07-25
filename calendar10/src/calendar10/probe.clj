(ns calendar10.probe
  "The transport's end-to-end proof (waymark-6k5.1's acceptance):
  point it at the REAL family calendar and watch a document make the
  round trip. No engine, no database, no declaration — just the
  adapter and the credential, so a failure here is unambiguously the
  boundary's.

      make probe-calendar              # read: the window, as we see it
      WRITE=1 make probe-calendar      # read, then create → patch → delete

  The write probe is deliberately loud about what it is doing on a
  calendar real people read, and it cleans up after itself — including
  on failure, so a crashed probe never leaves \"waymark probe\" sitting
  on the family's Tuesday."
  (:require [calendar10.oauth :as oauth]
            [calendar10.source :as src]
            [waymark10.server.mirror :as mirror]
            [clojure.string :as str])
  (:import (java.time LocalDate)))

(defn- refuse! [& parts]
  (binding [*out* *err*] (println (apply str parts)))
  (System/exit 1))

(defn- adapter []
  (let [token-fn (oauth/from-env)]
    (when-not token-fn
      (refuse! "no google credential in the environment — set "
               "CALENDAR10_GOOGLE_CLIENT_ID / _CLIENT_SECRET / "
               "_REFRESH_TOKEN (scripts/gcal-refresh-token.sh mints the "
               "refresh token)."))
    (src/from-env token-fn)))

(defn- show [x doc]
  (println (format "  %-46s %s  %-9s %s"
                   x
                   (:date doc)
                   (if (:all_day doc) "all-day" (str (:kind doc)))
                   (:title doc))))

(defn- read-probe [cal]
  (let [ids (mirror/discover cal)]
    (println (str "discover: " (count ids) " occurrence(s) in the window"))
    (let [docs (mirror/pull-many cal ids)]
      (println (str "pull-many: " (count docs) " answered"))
      ;; a :gone value is a keyword, not a [doc etag] pair — sorting
      ;; through `first` would throw on the very rows worth seeing
      (doseq [[x v] (sort-by (fn [[_ v]] (if (= :gone v) "" (:date (first v))))
                             docs)]
        (if (= :gone v)
          (println (format "  %-46s (gone)" x))
          (show x (first v))))
      docs)))

(defn- write-probe [cal]
  (let [tomorrow (str (.plusDays (LocalDate/now) 1))
        doc {:title "waymark probe — safe to delete"
             :date tomorrow
             :end_date tomorrow
             :all_day true
             :kind "note"
             :detail "Created by calendar10.probe. Deletes itself."}
        _ (println (str "\ncreate: an all-day probe event on " tomorrow " …"))
        [x etag] (mirror/push-create cal doc)]
    (println (str "  born " x " etag " etag))
    (try
      (let [[pulled pulled-etag] (mirror/pull cal x)]
        (println (str "  pull back: " (pr-str (select-keys pulled
                                                           [:title :date :all_day
                                                            :kind :calendar]))))
        (when-not (= etag pulled-etag)
          (println (str "  note: etag moved between create and pull ("
                        etag " → " pulled-etag ")"))))
      (println "patch: retitling …")
      (let [etag2 (mirror/push cal x (assoc doc :title "waymark probe — retitled"))
            [pulled _] (mirror/pull cal x)]
        (println (str "  etag " etag2 " title " (pr-str (:title pulled))))
        (when-not (= "waymark probe — retitled" (:title pulled))
          (refuse! "the patch did not take — the title came back "
                   (pr-str (:title pulled)))))
      (finally
        (println "delete: cleaning up …")
        (src/delete-event! cal x)
        (println (str "  removed " x))))
    (println "\nround trip clean: the transport reads AND writes.")))

(defn -main [& args]
  (let [cal (adapter)
        write? (or (= "1" (System/getenv "WRITE"))
                   (some #{"roundtrip"} args))]
    (println (str "calendars: "
                  (str/join ", " (map (fn [[t id]] (str t "=" id))
                                      (:calendars cal)))))
    (read-probe cal)
    (when write? (write-probe cal))
    (shutdown-agents)))
