import { PlaywrightCheck } from 'checkly/constructs'

// Each suite is a standard Playwright project (see ../playwright.config.ts).
// These thin wrappers are the ONLY Checkly-aware code — they schedule the
// suites and tag them by cost. `no-spend` suites make no paid external call and
// run on every branch; `spends` makes a real (paid) Claude call and runs only
// as a daily production monitor. The ephemeral runner selects `--tags no-spend`.
const SUITES: Array<{
  project: string
  name: string
  frequency: number
  locations: string[]
  tags: string[]
}> = [
  { project: 'liveness',      name: 'The server is running and reports its version.',                                 frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'homepage',      name: 'The homepage loads and renders without client-side errors.',                     frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'auth-boundary', name: 'Protected endpoints reject unauthenticated requests with 401.',                  frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'auth0-login',   name: 'The synthetic user can obtain an Auth0 access token.',                           frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'articles',      name: 'Published articles can be listed and opened, and missing articles return 404.',  frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'recipes',       name: 'Recipes can be created, labeled, read, and deleted, and their invariants hold.', frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'projects',      name: 'Authenticated users can create, read, update, and delete a project.',            frequency: 360,  locations: ['us-east-1'],              tags: ['spends'] },
  { project: 'scoring',       name: 'Scoring a project returns a score from Claude.',                                 frequency: 1440, locations: ['us-east-1'],              tags: ['spends'] },
]

for (const suite of SUITES) {
  new PlaywrightCheck(`suite-${suite.project}`, {
    name: suite.name,
    playwrightConfigPath: '../playwright.config.ts',
    pwProjects: [suite.project],
    frequency: suite.frequency,
    locations: suite.locations,
    tags: suite.tags,
  })
}
