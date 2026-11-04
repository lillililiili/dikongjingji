import { expect, test } from '@playwright/test'

// Runs against a production H5 build, where unfinished channels must stay hidden.
test.skip(
  !process.env.E2E_PUBLIC_BUILD,
  'Set E2E_PUBLIC_BUILD=1 and serve a production H5 build to run these cases.',
)

test.describe('公开构建', () => {
  test('PUB-001 只露出已闭环的频道和入口', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-page')).toBeVisible()

    await expect(page.locator('.home-header__channel')).toHaveText([
      '推荐',
      '专业服务',
    ])
    await expect(page.getByTestId('home-scan')).toHaveCount(0)
    await expect(page.getByTestId('home-card-experience')).toHaveCount(0)
    await expect(page.locator('.home-entries__card')).toHaveCount(1)
    await expect(page.locator('.home-entries__king-entry')).toHaveCount(1)
    await expect(page.locator('.home-entries__dots')).toHaveCount(0)
    await expect(page.getByTestId('home-search-input')).toContainText(
      '搜索巡检服务、场景或关键词',
    )
  })

  test('PUB-002 底栏五项完整，专业服务只有巡检', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(
      page.locator('.root-tab-bar .root-tab-bar__label'),
    ).toHaveText(['首页', '发现', '消息', '购物车', '我的'])

    await page.getByTestId('home-king-service').click()
    await expect(page.getByTestId('inspection-portal')).toBeVisible()
    await expect(page.locator('.service-channel__type')).toHaveText(['巡检'])
  })

  test('PUB-003 旧商城链接回到首页，不停在没有出口的页面', async ({ page }) => {
    await page.goto('/#/pages/mall/index')

    await expect(page.getByTestId('home-page')).toBeVisible()
    await expect(page.getByTestId('home-channel-recommend')).toHaveClass(
      /home-header__channel--active/,
    )
  })

  test('PUB-004 发现页未开通时底栏仍在', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await page.getByTestId('root-tab-discover').click()

    await expect(page.getByText('发现频道尚未开通')).toBeVisible()
    await expect(page.getByTestId('root-tab-discover')).toHaveClass(
      /root-tab-bar__item--active/,
    )
    await expect(page.locator('uni-tabbar .uni-tabbar')).toBeHidden()
  })

  test('PUB-005 推荐频道搜索进入巡检目录', async ({ page }) => {
    await page.goto('/#/pages/home/index')
    await expect(page.getByTestId('home-city')).toContainText('东营')

    const input = page.getByTestId('home-search-input').locator('input')
    await input.fill('电力')
    await page.getByTestId('home-search-submit').click()

    await expect(page.getByTestId('inspection-catalog')).toBeVisible()
    await expect(
      page.getByTestId('catalog-search-input').locator('input'),
    ).toHaveValue('电力')
  })
})
