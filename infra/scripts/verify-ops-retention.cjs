// Run prepare, cross midnight on the isolated stack, then run verify before and after a backend restart.
// Only point the three test origins at the same disposable, restored database.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { readFileSync, writeFileSync } = require('node:fs')
const { createRequire } = require('node:module')
const { resolve } = require('node:path')
const { chromium } = createRequire(resolve(__dirname, '../../apps/mobile/package.json'))('@playwright/test')

const [step, recordFile] = process.argv.slice(2)
const activityId = 'a2000000-0000-4000-8000-000000000001'
const city = '370500'
const sampleImage = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jOZkAAAAASUVORK5CYII=', 'base64')
function testOrigin(name) {
  const url = new URL(process.env[name])
  assert.ok(['127.0.0.1', 'localhost'].includes(url.hostname), 'Use a local isolated stack')
  assert.ok(!['18420', '17430', '5182'].includes(url.port), 'Daily development ports are reserved')
  return url.origin
}
const api = testOrigin('RETENTION_API_URL')
const admin = testOrigin('RETENTION_ADMIN_URL')
const mobile = testOrigin('RETENTION_H5_URL')

async function read(url, headers = {}) {
  const response = await fetch(url, { headers })
  assert.equal(response.status, 200, new URL(url).pathname)
  return response.json()
}
async function signedInHeaders() {
  const password = process.env.DEV_OPERATOR_PASSWORD
  assert.ok(password, 'Set the operator credential for the restored test database')
  const response = await fetch(api + '/api/v1/auth/login', { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ loginName: 'dev-operator', password }) })
  assert.equal(response.status, 200)
  return { Authorization: 'Bearer ' + (await response.json()).accessToken }
}
async function publicData(cityCode) {
  return read(api + '/api/v1/home/carousel?cityCode=' + cityCode)
}
async function current(headers) {
  const activity = await read(api + '/api/v1/ops/activities/' + activityId, headers)
  const list = await read(api + '/api/v1/ops/home/carousel?cityCode=' + city + '&page=1&size=100', headers)
  const slide = list.items.find(row => row.id === 'contest')
  assert.ok(slide, 'Restore the example Dongying carousel on the disposable stack')
  const secondary = list.items.filter(row => row.id !== 'contest' && row.cityCode === city)
    .sort((a, b) => a.id.localeCompare(b.id))[0]
  assert.ok(secondary, 'Expected a second Dongying slide for the withdrawal test')
  return { activity, slide, secondary }
}
async function prepare(page, headers) {
  assert.equal(process.env.RETENTION_ALLOW_CLONE_WRITES, 'true', 'Explicitly enable writes on the restored copy')
  const initial = await current(headers)
  const otherCity = await publicData('370100')
  const label = randomUUID().slice(0, 8)
  const title = '跨日活动 ' + label
  const slideTitle = '跨日轮播 ' + label
  const amount = initial.activity.priceAmountMinor + 123
  const sortOrder = initial.slide.sortOrder === 1 ? 2 : 1
  await page.goto(admin + '/login')
  await page.locator('input[autocomplete="username"]').fill('dev-operator')
  await page.locator('input[autocomplete="current-password"]').fill(process.env.DEV_OPERATOR_PASSWORD)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL(url => url.pathname !== '/login')
  await page.goto(admin + '/activities/' + activityId)
  const activityTitle = page.getByLabel('活动名称', { exact: true })
  await activityTitle.fill(title)
  if (!initial.activity.enrollmentNotice) {
    await page.getByLabel('报名须知', { exact: true }).fill('按所选场次准时到场，请出示报名凭证。')
  }
  await page.getByLabel('单价（元/人）', { exact: true }).fill((amount / 100).toFixed(2))
  await page.getByRole('button', { name: '保存活动信息', exact: true }).click()
  const activitySaved = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname.endsWith('/' + activityId))
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const savedActivity = await activitySaved
  assert.equal(savedActivity.status(), 200, 'Activity update: ' + await savedActivity.text())
  await page.goto(admin + '/home-carousel')
  await page.getByRole('row').filter({ hasText: initial.slide.titleFirst }).getByRole('button', { name: '编辑' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('标题第一行', { exact: true }).fill(slideTitle)
  await dialog.getByRole('spinbutton').fill(String(sortOrder))
  await dialog.getByRole('spinbutton').press('Tab')
  const uploaded = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/ops/media'))
  await dialog.locator('input[type="file"]').setInputFiles({ name: 'retention.png', mimeType: 'image/png', buffer: sampleImage })
  assert.equal((await uploaded).status(), 201, 'New carousel media upload')
  const slideSaved = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname.endsWith('/contest'))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  const savedSlide = await slideSaved
  assert.equal(savedSlide.status(), 200, 'Slide update: ' + await savedSlide.text())
  await dialog.waitFor({ state: 'hidden' })
  const secondary = initial.secondary
  await page.getByRole('row').filter({ hasText: secondary.titleFirst }).getByRole('button', { name: '编辑' }).click()
  await dialog.locator('.el-form-item').filter({ hasText: /^状态/ }).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '已下架', exact: true }).click()
  const withdrawn = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname.endsWith('/' + secondary.id))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  assert.equal((await withdrawn).status(), 200, 'Withdrawal')
  const stored = await current(headers)
  assert.equal(stored.activity.title, title)
  assert.equal(stored.activity.priceAmountMinor, amount)
  assert.equal(stored.slide.titleFirst, slideTitle)
  assert.equal(stored.slide.sortOrder, sortOrder)
  assert.notEqual(stored.slide.imageUrl, initial.slide.imageUrl)
  assert.equal(stored.secondary.status, 'OFFLINE')
  return { ...stored, otherCity, publicSlides: await publicData(city) }
}
async function verify(page, headers, expected) {
  const stored = await current(headers)
  assert.deepEqual(stored.slide, expected.slide)
  assert.deepEqual(stored.secondary, expected.secondary)
  // Booking times/statuses calculated at read time can legitimately change at midnight.
  for (const key of ['title', 'priceAmountMinor', 'version', 'coverUrl', 'galleryUrls', 'status', 'updatedAt']) {
    assert.deepEqual(stored.activity[key], expected.activity[key], key)
  }
  assert.deepEqual(await publicData(city), expected.publicSlides)
  const fileName = new URL(stored.slide.imageUrl).pathname.split('/').pop()
  const preview = await fetch(api + '/api/v1/dev-media/ops/covers/' + fileName)
  assert.equal(preview.status, 200, 'Published carousel image')
  assert.deepEqual(Buffer.from(await preview.arrayBuffer()), sampleImage)
  assert.ok(!expected.publicSlides.items.some(row => row.id === expected.secondary.id))
  assert.deepEqual(await publicData('370100'), expected.otherCity)
  const publicActivity = await read(api + '/api/v1/activities/' + activityId + '?cityCode=' + city)
  assert.equal(publicActivity.title, expected.activity.title)
  assert.equal(publicActivity.price.amountMinor, expected.activity.priceAmountMinor)
  await page.goto(mobile + '/#/pages/activity/detail?activityId=' + activityId + '&cityCode=' + city)
  await page.getByText(expected.activity.title, { exact: true }).first().waitFor()
  await page.goto(mobile + '/#/pages/home/index')
  await page.getByTestId('home-carousel').getByText(expected.slide.titleFirst, { exact: true }).first().waitFor({state:'attached'})
}
async function main() {
  assert.ok(recordFile && ['prepare', 'verify'].includes(step), 'Expected prepare|verify and a local evidence file')
  const headers = await signedInHeaders()
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome', headless: true })
  try {
    const page = await browser.newPage()
    page.setDefaultTimeout(15000)
    const expected = step === 'prepare' ? await prepare(page, headers) : JSON.parse(readFileSync(recordFile, 'utf8'))
    await verify(page, headers, expected)
    if (step === 'prepare') writeFileSync(recordFile, JSON.stringify(expected, null, 2))
    console.log(step + ' passed: saved activity, price, carousel order and media, other city and H5 detail')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
