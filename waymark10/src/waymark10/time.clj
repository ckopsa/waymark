(ns waymark10.time
  "The one reading of an instant from what a row, a log or a wire hands
  over. Every module asks here instead of keeping its own copy."
  (:import (java.time Instant LocalDate OffsetDateTime ZoneOffset)))

(set! *warn-on-reflection* true)

(defn instant-of
  "`v` as an Instant: an Instant as itself, an OffsetDateTime or any
  inst (a Date) at its moment, and anything else read from its string as
  an RFC 3339 instant. nil, the empty string and what does not parse are
  nil. The options keep the few readings that differ:
    :offset?    parse the string as an OffsetDateTime
    :date-only? read a bare YYYY-MM-DD as its first moment in UTC
    :strict?    throw what the parse threw instead of answering nil"
  (^Instant [v] (instant-of v nil))
  (^Instant [v {:keys [offset? date-only? strict?]}]
   (cond
     (nil? v) nil
     (instance? Instant v) v
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
