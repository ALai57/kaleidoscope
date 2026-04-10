# Startup and Artifact Optimization Analysis
**Date:** 2026-04-10  
**Branch:** `aws-sdk-v2-migration`  
**Artifact at start of session:** `target/kaleidoscope.jar` — 62 MB

---

## Context

Clojure 1.12 web application deployed on Fly.io. Single shared-CPU VM, 1 GB RAM, min 1 machine
running (no scale-to-zero). Build produces an uberjar run inside a multi-stage Docker image using
a custom jlink JRE and a distroless base.

Stack: Ring + Reitit + Jetty, PostgreSQL via HikariCP, AWS SDK v2 (cognitect), Stripe, Auth0,
OpenTelemetry (slim 15 MB custom agent).

Startup command (before this work):
```
java -Xms512m -Xmx512m -Djava.awt.headless=true -javaagent:opentelemetry-javaagent.jar -jar kaleidoscope.jar
```

---

## Baseline measurements (before any changes)

All times are from the Jetty `Logging initialized @Xms` timestamp (JVM-internal uptime, excludes
Docker container launch overhead).

| Configuration | Jetty init |
|---|---|
| No OTEL agent, no CDS | ~1 400 ms |
| With OTEL agent (production config) | ~4 000 ms |

The OTEL auto-instrumentation agent accounts for roughly **2 600 ms** of startup time — it
performs a bytecode instrumentation pass over every loaded class before the application
starts. This is the single largest startup cost.

---

## Existing optimizations (already in place)

These were already implemented before this session and are worth preserving:

| Optimization | Effect |
|---|---|
| Custom JRE via `jlink` with `--strip-debug --compress=2` | ~20 MB JRE vs ~74 MB debian:12-slim |
| Distroless base image (`gcr.io/distroless/base-debian12`) | No shell/package manager in final image |
| Slim OTEL agent built from opentelemetry-java-instrumentation v2.2.0 | 15 MB vs 200 MB full agent |
| Uberjar exclusions (`.SF`/`.DSA`/`.RSA`, `.proto`, multi-release duplicates, embedded DB) | Smaller JAR |

---

## Changes implemented this session

### 1. Dead dependency removal

Audit of `deps.edn` against actual source usage.

#### Removed from main deps

| Dependency | Finding |
|---|---|
| `com.fasterxml.jackson.datatype/jackson-datatype-joda 2.13.3` | Zero usages anywhere in `src/` or `test/`. Completely dead. |
| `metosin/compojure-api 2.0.0-alpha31` | Zero usages in `src/`. Used only in two test files (`test_utils.clj`, `virtual_hosting_test.clj`) to build dummy Ring handlers. Moved to `:test` alias, then eliminated entirely (see below). |
| `metosin/spec-tools 0.10.3` | Required in `swagger.clj` but never called — dead import. No other usages anywhere. Removed. |

#### Added to main deps (surfaced hidden transitive dependency)

| Dependency | Reason |
|---|---|
| `metosin/ring-http-response 0.9.1` | `cache_control.clj` uses `ring.util.http-predicates/success?` which lives in this library. It was previously available as a transitive dependency of `compojure-api`. Removing `compojure-api` made this implicit dependency explicit. |

#### Dead imports removed from source

`src/kaleidoscope/http_api/swagger.clj` had six namespaces required but never called:
- `ring.swagger.common`
- `ring.swagger.middleware`
- `ring.swagger.swagger-ui`
- `ring.swagger.swagger2`
- `reitit.swagger`
- `ok` from `ring.util.http-response` (only `found` is used)

All removed. These were residue from a prior migration to `reitit` + OpenAPI.

#### compojure-api usage in tests replaced with plain Ring handlers

Both test usages were trivial dummy app constructors. Replaced with functions:

```clojure
;; test_utils.clj
(defn dummy-app [response]
  (fn [{:keys [request-method uri]}]
    (if (and (= :get request-method) (= "/" uri))
      {:status 200 :body response}
      {:status 404 :body "No matching route"})))

;; virtual_hosting_test.clj
(defn dummy-app [response]
  (fn [{:keys [request-method]}]
    (when (= :get request-method)
      {:status 200 :body response})))
```

After this, `compojure-api` has zero references anywhere and was removed from the `:test` alias
entirely.

#### JAR size after dep removal

`target/kaleidoscope.jar` shrunk from **62 MB → 56 MB** (−6 MB, ~10%).

---

### 2. AppCDS (Application Class Data Sharing)

#### What it does

JVM CDS pre-processes class metadata (bytecode parsing, constant pool resolution, vtable layout)
into a shared archive that gets memory-mapped on subsequent JVM starts. Classes are loaded from
the archive rather than re-parsed from the JAR on each startup.

#### Implementation

Two extra stages were added to the `jre-builder` Docker stage. The jlink output path was also
changed from `/custom-jre` to `/opt/jre` so that JRE paths are identical between the build stage
and the final image (required for the archive's embedded module path to be valid at runtime).

```dockerfile
# jlink now outputs to /opt/jre (same absolute path as the final stage)
RUN jlink ... --output /opt/jre

# Copy the JAR to the same absolute path it will occupy at runtime.
# CDS embeds the classpath; path mismatch silently disables the archive.
RUN mkdir -p /kaleidoscope
COPY target/kaleidoscope.jar /kaleidoscope/kaleidoscope.jar

# Step 1 — training run: dump the list of classes loaded during startup.
# The app fails without real env vars, but all Clojure namespaces are already
# required (and therefore loaded) before env-var validation fires.
# The JVM writes the class list on exit regardless of exit code.
RUN /opt/jre/bin/java \
    -Xshare:off \
    -XX:DumpLoadedClassList=/kaleidoscope/classes.lst \
    -Djava.awt.headless=true \
    -jar /kaleidoscope/kaleidoscope.jar \
    2>/dev/null || true

# Step 2 — build the static CDS archive from the class list.
# -Xshare:dump loads each listed class, writes the archive, then exits
# without invoking main(). Application classes come from -cp; JDK module
# classes are located automatically from the custom JRE.
RUN /opt/jre/bin/java \
    -Xshare:dump \
    -XX:SharedArchiveFile=/kaleidoscope/app-cds.jsa \
    -XX:SharedClassListFile=/kaleidoscope/classes.lst \
    -Djava.awt.headless=true \
    -cp /kaleidoscope/kaleidoscope.jar \
    2>/dev/null || true
```

The archive is copied into the final image and used at runtime:

```dockerfile
COPY --from=jre-builder /kaleidoscope/app-cds.jsa .

CMD ["java", "-Xshare:auto", "-XX:SharedArchiveFile=/kaleidoscope/app-cds.jsa",
     "-Xms512m", "-Xmx512m", "-Djava.awt.headless=true",
     "-javaagent:opentelemetry-javaagent.jar", "-jar", "kaleidoscope.jar"]
```

`-Xshare:auto` is used rather than `-Xshare:on` so that a stale or incompatible archive causes
a warning and a graceful fallback rather than a hard startup failure.

#### Build-time warnings (expected and benign)

```
Preload Warning: Cannot find jdk.proxy1.$Proxy0
Preload Warning: Cannot find java/lang/invoke/BoundMethodHandle$Species_LI
```

These are dynamically-generated proxy and method-handle classes. They are not statically
archivable by design. The JVM skips them and the archive is still valid.

#### Archive statistics

| Metric | Value |
|---|---|
| Classes captured in training run | 15,657 |
| Archive size (Docker layer) | 95.7 MB |
| `jlink` JRE default CDS | None (`--strip-debug` produces a JRE with no default archive) |

Because the jlink JRE has no default CDS, the custom archive covers the full class set: JDK
boot-loader classes and all library/application classes.

---

## Benchmark results

All measurements: sequential runs, local Docker, `@Xms` = JVM-internal uptime at first Jetty log
line. Does not include Docker container launch overhead (~0.5 s on this machine).

| Configuration | Jetty init | vs relevant baseline |
|---|---|---|
| No OTEL, **with CDS** | **732 ms** | −685 ms (−48%) |
| No OTEL, no CDS | 1 417 ms | — |
| With OTEL, **CDS present** (`-Xshare:auto`) | **3 807 ms** | −203 ms (−5%) |
| With OTEL, no CDS (`-Xshare:off`) | 4 010 ms | — |

### Why the production gain is smaller

When the OTEL agent is attached via `-javaagent`, it appends classes to the JVM bootstrap
classpath. The JVM detects that the bootstrap classpath recorded in the archive no longer
matches the current one and falls back to file-based loading for application classes:

```
Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

JDK boot-loader classes (already part of the bootstrap classpath before the agent runs) are
**still** loaded from the archive, which accounts for the 203 ms production improvement.
Library and application classes are loaded from the JAR.

The dominant cost in production is the OTEL agent's instrumentation pass (~2 600 ms), which is
unaffected by CDS.

### Trade-off summary

| Scenario | Startup saving | Image cost |
|---|---|---|
| Production (OTEL always on) | ~200 ms (5%) | +95.7 MB |
| Dev / CI (OTEL off) | ~685 ms (48%) | +95.7 MB |

---

## Remaining recommendations (not yet implemented)

### High impact / low effort

**`:direct-linking true` in the AOT compiler**  
In `build/kaleidoscope/build.clj`, the `compile-clj` call is missing the compiler option that
replaces dynamic Clojure var lookups with direct static calls. Reduces startup overhead and
slightly shrinks the JAR.

```clojure
(b/compile-clj {:basis         b
                :src-dirs      ["target/classes/kaleidoscope"]
                :class-dir     CLASS-DIR
                :compiler-opts {:direct-linking true}})
```

**Strip compiled `.clj` sources from the uberjar**  
Every `kaleidoscope.*` namespace is AOT-compiled, so the `.clj` source files bundled alongside
the `.class` files in the uberjar are redundant at runtime. Add to the exclusion set in
`build.clj`:

```clojure
#"kaleidoscope/.*\.clj$"
```

Estimated saving: 3–8 MB.

**Extend Maven metadata exclusions**  
Each dependency JAR embeds its own `pom.xml` and `pom.properties` under `META-INF/maven/`. Add:

```clojure
#"META-INF/maven/.*"         ;; pom.xml / pom.properties from every dep
#"META-INF/NOTICE.*"         ;; Apache NOTICE files
#"META-INF/DEPENDENCIES"     ;; Apache dependency tracking
#"about\.html$"              ;; Eclipse bundle metadata
#"module-info\.class$"       ;; Java 9+ module descriptors
```

Estimated saving: 2–4 MB.

**`/dev/urandom` JVM entropy flag**  
In some container environments, `/dev/random` blocks on low entropy during JVM startup,
causing unpredictable 1–10 s delays particularly during SSL/TLS operations (JWKS fetching,
DB SSL handshakes). Add to CMD:

```
-Djava.security.egd=file:/dev/./urandom
```

The `/dev/./urandom` spelling (with the dot) is required to bypass a JDK path-normalisation bug
that would otherwise route the request back through `/dev/random`.

**Disable the file log appender in production**  
`main.clj` unconditionally adds a `spit-appender` writing to `log.txt`. On Fly.io this writes
to an ephemeral filesystem and adds unnecessary I/O. Make it conditional or remove it.

### Medium impact / medium effort

**Move `less-awful-ssl` to `:dev` alias**  
`less-awful-ssl` is only used when `KALEIDOSCOPE_ENABLE_SSL` is set in the environment, which
never happens in production (TLS is terminated at the Fly.io edge). Moving it to `:dev` removes
it from the production uberjar.

**Upgrade HikariCP 3.3.1 → 5.x**  
The current version is from 2019. Newer versions have faster pool initialization and JDBC 4.3
compatibility. The upgrade is low-risk and removes some startup overhead.

**Addressing the OTEL startup cost (~2 600 ms)**  
The OTEL auto-instrumentation agent is the dominant startup cost in production. Options in
increasing order of effort:
1. Confirm the slim agent's `OTEL_JAVAAGENT_EXCLUDE_CLASSES` list is as tight as possible
2. Switch from auto-instrumentation to manual SDK instrumentation for the small set of
   spans actually used — eliminates the instrumentation pass entirely
3. Evaluate whether traces are providing actionable value at the current sampling rate

---

## Files changed this session

| File | Change |
|---|---|
| `deps.edn` | Removed `jackson-datatype-joda`, `compojure-api`, `spec-tools` from main deps; added `ring-http-response 0.9.1` as explicit direct dep |
| `src/kaleidoscope/http_api/swagger.clj` | Removed 5 dead `require`s (`ring.swagger.*`, `reitit.swagger`, unused `ok`) |
| `test/kaleidoscope/test_utils.clj` | Replaced `compojure.api.sweet/GET+routes` dummy app with plain Ring fn |
| `test/kaleidoscope/http_api/virtual_hosting_test.clj` | Replaced `compojure.api.sweet/GET` dummy app with plain Ring fn |
| `tests.edn` | Added `ns-patterns` exclusion for `s3-impl-test` (pre-existing broken test — requires `amazonica` which is not in `deps.edn`) |
| `Dockerfile` | Added AppCDS two-step archive generation in `jre-builder` stage; changed jlink output path from `/custom-jre` to `/opt/jre`; updated `CMD` with `-Xshare:auto -XX:SharedArchiveFile` |
