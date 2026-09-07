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
            [workqueue10.main :as main]
            [workqueue10.sources.flickr :as fk]
            [workqueue10.sources.hub :as hub]
            [waymark10.schema :as schema]
            [waymark10.server.mirror :as mirror]))

(def ^:private movie
  ;; the verified live shape, byte for byte (spec-media-flickr.md);
  ;; medium joined the work in flickr's design note 13
  {:work_key "movie:12-angry-men-1957" :kind "movie" :medium "video"
   :title "12 Angry Men" :year 1957 :genres [] :overview ""
   :episode_count 0 :item_count 1 :representative_item_id 51})

(def ^:private colton
  {:name "Colton" :status "active"
   :progress 0.0137M :progress_text "1:19" :updated_at 1785133261.301})

;; the three kinds design note 13 grew, in the README's library-layout
;; grammar: an audiobook SET (parts), a single chaptered m4b, an album
;; (tracks), and a book — each with the author or artist its path names
(def ^:private dune
  {:work_key "audiobook:frank-herbert/dune-1965" :kind "audiobook" :medium "audio"
   :title "Dune" :author "Frank Herbert" :year 1965 :genres [] :overview ""
   :episode_count 0 :part_count 12 :item_count 12 :representative_item_id 301})

(def ^:private dispossessed
  {:work_key "audiobook:ursula-k-le-guin/the-dispossessed" :kind "audiobook" :medium "audio"
   :title "The Dispossessed" :author "Ursula K. Le Guin" :genres [] :overview ""
   :episode_count 0 :item_count 1 :representative_item_id 340})

(def ^:private ok-computer
  {:work_key "album:radiohead/ok-computer-1997" :kind "album" :medium "audio"
   :title "OK Computer" :author "Radiohead" :year 1997 :genres [] :overview ""
   :episode_count 0 :track_count 12 :item_count 12 :representative_item_id 410})

(def ^:private orwell
  {:work_key "book:george-orwell/1984" :kind "book" :medium "text"
   :title "1984" :author "George Orwell" :genres [] :overview ""
   :episode_count 0 :item_count 1 :representative_item_id 520})

(defn- heard
  "One audience entry in flickr's own progress words."
  [text frac]
  {:name "Colton" :status "active" :progress frac :progress_text text
   :updated_at 1786000000.0})

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

(deftest two-words-for-what-it-is
  ;; flickr's `kind` is the word a person says (→ :medium, the
  ;; envelope's tag); its `medium` is the file's nature (→ :format);
  ;; `author` is the author or the artist (→ :creator)
  (testing "an audiobook: the kind word, audio, and its author"
    (let [d (fk/work->doc dune nil)]
      (is (= "audiobook" (:medium d)))
      (is (= "audio" (:format d)))
      (is (= "Frank Herbert" (:creator d)))
      (is (= 1965 (:year d)))))
  (testing "an album by an artist"
    (let [d (fk/work->doc ok-computer nil)]
      (is (= "album" (:medium d)))
      (is (= "audio" (:format d)))
      (is (= "Radiohead" (:creator d)))))
  (testing "a book is text"
    (let [d (fk/work->doc orwell nil)]
      (is (= "book" (:medium d)))
      (is (= "text" (:format d)))
      (is (= "George Orwell" (:creator d)))))
  (testing "a movie is video and names no creator yet — absence, not
            an empty string, so the hub's own word would stand"
    (let [d (fk/work->doc movie nil)]
      (is (= "video" (:format d)))
      (is (not (contains? d :creator)))))
  (testing "a work from before the field existed carries no :format —
            the wire's omission is silence here too"
    (is (not (contains? (fk/work->doc (dissoc movie :medium) nil) :format)))))

(deftest progress-words-pass-through-in-every-shape
  ;; the fraction law's second half: the authority's words are kept
  ;; exactly as spelled, whatever grammar the work's shape gave them
  (testing "an audiobook set: part n of m and the clock inside it"
    (let [d (fk/work->doc (assoc dune :audiences [(heard "part 3 of 12 · 41:10" 0.21M)]) nil)]
      (is (= "part 3 of 12 · 41:10" (:progress_text d)))
      (is (= 0.21M (:progress d)))))
  (testing "a chaptered single file: the chapter, then position over the whole"
    (let [d (fk/work->doc (assoc dispossessed :audiences
                                 [(heard "ch. 7 · 1:19:22 / 11:30:00" 0.115M)]) nil)]
      (is (= "ch. 7 · 1:19:22 / 11:30:00" (:progress_text d)))))
  (testing "an album: track n of m"
    (is (= "track 3 of 12 · 2:41"
           (:progress_text (fk/work->doc (assoc ok-computer :audiences
                                                [(heard "track 3 of 12 · 2:41" 0.19M)]) nil)))))
  (testing "a book, once the reader lands: the chapter and a percent"
    (let [d (fk/work->doc (assoc orwell :audiences [(heard "ch. 7 · 34%" 0.34M)]) nil)]
      (is (= "ch. 7 · 34%" (:progress_text d)))
      (is (= 0.34M (:progress d)))))
  (testing "a book TODAY: flickr reports a position with no locator —
            text \"\", fraction 0 — and blank words are silence, not a
            position: status lands, neither progress field is asserted,
            so a place the hub logged by hand stands"
    (let [d (fk/work->doc (assoc orwell :audiences [(heard "" 0.0)]) nil)]
      (is (= "active" (:status d)))
      (is (= "Colton" (:audience_name d)))
      (is (not (contains? d :progress_text)))
      (is (not (contains? d :progress))))))

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
                          :title "Colton's Minecraft Adventure"}))))
  (testing "the README's route table has no album or audiobook listing,
            so every other kind opens at its representative item — a
            set's or an album's item pane lists the parts or tracks"
    (is (= "https://stream.kopsa.info/#/item/301"
           (fk/deep-link "https://stream.kopsa.info" dune))
        "a multi-part audiobook")
    (is (= "https://stream.kopsa.info/#/item/340"
           (fk/deep-link "https://stream.kopsa.info" dispossessed))
        "a single-file audiobook")
    (is (= "https://stream.kopsa.info/#/item/410"
           (fk/deep-link "https://stream.kopsa.info" ok-computer))
        "an album")
    (is (= "https://stream.kopsa.info/#/item/520"
           (fk/deep-link "https://stream.kopsa.info" orwell))
        "a book")))

;; ── discovery and the kind filter ───────────────────────────────────

(deftest the-works-of-intent-mirror
  (let [f (fk/fake-source)]
    (fk/seed! f movie)
    (fk/seed! f {:work_key "show:ninjago" :kind "show" :title "Ninjago"})
    (doseq [w [dune dispossessed ok-computer orwell]] (fk/seed! f w))
    (fk/seed! f {:work_key "file:250" :kind "file"
                 :title "'Pocalypse Preppin' - Checkers.mkv"})
    (testing "discovery names the works of intent — movie, show,
              audiobook, album, book — and never the per-file
              inventory: 322 unidentified files at verification,
              excluded by decision"
      (is (= #{"movie:12-angry-men-1957" "show:ninjago"
               (:work_key dune) (:work_key dispossessed)
               (:work_key ok-computer) (:work_key orwell)}
             (set (conf/source-discover f)))))
    (testing "the new kinds pull as whole docs, deep link and all"
      (let [[doc etag] (conf/source-pull f (:work_key ok-computer))]
        (is (= "album" (:medium doc)))
        (is (= "Radiohead" (:creator doc)))
        (is (= "https://stream.kopsa.info/#/item/410" (:source_ui_href doc)))
        (is (str/ends-with? etag (str "|" fk/translation-rev)))))
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

(deftest the-media-declaration-admits-every-mirrored-kind
  ;; the source's kind filter and the kind's :medium enum are two
  ;; spellings of one vocabulary; a work the source lets through that
  ;; the schema refuses would fail at observe, row by row
  (let [media (first (filter #(= :media (:kind %)) (main/check-resources)))
        [tag & admitted] (schema/field-schema (:schema media) :medium)
        [ctag & birth] (schema/field-schema (:create-schema media) :medium)
        choices (get-in (schema/entry-map (:create-schema media))
                        [:medium :properties :x-display :choices])]
    (is (= :enum tag))
    (is (every? (set admitted) fk/mirrored-kinds)
        (str "the enum is missing a mirrored kind: "
             (pr-str (remove (set admitted) fk/mirrored-kinds))))
    (is (contains? (set admitted) "album") "album joined the vocabulary")
    (testing "the birth door offers the same words, each with a sentence"
      (is (= :enum ctag))
      (is (= (set admitted) (set birth)))
      (is (= (set birth) (set (keys choices)))))
    (testing ":format is the file's nature beside the kind word"
      (is (= [:enum "video" "audio" "text"]
             (schema/field-schema (:schema media) :format))))))

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

;; ── the places a work offers (waymark-z8u4) ─────────────────────────
;; The chapter picker: a decision's passage launch offers from and to
;; off the media row's own chapters and episodes. The tokens are
;; VALUES the passage grammar reads back — a chip spells a place, the
;; guard still judges it — so every token here is parsed as well as
;; compared.

(def ^:private cellar-chapters
  ;; an item's media_info.chapters, {start_s title}; the fraction on
  ;; the last one is a chapter atom's, not a place anyone names
  [{:start_s 0 :title "Opening"}
   {:start_s 4740 :title "The Cellar"}
   {:start_s 5070.5 :title "The Verdict"}])

(def ^:private the-wire
  {:work_key "show:the-wire" :kind "show" :medium "video"
   :title "The Wire" :genres [] :overview ""
   :episode_count 3 :item_count 3 :representative_item_id 61
   ;; the fake's shelf (seed!): the episodes, one of them twice, and
   ;; an extra that is no episode at all
   :items [{:id 63 :season 2 :episode 5}
           {:id 61 :season 1 :episode 1}
           {:id 62 :season 1 :episode 2}
           {:id 64 :season 2 :episode 5}
           {:id 99 :title "extras"}]})

(deftest places-are-tokens-in-the-passage-grammar
  (testing "a film's chapters are times — H:MM:SS past the hour, M:SS
            under it — and every one reads back"
    (is (= ["0:00" "1:19:00" "1:24:30"] (fk/chapters->tokens "movie" cellar-chapters)))
    (doseq [t (fk/chapters->tokens "movie" cellar-chapters)]
      (is (= :time (:grammar (passage/parse t))) t)
      (is (nil? (passage/misfit (passage/parse t) "movie")) t)))
  (testing "a chaptered m4b is an audiobook's chapters the same way;
            an album's tracks too"
    (is (= ["0:00" "41:10" "1:19:22"]
           (fk/chapters->tokens "audiobook" [{:start_s 0 :title "Part 1"}
                                             {:start_s 2470 :title "Part 2"}
                                             {:start_s 4762 :title "Part 3"}])))
    (is (= ["2:41"] (fk/chapters->tokens "album" [{:start_s 161 :title "Airbag"}]))))
  (testing "a show's episodes are S02E05 0:00 — sorted by season and
            number, each once, an item that is no episode skipped —
            and each reads as an episode and a time"
    (let [toks (fk/chapters->tokens "show" (:items the-wire))]
      (is (= ["S01E01 0:00" "S01E02 0:00" "S02E05 0:00"] toks))
      (is (= "S02E05" (get-in (passage/parse (last toks)) [:episode :text])))
      (is (nil? (passage/misfit (passage/parse (last toks)) "show")))))
  (testing "a book's sections are ch. 1 … ch. n in reading order — the
            n-th section is chapter n, as flickr's ch:<n> locator counts"
    (is (= ["ch. 1" "ch. 2" "ch. 3"]
           (fk/chapters->tokens "book" [{:title "I"} {:title "II"} {:title "III"}])))
    (is (nil? (passage/misfit (passage/parse "ch. 3") "book")))
    (is (= ["ch. 1"] (fk/chapters->tokens "comic" [{:title "Issue 1"}]))))
  (testing "nothing to offer is []"
    (is (= [] (fk/chapters->tokens "movie" [])))
    (is (= [] (fk/chapters->tokens "movie" nil)))
    (is (= [] (fk/chapters->tokens nil cellar-chapters)) "a medium never said")
    (is (= [] (fk/chapters->tokens "file" cellar-chapters))
        "a medium the grammar does not know")))

(deftest places-are-read-off-the-row-through-the-source
  (let [f (fk/fake-source)]
    (fk/seed! f (assoc movie :items [{:id 51 :media_info {:chapters cellar-chapters}}]))
    (fk/seed! f the-wire)
    (fk/seed! f (assoc orwell :items [{:id 520 :media_info {:sections [{:title "Part One"}
                                                                       {:title "Part Two"}]}}]))
    (testing "a film: the item the row's own deep link names, its chapters"
      (let [[doc] (conf/source-pull f "movie:12-angry-men-1957")]
        (is (= ["0:00" "1:19:00" "1:24:30"] (fk/places f doc)))
        (is (= "/api/items/51" (:path (last (fk/requests f)))))))
    (testing "a show: the work's items, by key — the key travels encoded"
      (let [[doc] (conf/source-pull f "show:the-wire")]
        (is (= ["S01E01 0:00" "S01E02 0:00" "S02E05 0:00"] (fk/places f doc)))
        (is (= "/api/works/show%3Athe-wire/items" (:path (last (fk/requests f)))))))
    (testing "a book: the item's sections"
      (let [[doc] (conf/source-pull f (:work_key orwell))]
        (is (= ["ch. 1" "ch. 2"] (fk/places f doc)))))
    (testing "the feed never carries the shelf"
      (is (not-any? #(contains? % :items)
                    (:works ((:call (:source f)) "GET" fk/feed-path {})))))
    (testing "a row with nothing to ask about offers nothing, and asks nothing"
      (let [before (count (fk/requests f))]
        (is (= [] (fk/places f {:medium "movie" :title "A hub film"})))
        (is (= [] (fk/places f {:medium "show" :title "A hub show"})))
        (is (= before (count (fk/requests f))))))
    (testing "a flickr that does not answer throws — the boot's hook,
              not this read, decides what silence is worth"
      (fk/down! f true)
      (is (thrown? clojure.lang.ExceptionInfo
                   (fk/places f {:medium "movie"
                                 :source_ui_href "https://stream.kopsa.info/#/item/51"}))))))

(deftest the-boots-hook-answers-for-every-row
  ;; main/places, the fn (:services eng) :places carries: the media
  ;; confluence's own flickr answers a flickr row; a hub row, a row of
  ;; another kind, and a dark socket all answer [] — the chips are
  ;; advertisement, and the form's box still takes a typed place
  (let [f (fk/fake-source)
        srcs {"flickr" f "hub" (hub/source)}]
    (fk/seed! f (assoc movie :items [{:id 51 :media_info {:chapters cellar-chapters}}]))
    (let [[doc] (conf/source-pull f "movie:12-angry-men-1957")
          row (assoc doc :source "flickr")]
      (is (= ["0:00" "1:19:00" "1:24:30"] (main/places srcs :media row)))
      (is (= [] (main/places srcs :media (assoc row :source "hub")))
          "a hub row has no authority to ask")
      (is (= [] (main/places srcs :task row))
          "a row of another kind names no places")
      (fk/down! f true)
      (is (= [] (main/places srcs :media row))
          "a flickr that does not answer offers nothing, and nothing throws"))))
