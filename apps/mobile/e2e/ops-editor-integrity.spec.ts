import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'
import { apiLogin, BUYER } from './support/trade-fixtures'
import { testAdmin, testApi, testMobile } from './support/test-stack'

const editors = [
  { name: '活动', path: '/activities', id: 'activityId', label: '活动名称', child: '新增场次' },
  { name: '场馆', path: '/venues', id: 'venueId', label: '场馆名称', child: '新增设备' },
  { name: '图文', path: '/content/articles', id: 'contentId', label: '标题' },
  { name: '视频', path: '/content/videos', id: 'contentId', label: '标题' },
  { name: '专题', path: '/content/topics', id: 'topicId', label: '标题' },
] as const

test.use({ trace: 'off', viewport: { width: 1366, height: 900 } })
test.describe.configure({ timeout: 90_000 })

test.beforeEach(() => {
  test.skip(!process.env.DEV_OPERATOR_PASSWORD || process.env.E2E_OPS_CONTENT_TEST !== '1', '需要隔离运营测试栈')
})

async function signIn(page: Page, path: string): Promise<void> {
  await page.goto(testAdmin + path)
  await page.locator('input[autocomplete="username"]').fill('dev-operator')
  await page.locator('input[autocomplete="current-password"]').fill(process.env.DEV_OPERATOR_PASSWORD!)
  const loggedIn = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/auth/login')
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  expect((await loggedIn).status()).toBe(200)
  await page.waitForURL(url => url.pathname === path)
}

async function operatorHeaders(page: Page) {
  const login = await page.request.post(testApi + '/api/v1/auth/login', {
    data: { loginName: 'dev-operator', password: process.env.DEV_OPERATOR_PASSWORD },
  })
  expect(login.status()).toBe(200)
  return { Authorization: 'Bearer ' + (await login.json()).accessToken }
}

async function firstResourceId(page: Page, resource: string, field: string, status?: string): Promise<string> {
  const first = await page.request.get(testApi + resource, {
    headers: await operatorHeaders(page),
    params: { page: 1, size: 1, ...(status ? { status } : {}) },
  })
  expect(first.status()).toBe(200)
  const id = (await first.json()).items[0]?.[field]
  expect(id).toBeTruthy()
  return id
}

for (const editor of editors) {
  test(editor.name + '详情读取失败可重试，实际资源有未保存修改时保护发布状态', async ({ page }) => {
    const missing = randomUUID()
    const resource = '/api/v1/ops' + editor.path
    await signIn(page, editor.path + '/' + missing)
    const retry = page.getByRole('button', { name: '重新加载', exact: true })
    await expect(retry).toBeVisible()
    await expect(page.getByRole('button', { name: /^(保存|保存资料|保存活动信息|存为草稿|新建草稿)$/ })).toHaveCount(0)
    const failed = page.waitForResponse(res => new URL(res.url()).pathname === resource + '/' + missing)
    await retry.click()
    expect((await failed).status()).toBe(404)
    await expect(retry).toBeVisible()

    const id = await firstResourceId(page, resource, editor.id)
    await page.goto(testAdmin + editor.path + '/' + id)
    const input = page.locator('.el-form-item').filter({ has: page.getByText(editor.label, { exact: true }) }).locator('input').first()
    const changeStatus = page.getByRole('button', { name: /^(上线|下线)$/ })
    await expect(changeStatus).toBeEnabled()
    const original = await input.inputValue()
    await input.fill('尚未保存的' + editor.name)
    await expect(changeStatus).toBeDisabled()
    await expect(page.getByText(/有未保存的修改/)).toBeVisible()
    if ('child' in editor) await expect(page.getByRole('button', { name: editor.child })).toBeDisabled()
    await input.fill(original)
    await expect(changeStatus).toBeEnabled()
  })
}

test('商品基本资料的未保存输入会保护规格和上架状态，可明确放弃后继续', async ({ page }) => {
  const id = await firstResourceId(page, '/api/v1/ops/catalog/products', 'productId', 'PUBLISHED')
  await signIn(page, '/catalog/products/' + id)
  const input = page.locator('.el-form-item').filter({ has: page.getByText('商品名称', { exact: true }) }).locator('input')
  const publish = page.getByTestId('publish-button')
  await expect(publish).toBeEnabled()
  const initial = await input.inputValue()
  await input.fill('尚未保存的商品资料')
  await expect(publish).toBeDisabled()
  await page.getByRole('tab', { name: /^规格/ }).click()
  await expect(page.getByRole('tab', { name: '基本资料' })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '放弃修改' }).click()
  await expect(input).toHaveValue(initial)
  await expect(publish).toBeEnabled()
  await page.getByRole('tab', { name: /^规格/ }).click()
  await expect(page.getByRole('tab', { name: /^规格/ })).toHaveAttribute('aria-selected', 'true')
})

for (const resource of [
  { name: '巡检', route: '/inspection-services', api: '/inspection/services' },
  { name: '专业服务', route: '/pro-services', api: '/pro-services' },
]) {
  test(resource.name + '改动记录断网不影响详情与保存入口，重试可恢复', async ({ page }) => {
    const base = '/api/v1/ops' + resource.api
    const id = await firstResourceId(page, base, 'serviceId')
    const history = '**' + base + '/' + id + '/changes'
    await page.route(history, route => route.abort('connectionreset'))
    await signIn(page, resource.route + '/' + id)
    const retry = page.getByRole('button', { name: '重试改动记录' })
    await expect(retry).toBeVisible()
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeEnabled()
    await page.unroute(history)
    const ok = page.waitForResponse(res => new URL(res.url()).pathname === base + '/' + id + '/changes')
    await retry.click()
    expect((await ok).status()).toBe(200)
    await expect(retry).toHaveCount(0)
  })
}

for (const records of [
  { name: '城市', path: '/cities', history: '/api/v1/ops/cities/*/service-status-changes' },
  { name: '直播', path: '/live', history: '/api/v1/ops/live/rooms/*/changes' },
]) {
  test(records.name + '审计抽屉加载失败可独立重试', async ({ page }) => {
    const history = '**' + records.history
    await page.route(history, route => route.abort('connectionreset'))
    await signIn(page, records.path)
    await page.getByRole('button', { name: '改动记录', exact: true }).first().click()
    const retry = page.getByRole('button', { name: '重试改动记录' })
    await expect(retry).toBeVisible()
    await page.unroute(history)
    const ok = page.waitForResponse(response => response.url().endsWith(records.history.split('*')[1]) && response.ok())
    await retry.click()
    expect((await ok).status()).toBe(200)
    await expect(retry).toHaveCount(0)
  })
}

test('后台发布系统通知，App 收件并显示正文，后台撤回后原详情失效', async ({ page, context, request }) => {
  const title = '运营联动验收' + randomUUID().slice(0, 8)
  const resource = '/api/v1/ops/notifications/system-notices'
  await signIn(page, '/system-notices')
  await page.getByTestId('notice-publish-open').click()
  const dialog = page.getByRole('dialog', { name: '发布系统通知' })
  await dialog.getByRole('textbox', { name: '标题', exact: true }).fill(title)
  await dialog.getByRole('textbox', { name: /摘要/ }).fill('隔离库发布验收')
  await dialog.getByRole('textbox', { name: '正文', exact: true }).fill('验收正文只存在隔离运营库。')
  await page.getByTestId('notice-publish-submit').click()
  const posting = page.waitForResponse(response => new URL(response.url()).pathname === resource && response.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '发布', exact: true }).click()
  const posted = await posting
  expect(posted.ok()).toBe(true)
  const id = (await posted.json()).id
  const row = page.getByRole('row').filter({ hasText: title })
  const mobile = await context.newPage()
  const session = await apiLogin(request, BUYER)
  expect(session).toBeTruthy()
  await mobile.addInitScript(token => localStorage.setItem('inspection.accessToken', token), session!.token)
  try {
    await mobile.goto(testMobile + '/#/pages/message/index')
    await mobile.locator('[data-category="SYSTEM_NOTICE"]').filter({ hasText: title }).click({ timeout: 12_000 })
    await expect(mobile.getByTestId('notice-title')).toHaveText(title)
    await expect(mobile.getByTestId('notice-page')).toContainText('验收正文只存在隔离运营库。')
  } finally {
    await row.getByRole('button', { name: '撤回' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '撤回' }).click()
    await expect(row).toContainText('已撤回')
  }
  await mobile.goto(testMobile + '/?refresh=1#/pages/message/notice?id=' + id)
  await expect(mobile.getByTestId('notice-missing')).toBeVisible()
})

test('后台编辑场馆设备并停用，App 馆内设备随状态更新', async ({ page, context }) => {
  const id = await firstResourceId(page, '/api/v1/ops/venues', 'venueId', 'PUBLISHED')
  const title = '验收机器人' + randomUUID().slice(0, 8)
  const base = '/api/v1/ops/venues/' + id + '/devices'
  await signIn(page, '/venues/' + id)
  await page.getByRole('button', { name: '新增设备' }).click()
  const create = page.getByRole('dialog', { name: '新增设备' })
  await create.getByRole('textbox', { name: '设备名称' }).fill(title)
  await create.getByRole('button', { name: '保存' }).click()
  const posted = page.waitForResponse(response => new URL(response.url()).pathname === base && response.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
  const created = await posted
  expect(created.ok()).toBe(true)
  const venue = await created.json()
  const deviceId = venue.devices.find((d: { name: string }) => d.name === title).deviceId
  const row = page.getByRole('row').filter({ hasText: title })
  const mobile = await context.newPage()
  async function readDevices(visit: number) {
    const read = mobile.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/venues/' + id + '/devices')
    await mobile.goto(testMobile + '/?visit=' + visit + '#/pages/venue/devices?venueId=' + id + '&cityCode=' + venue.cityCode)
    expect((await read).ok()).toBe(true)
  }
  try {
    await readDevices(1)
    await expect(mobile.getByTestId('venue-device-' + deviceId)).toContainText(title)
    await row.getByRole('button', { name: '编辑' }).click()
    const edit = page.getByRole('dialog', { name: '修改设备', exact: true })
    await edit.getByRole('textbox', { name: '说明' }).fill('本场馆配置的设备说明')
    await edit.getByRole('button', { name: '保存' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
    await expect(edit).toBeHidden()
    await readDevices(2)
    await expect(mobile.getByTestId('venue-device-' + deviceId)).toContainText('本场馆配置的设备说明')
  } finally {
    await row.getByRole('button', { name: '停用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '停用', exact: true }).click()
    await expect(row.getByRole('button', { name: '启用' })).toBeVisible()
  }
  await readDevices(3)
  await expect(mobile.getByTestId('venue-device-' + deviceId)).toHaveCount(0)
})

test('运营新建课程、录入章节和课时，刷新保留目录并阻止未齐资料上架', async ({ page }) => {
  const title = '验收课程' + randomUUID().slice(0, 8)
  await signIn(page, '/courses')
  await page.getByRole('button', { name: '新建课程' }).click()
  const dialog = page.getByRole('dialog', { name: '新建课程' })
  await dialog.getByRole('textbox', { name: '课程名称' }).fill(title)
  await dialog.getByRole('combobox').click()
  await page.getByRole('option').first().click()
  await dialog.getByRole('button', { name: '新建', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '新建', exact: true }).click()
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await expect(page.getByRole('button', { name: '上架', exact: true })).toBeDisabled()
  await page.getByRole('tab', { name: '目录' }).click()
  await page.getByRole('button', { name: '加一章' }).click()
  await page.getByPlaceholder('章名', { exact: true }).fill('第一章')
  await page.getByRole('button', { name: '加一节课时' }).click()
  await page.getByPlaceholder('课时名称').fill('验收课时')
  await page.getByPlaceholder('时长 12:30').fill('01:35')
  await page.getByRole('button', { name: '保存目录', exact: true }).click()
  const save = page.waitForResponse(res => res.url().endsWith('/outline') && res.request().method() === 'PUT')
  await page.locator('.el-message-box').getByRole('button', { name: '保存目录', exact: true }).click()
  const saved = await save
  expect(saved.ok()).toBe(true)
  expect((await saved.json()).chapters[0].lessons[0]).toMatchObject({ title: '验收课时', durationSeconds: 95 })
  await page.reload()
  await page.getByRole('tab', { name: '目录' }).click()
  await expect(page.getByPlaceholder('章名', { exact: true })).toHaveValue('第一章')
  await expect(page.getByPlaceholder('课时名称')).toHaveValue('验收课时')
  await expect(page.getByRole('button', { name: '上架', exact: true })).toBeDisabled()
})

test('活动场次新增、停售、恢复与无报名删除，App 显示相应的报名状态', async ({ page, context }) => {
  const id = await firstResourceId(page, '/api/v1/ops/activities', 'activityId', 'PUBLISHED')
  const title = '验收场次' + randomUUID().slice(0, 8)
  const dates = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10) + ' 10:00'
  await signIn(page, '/activities/' + id)
  await page.getByRole('button', { name: '新增场次' }).click()
  const dialog = page.getByRole('dialog', { name: '新增场次' })
  await dialog.getByRole('textbox', { name: '场次名称' }).fill(title)
  for (const [placeholder, date] of [['北京时间', dates(35)], ['不晚于开始时间', dates(33)]]) {
    const input = dialog.getByPlaceholder(placeholder, { exact: true })
    await input.fill(date)
    await input.press('Enter')
  }
  await dialog.getByRole('spinbutton').fill('9')
  await dialog.getByRole('button', { name: '保存' }).click()
  const base = '/api/v1/ops/activities/' + id + '/sessions'
  const added = page.waitForResponse(res => new URL(res.url()).pathname === base && res.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
  const created = await added
  expect(created.ok()).toBe(true)
  const activity = await created.json()
  const sessionId = activity.sessions.find((s: { label: string }) => s.label === title).sessionId
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: title, exact: true }) })
  const mobile = await context.newPage()
  async function readActivity(visit: number) {
    const read = mobile.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/activities/' + id)
    await mobile.goto(testMobile + '/?session-check=' + visit + '#/pages/activity/detail?activityId=' + id + '&cityCode=' + activity.cityCode)
    expect((await read).ok()).toBe(true)
    return mobile.getByTestId('activity-session-' + sessionId)
  }
  async function change(action: string) {
    await row.getByRole('button', { name: action, exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: action, exact: true }).click()
  }
  try {
    await expect(await readActivity(1)).toHaveAttribute('data-status', 'OPEN')
    await change('停售')
    await expect(row.getByRole('button', { name: '恢复售卖' })).toBeVisible()
    await expect(await readActivity(2)).toHaveAttribute('data-status', 'CLOSED')
    await change('恢复售卖')
    await expect(row.getByRole('button', { name: '停售' })).toBeVisible()
    await expect(await readActivity(3)).toHaveAttribute('data-status', 'OPEN')
  } finally {
    await change('删除')
    await expect(row).toHaveCount(0)
  }
  await expect(await readActivity(4)).toHaveCount(0)
  const auditRows = page.getByRole('row').filter({ hasText: title })
    .filter({ has: page.getByRole('cell', { name: '场次', exact: true }) })
  for (const action of ['新建', '停售', '恢复售卖', '删除']) {
    await expect(auditRows.getByRole('cell', { name: action, exact: true })).toHaveCount(1)
  }
})

test('城市服务状态由运营明确选择，App 城市面板展示更新并可恢复', async ({ page, context }) => {
  await signIn(page, '/cities')
  await page.getByPlaceholder('名称、拼音或 6 位编码').fill('370600')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: '370600' })
  await expect(row).toContainText('未开通')
  const note = '验收城市开通状态' + randomUUID().slice(0, 8)
  const mobile = await context.newPage()
  async function update(target: string) {
    await row.getByRole('button', { name: '改状态' }).click()
    const dialog = page.getByRole('dialog', { name: /修改开通状态/ })
    const save = dialog.getByRole('button', { name: '保存' })
    await expect(save).toBeDisabled()
    await dialog.locator('label.el-radio').filter({ hasText: new RegExp('^' + target) }).click()
    await dialog.getByRole('textbox', { name: '改动说明' }).fill(note)
    await save.click()
    await page.locator('.el-message-box').getByRole('button', { name: '确认修改' }).click()
    await expect(row).toContainText(target)
  }
  async function searchCity(visit: number) {
    await mobile.goto(testMobile + '/?city-check=' + visit + '#/pages/home/index')
    await mobile.getByTestId('home-city').click()
    const sheet = mobile.locator('.city-sheet')
    await sheet.getByLabel('搜索城市').locator('input').fill('烟台')
    return sheet.locator('.city-sheet__search-results .city-sheet__city')
  }
  await update('筹备中')
  try {
    await expect(await searchCity(1)).toHaveText('烟台市筹备中')
  } finally {
    await update('未开通')
  }
  await expect(await searchCity(2)).toHaveText('烟台市未开通')
  await row.getByRole('button', { name: '改动记录' }).click()
  await expect(page.getByRole('dialog').getByText(note, { exact: true })).toHaveCount(2)
})

for (const config of [
  { name: '巡检', adminPath: '/inspection-services', apiPath: '/inspection/services', status: 'ACTIVE', appPath: '/pages/service/inspection/detail' },
  { name: '专业', adminPath: '/pro-services', apiPath: '/pro-services', status: 'PUBLISHED', appPath: '/pages/proservice/detail' },
]) {
  test(config.name + '目录保存、上架及下架与 App 详情一致', async ({ page, context }) => {
    const base = '/api/v1/ops' + config.apiPath
    const id = await firstResourceId(page, base, 'serviceId', config.status)
    const headers = await operatorHeaders(page)
    const seed = await page.request.get(testApi + base + '/' + id, { headers })
    expect(seed.ok()).toBe(true)
    const sample = await seed.json()
    const title = '验收' + config.name + randomUUID().slice(0, 8)
    const shared = {
      title, summary: '运营验收的服务资料', providerId: sample.providerId, cityCodes: ['370500'],
    }
    const item = [{ title: '验收条目' }]
    const fields = config.name === '巡检' ? {
      categoryCode: sample.categoryCode, coverUrl: 'https://service-cover.example.test/cover.png',
      priceMode: 'PROJECT_QUOTE', availability: 'RESERVABLE', primaryAction: 'RESERVE',
      includesReport: true, serviceMethods: ['UAV'], bookingItems: item, processSteps: item, deliverables: item,
    } : {
      type: 'AERIAL', categoryCode: 'TOURISM', priceMode: 'PROJECT', sortOrder: 99,
      facets: ['PHOTOS'], steps: ['确认现场需求'],
    }
    const posted = await page.request.post(testApi + base, { headers, data: { ...shared, ...fields } })
    expect(posted.ok(), await posted.text()).toBe(true)
    const serviceId = (await posted.json()).serviceId
    let releaseDetail!: () => void
    const gate = new Promise<void>(resolve => { releaseDetail = resolve })
    await page.route('**' + base + '/' + serviceId, async route => {
      await gate
      await route.continue()
    }, { times: 1 })
    await signIn(page, config.adminPath + '/' + serviceId)
    const input = page.locator('.el-form-item').filter({ has: page.getByText('标题', { exact: true }) }).locator('input')
    const edited = title + '修订'
    try {
      await expect(input).toBeDisabled()
    } finally {
      releaseDetail()
    }
    await expect(input).toHaveValue(title)
    await input.fill(edited)
    async function action(label: string, path: string, method: string) {
      await page.getByRole('button', { name: label, exact: true }).click()
      const pending = page.waitForResponse(res => new URL(res.url()).pathname === base + '/' + serviceId + path && res.request().method() === method)
      await page.locator('.el-message-box').getByRole('button', { name: label, exact: true }).click()
      const response = await pending
      expect(response.ok(), await response.text()).toBe(true)
      expect((await response.json()).title).toBe(edited)
    }
    await action('保存', '', 'PUT')
    const mobile = await context.newPage()
    await mobile.route('https://service-cover.example.test/**', route => route.fulfill({ status: 204 }))
    async function openApp(visit: number) {
      const read = mobile.waitForResponse(res => new URL(res.url()).pathname === '/api/v1' + config.apiPath + '/' + serviceId)
      await mobile.goto(testMobile + '/?service-check=' + visit + '#' + config.appPath + '?serviceId=' + serviceId + '&cityCode=370500')
      return (await read).status()
    }
    expect(await openApp(1)).toBe(404)
    await action('上架', '/publish', 'POST')
    try {
      expect(await openApp(2)).toBe(200)
      await expect(mobile.getByText(edited, { exact: true }).first()).toBeVisible()
    } finally {
      await action('下架', '/offline', 'POST')
    }
    expect(await openApp(3)).toBe(404)
    await expect(mobile.getByText(edited, { exact: true })).toHaveCount(0)
  })
}

test('场馆按周生成场次可重复提交且不增场，取消后 App 移除该时段', async ({ page, context }) => {
  const venueId = await firstResourceId(page, '/api/v1/ops/venues', 'venueId', 'PUBLISHED')
  const name = '排期验收' + randomUUID().slice(0, 8)
  await signIn(page, '/venues/' + venueId)
  await page.getByRole('button', { name: '新建项目' }).click()
  const dialog = page.getByRole('dialog', { name: '新建项目' })
  await dialog.getByRole('textbox', { name: '项目名称' }).fill(name)
  await dialog.getByRole('button', { name: '保存' }).click()
  const post = page.waitForResponse(res => res.url().endsWith('/projects') && res.request().method() === 'POST')
  await page.locator('.el-message-box').getByRole('button', { name: '保存' }).click()
  const response = await post
  expect(response.ok(), await response.text()).toBe(true)
  const venue = await response.json()
  const projectId = venue.projects.find((p: { name: string }) => p.name === name).projectId
  const row = page.getByRole('row').filter({ hasText: name })
  async function generate() {
    await row.getByRole('button', { name: '按周生成场次' }).click()
    await page.getByRole('dialog', { name: /按周生成场次/ }).getByRole('button', { name: '生成', exact: true }).click()
    const save = page.waitForResponse(res => res.url().endsWith('/sessions/generate') && res.request().method() === 'POST')
    await page.locator('.el-message-box').getByRole('button', { name: '生成' }).click()
    const result = await save
    expect(result.ok(), await result.text()).toBe(true)
    return result.json()
  }
  const mobile = await context.newPage()
  try {
    const first = await generate()
    expect(first.createdCount).toBeGreaterThan(0)
    expect(await generate()).toMatchObject({ createdCount: 0, skippedExistingCount: first.createdCount })
    const load = page.waitForResponse(res => new URL(res.url()).pathname.endsWith('/projects/' + projectId + '/sessions'))
    await row.getByRole('button', { name: '场次', exact: true }).click()
    const sessions = await (await load).json()
    const session = sessions.find((s: { status: string }) => s.status === 'ACTIVE')
    expect(session).toBeTruthy()
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })
      .format(new Date(session.startsAt))
    async function viewSession(visit: number) {
      await mobile.goto(testMobile + '/?venue-check=' + visit + '#/pages/venue/detail?venueId=' + venueId + '&cityCode=' + venue.cityCode)
      await mobile.getByTestId('venue-project-' + projectId).click()
      await mobile.getByTestId('venue-day-' + date).click()
      await expect(mobile.getByTestId('venue-sessions-loading')).toHaveCount(0)
      return mobile.getByTestId('venue-session-' + session.sessionId)
    }
    await expect(await viewSession(1)).toBeVisible()
    const drawer = page.getByRole('dialog', { name: '场次 · ' + name })
    await drawer.getByRole('button', { name: '取消', exact: true }).first().click()
    const cancel = page.waitForResponse(res => res.url().endsWith('/sessions/' + session.sessionId + '/cancel'))
    await page.locator('.el-message-box').getByRole('button', { name: '取消场次' }).click()
    expect((await cancel).ok()).toBe(true)
    await expect(await viewSession(2)).toHaveCount(0)
    await drawer.getByRole('button', { name: /关闭|close/i }).click()
  } finally {
    const drawer = page.getByRole('dialog', { name: '场次 · ' + name })
    if (await drawer.isVisible()) await page.keyboard.press('Escape')
    await row.getByRole('button', { name: '停用', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '停用' }).click()
    await expect(row.getByRole('button', { name: '启用', exact: true })).toBeVisible()
  }
})
