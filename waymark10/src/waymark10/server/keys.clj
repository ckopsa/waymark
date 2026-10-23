(ns waymark10.server.keys
  "Every key the engine checks is minted, hashed and compared here, and
  nowhere else: the seat's fire, inbox and feed keys, the transcript
  key, the invite token, the MCP session id, and the raw compares of a
  runner link's token, a subscription's secret and a signature.

  `mint` is 128 bits of SecureRandom, base64url, unpadded: 22
  characters no hand types. `hash` is `wire/sha256-hex`. `hash-b64` is
  the same SHA-256 in base64url, the spelling the seat and transcript
  rows hold today; it stays until those rows are moved, so no stored
  value changes here. `equal?` is the one constant-time compare, and
  `matches?` is it over a presented key's hash."
  (:refer-clojure :exclude [hash])
  (:require [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defonce ^:private ^SecureRandom random (SecureRandom.))

(defn- b64url ^String [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn mint
  "A fresh key: `n` random bytes (16 unless said), base64url, unpadded."
  (^String [] (mint 16))
  (^String [n]
   (let [b (byte-array (long n))]
     (.nextBytes random b)
     (b64url b))))

(defn hash
  "The SHA-256 of a key, lowercase hex (`wire/sha256-hex`)."
  ^String [key]
  (wire/sha256-hex (str key)))

(defn hash-b64
  "The SHA-256 of a key, base64url, unpadded. A blank key hashes to
  nil, so a row that holds no key matches nothing."
  [key]
  (when-some [key (some-> key str not-empty)]
    (b64url (.digest (MessageDigest/getInstance "SHA-256")
                     (.getBytes ^String key StandardCharsets/UTF_8)))))

(defn equal?
  "Are `a` and `b` the same string? `MessageDigest/isEqual` over UTF-8
  bytes, constant time in their length, so a caller cannot walk a key
  one character at a time off the clock. A blank or nil side is never
  equal: nil must never match nil."
  [a b]
  (boolean
   (when-some [a (some-> a str not-empty)]
     (when-some [b (some-> b str not-empty)]
       (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                              (.getBytes ^String b StandardCharsets/UTF_8))))))

(defn matches?
  "Does the `presented` key hash to the `held` hash? `hash-b64`, the
  spelling the key rows hold, compared by `equal?`."
  [held presented]
  (equal? held (hash-b64 presented)))
