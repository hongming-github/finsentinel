// Screenshots of every console page against a running stack (README "Console", ADR 0027), into docs/console/.
// Signs in through Keycloak like a person would (alice: analyst, sam: supervisor; dev users, password = name), using
// the Chrome installed on this machine (playwright-core downloads no browser).
//
//   npm run screenshots                  needs scripts/stack.sh up, the Flink job, some traffic and npm run dev
//   npm run screenshots -- --out=DIR     somewhere else
//   npm run screenshots -- --only=assistant [--case=ID] [--name=NAME]
//                                        only the case assistant (ADR 0028): a conversation on the case (default: the
//                                        first awaiting a decision), e.g. against a real model instead of fake-llm
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const CONSOLE = 'http://localhost:8097'
const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3)
const out = resolve(arg('out') ?? '../docs/console')
mkdirSync(out, { recursive: true })
// A follow-up that needs the previous answer, a pronoun, and a regulatory follow-up whose "it" must be resolved.
const QUESTIONS = [
  'Why did the agents reach this disposition and risk score?',
  'Which transaction matters most, and had the customer paid that payee before?',
  'What does the EUPG say about notifying the customer of a transfer like that?',
  'And does it require a cooling-off period?',
]

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  if (arg('only') === 'assistant') {
    const alice = await signIn('alice')
    await openCase(alice, arg('case'))
    await converse(alice, arg('name') ?? '11-case-assistant')
    process.exit(0)
  }
  const alice = await signIn('alice')
  await shot(alice, '/', '01-overview-analyst')
  await shot(alice, '/cases?stage=AWAITING_DECISION', '02-cases')
  await alice.locator('table a.mono').first().click()
  await alice.getByText('Observability').waitFor()
  await alice.waitForLoadState('networkidle')
  await save(alice, '03-case-detail')
  await converse(alice, '11-case-assistant')
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

async function openCase(page, caseId) {
  if (caseId) {
    await page.evaluate((p) => {
      window.history.pushState(null, '', p)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, `/cases/${caseId}`)
  } else {
    await shot(page, '/cases?stage=AWAITING_DECISION', null)
    await page.locator('table a.mono').first().click()
  }
  await page.getByText('Ask about this case').waitFor()
  await page.waitForLoadState('networkidle')
}

/** A new conversation on the open case, the questions one after the other, then the panel alone. */
async function converse(page, name) {
  const panel = page.locator('section.assistant')
  await panel.getByRole('button', { name: 'New conversation' }).click()
  await panel.getByText('Ask the first question.').waitFor()
  for (const [i, q] of QUESTIONS.entries()) {
    await panel.locator('textarea').fill(q)
    await panel.getByRole('button', { name: 'Ask' }).click()
    await panel.locator('.turn .bubble.a:not(.muted)').nth(i).waitFor({ timeout: 120_000 })
  }
  await page.waitForTimeout(500)
  await panel.screenshot({ path: `${out}/${name}.png` })
  console.log(`  ${name}.png`)
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
  if (name) await save(page, name, options)
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
