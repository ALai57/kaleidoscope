import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

// Runs from a single region only to avoid creating duplicate canary projects.
// Requires CHECKLY env vars: AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET
new MultiStepCheck('project-lifecycle', {
  name: 'Authenticated users can create, view, update, and delete a project',
  frequency: 360, // every 6 hours; can also be triggered on-demand via `npx checkly trigger`
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'project-lifecycle.spec.ts'),
  },
})
