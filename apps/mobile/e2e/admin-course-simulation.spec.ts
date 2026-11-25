import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'

import { expect, test as base, type APIResponse, type Response } from '@playwright/test'

import { databaseAvailable, psql } from './support/trade-fixtures'

const admin = process.env.ADMIN_BASE_URL ?? 'http://127.0.0.1:17430'
const drone = 'b1000000-0000-4000-8000-000000000002'
const robot = 'b1000000-0000-4000-8000-000000000001'
const media = 'https://course-assets.example.test'
const organizationId = 'a1000000-0000-4000-8000-000000000003'

async function savedCourse(response: APIResponse) {
  const result = await response.json()
  expect(response.ok(), JSON.stringify({ status: response.status(), code: result.code, missing: result.details?.missing })).toBe(true)
  return result
}

const test = base.extend<{ courseId: string }>({
  courseId: async ({ request }, use) => {
    const password = process.env.DEV_OPERATOR_PASSWORD
    base.skip(!password || !databaseAvailable(), '需要本机平台运营测试账号及可清理的开发数据库')
    const login = await request.post(admin + '/api/v1/auth/login', { data: { loginName: 'dev-operator', password } })
    expect(login.ok()).toBe(true)
    const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
    const title = 'e2e-course-simulation-' + randomUUID()
    const fields = { title, organizationId, topic: 'DRONE', sortOrder: 0, priceAmountMinor: 100, coverUrl: media + '/cover.jpg' }
    const created = await savedCourse(await request.post(admin + '/api/v1/ops/courses', {
      headers, data: { title, organizationId, topic: 'DRONE', mode: 'ONLINE', simulationItemId: drone },
    }))
    const id: string = created.courseId
    expect(id).toMatch(/^[0-9a-f-]{36}$/i)
    const url = admin + '/api/v1/ops/courses/' + id
    try {
      const configured = await savedCourse(await request.put(url, { headers, data: { ...fields, version: created.version } }))
      const outlined = await savedCourse(await request.put(url + '/outline', { headers, data: {
        version: configured.version, chapters: [{ title: '第一章', lessons: [
          { title: '仿真体验预习', durationSeconds: 60, trial: true, videoUrl: media + '/trial.mp4' },
        ] }],
      } }))
      const published = await savedCourse(await request.post(url + '/list', { headers, data: { version: outlined.version } }))
      expect(published).toMatchObject({ status: 'LISTED', listMissing: [], simulationItemId: drone })
      await use(id)
    } finally {
      const own = `SELECT id FROM course WHERE id = '${id}' AND title = '${title}'`
      psql(`BEGIN;
        DELETE FROM course_ops_change WHERE course_id IN (${own});
        DELETE FROM course_lesson WHERE course_id IN (${own});
        DELETE FROM course_chapter WHERE course_id IN (${own});
        DELETE FROM course WHERE id IN (${own});
        COMMIT;`)
      expect(psql(`SELECT count(*) FROM course WHERE id = '${id}'`)).toBe('0')
    }
  },
})

test('运营保存和清空配套仿真后，客户试听立即显示对应任务或隐藏入口', async ({ page, context, courseId }, testInfo) => {
  test.setTimeout(90_000)
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password, '需要本机平台运营测试账号口令')
  const customer = await context.newPage()
  await context.route(media + '/cover.jpg', route => route.fulfill({
    path: resolve(__dirname, '../src/static/images/preview/course-flight.jpg'), contentType: 'image/jpeg',
  }))
  const opsPath = '/api/v1/ops/courses/' + courseId
  const courseResponse = (method: string) => (response: Response) =>
    new URL(response.url()).pathname === opsPath && response.request().method() === method
  await page.setViewportSize({ width: 1366, height: 860 })
  await page.goto(`${admin}/courses/${courseId}`)
  await page.locator('input[autocomplete="username"]').fill('dev-operator')
  await page.locator('input[autocomplete="current-password"]').fill(password!)
  const initialRead = page.waitForResponse(courseResponse('GET'))
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  const response = await initialRead
  const initial = await response.json()
  expect(initial.simulationItemId).toBe(drone)
  const picker = page.locator('[data-test="simulation-picker"]')
  await expect(picker).toContainText('无人机起降与悬停')

  async function save(id: string | null) {
    await page.getByRole('button', { name: '保存基本资料' }).click()
    const saved = page.waitForResponse(courseResponse('PUT'))
    await page.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
    const updated = await saved
    const data = await updated.json()
    expect(updated.ok(), JSON.stringify({ status: updated.status(), code: data.code, missing: data.details?.missing })).toBe(true)
    expect(data.simulationItemId).toBe(id)
    expect(data.experienceItemId).toBe(initial.experienceItemId)
    await expect(page.locator('.el-notification', { hasText: '已保存基本资料' }).last()).toBeVisible()
  }

  async function openCustomerTrial(id: string | null) {
    const publicRead = customer.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/course-trials')
    await customer.goto('/?visit=' + (id ?? 'cleared') + '#/pages/course/trial?courseId=' + courseId)
    const publicTrial = await publicRead
    expect(publicTrial.ok()).toBe(true)
    expect((await publicTrial.json()).simulationItemId).toBe(id)
    await expect(customer.getByTestId('course-trial-lesson-1')).toBeVisible()
  }

  try {
    await picker.click()
    const alternate = page.getByRole('option', { name: '机器人避障模拟' })
    await expect(alternate).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('ops-simulation-picker.png') })
    await alternate.click()
    await save(robot)
    await openCustomerTrial(robot)
    await customer.getByTestId('course-trial-simulation').click()
    await expect(customer.getByTestId('simulation-add-forward')).toBeVisible()
    await expect(customer).toHaveURL(url => url.hash.endsWith('experienceId=' + robot))
    await picker.hover()
    await picker.locator('.el-select__clear').click()
    await save(null)
    await openCustomerTrial(null)
    await expect(customer.getByTestId('course-trial-simulation')).toHaveCount(0)
    await expect(customer.getByTestId('course-trial-buy')).toBeVisible()
  } finally {
    await customer.close()
  }
})
