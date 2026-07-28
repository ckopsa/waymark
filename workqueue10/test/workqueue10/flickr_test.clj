(ns workqueue10.flickr-test
  "The flickr boundary, whole: the translation (fraction law
  pre-obeyed, the audience rule, the kind filter), the opaque cursor
  with its two resync triggers (a malformed 400 and the deletion
  mark), the full-list batch that turns absence into the :on-gone
  observation, the push that is always the :noop freshness check,
  and the birth that refuses because a catalog scans its own files.

  No database and no network — the fake stands behind the TRANSPORT
  (its cursor grammar is the live engine's own l<n>.s<n>, verified
  2026-07-28), so every assertion below runs the real source's feed
  reading, cursor echo, kind filter and translation, and only the
  socket is missing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.flickr :as fk]
            [workqueue10.sources.hub :as hub]
            [waymark10.server.mirror :as mirror]))

(def ^:private movie
  ;; the verified live shape, byte for byte (spec-media-flickr.md)
  {:work_key "movie:12-angry-men-1957" :kind "movie"
   :title "12 Angry Men" :year 1957 :genres [] :overview ""
   :episode_count 0 :item_count 1 :representative_item_id 51})

(def ^:private colton
  {:name "Colton" :status "active"
   :progress 0.0137M :progress_text "1:19" :updated_at 1785133261.301})

;; ── the translation ─────────────────────────────────────────────────

(deftest work-translation
  (testing "the canonical envelope: title, medium, year, and the
            catalog-neutral identity"
    (let [d (fk/work->doc movie nil)]
      (is (= "12 Angry Men" (:title d)))
      (is (= "movie" (:medium d)))
      (is (= 1957 (:year d)))
      (is (= "movie:12-angry-men-1957" (:work_key d)))))

  (testing "the fraction law arrives pre-obeyed: the canonical
            fraction BESIDE the authority's own words, untranslated"
    (let [d (fk/work->doc (assoc movie :audiences [colton]) nil)]
      (is (= 0.0137M (:progress d)))
      (is (= "1:19" (:progress_text d)))
      (is (= "active" (:status d)))
      (is (= "Colton" (:audience_name d)))))

  (testing "a fraction the source cannot know stays off beside intact
            text — the law's own gap"
    (let [d (fk/work->doc (assoc movie :audiences
                                 [(assoc colton :progress nil)])
                          nil)]
      (is (nil? (:progress d)))
      (is (= "1:19" (:progress_text d)))))

  (testing "a work nobody has started says NOTHING about status or
            position — under :partial that silence is what keeps the
            hub's queued/abandoned/logged words intact"
    (let [d (fk/work->doc movie nil)]
      (is (not (contains? d :status)))
      (is (not (contains? d :progress)))
      (is (not (contains? d :audience_name)))))

  (testing "a third status fails loudly rather than mapping silently —
            flickr speaks active and finished, nothing else"
    (is (thrown? IllegalArgumentException
                 (fk/work->doc (assoc movie :audiences
                                      [(assoc colton :status "paused")])
                               nil)))))

(deftest the-audience-rule
  ;; the parent spec's punt, observed: per-audience progress, PLURAL,
  ;; on one work — the addendum's rule maps it without redesigning
  ;; the row
  (let [jack {:name "Jack" :status "finished" :progress 1.0M
              :progress_text "S02E05" :updated_at 1785999999.0}
        work (assoc movie :audiences [colton jack])]
    (testing "no hub opinion takes the most-recently-updated entry"
      (is (= "Jack" (:audience_name (fk/work->doc work nil)))))
    (testing "a row whose audience is set follows that audience"
      (let [d (fk/work->doc work "Colton")]
        (is (= "Colton" (:audience_name d)))
        (is (= "1:19" (:progress_text d)))))
    (testing "a preferred name the feed no longer carries falls back
              to the freshest entry rather than answering nothing"
      (is (= "Jack" (:audience_name (fk/work->doc work "Grandma")))))))

(deftest deep-links-are-the-uis-own
  (testing "a movie deep-links to its detail pane by representative
            item id"
    (is (= "https://stream.kopsa.info/#/item/51"
           (fk/deep-link "https://stream.kopsa.info" movie))))
  (testing "a show deep-links to its episode list by title,
            encodeURIComponent'd — the UI's own showHash spelling"
    (is (= "https://stream.kopsa.info/#/show/Colton%27s%20Minecraft%20Adventure"
           (fk/deep-link "https://stream.kopsa.info"
                         {:kind "show"
                          :title "Colton's Minecraft Adventure"})))))

;; ── discovery and the kind filter ───────────────────────────────────

(deftest only-movie-and-show-kinds-mirror
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (fk/seed! f {:work_key "show:ninjago" :kind "show" :title "Ninjago"})
    (fk/seed! f {:work_key "file:250" :kind "file"
                 :title "'Pocalypse Preppin' - Checkers.mkv"})
    (testing "discovery names the works of intent and never the
              per-file inventory — 322 unidentified files at
              verification, excluded by decision"
      (is (= #{"movie:12-angry-men-1957" "show:ninjago"}
             (set (conf/source-discover f)))))
    (testing "a file-kind work is gone to the batch and 404 to the
              singular pull — never a row candidate by any door"
      (is (= :gone (get (conf/source-pull-many f ["file:250"]) "file:250")))
      (is (= 404 (:status (ex-data (try (conf/source-pull f "file:250")
                                        (catch clojure.lang.ExceptionInfo e e)))))))))

;; ── the cursor ──────────────────────────────────────────────────────

(defn- feed-params [f]
  (map :params (fk/requests f)))

(deftest the-cursor-is-opaque-stored-and-echoed
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (testing "the first pass is cursorless — the initial sync reads
              the world once (416 works at verification)"
      (is (= ["movie:12-angry-men-1957"] (conf/source-discover f)))
      (is (nil? (:since (first (feed-params f))))))
    (testing "the answered cursor is stored and echoed, never parsed"
      (let [held (fk/cursor f)]
        (is (some? held))
        (is (empty? (conf/source-discover f))
            "a current-cursor round-trip answers zero works — verified
             live")
        (is (= held (:since (last (feed-params f)))))))
    (testing "a single playback session surfaces exactly its one work"
      (fk/play! f "movie:12-angry-men-1957" colton)
      (is (= ["movie:12-angry-men-1957"] (conf/source-discover f)))
      (is (= "1:19" (:progress_text (first (conf/source-pull
                                            f "movie:12-angry-men-1957"))))))))

(deftest a-malformed-cursor-means-resync-from-scratch
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (conf/source-discover f)
    ;; corrupt the stored cursor (a redeploy of the authority with a
    ;; new grammar, a bad restore — the 400 is the engine's answer
    ;; either way, verified live)
    (reset! (:cursor (:source f)) "garbage")
    (testing "the 400 resets the cursor and the SAME pass re-reads the
              world — resync from scratch, not an outage"
      (is (= ["movie:12-angry-men-1957"] (conf/source-discover f)))
      (is (= "garbage" (:since (last (butlast (feed-params f)))))
          "the bad cursor really went to the wire and got its 400")
      (is (nil? (:since (last (feed-params f))))
          "the retry went out cursorless"))
    (testing "…and the fresh cursor holds again"
      (let [held (fk/cursor f)]
        (conf/source-discover f)
        (is (= held (:since (last (feed-params f)))))))))

(deftest the-cursor-survives-an-unreachable-pass
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (conf/source-discover f)
    (let [held (fk/cursor f)]
      (fk/down! f true)
      (is (thrown? Exception (conf/source-discover f)))
      (is (= held (fk/cursor f))
          "a pass that threw leaves the cursor standing — the next
           pass re-asks the window rather than skipping it"))))

(deftest the-deletion-mark-answers-the-full-list
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (fk/seed! f {:work_key "show:ninjago" :kind "show" :title "Ninjago"})
    (conf/source-discover f)
    (fk/delete! f "show:ninjago")
    (testing "a cursor from before the mark gets the WHOLE library
              back — always correct, just not minimal (the engine's
              own rule)"
      (is (= ["movie:12-angry-men-1957"] (conf/source-discover f))))
    (testing "absence against the full list is the :on-gone
              observation — the batch answers :gone, never silence"
      (let [pulled (conf/source-pull-many
                    f ["movie:12-angry-men-1957" "show:ninjago"])]
        (is (vector? (get pulled "movie:12-angry-men-1957")))
        (is (= :gone (get pulled "show:ninjago")))))))

;; ── etags ───────────────────────────────────────────────────────────

(deftest the-etag-is-content-plus-translation
  (let [f (fk/fake-source)
        id (fk/seed! f movie)
        etag-of #(second (conf/source-pull f %))]
    (testing "flickr mints no version, so the version is the
              translated content with our revision composed on"
      (is (str/ends-with? (etag-of id) (str "|" fk/translation-rev))))
    (testing "the authority moving moves the etag"
      (let [before (etag-of id)]
        (fk/play! f id colton)
        (is (not= before (etag-of id)))))
    (testing "…and an unchanged work answers an unchanged etag — the
              observed-unchanged discipline rides on this"
      (is (= (etag-of id) (etag-of id))))))

;; ── the shield: push and birth ──────────────────────────────────────

(deftest every-push-is-the-noop-freshness-check
  (let [f (fk/fake-source)
        id (fk/seed! f movie)]
    (testing "NOTHING travels — flickr owns what happened, the hub
              owns what is intended; the answer is the fresh etag, so
              a hub-local write's :to :fresh is earned"
      (is (= (second (conf/source-pull f id))
             (conf/source-push f id {:status "abandoned" :priority 1})))
      (is (empty? (remove #(= "GET" (:method %)) (fk/requests f)))
          "the wire carried reads and nothing else"))
    (testing "a push against a work the library dropped refuses
              404-shaped — the conflicted landing, a person decides"
      (fk/delete! f id)
      (is (= 404 (:status (ex-data (try (conf/source-push f id {:status "finished"})
                                        (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest flickr-takes-no-births
  (is (thrown-with-msg? Exception #"flickr takes no births"
                        (conf/source-create (fk/fake-source)
                                            {:title "Dune" :medium "movie"}))
      "a media row with no catalog is the hub's — the create door's
       enum already says so; this refusal is the seam holding the
       line for anything that reaches it another way"))

;; ── the hub, the twenty-line authority ──────────────────────────────

(deftest the-hub-always-agrees
  (let [h (hub/source)]
    (is (empty? (conf/source-discover h))
        "hub rows are born at the capture door, never minted")
    (let [[id etag] (conf/source-create h {:title "Some dinner rec"
                                           :medium "book"})]
      (is (seq id))
      (is (= hub/etag etag))
      (testing "every pull answers an empty document (silence, under
                :partial) beside the constant etag — eternal
                agreement, and never a gone row"
        (is (= [{} hub/etag] (conf/source-pull h id)))
        (is (= {id [{} hub/etag]} (conf/source-pull-many h [id]))))
      (testing "every push is the :noop — a hub-local write has no
                authority to tell"
        (is (= hub/etag (conf/source-push h id {:status "finished"})))))))

;; ── through the confluence ──────────────────────────────────────────

(deftest the-media-confluence-tags-both-sources
  (let [f (fk/fake-source)
        _ (fk/seed! f movie)
        feed (conf/confluence {"flickr" f "hub" (hub/source)})]
    (is (= ["flickr:movie:12-angry-men-1957"] (vec (mirror/discover feed)))
        "the hub discovers nothing beside it")
    (let [[doc _] (mirror/pull feed "flickr:movie:12-angry-men-1957")]
      (is (= "flickr" (:source doc)))
      (is (= "12 Angry Men" (:title doc)))
      (is (= "https://stream.kopsa.info/#/item/51" (:source_ui_href doc))
          "the verified hash deep link — the :origin affordance"))
    (testing "a hub birth routes on :source and claims a namespaced
              identity"
      (let [[xid etag] (mirror/push-create
                        feed {:source "hub" :title "Some dinner rec"
                              :medium "book" :status "queued"})]
        (is (str/starts-with? xid "hub:"))
        (is (= hub/etag etag))))))
