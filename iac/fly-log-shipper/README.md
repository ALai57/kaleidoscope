# Fly Log Shipper

Forwards logs from Fly.io apps to external destinations using [fly-log-shipper](https://github.com/superfly/fly-log-shipper) (Vector-based).

The shipper runs as a separate Fly.io app. It subscribes to the org's internal NATS log stream and fans logs out to configured sinks.

---

## Deploy

```bash
fly apps create kaleidoscope-log-shipper --org personal
fly secrets set <...see Secrets below...> --app kaleidoscope-log-shipper
fly deploy --config iac/fly-log-shipper/fly.toml
```

---

## Secrets

| Secret | Description |
|---|---|
| `ORG` | Fly.io org slug (e.g. `personal`) |
| `ACCESS_TOKEN` | Fly.io personal access token — used to authenticate against the NATS log stream |
| `APP_NAME` | *(optional)* Filter to a single app; omit to ship all org logs |
| `LOKI_URL` | Grafana Loki base URL — **no path** (Vector appends `/loki/api/v1/push` automatically) |
| `LOKI_USERNAME` | Grafana Loki numeric user ID |
| `LOKI_PASSWORD` | Grafana Cloud API token with **Logs → Write** scope |
| `HTTP_URL` | Full Sumologic HTTP collector URL (token embedded in path) |
| `HTTP_TOKEN` | Set to any non-empty value — Sumologic uses URL auth, not bearer, but this must be set for the sink to load |

Generate the Fly.io access token with:
```bash
fly tokens create deploy
```

---

## Configuring Grafana Loki Cloud

1. Log in to [grafana.com](https://grafana.com) and go to **My Account**
2. Under your stack, click **Details**
3. In the **Loki** section, note:
   - **URL** → use as `LOKI_URL` (base host only, e.g. `https://logs-prod-us-central1.grafana.net`)
   - **User** → use as `LOKI_USERNAME` (a numeric ID)
4. Generate an API token: click **Generate now** (or go to **Access Policies → Create access token**)
   - Scope: **logs:write**
   - Use the token as `LOKI_PASSWORD`

---

## Supported sinks

fly-log-shipper activates a sink only when its required env vars are set. Run `fly logs --app kaleidoscope-log-shipper` after deploy and look for `Configured sinks:` to confirm which are active.

See the [full sink list](https://github.com/superfly/fly-log-shipper/tree/main/vector-configs/sinks) for other destinations (Datadog, S3, Honeycomb, etc.).
