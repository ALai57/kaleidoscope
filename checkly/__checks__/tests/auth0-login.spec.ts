import { test, expect } from '@playwright/test'
import { getAccessToken } from './lib/auth'

// Suite 4 — isolates the shared Auth0 dependency so a login outage shows up as
// one red line instead of reddening every write-path suite at once.
test('The synthetic user can obtain an Auth0 access token', async ({ request }) => {
  const token = await getAccessToken(request)
  expect(token, 'Auth0 returned no access token').toBeTruthy()
})
