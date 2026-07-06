import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

new MultiStepCheck('compositions-listing', {
  name: 'Readers can list and open articles',
  frequency: 360, // every 6 hours
  locations: ['us-east-1', 'eu-west-1'],
  code: {
    entrypoint: path.join(__dirname, 'compositions.spec.ts'),
  },
})
