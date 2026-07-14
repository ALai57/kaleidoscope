import { test, expect } from '@playwright/test'

// Suite 1 — is the deployed process up and serving HTTP?
test('The server is running and reports its version', async ({ request }) => {
  const res = await request.get('/ping')
  expect(res.status()).toBe(200)
  const body = await res.json()
  expect(body.version, 'The /ping response has no version').toBeTruthy()
})
