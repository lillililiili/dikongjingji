import { expect, test, type Page } from '@playwright/test'
import { devPassword, psql } from './support/trade-fixtures'
import { testAdmin } from './support/test-stack'

test.use({ trace: 'off', viewport: { width: 1366, height: 860 } })

const support = ['/lookup/orders', '/lookup/users', '/support/conversations']
const operator = [...support, '/activities', '/catalog/products', '/cities', '/cities/map-places', '/content',
  '/content/agreements', '/courses', '/home-carousel', '/imports', '/inspection-services', '/live',
  '/merchant-accounts', '/merchant-profiles', '/organizations', '/pro-services', '/provider-profiles',
  '/reconciliation', '/system-notices', '/venues']
const merchant = ['activity-results', 'aftersales', 'conversations', 'demands', 'inspection', 'live-rooms',
  'mall-inquiries', 'mall-orders', 'merchant-account', 'merchant-profile', 'organization', 'products',
  'provider-profile', 'rental-inquiries', 'rental-orders', 'service-orders'].map(name => '/workbench/' + name)

async function signIn(page: Page, login: string) {
  await page.goto(testAdmin + '/login')
  await page.getByLabel('用户名').fill(login)
  const password = login === 'dev-operator' ? process.env.DEV_OPERATOR_PASSWORD : devPassword()
  expect(password, '需要隔离库测试账号').toBeTruthy()
  await page.locator('input[autocomplete=current-password]').fill(password!)
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  await expect(page.locator('.home .card').first()).toBeVisible()
}

async function verifyPage(page: Page, path: string) {
  await expect(page).toHaveURL(url => url.pathname === path)
  await expect(page.locator('.layout__main h1').first()).toBeVisible()
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.layout__main .el-alert--error:visible, .el-message--error:visible')).toHaveCount(0)
  await expect(page.locator('.layout__main')).not.toContainText('页面不存在')
}

for (const [login, paths] of [
  ['dev-operator', operator], ['dev-support', [...support, '/feedback']], ['dev-merchant', merchant],
] as const) {
  test(`后台菜单逐页操作 · ${login}`, async ({ page }) => {
    test.setTimeout(180_000)
    const errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    page.on('response', async response => {
      const url = new URL(response.url())
      if (!url.pathname.startsWith('/api/') || response.status() < 400) return
      if (url.pathname === '/api/v1/platform/trade/reconciliation' && response.status() === 409 &&
          (await response.json()).code === 'TRADE_RECONCILIATION_NOT_READY') return
      errors.push(response.status() + ' ' + url.pathname)
    })
    await signIn(page, login)
    const links = page.locator('.home .card')
    expect((await links.evaluateAll(nodes => nodes.map(n => n.getAttribute('href')))).sort()).toEqual([...paths].sort())
    for (const path of paths) {
      await test.step(path, async () => {
        await page.locator(`.home .card[href="${path}"]`).click()
        await verifyPage(page, path)
        await page.locator('.layout__brand').click()
        await expect(links.first()).toBeVisible()
      })
    }
    expect(errors).toEqual([])
  })
}

test('运营端新建表单、已有资源详情与页签', async ({ page }) => {
  test.setTimeout(150_000)
  await signIn(page, 'dev-operator')
  const forms = ['/activities/new', '/venues/new', '/pro-services/new', '/inspection-services/new',
    '/content/articles/new', '/content/videos/new', '/content/topics/new', '/venues/online-experiences']
  const details = [
    ['/activities', 'select id from activity'], ['/venues', 'select id from venue'],
    ['/pro-services', 'select id from pro_service'], ['/inspection-services', 'select id from inspection_service'],
    ['/courses', 'select id from course'], ['/catalog/products', 'select id from mall_product'],
    ['/content/articles', "select id from content_article where kind <> 'VIDEO'"],
    ['/content/videos', "select id from content_article where kind = 'VIDEO'"],
    ['/content/topics', 'select id from content_topic'], ['/organizations', 'select id from identity_organization'],
    ['/merchant-profiles', 'select id from mall_merchant_profile_submission'],
    ['/lookup/users', "select id from identity_user where login_name='dev-customer'"],
  ]
  for (const [base, sql] of details) {
    const id = psql(sql + ' order by 1 limit 1')
    expect(id, base).toBeTruthy()
    forms.push(base + '/' + id)
  }
  for (const path of forms) {
    await test.step(path, async () => {
      await page.goto(testAdmin + path)
      await verifyPage(page, path)
      const tabs = page.locator('.layout__main').getByRole('tab')
      for (const tab of await tabs.all()) {
        await tab.click()
        await expect(tab).toHaveAttribute('aria-selected', 'true')
        await page.waitForLoadState('networkidle')
        await expect(page.locator('.layout__main .el-alert--error:visible')).toHaveCount(0)
      }
    })
  }
})

test('后台快捷导航、刷新、页签和会话恢复', async ({ page }) => {
  await signIn(page, 'dev-support')
  for (const [path, title] of [['/activities', '没有权限'], ['/missing-audit-route', '页面不存在']]) {
    await page.goto(testAdmin + path)
    await expect(page.getByText(title, {exact:true}).last()).toBeVisible()
    await page.getByRole('button', {name:'回首页'}).click()
    await expect(page).toHaveURL(url => url.pathname === '/')
  }
  await page.keyboard.press('Control+k')
  const search = page.getByRole('textbox', { name: '搜索菜单' })
  await search.fill('用户查询')
  await search.press('Enter')
  await verifyPage(page, '/lookup/users')
  for (const width of [1024, 1280]) {
    await page.setViewportSize({width, height:800})
    const excess = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(excess, width + ' 像素宽的后台不应水平溢出').toBeLessThanOrEqual(1)
    await expect(page.getByTestId('user-lookup-search')).toBeVisible()
  }
  await page.getByTitle('刷新当前页').click()
  await expect(page.locator('.el-table__row').first()).toBeVisible()
  await page.getByTitle('折叠菜单').click()
  await expect(page.getByTitle('展开菜单')).toBeVisible()
  await page.getByTitle('布局与主题设置').click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await page.route('**/api/v1/auth/me', route => route.fulfill({status:503, body:'{}'}))
  await page.reload()
  await expect(page.getByText('暂时无法读取账号资料')).toBeVisible()
  await page.unroute('**/api/v1/auth/me')
  await page.getByRole('button', {name:'重新加载', exact:true}).click()
  await verifyPage(page, '/lookup/users')
  await page.getByRole('button', {name:'关闭 用户查询', exact:true}).click()
  await expect(page).toHaveURL(url => url.pathname === '/')
})

test('地图地点启用后可在巡检预约选点搜索，停用后搜索结果消失且留有记录', async ({ page, context }) => {
  test.setTimeout(75_000)
  await signIn(page, 'dev-operator')
  await page.goto(testAdmin + '/cities/map-places')
  const name = '选点验收' + Date.now()
  await page.getByRole('button', {name:'新增地点'}).click()
  const editor = page.getByRole('dialog', {name:'新增地图地点', exact:true})
  await editor.getByRole('combobox').fill('东营')
  await page.getByRole('option', {name:/东营/}).first().click()
  await editor.getByLabel('名称', {exact:true}).fill(name)
  await editor.getByLabel('详细地址').fill('东营市验收场地')
  await editor.getByLabel('WGS84 经度').fill('118.67')
  await editor.getByLabel('WGS84 纬度').fill('37.43')
  await editor.locator('.el-switch__core').click()
  await expect(editor.getByRole('switch')).toHaveAttribute('aria-checked', 'true')
  await editor.getByRole('button', {name:'保存', exact:true}).click()
  await page.locator('.el-message-box').getByRole('button',{name:'确认',exact:true}).click()
  await expect(editor).toBeHidden()
  await page.getByPlaceholder('名称或地址').fill(name)
  await page.getByRole('button',{name:'查询'}).click()
  const row = page.getByRole('row').filter({hasText:name})
  await expect(row).toContainText('运营录入')
  const app = await context.newPage()
  await app.goto('/#/pages/place/map-pick?purpose=INSPECTION&requestId=auditplace&cityCode=370500&cityName=东营')
  const search = app.getByTestId('map-pick-search').locator('input')
  await search.fill(name)
  await expect(app.locator('.map-pick [data-testid^="map-pick-place-"]').filter({hasText:name})).toContainText('东营市验收场地')
  await row.getByRole('button',{name:'停用'}).click()
  await page.locator('.el-message-box').getByRole('button',{name:'确认',exact:true}).click()
  await expect(row.getByRole('button',{name:'启用'})).toBeVisible()
  await app.reload()
  await search.fill(name)
  await expect(app.getByTestId('map-pick-none')).toBeVisible()
  await row.getByRole('button',{name:'改动记录'}).click()
  await expect(page.locator('.el-drawer .el-timeline-item')).toHaveCount(2)
})
