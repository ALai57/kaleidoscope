import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

let headers: Record<string, string> = {}
let projectId: string | null = null
let definitionId: string | null = null

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

test.afterAll('Delete the synthetic project and score definition', async ({ request }) => {
  if (projectId)    await request.delete(`/projects/${projectId}`, { headers })
  if (definitionId) await request.delete(`/score-definitions/${definitionId}`, { headers })
})

// Suite 8 — proves the whole AI scoring chain: key valid, model reachable,
// scorer wired as `llm`, response parsed. Cost is bounded to ONE Claude call by
// using a single-dimension definition.
test('Scoring a project returns a score from Claude', async ({ request }) => {
  await test.step('Create a synthetic project', async () => {
    const res = await request.post('/projects', {
      headers,
      data: { title: `checkly-synthetic-scoring-${Date.now()}`, description: 'Synthetic monitoring — safe to delete' },
    })
    expect(res.status()).toBe(200)
    projectId = (await res.json()).id
    expect(projectId).toBeTruthy()
  })

  await test.step('Create a single-dimension score definition (bounds cost to one Claude call)', async () => {
    const res = await request.post('/score-definitions', {
      headers,
      data: { name: `checkly-synthetic-scoredef-${Date.now()}`, dimensions: [{ name: 'Clarity', criteria: 'Is the project description clear?' }] },
    })
    expect(res.status()).toBe(200)
    definitionId = (await res.json()).id
    expect(definitionId).toBeTruthy()
  })

  await test.step('Trigger scoring — makes one real Claude call', async () => {
    const res = await request.post(`/projects/${projectId}/scores`, {
      headers, data: { 'definition-ids': [definitionId] },
    })
    expect(res.status()).toBe(200)
  })

  await test.step('A fresh score from Claude comes back for the definition', async () => {
    const res = await request.get(`/projects/${projectId}/scores`, { headers })
    expect(res.status()).toBe(200)
    const runs: Array<{ definition: { id: string }, overall: number }> = await res.json()
    const run = runs.find((r) => r.definition.id === definitionId)
    expect(run, 'No score run recorded — the Claude call likely failed (POST /scores returns 200 even when scoring throws)').toBeTruthy()
    expect(typeof run!.overall, 'Score run has no numeric overall').toBe('number')
  })
})
