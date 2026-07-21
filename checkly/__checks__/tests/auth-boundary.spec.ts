import { test, expect } from '@playwright/test'

// Suite 3 — is the auth wall running and returning 401, not 200 (open) or 500
// (crashing)?
test('Protected endpoints reject unauthenticated requests with 401', async ({ request }) => {
  const endpoints: Array<{ method: 'get' | 'post', path: string, description: string }> = [
    { method: 'get',  path: '/api/v1/articles',  description: 'Listing articles requires authentication' },
    { method: 'get',  path: '/api/v1/projects',  description: 'Listing projects requires authentication' },
    { method: 'post', path: '/api/v1/workflows', description: 'Starting a workflow requires authentication' },
    { method: 'get',  path: '/api/v1/agents',    description: 'Listing agents requires authentication' },
  ]

  for (const { method, path, description } of endpoints) {
    await test.step(description, async () => {
      const res = await request[method](path)
      expect(res.status(), `Expected 401 from ${method.toUpperCase()} ${path}`).toBe(401)
    })
  }
})
