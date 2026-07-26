(ns workqueue10.gtasks-test
  "The Google Tasks boundary, whole: the translation, the two seams
  the spec warns fail SILENTLY (an etag that cannot see our own
  mapping change, and a list read that hides its deletions), the
  incremental cursor with its overlap window, and the one push that
  travels.

  No database and no network — the fake stands behind the TRANSPORT,
  so every assertion below runs the real source's paging, path
  building, cursor arithmetic and translation. The fake also polices
  the query the way Google does: a read that forgot showDeleted would
  see no deleted tasks here either, which is what turns the spec's
  silent seam into a failing test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.gtasks :as gt]
            [waymark10.server.mirror :as mirror])
  (:import (java.time Instant)))

(def ^:private list-id "MTIzNA")

(defn- fake []
  (gt/fake-source {:lists list-id}))

(defn- doc-of [fake id]
  (first (conf/source-pull fake id)))

(defn- etag-of [fake id]
  (second (conf/source-pull fake id)))

;; ── the translation ─────────────────────────────────────────────────

(deftest task-translation
  (testing "status, per the API's two values — deletion is gone, never
            a status"
    (is (= "open" (:status (gt/task->doc {:title "x" :status "needsAction"} nil))))
    (is (= "done" (:status (gt/task->doc {:title "x" :status "completed"} nil)))))

  (testing "a third status fails loudly rather than mapping silently"
    (is (thrown? IllegalArgumentException
                 (gt/task->doc {:title "x" :status "archived"} nil))))

  (testing "title, notes and google's own web link ride across"
    (let [d (gt/task->doc {:title "Sharpen chisels"
                           :status "needsAction"
                           :notes "the 12mm one first"
                           :webViewLink "https://tasks.google.com/task/abc"}
                          nil)]
      (is (= "Sharpen chisels" (:title d)))
      (is (= "the 12mm one first" (:detail d)))
      (is (= "https://tasks.google.com/task/abc" (:source_ui_href d))
          "the one origin link in the queue no client of ours invented")))

  (testing "a subtask FLATTENS: the parent's title prefixes the detail,
            and no parent leaves it off"
    (is (= "Kitchen remodel — pick a faucet"
           (:detail (gt/task->doc {:title "Faucet" :status "needsAction"
                                   :notes "pick a faucet"
                                   :parent "p-1"}
                                  "Kitchen remodel"))))
    (is (= "Kitchen remodel"
           (:detail (gt/task->doc {:title "Faucet" :status "needsAction"
                                   :parent "p-1"}
                                  "Kitchen remodel"))))
    (is (nil? (:detail (gt/task->doc {:title "Faucet" :status "needsAction"}
                                     nil)))
        "nothing to say is nil, not an empty string"))

  (testing "due records a DATE: it widens to the day's CLOSING midnight
            UTC — the chore-source law, so overdue flips the morning
            after"
    (is (= "2026-07-26T00:00:00Z" (gt/due->instant "2026-07-25")))
    (is (= "2026-07-26T00:00:00Z" (gt/due->instant "2026-07-25T00:00:00.000Z"))
        "the API dresses the day as an instant; only the day is a fact")
    (is (= "2026-07-26T00:00:00Z" (gt/due->instant "2026-07-25T17:30:00.000Z"))
        "…and a time portion, which the API says it discards, is not
         read back as precision we do not have")
    (is (nil? (gt/due->instant nil)))
    (is (nil? (gt/due->instant "")))
    (is (nil? (gt/due->instant "not-a-date"))
        "one unreadable field costs its own value, never the pass")))

(deftest gone-is-two-flags
  (testing "deleted and hidden (completed-then-CLEARED) are both gone —
            a merely completed task is not"
    (is (gt/gone? {:deleted true}))
    (is (gt/gone? {:hidden true}))
    (is (not (gt/gone? {:status "completed"})))
    (is (not (gt/gone? {:status "needsAction"})))))

(deftest identity-splits-on-the-first-slash
  (is (= [list-id "abc"] (gt/split-id (str list-id "/abc"))))
  (is (= [list-id "a/b"] (gt/split-id (str list-id "/a/b")))
      "a task id carrying a slash survives the round trip")
  (is (thrown-with-msg? Exception #"carries no tasklist/task split"
                        (gt/split-id "bare-task-id"))))

(deftest naming-lists-narrows-saying-nothing-means-all
  (is (= ["a" "b"] (gt/parse-lists "a, b")))
  (is (= ["a" "b"] (gt/parse-lists ["a" "b"])))
  (is (nil? (gt/parse-lists nil))
      "unsaid is EVERY list the account has — the household keeps ten
       and wanted ten; the old reading mirrored @default alone and
       never said so")
  (is (nil? (gt/parse-lists "")))
  (is (nil? (gt/parse-lists []))))

;; ── the etag seam ───────────────────────────────────────────────────

(deftest etag-carries-the-translation-revision
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]
    (testing "google's own etag, with this namespace's translation
              revision composed onto it — the remote version can see
              google changing and can NEVER see our mapping changing,
              so the two travel together"
      (let [etag (etag-of f id)]
        (is (str/ends-with? etag (str "|" gt/translation-rev)))
        (is (= (str (:etag (gt/stored f list-id "t-1"))
                    "|" gt/translation-rev)
               etag))))

    (testing "the authority moving moves the etag (no content hash
              needed — google mints a real version)"
      (let [before (etag-of f id)]
        (gt/complete! f list-id "t-1")
        (is (not= before (etag-of f id)))))))

;; ── the deletion seam ───────────────────────────────────────────────

(defn- list-params
  "The params of every tasks.list the source has made."
  [f]
  (keep (fn [{:keys [method path params]}]
          (when (and (= "GET" method) (not (str/includes? path "/tasks/")))
            params))
        (gt/requests f)))

(deftest discover-asks-for-the-things-that-would-otherwise-vanish
  (let [f (fake)]
    (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})
    (conf/source-discover f)
    (testing "showDeleted, showHidden and showCompleted ride TOGETHER —
              updatedMin alone would hide deletions and hide
              completed-then-cleared tasks, and the failure mode is
              rows that quietly stop being reconciled"
      (let [params (first (list-params f))]
        (is (true? (:showDeleted params)))
        (is (true? (:showHidden params)))
        (is (true? (:showCompleted params)))
        (is (= gt/page-size (:maxResults params))
            "the API's ceiling — the default 20 would be round trips
             for nothing")))))

(deftest deleted-and-cleared-both-read-as-gone
  (let [f (fake)
        deleted (gt/seed! f list-id {:id "t-del" :title "Buy milk"})
        cleared (gt/seed! f list-id {:id "t-clr" :title "Call the vet"})
        purged (gt/seed! f list-id {:id "t-purge" :title "Old thing"})
        alive (gt/seed! f list-id {:id "t-live" :title "Sharpen chisels"})]
    (gt/delete! f list-id "t-del")
    (gt/clear! f list-id "t-clr")
    (gt/purge! f list-id "t-purge")

    (testing "discovery carries neither the deleted nor the cleared one"
      (is (= [alive] (conf/source-discover f))))

    (testing "a batch pull answers :gone for each — the observed
              deletion the :on-gone policy reads, including the one
              google has swept off the list entirely"
      (let [pulled (conf/source-pull-many f [deleted cleared purged alive])]
        (is (= :gone (get pulled deleted)))
        (is (= :gone (get pulled cleared)))
        (is (= :gone (get pulled purged)))
        (is (vector? (get pulled alive)))))

    (testing "a singular pull throws the 404-shaped gone the protocol
              asks for"
      (is (= 404 (:status (ex-data (try (conf/source-pull f deleted)
                                        (catch clojure.lang.ExceptionInfo e e))))))
      (is (= 404 (:status (ex-data (try (conf/source-pull f cleared)
                                        (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest a-completed-task-is-a-status-not-a-deletion
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]
    (gt/complete! f list-id "t-1")
    (is (empty? (conf/source-discover f))
        "discovery queues open work only")
    (is (= "done" (:status (doc-of f id)))
        "…but a row we already hold reconciles to done, not to gone")))

(deftest a-list-that-will-not-answer-is-unreachable-not-empty
  (let [f (fake)]
    (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})
    (gt/down! f true)
    (testing "an unreachable google throws on every verb — the
              confluence keeps the stored truth serving; a silently
              empty feed would look like the whole list was deleted"
      (is (thrown? Exception (conf/source-discover f)))
      (is (thrown? Exception (conf/source-pull-many f [(str list-id "/t-1")])))
      (is (thrown? Exception (conf/source-pull f (str list-id "/t-1")))))
    (gt/down! f false)
    (is (= 1 (count (conf/source-discover f))))))

;; ── the cursor ──────────────────────────────────────────────────────

(deftest the-cursor-is-googles-clock-and-asks-from-behind-it
  (let [f (fake)]
    (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})

    (testing "the first pass asks for everything — no cursor, no bound"
      (conf/source-discover f)
      (is (nil? (:updatedMin (first (list-params f))))))

    (testing "the cursor is the highest `updated` google showed us,
              never our own clock"
      (is (= (:updated (gt/stored f list-id "t-1")) (gt/cursor f))))

    (testing "the next pass asks from the OVERLAP window behind it, so
              a write google stamped a hair before our read is not lost
              forever"
      (let [before (gt/cursor f)]
        (conf/source-discover f)
        (is (= (str (.minusSeconds (Instant/parse before) gt/overlap-seconds))
               (:updatedMin (last (list-params f)))))))

    (testing "…and a row re-seen inside that window is discovered once,
              not twice"
      (is (= 1 (count (conf/source-discover f)))))

    (testing "the cursor advances when the authority moves"
      (let [before (gt/cursor f)]
        (gt/seed! f list-id {:id "t-2" :title "Order clamps"})
        (conf/source-discover f)
        (is (pos? (compare (gt/cursor f) before)))))))

(deftest the-cursor-does-not-advance-past-a-failed-pass
  (let [f (fake)]
    (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})
    (conf/source-discover f)
    (let [held (gt/cursor f)]
      (gt/down! f true)
      (is (thrown? Exception (conf/source-discover f)))
      (is (= held (gt/cursor f))
          "a pass that threw mid-way leaves the cursor where it was —
           the next pass re-reads the window rather than skipping it"))))

(deftest high-water-never-walks-backwards
  (is (= "2026-07-25T12:00:00Z"
         (gt/high-water "2026-07-25T12:00:00Z" ["2026-07-25T08:00:00Z"])))
  (is (= "2026-07-25T14:00:00Z"
         (gt/high-water "2026-07-25T12:00:00Z" ["2026-07-25T14:00:00Z"
                                                "2026-07-25T09:00:00Z"])))
  (is (= "2026-07-25T12:00:00Z" (gt/high-water "2026-07-25T12:00:00Z" [])))
  (is (nil? (gt/high-water nil []))
      "nothing seen yet is no cursor at all — the next pass reads the
       world")
  (is (nil? (gt/updated-min nil))))

(deftest paging-follows-to-the-end
  ;; a truncated feed would read as a list-wide deletion, so the loop
  ;; is not optional
  (let [f (fake)]
    (dotimes [n (inc gt/page-size)]
      (gt/seed! f list-id {:id (format "t-%03d" n) :title (str "task " n)}))
    (is (= (inc gt/page-size) (count (conf/source-discover f))))
    (is (= 2 (count (list-params f))) "one page over the ceiling is two calls")
    (is (some? (:pageToken (last (list-params f)))))))

;; ── identity, hrefs and the batch ───────────────────────────────────

(deftest pull-many-reads-one-list-not-one-task
  (let [f (fake)
        a (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"
                               :due "2026-07-25T00:00:00.000Z"
                               :webViewLink "https://tasks.google.com/task/t-1"})
        b (gt/seed! f list-id {:id "t-2" :title "Order clamps"})]
    (let [pulled (conf/source-pull-many f [a b])]
      (is (= #{a b} (set (keys pulled))))
      (is (= "Sharpen chisels" (:title (first (get pulled a)))))
      (is (= "2026-07-26T00:00:00Z" (:due_at (first (get pulled a)))))
      (is (= (str gt/api-base "/lists/" list-id "/tasks/t-1")
             (:source_href (first (get pulled a))))
          "the API route back, beside google's browser link")
      (is (= "https://tasks.google.com/task/t-1"
             (:source_ui_href (first (get pulled a))))))
    (is (= 1 (count (list-params f)))
        "one whole-list read for the batch, not one GET per id")))

(deftest a-subtask-borrows-its-parents-title-from-the-list
  (let [f (fake)
        _ (gt/seed! f list-id {:id "p-1" :title "Kitchen remodel"})
        kid (gt/seed! f list-id {:id "t-1" :title "Faucet" :parent "p-1"
                                 :notes "pick one"})]
    (is (= "Kitchen remodel — pick one"
           (:detail (first (get (conf/source-pull-many f [kid]) kid))))
        "the batch already holds the parent — no extra round trip")
    (is (= "Kitchen remodel — pick one" (:detail (doc-of f kid)))
        "a singular pull spends the one extra GET for it")))

;; ── push ────────────────────────────────────────────────────────────

(deftest only-a-local-done-travels
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]

    (testing "a hub-local write (prioritize) says nothing to google and
              answers the fresh etag — the round trip doubles as a
              freshness check"
      (let [etag (conf/source-push f id {:status "open" :priority 1})]
        (is (= (etag-of f id) etag))
        (is (= "needsAction" (:status (gt/stored f list-id "t-1")))
            "google never heard about the ranking")))

    (testing "Done travels as the status PATCH, and only the status —
              re-sending a mirrored title would overwrite an edit made
              on the phone"
      (let [etag (conf/source-push f id {:status "done" :title "Renamed here"})
            task (gt/stored f list-id "t-1")]
        (is (= "completed" (:status task)))
        (is (= "Sharpen chisels" (:title task)))
        (is (= (str (:etag task) "|" gt/translation-rev) etag))))

    (testing "an authority that already agrees is idempotence, not a
              second write"
      (let [rev (:etag (gt/stored f list-id "t-1"))]
        (conf/source-push f id {:status "done"})
        (is (= rev (:etag (gt/stored f list-id "t-1"))))))))

(deftest push-fences-on-the-raw-google-etag
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]
    (conf/source-push f id {:status "done"})
    (let [patch (first (filter #(= "PATCH" (:method %)) (gt/requests f)))]
      (is (= {:status "completed"} (:body patch))
          "the one field that travels")
      (is (not (str/includes? (str (:if-match patch)) gt/translation-rev))
          "the fence presented UPSTREAM is google's own etag — the
           translation revision is ours to report, never theirs to
           check"))))

(deftest push-against-a-gone-task-is-the-conflicted-state
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]
    (gt/delete! f list-id "t-1")
    (is (= 404 (:status (ex-data (try (conf/source-push f id {:status "done"})
                                      (catch clojure.lang.ExceptionInfo e e))))))))

(deftest google-tasks-takes-no-births
  (is (thrown-with-msg? Exception #"takes no births"
                        (conf/source-create (fake) {:title "Something new"}))))

;; ── the lists ───────────────────────────────────────────────────────

(defn- inventory-calls
  "Every read of the account's own inventory of lists."
  [f]
  (filter #(and (= "GET" (:method %))
                (= gt/lists-path (:path %)))
          (gt/requests f)))

(deftest unset-lists-means-every-list-the-account-has
  (let [f (gt/fake-source)]                 ; no :lists — the all posture
    (gt/list! f "L-home" "Home")
    (gt/list! f "L-work" "Work")
    (gt/seed! f "L-home" {:id "t-1" :title "Sharpen chisels"})
    (gt/seed! f "L-work" {:id "t-2" :title "File the expense report"})

    (testing "discovery reads the inventory and then every list in it —
              ten lists in the household, ten mirrored"
      (is (= #{"L-home/t-1" "L-work/t-2"} (set (conf/source-discover f))))
      (is (= 1 (count (inventory-calls f)))
          "one inventory read per pass, not one per list"))

    (testing "a list added on the phone joins the next pass by itself"
      (gt/list! f "L-shed" "Shed")
      (gt/seed! f "L-shed" {:id "t-3" :title "Order clamps"})
      (is (contains? (set (conf/source-discover f)) "L-shed/t-3")))

    (testing "the list feed mirrors the same inventory"
      (is (= #{"L-home" "L-work" "L-shed"} (set (conf/list-discover f))))
      (let [[doc etag] (conf/list-pull f "L-home")]
        (is (= "Home" (:title doc)))
        (is (= (str gt/api-base gt/lists-path "/L-home") (:source_href doc)))
        (is (nil? (:source_ui_href doc))
            "google publishes no per-list web URL, so the origin link
             honestly omits rather than guessing")
        (is (str/ends-with? etag (str "|" gt/translation-rev)))))))

(deftest naming-lists-narrows-and-never-asks-google-what-it-has
  (let [f (gt/fake-source {:lists "L-home"})]
    (gt/list! f "L-home" "Home")
    (gt/list! f "L-work" "Work")
    (gt/seed! f "L-home" {:id "t-1" :title "Sharpen chisels"})
    (gt/seed! f "L-work" {:id "t-2" :title "File the expense report"})
    (is (= ["L-home/t-1"] (conf/source-discover f)))
    (is (= ["L-home"] (conf/list-discover f)))
    (is (empty? (inventory-calls f))
        "an operator who named the lists has already answered the
         question the inventory route asks")))

(deftest the-list-batch-is-one-inventory-read
  (let [f (gt/fake-source)]
    (gt/list! f "L-home" "Home")
    (gt/list! f "L-work" "Work")
    (let [pulled (conf/list-pull-many f ["L-home" "L-work" "L-gone"])]
      (is (= "Home" (:title (first (get pulled "L-home")))))
      (is (= "Work" (:title (first (get pulled "L-work")))))
      (is (= :gone (get pulled "L-gone"))
          "absent from an inventory that ANSWERED is gone — unlike the
           task collection there are no show-flags to forget here")
      (is (= 1 (count (inventory-calls f)))))))

(deftest a-deleted-list-is-gone-not-empty
  (let [f (gt/fake-source)]
    (gt/list! f "L-home" "Home")
    (gt/seed! f "L-home" {:id "t-1" :title "Sharpen chisels"})
    (gt/drop-list! f "L-home")
    (is (empty? (conf/list-discover f)))
    (is (= :gone (get (conf/list-pull-many f ["L-home"]) "L-home")))
    (is (= 404 (:status (ex-data (try (conf/list-pull f "L-home")
                                      (catch clojure.lang.ExceptionInfo e e))))))))

(deftest an-unreachable-google-keeps-the-lists-stored
  (let [f (gt/fake-source)]
    (gt/list! f "L-home" "Home")
    (gt/down! f true)
    (is (thrown? Exception (conf/list-discover f)))
    (is (thrown? Exception (conf/list-pull f "L-home")))
    (is (thrown? Exception (conf/list-pull-many f ["L-home"])))))

(deftest a-task-carries-the-list-it-lives-in
  (let [f (fake)
        id (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})]
    (is (= list-id (:list_key (doc-of f id)))
        "source-local here — the confluence namespaces it")
    (is (= list-id (:list_key (first (get (conf/source-pull-many f [id]) id))))
        "the batch and the singular read agree")))

;; ── through the confluence ──────────────────────────────────────────

(deftest the-confluence-tags-it
  (let [f (fake)
        _ (gt/seed! f list-id {:id "t-1" :title "Sharpen chisels"})
        feed (conf/confluence {"gtasks" f})
        lists (conf/list-confluence (conf/list-sources {"gtasks" f}))]
    (is (= [(conf/xid "gtasks" (str list-id "/t-1"))]
           (vec (mirror/discover feed))))
    (let [[doc _] (mirror/pull feed (conf/xid "gtasks" (str list-id "/t-1")))]
      (is (= "gtasks" (:source doc)))
      (is (= "Sharpen chisels" (:title doc)))
      (testing "…and the task's list key is the LIST row's external id,
                which is the whole point of the shared tag"
        (is (= (conf/xid "gtasks" list-id) (:list_key doc)))
        (is (contains? (set (mirror/discover lists)) (:list_key doc)))))))
