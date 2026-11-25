import { expect, test } from '@playwright/test'
import { s53Enrollment } from '../src/features/activity/tests/result-change-fixtures'
import { venueBooking } from '../src/features/experience/tests/venue-booking-fixtures'

const venue = '/#/pages/venue/detail?venueId=b2000000-0000-4000-8000-000000000001&cityCode=370500'
const activity = '/#/pages/activity/detail?activityId=a2000000-0000-4000-8000-000000000001&cityCode=370500'

test('场馆位置在第三方网络错误时保留地址与导航，可重新加载', async ({ page }) => {
  const provider = /https:\/\/(?:uri\.amap\.com\/marker|www\.amap\.com\/ssr\/embed\/place)\?/
  await page.route(provider, route => route.abort('failed'))
  await page.goto(venue)
  const address = await page.getByTestId('venue-detail-map').innerText()
  await page.getByTestId('venue-detail-map').click()
  await expect(page.getByTestId('destination-error')).toHaveText('地图加载失败')
  await expect(page.getByTestId('destination-page')).toContainText(address)
  await page.getByTestId('destination-navigate').click()
  for (const providerName of ['高德地图', '百度地图', '腾讯地图']) {
    await expect(page.getByText(providerName, { exact: true })).toBeVisible()
  }
  await page.getByText(/^(取消|Cancel)$/).click()
  await page.unroute(provider)
  await page.route(provider, route => route.fulfill({ contentType: 'text/html', body: '<title>test map provider</title>' }))
  await page.getByTestId('destination-retry').click()
  await expect(page.getByText('刷新地图', { exact: true })).toBeVisible()
})

test('活动导航将对应活动的目的地交给用户选择的地图', async ({ page }) => {
  const endpoint = 'https://api.map.baidu.com/direction'
  await page.route(endpoint + '**', route => route.fulfill({ contentType: 'text/html', body: '<title>test route provider</title>' }))
  await page.goto(activity)
  await page.getByTestId('activity-detail-navigate').click()
  await page.getByText('百度地图', { exact: true }).click()
  await page.waitForURL(endpoint + '**')
  const query = new URL(page.url()).searchParams
  expect(query.get('destination')).toMatch(/^latlng:[\d.]+,[\d.]+\|name:.+$/)
  expect(query.get('coord_type')).toBe('gcj02')
})

test('手动定位切换地图中心并保留导航目的地，定位失败可重试', async ({ page, context }) => {
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ longitude: 119.12, latitude: 36.09 })
  await page.addInitScript(() => {
    const acquire = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation)
    navigator.geolocation.getCurrentPosition = (...args) => {
      document.documentElement.dataset.locationRequests = '1'
      acquire(...args)
    }
  })
  await page.route(/https:\/\/(?:uri\.amap\.com\/marker|www\.amap\.com\/ssr\/embed\/place)\?/, route =>
    route.fulfill({ contentType: 'text/html', body: '<title>test map provider</title>' }))
  await page.goto(venue)
  const address = await page.getByTestId('venue-detail-map').innerText()
  await page.getByTestId('venue-detail-map').click()
  await expect(page.locator('iframe')).toHaveCount(1)
  expect(await page.locator('html').getAttribute('data-location-requests')).toBeNull()
  const originalMap = await page.locator('iframe').getAttribute('src')
  await page.getByTestId('destination-locate').click()
  await expect(page.locator('iframe')).toHaveAttribute('title', '我的位置')
  await expect(page.getByTestId('destination-page')).toContainText(address)
  await page.getByTestId('destination-locate').click()
  await expect(page.locator('iframe')).toHaveAttribute('src', originalMap!)
  await page.evaluate(() => {
    navigator.geolocation.getCurrentPosition = (_, failure) => failure?.({
      code: 1, message: 'permission denied', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3,
    })
  })
  await page.getByTestId('destination-locate').click()
  await expect(page.getByTestId('destination-location-message')).toContainText('无法获取当前位置')
  await expect(page.getByTestId('destination-locate')).toBeEnabled()
})

test('到馆预约凭证从场馆读取坐标，报名凭证缺坐标时仍可查看、复制地址', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('inspection.accessToken', 'map-test-fixture'))
  const booking = venueBooking()
  booking.venue.venueId = 'b2000000-0000-4000-8000-000000000001'
  await page.route('**/api/v1/venue-bookings/map-test', route => route.fulfill({ json: booking }))
  await page.route('**/api/v1/trade/**', route => route.fulfill({ status: 404, json: {} }))
  await page.goto('/#/pages/venue/booking?bookingId=map-test')
  await page.getByTestId('venue-booking-navigate').click()
  await expect(page.getByText('腾讯地图', { exact: true })).toBeVisible()
  await page.getByText(/^(取消|Cancel)$/).click()
  await page.getByTestId('venue-booking-map').click()
  await expect(page.getByTestId('destination-page')).toContainText(booking.venue.address)

  const enrollment = s53Enrollment()
  await page.route('**/api/v1/activity-enrollments/map-test', route => route.fulfill({ json: enrollment }))
  await page.goto('/#/pages/activity/ticket?enrollmentId=map-test')
  await page.getByTestId('ticket-navigate').click()
  await expect(page.getByText('暂无精确位置', { exact: true })).toBeVisible()
  await page.locator('uni-modal').getByText(/^(取消|Cancel)$/).click()
  await page.getByTestId('ticket-map').click()
  await expect(page.getByTestId('destination-error')).toHaveText('暂无精确位置')
  await expect(page.getByTestId('destination-page')).toContainText(enrollment.location.address)
  await expect(page.locator('iframe')).toHaveCount(0)
})
