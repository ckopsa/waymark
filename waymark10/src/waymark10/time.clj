(ns waymark10.time
  "The one reading of an instant from what a row, a log or a wire hands
  over. Every module asks here instead of keeping its own copy."
  (:import (java.time Instant LocalDate OffsetDateTime ZoneOffset)))

(set! *warn-on-reflection* true)

(def ^:private rfc3339
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})")

(defn instant-of
  "`v` as an Instant: an Instant as itself, an OffsetDateTime or any
  inst (a Date) at its moment, and anything else read from its string as
  an RFC 3339 instant. nil, the empty string and what does not parse are
  nil. The options keep the few readings that differ:
    :offset?    parse the string as an OffsetDateTime
    :date-only? read a bare YYYY-MM-DD as its first moment in UTC
    :strict?    throw what the parse threw instead of answering nil
    :rfc3339?   the expression language's rule: only an Instant, or a
                string matching the RFC 3339 shape read as an
                OffsetDateTime; anything else is nil"
  (^Instant [v] (instant-of v nil))
  (^Instant [v {:keys [offset? date-only? strict? rfc3339?]}]
   (cond
     (nil? v) nil
     (instance? Instant v) v
     rfc3339? (when (and (string? v) (re-matches rfc3339 v))
                (instant-of v {:offset? true}))
     (instance? OffsetDateTime v) (.toInstant ^OffsetDateTime v)
     (inst? v) (Instant/ofEpochMilli (inst-ms v))
     :else
     (let [s (str v)]
       (when-not (= "" s)
         (try (if offset?
                (.toInstant (OffsetDateTime/parse s))
                (Instant/parse s))
              (catch Exception e
                (when strict? (throw e))
                (when date-only?
                  (try (.toInstant (.atStartOfDay (LocalDate/parse s)
                                                  ZoneOffset/UTC))
                       (catch Exception _ nil))))))))))
