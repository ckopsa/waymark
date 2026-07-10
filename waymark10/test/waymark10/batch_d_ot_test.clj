(ns waymark10.batch-d-ot-test
  "Batch-D acceptance, part 1: the OT core, proved generatively. No
  database, no sockets — collab's transform/apply/accept-edit are
  pure, and this namespace drives exactly the functions the wire
  handlers call.

  The convergence proof (the acceptance bar): N clients (2–4), each
  a faithful relay/2 client model — one op in flight, a local queue
  of unacked ops, incoming server ops transformed through the queue
  with the same transform-pair the server uses — issue arbitrary
  interleaved insert/delete batches from arbitrary base revs. After
  quiescence every client's locally-composed document must equal
  the server's, and every client's rev the server's rev, across 250
  shrinkable trials. TP1 (the transform identity) gets its own 300
  trials. A transform bug shrinks to a minimal counterexample here
  before it ever touches a websocket.

  Compaction is proved at the cap: op-log-cap entries retained, a
  base rev behind the horizon answers stale (a resync, never
  corruption)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [waymark10.server.collab :as collab]))

;; ── op sugar ────────────────────────────────────────────────────────

(defn- ops-insert [len pos s]
  (collab/parse-ops [{:retain pos} {:insert s} {:retain (- len pos)}]))

(defn- ops-delete [len pos n]
  (collab/parse-ops [{:retain pos} {:delete n} {:retain (- len pos n)}]))

;; ── the primitives ──────────────────────────────────────────────────

(deftest parse-ops-normalizes-and-refuses
  (is (= [{:retain 3}] (collab/parse-ops [{:retain 1} {:retain 2}])))
  (is (= [{:insert "ab"}] (collab/parse-ops [{:insert "a"} {:insert "b"}])))
  (is (= [{:retain 1} {:delete 2}]
         (collab/parse-ops [{:retain 1} {:retain 0} {:insert ""} {:delete 2}])))
  (is (= [] (collab/parse-ops [])))
  (is (nil? (collab/parse-ops [{:retain -1}])) "negative lengths refuse")
  (is (nil? (collab/parse-ops [{:retain 1 :insert "x"}])) "one key per component")
  (is (nil? (collab/parse-ops ["x"])) "components are maps")
  (is (nil? (collab/parse-ops {:retain 1})) "ops is a sequence")
  (is (nil? (collab/parse-ops [{:grow 3}])) "unknown components refuse"))

(deftest apply-ops-spans-exactly
  (is (= "aXbc" (collab/apply-ops "abc" (ops-insert 3 1 "X"))))
  (is (= "ac" (collab/apply-ops "abc" (ops-delete 3 1 1))))
  (is (= "" (collab/apply-ops "" [])))
  (is (nil? (collab/apply-ops "abc" [{:retain 2}])) "short ops refuse")
  (is (nil? (collab/apply-ops "abc" [{:retain 4}])) "long ops refuse"))

(deftest transform-breaks-ties-in-server-order
  ;; both insert at position 0 of "": the a (server-priority) text first
  (let [a [{:insert "A"}] b [{:insert "B"}]
        [a' b'] (collab/transform-pair a b)]
    (is (= "AB" (collab/apply-ops (collab/apply-ops "" a) b')))
    (is (= "AB" (collab/apply-ops (collab/apply-ops "" b) a')))))

(deftest transform-refuses-mismatched-bases
  (is (nil? (collab/transform-pair [{:retain 2}] [{:retain 3}]))))

(defspec transform-tp1 300
  (prop/for-all
   [[doc a b]
    (gen/let [doc (gen/fmap #(apply str %)
                            (gen/vector gen/char-alphanumeric 0 20))
              a (gen/one-of
                 (cond-> [(gen/let [pos (gen/choose 0 (count doc))
                                    s (gen/not-empty gen/string-alphanumeric)]
                            (ops-insert (count doc) pos s))]
                   (pos? (count doc))
                   (conj (gen/let [pos (gen/choose 0 (dec (count doc)))
                                   n (gen/choose 1 (- (count doc) pos))]
                           (ops-delete (count doc) pos n)))))
              b (gen/one-of
                 (cond-> [(gen/let [pos (gen/choose 0 (count doc))
                                    s (gen/not-empty gen/string-alphanumeric)]
                            (ops-insert (count doc) pos s))]
                   (pos? (count doc))
                   (conj (gen/let [pos (gen/choose 0 (dec (count doc)))
                                   n (gen/choose 1 (- (count doc) pos))]
                           (ops-delete (count doc) pos n)))))]
      [doc a b])]
   (let [[a' b'] (collab/transform-pair a b)]
     (= (collab/apply-ops (collab/apply-ops doc a) b')
        (collab/apply-ops (collab/apply-ops doc b) a')))))

;; ── accept-edit: revs, the horizon, the cap ─────────────────────────

(defn- grown
  "A server field state after n sequential single-char inserts."
  [n]
  (reduce (fn [st i]
            (:state (collab/accept-edit st (:rev st)
                                        (ops-insert (count (:value st))
                                                    (count (:value st))
                                                    (str (mod i 10))))))
          {:value "" :rev 0 :log []}
          (range n)))

(deftest accept-edit-rev-discipline
  (let [st {:value "abc" :rev 3 :log [{:rev 3 :ops (ops-insert 2 2 "c")}]}]
    (testing "base = rev applies untransformed"
      (let [out (collab/accept-edit st 3 (ops-insert 3 0 "X"))]
        (is (= :applied (:outcome out)))
        (is (= "Xabc" (get-in out [:state :value])))
        (is (= 4 (get-in out [:state :rev])))))
    (testing "base ahead of the rev is stale"
      (is (= :stale (:outcome (collab/accept-edit st 4 [{:retain 3}])))))
    (testing "base behind the retained horizon is stale"
      (is (= :stale (:outcome (collab/accept-edit st 1 [{:retain 1}])))))
    (testing "ops that do not span the document are malformed"
      (is (= :malformed (:outcome (collab/accept-edit st 3 [{:retain 9}])))))))

(deftest op-log-caps-and-compacts
  (let [n (+ collab/op-log-cap 17)
        st (grown n)]
    (is (= n (:rev st)))
    (is (= collab/op-log-cap (count (:log st))) "the cap holds")
    (is (= (inc (- n collab/op-log-cap)) (:rev (first (:log st))))
        "oldest entries compacted away")
    (testing "a base just inside the horizon still transforms"
      (is (= :applied (:outcome (collab/accept-edit
                                 st (- n collab/op-log-cap)
                                 [{:retain (- n collab/op-log-cap)}])))))
    (testing "a base behind the horizon answers stale — resync, not LWW"
      (is (= :stale (:outcome (collab/accept-edit
                               st (dec (- n collab/op-log-cap))
                               [{:retain (dec (- n collab/op-log-cap))}])))))))

;; ── the convergence proof ───────────────────────────────────────────
;;
;; The client model is the protocol's client half: apply your own op
;; locally at once; keep unacked ops in order with at most the head
;; in flight (base = your last synced rev); on a remote op, transform
;; it through the queue (remote is server history — priority) and
;; apply the residue; on an ack, pop the head, adopt the rev, send
;; the next.

(defn- fresh-client [] {:doc "" :r 0 :outgoing [] :inflight? false :inbox []})

(defn- send-front [st i]
  (let [c (get-in st [:clients i])]
    (if (and (not (:inflight? c)) (seq (:outgoing c)))
      (-> st
          (assoc-in [:clients i :inflight?] true)
          (update :sin conj {:client i :base (:r c)
                             :ops (first (:outgoing c))}))
      st)))

(defn- client-edit [st i {:keys [pos len kind text]}]
  (let [c (get-in st [:clients i])
        doc (:doc c)
        l (count doc)
        op (if (or (= :insert kind) (zero? l))
             (ops-insert l (mod pos (inc l)) text)
             (let [p (mod pos l)]
               (ops-delete l p (inc (mod len (- l p))))))]
    (if (empty? op)
      st
      (-> st
          (update-in [:clients i :doc] #(collab/apply-ops % op))
          (update-in [:clients i :outgoing] conj op)
          (send-front i)))))

(defn- server-step [st]
  (if-some [{:keys [client base ops]} (first (:sin st))]
    (let [out (collab/accept-edit (:server st) base ops)]
      (if (not= :applied (:outcome out))
        (assoc st :failed (:outcome out))
        (-> st
            (assoc :server (:state out))
            (update :sin subvec 1)
            (update :clients
                    (fn [cs]
                      (let [rev (get-in out [:state :rev])]
                        (vec (map-indexed
                              (fn [j c]
                                (update c :inbox conj
                                        (if (= j client)
                                          {:t :ack :rev rev}
                                          {:t :edit :rev rev
                                           :ops (:ops' out)})))
                              cs))))))))
    st))

(defn- recv-edit [c {:keys [rev ops]}]
  (let [[rop out]
        (reduce (fn [[rop out] o]
                  (if-some [[rop' o'] (collab/transform-pair rop o)]
                    [rop' (conj out o')]
                    (reduced [::broken out])))
                [ops []] (:outgoing c))]
    (if (= ::broken rop)
      (assoc c :doc ::broken)
      (-> c
          (assoc :outgoing out)
          (update :doc #(some-> % (collab/apply-ops rop)))
          (assoc :r rev)))))

(defn- client-step [st i]
  (let [c (get-in st [:clients i])]
    (if-some [f (first (:inbox c))]
      (let [c' (update c :inbox subvec 1)
            c' (case (:t f)
                 :ack (-> c'
                          (update :outgoing subvec 1)
                          (assoc :r (:rev f) :inflight? false))
                 :edit (recv-edit c' f))
            st (assoc-in st [:clients i] c')]
        (if (= :ack (:t f)) (send-front st i) st))
      st)))

(defn- quiescent? [st]
  (and (empty? (:sin st))
       (every? #(and (empty? (:inbox %)) (empty? (:outgoing %)))
               (:clients st))))

(defn- drain [st]
  (loop [st st n 0]
    (cond
      (:failed st) st
      (quiescent? st) st
      (> n 100000) (assoc st :failed :no-quiescence)
      :else (recur (reduce client-step (server-step st)
                           (range (count (:clients st))))
                   (inc n)))))

(defn- run-sim [n-clients actions]
  (drain
   (reduce (fn [st a]
             (if (:failed st)
               st
               (case (:do a)
                 :edit (client-edit st (mod (:c a) n-clients) a)
                 :server (server-step st)
                 :client (client-step st (mod (:c a) n-clients)))))
           {:server {:value "" :rev 0 :log []}
            :sin []
            :clients (vec (repeatedly n-clients fresh-client))}
           actions)))

(def ^:private gen-action
  (gen/frequency
   [[5 (gen/let [c gen/nat
                 pos gen/nat
                 len gen/nat
                 kind (gen/elements [:insert :delete])
                 text (gen/not-empty gen/string-alphanumeric)]
        {:do :edit :c c :pos pos :len len :kind kind :text text})]
    [3 (gen/return {:do :server})]
    [3 (gen/let [c gen/nat] {:do :client :c c})]]))

(defspec clients-converge 250
  (prop/for-all [n (gen/choose 2 4)
                 actions (gen/vector gen-action 1 60)]
    (let [st (run-sim n actions)
          server-doc (get-in st [:server :value])
          server-rev (get-in st [:server :rev])]
      (and (nil? (:failed st))
           (every? #(= server-doc (:doc %)) (:clients st))
           (every? #(= server-rev (:r %)) (:clients st))))))

(deftest a-worked-concurrent-story
  ;; two clients, three interleaved edits each, deliveries maximally
  ;; delayed — the deterministic sibling of the generative proof
  (let [st (run-sim 2
                    (concat
                     (for [i (range 3)]
                       {:do :edit :c 0 :pos 0 :len 0 :kind :insert
                        :text (str "a" i)})
                     (for [i (range 3)]
                       {:do :edit :c 1 :pos 999 :len 0 :kind :insert
                        :text (str "b" i)})))]
    (is (nil? (:failed st)))
    (is (= (get-in st [:server :value])
           (get-in st [:clients 0 :doc])
           (get-in st [:clients 1 :doc])))
    (is (= 6 (get-in st [:server :rev])))))
