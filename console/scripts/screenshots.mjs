// Screenshots of every console page against a running stack (README "Console", ADR 0027), into docs/console/.
// Signs in through Keycloak like a person would (alice: analyst, sam: supervisor; dev users, password = name), using
// the Chrome installed on this machine (playwright-core downloads no browser).
//
//   npm run screenshots                  needs scripts/stack.sh up, the Flink job, some traffic and npm run dev
//   npm run screenshots -- --out=DIR     somewhere else
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const CONSOLE = 'http://localhost:8097'
const out = resolve(process.argv.find((a) => a.startsWith('--out='))?.slice(6) ?? '../docs/console')
mkdirSync(out, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const alice = await signIn('alice')
  await shot(alice, '/', '01-overview-analyst')
  await shot(alice, '/cases?stage=AWAITING_DECISION', '02-cases')
  await alice.locator('table a.mono').first().click()
  await alice.getByText('Observability').waitFor()
  await alice.waitForLoadState('networkidle')
  await save(alice, '03-case-detail')
  const trace = await alice.getByText('Trace of the current run (Tempo)').getAttribute('href')
  const logs = await alice.getByText('Logs of that trace (Loki)').getAttribute('href')
  await shot(alice, '/alerts', '04-alerts')
  await shot(alice, '/usage', '05-usage-analyst-forbidden', { fullPage: false })

  const sam = await signIn('sam')
  await shot(sam, '/', '06-overview-supervisor')
  await shot(sam, '/usage', '07-usage')
  await shot(sam, '/audit', '08-audit')

  // Where the case's links lead: Grafana (anonymous admin in the LGTM image).
  const grafana = await (await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: 'dark' })).newPage()
  if (trace) await external(grafana, trace, '09-tempo-trace', 'Trace ID')
  if (logs) await external(grafana, logs, '10-loki-logs', 'Logs volume')
  console.log(`screenshots in ${out}`)
} finally {
  await browser.close()
}

async function signIn(user) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: 'light' })
  const page = await context.newPage()
  await page.goto(CONSOLE)
  await page.getByRole('button', { name: 'Sign in with Keycloak' }).click()
  await page.locator('#username').fill(user)
  await page.locator('#password').fill(user)
  await page.locator('#kc-login').click()
  await page.getByRole('button', { name: 'Sign out' }).waitFor()
  return page
}

async function shot(page, path, name, options = {}) {
  // Client-side navigation: a full reload would sign in again through Keycloak.
  await page.evaluate((p) => {
    window.history.pushState(null, '', p)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, path)
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => !document.querySelector('.loading'))
  await page.waitForTimeout(1500) // health checks and the first refetch
  await save(page, name, options)
}

async function save(page, name, options = {}) {
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: options.fullPage ?? true })
  console.log(`  ${name}.png`)
}

async function external(page, url, name, text) {
  await page.goto(url)
  await page.getByText(text).first().waitFor({ timeout: 20_000 })
  await page.waitForTimeout(2500)
  await save(page, name, { fullPage: false })
}
