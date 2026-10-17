import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4300',
    channel: 'chrome',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command:
        'node node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4300 --configuration preview',
      url: 'http://127.0.0.1:4300',
      reuseExistingServer: false,
      timeout: 120000,
    },
    {
      command:
        'node node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4301 --configuration production',
      url: 'http://127.0.0.1:4301',
      reuseExistingServer: false,
      timeout: 120000,
    },
  ],
});
