import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { API, BUYER, apiLogin, databaseAvailable, psql } from './support/trade-fixtures'

const recordedId = 'b1000000-0000-4000-8000-000000000003'
const softwareId = 'b1000000-0000-4000-8000-000000000004'
const courseId = 'b1000000-0000-4000-8000-000000000005'

for (const [index, route] of ['simulation', 'result', 'software-trial', 'course-trial'].entries()) {
  test(`LINK-00${index + 1} 无参数打开 ${route} 后可回到体验频道`, async ({ page }) => {
    await page.goto(`/#/pages/experience/${route}`)
    await expect(page.getByTestId('experience-link-missing')).toContainText('链接缺少')
    await page.getByRole('button', { name: '去体验频道' }).click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  })
}

test('LINK-005 缺少场馆或城市时不发送设备请求，可返回体验频道', async ({ page }) => {
  const requests: string[] = []
  page.on('request', request => {
    if (/\/api\/v1\/venues\//.test(request.url())) requests.push(request.url())
  })
  for (const query of ['', '?cityCode=370500', '?venueId=b2000000-0000-4000-8000-000000000001']) {
    await page.goto(`/#/pages/venue/devices${query}`)
    await expect(page.getByTestId('experience-link-missing')).toBeVisible()
    await page.getByRole('button', { name: '去体验频道' }).click()
    await expect(page.getByTestId('experience-channel')).toBeVisible()
  }
  expect(requests).toEqual([])
})

test('LINK-006 软件试用和课程试听的错误项目链接不提示登录，也不会替账号启动其他体验', async ({ page, request }) => {
  const starts: string[] = []
  page.on('request', req => {
    if (req.method() === 'POST' && req.url().endsWith('/api/v1/experience/trials')) starts.push(req.url())
  })
  for (const authenticated of [false, true]) {
    if (authenticated) {
      const session = await apiLogin(request, BUYER)
      expect(session).toBeTruthy()
      await page.evaluate(token => localStorage.setItem('inspection.accessToken', token), session!.token)
      await page.reload()
    }
    for (const [route, id] of [['software-trial', courseId], ['course-trial', softwareId]]) {
      await page.goto(`/#/pages/experience/${route}?experienceId=${id}`)
      await expect(page.getByTestId('trial-unavailable')).toBeVisible()
      await expect(page.getByTestId('trial-login')).toHaveCount(0)
    }
  }
  expect(starts).toEqual([])
})

test('REC-001 录播详情断网后重新加载，恢复接口中的项目和视频空状态', async ({ page }) => {
  let offline = true
  await page.route(`**/api/v1/experiences/${recordedId}`, route => offline ? route.abort() : route.continue())
  await page.goto(`/#/pages/experience/recorded?experienceId=${recordedId}`)
  const content = page.getByTestId('recorded-page')
  await expect(content).toContainText('录播内容加载失败')
  offline = false
  await content.getByRole('button', { name: '重新加载' }).click()
  await expect(content).toContainText('该项目尚未配置录播视频')
})

test('REC-002 运营发布的录播可加载、重试播放，下架后原链接不可用', async ({ page, request }) => {
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password || !databaseAvailable(), '需要隔离测试数据库和运营账号')
  const login = await request.post(`${API}/api/v1/auth/login`, { data: { loginName: 'dev-operator', password } })
  expect(login.ok()).toBe(true)
  const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
  const id = randomUUID()
  const opsUrl = `${API}/api/v1/ops/experience-items/${id}`
  const videoUrl = 'https://recorded-media.example.test/test-video.mp4'
  const fields = { type: 'RECORDED_DEMO', title: '验收录播 ' + id, summary: '录播播放与下架回归',
    sortOrder: 100000, loginRequired: false, coverUrl: 'https://recorded-media.example.test/cover.jpg', videoUrl }
  try {
    const draft = await request.put(opsUrl, { headers, data: { ...fields, status: 'DRAFT' } })
    expect(draft.ok()).toBe(true)
    const published = await request.put(opsUrl, { headers, data: { ...fields, status: 'PUBLISHED', version: (await draft.json()).version } })
    expect(published.ok()).toBe(true)
    let interrupted = true
    await page.route(videoUrl, async route => {
      if (interrupted) return route.abort()
      const video = await request.get(`${API}/api/v1/dev-media/videos/preview/course-robot-coding.mp4`)
      expect(video.ok()).toBe(true)
      await route.fulfill({ response: video })
    })
    await page.route(fields.coverUrl, route => route.fulfill({ status: 204 }))
    await page.goto(`/#/pages/experience/recorded?experienceId=${id}`)
    const content = page.getByTestId('recorded-page')
    await expect(content).toContainText(fields.title)
    await expect(content).toContainText('视频播放失败')
    interrupted = false
    await content.getByText('重试播放').click()
    await expect.poll(() => page.locator('video').evaluate(el => (el as HTMLVideoElement).readyState)).toBeGreaterThanOrEqual(2)
    const offline = await request.put(opsUrl, { headers, data: { ...fields, status: 'OFFLINE', version: (await published.json()).version } })
    expect(offline.ok()).toBe(true)
    await page.reload()
    await expect(content).toContainText('该录播项目暂不可用')
  } finally {
    psql(`DELETE FROM experience_item_change WHERE experience_id = '${id}';
      DELETE FROM experience_item WHERE id = '${id}';`)
  }
})

test('CAL-001 独立打开活动日历选择有余位的场次，返回详情时保留同一场次', async ({ page }) => {
  await page.goto('/#/pages/activity/calendar?activityId=a2000000-0000-4000-8000-000000000005&cityCode=370500')
  await page.locator('[data-testid^="activity-calendar-day-"][aria-disabled="false"]').first().click()
  const option = page.locator('[data-testid^="activity-calendar-session-"][aria-disabled="false"]').first()
  const sessionId = (await option.getAttribute('data-testid'))!.slice('activity-calendar-session-'.length)
  await option.click()
  await page.getByTestId('activity-calendar-confirm').click()
  await expect(page.getByTestId(`activity-session-${sessionId}`)).toHaveAttribute('aria-pressed', 'true')
})

test('VEN-107 返回场馆详情时重读余位，保留选中的项目和日期', async ({ page }) => {
  await page.goto('/#/pages/venue/detail?venueId=b2000000-0000-4000-8000-000000000001&cityCode=370500')
  const project = page.getByTestId('venue-project-b4000000-0000-4000-8000-000000000102')
  await project.click()
  const day = page.locator('[data-testid^="venue-day-"]').nth(1)
  await day.click()
  await expect(page.getByTestId('venue-sessions-loading')).toHaveCount(0)
  await page.getByTestId('venue-detail-devices').click()
  await expect(page.getByTestId('venue-devices')).toBeVisible()
  const sessions = page.waitForResponse(response => /\/venues\/[^/]+\/sessions\?/.test(response.url()) && response.ok())
  await page.getByTestId('venue-devices-back').click()
  await sessions
  await expect(project).toHaveAttribute('aria-pressed', 'true')
  await expect(day).toHaveAttribute('aria-pressed', 'true')
})
