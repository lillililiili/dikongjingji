import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

const courseId = 'd7c00000-0000-4000-8000-000000000001'
const media = 'https://course-media.example.test'
const sample = resolve(__dirname, '../src/static/videos/preview/course-flight-principle.mp4')

test('游客从体验频道进入真实课程试听，切换课时并进入对应的无人机仿真', async ({ page }, testInfo) => {
  await page.goto('/#/pages/home/index')
  await page.getByTestId('home-channel-experience').click()
  await page.getByTestId('experience-type-course_trial').click()
  await page.locator('.experience-card[data-type="COURSE_TRIAL"]').first().click()
  await expect(page).toHaveURL(/\/pages\/course\/trial\?experienceItemId=/)
  await expect(page.getByTestId('course-trial-slot')).toContainText('升力从哪里来')
  const secondLesson = page.getByTestId('course-trial-lesson-2')
  await secondLesson.click()
  await expect(secondLesson).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('course-trial-slot')).toContainText('四旋翼怎样保持平衡')
  await page.screenshot({ path: testInfo.outputPath('trial-from-experience.png') })
  await page.getByTestId('course-trial-play').click()
  await expect.poll(() => page.locator('video').evaluate((v) => (v as HTMLVideoElement).readyState)).toBeGreaterThanOrEqual(2)
  await page.getByTestId('course-trial-simulation').click()
  await expect(page).toHaveURL(/\/pages\/experience\/simulation\?experienceId=b1000000-0000-4000-8000-000000000002/)
  await expect(page.getByTestId('simulation-status')).toContainText('悬停')
  await page.screenshot({ path: testInfo.outputPath('drone-from-trial.png') })
})

async function openTrial(page: Page, simulationItemId: string | null = null) {
  // 测试课程与视频故障由请求桩提供，页面使用实际的 H5 路由、接口映射和播放器。
  await page.route('**/api/v1/course-trials?**', (route) => route.fulfill({ json: {
    courseId, simulationItemId, title: '无人机飞行原理线上课', organization: { name: '课程测试机构' },
    lessonCount: 3, purchasable: true, price: { mode: 'FIXED', amountMinor: 9900 },
    lessons: ['升力从哪里来', '四旋翼怎样保持平衡'].map((title, i) => ({
      lessonId: String(i + 1), no: i + 1, chapterTitle: '飞行原理', title, durationSeconds: 90,
      playUrl: media + '/lesson-' + (i + 1) + '.mp4',
    })),
  } }))
  await page.goto('/#/pages/course/trial?courseId=' + courseId)
  await expect(page.getByTestId('course-trial-play')).toBeVisible()
}

test('试听按配置进入对应机器人仿真，返回仍是同一课时', async ({ page }, testInfo) => {
  const robot = 'b1000000-0000-4000-8000-000000000001'
  await openTrial(page, robot)
  await page.getByTestId('course-trial-lesson-2').click()
  await page.getByTestId('course-trial-simulation').click()
  await expect(page).toHaveURL(new RegExp('/pages/experience/simulation\\?experienceId=' + robot))
  await expect(page.getByTestId('simulation-add-forward')).toBeVisible()
  await page.getByTestId('simulation-exit').click()
  await expect(page.getByTestId('course-trial-lesson-2')).toHaveAttribute('aria-pressed', 'true')
  await page.screenshot({ path: testInfo.outputPath('trial-with-simulation.png') })
})

test('没有配套仿真时仍可试听并查看课程详情', async ({ page }, testInfo) => {
  await openTrial(page)
  await expect(page.getByTestId('course-trial-simulation')).toHaveCount(0)
  await expect(page.getByTestId('course-trial-buy')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('trial-without-simulation.png') })
  await page.getByTestId('course-trial-course').click()
  await expect(page).toHaveURL(new RegExp('/pages/course/detail\\?courseId=' + courseId))
})

test('试听视频中断后能重新播放当前课时，或选择其他课时', async ({ page }, testInfo) => {
  let firstLessonAvailable = false
  let failedRequests = 0
  await page.route(media + '/**', (route) => {
    if (!firstLessonAvailable && route.request().url().endsWith('/lesson-1.mp4')) {
      failedRequests++
      return route.abort('failed')
    }
    return route.fulfill({ path: sample, contentType: 'video/mp4' })
  })
  await openTrial(page)
  await page.getByTestId('course-trial-play').click()
  await expect.poll(() => failedRequests).toBeGreaterThan(0)
  const retry = page.getByRole('button', { name: '重新播放' })
  await expect(retry).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('视频加载失败')
  await expect(page.getByTestId('course-trial-slot')).toContainText('升力从哪里来')
  for (const width of [320, 375, 390, 430]) {
    await page.setViewportSize({ width, height: 844 })
    await expect(retry).toBeInViewport()
    expect((await retry.boundingBox())?.height).toBeGreaterThanOrEqual(44)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: testInfo.outputPath('trial-media-error-' + width + '.png') })
  }
  firstLessonAvailable = true
  await retry.click()
  await expect.poll(() => page.locator('video').evaluate((video) => (video as HTMLVideoElement).readyState)).toBeGreaterThanOrEqual(2)
  await page.getByTestId('course-trial-lesson-2').click()
  await expect(page.getByTestId('course-trial-slot')).toContainText('四旋翼怎样保持平衡')
  await expect(page.getByTestId('course-trial-play')).toHaveText('开始试听')
})
