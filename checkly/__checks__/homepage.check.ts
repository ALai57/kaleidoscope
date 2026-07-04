import { BrowserCheck } from 'checkly/constructs'
import * as path from 'path'

new BrowserCheck('homepage-browser', {
  name: 'The homepage loads without errors',
  frequency: 5,
  locations: ['us-east-1'],
  code: {
    entrypoint: path.join(__dirname, 'homepage.spec.ts'),
  },
})
