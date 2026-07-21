import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

let headers: Record<string, string> = {}
let projectId: string | null = null

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

test.afterAll('Delete the synthetic test project if one remains', async ({ request }) => {
  if (projectId) await request.delete(`/api/v1/projects/${projectId}`, { headers })
})

test('Authenticated users can create, read, update, and delete a project', async ({ request }) => {
  await test.step('Create a new project', async () => {
    const res = await request.post('/api/v1/projects', {
      headers,
      data: { title: `checkly-synthetic-test-${Date.now()}`, description: 'Synthetic monitoring test — safe to delete' },
    })
    expect(res.status()).toBe(200)
    const created = await res.json()
    expect(created.id).toBeTruthy()
    projectId = created.id
  })

  await test.step('View the newly created project', async () => {
    const res = await request.get(`/api/v1/projects/${projectId}`, { headers })
    expect(res.status()).toBe(200)
    expect((await res.json()).id).toBe(projectId)
  })

  await test.step('Update the project', async () => {
    const res = await request.put(`/api/v1/projects/${projectId}`, {
      headers, data: { title: `checkly-synthetic-test-${Date.now()}` },
    })
    expect(res.status()).toBe(200)
  })

  const deletedId = projectId

  await test.step('Delete the project', async () => {
    const res = await request.delete(`/api/v1/projects/${deletedId}`, { headers })
    expect(res.status()).toBe(204)
    projectId = null
  })

  await test.step('Confirm the project no longer exists', async () => {
    const res = await request.get(`/api/v1/projects/${deletedId}`, { headers })
    expect(res.status()).toBe(404)
  })
})
