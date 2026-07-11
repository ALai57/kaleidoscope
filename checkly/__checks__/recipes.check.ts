import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

// Runs from a single region only to avoid creating duplicate synthetic recipes.
// Requires CHECKLY env vars: AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET (for the
// authenticated lifecycle steps). Override ENVIRONMENT_URL to point at an
// ephemeral env — see config.ts.
new MultiStepCheck('recipes-lifecycle', {
  name: 'The recipes API supports the full lifecycle and enforces its label and access rules',
  frequency: 360, // every 6 hours; can also be triggered on-demand via `npx checkly trigger`
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'recipes.spec.ts'),
  },
})
