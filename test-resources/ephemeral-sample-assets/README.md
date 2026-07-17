# Ephemeral sample tenant assets

Sample assets synced by `scripts/ephemeral/seed-tenant-assets` to an ephemeral
env's isolated S3 prefix (`s3://kal-ephemeral/tenant-assets/<slug>/`), never
the real per-tenant bucket.

Each top-level directory here is named after a tenant hostname from
`resources/tenants.json` and mirrors the URL paths the backend actually
serves for that tenant (`static/...` -> `/static/...`, `media/...` ->
`/media/...`), so a seeded ephemeral env exercises the same request paths as
production without touching real tenant data.

These are placeholders — small, no-PII stand-ins (a minimal `favicon.ico`, a
short text file) meant to be swapped for curated, still-no-PII sample assets
later. Add a new `<hostname>/` directory here to support seeding a different
tenant.
