(ns calendar10.oauth-test
  "The outbound credential's caching law, over a scripted mint — no
  network. What matters here is that the stored secret is the refresh
  token and the access token is derived, short, and re-derived before
  it can expire under a running pass."
  (:require [calendar10.oauth :as oauth]
            [clojure.test :refer [deftest is testing]]))

(def ^:private config
  {:client-id "cid" :client-secret "secret" :refresh-token "1//refresh"})

(defn- scripted
  "A mint that counts its calls and answers the given lifetimes in
  turn, minting a distinct token each time."
  [lifetimes]
  (let [calls (atom 0)]
    [calls
     (fn [_config]
       (let [n (swap! calls inc)]
         {:access_token (str "ya29.token-" n)
          :expires_in (nth lifetimes (dec n) (last lifetimes))}))]))

(deftest an-unconfigured-credential-is-nil
  (testing "so an app wires it unconditionally and offline dev has no calendar"
    (is (nil? (oauth/access-token-fn {})))
    (is (nil? (oauth/access-token-fn (assoc config :refresh-token ""))))
    (is (nil? (oauth/access-token-fn (assoc config :client-secret nil))))))

(deftest a-live-token-is-reused
  (let [[calls mint] (scripted [3600])
        token-fn (oauth/access-token-fn (assoc config :mint-fn mint))]
    (is (= "ya29.token-1" (token-fn)))
    (is (= "ya29.token-1" (token-fn)))
    (is (= "ya29.token-1" (token-fn)))
    (is (= 1 @calls) "one mint serves the hour — no token per call")))

(deftest a-token-near-its-exp-is-re-minted
  (testing "the skew is wider than a pass: a token with 30s left is not reused"
    (let [[calls mint] (scripted [30 3600])
          token-fn (oauth/access-token-fn (assoc config :mint-fn mint))]
      (is (= "ya29.token-1" (token-fn)))
      (is (= "ya29.token-2" (token-fn))
          "30s of life is inside the refresh skew — mint again rather than
           let a discovery pass die mid-flight")
      (is (= "ya29.token-2" (token-fn)) "the fresh hour then caches")
      (is (= 2 @calls)))))

(deftest a-response-without-a-token-throws
  (let [token-fn (oauth/access-token-fn
                  (assoc config :mint-fn (fn [_] {:expires_in 3600})))]
    (is (thrown-with-msg? Exception #"no access_token" (token-fn)))))

(deftest a-refusal-propagates
  (testing "an un-mintable credential is one unreachable feed, not an empty one"
    (let [token-fn (oauth/access-token-fn
                    (assoc config :mint-fn
                           (fn [_] (throw (ex-info "google refused the refresh-token mint (400): invalid_grant"
                                                   {:status 400})))))]
      (is (thrown-with-msg? Exception #"invalid_grant" (token-fn))))))

(deftest the-form-encoding-is-url-safe
  (is (= "grant_type=refresh_token&refresh_token=1%2F%2Fabc%2Bd"
         (oauth/form-encode {"grant_type" "refresh_token"
                             "refresh_token" "1//abc+d"})))
      )
