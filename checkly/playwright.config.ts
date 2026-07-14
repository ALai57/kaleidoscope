import { defineConfig, devices } from '@playwright/test'

// Every suite targets this base URL. Defaults to production so the scheduled
// monitors watch the live site; override with ENVIRONMENT_URL to point at an
// ephemeral or staging environment.
const BASE_URL = process.env.ENVIRONMENT_URL ?? 'https://sahiltalkingcents.com'

export default defineConfig({
  testDir: './__checks__/tests',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: BASE_URL,
    ...devices['Desktop Chrome'],
  },
  // One project per feature suite. Each maps to a single spec so it can be run
  // and scheduled in isolation (npx playwright test --project=<name>).
  projects: [
    { name: 'liveness',      testMatch: /liveness\.spec\.ts/ },
    { name: 'homepage',      testMatch: /homepage\.spec\.ts/ },
    { name: 'auth-boundary', testMatch: /auth-boundary\.spec\.ts/ },
    { name: 'auth0-login',   testMatch: /auth0-login\.spec\.ts/ },
    { name: 'articles',      testMatch: /articles\.spec\.ts/ },
    { name: 'recipes',       testMatch: /recipes\.spec\.ts/ },
    { name: 'projects',      testMatch: /projects\.spec\.ts/ },
    { name: 'scoring',       testMatch: /scoring\.spec\.ts/ },
  ],
})
