import { ApiCheck, AssertionBuilder } from 'checkly/constructs'
import { BASE_URL } from './config'

new ApiCheck('health-ping', {
  name: 'The website responds to health checks',
  frequency: 360, // every 6 hours
  locations: ['us-east-1', 'eu-west-1'],
  request: {
    url: `${BASE_URL}/ping`,
    method: 'GET',
    assertions: [
      AssertionBuilder.statusCode().equals(200),
      AssertionBuilder.jsonBody('$.version').isNotNull(),
    ],
  },
})
