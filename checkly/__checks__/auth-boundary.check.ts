import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

new MultiStepCheck('auth-boundary', {
  name: 'Auth boundary — protected endpoints return 401',
  frequency: 10,
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'auth-boundary.spec.ts'),
  },
})
