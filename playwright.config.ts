import { defineConfig, devices } from '@playwright/test'

const port = 4177
const baseUrl = `http://127.0.0.1:${port}/atlas/`

export default defineConfig({
  testDir: './tests/ui',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : 'list',
  use: {
    baseURL: baseUrl,
    trace: 'retain-on-failure'
  },
  webServer: {
    command: `npm run docs:dev -- --port ${port}`,
    url: baseUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      ...process.env,
      DOCS_BASE: '/atlas/'
    }
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
