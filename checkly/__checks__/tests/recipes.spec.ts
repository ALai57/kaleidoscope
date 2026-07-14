import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

// Wire format is kebab-case (backend Malli models are kebab-keyed).
let headers: Record<string, string> = {}
let recipeUrl: string | null = null
let groupId: string | null = null
const labelIds: string[] = []

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

// Always clean up, even if assertions fail mid-test.
test.afterAll('Remove any synthetic recipe, labels, and group that remain', async ({ request }) => {
  if (recipeUrl) await request.delete(`/recipes/${recipeUrl}`, { headers })
  for (const id of labelIds) await request.delete(`/recipe-labels/${id}`, { headers })
  if (groupId) await request.delete(`/recipe-label-groups/${groupId}`, { headers })
})

test('Recipes can be created, labeled, read, and deleted, and their invariants hold', async ({ request }) => {
  const slug = `checkly-synthetic-recipe-${Date.now()}`

  await test.step('Create a new recipe', async () => {
    const res = await request.post('/recipes', {
      headers,
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
    expect((await res.json())['recipe-url']).toBe(slug)
    recipeUrl = slug
  })

  await test.step('View the newly created recipe with its parsed content', async () => {
    const res = await request.get(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(200)
    const recipe = await res.json()
    expect(recipe.content.title).toBe('Checkly Synthetic Recipe')
    expect(recipe.content.ingredients).toContain('1 cup flour')
  })

  await test.step('At most one label from a group can apply to a recipe', async () => {
    const groupRes = await request.post('/recipe-label-groups', {
      headers, data: { name: `checkly-ethnicity-${Date.now()}` },
    })
    expect(groupRes.status()).toBe(200)
    groupId = (await groupRes.json()).id

    const indianRes  = await request.post('/recipe-labels', { headers, data: { name: `indian-${Date.now()}`,  'group-id': groupId } })
    const mexicanRes = await request.post('/recipe-labels', { headers, data: { name: `mexican-${Date.now()}`, 'group-id': groupId } })
    expect(indianRes.status()).toBe(200)
    expect(mexicanRes.status()).toBe(200)
    const indianId  = (await indianRes.json()).id
    const mexicanId = (await mexicanRes.json()).id
    labelIds.push(indianId, mexicanId)

    await test.step('Two labels from the same group are rejected with a 400', async () => {
      const res = await request.put(`/recipes/${slug}`, { headers, data: { 'label-ids': [indianId, mexicanId] } })
      expect(res.status()).toBe(400)
    })

    await test.step('A single label from the group is accepted', async () => {
      const res = await request.put(`/recipes/${slug}`, { headers, data: { 'label-ids': [indianId] } })
      expect(res.status()).toBe(200)
    })
  })

  await test.step('Scraping a disallowed URL is rejected without fetching it', async () => {
    // 169.254.169.254 is the cloud metadata endpoint; the SSRF guard must block
    // it and return 422 rather than fetching.
    const res = await request.post('/recipes/scrape', {
      headers, data: { url: 'http://169.254.169.254/latest/meta-data/' },
    })
    expect(res.status()).toBe(422)
  })

  await test.step('Delete the recipe', async () => {
    const res = await request.delete(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(200)
    recipeUrl = null
  })

  await test.step('Confirm the recipe no longer exists', async () => {
    const res = await request.get(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(404)
  })
})

test('Recipes are readable publicly but only writable by authenticated users', async ({ request }) => {
  await test.step('Anyone can list recipes', async () => {
    const res = await request.get('/recipes')
    expect(res.status()).toBe(200)
    expect(Array.isArray(await res.json())).toBe(true)
  })

  await test.step('Anyone can list recipe labels', async () => {
    const res = await request.get('/recipe-labels')
    expect(res.status()).toBe(200)
  })

  await test.step('Creating a recipe without a token is rejected with a 401', async () => {
    const res = await request.post('/recipes', { data: { content: { title: 'unauthorized', ingredients: [] } } })
    expect(res.status()).toBe(401)
  })

  await test.step('Scraping without a token is rejected with a 401', async () => {
    const res = await request.post('/recipes/scrape', { data: { url: 'http://example.com/recipe' } })
    expect(res.status()).toBe(401)
  })
})
