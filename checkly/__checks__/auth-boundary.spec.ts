import { test, expect } from '@playwright/test'

const BASE_URL = 'https://sahiltalkingcents.com'

// Verifies the auth wall is running and returning 401, not crashing with 500.
test('Unauthenticated users cannot access protected resources', async ({ request }) => {
  const endpoints: Array<{ method: 'GET' | 'POST', path: string, description: string }> = [
    { method: 'GET',  path: '/articles',  description: 'Listing articles requires authentication' },
    { method: 'GET',  path: '/projects',  description: 'Listing projects requires authentication' },
    { method: 'POST', path: '/workflows', description: 'Starting a workflow requires authentication' },
    { method: 'GET',  path: '/agents',    description: 'Listing agents requires authentication' },
  ]

  for (const { method, path, description } of endpoints) {
    await test.step(description, async () => {
      const res = await request[method.toLowerCase() as 'get' | 'post'](`${BASE_URL}${path}`)
      expect(res.status(), `Expected 401 from ${method} ${path}`).toBe(401)
    })
  }
})
