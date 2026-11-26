import { expect, test } from '@playwright/test'

test.skip(process.env.E2E_EXPERIENCE_MALL_PLAY_BUILD !== '1', '使用仅开启体验、商城、趣玩的生产构建')

test('PLAY-ART 生产版开放趣玩时，深链加载两款游戏的自绘图标', async ({ page }) => {
  await page.goto('/#/pages/play/index')
  await expect(page.getByTestId('play-games')).toBeVisible()
  const images = page.locator('.play-games__cover-image img')
  await expect(images).toHaveCount(2)
  await expect(images.nth(0)).toHaveAttribute('src', /\/static\/images\/play\/avatar-robot\.svg$/)
  await expect(images.nth(1)).toHaveAttribute('src', /\/static\/images\/play\/avatar-drone\.svg$/)
  await expect.poll(() => images.evaluateAll(items => items.every(item => (item as HTMLImageElement).naturalWidth > 0))).toBe(true)
})

test('选开体验时可以为到馆预约维护参与人，活动报名仍关闭', async ({ page }) => {
  await page.goto('/#/pages/venue/detail?venueId=b2000000-0000-4000-8000-000000000001&cityCode=370500')
  await expect(page.getByTestId('venue-detail')).toContainText('东营低空与机器人体验馆')
  await page.goto('/?r=participants#/pages/activity/participants?from=venue')
  await expect(page.getByTestId('login-password-submit')).toBeVisible()
  await expect(page).toHaveURL(/returnUrl=.*participants/)
  await page.goto('/?r=enroll#/pages/activity/enroll')
  await expect(page.getByTestId('activity-unavailable')).toBeVisible()
})

test('三个开关组合只显示对应首页频道，商城商品可读且不能加入购物车', async ({ page }) => {
  await page.goto('/#/pages/home/index')
  await expect(page.locator('.home-header__channel')).toHaveText(['推荐', '专业服务', '体验', '商城'])
  await page.getByTestId('home-king-games').click()
  await expect(page.getByTestId('play-game-robot-maze')).toBeVisible()
  await page.goto('/?r=product#/pages/mall/product?productId=c2000000-0000-4000-8000-000000000101&cityCode=370500')
  await expect(page.getByTestId('mall-product-name')).toBeVisible()
  await expect(page.getByTestId('mall-product-cart')).toHaveCount(0)
  await page.goto('/?r=checkout#/pages/cart/checkout')
  await expect(page.getByTestId('checkout-unavailable')).toBeVisible()
})
