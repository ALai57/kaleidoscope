# OTEL Collector

Receives traces from `kaleidoscope-publishing` over Fly.io's private network and fans them out to:
- **Grafana Tempo** — existing trace backend
- **Sumo Logic** — centralized observability

The main app points `OTEL_EXPORTER_OTLP_ENDPOINT` at this collector (`http://kaleidoscope-otel-collector.internal:4318`). The collector adds a batch processor and exports to all backends in parallel.

---

## Deploy

```bash
fly apps create kaleidoscope-otel-collector --org personal
fly secrets set \
  GRAFANA_INSTANCE_ID=<numeric-id> \
  GRAFANA_API_KEY=<api-token> \
  SUMOLOGIC_OTLP_ENDPOINT=<presigned-url> \
  --app kaleidoscope-otel-collector
fly deploy --config iac/otel-collector/fly.toml
```

Then redeploy the main app to pick up the new `OTEL_EXPORTER_OTLP_ENDPOINT`:

```bash
fly deploy --config fly.toml
```

---

## Secrets

| Secret | Description |
|---|---|
| `GRAFANA_INSTANCE_ID` | Grafana numeric instance/user ID (shown in Grafana Cloud → My Account → Stack Details → Tempo → User) |
| `GRAFANA_API_KEY` | Grafana Cloud API token with **traces:write** scope |
| `SUMOLOGIC_OTLP_ENDPOINT` | Sumo Logic presigned OTLP URL — auth is embedded in the URL, no separate credentials needed. See below. |

### Finding the Sumo Logic presigned OTLP URL

1. Log in to Sumo Logic → **Manage Data → Collection → Add Collector**
2. Choose **Hosted Collector**, then add an **OTLP** source
3. Copy the generated endpoint URL — it contains the auth token in the path

The collector appends `/v1/traces` automatically — set the base path only (omit `/v1/traces` from the URL you copy).

### Finding Grafana Tempo credentials

1. Log in to [grafana.com](https://grafana.com) → **My Account**
2. Under your stack, click **Details**
3. In the **Tempo** section: note the **User** (numeric ID → `GRAFANA_INSTANCE_ID`)
4. Generate an API token with **traces:write** scope → `GRAFANA_API_KEY`

---

## Verifying

Check the collector is healthy:

```bash
fly logs --app kaleidoscope-otel-collector
```

Look for `Everything is ready. Begin running and processing data.` on startup.

The health check endpoint is available internally at port 13133. To test from a Fly machine:

```bash
fly ssh console --app kaleidoscope-otel-collector
curl http://localhost:13133/health/status
```

---

## Bugsnag Performance — investigated and dropped

We attempted to fan traces out to Bugsnag Performance via their OTLP endpoint (`https://{api-key}.otlp.bugsnag.com:4318/v1/traces`) but found it does not work for generic server-side OTLP spans.

**What we tried:**
- Correct API key in the subdomain, port 4318, `traces_endpoint` config
- Both gzip (default) and uncompressed (`compression: none`) payloads
- Adding the `bugsnag.sampling.p: 1.0` span attribute their SDK sets
- Direct curl with a valid OTLP JSON payload

**What we observed:**
- The endpoint returns `HTTP 200 {"partialSuccess":{}}` for everything — including raw garbage bytes — with no validation
- No spans ever appeared in the Bugsnag Performance API or dashboard despite confirmed 200 responses
- The OTel collector logged no errors because it saw only 200s

**Conclusion:** Bugsnag Performance's OTLP endpoint appears to be designed for their own mobile/browser SDKs, which attach Bugsnag-specific internal attributes. Generic server-side OTLP spans are silently accepted but never indexed. Bugsnag's Java SDK (`com.bugsnag/bugsnag`, already in `deps.edn`) is the supported path for error monitoring on this service — that is separate from Performance and works correctly.

---

## Architecture notes

- Runs on Fly.io private network only — no public ports exposed
- The main app reaches it via `kaleidoscope-otel-collector.internal:4318` (Fly 6PN DNS)
- Uses `otel/opentelemetry-collector-contrib` image (includes `basicauth` extension and all HTTP exporters)
- Pin the image tag in `Dockerfile` before running in production
