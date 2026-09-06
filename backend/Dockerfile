# =============================================================================
# SeatLock API — production image.
#
# Multi-stage, for the same reason the frontend's Dockerfile is:
#
#   Stage 1 needs Maven, a full JDK, and ~200MB of downloaded dependencies to
#   turn source into a jar. Around 800MB in total.
#   Stage 2 needs a JRE and one jar. Around 190MB.
#
# Only stage 2 ships. Nothing that BUILT the application is present in the image
# that RUNS it — no compiler, no build tool, no source, no dependency cache, no
# ~/.m2 full of jars an attacker could inspect for known-vulnerable versions.
# That is a size win and a security one, and it costs nothing but a second FROM.
# =============================================================================


# ---- Stage 1: build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# ---------------------------------------------------------------------------
# Copy the POM ALONE first, resolve dependencies, and only then copy src/.
#
# This is the single most valuable line in the file, and the reason is how
# Docker's layer cache works: each instruction produces a layer keyed by the
# files it was built from, and a layer is reused only if every layer before it
# was reused too. So if `COPY . .` came first, editing one line of Java would
# invalidate the copy layer, which would invalidate the dependency layer, which
# would re-download the entire Spring Boot dependency tree — two to three
# minutes, on every single build, to fetch bytes that did not change.
#
# Splitting it means the download layer is keyed on pom.xml alone. Change any
# amount of code and it is served from cache; only a genuine dependency change
# pays for a re-resolve. On CI, where the cache is warm, this is the difference
# between a 30-second image build and a 3-minute one.
# ---------------------------------------------------------------------------
COPY pom.xml .

# `-B` (batch mode) turns off the ANSI progress spinner, which otherwise fills
# CI logs with thousands of carriage returns and makes them unreadable.
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests are NOT run here, and that is deliberate rather than lazy. The
# integration tests use Testcontainers, which needs a Docker daemon — and there
# is no Docker daemon inside a Docker build. Running them here would mean either
# mounting the host's socket into the build (a well-known privilege-escalation
# path) or quietly skipping the tests that matter most.
#
# So the split is: CI runs `mvn -B verify` with real containers and fails the
# build if anything is red; this image is only ever built from a commit that
# already passed. See .github/workflows/ci.yml.
RUN mvn -B -q clean package -DskipTests


# ---- Stage 2: run -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# ---------------------------------------------------------------------------
# A dedicated unprivileged user.
#
# Containers run as root unless told otherwise, and root inside a container is
# very nearly root outside it: a container escape, a writable bind mount, or a
# kernel bug all become far more serious when the process on the other side has
# uid 0. Nothing this application does needs privilege — it binds port 8080,
# which is above 1024, and it writes nothing outside /tmp.
#
# `-S` creates a system user with no password and no login shell, so the account
# cannot be used to log in even if something else goes wrong.
# ---------------------------------------------------------------------------
RUN addgroup -S seatlock && adduser -S -G seatlock -h /app seatlock

WORKDIR /app

# Copy as the unprivileged user so the jar is not root-owned and therefore not
# writable by the process that runs it. An application that cannot overwrite its
# own binary is one fewer step available to anyone who finds a file-write bug.
COPY --from=build --chown=seatlock:seatlock /build/target/*.jar app.jar

USER seatlock

# ---------------------------------------------------------------------------
# Heap sizing: a PERCENTAGE, never a fixed -Xmx.
#
# The JVM decides its default heap from what it thinks the machine has. Modern
# JVMs are container-aware and read the cgroup limit rather than the host's
# total RAM, so `-XX:MaxRAMPercentage=75` means "75% of whatever this container
# was actually given" — and the same image is then correct on a 512MB task and
# on a 4GB one.
#
# A hard `-Xmx512m` is wrong in both directions. Give the container 2GB and the
# JVM ignores 1.5GB of it while the rest of the memory sits idle. Give it 256MB
# and the JVM cheerfully tries to grow to 512MB, at which point the kernel's OOM
# killer takes the process — no exception, no stack trace, just exit code 137
# and a container that "randomly restarts". Sizing from the limit means the two
# numbers cannot drift apart.
#
# The remaining 25% is not waste: it is thread stacks, metaspace, code cache,
# direct byte buffers (Netty/Lettuce use them) and the JVM's own overhead, none
# of which is counted in the heap but all of which counts towards the cgroup.
#
# ExitOnOutOfMemoryError: a JVM that has run out of heap is not going to recover;
# it will thrash the GC and answer nothing. Dying lets the orchestrator replace
# it, which is a real recovery. Staying up and being useless is not.
#
# There is no -XX:+UseContainerSupport here because it has been the default
# since JDK 10; setting it would only suggest we were not sure.
# ---------------------------------------------------------------------------
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+ExitOnOutOfMemoryError"

ENV SERVER_PORT=8080

# Documentation only — this publishes nothing. `docker run -p` does that.
EXPOSE 8080

# ---------------------------------------------------------------------------
# Readiness, not liveness, and specifically not `/actuator/health`.
#
#   /actuator/health/liveness   "the process is alive"      — restart me if not
#   /actuator/health/readiness  "I can serve traffic"       — route to me if so
#
# The distinction matters during startup and during a database outage. Readiness
# includes the datasource and Redis checks, so a container whose Postgres is
# unreachable is marked unhealthy and taken out of the load balancer instead of
# accepting requests it cannot serve. Liveness deliberately does not, because
# restarting the application would not fix somebody else's database and a
# restart loop during an outage makes recovery slower.
#
# start-period is generous because a Spring Boot context plus Flyway migrations
# takes a while, and health checks that fail during startup should not count
# against the retry budget.
# ---------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1

# ---------------------------------------------------------------------------
# `sh -c` so that $JAVA_OPTS is expanded by a shell — the exec form of ENTRYPOINT
# does no variable substitution, so ["java", "$JAVA_OPTS", ...] would pass the
# literal string "$JAVA_OPTS" to the JVM.
#
# `exec` matters just as much: without it the shell stays as PID 1 and the JVM
# becomes its child, so `docker stop` sends SIGTERM to the shell, which does not
# forward it. The JVM never hears it, never runs its shutdown hooks, and is
# SIGKILLed ten seconds later — mid-request, mid-transaction. With `exec`, the
# JVM *replaces* the shell as PID 1 and receives the signal directly, so Spring
# closes the connection pool and drains in-flight requests properly.
# ---------------------------------------------------------------------------
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
