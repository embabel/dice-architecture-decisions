import { defineConfig } from '@playwright/test'

const port = process.env.DECISIONS_BROWSER_PORT || '9184'
const address = 'http://127.0.0.1:' + port

export default defineConfig({
  testDir: './browser',
  use: { baseURL: address },
  webServer: {
    command: 'java -jar target/dice-architecture-decisions.jar --spring.profiles.active=offline --server.port=' + port,
    env: { DECISIONS_HISTORY_FILE: 'target/browser-decisions-' + process.pid + '.json' },
    url: address,
    reuseExistingServer: false,
    timeout: 40_000,
  },
})
