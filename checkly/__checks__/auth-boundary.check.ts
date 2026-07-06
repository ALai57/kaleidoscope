import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

new MultiStepCheck('auth-boundary', {
  name: 'Unauthenticated users cannot access protected resources',
  frequency: 360, // every 6 hours
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'auth-boundary.spec.ts'),
  },
})
