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

  NO KEY IS A STATE THIS ENGINE RUNS IN, said aloud (ticket d682ee14):
  it boots and serves, but a string to seal is refused — never written
  as plaintext — and `enc:` values already written refuse to open
  (loudly, never as an empty value). Plaintext written before then
  still reads, and the boot backfill seals it once a key is set. Boot
  logs an ERROR and the cracks document carries `at_rest {crack true}`.
  keyed-digest alone still answers its input with no key: it is a
  lookup, not a stored value.

  A MALFORMED KEY IS A THIRD STATE, and never a stop (ticket d8771d50):
  a key that is set and is not 32 bytes of base64 boots and serves, but
  nothing is sealed and nothing opens — `enc:` values refuse to open, a
  plaintext write to a declared field refuses, the boot backfill does
  not run — boot logs an ERROR naming the problem (never the value) and
  the cracks document carries `at_rest {state \"malformed\"}`. It never
  reads as no key."
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

(def ^:private malformed-why
  (str env-var " must be 32 bytes, spelled in base64"))

(defn read-key
  "The environment's spelling of a key → {:id :bytes}, nil when it is
  unset or blank, or ::malformed when it is set and wrong — a state the
  engine runs in, not an exception that stops it."
  [s]
  (when-some [s (some-> s str str/trim not-empty)]
    (try (parse-key s)
         (catch clojure.lang.ExceptionInfo _ ::malformed))))

(def ^:private env-key
  (delay (read-key (System/getenv env-var))))

(defonce ^:private override (atom ::env))

(defn use-key!
  "For a test: this key ({:id :bytes}, nil for none, or ::malformed) in
  place of the environment's until (use-key! ::env) gives it back. → the
  one it put aside, so a test can put that back."
  [k]
  (first (reset-vals! override k)))

(defn- current []
  (let [o @override]
    (if (= ::env o) @env-key o)))

(defn malformed?
  "Whether the key that is set is not one: see the ns doc."
  []
  (= ::malformed (current)))

(defn data-key
  "The key values are sealed with now, or nil while none is set or the
  one set is malformed (malformed? tells the two apart)."
  []
  (let [k (current)]
    (when (map? k) k)))

(defn sealed? [v]
  (and (string? v) (str/starts-with? v prefix)))

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- cipher ^Cipher [mode ^bytes k ^bytes nonce]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode) (SecretKeySpec. k "AES") (GCMParameterSpec. 128 nonce))))

(defn- refuse [msg]
  ;; the value never rides the exception: only what is wrong
  (throw (ex-info (str "at-rest: " msg) {:at-rest :unopenable})))

(defn seal
  "`v` sealed under the data key; v itself when it is no string or is
  sealed already. A string to seal while no key is set, or while the
  key is malformed, THROWS: it is never written as plaintext."
  [v]
  (if-not (and (string? v) (not (sealed? v)))
    v
    (if-some [{:keys [id bytes]} (data-key)]
      (let [nonce (byte-array 12)
            _ (.nextBytes rng nonce)
            ct (.doFinal (cipher Cipher/ENCRYPT_MODE bytes nonce) (utf8 v))
            enc (Base64/getEncoder)]
        (str prefix "v1:" id ":" (.encodeToString enc nonce) ":"
             (.encodeToString enc ct)))
      (let [bad (malformed?)]
        (throw (ex-info (str "at-rest: " (if bad malformed-why (str "no " env-var " is set"))
                             "; a field declared :at-rest :encrypted is not written")
                        {:at-rest (if bad :malformed-key :missing-key)}))))))

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
        (malformed?)
        (refuse (str "a value sealed under key " id " and " malformed-why))
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
  (cond
    (malformed?)
    {:state "malformed" :encrypted false :crack true
     :why (str malformed-why ": nothing is sealed or opened, and the "
               "fields declared :at-rest :encrypted refuse writes.")}
    (data-key)
    {:state "encrypted" :encrypted true :key_id (:id (data-key))}
    :else
    {:state "none" :encrypted false :crack true
     :why (str "No " env-var " is set: the key is missing, so the fields "
               "declared :at-rest :encrypted refuse writes and values "
               "sealed earlier do not open.")}))

(defn boot-line
  "Boot's sentence for the key, or nil when values are sealed. It names
  the problem and never the value."
  []
  (let [{:keys [state why]} (note)]
    (case state
      "malformed" (str "waymark10 at-rest: ERROR " why)
      "none" (str "waymark10 at-rest: ERROR " why)
      nil)))

(defonce ^:private warned (atom false))

(defn warn-once!
  "Boot's sentence when values are not sealed: no key, or a malformed one."
  []
  (when-some [line (boot-line)]
    (when (compare-and-set! warned false true)
      (binding [*out* *err*]
        (println line)))))
