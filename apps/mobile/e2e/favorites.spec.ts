import { mkdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import {
  BUYER,
  CITY,
  apiLogin,
  databaseAvailable,
  devPassword,
  psql,
  type Session,
} from './support/trade-fixtures'

// 收藏与对比（计划 08 S5-6，契约 openapi-favorite.yaml 1.0.0）：没登录点“收藏”先登录、回来就收藏好；各详情页的收藏；
// “我的 → 收藏与对比”四个页签和数量、点开、取消收藏与撤销、已下架的灰显；商城卡片勾“对比”、浮条、对比页
// （只看不同、不同项高亮）、不同类目不能一起对比。真实后端；前后删掉 dev-customer 的收藏，清掉本机的对比勾选。
const KIT = 'c2000000-0000-4000-8000-000000000101'
const ARM = 'c2000000-0000-4000-8000-000000000102'
const DRONE = 'c2000000-0000-4000-8000-000000000201'
const POWER_INSPECTION = '33333333-3333-3333-3333-333333333333'
const AERIAL_PARK = 'f2100000-0000-4000-8000-000000000001'
const CONTEST = 'a2000000-0000-4000-8000-000000000001'
const VENUE = 'b2000000-0000-4000-8000-000000000001'
const CASE_DOG = 'f3000000-0000-4000-8000-000000000001'
const VIDEO_ROBOT = 'f3200000-0000-4000-8000-000000000001'
// A product id nothing in the development data uses: stands for a product taken down after it was favorited.
const GONE_PRODUCT = 'c2000000-0000-4000-8000-0000000009f1'
const COMPARE_KEY = 'mall-compare-selection'
const visualOutput = resolve(__dirname, '../../../../../.planning/favorites/visual')
const compareOutput = resolve(__dirname, '../../../../../.planning/style-compare/favorites')
const boardDir = resolve(__dirname, '../../../../../.planning/prototype/v2-20261114')

let buyer: Session

function purgeFavorites(): void {
  psql(`DELETE FROM favorite_item WHERE account_id = (SELECT id FROM identity_user WHERE login_name = '${BUYER}');`)
}

// “TYPE:on|off” per row, sorted here so the database's collation does not matter.
function favoriteRows(): string {
  const rows = psql(`SELECT target_type || ':' || CASE WHEN removed_at IS NULL THEN 'on' ELSE 'off' END FROM favorite_item
    WHERE account_id = (SELECT id FROM identity_user WHERE login_name = '${BUYER}');`)
  return rows ? rows.split('\n').sort().join(',') : ''
}

async function signIn(page: Page, session: Session): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, session.token)
}

// The picks live in local storage; every test starts without any.
async function clearComparePicks(page: Page): Promise<void> {
  await page.addInitScript((key) => {
    if (!sessionStorage.getItem('e2e-compare-cleared')) {
      localStorage.removeItem(key)
      sessionStorage.setItem('e2e-compare-cleared', '1')
    }
  }, COMPARE_KEY)
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
}

async function tallShot(page: Page, path: string): Promise<void> {
  const viewport = page.viewportSize() as { width: number; height: number }
  await page.evaluate(() => window.scrollTo(0, 0))
  const height = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
  await page.waitForTimeout(200)
  await page.screenshot({ path })
  await page.setViewportSize(viewport)
}

async function favoriteHere(page: Page): Promise<void> {
  const button = page.getByTestId('favorite-button')
  await expect(button).toHaveAttribute('data-favorited', 'false')
  await button.click()
  await expect(button).toHaveAttribute('data-favorited', 'true')
  // The product page's is a round button on the picture without words; every one says it in its label.
  await expect(button).toHaveAttribute('aria-label', '取消收藏')
}

async function openMallChannel(page: Page): Promise<void> {
  await page.goto('/#/pages/home/index')
  await expect(page.getByTestId('home-page')).toBeVisible()
  await expect(page.getByTestId('home-city')).toContainText('东营')
  await page.getByTestId('home-channel-mall').click()
  await expect(page.getByTestId('mall-channel')).toBeVisible()
  await expect(page.locator('.mall-card').first()).toBeVisible()
}

function compareCheckOf(page: Page, productId: string) {
  return page.getByTestId(`mall-product-${productId}`).getByTestId('compare-check')
}

test.describe('收藏与对比', () => {
  test.beforeAll(async ({ request }) => {
    test.skip(!databaseAvailable(), '开发数据库容器没有运行')
    const session = await apiLogin(request, BUYER)
    test.skip(!session, '本地后端没有开发账号（不是 dev 配置）')
    buyer = session as Session
    purgeFavorites()
  })

  test.afterAll(() => {
    if (databaseAvailable()) purgeFavorites()
  })

  test.beforeEach(async ({ page }) => {
    purgeFavorites()
    await clearComparePicks(page)
  })

  test('FAV-001 没登录点活动详情的“收藏”：先登录，回到同一个活动并已收藏', async ({ page }) => {
    await page.goto(`/#/pages/activity/detail?activityId=${CONTEST}&cityCode=${CITY}`)
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()
    const button = page.getByTestId('favorite-button')
    await expect(button).toHaveText(/收藏/)
    await button.click()

    await expect(page).toHaveURL(/pages\/service\/inspection\/login\?returnUrl=/)
    expect(favoriteRows()).toBe('')
    await page.locator('.inspection-login__input input').nth(0).fill(BUYER)
    await page.locator('.inspection-login__input input').nth(1).fill(devPassword())
    await page.getByTestId('login-agreement').click()
    await page.locator('.inspection-login__submit').click()

    await expect(page).toHaveURL(new RegExp(`pages/activity/detail\\?activityId=${CONTEST}&cityCode=${CITY}`))
    await expect(page.getByTestId('favorite-button')).toHaveAttribute('data-favorited', 'true')
    expect(favoriteRows()).toBe('ACTIVITY:on')
  })

  test('FAV-002 各详情页收藏；“我的 → 收藏与对比”四个页签、数量、点开详情；取消收藏后撤销', async ({ page }) => {
    test.setTimeout(90_000)
    await signIn(page, buyer)

    await page.goto(`/#/pages/mall/product?productId=${KIT}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toHaveText('编程机器人探索套装')
    await favoriteHere(page)
    await page.goto(`/#/pages/mall/product?productId=${ARM}&cityCode=${CITY}`)
    await expect(page.getByTestId('mall-product-name')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/service/inspection/detail?serviceId=${POWER_INSPECTION}&cityCode=${CITY}`)
    await expect(page.getByTestId('inspection-detail')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/proservice/detail?serviceId=${AERIAL_PARK}&cityCode=${CITY}`)
    await expect(page.getByTestId('pro-detail-title')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/activity/detail?activityId=${CONTEST}&cityCode=${CITY}`)
    await expect(page.getByTestId('activity-detail-title')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/venue/detail?venueId=${VENUE}&cityCode=${CITY}`)
    await expect(page.getByTestId('venue-detail-name')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/content/article?articleId=${CASE_DOG}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-article-title')).toBeVisible()
    await favoriteHere(page)
    await page.goto(`/#/pages/content/video?videoId=${VIDEO_ROBOT}&cityCode=${CITY}`)
    await expect(page.getByTestId('content-video-title')).toBeVisible()
    await favoriteHere(page)
    // Again on the same page: the state is read back, and a second tap unfavorites.
    await page.reload()
    await expect(page.getByTestId('favorite-button')).toHaveAttribute('data-favorited', 'true')
    await page.getByTestId('favorite-button').click()
    await expect(page.getByTestId('favorite-button')).toHaveAttribute('data-favorited', 'false')
    await page.getByTestId('favorite-button').click()
    await expect(page.getByTestId('favorite-button')).toHaveAttribute('data-favorited', 'true')

    await page.goto('/#/pages/mine/index')
    await page.getByTestId('mine-link-favorites').click()
    await expect(page).toHaveURL(/pages\/mine\/favorites/)
    await expect(page.getByTestId('favorites-tab-goods')).toContainText('商品 2')
    await expect(page.getByTestId('favorites-tab-service')).toContainText('服务 2')
    await expect(page.getByTestId('favorites-tab-event')).toContainText('活动 2')
    await expect(page.getByTestId('favorites-tab-content')).toContainText('内容 2')
    // Newest first.
    const goods = page.locator('[data-testid^="favorite-c2000000"]')
    await expect(goods).toHaveCount(2)
    await expect(goods.nth(0)).toContainText('桌面六轴机械臂')
    await expect(goods.nth(1)).toContainText('编程机器人探索套装')
    await expect(goods.nth(1)).toContainText('家在售')
    await expect(page.getByText('勾选 2–3 件商品即可对比')).toBeVisible()

    await page.getByTestId('favorites-tab-event').click()
    await expect(page.getByTestId(`favorite-${VENUE}`)).toContainText('场馆')
    await page.getByTestId(`favorite-${CONTEST}`).locator('.favorite-card__main').click()
    await expect(page).toHaveURL(new RegExp(`pages/activity/detail\\?activityId=${CONTEST}`))
    await page.goBack()

    await page.getByTestId('favorites-tab-content').click()
    await expect(page.getByTestId(`favorite-${VIDEO_ROBOT}`)).toContainText('视频')
    await page.getByTestId(`favorite-${CASE_DOG}`).getByTestId('favorite-remove').click()
    await expect(page.getByTestId(`favorite-${CASE_DOG}`)).toHaveCount(0)
    await expect(page.getByTestId('favorites-tab-content')).toContainText('内容 1')
    await expect(page.getByTestId('favorites-undo')).toContainText('已取消收藏')
    await page.getByTestId('favorites-undo-action').click()
    await expect(page.getByTestId(`favorite-${CASE_DOG}`)).toBeVisible()
    await expect(page.getByTestId('favorites-tab-content')).toContainText('内容 2')
    await expect(page.getByTestId('favorites-undo')).toHaveCount(0)
    expect(favoriteRows()).toBe('ACTIVITY:on,CONTENT:on,CONTENT:on,INSPECTION_SERVICE:on,PRODUCT:on,PRODUCT:on,PRO_SERVICE:on,VENUE:on')
  })

  test('FAV-003 已下架的收藏灰显“已下架”，不能点开，只能取消收藏', async ({ page }) => {
    await signIn(page, buyer)
    psql(`INSERT INTO favorite_item (account_id, target_type, target_id, title, favorited_at)
      SELECT id, 'PRODUCT', '${GONE_PRODUCT}', '入门教学无人机', now() - interval '1 day'
      FROM identity_user WHERE login_name = '${BUYER}';`)

    await page.goto('/#/pages/mine/favorites')
    const gone = page.getByTestId(`favorite-${GONE_PRODUCT}`)
    await expect(gone).toHaveAttribute('data-available', 'false')
    await expect(gone).toContainText('入门教学无人机')
    await expect(gone).toContainText('已下架')
    await expect(gone).toContainText('不能查看详情或购买')
    await expect(gone.getByTestId('compare-check')).toHaveCount(0)
    await gone.locator('.favorite-card__main').click()
    await expect(page).toHaveURL(/pages\/mine\/favorites/)
    await gone.getByTestId('favorite-remove').click()
    await expect(page.getByTestId('favorites-empty')).toContainText('还没有收藏')
    // 撤销 brings back even a product that is gone.
    await page.getByTestId('favorites-undo-action').click()
    await expect(page.getByTestId(`favorite-${GONE_PRODUCT}`)).toContainText('已下架')
  })

  test('FAV-004 商城卡片勾“对比”：浮条、最多 3 件、不同类目不能一起；对比页逐行对比、只看不同', async ({ page }) => {
    await openMallChannel(page)
    await page.getByTestId('mall-category-robot').click()
    await expect(page.getByTestId(`mall-product-${KIT}`)).toBeVisible()
    await expect(page.getByTestId('mall-compare-bar')).toHaveCount(0)
    await compareCheckOf(page, KIT).click()
    // One pick is not enough for the pill; the card stays where it is.
    await expect(page.getByTestId('mall-compare-bar')).toHaveCount(0)
    await expect(page).toHaveURL(/pages\/home\/index/)
    await compareCheckOf(page, ARM).click()
    await expect(page.getByTestId('mall-compare-bar')).toContainText('已选 2 件对比')
    await expect(page.getByTestId('mall-compare-bar')).toContainText('最多 3 件')
    await expect(page.getByTestId(`mall-product-${KIT}`)).toHaveClass(/mall-card--picked/)

    await page.getByTestId('mall-category-drone').click()
    await expect(page.getByTestId(`mall-product-${DRONE}`)).toBeVisible()
    await expect(compareCheckOf(page, DRONE)).toHaveAttribute('aria-disabled', 'true')
    await compareCheckOf(page, DRONE).click()
    await expect(page.getByText('只能对比同一类目的商品，请先取消已选的')).toBeVisible()
    await expect(compareCheckOf(page, DRONE)).toHaveAttribute('data-checked', 'false')

    await page.getByTestId('compare-bar-start').click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/compare\\?ids=${KIT},${ARM}&cityCode=${CITY}`))
    await expect(page.getByTestId('mall-compare-count')).toHaveText('2 / 3')
    await expect(page.getByTestId(`mall-compare-column-${KIT}`)).toContainText('编程机器人探索套装')
    await expect(page.getByTestId(`mall-compare-column-${ARM}`)).toContainText('桌面六轴机械臂')
    await expect(page.getByTestId('mall-compare-row-sellers')).toContainText('家（')
    await expect(page.getByTestId('mall-compare-row-invoice')).toHaveAttribute('data-differs', 'false')
    const rows = page.locator('[data-testid^="mall-compare-row-"]')
    const all = await rows.count()
    const differing = await page.locator('[data-testid^="mall-compare-row-"][data-differs="true"]').count()
    await page.getByTestId('mall-compare-only-different').click()
    await expect(rows).toHaveCount(differing)
    expect(differing).toBeLessThan(all)
    await page.getByTestId(`mall-compare-open-${ARM}`).click()
    await expect(page).toHaveURL(new RegExp(`pages/mall/product\\?productId=${ARM}`))
  })

  test('FAV-005 收藏页、对比页和带浮条的商城频道：320/375/430 不横向溢出，390 宽与画板并排截图', async ({ page }) => {
    test.setTimeout(120_000)
    await signIn(page, buyer)
    for (const [type, id, title, at] of [
      ['PRODUCT', KIT, '编程机器人探索套装', '3 minutes'],
      ['PRODUCT', ARM, '桌面六轴机械臂 教学版', '2 minutes'],
      ['PRODUCT', GONE_PRODUCT, '入门教学无人机', '1 minute'],
    ] as const) {
      psql(`INSERT INTO favorite_item (account_id, target_type, target_id, title, favorited_at)
        SELECT id, '${type}', '${id}', '${title}', now() - interval '${at}' FROM identity_user WHERE login_name = '${BUYER}';`)
    }
    await openMallChannel(page)
    await page.getByTestId('mall-category-robot').click()
    await compareCheckOf(page, KIT).click()
    await compareCheckOf(page, ARM).click()
    await expect(page.getByTestId('mall-compare-bar')).toBeVisible()

    mkdirSync(visualOutput, { recursive: true })
    mkdirSync(compareOutput, { recursive: true })
    let loads = 0
    for (const width of [375, 320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const at = (hash: string) => `/?w=${width}&n=${++loads}${hash}`
      const shot = (name: string) => width === 390
        ? resolve(compareOutput, `${name}-app.png`)
        : resolve(visualOutput, `${name}-${width}.png`)

      await page.goto(at('#/pages/mine/favorites'))
      await expect(page.getByTestId(`favorite-${GONE_PRODUCT}`)).toContainText('已下架')
      await expect(page.getByTestId('compare-bar')).toContainText('已选 2 件对比')
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('Favorites') })

      await page.goto(at(`#/pages/mall/compare?ids=${KIT},${ARM}&cityCode=${CITY}`))
      await expect(page.getByTestId(`mall-compare-column-${ARM}`)).toBeVisible()
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('MallCompare'))

      await page.goto(at('#/pages/home/index'))
      await page.getByTestId('home-channel-mall').click()
      await page.getByTestId('mall-category-robot').click()
      await expect(page.getByTestId('mall-compare-bar')).toBeVisible()
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('Mall2') })

      await page.goto(at(`#/pages/activity/detail?activityId=${CONTEST}&cityCode=${CITY}`))
      await expect(page.getByTestId('favorite-button')).toBeVisible()
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('ActDetail') })

      await page.goto(at(`#/pages/content/video?videoId=${VIDEO_ROBOT}&cityCode=${CITY}`))
      await expect(page.getByTestId('favorite-button')).toBeVisible()
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('ContentVideo') })

      await page.goto(at(`#/pages/mall/product?productId=${KIT}&cityCode=${CITY}`))
      await expect(page.getByTestId('favorite-button')).toBeVisible()
      await page.waitForTimeout(300)
      await assertNoHorizontalOverflow(page)
      await page.screenshot({ path: shot('PdFull') })

      await page.goto(at('#/pages/mine/index'))
      await expect(page.getByTestId('mine-link-favorites')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await tallShot(page, shot('Mine'))
    }

    // The boards at 390 next to the App at 390.
    for (const board of ['Favorites', 'MallCompare', 'Mall2', 'ActDetail', 'ContentVideo', 'PdFull', 'Mine']) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`file://${resolve(boardDir, `${board}.html`)}`)
      const frame = page.locator('#root > div').first()
      await expect(frame).toBeVisible()
      const height = Math.ceil((await frame.boundingBox())?.height ?? 844) + 60
      await page.setViewportSize({ width: 390, height })
      await page.waitForTimeout(300)
      await frame.screenshot({ path: resolve(compareOutput, `${board}-board.png`) })

      const image = (name: string) => `data:image/png;base64,${readFileSync(resolve(compareOutput, name)).toString('base64')}`
      await page.setViewportSize({ width: 820, height: 900 })
      await page.setContent(`
        <body style="margin:0;background:#dfe5ee;font:13px sans-serif">
          <div style="display:flex;gap:20px;padding:10px;align-items:flex-start">
            <figure style="margin:0;width:390px"><figcaption>画板 ${board}</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-board.png`)}"></figure>
            <figure style="margin:0;width:390px"><figcaption>App 390 宽</figcaption>
              <img style="width:390px;display:block" src="${image(`${board}-app.png`)}"></figure>
          </div>
        </body>`)
      await page.waitForTimeout(200)
      await page.screenshot({ path: resolve(compareOutput, `${board}-compare.png`), fullPage: true })
    }
  })
})
