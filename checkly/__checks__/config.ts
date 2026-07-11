// The base URL every check targets.
//
// Defaults to production so the scheduled monitors keep watching the live site.
// Set ENVIRONMENT_URL to point the checks at another environment — e.g. an
// ephemeral Fly env when validating a branch before it ships:
//
//   ENVIRONMENT_URL=https://kal-eph-<slug>.fly.dev \
//     npx checkly test --env ENVIRONMENT_URL=https://kal-eph-<slug>.fly.dev
//
// (It is passed both as a shell env var — read here at construct-parse time by
// health.check.ts — and via --env, which injects it into the spec runtime.)
export const BASE_URL = process.env.ENVIRONMENT_URL ?? 'https://sahiltalkingcents.com'
