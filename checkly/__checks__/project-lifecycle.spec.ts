import { test, expect } from '@playwright/test'

const BASE_URL    = 'https://sahiltalkingcents.com'
const AUTH0_DOMAIN = 'dev-722l4eivlaenj2h1.us.auth0.com'
const AUDIENCE    = 'https://api.andrewslai.com'

let projectId: string | null = null
let authHeaders: Record<string, string> = {}

test.beforeAll(async ({ request }) => {
  const tokenRes = await request.post(`https://${AUTH0_DOMAIN}/oauth/token`, {
    data: {
      grant_type:    'client_credentials',
      client_id:     process.env.AUTH0_CLIENT_ID,
      client_secret: process.env.AUTH0_CLIENT_SECRET,
      audience:      AUDIENCE,
    },
  })
  expect(tokenRes.status(), 'Auth0 token request failed').toBe(200)
  const { access_token } = await tokenRes.json()
  authHeaders = { Authorization: `Bearer ${access_token}` }
})

// Always clean up, even if assertions fail mid-test.
test.afterAll(async ({ request }) => {
  if (projectId) {
    await request.delete(`${BASE_URL}/projects/${projectId}`, { headers: authHeaders })
  }
})

test('Authenticated users can create, view, update, and delete a project', async ({ request }) => {
  await test.step('Create a new project', async () => {
    const createRes = await request.post(`${BASE_URL}/projects`, {
      headers: authHeaders,
      data: { title: `checkly-synthetic-test-${Date.now()}`, description: 'Synthetic monitoring test — safe to delete' },
    })
    expect(createRes.status()).toBe(200)
    const created = await createRes.json()
    expect(created.id).toBeTruthy()
    projectId = created.id
  })

  await test.step('View the newly created project', async () => {
    const getRes = await request.get(`${BASE_URL}/projects/${projectId}`, { headers: authHeaders })
    expect(getRes.status()).toBe(200)
    expect((await getRes.json()).id).toBe(projectId)
  })

  await test.step('Update the project', async () => {
    const updateRes = await request.put(`${BASE_URL}/projects/${projectId}`, {
      headers: authHeaders,
      data: { title: `checkly-synthetic-test-${Date.now()}`},
    })
    expect(updateRes.status()).toBe(200)
  })

  const deletedId = projectId

  await test.step('Delete the project', async () => {
    const deleteRes = await request.delete(`${BASE_URL}/projects/${deletedId}`, { headers: authHeaders })
    expect(deleteRes.status()).toBe(204)
    projectId = null  // prevents afterAll from double-deleting
  })

  await test.step('Confirm the project no longer exists', async () => {
    const goneRes = await request.get(`${BASE_URL}/projects/${deletedId}`, { headers: authHeaders })
    expect(goneRes.status()).toBe(404)
  })
})
