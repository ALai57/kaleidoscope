import { test, expect } from '@playwright/test'
import { BASE_URL } from './config'

const AUTH0_DOMAIN = 'dev-722l4eivlaenj2h1.us.auth0.com'
const AUDIENCE     = 'https://api.andrewslai.com'

// Wire format is kebab-case (the UI client converts snake_case → kebab before
// sending; the backend's Malli models are kebab-keyed), so requests below use
// hyphenated keys directly.

let authHeaders: Record<string, string> = {}
let recipeUrl: string | null = null
let groupId: string | null = null
const labelIds: string[] = []

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  const tokenRes = await request.post(`https://${AUTH0_DOMAIN}/oauth/token`, {
    data: {
      grant_type:    'client_credentials',
      client_id:     process.env.AUTH0_CLIENT_ID,
      client_secret: process.env.AUTH0_CLIENT_SECRET,
      audience:      AUDIENCE,
    },
  })
  expect(tokenRes.status(), 'The synthetic test user could not log in').toBe(200)
  const { access_token } = await tokenRes.json()
  authHeaders = { Authorization: `Bearer ${access_token}` }
})

// Always clean up, even if assertions fail mid-test.
test.afterAll('Remove any synthetic recipe, labels, and group that remain', async ({ request }) => {
  if (recipeUrl) {
    await request.delete(`${BASE_URL}/recipes/${recipeUrl}`, { headers: authHeaders })
  }
  for (const id of labelIds) {
    await request.delete(`${BASE_URL}/recipe-labels/${id}`, { headers: authHeaders })
  }
  if (groupId) {
    await request.delete(`${BASE_URL}/recipe-label-groups/${groupId}`, { headers: authHeaders })
  }
})

test('Authenticated users can create, label, and delete a recipe, and the one-per-group rule holds', async ({ request }) => {
  const slug = `checkly-synthetic-recipe-${Date.now()}`

  await test.step('Create a new recipe', async () => {
    const res = await request.post(`${BASE_URL}/recipes`, {
      headers: authHeaders,
      data: {
        'recipe-url': slug,
        content: {
          title: 'Checkly Synthetic Recipe',
          ingredients: ['1 cup flour', '2 eggs'],
          'instructions-html': '<ol><li>Mix</li><li>Bake</li></ol>',
        },
        'public-visibility': true,
      },
    })
    expect(res.status()).toBe(200)
    const created = await res.json()
    expect(created['recipe-url']).toBe(slug)
    recipeUrl = slug
  })

  await test.step('View the newly created recipe with its parsed content', async () => {
    const res = await request.get(`${BASE_URL}/recipes/${slug}`, { headers: authHeaders })
    expect(res.status()).toBe(200)
    const recipe = await res.json()
    expect(recipe.content.title).toBe('Checkly Synthetic Recipe')
    expect(recipe.content.ingredients).toContain('1 cup flour')
  })

  await test.step('At most one label from a group can apply to a recipe', async () => {
    const groupRes = await request.post(`${BASE_URL}/recipe-label-groups`, {
      headers: authHeaders,
      data: { name: `checkly-ethnicity-${Date.now()}` },
    })
    expect(groupRes.status()).toBe(200)
    groupId = (await groupRes.json()).id

    const indianRes = await request.post(`${BASE_URL}/recipe-labels`, {
      headers: authHeaders,
      data: { name: `indian-${Date.now()}`, 'group-id': groupId },
    })
    const mexicanRes = await request.post(`${BASE_URL}/recipe-labels`, {
      headers: authHeaders,
      data: { name: `mexican-${Date.now()}`, 'group-id': groupId },
    })
    expect(indianRes.status()).toBe(200)
    expect(mexicanRes.status()).toBe(200)
    const indianId = (await indianRes.json()).id
    const mexicanId = (await mexicanRes.json()).id
    labelIds.push(indianId, mexicanId)

    await test.step('Two labels from the same group are rejected with a 400', async () => {
      const res = await request.put(`${BASE_URL}/recipes/${slug}`, {
        headers: authHeaders,
        data: { 'label-ids': [indianId, mexicanId] },
      })
      expect(res.status()).toBe(400)
    })

    await test.step('A single label from the group is accepted', async () => {
      const res = await request.put(`${BASE_URL}/recipes/${slug}`, {
        headers: authHeaders,
        data: { 'label-ids': [indianId] },
      })
      expect(res.status()).toBe(200)
    })
  })

  await test.step('Scraping a disallowed URL is rejected without fetching it', async () => {
    // 169.254.169.254 is the cloud metadata endpoint; the SSRF guard must block
    // it and return 422 rather than fetching. This exercises the scrape route
    // end-to-end without depending on any external site being up.
    const res = await request.post(`${BASE_URL}/recipes/scrape`, {
      headers: authHeaders,
      data: { url: 'http://169.254.169.254/latest/meta-data/' },
    })
    expect(res.status()).toBe(422)
  })

  await test.step('Delete the recipe', async () => {
    const res = await request.delete(`${BASE_URL}/recipes/${slug}`, { headers: authHeaders })
    expect(res.status()).toBe(200)
    recipeUrl = null // prevents afterAll from double-deleting
  })

  await test.step('Confirm the recipe no longer exists', async () => {
    const res = await request.get(`${BASE_URL}/recipes/${slug}`, { headers: authHeaders })
    expect(res.status()).toBe(404)
  })
})

test('Recipes are readable publicly but only writable by authenticated users', async ({ request }) => {
  await test.step('Anyone can list recipes', async () => {
    const res = await request.get(`${BASE_URL}/recipes`)
    expect(res.status()).toBe(200)
    expect(Array.isArray(await res.json())).toBe(true)
  })

  await test.step('Anyone can list recipe labels', async () => {
    const res = await request.get(`${BASE_URL}/recipe-labels`)
    expect(res.status()).toBe(200)
  })

  await test.step('Creating a recipe without a token is rejected with a 401', async () => {
    const res = await request.post(`${BASE_URL}/recipes`, {
      data: { content: { title: 'unauthorized', ingredients: [] } },
    })
    expect(res.status()).toBe(401)
  })

  await test.step('Scraping without a token is rejected with a 401', async () => {
    const res = await request.post(`${BASE_URL}/recipes/scrape`, {
      data: { url: 'http://example.com/recipe' },
    })
    expect(res.status()).toBe(401)
  })
})
