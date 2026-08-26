(ns waymark10.aot
  "Ahead-of-time compilation of the THIRD-PARTY libraries, and only
  those.

  WHY NOT THE FRAMEWORK TOO. Clojure compiles from source at every JVM
  start, and waymark10 is ~70k lines, so each CI shard pays that
  compile again. The obvious fix is to AOT the framework and cache the
  result — but the cache key would have to include the source, and CI
  runs precisely because the source changed. It would miss on the
  pushes it was built for. Worse, the shards compile in PARALLEL today,
  so moving that work into one earlier job does not shorten the run at
  all: it serialises what was already overlapped.

  The libraries are the part that does pay. They change only when
  deps.edn changes, which is rarely, so a cache keyed on deps.edn hits
  on nearly every push — and it is the same key the Maven cache
  already uses, so the two rebuild together and cannot disagree.

  AND IT KEEPS CI HONEST. Dockerfile.workqueue10 runs the app FROM
  SOURCE (`CMD [\"clojure\" \"-M:dev\"]`). AOT changes when top-level
  forms evaluate, and this codebase has real top-level side effects —
  defresource runs the whole check battery at load. Compiling
  waymark10 in CI would mean testing a loading order production never
  performs. Compiling malli does not: it is the same library code,
  the same versions, resolved from the same deps.edn.

  Note what this does NOT buy. The ~107s of usability warnings is the
  check battery EXECUTING at load, and AOT still emits a class whose
  initialiser runs it. Only compilation is saved, never evaluation."
  (:require [clojure.java.io :as io]))

(def libs
  "Every third-party Clojure namespace the source tree requires,
  from a grep over waymark10, workqueue10 and calendar10, plus the
  test runner. Java libraries (HikariCP, the postgres driver) are
  already bytecode and have nothing to compile.

  Naming the leaves is enough: compile pulls in whatever they require."
  '[jsonista.core
    malli.core malli.error malli.generator malli.json-schema
    malli.registry malli.transform malli.util
    next.jdbc next.jdbc.result-set
    reitit.core reitit.ring
    org.httpkit.client org.httpkit.server
    buddy.core.keys buddy.sign.jwt
    kaocha.runner])

(defn compile-libs
  "Compile `libs` into *compile-path*. Invoked as
     clojure -X:test:aot waymark10.aot/compile-libs
   -X ignores :main-opts, so this cannot collide with the :test
   alias's kaocha entry point."
  [_]
  (let [dir (io/file *compile-path*)]
    (.mkdirs dir)
    (println "aot: compiling" (count libs) "library namespaces into" (str dir))
    (let [t0 (System/currentTimeMillis)]
      (try
        (doseq [lib libs]
          (compile lib))
        (println (format "aot: done in %.1fs"
                         (/ (- (System/currentTimeMillis) t0) 1000.0)))
        ;; in a finally: a compile that throws would otherwise leave the
        ;; agent pool holding the JVM open for its full 60s keepalive
        ;; before the caller ever saw the failure.
        (finally (shutdown-agents))))))
