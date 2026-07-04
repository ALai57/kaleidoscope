import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

new MultiStepCheck('auth-boundary', {
  name: 'Unauthenticated users cannot access protected resources',
  frequency: 10,
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'auth-boundary.spec.ts'),
  },
})
