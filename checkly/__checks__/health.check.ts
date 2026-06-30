import { ApiCheck, AssertionBuilder } from 'checkly/constructs'

new ApiCheck('health-ping', {
  name: 'Health — /ping',
  frequency: 1,
  locations: ['us-east-1', 'eu-west-1'],
  request: {
    url: 'https://sahiltalkingcents.com/ping',
    method: 'GET',
    assertions: [
      AssertionBuilder.statusCode().equals(200),
      AssertionBuilder.jsonBody('$.version').isNotNull(),
    ],
  },
})
