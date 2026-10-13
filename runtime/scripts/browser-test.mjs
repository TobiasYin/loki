import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { serve } from './serve.mjs';
const { server, url } = await serve();
let browser;
try {
  browser = await chromium.launch({ headless: true, ...(process.env.THOR_CHROME_EXECUTABLE ? { executablePath: process.env.THOR_CHROME_EXECUTABLE } : {}) });
  const page = await browser.newPage(); const errors = []; const requests = [];
  page.on('pageerror', e => errors.push(e.message)); page.on('request', r => requests.push(r.url()));
  await page.goto(url); await page.waitForFunction(() => window.harnessResult, { timeout: 30_000 });
  const result = await page.evaluate(() => window.harnessResult);
  assert.deepEqual(errors, []); assert.equal(result.ok, true, JSON.stringify(result));
  assert.ok(requests.every(u => u.startsWith(url + '/')));
  assert.equal(await page.evaluate(() => typeof process), 'undefined');
  assert.equal(await page.evaluate(() => typeof Buffer), 'undefined');
  console.log(JSON.stringify({ browser: browser.version(), csp: 'self scripts / no unsafe-eval / no connections', networkRequests: requests, ...result }, null, 2));
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
