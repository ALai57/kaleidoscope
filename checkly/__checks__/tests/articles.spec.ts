import { test, expect } from '@playwright/test'

// Suite 5 — does the public CMS read path work end-to-end?
test('Published articles can be listed and opened, and missing articles return 404', async ({ request }) => {
  let first: { 'article-url': string }

  await test.step('The list of articles loads and is not empty', async () => {
    const listRes = await request.get('/api/v1/compositions')
    expect(listRes.status()).toBe(200)
    const articles = await listRes.json()
    expect(Array.isArray(articles)).toBe(true)
    expect(articles.length).toBeGreaterThan(0)
    first = articles[0]
    expect(first).toHaveProperty('article-url')
    expect(first).toHaveProperty('article-title')
    expect(first).toHaveProperty('hostname')
  })

  await test.step('The first article can be opened', async () => {
    const slug = first['article-url']
    const articleRes = await request.get(`/api/v1/compositions/${encodeURIComponent(slug)}`)
    expect(articleRes.status()).toBe(200)
    const article = await articleRes.json()
    expect(article['article-url']).toBe(slug)
    expect(typeof article.content).toBe('string')
    expect(article.content.length).toBeGreaterThan(0)
  })

  await test.step('Opening an article that does not exist returns a 404', async () => {
    const missingRes = await request.get('/api/v1/compositions/checkly-nonexistent-xyz')
    expect(missingRes.status()).toBe(404)
  })
})
