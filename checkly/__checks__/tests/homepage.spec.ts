import { test, expect } from '@playwright/test'

// Suite 2 — does the SPA actually render for a human (static content served,
// app shell boots, no uncaught JS)?
test('The homepage loads and renders without client-side errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  await test.step('The homepage loads successfully', async () => {
    const response = await page.goto('/')
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
