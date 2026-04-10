# OTEL Agentless Migration Analysis
**Date:** 2026-04-10  
**Branch:** `aws-sdk-v2-migration`  
**Follows:** `analysis/2026-04-10-startup-and-artifact-optimization-analysis.md`

---

## Context

This session continues from the prior optimization work. At the end of that session:

- JAR: 56 MB (down from 62 MB after dead dep removal)
- Production startup: ~3,807 ms (agent + CDS) / ~4,010 ms (agent, no CDS)
- CDS benefit in production was limited to ~200 ms because the OTEL agent appends to the JVM bootstrap classpath, causing the JVM to fall back to file-based loading for application classes
- The agent's bytecode instrumentation pass accounted for ~2,600 ms of startup time

The question addressed here: **can we preserve full telemetry coverage while eliminating the agent?**

---

## Existing telemetry inventory (before this change)

### What the agent provided automatically
- JDBC — individual SQL query spans (statement, table, operation)
- HikariCP — connection pool metrics (usage, idle, wait time, create/acquire time)
- Jetty — inbound HTTP server spans (method, route, status code)
- AWS SDK 1.11 — outbound SDK call spans
- Apache HttpClient / OkHttp — outbound HTTP call spans

### What manual instrumentation already covered
The codebase had 31 `span/with-span!` calls across 12 files, covering:

| Area | Namespaces | Coverage |
|------|-----------|----------|
| HTTP middleware | `middleware.clj` | All inbound requests, request-ID attachment, host/URI forcing |
| Virtual hosting | `virtual_hosting.clj` | Per-host routing decisions |
| Database (all ops) | `rdbms.clj` | find, get, insert, update, delete |
| S3 operations | `s3_impl.clj` | ls, get, put, stream conversion |
| Photo processing | `albums.clj` | Upload, resize, version creation |
| Auth | `buddy_backends.clj`, `auth0.clj` | JWT verification, token backend |
| API endpoints | `articles.clj`, `payments.clj`, `static_resources.clj` | Key business operations |
| Initialization | `main.clj` | OTEL and Stripe startup spans |

**Overlap assessment:** The manual spans already cover every meaningful business-logic path. What the agent added beyond this was library-level detail (individual SQL query text, pool wait times, SDK call parameters).

---

## Approach chosen: per-library instrumentation JARs (Option B)

Instead of the agent (which performs a full bytecode instrumentation pass at startup), use the standalone library instrumentation JARs that work as regular compile-time dependencies. These are initialized programmatically and instrument specific components without the agent overhead.

Components restored via library JARs:
- **HikariCP pool metrics** via `opentelemetry-hikaricp-3.0 2.2.0-alpha`
- **JDBC query spans** via `opentelemetry-jdbc 2.2.0-alpha`

Not restored (no standalone JAR approach exists for these, and manual spans already cover the same paths):
- Jetty server spans → replaced by existing Ring middleware spans
- AWS SDK / HTTP client spans → covered by existing manual spans at the call sites

---

## Implementation

### `deps.edn`

Three new main deps added:

```clojure
com.github.steffan-westcott/clj-otel-sdk             {:mvn/version "0.2.6"}
com.github.steffan-westcott/clj-otel-exporter-otlp   {:mvn/version "0.2.6"}
io.opentelemetry.instrumentation/opentelemetry-jdbc                {:mvn/version "2.2.0-alpha"}
io.opentelemetry.instrumentation/opentelemetry-hikaricp-3.0        {:mvn/version "2.2.0-alpha"}
```

The `:otel` dev alias had its `-javaagent` JVM opt removed; OTLP config now flows through `OTEL_*` env vars read by the SDK at startup.

### `src/kaleidoscope/main.clj`

`init-otel!` now initializes the SDK programmatically. It reads `OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_ENDPOINT` from the environment (the same vars the agent previously read) and registers the SDK as the global OpenTelemetry instance:

```clojure
(defn init-otel! []
  (let [service-name (or (System/getenv "OTEL_SERVICE_NAME") "kaleidoscope")
        endpoint     (System/getenv "OTEL_EXPORTER_OTLP_ENDPOINT")]
    (sdk/init-otel-sdk!
     service-name
     {:set-as-global true
      :tracer-provider
      {:span-processors
       (when endpoint
         [{:exporters [(otlp-trace/span-exporter
                        {:endpoint (str endpoint "/v1/traces")})]}])}}))
  ...)
```

`init-otel!` and `initialize-logging!` were moved before `env/start-system!` in `start-application!` so the SDK is registered globally before the datasource is created (the datasource wrapper calls `GlobalOpenTelemetry/get` at construction time).

### `src/kaleidoscope/init/env.clj`

The `postgres` datasource launcher now wraps the raw `HikariDataSource` with both instrumentation layers after the eager pool initialization:

```clojure
"postgres" (fn [env]
  (let [ds   (connection/->pool HikariDataSource (env->pg-conn env))
        _    (initialize-connection-pool! ds)
        otel (GlobalOpenTelemetry/get)]
    (->> ds
         (.dataSource (HikariTelemetry/create otel))
         (.wrap (JdbcTelemetry/create otel)))))
```

`initialize-connection-pool!` runs on the raw `HikariDataSource` (which needs the concrete type). `HikariTelemetry/create` registers a metrics tracker on the pool and returns the same datasource. `JdbcTelemetry/wrap` wraps connections to intercept query execution and emit spans.

The embedded H2 and embedded-postgres launchers used in tests are not wrapped, preserving test isolation. `GlobalOpenTelemetry/get` returns a no-op instance in tests (the SDK is not initialized), so any accidental wrapping would be safe.

### `Dockerfile`

- `COPY opentelemetry-javaagent.jar .` removed
- `ARG exclude_list` and `ENV OTEL_JAVAAGENT_EXCLUDE_CLASSES` removed (agent-specific)
- `-javaagent:opentelemetry-javaagent.jar` removed from `CMD`
- `OTEL_*` environment variables kept — the SDK reads them identically

```dockerfile
CMD ["java", "-Xshare:auto", "-XX:SharedArchiveFile=/kaleidoscope/app-cds.jsa",
     "-Xms512m", "-Xmx512m", "-Djava.awt.headless=true", "-jar", "kaleidoscope.jar"]
```

The CDS archive generation steps in `jre-builder` are unchanged and now cover the full class set without any bootstrap classpath conflict.

---

## Benchmark results

All measurements: native ARM64, JAR run directly (not inside Docker). `@Xms` = JVM-internal uptime at first Jetty log line, consistent with the methodology used in the prior session.

**Note on Docker numbers:** The Docker image is built for `linux/amd64` (Fly.io target). Running it locally on ARM64 goes through QEMU emulation and reads ~7,400 ms — this is emulation overhead, not application startup. The direct-JAR measurements below are ARM64 native and are the valid comparison baseline.

| Configuration | Jetty init | vs prior |
|---|---|---|
| **New: no agent, with CDS** | **~860 ms** | — |
| **New: no agent, no CDS** | **~1,560 ms** | — |
| Prior: agent + CDS (production) | ~3,807 ms | −2,945 ms (−77%) |
| Prior: agent, no CDS | ~4,010 ms | −2,450 ms (−61%) |
| Prior: no agent, with CDS | ~732 ms | +128 ms (+17%) |
| Prior: no agent, no CDS | ~1,417 ms | +143 ms (+10%) |

### Breakdown of the gains

**Against old production config (agent + CDS, `~3,807 ms`):**
Removing the agent recovers the ~2,600 ms instrumentation pass. CDS now delivers its full benefit without the bootstrap classpath conflict, saving ~700 ms. Total: **~2,945 ms faster, −77%**.

**Cost of the new SDK and instrumentation deps (+~130 ms with CDS):**
The four new JARs (`clj-otel-sdk`, `clj-otel-exporter-otlp`, `opentelemetry-jdbc`, `opentelemetry-hikaricp-3.0`) add ~130 ms of class-loading time compared to running with zero telemetry (the "no agent" baseline). This is the cost of per-library instrumentation. At 860 ms total, it is negligible.

**CDS benefit is now fully realized:**
Previously, CDS saved only ~200 ms in production because the agent's bootstrap classpath append disabled application-class sharing. Without the agent, CDS delivers the full ~700 ms saving (1,560 ms → 860 ms, −45%).

### JAR size

| State | JAR size |
|---|---|
| Start of prior session | 62 MB |
| After dead dep removal | 56 MB |
| After adding SDK + instrumentation deps | 58 MB |

Net change across both sessions: **−4 MB**.

---

## Telemetry coverage after migration

| Signal | Before (agent) | After (agentless) | Gap |
|--------|---------------|-------------------|-----|
| Inbound HTTP spans | Jetty auto-instrumentation | Ring middleware `span/with-span!` | None — same data |
| SQL query spans | JDBC auto-instrumentation | `JdbcTelemetry` wrapper on datasource | None |
| Connection pool metrics | HikariCP auto-instrumentation | `HikariTelemetry` wrapper on datasource | None |
| Business logic spans | 31 manual `span/with-span!` calls | Same 31 manual spans | None |
| AWS SDK spans | Auto-instrumentation | Manual spans at call sites | SDK parameters not auto-captured |
| Outbound HTTP spans | Apache HttpClient / OkHttp auto-inst. | Manual spans at call sites | Headers/URLs not auto-captured |

The only meaningful gaps are outbound SDK/HTTP spans. The existing manual spans at those call sites already capture the business-relevant context (which S3 bucket, which endpoint, what the operation is). The agent's automatic spans added low-level HTTP details (headers, full URLs) that are rarely actionable.

---

## Files changed this session

| File | Change |
|------|--------|
| `deps.edn` | Added `clj-otel-sdk`, `clj-otel-exporter-otlp`, `opentelemetry-jdbc`, `opentelemetry-hikaricp-3.0`; removed agent JVM opt from `:otel` alias |
| `src/kaleidoscope/main.clj` | `init-otel!` now initializes SDK programmatically; moved before `start-system!`; added `clj-otel-sdk` and `clj-otel-exporter-otlp` requires |
| `src/kaleidoscope/init/env.clj` | postgres launcher wraps datasource with `HikariTelemetry` + `JdbcTelemetry` |
| `Dockerfile` | Removed agent COPY, ARG, ENV, and `-javaagent` from CMD |
