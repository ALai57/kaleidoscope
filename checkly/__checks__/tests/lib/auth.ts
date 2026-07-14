import { APIRequestContext, expect } from '@playwright/test'

const AUTH0_DOMAIN = 'dev-722l4eivlaenj2h1.us.auth0.com'
const AUDIENCE     = 'https://api.andrewslai.com'

// Client-credentials (M2M) exchange for the synthetic monitoring user.
export async function getAccessToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`https://${AUTH0_DOMAIN}/oauth/token`, {
    data: {
      grant_type:    'client_credentials',
      client_id:     process.env.AUTH0_CLIENT_ID,
      client_secret: process.env.AUTH0_CLIENT_SECRET,
      audience:      AUDIENCE,
    },
  })
  expect(res.status(), 'The synthetic test user could not log in').toBe(200)
  const { access_token } = await res.json()
  return access_token
}

export async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await getAccessToken(request)}` }
}
