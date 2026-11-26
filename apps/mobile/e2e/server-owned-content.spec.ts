import { expect, test } from '@playwright/test'

test('旧商城链接进入本城的真实商品列表', async ({ page }) => {
  const products = page.waitForResponse(r =>
    new URL(r.url()).pathname === '/api/v1/mall/products' && r.request().method() === 'GET')
  await page.goto('/#/pages/mall/index')
  await expect(page.getByTestId('home-channel-mall')).toHaveClass(/--active/)
  const response = await products
  expect(response.ok()).toBe(true)
  const { items } = await response.json()
  expect(items.length).toBeGreaterThan(0)
  await expect(page.getByTestId(`mall-product-${items[0].productId}`)).toContainText(items[0].name)
})

test('首页按城市发布的轮播显示，发布列表清空或不可用时保留频道入口', async ({ page }) => {
  let status = 200
  const items = [{
    id: 'published-card', tag: '城市发布', titleLines: ['本期活动', '由城市发布'],
    descriptionLines: ['测试用的动态内容', '来自响应'], actionLabel: '查看活动',
    imageUrl: 'https://media.example.test/carousel.jpg', imageAlt: '活动图', tone: 'SUN', target: 'ACTIVITY',
  }]
  await page.route('https://media.example.test/**', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/home/carousel?**', route => route.fulfill({ status, json: { items } }))
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-carousel')).toContainText('由城市发布')
  expect(await page.getByTestId('home-carousel').getAttribute('data-current-slide')).toBe('published-card')
  await page.getByTestId('home-carousel-action-published-card').click()
  await expect(page.getByTestId('home-channel-activity')).toHaveClass(/--active/)
  items.length = 0
  for (status of [200, 503]) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/home/carousel')
    await page.reload()
    expect((await response).status()).toBe(status)
    await expect(page.getByTestId('home-card-service')).toBeVisible()
    await expect(page.getByTestId('home-carousel')).toHaveCount(0)
    await expect(page.getByText('无人机足球赛')).toHaveCount(0)
  }
})

test('协议随已选文档读取，迟到的旧正文、未发布和错误分别处理', async ({ page }) => {
  let release!: () => void
  const delayed = new Promise<void>(resolve => { release = resolve })
  let retry = false
  await page.route('**/api/v1/platform/agreements/*', async route => {
    const doc = new URL(route.request().url()).pathname.split('/').pop()
    if (doc === 'PRIVACY_POLICY') await delayed
    if (doc === 'CHILD_PRIVACY_RULES') return route.fulfill({ status: 404, json: {} })
    if (doc === 'TRADE_AGREEMENT' && !retry) return route.fulfill({ status: 503, json: {} })
    return route.fulfill({ json: {
      title: '发布文档 ' + doc, version: '2.3', publishedOn: '2026-11-25',
      sections: [{ title: '发布章节', body: '服务端提供的正文 ' + doc }],
    } })
  })
  const initial = page.waitForRequest(r => r.url().endsWith('/platform/agreements/PRIVACY_POLICY'))
  await page.goto('/#/pages/mine/agreement?doc=PRIVACY_POLICY')
  await initial
  async function pick(doc: string) {
    await page.getByTestId('agreement-all').click()
    await page.getByTestId('agreement-pick-' + doc).click()
  }
  await pick('user_agreement')
  await expect(page.getByText('服务端提供的正文 USER_AGREEMENT')).toBeVisible()
  const old = page.waitForResponse(r => r.url().endsWith('/platform/agreements/PRIVACY_POLICY'))
  release()
  await old
  await expect(page.getByTestId('agreement-title')).toHaveText('发布文档 USER_AGREEMENT')
  await pick('child_privacy_rules')
  await expect(page.getByTestId('agreement-unpublished')).toBeVisible()
  await expect(page.getByText('2026-11-25')).toHaveCount(0)
  await pick('trade_agreement')
  await expect(page.getByTestId('agreement-retry')).toBeVisible()
  retry = true
  await page.getByTestId('agreement-retry').click()
  await expect(page.getByText('服务端提供的正文 TRADE_AGREEMENT')).toBeVisible()
})
