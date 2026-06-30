import { test, expect } from '@playwright/test'

test('homepage loads with no JS errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  const response = await page.goto('https://sahiltalkingcents.com')
  expect(response?.status()).toBe(200)

  await page.waitForLoadState('networkidle')

  // Page has a non-empty title
  await expect(page).toHaveTitle(/.+/)

  // No unhandled JS exceptions
  expect(errors, `JS errors on homepage: ${errors.join(', ')}`).toHaveLength(0)
})
