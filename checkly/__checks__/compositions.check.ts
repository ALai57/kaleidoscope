import { MultiStepCheck } from 'checkly/constructs'
import * as path from 'path'

new MultiStepCheck('compositions-listing', {
  name: 'Compositions — list and fetch article',
  frequency: 5,
  locations: ['us-east-1', 'eu-west-1'],
  code: {
    entrypoint: path.join(__dirname, 'compositions.spec.ts'),
  },
})
