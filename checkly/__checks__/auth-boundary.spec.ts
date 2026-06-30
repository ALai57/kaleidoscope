import { test, expect } from '@playwright/test'

const BASE_URL = 'https://sahiltalkingcents.com'

// Verifies the auth wall is running and returning 401, not crashing with 500.
test('protected endpoints reject unauthenticated requests with 401', async ({ request }) => {
  const endpoints: Array<{ method: 'GET' | 'POST', path: string }> = [
    { method: 'GET',  path: '/articles' },
    { method: 'GET',  path: '/projects' },
    { method: 'POST', path: '/workflows' },
    { method: 'GET',  path: '/agents' },
  ]

  for (const { method, path } of endpoints) {
    const res = await request[method.toLowerCase() as 'get' | 'post'](`${BASE_URL}${path}`)
    expect(res.status(), `Expected 401 from ${method} ${path}`).toBe(401)
  }
})
