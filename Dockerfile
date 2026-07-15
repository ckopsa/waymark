# Serves the mealplan10 app: a waymark10 engine on http-kit.
# Build:  make image10   (buildx arm64 → docker.kopsa.info/mealplan10:<tag>)
FROM clojure:temurin-21-tools-deps-bookworm-slim

WORKDIR /app

# Dependency layer: both deps.edn files first, so a source-only change
# never re-downloads the world. -P prepares the full :dev classpath.
COPY waymark10/deps.edn waymark10/deps.edn
COPY mealplan10/deps.edn mealplan10/deps.edn
RUN cd mealplan10 && clojure -P -M:dev

# Sources: the framework and the app, run from source (a home-cluster
# app; JVM boot beats maintaining a build pipeline).
COPY waymark10/ waymark10/
COPY mealplan10/ mealplan10/

WORKDIR /app/mealplan10
EXPOSE 8010

# MEALPLAN10_DSN / MEALPLAN10_PORT / MEALPLAN_GCAL_ICS_URL arrive from
# the Nomad job. Production posture: WAYMARK10_AUTO_MIGRATE stays
# unset — boot REFUSES on schema drift and names the plan; the deploy
# gate runs `clojure -M:migrate` (APPLY=1) before rolling.
CMD ["clojure", "-M:dev"]
