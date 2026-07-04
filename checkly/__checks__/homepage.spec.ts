import { test, expect } from '@playwright/test'

test('The homepage loads without errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  await test.step('The homepage loads successfully', async () => {
    const response = await page.goto('https://sahiltalkingcents.com')
    expect(response?.status()).toBe(200)
    await page.waitForLoadState('networkidle')
  })

  await test.step('The page has a title', async () => {
    await expect(page).toHaveTitle(/.+/)
  })

  await test.step('The page has no unhandled JavaScript errors', async () => {
    expect(errors, `JS errors on homepage: ${errors.join(', ')}`).toHaveLength(0)
  })
})
