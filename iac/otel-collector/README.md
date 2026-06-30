# OTEL Collector

Receives traces from `kaleidoscope-publishing` over Fly.io's private network and fans them out to:
- **Grafana Tempo** — existing trace backend
- **Bugsnag Performance** — error + performance monitoring
- **Sumo Logic** — centralized observability

The main app points `OTEL_EXPORTER_OTLP_ENDPOINT` at this collector (`http://kaleidoscope-otel-collector.internal:4318`). The collector adds a batch processor and exports to all three backends in parallel.

---

## Deploy

```bash
fly apps create kaleidoscope-otel-collector --org personal
fly secrets set \
  GRAFANA_INSTANCE_ID=<numeric-id> \
  GRAFANA_API_KEY=<api-token> \
  BUGSNAG_ORG_API_KEY=<organization-api-key> \
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
| `BUGSNAG_ORG_API_KEY` | Bugsnag **organization-level** API key — used as a subdomain in the OTLP endpoint. Found under Organization settings, not project settings. |
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

## Architecture notes

- Runs on Fly.io private network only — no public ports exposed
- The main app reaches it via `kaleidoscope-otel-collector.internal:4318` (Fly 6PN DNS)
- Uses `otel/opentelemetry-collector-contrib` image (includes `basicauth` extension and all HTTP exporters)
- Pin the image tag in `Dockerfile` before running in production
