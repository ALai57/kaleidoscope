import { defineConfig } from 'checkly'

export default defineConfig({
  projectName: 'Kaleidoscope Publishing',
  logicalId: 'kaleidoscope-publishing',
  checks: {
    activated: true,
    muted: false,
    runtimeId: '2026.04',
    locations: ['us-east-1', 'eu-west-1'],
    tags: ['kaleidoscope', 'production'],
    frequency: 360,
  },
  cli: {
    runLocation: 'us-east-1',
  },
})
