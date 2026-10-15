(ns waymark10.server.at-rest
  "At-rest encryption of the credentials that open something OUTSIDE
  the engine (ticket dbeb2f25): a field declared {:secret true :at-rest
  :encrypted} is sealed by the Postgres store on every write and opened
  on every read, so handlers, guards and secrets/resolve-refs! see
  plaintext and a database read, a dump or a backup sees only
  `enc:v1:<key-id>:<nonce>:<ciphertext>`.

  AES-256-GCM, a fresh 96-bit nonce per value, the key read from
  WAYMARK10_DATA_KEY (32 bytes, base64) and nowhere else: never a row,
  never a log line. The key id is the first four bytes of the key's
  SHA-256, so a value names the key that sealed it and a rotation has
  room to read both.

  NO KEY IS A STATE THIS ENGINE RUNS IN, said aloud: values are written
  as plaintext, `enc:` values already written refuse to open (loudly —
  never as an empty value), boot warns once and the cracks document
  carries `at_rest`. A key that is set and malformed refuses at the
  first use rather than reading as no key."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto AEADBadTagException Cipher Mac)
           (javax.crypto.spec GCMParameterSpec SecretKeySpec)))

(set! *warn-on-reflection* true)

(def env-var "WAYMARK10_DATA_KEY")

(def ^:private prefix "enc:")

(defn- hex [^bytes bs n]
  (apply str (map #(format "%02x" (bit-and (long %) 0xff)) (take n bs))))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn parse-key
  "A data key from its base64 spelling → {:id :bytes}. Anything but 32
  bytes throws: a key that is set and wrong must never read as none."
  [s]
  (let [bs (try (.decode (Base64/getDecoder) (str/trim (str s)))
                (catch IllegalArgumentException _ nil))]
    (when-not (and bs (= 32 (alength ^bytes bs)))
      (throw (ex-info (str env-var " must be 32 bytes, spelled in base64")
                      {:at-rest :malformed-key})))
    {:id (hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes bs) 4)
     :bytes bs}))

(def ^:private env-key
  (delay (some-> (System/getenv env-var) str/trim not-empty parse-key)))

(defonce ^:private override (atom ::env))

(defn use-key!
  "For a test: this key ({:id :bytes}, or nil for none) in place of the
  environment's until (use-key! ::env) gives it back. → the one it
  put aside, so a test can put that back."
  [k]
  (first (reset-vals! override k)))

(defn data-key
  "The key values are sealed with now, or nil while none is set."
  []
  (let [o @override]
    (if (= ::env o) @env-key o)))

(defn sealed? [v]
  (and (string? v) (str/starts-with? v prefix)))

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- cipher ^Cipher [mode ^bytes k ^bytes nonce]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode) (SecretKeySpec. k "AES") (GCMParameterSpec. 128 nonce))))

(defn seal
  "`v` sealed under the data key; v itself when it is no string, is
  sealed already, or no key is set."
  [v]
  (if-some [{:keys [id bytes]} (when (and (string? v) (not (sealed? v)))
                                 (data-key))]
    (let [nonce (byte-array 12)
          _ (.nextBytes rng nonce)
          ct (.doFinal (cipher Cipher/ENCRYPT_MODE bytes nonce) (utf8 v))
          enc (Base64/getEncoder)]
      (str prefix "v1:" id ":" (.encodeToString enc nonce) ":"
           (.encodeToString enc ct)))
    v))

(defn- refuse [msg]
  ;; the value never rides the exception: only what is wrong
  (throw (ex-info (str "at-rest: " msg) {:at-rest :unopenable})))

(defn open
  "A sealed value opened; anything else as it is. A sealed value with
  no key, under another key, or whose tag does not verify THROWS — it
  never reads as nil."
  [v]
  (if-not (sealed? v)
    v
    (let [[_ ver id n ct :as parts] (str/split v #":")
          {kid :id k :bytes} (data-key)
          dec (Base64/getDecoder)]
      (cond
        (or (not= 5 (count parts)) (not= "v1" ver))
        (refuse "a value in a form this engine did not write")
        (nil? k)
        (refuse (str "a value sealed under key " id " and no " env-var " set"))
        (not= id kid)
        (refuse (str "a value sealed under key " id "; the key here is " kid))
        :else
        (try
          (String. (.doFinal (cipher Cipher/DECRYPT_MODE k (.decode dec ^String n))
                             (.decode dec ^String ct))
                   StandardCharsets/UTF_8)
          (catch AEADBadTagException _
            (refuse (str "a value under key " id " does not verify"))))))))

(defn- each [f fields data]
  (if (and (seq fields) (map? data))
    (reduce (fn [d k] (if (string? (get d k)) (update d k f) d))
            data fields)
    data))

(defn seal-fields [fields data] (each seal fields data))

(defn open-fields [fields data] (each open fields data))

(defn keyed-digest
  "An input digest that tells nothing of the values it was taken over:
  HMAC-SHA256 under a subkey of the data key. With no key it is `d` —
  and then the values themselves are plaintext at rest."
  [d]
  (if-some [{:keys [bytes]} (data-key)]
    (let [mac (fn ^bytes [^bytes k ^bytes msg]
                (.doFinal (doto (Mac/getInstance "HmacSHA256")
                            (.init (SecretKeySpec. k "HmacSHA256")))
                          msg))
          sub (mac bytes (utf8 "waymark10 input digest"))]
      (str "hmac:" (hex (mac sub (utf8 (str d))) 32)))
    d))

(defn note
  "What the cracks document says of at-rest encryption."
  []
  (try
    (if-some [k (data-key)]
      {:encrypted true :key_id (:id k)}
      {:encrypted false
       :why (str "No " env-var " is set: the fields declared :at-rest "
                 ":encrypted are written as plaintext.")})
    (catch clojure.lang.ExceptionInfo e
      {:encrypted false :why (ex-message e)})))

(defonce ^:private warned (atom false))

(defn warn-once!
  "Boot's sentence when values will be written as plaintext."
  []
  (let [{:keys [encrypted why]} (note)]
    (when (and (not encrypted) (compare-and-set! warned false true))
      (binding [*out* *err*]
        (println (str "waymark10 at-rest: WARNING " why))))))
