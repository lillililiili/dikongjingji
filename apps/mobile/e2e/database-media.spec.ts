import { expect, test } from '@playwright/test'

import { testApi as api } from './support/test-stack'
const city = '370500'
const course = 'd7c00000-0000-4000-8000-000000000001'
const video = 'f3200000-0000-4000-8000-000000000001'
const room = 'f4000000-0000-4000-8000-000000000001'

const cases = [
  { name: '课程试听', endpoint: '/api/v1/course-trials?courseId=' + course,
    route: '/pages/course/trial?courseId=' + course, play: 'course-trial-play' },
  { name: '发现视频', endpoint: '/api/v1/content/videos/' + video + '?cityCode=' + city,
    route: '/pages/content/video?videoId=' + video + '&cityCode=' + city, play: 'content-video-play' },
  { name: '直播', endpoint: '/api/v1/live/rooms/' + room + '?cityCode=' + city,
    route: '/pages/live/room?roomId=' + room + '&cityCode=' + city, play: null },
]

for (const sample of cases) {
  test(sample.name + '播放器读取接口返回的媒体并实际播放', async ({ page, request }) => {
    const response = await request.get(api + sample.endpoint)
    expect(response.status()).toBe(200)
    const record = await response.json()
    const path = record.lessons ? record.lessons[0].playUrl : record.playUrl
    expect(path).toMatch(/^\/api\/v1\/dev-media\/videos\/preview\/.+\.mp4$/)
    const url = new URL(path, api).href
    const range = await request.get(url, { headers: { Range: 'bytes=0-31' } })
    expect(range.status()).toBe(206)
    expect(range.headers()['content-type']).toBe('video/mp4')
    expect(range.headers()['content-range']).toMatch(/^bytes 0-31\/\d+$/)
    expect((await range.body()).length).toBe(32)

    await page.goto('/#' + sample.route)
    if (sample.play) await page.getByTestId(sample.play).click()
    const player = page.locator('video').first()
    await expect(player).toHaveJSProperty('currentSrc', url)
    await expect.poll(() => player.evaluate(element => element.currentTime)).toBeGreaterThan(0.25)
  })
}

test('同一个已入库的视频失去媒体引用后，页面没有可播放源', async ({ page }) => {
  await page.route('**/api/v1/content/videos/' + video + '?**', async route => {
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    const record = await response.json()
    await route.fulfill({ response, json: { ...record, playUrl: null, coverImage: null } })
  })
  await page.goto('/#/pages/content/video?videoId=' + video + '&cityCode=' + city)
  await expect(page.getByTestId('content-video-no-source')).toBeVisible()
  await expect(page.getByTestId('content-video-player').locator('video')).toHaveCount(0)
})

test('巡检热榜服务缺图时仍显示名称并能进入该服务详情', async ({ page, request }) => {
  const portal = '/api/v1/inspection/portal?cityCode=' + city
  const response = await request.get(api + portal)
  expect(response.ok()).toBe(true)
  const payload = await response.json()
  const service = payload.hotServices[0]
  expect(service.serviceId).toBeTruthy()
  await page.route('**/api/v1/inspection/portal?**', route => route.fulfill({
    json: { ...payload, hotServices: [{ ...service, coverImage: null }, ...payload.hotServices.slice(1)] },
  }))
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-service').click()
  const card = page.locator('.hot-service-card').filter({ hasText: service.name }).first()
  await expect(card).toBeVisible()
  await expect(card.locator('.hot-service-card__visual')).toHaveCount(0)
  await card.click()
  await expect(page.getByTestId('inspection-detail')).toBeVisible()
  expect(new URLSearchParams(page.url().split('?')[1]).get('serviceId')).toBe(service.serviceId)
})
