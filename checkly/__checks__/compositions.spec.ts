import { test, expect } from '@playwright/test'
import { BASE_URL } from './config'

test('Readers can list and open articles', async ({ request }) => {
  let first: { 'article-url': string }

  await test.step('The list of articles loads and is not empty', async () => {
    const listRes = await request.get(`${BASE_URL}/compositions`)
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
    const articleRes = await request.get(`${BASE_URL}/compositions/${encodeURIComponent(slug)}`)
    expect(articleRes.status()).toBe(200)
    const article = await articleRes.json()
    expect(article['article-url']).toBe(slug)
    expect(typeof article.content).toBe('string')
    expect(article.content.length).toBeGreaterThan(0)
  })

  await test.step('Opening an article that does not exist returns a proper "not found" response', async () => {
    const missingRes = await request.get(`${BASE_URL}/compositions/checkly-nonexistent-xyz`)
    expect(missingRes.status()).toBe(404)
  })
})
