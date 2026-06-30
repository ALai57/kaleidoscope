import { test, expect } from '@playwright/test'

const BASE_URL = 'https://sahiltalkingcents.com'

test('compositions list is non-empty and first article is fetchable', async ({ request }) => {
  // Step 1: list returns data with expected shape
  const listRes = await request.get(`${BASE_URL}/compositions`)
  expect(listRes.status()).toBe(200)
  const articles = await listRes.json()
  expect(Array.isArray(articles)).toBe(true)
  expect(articles.length).toBeGreaterThan(0)
  const first = articles[0]
  expect(first).toHaveProperty('article-url')
  expect(first).toHaveProperty('article-title')
  expect(first).toHaveProperty('hostname')

  // Step 2: fetch the first article by slug
  const slug = first['article-url']
  const articleRes = await request.get(`${BASE_URL}/compositions/${encodeURIComponent(slug)}`)
  expect(articleRes.status()).toBe(200)
  const article = await articleRes.json()
  expect(article['article-url']).toBe(slug)
  expect(typeof article.content).toBe('string')
  expect(article.content.length).toBeGreaterThan(0)

  // Step 3: 404 (not 500) for a nonexistent slug
  const missingRes = await request.get(`${BASE_URL}/compositions/checkly-nonexistent-xyz`)
  expect(missingRes.status()).toBe(404)
})
